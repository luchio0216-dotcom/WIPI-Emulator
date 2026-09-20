#!/usr/bin/env python3
"""Patch KTF stream-file overwrite semantics used by Inotia 1 saves.

Inotia can create a fresh save with the pinned WIE implementation, but when an
existing save is overwritten it follows the handset-style replace sequence:
write a temporary store, remove the old named store, then rename the temporary
store onto the final name. W-Feature models KTF slot 7 as MC_fsRename; pinned
WIE still routes slot 7 to standard record listing. The old name-keyed remove
shim also deleted record 1 but left the repository directory behind, making the
final destination still look occupied.

Keep the change narrow to Inotia (AID 010100D3) for slot 7, while making
name-keyed remove distinguish packaged resources from mutable player stores.
"""
from pathlib import Path
import os

AID = "010100D3"

cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))
if not roots:
    raise SystemExit("WIE checkout not found; run cargo fetch first")

old_delete = '''    // Not a real handle — KTF name-keyed form. W-Feature models this slot as
    // MC_fsRemove(name, area): the named per-title store is actually removed.
    let Ok(name) = String::from_utf8(read_null_terminated_string_bytes(context, a0 as u32)?) else {
        return Ok(-22);
    };
    let system = context.system();
    let pid = system.pid().to_owned();
    if system.platform().database_repository().exists(&name, &pid).await {
        let mut db = system.platform().database_repository().open(&name, &pid).await;
        db.delete(1).await;
    }
    tracing::debug!("MC_dbDeleteRecord(name-keyed {name:?}, {a1}) -> 0 (removed backing record)");
    Ok(0)
'''

new_delete = '''    // Not a real handle — KTF name-keyed form (MC_fsRemove semantics).
    // Mutable player files must remove the whole repository so a following
    // MC_fsRename(temp, final) sees a free destination. Packaged P/ resources
    // are different: WIE has no removal ledger, so keep an empty repository as
    // a tombstone to stop the packaged record from being seeded again.
    let Ok(raw_name) = String::from_utf8(read_null_terminated_string_bytes(context, a0 as u32)?) else {
        return Ok(-22);
    };
    let name = raw_name.trim_start_matches('/').to_owned();
    let packaged = read_packaged_database(context, &name).await?.is_some();
    let system = context.system();
    let pid = system.pid().to_owned();
    if system.platform().database_repository().exists(&name, &pid).await {
        if packaged {
            let mut db = system.platform().database_repository().open(&name, &pid).await;
            db.delete(1).await;
        } else {
            system.platform().database_repository().delete(&name, &pid).await;
        }
    }
    tracing::info!("MC_dbDeleteRecord(name-keyed {name:?}, {a1}) -> 0 (packaged={packaged}, whole_store={})", !packaged);
    Ok(0)
'''

rename_insert_marker = '''pub async fn delete_database(context: &mut dyn WIPICContext, ptr_name: WIPICWord, flags: i32) -> Result<i32> {
'''
rename_function = f'''/// KTF stream-files reuse slot 7 as MC_fsRename(oldName, newName, area).
/// Inotia uses this when replacing an existing save: write temp -> remove old
/// -> rename temp to save0.dat. For other KTF titles keep WIE's standard
/// ListRecord behaviour to avoid widening this compatibility shim.
pub async fn list_record_or_rename_ktf(
    context: &mut dyn WIPICContext,
    a0: i32,
    a1: WIPICWord,
    a2: WIPICWord,
) -> Result<i32> {{
    if context.system().aid() != "{AID}" {{
        return list_record(context, a0, a1, a2).await;
    }}

    let Ok(old_raw) = String::from_utf8(read_null_terminated_string_bytes(context, a0 as u32)?) else {{
        return Ok(-22);
    }};
    let Ok(new_raw) = String::from_utf8(read_null_terminated_string_bytes(context, a1)?) else {{
        return Ok(-22);
    }};
    let old_name = old_raw.trim_start_matches('/').to_owned();
    let new_name = new_raw.trim_start_matches('/').to_owned();
    tracing::info!("MC_fsRename({{old_name:?}}, {{new_name:?}}, area={{a2}})");

    if old_name.is_empty() || new_name.is_empty() {{
        return Ok(-22);
    }}
    if old_name == new_name {{
        return Ok(0);
    }}

    // The handset refuses to rename onto an occupied destination. The normal
    // Inotia overwrite sequence removes the final name immediately before
    // this call, so a still-existing destination is a real error.
    let destination_packaged = read_packaged_database(context, &new_name).await?.is_some();
    let system = context.system();
    let pid = system.pid().to_owned();
    if destination_packaged || system.platform().database_repository().exists(&new_name, &pid).await {{
        tracing::warn!("MC_fsRename destination already exists: {{new_name:?}}");
        return Ok(-22);
    }}

    // The overwrite source is the mutable temporary stream just written by
    // Inotia. Do not fall back to packaged P data here: besides being the wrong
    // KTF semantic, doing so requires a second mutable context borrow while the
    // repository is borrowed. A missing temp store is therefore a real error.
    if !system.platform().database_repository().exists(&old_name, &pid).await {{
        tracing::warn!("MC_fsRename mutable source missing: {{old_name:?}}");
        return Ok(-12);
    }}
    let db = system.platform().database_repository().open(&old_name, &pid).await;
    let Some(data) = db.get(1).await else {{
        tracing::warn!("MC_fsRename source record missing: {{old_name:?}}");
        return Ok(-12);
    }};

    let mut destination = system.platform().database_repository().open(&new_name, &pid).await;
    if !destination.set(1, &data).await {{
        system.platform().database_repository().delete(&new_name, &pid).await;
        return Ok(-22);
    }}
    system.platform().database_repository().delete(&old_name, &pid).await;

    tracing::info!("MC_fsRename {{old_name:?}} -> {{new_name:?}} complete ({{}} bytes)", data.len());
    Ok(0)
}}

'''

method_old = 'WIPICDatabaseMethodId::ListRecord => Some(database::list_record.into_body()),'
method_new = 'WIPICDatabaseMethodId::ListRecord => Some(database::list_record_or_rename_ktf.into_body()),'

patched = []
already = []
for root in roots:
    for path in root.glob("**/wie_wipi_c/src/api/database.rs"):
        text = path.read_text()
        changed = False
        if new_delete not in text:
            if old_delete not in text:
                raise SystemExit(f"Expected Inotia name-keyed delete shim not found in {path}")
            text = text.replace(old_delete, new_delete, 1)
            changed = True
        if rename_function not in text:
            if rename_insert_marker not in text:
                raise SystemExit(f"Rename insertion point not found in {path}")
            text = text.replace(rename_insert_marker, rename_function + rename_insert_marker, 1)
            changed = True
        if changed:
            path.write_text(text)
            patched.append(path)
        else:
            already.append(path)

    for path in root.glob("**/wie_ktf/src/runtime/wipi_c/method_table.rs"):
        text = path.read_text()
        if method_new in text:
            already.append(path)
        elif method_old in text:
            path.write_text(text.replace(method_old, method_new, 1))
            patched.append(path)
        else:
            raise SystemExit(f"KTF slot 7 mapping not found in {path}")

if not patched and not already:
    raise SystemExit("No WIE save-overwrite targets found")
for path in patched:
    print(f"save-overwrite patched: {path}")
for path in already:
    print(f"save-overwrite already patched: {path}")
