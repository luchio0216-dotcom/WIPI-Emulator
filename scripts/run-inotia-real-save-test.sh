#!/usr/bin/env bash
set -euo pipefail

ROOT="$PWD"
RESULT_DIR="$ROOT/inotia-save-result"
mkdir -p "$RESULT_DIR"

collect_evidence_files() {
  local f
  for f in \
    user-wfs-imported-slot1-gameplay.png \
    user-wfs-save-step1-clr.png \
    user-wfs-save-step2-num4.png \
    user-wfs-save-step3-num5.png \
    user-wfs-save-step4-result.png \
    user-wfs-overwrite-result.png \
    user-wfs-exit-step1-dismiss-save.png \
    user-wfs-exit-step2-num2.png \
    user-wfs-exit-step3-select-exit.png \
    user-wfs-main-menu-after-exit.png \
    user-wfs-mainmenu-reloaded-slot1-gameplay.png \
    user-wfs-reloaded-slot1-gameplay.png \
    user-wfs-final-report.txt; do
    adb exec-out run-as "$PKG" cat "cache/$f" > "$RESULT_DIR/$f" 2>/dev/null || true
    if [ -f "$RESULT_DIR/$f" ] && [ "$(wc -c < "$RESULT_DIR/$f")" -lt 100 ]; then rm -f "$RESULT_DIR/$f"; fi
  done
  adb exec-out run-as "$PKG" cat files/inotia-user-slot1-phase1.txt > "$RESULT_DIR/phase1-report.txt" 2>/dev/null || true
  if [ -f "$RESULT_DIR/phase1-report.txt" ] && [ "$(wc -c < "$RESULT_DIR/phase1-report.txt")" -lt 20 ]; then rm -f "$RESULT_DIR/phase1-report.txt"; fi
}

collect_diagnostics() {
  local rc=$?
  if [ -n "${PKG:-}" ]; then collect_evidence_files || true; fi
  cp /tmp/phase1.txt "$RESULT_DIR/phase1-instrumentation.txt" 2>/dev/null || true
  cp /tmp/phase2.txt "$RESULT_DIR/phase2-instrumentation.txt" 2>/dev/null || true
  adb logcat -d > "$RESULT_DIR/logcat.txt" 2>/dev/null || true
  if [ "$rc" -ne 0 ]; then echo "real-save-test exit=$rc" > "$RESULT_DIR/runner-failure.txt"; fi
  return "$rc"
}
trap collect_diagnostics EXIT

echo 'Building app and androidTest APKs...'
gradle -p android :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
APP_APK="$(find "$ROOT/android/app/build/outputs/apk" -type f -name '*.apk' ! -path '*/androidTest/*' -print -quit)"
TEST_APK="$(find "$ROOT/android/app/build/outputs/apk" -type f -name '*.apk' -path '*/androidTest/*' -print -quit)"
test -n "$APP_APK" && test -f "$APP_APK"; test -n "$TEST_APK" && test -f "$TEST_APK"
adb install -r "$APP_APK"; adb install -r "$TEST_APK"

PKG='com.parkjeongseop.wipi.pdata4'
RUNNER='com.parkjeongseop.wipi.pdata4.test/androidx.test.runner.AndroidJUnitRunner'
CLASS='com.parkjeongseop.wipi.InotiaUserWfsSlot1PersistenceTest'
adb shell pm clear "$PKG" >/dev/null; adb logcat -c || true

set +e
adb shell am instrument -w -r -e class "$CLASS#importAndOverwriteSlot1ThenWaitForForceStop" "$RUNNER" > /tmp/phase1.txt 2>&1 &
PHASE1_HOST_PID=$!
set -e
READY=0
for _ in $(seq 1 240); do
  READY_VALUE="$(adb exec-out run-as "$PKG" cat files/inotia-force-stop-ready.flag 2>/dev/null | tr -d '\r\n' || true)"
  if [[ "$READY_VALUE" =~ ^[0-9a-f]{64}$ ]]; then printf '%s' "$READY_VALUE" > /tmp/committed-sha.txt; READY=1; break; fi
  if ! kill -0 "$PHASE1_HOST_PID" 2>/dev/null; then echo 'Phase 1 exited before verified save/main-menu reload.' >&2; cat /tmp/phase1.txt || true; exit 1; fi
  sleep 2
