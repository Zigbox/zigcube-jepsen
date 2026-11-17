#!/usr/bin/env bash
# The CI runner's side of a GCP run (.github/workflows/gcp.yml). Talks to the
# control VM through an IAP tunnel with OS Login, as the workflow's service
# account (gcloud uses the Workload Identity Federation credentials the auth
# action set up; nothing here reads or prints a token).
#
# Env: ZONE, CONTROL (the control VM's name), RESULTS (local directory).
# Usage: ci.sh wait-ssh | ship <harness.tar.gz> <zigcube.tar.gz> <key>
#        | start "<tests>" <time-limit> | wait | fetch
set -euo pipefail

: "${ZONE:?}" "${CONTROL:?}"

# One OS Login key per run, expiring on its own so the service account's
# login profile does not fill up.
gssh() {
  gcloud compute ssh "$CONTROL" --zone "$ZONE" --tunnel-through-iap --quiet \
    --strict-host-key-checking=no --ssh-key-expire-after=6h \
    --command "$1" -- -o ServerAliveInterval=30 -o ServerAliveCountMax=6 -o LogLevel=ERROR
}

gscp() {
  gcloud compute scp --zone "$ZONE" --tunnel-through-iap --quiet \
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
    gssh 'sudo tail -n 50 /var/log/syslog' || true
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
    # The job's timeout bounds this.
    seen=0
    while :; do
      sleep 60
      if ! status=$(gssh "tail -n +$((seen + 1)) ~/zj/out/run.log 2>/dev/null; echo \"@exit=\$(cat ~/zj/out/exit 2>/dev/null)\"" 2>/dev/null); then
        echo "(poll failed, retrying)"; continue
      fi
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

  *)
    echo "usage: $0 wait-ssh | ship H B K | start TESTS LIMIT | wait | fetch" >&2
    exit 2
    ;;
esac
