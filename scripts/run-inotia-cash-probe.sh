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

# The latest failure is a guest PC=0 immediately after MC_utilInetAddrInt with
# callers at Base+0xc470/+0xc6c4. Capture only narrow byte windows from the
# public game package for disassembly/ABI diagnosis; never persist client.bin.
python3 - <<'PY' > "$RESULT_DIR/crash-site-bytes.txt" || true
import zipfile
z=zipfile.ZipFile('android/app/src/androidTest/assets/inotia1_flat.zip')
name=next((n for n in z.namelist() if n.lower().endswith('client.bin')),None)
print('client_entry=',name)
if name:
    b=z.read(name)
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
