#!/usr/bin/env bash
set -euo pipefail

CASE_NAME="${1:?case name required}"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
CI_DIR="$ROOT_DIR/inotia4-ci"
WORK="$RUNNER_TEMP/inotia4-$CASE_NAME"
LOGDIR="$CI_DIR/test-results/$CASE_NAME"
TARGET_ENTRY="assets/common/game_res/memorytext_e.dat.jpg"
PACKAGE="com.com2us.inotia4.normal.freefull.google.global.android.common"

# Exact source selected by the user in chat: andy4_19006.apk.
# Local verification of that uploaded file:
#   MD5    5cd0d4f745e3f038387b5328cd52fa65
#   SHA256 36b0e5502f53f86bf0689f438a433dce740612b60a51b8a38a170fe3575fabc5
# The download endpoint below currently returns the byte-identical APK, so CI
# accepts it only when BOTH whole-file hashes match these pinned values.
EXACT_APK_URL="https://api.vkxiazai.com/down/19006"
EXACT_APK_REFERER="https://www.vkxiazai.com/game/19006.html"
EXACT_APK_MD5="5cd0d4f745e3f038387b5328cd52fa65"
EXACT_APK_SHA256="36b0e5502f53f86bf0689f438a433dce740612b60a51b8a38a170fe3575fabc5"
USER_ORIGINAL_RESOURCE_SHA256="4e674cc35edf276e466c66b8c7562586f9d7c5e46bbb712a90bc8945c0705a22"
USER_PATCHED_RESOURCE_SHA256="75bb655c8c2cd3e89a28e8b90e1e6d1d7900f26521dc5aece8572964079cb3ee"

# APKPure is used only to obtain the x86 native libraries needed by GitHub's
# accelerated API-28 x86 emulator. Game resources/classes come from the exact
# user-selected APK above.
XAPK_URL="https://d.apkpure.net/b/XAPK/com.com2us.inotia4.normal.freefull.google.global.android.common?versionCode=139004"

rm -rf "$WORK"
mkdir -p "$WORK" "$LOGDIR"

EXACT_APK="$RUNNER_TEMP/inotia4-exact-user-match.apk"
if [[ ! -s "$EXACT_APK" ]]; then
  echo "Downloading byte-identical copy of user-selected andy4_19006.apk..."
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

