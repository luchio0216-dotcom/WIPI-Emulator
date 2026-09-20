#!/usr/bin/env python3
"""Correct Inotia KTF storage probe and advance the real-save menu probe.

The KTF zero-length database-list form is used by Inotia as an available
persistent-storage capacity query. Real-save run #37 proves that soft-key tab
cycling is not valid: SOFT_L opens the minimap and SOFT_R leaves it there.
Probe the remaining feature-phone HANGUP key, which is a distinct WIPI keycode
already supported by the host but has not been exercised by earlier runs.
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

test_path = Path("android/app/src/androidTest/java/com/parkjeongseop/wipi/InotiaUserWfsSlot1PersistenceTest.kt")
if test_path.is_file():
    text = test_path.read_text()
    start = text.index('    private fun saveFromGameplay(dir: File) {')
    end = text.index('\n    private fun press(key: String)', start)
    replacement = '''    private fun saveFromGameplay(dir: File) {
        // Evidence through run #37: SOFT_L/R, OK, NUM0 and STAR reach the
        // minimap; CLR is a no-op. HANGUP is a separate WIPI handset key that
        // the Android host maps but previous probes never exercised. On KTF
        // feature-phone titles the red/end key commonly owns game/system UI,
        // so probe it directly instead of trying to navigate inside minimap.
        press("HANGUP")
        frame(dir, "user-wfs-menu-open.png", capture(1600))
        // If HANGUP opens the System list shown by the real-device reference,
        // Save is the first highlighted row: OK enters Save, then OK confirms.
        press("OK")
        frame(dir, "user-wfs-system-list.png", capture(1200))
        press("OK")
        frame(dir, "user-wfs-save-selected.png", capture(2200))
        press("OK")
        frame(dir, "user-wfs-save-after-confirm.png", capture(1800))
    }
'''
    test_path.write_text(text[:start] + replacement + text[end:])
    print(f"advanced real-save menu navigation to HANGUP system probe: {test_path}")
