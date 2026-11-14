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
   or a value at two versions, fails the check as well. api versions are
   the store's **global** revision, so a lost write is not caught that way:
   after the store goes back, other keys move the revision past it, and the
   lost write would be spliced into the order (review of PR #2). So the api
   store read also reads each register key's history from Zigcube's
   `store_log`; above the log's compaction point (the server keeps 1000
   revisions) the surviving chain decides the order, and an acknowledged
   write or read not on it fails the check (`:off-chain`). Below it, such
   ops are counted as `:unverifiable`. Knossos stays on as a cross-check. The API still has no compare-and-set, so the api model
   stays a plain register; pg cas ops now use unique values too.
3. **Unfenced failover.** Done: `--failover unfenced`. The pair is cut
   apart from each other only (the server and the clients still reach both
   nodes), and the standby is promoted with the old primary alive.
   - api: `zcube-server` stays on the old primary for `--repoint-delay-ms`
     (5 s), then is repointed.
   - pg: there is no repoint. The JDBC URL lists both hosts with
     `targetServerType=primary`, so from the promotion on a reconnecting
     client may reach either primary; open connections stay where they are.
   The verdict allows a ZC-E0215 halt only in this mode, and even then Elle
   and Knossos must pass (for api, Elle includes the store-log check of
   item 2).

   **What this mode does and does not show.** With `--replication sync`
   (`remote_apply`, `synchronous_standby_names = '*'`) it cannot produce an
   acknowledged write on the old timeline at all, whatever the repoint
   delay: the old primary has lost its synchronous standby, so every commit
   on it blocks until the client's timeout or the rejoin, and the client
   records `:info`; the promoted node inherits the same setting and has no
   standby until the rejoin, so it cannot commit either. "Unfenced, no lost
   write" here is a property of synchronous replication, **not** evidence
   that fencing is unnecessary: an asynchronous or quorum setup without
   fencing can lose acknowledged writes, which the `unfenced-async` CI job
   shows (below). What the sync unfenced mode does exercise is the known
   Postgres hazard of a commit that is locally visible on the old primary
   but not yet replicated: a read there can see a write that the rejoin
   then wipes. Elle would flag such a read; none was seen.

   **unfenced-async** (CI job, review of PR #2): `--replication async
   --failover unfenced --expect lost-write`. The old primary keeps
   acknowledging writes while it is still reachable; the rejoin wipes them.
   It passes only if the checkers catch that loss, like the negative
   control.
   Local pg run (sync): valid, no halt (pg has no server to halt).
4. **Negative control.** Done: `--replication async --failover lagged
   --expect lost-write`. The standby is asynchronous and cut off for
   `--lag-ms` (4 s) before a fenced promotion, so writes acknowledged in
   that window are lost. The run is valid only if `sanity` is exactly
   `true` (not vacuous), the final store read worked, Knossos or Elle
   caught it, and Elle's store-based facts show the loss itself
   (`:lost-evidence`: a re-used version, an acknowledged op off the
   surviving chain, or an acknowledged write beyond the store's version of
   its key); any other anomaly alone does not count. A ZC-E0215 halt is reported
   (`:halted?`) but does not count: the control shows that the checkers
   catch the loss, not that Zigcube does. Local pg run: caught
   by both Knossos and Elle (G-single-item, G0-realtime, G1c,
   incompatible-order; versions re-used for two values).
5. **Measure the outage.** Done: the `:outage` checker. Per failover, ms
   from its start to the first acknowledgement of a write issued after the
   promotion finished (an earlier write can still commit on the old
   primary), the longest stretch between consecutive write acks, and when
   the promotion and the rejoin finished. Local pg numbers: fenced 4.3 to
   4.7 s; unfenced 9.3 s for the failover whose writes were in flight.
   With `synchronous_standby_names = '*'`, the promoted primary cannot
   commit until a standby is back, so the outage ends at the **rejoin**
   (3 s after the failover in this schedule, plus the `pg_basebackup`), not
   at the promotion (about 0.3 s). For an operator: a sync pair that loses
   a node stops taking writes until it has two again.

CI runs a matrix of workload (`api`, `pg`) by mode (`fenced`, `unfenced`,
`negative-control`, `unfenced-async`), eight jobs, plus `lein test` (checker
unit tests, including a table test of the verdict and the vacuity reasons).

The negative controls (`negative-control`, `unfenced-async`) pass only with
direct evidence of the loss from Elle's store-based facts (`:lost-evidence`:
a version re-used for two values, an acknowledged op off the surviving
chain, or an acknowledged write beyond the store's version of its key), not
on any anomaly, and not on a ZC-E0215 halt. A failed rejoin now makes a run
vacuous (`:a-rejoin-failed`), and every node's db setup first flushes
iptables, so a re-run after a killed run does not inherit its rules.

**Local-run cut.** In the session that built this, Docker worked (with a
local base image: the sandbox's proxy takes only HTTPS, so the node image's
apt sources had to be rewritten, which is not committed) and Leiningen came
from apt (2.10.0). The `pg` workload ran locally in all three modes, 60 s
each. The `api` workload did **not** run locally: building Zigcube was not
allowed in that sandbox, so the api runs (and ZC-E0215 under an unfenced
failover) were checked only in CI.

**CI result** (run 43441912526, PR #2, head `ec813a1`, 120 s per run):
all eight jobs green, `lein test` green. Outage counts only writes issued
after the promotion finished, measured from the start of the failover.

| workload | mode | verdict | caught by (loss evidence) | write outage per failover (ms) |
|---|---|---|---|---|
| api | fenced | valid, no halt | - | 4361 to 4428 (4); 1 of 5 never recovered |
| api | unfenced | valid, **no** ZC-E0215 halt | - | 9715 to 11858 (4) |
| api | unfenced-async | lost write, server halted | Elle (`:off-chain`, `:beyond-store`) | 5207 (1); 3 of 4 never recovered (halt) |
| api | negative control | lost write, server halted | Elle (`:off-chain`, `:beyond-store`) | none recovered (4 of 4, halt) |
| pg | fenced | valid | - | 4296 to 4336 (4); 1 of 5 never recovered |
| pg | unfenced | valid | - | 9421 to 9575 (4) |
| pg | unfenced-async | lost write | Knossos and Elle (`:version-conflicts`, `:beyond-store`) | 5175 to 5291 (4) |
| pg | negative control | lost write | Knossos and Elle (`:version-conflicts`, `:beyond-store`) | 4367 to 4691 (4) |

The halt alone no longer counts: both api lost-write runs were caught by
Elle from the store-log chain check, with the halt reported apart.

So unfenced failover with sync replication ended in stalls (about 9.5 to
12 s, up to the rejoin) and never in a halt; as item 3 explains, sync
replication rules out a lost acknowledged write there by construction.
The ZC-E0215 path was exercised by the negative control and by
`unfenced-async`.

Open:
- `:writes-ok-after` used to count a write acknowledged on the old primary
  before the fence landed (12 and 9 ms in two pg fenced failovers, 207 ms
  in the api negative control). Fixed: only writes issued after the
  promotion finished count.
- api: a lost write at or below the log's compaction point is not
  detectable from the store; `:unverifiable` counts the ops this affects.
- pg fenced and api fenced in CI each had one failover of 5 marked
  `:never-recovered` (no write issued after it was acknowledged before the
  next failover or the end). Not a safety problem (Elle and Knossos pass);
  it recurred, so check in the `jepsen-store-*-fenced` artifacts whether
  it is always the last failover, cut off by the end of the run.
- Sync unfenced does not exercise split brain (item 3). `unfenced-async`
  now does, with an expected loss. Still open (review of PR #2): recording
  which node served each op, with a `sanity` fact that the unfenced window
  actually served ops from the old primary; and a variant in which the
  promoted node drops `synchronous_standby_names` (the operator's emergency
  step), which would need its own expectation, since a Postgres commit
  waiting on its sync standby is already visible to other sessions there.
- The partition nemesis's heal (`iptables -F`) also removes the pair rules
  of a failover; safe only because the schedule never overlaps the two.

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

**Done (branch `claude/jepsen-2`).** `--workload lease`; README "The lease
test". What changed from the plan above:

- The fake runtime's state has no stop times, so every replica runs a
  recorder that writes its own start, heartbeats and TERMs on the host clock.
- I1a is replayed from a watch of the whole run, not by `GET /v1/list?at=`
  per revision (the watch gives every revision in one pass). The store-side
  cross-check (`store_log` on the primary) is not done.
- Each service has one replica, so old and new instances are paired by
  service; the server's decisions are not needed for the pairing.
- The fencing inequality is checked from `src/constants.zig` of the Zigcube
  that ran. It cannot be broken from here: `fence_margin_ms` is a constant
  and the server refuses a ttl the margin cannot cover (ZC-E0121). The
  smallest test-only switch, **proposed, not added** (Zigcube is read-only
  from here): a teeth agent root `src/zcube_agent_fence_teeth.zig`, after
  the `zcube_agent_teeth.zig` precedent, declaring
  `c1_teeth_fence_margin_ms` (say 1000) that `fencing.zig` reads in place of
  `fence_margin_ms` on the agent only, built as a separate
  `zcube-agent-fence-teeth`. The server keeps the real margin and accepts
  ttl 10000; the agent fences 2 s later than the server assumes, its KILL
  lands after the lost grace, and I1b must fail by about 1.1 s with no
  wedge. Until then the negative control is a SIGSTOPped agent node, which
  must and does break I1b (20.6 s).
- Finding: at `f7736a7` a fence's KILL comes ~8 s late on an agent cut off
  from the server (README "Findings"); the lease jobs pin `fb385b2`, which
  fixes it. `fb385b2` differs from `f7736a7` only in `agent_loop.zig`,
  `agent.zig` (one line), `controller.zig`, build.zig and docs.
- Not reached: the model's FINDINGS 1 (stale registration PUT re-attaching
  an orphan lease: needs an agent restart or a connection broken with the
  PUT in flight) and 2 (no lost grace for a second expiry: needs a node
  controller a whole lease behind). Both are for slice 3/4 faults; the I1b
  checker applies unchanged.

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