# Inventory hidden protector payloads. The exact build is Jiagu-protected and
# may carry ABI payloads outside lib/, so inspect every archive member rather
# than assuming the visible arm64 directory is the whole native payload.
if [[ "$CASE_NAME" == "resigned-control" ]]; then
  mkdir -p "$WORK/exact-inventory"
  unzip -q -o "$EXACT_APK" -d "$WORK/exact-inventory"
  {
    echo "jiagu_named_entries_begin"
    find "$WORK/exact-inventory" -type f | sed "s#^$WORK/exact-inventory/##" | grep -Ei 'jiagu|hercules|ogm|\.so
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

# Build the real arm64 artifact from the exact user-selected source. This is the
# APK intended for Fold7 testing after the launch-surrogate checks pass.
if [[ "$CASE_NAME" == "patched-999" ]]; then
  mkdir -p "$LOGDIR/tested-apks"
  ARM_CANDIDATE="$LOGDIR/tested-apks/Inotia4_Berserker_Buffs_999s_arm64_candidate.apk"
  sign_apk "$UNSIGNED" "$ARM_CANDIDATE"
  sha256sum "$ARM_CANDIDATE" | tee "$LOGDIR/tested-apks/sha256.txt"
fi

# GitHub's accelerated runner is x86. Transplant only x86 native libraries
# from official 1.3.9 while retaining the selected APK's classes, manifest and
# game resources. This surrogate is used only for automated launch checking.
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

OFFICIAL_BASE=""
while IFS= read -r apk; do
  name="$(basename "$apk")"
  if [[ "$name" != config.*.apk && "$name" != split_config.*.apk ]]; then
    OFFICIAL_BASE="$apk"
    break
  fi
done < <(find "$XAPK_DIR" -type f -name '*.apk' | sort)
if [[ -z "$OFFICIAL_BASE" ]]; then
  OFFICIAL_BASE="$(find "$XAPK_DIR" -type f -name '*.apk' -printf '%s %p\n' | sort -nr | head -n1 | cut -d' ' -f2-)"
fi

{
  echo "x86_split=$X86_SPLIT"
  echo "official_base=$OFFICIAL_BASE"
  for entry in classes.dex AndroidManifest.xml resources.arsc; do
    exact_hash="$(unzip -p "$EXACT_APK" "$entry" 2>/dev/null | sha256sum | awk '{print $1}')"
    official_hash="$(unzip -p "$OFFICIAL_BASE" "$entry" 2>/dev/null | sha256sum | awk '{print $1}')"
    safe_entry="$(printf '%s' "$entry" | tr -c 'A-Za-z0-9' '_')"
    echo "exact_${safe_entry}_sha256=$exact_hash"
    echo "official_${safe_entry}_sha256=$official_hash"
  done
  echo "exact_native_entries_begin"
  unzip -Z1 "$EXACT_APK" | grep '^lib/' | sort || true
  echo "exact_native_entries_end"
  echo "official_x86_entries_begin"
  unzip -Z1 "$X86_SPLIT" | grep '^lib/x86/' | sort || true
  echo "official_x86_entries_end"
} | tee "$LOGDIR/runtime-surrogate.txt"

# Establish whether the official 1.3.9 x86 package itself survives on this
# emulator. This is diagnostic only and never relaxes the exact-source checks.
if [[ "$CASE_NAME" == "resigned-control" ]]; then
  mapfile -t OFFICIAL_APKS < <(find "$XAPK_DIR" -type f -name '*.apk' | sort)
  adb wait-for-device
  adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
  adb logcat -c || true
  set +e
  adb install-multiple -r -t "${OFFICIAL_APKS[@]}" 2>&1 | tee "$LOGDIR/official-x86-install.txt"
  OFFICIAL_INSTALL_RC=${PIPESTATUS[0]}
  set -e
  if [[ $OFFICIAL_INSTALL_RC -eq 0 ]]; then
    OFFICIAL_ACTIVITY="$(adb shell cmd package resolve-activity --brief "$PACKAGE" 2>/dev/null | tr -d '\r' | tail -n1 || true)"
    timeout 10s adb shell am start -n "$OFFICIAL_ACTIVITY" > "$LOGDIR/official-x86-am-start.txt" 2>&1 || true
    sleep 8
    {
      echo "official_pid=$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
      echo "official_resumed=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity' || true)"
      echo "official_focus=$(adb shell dumpsys window windows 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp' || true)"
    } | tee "$LOGDIR/official-x86-state.txt"
    adb exec-out screencap -p > "$LOGDIR/official-x86-screenshot.png" || true
    adb logcat -d -b all -v threadtime > "$LOGDIR/official-x86-logcat.txt" || true
  else
    echo "official_install_rc=$OFFICIAL_INSTALL_RC" | tee "$LOGDIR/official-x86-state.txt"
  fi
  adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
fi

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

# The exact protected classes currently request an absolute
# data/data/<package>/.jiagu/libjiagu.so path, whereas the official x86 package
# stays alive. Probe whether either official x86 library can act as that runtime
# payload. This is diagnostic only: the strict validation below is reinstalled
# clean and cannot pass because of this probe.
if [[ "$CASE_NAME" == "resigned-control" ]]; then
  adb root >/dev/null 2>&1 || true
  adb wait-for-device || true
  UID_NUM="$(adb shell dumpsys package "$PACKAGE" 2>/dev/null | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' | head -n1 | tr -d '\r')"
  NATIVE_DIR="$(adb shell dumpsys package "$PACKAGE" 2>/dev/null | sed -n 's/.*nativeLibraryDir=\([^ ]*\).*/\1/p' | head -n1 | tr -d '\r')"
  {
    echo "uid=$UID_NUM"
    echo "native_dir=$NATIVE_DIR"
    adb shell ls -la "$NATIVE_DIR" 2>&1 || true
  } | tee "$LOGDIR/jiagu-preseed-environment.txt"

  for candidate in libHercules.so libgame.so; do
    adb shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
    adb shell rm -rf "/data/data/$PACKAGE/.jiagu" >/dev/null 2>&1 || true
    adb shell mkdir -p "/data/data/$PACKAGE/.jiagu" || true
    adb shell cp "$NATIVE_DIR/$candidate" "/data/data/$PACKAGE/.jiagu/libjiagu.so" || true
    adb shell chmod 755 "/data/data/$PACKAGE/.jiagu/libjiagu.so" || true
    if [[ -n "$UID_NUM" ]]; then
      adb shell chown "$UID_NUM:$UID_NUM" "/data/data/$PACKAGE/.jiagu" "/data/data/$PACKAGE/.jiagu/libjiagu.so" || true
    fi
    adb logcat -c || true
    timeout 10s adb shell am start -n "$PACKAGE/.MainActivity" > "$LOGDIR/jiagu-preseed-$candidate-start.txt" 2>&1 || true
    sleep 5
    {
      echo "candidate=$candidate"
      echo "pid=$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
      echo "resumed=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity' || true)"
      echo "focus=$(adb shell dumpsys window windows 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp' || true)"
      adb logcat -d -b all -v threadtime | grep -Ei 'FATAL EXCEPTION|Fatal signal|dlopen failed|UnsatisfiedLinkError|has died|jiagu|Hercules|libgame' | tail -n 120 || true
    } | tee "$LOGDIR/jiagu-preseed-$candidate-result.txt"
  done

  # Restore a clean install before the strict control validation.
  adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
  adb install -r -t "$SURROGATE" > "$LOGDIR/reinstall-after-jiagu-probe.txt" 2>&1
fi

RESOLVED_ACTIVITY="$(adb shell cmd package resolve-activity --brief "$PACKAGE" 2>/dev/null | tr -d '\r' | tail -n1 || true)"
echo "resolved_activity=$RESOLVED_ACTIVITY" | tee "$LOGDIR/activity.txt"
if [[ -z "$RESOLVED_ACTIVITY" || "$RESOLVED_ACTIVITY" != "$PACKAGE"* ]]; then
  echo "RESULT=$CASE_NAME NO_LAUNCHER_ACTIVITY" | tee "$LOGDIR/summary.txt"
  exit 21
fi

adb logcat -c || true
set +e
timeout 10s adb shell am start -n "$RESOLVED_ACTIVITY" 2>&1 | tee "$LOGDIR/am-start.txt"
START_RC=${PIPESTATUS[0]}
set -e
if [[ $START_RC -eq 124 ]]; then
  echo "am_start_timeout=10s" | tee -a "$LOGDIR/am-start.txt"
  START_RC=0
fi
if [[ $START_RC -ne 0 ]]; then
  echo "RESULT=$CASE_NAME START_FAIL rc=$START_RC" | tee "$LOGDIR/summary.txt"
  exit 22
fi

# Capture the first seconds at high resolution: protected/native apps can exit
# before the first 5-second checkpoint without emitting a Java exception.
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

# Dismiss Android's one-time immersive-mode education overlay if it appears.
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

# A launch that immediately closes is a hard failure. We sample at 5s as well
# as 10/30/60s so the CI catches the Fold7 symptom described by the user.
sleep 3
STATE5_RC=0; sample_state 5s || STATE5_RC=$?
sleep 5
STATE10_RC=0; sample_state 10s || STATE10_RC=$?
sleep 20
STATE30_RC=0; sample_state 30s || STATE30_RC=$?
sleep 30
STATE60_RC=0; sample_state 60s || STATE60_RC=$?
adb logcat -d -b all -v threadtime > "$LOGDIR/logcat.txt" || true
adb shell dumpsys package "$PACKAGE" > "$LOGDIR/dumpsys-package.txt" 2>&1 || true
adb shell dumpsys activity processes > "$LOGDIR/dumpsys-processes.txt" 2>&1 || true
adb root >/dev/null 2>&1 || true
adb wait-for-device || true
adb shell ls -la /data/tombstones > "$LOGDIR/tombstones-list.txt" 2>&1 || true
mkdir -p "$LOGDIR/tombstones"
adb pull /data/tombstones "$LOGDIR/tombstones" > "$LOGDIR/tombstones-pull.txt" 2>&1 || true

grep -Ei 'FATAL EXCEPTION|Fatal signal|SIGSEGV|SIGABRT|ANR in|has died|linker|dlopen failed|UnsatisfiedLinkError|SecurityException|signature|certificate|Hercules|StubApp|libgame|com2us|inotia4' "$LOGDIR/logcat.txt" \
  | tail -n 400 > "$LOGDIR/launch-diagnostics.txt" || true
echo "diagnostic_lines_begin"
cat "$LOGDIR/launch-diagnostics.txt"
echo "diagnostic_lines_end"

FATAL_LINES="$(grep -E 'FATAL EXCEPTION|Fatal signal|SIGSEGV|SIGABRT|ANR in|has died' "$LOGDIR/logcat.txt" | grep -Ei 'inotia4|com2us|StubApp|Hercules|libgame' || true)"
SYSTEM_OVERLAY=no
if [[ -f "$LOGDIR/window-60s.xml" ]] && grep -Eqi 'Viewing full screen|GOT IT|full.?screen education|immersive' "$LOGDIR/window-60s.xml"; then SYSTEM_OVERLAY=yes; fi

# The game is largely native-rendered, so UIAutomator cannot reliably read the
# title text. Require the captured frame to contain substantial rendered image
# data rather than a tiny/empty capture; screenshots remain attached for visual
# verification against the Google Play Games / Chinese prompt / Inotia IV title
# screens supplied by the user.
SCREENSHOT_OK=yes
for shot in "$LOGDIR/screenshot-5s.png" "$LOGDIR/screenshot-10s.png" "$LOGDIR/screenshot-30s.png" "$LOGDIR/screenshot-60s.png"; do
  if [[ ! -s "$shot" || $(stat -c %s "$shot") -lt 20000 ]]; then SCREENSHOT_OK=no; fi
done

{
  echo "case=$CASE_NAME"
  echo "start_rc=$START_RC"
  echo "state5_rc=$STATE5_RC"
  echo "state10_rc=$STATE10_RC"
  echo "state30_rc=$STATE30_RC"
  echo "state60_rc=$STATE60_RC"
  echo "system_overlay_60s=$SYSTEM_OVERLAY"
  echo "screenshots_nonempty=$SCREENSHOT_OK"
  echo "source_resource_sha256=$SOURCE_RESOURCE_SHA256"
  echo "fatal_lines_begin"
  printf '%s\n' "$FATAL_LINES"
  echo "fatal_lines_end"
} | tee "$LOGDIR/summary.txt"

if [[ $STATE5_RC -ne 0 || $STATE10_RC -ne 0 || $STATE30_RC -ne 0 || $STATE60_RC -ne 0 ]]; then
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
if [[ "$SCREENSHOT_OK" != yes ]]; then
  echo "RESULT=$CASE_NAME EMPTY_SCREENSHOT_FAIL" | tee -a "$LOGDIR/summary.txt"
  exit 33
fi

echo "RESULT=$CASE_NAME FOREGROUND_60S_OK" | tee -a "$LOGDIR/summary.txt"
 | sort || true
    echo "jiagu_named_entries_end"
    echo "elf_inventory_begin"
    while IFS= read -r file; do
      desc="$(file -b "$file" 2>/dev/null || true)"
      if [[ "$desc" == *ELF* ]]; then
        rel="${file#"$WORK/exact-inventory/"}"
        printf '%s | %s\n' "$rel" "$desc"
      fi
    done < <(find "$WORK/exact-inventory" -type f)
    echo "elf_inventory_end"
    echo "jiagu_string_hits_begin"
    grep -RIna -m 20 -E 'libjiagu|\.jiagu' "$WORK/exact-inventory" 2>/dev/null || true
    echo "jiagu_string_hits_end"
  } | tee "$LOGDIR/exact-protector-inventory.txt"
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

