# Plan

Slices after the first. Each names what it adds, what it checks, and what it
needs. Invariant names (I1a..I7, J1..J11, the fencing inequality) refer to
Zigcube's `docs/authority.md` and job documentation.

> Written without access to the Zigcube repository (see README, "What is not
> covered yet"). Invariants and findings are referred to by the names given in
> the brief; their exact statements, and any detail of how a finding
> reproduces, must be filled in from `docs/authority.md`, `docs/api.md`,
> `docs/agent.md` and issues #237 and #238 before the slice is built. Items
> marked **(to confirm)** are assumptions.

## Slice 1b: finish slice 1 against `zcube-server`

Blocked only on read access to `Zigbox/zigcube`.

1. CI: check out Zigcube at a pinned commit of `main`, build `zcube-server`
   and `zcube-agent` as `AGENTS.md` describes, copy the binaries into the
   nodes. If the repository is private, the workflow needs a read-only deploy
   key or token as an Actions secret.
2. Deployment: `zcube-server` on `n3` pointed at the Postgres pair; agents
   with FakeRuntime on `n4`, `n5`. Decide how the server finds the primary
   after failover (multi-host connection string, or a restart by the nemesis)
   from how `pg_dev` does it.
3. Client: replace the JDBC client in `register.clj` with an HTTP client for
   the register (compare-and-set) surface in `docs/api.md`, keeping the same
   `read` / `write` / `cas` op shapes so generator and checker are unchanged.
   Map responses carefully: only a response that proves the op did not happen
   is `:fail`; timeouts and 5xx are `:info`.
4. Run clients from inside the cluster or add the control host to the
   partition grudge, so that partitions cut clients from the server and the
   server from Postgres, not only primary from standby.
5. Keep the direct-to-Postgres test as a second job: when the HTTP test
   fails, it tells store faults from server faults.
6. Negative control: a run with `synchronous_commit = local` on the client
   sessions, or an unfenced failover, that is expected to be non-linearizable,
   to show the checker catches it here.

## Slice 2: leases and fencing (I1b, measuring overlap)

- Workload: slots and replicas assigned to agents through the API; agents on
  `n4`, `n5` (and more containers if needed) run FakeRuntime and write their
  own start and stop records, with local timestamps, to a file on the node.
- Faults: partition an agent from the server, and the server from Postgres,
  for longer than the lease.
- Checkers:
  - at most one live assignment per slot at any point in the server's
    history (from the API and from the store);
  - no two running copies of a replica beyond the I1b window, computed from
    the agents' start/stop records collected after the run, not from the
    server's view. Report the measured maximum overlap, not only pass/fail;
  - the fencing inequality, with the bounds actually configured.
- Needs: a common time base for records from different nodes. Without clock
  faults the containers share the host clock, so raw timestamps compare
  directly. Record `CLOCK_MONOTONIC` as well; it is also shared.

## Slice 3: clock steps (#238 finding 3)

- Fault: skew and step the clock of one process (agent, then server) with
  libfaketime: `FAKETIME_TIMESTAMP_FILE` rewritten by the nemesis.
- First task: establish whether libfaketime affects the Zigcube binaries at
  all (static linking, raw syscalls, vDSO; see README). If not, choose
  between a test-only clock offset in the binaries and KVM guests.
- Checker: slice 2's overlap checker. Records must carry both the faked wall
  clock and the true monotonic clock so overlap is measured in real time.
- Expected: reproduces finding 3 **(to confirm against the issue)**.

## Slice 4: SIGSTOP of an agent (#238 finding 4)

- Fault: SIGSTOP an agent for longer than its lease, then SIGCONT, alone and
  combined with a partition. The classic paused-leaseholder case: the agent
  resumes believing it still holds the lease.
- Checker: overlap from the agents' records; FakeRuntime must timestamp a
  "still running" heartbeat record so a resumed copy is visible.
- Expected: reproduces finding 4 **(to confirm against the issue)**.

## Slice 5: the stale register PUT (#237)

- Workload: the register workload of slice 1b, plus a client that is delayed
  (its request held by a partition or by SIGSTOP of a proxy) so that its PUT
  arrives after newer writes.
- Checker: linearizability with Knossos; if the API exposes versions, also
  Elle's list-append or rw-register over versioned writes for a cheaper,
  more precise anomaly report.
- Expected: a non-linearizable history showing #237 **(to confirm)**.

## Slice 6: jobs (J1..J11)

- Workload: submit, cancel and observe jobs through the API under all of the
  above faults.
- Checkers: each of J1..J11 that can be decided from API responses and agent
  records. List, per invariant, whether it is observable from outside; the
  ones that are not stay with the in-tree checker (`src/testing/history.zig`).

## Cross-cutting

- Unfenced Postgres failover (old primary alive behind a partition), and
  failover racing a partition, as extra nemesis schedules.
- Longer and randomised schedules as a nightly job; the push job stays short.
- A table in the README mapping each invariant to the test that checks it.
