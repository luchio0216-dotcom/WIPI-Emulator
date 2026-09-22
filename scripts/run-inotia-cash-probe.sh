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

# Diagnose the PC=0 call site without persisting any game binary. Inotia ships a
# nested 010100D3.jar, so inspect its directory rather than treating compressed
# JAR bytes as ARM code. Emit metadata and only narrow byte windows from a
# plausible nested executable/resource; never copy the package or user WFS.
python3 - <<'PY' > "$RESULT_DIR/crash-site-bytes.txt" || true
import io, zipfile
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
# This title carries its ARM image as client.bin<decimal-size> (for example
# client.bin138532), not a literal client.bin suffix. Prefer that exact family
# before generic large resources such as work.bar.
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
    for off in (0xc430,0xc470,0xc6a0,0xc6c4):
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
