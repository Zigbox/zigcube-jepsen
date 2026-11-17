#!/usr/bin/env bash
# The CI runner's side of a GCP run (.github/workflows/gcp.yml). Talks to the
# control VM through an IAP tunnel with OS Login, as the workflow's service
# account (gcloud uses the Workload Identity Federation credentials the auth
# action set up; nothing here reads or prints a token).
#
# Env: ZONE, CONTROL (the control VM's name; diag works without it),
# RESULTS (local directory), RUN_ID (diag).
# Usage: ci.sh wait-ssh | ship <harness.tar.gz> <zigcube.tar.gz> <key>
#        | start "<tests>" <time-limit> | wait | fetch | diag
set -euo pipefail

: "${ZONE:?}"

# One OS Login key per run, expiring on its own so the service account's
# login profile does not fill up. T=<seconds> bounds one call (0: no bound).
gssh() {
  : "${CONTROL:?}"
  timeout "${T:-0}" gcloud compute ssh "$CONTROL" --zone "$ZONE" --tunnel-through-iap --quiet \
    --strict-host-key-checking=no --ssh-key-expire-after=6h \
    --command "$1" -- -o ServerAliveInterval=30 -o ServerAliveCountMax=6 -o LogLevel=ERROR
}

gscp() {
  timeout "${T:-0}" gcloud compute scp --zone "$ZONE" --tunnel-through-iap --quiet \
    --strict-host-key-checking=no "$@"
}

