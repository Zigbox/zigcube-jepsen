# A fencing agent cut off from the server kills ~8 s late (Zigcube f7736a7)

Found by the slice 2 lease test. Reported to the supervisor, not filed.
Fixed on Zigcube `main` by `fb385b2` (#225), which was filed for a
different symptom of the same wait.

## Cause

`Agent.reconcile` runs the fence with no server call: the first
`fence_step` sends TERM, later steps send KILL from `fence.kill_at_ms`
(`fence_at + fence_stop_ms`) on. Between steps, `fence_step` returns
`at_ms = now + fence_poll_ms` with `acted = 0`, so `agent_loop` waits,
and at `f7736a7` every wait is a server watch (`agent_run.zig`
`Shell.watch` -> `RemoteStore.watch_once`). The watch's *hold* is capped
by `act_in_ms` (so 100 ms), but its I/O deadline comes from
`calls_deadline_ms`, which is `min(fence_at_ms(), wake)`, and
`fence_at_ms()` is null while `fence.active`. With no run deadlines that
is maxInt, so `exchange` sets no timeout and the call runs to
`agent_io_timeout_ms` (10 s) when the server does not answer: packets
dropped, or the server itself waiting on an unreachable store. The KILL step
comes ~10 s after TERM instead of 2 s, i.e. ~7 s after the server's lease
has ended, and the replacement runs alongside the old instance.

The model (zigcube-tla FINDINGS 4) has no term for a late KILL step, which
is why this was not caught there.

## History (local run, 2026-11-14, ttl 10000, lost grace 1000)

Faults (Jepsen nemesis, UTC): 00:19:25.517 `n4` cut off from the server
`n3`; 00:19:50.626 healed; 00:20:05.702 `n3` cut off from `n1`,`n2`
(Postgres); 00:20:30.822 healed.

Each replica runs a recorder that ignores TERM, so only the KILL ends it.
Times are the recorders' own (`$EPOCHREALTIME`, shared host clock):

| node | service | replica | pid | start | TERM noted | last heartbeat |
|---|---|---|---|---|---|---|
| n4 | l0x | l0x-1 | 420 | 00:18:55.493 | 00:19:32.122 | 00:19:42.320 |
| n5 | l0x | l0x-2 | 408 | 00:19:36.926 | 00:20:12.303 | 00:20:22.462 |
| n4 | l0x | l0x-3 | 454 | 00:20:32.693 | - | 00:21:15.975 |
| n5 | l1x | l1x-1 | 384 | 00:18:55.504 | 00:20:12.303 | 00:20:22.462 |
| n5 | l1x | l1x-2 | 440 | 00:20:32.698 | - | 00:21:15.975 |
| n4 | l2x | l2x-1 | 428 | 00:18:55.506 | 00:19:32.122 | 00:19:42.322 |
| n5 | l2x | l2x-2 | 416 | 00:19:36.937 | 00:20:12.303 | 00:20:22.462 |
| n4 | l2x | l2x-3 | 462 | 00:20:32.727 | - | 00:21:15.975 |
| n5 | l3x | l3x-1 | 392 | 00:18:55.515 | 00:20:12.303 | 00:20:22.462 |
| n5 | l3x | l3x-2 | 448 | 00:20:32.734 | - | 00:21:15.975 |
| n4 | l4x | l4x-1 | 436 | 00:18:55.552 | 00:19:32.122 | 00:19:42.321 |
| n5 | l4x | l4x-2 | 424 | 00:19:36.949 | 00:20:12.303 | 00:20:22.462 |
| n4 | l4x | l4x-3 | 470 | 00:20:32.736 | - | 00:21:15.975 |
| n5 | l5x | l5x-1 | 400 | 00:18:55.545 | 00:20:12.303 | 00:20:22.462 |
| n5 | l5x | l5x-2 | 456 | 00:20:32.744 | - | 00:21:15.976 |

- Fence by `n4`: TERM 00:19:32.122 (on time: last keepalive ~00:19:25.1 +
  7 s), KILL due 00:19:34.1. Replacements on `n5` started 00:19:36.93; the
  old instances' last heartbeats were 00:19:42.32, **10.2 s after TERM**.
  Overlaps 5395, 5386, 5371 ms; I1b's bound is 1900 ms.
- Fence by `n5` in the store partition: TERM 00:20:12.303, last heartbeat
  00:20:22.462, again 10.16 s. No overlap only because the replacements came
  at 00:20:32.7.
- I1a held at all 39 revisions; the server never assigned a replica twice.

The run is `store/zcube-lease-valid/20261114T001848.743Z` (local, not
committed). To reproduce: `lein run test --workload lease --expect overlap
--lease-faults partition` against binaries built at `f7736a7`; CI runs it
as `lease / late-kill`. A second local run gave overlaps of 5998 ms and
5206 ms.

## At fb385b2

`fence_step` returns `watch = false` and the loop pauses instead of
watching. The same schedule: every fenced instance gone 2.9 s before its
replacement started (max overlap -2912 ms). A regression test in Zigcube for
this effect (a fencing agent whose server does not answer still kills at
`kill_at_ms`) would keep it fixed; #225's tests are about the busy loop.
