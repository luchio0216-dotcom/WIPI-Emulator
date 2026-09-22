#!/usr/bin/env bash
set -euo pipefail

ROOT="$PWD"
RESULT_DIR="$ROOT/inotia-cash-probe-result"
mkdir -p "$RESULT_DIR"
PKG='com.parkjeongseop.wipi.pdata4'
RUNNER='com.parkjeongseop.wipi.pdata4.test/androidx.test.runner.AndroidJUnitRunner'
CLASS='com.parkjeongseop.wipi.InotiaCashShopProbeTest#openCashShopAndCaptureBackendFailure'

collect() {
  local f
  for f in cash-probe-00-gameplay.png cash-probe-01-clr.png cash-probe-02-system-tab.png cash-probe-03-system-list.png cash-probe-04-down1.png cash-probe-05-down2.png cash-probe-06-down3.png cash-probe-07-charge-prompt.png cash-probe-08-confirm-yes.png cash-probe-09-after-wait.png cash-probe-report.txt; do
    adb exec-out run-as "$PKG" cat "cache/$f" > "$RESULT_DIR/$f" 2>/dev/null || true
    if [ -f "$RESULT_DIR/$f" ] && [ "$(wc -c < "$RESULT_DIR/$f")" -lt 20 ]; then rm -f "$RESULT_DIR/$f"; fi
  done
  adb logcat -d > "$RESULT_DIR/logcat.txt" 2>/dev/null || true
}
trap 'rc=$?; collect || true; exit $rc' EXIT

gradle -p android :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
APP_APK="$(find "$ROOT/android/app/build/outputs/apk" -type f -name '*.apk' ! -path '*/androidTest/*' -print -quit)"
TEST_APK="$(find "$ROOT/android/app/build/outputs/apk" -type f -name '*.apk' -path '*/androidTest/*' -print -quit)"
test -f "$APP_APK"; test -f "$TEST_APK"
adb install -r "$APP_APK"
adb install -r "$TEST_APK"
adb shell pm clear "$PKG" >/dev/null
adb logcat -c || true
set +e
adb shell am instrument -w -r -e class "$CLASS" "$RUNNER" > /tmp/cash-probe.txt 2>&1
RC=$?
set -e
cat /tmp/cash-probe.txt
cp /tmp/cash-probe.txt "$RESULT_DIR/instrumentation.txt"
collect

# Inspect only narrow windows from the private test package's client.bin. Never
# persist the package or WFS. Besides the indirect-call trampoline, decode the
# two conditional branches after MC_netSocketConnect. This distinguishes the
# client's accepted pending results (0/-19) from its immediate cleanup path and
# gives the next static state-machine targets without guessing another return.
python3 - <<'PY' > "$RESULT_DIR/crash-site-bytes.txt" || true
import io, struct, zipfile
outer=zipfile.ZipFile('android/app/src/androidTest/assets/inotia1_flat.zip')
infos=[i for i in outer.infolist() if not i.is_dir()]
print('outer entries:')
for i in infos: print(f'  {i.filename!r} size={i.file_size}')
jar=next((i for i in infos if i.filename.lower().endswith('.jar')),None)
if not jar:
    print('nested_jar=None'); raise SystemExit
raw=outer.read(jar)
try: inner=zipfile.ZipFile(io.BytesIO(raw))
except zipfile.BadZipFile:
    print('nested_jar_bad_zip=',jar.filename); raise SystemExit
inner_infos=[i for i in inner.infolist() if not i.is_dir()]
print('nested jar=',repr(jar.filename))
print('nested entries:')
for i in inner_infos: print(f'  {i.filename!r} size={i.file_size}')
preferred=[i for i in inner_infos if i.filename.lower().startswith('client.bin') and i.file_size>0xc704]
if not preferred:
    preferred=[i for i in inner_infos if i.filename.lower().endswith(('.bin','.mod','.exe','.so')) and i.file_size>0xc704]
