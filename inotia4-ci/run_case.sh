#!/usr/bin/env bash
set -euo pipefail

CASE_NAME="${1:?case name required}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CI_DIR="$ROOT_DIR/inotia4-ci"
WORK="$RUNNER_TEMP/inotia4-$CASE_NAME"
SRC="$WORK/source"
SELECTED="$WORK/selected"
SIGNED="$WORK/signed"
LOGDIR="$ROOT_DIR/inotia4-ci/test-results/$CASE_NAME"
TARGET_ENTRY="assets/common/game_res/memorytext_e.dat.jpg"
PACKAGE="com.com2us.inotia4.normal.freefull.google.global.android.common"
XAPK_URL="https://d.apkpure.net/b/XAPK/com.com2us.inotia4.normal.freefull.google.global.android.common?versionCode=139004"
USER_ORIGINAL_RESOURCE_SHA256="4e674cc35edf276e466c66b8c7562586f9d7c5e46bbb712a90bc8945c0705a22"
USER_PATCHED_RESOURCE_SHA256="75bb655c8c2cd3e89a28e8b90e1e6d1d7900f26521dc5aece8572964079cb3ee"

rm -rf "$WORK"
mkdir -p "$SRC" "$SELECTED" "$SIGNED" "$LOGDIR"

XAPK="$RUNNER_TEMP/inotia4-1.3.9.xapk"
if [[ ! -s "$XAPK" ]]; then
  echo "Downloading Inotia4 1.3.9 public test source..."
  curl -fL --retry 5 --retry-delay 3 -A 'Mozilla/5.0 GitHub-Actions-Inotia4-CI' "$XAPK_URL" -o "$XAPK"
fi
sha256sum "$XAPK" | tee "$LOGDIR/xapk.sha256"
unzip -q -o "$XAPK" -d "$SRC"

