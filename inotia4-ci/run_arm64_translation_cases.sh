#!/usr/bin/env bash
set -u
RESULT_ROOT="inotia4-ci/arm64-translation-results"
mkdir -p "$RESULT_ROOT"
: > "$RESULT_ROOT/status.txt"

run_case() {
  local name="$1"
  echo "===== ARM64 TRANSLATION CASE: $name ====="
  set +e
  bash inotia4-ci/run_arm64_translation_case.sh "$name"
  local rc=$?
  set -e
  echo "$name=$rc" | tee -a "$RESULT_ROOT/status.txt"
}

set -e
run_case exact-original
run_case resigned-control
run_case patched-999

CONTROL_RC="$(awk -F= '$1=="resigned-control"{print $2}' "$RESULT_ROOT/status.txt")"
PATCHED_RC="$(awk -F= '$1=="patched-999"{print $2}' "$RESULT_ROOT/status.txt")"
echo "VALIDATION=NOT_READY" > "$RESULT_ROOT/arm64-validation.txt"
if [[ "$CONTROL_RC" == 0 && "$PATCHED_RC" == 0 ]] \
  && grep -q '^RESULT=resigned-control ARM64_TRANSLATION_60S_OK$' "$RESULT_ROOT/resigned-control/summary.txt" \
  && grep -q '^RESULT=patched-999 ARM64_TRANSLATION_60S_OK$' "$RESULT_ROOT/patched-999/summary.txt" \
  && test -s "$RESULT_ROOT/patched-999/tested-apks/Inotia4_Berserker_Buffs_999s_arm64_candidate.apk"; then
  echo "VALIDATION=PASS" > "$RESULT_ROOT/arm64-validation.txt"
fi
cat "$RESULT_ROOT/status.txt"
cat "$RESULT_ROOT/arm64-validation.txt"
exit 0
