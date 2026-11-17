#!/usr/bin/env bash
# gcp.yml's teardown: `tofu destroy` of one run's state, after `tofu init`
# with prefix runs/$RUN_ID. Up to three attempts. If the state is locked
# (a run cancelled during apply leaves its lock behind), and the lock is on
# this run's own state, force-unlock it before the next attempt: the live
# job is over by then and the concurrency group admits no other run, so
# nothing else can hold it. A lock anywhere else is never touched.
#
# Env: RUN_ID; TOFU (default tofu), TOFU_DIR (default tofu), SLEEP (default 30).
set -uo pipefail

: "${RUN_ID:?}"
tf=${TOFU:-tofu}
dir=${TOFU_DIR:-tofu}
log=$(mktemp)
trap 'rm -f "$log"' EXIT

for i in 1 2 3; do
  "$tf" -chdir="$dir" destroy -auto-approve -no-color -lock-timeout=2m 2>&1 | tee "$log"
  if [ "${PIPESTATUS[0]}" = 0 ]; then exit 0; fi
  if grep -q 'Error acquiring the state lock' "$log"; then
    # Lock Info lines, boxed (│) or not.
    id=$(sed -nE 's/^[^A-Za-z]*ID:[[:space:]]+([0-9A-Za-z-]+).*/\1/p' "$log" | head -n 1)
    path=$(sed -nE 's/^[^A-Za-z]*Path:[[:space:]]+([^[:space:]]+).*/\1/p' "$log" | head -n 1)
    case "$path" in
      */runs/"$RUN_ID"/default.tflock)
        if [ -n "$id" ]; then
          echo "::warning::state of run $RUN_ID locked by an interrupted run ($id); force-unlocking"
          "$tf" -chdir="$dir" force-unlock -force "$id"
        fi
        ;;
      *)
        echo "::error::state lock at '${path:-?}' is not this run's (runs/$RUN_ID); not touching it"
        exit 1
        ;;
    esac
  fi
  echo "destroy attempt $i failed; retrying"
  sleep "${SLEEP:-30}"
done
echo "::error::tofu destroy failed: state runs/${RUN_ID} in the state bucket; see README 'Tearing down by hand'"
exit 1
