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
| Staging | A shared ARM64 host at a Rincol home site (Raspberry Pi 4, Debian 13), `/opt/bms`, behind a Cloudflare Tunnel (section 9.13, ADR-018) | Every green build of `main`: the host pulls the `staging` pointer | Fabricated tenants only (chapter 15 section 15.8) |
| Production | One cloud VM (Hetzner Cloud, Ubuntu LTS), `/opt/bms` | A `vX.Y.Z` tag, after the dev lead approves | Real tenants |

Staging and production are separate hosts with separate databases, separate `.env` files,
separate R2 prefixes and separate host names. Nothing is shared between them. Sections 9.3 to 9.5
describe the production VM (ADR-006), which is not provisioned yet; until it is, its deploy job
skips with a notice. Staging differs as section 9.13 describes (ADR-018).

## 9.3 Topology of the production VM

```
                 Internet (HTTPS 443, HTTP 80 redirects)
                                  |
     DNS: bms, bms-callbacks, <slug>-bms (.rincoltech.com)  ->  VM public IPv4 (not proxied)
                                  |
+--------------------------------- VM (Docker Compose project "bms") -----------------------+
|                                                                                            |
|  proxy (Caddy + Cloudflare DNS module)  :80 :443   wildcard TLS, security headers          |
|     |  /api/*, /healthz, /readyz, /version  ->  api:8080                                   |
|     |  callback host: provider callbacks only -> api:8080                                  |
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

## 9.4 Sizing: a 4 GB VM (production)

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

**Host names** (ADR-018, chapter 7 section 7.2). Every BMS host is one label under the Rincol zone,
`rincoltech.com`: the platform console `bms.rincoltech.com`, tenants `<slug>-bms.rincoltech.com`,
provider callbacks `bms-callbacks.rincoltech.com`; staging uses `bms-staging`,
`<slug>-bms-staging` and `bms-staging-callbacks`. One label deep means one wildcard certificate,
`*.rincoltech.com`, covers them all, including tenants created later, with no per-tenant
certificate step. The API and the PWA read the pattern and the platform host from
`BMS_TENANT_HOST_PATTERN` and `BMS_PLATFORM_HOST`.

**Why a custom proxy image (production VM).** Let's Encrypt issues wildcard certificates only
through the **ACME DNS-01 challenge**: Caddy must create a `_acme-challenge` TXT record in the
zone. The stock Caddy image has no DNS provider modules, so `deploy/caddy/Dockerfile` builds
Caddy with exactly one, `caddy-dns/cloudflare`, and bakes in `deploy/caddy/Caddyfile`, whose one
site is `*.<BMS_DNS_ZONE>`. The image is built and tagged with the api and web images, so a
release pins all three. It also carries `Caddyfile.tunnel`, the staging host's internal origin
(section 9.13).

**DNS records on the production path** (Cloudflare zone `rincoltech.com`; records DNS only, not
proxied, so Caddy terminates TLS itself): an `A` (and `AAAA` if used) record for
`bms`, `bms-callbacks` and each `<slug>-bms` pointing at the VM. The zone apex and the company's
other names are not served by BMS. Staging's records are proxied CNAMEs to its tunnel instead
(section 9.13).

**Token.** `CLOUDFLARE_API_TOKEN` is a Cloudflare API token scoped to `Zone.DNS:Edit` on the zone
only, stored in the production host's `.env` only. It cannot read or change anything else.

**Routing and headers** (`deploy/caddy/Caddyfile`): HSTS for one year, `nosniff`,
`Referrer-Policy: same-origin`, a restrictive `Permissions-Policy`, and a Content Security
Policy of `default-src 'self'` with no inline scripts (chapter 8 section 8.8). Request bodies
are capped at 6 MB (5 MB uploads plus framing). HTTP/3 is enabled on UDP 443.

## 9.6 Images and containers

| Image | Built from | Runs as | Health |
|---|---|---|---|
| `bms-platform-api` | `backend/Dockerfile`: Maven build on `maven:3.9-eclipse-temurin-25`, layered jar on `eclipse-temurin:25-jre` | uid 10001 | `/readyz` over bash `/dev/tcp` (the JRE image has no curl) |
| `bms-platform-web` | `frontend/Dockerfile`: `npm ci && npm run build` on `node:22-alpine`, served by `caddy:2.11-alpine`; also serves `/app-config.json` from `BMS_TENANT_HOST_PATTERN` and `BMS_PLATFORM_HOST` | uid 10001 | `deploy.sh` fetches `/` through the proxy |
| `bms-platform-proxy` | `deploy/caddy/Dockerfile` with the `deploy/` context: Caddy with the Cloudflare DNS module, `Caddyfile` (production edge) and `Caddyfile.tunnel` (staging origin), plus the release's host files in `/usr/share/bms-deploy` for the staging puller | root (binds 80 and 443 on the VM) | container running |

Every image is a manifest list for `linux/amd64` and `linux/arm64` (ADR-018). Compilation runs
once, natively, in a stage pinned to the build machine's platform (a jar and a JavaScript bundle
are the same on every architecture; Caddy is cross-compiled by Go); the per-target runtime stages
contain no `RUN` step, so nothing executes under emulation.
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
- In GitHub: environment secrets in `production` (chapter 10 section 10.9), plus repository secrets
  `COSIGN_PRIVATE_KEY` and `COSIGN_PASSWORD`, used only by `deploy.yml`'s build job to sign each
  image it pushes (ADR-018 finding H2). The registry credential used on the production host is the
  workflow's own short-lived token. The three GHCR packages stay private (ADR-018 finding M5): the
  staging host holds no GitHub credential, but its `bms` user logs in to GHCR once with a token
  scoped to `read:packages`, which Docker remembers for every pull. Its host files still come inside
  the release's proxy image, never from GitHub directly.
- Host names: `BMS_TENANT_HOST_PATTERN` and `BMS_PLATFORM_HOST` for the API and the web
  container; on the production VM also `BMS_DNS_ZONE` and `BMS_CALLBACK_HOST` for the proxy; on
  the staging host `CLOUDFLARE_TUNNEL_TOKEN` and `CLOUDFLARED_IMAGE` for cloudflared.
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
`https://<platform host>/healthz` (staging `https://bms-staging.rincoltech.com/healthz`) and on one
tenant host every minute; an alert when
`state/last_backup_ok` is older than 26 hours; disk above 70 percent; failed db-scheduler
executions (`scheduled_tasks.consecutive_failures > 0`); the nightly job failure alerts of
chapter 5. The API logs JSON (ECS format) with the request id on every line in the `server`
profile.

