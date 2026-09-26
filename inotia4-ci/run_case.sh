#!/usr/bin/env bash
set -euo pipefail

CASE_NAME="${1:?case name required}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CI_DIR="$ROOT_DIR/inotia4-ci"
WORK="$RUNNER_TEMP/inotia4-$CASE_NAME"
LOGDIR="$CI_DIR/test-results/$CASE_NAME"
TARGET_ENTRY="assets/common/game_res/memorytext_e.dat.jpg"
PACKAGE="com.com2us.inotia4.normal.freefull.google.global.android.common"

# This is the exact v1.3.9 APK supplied in chat, identified independently by
# its whole-file MD5/SHA-256. The public page advertises the same MD5.
EXACT_APK_URL="https://api.vkxiazai.com/down/19006"
EXACT_APK_REFERER="https://www.vkxiazai.com/game/19006.html"
EXACT_APK_MD5="650cf86aac816e651a56c8642b03afa7"
EXACT_APK_SHA256="e16e474049ddd258787c48db51401c59bff69a4718d5594409c4520ecdcb4a64"
USER_ORIGINAL_RESOURCE_SHA256="4e674cc35edf276e466c66b8c7562586f9d7c5e46bbb712a90bc8945c0705a22"
USER_PATCHED_RESOURCE_SHA256="75bb655c8c2cd3e89a28e8b90e1e6d1d7900f26521dc5aece8572964079cb3ee"

# APKPure is used only to obtain the x86 native libraries needed by GitHub's
# accelerated API-28 x86 emulator. Game resources/classes come from the exact
# user-matching APK above.
XAPK_URL="https://d.apkpure.net/b/XAPK/com.com2us.inotia4.normal.freefull.google.global.android.common?versionCode=139004"

rm -rf "$WORK"
mkdir -p "$WORK" "$LOGDIR"

EXACT_APK="$RUNNER_TEMP/inotia4-exact-user-match.apk"
if [[ ! -s "$EXACT_APK" ]]; then
  echo "Downloading exact user-matching Inotia4 1.3.9 APK..."
  curl -fL --retry 4 --retry-delay 3 \
    -A 'Mozilla/5.0 GitHub-Actions-Inotia4-CI' \
    -e "$EXACT_APK_REFERER" \
    "$EXACT_APK_URL" -o "$EXACT_APK"
fi

ACTUAL_MD5="$(md5sum "$EXACT_APK" | awk '{print $1}')"
ACTUAL_SHA256="$(sha256sum "$EXACT_APK" | awk '{print $1}')"
{
  echo "exact_source_md5=$ACTUAL_MD5"
  echo "expected_source_md5=$EXACT_APK_MD5"
  echo "exact_source_sha256=$ACTUAL_SHA256"
  echo "expected_source_sha256=$EXACT_APK_SHA256"
} | tee "$LOGDIR/source-fingerprint.txt"
if [[ "$ACTUAL_MD5" != "$EXACT_APK_MD5" || "$ACTUAL_SHA256" != "$EXACT_APK_SHA256" ]]; then
  echo "RESULT=$CASE_NAME EXACT_SOURCE_MISMATCH" | tee "$LOGDIR/summary.txt"
  exit 11
fi

unzip -p "$EXACT_APK" "$TARGET_ENTRY" > "$WORK/original-memorytext_e.dat.jpg"
SOURCE_RESOURCE_SHA256="$(sha256sum "$WORK/original-memorytext_e.dat.jpg" | awk '{print $1}')"
{
  echo "source_resource_sha256=$SOURCE_RESOURCE_SHA256"
  echo "user_original_resource_sha256=$USER_ORIGINAL_RESOURCE_SHA256"
  echo "user_patched_resource_sha256=$USER_PATCHED_RESOURCE_SHA256"
  [[ "$SOURCE_RESOURCE_SHA256" == "$USER_ORIGINAL_RESOURCE_SHA256" ]] && echo "resource_fixture_match=yes" || echo "resource_fixture_match=no"
} | tee "$LOGDIR/resource-hashes.txt"
if [[ "$SOURCE_RESOURCE_SHA256" != "$USER_ORIGINAL_RESOURCE_SHA256" ]]; then
  echo "RESULT=$CASE_NAME FIXTURE_MISMATCH" | tee "$LOGDIR/summary.txt"
  exit 15
fi

UNSIGNED="$WORK/unsigned.apk"
cp "$EXACT_APK" "$UNSIGNED"

# Remove old signing metadata before any repack/sign operation.
zip -q -d "$UNSIGNED" 'META-INF/*.RSA' 'META-INF/*.DSA' 'META-INF/*.EC' 'META-INF/*.SF' 'META-INF/*.MF' >/dev/null 2>&1 || true

