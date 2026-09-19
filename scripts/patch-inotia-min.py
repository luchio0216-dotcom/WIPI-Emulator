#!/usr/bin/env python3
"""Patch the pinned WIE checkout for Inotia 1's retired KTF receipt gate.

This keeps the embedded subscriber identity workaround and also aligns one
storage semantic with W-Feature: KTF table slot 6 is a name-keyed file/database
remove when the first argument is not an open handle. WIE currently turns that
shape into a no-op; W-Feature actually removes the named store. Inotia 1 calls
that path on char.dat immediately before its obsolete 600 KB branch, so this
experiment makes the side effect real instead of only returning success.
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
    // Inotia 1 reaches this exact path for char.dat before its legacy receipt
    // decision, so preserve the successful return value but perform the side effect.
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

patched = []
already = []
for root in roots:
    kernel_paths = list(root.glob("**/wie_wipi_c/src/api/kernel.rs"))
    database_paths = list(root.glob("**/wie_wipi_c/src/api/database.rs"))

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
        if delete_replacement in text:
            already.append(path)
            continue
        if delete_needle in text:
            path.write_text(text.replace(delete_needle, delete_replacement, 1))
            patched.append(path)

if not patched and not already:
    raise SystemExit("Could not find the pinned WIE KTF compatibility targets to patch")

for path in patched:
    print(f"patched: {path}")
for path in already:
    print(f"already patched: {path}")
