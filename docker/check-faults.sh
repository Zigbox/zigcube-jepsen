#!/usr/bin/env bash
# Probe which fault-injection primitives work on this host's containers.
# Prints one line per primitive: OK, or NO with the reason. Exits non-zero
# only if a primitive the battery relies on today (iptables, SIGSTOP) fails.
set -uo pipefail
rc=0
say() { printf '%-28s %s\n' "$1" "$2"; }

# 1. iptables partition between two nodes, and heal.
if docker exec n1 ping -c1 -W2 n2 >/dev/null 2>&1 \
   && docker exec n1 iptables -w -A INPUT -s 10.77.0.12 -j DROP \
   && ! docker exec n2 ping -c1 -W2 n1 >/dev/null 2>&1 \
   && docker exec n3 ping -c1 -W2 n1 >/dev/null 2>&1 \
   && docker exec n1 iptables -w -F \
   && docker exec n2 ping -c1 -W2 n1 >/dev/null 2>&1; then
  say "iptables partition" OK
else say "iptables partition" "NO"; rc=1; docker exec n1 iptables -w -F; fi

# 2. libfaketime: per-process clock offset (skew) and rate (drift).
real=$(docker exec n1 date +%s)
fake=$(docker exec n1 faketime -f '+120s' date +%s)
d=$((fake - real))
if [ "$d" -ge 119 ] && [ "$d" -le 122 ]; then say "libfaketime skew (+120s)" "OK (observed +${d}s)"
else say "libfaketime skew (+120s)" "NO (observed ${d}s)"; rc=1; fi

# 3. libfaketime step of an already-running process via FAKETIME_TIMESTAMP_FILE.
docker exec n1 sh -c 'echo "+0" > /tmp/ft; rm -f /tmp/ft.out
  FAKETIME_NO_CACHE=1 FAKETIME_TIMESTAMP_FILE=/tmp/ft \
  LD_PRELOAD=/usr/lib/x86_64-linux-gnu/faketime/libfaketime.so.1 \
  sh -c "date +%s; sleep 1; date +%s" > /tmp/ft.out & sleep 0.5; echo "+300s" > /tmp/ft; wait'
a=$(docker exec n1 sed -n 1p /tmp/ft.out); b=$(docker exec n1 sed -n 2p /tmp/ft.out)
if [ $((b - a)) -ge 299 ]; then say "libfaketime live step" "OK (stepped +$((b - a))s)"
else say "libfaketime live step" "NO (delta $((b - a))s)"; fi

# 4. date -s: expected to be refused. CLOCK_REALTIME is not namespaced.
if out=$(docker exec n1 date -s '+60 seconds' 2>&1); then
  say "date -s (real clock)" "WORKS -- and it stepped the HOST clock; do not use"
else say "date -s (real clock)" "NO (expected): $(echo "$out" | head -1 | sed "s/.*: //")"; fi

# 5. SIGSTOP / SIGCONT of a process on a node.
docker exec -d n1 sleep 300
sleep 0.3
if docker exec n1 pkill -STOP -x sleep \
   && docker exec n1 sh -c 'ps -o stat= -C sleep | grep -q T' \
   && docker exec n1 pkill -CONT -x sleep; then say "SIGSTOP/SIGCONT" OK
else say "SIGSTOP/SIGCONT" NO; rc=1; fi
docker exec n1 pkill -x sleep
exit $rc
