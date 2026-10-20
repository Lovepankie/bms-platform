# 10. CI/CD Pipeline

**Status:** Draft · **Owner:** Hillary

## 10.1 Scope

How a change travels from a branch to production: the checks on every pull request, the
images built on `main`, the staging deploy, the production release, and the host-side deploy
and rollback. The decisions are ADR-006 and, for staging and multi-arch images, ADR-018; the
hosts are chapter 9; `PROCESS.md` section 7 is the
short contract developers work to.

## 10.2 Overview

```
branch --PR--> ci.yml (self-hosted hillary-pi): backend mvn verify | frontend contract, types,
               tests, build | actionlint, shellcheck, compose config  + dash, ADR citation, model
               guards. Read-only: no packages:write, no deploy secret ever reaches this runner.
   |
   | squash merge
   v
 main --> deploy.yml (meta, build, staging-pointer, promote on the deploy runner bms-deploy-laptop, ADR-040)
            meta:     sha-<7>
            build:    api, web, proxy images, built once for linux/amd64 and linux/arm64,
                      pushed to GHCR as sha-<7>; each pushed digest is written to the build job's log
            signer:   (the maintainer's Mac, not a runner) signs image@digest with the Keychain key (ADR-040)
            staging-pointer: waits until cosign verifies the three signatures, then retags sha-<7> as `staging`
                                  :
   staging host (ARM64, behind a Cloudflare Tunnel; nothing reaches in)
            bms-pull.timer, every 2 min: pull api:staging, read its labels -> sha-<7>
            -> verify api/web/proxy signatures against the host's own cosign.pub, digest by digest
            -> stage releases/sha-<7>/ -> deploy.sh sha-<7> -> promote to live only on success
   |
   | git tag vX.Y.Z (a commit on main, already on staging)
   v
 tag  --> deploy.yml (NOT YET: the runner group admits only refs/heads/main, so a release tag leaves `meta` queued
            until a group entry for the tag exists, deferred with the production VM, ADR-040)
            meta:     checks vX.Y.Z format and that the commit is on main
            promote:  retag sha-<7> as vX.Y.Z for all three images (no rebuild; already signed)
            production: environment `production` (dev lead approves) -> ssh -> deploy.sh vX.Y.Z
```

## 10.3 Continuous integration (`.github/workflows/ci.yml`)

