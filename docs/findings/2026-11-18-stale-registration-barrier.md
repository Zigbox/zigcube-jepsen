# Stale registrations: what the barrier runs show (Zigcube 190f8dc, 7d141a7)

The test is `zcube-jepsen.stale-register` (`docs/stale-register.md`). It
uses the real server over mTLS, with no proxy. Below are two separate
findings. Keep them separate: the evidence for (a) is the control run, and
(b) is about which code an earlier run tested.

## The runs

| run | server | agent | result |
|---|---|---|---|
| stale-put CI run [44232537976](https://github.com/Zigbox/zigcube-jepsen/actions/runs/44232537976) (stale-put.yml on main b3a9898) | Zigcube **f7736a7**, the workflow's pin | f7736a7 | idle: n4 lost for 20.2 s; running: overlap 0 ms |
| `stale-register-control` | 190f8dc (0.5.2) + barrier patch | 190f8dc | *pending: GCP dispatch after merge* |
| `stale-register-candidate` | 7d141a7 (#281) + barrier | 190f8dc | *pending: GCP dispatch after merge* |

The two stale-register rows get filled in from the GCP run, from its
summary table and each test's `stale-register.edn`, for example its
`:outcome`, `:why`, `done`, and `/nodes/n4` at N. The expected outcomes are
`:stale-registration-landed` (exit 1) for the control and `:refused`
(exit 0) for the candidate.

## (a) On 190f8dc, a delayed registration could overwrite a live tenure

At 190f8dc, `register` in `src/agent_api.zig` applied a node's registration
with no lease compare. So a PUT on L1 that was delayed until after the same
agent had registered on L2 still committed, while L1 was live. That put
`/nodes/<node>` back on L1 and ended the L2 tenure the agent was working
under. The control run shows this on 0.5.2 itself.

- How the PUT is held: by the barrier at the server, not by a proxy.
- What the run shows: `done-n4-L1` should say `succeeded 1`, and `/nodes/n4`
  at that revision should be on L1. The checker fails the run as
  `:stale-registration-landed` and gives the reason.

The candidate shows the fix. The same sequence against 7d141a7, with the
0.5.2 agent (which sends no `replaces`), should end with `succeeded 0`
(503 ZC-E0205) and `/nodes/n4` still on L2 at its own tenure.

## (b) Run 44232537976's idle-node result came from older code

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
ad4c6ca (#258) and 00f35c1 (#254). fb385b2 (#225) also changed how long the agent
waits when it fences (`docs/stale-put.md`).
So the 20.2 s and the zero overlap should not be quoted for 0.5.2.

What carries over to 0.5.2 is (a): the stale write itself, which the
control run shows. Whether 0.5.2 would also leave an idle node lost, and for
how long, was not measured. stale-put needs plain HTTP, so it cannot run on
a mTLS (B7+) build.
