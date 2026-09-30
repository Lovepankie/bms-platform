# ADR-018: Staging on a shared ARM64 host behind a Cloudflare Tunnel, pull-based deploy, hyphenated hosts under the Rincol zone

## Status

Accepted (2026-09-29), issue #16. Amended (2026-09-30) after an adversarial review of the pull
request that implemented it (`docs/reviews/pr-21-adversarial-review.md`), before merge: the runner
split, image signing, private packages, the shared zone's effect on cookies, and several
docs-contradicting-code passages below are corrected in place rather than left for a later ADR,
since nothing here has shipped yet. Supersedes in part:

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
  and `cloudflared`, on two networks: `edge` (cloudflared, proxy) and `internal` (proxy, api, web,
  postgres), so a wrong tunnel ingress rule or a leaked onboarding token cannot reach PostgreSQL or
  the API's management port (amendment, finding M6; the original text described one flat network).
  No service publishes a port. `cloudflared` holds the tunnel with a connector token from the host
  `.env` and delivers plain HTTP to one internal origin, `proxy`, which is the proxy image started
  with `Caddyfile.tunnel`: it serves the PWA and proxies `/api`, `/healthz`, `/readyz` and
  `/version` to the API, passing the original `Host` through. TLS ends at Cloudflare's edge with
  its free `*.rincoltech.com` certificate.
  **Amendment (finding M1):** the original text stopped at "passing the original `Host` through"
  as if that were the whole story; it was not. Caddy's `trusted_proxies static private_ranges`
  also kept whatever `X-Forwarded-Host` a client sent instead of setting it from `Host`, and Spring
  Boot's `native` forwarded-header strategy installs Tomcat's `RemoteIpValve`, which by default
  reads exactly that header to override `getServerName()`. Together, a request could carry a real
  tenant `Host` and a forged `X-Forwarded-Host` naming the platform host or another tenant, and the
  API would resolve the forged one. `Caddyfile.tunnel` now overwrites `X-Forwarded-Host` from the
  resolved `Host` in both its proxy blocks, and `server.tomcat.remoteip.host-header` is pointed at
  a header nothing sends, so the API's tenant and platform resolution can only ever come from
  `Host`, on staging and everywhere else `native` is used.
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
  live nor the last failed one, it resolves the api, web and proxy images to their digests and
  verifies each one's cosign signature (below) before touching anything else. Only once every
  signature verifies does it copy that release's host files out of its proxy image
  (`/usr/share/bms-deploy`, built from the repository's `deploy/` at the same commit) into
  `releases/<tag>/`, and run `releases/<tag>/deploy.sh <sha-tag>`: migrations before the swap, the
  readiness gate and automatic rollback, exactly as before. Only a successful `deploy.sh` moves the
  `current` symlink and the live top-level copies of `compose.yml`, `deploy.sh`, `backup.sh` and
  `pull-staging.sh` to this release; a failed one leaves the previous release live, the puller
  un-replaced, and `releases/<tag>/` in place for inspection (amendment, finding M3: the original
  design installed a release's files, including the puller's own, before knowing whether it
  worked, so a broken release could leave `bms-pull.timer` running broken code with no path to a
  fix). A failed tag is recorded in `state/last_failed_tag` and not retried until someone removes
  it or deploys by hand. After a success it removes release images and `releases/` directories
  other than the live and previous ones, since the SD card is small.
- **Amendment (finding H2).** The original design trusted the registry outright: the puller's only
  check was that the image's labels were the right shape, so anyone who could write to the three
  GHCR packages (a compromised `deploy.yml` job, a maintainer token, an org admin) could run
  arbitrary code as the root-equivalent `bms` user, and the pointer was a mutable tag with no
  provenance check. `deploy.yml`'s `build` job now signs each pushed image by digest with cosign,
  using a private key held only in the repository secrets `COSIGN_PRIVATE_KEY` and
  `COSIGN_PASSWORD`, with `--tlog-upload=false` (this repository is private; a public Rekor entry
  would publish its name and commits). The staging puller and `deploy.sh` verify the api, web and
  proxy signatures against `deploy/cosign.pub`, provisioned once by hand and never sourced from the
  release itself, and use the resolved digest (`image@sha256:...`), never a tag, for `docker
  create` and for every container the deploy switches. A signature that does not verify stops the
  deploy before anything is extracted or run.
- **Amendment (finding M5).** The original design made the three GHCR packages public so the host
  could pull anonymously. On reflection this is the wrong default for a private repository: the api
  image is the whole application jar, the web image the whole bundle, and the proxy image carries
  the deploy scripts and SQL, none of which should be published just to save one credential. The
  packages stay **private**; the `bms` user logs in to GHCR once with a token limited to
  `read:packages`, which Docker stores for the puller. Nothing reaches into the host.
- **Amendment (finding L1).** The CI job that moves the `staging` pointer now fails the workflow
  on an inspect or parse error instead of silently treating it as "no existing pointer" and moving
  regardless. `deploy.sh` also refuses, on the host, to deploy a release built before the one
  already live (compared by the `org.opencontainers.image.created` label) unless an operator has
  created `state/allow_downgrade`, so a stale or out-of-order pointer cannot quietly roll the host
  backward.
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
not registered (see the next paragraph), and the build publishes one manifest list per image for
`linux/amd64` and `linux/arm64`. The production retag copies the whole list.

**Read-only CI runs on the host's walled runner; `deploy.yml` does not (amended, finding H1).** The
repository is private and the organisation has no paid Actions minutes, so `ci.yml`, `dash-guard`,
`adr-citation-guard`, `architecture-model`, `linked-issue-guard` and `estimate-guard` run on a
self-hosted runner. It is the runner that already lives on the staging host for another repository
of the same organisation. It is registered once at organisation level in a runner group restricted
to exactly two repositories and closed to public ones, and every job targets the `hillary-pi`
label. One runner process means jobs from the two repositories queue and never run side by side.
This revisits the deploy option rejected above ("a self-hosted runner on the host"). What makes it
acceptable for these jobs is that the runner is walled from staging, not that the risk vanishes:
- it runs as its own user with no sudo, outside the docker group, with its own rootless Docker
  daemon, so it cannot reach the root daemon that runs staging or its volumes;
- a cgroup caps it at 5.5 GB of memory and three CPUs, so a build cannot starve staging;
- an owner-match firewall rule blocks it from the home LAN, the overlay network and link-local
  ranges;
- these jobs declare `contents: read` only, never `packages: write` and never a deploy secret;
  a private repository also accepts pull requests only from organisation members.

The original version of this ADR argued that this same wall was also the boundary around
`deploy.yml`, since "pull request jobs get read access only" and package write "exists only in
`deploy.yml`, which runs only on pushes to `main`". The review that amended this ADR
(`docs/reviews/pr-21-adversarial-review.md`, finding H1) showed that argument holds per token, not
per machine: the runner is one long-lived process shared by every job of both repositories, and
nothing made it ephemeral between them. A pull request that can change what CI executes (a
workflow file, a build plugin, an npm `postinstall`) can implant something that outlives its own
job and reads the next `deploy.yml` run's `packages: write` token, or later a production deploy's
SSH key, straight off the runner. The wall that actually matters, "the machine that runs unreviewed
pull request code" versus "the machine that holds a deploy credential", was missing.

**Amendment: `deploy.yml` runs on `ubuntu-latest`.** `meta`, `build`, `staging-pointer`, `promote`
and `deploy-production` all run on GitHub's hosted runner: a fresh virtual machine per job,
destroyed afterward, so no job can leave anything for the next one to find. `docker/setup-qemu-action`
registers QEMU there for the `linux/arm64` leg of the multi-arch build (layer copies only, no
runtime stage ever executes a `RUN`), a privilege the walled Pi runner was never given and still
is not: it keeps running only the read-only jobs above. Staging is still deployed by the host's own
timer (pull), never by any runner.

## Consequences

**Better:**

- Read-only CI still costs nothing: those jobs use no Actions minutes, and the repository stays
  private. `deploy.yml`, which now runs on `ubuntu-latest`, does consume minutes (amendment,
  finding H1); see Worse below.
- No job that holds `packages: write` or a deploy secret runs on the same machine as unreviewed
  pull request code, and no persistent process carries state from one `deploy.yml` run to the next.
- Every image is signed and the packages are private, so writing to the registry is no longer
  enough by itself to run code on the staging host (amendment, findings H2 and M5).
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
- `deploy.yml` on `ubuntu-latest` (amendment, finding H1) uses GitHub Actions minutes, unlike the
  self-hosted runner it used to run on; only pushes to `main` and version tags run it, which is a
  handful of runs per day, not every pull request.
- Every tenant host, both platform consoles, staging and production, and the company's own
  `rincoltech.com` site are now one registrable domain (amendment, finding M2): `SameSite=Strict`
  alone no longer keeps one from setting a cookie that reaches another. The refresh cookies carry
  the `__Host-` prefix so a sibling host cannot set a same-named one that shadows them, but the
  zone is still shared for everything else a cookie or a same-site policy might assume; a separate
  zone before production launches would remove this class of question entirely.

**Watch for:**

- Read-only CI still executes pull request code on the same machine as staging (`ci.yml` and the
  documentation guards); the walls in the runner-split section above are the control, not
  `deploy.yml`'s absence, since that workflow never ran review-independent code to begin with. Any
  change to those walls (sudo, docker group, firewall, memory cap) reopens the risk this amendment
  addressed. CI is also slower on a Raspberry Pi, and jobs wait while the other repository's jobs
  run.
- A runtime-stage `RUN` step would break the amd64 build (no emulation on the runner that builds
  it, self-hosted or hosted). Keep runtime stages to `COPY` and metadata.
- The signing key: `COSIGN_PRIVATE_KEY` and `COSIGN_PASSWORD` are the new root of trust for
  everything that reaches the staging host. Losing them means generating a new pair, replacing
  `deploy/cosign.pub`, and re-provisioning the staging host's copy by hand; leaking them is
  equivalent to leaking `packages: write` was before this amendment.
- Memory: the 900 MB slice will OOM-kill a container before it touches the other workloads. Watch
  `systemctl status bms.slice` and `docker stats` after releases that add dependencies or jobs.
- SD card wear and space: container logs are capped, old release images and `releases/` directories
  are removed, PostgreSQL checkpoints less often. Keep the backups in R2 current; an SD card can
  fail without warning.
- The pointer and the labels: a build that skips the labels leaves the puller refusing to deploy
  (it logs why). Keep `org.opencontainers.image.version`, `revision` and `created` in `deploy.yml`.
- A failed release stays failed on staging until someone looks: check `state/last_failed_tag`.
- Production: when its VM is provisioned, set `BMS_TENANT_HOST_PATTERN={slug}-bms.rincoltech.com`,
  `BMS_PLATFORM_HOST=bms.rincoltech.com` and `BMS_DNS_ZONE=rincoltech.com`; its Cloudflare token then
  covers the company zone's DNS, so scope it to `Zone.DNS:Edit` on that zone only and keep it on
  the production host alone.

## Related ADRs

- ADR-006: the delivery pipeline this changes for staging.
- ADR-009: the PWA, which now reads its hosts at run time.
- ADR-016: the platform API, now served only on the configured platform host.
