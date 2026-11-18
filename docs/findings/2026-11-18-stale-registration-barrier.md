# Stale registrations: what the barrier runs show (Zigcube 190f8dc, 7d141a7)

The test is `zcube-jepsen.stale-register` (`docs/stale-register.md`). It
uses the real server over mTLS, with no proxy. Below are two separate
findings. Keep them separate: (a) is about whether a held registration
lands, and its evidence is the barrier runs; (b) is about which code an
earlier stale-put run tested.

## The runs

| run | server | agent | result |
|---|---|---|---|
| `stale-register-control`, local docker, ttl 10 s | 190f8dc (0.5.2) + barrier patch | 190f8dc | `:stale-registration-landed`: held L1 = 2, L2 = 3 registered at revision 6; release gave `succeeded 1 revision 9` (server log: PUT 200); `/nodes/n4` at 9 on lease 2, mod_revision 9; the store holds a put at 9 on lease 2 after lease 3 |
| `stale-register-candidate`, local docker, ttl 10 s | 7d141a7 (#281) + barrier | 190f8dc | `:refused`: held L1 = 2, L2 = 3 registered at revision 6; release gave `succeeded 0` (server log: PUT 503); `/nodes/n4` at 8 on lease 3, mod_revision 6 |
| `stale-register-control`, GCP | 190f8dc (0.5.2) + barrier patch | 190f8dc | *pending: GCP dispatch after merge* |
| `stale-register-candidate`, GCP | 7d141a7 (#281) + barrier | 190f8dc | *pending: GCP dispatch after merge* |

The local summaries are `docs/stale-register/control-local.edn` and
`docs/stale-register/candidate-local.edn`, copied from each run's
`stale-register.edn`. They were written by an earlier checker. They hold
the same steps and reads, but not the keys the current checker adds
(`:tls?`, `:leases-after-done`, `:released-put-log`, `:finding-a`,
`:finding-b`, `:after`). So they show what happened, but the current
checker's proof of each verdict is first recorded by the GCP runs (or a
local rerun, if one is added below).

The GCP rows get filled in from that run's summary table and each test's
`stale-register.edn`: its `:outcome`, `:why`, `:finding-a`, `:finding-b`,
and `:after`.

### How the GCP result reads (decided)

Each test is run with the outcome it is expected to show:
`--expect refused` for the candidate and
`--expect stale-registration-landed` for the control. A test exits 0 only
when its outcome is the expected one, and that outcome was proved from the
evidence (`docs/stale-register.md`, "Checks"). So:

- a green job means the control showed the landing and the candidate showed
  the refusal, each proved;
- a red job means at least one test was `:vacuous`, `:unknown`, or
  `:unexpected` (for example, the control was refused, or the candidate
  landed).

The job summary's `outcome` column gives each test's outcome, so the table
can be read without opening the edn files.

## (a) On 190f8dc, a delayed registration could overwrite a live tenure

At 190f8dc, `register` in `src/agent_api.zig` applied a node's registration
with no lease compare. So a PUT on L1 that was delayed until after the same
agent had registered on L2 still committed, while L1 was live. That put
`/nodes/<node>` back on L1, over the L2 tenure. The control run shows this
on 0.5.2 itself.

- How the PUT is held: by the barrier at the server, not by a proxy.
- What the local control run shows (`control-local.edn`): `done-n4-2` says
  `succeeded 1 revision 9`; `/nodes/n4` at revision 9 is on lease 2 with
  mod_revision 9; the store holds a put of `/nodes/n4` at 9 on lease 2
  after the put on lease 3 at 6.

The candidate shows the fix. The same sequence against 7d141a7, with the
0.5.2 agent (which sends no `replaces`), ends with `succeeded 0`, the
server's log line for that PUT says 503, and `/nodes/n4` stays on L2 at its
own tenure (locally: lease 3, mod_revision 6).

What the candidate run does not show: the error code. The barrier's `done`
file records only whether the transaction succeeded, and the server's
request log records only the status. ZC-E0205 (lease order) is not visible
without a Zig change, and ZC-E0205 is also used for a lease that is not
found. The checker narrows this down instead: L1 and L2 must both be live in
the store right after `done`, so the refusal was not for a lost lease. A 503
for some other reason, such as an unavailable store, would still read as
`:refused`; this is noted in the checker's docstring.

### What the stale write left behind (not yet measured)

This is separate from (a). (a) is about whether the stale write lands; this
is about the state it leaves. In an earlier local control run, not kept, the
agent's next L2 keepalive was answered 503, and it granted a new lease and
registered again. That is one observation, with no saved evidence.

The checker now records it: `:observe-after` polls `/v1/nodes` for ttl +
lost grace + 5 s after the release, and keeps each change of `/nodes/n4`
(`:after` and `:finding-b` in `stale-register.edn`). The GCP runs give the
first saved measurement.

## (b) Run 44232537976's idle-node result came from older code

| run | server | agent | result |
|---|---|---|---|
| stale-put CI run [44232537976](https://github.com/Zigbox/zigcube-jepsen/actions/runs/44232537976) (stale-put.yml on main b3a9898) | Zigcube **f7736a7**, the workflow's pin | f7736a7 | idle: n4 lost for 20.2 s; running: overlap 0 ms |

Run 44232537976 was the stale-put workflow on main b3a9898. It built Zigcube
at that workflow's pin, `f7736a72f85c8ea21c1359c69a19539e00f0a5b9` (from
2026-11-12, `.github/workflows/stale-put.yml`). That is 25 commits before
190f8dc, and before #281. The run showed two things:

- **idle:** the stale registration landed, `/nodes/n4` was deleted when L1
  ran out, and n4 stayed `lost` for about 20.2 s, to the end of the run. All
  that time its agent was renewing L2 and was told nothing.
- **running:** zero overlap. The stale write made the agent fence, and it
  re-registered on L3.

This result describes f7736a7. It is not a measurement of 0.5.2 or of
7d141a7. Between f7736a7 and 190f8dc, `src/agent_api.zig` changed twice:
ad4c6ca (#258) and 00f35c1 (#254). fb385b2 (#225) also changed how long the
agent waits when it fences (`docs/stale-put.md`). So the 20.2 s and the zero
overlap should not be quoted for 0.5.2.

What carries over to 0.5.2 is (a): the stale write itself, which the
control run shows. Whether 0.5.2 would also leave an idle node lost, and for
how long, was not measured. stale-put needs plain HTTP, so it cannot run on
a mTLS (B7+) build.
