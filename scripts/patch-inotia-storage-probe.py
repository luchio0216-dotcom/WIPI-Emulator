#!/usr/bin/env python3
"""Correct KTF/WIPI zero-length database-list storage-capacity probe.

Some legacy titles use MC_dbListDataBase(non-null, 0) as a free-storage query.
Inotia 1 only needed about 1 MiB, while Inotia 2 refuses to start unless more
than ~2103 KiB is reported. Return a comfortably large virtual capacity so the
emulator does not trip the handset-era storage gate.

This patch deliberately does not rewrite Android instrumentation input logic.
The real-save test source owns the user-confirmed keypad sequence:
CLR -> 4 -> 5 -> 5 for Save, then 5 -> 2 -> 5 -> 5 for Exit.
"""
from pathlib import Path
import os

cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))
old = '''    if buf_len == 0 {\n        tracing::debug!("MC_dbListDataBase zero-length capacity probe -> 6");\n        return Ok(6);\n    }'''
old_1m = '''    if buf_len == 0 {\n        const INOTIA_KTF_AVAILABLE_STORAGE: i32 = 1024 * 1024;\n        tracing::debug!("MC_dbListDataBase zero-length storage-capacity probe -> {INOTIA_KTF_AVAILABLE_STORAGE}");\n        return Ok(INOTIA_KTF_AVAILABLE_STORAGE);\n    }'''
new = '''    if buf_len == 0 {\n        const KTF_AVAILABLE_STORAGE: i32 = 64 * 1024 * 1024;\n        tracing::debug!("MC_dbListDataBase zero-length storage-capacity probe -> {KTF_AVAILABLE_STORAGE}");\n        return Ok(KTF_AVAILABLE_STORAGE);\n    }'''
patched = []
already = []
for root in roots:
    for path in root.glob("**/wie_wipi_c/src/api/database.rs"):
        text = path.read_text()
        if new in text:
            already.append(path)
            continue
        if old in text:
            path.write_text(text.replace(old, new, 1))
            patched.append(path)
            continue
        if old_1m in text:
            path.write_text(text.replace(old_1m, new, 1))
            patched.append(path)
if not patched and not already:
    raise SystemExit("Could not patch KTF/WIPI storage-capacity probe")
for path in patched:
    print(f"storage probe patched: {path}")
for path in already:
    print(f"storage probe already patched: {path}")