covering=[i for i in inner_infos if not i.filename.lower().endswith(('.class','.mf')) and i.file_size>0xc704]
candidates=preferred or covering
name=candidates[0].filename if candidates else None
print('selected_nested_entry=',repr(name))
if name:
    b=inner.read(name)
    print('selected_magic=',b[:16].hex(' '))
    def thumb_bl_target(off):
        if off+4>len(b): return None
        h1,h2=struct.unpack_from('<HH',b,off)
        if (h1 & 0xf800)!=0xf000 or (h2 & 0xd000)!=0xd000: return None
        s=(h1>>10)&1; j1=(h2>>13)&1; j2=(h2>>11)&1
        i1=(~(j1^s))&1; i2=(~(j2^s))&1
        imm=(s<<24)|(i1<<23)|(i2<<22)|((h1&0x3ff)<<12)|((h2&0x7ff)<<1)
        if s: imm-=1<<25
        return (off+4+imm)&0xffffffff
    def thumb_cond_target(off):
        if off+2>len(b): return None
        h=struct.unpack_from('<H',b,off)[0]
        if (h & 0xf000)!=0xd000 or ((h>>8)&0xf)>=0xe: return None
        imm=h&0xff
        if imm&0x80: imm-=0x100
        return (off+4+(imm<<1))&0xffffffff
    call=0xc6c4
    target=thumb_bl_target(call)
    print(f'call_0xc6c4_halfwords=0x{struct.unpack_from("<H",b,call)[0]:04x},0x{struct.unpack_from("<H",b,call+2)[0]:04x}')
    print('call_0xc6c4_thumb_bl_target=',None if target is None else f'0x{target:x}')
    branch_targets=[]
    for off,label in [(0xc6ca,'r0_eq_0'),(0xc6d2,'r0_eq_minus19')]:
        h=struct.unpack_from('<H',b,off)[0]
        t=thumb_cond_target(off)
        print(f'connect_result_branch {label} off=0x{off:x} halfword=0x{h:04x} target='+('None' if t is None else f'0x{t:x}'))
        if t is not None and t < len(b): branch_targets.append(t)
    offsets=[0xc430,0xc470,0xc6a0,0xc6c4,0xc6d4]
    if target is not None and target < len(b): offsets += [max(0,target-0x20),target,target+0x20]
    for t in branch_targets: offsets += [max(0,t-0x20),t,t+0x20]
    seen=set()
    for off in offsets:
        if off in seen: continue
        seen.add(off)
        lo=max(0,off-32); hi=min(len(b),off+64)
        print(f'offset=0x{off:x} range=0x{lo:x}-0x{hi:x}')
        for p in range(lo,hi,16): print(f'{p:08x}: '+b[p:p+16].hex(' '))
PY

echo '=== INOTIA CASH PROBE REPORT ==='
cat "$RESULT_DIR/cash-probe-report.txt" 2>/dev/null || true
echo '=== INOTIA CASH CRASH SITE BYTES ==='
cat "$RESULT_DIR/crash-site-bytes.txt" 2>/dev/null || true
echo '=== INOTIA CASH NETWORK TRACE ==='
grep -E -i 'Inotia cash probe|MC_net|MC_utilInet|Unimplemented|Invalid memory|Fatal error|pollExit|socket|send|recv|write|read' "$RESULT_DIR/logcat.txt" | tail -n 300 || true
echo '=== END INOTIA CASH NETWORK TRACE ==='

if grep -Eq 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2' /tmp/cash-probe.txt; then
  exit 1
fi
if ! grep -Eq 'OK \(1 test' /tmp/cash-probe.txt; then
  exit "$RC"
fi
test -s "$RESULT_DIR/cash-probe-08-confirm-yes.png"
test -s "$RESULT_DIR/cash-probe-report.txt"
echo INOTIA_CASH_SHOP_PROBE_COMPLETE
