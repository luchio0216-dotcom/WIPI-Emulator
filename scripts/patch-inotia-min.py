#!/usr/bin/env python3
"""Patch the pinned WIE checkout for Inotia 1 KTF compatibility.

The title uses several KTF-specific behaviours that are not represented by the
pinned generic WIPI implementation: subscriber fallback, name-keyed remove,
stream seek semantics, and database-list probing. Keep the patch deliberately
narrow to AID 010100D3 where identity is involved.
"""
from pathlib import Path
import os

AID = "010100D3"
FALLBACK = "01012349876"

cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))

phone_needle = '"PHONENUMBER" => "", // putting this cause some game to fail authentication'
phone_replacement = (
    f'"PHONENUMBER" => if context.system().aid() == "{AID}" '
    f'{{ "{FALLBACK}" }} else {{ "" }}, // Inotia KTF offline subscriber fallback'
)
min_needle = '"MIN" => "01000000000",'
min_replacement = (
    f'"MIN" => if context.system().aid() == "{AID}" '
    f'{{ "{FALLBACK}" }} else {{ "01000000000" }},'
)

delete_needle = '''    // Not a real handle — KTF name-keyed form. No-op preserves saves; the
    // bytes of a name string would otherwise round-trip into the standard
    // path and silently delete record 1 of the just-saved DB.
    tracing::debug!("MC_dbDeleteRecord(name-keyed @ {a0:#x}, {a1}) -> 0 (no-op)");
    Ok(0)
'''
delete_replacement = '''    // Not a real handle — KTF name-keyed form. W-Feature models this slot as
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

seek_needle = '''    // KTF reuses slot 4 as a stream-control op `(handle, offset, mode)`. The
    // shapes observed across games:
    //
    //   - `(handle, slot_offset, 0)` — multi-slot save files store each
    //     slot at a known byte offset within record 1; this seeks both
    //     cursors so the next read/write hits the right slot while
    //     preserving the bytes belonging to the other slots.
    //   - `(handle, 0, 0)` and `(handle, 0, 2)` — rewinds both cursors.
    //     mode=0 vs 2 isn't a length and isn't truncate (truncating on
    //     mode=2 on the read path destroys a prefetched buffer during a
    //     subsequent re-open and wipes the saved record). Both are treated
    //     as plain seek-and-rewind.
    if rec_id >= 0 {
        let offset = rec_id as u32;
        handle.read_cursor = offset;
        handle.write_cursor = offset;
        write_generic(context, db_id as _, handle)?;
        return Ok(0);
    }

    Ok(-22) // M_E_BADRECID
'''
seek_replacement = '''    // KTF slot 4 is a byte seek: (handle, signed_offset, origin).
    // Original handset behaviour uses 0=SET, 1=CUR, 2=END.
    let base: i64 = match mode {
        0 => 0,
        1 => handle.read_cursor as i64,
        2 => handle.buffer_len as i64,
        _ => return Ok(-22),
    };
    let position = (base + rec_id as i64).clamp(0, handle.buffer_len as i64) as u32;
    handle.read_cursor = position;
    handle.write_cursor = position;
    write_generic(context, db_id as _, handle)?;
    tracing::debug!("MC_dbSelectRecord seek -> {position}");
    Ok(position as i32)
