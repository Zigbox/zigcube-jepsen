#!/usr/bin/env bash
# Runs on the control VM (gcp/ci.sh start launches it detached). Waits for
# the nodes, then runs the selected tests one after another against n1..n5
# and writes, under ~/zj/out:
#
#   run.log        this script's own log
#   logs/<t>.log   each test's stdout and stderr
#   summary.tsv    test, exit code, seconds, store directory
#   exit           0 if every test passed, else 1 (written last)
#
# Usage: control-run.sh "<test> <test> ..." <time-limit-seconds>
# Tests: api-fenced api-unfenced api-negative-control api-unfenced-async,
#        the same four for pg, lease-valid lease-wedged lease-late-kill,
#        stale-put-running stale-put-registered stale-put-idle.
set -uo pipefail

tests=${1:?tests}
time_limit=${2:?time limit}
zj=$HOME/zj
out=$zj/out
key=$zj/key
zc=$zj/zigcube
nodes=(n1 n2 n3 n4 n5)

mkdir -p "$out/logs"
exec >>"$out/run.log" 2>&1
log() { echo "$(date -u +%H:%M:%S) $*"; }
finish() { echo "$1" > "$out/exit"; log "done, exit $1"; exit "$1"; }

cd "$zj/harness" || finish 2

# Every name must be known before anything runs.
args_for() {
  local t=$1 lease_limit
  local common=(--ssh-private-key "$key" --zcube-bin "$zc/bin"
                --zcube-images "$zc/images.zon")
  local core=(run test "${common[@]}" --zcube-constants "$zc/constants.zig"
              --concurrency 10 --min-failovers 2)
  case "$t" in
    api-*|pg-*)
      local w=${t%%-*} mode=${t#*-}
      local m
      case "$mode" in
        fenced)           m=(--failover fenced) ;;
        unfenced)         m=(--failover unfenced) ;;
        negative-control) m=(--replication async --failover lagged --expect lost-write) ;;
        unfenced-async)   m=(--replication async --failover unfenced --expect lost-write) ;;
        *) return 1 ;;
      esac
      echo "${core[@]}" --workload "$w" --time-limit "$time_limit" "${m[@]}"
      ;;
    # The lease runs keep jepsen.yml's time limits unless asked for longer:
    # the fault schedule needs them.
    lease-valid)
      lease_limit=$(( time_limit > 200 ? time_limit : 200 ))
      echo "${core[@]}" --workload lease --time-limit "$lease_limit" ;;
    lease-wedged)
      lease_limit=$(( time_limit > 120 ? time_limit : 120 ))
      echo "${core[@]}" --workload lease --time-limit "$lease_limit" --expect overlap --fault-ms 30000 ;;
    lease-late-kill)
      lease_limit=$(( time_limit > 120 ? time_limit : 120 ))
      echo "${core[@]}" --workload lease --time-limit "$lease_limit" --expect overlap --lease-faults partition ;;
    stale-put-running)
      echo run -m zcube-jepsen.stale-put test "${common[@]}" --expect auto --release-after running ;;
    stale-put-registered)
      echo run -m zcube-jepsen.stale-put test "${common[@]}" --expect auto --release-after registered ;;
    stale-put-idle)
      echo run -m zcube-jepsen.stale-put test "${common[@]}" --expect auto --release-after registered --services 0 ;;
    *) return 1 ;;
  esac
}

for t in $tests; do
  args_for "$t" >/dev/null || { log "unknown test: $t"; finish 2; }
done

log "zigcube: $(cat "$zc/COMMIT" 2>/dev/null || echo unknown)"
log "waiting for the nodes"
for n in "${nodes[@]}"; do
  ok=
  for _ in $(seq 1 90); do
    if ssh -i "$key" -o BatchMode=yes -o StrictHostKeyChecking=no \
         -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5 -o LogLevel=ERROR \
         "root@$n" test -f /var/lib/zj-ready; then ok=1; break; fi
    sleep 10
  done
  [ -n "$ok" ] || { log "$n never became ready"; finish 2; }
  log "$n ready"
done

log "lein deps"
lein deps > "$out/logs/lein-deps.log" 2>&1 || { log "lein deps failed"; finish 2; }

printf 'test\texit\tseconds\tstore\n' > "$out/summary.tsv"
failed=0
for t in $tests; do
  read -r -a a <<<"$(args_for "$t")"
  log "start $t: lein ${a[*]}"
  start=$(date +%s)
  lein "${a[@]}" > "$out/logs/$t.log" 2>&1
  rc=$?
  store=$(readlink -f store/latest 2>/dev/null || echo -)
  printf '%s\t%s\t%s\t%s\n' "$t" "$rc" "$(( $(date +%s) - start ))" "${store#"$zj/harness/"}" >> "$out/summary.tsv"
  log "end $t: exit $rc"
  [ "$rc" = 0 ] || failed=1
done

finish "$failed"
