# ADR-040: Build on a dedicated runner on the staging host, sign on the maintainer's Mac

## Status

Proposed. The owner approved the approach on 2026-10-18 (the Pi as the build machine, signing on the Mac,
"I'm not raising any budget any minute"); this text becomes Accepted when the pull request that carries it is
merged. Supersedes the hosted-runner rule of ADR-018 finding H1 for the jobs of `deploy.yml`; amends ADR-018
finding H2 (where the signing key lives). Builds on ADR-006 (build once, promote), ADR-018 (staging on an ARM64
host, pull-based deploy, cosign verification by the puller) and the ADR-018 amendments of 2026-10-16 and
2026-10-18.

## Context

The organisation's GitHub Actions budget for hosted runners ran out on 2026-10-17. A hosted job then does
not start at all. Every job of `deploy.yml` was on `ubuntu-latest`, so nothing could be built or pointed at
staging, and a migration cannot be merged safely until staging has proven it. The owner will not raise the
budget, so no job may depend on hosted minutes.

ADR-018 finding H1 had put the deploy jobs on hosted runners for a reason that still holds: a persistent
runner that runs pull request code and also holds `packages: write` and a signing key lets a pull request
implant something that survives into the next deploy run and steals the token or the key. Finding H2 had put
the cosign private key in repository secrets so the build job could sign. An adversarial review of the first
draft of this ADR showed the sharper form of the problem: any user with write access can push a branch whose
workflow targets a self-hosted label, so a runner that serves the deploy build and also trusts nothing but
its own wipe hooks is not safe, because the signer cannot tell a poisoned build from a clean one.

Facts that shaped the answer:

- The staging puller (`deploy/pull-staging.sh`) verifies the cosign signature of the api, web and proxy
  digests and, when one fails, marks the release failed for good and never retries it. The `staging` pointer
  must therefore move only after the signatures exist, and the signature must be in the format cosign 2.4.1
  reads (cosign v3 writes another format).
- The cosign key pair already exists: the private key is in the maintainer's Keychain (`bms-cosign-key`,
  `bms-cosign-password`) and its public half equals `deploy/cosign.pub`. The same key is also stored as the
  repository secrets `COSIGN_PRIVATE_KEY` and `COSIGN_PASSWORD`, readable by any workflow on any branch, so it
  must be treated as exposed and rotated (decision 7).
- Organisation runner groups can be restricted to selected workflows, and this works on the organisation's
  free plan: a group was created through the API and accepted `restricted_to_workflows` with a workflow
  `deploy.yml@refs/heads/main`.