# Build the real arm64 artifact from the exact user-selected source. This is the
# APK intended for Fold7 testing after the launch-surrogate checks pass.
if [[ "$CASE_NAME" == "patched-999" ]]; then
  mkdir -p "$LOGDIR/tested-apks"
  ARM_CANDIDATE="$LOGDIR/tested-apks/Inotia4_Berserker_Buffs_999s_arm64_candidate.apk"
  sign_apk "$UNSIGNED" "$ARM_CANDIDATE"
  sha256sum "$ARM_CANDIDATE" | tee "$LOGDIR/tested-apks/sha256.txt"
fi

# GitHub's accelerated runner is x86. Transplant only x86 native libraries
# from official 1.3.9 while retaining the selected APK's classes, manifest and
# game resources. This surrogate is used only for automated launch checking.
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

OFFICIAL_BASE=""
while IFS= read -r apk; do
  name="$(basename "$apk")"
  if [[ "$name" != config.*.apk && "$name" != split_config.*.apk ]]; then
    OFFICIAL_BASE="$apk"
    break
  fi
done < <(find "$XAPK_DIR" -type f -name '*.apk' | sort)
if [[ -z "$OFFICIAL_BASE" ]]; then
  OFFICIAL_BASE="$(find "$XAPK_DIR" -type f -name '*.apk' -printf '%s %p\n' | sort -nr | head -n1 | cut -d' ' -f2-)"
