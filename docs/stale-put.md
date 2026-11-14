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
     runs them, then start n5 and release the held PUT.
   - `registered` (case b): start n5 and release right after n4's second
     registration, then apply.
   - `--services 0` with `registered` ("idle"): nothing to run (#237's
     liveness note).
5. `:observe` for 30 s, then `:collect`. Collect reads the server's
   `store_log`, the agents' process records (`resources/proc-watch.sh`: every
   100 ms, the replica ids whose fake container command is alive) and the
   proxy's request log.

The release is sent while L1 is still live (the default ttl is 10 s), so a
correct server must refuse it.

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
  replicas (an old and a new instance) were alive at once. **This is a
  minimal overlap checker of slice 5's own**, made to keep clear of slice 2's
  general one (`claude/jepsen-2`).
  - A gap of 1 s or more in a replica's samples ends an interval.
  - The containers share the host clock.
- **Liveness:**
  - How long n4 was `lost` at the end of the run.
  - Keepalives on L2 that the server answered 200 after the stale PUT landed
    ("orphan keepalives").
- **Verdict:** `judge`, with `--expect auto|bug|valid`. The default is
  `auto`.
  - `:expected-failure-237` (passes): the release committed and left a stale
    registration in the store.
  - `:fixed` (passes, but only if every check holds): the server refused the
    release while L1 was live.
  - Fails: the release landed without a stale registration; the run is
    vacuous (nothing held, or the refusal came because L1 had already ended);
    or the fixed run broke a check.

## Results at the CI pin (zigcube f7736a7)

zigcube main fb385b2 still has `register` (`src/agent_api.zig`) applying the
PUT with no compare, so #237 is open. Every local run gave
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
  containers, which ignore TERM in this test, were KILLed about 2.5 s later.
- It then took a new lease and re-registered (rev 18; again unconditional).
  Every replica was replaced on n4 itself.
- The I1a replay and the overlap check found nothing (overlap 0 ms), and the
  node was never lost.

So the overlap #237 predicts for case (a), about 5.9 s while L1 runs out,
**did not happen at this pin**. What happened instead was a needless fence
that restarted all of n4's work. That is a wrong outcome even though no
invariant broke: the agent did nothing wrong and lost its work.

### registered (case b)

`docs/stale-put/registered.edn`: the stale registration landed at rev 4.
Once work was assigned, the agent fenced and re-registered on L3, as in case
a. No overlap and no I1a violation.

### idle (case b with nothing to run)

`docs/stale-put/idle.edn`:

- The stale registration landed at rev 4.
- When L1 ended, `/nodes/n4` was deleted (rev 6), and node-status went
  `lost` at rev 7.
- n4 stayed lost to the end of the run (`:lost-for-ms 20311`), while the
  agent kept renewing L2 and was told nothing.

This is #237's liveness note, reproduced: the node cannot come back until the
agent restarts.

This history's `:orphan-keepalives` is empty because of a path-matching bug in
the check (it missed `?known=`). That bug was fixed after the run; the CI runs
of the idle variant have the count.

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
- Every run had the node's replicas placed back on n4. A schedule where n5
  takes them (so that an old instance on n4 and a new one on n5 could
  overlap) was not reached: n4 was always the only ready node when the apply
  ran.