case "${1:-}" in
  wait-ssh)
    # The VM boots, then its startup script installs a JDK and lein.
    for i in $(seq 1 60); do
      if gssh 'test -f /var/lib/zj-ready' 2>/dev/null; then
        echo "control VM ready after $i tries"; exit 0
      fi
      sleep 15
    done
    echo "::error::control VM not reachable over IAP, or its startup script did not finish"
    T=120 gssh 'sudo journalctl -u google-startup-scripts --no-pager | tail -n 80' || true
    exit 1
    ;;

  ship)
    harness=${2:?} bundle=${3:?} key=${4:?}
    gssh 'rm -rf ~/zj ~/zj-in && mkdir -p ~/zj-in'
    gscp "$harness" "$bundle" "$key" "$CONTROL:zj-in/"
    # The key goes to ~/zj/key, readable by the OS Login user alone.
    gssh "set -e; mkdir -p ~/zj/harness ~/zj/zigcube ~/zj/out
          tar -xzf ~/zj-in/$(basename "$harness") -C ~/zj/harness
          tar -xzf ~/zj-in/$(basename "$bundle") -C ~/zj/zigcube
          install -m 600 ~/zj-in/$(basename "$key") ~/zj/key
          rm -rf ~/zj-in
          ls -l ~/zj/zigcube ~/zj/zigcube/bin"
    ;;

  start)
    tests=${2:?} limit=${3:?}
    # Detached, so a dropped IAP session does not kill the run.
    gssh "nohup setsid bash ~/zj/harness/gcp/control-run.sh $(printf '%q' "$tests") $(printf '%q' "$limit") </dev/null >/dev/null 2>&1 &
          sleep 2; cat ~/zj/out/run.log 2>/dev/null || true"
    ;;

  wait)
    # The job's timeout bounds the run; this bounds a control VM that is
    # gone (deleted, or failed with automatic_restart off): 10 failed polls
    # in a row, about 10 to 20 minutes, and it gives up.
    seen=0 failures=0
    while :; do
      sleep 60
      if ! status=$(T=300 gssh "tail -n +$((seen + 1)) ~/zj/out/run.log 2>/dev/null; echo \"@exit=\$(cat ~/zj/out/exit 2>/dev/null)\"" 2>/dev/null); then
        failures=$((failures + 1))
        if [ "$failures" -ge 10 ]; then
          echo "::error::$failures polls of the control VM failed in a row; giving up"
          exit 1
        fi
        echo "(poll failed, $failures in a row, retrying)"; continue
      fi
      failures=0
      lines=$(printf '%s\n' "$status" | grep -v '^@exit=' || true)
      if [ -n "$lines" ]; then
        printf '%s\n' "$lines"
        seen=$((seen + $(printf '%s\n' "$lines" | wc -l)))
      fi
      code=$(printf '%s\n' "$status" | sed -n 's/^@exit=//p')
      if [ -n "$code" ]; then
        echo "remote run finished: exit $code"
        exit 0
      fi
    done
    ;;

  fetch)
    : "${RESULTS:?}"
    # shellcheck disable=SC2016 # expanded on the control VM
    gssh 'cd ~/zj && tar -czf ~/results.tar.gz out $(test -d harness/store && echo harness/store)'
    mkdir -p "$RESULTS"
    gscp "$CONTROL:results.tar.gz" "$RESULTS/results.tar.gz"
    tar -xzf "$RESULTS/results.tar.gz" -C "$RESULTS"
    rm -f "$RESULTS/results.tar.gz"
    if [ -f "$RESULTS/out/summary.tsv" ]; then column -t -s $'\t' "$RESULTS/out/summary.tsv"; fi
    code=$(cat "$RESULTS/out/exit" 2>/dev/null || echo missing)
    echo "remote exit: $code"
    [ "$code" = 0 ]
    ;;

  diag)
    # Why a VM did not become ready, for the results artifact. The serial
    # port output (readable with serial-port-enable=FALSE, which only turns
    # off the interactive console) has the boot and the startup script's
    # output, also when SSH is what broke. Then the startup script's journal
    # of the control VM over IAP, and of each node from the control VM.
    # Best effort and bounded: never fails the job on its own.
    : "${RESULTS:?}" "${RUN_ID:?}"
    d="$RESULTS/diag"
    mkdir -p "$d"
    names=$(gcloud compute instances list --filter="labels.run_id=${RUN_ID}" \
              --format='value(name,zone.basename())') || names=
    while read -r name zone; do
      [ -n "$name" ] || continue
      timeout 120 gcloud compute instances get-serial-port-output "$name" --zone "$zone" \
        > "$d/$name.serial.log" 2>&1 || echo "(serial output of $name: failed)"
      echo "$name: $(grep -c . "$d/$name.serial.log") serial lines; startup script:"
      grep -E 'startup-script|zcube-jepsen' "$d/$name.serial.log" | tail -n 15 || true
    done <<<"$names"
    if [ -n "${CONTROL:-}" ]; then
      # shellcheck disable=SC2016 # expanded on the control VM
      T=300 gssh 'sudo journalctl -u google-startup-scripts --no-pager > /tmp/zj-startup-control.log 2>&1
        for n in n1 n2 n3 n4 n5; do
          test -f ~/zj/key || break
          timeout 30 ssh -i ~/zj/key -o BatchMode=yes -o StrictHostKeyChecking=no \
            -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5 -o LogLevel=ERROR \
            "root@$n" "journalctl -u google-startup-scripts -u ssh --no-pager" \
            > "/tmp/zj-startup-$n.log" 2>&1 || echo "(root ssh to $n failed)" >> "/tmp/zj-startup-$n.log"
        done
        tar -czf /tmp/zj-diag.tar.gz -C /tmp $(cd /tmp && ls zj-startup-*.log)' \
        && T=120 gscp "$CONTROL:/tmp/zj-diag.tar.gz" "$d/journals.tar.gz" \
        && tar -xzf "$d/journals.tar.gz" -C "$d" && rm -f "$d/journals.tar.gz" \
        || echo "(journals over IAP: failed)"
    fi
    ls -l "$d"
    ;;

  *)
    echo "usage: $0 wait-ssh | ship H B K | start TESTS LIMIT | wait | fetch | diag" >&2
    exit 2
    ;;
esac
