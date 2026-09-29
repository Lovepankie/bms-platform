# 10. CI/CD Pipeline

**Status:** Draft · **Owner:** Hillary

## 10.1 Scope

How a change travels from a branch to production: the checks on every pull request, the
images built on `main`, the staging deploy, the production release, and the host-side deploy
and rollback. The decision is ADR-006; the hosts are chapter 9; `PROCESS.md` section 7 is the
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
            build:    api, web, proxy images, built once, pushed to GHCR as sha-<7>
            staging:  environment `staging` -> ssh -> /opt/bms/deploy.sh sha-<7>
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
| Delivery | `actionlint`, `shellcheck` on `deploy/`, `docker compose config` on both compose files | Workflows, scripts and compose files are valid |

The documentation guards (`dash-guard`, `adr-citation-guard`, `architecture-model`,
`linked-issue-guard`) run as their own workflows (ADR-005).

`pull_request` is used, never `pull_request_target`: a run for a fork's pull request gets a
read-only token and no secrets. Nothing in CI pushes images or deploys.

## 10.4 Build and publish (`deploy.yml`, push to `main`)

- `meta` resolves `sha-<first 7 characters of the commit>`.
- `build` (matrix `api`, `web`, `proxy`) builds each image once with Buildx, with the GitHub
  Actions cache per image, and pushes
  `ghcr.io/rincoltech-solutions-ltd/bms-platform-<image>:sha-<7>`. The api image receives the
  tag as `GIT_SHA`. Only this job has `packages: write`.
- The workflow runs only for this repository (`github.repository` check), so a fork's `main`
  cannot publish.

## 10.5 Staging deploy

The `deploy-staging` job runs in the GitHub Environment `staging`, in the concurrency group
`deploy-staging` (queued, never cancelled). It reads `STAGING_HOST` and `STAGING_SSH_KEY`; if
either is empty it emits a notice and succeeds. Otherwise the composite action
`.github/actions/ssh-deploy`:

1. writes the SSH key to a file readable only by the runner user, and pins the host key from
   `STAGING_SSH_KNOWN_HOSTS` (or scans it with a warning if that secret is absent);
2. copies `deploy/compose.yml`, `deploy.sh`, `backup.sh`, `postgres/` and `sql/` to `/opt/bms`;
3. pipes the workflow's `GITHUB_TOKEN` (packages: read, valid for this run only) into
   `docker login ghcr.io` on the host over SSH standard input;
4. runs `/opt/bms/deploy.sh sha-<7>` (section 10.7), then logs the host out of the registry;
5. deletes the key file, whatever happened.

## 10.6 Production release

1. Pick a commit on `main` that is green on staging.
2. `git tag vX.Y.Z <commit> && git push origin vX.Y.Z` (semantic versioning, `v0.x.y` before the
   pilot go-live; `PROCESS.md` section 7).
3. `meta` refuses a tag that is not `vMAJOR.MINOR.PATCH` or whose commit is not on `main`.
4. `promote` checks that all three `sha-<7>` images exist and retags them `vX.Y.Z` with
   `docker buildx imagetools create`. Nothing is rebuilt.
5. `deploy-production` waits for the dev lead's approval (the `production` environment's required
   reviewer), then deploys `vX.Y.Z` exactly as staging, in the concurrency group
   `deploy-production`, skipping with a notice while `PROD_HOST` or `PROD_SSH_KEY` is unset.

## 10.7 On the host: `deploy/deploy.sh <tag>`

| Step | Action | On failure |
|---|---|---|
| 1 | Validate the tag (`sha-<hex>` or `vX.Y.Z`); take `state/deploy.lock` | Exit 2 or 3; nothing changed |
| 2 | Pull the three images for the tag if not present (tags are immutable) | Exit non-zero; nothing changed |
| 3 | Start PostgreSQL if needed; wait until healthy | Exit non-zero; nothing changed |
| 4 | `docker compose run --rm migrate` (Flyway as `bms_owner`) | Exit 1; old containers still serving; `failed-migration` recorded |
| 5 | Recreate `api`, `web`, `proxy` on the new tag | |
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
- Write access to packages exists only in `build` and `promote`, on pushes to `main` and version
  tags of this repository.
- The host holds no registry credential between deploys.
- The production environment requires the dev lead's approval; branch protection on `main`
  requires review and green checks (`PROCESS.md` section 9).

## 10.9 GitHub configuration

| Where | Name | Value |
|---|---|---|
| Environment `staging`, secret | `STAGING_HOST` | Host name or IP of the staging VM |
| Environment `staging`, secret | `STAGING_SSH_KEY` | Private key of the `deploy` user on that VM |
| Environment `staging`, secret | `STAGING_SSH_KNOWN_HOSTS` | Output of `ssh-keyscan <host>` taken on a trusted network (recommended) |
| Environment `staging`, variable | `STAGING_SSH_USER` | Optional; default `deploy` |
| Environment `production`, secrets and variable | `PROD_HOST`, `PROD_SSH_KEY`, `PROD_SSH_KNOWN_HOSTS`, `PROD_SSH_USER` | As above, for production |
| Environment `production`, protection rule | Required reviewer | Hillary Arinda |
| Branch protection on `main` | Required checks | `Backend (mvn verify)`, `Frontend (types, tests, build)`, `Workflows, scripts and compose files`, plus the guards |
| Packages | `bms-platform-api`, `-web`, `-proxy` | Linked to this repository; visibility decided at provisioning (docs/runbooks/provision-host.md) |

## 10.10 Local equivalents

| Make target | Does |
|---|---|
| `make test` | `mvn verify` and the frontend tests, as CI |
| `make lint`, `make fmt` | Spotless check or apply; TypeScript check |
| `make build` | The three images, as CI builds them |
| `make openapi` | Regenerate `docs/api/openapi.json` and the frontend client after a contract change |
| `make dev`, `make seed`, `make migrate` | The local stack of chapter 9 section 9.2 |

## 10.11 Open items

- A registry retention policy for `sha-*` tags older than 90 days that no release references.
- End-to-end browser tests against staging after each deploy (chapter 15 section 15.10).
- Dependency and image vulnerability scanning (NFR-SEC-06) as a CI job.

## 10.12 Claude cloud agent sessions

Work can also run as a Claude Code session in Anthropic's cloud (runbook
`docs/runbooks/cloud-agent-sessions.md`). Such a session is one more contributor: it
works on a branch and opens a pull request, which goes through the same CI and review
as any other. The cloud sandbox ships OpenJDK 21, so the SessionStart hook
`.claude/hooks/cloud-setup.sh` installs Temurin 25 in cloud sessions only
(`CLAUDE_CODE_REMOTE=true`) and exports `JAVA_HOME` for the session. Developer
machines are unaffected.
