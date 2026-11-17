#!/usr/bin/env bash
# Deletes what a GCP Jepsen run left behind: every instance (and orphan
# disk) labelled purpose=jepsen, and every network named zj-* with its
# routers (and their NAT), firewall rules and subnets.
#
# Two scopes:
#   - Age (the hourly gcp-sweeper.yml): everything created more than
#     MAX_AGE_HOURS ago. At least MIN_AGE_HOURS (3): a live run (150 min job
#     plus a 30 min teardown) never holds anything older, so the sweeper
#     cannot delete a cluster under a run that is still going.
#   - One run (gcp.yml's teardown, after tofu destroy): RUN_ID set, whatever
#     its age. That catches what an interrupted apply created but never
#     wrote to the state.
#
# Every listing is read before it is looped over, so a failed gcloud call
# (permissions, API, filter) fails the script instead of looking empty.
#
# Exits FOUND_EXIT (default 1) if it found anything (after deleting it, so
# the scheduled workflow goes red: some teardown failed), 0 if there was
# nothing, 2 on bad input, and gcloud's status if a call failed.
#
# Env: PROJECT; MAX_AGE_HOURS (default 4) or RUN_ID; DRY_RUN=1 lists
# without deleting; FOUND_EXIT.
set -euo pipefail

: "${PROJECT:?}"
MIN_AGE_HOURS=3
found_exit=${FOUND_EXIT:-1}
g() { gcloud --project "$PROJECT" --quiet "$@"; }
found=0
run() {
  echo "+ $*"
  if [ "${DRY_RUN:-0}" != 1 ]; then g "$@"; fi
}

if [ -n "${RUN_ID:-}" ]; then
  [[ "$RUN_ID" =~ ^[0-9a-z][0-9a-z-]{0,20}$ ]] || { echo "RUN_ID: as tofu's run_id" >&2; exit 2; }
  scope="run $RUN_ID"
  label_filter="labels.purpose=jepsen AND labels.run_id=$RUN_ID"
  net_filter="name=zj-${RUN_ID}-net"
else
  hours=${MAX_AGE_HOURS:-4}
  [[ "$hours" =~ ^[0-9]+$ ]] || { echo "MAX_AGE_HOURS must be a whole number" >&2; exit 2; }
  if [ "$hours" -lt "$MIN_AGE_HOURS" ]; then
    echo "MAX_AGE_HOURS=$hours: at least $MIN_AGE_HOURS, so a run in progress is never swept; to clean up one run now, re-run its 'tofu destroy' job (it sweeps that run)" >&2
    exit 2
  fi
  cutoff=$(date -u -d "-${hours} hours" +%Y-%m-%dT%H:%M:%SZ)
  scope="created before $cutoff"
  label_filter="labels.purpose=jepsen AND creationTimestamp<'$cutoff'"
  net_filter="name~^zj- AND creationTimestamp<'$cutoff'"
fi

echo "sweeping project $PROJECT: purpose=jepsen instances and zj-* networks, $scope"

instances=$(g compute instances list --filter="$label_filter" --format='value(name,zone.basename())')
while read -r name zone; do
  [ -n "$name" ] || continue
  found=1
  echo "::warning::leftover instance $name ($zone)"
  run compute instances delete "$name" --zone "$zone" --delete-disks=all
done <<<"$instances"

# Disks of instances that were deleted without them (auto_delete is on, so
# normally none).
disks=$(g compute disks list --filter="$label_filter AND -users:*" --format='value(name,zone.basename())')
while read -r name zone; do
  [ -n "$name" ] || continue
  found=1
  echo "::warning::leftover disk $name ($zone)"
  run compute disks delete "$name" --zone "$zone"
done <<<"$disks"

nets=$(g compute networks list --filter="$net_filter" --format='value(name)')
while read -r net; do
  [ -n "$net" ] || continue
  found=1
  echo "::warning::leftover network $net"
  # An instance without the label in that network (should not exist) would
  # block the deletion; say so rather than delete something unlabelled.
  others=$(g compute instances list --filter="networkInterfaces.network~/networks/${net}\$" --format='value(name)')
  if [ -n "$others" ] && [ "${DRY_RUN:-0}" != 1 ]; then
    echo "::error::network $net still has instances, not deleting it: $others"
    continue
  fi
  routers=$(g compute routers list --filter="network~/networks/${net}\$" --format='value(name,region.basename())')
  while read -r r region; do
    [ -n "$r" ] || continue
    run compute routers delete "$r" --region "$region"
  done <<<"$routers"
  rules=$(g compute firewall-rules list --filter="network~/networks/${net}\$" --format='value(name)')
  while read -r f; do
    [ -n "$f" ] || continue
    run compute firewall-rules delete "$f"
  done <<<"$rules"
  subnets=$(g compute networks subnets list --filter="network~/networks/${net}\$" --format='value(name,region.basename())')
  while read -r s region; do
    [ -n "$s" ] || continue
    run compute networks subnets delete "$s" --region "$region"
  done <<<"$subnets"
  run compute networks delete "$net"
done <<<"$nets"

if [ "$found" = 1 ]; then
  level=error; [ "$found_exit" != 0 ] || level=warning
  echo "::$level::found leftovers of a GCP Jepsen run ($scope; deleted unless DRY_RUN=1)"
  exit "$found_exit"
fi
echo "nothing to sweep"
