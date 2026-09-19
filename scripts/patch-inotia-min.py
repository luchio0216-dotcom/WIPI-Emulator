#!/usr/bin/env python3
"""Patch the pinned WIE checkout for Inotia 1's retired KTF billing gate.

The exact Inotia KTF build (AID 010100D3) treats MIN values with five or more
characters as a billable subscriber and enters its obsolete 600 KB network /
receipt path.  Real compatible emulators use a short local subscriber value for
this title.  Keep WIE's normal MIN for every other title and return 9999 only
for this AID.
"""
from pathlib import Path
import os

cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))
needle = '"MIN" => "01000000000",'
replacement = '"MIN" => if context.system().aid() == "010100D3" { "9999" } else { "01000000000" },'
patched = []
already = []

for root in roots:
    for path in root.glob("**/wie_wipi_c/src/api/kernel.rs"):
        text = path.read_text()
        if replacement in text:
            already.append(path)
            continue
        if needle not in text:
            continue
        path.write_text(text.replace(needle, replacement, 1))
        patched.append(path)

if not patched and not already:
    raise SystemExit("Could not find the pinned WIE kernel.rs MIN implementation to patch")

for path in patched:
    print(f"patched: {path}")
for path in already:
    print(f"already patched: {path}")
