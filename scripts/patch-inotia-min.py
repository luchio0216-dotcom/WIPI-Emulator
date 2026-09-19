#!/usr/bin/env python3
"""Patch the pinned WIE checkout for Inotia 1's retired KTF receipt gate.

W-Feature documents this title as the KTF subscriber-fallback case and also
models KTF table slot 4 as a real byte seek. Inotia probes char.dat with
SEEK_SET followed by SEEK_END before deciding whether the packaged data is
valid. Pinned WIE currently rewinds for both calls and returns 0, so the game
sees a zero-length file, removes char.dat, and takes the obsolete 600 KB
network path. Align the seek semantics with W-Feature while keeping the
subscriber identity and name-keyed remove compatibility from earlier tests.
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
    // W-Feature and original handset behaviour use 0=SET, 1=CUR, 2=END.
    // Inotia specifically does seek(0, SET) then seek(0, END) and compares
    // the returned END position with the expected packaged data length.
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
        if changed:
            path.write_text(text)
            patched.append(path)
        elif delete_replacement in text and seek_replacement in text:
            already.append(path)

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
