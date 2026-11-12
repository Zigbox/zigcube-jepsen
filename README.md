# zigcube-jepsen

A Jepsen battery for Zigcube: a real multi-node deployment under network
partitions, clock faults, process pauses and Postgres failover, with checkers
for linearizability of the store, the authority invariants and the job
invariants. It lives out of the Zigcube tree by decision (Clojure and
Leiningen are allowed here, not there).

**Status: slice 1, partial.** The cluster, the fault primitives and one
linearizability test run and pass. The test does **not yet go through
`zcube-server`**; it drives the Postgres store directly. See
[What is not covered yet](#what-is-not-covered-yet).

## What runs

One test, `zcube-register-pg`:

| Part       | What it is |
|------------|------------|
| Nodes      | 5 Docker containers `n1`..`n5` (Ubuntu 24.04), each with its own network namespace, on bridge `10.77.0.0/24`. Jepsen runs on the host and drives them over SSH. |
| Deployment | `n1`: Postgres 16 primary. `n2`: synchronous streaming standby (`synchronous_standby_names = '*'`, `synchronous_commit = on`). `n3`..`n5`: empty, reserved for `zcube-server` and two `zcube-agent`s. |
| Workload   | Compare-and-set registers, one row per key: `read`, `write`, `cas`. 10 workers, 2 per key, 60 ops per key. Clients connect to whichever of `n1`/`n2` is primary. |
| Faults     | A loop of: random-halves partition of all 5 nodes (iptables) for 8 s, heal, **failover** (SIGKILL the primary, promote the standby), then **rejoin** (re-seed the old primary with `pg_basebackup` as the new synchronous standby). |
| Checkers   | Knossos linearizability (`:linear`, CAS-register model) per key; a timeline per key; a sanity check that fails the run if any op kind never succeeded or fewer than `--min-failovers` failovers completed, so the test cannot pass vacuously. |

A write or cas that errors after being sent is recorded as indeterminate
(`:info`); a read that errors, or any op that never got a connection, is a
definite `:fail`.

## How to run it

Needs Docker, a JDK (17+), and Leiningen (or `curl` the `lein` script as CI
does). The control process must be able to reach the container IPs, so run on
Linux.

```sh
docker/up.sh              # build the node image, start n1..n5, add them to /etc/hosts (uses sudo)
docker/check-faults.sh    # which fault primitives work on this host
lein run test --ssh-private-key docker/.ssh/id_ed25519 --time-limit 120 --concurrency 10
docker/down.sh
```

Results land in `store/zcube-register-pg/<time>/`: `results.edn`,
`history.edn`, `jepsen.log`, per-key timelines under `independent/`, and each
Postgres log under `n1/`, `n2/`. `lein run serve` browses them.

Options: `--time-limit S`, `--concurrency N` (a multiple of 2),
`--ops-per-key N`, `--min-failovers N`.

CI: `.github/workflows/jepsen.yml` does the above on `ubuntu-24.04` for every
push to `main` and `claude/harness`, and uploads `store/` as an artifact.

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

- **`zcube-server` is not in the loop.** The brief for this slice was a
  register workload against `zcube-server`'s API. This session could not read
  the Zigcube repository (access to `Zigbox/zigcube` was not granted to it),
  so the API shape, the build steps and the commit to pin are unknown here.
  Nothing about Zigcube's API was guessed. The workload talks to Postgres with
  its own `registers` table instead, which exercises the harness, the faults
  and the checker, and says something about the store's replication setup,
  but nothing about Zigcube's own code.
- **CI does not build Zigcube** for the same reason.
- **Failover is fenced.** The nemesis kills the old primary before promoting.
  An unfenced failover (old primary alive behind a partition, still serving
  reads) is the interesting case for the fencing inequality and is not run.
- **Partitions do not cut clients off.** Clients run on the control host,
  which is never partitioned from the nodes. A partition here only separates
  primary from standby (commits stall) when the random halves split them.
- **No clock faults and no SIGSTOP in the test**, only the probe.
- **No negative control.** There is no run that is expected to fail, so the
  checker has not been shown to catch a real violation in this setup.
- Authority invariants (I1a..I7), job invariants (J1..J11), and the model
  findings in Zigcube #237 and #238: see `docs/plan.md`.

## Layout

```
project.clj                     Jepsen 0.3.5, pgjdbc
src/zcube_jepsen/core.clj       test map, generator, CLI
src/zcube_jepsen/postgres.clj   primary + sync standby setup, failover nemesis
src/zcube_jepsen/register.clj   CAS register client (JDBC today)
docker/                         node image, up/down, fault probe
docs/plan.md                    later slices
```

## License

Apache 2.0. See `LICENSE`.