## 9.12 Host layout

```
/opt/bms/
  compose.yml            production: copied from deploy/ on every deploy. Staging: refreshed from
                          releases/<tag>/ only after deploy.sh succeeds (ADR-018 finding M3)
  deploy.sh, backup.sh   as above
  pull-staging.sh        staging only: the puller; also refreshed only after a successful deploy
  releases/<tag>/        staging only: each release's host files, staged before deploy.sh runs;
                          the previous one to two releases are kept for inspection and rollback
  current                staging only: symlink to the live releases/<tag>/, updated only on success
  cosign.pub             the signing public key (ADR-018 finding H2): committed at deploy/cosign.pub,
                          provisioned once by hand, never replaced by a release
  systemd/               staging only: the unit files, for reference; installed by hand
  postgres/initdb/       copied from deploy/ on every deploy
  sql/                   onboarding scripts (docs/runbooks/onboard-tenant.md)
  .env                   written once by hand (docs/runbooks/provision-host.md); mode 600
  state/current_tag, current_created   the live release and when it was built (for the downgrade guard)
  state/history.log      every deploy, rollback and failure with its time
  state/last_backup_ok   time of the last good backup
  state/deploy.lock      one deploy at a time
  state/last_failed_tag  staging only: a release the puller will not retry
  state/pull.lock        staging only: one puller run at a time
  state/allow_downgrade  staging only: presence lets deploy.sh accept a release built before the live one
  backups/               transient; each encrypted dump is deleted after upload
```

## 9.13 Staging: a shared ARM64 host behind a Cloudflare Tunnel

Staging runs on an ARM64 host that already exists at a Rincol home site: a Raspberry Pi 4 with
8 GB, Debian 13 and SD card storage, shared with an unrelated edge workload and a capped CI runner
(ADR-018). BMS may use about 900 MB of it, hard-capped. The site has no inbound ports.

