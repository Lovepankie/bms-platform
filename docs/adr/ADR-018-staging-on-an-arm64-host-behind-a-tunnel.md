# ADR-018: Staging on a shared ARM64 host behind a Cloudflare Tunnel, pull-based deploy, hyphenated hosts under the Rincol zone

## Status

Accepted (2026-09-29), issue #16. Supersedes in part:

- **ADR-006:** the staging half of the delivery pipeline. Staging no longer runs on a cloud VM
  reached over SSH from GitHub Actions: CI moves a `staging` pointer tag and the staging host pulls
  (section "Deploy to staging" below). The SSH `deploy-staging` job and the `STAGING_*` secrets are
  removed. Images are now built for `linux/amd64` and `linux/arm64`. Everything ADR-006 decides
  for production (tag, retag without rebuild, approval, SSH deploy, `deploy.sh`) is unchanged.
- **ADR-016 and chapter 7 section 7.2:** "the platform API is served on a host that names no
  tenant (`app.<base domain>`)" becomes "the platform API is served only on the configured
  platform host".
- **The domain assumption** of chapter 2 section 2.4 and chapter 9 section 9.5 (a product domain of
  its own, tenants at `<slug>.<base domain>`, the console at `app.<base domain>`, staging under
  `staging.<product domain>`): BMS is a Rincol product served under the `rincoltech.com` zone.

## Context

Staging must exist now, for the pilot tenant's fabricated walkthroughs and for every merge to be
seen running, but a second cloud VM is not in the budget before the pilot. An ARM64 host already
runs at a Rincol home site: a Raspberry Pi 4 (8 GB, Debian 13, SD card storage) shared with an
unrelated edge workload and a capped CI runner. BMS staging may use about 900 MB of its memory,
as a hard cap. The site has no inbound ports and no fixed address; the host reaches the internet
only outbound. Production still gets a dedicated VM later.

Three things in the existing design do not fit that host:

1. **Hosts.** Chapter 7 put tenants at `<slug>.<base domain>` and the console at
   `app.<base domain>`, on a product domain of its own with a wildcard certificate issued by Caddy
   through DNS-01. The company zone is `rincoltech.com`, and the free Cloudflare edge certificate
   covers `rincoltech.com` and `*.rincoltech.com` only, one label deep. `demo.bms-staging.rincoltech.com`
   is two labels deep and would need a paid advanced certificate.
2. **Architecture.** The images were built for `linux/amd64` only.
3. **Reachability.** ADR-006 deploys by SSH from GitHub Actions to the host. GitHub cannot reach a
   host behind NAT with no inbound ports, and a deploy key into a home network is a liability.

Options considered for exposure: port forwarding with dynamic DNS (opens the home network, needs
a certificate on the host, breaks on address changes); a VPN or overlay network (members and staff
would need a client); a Cloudflare Tunnel (outbound only, TLS at the edge, no certificate on the
host).

Options considered for deploy: a self-hosted GitHub runner on the host (a runner that executes
workflow code next to staging data and the unrelated workloads, with registry write tokens in
reach); SSH through the tunnel (Cloudflare Access for SSH, a deploy key again, more moving parts);
pull by the host (the host asks the registry what staging should run).

Options considered for multi-arch: build everything under QEMU emulation (Maven and npm under
emulation are many times slower and flaky); a native ARM64 runner (not free on the current plan);
build the architecture-independent parts natively and only assemble the runtime layer per target.

## Decision

**Hosts are single labels under the Rincol zone, with hyphens.**

| Environment | Platform console | Tenant | Provider callbacks |
|---|---|---|---|
| Staging | `bms-staging.rincoltech.com` | `{slug}-bms-staging.rincoltech.com`, for example `demo-bms-staging.rincoltech.com` | `bms-staging-callbacks.rincoltech.com` (when chapter 12 lands) |
| Production (later) | `bms.rincoltech.com` | `{slug}-bms.rincoltech.com` | `bms-callbacks.rincoltech.com` |

