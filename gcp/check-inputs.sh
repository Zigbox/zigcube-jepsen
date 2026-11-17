#!/usr/bin/env bash
# Checks gcp.yml's dispatch inputs before anything is built or created:
# each value's shape, every test name against the list control-run.sh
# knows, and that the whole run fits in the live job's timeout. The
# workflow passes the inputs in the environment (never as ${{ }} inside a
# script), and so does the offline self-test (check-inputs.sh --self-test).
#
# Env: ZIGCUBE_REF, TESTS, TIME_LIMIT; LIVE_TIMEOUT_MIN (the live job's
# timeout-minutes, default 150).
set -euo pipefail

# Budget, in seconds. SETUP: boot, startup scripts, ship, lein deps.
# PER_TEST: Jepsen's own setup, teardown and analysis around the load.
# MARGIN: kept free under the job timeout for collecting the results.
SETUP=1200
PER_TEST=300
MARGIN=600

known="api-fenced api-unfenced api-negative-control api-unfenced-async
pg-fenced pg-unfenced pg-negative-control pg-unfenced-async
lease-valid lease-wedged lease-late-kill
stale-put-running stale-put-registered stale-put-idle"

fail() { echo "::error::$*" >&2; exit 1; }

# Seconds of load a test takes for a given time_limit (control-run.sh's
# args_for: the lease tests keep jepsen.yml's minimums; stale-put has its own
# fixed phases, a few minutes at most).
load_seconds() {
  local t=$1 limit=$2
  case "$t" in
    api-*|pg-*)      echo "$limit" ;;
    lease-valid)     echo $(( limit > 200 ? limit : 200 )) ;;
    lease-*)         echo $(( limit > 120 ? limit : 120 )) ;;
    stale-put-*)     echo 180 ;;
  esac
}

check() {
  local ref=$1 tests=$2 limit=$3 timeout_min=$4
  # A commit, branch or tag of Zigbox/zigcube. No leading '-', no '..'
  # (actions/checkout takes it as a ref, never as a shell word, but keep it
  # boring anyway).
  [[ "$ref" =~ ^[0-9A-Za-z_][0-9A-Za-z._/-]*$ && "$ref" != *..* ]] \
    || fail "zigcube_ref: a commit, branch or tag (letters, digits, . _ / -)"
  if ! [[ "$limit" =~ ^[0-9]+$ ]] || [ "$limit" -lt 30 ] || [ "$limit" -gt 1800 ]; then
    fail "time_limit must be a whole number of seconds, 30 to 1800"
  fi
  [[ "$tests" =~ ^[a-z0-9\ -]+$ ]] || fail "tests: names separated by spaces"
  local t n=0 total=$SETUP
  for t in $tests; do
    grep -qx -- "$t" <<<"${known//[[:space:]]/$'\n'}" || fail "unknown test: $t"
    n=$((n + 1))
    total=$(( total + $(load_seconds "$t" "$limit") + PER_TEST ))
  done
  [ "$n" -ge 1 ] || fail "tests: none given"
  local budget=$(( timeout_min * 60 - MARGIN ))
  if [ "$total" -gt "$budget" ]; then
    fail "$n test(s) at time_limit=$limit need about $((total / 60)) min, more than the $((budget / 60)) min the live job's ${timeout_min} min timeout leaves; run fewer tests or a shorter time_limit"
  fi
  echo "inputs ok: $n test(s), about $((total / 60)) of $((budget / 60)) min"
}

self_test() {
  local ok=(
    "fb385b2d0d7e6beefd3225e71ae2bcf3437aa474|api-fenced pg-fenced lease-valid lease-wedged|120"
    "main|stale-put-running stale-put-idle|30"
    "refs/heads/j2-dist|lease-late-kill|1800"
    "v1.2.3|api-fenced api-fenced api-fenced|600"
  )
  local bad=(
    "fb385b2|api-fenced; rm -rf /|120"
    "fb385b2|api-fenced \$(id)|120"
    "fb385b2|api-fenced|12"
    "fb385b2|api-fenced|1801"
    "fb385b2|api-fenced|1e3"
    "fb385b2|api-fencedx|120"
    "fb385b2||120"
    "-fb385b2|api-fenced|120"
    "refs/../x|api-fenced|120"
    "x;id|api-fenced|120"
    # Over budget: four 30 min tests, and twenty short ones.
    "main|api-fenced pg-fenced lease-valid lease-wedged|1800"
    "main|api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced api-fenced|120"
  )
  local c r t l rc=0
  for c in "${ok[@]}"; do
    IFS='|' read -r r t l <<<"$c"
    (check "$r" "$t" "$l" 150) >/dev/null 2>&1 || { echo "FAIL: rejected $c"; rc=1; }
  done
  for c in "${bad[@]}"; do
    IFS='|' read -r r t l <<<"$c"
    if (check "$r" "$t" "$l" 150) >/dev/null 2>&1; then echo "FAIL: accepted $c"; rc=1; fi
  done
  [ "$rc" = 0 ] && echo "check-inputs self-test: ${#ok[@]} accepted, ${#bad[@]} rejected, as expected"
  return "$rc"
}

if [ "${1:-}" = --self-test ]; then
  self_test
else
  check "${ZIGCUBE_REF-}" "${TESTS-}" "${TIME_LIMIT-}" "${LIVE_TIMEOUT_MIN:-150}"
fi
