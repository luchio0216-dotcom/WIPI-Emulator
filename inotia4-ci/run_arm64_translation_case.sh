#!/usr/bin/env bash
set -euo pipefail

CASE_NAME="${1:?case name required}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CI_DIR="$ROOT_DIR/inotia4-ci"
WORK="$RUNNER_TEMP/inotia4-arm64-$CASE_NAME"
LOGDIR="$CI_DIR/arm64-translation-results/$CASE_NAME"
TARGET_ENTRY="assets/common/game_res/memorytext_e.dat.jpg"
PACKAGE="com.com2us.inotia4.normal.freefull.google.global.android.common"
EXACT_APK_URL="https://api.vkxiazai.com/down/19006"
EXACT_APK_REFERER="https://www.vkxiazai.com/game/19006.html"
EXACT_APK_MD5="5cd0d4f745e3f038387b5328cd52fa65"
EXACT_APK_SHA256="36b0e5502f53f86bf0689f438a433dce740612b60a51b8a38a170fe3575fabc5"
ORIGINAL_RESOURCE_SHA256="4e674cc35edf276e466c66b8c7562586f9d7c5e46bbb712a90bc8945c0705a22"
PATCHED_RESOURCE_SHA256="75bb655c8c2cd3e89a28e8b90e1e6d1d7900f26521dc5aece8572964079cb3ee"

rm -rf "$WORK"
mkdir -p "$WORK" "$LOGDIR"
EXACT_APK="$RUNNER_TEMP/inotia4-exact-user-match.apk"
if [[ ! -s "$EXACT_APK" ]]; then
  curl -fL --retry 4 --retry-delay 3 -A 'Mozilla/5.0 GitHub-Actions-Inotia4-CI' -e "$EXACT_APK_REFERER" "$EXACT_APK_URL" -o "$EXACT_APK"
fi

ACTUAL_MD5="$(md5sum "$EXACT_APK" | awk '{print $1}')"
ACTUAL_SHA256="$(sha256sum "$EXACT_APK" | awk '{print $1}')"
printf 'exact_source_md5=%s\nexact_source_sha256=%s\n' "$ACTUAL_MD5" "$ACTUAL_SHA256" | tee "$LOGDIR/source-fingerprint.txt"
[[ "$ACTUAL_MD5" == "$EXACT_APK_MD5" && "$ACTUAL_SHA256" == "$EXACT_APK_SHA256" ]] || exit 11

unzip -p "$EXACT_APK" "$TARGET_ENTRY" > "$WORK/original-resource"
SOURCE_RESOURCE_SHA256="$(sha256sum "$WORK/original-resource" | awk '{print $1}')"
echo "source_resource_sha256=$SOURCE_RESOURCE_SHA256" | tee "$LOGDIR/resource-hashes.txt"
[[ "$SOURCE_RESOURCE_SHA256" == "$ORIGINAL_RESOURCE_SHA256" ]] || exit 15

if [[ "$CASE_NAME" == "exact-original" ]]; then
  SIGNED="$EXACT_APK"
  echo "signature_mode=original-untouched" > "$LOGDIR/signature.txt"
else
  UNSIGNED="$WORK/unsigned.apk"
  cp "$EXACT_APK" "$UNSIGNED"
  zip -q -d "$UNSIGNED" 'META-INF/*.RSA' 'META-INF/*.DSA' 'META-INF/*.EC' 'META-INF/*.SF' 'META-INF/*.MF' >/dev/null 2>&1 || true

  if [[ "$CASE_NAME" == "patched-999" ]]; then
    base64 -d "$CI_DIR/patches/memorytext_e.dat.jpg.b64" > "$WORK/patched-resource"
    [[ "$(sha256sum "$WORK/patched-resource" | awk '{print $1}')" == "$PATCHED_RESOURCE_SHA256" ]] || exit 13
    zip -q -d "$UNSIGNED" "$TARGET_ENTRY"
    mkdir -p "$WORK/inject/assets/common/game_res"
    cp "$WORK/patched-resource" "$WORK/inject/$TARGET_ENTRY"
    (cd "$WORK/inject" && zip -q -0 "$UNSIGNED" "$TARGET_ENTRY")
    INSTALLED_PATCH_SHA="$(unzip -p "$UNSIGNED" "$TARGET_ENTRY" | sha256sum | awk '{print $1}')"
    echo "installed_patch_sha256=$INSTALLED_PATCH_SHA" | tee -a "$LOGDIR/resource-hashes.txt"
    [[ "$INSTALLED_PATCH_SHA" == "$PATCHED_RESOURCE_SHA256" ]] || exit 16
  fi

  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
  ZIPALIGN="$(find "$ANDROID_HOME/build-tools" -type f -name zipalign | sort -V | tail -n1)"
  KEYSTORE="$RUNNER_TEMP/inotia4-ci-signing.jks"
  if [[ ! -f "$KEYSTORE" ]]; then
    keytool -genkeypair -noprompt -keystore "$KEYSTORE" -storepass android -keypass android -alias inotia4-ci -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Inotia4 CI,OU=Testing,O=Local,L=Seoul,ST=Seoul,C=KR'
  fi
  ALIGNED="$WORK/aligned.apk"
  SIGNED="$WORK/signed.apk"
  "$ZIPALIGN" -P 16 -f 4 "$UNSIGNED" "$ALIGNED"
  "$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias inotia4-ci --ks-pass pass:android --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out "$SIGNED" "$ALIGNED"
  "$APKSIGNER" verify --verbose --print-certs "$SIGNED" > "$LOGDIR/signature.txt" 2>&1
