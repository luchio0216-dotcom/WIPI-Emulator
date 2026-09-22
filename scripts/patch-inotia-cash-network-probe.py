#!/usr/bin/env python3
from pathlib import Path
import os
AID="010100D3"
cargo_home=Path(os.environ.get("CARGO_HOME",str(Path.home()/".cargo")))
roots=list((cargo_home/"git"/"checkouts").glob("wie-*"))
if not roots: raise SystemExit("WIE checkout not found; run cargo fetch first")
connect_old='''            context.call_function(self.cb, &[u32::MAX, self.param]).await?; // callback with M_E_ERROR
'''
connect_new=f'''            let result = if context.system().aid() == "{AID}" {{ tracing::info!("Inotia cash probe: MC_netConnect callback -> success (offline local probe)"); 0 }} else {{ u32::MAX }};
            context.call_function(self.cb, &[result, self.param]).await?;
'''
socket_callback=f'''

pub async fn socket_connect_inotia(context: &mut dyn WIPICContext, fd: WIPICWord, addr: WIPICWord, port: WIPICWord, cb: WIPICWord, param: WIPICWord) -> Result<i32> {{
    if context.system().aid() != "{AID}" {{ return Err(WieError::Unimplemented("3: MC_netSocketConnect".into())); }}
    tracing::warn!("Inotia cash probe: MC_netSocketConnect fd={{fd:#x}} addr={{addr:#x}} port={{port}} cb={{cb:#x}} param={{param:#x}} -> local success; no external socket");
    struct SocketConnectCallback {{ cb: WIPICWord, param: WIPICWord }}
    #[async_trait::async_trait]
    impl MethodBody<WieError> for SocketConnectCallback {{ async fn call(&self, context: &mut dyn WIPICContext, _: Box<[WIPICWord]>) -> Result<WIPICResult> {{ context.system().sleep(1).await; tracing::warn!("Inotia cash probe: MC_netSocketConnect callback -> success"); context.call_function(self.cb, &[0,self.param]).await?; Ok(WIPICResult {{ results: Vec::new() }}) }} }}
    context.spawn(Box::new(SocketConnectCallback {{ cb,param }}))?; Ok(0)
}}
'''
helper=f'''
fn gen_inotia_net_probe_stub(id: WIPICWord, name: &'static str, success: u32) -> WIPICMethodBody {{ let body=move |context: &mut dyn WIPICContext| {{ let is_inotia=context.system().aid()=="{AID}"; async move {{ if is_inotia {{ tracing::warn!("Inotia cash probe: {{name}} id={{id}} -> synthetic {{success:#x}}"); Ok::<u32,WieError>(success) }} else {{ Err(WieError::Unimplemented(format!("{{id}}: {{name}}"))) }} }} }}; body.into_body() }}
fn gen_inotia_inet_addr_int_probe(id: WIPICWord, name: &'static str) -> WIPICMethodBody {{ let body=move |context: &mut dyn WIPICContext, addr: WIPICWord| {{ let is_inotia=context.system().aid()=="{AID}"; let parsed=if is_inotia {{ let mut bytes=[0u8;16]; let mut len=0usize; let mut bad=false; while len<15 {{ let mut one=[0u8;1]; if wie_util::ByteRead::read_bytes(context,addr.wrapping_add(len as u32),&mut one).is_err() {{ bad=true; break; }} if one[0]==0 {{ break; }} bytes[len]=one[0]; len+=1; }} if bad {{ None }} else {{ core::str::from_utf8(&bytes[..len]).ok().and_then(|text| {{ let mut out=0u32; let mut count=0usize; for (index,part) in text.split('.').enumerate() {{ if index>=4 {{ return None; }} let octet=part.parse::<u8>().ok()?; out|=(octet as u32)<<(8*index); count+=1; }} if count==4 {{ tracing::warn!("Inotia cash probe: MC_utilInetAddrInt({{text}}) -> {{out:#x}} (offline)"); Some(out) }} else {{ None }} }}) }} }} else {{ None }}; async move {{ if is_inotia {{ Ok::<u32,WieError>(parsed.unwrap_or(u32::MAX)) }} else {{ Err(WieError::Unimplemented(format!("{{id}}: {{name}}"))) }} }} }}; body.into_body() }}
'''
found=[False,False,False]
for root in roots:
 for path in root.glob("**/wie_wipi_c/src/api/net.rs"):
  text=path.read_text()
  if connect_new not in text:
   if connect_old not in text: raise SystemExit(f"MC_netConnect callback not found in {path}")
   text=text.replace(connect_old,connect_new,1)
  if socket_callback.strip() not in text:
   marker='\npub async fn close(_context: &mut dyn WIPICContext) -> Result<()> {'
   if marker not in text: raise SystemExit(f"socket marker not found in {path}")
   text=text.replace(marker,socket_callback+marker,1)
  path.write_text(text); found[0]=True
 for path in root.glob("**/wie_ktf/src/runtime/wipi_c/method_table.rs"):
  text=path.read_text()
  if helper.strip() not in text:
   anchor='''fn gen_stub(id: WIPICWord, name: &'static str) -> WIPICMethodBody {\n    let body = move |_: &mut dyn WIPICContext| async move { Err::<(), _>(WieError::Unimplemented(format!("{id}: {name}"))) };\n\n    body.into_body()\n}\n'''
   if anchor not in text: raise SystemExit(f"gen_stub anchor missing {path}")
   text=text.replace(anchor,anchor+helper,1)
  for old,new in [('        gen_stub(2, "MC_netSocket"),','        gen_inotia_net_probe_stub(2, "MC_netSocket", 1),'),('        gen_stub(3, "MC_netSocketConnect"),','        net::socket_connect_inotia.into_body(),'),('        gen_inotia_socket_connect_probe(3, "MC_netSocketConnect"),','        net::socket_connect_inotia.into_body(),'),('        gen_stub(4, "MC_utilInetAddrInt"),','        gen_inotia_inet_addr_int_probe(4, "MC_utilInetAddrInt"),')]:
   if new not in text and old in text: text=text.replace(old,new,1)
  path.write_text(text); found[1]=True
 for path in root.glob("**/wie_ktf/src/runtime/wipi_c/interface.rs"):
  text=path.read_text()
  old=f'''    if context.system().aid() == "{AID}" {{\n        tracing::warn!("Inotia cash probe: WIPIC tables util={{util_interface:#x}} misc={{misc_interface:#x}} interface3={{interface_3:#x}} interface4={{interface_4:#x}} interface5={{interface_5:#x}} db={{database_interface:#x}} interface7={{interface_7:#x}} uic={{uic_interface:#x}} media={{media_interface:#x}} net={{net_interface:#x}} interface11={{interface_11:#x}} interface12={{interface_12:#x}}");\n    }}\n'''
  log='''    tracing::warn!("Inotia cash probe: WIPIC tables aid={} util={util_interface:#x} misc={misc_interface:#x} interface3={interface_3:#x} interface4={interface_4:#x} interface5={interface_5:#x} db={database_interface:#x} interface7={interface_7:#x} uic={uic_interface:#x} media={media_interface:#x} net={net_interface:#x} interface11={interface_11:#x} interface12={interface_12:#x}", context.system().aid());\n'''
  text=text.replace(old,'').replace(log,'')
  anchor='''    let interface_12 = write_methods(core, context, WIPICTableId::Interface12, method_table::get_unk12_method_table())?;\n'''
  if anchor not in text: raise SystemExit(f"interface_12 marker missing {path}")
  text=text.replace(anchor,anchor+log,1)
  # client.bin loads WIPICInterface+0x78 before the runtime has initialized AID.
  # Reserve that legacy tail for KTF interfaces up front and install a dispatcher stub.
  # The method body itself remains strictly AID-gated, so non-Inotia games retain the
  # original Unimplemented network behavior and cannot gain local-success semantics.
  alloc_old='''    let address = context.alloc_raw(size_of::<WIPICInterface>() as u32)?;\n\n    write_generic(context, address, interface)?;\n\n    Ok(address)\n'''
  previous=f'''    let is_inotia_legacy = context.system().aid() == "{AID}";\n    let interface_size = if is_inotia_legacy {{ 0x80 }} else {{ size_of::<WIPICInterface>() as u32 }};\n    let address = context.alloc_raw(interface_size)?;\n\n    write_generic(context, address, interface)?;\n    if is_inotia_legacy {{\n        let socket_connect_stub = core.make_svc_stub(crate::runtime::SVC_CATEGORY_WIPIC, WIPICTableId::Net.function_id(3u16))?;\n        write_generic(context, address + 0x78, socket_connect_stub)?;\n        tracing::warn!("Inotia cash probe: legacy WIPIC +0x78 MC_netSocketConnect stub={{socket_connect_stub:#x}} interface={{address:#x}}");\n    }}\n\n    Ok(address)\n'''
  alloc_new='''    let interface_size = core::cmp::max(size_of::<WIPICInterface>() as u32, 0x80);\n    let address = context.alloc_raw(interface_size)?;\n\n    write_generic(context, address, interface)?;\n    let socket_connect_stub = core.make_svc_stub(crate::runtime::SVC_CATEGORY_WIPIC, WIPICTableId::Net.function_id(3u16))?;\n    write_generic(context, address + 0x78, socket_connect_stub)?;\n    tracing::warn!("Inotia cash probe: guarded legacy WIPIC +0x78 socket-connect dispatcher stub={socket_connect_stub:#x} interface={address:#x} aid_at_init={}", context.system().aid());\n\n    Ok(address)\n'''
  if alloc_new not in text:
   if previous in text: text=text.replace(previous,alloc_new,1)
   elif alloc_old in text: text=text.replace(alloc_old,alloc_new,1)
   else: raise SystemExit(f"interface allocation marker missing {path}")
  path.write_text(text); found[2]=True
if not all(found): raise SystemExit(f"Missing patch target: {found}")
