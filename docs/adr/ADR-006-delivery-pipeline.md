# ADR-006: Delivery pipeline: build once, main to staging, tag to production, migrations before swap

## Status

Accepted (2026-09-29). Superseded in part by ADR-018 (2026-09-29): staging runs on a shared ARM64
host behind a Cloudflare Tunnel and pulls its releases from a `staging` pointer tag instead of
being deployed over SSH, and images are built for `linux/amd64` and `linux/arm64`. The production
path below is unchanged.

## Context

Two developers and their coding agents will merge to `main` many times a week. The pilot
tenant's books live in this system, so a release must be repeatable, reversible and
traceable: what runs on production must be exactly what ran on staging, a failed release must
not leave the service down, and anyone must be able to tell which commit a host is running.

Constraints: one virtual machine per environment (staging, production) with Docker Compose
(chapter 9); the repository is public, so pull requests from forks must never reach secrets or
the registry; the hosts are not provisioned yet, so the pipeline must be complete and green
before they exist.

Options considered for promotion: rebuild from the tag (simple, but production then runs an
image staging never ran); deploy `latest` (not reproducible, and rollback has nothing to go
back to); build once per commit and promote the identical image by retagging.

Options considered for migrations: run them when the application starts (every replica races
to migrate, and a bad migration crash-loops the service); run them as a one-shot step before
the application containers switch.

## Decision

- **Pull request:** `.github/workflows/ci.yml` runs `mvn verify` (unit, architecture, formatting
  and integration tests on PostgreSQL 16 in Testcontainers), the frontend's contract check,
  type check, tests and build, and `actionlint`, `shellcheck` and compose validation. The
  documentation guards run alongside. It runs on `pull_request`, never
  `pull_request_target`, so a fork's code never sees a secret or a write token.
- **Merge to `main`:** `.github/workflows/deploy.yml` builds the `api`, `web` and `proxy` images
  once, tags them `sha-<first 7 of the commit>`, pushes them to
  `ghcr.io/rincoltech-solutions-ltd/bms-platform-{api,web,proxy}`, and deploys that tag to the
  GitHub Environment `staging`.
- **Tag `vX.Y.Z`:** the same workflow checks the tagged commit is on `main`, retags its
  existing `sha-<short>` images as `vX.Y.Z` without rebuilding (and fails if they do not
  exist), and deploys that tag to the GitHub Environment `production`, which requires the dev
  lead's approval.
- **On the host,** `deploy/deploy.sh <tag>` is the only deploy mechanism, for CI and for people:
  pull the pinned images, start PostgreSQL, run the migrations with
  `docker compose run --rm migrate` as `bms_owner`, switch `api`, `web` and `proxy`, then gate
  on readiness (database reachable, schema at least the image's newest migration, web
  reachable behind the proxy). If the gate fails, it switches back to the previously recorded
  tag and exits non-zero. It records every outcome in `state/history.log` and the live tag in
  `state/current_tag`. Running it twice with the same tag changes nothing.
- **Migrations are expand and contract** (chapter 6 section 6.9), because a rollback swaps
  containers and never reverses a migration: the previous release must run on the new schema.
- **Not provisioned is not a failure:** each deploy job checks its environment's `*_HOST` and
  `*_SSH_KEY` secrets; if either is missing it emits a `::notice::` and succeeds. Build, push
  and retag still run.
- **Registry access on the host** uses the workflow's own `GITHUB_TOKEN` (packages: read),
  passed over SSH standard input to `docker login` and logged out after the deploy, so no
  long-lived registry credential lives on a host.
- One deploy at a time per environment: GitHub concurrency groups `deploy-staging` and
  `deploy-production`, and a file lock in `deploy.sh`.

## Consequences

**Better:**

- Production runs byte-for-byte the images staging ran; a release is a tag, and a rollback is
  the previous tag.
- A failed migration leaves the old version serving; a failed start rolls back automatically,
  within the NFR-AVL-03 budget.
- Every host can say what it runs (`/version`, `state/current_tag`) and what it ran
  (`state/history.log`).
- The pipeline is green before any host exists, and turning a host on is adding four secrets.

**Worse:**

- Expand and contract makes some schema changes two releases long.
- Recreating the API container is a short outage (the JVM start, about 10 to 30 seconds) on a
  single host; there is no blue-green pair.
- Three images per commit are kept in the registry; old `sha-*` tags need a retention rule.

**Watch for:**

- A migration that is not backward compatible. Review every migration for it; the rollback
  path depends on it.
- Anyone deploying by hand without `deploy.sh`, or editing files on a host. The host's files
  are overwritten from the repository on every deploy.
- A tag created on a commit that failed on staging. The pipeline refuses commits not on `main`
  and images that were never built, but staging's health is the reviewer's check before
  approving production.

## Related ADRs

- ADR-003: migrations run as the owner role; the application only ever as `bms_app`.
- ADR-005: the guards that run beside CI.
- ADR-010: the `migrate` command and the readiness check live in the API image.
