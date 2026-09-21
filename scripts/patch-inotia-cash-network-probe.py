#!/usr/bin/env python3
"""Advance Inotia 1 through defunct KTF cash-shop network gates for offline probing.

AID-scoped only. No Internet access is implemented. The first connect callback is
reported successful, and MC_netSocket gets a synthetic local descriptor so the
next API/request in the old cash-shop protocol becomes observable.
"""
from pathlib import Path
import os

AID = "010100D3"
cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))
if not roots:
    raise SystemExit("WIE checkout not found; run cargo fetch first")

connect_old = '''            context.call_function(self.cb, &[u32::MAX, self.param]).await?; // callback with M_E_ERROR
'''
connect_new = f'''            let result = if context.system().aid() == "{AID}" {{
                tracing::info!("Inotia cash probe: MC_netConnect callback -> success (offline local probe)");
                0
            }} else {{
                u32::MAX
            }};
            context.call_function(self.cb, &[result, self.param]).await?;
'''

helper = f'''
fn gen_inotia_net_probe_stub(id: WIPICWord, name: &'static str, success: u32) -> WIPICMethodBody {{
    let body = move |context: &mut dyn WIPICContext| {{
        let is_inotia = context.system().aid() == "{AID}";
        async move {{
            if is_inotia {{
                tracing::warn!("Inotia cash probe: {{name}} id={{id}} -> synthetic {{success:#x}}");
                Ok::<u32, WieError>(success)
            }} else {{
                Err(WieError::Unimplemented(format!("{{id}}: {{name}}")))
            }}
        }}
    }};
    body.into_body()
}}
'''

found_net = False
found_table = False
for root in roots:
    for path in root.glob("**/wie_wipi_c/src/api/net.rs"):
        text = path.read_text()
        if connect_new not in text:
            if connect_old not in text:
                raise SystemExit(f"Expected MC_netConnect callback not found in {path}")
            text = text.replace(connect_old, connect_new, 1)
            path.write_text(text)
        print(f"inotia cash connect probe patched: {path}")
        found_net = True

    for path in root.glob("**/wie_ktf/src/runtime/wipi_c/method_table.rs"):
        text = path.read_text()
        if helper.strip() not in text:
            anchor = '''fn gen_stub(id: WIPICWord, name: &'static str) -> WIPICMethodBody {\n    let body = move |_: &mut dyn WIPICContext| async move { Err::<(), _>(WieError::Unimplemented(format!("{id}: {name}"))) };\n\n    body.into_body()\n}\n'''
            if anchor not in text:
                raise SystemExit(f"gen_stub anchor not found in {path}")
            text = text.replace(anchor, anchor + helper, 1)
        old = '        gen_stub(2, "MC_netSocket"),'
        new = '        gen_inotia_net_probe_stub(2, "MC_netSocket", 1),'
        if new not in text:
            if old not in text:
                raise SystemExit(f"MC_netSocket table entry not found in {path}")
            text = text.replace(old, new, 1)
        path.write_text(text)
        print(f"inotia cash socket probe patched: {path}")
        found_table = True

if not found_net or not found_table:
    raise SystemExit(f"Missing WIE patch target: net={found_net} table={found_table}")
