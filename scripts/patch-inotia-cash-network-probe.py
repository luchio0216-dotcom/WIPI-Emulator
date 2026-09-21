#!/usr/bin/env python3
"""Advance Inotia 1 through defunct KTF cash-shop network gates for offline probing.

AID-scoped only. No Internet access is implemented. The first connect callback is
reported successful, MC_netSocket gets a synthetic local descriptor, legacy
address conversion reads the guest dotted-quad string, and socket-connect is
completed locally so the next request/write API can be observed.
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

fn gen_inotia_socket_connect_probe(id: WIPICWord, name: &'static str) -> WIPICMethodBody {{
    // Do not retain the borrowed WIPICContext across this generated async body.
    // MethodImpl requires a future independent of that borrow; retaining context here
    // causes a lifetime error before the emulator can run. For this probe we return
    // synchronous local success and log the callback address/parameter. A later
    // AID-scoped callback scheduler can be added once the subsequent client behavior
    // establishes whether this legacy API requires the callback to advance.
    let body = move |context: &mut dyn WIPICContext, fd: WIPICWord, addr: WIPICWord, port: WIPICWord, cb: WIPICWord, param: WIPICWord| {{
        let is_inotia = context.system().aid() == "{AID}";
        async move {{
            if is_inotia {{
                tracing::warn!("Inotia cash probe: MC_netSocketConnect fd={{fd:#x}} addr={{addr:#x}} port={{port}} cb={{cb:#x}} param={{param:#x}} -> local synchronous success; callback deferred; no external socket");
                Ok::<u32, WieError>(0)
            }} else {{
                Err(WieError::Unimplemented(format!("{{id}}: {{name}}")))
            }}
        }}
    }};
    body.into_body()
}}

fn gen_inotia_inet_addr_int_probe(id: WIPICWord, name: &'static str) -> WIPICMethodBody {{
    let body = move |context: &mut dyn WIPICContext, addr: WIPICWord| {{
        let is_inotia = context.system().aid() == "{AID}";
        let parsed = if is_inotia {{
            let mut bytes = [0u8; 16];
            let mut len = 0usize;
            let mut read_error = false;
            while len < 15 {{
                let mut one = [0u8; 1];
                if wie_util::ByteRead::read_bytes(context, addr.wrapping_add(len as u32), &mut one).is_err() {{
                    read_error = true;
                    break;
                }}
                if one[0] == 0 {{ break; }}
                bytes[len] = one[0];
                len += 1;
            }}
            if read_error {{
                None
            }} else {{
                core::str::from_utf8(&bytes[..len]).ok().and_then(|text| {{
                    let mut out = 0u32;
                    let mut count = 0usize;
                    for (index, part) in text.split('.').enumerate() {{
                        if index >= 4 {{ return None; }}
                        let octet = part.parse::<u8>().ok()?;
                        out |= (octet as u32) << (8 * index);
                        count += 1;
                    }}
                    if count == 4 {{
                        tracing::warn!("Inotia cash probe: MC_utilInetAddrInt({{text}}) -> {{out:#x}} (offline; no host connection)");
                        Some(out)
                    }} else {{ None }}
                }})
            }}
        }} else {{
            None
        }};
        async move {{
            if is_inotia {{
                Ok::<u32, WieError>(parsed.unwrap_or(u32::MAX))
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

        replacements = [
            ('        gen_stub(2, "MC_netSocket"),', '        gen_inotia_net_probe_stub(2, "MC_netSocket", 1),'),
            ('        gen_stub(3, "MC_netSocketConnect"),', '        gen_inotia_socket_connect_probe(3, "MC_netSocketConnect"),'),
            ('        gen_stub(4, "MC_utilInetAddrInt"),', '        gen_inotia_inet_addr_int_probe(4, "MC_utilInetAddrInt"),'),
        ]
        for old, new in replacements:
            if new not in text:
                if old not in text:
                    raise SystemExit(f"method table entry not found in {path}: {old.strip()}")
                text = text.replace(old, new, 1)
        path.write_text(text)
        print(f"inotia cash socket/connect/address probe patched: {path}")
        found_table = True

if not found_net or not found_table:
    raise SystemExit(f"Missing WIE patch target: net={found_net} table={found_table}")
