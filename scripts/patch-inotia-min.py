#!/usr/bin/env python3
"""Patch the pinned WIE checkout for Inotia 1's retired KTF receipt gate.

W-Feature's public authentication research identifies this exact Inotia archive
as a KTF subscriber-fallback case. Its native accessor reads PHONENUMBER; if the
input is <=4 chars it substitutes an embedded 11-digit fallback. The executable
used by our test contains that fallback as 01012349876 immediately beside the
PHONENUMBER property string. Supplying the embedded full identity directly is
the cleaner compatibility behavior and keeps the C property readers consistent.

Only AID 010100D3 is adapted. All other games retain WIE's defaults.
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

patched = []
already = []
for root in roots:
    for path in root.glob("**/wie_wipi_c/src/api/kernel.rs"):
        text = path.read_text()
        if phone_replacement in text and min_replacement in text:
            already.append(path)
            continue
        changed = False
        if phone_needle in text:
            text = text.replace(phone_needle, phone_replacement, 1)
            changed = True
        if min_needle in text:
            text = text.replace(min_needle, min_replacement, 1)
            changed = True
        # Also upgrade the earlier short-MIN experiment if a cached checkout
        # already contains it.
        old_short_min = f'"MIN" => if context.system().aid() == "{AID}" {{ "9999" }} else {{ "01000000000" }},'
        if old_short_min in text:
            text = text.replace(old_short_min, min_replacement, 1)
            changed = True
        if changed:
            path.write_text(text)
            patched.append(path)

if not patched and not already:
    raise SystemExit("Could not find the pinned WIE C system-property implementation to patch")

for path in patched:
    print(f"patched: {path} -> PHONENUMBER/MIN {FALLBACK} for {AID}")
for path in already:
    print(f"already patched: {path}")
