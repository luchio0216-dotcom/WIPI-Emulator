#!/usr/bin/env python3
"""Keep KTF packaged database seeds out of writable storage until a guest writes them.

W-Feature's KTF runtime exposes archive GuestFiles as packaged database seeds in
memory. Merely opening one does not persist the archive bytes into the title's
writable save store. The pinned WIE currently copies every packaged seed into
DatabaseRepository on first open, making Inotia 2's ~2.1 MiB payload look like
runtime storage. Patch only that semantic difference; stream writes still
persist normally.
"""
from pathlib import Path
import os

cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))

old = '''    } else if let Some(data) = packaged {
        let mut db = system.platform().database_repository().open(&name, &pid).await;
        db.set(1, &data).await;
        data
    } else if mode == 4 {
'''
new = '''    } else if let Some(data) = packaged {
        // KTF package-owned data is an in-memory seed, not writable storage.
        // W-Feature keeps GuestFiles this way and only persists after a guest
        // write. Persisting here makes Inotia 2's ~2.1 MiB P payload look like
        // consumed runtime storage and triggers its startup space gate.
        data
    } else if mode == 4 {
'''

patched = []
already = []
for root in roots:
    for path in root.glob("**/wie_wipi_c/src/api/database.rs"):
        text = path.read_text()
        if new in text:
            already.append(path)
            continue
        if old not in text:
            continue
        path.write_text(text.replace(old, new, 1))
        patched.append(path)

if not patched and not already:
    raise SystemExit("Could not find pinned WIE packaged database seed path")
for path in patched:
    print(f"KTF packaged DB seed patched: {path}")
for path in already:
    print(f"KTF packaged DB seed already patched: {path}")
