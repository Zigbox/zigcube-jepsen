# stale-register: #237's stale registration, held at the real server

`zcube-jepsen.stale-register` checks Zigcube's refusal of a stale
registration (Zigbox/zigcube#281, decision
`docs/decisions/2026-11-18-registration-lease-order.md` there). A
`PUT /v1/agent/nodes/<node>` carries a lease. If that lease is older than the
one `/nodes/<node>` already holds, the server must refuse it (503 ZC-E0205).
The test runs this against the real server, over its mTLS `--listen`. No
proxy sits between the agent and the server, and the test does not use
stale-put's plain-HTTP hold proxy (`docs/stale-put.md`). stale-put is
unchanged and still needs plain HTTP.

## The barrier

Zigcube has a test-only hook, `src/server_register_barrier.zig`. It is
compiled in only with `zig build -Dregister-barrier=true`, a comptime option
that `dist` and `release` always set false. It is switched on at run time
with `ZCUBE_TEST_REGISTER_BARRIER=DIR`.

When on, the hook can hold one armed registration just before that
request's transaction. It talks to the test through files in `DIR` on the
server node (`/var/lib/zj-barrier`):

| who | writes | meaning |
|---|---|---|
| test | `arm-<node>` | hold the next registration of `<node>` |
| server | `held-<node>-<lease>` | holding it, and waiting |
| test | `release-<node>-<lease>` | let it go on |
| server | `done-<node>-<lease>` | `succeeded 0\|1 revision N at_ms T` |

If a held request gets no release, it goes on by itself after 300 s.

The harness passes the variable to the server through `:zcube-server-env`,
which `zcube/start-server!` hands to `start-daemon!` as `:env`. No other
test sets it.

## Steps

The held node is the first agent node, n4. The brief that asked for this
test calls it n1. The steps:

1. Start n4's agent and wait until it is registered and ready (lease L0).
2. Stop the agent and wait until L0 has run out: the server deletes
   `/nodes/n4`. A plain restart would not do, as the 0.5.2 agent keeps its
   lease in `lease.json` in its state directory and resumes it, with no new
   registration.
3. Arm n4 and start the agent. It grants L1 and registers, and the barrier
   holds that PUT. Wait for `held-n4-L1`.
4. Kill the agent, remove its `lease.json`, and start it again. It grants L2
   while L1 is still live, and registers; that PUT is not held. Wait until
   `/nodes/n4` is on L2 (L2 > L1) and ready. (Removing the file stands for an
   agent that lost its state, the one way to get a second grant while L1
   lives.)
5. Write `release-n4-L1`, then wait for `done-n4-L1`.
6. Read `GET /v1/nodes?at=N`. N is the revision the released PUT wrote if it
   succeeded. Otherwise N is the server's revision read right after `done`.
7. Read the store's log (`zcube.store_log`, via psql) to check I2.

Every wait has a deadline. A step that runs out of time or throws makes the
run vacuous.

## Checks (`judge`, unit-tested in `stale_register_test.clj`)

The test has three outcomes:

- **`:refused`** (passes). All of these hold:
  - `done` says `succeeded 0`.
  - At N, `/nodes/n4` is on L2 at L2's own tenure (mod_revision).
  - The store has no put of `/nodes/n4` on a lease lower than an earlier
    put's (I2, `stale-put/stale-registrations`).
- **`:stale-registration-landed`** (fails, and says why). Any of these:
  - The release succeeded.
  - `/nodes/n4` is back on L1.
  - The store has a stale put.
- **`:vacuous`** (fails). Any of these:
  - A step did not happen as planned.
  - L2 is not newer than L1.
  - The read failed or was truncated.
  - The store was not read.
  - The released transaction ran after L1 may have ended. Such a PUT is
    refused for a reason that is not #237's. L1 lives until at least the
    second agent start plus the ttl. `done`'s `at_ms` must come at least
    1 s before that (`clock-margin-ms`). The default ttl is 10 s.

Any other result is `:unexpected` and fails.

The summary goes to `store/<test>/<time>/stale-register.edn`.

## The two runs

| test | server | agent | must |
|---|---|---|---|
| `stale-register-candidate` | Zigcube 7d141a7 (#281 merged), barrier built in | 190f8dc (0.5.2), which sends no `replaces` | pass (`:refused`) |
| `stale-register-control` | 190f8dc with the barrier patched in | 190f8dc | fail (`:stale-registration-landed`) |

The control is a known-bad build. It shows that the barrier really reaches
the code path and that the checker sees a stale registration land. So its
run is red by design, and the GCP run as a whole exits 1. Read each test's
row in the summary table on its own.

The control's patch is `gcp/patches/register-barrier-190f8dc.patch`.
- What it is: `git diff 190f8dc 7d141a7` for `src/server.zig`,
  `src/server_http.zig`, `src/zcube_server.zig` and
  `src/server_register_barrier.zig` (the hook and its wiring), plus the
  `register-barrier` build option in `build.zig`.
- What it leaves out: #281's change to `agent_api.zig`, and the barrier's
  unit test.
- Where it applies: cleanly on 190f8dc.

## Where the barrier servers are built (ruling)

The supervisor ruled that **the barrier server never goes into a dist
tarball.** gcp.yml's `build` job builds it itself, from source, beside the
normal build. This happens only when a `stale-register-*` test is asked for:

- **Candidate.** `zcube-server` at `zigcube_ref`, built with
  `-Doptimize=ReleaseSafe -Dcpu=baseline -Dregister-barrier=true` into its
  own prefix.
- **Control.** Zigcube at `190f8dcfa60a22da62d82ad8bac714cb64848c59`
  (`STALE_REGISTER_CONTROL_REF` in gcp.yml), with the patch applied, and the
  same flags. Its `zcube-agent` is the agent for both runs. The patch does
  not touch the agent.
- **Packing.** Both go into the bundle under
  `stale-register/{candidate,control}/` (`bin/`, `images.zon`, `COMMIT`),
  apart from `bin/` and `dist/`.
- **Checks.** The job fails if either barrier server lacks the string
  `ZCUBE_TEST_REGISTER_BARRIER`. It also fails if the normal `bin/` server
  or anything in a `dist/` tarball has it.

## How to run it

Locally, with the five nodes from `docker/up.sh`, build barrier binaries
from a Zigcube checkout:

```
zig build -Doptimize=ReleaseSafe -Dcpu=baseline -Dregister-barrier=true -p /tmp/sr
lein run -m zcube-jepsen.stale-register test --ssh-private-key docker/.ssh/id_ed25519 \
  --zcube-bin /tmp/sr/bin --zcube-images <zigcube>/fixtures/images.zon --label candidate
```

The options are `--lease-ttl-ms` (default 10000; the server refuses more with ZC-E0121, as its fence margin could not cover it), `--replication`,
`--zcube-tls auto|on|off` and `--label`.

On GCP:

```
gh workflow run gcp.yml --repo Zigbox/zigcube-jepsen --ref main \
  -f zigcube_ref=7d141a70c07181496c27543808bcc784b3b701dd \
  -f tests="stale-register-candidate stale-register-control" \
  -f time_limit=120
```

`time_limit` is not used by these two tests. It only has to pass the input
check.

Results: `docs/findings/2026-11-18-stale-registration-barrier.md`.
