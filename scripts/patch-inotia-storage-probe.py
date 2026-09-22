#!/usr/bin/env python3
"""Correct KTF/WIPI handset-era storage and execution-memory probes.

Some legacy titles use MC_dbListDataBase(non-null, 0) as a free-storage query.
Inotia 2 also checks MC_knlGetTotalMemory/MC_knlGetFreeMemory before startup and
refuses to run when the emulator reports the old 1 MiB stub value. Return a
comfortably large virtual capacity for both persistent storage and execution
memory so modern hosts do not trip those handset-era gates.

This patch deliberately does not rewrite Android instrumentation input logic.
The real-save test source owns the user-confirmed keypad sequence:
CLR -> 4 -> 5 -> 5 for Save, then 5 -> 2 -> 5 -> 5 for Exit.
"""
from pathlib import Path
import os

cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))

DB_OLD = '''    if buf_len == 0 {\n        tracing::debug!("MC_dbListDataBase zero-length capacity probe -> 6");\n        return Ok(6);\n    }'''
DB_OLD_1M = '''    if buf_len == 0 {\n        const INOTIA_KTF_AVAILABLE_STORAGE: i32 = 1024 * 1024;\n        tracing::debug!("MC_dbListDataBase zero-length storage-capacity probe -> {INOTIA_KTF_AVAILABLE_STORAGE}");\n        return Ok(INOTIA_KTF_AVAILABLE_STORAGE);\n    }'''
DB_NEW = '''    if buf_len == 0 {\n        const KTF_AVAILABLE_STORAGE: i32 = 64 * 1024 * 1024;\n        tracing::debug!("MC_dbListDataBase zero-length storage-capacity probe -> {KTF_AVAILABLE_STORAGE}");\n        return Ok(KTF_AVAILABLE_STORAGE);\n    }'''

KERNEL_OLD_TOTAL = '''pub async fn get_total_memory(_context: &mut dyn WIPICContext) -> Result<i32> {\n    tracing::warn!("stub MC_knlGetTotalMemory()");\n\n    Ok(0x100000) // TODO hardcoded\n}'''
KERNEL_OLD_FREE = '''pub async fn get_free_memory(_context: &mut dyn WIPICContext) -> Result<i32> {\n    tracing::warn!("stub MC_knlGetFreeMemory()");\n\n    Ok(0x100000) // TODO hardcoded\n}'''
KERNEL_NEW_TOTAL = '''pub async fn get_total_memory(_context: &mut dyn WIPICContext) -> Result<i32> {\n    const KTF_TOTAL_MEMORY: i32 = 64 * 1024 * 1024;\n    tracing::debug!("MC_knlGetTotalMemory() -> {KTF_TOTAL_MEMORY}");\n\n    Ok(KTF_TOTAL_MEMORY)\n}'''
KERNEL_NEW_FREE = '''pub async fn get_free_memory(_context: &mut dyn WIPICContext) -> Result<i32> {\n    const KTF_FREE_MEMORY: i32 = 64 * 1024 * 1024;\n    tracing::debug!("MC_knlGetFreeMemory() -> {KTF_FREE_MEMORY}");\n\n    Ok(KTF_FREE_MEMORY)\n}'''

patched = []
already = []

for root in roots:
    for path in root.glob("**/wie_wipi_c/src/api/database.rs"):
        text = path.read_text()
        if DB_NEW in text:
            already.append(path)
        elif DB_OLD in text:
            path.write_text(text.replace(DB_OLD, DB_NEW, 1))
            patched.append(path)
        elif DB_OLD_1M in text:
            path.write_text(text.replace(DB_OLD_1M, DB_NEW, 1))
            patched.append(path)

    for path in root.glob("**/wie_wipi_c/src/api/kernel.rs"):
        text = path.read_text()
        changed = False
        if KERNEL_NEW_TOTAL not in text:
            if KERNEL_OLD_TOTAL in text:
                text = text.replace(KERNEL_OLD_TOTAL, KERNEL_NEW_TOTAL, 1)
                changed = True
            else:
                continue
        if KERNEL_NEW_FREE not in text:
            if KERNEL_OLD_FREE in text:
                text = text.replace(KERNEL_OLD_FREE, KERNEL_NEW_FREE, 1)
                changed = True
            else:
                continue
        if changed:
            path.write_text(text)
            patched.append(path)
        else:
            already.append(path)

if not patched and not already:
    raise SystemExit("Could not patch KTF/WIPI storage/execution-memory probes")

for path in patched:
    print(f"storage/memory probe patched: {path}")
for path in already:
    print(f"storage/memory probe already patched: {path}")
