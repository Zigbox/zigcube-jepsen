# Slice 5: the stale registration PUT (Zigbox/zigcube#237)

## What the test does

`zcube-jepsen.stale-put` (its own `-main`; the register test is untouched)
runs zcube-server on n3 against Postgres on n1/n2, and real agents on n4 and
n5 (FakeRuntime). The agent on n4 talks to the server through a TCP proxy in
the Jepsen control process (`hold_proxy.clj`), which can hold one request.

Schedule (nemesis steps, recorded in `:steps`):

1. `:arm`: hold the next `PUT /v1/agent/nodes/n4`.
2. `:start-agent n4`. It grants lease L1, and the proxy holds its
   registration PUT, which the agent sees as a slow server.
3. `:kill-agent n4`, then `:start-agent n4` again. The new agent grants L2 and
   registers, and that PUT passes. This is the "agent restarted while its PUT
   was still at the server" case of #237.
4. Depending on `--release-after`:
   - `running` (#237 case a): apply 3 single-replica services, wait until n4
     runs them, then start n5, wait until it is registered and ready
     (`:await-ready`), and release the held PUT.
   - `registered` (case b): start n5, wait until it is ready, and release
     right after n4's second registration, then apply.
   - `--services 0` with `registered` ("idle"): nothing to run (#237's
     liveness note).
5. `:observe` for 30 s, then `:collect`. Collect reads the server's
   `store_log`, the agents' process records (`resources/proc-watch.sh`: every
   100 ms, the replica ids whose fake container command is alive) and the
   proxy's request log.

The release is sent while L1 is still live (the default ttl is 10 s), so a
correct server must refuse it. Every wait (`:await-registered`,
`:await-running`, `:await-ready`) has a deadline; a wait that runs out, or a
step that throws, makes the run vacuous.

## Checks (all in `stale_put.clj`, unit-tested in `stale_put_test.clj`)

- **I2, stale registration:** a write of `/nodes/<n>` on a lower lease than
  an earlier write of it.
- **I1a, tenure half:** a replay of the store history.
  - Tenure is the mod_revision of `/nodes/<n>`.
  - An observation is admissible iff its tenure and assignment match and
    node-status is ready.
  - The replay flags two admissible observations of one replica id, and
    observations written under a superseded tenure.
- **Overlap:** from the agents' records, per service, the longest time two
  replicas (an old and a new instance) may have been alive at once. **This
  is a minimal overlap checker of slice 5's own**, made to keep clear of
  slice 2's general one (`claude/jepsen-2`).
  - A run of samples naming a replica is widened to the node's samples on
    either side of it (the replica may have started just after the sample
    before, and stopped just before the sample after).
  - A gap between samples (the watcher stalled) is unknown, not absent: it
    never splits a run.
  - A replica is put in its service by its `/assigned` row, or, if it has
    none in the log, by its id (`<service>-<n>`).
  - The containers share the host clock.
- **Liveness:**
  - How long n4 was `lost` at the end of the run.
  - "Orphan keepalives": renewals of one of n4's leases that the server
    answered 200 after the release while `/nodes/n4`, replayed from the
    store at the time of the answer, named another lease or none. (The time
    of each `/nodes/n4` put is the `at_ms` of the node-status the server
    wrote for that tenure.) A renewal of L3 after the agent registered on L3
    is not one.
- **Verdict:** `judge`, with `--expect auto|bug|valid`. The default is
  `auto`.
  - `:expected-failure-237` (passes): the release committed and left a stale
    registration in the store.
  - `:expected-failure-237` needs the stale row to be the release's own
    put: the revision its answer names, on L1.
  - `:fixed` (passes, but only if every check holds): the server refused the
    release while L1 was live. The refusal must be a 4xx answered before L1's
    earliest possible end (grant request + ttl), not ZC-E0205, and either
    carry a code in `refusal-codes` (ZC-E0105, ZC-E0510) or have left
    `/nodes/n4` on L2's registration (read right after the answer). The run
    must also be complete: collect worked, the store log starts at revision
    1 with nothing compacted, L2's registration is in it, and every agent
    node (and every node a replica was assigned to) has process samples.
    Otherwise the checks could hold because there was nothing to check.
  - Fails: the release landed without a stale registration; the run is
    vacuous (nothing held, a wait ran out, a 5xx, or a refusal that came
    after L1 could have ended or does not show L2 kept); the fixed run was
    incomplete; or it broke a check.

## Results at the CI pin (zigcube f7736a7)

These results were taken at f7736a7, the pin of the register job and of this
workflow. zigcube main fb385b2 still has `register` (`src/agent_api.zig`)
applying the PUT with no compare, so #237 is open there too, and the stale
registration should land the same way. But fb385b2 changed how long the
agent waits when it fences, so the timings below (how soon the agent's
containers were killed after the fence, and so how soon the replacements
started) describe f7736a7 only; they were not re-taken at fb385b2. Every local run gave
`:valid? true, :outcome :expected-failure-237`. The histories are in
`docs/stale-put/{running,registered,idle}.edn`, and the CI artifacts hold
the same for each run.

### running (case a)

`docs/stale-put/running.edn`, store rows:

