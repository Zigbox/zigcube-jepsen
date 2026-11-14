# zigcube-jepsen

A Jepsen battery for Zigcube: a real multi-node deployment under network
partitions, clock faults, process pauses and Postgres failover, with checkers
for linearizability of the store, the authority invariants and the job
invariants. It lives out of the Zigcube tree by decision (Clojure and
Leiningen are allowed here, not there).

**Status: slice 1 done.** Five nodes, the fault primitives, and a register
test through `zcube-server`'s API under partitions and Postgres failover run
and pass in CI. What the test does not yet do is listed under
[What is not covered yet](#what-is-not-covered-yet).

## What runs

Two tests on the same cluster and the same fault schedule.

| Part       | What it is |
|------------|------------|
| Nodes      | 5 Docker containers `n1`..`n5` (Ubuntu 24.04), each with its own network namespace, on bridge `10.77.0.0/24`. Jepsen runs on the host and drives them over SSH. |
| Postgres   | `n1`: Postgres 16 primary. `n2`: synchronous streaming standby (`synchronous_standby_names = '*'`, `synchronous_commit = remote_apply`, the setup Zigcube's `docs/operations.md` supports). |
| Faults     | A loop of: random-halves partition of all 5 nodes (iptables) for 8 s, **with the control host (the clients) on one side at random**: the nodes of the other half drop its traffic to ports 5432 and 7070; heal, **failover** (SIGKILL the primary, check no postgres process is left on it, then promote the standby; if the old primary is not confirmed stopped the op fails and nothing is promoted), then **rejoin** (re-seed the old primary with `pg_basebackup` as the new synchronous standby). `--failover unfenced` and `lagged` change the failover (below). After the load: heal, rejoin, 15 s settle, **final reads** (two per key, retried until `:ok`) of every key that was used, then a **store read** of every register straight from the current primary. |
| Checkers   | Knossos linearizability (`:linear`) per key, final reads included; a timeline per key; **Elle** (`:elle`) on the history as a versioned register (every `:ok` op carries the store's version of its value; the store read closes it), strict serializable, plus version/value conflicts; **outage** (`:outage`): per failover, ms until a write issued after it was acknowledged; **sanity**: the run is vacuous if an op kind never succeeded, fewer than `--min-failovers` failovers completed, or a failover failed. The verdict (`:valid?`) is below. |

**`--workload api`** (`zcube-register-api`, the default): through Zigcube.

- `n3` runs `zcube-server` on the Postgres pair; `n4` and `n5` run
  `zcube-agent` with the fake runtime. CI builds them from `Zigbox/zigcube`
  at a pinned commit of `main` (`ZIGCUBE_COMMIT` in the workflow), with the
  Zig toolchain Zigcube pins, checksum verified.
- The API has no general register. The register is **a service's replica
  count**: `write v` is `POST /v1/apply` of service `r<key>x` with
  `replicas: v+1`; `read` is `GET /v1/list?prefix=/services/r<key>x`. There
  is no compare-and-set op, so the model is a plain register. The agents
  really start and stop the fake containers the writes ask for.
- `200` is `:ok`. `400`, and `409` only with ZC-E0105 (the server's compare
  kept failing), are `:fail`, as is a request that never connected. Any other
  failure of a write is `:info`, since it may have committed: a timeout, a
  `503`, a `404`, or a `409` with another code. A failed read is `:fail`.
- Write values are a per-key counter (0, 1, 2, ...), never repeated within a
  key, so a read names the exact write it saw and a rollback past an
  acknowledged write cannot be explained by an `:info` write of the same
  value. The `pg` workload does the same since slice 1b: a cas expects the
  value of the latest write and sets a fresh one.
- Zigcube has no multi-host connection string; the operator repoints the
  store's address after a failover. Here that address is the name `pg` in
  `n3`'s `/etc/hosts`, which the failover nemesis rewrites after promoting.
  The server is not restarted.

**`--workload pg`** (`zcube-register-pg`): the store alone. Compare-and-set
registers in a table of their own, over JDBC to whichever of `n1`/`n2` is
primary; model is a CAS register. When the API test fails, this one tells a
store fault from a server fault.

Both use 10 workers, 2 per key, 60 ops per key.

**Failover modes and the verdict.**

| Mode | Options | Valid when |
|---|---|---|
| fenced | `--failover fenced` (default) | Knossos and Elle pass, not vacuous, store read worked, every finally-read key got an `:ok` read, no op saw `ZC-E0215` (Zigcube's halt when the store went back past a revision it had seen, answered `502`). |
| unfenced | `--failover unfenced` | The same, except a `ZC-E0215` halt is an allowed ending (then the final reads are waived; Elle must still pass, which for api includes every acknowledged write above the store log's compaction point being on the surviving store's history of its key). With sync replication neither side can commit while the pair is apart, so this mode shows stalls, not split brain, and says nothing about whether fencing is needed (it is: see `unfenced-async`). The pair is cut apart from each other only, the standby promoted with the old primary alive; api repoints `zcube-server` after `--repoint-delay-ms` (5000), pg clients have no repoint (the JDBC URL lists both hosts). |
| unfenced-async | `--replication async --failover unfenced --expect lost-write` | As the negative control: the old primary keeps acknowledging writes that the rejoin wipes, and the checkers must catch it. |
| negative control | `--replication async --failover lagged --expect lost-write` | Not vacuous, the store read worked, Knossos or Elle caught it (`:caught-by`), and Elle's store-based facts show the loss itself (`:lost-evidence`). A `ZC-E0215` halt is reported as `:halted?` but does not count. The standby is asynchronous and cut off for `--lag-ms` (4000) before a fenced promotion, so acknowledged writes are lost. |

### Last result

Slice 1b (branch `claude/jepsen-1b`), local runs of the `pg` workload, 60 s
each: fenced valid (write outage 4.3 to 4.7 s per failover), unfenced valid
(up to 9.3 s), negative control valid, i.e. the loss was caught by Knossos
and Elle. With a synchronous pair the outage lasts until the old primary has
rejoined as standby, not until promotion. The `api` workload was not run
locally (see `docs/plan.md`, slice 1b).

CI (run 43432518018, 120 s each), all six jobs green: api fenced valid
(outage 4.7 to 5.3 s), api unfenced valid with no `ZC-E0215` halt (9.5 to
9.9 s), api negative control caught by Elle and by a `ZC-E0215` halt; pg
fenced valid (up to 4.5 s), pg unfenced valid (9.4 to 9.8 s), pg negative
control caught by Knossos and Elle. (Before the PR #2 review fixes; see
`docs/plan.md`.)

Before slice 1b:

CI run [43354416522](https://github.com/Zigbox/zigcube-jepsen/actions/runs/43354416522)
on commit `1d1e4f7`, 120 s of load per job, both jobs green:

| workload | ok ops | failovers / rejoins | final reads | verdict |
|---|---|---|---|---|
| `pg` | 1555 write, 1712 read, 290 cas | 5 / 5 | 162 ok over 81 keys | every key linearizable, sanity valid |
| `api` | 449 write, 1592 read | 4 / 4 | 104 ok over 52 keys | every key linearizable, sanity valid |

Write values are unique per key, a final read of every key follows the heal,
and no op carried ZC-E0215. Earlier numbers (run 4 and the head run before
this change) came from the 5-value register without final reads and say
little about lost writes. Nothing yet checks that the agents fence themselves
during outages (slice 2).

## How to run it

Needs Docker, a JDK (17+), and Leiningen (or `curl` the `lein` script as CI
does). The control process must be able to reach the container IPs, so run on
Linux.

```sh
# Zigcube, for the api workload (needs Zig 0.17.0, libpq-dev, libsqlite3-dev):
git clone https://github.com/Zigbox/zigcube && (cd zigcube && zig build -Doptimize=ReleaseSafe)

docker/up.sh              # build the node image, start n1..n5, add them to /etc/hosts (uses sudo)
docker/check-faults.sh    # which fault primitives work on this host
lein run test --workload api --ssh-private-key docker/.ssh/id_ed25519 --time-limit 120 --concurrency 10
lein run test --workload pg  --ssh-private-key docker/.ssh/id_ed25519 --time-limit 120 --concurrency 10
docker/down.sh
```

Results land in `store/<test name>/<time>/`: `results.edn`, `history.edn`,
`jepsen.log`, per-key timelines under `independent/`, and the node logs
(`n1/`, `n2/` Postgres; `n3/server.log`; `n4/`, `n5/` `agent.log`).
`lein run serve` browses them.

Options: `--workload api|pg`, `--zcube-bin DIR` (default
`zigcube/zig-out/bin`), `--zcube-images FILE` (default
`zigcube/fixtures/images.zon`), `--lease-ttl-ms N`, `--time-limit S`,
`--concurrency N` (a multiple of 2), `--ops-per-key N`, `--min-failovers N`,
`--failover fenced|unfenced|lagged`, `--replication sync|async`,
`--expect valid|lost-write`, `--repoint-delay-ms N`, `--lag-ms N`,
`--[no-]client-partition`. `lein test` runs the checkers' unit tests. Elle
draws the cycles it finds with graphviz (`dot`); without it, it checks
without drawings.

CI: `.github/workflows/jepsen.yml` runs each workload in each mode (six jobs)
on `ubuntu-24.04` for every pull request and every push to `main`,
`claude/harness` and `claude/jepsen-1b`, and uploads `store/` as artifacts
`jepsen-store-<workload>-<mode>`.

## Fault primitives in containers

Measured by `docker/check-faults.sh`, which CI runs before the test.

| Primitive | Works? | Notes |
|-----------|--------|-------|
| iptables partitions | yes | Needs `--cap-add NET_ADMIN`. Each container has its own netns, so rules on one node do not affect another. Used by the test today. |
| Clock skew, per process (`faketime -f '+120s'`) | yes | libfaketime, `LD_PRELOAD`. Affects only processes started under it. |
| Clock step of a running process | yes | libfaketime with `FAKETIME_TIMESTAMP_FILE` and `FAKETIME_NO_CACHE=1`: rewrite the file, the process sees the step. |
| `date -s` / `settimeofday` / `adjtimex` | **no** | `CLOCK_REALTIME` is not namespaced. Linux time namespaces cover only `CLOCK_MONOTONIC` and `CLOCK_BOOTTIME`. With `SYS_TIME` a node would step the host and all five nodes at once, so the capability is deliberately not granted and the call fails with `Operation not permitted`. Jepsen's stock `nemesis.time` (which compiles a `settimeofday` helper) therefore cannot be used; clock faults must go through libfaketime. |
| SIGSTOP / SIGCONT | yes | Plain signals inside the container. |

Limits of libfaketime to keep in mind for the clock slices: it only
intercepts libc calls in dynamically linked processes. **A statically linked
binary, or one that makes raw `clock_gettime` syscalls or reads the vDSO
directly, is not affected.** Zig binaries are often static and do not go
through libc unless linked with it, so whether `zcube-server` and
`zcube-agent` can be skewed this way is not yet established. It also does not
fake `CLOCK_MONOTONIC` unless asked, and Postgres run under it is untested
here. If libfaketime cannot reach the Zigcube binaries, the fallbacks are a
clock-offset test hook in the binaries, or KVM guests with their own clocks.

## What is not covered yet

- **No compare-and-set through the API**, because the API has none. A plain
  register is a weaker test than a CAS register: it cannot show a lost
  update between two writers, only stale or impossible reads.
- **Only the apply and list paths of the server are exercised.** Agents run
  and are told to start containers, but nothing checks what they did.
- **No clock faults and no SIGSTOP in the test**, only the probe.
- **The api workload has run only in CI.** The session that wrote it could
  not build Zigcube locally (its Zigbox dependency could not be fetched
  there).
- Authority invariants (I1a..I7), job invariants (J1..J11), and the model
  findings in Zigcube #237 and #238: see `docs/plan.md`.

## Layout

```
project.clj                     Jepsen 0.3.5, pgjdbc, cheshire
src/zcube_jepsen/core.clj       test map, workloads, generator, CLI
src/zcube_jepsen/postgres.clj   primary + sync standby setup, failover nemesis
src/zcube_jepsen/zcube.clj      zcube-server and zcube-agent on the nodes
src/zcube_jepsen/api.clj        register client over zcube-server's HTTP API
src/zcube_jepsen/register.clj   CAS register client over JDBC (pg workload)
src/zcube_jepsen/nemesis.clj    partitions that include the control host
src/zcube_jepsen/checkers.clj   Elle versioned register, outage, sanity, verdict
test/                           checker unit tests
docker/                         node image, up/down, fault probe
docs/plan.md                    slices: what is done and what is next
```

## License

Apache 2.0. See `LICENSE`.
