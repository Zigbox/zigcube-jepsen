# Plan

Slices after the first. Each names what it adds, what it checks, and what it
needs. Invariant names (I1a..I7, J1..J11, the fencing inequality) refer to
Zigcube's `docs/authority.md` and job documentation.

> Issues #237 and #238 could not be read from the session that wrote this
> (its GitHub access covers only this repository, and no document in the
> Zigcube tree quotes them). What each finding says, and how it reproduces,
> must be filled in from the issues before slices 3 to 5 are built. Items
> marked **(to confirm)** are assumptions.

## Slice 1b: remainder

Done: CI builds Zigcube at a pinned commit; `zcube-server` on `n3`, agents on
`n4`, `n5`; the register goes through `POST /v1/apply` and `GET /v1/list`; the
Postgres-only test is a second job. Left:

1. **Partitions that cut clients off.** Clients run on the control host,
   which no partition includes. Run them inside the cluster, or add the
   control host to the grudge.
2. **A compare-and-set op.** The API has none, so the model is a plain
   register. `/v1/list` returns `mod_revision`; a read that carries it turns
   the history into a versioned register, which Elle can check more cheaply
   and more precisely than Knossos.
3. **Unfenced failover.** Promote the standby while the old primary is alive
   behind a partition and the server still points at it. `docs/operations.md`
   says the server remembers the highest revision in `--state-dir` and halts
   with ZC-E0215 on a store that went backwards; this is the run that tests
   it. With `synchronous_commit = remote_apply` the old primary cannot
   acknowledge a write alone, so the expected result is stalls, not loss.
4. **Negative control.** A run expected to fail (asynchronous standby, which
   Zigcube documents as unsupported, plus failover), to show the checker
   catches a real loss here.
5. **How the server takes a failover.** The nemesis rewrites the `pg` name on
   `n3` and does not restart the server. Record from the logs whether the
   server reconnects by itself and how long writes fail.

## Slice 2: leases and fencing (I1b, measuring overlap)

- Workload: slots and replicas assigned to agents through the API; agents on
  `n4`, `n5` (and more containers if needed) run FakeRuntime and write their
  own start and stop records, with local timestamps, to a file on the node.
- Faults: partition an agent from the server, and the server from Postgres,
  for longer than the lease.
- Checkers:
  - **I1a**: at every revision each replica id has at most one assignment
    (`/assigned/<node>/...` read with `GET /v1/list?at=<revision>`, and from
    the store after the run). No fault exempts this one;
  - **I1b**: a replaced replica's old instance and its replacement do not
    both run for longer than `lost_grace_ms` plus kill latency, computed from
    the agents' start/stop records collected after the run, not from the
    server's view. Report the measured maximum overlap, not only pass/fail.
    A replacement has a new replica id, so the pairing of old and new comes
    from the server's decisions;
  - the fencing inequality with the constants actually configured:
    `fence_margin >= fence_stop_ms + ttl * skew + kill latency` (defaults:
    `ttl` 10 s, `fence_stop_ms` 2 s, `skew` 1 %, `fence_margin` 3 s,
    `lost_grace_ms` 1 s). The agent fences at `s + ttl - fence_margin` on
    `CLOCK_BOOTTIME`, where `s` is when it sent its last successful
    keepalive.
- Needs: a common time base for records from different nodes. The containers
  share the host's clocks, so timestamps compare directly. The fake runtime
  keeps one `zcube-agent supervise <dir>` process per container; whether its
  state directory already holds usable start and stop times, or a watcher on
  the node must record them, is the first thing to find out.

## Slice 3: clock steps (#238 finding 3)

- Fault: skew and step the clock of one process (agent, then server) with
  libfaketime: `FAKETIME_TIMESTAMP_FILE` rewritten by the nemesis.
- First task: establish whether libfaketime affects the Zigcube binaries at
  all (static linking, raw syscalls, vDSO; see README). If not, choose
  between a test-only clock offset in the binaries and KVM guests.
- The agent's fencing deadline is on `CLOCK_BOOTTIME` and the inequality
  bounds clock **rate** skew, not absolute values. A wall-clock step should
  therefore change nothing, and the fault that matters is a rate change
  (`faketime -f '+0 x1.05'`) with `FAKETIME_DONT_FAKE_MONOTONIC` off, if
  libfaketime reaches `CLOCK_BOOTTIME` at all. Neither is a clock a container
  can change for real.
- Checker: slice 2's overlap checker. Records must carry both the faked wall
  clock and the true monotonic clock so overlap is measured in real time.
- Expected: reproduces finding 3 **(to confirm against the issue)**.

## Slice 4: SIGSTOP of an agent (#238 finding 4)

- Fault: SIGSTOP an agent for longer than its lease, then SIGCONT, alone and
  combined with a partition. The classic paused-leaseholder case: the agent
  resumes believing it still holds the lease.
- Checker: overlap from the agents' records; FakeRuntime must timestamp a
  "still running" heartbeat record so a resumed copy is visible.
- `docs/authority.md` already states that the I1b bound fails for a wedged
  node ("the agent is frozen") and that nothing on the server can know. So an
  overlap here is the documented limit; the run measures how long it lasts
  after SIGCONT, and checks that I1a still holds throughout.
- Expected: reproduces finding 4 **(to confirm against the issue)**.

## Slice 5: the stale register PUT (#237)

- "Register" here is most likely the agent's node registration,
  `PUT /v1/agent/nodes/{node}`, not the register workload **(to confirm)**:
  a registration from an old tenure that arrives after a newer one.
- Workload: an agent client driven by the test (or a real agent behind a
  proxy that can hold a request), delayed by a partition or SIGSTOP so its
  PUT arrives after the node registered again.
- Checker: I1a's second half, at most one tenure per node under which an
  observation is admissible, and I2, no transaction commits under a
  superseded tenure, read from the store's history.
- Expected: a history showing #237 **(to confirm)**.

## Slice 6: jobs (J1..J11)

- Workload: submit, cancel and observe jobs through the API under all of the
  above faults.
- Checkers: each of J1..J11 (`docs/core.md`) that can be decided from API
  responses and agent records. From outside: J1 (no job container alive more
  than `overlap_ms_max` after its job is terminal), J3 (a terminal status
  never changes), J4 (a watcher of `GET /v1/jobs/watch` that is killed and
  resumed misses no ending), J5, J6 and J8 (bounds on how long a container
  outlives its stop or deadline). J7 (each start has a committed claim) needs
  the store. The rest stay with the in-tree checker
  (`src/testing/history.zig`) unless the issue text says otherwise.

## Cross-cutting

- Unfenced Postgres failover (old primary alive behind a partition), and
  failover racing a partition, as extra nemesis schedules.
- Longer and randomised schedules as a nightly job; the push job stays short.
- A table in the README mapping each invariant to the test that checks it.