```
 Visitor --HTTPS--> Cloudflare edge (TLS for *.rincoltech.com; proxied CNAMEs:
                    bms-staging, <slug>-bms-staging  ->  <tunnel id>.cfargotunnel.com)
                                  ^
                                  | outbound-only tunnel, opened by cloudflared
+------------- ARM64 host, Docker Compose project "bms", all in bms.slice (900 MB) ----------+
|  cloudflared  --http-->  proxy :8080 (Caddyfile.tunnel; security headers, 6 MB bodies)      |
|                             |  /api/*, /healthz, /readyz, /version  ->  api:8080            |
|                             |  everything else                       ->  web:8080            |
|  api (bms_app)   postgres (volume pgdata)   web   migrate (one-shot, bms_owner)             |
|  bms-pull.timer (every 2 min) -> pull-staging.sh -> deploy.sh <sha-tag>                     |
+--------------------------------------------------------------------------------------------+
        | nightly backup.sh (pg_dump, encrypted)  ->  Cloudflare R2: bms-backups/staging/
```

- **No published port.** `deploy/compose.pi-staging.yml` (installed as `/opt/bms/compose.yml`)
  publishes nothing. cloudflared dials out to Cloudflare with the connector token
  `CLOUDFLARE_TUNNEL_TOKEN` and forwards each public hostname to `http://proxy:8080`, keeping the
  original `Host`, which the API resolves the tenant from. TLS ends at Cloudflare; no certificate
  or DNS token lives on the host. Caddy trusts `Cf-Connecting-Ip` from the private network only, and
  overwrites `X-Forwarded-Host` from that same resolved `Host` in both its proxy blocks (ADR-018
  finding M1): the API's Tomcat is also configured to ignore any `X-Forwarded-Host`, so a request's
  tenant and platform resolution can only ever come from `Host`.
- **Two networks.** `edge` (cloudflared, proxy) and `internal` (proxy, api, web, postgres, migrate):
  cloudflared can reach only `proxy:8080`, never PostgreSQL or the API's management port, whatever a
  tunnel ingress rule or an onboarding token change gets wrong (ADR-018 finding M6).
- **Public hostnames.** The tunnel carries `bms-staging.rincoltech.com` and one entry per tenant,
  `<slug>-bms-staging.rincoltech.com`, each with a proxied CNAME to `<tunnel id>.cfargotunnel.com`
  (`docs/runbooks/onboard-tenant.md`).
- **Memory budget.** Container limits, and the slice that caps them all:

  | Container | Limit | Notes |
  |---|---|---|
  | `api` | 448 MB | Serial GC, C1 only (`TieredStopAtLevel=1`), heap at most 50 percent, metaspace 128 MB (ADR-018 finding L6: was 160 MB, leaving too little headroom), code cache 48 MB, 512 KB stacks, 24 Tomcat threads, pool of 5. Measured at about 235 MB after start. |
  | `postgres` | 176 MB | `shared_buffers=48MB`, `effective_cache_size=128MB`, `work_mem=2MB`, `max_connections=20`, longer checkpoints to spare the SD card. |
  | `cloudflared` | 48 MB | |
  | `proxy`, `web` | 32 MB each | Caddy |
  | `migrate` | 160 MB | Only while a deploy runs, next to the old API |
  | **Total** | 736 MB steady, 896 MB during a deploy | `bms.slice`: `MemoryMax=900M`, `MemoryHigh=860M`, no swap, `CPUQuota=250%` |

  Every container is started with `cgroup_parent: bms.slice`, so the slice is a hard cap for the
  whole stack: under pressure the kernel reclaims and then kills inside BMS, never in the other
  workloads.
- **SD card.** Container logs use the `local` driver capped at 2 x 5 MB per container; the puller
  removes release images other than the live and previous ones; PostgreSQL checkpoints every 15
  minutes. The nightly backup to R2 (section 9.10) is the recovery path if the card fails.
- **Deploy.** Pull-based (chapter 10 section 10.5): `bms-pull.timer` runs `pull-staging.sh` every
  two minutes as the `bms` user (a member of the `docker` group, no password or SSH key).
- **Sizing is for fabricated data** and a handful of testers. Response times on staging do not
  predict production.

## 9.14 Open items

- Confirm the hosting region against the data protection requirement for processing outside
  Uganda before production data arrives (NFR-DP-07); record the outcome in an ADR.
- Create the production Cloudflare token scoped to the `rincoltech.com` zone when the production
  VM is provisioned.
- Registry retention for old `sha-*` images.
- Off-host log shipping and an error tracker (NFR-OBS-03).
