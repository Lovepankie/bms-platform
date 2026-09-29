# 9. Infrastructure Design

**Status:** Draft · **Owner:** Hillary

## 9.1 Scope

Where the platform runs, what runs there, how it is sized, how traffic reaches it, where
configuration and secrets live, and how data is backed up and restored. The pipeline that
puts releases on these hosts is chapter 10 and ADR-006. The step-by-step procedures are in
`docs/runbooks/`.

## 9.2 Environments

| Environment | Where | Deployed by | Data |
|---|---|---|---|
| Local | A developer's machine, `docker-compose.yml` at the repository root (`make dev`) | The developer | Fabricated; the `demo` tenant from `make seed` |
| CI | GitHub-hosted Ubuntu runners; PostgreSQL 16 in Testcontainers | Every pull request and push to `main` | Fabricated, per test |
| Staging | One cloud VM (Hetzner Cloud, Ubuntu LTS), `/opt/bms` | Every merge to `main`, automatically | Fabricated tenants only (chapter 15 section 15.8) |
| Production | One cloud VM (Hetzner Cloud, Ubuntu LTS), `/opt/bms` | A `vX.Y.Z` tag, after the dev lead approves | Real tenants |

Staging and production are separate VMs with separate databases, separate `.env` files,
separate R2 prefixes and separate GitHub Environments. Nothing is shared between them. Neither
host is provisioned yet; until they are, deploy jobs skip with a notice (ADR-006).

## 9.3 Topology of one environment

```
                 Internet (HTTPS 443, HTTP 80 redirects)
                                  |
            DNS: <base> and *.<base>  ->  VM public IPv4 (Cloudflare DNS, not proxied)
                                  |
+--------------------------------- VM (Docker Compose project "bms") -----------------------+
|                                                                                            |
|  proxy (Caddy + Cloudflare DNS module)  :80 :443   wildcard TLS, security headers          |
|     |  /api/*, /healthz, /readyz, /version  ->  api:8080                                   |
|     |  api.<base>: provider callbacks only  ->  api:8080                                   |
|     |  everything else                      ->  web:8080                                   |
|     v                                                                                      |
|  api (Spring Boot, bms_app)  :8080 app, :8081 management (never published)                 |
|     |  one transaction per request, app.tenant_id bound per transaction                   |
|     |  db-scheduler polling (the worker role, ADR-008)                                      |
|     v                                                                                      |
|  postgres (PostgreSQL 16)  volume pgdata       migrate (one-shot, bms_owner) -> postgres   |
|  web (Caddy serving the PWA bundle)  :8080                                                 |
+--------------------------------------------------------------------------------------------+
        |                                                         |
        | nightly backup.sh (pg_dump, encrypted)                   | documents (later)
        v                                                         v
   Cloudflare R2: bms-backups/<environment>/{daily,monthly}/   Cloudflare R2: documents bucket
```

Only the proxy publishes ports. The API, web and database are reachable only on the Compose
network. There is no Redis (ADR-008).

## 9.4 Sizing: a 4 GB VM

The starting size is a 2 vCPU, 4 GB, 40 GB disk VM per environment (for example Hetzner
CX22). Memory is budgeted with container limits so one runaway process cannot starve the rest:

| Container | Limit | Notes |
|---|---|---|
| `api` | 1536 MiB | JVM heap up to 70 percent of the limit (`-XX:MaxRAMPercentage=70`); the rest is metaspace (capped at 256 MiB), thread stacks, direct buffers, code cache. G1 collector; `ExitOnOutOfMemoryError` so Docker restarts a broken JVM rather than leaving it half alive. |
| `postgres` | 1280 MiB | `shared_buffers=384MB`, `effective_cache_size=1GB`, `work_mem=8MB`, `maintenance_work_mem=128MB`, `max_connections=60`. |
| `proxy` | 128 MiB | Caddy. |
| `web` | 64 MiB | Caddy serving static files. |
| Host and burst | about 1 GB | OS, Docker, the nightly backup, `deploy.sh`. |

