#!/usr/bin/env python3
"""Correct Inotia KTF zero-length database-list storage-capacity probe.

This patch deliberately does not rewrite Android instrumentation input logic.
The real-save test source owns the user-confirmed keypad sequence:
CLR -> 4 -> 5 -> 5 for Save, then 5 -> 2 -> 5 -> 5 for Exit.
"""
from pathlib import Path
import os

cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))
old = '''    if buf_len == 0 {\n        tracing::debug!("MC_dbListDataBase zero-length capacity probe -> 6");\n        return Ok(6);\n    }'''
new = '''    if buf_len == 0 {\n        const INOTIA_KTF_AVAILABLE_STORAGE: i32 = 1024 * 1024;\n        tracing::debug!("MC_dbListDataBase zero-length storage-capacity probe -> {INOTIA_KTF_AVAILABLE_STORAGE}");\n        return Ok(INOTIA_KTF_AVAILABLE_STORAGE);\n    }'''
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
if not patched and not already:
    raise SystemExit("Could not patch Inotia KTF storage-capacity probe")
for path in patched:
    print(f"storage probe patched: {path}")
for path in already:
    print(f"storage probe already patched: {path}")
