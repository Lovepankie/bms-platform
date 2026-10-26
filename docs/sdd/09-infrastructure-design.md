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
| CI | Self-hosted runners (Jerome's laptop first, the staging Pi as overflow; ADR-018 amendment 2026-10-16) and, for the deploy jobs only, the dedicated deploy runner on the staging host (ADR-040); PostgreSQL 16 in Testcontainers | Every pull request and push to `main` | Fabricated, per test |
| Staging | A shared ARM64 host at a Rincol home site (Raspberry Pi 4, Debian 13), `/opt/bms`, behind a Cloudflare Tunnel (section 9.13, ADR-018) | Every green build of `main`: the host pulls the `staging` pointer | Fabricated tenants only (chapter 15 section 15.8) |
| Production | One cloud VM (Ubuntu 24.04 LTS), `/opt/bms`, created by Terraform in `infra/production/` on Hetzner Cloud or AWS EC2, the owner's choice (section 9.3.1, ADR-063); not created yet | Today's pipeline: a `vX.Y.Z` tag, after the dev lead approves (ADR-006); proposed: the host pulls a signed `production` pointer the owner moves (ADR-063) | Real tenants |

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
|  web (Caddy serving the PWA bundle)  :8080, volume web_assets read-only (ADR-078)          |
+--------------------------------------------------------------------------------------------+
        |                                                         |
        | nightly backup.sh (pg_dump, encrypted)                   | documents (later)
        v                                                         v
   Cloudflare R2: bms-backups/<environment>/{daily,monthly}/   Cloudflare R2: documents bucket
```

Only the proxy publishes ports. The API, web and database are reachable only on the Compose
network. There is no Redis (ADR-008).

### 9.3.1 Provisioning with Terraform (ADR-063)

The VM and everything it needs outside itself are code in `infra/production/`, reviewed and not applied
until production day (runbook `docs/runbooks/terraform-production.md`). Two roots create the same host on
two providers, and the owner applies only the one chosen: `hetzner/` (a server with delete and rebuild
protection, Hetzner backups only if the owner chooses them, a Hetzner firewall) and `aws/` (EC2 with termination protection, IMDSv2 only,
an encrypted gp3 disk, an Elastic IP, a security group). Both use two shared modules:

| Module | Creates |
|---|---|
| `modules/cloud-init` | The first-boot configuration: the `ops` user for people (SSH keys only, sudo), sshd with no passwords and no root, Docker from Docker's apt repository, unattended upgrades, fail2ban, the `bms` service user in the docker group, `/opt/bms`, and the units in `deploy/systemd` (`bms-pull`, `bms-monitor`, `bms-restore-drill`), installed but not enabled until the host has its `.env` |
| `modules/cloudflare` | In the existing Cloudflare account: A (and on Hetzner AAAA) records for `bms`, `bms-callbacks` and each `<slug>-bms`, DNS only; the R2 backup bucket with the lock rules (`production/daily/` 30 days, `production/monthly/` 365 days) and lifecycle rules (a day later) of ADR-058; the R2 documents bucket; and three account tokens for the host, each limited to the VM's addresses if Cloudflare enforces the IP condition: object read and write on the backup bucket, the same on the documents bucket, and DNS edit plus zone read on the whole zone for Caddy's DNS-01 challenge (an accepted risk: a host compromise could change mail records; ADR-063 decision 4) |

Firewall on both: inbound 22 from the admin address list only (the whole internet is refused by
validation), 80 and 443 TCP and 443 UDP from anywhere, nothing else; outbound open. The provider
location or region, the server size and the R2 location have no default: they are the owner's decisions,
and the region is the data protection decision of NFR-DP-07. No secret goes into cloud-init (user data
is readable from the provider's metadata service). The three host tokens are created by Terraform, so
their values are in Terraform state, which lives in its own private R2 bucket and never in git; the host
receives them once through `terraform output -raw host_env`.

## 9.4 Sizing: a 4 GB VM (production)

The starting size is a 2 vCPU, 4 GB, 40 GB disk VM per environment (for example Hetzner
CX23 or an AWS t3.medium). Memory is budgeted with container limits so one runaway process cannot starve the rest:

| Container | Limit | Notes |
|---|---|---|
| `api` | 1536 MiB | JVM heap up to 70 percent of the limit (`-XX:MaxRAMPercentage=70`); the rest is metaspace (capped at 256 MiB), thread stacks, direct buffers, code cache. G1 collector; `ExitOnOutOfMemoryError` so Docker restarts a broken JVM rather than leaving it half alive. |
| `postgres` | 1280 MiB | `shared_buffers=384MB`, `effective_cache_size=1GB`, `work_mem=8MB`, `maintenance_work_mem=128MB`, `max_connections=60`. |
| `proxy` | 128 MiB | Caddy. |
| `web` | 64 MiB | Caddy serving static files. |
| Host and burst | about 1 GB | OS, Docker, the nightly backup, `deploy.sh`. |

The API pool is 10 connections (`BMS_DB_POOL_SIZE`). Virtual threads serve requests, so the pool,
not a thread count, is the concurrency limit on the database. Every pool connection starts with a 60 s
`statement_timeout` and a 60 s `idle_in_transaction_session_timeout` (`BMS_DB_STATEMENT_TIMEOUT`,
`BMS_DB_IDLE_IN_TRANSACTION_TIMEOUT`, ADR-028). The PostgreSQL settings above were measured with
`scripts/db-bench` at 25 times the staging data (ADR-028) and are kept; the recommended values,
including `pg_stat_statements` for production, are in `deploy/postgres/recommended/` and change only
through `docs/runbooks/database-tuning.md`. `docs/runbooks/database-health-check.md` is the monthly
check of slow queries, bloat and indexes. When the capacity dataset
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

**Upstreams** (`deploy/caddy/upstreams.caddy`, imported by both Caddyfiles, ADR-083). Every route
proxies to `dynamic a api 8080` or `dynamic a web 8080`, refreshed each second, so during a deploy
both the old and the new container of a service take requests. A refused connection marks that
address down for 2 seconds and the request goes to another; with none answering, Caddy tries again
every 250 ms for up to 30 seconds before a 502. Only a failed connection is retried, and keep-alive
to the containers is off so that a POST is never written on a connection the old container is
closing. Every site also imports `not_delivered`: a 502, 503 or 504 whose error is a failed dial or
`no upstreams available`, the only case that proves nothing reached a container, answers with the
header `Bms-Not-Delivered: 1` and an `app_updating` problem; any other proxy error stays bare, and the
PWA reads it as possibly saved (chapter 7 section 7.8).

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

The web image serves the bundle in three classes, which `frontend/Caddyfile` keeps apart:

| Path | Cache-Control | Missing file |
|---|---|---|
| `/assets/*` and `/workbox-*.js`, everything the build names with a content hash | `public, max-age=31536000, immutable` | the same path in `/srv/previous` (earlier builds, ADR-078), else 404, `no-store` |
| `/app-config.json`, built from the container's environment | `no-cache` | not applicable, it is a `respond` and never reads the disk |
| everything else (`/`, every route, `sw.js`, `push-sw.js`, the BMS icons) | `no-cache` | `/index.html`, so the router takes over |

The installed app's manifest and its icons are not files of the web image: the API serves them per host
under `/api/v1/branding/app/` (ADR-066), so the edge proxy sends them to the API with the rest of `/api/*`,
the manifest `no-cache` with an ETag and the icons drawn from the business's logo for an hour. The BMS icons
above are their fallback.

Nothing hashed is ever served through the single-page fallback. `index.html` names the hashed
assets of its own build, so a copy of it kept from an earlier deploy asks for files that are gone;
answered by the fallback, those requests return 200 with HTML, which the browser refuses as a
stylesheet or a module under `X-Content-Type-Options: nosniff` and then holds for a year under the
immutable header. The device renders the app unstyled until its site data is cleared. The workbox
runtime belongs in the same class although it sits at the root rather than under `/assets`:
`sw.js` reaches it with `importScripts`, where HTML throws a `SyntaxError` and the service worker
fails to install, costing offline and push as well. A 404 that is not cached keeps the failure
loud and lets the next load repair itself. For the same reason every HTML response revalidates: a
path matcher cannot do it, because `header /index.html` matches the literal path only while real
pages arrive as `/` or a route.

Earlier builds' hashed files stay served for 14 days (ADR-078). A page left open, or a service
worker that has not yet taken the new build, still runs the previous `index.html` and loads its
screens lazily by hashed names the new image does not carry. So `web` mounts the named volume
`web_assets` read-only at `/srv/previous`, and the Caddyfile tries a hashed path there after
`/srv`; `/previous` itself answers 404. `deploy.sh` fills it just before every switch, rollbacks
included: it runs the outgoing web image, by its local image ID with `pull_policy: never` on the service, once as the
one-shot `keep-assets` service (no network, no capability, read-only root, 32 MB). That run first
deletes files whose date is more than `BMS_KEEP_ASSETS_DAYS` (default 14) days old, that is files
that left the live build more than 14 days ago, then copies that build's `/assets` tree and
`/workbox-*.js` without overwriting anything, refreshing the date of names it already holds. It has
60 seconds before a deploy's switch (`BMS_KEEP_ASSETS_TIMEOUT`) and 20 before a rollback's. A
failure or a timeout is a warning, never a failed deploy or rollback. The volume is not backed up; losing it costs
a reload on an old copy, not data.

During a deploy a new `api` or `web` container starts beside the old one and the old one stops
only once the new one's health check passes (`/readyz` for the api, a `wget` of `/` every 5
seconds for web), ADR-083. Before the new web starts, its own hashed files are copied into
`web_assets` (`keep-assets` run by the incoming image), so during the overlap the old container also
serves them and a page from the new one loads whichever container answers; if that copy fails, web
restarts in place instead. The proxy is recreated only when what it runs changed.

The API image carries two commands: the default starts the application; `migrate` applies the
Flyway migrations as `bms_owner` and exits (ADR-006, ADR-010). The API image also carries the
commit it was built from (`BMS_GIT_SHA`), shown by `/version` and `/actuator/info` and sent on every response as
`Bms-Build`. The web image is built with the same sha tag and the commit time (`GIT_SHA`, `GIT_TIME`), so a running app
knows its own build, loads a newer one by itself and can be refused with 426 `app_too_old` below `BMS_MIN_CLIENT_BUILD`
(ADR-088).

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
- Sign-in limits (ADR-059, issue #426), optional: `BMS_AUTH_SIGN_IN_FAILURES_PER_IP` (FAILED requests from one client
  IP in 10 minutes on the public sign-in, two-step, invitation and setup routes; 300; a success costs nothing, so a
  mobile carrier's NAT address shared by many users is not used up by their sign-ins) and
  `BMS_AUTH_ARGON2_MAX_CONCURRENT` (argon2id hashes at once; 4 in production, 2 on the staging host's small heap). The
  five-failure lock per address is in the database and is not a setting.
- `BMS_ENVIRONMENT`: `deploy/compose.yml` (production only) passes `${BMS_ENVIRONMENT:-production}`, so an unset value
  keeps every production guard on there (SMTP required, ADR-059); the staging and local files leave it unset.
- Container logs (ADR-059, issue #426): every service of `deploy/compose.yml` uses Docker's `local` driver capped at
  five files of 20 MB (about 100 MB a container at most, compressed), the local `docker-compose.yml` three of 10 MB,
  and the staging file keeps two of 5 MB. The API's JSON lines are redacted of personal data before Docker sees them
  (chapter 8 section 8.9), and PostgreSQL runs with `log_error_verbosity=terse` in all three files, so its log never
  prints a refused duplicate's key value.
- Web Push (ADR-050), optional on every host: `BMS_PUSH_VAPID_PUBLIC_KEY`, `BMS_PUSH_VAPID_PRIVATE_KEY`, `BMS_PUSH_SUBJECT` and
  `BMS_PUSH_EXTRA_ENDPOINT_HOSTS`; both compose files pass them; unset means push is off (`docs/runbooks/push-notifications.md`).
- Outbox senders (ADR-024, spec section 11), optional on every host except that email is required in production:
  `BMS_SMTP_HOST`,
  `BMS_SMTP_PORT` (465, implicit TLS; port 587 is blocked on the staging host), `BMS_SMTP_USER`,
  `BMS_SMTP_PASSWORD` and `BMS_MAIL_FROM` for email (with `BMS_SUPPORT_CONTACT`, the optional support line in
  the footer of every email, empty by default; issue #396, ADR-054), `BMS_TELEGRAM_BOT_TOKEN` and
  `BMS_TELEGRAM_OPERATOR_CHAT_ID` for operator alerts. Both compose files pass them to the API
  only. With a sender's values unset it is off: its messages wait as `pending` and nothing fails, except email in
  production: with `BMS_ENVIRONMENT=production` the API refuses to start unless `BMS_SMTP_HOST`, `BMS_SMTP_USER`,
  `BMS_SMTP_PASSWORD` and `BMS_MAIL_FROM` (an address) are set, naming the unset ones (ADR-059, issue #426).
  The staging host's `/opt/bms/.env` holds them since 2026-10-06 (issue #89). Outbound traffic is
  SMTPS to the mail provider and HTTPS to `api.telegram.org`; nothing is opened inbound.
  `BMS_SIGNUP_MAX_APPLICATIONS_PER_HOUR` (default 30) and
  `BMS_SIGNUP_MAX_VERIFICATION_EMAILS_PER_HOUR` (default 60) are the global sign-up caps.
- SMS (ADR-036, issue #212, `docs/runbooks/sms-setup.md`), optional on every host: `BMS_ENVIRONMENT` (the staging compose
  file fixes it to `staging`; production sets it in `.env`; anything but `production` is treated as not production),
  `BMS_SMS_PROVIDER` (`africastalking` or `fake`), `BMS_SMS_MODE` (`off`, `sink`, `allowlist`, `live`; unset is `sink` on staging
  and `off` elsewhere), `BMS_SMS_ALLOWLIST`, `BMS_SMS_PLATFORM_DAILY_CAP` (default 2000), `BMS_SMS_AT_BASE_URL`,
  `BMS_SMS_AT_USERNAME` and `BMS_SMS_AT_API_KEY`. The API refuses to start, in every mode, when outside production the URL is
  not the Africa's Talking sandbox host or the account is not `sandbox`, when the mode is `live` outside production, and in
  production when the mode is `sink` or the provider is the fake. Outbound traffic is HTTPS to the provider; nothing is
  opened inbound until the delivery report slice (#215).
- Client extras (issue #210, design): Pesapal `BMS_PESAPAL_PROVIDER`, `BMS_PESAPAL_BASE_URL`, `BMS_PESAPAL_CALLBACK_BASE_URL` (a lender's own account is entered by the lender and
  stored encrypted, so there is no per-lender variable; the consumer key, secret, IPN id, token and tenant id variables of #213 are gone, #233), `BMS_DATA_KEY_PREVIOUS` (retired data keys during a rotation), `BMS_PESAPAL_PLATFORM_CONSUMER_KEY`, `BMS_PESAPAL_PLATFORM_CONSUMER_SECRET`,
  `BMS_PESAPAL_PLATFORM_IPN_ID`, `BMS_PESAPAL_PLATFORM_IPN_TOKEN` (an IPN registration belongs to one credential pair); SMS `BMS_SMS_PROVIDER`, `BMS_SMS_MODE` (`off` default, `sink` on staging),
  `BMS_SMS_ALLOWLIST`, `BMS_SMS_AT_BASE_URL`, `BMS_SMS_AT_USERNAME`, `BMS_SMS_AT_API_KEY`, `BMS_SMS_CALLBACK_TOKEN`, `BMS_SMS_PLATFORM_DAILY_CAP`; AI (built in #220, `docs/runbooks/ai-assistant.md`)
  `BMS_AI_PROVIDER` (`off` by default, `openai_compatible`, or `recording` in dev and test only), `BMS_AI_BASE_URL`, `BMS_AI_API_KEY`, `BMS_AI_MODEL`, `BMS_AI_ENABLED_TENANTS` (the platform allow-list), `BMS_AI_PLATFORM_DAILY_TOKENS`, `BMS_AI_PLATFORM_DAILY_COST_MICRO`, `BMS_AI_PRICE_INPUT_MICRO_PER_MILLION`, `BMS_AI_PRICE_OUTPUT_MICRO_PER_MILLION`, `BMS_AI_PRICE_CURRENCY` and the tuning values of the spec's section 9.1; an incomplete provider setting is off, never a startup failure; Ask BMS (ADR-096, `docs/runbooks/ask-bms.md`) `BMS_HELP_ASK_TENANTS` (empty: off for every business), `BMS_HELP_ASK_GEMINI_API_KEY`, `BMS_HELP_ASK_GROQ_API_KEY`, `BMS_HELP_ASK_DEEPSEEK_API_KEY` (a provider without a key is skipped), their `_MODEL` names, `BMS_HELP_ASK_GEMINI_REASONING` (`minimal` by default, Gemini only), DeepSeek's prices, `BMS_HELP_ASK_DAILY_QUESTIONS` and `BMS_HELP_ASK_DEEPSEEK_MONTHLY_COST_MICRO`; the provider allow-list (one host each for Gemini, Groq and DeepSeek) is the code constant `AskProviders.ALLOWED`, never configuration or an environment value, a base URL off it or anything naming Anthropic or Claude stops startup, DeepSeek runs only with both `BMS_HELP_ASK_DEEPSEEK_PRICE_*` set, and `BMS_HELP_ASK_USER_HOURLY_QUESTIONS` limits each person; reports `BMS_REPORTS_MAX_CONCURRENT_RUNS`,
  `BMS_REPORTS_PDF_ROW_CAP`, `BMS_REPORTS_XLSX_ROW_CAP` (staging 1, 500 and 20,000, because the staging API has about 224 MB of
  heap), `BMS_REPORTS_MAX_FILE_BYTES` (64 MiB; 8 MiB on staging) and `BMS_REPORTS_SPOOL_DIR` (empty: a folder in the temp
  directory). `BMS_REPORTS_XLSX_ROW_CAP` is the row cap of every tabular output (an Excel sheet, a CSV section, a JSON table);
  `BMS_REPORTS_PDF_ROW_CAP` cuts a longer PDF table to that many rows (its total kept) with a pointer to the Excel file (slice D2, issue #217; on the staging heap 500 is measured, 3,000 fills the old generation). All four are plain settings in `deploy/compose*.yml` and `.env.example`, not secrets. Each adapter is off
  when its values are unset. The API gets them and no other container does. Staging uses sandbox values only and the
  startup guards of `docs/specs/client-extras.md` section 8 refuse a live Pesapal URL, SMS `live` mode and any non-sandbox SMS
  provider URL or account in every SMS mode there. The staging
  tunnel must expose the callback host for provider notifications (the sandbox accepts any https name and cannot prove reachability, so the first staging run of #213 does; spec section 3.12). The sandbox also needs the JVM truststore checked for the provider's intermediate certificate. Edge rules, **required before activation and not yet present** (the application's own per address and overall limit holds without them, and the Caddyfile forwards
  `/api/v1/payments/callbacks/*`, `/api/v1/payments/return/*` and `/api/v1/channels/*` on the callback host): a
  Cloudflare rate limit on those paths, the provider's source
  address allow-list for SMS delivery reports where the provider publishes one, and redaction of the path token from the proxy's
  access log.
- Edge rule for the public sign-up (ADR-024, chapter 7 section 7.10), added by the dev lead in the
  Cloudflare dashboard for the platform host: a rate limiting rule on `URI Path starts with
  /api/v1/platform/sign-up/`, counting by IP, 10 requests per minute, action block for 10 minutes.
  It is the first line; the API's own bounds hold without it.
- Client addresses for the per-address limits: on staging, Cloudflare appends the visitor to
  `X-Forwarded-For`, cloudflared and Caddy (`Caddyfile.tunnel`, which trusts the tunnel and reads
  `Cf-Connecting-Ip`) pass it on, and Tomcat's RemoteIpValve takes the right-most untrusted value,
  so a value a client prepends changes nothing. On the production VM the tenant and platform
  records are DNS only (`docs/runbooks/provision-host.md`), so Caddy's peer is the visitor. If the
  production records are ever proxied through Cloudflare, add Cloudflare's published ranges to
  `trusted_proxies` with `client_ip_headers Cf-Connecting-Ip` in `deploy/caddy/Caddyfile` first;
  otherwise every visitor behind one edge shares one bucket.
- Production refuses development switches at startup: `ALLOW_TENANT_HEADER` and `AUTH_MODE=dev`
  are accepted only in the `dev` and `test` profiles, and the servers run the `server` profile.

## 9.9 Background jobs

db-scheduler runs inside the API process with its state in `scheduled_tasks` (ADR-008). No
separate worker container exists yet; `BMS_SCHEDULER_ENABLED` lets one be split out later from
the same image.

Jobs added by the client extras design (issue #210, each with its slice): `core.payments.poll` (built in #213: every 10 seconds, it makes orders the request did not finish, asks the provider about intents whose check is due, books what is paid, and re-asks about a booked payment once a day for 14 days; it replaces the designed `create-order` and `poll-pending` jobs), `core.payments.housekeeping` (nightly 03:40, built in #213: retention of notification rows and counters) and `core.payments.reconcile` (nightly after the end of day, **not built in #213**: slice #224, with the statement upload and the reconciliation report),
`core.reporting.report-runner` (every 15 seconds, one run per tenant, one at a time on staging and two elsewhere) and its nightly file expiry
`core.reporting.report-expiry` (03:30, built in issue #214; `docs/runbooks/reports-operations.md`; files are deleted in batches with no
transaction open). A report run is stamped with the process that holds it and beaten every 10 seconds (`bms.reports.heartbeat`); a run
with no beat for a minute (`bms.reports.heartbeat-stale`; the same limit on the first tick of a new process) is failed as `interrupted`,
and a clean stop interrupts the workers and gives them 15 seconds to close their runs (the api containers set `stop_grace_period: 30s`),
`lending.advisor.daily-brief` (per tenant with the assistant on, idempotent by date), and a higher batch for the SMS
channel of the outbox sender.

Platform jobs (no tenant bound): `core.notifications.outbox-sender` every 30 seconds (at most 50
rows a run), `core.notifications.outbox-purge` nightly at 03:15 (sent rows after 30 days) and
`core.onboarding.application-housekeeping` nightly at 02:45 (expiry after 14 days unconfirmed,
deletion 90 days after closing), all Africa/Kampala (ADR-024).

## 9.10 Backups and restore

`deploy/backup.sh`, run nightly by cron at 01:15 host time (NFR-BAK-01 to NFR-BAK-03):

1. `pg_dump --format custom` as `bms_owner` inside the postgres container;
2. encrypted on the host with AES-256 (`openssl enc -aes-256-cbc -pbkdf2 -iter 200000`) using
   `BACKUP_ENCRYPTION_KEY`, before anything leaves the host (NFR-BAK-02);
3. uploaded with `rclone` (pinned image, configured from environment variables, no config
   file) to `r2:<R2_BACKUP_BUCKET>/<environment>/daily/`, and on the first of the month also to
   `monthly/`;
4. daily copies older than 30 days and monthly copies older than 365 days are deleted
   (NFR-BAK-03), by `backup.sh` until the bucket's lifecycle rules take over; with
   `BACKUP_RETENTION_ON_HOST=false` the host deletes nothing, and R2 bucket lock rules keep every
   object under `daily/` for 30 days and under `monthly/` for 365, so the host's object read and
   write token cannot delete or overwrite a backup inside its window (ADR-058,
   `docs/runbooks/restore-from-backup.md` section 7);
5. `state/last_backup_ok` is written on the host, or `state/last_backup_error` when a run fails.
   `deploy/monitor.sh` (section 9.11) reports a failed run, no good backup in 26 hours, and once an
   hour a newest object under `daily/` older than 26 hours. The external heartbeat of section 9.11
   alerts when the host itself goes quiet; it replaces the workstation watchdog this section used to
   name (ADR-058).
6. `deploy/restore-drill.sh`, run weekly by `bms-restore-drill.timer` (Sunday 03:30 host time),
   restores the newest daily dump into a throwaway PostgreSQL (no network, 384 MB, inside
   `bms.slice` on staging), checks the Flyway history against the live one, reads the row counts and
   proves the application role can read a tenant's rows under row-level security, then removes
   everything, including the decrypted dump. It records one line with its time in seconds against
   the 2 hour RTO in `state/last_restore_drill` or `state/last_restore_drill_error`, appends it to
   `state/restore-drill-history.log` and uploads it to `<environment>/drill/` in R2. `monitor.sh`
   alerts on a failed drill, no good drill in 9 days, or a drill over the RTO. The quarterly timed
   restore onto a fresh VM stays a human drill recorded in `docs/meetings/<date>/`.

The R2 token is scoped to the backup bucket; on a host built by `infra/production/` it is an account token
with object read and write on that bucket only, limited to the VM's addresses if Cloudflare enforces the IP condition, and the lock and
lifecycle rules are created by the same Terraform (section 9.3.1), so the host's token cannot remove them; an account
administrator or the Terraform token still can, and both are guarded as root credentials. The encryption key has an offline copy held by
the dev lead (chapter 8 section 8.7). The restore procedure and the quarterly drill
(NFR-BAK-04) are `docs/runbooks/restore-from-backup.md`.

## 9.11 Monitoring and alerts

Two layers, one channel: the Telegram operator chat the API already uses (`BMS_TELEGRAM_BOT_TOKEN`,
`BMS_TELEGRAM_OPERATOR_CHAT_ID`, from the host env file only), ADR-058, runbook
`docs/runbooks/monitoring-and-alerts.md`.

- **External uptime monitor** (a hosted free tier, Hillary's choice): `https://<platform host>/healthz`
  (staging `https://bms-staging.rincoltech.com/healthz`) and one tenant sign-in page every minute,
  alerting after three failures (NFR-AVL-01, NFR-OBS-04); and a heartbeat that every `monitor.sh`
  run pings, which alerts when the host goes quiet.
- **Host checks**, `deploy/monitor.sh` every five minutes (`bms-monitor.timer`): the site from the
  host; db-scheduler executions with `scheduled_tasks.consecutive_failures > 0`, picked with no
  heartbeat for 15 minutes, or due 30 minutes ago and not picked (this covers the nightly job
  failures of chapter 5, since a tenant job that fails for any tenant fails its execution); disk
  above 70 percent on `/` and `/opt/bms`; a certificate of the platform, callback or tenant host
  expiring within 14 days (the DNS-01 renewal fails silently when the Cloudflare token breaks); and
  the backup and restore drill results of section 9.10. A problem is sent once, repeated every six
  hours and cleared with one message; an unsent message is retried on the next run. Messages carry
  check names, hosts and numbers only.

Not built yet: the worker queue age, the subledger reconciliation difference, and, for the client
extras (spec section 9.2), notifications per hour, unknown references and reconciliation differences
for payments, failed and unknown SMS and the daily cap at 80 percent, outbox failures and the SMS
balance, AI fallbacks, validation failures and cap hits, and report run time and queue age. The API
logs JSON (ECS format) with the request id on every line in the `server` profile. Incidents follow
`docs/runbooks/security-incident.md`; support hours and the on-call rota are
`docs/runbooks/support-and-on-call.md`.

## 9.12 Host layout

```
/opt/bms/
  compose.yml            production: copied from deploy/ on every deploy. Staging: refreshed from
                          releases/<tag>/ only after deploy.sh succeeds (ADR-018 finding M3)
  deploy.sh, backup.sh   as above
  restore-drill.sh, monitor.sh   the weekly drill and the five-minute host checks (section 9.11)
  pull-staging.sh        staging only: the puller; also refreshed only after a successful deploy
  releases/<tag>/        staging only: each release's host files, staged before deploy.sh runs;
                          the previous one to two releases are kept for inspection and rollback
  current                staging only: symlink to the live releases/<tag>/, updated only on success
  cosign.pub             the signing public key (ADR-018 finding H2): committed at deploy/cosign.pub,
                          provisioned once by hand, never replaced by a release
  systemd/               the unit files, for reference; installed by hand, or by cloud-init on a host
                          built by infra/production (section 9.3.1)
  postgres/initdb/       copied from deploy/ on every deploy. Production: the restore drill reads its init scripts here.
                          Staging: the drill reads releases/<tag>/postgres/initdb (the staged release it matches)
  sql/                   onboarding scripts (docs/runbooks/onboard-tenant.md)
  .env                   written once by hand (docs/runbooks/provision-host.md); mode 600
  state/current_tag, current_created   the live release and when it was built (for the downgrade guard)
  state/history.log      every deploy, rollback and failure with its time
  state/last_backup_ok   time of the last good backup; state/last_backup_error the last failed run
  state/last_restore_drill, last_restore_drill_error, restore-drill-history.log   the drill results
  state/alerts/<check>   monitor.sh: when a problem was first seen and last sent
  state/deploy.lock      one deploy at a time
  state/last_failed_tag  staging only: a release the puller will not retry
  state/pull.lock        staging only: one puller run at a time
  state/allow_downgrade  staging only: presence lets deploy.sh accept a release built before the live one
  backups/               transient; each encrypted dump is deleted after upload
```

## 9.13 Staging: a shared ARM64 host behind a Cloudflare Tunnel

Staging runs on an ARM64 host that already exists at a Rincol home site: a Raspberry Pi 4 with
8 GB, Debian 13 and SD card storage, shared with an unrelated edge workload and a capped CI runner
(ADR-018). BMS may use about 1.6 GB of it, hard-capped (900 MB until decision D37, 24 Oct 2026). The site has no inbound ports.

```
 Visitor --HTTPS--> Cloudflare edge (TLS for *.rincoltech.com; proxied CNAMEs:
                    bms-staging, <slug>-bms-staging  ->  <tunnel id>.cfargotunnel.com)
                                  ^
                                  | outbound-only tunnel, opened by cloudflared
+------------- ARM64 host, Docker Compose project "bms", all in bms.slice (1.6 GB) ----------+
|  cloudflared  --http-->  proxy :8080 (Caddyfile.tunnel; security headers, 6 MB bodies)      |
|                             |  /api/*, /healthz, /readyz, /version  ->  api:8080            |
|                             |  everything else                       ->  web:8080            |
|  api (bms_app)   postgres (volume pgdata)   web (volume web_assets)   migrate (one-shot)    |
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
  | `postgres` | 176 MB | `shared_buffers=48MB`, `effective_cache_size=128MB`, `work_mem=2MB`, `max_connections=20`, longer checkpoints to spare the SD card. Measured at 25 times the staging data inside these limits (ADR-028); recommended next: two autovacuum workers of 16 MB each (`deploy/postgres/recommended/pi-staging.conf`), not yet applied. |
  | `cloudflared` | 48 MB | |
  | `proxy`, `web` | 32 MB each | Caddy |
  | `migrate` | 160 MB | Only while a deploy runs, next to the old API |
  | second `api`, `web` | 448 MB, 32 MB | Only during a switch, beside the old ones until they are healthy (ADR-083). The second api starts only while its own limit plus 64 MB (512 MB) is free below the slice's `MemoryHigh` and on the host, so its start-up peak can never bring the slice's OOM killer onto the old api or PostgreSQL (reclaim, a slowdown, stays possible); otherwise the api restarts in place, with the old gap. Measured 24 Oct 2026: about 790 MB in use, so about 710 MB free below the 1500 MB `MemoryHigh`: staging overlaps on every deploy. `BMS_ROLL_MIN_FREE_MB` can replace the limit with a measured start-up peak |
  | **Total** | 736 MB steady, 896 MB while migrations run, 1216 MB by limits during an overlap | `bms.slice`: `MemoryMax=1600M`, `MemoryHigh=1500M` (decision D37), no swap, `CPUQuota=250%` |

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
- Choose the production provider (Hetzner Cloud or AWS), its location or region, the server size and
  the R2 location, then apply `infra/production/` (ADR-063, `docs/runbooks/terraform-production.md`).
- Registry retention for old `sha-*` images.
- Off-host log shipping and an error tracker (NFR-OBS-03).
