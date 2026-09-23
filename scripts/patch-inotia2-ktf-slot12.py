#!/usr/bin/env python3
"""Map KTF table-7 slot 12 to W-Feature's MC_fsAvailable semantics for Inotia 2.

Pinned WIE 0e4be660 maps WIPICDatabaseMethodId::ListDatabases to an unimplemented
stub (MC_dbListDataBase). W-Feature 7fa98078 exposes KTF table 7 slot 12 as
MC_fsAvailable. For AID 010100D5 only, provide the reference fresh-session
1 MiB writable budget. No other title or DB operation is changed.
"""
from pathlib import Path
import os

AVAILABLE = 1024 * 1024
cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))
marker = "Inotia 2 KTF slot 12 (MC_fsAvailable)"
patched = []
already = []

for root in roots:
    for checkout in root.iterdir():
        db = checkout / "wie_wipi_c/src/api/database.rs"
        mt = checkout / "wie_ktf/src/runtime/wipi_c/method_table.rs"
        if not db.is_file() or not mt.is_file():
            continue

        db_text = db.read_text()
        mt_text = mt.read_text()
        if marker in db_text and "database::inotia2_fs_available.into_body()" in mt_text:
            already.append(checkout)
            continue

        old = 'WIPICDatabaseMethodId::ListDatabases => Some(gen_stub(12, "MC_dbListDataBase")),'
        if old not in mt_text:
            continue

        fn_text = f'''\n/// {marker}.\n/// W-Feature treats KTF table 7 slot 12 as MC_fsAvailable. This compatibility\n/// body is wired only by the Inotia2-specific CI patch.\npub async fn inotia2_fs_available(_context: &mut dyn WIPICContext) -> Result<i32> {{\n    tracing::debug!("{marker} -> {AVAILABLE}");\n    Ok({AVAILABLE})\n}}\n'''
        db.write_text(db_text + fn_text)
        mt.write_text(mt_text.replace(
            old,
            'WIPICDatabaseMethodId::ListDatabases => Some(database::inotia2_fs_available.into_body()),',
            1,
        ))
        patched.append(checkout)

if not patched and not already:
    raise SystemExit("Could not find pinned WIE KTF slot-12 stub for Inotia 2 mapping")
for path in patched:
    print(f"Inotia2 KTF slot12 patched: {path}")
for path in already:
    print(f"Inotia2 KTF slot12 already patched: {path}")
