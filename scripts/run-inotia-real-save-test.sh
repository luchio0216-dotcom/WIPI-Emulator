#!/usr/bin/env bash
set -euo pipefail

ROOT="$PWD"
RESULT_DIR="$ROOT/inotia-save-result"
mkdir -p "$RESULT_DIR"

collect_diagnostics() {
  local rc=$?
  cp /tmp/phase1.txt "$RESULT_DIR/phase1-instrumentation.txt" 2>/dev/null || true
  cp /tmp/phase2.txt "$RESULT_DIR/phase2-instrumentation.txt" 2>/dev/null || true
  adb logcat -d > "$RESULT_DIR/logcat.txt" 2>/dev/null || true
  if [ "$rc" -ne 0 ]; then
    echo "real-save-test exit=$rc" > "$RESULT_DIR/runner-failure.txt"
  fi
  return "$rc"
}
trap collect_diagnostics EXIT

echo 'Building app and androidTest APKs...'
gradle -p android :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace

APP_APK="$(find "$ROOT/android/app/build/outputs/apk" -type f -name '*.apk' ! -path '*/androidTest/*' -print -quit)"
TEST_APK="$(find "$ROOT/android/app/build/outputs/apk" -type f -name '*.apk' -path '*/androidTest/*' -print -quit)"
echo "APP_APK=$APP_APK"
echo "TEST_APK=$TEST_APK"
test -n "$APP_APK" && test -f "$APP_APK"
test -n "$TEST_APK" && test -f "$TEST_APK"

adb install -r "$APP_APK"
adb install -r "$TEST_APK"

PKG='com.parkjeongseop.wipi.pdata4'
RUNNER='com.parkjeongseop.wipi.pdata4.test/androidx.test.runner.AndroidJUnitRunner'
CLASS='com.parkjeongseop.wipi.InotiaUserWfsSlot1PersistenceTest'

adb shell pm clear "$PKG" >/dev/null
adb logcat -c || true

# Phase 1 imports the real private WFS, loads the existing SLOT 1, changes real
# play state, and overwrites SLOT 1. It deliberately remains alive so the host
# can prove an external process death with am force-stop.
set +e
adb shell am instrument -w -r \
  -e class "$CLASS#importAndOverwriteSlot1ThenWaitForForceStop" \
  "$RUNNER" > /tmp/phase1.txt 2>&1 &
PHASE1_HOST_PID=$!
set -e

# Do not trust run-as/cat's host exit code for readiness: on some Android
# images a missing file can still leave the outer command looking successful.
# The marker is only valid when it contains the exact 64-hex committed hash.
READY=0
for _ in $(seq 1 180); do
  READY_VALUE="$(adb exec-out run-as "$PKG" cat files/inotia-force-stop-ready.flag 2>/dev/null | tr -d '\r\n' || true)"
  if [[ "$READY_VALUE" =~ ^[0-9a-f]{64}$ ]]; then
    printf '%s' "$READY_VALUE" > /tmp/committed-sha.txt
    READY=1
    break
  fi
  if ! kill -0 "$PHASE1_HOST_PID" 2>/dev/null; then
    echo 'Phase 1 instrumentation exited before verified SLOT 1 overwrite.' >&2
    cat /tmp/phase1.txt || true
    exit 1
  fi
  sleep 2
done

if [ "$READY" -ne 1 ]; then
  echo 'Timed out waiting for verified SLOT 1 overwrite.' >&2
  cat /tmp/phase1.txt || true
  exit 1
fi

PHASE1_REPORT="$(adb exec-out run-as "$PKG" cat files/inotia-user-slot1-phase1.txt 2>/dev/null || true)"
printf '%s\n' "$PHASE1_REPORT" > "$RESULT_DIR/phase1-report.txt"
if ! grep -q 'slot1LoadSucceeded=true' "$RESULT_DIR/phase1-report.txt"; then
  echo 'SLOT 1 load was not verified.' >&2
  cat "$RESULT_DIR/phase1-report.txt" >&2 || true
  exit 1
