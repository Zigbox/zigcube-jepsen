#!/usr/bin/env bash
# The node's own record of which fake containers run, for the overlap check
# (slice 5; slice 2 builds the general one). Every 100 ms it prints one line:
#   <wall ms> <replica id> <replica id> ...
# naming each replica whose command is alive: a process whose parent is a
# `zcube-agent supervise <state dir>/containers/<replica id>`. A supervisor
# with no live command does not count. The containers share the host's
# clock, so lines from different nodes compare directly.
while :; do
  t=$(date +%s%3N)
  r=$(ps -eo pid=,ppid=,args= | awk '
    { pid = $1; ppid = $2; $1 = ""; $2 = ""; a = $0
      if (a ~ /zcube-agent supervise /) { n = split(a, w, " "); d = w[n]; sub(/.*\//, "", d); sup[pid] = d }
      else { par[pid] = ppid } }
    END { for (p in par) if (par[p] in sup) printf " %s", sup[par[p]] }')
  echo "$t$r"
  sleep 0.1
done