'''

listdb_insert_needle = '''pub async fn exists_database(context: &mut dyn WIPICContext, ptr_name: WIPICWord, r#type: i32) -> Result<i32> {
'''
listdb_function = '''pub async fn list_databases_ktf(context: &mut dyn WIPICContext, buf_ptr: WIPICWord, buf_len: WIPICWord) -> Result<i32> {
    tracing::debug!("MC_dbListDataBase({buf_ptr:#x}, {buf_len})");

    // Inotia calls this as MC_dbListDataBase(non-null, 0) immediately after
    // selecting Scenario Mode. On the handset this is a legal zero-length
    // probe. Returning M_E_SHORTBUF (-18) makes the game display its
    // "not enough storage space" dialog. A zero-length probe must therefore
    // succeed without touching the caller buffer.
    if buf_len == 0 {
        tracing::debug!("MC_dbListDataBase zero-length probe -> success");
        return Ok(0);
    }

    if buf_ptr == 0 {
        return Ok(-22);
    }

    // Empty database list: one NUL is sufficient for a one-byte buffer;
    // use the conventional double-NUL terminator when space permits.
    context.write_bytes(buf_ptr, &[0u8])?;
    if buf_len >= 2 {
        context.write_bytes(buf_ptr + 1, &[0u8])?;
    }
    tracing::debug!("MC_dbListDataBase -> 0 databases");
    Ok(0)
}

'''
listdb_method_needle = 'WIPICDatabaseMethodId::ListDatabases => Some(gen_stub(12, "MC_dbListDataBase")),'
listdb_method_replacement = 'WIPICDatabaseMethodId::ListDatabases => Some(database::list_databases_ktf.into_body()),'

handset_sig_needle = '''    async fn get_system_property(jvm: &Jvm, _: &mut WieJvmContext, name: ClassInstanceRef<String>) -> JvmResult<ClassInstanceRef<String>> {
'''
handset_sig_replacement = '''    async fn get_system_property(jvm: &Jvm, context: &mut WieJvmContext, name: ClassInstanceRef<String>) -> JvmResult<ClassInstanceRef<String>> {
'''
handset_value_needle = '''        let value = match name.as_ref() {
            "VIBRATORLEVEL" => "0",
            _ => "",
        };
'''
handset_value_replacement = f'''        let inotia = context.system().aid() == "{AID}";
        let value = match name.as_ref() {{
            "VIBRATORLEVEL" => "0",
            "PHONENUMBER" | "MIN" if inotia => "{FALLBACK}",
            _ => "",
        }};
'''

patched = []
already = []
for root in roots:
    kernel_paths = list(root.glob("**/wie_wipi_c/src/api/kernel.rs"))
    database_paths = list(root.glob("**/wie_wipi_c/src/api/database.rs"))
    handset_paths = list(root.glob("**/wie_wipi_java/src/classes/org/kwis/msp/handset/handset_property.rs"))
    method_table_paths = list(root.glob("**/wie_ktf/src/runtime/wipi_c/method_table.rs"))

    for path in kernel_paths:
        text = path.read_text()
        changed = False
        if phone_replacement not in text and phone_needle in text:
            text = text.replace(phone_needle, phone_replacement, 1)
            changed = True
        if min_replacement not in text and min_needle in text:
            text = text.replace(min_needle, min_replacement, 1)
            changed = True
        old_short_min = f'"MIN" => if context.system().aid() == "{AID}" {{ "9999" }} else {{ "01000000000" }},'
        if old_short_min in text:
            text = text.replace(old_short_min, min_replacement, 1)
            changed = True
        if changed:
            path.write_text(text)
            patched.append(path)
        elif phone_replacement in text and min_replacement in text:
            already.append(path)

    for path in database_paths:
        text = path.read_text()
        changed = False
        if delete_replacement not in text and delete_needle in text:
            text = text.replace(delete_needle, delete_replacement, 1)
            changed = True
        if seek_replacement not in text and seek_needle in text:
            text = text.replace(seek_needle, seek_replacement, 1)
            changed = True
        if listdb_function not in text and listdb_insert_needle in text:
            text = text.replace(listdb_insert_needle, listdb_function + listdb_insert_needle, 1)
            changed = True
        if changed:
            path.write_text(text)
            patched.append(path)
        elif delete_replacement in text and seek_replacement in text and listdb_function in text:
            already.append(path)

    for path in method_table_paths:
        text = path.read_text()
        if listdb_method_replacement in text:
            already.append(path)
            continue
        if listdb_method_needle in text:
            path.write_text(text.replace(listdb_method_needle, listdb_method_replacement, 1))
            patched.append(path)

    for path in handset_paths:
        text = path.read_text()
        if handset_value_replacement in text:
            already.append(path)
            continue
        changed = False
        if handset_sig_needle in text:
            text = text.replace(handset_sig_needle, handset_sig_replacement, 1)
            changed = True
        if handset_value_needle in text:
            text = text.replace(handset_value_needle, handset_value_replacement, 1)
            changed = True
        if changed:
            path.write_text(text)
            patched.append(path)

if not patched and not already:
    raise SystemExit("Could not find the pinned WIE KTF compatibility targets to patch")

for path in patched:
    print(f"patched: {path}")
for path in already:
    print(f"already patched: {path}")
