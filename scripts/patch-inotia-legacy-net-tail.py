#!/usr/bin/env python3
from pathlib import Path
import os

cargo_home = Path(os.environ.get("CARGO_HOME", str(Path.home() / ".cargo")))
roots = list((cargo_home / "git" / "checkouts").glob("wie-*"))
if not roots:
    raise SystemExit("WIE checkout not found; run cargo fetch first")

patched = False
for root in roots:
    for path in root.glob("**/wie_ktf/src/runtime/wipi_c/interface.rs"):
        text = path.read_text()
        old = '''fn write_methods(core: &mut ArmCore, context: &mut dyn WIPICContext, table_id: WIPICTableId, methods: Vec<WIPICMethodBody>) -> Result<u32> {
    let address = context.alloc_raw((methods.len() * 4) as u32)?;

    let mut cursor = address;
    for (index, _) in methods.into_iter().enumerate() {
        let address = core.make_svc_stub(crate::runtime::SVC_CATEGORY_WIPIC, table_id.function_id(index as u16))?;

        write_generic(context, cursor, address)?;
        cursor += 4;
    }

    Ok(address)
}
'''
        new = '''fn write_methods(core: &mut ArmCore, context: &mut dyn WIPICContext, table_id: WIPICTableId, methods: Vec<WIPICMethodBody>) -> Result<u32> {
    // Inotia 1's KTF client uses a legacy Net ABI: the socket-connect entry is
    // loaded from net_interface + 0x78.  Keep the normal table intact, reserve
    // the legacy tail only for Net, and alias that slot to the existing Net
    // method id 3 dispatcher.  The method body remains AID-gated by the cash
    // probe patch, so this does not grant local-success semantics to other apps.
    let normal_size = (methods.len() * 4) as u32;
    let alloc_size = if table_id == WIPICTableId::Net { core::cmp::max(normal_size, 0x7c) } else { normal_size };
    let address = context.alloc_raw(alloc_size)?;

    let mut cursor = address;
    for (index, _) in methods.into_iter().enumerate() {
        let stub = core.make_svc_stub(crate::runtime::SVC_CATEGORY_WIPIC, table_id.function_id(index as u16))?;
        write_generic(context, cursor, stub)?;
        cursor += 4;
    }

    if table_id == WIPICTableId::Net {
        let socket_connect_stub = core.make_svc_stub(crate::runtime::SVC_CATEGORY_WIPIC, table_id.function_id(3u16))?;
        write_generic(context, address + 0x78, socket_connect_stub)?;
        tracing::warn!("Inotia cash probe: legacy Net +0x78 alias -> method 3 stub={socket_connect_stub:#x} net={address:#x}");
    }

    Ok(address)
}
'''
        if new in text:
            patched = True
            continue
        if old not in text:
            raise SystemExit(f"write_methods marker missing in {path}")
        path.write_text(text.replace(old, new, 1))
        print(f"legacy Net tail patched: {path}")
        patched = True

if not patched:
    raise SystemExit("No KTF interface.rs patch target found")
