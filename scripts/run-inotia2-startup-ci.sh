#!/usr/bin/env bash
set -euo pipefail

gradle -p android :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace

APP_APK="$(find android/app/build/outputs/apk/debug -name '*.apk' | head -n1)"
TEST_APK="$(find android/app/build/outputs/apk/androidTest/debug -name '*.apk' | head -n1)"

if [[ -z "$APP_APK" || ! -f "$APP_APK" ]]; then
  echo "Application APK not found" >&2
  exit 2
fi
if [[ -z "$TEST_APK" || ! -f "$TEST_APK" ]]; then
  echo "AndroidTest APK not found" >&2
  exit 2
fi

echo "APP_APK=$APP_APK"
echo "TEST_APK=$TEST_APK"
adb install -r "$APP_APK"
adb install -r "$TEST_APK"

set +e
adb shell am instrument -w -r \
  -e class com.parkjeongseop.wipi.Inotia2StartupTest \
  com.parkjeongseop.wipi.pdata4.test/androidx.test.runner.AndroidJUnitRunner \
  > /tmp/inotia2-instrumentation.txt 2>&1
TEST_RC=$?
set -e

cat /tmp/inotia2-instrumentation.txt

if grep -qE 'FAILURES!!!|No Inotia 2 emulator frame was produced|Inotia 2 never opened/provisioned cert.c2s' /tmp/inotia2-instrumentation.txt; then
  TEST_RC=1
elif grep -qE 'OK \([0-9]+ tests?\)' /tmp/inotia2-instrumentation.txt; then
  TEST_RC=0
fi

mkdir -p inotia2-startup-result
PKG='com.parkjeongseop.wipi.pdata4'
for f in \
  inotia2-frame-0.png \
  inotia2-frame-1.png \
  inotia2-frame-2.png \
  inotia2-frame-3.png \
  inotia2-frame-4.png \
  inotia2-frame-5.png \
  inotia2-report.txt; do
  adb exec-out run-as "$PKG" cat "cache/$f" > "inotia2-startup-result/$f" 2>/dev/null || true
done
cp /tmp/inotia2-instrumentation.txt inotia2-startup-result/instrumentation.txt || true
adb logcat -d > inotia2-startup-result/logcat.txt 2>/dev/null || true

# Remove zero-byte placeholders created by failed adb cat calls so artifact
# contents directly show which evidence files were actually produced.
find inotia2-startup-result -type f -size 0 -delete || true
ls -lh inotia2-startup-result || true

exit "$TEST_RC"