The API pool is 10 connections (`BMS_DB_POOL_SIZE`). Virtual threads serve requests, so the pool,
not a thread count, is the concurrency limit on the database. When the capacity dataset
(NFR-CAP-01) or real load shows pressure, the next step is an 8 GB VM with the same files and
larger limits, not a second host.

## 9.5 Edge proxy, DNS and TLS

**Why a custom proxy image.** Every tenant is a subdomain (`<slug>.<base>`), plus `app.<base>`
for the platform console and `api.<base>` for provider callbacks (chapter 7 section 7.2). One
wildcard certificate for `*.<base>` covers them all, including tenants created later, with no
per-tenant certificate step. Let's Encrypt issues wildcard certificates only through the
**ACME DNS-01 challenge**: Caddy must create a `_acme-challenge` TXT record in the base domain's
DNS zone. The stock Caddy image has no DNS provider modules, so `deploy/caddy/Dockerfile` builds
Caddy with exactly one, `caddy-dns/cloudflare`, and bakes in `deploy/caddy/Caddyfile`. The image
is built and tagged with the api and web images, so a release pins all three.

**DNS records** (Cloudflare zone of the base domain; records DNS only, not proxied, so Caddy
terminates TLS itself):

| Record | Value |
|---|---|
| `A <base>` | VM IPv4 (the apex redirects to `app.<base>`) |
| `A *.<base>` | VM IPv4 |
| `AAAA` for both | VM IPv6, if used |

Staging uses its own base domain (for example `staging.<product domain>`, with
`*.staging.<product domain>`), so staging and production certificates, cookies and hosts never
overlap.

**Token.** `CLOUDFLARE_API_TOKEN` is a Cloudflare API token scoped to `Zone.DNS:Edit` on the base
domain's zone only, stored in the host `.env`. It cannot read or change anything else.

**Routing and headers** (`deploy/caddy/Caddyfile`): HSTS for one year, `nosniff`,
`Referrer-Policy: same-origin`, a restrictive `Permissions-Policy`, and a Content Security
Policy of `default-src 'self'` with no inline scripts (chapter 8 section 8.8). Request bodies
are capped at 6 MB (5 MB uploads plus framing). HTTP/3 is enabled on UDP 443.

## 9.6 Images and containers

| Image | Built from | Runs as | Health |
|---|---|---|---|
| `bms-platform-api` | `backend/Dockerfile`: Maven build on `maven:3.9-eclipse-temurin-25`, layered jar on `eclipse-temurin:25-jre` | uid 10001 | `/readyz` over bash `/dev/tcp` (the JRE image has no curl) |
| `bms-platform-web` | `frontend/Dockerfile`: `npm ci && npm run build` on `node:22-alpine`, served by `caddy:2.11-alpine` | uid 10001 | `deploy.sh` fetches `/` through the proxy |
| `bms-platform-proxy` | `deploy/caddy/Dockerfile` | root (binds 80 and 443) | container running |
| PostgreSQL | `postgres:16.x-alpine`, pinned in the host `.env` (`POSTGRES_IMAGE`) | postgres | `pg_isready` |

The API image carries two commands: the default starts the application; `migrate` applies the
Flyway migrations as `bms_owner` and exits (ADR-006, ADR-010). The API image also carries the
commit it was built from (`BMS_GIT_SHA`), shown by `/version` and `/actuator/info`.

Actuator is locked down: all endpoints are disabled except `health` and `info`, which exist only
on the management port 8081 (never published). The application port exposes `/healthz`
(liveness) and `/readyz` (readiness: database reachable and schema at the image's newest
migration) with status only, no details.

## 9.7 Database