fi
if ! grep -q 'overwriteSucceeded=true' "$RESULT_DIR/phase1-report.txt"; then
  echo 'SLOT 1 overwrite was not verified.' >&2
  cat "$RESULT_DIR/phase1-report.txt" >&2 || true
  exit 1
fi

for f in user-wfs-imported-slot1-gameplay.png user-wfs-overwrite-result.png; do
  adb exec-out run-as "$PKG" cat "cache/$f" > "$RESULT_DIR/$f" 2>/dev/null || true
  if [ -f "$RESULT_DIR/$f" ] && [ "$(wc -c < "$RESULT_DIR/$f")" -lt 100 ]; then
    rm -f "$RESULT_DIR/$f"
  fi
done

PID_BEFORE="$(adb shell pidof "$PKG" | tr -d '\r')"
if [ -z "$PID_BEFORE" ]; then
  echo 'Target process was not alive before force-stop.' >&2
  exit 1
fi
COMMITTED_SHA="$(tr -d '\r\n' < /tmp/committed-sha.txt)"
echo "pidBeforeForceStop=$PID_BEFORE" | tee "$RESULT_DIR/force-stop.txt"
echo "committedSha256=$COMMITTED_SHA" >> "$RESULT_DIR/force-stop.txt"

adb shell am force-stop "$PKG"
PID_GONE=0
for _ in $(seq 1 30); do
  if [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then
    PID_GONE=1
    break
  fi
  sleep 1
done
if [ "$PID_GONE" -ne 1 ]; then
  echo 'Target PID survived force-stop.' >&2
  exit 1
fi
echo 'pidAfterForceStop=none' >> "$RESULT_DIR/force-stop.txt"

# The host-side instrumentation command is expected to terminate because the
# target process was killed. Its exit status is not the persistence verdict.
wait "$PHASE1_HOST_PID" || true
cp /tmp/phase1.txt "$RESULT_DIR/phase1-instrumentation.txt" || true

# Phase 2 starts a new process and must load the already-overwritten SLOT 1
# without importing the WFS again.
set +e
adb shell am instrument -w -r \
  -e class "$CLASS#reloadPersistedSlotAfterForceStop" \
  "$RUNNER" > /tmp/phase2.txt 2>&1
PHASE2_RC=$?
set -e
cat /tmp/phase2.txt
cp /tmp/phase2.txt "$RESULT_DIR/phase2-instrumentation.txt"
adb exec-out run-as "$PKG" cat cache/user-wfs-reloaded-slot1-gameplay.png > "$RESULT_DIR/user-wfs-reloaded-slot1-gameplay.png" 2>/dev/null || true
adb exec-out run-as "$PKG" cat cache/user-wfs-final-report.txt > "$RESULT_DIR/final-report.txt" 2>/dev/null || true
adb logcat -d > "$RESULT_DIR/logcat.txt" 2>/dev/null || true

if [ "$PHASE2_RC" -ne 0 ]; then
  echo "Phase 2 instrumentation returned $PHASE2_RC" >&2
  exit 1
fi
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2|INSTRUMENTATION_CODE: -1' /tmp/phase2.txt; then
  echo 'Phase 2 instrumentation reported a failure.' >&2
  exit 1
fi
if ! grep -Eq 'OK \(1 test' /tmp/phase2.txt; then
  echo 'Phase 2 did not report one successful test.' >&2
  exit 1
fi
if ! grep -q 'forceStopPersistence=true' "$RESULT_DIR/final-report.txt"; then
  echo 'force-stop persistence was not verified.' >&2
  exit 1
fi
if ! grep -q 'slot1ReloadSucceeded=true' "$RESULT_DIR/final-report.txt"; then
  echo 'SLOT 1 reload was not verified.' >&2
  exit 1
fi
if ! grep -q "committedSaveSha256=$COMMITTED_SHA" "$RESULT_DIR/final-report.txt"; then
  echo 'Committed save hash was not preserved across process death.' >&2
  exit 1
fi

echo 'INOTIA_REAL_SLOT1_FORCE_STOP_PERSISTENCE_OK'
