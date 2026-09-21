#!/usr/bin/env python3
"""Let Inotia 1 advance past its first KTF network-connect gate for cash-shop probing.

This is deliberately AID-scoped. It does not implement Internet access. It only
reports a successful MC_netConnect callback for Inotia 1 so the next platform
API/request in the defunct cash-shop path becomes observable. All other titles
retain WIE's normal offline failure callback.
"""
from pathlib import Path
import os

AID = "010100D3"
cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))
if not roots:
    raise SystemExit("WIE checkout not found; run cargo fetch first")

old = '''            context.call_function(self.cb, &[u32::MAX, self.param]).await?; // callback with M_E_ERROR
'''
new = f'''            let result = if context.system().aid() == "{AID}" {{
                tracing::info!("Inotia cash probe: MC_netConnect callback -> success (offline local probe)");
                0
            }} else {{
                u32::MAX
            }};
            context.call_function(self.cb, &[result, self.param]).await?;
'''

found = False
for root in roots:
    for path in root.glob("**/wie_wipi_c/src/api/net.rs"):
        text = path.read_text()
        if new in text:
            print(f"inotia cash network probe already patched: {path}")
            found = True
            continue
        if old not in text:
            raise SystemExit(f"Expected MC_netConnect callback not found in {path}")
        path.write_text(text.replace(old, new, 1))
        print(f"inotia cash network probe patched: {path}")
        found = True
if not found:
    raise SystemExit("No WIE net.rs target found")