done
if [ "$READY" -ne 1 ]; then echo 'Timed out waiting for verified save/main-menu reload.' >&2; cat /tmp/phase1.txt || true; exit 1; fi

collect_evidence_files || true
for gate in slot1LoadSucceeded=true overwriteSucceeded=true saveCompletePathSucceeded=true mainMenuReloadSucceeded=true exactSaveSequence=CLR,4,5,5 exactExitSequence=5,2,5,5; do
  if ! grep -q "$gate" "$RESULT_DIR/phase1-report.txt"; then echo "Missing phase1 gate: $gate" >&2; cat "$RESULT_DIR/phase1-report.txt" >&2 || true; exit 1; fi
done
for evidence in user-wfs-overwrite-result.png user-wfs-main-menu-after-exit.png user-wfs-mainmenu-reloaded-slot1-gameplay.png; do
  test -s "$RESULT_DIR/$evidence" || { echo "Missing evidence: $evidence" >&2; exit 1; }
done

PID_BEFORE="$(adb shell pidof "$PKG" | tr -d '\r')"; test -n "$PID_BEFORE"
COMMITTED_SHA="$(tr -d '\r\n' < /tmp/committed-sha.txt)"
echo "pidBeforeForceStop=$PID_BEFORE" | tee "$RESULT_DIR/force-stop.txt"; echo "committedSha256=$COMMITTED_SHA" >> "$RESULT_DIR/force-stop.txt"
adb shell am force-stop "$PKG"
PID_GONE=0
for _ in $(seq 1 30); do if [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then PID_GONE=1; break; fi; sleep 1; done
test "$PID_GONE" -eq 1; echo 'pidAfterForceStop=none' >> "$RESULT_DIR/force-stop.txt"
wait "$PHASE1_HOST_PID" || true; cp /tmp/phase1.txt "$RESULT_DIR/phase1-instrumentation.txt" || true

set +e
adb shell am instrument -w -r -e class "$CLASS#reloadPersistedSlotAfterForceStop" "$RUNNER" > /tmp/phase2.txt 2>&1
PHASE2_RC=$?
set -e
cat /tmp/phase2.txt; cp /tmp/phase2.txt "$RESULT_DIR/phase2-instrumentation.txt"; collect_evidence_files || true; adb logcat -d > "$RESULT_DIR/logcat.txt" 2>/dev/null || true
if [ "$PHASE2_RC" -ne 0 ]; then echo "Phase 2 instrumentation returned $PHASE2_RC" >&2; exit 1; fi
# AndroidJUnitRunner's normal successful terminal result is INSTRUMENTATION_CODE: -1.
# Failure is determined by FAILURES/status -2 and by the explicit JUnit OK + persistence gates below.
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2' /tmp/phase2.txt; then echo 'Phase 2 instrumentation reported a failure.' >&2; exit 1; fi
if ! grep -Eq 'OK \(1 test' /tmp/phase2.txt; then echo 'Phase 2 did not report one successful test.' >&2; exit 1; fi
for gate in forceStopPersistence=true slot1ReloadSucceeded=true; do grep -q "$gate" "$RESULT_DIR/user-wfs-final-report.txt" || { echo "Missing final gate: $gate" >&2; exit 1; }; done
grep -q "committedSaveSha256=$COMMITTED_SHA" "$RESULT_DIR/user-wfs-final-report.txt" || { echo 'Committed save hash was not preserved across process death.' >&2; exit 1; }
echo 'INOTIA_REAL_SLOT1_SAVE_EXIT_MAINMENU_RELOAD_FORCESTOP_OK'
