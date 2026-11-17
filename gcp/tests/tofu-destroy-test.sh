#!/usr/bin/env bash
# Offline checks of gcp/tofu-destroy.sh against a stub tofu that replays
# OpenTofu 1.13.9's "Error acquiring the state lock" output (-no-color; the
# GCS backend's Path is the lock object's gs:// URL).
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
stubs=$(mktemp -d)
trap 'rm -rf "$stubs"' EXIT

# STUB_LOCK_PATH: the lock the first destroy reports (empty: no lock);
# STUB_FAILS: how many destroys fail.
cat > "$stubs/tofu" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$STUB_LOG"
n=$(grep -c ' destroy ' "$STUB_LOG")
case "$*" in
  *" destroy "*)
    if [ "$n" -le "${STUB_FAILS:-0}" ]; then
      if [ -n "${STUB_LOCK_PATH:-}" ]; then
        cat <<OUT
Acquiring state lock. This may take a few moments...

Error: Error acquiring the state lock

Error message: writing "${STUB_LOCK_PATH}" failed: googleapi: Error 412:
At least one of the pre-conditions you specified did not hold.,
conditionNotMet
Lock Info:
  ID:        1763410611774093
  Path:      ${STUB_LOCK_PATH}
  Operation: OperationTypeApply
  Who:       runner@fv-az123-456
  Version:   1.13.9
  Created:   2026-11-17 20:16:51.774093504 +0000 UTC
  Info:

OpenTofu acquires a state lock to protect the state from being written
by multiple users at the same time.
OUT
      else
        echo "Error: googleapi: Error 503: backend error"
      fi
      exit 1
    fi
    echo "Destroy complete! Resources: 9 destroyed." ;;
  *" force-unlock "*) echo "OpenTofu state has been successfully unlocked!" ;;
esac
STUB
chmod +x "$stubs/tofu"

fail=0
case_() {
  local name=$1 want=$2 pat=$3 nopat=$4; shift 4
  : > "$stubs/log"
  env STUB_LOG="$stubs/log" TOFU="$stubs/tofu" SLEEP=0 RUN_ID=123 "$@" \
    bash "$here/../tofu-destroy.sh" > "$stubs/out" 2>&1
  local rc=$?
  if [ "$rc" != "$want" ]; then echo "FAIL $name: exit $rc, want $want"; sed 's/^/    /' "$stubs/out"; fail=1; return; fi
  if [ "$pat" != - ] && ! grep -qE -- "$pat" "$stubs/log"; then echo "FAIL $name: no '$pat'"; cat "$stubs/log"; fail=1; return; fi
  if [ "$nopat" != - ] && grep -qE -- "$nopat" "$stubs/log"; then echo "FAIL $name: unexpected '$nopat'"; cat "$stubs/log"; fail=1; return; fi
  echo "ok   $name"
}

case_ "clean destroy"                   0 "destroy -auto-approve -no-color -lock-timeout=2m" "force-unlock"
case_ "own stale lock: unlock, retry"   0 "force-unlock -force 1763410611774093" - \
  STUB_FAILS=1 STUB_LOCK_PATH=gs://zj-state/runs/123/default.tflock
case_ "another run's lock: untouched"   1 - "force-unlock" \
  STUB_FAILS=1 STUB_LOCK_PATH=gs://zj-state/runs/1234/default.tflock
case_ "other error: three attempts"     1 - "force-unlock" STUB_FAILS=3
[ "$(grep -c ' destroy ' "$stubs/log")" = 3 ] || { echo "FAIL: want 3 destroy attempts"; fail=1; }
case_ "two transient errors, then ok"   0 - "force-unlock" STUB_FAILS=2

[ "$fail" = 0 ] && echo "tofu-destroy.sh checks: all passed"
exit "$fail"
