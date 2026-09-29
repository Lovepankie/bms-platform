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
branch --PR--> ci.yml: backend mvn verify | frontend contract, types, tests, build |
               actionlint, shellcheck, compose config  + dash, ADR citation, model guards
   |
   | squash merge
   v
 main --> deploy.yml
            meta:     sha-<7>
            build:    api, web, proxy images, built once for linux/amd64 and linux/arm64,
                      pushed to GHCR as sha-<7>
            staging-pointer: retag sha-<7> as `staging` (no rebuild)
                                  :
   staging host (ARM64, behind a Cloudflare Tunnel; nothing reaches in)
            bms-pull.timer, every 2 min: pull api:staging, read its labels -> sha-<7>
            -> install deploy/ at that commit -> /opt/bms/deploy.sh sha-<7>
   |
   | git tag vX.Y.Z (a commit on main, already on staging)
   v
 tag  --> deploy.yml
            meta:     checks vX.Y.Z format and that the commit is on main
            promote:  retag sha-<7> as vX.Y.Z for all three images (no rebuild)
            production: environment `production` (dev lead approves) -> ssh -> deploy.sh vX.Y.Z
```

## 10.3 Continuous integration (`.github/workflows/ci.yml`)

Runs on every pull request and every push to `main`.

| Job | Steps | Proves |
|---|---|---|
| Backend | JDK 25 (Temurin), `mvn -B verify` in `backend/` | Unit tests; the Spring Modulith boundary test and the core-never-depends-on-a-vertical rule (NFR-MNT-01); the one-clock rule; Spotless formatting; then integration tests against PostgreSQL 16 in Testcontainers, connected as `bms_app`: the RLS catalogue, cross-tenant and unbound-session suites (NFR-ISO-01 to 03), the tenant binding hook, the role guard (NFR-SEC-03), ledger invariants (ADR-004), the members API, actuator exposure, route permissions (FR-IAM-03) and the OpenAPI snapshot (chapter 7 section 7.3). Reports are uploaded on failure. |
| Frontend | Node 22, `npm ci`, regenerate `src/api/schema.d.ts` from `docs/api/openapi.json` and fail on a diff, `npm run lint` (TypeScript), `npm test` (Vitest), `npm run build` | The typed client matches the committed contract; the PWA builds |
| Delivery | `actionlint`, `shellcheck` on `deploy/` (including the puller), `docker compose config` on the three compose files, `systemd-analyze verify` on the staging slice and timer | Workflows, scripts, compose files and unit files are valid |

The documentation guards (`dash-guard`, `adr-citation-guard`, `architecture-model`,
`linked-issue-guard`) run as their own workflows (ADR-005).

`pull_request` is used, never `pull_request_target`: a run for a fork's pull request gets a
read-only token and no secrets. Nothing in CI pushes images or deploys.

## 10.4 Build and publish (`deploy.yml`, push to `main`)

- `meta` resolves `sha-<first 7 characters of the commit>`.
- `build` (matrix `api`, `web`, `proxy`) builds each image once with Buildx, for `linux/amd64`
  and `linux/arm64`, with the GitHub Actions cache per image, and pushes one manifest list
  `ghcr.io/rincoltech-solutions-ltd/bms-platform-<image>:sha-<7>`. The api image receives the
  tag as `GIT_SHA`. Every image carries the labels `org.opencontainers.image.source`,
  `org.opencontainers.image.revision` (the full commit) and `org.opencontainers.image.version`
  (`sha-<7>`).
- **No emulated compilation** (ADR-018): every Dockerfile compiles in a stage pinned to
  `$BUILDPLATFORM` (Maven and npm run natively on the runner; the proxy's Caddy is
  cross-compiled by Go for `TARGETARCH`), and the per-target runtime stages have no `RUN` step.
  QEMU is registered for arm64 only as a safety net for a future runtime `RUN` step.
- Only `build`, `staging-pointer` and `promote` have `packages: write`.
- The workflow runs only for this repository (`github.repository` check), so a fork's `main`
  cannot publish.

## 10.5 Staging deploy (pull-based, ADR-018)

GitHub cannot reach the staging host: it sits behind a Cloudflare Tunnel with no inbound port. So
CI publishes what staging should run, and the host fetches it.

1. **Pointer.** After `build` succeeds on `main`, the `staging-pointer` job (concurrency group
   `staging-pointer`, queued) retags the three `sha-<7>` manifest lists as `staging` with
   `docker buildx imagetools create`. Nothing is rebuilt. It first reads the revision label of the
   current `api:staging` and leaves the pointer alone when it already names a newer commit, so a
   slow older run never moves staging backwards.
2. **Timer.** On the host, `bms-pull.timer` starts `bms-pull.service` two minutes after the last
   run ends; it runs `/opt/bms/pull-staging.sh` as the `bms` user.
3. **Resolve.** The puller pulls `api:staging` (anonymously; the packages are public) and reads
   `org.opencontainers.image.version` and `org.opencontainers.image.revision` from the image. It
   refuses labels that do not agree (`version` must be `sha-` plus the first 7 characters of
   `revision`). When the pointer did not move, the pull is a manifest check only.
4. **Compare.** If the tag equals `state/current_tag`, or `state/last_failed_tag`, the run ends
   silently.
5. **Host files.** It downloads the repository tarball at the labelled commit from
   `codeload.github.com` and installs `deploy/compose.pi-staging.yml` as `compose.yml`, the three
   scripts (by rename, so a running copy is never edited in place), `postgres/`, `sql/` and
   `systemd/`. The host's files always match the release, as the SSH copy did.
6. **Deploy.** It runs `./deploy.sh sha-<7>` (section 10.7). Exit 3 (another deploy holds the
   lock) is retried on the next run; any other failure writes the tag to `state/last_failed_tag`,
   so a broken release is not retried every two minutes.
7. **Tidy.** After a success it removes release images other than the live and the previous one.

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
   the host key from `PROD_SSH_KNOWN_HOSTS`, copies `deploy/compose.yml`, the scripts,
   `postgres/` and `sql/` to `/opt/bms`, logs the host in to GHCR with the workflow's
   `GITHUB_TOKEN` over SSH standard input, runs `/opt/bms/deploy.sh vX.Y.Z`, logs out and deletes
   the key file. The `vX.Y.Z` manifest lists carry both architectures.

## 10.7 On the host: `deploy/deploy.sh <tag>`

| Step | Action | On failure |
|---|---|---|
| 1 | Validate the tag (`sha-<hex>` or `vX.Y.Z`); take `state/deploy.lock` | Exit 2 or 3; nothing changed |
| 2 | Pull the three images for the tag if not present (tags are immutable) | Exit non-zero; nothing changed |
| 3 | Start PostgreSQL if needed; wait until healthy | Exit non-zero; nothing changed |
| 4 | `docker compose run --rm migrate` (Flyway as `bms_owner`) | Exit 1; old containers still serving; `failed-migration` recorded |
| 5 | `docker compose up` every default service: `api`, `web`, `proxy` are recreated on the new tag; `postgres` and, on staging, `cloudflared` only if their pinned definition changed | |
| 6 | Wait up to `READY_TIMEOUT` (240 s) for the API health check (`/readyz`: database and migrations at head), and for `web` and `api` to answer through the proxy container | Go to 7 |
| 7 | Switch back to `state/current_tag`, wait for it to be ready, record `failed-health` and `rolled-back-to`, exit 1 | Record `rollback-unhealthy`, exit 1; follow `docs/runbooks/rollback.md` |
| 8 | Write the tag to `state/current_tag`; append `deployed` to `state/history.log` | |

Migrations are never reversed on rollback, so they are expand and contract (chapter 6 section
6.9). Re-running the script with the live tag is a no-op that passes the gate. A deploy
recreates the API container, so there is a short outage while the JVM starts (NFR-AVL-03
allows 30 seconds; measured locally at under 10).

## 10.8 Security of the pipeline

- Secrets are environment secrets, masked in logs by GitHub; no step echoes one, no step uses
  `set -x`, and secrets are passed to programs through environment variables or standard
  input, never command-line arguments that could appear in a process list on the host.
- Write access to packages exists only in `build`, `staging-pointer` and `promote`, on pushes to
  `main` and version tags of this repository.
- The production host holds no registry credential between deploys. The staging host holds none
  ever: it pulls public packages and a public tarball, and nothing on GitHub can reach it.
- The production environment requires the dev lead's approval; branch protection on `main`
  requires review and green checks (`PROCESS.md` section 9).

## 10.9 GitHub configuration

| Where | Name | Value |
|---|---|---|
| Environment `staging` | none | No longer used by the pipeline (ADR-018); `STAGING_*` secrets, if set, can be deleted |
| Environment `production`, secrets | `PROD_HOST`, `PROD_SSH_KEY`, `PROD_SSH_KNOWN_HOSTS` | Host name or IP of the production VM; private key of its `deploy` user; `ssh-keyscan <host>` output taken on a trusted network |
| Environment `production`, variable | `PROD_SSH_USER` | Optional; default `deploy` |
| Environment `production`, protection rule | Required reviewer | Hillary Arinda |
| Branch protection on `main` | Required checks | `Backend (mvn verify)`, `Frontend (types, tests, build)`, `Workflows, scripts and compose files`, plus the guards |
| Packages | `bms-platform-api`, `-web`, `-proxy` | Linked to this repository; **public**, so the staging host pulls without a credential (ADR-018) |

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
