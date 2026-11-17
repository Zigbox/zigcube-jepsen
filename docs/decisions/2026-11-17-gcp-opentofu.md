# Jepsen on GCP, with OpenTofu, keyless (J3 slice 1)

Date: 2026-11-17. Status: accepted (owner decisions, binding); implemented
on `claude/j3-gcp`, not yet run live.

## Context

The harness ran only on Docker on a GitHub runner: five containers sharing
one kernel and one wall clock, which limits the faults (README, "Fault
primitives in containers") and the realism of timing. J3 moves it to real
VMs. The owner bootstrapped a GCP project by script: a CI service account
(`jepsen-ci@...`) with `compute.instanceAdmin.v1`, `compute.networkAdmin`,
`compute.securityAdmin`, `iap.tunnelResourceAccessor`,
`compute.osAdminLogin`, and `storage.objectAdmin` on the state bucket only;
it cannot enable APIs or create service accounts. A Workload Identity
Federation provider admits this repository (id 1455702118, owner 347124131)
on `refs/heads/main` only. Repository variables (not secrets, none are
secret): `GCP_PROJECT_ID`, `GCP_WIF_PROVIDER`, `GCP_SERVICE_ACCOUNT`,
`GCP_REGION` (us-central1), `GCP_ZONE`, `TF_STATE_BUCKET` (versioned,
private). Regional CPU quota: 24.

## Decisions

1. **Everything in OpenTofu, created and destroyed by CI.** `tofu/` holds
   the whole cluster. A run applies it, runs Jepsen, and destroys it in a
   job that always runs. Nothing is created by hand or by gcloud, except
   that the sweeper (6) deletes leftovers with gcloud.
2. **Workload Identity Federation only.** `google-github-actions/auth` with
   `workload_identity_provider` and `service_account`; no service-account
   key is created, stored or used, ever. The credential file the action
   writes is a federation config (no key) and is never shipped: the harness
   goes to the VM as `git archive HEAD`. No token is printed or put on a
   command line. **Only jobs that run nothing but this repository's code
   from main may ask for a token.** `id-token: write` lets every step of a
   job mint one, so the Zigcube ref (any branch, tag or commit, possibly
   someone else's code, whose `build.zig` runs at build time) is built in a
   separate `build` job with `contents: read` only, and reaches the live
   job as an artifact that is copied to the control VM as it is, never
   unpacked or run on the runner. Workflow-level permissions are `{}`; each
   job asks for its own. What `build` still holds is the run's Actions
   runtime token (it can upload artifacts of this run), which has no reach
   into GCP.
3. **PRs are offline.** Since the provider admits only main, pull requests
   run `tofu fmt -check`, `tofu validate` (`-backend=false`), `tofu test`
   (a plan against a mocked provider, which checks names, addresses,
   labels, no external IP, no service account, OS Login), shellcheck and
   actionlint. Live runs are `workflow_dispatch` on main (a schedule may
   come later): land a reviewed PR, merge, dispatch.
4. **Network.** A VPC per run (`zj-<run_id>-net`), one subnet
   (10.78.0.0/24), Cloud Router + Cloud NAT for egress (apt, Maven), no
   external IPs. Firewall: tcp/22 from 35.235.240.0/20 (IAP) only, plus
   everything within the subnet.
5. **A control VM inside the VPC runs lein and Jepsen** (chosen over running
   Jepsen on the GitHub runner). Jepsen's SSH client (sshj) has no
   ProxyCommand, so reaching five nodes from the runner would need five
   IAP tunnels kept up for an hour, and the clients (JDBC, HTTP to
   `zcube-server`, the stale-put hold proxy that the agent must reach
   back) would also cross IAP, which changes the latencies the test
   measures and breaks the partitions that include the control host (they
   match its address). Inside the VPC the control VM is an ordinary host on
   the subnet, as the Docker host is on the bridge. The runner reaches only
   the control VM, through `gcloud compute ssh --tunnel-through-iap` with
   OS Login as the CI service account; the long run is started detached
   there and polled, so a dropped tunnel does not kill it.
6. **SSH to the nodes as root, by a throwaway key.** Jepsen drives nodes as
   root. Each run generates an ed25519 key on the runner; the public half
   is instance metadata, which the startup script puts in root's
   `authorized_keys` with `from="<subnet>"`. The sshd drop-in
   (`/etc/ssh/sshd_config.d/10-zcube-jepsen.conf`, read before the image's
   `50-cloudimg-settings.conf`, since sshd keeps the first value it gets)
   says `PermitRootLogin no` globally and `prohibit-password` only inside
   `Match Address <subnet>`, so root is refused from IAP's range and
   everywhere else even if the key leaked. On 24.04 sshd is socket
   activated: before `ssh.service` first runs there is no `/run/sshd` and
   no host keys, so the script creates `/run/sshd`, checks the drop-in with
   `sshd -t` against a throwaway host key, and applies it with
   `systemctl try-reload-or-restart ssh.service` (which does nothing if
   the service is not running yet; the socket starts it with the new
   config on the first connection). `tofu/tests/startup-sshd.sh` runs the
   real script in an Ubuntu 24.04 container and checks the result with
   `sshd -T -C` for an IAP, an outside and two subnet addresses. The
   private half of the key goes to the control VM over IAP and dies with
   it. OS Login stays on for everything else; project SSH keys are blocked.
7. **VMs.** Ubuntu 24.04 (`ubuntu-os-cloud/ubuntu-2404-lts-amd64`, the
   image the family resolves to is in the run summary). 5 x `e2-standard-4`
   nodes + 1 x `e2-standard-2` control = 22 vCPU, under the quota of 24.
   No service account (nothing on the metadata server to steal), no
   external IP, shielded boot, labels `purpose=jepsen`, `run_id`,
   `created_unix`, `role`. A startup script installs what the Docker image
   has (nodes) or a JDK, Leiningen (checksum-pinned) and graphviz
   (control), and writes `/var/lib/zj-ready`. GCE's max run duration
   (`max_run_hours`, 5) deletes each VM even if both CI and the sweeper fail.
8. **State.** GCS backend, bucket from `TF_STATE_BUCKET`, prefix
   `runs/<github run id>`, one state per run; a re-run of a failed teardown
   job uses the same prefix. The provider is pinned (`hashicorp/google`
   8.11.0) and `.terraform.lock.hcl` is committed for linux/darwin
   amd64/arm64.
9. **One run at a time.** `concurrency: gcp-live`, never cancelled in
   progress (the teardown must run). GitHub keeps at most one run pending
   per group: a newer dispatch replaces (cancels) the one waiting, never
   the one running. Job timeouts: 150 min live, 30 min teardown. The
   `inputs` job rejects a run that cannot fit: `gcp/check-inputs.sh`
   checks each input's shape and every test name, from the environment,
   never as `${{ }}` in a script, and that setup plus each test's load and
   overhead fits in the live job's timeout.
10. **The sweeper** (`gcp-sweeper.yml`, hourly on main, same auth) deletes
    instances labelled `purpose=jepsen` and networks named `zj-*` (with
    their routers, NAT, firewall rules and subnets; networks take no
    labels) created more than 4 hours ago, and fails if it found anything:
    a leftover means a teardown failed and someone should look. It refuses
    an age under 3 hours, so it can never delete a cluster under a run
    that is still going (150 + 30 min at most). A failed gcloud listing
    fails it; it does not read as "nothing found".
11. **Every action pinned by full SHA.**
12. **Docker stays.** `jepsen.yml` and `stale-put.yml` are unchanged and keep
    running on every PR. The one harness change (stale-put's control
    address from `$SSH_CLIENT`, not the default gateway, which on GCP is
    the VPC router) is the same address in Docker.
13. **Teardown survives a cancelled apply.** A run cancelled during
    `tofu apply` leaves its GCS state lock behind. `gcp/tofu-destroy.sh`
    destroys with `-lock-timeout=2m`; if the lock is still held it
    force-unlocks only a lock whose path is this run's own state
    (`runs/<run id>/default.tflock`; the concurrency group admits no other
    run, so it is stale) and retries, and fails on any other lock. Then,
    in `always()` steps, `gcp/sweep.sh` with `RUN_ID` deletes whatever of
    this run is still there, by label and network name and whatever its
    age (what GCE created but tofu never wrote to the state), and a dry
    run of the same sweep fails the job if anything is left.
14. **Diagnostics on failure.** A failed live job saves each VM's serial
    port output (readable through the API even with the interactive
    serial console off) and, when the control VM is reachable, the
    `google-startup-scripts` and `ssh` journals of it and every node, under
    `diag/` in the results artifact.

## What Zigcube is shipped

The `build` job (no GCP access, decision 2) builds the requested Zigcube ref exactly as
`jepsen.yml` does (Zig 0.17.0, checksum-verified, ReleaseSafe, baseline
CPU) and ships `zcube-server`, `zcube-agent`, `fixtures/images.zon`,
`src/constants.zig` and the commit id. Where the ref has a `dist` build step
(zigcube#277, J2), its output is shipped too, under `dist/`, but the harness
still installs the binaries itself; it does not use J2's host scripts or
systemd units. Switching to them is a later slice.

## Owner actions (GCP side, not done by this repository)

**Pin the WIF provider to the workflow files.** The provider admits this
repository on `refs/heads/main`; any workflow file on main could then ask
for a token. Narrowing it to the two workflows that need one closes that.
First read the current condition, and keep every clause of it:

```sh
gcloud iam workload-identity-pools providers describe PROVIDER \
  --project=PROJECT --location=global --workload-identity-pool=POOL \
  --format='value(attributeCondition)'
```

then, assuming it is the repository, owner and ref clauses described above:

```sh
gcloud iam workload-identity-pools providers update-oidc PROVIDER \
  --project=PROJECT --location=global --workload-identity-pool=POOL \
  --attribute-condition="assertion.repository_id=='1455702118' \
&& assertion.repository_owner_id=='347124131' \
&& assertion.ref=='refs/heads/main' \
&& assertion.job_workflow_ref in [\
'Zigbox/zigcube-jepsen/.github/workflows/gcp.yml@refs/heads/main', \
'Zigbox/zigcube-jepsen/.github/workflows/gcp-sweeper.yml@refs/heads/main']"
```

Both files must be in the list: with `gcp.yml` alone the sweeper's auth
fails and leftovers stay until `max_run_hours`. `job_workflow_ref` is
case-sensitive and carries the owner as GitHub reports it (`Zigbox`);
check it against a run's token claims if the first dispatch is refused.
The optional organization policies are in the README ("Running on GCP",
"Optional hardening").

## Not done / open

- Not run against GCP: the session that wrote this had no GCP credentials,
  by design. The first dispatch is the first real test; the parts most
  likely to need a fix are the ones no offline check reaches: GCE accepting
  `max_run_duration` with `instance_termination_action = DELETE` on standard
  VMs, OS Login for the service account through `gcloud compute ssh`
  (key import into its login profile), and the nodes' sshd drop-in.
- No scheduled live run yet.
- Clock faults on real VMs (the reason for J3) are not in this slice.