**Where jobs run (ADR-018, finding H1 and its amendment 2026-10-18).** The three small text guards (`dash-guard`,
`adr-citation-guard`, `linked-issue-guard`) only read the checked-out diff, hold no secret and cost seconds. They ran on the GitHub-hosted
`ubuntu-latest` runner until the organisation's Actions budget ran out (the job then does not start at all and every pull request fails the
three checks); they now target the same `CI_RUNS_ON` runners as the other read-only checks (`tour-guard`, ADR-049, was written for them). The deploy workflow runs on its own runner
(ADR-040, below). The read-only checks (`ci.yml`, `architecture-model`, `estimate-guard` and the small guards) target `runs-on: ${{ fromJSON(vars.CI_RUNS_ON || '["self-hosted","jerome-laptop"]') }}`: the three
organisation runners on Jerome's laptop first (runner group `jerome-laptop`, X64, 6 cores, WSL2 with Docker), and the organisation runner on the ARM64
staging host (runner group `hillary-pi`) only when all three laptop runners are busy or offline. `scripts/ci/pick-runner.sh` sets the repository
variable `CI_RUNS_ON` every minute (a launchd job on the maintainer's Mac) from the runners' busy flags; both groups admit private repositories only, public repositories refused (ADR-018 amendment 2026-10-16). It costs no Actions minutes,
which matters because the repository is private. The runner is walled from staging: its own user
without sudo or the docker group, a rootless Docker daemon (`DOCKER_HOST` is set in the runner's
environment, which Testcontainers picks up), a 5.5 GB memory and 300% CPU cgroup, and a firewall
that blocks the LAN and overlay network. Tools the host lacks run as containers
(`rhysd/actionlint`, `koalaman/shellcheck`); JSON handling uses `python3`. One job runs at a time
across both repositories, so a job may queue.

`deploy.yml` runs on a separate runner, `bms-deploy-laptop` (ADR-040 and its amendment of 2026-10-18, which supersede the
hosted-runner rule of ADR-018 finding H1 for these jobs): a runner on Jerome's laptop (WSL Ubuntu) under its own user
`bmsdeploy` (uid 1006), beside the other runner users of that machine and none of them, in the organisation runner group `bms-deploy`, which is visible to
this repository only and restricted to `deploy.yml` at `refs/heads/main`, so no pull request, branch workflow
or other repository can run a job there. The organisation's hosted Actions budget is
exhausted and will not be raised, so no job may depend on hosted minutes. The finding H1 risk, a persistent
runner that PR code and a `packages: write` token share, is answered differently: that runner holds NO
signing key and no deploy secret, only the per-job `GITHUB_TOKEN` (which GitHub invalidates when the job
ends); a root-owned hook fails any job that is not `deploy.yml` on `main`; its rootless Docker state,
credential files and work area are wiped before and after every job; its network is walled to the public
internet; it is memory- and CPU-capped so the other users of that machine are not at risk; and the build is trusted only through the signature, which is made
elsewhere (below). `deploy-production` is the one job that stays on `ubuntu-latest`, because it holds the
production SSH key and only runs for a release tag after the production VM exists.

Runs on every pull request and every push to `main`.

| Job | Steps | Proves |
|---|---|---|
| Backend | JDK 25 (Temurin), `mvn -B verify` in `backend/` | Unit tests; the Spring Modulith boundary test and the core-never-depends-on-a-vertical rule (NFR-MNT-01); the one-clock rule; Spotless formatting; then integration tests against PostgreSQL 16 in Testcontainers, connected as `bms_app`: the RLS catalogue, cross-tenant and unbound-session suites (NFR-ISO-01 to 03), the tenant binding hook, the role guard (NFR-SEC-03), ledger invariants (ADR-004), the members API, actuator exposure, route permissions (FR-IAM-03) and the OpenAPI snapshot (chapter 7 section 7.3). Reports are uploaded on failure. |
| Frontend | Node 22, `npm ci`, regenerate `src/api/schema.d.ts` from `docs/api/openapi.json` and fail on a diff, `npm run lint` (TypeScript), `npm test` (Vitest), `npm run build` | The typed client matches the committed contract; the PWA builds |
| Delivery | `actionlint`, `shellcheck` on `deploy/` (including the puller), `docker compose config` on the three compose files, `systemd-analyze verify` on the staging slice and timer | Workflows, scripts, compose files and unit files are valid |

The documentation guards (`dash-guard`, `adr-citation-guard`, `architecture-model`,
`linked-issue-guard`) run as their own workflows (ADR-005), and so does `placeholder-migration-guard`, which fails a pull request that
still holds a migration numbered `V99` (the placeholder a branch uses when it cannot know the next free number; a `V99` on main would
block every later migration, because Flyway runs with `outOfOrder` off), until the number is taken: there is no override. The first job of `deploy.yml` refuses the same file, so no image is built and the staging pointer never moves to a commit that holds one.
`tour-guard` (ADR-049, issue #337) is a further small guard on the same runners: it fails a pull request that adds or renames a route (a `createLazyRoute`, `createFileRoute`, `createRoute`, `retailScreen` or `lendingScreen` line added under `frontend/src/areas` or in `frontend/src/app/router.tsx`, tests excluded) without a change under `frontend/src/tour/`, unless the pull request body has a line `TOUR-EXEMPT: <reason>`; it reads only the diff and the body (passed as an environment variable, never interpolated), runs its own unit test first, and re-runs when the body is edited.

`pull_request` is used, never `pull_request_target`: a run for a fork's pull request gets a
read-only token and no secrets. Nothing in CI pushes images or deploys.

Contract tests against a real provider are a separate, manual workflow, never part of this one (spec
`docs/specs/client-extras.md` section 8, chapter 12 section 12.8). `sms-sandbox-contract` (`workflow_dispatch` only, on a
GitHub-hosted runner and only on `main`, because it holds a secret and the shared self-hosted runner never does, ADR-018 finding H1) sends one
message to the Africa's Talking **sandbox** with the repository secret `BMS_SMS_SANDBOX_API_KEY` and the repository variable
`BMS_SMS_CONTRACT_TO` (a sandbox test number), through `AfricasTalkingSandboxContractTest`, which builds its settings
through the SMS startup guard as a staging host would and so cannot be pointed at the live host. The test is skipped
unless `BMS_SMS_CONTRACT=true`, so it does nothing in `mvn verify`.

## 10.4 Build and publish (`deploy.yml`, push to `main`)

- `meta` resolves `sha-<first 7 characters of the commit>`.
- `build` (matrix `api`, `web`, `proxy`) builds each image once with Buildx, for `linux/amd64`
  and `linux/arm64`, with the GitHub Actions cache per image, and pushes one manifest list
  `ghcr.io/rincoltech-solutions-ltd/bms-platform-<image>:sha-<7>`. The api image receives the
  tag as `GIT_SHA`. Every image carries the labels `org.opencontainers.image.source`,
  `org.opencontainers.image.revision` (the full commit), `org.opencontainers.image.version`
  (`sha-<7>`) and `org.opencontainers.image.created` (the commit's push timestamp, used by the
  host-side downgrade guard of section 10.5).
- **No emulated compilation for the layers that matter** (ADR-018): every Dockerfile compiles in a
  stage pinned to `$BUILDPLATFORM` (Maven and npm run natively on the runner; the proxy's Caddy is
  cross-compiled by Go for `TARGETARCH`), and the per-target runtime stages have no `RUN` step, so
  the images that ship never execute anything under emulation. The deploy runner is ARM64 with rootless
  Docker, so it cannot register QEMU (a privileged binfmt step) and does not need to: Buildx assembles
  the `linux/amd64` manifest from layer copies, never a `RUN` (ADR-040). The matrix builds one image at
  a time (`max-parallel: 1`) to stay inside the runner's memory cap. The build uses no layer cache: the GitHub
  Actions cache is a hosted service that failed the first real deploy, and a registry cache could carry a layer
  poisoned by any workflow with `packages: write` into a signed image (ADR-040).
- **Signing (ADR-018 finding H2, as amended by ADR-040).** The private key lives only in the
  maintainer's Keychain (items `bms-cosign-key`, `bms-cosign-password`); it is no longer a repository
  secret and no runner ever sees it. Each build job writes `image`, `digest` and `revision` in a
  `bms-image-digest` line of its own log. A launchd job on the maintainer's Mac, `deploy/signer/bms_signer.py`
  (runbook `docs/runbooks/deploy-signer.md`), signs `image@digest` with `cosign sign --key ...
  --tlog-upload=false` only when the run is this repository's `deploy.yml`, a push to `main`, its commit is
  on `main`, the three build jobs succeeded on `bms-deploy-laptop` at that commit, the log holds exactly one matching
  digest line and the image labels name that commit on both platforms, and the Keychain key equals
  `deploy/cosign.pub`. The signer takes the digest from the job log and never from a tag, so a tag moved
  after the build cannot get a signature; it uses cosign v2.4.1, the version the puller verifies with. `--tlog-upload=false` keeps the
  signature out of the public Rekor transparency log: this repository is private, and a log entry would
  publish its name and commit shas. The staging host marks a release failed for good when a signature does
  not verify, so `staging-pointer` first waits (up to 20 minutes) until `cosign verify --key
  deploy/cosign.pub` passes for all three digests, and only then creates the `staging` tags from those
  verified digests (`docker buildx imagetools create` copies no content, so nothing needs signing twice). If the signer is off the pointer simply does not move.
- Only `build`, `staging-pointer` and `promote` have `packages: write`, and only on `bms-deploy-laptop`
  (ADR-040): the CI runners (`ci.yml` and the guards, on `CI_RUNS_ON`) never hold this permission or a
  deploy secret.
- The workflow runs only for this repository (`github.repository` check), so a fork's `main`
  cannot publish.

## 10.5 Staging deploy (pull-based, ADR-018)

GitHub cannot reach the staging host: it sits behind a Cloudflare Tunnel with no inbound port. So
CI publishes what staging should run, and the host fetches it.

1. **Pointer.** After `build` succeeds on `main`, the `staging-pointer` job (concurrency group
   `staging-pointer`, queued) retags the three `sha-<7>` manifest lists as `staging` with
   `docker buildx imagetools create`. Nothing is rebuilt. It first reads the revision label of the
   current `api:staging`, failing the job (rather than moving the pointer regardless) if that read
   errors for any reason other than the tag not existing yet (ADR-018 finding L1), and leaves the
   pointer alone when it already names a newer commit, so a slow older run never moves staging
   backwards.
2. **Timer.** On the host, `bms-pull.timer` starts `bms-pull.service` two minutes after the last
   run ends; it runs `/opt/bms/pull-staging.sh` as the `bms` user.
3. **Resolve.** The puller pulls `api:staging` (with the `read:packages` login of section 10.9;
   ADR-018 finding M5) and reads `org.opencontainers.image.version` and
   `org.opencontainers.image.revision` from the image. It refuses labels that do not agree
   (`version` must be `sha-` plus the first 7 characters of `revision`). When the pointer did not
   move, the pull is a manifest check only.
4. **Compare.** If the tag equals `state/current_tag`, or `state/last_failed_tag`, the run ends
   silently.
5. **Verify (ADR-018 finding H2).** Before anything from the release is trusted, the puller
   resolves the api, web and proxy images to their digests and verifies each one's cosign
   signature against `/opt/bms/cosign.pub`, a key it provisioned once by hand and that a release
   can never replace. Any signature that does not verify stops here: nothing is extracted or run.
6. **Host files (ADR-018 finding M3).** Only now does it pull the release's `proxy` image by
   digest, copy `/usr/share/bms-deploy` out of it (the proxy image is built from the `deploy/`
   context and carries that commit's host files), and stage `compose.pi-staging.yml` as
   `compose.yml`, the three scripts and `postgres/`, `sql/`, `systemd/` into `releases/sha-<7>/`.
   The live top-level copies (what `bms-pull.timer`, cron and the runbooks actually invoke) are not
   touched yet.
7. **Deploy.** It runs `releases/sha-<7>/deploy.sh sha-<7>` against the shared `state/` directory
   (section 10.7). `deploy.sh` re-resolves and re-verifies the same signatures on its own (it is
   also invoked directly on production, section 10.6), refuses a release built before the live one
   unless `state/allow_downgrade` exists, and uses `image@sha256:...` references throughout, never
   a mutable tag, once resolved. Exit 3 (another deploy holds the lock) is retried on the next run;
   any other failure writes the tag to `state/last_failed_tag`, so a broken release is not retried
   every two minutes, and `releases/sha-<7>/` is kept for inspection.
8. **Promote on success.** Only a successful `deploy.sh` moves the `current` symlink to this
   release and refreshes the live `compose.yml`, `deploy.sh`, `backup.sh` and `pull-staging.sh`
   from it. A failed release never displaces what is actually running, and never replaces the
   puller that would have to deploy its own fix (ADR-018 finding M3).
9. **Tidy.** After a success it removes release images and `releases/` directories other than the
   live and the previous one.

A deploy therefore reaches staging within about two to five minutes of the pointer moving. The
Actions run shows the pointer move; staging's own result is in `state/history.log` and
`journalctl -u bms-pull.service` on the host (`docs/runbooks/deploy.md`).

## 10.6 Production release

1. Pick a commit on `main` that is green on staging.
2. `git tag vX.Y.Z <commit> && git push origin vX.Y.Z` (semantic versioning, `v0.x.y` before the
   pilot go-live; `PROCESS.md` section 7).
3. `meta` refuses a tag that is not `vMAJOR.MINOR.PATCH` or whose commit is not on `main`.
4. `promote` checks that all three `sha-<7>` images exist and retags them `vX.Y.Z` with
   `docker buildx imagetools create`. Nothing is rebuilt.
5. `deploy-production` waits for the dev lead's approval (the `production` environment's required
   reviewer), then deploys `vX.Y.Z` in the concurrency group `deploy-production`, skipping with a
   notice while `PROD_HOST` or `PROD_SSH_KEY` is unset. The composite action
   `.github/actions/ssh-deploy` writes the SSH key to a file only the runner user can read, pins
   the host key from `PROD_SSH_KNOWN_HOSTS`, copies `deploy/compose.yml`, the scripts, `postgres/`,
   `sql/` and `deploy/cosign.pub` (from this run's own checkout of the reviewed commit) to
   `/opt/bms`, logs the host in to GHCR with the workflow's `GITHUB_TOKEN` over SSH standard input,
   runs `/opt/bms/deploy.sh vX.Y.Z`, logs out and deletes the key file. `deploy.sh` verifies the
   same cosign signatures as the staging puller (section 10.7) before switching anything. The
   `vX.Y.Z` manifest lists carry both architectures.

## 10.7 On the host: `deploy/deploy.sh <tag>`

| Step | Action | On failure |
|---|---|---|
| 1 | Validate the tag (`sha-<hex>` or `vX.Y.Z`); take `state/deploy.lock` | Exit 2 or 3; nothing changed |
| 2 | Resolve api, web and proxy to their digests and verify each cosign signature against `cosign.pub` (ADR-018 finding H2) | Exit 1, `failed-signature` recorded; nothing pulled beyond the manifest, nothing changed |
| 3 | Refuse a release built before `state/current_created` unless `state/allow_downgrade` exists (ADR-018 finding L1) | Exit 1, `refused-downgrade` recorded; nothing changed |
| 4 | Start PostgreSQL if needed; wait until healthy | Exit non-zero; nothing changed |
| 5 | `docker compose run --rm migrate` (Flyway as `bms_owner`) | Exit 1; old containers still serving; `failed-migration` recorded |
| 6 | `docker compose up --no-deps` only the named application services (`api`, `web`, `proxy`, plus `cloudflared` on staging via `BMS_EDGE_SERVICES`): `postgres` is never in this list, so it is never recreated by an application deploy, whatever else changed in `.env` (ADR-018 finding M4) | |
| 7 | Wait up to `READY_TIMEOUT` (240 s) for the API health check (`/readyz`: database and migrations at head), and for `web` and `api` to answer through the proxy container | Go to 8 |
| 8 | Switch back to the previous tag's already-verified digests, wait for it to be ready, record `failed-health` and `rolled-back-to`, exit 1 | Record `rollback-unhealthy`, exit 1; follow `docs/runbooks/rollback.md` |
| 9 | Write the tag and its build time to `state/current_tag` and `state/current_created` (temp file, `sync`, rename, so a power loss never leaves a truncated file, ADR-018 finding L7); append `deployed` to `state/history.log` | |

Migrations are never reversed on rollback, so they are expand and contract (chapter 6 section
6.9). Re-running the script with the live tag is a no-op that passes the gate. A deploy
recreates the API container, so there is a short outage while the JVM starts (NFR-AVL-03
allows 30 seconds; measured locally at under 10).

## 10.8 Security of the pipeline

- Secrets are environment secrets, masked in logs by GitHub; no step echoes one, no step uses
  `set -x`, and secrets are passed to programs through environment variables or standard
  input, never command-line arguments that could appear in a process list on the host.
- Write access to packages exists only in `build`, `staging-pointer` and `promote`, on the deploy runner
  `bms-deploy-laptop` (ADR-040), on pushes to `main` and version tags of this repository; the production SSH
  key exists only in `deploy-production`, on `ubuntu-latest`. The CI runners (`CI_RUNS_ON`, the
  `hillary-pi` group), shared with pull request code, never hold any of them, and no runner holds the
  cosign key.
- Every pushed image is signed by digest with cosign, using a private key that exists only in the
  maintainer's Keychain, never uploaded anywhere and never logged (ADR-018 finding H2, ADR-040). Both the staging puller and `deploy.sh` verify the
  signature before running anything from a release, against a public key that is never sourced
  from the release itself.
- The three GHCR packages are private (ADR-018 finding M5): the production host holds no registry
  credential between deploys; the staging host holds a `read:packages`-only token, provisioned once
  by hand, and its host files still come inside the release image, never fetched from GitHub
  directly.
- The production environment requires the dev lead's approval. Review of `main` is team discipline plus the
  merge helper's checks: GitHub branch protection and rulesets are not available for this private repository on
  the organisation's free plan (a 403 "Upgrade to GitHub Pro"), and every collaborator is an administrator. The
  signer therefore also requires that a commit came through a merged pull request (ADR-040).

## 10.9 GitHub configuration

| Where | Name | Value |
|---|---|---|
| Environment `staging` | none | No longer used by the pipeline (ADR-018); `STAGING_*` secrets, if set, can be deleted |
| Environment `production`, secrets | `PROD_HOST`, `PROD_SSH_KEY`, `PROD_SSH_KNOWN_HOSTS` | Host name or IP of the production VM; private key of its `deploy` user; `ssh-keyscan <host>` output taken on a trusted network |
| Environment `production`, variable | `PROD_SSH_USER` | Optional; default `deploy` |
| Environment `production`, protection rule | Required reviewer | Hillary Arinda |
| Repository secrets | `COSIGN_PRIVATE_KEY`, `COSIGN_PASSWORD` | Image signing (ADR-018 finding H2); generated once with `cosign generate-key-pair` on a machine that is not this repository's CI; `docs/runbooks/provision-host.md` |
| Branch protection on `main` | Required checks | `Backend (mvn verify)`, `Frontend (types, tests, build)`, `Workflows, scripts and compose files`, plus the guards |
| Packages | `bms-platform-api`, `-web`, `-proxy` | Linked to this repository; **private** (ADR-018 finding M5): the staging host's `bms` user authenticates with a `read:packages`-only token |

## 10.10 Local equivalents

| Make target | Does |
|---|---|
| `make test` | `mvn verify` and the frontend tests, as CI |
| `make lint`, `make fmt` | Spotless check or apply; TypeScript check |
| `make build` | The three images for the local platform; `docker buildx build --platform linux/amd64,linux/arm64 --load <context>` builds both architectures as CI does |
| `make openapi` | Regenerate `docs/api/openapi.json` and the frontend client after a contract change |
| `make dev`, `make seed`, `make migrate` | The local stack of chapter 9 section 9.2 |

## 10.11 Open items

- A registry retention policy for `sha-*` tags older than 90 days that no release references
  (the staging host prunes its own copies; the registry keeps everything).
- A notification when the staging puller records a failed tag, instead of reading
  `state/history.log`.
- End-to-end browser tests against staging after each deploy (chapter 15 section 15.10).
- Dependency and image vulnerability scanning (NFR-SEC-06) as a CI job.

## 10.12 Claude cloud agent sessions

Work can also run as a Claude Code session in Anthropic's cloud (runbook
`docs/runbooks/cloud-agent-sessions.md`). Such a session is one more contributor: it
works on a branch and opens a pull request, which goes through the same CI and review
as any other. The cloud sandbox ships OpenJDK 21 and its egress proxy blocks the usual
JDK download sites, so the SessionStart hook `.claude/hooks/cloud-setup.sh` installs
`openjdk-25-jdk-headless` from the Ubuntu archive in cloud sessions only
(`CLAUDE_CODE_REMOTE=true`), exports `JAVA_HOME` for the session and starts the Docker
daemon for the Testcontainers tests. Developer machines are unaffected.