if [[ "$CASE_NAME" == "patched-999" ]]; then
  base64 -d "$CI_DIR/patches/memorytext_e.dat.jpg.b64" > "$WORK/patched-memorytext_e.dat.jpg"
  PATCH_SHA="$(sha256sum "$WORK/patched-memorytext_e.dat.jpg" | awk '{print $1}')"
  if [[ "$PATCH_SHA" != "$USER_PATCHED_RESOURCE_SHA256" ]]; then
    echo "RESULT=$CASE_NAME PATCH_FIXTURE_BAD_SHA" | tee "$LOGDIR/summary.txt"
    exit 13
  fi
  zip -q -d "$UNSIGNED" "$TARGET_ENTRY"
  mkdir -p "$WORK/inject/assets/common/game_res"
  cp "$WORK/patched-memorytext_e.dat.jpg" "$WORK/inject/$TARGET_ENTRY"
  (
    cd "$WORK/inject"
    zip -q -0 "$UNSIGNED" "$TARGET_ENTRY"
  )
  INSTALLED_PATCH_SHA="$(unzip -p "$UNSIGNED" "$TARGET_ENTRY" | sha256sum | awk '{print $1}')"
  echo "installed_patch_sha256=$INSTALLED_PATCH_SHA" | tee -a "$LOGDIR/resource-hashes.txt"
  if [[ "$INSTALLED_PATCH_SHA" != "$USER_PATCHED_RESOURCE_SHA256" ]]; then
    echo "RESULT=$CASE_NAME PATCH_INJECTION_BAD_SHA" | tee "$LOGDIR/summary.txt"
    exit 16
  fi
fi

APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n1)"
ZIPALIGN="$(find "$ANDROID_HOME/build-tools" -type f -name zipalign | sort -V | tail -n1)"
[[ -n "$APKSIGNER" && -n "$ZIPALIGN" ]] || exit 14

KEYSTORE="$RUNNER_TEMP/inotia4-ci-signing.jks"
if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair -noprompt -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias inotia4-ci -keyalg RSA -keysize 2048 -validity 3650 \
    -dname 'CN=Inotia4 CI,OU=Testing,O=Local,L=Seoul,ST=Seoul,C=KR'
fi

sign_apk() {
  local input="$1" output="$2"
  local aligned="$WORK/aligned-$(basename "$output")"
  "$ZIPALIGN" -P 16 -f 4 "$input" "$aligned"
  "$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-key-alias inotia4-ci \
    --ks-pass pass:android --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$output" "$aligned"
  "$APKSIGNER" verify --verbose --print-certs "$output" >> "$LOGDIR/signature-verification.txt" 2>&1
  "$ZIPALIGN" -c -P 16 -v 4 "$output" >> "$LOGDIR/zipalign.txt" 2>&1
}

# Build the real arm64 artifact from the exact matching source. This is the APK
# that will ultimately be handed to the Fold7, but it is not falsely labelled
# runtime-verified until the surrogate runtime checks below also pass.
if [[ "$CASE_NAME" == "patched-999" ]]; then
  mkdir -p "$LOGDIR/tested-apks"
  ARM_CANDIDATE="$LOGDIR/tested-apks/Inotia4_Berserker_Buffs_999s_arm64_candidate.apk"
  sign_apk "$UNSIGNED" "$ARM_CANDIDATE"
  sha256sum "$ARM_CANDIDATE" | tee "$LOGDIR/tested-apks/sha256.txt"
fi

# GitHub's accelerated runner is x86. Transplant only x86 native libraries
# from the official 1.3.9 bundle while retaining the exact user's classes,
# manifest and game resources. This is a runtime surrogate, never the Fold7 APK.
XAPK="$RUNNER_TEMP/inotia4-official-139004.xapk"
if [[ ! -s "$XAPK" ]]; then
  curl -fL --retry 5 --retry-delay 3 -A 'Mozilla/5.0 GitHub-Actions-Inotia4-CI' "$XAPK_URL" -o "$XAPK"
fi
XAPK_DIR="$RUNNER_TEMP/inotia4-official-139004"
if [[ ! -d "$XAPK_DIR" ]]; then
  mkdir -p "$XAPK_DIR"
  unzip -q -o "$XAPK" -d "$XAPK_DIR"
fi
X86_SPLIT=""
while IFS= read -r apk; do
  if unzip -Z1 "$apk" | grep -q '^lib/x86/'; then X86_SPLIT="$apk"; break; fi
done < <(find "$XAPK_DIR" -type f -name '*.apk' | sort)
if [[ -z "$X86_SPLIT" ]]; then
  echo "RESULT=$CASE_NAME NO_X86_SURROGATE_LIBS" | tee "$LOGDIR/summary.txt"
  exit 17
fi