- PostgreSQL 16, one database `bms`, schema `public`, data on the named volume `pgdata`.
- Roles (chapter 6 section 6.3.1) are created by `deploy/postgres/initdb/01-roles.sh` the first
  time the volume is initialised: `bms_owner` (owns the schema; `BYPASSRLS` so that `pg_dump`
  and the `SECURITY DEFINER` resolvers see every tenant) and `bms_app` (`NOBYPASSRLS`, owns
  nothing). The container superuser `postgres` is used only by that script.
- Migrations run only through the one-shot `migrate` container. Nothing else changes the schema.
- `pg_trgm` is created by the first migration (a trusted extension; the database owner may
  create it).

## 9.8 Configuration and secrets

- Every variable is listed with a placeholder in `.env.example` at the repository root.
- On a host: `/opt/bms/.env`, mode 600, owned by the deploy user; one per environment; never
  committed; never copied between environments. Compose passes each service only the variables
  it needs: the API gets the `bms_app` password and never the owner's; only the `migrate`
  container gets `bms_owner`.
- In GitHub: environment secrets in `staging` and `production` (chapter 10 section 10.9). The
  registry credential used on the host is the workflow's own short-lived token.
- Production refuses development switches at startup: `ALLOW_TENANT_HEADER` and `AUTH_MODE=dev`
  are accepted only in the `dev` and `test` profiles, and the servers run the `server` profile.

## 9.9 Background jobs

db-scheduler runs inside the API process with its state in `scheduled_tasks` (ADR-008). No
separate worker container exists yet; `BMS_SCHEDULER_ENABLED` lets one be split out later from
the same image.

## 9.10 Backups and restore

`deploy/backup.sh`, run nightly by cron at 01:15 host time (NFR-BAK-01 to NFR-BAK-03):

1. `pg_dump --format custom` as `bms_owner` inside the postgres container;
2. encrypted on the host with AES-256 (`openssl enc -aes-256-cbc -pbkdf2 -iter 200000`) using
   `BACKUP_ENCRYPTION_KEY`, before anything leaves the host (NFR-BAK-02);
3. uploaded with `rclone` (pinned image, configured from environment variables, no config
   file) to `r2:<R2_BACKUP_BUCKET>/<environment>/daily/`, and on the first of the month also to
   `monthly/`;
4. daily copies older than 30 days and monthly copies older than 365 days are deleted
   (NFR-BAK-03);
5. `state/last_backup_ok` is written for the missing-backup alert.

The R2 token is scoped to the backup bucket. The encryption key has an offline copy held by
the dev lead (chapter 8 section 8.7). The restore procedure and the quarterly drill
(NFR-BAK-04) are `docs/runbooks/restore-from-backup.md`.

## 9.11 Monitoring and alerts

To be configured when the hosts are provisioned (NFR-OBS-04): an external uptime check on
`https://app.<base>/healthz` and on one tenant host every minute; an alert when
`state/last_backup_ok` is older than 26 hours; disk above 70 percent; failed db-scheduler
executions (`scheduled_tasks.consecutive_failures > 0`); the nightly job failure alerts of
chapter 5. The API logs JSON (ECS format) with the request id on every line in the `server`
profile.

## 9.12 Host layout

```
/opt/bms/
  compose.yml            copied from deploy/ on every deploy
  deploy.sh, backup.sh   copied from deploy/ on every deploy
  postgres/initdb/       copied from deploy/ on every deploy
  sql/                   onboarding scripts (docs/runbooks/onboard-tenant.md)
  .env                   written once by hand (docs/runbooks/provision-host.md); mode 600
  state/current_tag      the live release
  state/history.log      every deploy, rollback and failure with its time
  state/last_backup_ok   time of the last good backup
  state/deploy.lock      one deploy at a time
  backups/               transient; each encrypted dump is deleted after upload
```

## 9.13 Open items

- Confirm the hosting region against the data protection requirement for processing outside
  Uganda before production data arrives (NFR-DP-07); record the outcome in an ADR.
- Choose the production and staging base domains and create the Cloudflare zone and tokens.
- Registry retention for old `sha-*` images.
- Off-host log shipping and an error tracker (NFR-OBS-03).