fi

{
  echo "x86_split=$X86_SPLIT"
  echo "official_base=$OFFICIAL_BASE"
  for entry in classes.dex AndroidManifest.xml resources.arsc; do
    exact_hash="$(unzip -p "$EXACT_APK" "$entry" 2>/dev/null | sha256sum | awk '{print $1}')"
    official_hash="$(unzip -p "$OFFICIAL_BASE" "$entry" 2>/dev/null | sha256sum | awk '{print $1}')"
    safe_entry="$(printf '%s' "$entry" | tr -c 'A-Za-z0-9' '_')"
    echo "exact_${safe_entry}_sha256=$exact_hash"
    echo "official_${safe_entry}_sha256=$official_hash"
  done
  echo "exact_native_entries_begin"
  unzip -Z1 "$EXACT_APK" | grep '^lib/' | sort || true
  echo "exact_native_entries_end"
  echo "official_x86_entries_begin"
  unzip -Z1 "$X86_SPLIT" | grep '^lib/x86/' | sort || true
  echo "official_x86_entries_end"
} | tee "$LOGDIR/runtime-surrogate.txt"

# Establish whether the official 1.3.9 x86 package itself survives on this
# emulator. This is diagnostic only and never relaxes the exact-source checks.
if [[ "$CASE_NAME" == "resigned-control" ]]; then
  mapfile -t OFFICIAL_APKS < <(find "$XAPK_DIR" -type f -name '*.apk' | sort)
  adb wait-for-device
  adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
  adb logcat -c || true
  set +e
  adb install-multiple -r -t "${OFFICIAL_APKS[@]}" 2>&1 | tee "$LOGDIR/official-x86-install.txt"
  OFFICIAL_INSTALL_RC=${PIPESTATUS[0]}
  set -e
  if [[ $OFFICIAL_INSTALL_RC -eq 0 ]]; then
    OFFICIAL_ACTIVITY="$(adb shell cmd package resolve-activity --brief "$PACKAGE" 2>/dev/null | tr -d '\r' | tail -n1 || true)"
    timeout 10s adb shell am start -n "$OFFICIAL_ACTIVITY" > "$LOGDIR/official-x86-am-start.txt" 2>&1 || true
    sleep 8
    {
      echo "official_pid=$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
      echo "official_resumed=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity' || true)"
      echo "official_focus=$(adb shell dumpsys window windows 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp' || true)"
    } | tee "$LOGDIR/official-x86-state.txt"
    adb exec-out screencap -p > "$LOGDIR/official-x86-screenshot.png" || true
    adb logcat -d -b all -v threadtime > "$LOGDIR/official-x86-logcat.txt" || true
  else
    echo "official_install_rc=$OFFICIAL_INSTALL_RC" | tee "$LOGDIR/official-x86-state.txt"
  fi
  adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
