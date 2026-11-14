# Plan

Slices after the first. Each names what it adds, what it checks, and what it
needs. Invariant names (I1a..I7, J1..J11, the fencing inequality) refer to
Zigcube's `docs/authority.md` and job documentation.

> #237 and #238 were read on 2026-11-14 (Zigcube issues, both open, filed
> 2026-11-12 from Zigbox/zigcube-tla's FINDINGS.md; line numbers there are at
> zigcube `f7736a7`). Slices 3 to 5 below follow their text. On zigcube main
> at `fb385b2` (2026-11-13) neither is fixed: `register` in
> `src/agent_api.zig` still PUTs `/nodes/<node>` with no compare, the
> keepalive still looks only at the lease, and the constants are unchanged.

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

## Slice 3: a forward step of the store's clock (#238 finding 3)

#238 finding 3 (`finding-store-setback`): lease deadlines are absolute values
of the **store's** clock. Postgres expires a lease by `clock_timestamp()`
(`src/store_postgres.zig:163`), SQLite by wall time, so a forward step of the
database host's clock (an NTP step, a VM resume, a failover to a standby whose
clock is ahead) shortens every lease at once. authority.md (line 261) says
absolute clock values do not matter; for the store they do. The agent learns
of it only as `LeaseNotFound` on its next keepalive and fences at once, which
bounds the overlap by the renewal interval plus the fence time, not by the I1b
window.

- Fault: step the clock of the **Postgres primary** forward by more than
  `ttl - renewal interval` (several seconds), with libfaketime under
  `postgres` (`FAKETIME_TIMESTAMP_FILE` rewritten by the nemesis; Postgres is
  dynamically linked, so libfaketime should reach `clock_timestamp()`; check
  it first). A variant: fail over to a standby started with its clock ahead.
- Not the agent's clock: the agent fences on `CLOCK_BOOTTIME`, which a
  wall-clock step does not move, and the inequality bounds clock **rates**,
  which no container can change. A rate fault on the agent stays a later,
  separate item (libfaketime's reach into the static Zig binaries is still
  unknown; README).
- Checker: slice 2's overlap checker (records in real time from the agents),
  and I1a throughout.
- Expected: an overlap of a replaced replica's old and new instances longer
  than `lost_grace_ms` + kill latency (1.9 s), bounded by the renewal interval
  plus `fence_stop_ms` + kill latency. #238's repair is the owner's choice (a
  stated bound on forward steps, or deadlines on a store-side monotonic
  counter); until then the run is an expected failure.

## Slice 4: a late agent step, by SIGSTOP (#238 finding 4)

#238 finding 4 (`finding-step-lag`): the agent checks its fence at the start
of a reconcile step (`src/agent.zig:479-482`), so a step that starts `d` late
fences `d` late. `fence_margin >= fence_stop_ms + ttl * skew + kill latency`
is met with equality (`src/constants.zig:132-157`), so any lag comes out of
`lost_grace_ms` (1000 ms), and a lag above it is an overlap. authority.md
assumes the agent "alive and scheduled" with no number.

- Fault: partition an agent from the server (keepalives fail), then SIGSTOP
  it shortly before its fence time `s + ttl - fence_margin` for `d` = 0.5,
  1.5 and 3 s, then SIGCONT. The fence time comes from the agent's
  `lease.json` (`sent_ms`, on `CLOCK_BOOTTIME`, which containers share with
  the host).
- Checker: overlap from the agents' records (slice 2), and I1a throughout.
- Expected: no overlap beyond the window for `d` under `lost_grace_ms`, and an
  overlap of about `d - lost_grace_ms` above it. That is finding 4: the
  inequality has no term for step lag.
- A SIGSTOP longer than the lease is the wedged node that authority.md already
  names as outside the I1b bound; run it too, report the overlap after
  SIGCONT as measured, not as a violation, and check that I1a holds.

## Slice 5: the stale registration PUT (#237)

"Register" is the agent's node registration,
`PUT /v1/agent/nodes/{node}` (`register` in `src/agent_api.zig`), which the
server applies with no compare. #237 (TLA+ finding 1): a registration PUT whose
agent restarted, or whose connection broke, is still at the server and lands
after the agent's next registration. `/nodes/<node>` then sits on the
abandoned lease L1, which nobody renews, while the agent's keepalives on L2 go
on succeeding (they look only at the lease). L1 expires, the node is lost, and
after `lost_grace_ms` its replicas are replaced while the agent is `held` with
its containers running. A timed-out PUT cannot do it (the agent's timeout is
the ttl, so its lease is gone); a restart or a broken connection can.

Done on `claude/jepsen-5`; see the section after slice 6.

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
- #238 finding 2 (`finding-spent-grace`): the lost grace is counted from the
  first `lost` record, so a node that is lost, registers again and expires
  again before the node controller judges the new tenure is replaced with no
  grace. Not planned in a slice yet; it needs two expiries of one node close
  together (a partition, a short heal, a partition).
