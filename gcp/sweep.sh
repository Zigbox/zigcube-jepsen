#!/usr/bin/env bash
# Deletes what a GCP Jepsen run left behind: every instance labelled
# purpose=jepsen, and every network named zj-* with its routers (and their
# NAT), firewall rules and subnets, created more than MAX_AGE_HOURS ago.
# Exits 1 if it found anything (after deleting it), so the scheduled
# workflow fails loudly; 0 if there was nothing.
#
# Env: PROJECT, MAX_AGE_HOURS (default 4). DRY_RUN=1 lists without deleting.
set -euo pipefail

: "${PROJECT:?}"
hours=${MAX_AGE_HOURS:-4}
[[ "$hours" =~ ^[0-9]+$ ]] || { echo "MAX_AGE_HOURS must be a whole number" >&2; exit 2; }
cutoff=$(date -u -d "-${hours} hours" +%Y-%m-%dT%H:%M:%SZ)
g() { gcloud --project "$PROJECT" --quiet "$@"; }
found=0
run() {
  echo "+ $*"
  if [ "${DRY_RUN:-0}" != 1 ]; then g "$@"; fi
}

echo "sweeping project $PROJECT: purpose=jepsen instances and zj-* networks created before $cutoff"

while read -r name zone; do
  [ -n "$name" ] || continue
  found=1
  echo "::warning::leftover instance $name ($zone)"
  run compute instances delete "$name" --zone "$zone" --delete-disks=all
done < <(g compute instances list \
           --filter="labels.purpose=jepsen AND creationTimestamp<'$cutoff'" \
           --format='value(name,zone.basename())')

# Disks of instances that were deleted without them (auto_delete is on, so
# normally none).
while read -r name zone; do
  [ -n "$name" ] || continue
  found=1
  echo "::warning::leftover disk $name ($zone)"
  run compute disks delete "$name" --zone "$zone"
done < <(g compute disks list \
           --filter="labels.purpose=jepsen AND -users:* AND creationTimestamp<'$cutoff'" \
           --format='value(name,zone.basename())')

while read -r net; do
  [ -n "$net" ] || continue
  found=1
  echo "::warning::leftover network $net"
  # An instance without the label in that network (should not exist) would
  # block the deletion; say so rather than delete something unlabelled.
  others=$(g compute instances list --filter="networkInterfaces.network~/networks/${net}\$" --format='value(name)')
  if [ -n "$others" ]; then
    echo "::error::network $net still has instances: $others"
    continue
  fi
  while read -r r region; do
    [ -n "$r" ] && run compute routers delete "$r" --region "$region"
  done < <(g compute routers list --filter="network~/networks/${net}\$" --format='value(name,region.basename())')
  while read -r f; do
    [ -n "$f" ] && run compute firewall-rules delete "$f"
  done < <(g compute firewall-rules list --filter="network~/networks/${net}\$" --format='value(name)')
  while read -r s region; do
    [ -n "$s" ] && run compute networks subnets delete "$s" --region "$region"
  done < <(g compute networks subnets list --filter="network~/networks/${net}\$" --format='value(name,region.basename())')
  run compute networks delete "$net"
done < <(g compute networks list \
           --filter="name~^zj- AND creationTimestamp<'$cutoff'" \
           --format='value(name)')

if [ "$found" = 1 ]; then
  echo "::error::the sweeper found leftovers of a GCP Jepsen run (deleted unless DRY_RUN=1); a teardown failed, look at the runs older than ${hours} h"
  exit 1
fi
echo "nothing to sweep"
