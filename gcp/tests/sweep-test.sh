#!/usr/bin/env bash
# Offline checks of gcp/sweep.sh against a stub gcloud: a failed listing
# fails the sweep, nothing found is green, leftovers are deleted (networks
# after their routers, rules and subnets) and turn it red, too young an age
# is refused, and RUN_ID scopes the filters to one run.
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
sweep=$here/../sweep.sh
stubs=$(mktemp -d)
trap 'rm -rf "$stubs"' EXIT

# The stub answers `list` from $STUB_<resource> (or fails if STUB_FAIL names
# the resource) and logs every call.
cat > "$stubs/gcloud" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$STUB_LOG"
args=" $* "
for r in instances disks networks routers firewall-rules subnets; do
  case "$args" in
    *" $r list "*|*" networks $r list "*)
      if [ "${STUB_FAIL:-}" = "$r" ]; then echo "ERROR: (gcloud) permission denied" >&2; exit 1; fi
      v="STUB_${r//-/_}"; printf '%s' "${!v:-}"; exit 0 ;;
  esac
done
exit 0
STUB
chmod +x "$stubs/gcloud"

fail=0
# case <name> <want-exit> <grep that must match the log, or -> -- env...
case_() {
  local name=$1 want=$2 pat=$3; shift 3
  : > "$stubs/log"
  env PATH="$stubs:$PATH" STUB_LOG="$stubs/log" PROJECT=p "$@" bash "$sweep" > "$stubs/out" 2>&1
  local rc=$?
  if [ "$rc" != "$want" ]; then
    echo "FAIL $name: exit $rc, want $want"; sed 's/^/    /' "$stubs/out"; fail=1; return
  fi
  if [ "$pat" != - ] && ! grep -qE -- "$pat" "$stubs/log"; then
    echo "FAIL $name: no call matching '$pat'"; sed 's/^/    /' "$stubs/log"; fail=1; return
  fi
  echo "ok   $name"
}

case_ "nothing to sweep"            0 "instances list"
for r in instances disks networks; do
  case_ "failed $r listing fails"   1 - STUB_FAIL=$r
done
case_ "failed routers listing fails" 1 - STUB_FAIL=routers STUB_networks=zj-1-net
case_ "leftover instance: deleted, red" 1 "instances delete zj-1-n1 --zone z1" STUB_instances=$'zj-1-n1 z1\n'
case_ "leftover network: rules then net" 1 "networks delete zj-1-net" \
  STUB_networks=zj-1-net STUB_routers="zj-1-router r1" STUB_firewall_rules=$'zj-1-iap-ssh\nzj-1-internal' STUB_subnets="zj-1-subnet r1"
grep -q "firewall-rules delete zj-1-internal" "$stubs/log" || { echo "FAIL: second firewall rule not deleted"; fail=1; }
case_ "dry run deletes nothing"     1 - DRY_RUN=1 STUB_instances="zj-1-n1 z1"
grep -q "delete" "$stubs/log" && { echo "FAIL: dry run called delete"; fail=1; }
case_ "max_age_hours=0 refused"     2 - MAX_AGE_HOURS=0
case_ "max_age_hours=2 refused"     2 - MAX_AGE_HOURS=2
case_ "max_age_hours=x refused"     2 - MAX_AGE_HOURS=x
case_ "run scope: filters by run"   0 "labels.run_id=987 " RUN_ID=987
grep -q "name=zj-987-net" "$stubs/log" || { echo "FAIL: run-scoped network filter"; fail=1; }
grep -q "creationTimestamp" "$stubs/log" && { echo "FAIL: run scope must not filter by age"; fail=1; }
case_ "run scope, FOUND_EXIT=0"     0 "instances delete zj-987-n1" RUN_ID=987 FOUND_EXIT=0 STUB_instances="zj-987-n1 z1"
case_ "bad RUN_ID refused"          2 - "RUN_ID=1 OR name~."

[ "$fail" = 0 ] && echo "sweep.sh checks: all passed"
exit "$fail"
