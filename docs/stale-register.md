# stale-register: #237's stale registration, held at the real server

`zcube-jepsen.stale-register` checks Zigcube's refusal of a stale
registration (Zigbox/zigcube#281, decision
`docs/decisions/2026-11-18-registration-lease-order.md` there). A
`PUT /v1/agent/nodes/<node>` carries a lease. If that lease is older than the
one `/nodes/<node>` already holds, the server must refuse it (503 ZC-E0205;
the test sees the 503, not the code, see "Checks").
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
5. Write `release-n4-L1`, then wait for `done-n4-L1`. Right after it
   appears, read L1's and L2's rows from the store's lease table
   (`zcube.store_lease`, via psql).
6. Read `GET /v1/nodes?at=N`. N is the revision the released PUT wrote if it
   succeeded. Otherwise N is the server's revision read right after `done`.
7. Watch `/nodes/n4` every 500 ms for the ttl + the lost grace + 5 s, and
   keep each change (`:observe-after`; about 16 s at the default ttl).
8. Read the store's log (`zcube.store_log`, via psql) to check I2, and the
   server log's lines for `PUT /v1/agent/nodes/n4`.

Every wait has a deadline. A step that runs out of time or throws makes the
run vacuous.

## Checks (`judge`, unit-tested in `stale_register_test.clj`)

Each verdict is proved from evidence. A missing file, a timeout, or a hold
that was never reached never reads as a refusal.

**`:vacuous`**: the run did not set up #237's race. Any of these:

- A step ran out of time or threw.
- The run did not use mTLS (the test forces `:zcube-tls-mode :on`).
- `held-n4-L1` was never written, so the hold was not reached.
- Not L0 < L1 < L2, or L2's tenure (its registration's mod_revision) is
  unknown.
- There was no release, or no `done` with its succeeded flag, or `done`
  came before the release (less 1 s of clock margin).
- `done` says `succeeded 1` at a revision not above L2's tenure (an in-order
  write, not a stale one).
- `done` came less than 1 s (`clock-margin-ms`) before L1's earliest end:
  the second agent start plus the ttl. The default ttl is 10 s.
- L1 or L2 was not live in the store's lease table right after `done`. A
  lease id is never reused, and its row goes when the lease ends, so a row
  live after `done` was live while the PUT ran.
- The read at N failed or was truncated, or the store's log was not read.

**Finding (a)**, whether the held PUT landed:

- `:landed`: `done` says `succeeded 1`, and the store has a put of
  `/nodes/n4` on L1 at `done`'s revision.
- `:refused`: `done` says `succeeded 0`; the server log has the held PUT's
  line with status 503 (the first `PUT /v1/agent/nodes/n4` logged within 5 s
  after `done`, which took at least as long as the hold); and the store has
  no put of `/nodes/n4` on L1 after L2's tenure.
- `:unknown`: anything else, for example no log line, another status, or a
  store that disagrees with `done`.

The barrier records only whether the answer was 200, and the request log
only the status. So ZC-E0205 itself is not observed. A 503 with both leases
live and the key still on L2 is what #281's lease order gives. A 503 for
another reason at that moment (an unavailable store) would also read as
`:refused`.

**Finding (b)**, the state: `/nodes/n4` at N (on L1 at `done`'s revision,
on L2 at its tenure, absent, or other), and its changes after
(`:observe-after`).

**Outcome**:

- `:stale-registration-landed`: (a) is `:landed` and `/nodes/n4` is on L1
  at N.
- `:refused`: (a) is `:refused`, `/nodes/n4` is on L2 at its tenure at N,
  and the store has no put of `/nodes/n4` on a lease lower than an earlier
  put's (I2, `stale-put/stale-registrations`).
- `:vacuous` or `:unknown` as above.
- `:unexpected`: anything else.

`--expect` names the one outcome that passes: `refused` (the default) or
`stale-registration-landed`. Every other outcome fails. The summary goes to
`store/<test>/<time>/stale-register.edn`.

## The two runs

| test | server | agent | must |
|---|---|---|---|
| `stale-register-candidate` | Zigcube 7d141a7 (#281 merged), barrier built in | 190f8dc (0.5.2), which sends no `replaces` | `:refused` (`--expect refused`) |
| `stale-register-control` | 190f8dc with the barrier patched in | 190f8dc | `:stale-registration-landed` (`--expect stale-registration-landed`) |

The control is a known-bad build. It shows that the barrier really reaches
the code path and that the checker sees a stale registration land. It is
run with `--expect stale-registration-landed`, so it exits 0 when the
landing is proved. It fails if it is refused, vacuous, or unknown. So the
GCP job is green only if both tests showed their expected outcome, and the
summary table's `outcome` column says which outcome each test had.

The control's patch is `gcp/patches/register-barrier-190f8dc.patch`.
- What it is: the whole of `git diff 190f8dc 7d141a7` for
  `src/server.zig`, `src/server_http.zig`, `src/zcube_server.zig` and
  `src/server_register_barrier.zig` (the hook and its wiring): after the
  patch those four files are byte-identical to 7d141a7. From `build.zig` it
  takes only the hunks for the `register-barrier` option; the rest of that
  file's diff is left out.
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
- **Checks.** Before the candidate build, the job checks that `zig build -h`
  at `zigcube_ref` lists `-Dregister-barrier` (it needs #281), and fails
  otherwise. After the builds, it fails if either barrier server lacks the
  string `ZCUBE_TEST_REGISTER_BARRIER`. It also fails if the normal `bin/`
  server or anything in `dist/` has it: every archive in `dist/` is unpacked
  and scanned, an archive that will not unpack fails the job, and so does a
  `dist/` with no archive in it.
- **Inputs.** `gcp/check-inputs.sh` requires `zigcube_ref` to be a full
  40-hex commit when a `stale-register-*` test is asked for, so the
  candidate's commit is fixed.

## How to run it

Locally, with the five nodes from `docker/up.sh`, build barrier binaries
from a Zigcube checkout:

```
zig build -Doptimize=ReleaseSafe -Dcpu=baseline -Dregister-barrier=true -p /tmp/sr
lein run -m zcube-jepsen.stale-register test --ssh-private-key docker/.ssh/id_ed25519 \
  --zcube-bin /tmp/sr/bin --zcube-images <zigcube>/fixtures/images.zon \
  --label candidate --expect refused
```

For the control, use its build and `--label control --expect
stale-registration-landed`.

The options are `--lease-ttl-ms` (default 10000; the server refuses more
with ZC-E0121, as its fence margin could not cover it), `--replication`,
`--label` and `--expect refused|stale-registration-landed` (default
`refused`). mTLS is always on; there is no `--zcube-tls` option.

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