echo "x86_split=$X86_SPLIT" | tee "$LOGDIR/runtime-surrogate.txt"
SURROGATE_UNSIGNED="$WORK/surrogate-unsigned.apk"
cp "$UNSIGNED" "$SURROGATE_UNSIGNED"
zip -q -d "$SURROGATE_UNSIGNED" 'lib/arm64-v8a/*' 'lib/armeabi-v7a/*' 'lib/x86/*' 'lib/x86_64/*' >/dev/null 2>&1 || true
mkdir -p "$WORK/x86lib"
unzip -q -o "$X86_SPLIT" 'lib/x86/*' -d "$WORK/x86lib"
if ! find "$WORK/x86lib/lib/x86" -type f | grep -q .; then
  echo "RESULT=$CASE_NAME X86_LIB_EXTRACTION_FAIL" | tee "$LOGDIR/summary.txt"
  exit 18
fi
(
  cd "$WORK/x86lib"
  zip -q -0 -r "$SURROGATE_UNSIGNED" lib/x86
)
SURROGATE="$WORK/surrogate-signed.apk"
sign_apk "$SURROGATE_UNSIGNED" "$SURROGATE"

adb wait-for-device
adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
adb logcat -c || true
set +e
adb install -r -t "$SURROGATE" 2>&1 | tee "$LOGDIR/install.txt"
INSTALL_RC=${PIPESTATUS[0]}
set -e
if [[ $INSTALL_RC -ne 0 ]]; then
  adb logcat -d -v threadtime > "$LOGDIR/logcat-install-failure.txt" || true
  echo "RESULT=$CASE_NAME INSTALL_FAIL rc=$INSTALL_RC" | tee "$LOGDIR/summary.txt"
  exit 20
fi

RESOLVED_ACTIVITY="$(adb shell cmd package resolve-activity --brief "$PACKAGE" 2>/dev/null | tr -d '\r' | tail -n1 || true)"
echo "resolved_activity=$RESOLVED_ACTIVITY" | tee "$LOGDIR/activity.txt"
if [[ -z "$RESOLVED_ACTIVITY" || "$RESOLVED_ACTIVITY" != "$PACKAGE"* ]]; then
  echo "RESULT=$CASE_NAME NO_LAUNCHER_ACTIVITY" | tee "$LOGDIR/summary.txt"
  exit 21
fi

adb logcat -c || true
set +e
adb shell am start -W -n "$RESOLVED_ACTIVITY" 2>&1 | tee "$LOGDIR/am-start.txt"
START_RC=${PIPESTATUS[0]}
set -e
if [[ $START_RC -ne 0 ]]; then
  echo "RESULT=$CASE_NAME START_FAIL rc=$START_RC" | tee "$LOGDIR/summary.txt"
  exit 22
fi

# Dismiss Android's one-time immersive-mode education overlay if it appears.
sleep 2
adb shell input keyevent 66 >/dev/null 2>&1 || true
adb shell input tap 900 1750 >/dev/null 2>&1 || true

sample_state() {
  local tag="$1" pid resumed focus
  pid="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
  resumed="$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity' || true)"
  focus="$(adb shell dumpsys window windows 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp' || true)"
  printf 'pid_%s=%s\nresumed_%s=%s\nfocus_%s=%s\n' "$tag" "$pid" "$tag" "$resumed" "$tag" "$focus" | tee "$LOGDIR/state-$tag.txt"
  adb shell dumpsys activity activities > "$LOGDIR/dumpsys-$tag.txt" || true
  adb exec-out screencap -p > "$LOGDIR/screenshot-$tag.png" || true
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/window.xml "$LOGDIR/window-$tag.xml" >/dev/null 2>&1 || true
  [[ -n "$pid" ]] || return 1
  [[ "$resumed" == *"$PACKAGE"* || "$focus" == *"$PACKAGE"* ]] || return 2
  return 0
}

sleep 8
STATE10_RC=0; sample_state 10s || STATE10_RC=$?
sleep 20
STATE30_RC=0; sample_state 30s || STATE30_RC=$?
sleep 30
STATE60_RC=0; sample_state 60s || STATE60_RC=$?
adb logcat -d -v threadtime > "$LOGDIR/logcat.txt" || true

FATAL_LINES="$(grep -E 'FATAL EXCEPTION|Fatal signal|SIGSEGV|SIGABRT|ANR in|has died' "$LOGDIR/logcat.txt" | grep -Ei 'inotia4|com2us|StubApp|Hercules|libgame' || true)"
SYSTEM_OVERLAY=no
if [[ -f "$LOGDIR/window-60s.xml" ]] && grep -Eqi 'Viewing full screen|GOT IT|full.?screen education|immersive' "$LOGDIR/window-60s.xml"; then SYSTEM_OVERLAY=yes; fi

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

echo "RESULT=$CASE_NAME FOREGROUND_60S_OK" | tee -a "$LOGDIR/summary.txt"
