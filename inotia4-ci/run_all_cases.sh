#!/usr/bin/env bash
set -u
mkdir -p inotia4-ci/test-results
: > inotia4-ci/test-results/status.txt

run_case() {
  local name="$1"
  echo "===== CASE: $name ====="
  set +e
  bash inotia4-ci/run_case.sh "$name"
  local rc=$?
  set -e
  echo "$name=$rc" | tee -a inotia4-ci/test-results/status.txt
  return 0
}

set -e
run_case resigned-control
run_case patched-999

echo "===== CASE STATUS ====="
cat inotia4-ci/test-results/status.txt
