#!/usr/bin/env python3
"""Correct Inotia KTF storage probe and advance the real-save menu probe.

The KTF zero-length database-list form is used by Inotia as an available
persistent-storage capacity query.  Real-device UI evidence plus run #36 show
that the first menu page is the world map; LEFT/RIGHT are map navigation, not
menu-tab navigation.  Probe the feature-phone soft keys as tab selectors.
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

# Runs #31-#36 established that SOFT/OK/0/* can land on the world-map page,
# while CLR is a no-op.  The captured world-map page labels the bottom corners
# as soft-key actions, and LEFT/RIGHT merely move the map cursor.  Therefore
# stop guessing a different menu-open key: open the menu with SOFT_L and use
# SOFT_R to advance menu tabs until the System page, then select Save.
test_path = Path("android/app/src/androidTest/java/com/parkjeongseop/wipi/InotiaUserWfsSlot1PersistenceTest.kt")
if test_path.is_file():
    text = test_path.read_text()
    start = text.index('    private fun saveFromGameplay(dir: File) {')
    end = text.index('\n    private fun press(key: String)', start)
    replacement = '''    private fun saveFromGameplay(dir: File) {
        // Run #36 proved STAR still opens the world-map page. The world-map
        // page itself exposes soft-key actions; LEFT/RIGHT are map movement.
        // Treat this as a tabbed feature-phone menu: SOFT_L opens it and
        // repeated SOFT_R advances tabs toward the System page.
        press("SOFT_L")
        frame(dir, "user-wfs-menu-open.png", capture(1200))
        repeat(5) { press("SOFT_R") }
        frame(dir, "user-wfs-system-tab.png", capture(1200))
        press("OK")
        frame(dir, "user-wfs-system-list.png", capture(1200))
        press("OK")
        frame(dir, "user-wfs-save-selected.png", capture(2200))
        press("OK")
        frame(dir, "user-wfs-save-after-confirm.png", capture(1600))
    }
'''
    test_path.write_text(text[:start] + replacement + text[end:])
    print(f"advanced real-save menu navigation to SOFT_R tab cycling: {test_path}")
