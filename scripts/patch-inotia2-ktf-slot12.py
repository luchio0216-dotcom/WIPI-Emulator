#!/usr/bin/env python3
"""Map KTF table-7 slot 12 to the semantics W-Feature actually exposes for Inotia 2.

W-Feature revision 7fa98078 implements KTF WIPI-C table 7 as its filesystem
interface and slot 12 as MC_fsAvailable, returning a 1 MiB per-title writable
budget minus runtime growth.  The pinned WIE labels the same KTF slot as
MC_dbListDataBase.  Inotia 2 reaches this slot immediately before its 2103KB
startup gate, so for AID 010100D5 return the reference implementation's fresh
session value.  This is deliberately D5-only; no other database semantics are
changed.
"""
from pathlib import Path
import os

AID = "010100D5"
AVAILABLE = 1024 * 1024

cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))
needle = "pub async fn list_databases(context: &mut dyn WIPICContext"
marker = "Inotia 2 KTF slot 12 (MC_fsAvailable)"
patched = []
already = []

for root in roots:
    for path in root.glob("**/wie_wipi_c/src/api/database.rs"):
        text = path.read_text()
        if marker in text:
            already.append(path)
            continue
        start = text.find(needle)
        if start < 0:
            continue
        brace = text.find("{", start)
        if brace < 0:
            continue
        injection = f'''{{\n    // {marker}: W-Feature KTF table 7 slot 12 is\n    // MC_fsAvailable, not a database enumeration.  Packaged P/ bytes do not\n    // consume this writable budget, so a fresh session reports exactly 1 MiB.\n    if context.system().aid() == "{AID}" {{\n        tracing::debug!("{marker} -> {AVAILABLE}");\n        return Ok({AVAILABLE});\n    }}'''
        text = text[:brace] + injection + text[brace + 1:]
        path.write_text(text)
        patched.append(path)

if not patched and not already:
    raise SystemExit("Could not find pinned WIE list_databases for Inotia 2 slot-12 mapping")
for path in patched:
    print(f"Inotia2 KTF slot12 patched: {path}")
for path in already:
    print(f"Inotia2 KTF slot12 already patched: {path}")