- Jerome's laptop was considered and rejected as the build machine: it belongs to the Kyma project, a third
  party has administrator SSH to it, and its Windows password was still weak (AGA task #627), so a registry
  write token there would be readable by someone outside this project while a job runs. The production-class
  Hetzner box (`kimfamhub-prod`, 2 CPUs, 3.7 GB RAM, about 500 MB free) was rejected: it is a production box
  and too small. The Mac has no Docker by decision.
- The staging Pi has 4 cores, 7.6 GB RAM, 33 GB free disk, native ARM64 and already hosts a hardened runner
  user (`ghrunner`, AGA ADR-030) with rootless Docker, an nftables wall, a memory cap and job hooks, which
  are the pattern reused here.

## Decision

1. **A runner group that admits one workflow.** The organisation runner group `bms-deploy` is visible to
   `bms-platform` only and restricted to the workflow
   `RincolTech-Solutions-ltd/bms-platform/.github/workflows/deploy.yml@refs/heads/main`. No other workflow, no
   other branch, no pull request and no other repository can run a job on a runner of that group. This is the
   boundary; everything below is defence in depth behind it. Tested on 2026-10-18: a throwaway branch whose
   workflow is `on: push` with `runs-on: [self-hosted, bms-deploy]` stayed queued for two minutes while the
   runner was online and idle, and the runner's hook log stayed empty.
2. **A dedicated runner on the Pi.** `bms-deploy-pi`, registered in that group, label `bms-deploy`, run by a
   new user `bmsdeploy` (uid 1002) that is not `ghrunner` and not `nfetestpi1`: no sudo, not in the docker, adm
   or dialout groups, cron denied, its own rootless Docker. Files are in `deploy/runner/`; `provision.sh` installs
   them and `deprovision.sh` removes them. Runner auto-update is off. The runner install is read-only to its
   user and the unit and hooks are root-owned; the directory itself sits in the user's home, so a hostile job
   could still replace it, which is why decision 1 and not file ownership is the boundary.
3. **Defence in depth on the runner.**
   - A root-owned `job-started.sh` hook fails any job that is not `deploy.yml` at `refs/heads/main` on a
     push (a variable it cannot see counts as a refusal).
   - Network: an nftables wall matched on uid 1002 (copied from the AGA wall): the public internet only; the
     LAN, ZeroTier, the loopback services of the host named in the wall and all non-loopback IPv6 are refused.
     Loopback is otherwise open because rootless Docker needs it, so a new host service must be added to the
     list (`provision.sh` prints the listeners the wall does not name).
   - Memory: `user-1002.slice` is capped at 3.5 GB, and `user.slice` as a whole at 5.5 GB, because the AGA
     runner's 5.5 GB cap and this one added up to more than the host's 7.6 GB. The OpenEMS edge runs outside
     `user.slice`.
   - State: root-owned hooks remove all rootless Docker state (containers, images, volumes, build cache),
     credential and tool-cache files, temporary files of the user and the job work area before and after every
     job. This is a clean-up, not an isolation boundary; it does not kill leftover processes.
   - No secrets: the runner holds no signing key and no deploy secret. The only credential a job has is its own
     `GITHUB_TOKEN`, which GitHub invalidates when the job ends, plus the runner's own registration
     credentials.
4. **Jobs.** `meta`, `build`, `staging-pointer` and `promote` of `deploy.yml` run on `[self-hosted,
   bms-deploy]`. The build matrix runs one image at a time. QEMU registration is dropped: it needs a
   privileged binfmt step that rootless Docker cannot do, so every Dockerfile stage that has a `RUN` is pinned to
   `$BUILDPLATFORM` (the proxy Dockerfile's `empty` stage was not and is now). `deploy-production` stays on
   `ubuntu-latest`: it holds the production SSH key, which must never be on a shared runner, and it runs only
   for a release tag after the production VM exists. The group admits only `refs/heads/main`, so the `promote`
   job of a release tag will not start until a group entry for that tag exists; that is deliberately deferred
   until a production VM does.
5. **Signing moves off every runner.** The build job no longer signs. It writes `bms-image-digest
   image=... digest=... revision=...` into its own log. A launchd job on the maintainer's Mac,
   `deploy/signer/bms_signer.py`, polls GitHub and signs `image@digest` with the Keychain key, with
   `--tlog-upload=false` as before, and only when all of these hold: the run is this repository's
   `deploy.yml`, event `push`, branch `main`; its commit is on `main`; the three `Build and push` jobs
   succeeded on the runner named `bms-deploy-pi` at that commit; each log holds exactly one matching digest
   line for its image and commit; the image behind the digest carries a `revision` label equal to that commit
   on both platforms; and the Keychain key's public half equals `deploy/cosign.pub` on main. The digest comes
   from the log, never from a tag and never from an annotation (a check run can be updated through the API; a
   log of a finished job cannot). The signer uses cosign v2.4.1, installed by `deploy/signer/install.sh` with a
   pinned hash and asserted at every pass. The key reaches cosign through the environment and a private
   temporary Docker config only; nothing secret is logged.
6. **The pointer waits for the signature and points at digests.** `staging-pointer` verifies all three digests
   with `cosign verify --key deploy/cosign.pub` (one shared 25 minute budget, inside the job's 30 minute timeout) and then creates the `staging` tags from those verified digests, not from the mutable `sha-<short>`
   tag. If the signer is off the job times out and the pointer stays where it was. Nothing unsigned is ever
   pointed at.
7. **The key is rotated and the repository secrets are deleted.** Because `COSIGN_PRIVATE_KEY` and
   `COSIGN_PASSWORD` were readable by any workflow, a new key pair is generated on the Mac with cosign v2.4.1,
   stored in the Keychain, its public half committed as `deploy/cosign.pub` and installed on the staging host
   (`/opt/bms/cosign.pub`), and the two repository secrets are deleted, all before the first deploy under this
   ADR. The current staging release keeps running: the puller verifies only releases it is about to install.

## Threat model

The signature is only as trustworthy as what can reach the signer's inputs: the build runs on the Pi, the Pi also
runs pull request code, and the repository has no branch protection. Said plainly, the design makes a pull
request unable to get an image signed; it does not make the Pi, a repository administrator or a push to `main`
untrusted.

| Attacker or failure | What they can do | Outcome and why |
|---|---|---|
| A user with write access pushes a branch, tag or pull request whose workflow targets `bms-deploy` | Try to run code as `bmsdeploy` | The runner group admits only `deploy.yml` on `main`; the job never starts (probe tested, decision 1). If the setting failed open, the root-owned hook fails the job before a step runs (tested: an admitted probe was refused with the exact workflow reference). |
| The same user targets the other runner on the same Pi (`hillary-pi-aga-heavy`, labels `self-hosted,Linux,ARM64`; its group `hillary-pi` includes this repository) | Run pull request code on the Pi as `ghrunner` | This still happens today (ADR-018, CI overflow). It cannot reach `bmsdeploy`: a different uid, home, Docker daemon and wall. It shares the kernel, the disk and the 5.5 GB user cap with the deploy runner. A kernel or container escape on the Pi would defeat the isolation, and the signature then proves little. Accepted; narrowing the `hillary-pi` group is the lever if that is not acceptable. |
| A tag moved in the registry after the build | Point `sha-<short>` at a malicious image | The signer signs the digest from the job log and re-checks the labels; the pointer is created from the verified digests; the host verifies the signature against the digest. A swapped tag has no valid signature. |
| Another workflow edits the job's annotations or check run | Forge what the signer reads | The signer does not read annotations; it reads the job's log. |
| Theft of the runner's own credentials from the Pi (or an impostor runner) | Take a legitimate deploy job, build anything and write the log line | The signer signs it: the signature trust equals the runner's trust. The impostor still needs a `deploy.yml` job on `main` to be queued, which needs a commit that passed the signer's merged-pull-request check, but the image content is theirs. Accepted: protect the Pi. |
| A repository administrator pushes straight to `main` with an edited `deploy.yml` or Dockerfile | Anything that ships | GitHub branch protection and rulesets are not available for this private repository on the free plan, and all collaborators are administrators, so nothing technical prevents the push. The signer refuses a commit that did not come through a merged pull request (override `BMS_SIGNER_ALLOW_DIRECT=1`), which turns a silent direct push into a visible refusal. Review is otherwise team discipline. |
| Theft of the key from the Mac | Sign anything | The same exposure a workstation key always had; it is no longer also in GitHub. Rotation is in the runbook. |
| The Mac is off or the signer is stopped | Deploys wait | Fail closed: the pointer job times out after 25 minutes, staging stays on the last signed release, re-run the job when the signer is back. |

Residual risks, accepted: the group restriction is a GitHub setting, so it must be re-checked after any change
to the group; the Pi is shared with the NFE edge and the AGA runner, and the wall, the caps and the total
`user.slice` cap protect the edge but a full disk or a kernel bug in rootless containers remains a shared-host
risk; the runner's own credentials can be read by the runner's user, as they must be; anonymous Docker Hub pulls
are limited (about 100 an hour per source address) and one deploy makes about 16 to 30, from an address shared
with other hosts; a release tag cannot use the runner until a group entry exists for it.

## Consequences

Better: no dependency on hosted minutes or on a budget; one place for the signing key, which is rotated; the
signature is the only trust anchor and only `deploy.yml` on `main` can reach the runner, so a pull request
cannot ship an image the host will run; deploys keep the build-once, promote-by-retag flow.

Worse: a deploy now takes longer than a hosted one. Every local build is cold and serial on a 4-core Pi (the
remote layer cache of `docker/build-push-action` still applies); the estimate is 25 to 40 minutes per commit
and it is not measured yet. Deploys depend on the Mac being on and logged in (it is set never to sleep) and on
the single Pi runner, which cannot run two deploys at once. Maintaining a runner (runner version, disk, the
wall) is now ours.

To watch: the first real deploy under this ADR (record the duration in the runbook); the throwaway-workflow
test that proves the group restriction; disk use of the rootless Docker store between wipes; whether `docker
buildx` assembles the `linux/amd64` manifest without QEMU as designed (if not, the amd64 target moves to a
native build elsewhere or is dropped until a production VM exists); Docker Hub 429s; the Mac signer's log.

Rollback: `deprovision.sh` removes the runner; deleting the group and reverting this change restores the hosted
jobs, which work again only if a budget exists.