fi

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
timeout 10s adb shell am start -n "$RESOLVED_ACTIVITY" 2>&1 | tee "$LOGDIR/am-start.txt"
START_RC=${PIPESTATUS[0]}
set -e
if [[ $START_RC -eq 124 ]]; then
  echo "am_start_timeout=10s" | tee -a "$LOGDIR/am-start.txt"
  START_RC=0
fi
if [[ $START_RC -ne 0 ]]; then
  echo "RESULT=$CASE_NAME START_FAIL rc=$START_RC" | tee "$LOGDIR/summary.txt"
  exit 22
fi

# Capture the first seconds at high resolution: protected/native apps can exit
# before the first 5-second checkpoint without emitting a Java exception.
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

# Dismiss Android's one-time immersive-mode education overlay if it appears.
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

# A launch that immediately closes is a hard failure. We sample at 5s as well
# as 10/30/60s so the CI catches the Fold7 symptom described by the user.
sleep 3
STATE5_RC=0; sample_state 5s || STATE5_RC=$?
sleep 5
STATE10_RC=0; sample_state 10s || STATE10_RC=$?
sleep 20
STATE30_RC=0; sample_state 30s || STATE30_RC=$?
sleep 30
STATE60_RC=0; sample_state 60s || STATE60_RC=$?
adb logcat -d -b all -v threadtime > "$LOGDIR/logcat.txt" || true
adb shell dumpsys package "$PACKAGE" > "$LOGDIR/dumpsys-package.txt" 2>&1 || true
adb shell dumpsys activity processes > "$LOGDIR/dumpsys-processes.txt" 2>&1 || true
adb root >/dev/null 2>&1 || true
adb wait-for-device || true
adb shell ls -la /data/tombstones > "$LOGDIR/tombstones-list.txt" 2>&1 || true
mkdir -p "$LOGDIR/tombstones"
adb pull /data/tombstones "$LOGDIR/tombstones" > "$LOGDIR/tombstones-pull.txt" 2>&1 || true