- The API takes `BMS_TENANT_HOST_PATTERN` and `BMS_PLATFORM_HOST` and validates them at startup:
  exactly one `{slug}`, in the leftmost label, so the host is one label under the zone; valid DNS
  labels; a platform host that is a host name and does not match the pattern. Invalid or missing
  values stop the application.
- A request resolves a tenant only when its host is exactly the pattern's host for a valid slug,
  compared case-insensitively (DNS names are). A trailing dot, extra labels on either side, a
  different zone or a host that merely contains the pattern carry no slug and get the existing 404
  `unknown_tenant`. A slug is also refused when its label would exceed 63 characters under the
  pattern (51 characters of slug under `-bms-staging`).
- Platform routes are served only on the platform host (exact, case-insensitive), and still answer
  404 anywhere else.
- One-time links (invitations, the first tenant admin's link in the platform response) are built
  from the pattern: `https://<host for the slug>`. `BMS_LINK_ORIGIN` is removed; the `dev` profile
  sets `link-scheme: http` and `link-port: 8000` for `make dev`.
- The PWA classifies its host with the same rules. It reads the hosts at run time from
  `/app-config.json`, which the web container serves from the same two variables, so one image
  still serves staging and production (ADR-006's build once holds).
- Local development keeps `{slug}.localhost` with `localhost` as the platform host, and the dev
  `X-Tenant` header.

**Staging runs on the shared ARM64 host behind a Cloudflare Tunnel.**

- `deploy/compose.pi-staging.yml` (installed on the host as `/opt/bms/compose.yml`, so
  `deploy.sh`, `backup.sh` and the runbooks are unchanged) runs `postgres`, `api`, `web`, `proxy`
  and `cloudflared`. No service publishes a port. `cloudflared` holds the tunnel with a connector
  token from the host `.env` and delivers plain HTTP to one internal origin, `proxy`, which is the
  proxy image started with `Caddyfile.tunnel`: it serves the PWA and proxies `/api`, `/healthz`,
  `/readyz` and `/version` to the API, passing the original `Host` through. TLS ends at
  Cloudflare's edge with its free `*.rincoltech.com` certificate.
- Memory: container limits of 176 MB (PostgreSQL, 48 MB shared buffers), 448 MB (API: serial GC,
  C1 only, 50 percent heap, capped metaspace and code cache, 24 request threads), 32 MB (web),
  32 MB (proxy) and 48 MB (cloudflared), 736 MB in total, plus 160 MB for the one-shot migrate
  container during a deploy. Every container runs in the systemd slice `bms.slice`, whose
  `MemoryMax=900M` is the hard cap for the whole stack whatever the per-container numbers say.
- Each tenant needs a proxied DNS record `<slug>-bms-staging` (a CNAME to the tunnel) and a public
  hostname entry on the tunnel pointing at `http://proxy:8080`, both created through the Cloudflare
  API (`docs/runbooks/onboard-tenant.md`). Cloudflare Tunnel public hostnames cannot express the
  partial wildcard `*-bms-staging`, so tenants are added one by one.
- Backups are unchanged: `backup.sh` nightly to Cloudflare R2 under the `staging/` prefix.
- The production VM path keeps its public Caddy (`deploy/caddy/Caddyfile`) with a DNS-01 wildcard
  certificate, now for `*.<BMS_DNS_ZONE>`; its apex redirect is removed, since the zone apex is the
  company's site.

**Deploy to staging is pulled by the host.**

- After a green build of `main`, `deploy.yml` pushes the immutable `sha-<short>` images (unchanged)
  and then moves the tag `staging` of `api`, `web` and `proxy` to the same manifests with
  `docker buildx imagetools create`. It never moves the pointer backwards: when the pointer already
  names a newer commit, the older run leaves it.
- Every image carries the labels `org.opencontainers.image.version` (the `sha-<short>` tag) and
  `org.opencontainers.image.revision` (the full commit). The pointer is resolved by reading those
  labels from the pulled `api:staging` image; no separate manifest file is kept.
- `bms-pull.timer` runs `deploy/pull-staging.sh` every two minutes as the dedicated `bms` user.
  It pulls `api:staging` (a manifest check when nothing moved), and when the named tag is neither
  live nor the last failed one, it fetches the repository's `deploy/` directory at the labelled
  commit (the public tarball over HTTPS), installs it, and runs the existing `deploy.sh <sha-tag>`:
  migrations before the swap, the readiness gate and automatic rollback, exactly as before. A
  failed tag is recorded in `state/last_failed_tag` and not retried until someone removes it or
  deploys by hand. After a success it removes release images other than the live and previous
  ones, since the SD card is small.
- The GHCR packages are public (the repository is public and images hold no secrets), so the host
  pulls anonymously and holds no registry credential, no deploy key and no GitHub token. Nothing
  reaches into the host.
- The `bms` user is a member of the `docker` group, with no password, no SSH key and no login
  shell. Rootless Docker was not chosen: `cgroup_parent` into a system slice and memory limits
  need the system daemon's cgroup delegation, which rootless mode on Debian does not give without
  extra setup, and the stack must share the host daemon's image store with nothing else anyway.
  Docker group membership is equivalent to root on that host; the account is used only by the
  timer.
- The SSH-based staging job is removed rather than kept: with no inbound access it could never
  run, and two staging mechanisms would compete for `state/deploy.lock`.

**Images are multi-arch without emulated compilation.** Each Dockerfile compiles in a stage pinned
to `$BUILDPLATFORM`: Maven builds the jar and npm builds the bundle natively on the runner (their
output does not depend on the architecture), and the proxy's Caddy is cross-compiled by Go for
`TARGETARCH`. The per-target runtime stages contain no `RUN` step (users and ownership are set
with numeric ids and `COPY --chown`), so nothing executes under emulation. QEMU is still
registered in the build job so a future runtime `RUN` step builds, and the build publishes one
manifest list per image for `linux/amd64` and `linux/arm64`. The production retag copies the whole
list.

## Consequences

**Better:**

- Staging exists now at no hosting cost, on hardware already running, with no inbound port and no
  credential that lets anything outside reach the host.
- One free edge certificate covers every staging and production host, including tenants created
  later; no certificate lives on the staging host.
- Host names are configuration, validated at startup, so a staging image can never quietly serve
  production hosts or the other way round, and look-alike hosts are refused by exact matching.
- The deploy logic that matters (migrate before swap, readiness gate, rollback) is the same script
  for staging and production.
- ARM64 images come at nearly the cost of the amd64 ones, since only file copies run per target.

**Worse:**

- Staging is a single small host on a home connection and power supply: slower (the JVM starts in
  about a minute, C1 only) and less available than production will be. Its timings do not predict
  production performance.
- A deploy reaches staging up to two minutes (plus the pull) after the pointer moves, not at the
  end of the workflow; the Actions run no longer shows staging's result. `state/history.log` on
  the host does.
- Each new staging tenant needs a DNS record and a tunnel public hostname, one API call each.
- The `docker` group makes the `bms` user root-equivalent on a shared host.
- Staging depends on Cloudflare for both DNS and ingress.

**Watch for:**

- Memory: the 900 MB slice will OOM-kill a container before it touches the other workloads. Watch
  `systemctl status bms.slice` and `docker stats` after releases that add dependencies or jobs.
- SD card wear and space: container logs are capped, old release images are removed, PostgreSQL
  checkpoints less often. Keep the backups in R2 current; an SD card can fail without warning.
- The pointer and the labels: a build that skips the labels leaves the puller refusing to deploy
  (it logs why). Keep `org.opencontainers.image.version` and `revision` in `deploy.yml`.
- A failed release stays failed on staging until someone looks: check `state/last_failed_tag`.
- Production: when its VM is provisioned, set `BMS_TENANT_HOST_PATTERN={slug}-bms.rincoltech.com`,
  `BMS_PLATFORM_HOST=bms.rincoltech.com` and `BMS_DNS_ZONE=rincoltech.com`; its Cloudflare token then
  covers the company zone's DNS, so scope it to `Zone.DNS:Edit` on that zone only and keep it on
  the production host alone.

## Related ADRs

- ADR-006: the delivery pipeline this changes for staging.
- ADR-009: the PWA, which now reads its hosts at run time.
- ADR-016: the platform API, now served only on the configured platform host.
