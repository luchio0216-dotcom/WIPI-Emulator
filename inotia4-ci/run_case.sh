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
ACTIVITY="com.com2us.inotia4.normal.freefull.google.global.android.common.SplashActivity"
XAPK_URL="https://d.apkpure.net/b/XAPK/com.com2us.inotia4.normal.freefull.google.global.android.common?versionCode=139004"
USER_ORIGINAL_RESOURCE_SHA256="4e674cc35edf276e466c66b8c7562586f9d7c5e46bbb712a90bc8945c0705a22"
USER_PATCHED_RESOURCE_SHA256="75bb655c8c2cd3e89a28e8b90e1e6d1d7900f26521dc5aece8572964079cb3ee"

mkdir -p "$SRC" "$SELECTED" "$SIGNED" "$LOGDIR"

XAPK="$RUNNER_TEMP/inotia4-1.3.9.xapk"
if [[ ! -s "$XAPK" ]]; then
  echo "Downloading Inotia4 1.3.9 test source..."
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
X86_SPLIT_FOUND=0
for apk in "${ALL_APKS[@]}"; do
  if unzip -Z1 "$apk" | grep -qx "$TARGET_ENTRY"; then
    BASE_APK="$apk"
  fi
  if unzip -Z1 "$apk" | grep -q '^lib/x86/'; then
    X86_SPLIT_FOUND=1
  fi
done

if [[ -z "$BASE_APK" ]]; then
  echo "Could not locate base APK containing $TARGET_ENTRY" >&2
  printf '%s\n' "${ALL_APKS[@]}" >&2
  exit 11
fi
if [[ $X86_SPLIT_FOUND -ne 1 ]]; then
  echo "No x86 native split found; this workflow uses an x86 emulator." >&2
  exit 12
fi

echo "Base APK: $BASE_APK" | tee "$LOGDIR/source-layout.txt"
echo "All APKs:" >> "$LOGDIR/source-layout.txt"
printf '  %s\n' "${ALL_APKS[@]}" >> "$LOGDIR/source-layout.txt"

# Select base + all resource/language/density splits, while excluding native ABI
# splits other than x86. Android accepts multiple config splits; keeping them
# avoids missing-resource failures during SDK initialization.
for apk in "${ALL_APKS[@]}"; do
  listing="$(unzip -Z1 "$apk")"
  if grep -Eq '^lib/(arm64-v8a|armeabi-v7a|x86_64)/' <<<"$listing"; then
    continue
  fi
  cp "$apk" "$SELECTED/$(basename "$apk")"
done

BASE_NAME="$(basename "$BASE_APK")"
if [[ ! -f "$SELECTED/$BASE_NAME" ]]; then
  cp "$BASE_APK" "$SELECTED/$BASE_NAME"
fi

# Record the source resource hash so we know whether the public 1.3.9 fixture
# matches the user's uploaded APK at the exact game-data entry.
unzip -p "$SELECTED/$BASE_NAME" "$TARGET_ENTRY" > "$WORK/source-memorytext_e.dat.jpg"
SOURCE_RESOURCE_SHA256="$(sha256sum "$WORK/source-memorytext_e.dat.jpg" | awk '{print $1}')"
echo "source_resource_sha256=$SOURCE_RESOURCE_SHA256" | tee "$LOGDIR/resource-hashes.txt"
echo "user_original_resource_sha256=$USER_ORIGINAL_RESOURCE_SHA256" >> "$LOGDIR/resource-hashes.txt"
echo "user_patched_resource_sha256=$USER_PATCHED_RESOURCE_SHA256" >> "$LOGDIR/resource-hashes.txt"
if [[ "$SOURCE_RESOURCE_SHA256" == "$USER_ORIGINAL_RESOURCE_SHA256" ]]; then
  echo "resource_fixture_match=yes" >> "$LOGDIR/resource-hashes.txt"
else
  echo "resource_fixture_match=no" >> "$LOGDIR/resource-hashes.txt"
fi