fi

if [[ "$CASE_NAME" == "patched-999" ]]; then
  mkdir -p "$LOGDIR/tested-apks"
  cp "$SIGNED" "$LOGDIR/tested-apks/Inotia4_Berserker_Buffs_999s_arm64_candidate.apk"
  sha256sum "$LOGDIR/tested-apks/Inotia4_Berserker_Buffs_999s_arm64_candidate.apk" > "$LOGDIR/tested-apks/sha256.txt"
fi

{
  adb shell getprop ro.product.cpu.abi
  adb shell getprop ro.product.cpu.abilist
  adb shell getprop ro.dalvik.vm.native.bridge
} | tee "$LOGDIR/emulator-abi.txt"

adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
adb logcat -c || true
set +e
adb install -r -t "$SIGNED" 2>&1 | tee "$LOGDIR/install.txt"
INSTALL_RC=${PIPESTATUS[0]}
set -e
[[ $INSTALL_RC -eq 0 ]] || exit 20

ACTIVITY="$(adb shell cmd package resolve-activity --brief "$PACKAGE" 2>/dev/null | tr -d '\r' | tail -n1 || true)"
echo "resolved_activity=$ACTIVITY" | tee "$LOGDIR/activity.txt"
[[ "$ACTIVITY" == "$PACKAGE"* ]] || exit 21
set +e
timeout 10s adb shell am start -n "$ACTIVITY" 2>&1 | tee "$LOGDIR/am-start.txt"
START_RC=${PIPESTATUS[0]}
set -e
[[ $START_RC -eq 0 || $START_RC -eq 124 ]] || exit 22

: > "$LOGDIR/process-timeline.txt"
for tick in 0 1 2 3 4 5 6; do
  {
    echo "tick_${tick}_pid=$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
    echo "tick_${tick}_resumed=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity' || true)"
    echo "tick_${tick}_focus=$(adb shell dumpsys window windows 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp' || true)"
  } >> "$LOGDIR/process-timeline.txt"
  sleep 0.5
done
cat "$LOGDIR/process-timeline.txt"
adb shell input keyevent 66 >/dev/null 2>&1 || true
adb shell input tap 900 1750 >/dev/null 2>&1 || true

sample_state() {
  local tag="$1" pid resumed focus
  pid="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
  resumed="$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity' || true)"
  focus="$(adb shell dumpsys window windows 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp' || true)"
  printf 'pid_%s=%s\nresumed_%s=%s\nfocus_%s=%s\n' "$tag" "$pid" "$tag" "$resumed" "$tag" "$focus" | tee "$LOGDIR/state-$tag.txt"
  adb exec-out screencap -p > "$LOGDIR/screenshot-$tag.png" || true
  [[ -n "$pid" ]] || return 1
  [[ "$resumed" == *"$PACKAGE"* || "$focus" == *"$PACKAGE"* ]] || return 2
}

sleep 3
R5=0; sample_state 5s || R5=$?
sleep 5
R10=0; sample_state 10s || R10=$?
sleep 20
R30=0; sample_state 30s || R30=$?
sleep 30
R60=0; sample_state 60s || R60=$?
adb logcat -d -b all -v threadtime > "$LOGDIR/logcat.txt" || true
grep -Ei 'FATAL EXCEPTION|Fatal signal|SIGSEGV|SIGABRT|ANR in|has died|am_crash|am_kill|linker|dlopen|UnsatisfiedLinkError|SecurityException|signature|certificate|StubApp|Hercules|libgame|jiagu|com2us|inotia4' "$LOGDIR/logcat.txt" | tail -n 500 > "$LOGDIR/launch-diagnostics.txt" || true
echo "diagnostic_lines_begin"
cat "$LOGDIR/launch-diagnostics.txt"
echo "diagnostic_lines_end"
FATAL="$(grep -E 'FATAL EXCEPTION|Fatal signal|SIGSEGV|SIGABRT|ANR in|has died' "$LOGDIR/logcat.txt" | grep -Ei 'inotia4|com2us|StubApp|Hercules|libgame|jiagu' || true)"
SCREENSHOT_OK=yes
for shot in "$LOGDIR"/screenshot-*.png; do
  [[ -s "$shot" && $(stat -c %s "$shot") -ge 20000 ]] || SCREENSHOT_OK=no
done
{
  echo "case=$CASE_NAME"
  echo "state5_rc=$R5"
  echo "state10_rc=$R10"
  echo "state30_rc=$R30"
  echo "state60_rc=$R60"
  echo "screenshots_nonempty=$SCREENSHOT_OK"
  echo "fatal_lines_begin"
  printf '%s\n' "$FATAL"
  echo "fatal_lines_end"
} | tee "$LOGDIR/summary.txt"

[[ $R5 -eq 0 && $R10 -eq 0 && $R30 -eq 0 && $R60 -eq 0 ]] || { echo "RESULT=$CASE_NAME FOREGROUND_FAIL" | tee -a "$LOGDIR/summary.txt"; exit 30; }
[[ -z "$FATAL" ]] || { echo "RESULT=$CASE_NAME CRASH_LOG_FOUND" | tee -a "$LOGDIR/summary.txt"; exit 31; }
[[ "$SCREENSHOT_OK" == yes ]] || { echo "RESULT=$CASE_NAME SCREENSHOT_FAIL" | tee -a "$LOGDIR/summary.txt"; exit 33; }
echo "RESULT=$CASE_NAME ARM64_TRANSLATION_60S_OK" | tee -a "$LOGDIR/summary.txt"
