# Plan

Slices after the first. Each names what it adds, what it checks, and what it
needs. Invariant names (I1a..I7, J1..J11, the fencing inequality) refer to
Zigcube's `docs/authority.md` and job documentation.

> Issues #237 and #238 could not be read from the session that wrote this
> (its GitHub access covers only this repository, and no document in the
> Zigcube tree quotes them). What each finding says, and how it reproduces,
> must be filled in from the issues before slices 3 to 5 are built. Items
> marked **(to confirm)** are assumptions.

## Slice 1b: remainder (done on `claude/jepsen-1b`, green in CI)

Done before: CI builds Zigcube at a pinned commit; `zcube-server` on `n3`,
agents on `n4`, `n5`; the register goes through `POST /v1/apply` and
`GET /v1/list`; the Postgres-only test is a second job. Then:

1. **Partitions that cut clients off.** Done: `nemesis.clj`. The control
   host is a member of every partition: it lands on one half at random, and
   the nodes of the other half drop its traffic to the client ports (5432,
   7070). SSH stays open, as Jepsen's control plane.
   `--no-client-partition` restores the old behaviour.
2. **A versioned register checked with Elle.** Done: `checkers.clj`. Every
   `:ok` op carries `:version` (api: `mod_revision` from `/v1/list`, and the
   apply answer's `revision`; pg: a per-row counter). A final `:store-read`
   reads the store straight from the current primary. The history becomes
   an Elle list-append history (a read at version n reads every value up to
   n), checked for strict serializability; a version seen with two values,
   or a value at two versions, fails the check as well. Knossos stays on as
   a cross-check. The API still has no compare-and-set, so the api model
   stays a plain register; pg cas ops now use unique values too.
3. **Unfenced failover.** Done: `--failover unfenced`. The pair is cut
   apart, the standby promoted with the old primary alive, and clients (and
   `zcube-server`) repointed only after `--repoint-delay-ms` (5 s). The
   verdict allows a ZC-E0215 halt only in this mode, and even then the store
   read must hold every acknowledged write (Elle) and Knossos must pass.
   Local pg run: valid, no halt (pg has no server to halt).
4. **Negative control.** Done: `--replication async --failover lagged
   --expect lost-write`. The standby is asynchronous and cut off for
   `--lag-ms` (4 s) before a fenced promotion, so writes acknowledged in
   that window are lost. The run is valid only if it is not vacuous and
   Knossos, Elle or a ZC-E0215 halt caught the loss. Local pg run: caught
   by both Knossos and Elle (G-single-item, G0-realtime, G1c,
   incompatible-order; versions re-used for two values).
5. **Measure the outage.** Done: the `:outage` checker. Per failover, ms
   from its start to the first acknowledgement of a write issued after it
   started, the longest stretch with no write acknowledged, and when the
   promotion and the rejoin finished. Local pg numbers: fenced 4.3 to
   4.7 s; unfenced 9.3 s for the failover whose writes were in flight.
   With `synchronous_standby_names = '*'`, the promoted primary cannot
   commit until a standby is back, so the outage ends at the **rejoin**
   (3 s after the failover in this schedule, plus the `pg_basebackup`), not
   at the promotion (about 0.3 s). For an operator: a sync pair that loses
   a node stops taking writes until it has two again.

CI runs a matrix of workload (`api`, `pg`) by mode (`fenced`, `unfenced`,
`negative-control`), six jobs, plus `lein test` (checker unit tests).

**Local-run cut.** In the session that built this, Docker worked (with a
local base image: the sandbox's proxy takes only HTTPS, so the node image's
apt sources had to be rewritten, which is not committed) and Leiningen came
from apt (2.10.0). The `pg` workload ran locally in all three modes, 60 s
each. The `api` workload did **not** run locally: building Zigcube was not
allowed in that sandbox, so the api runs (and ZC-E0215 under an unfenced
failover) were checked only in CI.

**CI result** (run 43432518018, PR #2, head `fb8f4e5`, 120 s per run): all
six jobs green, `lein test` green.

| workload | mode | verdict | write outage per failover (ms) |
|---|---|---|---|
| api | fenced | valid, no halt | 4684 to 5314 (4 failovers) |
| api | unfenced | valid, **no** ZC-E0215 halt | 9488 to 9851 (4) |
| api | negative control | caught by Elle and ZC-E0215 (server halted) | 207 (the only failover that recovered before the halt) |
| pg | fenced | valid | 12, 4397, 9, 4499; one of 5 never recovered before the run ended |
| pg | unfenced | valid | 9384 to 9793 (4) |
| pg | negative control | caught by Knossos and Elle | - |

So unfenced failover, as scheduled here, ended in stalls (about 9.5 s,
the repoint delay plus the rejoin) and never in lost writes or a halt; the
ZC-E0215 path was exercised only by the negative control.

Open:
- The api negative control depends on Zigcube's state dir catching the
  loss (ZC-E0215) or the clients seeing it; if the server halts before any
  read shows the loss, `:caught-by` is `[:zc-e0215]` alone, which is still
  a pass but says less.
- `:writes-ok-after` can count a write acknowledged in the few ms before
  the nemesis's iptables rule landed (seen: 11 ms in one unfenced
  failover; in CI, 12 and 9 ms in two pg fenced failovers). Read it
  together with `:gap`.
- pg fenced in CI had one failover marked `:never-recovered` (no write
  issued after it was acknowledged before the next failover or the end).
  Not a safety problem (Elle and Knossos pass), but worth a look in the
  artifact `jepsen-store-pg-fenced` if it recurs.
- Unfenced did not provoke a halt: with a 5 s repoint delay the old
  primary is cut off from clients, so it takes no writes to diverge on. A
  schedule that lets clients keep writing to the old primary during the
  delay would be the stronger test.

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

- Failover racing a partition as an extra nemesis schedule (unfenced
  failover is done, slice 1b).
- Longer and randomised schedules as a nightly job; the push job stays short.
- A table in the README mapping each invariant to the test that checks it.
