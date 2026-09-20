#!/usr/bin/env python3
"""Correct Inotia KTF storage probe and advance the real-save key probe.

The KTF zero-length database-list form is used by Inotia as an available
persistent-storage capacity query.  Also patch the instrumentation probe for
this run: NUM0 was proven by run #35 to open the minimap, so test STAR next.
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

# Run #35 evidence: NUM0 opens the same minimap as SOFT_L/SOFT_R/OK.
# Probe STAR next without duplicating the previous failed input path.
test_path = Path("android/app/src/androidTest/java/com/parkjeongseop/wipi/InotiaUserWfsSlot1PersistenceTest.kt")
if test_path.is_file():
    text = test_path.read_text()
    old_probe = '''        // Runs #31/#32: soft-key guesses enter minimap. #33: CLR is a no-op.\n        // #34: center OK also enters minimap. Probe NUM0 next; the minimap itself\n        // advertises */# controls, so NUM0 is a stronger independent menu candidate.\n        press("0")'''
    new_probe = '''        // Runs #31/#32: soft keys enter minimap; #33 CLR is a no-op;\n        // #34 OK enters minimap; #35 NUM0 also enters minimap. Probe STAR next.\n        press("*")'''
    if old_probe not in text:
        raise SystemExit("Expected NUM0 save-menu probe not found")
    test_path.write_text(text.replace(old_probe, new_probe, 1))
    print(f"advanced real-save menu probe to STAR: {test_path}")
