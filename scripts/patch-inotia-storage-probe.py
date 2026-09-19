#!/usr/bin/env python3
"""Run #28: correct Inotia KTF scenario-mode storage probe semantics.

The only WIPI-C call after selecting Scenario Mode is the KTF database-table
slot currently named MC_dbListDataBase(ptr, 0). Returning 0 (#27) and 6 (#28)
both take the game's low-storage branch. That proves the zero-length form is
not a database-count query for this title; it is consumed as available
persistent-storage capacity. Report a conservative 1 MiB, well above the
small save DB requirement, while leaving the normal nonzero-length list form
untouched.
"""
from pathlib import Path
import os

cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))
old = '''    if buf_len == 0 {\n        tracing::debug!("MC_dbListDataBase zero-length capacity probe -> 6");\n        return Ok(6);\n    }'''
new = '''    if buf_len == 0 {\n        const INOTIA_KTF_AVAILABLE_STORAGE: i32 = 1024 * 1024;\n        tracing::debug!("MC_dbListDataBase zero-length storage-capacity probe -> {INOTIA_KTF_AVAILABLE_STORAGE}");\n        return Ok(INOTIA_KTF_AVAILABLE_STORAGE);\n    }'''
patched = []
for root in roots:
    for path in root.glob("**/wie_wipi_c/src/api/database.rs"):
        text = path.read_text()
        if new in text:
            continue
        if old in text:
            path.write_text(text.replace(old, new, 1))
            patched.append(path)
if not patched:
    raise SystemExit("Could not patch Inotia KTF storage-capacity probe")
for path in patched:
    print(f"storage probe patched: {path}")