grep -Ei 'FATAL EXCEPTION|Fatal signal|SIGSEGV|SIGABRT|ANR in|has died|linker|dlopen failed|UnsatisfiedLinkError|SecurityException|signature|certificate|Hercules|StubApp|libgame|com2us|inotia4' "$LOGDIR/logcat.txt" \
  | tail -n 400 > "$LOGDIR/launch-diagnostics.txt" || true
echo "diagnostic_lines_begin"
cat "$LOGDIR/launch-diagnostics.txt"
echo "diagnostic_lines_end"

FATAL_LINES="$(grep -E 'FATAL EXCEPTION|Fatal signal|SIGSEGV|SIGABRT|ANR in|has died' "$LOGDIR/logcat.txt" | grep -Ei 'inotia4|com2us|StubApp|Hercules|libgame' || true)"
SYSTEM_OVERLAY=no
if [[ -f "$LOGDIR/window-60s.xml" ]] && grep -Eqi 'Viewing full screen|GOT IT|full.?screen education|immersive' "$LOGDIR/window-60s.xml"; then SYSTEM_OVERLAY=yes; fi

# The game is largely native-rendered, so UIAutomator cannot reliably read the
# title text. Require the captured frame to contain substantial rendered image
# data rather than a tiny/empty capture; screenshots remain attached for visual
# verification against the Google Play Games / Chinese prompt / Inotia IV title
# screens supplied by the user.
SCREENSHOT_OK=yes
for shot in "$LOGDIR/screenshot-5s.png" "$LOGDIR/screenshot-10s.png" "$LOGDIR/screenshot-30s.png" "$LOGDIR/screenshot-60s.png"; do
  if [[ ! -s "$shot" || $(stat -c %s "$shot") -lt 20000 ]]; then SCREENSHOT_OK=no; fi
done

{
  echo "case=$CASE_NAME"
  echo "start_rc=$START_RC"
  echo "state5_rc=$STATE5_RC"
  echo "state10_rc=$STATE10_RC"
  echo "state30_rc=$STATE30_RC"
  echo "state60_rc=$STATE60_RC"
  echo "system_overlay_60s=$SYSTEM_OVERLAY"
  echo "screenshots_nonempty=$SCREENSHOT_OK"
  echo "source_resource_sha256=$SOURCE_RESOURCE_SHA256"
  echo "fatal_lines_begin"
  printf '%s\n' "$FATAL_LINES"
  echo "fatal_lines_end"
} | tee "$LOGDIR/summary.txt"

if [[ $STATE5_RC -ne 0 || $STATE10_RC -ne 0 || $STATE30_RC -ne 0 || $STATE60_RC -ne 0 ]]; then
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
if [[ "$SCREENSHOT_OK" != yes ]]; then
  echo "RESULT=$CASE_NAME EMPTY_SCREENSHOT_FAIL" | tee -a "$LOGDIR/summary.txt"
  exit 33
fi

echo "RESULT=$CASE_NAME FOREGROUND_60S_OK" | tee -a "$LOGDIR/summary.txt"