if [[ "$CASE_NAME" == "patched-999" ]]; then
  base64 -d "$CI_DIR/patches/memorytext_e.dat.jpg.b64" > "$WORK/patched-memorytext_e.dat.jpg"
  PATCH_SHA="$(sha256sum "$WORK/patched-memorytext_e.dat.jpg" | awk '{print $1}')"
  if [[ "$PATCH_SHA" != "$USER_PATCHED_RESOURCE_SHA256" ]]; then
    echo "Patch fixture SHA256 mismatch: $PATCH_SHA" >&2
    exit 13
  fi

  # Replace only the target stored ZIP member. Using Info-ZIP instead of
  # re-packing the entire APK preserves native-library local-header offsets.
  cp "$SELECTED/$BASE_NAME" "$WORK/patched-base.apk"
  zip -q -d "$WORK/patched-base.apk" "$TARGET_ENTRY"
  mkdir -p "$WORK/inject/assets/common/game_res"
  cp "$WORK/patched-memorytext_e.dat.jpg" "$WORK/inject/$TARGET_ENTRY"
  (
    cd "$WORK/inject"
    zip -q -0 "$WORK/patched-base.apk" "$TARGET_ENTRY"
  )
  cp "$WORK/patched-base.apk" "$SELECTED/$BASE_NAME"
  unzip -p "$SELECTED/$BASE_NAME" "$TARGET_ENTRY" | sha256sum | tee -a "$LOGDIR/resource-hashes.txt"
fi

APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)"
ZIPALIGN="$(find "$ANDROID_HOME/build-tools" -type f -name zipalign | sort -V | tail -n 1)"
if [[ -z "$APKSIGNER" ]]; then
  echo "apksigner not found under $ANDROID_HOME/build-tools" >&2
  exit 14
fi

if [[ "$CASE_NAME" == "official-original" ]]; then
  cp "$SELECTED"/*.apk "$SIGNED/"
else
  KEYSTORE="$WORK/test-signing.jks"
  keytool -genkeypair -noprompt -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias inotia4-ci -keyalg RSA -keysize 2048 -validity 3650 \
    -dname 'CN=Inotia4 CI,OU=Testing,O=Local,L=Seoul,ST=Seoul,C=KR'

  for apk in "$SELECTED"/*.apk; do
    out="$SIGNED/$(basename "$apk")"
    "$APKSIGNER" sign \
      --ks "$KEYSTORE" --ks-key-alias inotia4-ci \
      --ks-pass pass:android --key-pass pass:android \
      --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
      --out "$out" "$apk"
    "$APKSIGNER" verify --verbose --print-certs "$out" >> "$LOGDIR/signature-verification.txt" 2>&1
  done
fi

# Log alignment without making it the sole pass/fail signal.
for apk in "$SIGNED"/*.apk; do
  echo "=== $(basename "$apk") ===" >> "$LOGDIR/zipalign.txt"
  "$ZIPALIGN" -c -P 16 -v 4 "$apk" >> "$LOGDIR/zipalign.txt" 2>&1 || true
done

adb logcat -c || true
adb shell pm clear "$PACKAGE" >/dev/null 2>&1 || true
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
  echo "RESULT=$CASE_NAME INSTALL_FAIL rc=$INSTALL_RC"
  exit 20
fi

# Copy any expansion files included in the XAPK.
while IFS= read -r -d '' obb; do
  adb shell mkdir -p "/sdcard/Android/obb/$PACKAGE"
  adb push "$obb" "/sdcard/Android/obb/$PACKAGE/$(basename "$obb")" || true
done < <(find "$SRC" -type f -name '*.obb' -print0)

adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb logcat -c || true

set +e
adb shell am start -W -n "$PACKAGE/$ACTIVITY" 2>&1 | tee "$LOGDIR/am-start.txt"
START_RC=${PIPESTATUS[0]}
set -e

sleep 8
PID8="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
adb shell dumpsys activity activities > "$LOGDIR/dumpsys-8s.txt" || true
adb exec-out screencap -p > "$LOGDIR/screenshot-8s.png" || true
sleep 22
PID30="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
adb shell dumpsys activity activities > "$LOGDIR/dumpsys-30s.txt" || true
adb exec-out screencap -p > "$LOGDIR/screenshot-30s.png" || true
adb logcat -d -v threadtime > "$LOGDIR/logcat.txt" || true

{
  echo "case=$CASE_NAME"
  echo "start_rc=$START_RC"
  echo "pid_8s=$PID8"
  echo "pid_30s=$PID30"
  echo "source_resource_sha256=$SOURCE_RESOURCE_SHA256"
  grep -E 'FATAL EXCEPTION|Fatal signal|AndroidRuntime|SIG(SEGV|ABRT)|SecurityException|PackageManager|StubApp|Hercules' "$LOGDIR/logcat.txt" | tail -n 250 || true
} | tee "$LOGDIR/summary.txt"

if [[ -z "$PID8" || -z "$PID30" ]]; then
  echo "RESULT=$CASE_NAME LAUNCH_FAIL"
  exit 30
fi

echo "RESULT=$CASE_NAME LAUNCH_OK pid=$PID30"