mapfile -t ALL_APKS < <(find "$SRC" -type f -name '*.apk' | sort)
if [[ ${#ALL_APKS[@]} -eq 0 ]]; then
  echo "No APK files found in downloaded XAPK" >&2
  exit 10
fi

BASE_APK=""
for apk in "${ALL_APKS[@]}"; do
  name="$(basename "$apk")"
  if [[ "$name" != config.*.apk ]]; then
    if [[ -z "$BASE_APK" || $(stat -c %s "$apk") -gt $(stat -c %s "$BASE_APK") ]]; then
      BASE_APK="$apk"
    fi
  fi
done
if [[ -z "$BASE_APK" ]]; then
  BASE_APK="$(find "$SRC" -type f -name '*.apk' -printf '%s %p\n' | sort -nr | head -n1 | cut -d' ' -f2-)"
fi

# Keep only the base, resource/language/density splits and x86/x86_64 native
# split for the CI emulator. The Android 15 emulator can use ABI translation
# when an ARM-only fixture is used in later iterations.
for apk in "${ALL_APKS[@]}"; do
  listing="$(unzip -Z1 "$apk")"
  if grep -Eq '^lib/(arm64-v8a|armeabi-v7a)/' <<<"$listing"; then
    continue
  fi
  cp "$apk" "$SELECTED/$(basename "$apk")"
done

BASE_NAME="$(basename "$BASE_APK")"
if [[ ! -f "$SELECTED/$BASE_NAME" ]]; then
  cp "$BASE_APK" "$SELECTED/$BASE_NAME"
fi

TARGET_PRESENT=no
SOURCE_RESOURCE_SHA256=missing
TARGET_OWNER=""
for apk in "$SELECTED"/*.apk; do
  if unzip -Z1 "$apk" | grep -qx "$TARGET_ENTRY"; then
    TARGET_PRESENT=yes
    TARGET_OWNER="$apk"
    unzip -p "$apk" "$TARGET_ENTRY" > "$WORK/source-memorytext_e.dat.jpg"
    SOURCE_RESOURCE_SHA256="$(sha256sum "$WORK/source-memorytext_e.dat.jpg" | awk '{print $1}')"
    break
  fi
done

{
  echo "base_apk=$BASE_APK"
  echo "target_entry_present=$TARGET_PRESENT"
  echo "target_owner=$TARGET_OWNER"
  echo "source_resource_sha256=$SOURCE_RESOURCE_SHA256"
  echo "user_original_resource_sha256=$USER_ORIGINAL_RESOURCE_SHA256"
  echo "user_patched_resource_sha256=$USER_PATCHED_RESOURCE_SHA256"
  [[ "$SOURCE_RESOURCE_SHA256" == "$USER_ORIGINAL_RESOURCE_SHA256" ]] && echo "resource_fixture_match=yes" || echo "resource_fixture_match=no"
} | tee "$LOGDIR/resource-hashes.txt"

# Never claim a gameplay patch passed when the public fixture does not contain
# the exact same source data as the APK supplied by the user.
if [[ "$CASE_NAME" == "patched-999" && "$SOURCE_RESOURCE_SHA256" != "$USER_ORIGINAL_RESOURCE_SHA256" ]]; then
  echo "RESULT=$CASE_NAME FIXTURE_MISMATCH" | tee "$LOGDIR/summary.txt"
  exit 15
fi

if [[ "$CASE_NAME" == "patched-999" ]]; then
  base64 -d "$CI_DIR/patches/memorytext_e.dat.jpg.b64" > "$WORK/patched-memorytext_e.dat.jpg"
  PATCH_SHA="$(sha256sum "$WORK/patched-memorytext_e.dat.jpg" | awk '{print $1}')"
  [[ "$PATCH_SHA" == "$USER_PATCHED_RESOURCE_SHA256" ]] || exit 13

  PATCH_OWNER="$SELECTED/$(basename "$TARGET_OWNER")"
  cp "$PATCH_OWNER" "$WORK/patched-owner.apk"
  zip -q -d "$WORK/patched-owner.apk" "$TARGET_ENTRY"
  mkdir -p "$WORK/inject/assets/common/game_res"
  cp "$WORK/patched-memorytext_e.dat.jpg" "$WORK/inject/$TARGET_ENTRY"
  (
    cd "$WORK/inject"
    zip -q -0 "$WORK/patched-owner.apk" "$TARGET_ENTRY"
  )
  cp "$WORK/patched-owner.apk" "$PATCH_OWNER"
  ACTUAL_PATCH_SHA="$(unzip -p "$PATCH_OWNER" "$TARGET_ENTRY" | sha256sum | awk '{print $1}')"
  echo "installed_patch_sha256=$ACTUAL_PATCH_SHA" >> "$LOGDIR/resource-hashes.txt"
  [[ "$ACTUAL_PATCH_SHA" == "$USER_PATCHED_RESOURCE_SHA256" ]] || exit 16
fi

APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
ZIPALIGN="$(find "$ANDROID_HOME/build-tools" -type f -name zipalign | sort -V | tail -n1)"
[[ -n "$APKSIGNER" && -n "$ZIPALIGN" ]] || exit 14

if [[ "$CASE_NAME" == "official-original" ]]; then
  cp "$SELECTED"/*.apk "$SIGNED/"
else
  KEYSTORE="$WORK/test-signing.jks"
  keytool -genkeypair -noprompt -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias inotia4-ci -keyalg RSA -keysize 2048 -validity 3650 \
    -dname 'CN=Inotia4 CI,OU=Testing,O=Local,L=Seoul,ST=Seoul,C=KR'

  for apk in "$SELECTED"/*.apk; do
    name="$(basename "$apk")"
    aligned="$WORK/aligned-$name"
    "$ZIPALIGN" -P 16 -f 4 "$apk" "$aligned"
    "$APKSIGNER" sign \
      --ks "$KEYSTORE" --ks-key-alias inotia4-ci \
      --ks-pass pass:android --key-pass pass:android \
      --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
      --out "$SIGNED/$name" "$aligned"
    "$APKSIGNER" verify --verbose --print-certs "$SIGNED/$name" >> "$LOGDIR/signature-verification.txt" 2>&1
  done
fi

for apk in "$SIGNED"/*.apk; do
  echo "=== $(basename "$apk") ===" >> "$LOGDIR/zipalign.txt"
  "$ZIPALIGN" -c -P 16 -v 4 "$apk" >> "$LOGDIR/zipalign.txt" 2>&1 || true
done

adb wait-for-device
adb logcat -c || true
adb uninstall "$PACKAGE" >/dev/null 2>&1 || true

mapfile -t INSTALL_APKS < <(find "$SIGNED" -maxdepth 1 -type f -name '*.apk' | sort)
printf 'Installing %d APK(s):\n' "${#INSTALL_APKS[@]}" | tee "$LOGDIR/install.txt"
printf '  %s\n' "${INSTALL_APKS[@]}" | tee -a "$LOGDIR/install.txt"
set +e
adb install-multiple -r -t "${INSTALL_APKS[@]}" 2>&1 | tee -a "$LOGDIR/install.txt"
INSTALL_RC=${PIPESTATUS[0]}
set -e
if [[ $INSTALL_RC -ne 0 ]]; then
  adb logcat -d -v threadtime > "$LOGDIR/logcat-install-failure.txt" || true
  echo "RESULT=$CASE_NAME INSTALL_FAIL rc=$INSTALL_RC" | tee "$LOGDIR/summary.txt"
  exit 20
fi

adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb logcat -c || true

RESOLVED_ACTIVITY="$(adb shell cmd package resolve-activity --brief "$PACKAGE" 2>/dev/null | tr -d '\r' | tail -n1 || true)"
echo "resolved_activity=$RESOLVED_ACTIVITY" | tee "$LOGDIR/activity.txt"
if [[ -z "$RESOLVED_ACTIVITY" || "$RESOLVED_ACTIVITY" != "$PACKAGE"* ]]; then
  echo "RESULT=$CASE_NAME NO_LAUNCHER_ACTIVITY" | tee "$LOGDIR/summary.txt"
  exit 21
fi

set +e
adb shell am start -W -n "$RESOLVED_ACTIVITY" 2>&1 | tee "$LOGDIR/am-start.txt"
START_RC=${PIPESTATUS[0]}
set -e
if [[ $START_RC -ne 0 ]]; then
  echo "RESULT=$CASE_NAME START_FAIL rc=$START_RC" | tee "$LOGDIR/summary.txt"
  exit 22
fi

# Android may show its own one-time immersive/full-screen education overlay.
# Attempt to dismiss it so that foreground checks measure the game itself.
sleep 2
adb shell input keyevent 66 >/dev/null 2>&1 || true
adb shell input tap 900 1750 >/dev/null 2>&1 || true

sample_state() {
  local tag="$1"
  local pid resumed focus
  pid="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
  resumed="$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity' || true)"
  focus="$(adb shell dumpsys window windows 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp' || true)"
  printf 'pid_%s=%s\nresumed_%s=%s\nfocus_%s=%s\n' "$tag" "$pid" "$tag" "$resumed" "$tag" "$focus" | tee "$LOGDIR/state-$tag.txt"
  adb shell dumpsys activity activities > "$LOGDIR/dumpsys-$tag.txt" || true
  adb exec-out screencap -p > "$LOGDIR/screenshot-$tag.png" || true
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/window.xml "$LOGDIR/window-$tag.xml" >/dev/null 2>&1 || true
  if [[ -z "$pid" ]]; then return 1; fi
  if [[ "$resumed" != *"$PACKAGE"* && "$focus" != *"$PACKAGE"* ]]; then return 2; fi
  return 0
}

sleep 8
sample_state 10s || STATE10_RC=$?
STATE10_RC=${STATE10_RC:-0}
sleep 20
sample_state 30s || STATE30_RC=$?
STATE30_RC=${STATE30_RC:-0}
sleep 30
sample_state 60s || STATE60_RC=$?
STATE60_RC=${STATE60_RC:-0}

adb logcat -d -v threadtime > "$LOGDIR/logcat.txt" || true
FATAL_LINES="$(grep -E 'FATAL EXCEPTION|Fatal signal|SIGSEGV|SIGABRT|ANR in|has died' "$LOGDIR/logcat.txt" | grep -Ei 'inotia4|com2us|StubApp|Hercules' || true)"
SYSTEM_OVERLAY=no
if [[ -f "$LOGDIR/window-60s.xml" ]] && grep -Eqi 'Viewing full screen|GOT IT|full.?screen education|immersive' "$LOGDIR/window-60s.xml"; then
  SYSTEM_OVERLAY=yes
fi

{
  echo "case=$CASE_NAME"
  echo "start_rc=$START_RC"
  echo "state10_rc=$STATE10_RC"
  echo "state30_rc=$STATE30_RC"
  echo "state60_rc=$STATE60_RC"
  echo "system_overlay_60s=$SYSTEM_OVERLAY"
  echo "source_resource_sha256=$SOURCE_RESOURCE_SHA256"
  echo "fatal_lines_begin"
  printf '%s\n' "$FATAL_LINES"
  echo "fatal_lines_end"
} | tee "$LOGDIR/summary.txt"

if [[ $STATE10_RC -ne 0 || $STATE30_RC -ne 0 || $STATE60_RC -ne 0 ]]; then
  echo "RESULT=$CASE_NAME FOREGROUND_FAIL" | tee -a "$LOGDIR/summary.txt"
  exit 30
fi
if [[ -n "$FATAL_LINES" ]]; then
  echo "RESULT=$CASE_NAME CRASH_LOG_FOUND" | tee -a "$LOGDIR/summary.txt"
  exit 31
fi
if [[ "$SYSTEM_OVERLAY" == yes ]]; then
  echo "RESULT=$CASE_NAME SYSTEM_OVERLAY_FALSE_POSITIVE" | tee -a "$LOGDIR/summary.txt"
  exit 32
fi

if [[ "$CASE_NAME" == "patched-999" ]]; then
  mkdir -p "$LOGDIR/tested-apks"
  cp "$SIGNED"/*.apk "$LOGDIR/tested-apks/"
fi

echo "RESULT=$CASE_NAME FOREGROUND_60S_OK" | tee -a "$LOGDIR/summary.txt"