```
rev 1   /nodes/n4         lease 2          <- the agent's second registration (L2)
rev 2   /node-status/n4   ready, tenure 1
rev 9-12  /assigned/n4/sp{0,1,2}-1
rev 13-14 /observed/n4/sp*-1   running
rev 15  /nodes/n4         lease 1          <- the held PUT, released: stale (I2)
rev 16  /node-status/n4   ready, tenure 15
rev 17  /observed/n4/sp*-1   deleted       (the server drops observations of tenure 1)
rev 18  /nodes/n4         lease 3          <- the agent fenced, granted L3, registered again
rev 19  /node-status/n4   ready, tenure 18
rev 21  /observed/n4/sp*-1   stopped, exit signal 9
rev 25-31 sp*-1 unassigned, sp*-2 assigned to n4
rev 32-33 /observed/n4/sp*-2 running
```

What happened:

- The stale write committed (rev 15).
- The agent's next report, `POST .../observed?known=17`, was answered 503.
  The agent fenced itself (`ZC-W0501`, "lease 2 could not be kept"), and its
  containers, which ignore TERM in this test, were KILLed about 2.0 s later
  (503 at ...369766, last sample alive at ...371765).
- It then took a new lease and re-registered (rev 18; again unconditional).
  Every replica was replaced on n4 itself.
- The I1a replay and the overlap check found nothing (overlap 0 ms), and the
  node was never lost.

So the overlap #237 predicts for case (a), about 5.9 s while L1 runs out,
**did not happen at this pin**. What happened instead was a needless fence
that restarted all of n4's work. The fence is the designed response: the
server's tenure compare refused the agent's report because its tenure (rev
1) was no longer `/nodes/n4`'s (rev 15), the ZC-E0510 path, and the agent
fences on that. It is needless only because the tenure change it reacts to
was the stale registration itself: the agent did nothing wrong and lost its
work. No invariant broke.

### registered (case b)

`docs/stale-put/registered.edn`: the stale registration landed at rev 4.
Once work was assigned, the agent fenced and re-registered on L3 (rev 15).
Unlike case a, not every replica was replaced: sp0-1 and sp1-1 were killed
and replaced by sp0-2 and sp1-2, but sp2-1 was only started after the agent
had re-registered and ran to the end of the run. No overlap and no I1a
violation.

### idle (case b with nothing to run)

`docs/stale-put/idle.edn`:

- The stale registration landed at rev 4.
- When L1 ended, `/nodes/n4` was deleted (rev 6), and node-status went
  `lost` at rev 7.
- n4 stayed lost to the end of the run (`:lost-for-ms 20311`), while the
  agent kept renewing L2 and was told nothing.

This is #237's liveness note, reproduced: the node cannot come back until the
agent restarts.

The committed histories' `:orphan-keepalives` fields came from earlier
versions of the check: the first missed `?known=` paths, and the one CI ran
(run 43533838746) counted every renewal of a lease other than L1, including
the legitimate renewals of L3 after the agent registered again (8 in
running and in registered). Re-running the current check over the three
committed histories gives:

| variant | orphan keepalives | |
|---|---|---|
| running | 0 | every renewal after the release was of L3, after `/nodes/n4` named L3; the agent never renewed L2 after the release |
| registered | 0 | the same |
| idle | 9 | all of L2: 2 while `/nodes/n4` named L1, 7 after it was deleted |

In CI run 43533838746 the idle variant counted 9 and n4 was lost for
20358 ms.

## How to run it

As in the README (5 nodes from `docker/up.sh`, Zigcube built), then:

```
lein run -m zcube-jepsen.stale-put test --ssh-private-key docker/.ssh/id_ed25519 \
  --release-after running            # or: registered; add --services 0 for idle
```

- Other options: `--lease-ttl-ms`, `--services`, `--spare-at before|after`,
  `--[no-]ignore-term`, `--observe-s`, `--expect auto|bug|valid`, and
  `--proxy-port`. The default port is 17070; the nodes reach the control
  host at their default gateway.
- The summary goes to `store/<test>/<time>/stale-put.edn`.

CI: `.github/workflows/stale-put.yml` runs all three variants with
`--expect auto`, as expected failures for #237:

- They stay green while the bug shows.
- They turn red if the stale PUT stops landing without the server refusing
  it.
- They pass as `:fixed` once the server refuses it, provided the other checks
  then hold.

## Not done

- Only the restart path of #237 is driven. The broken-connection path (the
  agent's connection drops but its PUT is still in the server) is not.
- In all three committed histories n5 never registered: there is no
  `/nodes/n5` or `/node-status/n5` row and no n5 process interval, so a
  replacement on n5 was impossible and every replica went back on n4. The
  harness started n5's agent with `--server n3:7070`, a name, while every
  other agent start (zcube/start-agent!, and n4's through the proxy) passes
  an IP address; n5 is now started with n3's IP. The step `:await-ready n5`
  now waits for n5's registration and ready status and makes the run
  vacuous if it does not come (and logs n5's agent log), so a run where n5
  could not take the work no longer passes silently. Whether n5 then
  takes the replicas, and whether an old instance on n4 overlaps a new one
  on n5, is for the next CI run to show; these histories predate the fix.
