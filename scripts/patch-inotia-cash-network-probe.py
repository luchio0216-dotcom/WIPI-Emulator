#!/usr/bin/env python3
"""Advance Inotia 1 through defunct KTF cash-shop network gates for offline probing.

AID-scoped only. No Internet access is implemented. MC_netConnect and
MC_netSocketConnect complete through guest callbacks, MC_netSocket gets a
synthetic descriptor, and legacy address conversion only parses the guest IP.
Also log KTF WIPIC interface/table addresses to diagnose indirect dispatch.
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

socket_callback = f'''

pub async fn socket_connect_inotia(
    context: &mut dyn WIPICContext,
    fd: WIPICWord,
    addr: WIPICWord,
    port: WIPICWord,
    cb: WIPICWord,
    param: WIPICWord,
) -> Result<i32> {{
    if context.system().aid() != "{AID}" {{
        return Err(WieError::Unimplemented("3: MC_netSocketConnect".into()));
    }}
    tracing::warn!("Inotia cash probe: MC_netSocketConnect fd={{fd:#x}} addr={{addr:#x}} port={{port}} cb={{cb:#x}} param={{param:#x}} -> scheduling local success callback; no external socket");

    struct SocketConnectCallback {{ cb: WIPICWord, param: WIPICWord }}
    #[async_trait::async_trait]
    impl MethodBody<WieError> for SocketConnectCallback {{
        async fn call(&self, context: &mut dyn WIPICContext, _: Box<[WIPICWord]>) -> Result<WIPICResult> {{
            context.system().sleep(1).await;
            tracing::warn!("Inotia cash probe: MC_netSocketConnect callback -> success");
            context.call_function(self.cb, &[0, self.param]).await?;
            Ok(WIPICResult {{ results: Vec::new() }})
        }}
    }}
    context.spawn(Box::new(SocketConnectCallback {{ cb, param }}))?;
    Ok(0)
}}
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

fn gen_inotia_inet_addr_int_probe(id: WIPICWord, name: &'static str) -> WIPICMethodBody {{
    let body = move |context: &mut dyn WIPICContext, addr: WIPICWord| {{
        let is_inotia = context.system().aid() == "{AID}";
        let parsed = if is_inotia {{
            let mut bytes = [0u8; 16];
            let mut len = 0usize;
            let mut read_error = false;
            while len < 15 {{
                let mut one = [0u8; 1];
                if wie_util::ByteRead::read_bytes(context, addr.wrapping_add(len as u32), &mut one).is_err() {{ read_error = true; break; }}
                if one[0] == 0 {{ break; }}
                bytes[len] = one[0]; len += 1;
            }}
            if read_error {{ None }} else {{
                core::str::from_utf8(&bytes[..len]).ok().and_then(|text| {{
                    let mut out = 0u32; let mut count = 0usize;
                    for (index, part) in text.split('.').enumerate() {{
                        if index >= 4 {{ return None; }}
                        let octet = part.parse::<u8>().ok()?;
                        out |= (octet as u32) << (8 * index); count += 1;
                    }}
                    if count == 4 {{
                        tracing::warn!("Inotia cash probe: MC_utilInetAddrInt({{text}}) -> {{out:#x}} (offline; no host connection)");
                        Some(out)
                    }} else {{ None }}
                }})
            }}
        }} else {{ None }};
        async move {{
            if is_inotia {{ Ok::<u32, WieError>(parsed.unwrap_or(u32::MAX)) }}
            else {{ Err(WieError::Unimplemented(format!("{{id}}: {{name}}"))) }}
        }}
    }};
    body.into_body()
}}
'''

found_net = False
found_table = False
found_interface = False
for root in roots:
    for path in root.glob("**/wie_wipi_c/src/api/net.rs"):
        text = path.read_text()
        if connect_new not in text:
            if connect_old not in text:
                raise SystemExit(f"Expected MC_netConnect callback not found in {path}")
            text = text.replace(connect_old, connect_new, 1)
        if socket_callback.strip() not in text:
            marker = '\npub async fn close(_context: &mut dyn WIPICContext) -> Result<()> {'
            if marker not in text:
                raise SystemExit(f"socket callback insertion marker not found in {path}")
            text = text.replace(marker, socket_callback + marker, 1)
        path.write_text(text)
        print(f"inotia cash connect callbacks patched: {path}")
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
            ('        gen_stub(3, "MC_netSocketConnect"),', '        net::socket_connect_inotia.into_body(),'),
            ('        gen_inotia_socket_connect_probe(3, "MC_netSocketConnect"),', '        net::socket_connect_inotia.into_body(),'),
            ('        gen_stub(4, "MC_utilInetAddrInt"),', '        gen_inotia_inet_addr_int_probe(4, "MC_utilInetAddrInt"),'),
        ]
        for old, new in replacements:
            if new not in text and old in text:
                text = text.replace(old, new, 1)
        required = [
            'gen_inotia_net_probe_stub(2, "MC_netSocket", 1)',
            'net::socket_connect_inotia.into_body()',
            'gen_inotia_inet_addr_int_probe(4, "MC_utilInetAddrInt")',
        ]
        if not all(x in text for x in required):
            raise SystemExit(f"network method table patch incomplete in {path}")
        path.write_text(text)
        print(f"inotia cash socket/connect/address probe patched: {path}")
        found_table = True

    # The crash occurs before method id 3 reaches the SVC handler. Log the
    # actual interface pointers allocated for AID 010100D3 so we can determine
    # whether the guest is indexing the net table with a legacy layout/offset.
    for path in root.glob("**/wie_ktf/src/runtime/wipi_c/interface.rs"):
        text = path.read_text()
        needle = '''    let net_interface = write_methods(core, context, WIPICTableId::Net, method_table::get_net_method_table())?;\n'''
        replacement = needle + f'''    if context.system().aid() == "{AID}" {{\n        tracing::warn!("Inotia cash probe: WIPIC tables util={{util_interface:#x}} misc={{misc_interface:#x}} interface3={{interface_3:#x}} interface4={{interface_4:#x}} interface5={{interface_5:#x}} db={{database_interface:#x}} interface7={{interface_7:#x}} uic={{uic_interface:#x}} media={{media_interface:#x}} net={{net_interface:#x}} interface11={{interface_11:#x}} interface12={{interface_12:#x}}");\n    }}\n'''
        if replacement not in text:
            if needle not in text:
                raise SystemExit(f"net interface allocation marker not found in {path}")
            text = text.replace(needle, replacement, 1)
        path.write_text(text)
        print(f"inotia cash interface-address probe patched: {path}")
        found_interface = True

if not found_net or not found_table or not found_interface:
    raise SystemExit(f"Missing WIE patch target: net={found_net} table={found_table} interface={found_interface}")
