# Adversarial review of PR #21 (feat/16-pi-staging)

Reviewed head: `724653a` (diff `origin/main...feat/16-pi-staging`, 69 files). Independent,
read-only review; I did not write this code. Scope: host resolution, the pull-based staging
deploy, `compose.pi-staging.yml`, CI on the self-hosted runner, ADR-018 and the runbooks and SDD
chapters it touches.

## Verdict: REQUEST CHANGES

| Severity | Count |
|---|---|
| HIGH | 2 |
| MEDIUM | 6 |
| LOW | 9 |

The host parser itself is solid and the puller validates the tag it passes to `deploy.sh`. The
blocking problems are about trust boundaries: a persistent self-hosted runner that runs
unreviewed pull request code also holds the registry write token, and the registry content is
executed on the staging host by a root-equivalent user with no provenance check. Together they
turn "can open a pull request" into "root on the shared host" (and later into production).

---

## HIGH

### H1. PR code on a persistent runner can steal the `packages: write` token and so reach root on the host

Files: `.github/workflows/*.yml` (every job now `runs-on: [self-hosted, hillary-pi]`),
`.github/workflows/deploy.yml` (`build`, `staging-pointer`, `promote`, `deploy-production`),
ADR-018 "CI and image builds run on the host's walled runner".

ADR-018 argues that PR jobs are safe because their `GITHUB_TOKEN` is read only and package write
exists only in `deploy.yml` on `main`. That is true per token, but not per machine. The runner is
one long-lived process under one Unix user with one rootless Docker daemon, shared by PR jobs of
this repository, by `deploy.yml` on `main`, by the production deploy job, and by a second
repository. Nothing in the PR makes the runner ephemeral or resets its state between jobs.

Failure scenario (any organisation member who can open a PR, no review or merge needed):

1. A PR changes `ci.yml` (or any script CI runs, for example a Maven plugin or an npm
   `postinstall`) to do one of: `docker run -d --restart always -v "$HOME:/h" ...` in the
   runner's rootless daemon (a container outlives the job and the runner's orphan-process
   cleanup); write `ACTIONS_RUNNER_HOOK_JOB_STARTED` into the runner install's `.env`; replace a
   binary under `_work/_tool`; or add a `~/.gitconfig` hook or credential helper. All of these are
   writable by the runner user.
2. The next push to `main` runs `deploy.yml` on the same runner. `docker/login-action` writes the
   job's `GITHUB_TOKEN` (with `packages: write`) to `~/.docker/config.json`; the implant reads it
   and exfiltrates it (the firewall only blocks LAN, overlay and link-local; internet egress is
   open, it has to be for GHCR).
3. With that token, during the job's lifetime, the attacker pushes `bms-platform-proxy:sha-XXXXXXX`
   whose `/usr/share/bms-deploy/compose.pi-staging.yml` mounts `/` with `privileged: true`, and an
   `api:staging` whose labels name that tag and a matching 40-hex revision (see H2: the puller only
   checks the format).
4. Within two minutes `bms-pull.timer` installs and runs it as `bms`, a member of the root
   Docker daemon's `docker` group. Result: root on the shared Raspberry Pi, including the
   unrelated edge workload, the runner itself, the staging database and `.env` (tunnel token, data
   key, JWK, R2 keys).

The same implant also sees `PROD_SSH_KEY` when `deploy-production` runs on this runner (the
environment's required reviewer approves a legitimate tag; the secret is then exposed to a
compromised machine). It is also a cross-repository path: the other repository's PRs reach this
repository's deploy token, and the reverse.

Required change (any one of these closes the chain; the first two together are best):

- Separate the trust levels onto different runners: PR and branch CI on one runner user, and
  `deploy.yml` on a different runner registered under a different Unix user with its own rootless
  daemon, in a runner group restricted to `deploy.yml` on `refs/heads/main` and `refs/tags/v*`
  ("selected workflows" pinned to the ref).
- Make runners ephemeral (`--ephemeral`, re-registered per job by a wrapper that wipes the runner
  home, `_work`, the tool cache and the rootless Docker data root) so no job can influence the
  next.
- Do not run `deploy-production` on this host at all; its SSH key must never be on a machine that
  executes PR code.
- Amend ADR-018's "walls" paragraph: the stated control (read-only PR tokens) does not address
  cross-job persistence, and the wall that matters, runner versus registry write, is missing.

### H2. The puller executes registry content as a root-equivalent user with no provenance check

Files: `deploy/pull-staging.sh:44-70`, `deploy/caddy/Dockerfile` (host files in the proxy image),
`deploy/systemd/bms-pull.service`, ADR-018 "Deploy to staging is pulled by the host".

`pull-staging.sh` copies `compose.yml`, `deploy.sh`, `backup.sh`, `pull-staging.sh` itself,
`postgres/initdb`, `sql/` and `systemd/` out of whatever image the `staging` pointer names, then
runs `./deploy.sh`. The only check is on label format:

```bash
if [[ ! "$revision" =~ ^[0-9a-f]{40}$ || "$tag" != "sha-${revision:0:7}" ]]; then
```

This prevents shell or path injection into `deploy.sh` (good, see "What holds up" below), but it
authenticates nothing: any 40 hex characters pass. The trust root is therefore "anyone who can
write to the three GHCR packages", and the result of writing is code execution as `bms`, which
ADR-018 itself records as root-equivalent. Holders of that write include every `deploy.yml` job
token (H1), any maintainer PAT with `write:packages`, and any org admin. There is no image
signature or attestation, and the pointer is a mutable tag.

The self-update also means one bad release replaces the puller that would deploy the fix (see M3).

Required change: sign the images in `deploy.yml` (for example `cosign sign` keyless with the
workflow's OIDC identity, or `actions/attest-build-provenance`) and have the puller verify, before
`docker create`, that the proxy and api manifests at the resolved digest were signed by
`.github/workflows/deploy.yml@refs/heads/main` of this repository. Resolve the tag to a digest
once and use `image@sha256:...` for `docker create` and for `deploy.sh`, so the verified bytes are
the ones executed. Until then, ADR-018 should state plainly that GHCR write equals root on the
staging host.

---

## MEDIUM

### M1. `X-Forwarded-Host` from the internet overrides the Host the API resolves on staging

Files: `backend/src/main/resources/application.yml:38` (`forward-headers-strategy: native`),
`deploy/caddy/Caddyfile.tunnel:10-14`, `TenantResolutionFilter.slugFrom`,
`AuthenticationFilter.java:81`, ADR-018 ("passing the original `Host` through").

Both the tenant resolution and the platform-host gate use `request.getServerName()`. With the
`native` strategy Spring Boot installs Tomcat's `RemoteIpValve`, whose `host-header` defaults to
`X-Forwarded-Host`, and it trusts requests from private addresses (the Docker network). Caddy's
`trusted_proxies static private_ranges` makes Caddy keep an incoming `X-Forwarded-Host` from
cloudflared instead of setting it from the Host, and Cloudflare forwards a client-supplied
`X-Forwarded-Host` unchanged. So on staging:

```
GET /api/v1/platform/tenants HTTP/1.1
Host: demo-bms-staging.rincoltech.com
X-Forwarded-Host: bms-staging.rincoltech.com
```

is treated by the API as a request to the platform host, and `X-Forwarded-Host:
other-bms-staging.rincoltech.com` resolves another tenant. Browsers cannot set this header cross
origin without a preflight, and tokens still bind the principal, so this is not a direct tenant
break; but it falsifies the ADR's premise that the Host decides and nothing downstream can choose
another, it feeds the host recorded by `flow.login(..., http.getServerName(), ...)`, and any future
host-derived link or cache would inherit it. The production VM is not affected in the same way
because its Caddy sees public clients, which are not trusted proxies.

I did not run it end to end (no GHCR or tunnel access from the review sandbox); the chain rests on
the documented defaults of Spring Boot's `server.tomcat.remoteip.host-header` and Caddy's trusted
proxy handling. An integration test would settle it.

Fix: in `Caddyfile.tunnel` set `header_up X-Forwarded-Host {host}` (or strip it) in both
`reverse_proxy` blocks, and set `server.tomcat.remoteip.host-header` to a header nothing sends (or
switch to an explicit strategy) so the API reads only `Host`. Add a test that a request with a
foreign `X-Forwarded-Host` resolves the tenant of `Host`, and one that the platform gate ignores it.

### M2. Staging, production and the company site now share one registrable domain; cookies are not host-locked

Files: ADR-018 host table, `AuthApi.cookie` (`bms_rt`, `bms_prt`, `SameSite=Strict`, no prefix).

Every tenant, both platform consoles, staging and production, and the company's own
`rincoltech.com` site are same-site. `SameSite=Strict` therefore no longer separates staging from
production or BMS from anything else on the zone. Any host on `rincoltech.com` (the marketing
site, a future tool, or the staging stack, which runs every `main` commit on a home Pi) can set
`bms_rt=...; Domain=rincoltech.com; Path=/api/v1/auth` and have it sent to every production tenant
(cookie tossing, session fixation or forced sign-out). The previous design had a product domain of
its own.

Fix: rename the refresh cookies with the `__Host-` prefix (requires `Path=/`, so move the path
scoping into the server check), which makes Domain cookies from siblings unable to shadow them;
and record in ADR-018 that the zone is shared and what that costs. Consider a separate zone for
staging before production launches.

### M3. Host files are installed before the deploy and never rolled back

File: `deploy/pull-staging.sh:59-80`.

The new `compose.yml`, `deploy.sh`, `backup.sh`, `pull-staging.sh`, `postgres/`, `sql/` and
`systemd/` are written before `deploy.sh` runs. When the release fails (health gate, migration),
`deploy.sh` rolls back the images but the host keeps the failed release's files:

- the automatic rollback inside `deploy.sh` runs the previous images against the new compose
  file (a renamed env var or changed service breaks the rollback itself);
- a failed release's `pull-staging.sh` stays installed and runs every two minutes; if it is the
  broken part, auto-deploy stops for good and needs hands on the host;
- `backup.sh` from a failed release runs at 01:15;
- `cp -R "$src/$dir/." "$dir/"` never deletes files removed upstream, so a retired
  `postgres/initdb` or `sql` script stays forever.

Fix: unpack each release into `releases/<tag>/` and switch a `current` symlink only after
`deploy.sh` succeeds (and back on failure); keep the puller itself out of the self-update or
update it only after success.

### M4. `deploy.sh` now brings up every service, on production too

File: `deploy/deploy.sh:57-60`.

`switch_to` changed from `up --no-deps ... api web proxy` to `up --detach --remove-orphans`. This
also applies to the production VM and to the rollback path. If `.env` changed a pinned value
(`POSTGRES_IMAGE` bumped, a postgres setting), the next application deploy recreates PostgreSQL
after migrations have run, and a rollback does the same. A database restart was never part of an
application deploy in ADR-006 and is not mentioned in `docs/runbooks/rollback.md`.

Fix: keep the explicit list and add `cloudflared` only where it exists (for example
`${BMS_EDGE_SERVICES:-proxy}` or a compose profile), or document and gate the database recreate.

### M5. Making the GHCR packages public publishes the private repository's code

Files: ADR-018 "The three GHCR packages are made public", `docs/runbooks/provision-host.md` section 5,
`deploy/caddy/Dockerfile` (now ships `deploy/`, `sql/`, `postgres/` too).

The api image is the complete application jar (trivially decompiled), the web image the full
bundle, and the proxy image now carries the deploy scripts, SQL and systemd units. "Their images
hold no secrets" is true, but the repository is private for a reason, and this also discloses the
security design (definer functions, auth flow) to anyone. This is an owner decision, not a
reviewer one, but it should be made explicitly. The runbook already describes the private
alternative (`read:packages` token for `bms`), which costs one stored credential on a host where
`bms` is already root-equivalent. Recommend keeping packages private and using that alternative
as the default.

### M6. cloudflared sits on the same flat network as PostgreSQL and the API management port

File: `deploy/compose.pi-staging.yml` (no `networks:`), `docs/runbooks/onboard-tenant.md`.

The tunnel's ingress is remotely managed in Cloudflare, not in this repository, and cloudflared can
reach `postgres:5432` and `api:8081` (actuator) on the default network. One wrong ingress entry,
a mistake in the onboarding `jq` edit (which PUTs the whole configuration), or a leaked onboarding
token (it holds `Zone.DNS:Edit` on the whole company zone plus `Cloudflare Tunnel:Edit`) exposes
the database or management port to the internet. Nothing in the repo or runbook checks that the
catch-all rule is `http_status:404`.

Fix: two networks, `edge` (cloudflared, proxy) and `internal` (proxy, api, web, postgres), so
cloudflared can only reach `proxy:8080`; add a runbook check that the last ingress rule is
`http_status:404` and that every other rule's service is `http://proxy:8080`.

---

## LOW

### L1. Pointer monotonicity fails open and is not enforced on the host

`deploy.yml` `staging-pointer`: `imagetools inspect ... || true` means an inspect or parse error
leaves `pointer` empty and the pointer is moved regardless; `git merge-base` failing (revision not
in history) also moves it. The puller has no check of its own: any pointer value different from
`current_tag` and `last_failed_tag` is deployed, including an older release, which also installs
that older release's `pull-staging.sh` and scripts. With H2 fixed, add a host-side guard (for
example refuse a revision whose signed build time is older than the live one unless
`state/allow_downgrade` exists), and make the CI guard fail closed on inspect errors.

### L2. systemd hardening is cosmetic next to the Docker socket, and incomplete

`bms-pull.service` sets `NoNewPrivileges`, `PrivateTmp`, `ProtectSystem=full`. With the root
daemon's socket these do not bound anything. Still worth adding, since they are free:
`ProtectHome=yes`, `ReadWritePaths=/opt/bms`, `ProtectSystem=strict`, `PrivateDevices=yes`,
`RestrictSUIDSGID=yes`, `CapabilityBoundingSet=`, `RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6`,
`UMask=0077`, `ProtectKernelTunables=yes`, `ProtectControlGroups=yes`. Also, unit changes arrive in
`/opt/bms/systemd/` but are never installed (runbook 8.4 says copy by hand), so the running units
drift silently from the repository.

### L3. Container hardening in `compose.pi-staging.yml`

The proxy (stock `caddy` runtime, now listening only on `:8080`) runs as root in its container;
none of the services set `cap_drop: [ALL]`, `security_opt: [no-new-privileges:true]` or
`read_only: true`. On a host shared with other workloads these are cheap: `user: "10001:10001"`
for proxy (as web already does), `cap_drop` and `no-new-privileges` for api, web, proxy,
cloudflared.

### L4. Punycode and look-alike slugs; missing parser tests

`SLUG` accepts `xn--...`, so a tenant can register an IDN A-label that browsers may render as
Unicode resembling another tenant. Reserve slugs starting with `xn--` (or any `??--` at positions
3 and 4, as IDNA does). Slugs may also contain `-bms-staging` or `rincoltech`, giving hosts like
`acme-rincoltech-login-bms-staging.rincoltech.com`; consider a deny list. Tests for the parser are
good for trailing dot, case, extra labels and suffix tricks, but there is no test for `xn--`, for
`X-Forwarded-Host` (M1), for an absolute-URI request line, or for a Host with a port through the
filter.

### L5. `backup.sh` runs outside `bms.slice`

`backup.sh` starts rclone with a plain `docker run --rm`, without `--cgroup-parent bms.slice` or a
memory limit. ADR-018 and SDD 9.13 say the slice is the hard cap for the whole stack; the nightly
backup is not in it. Add `--cgroup-parent bms.slice --memory 64m` on the staging path.

### L6. API memory sizing is at the container limit

448 MB container: heap 50 percent (224 MB) plus `MaxMetaspaceSize=160m` plus
`ReservedCodeCacheSize=48m` is already 432 MB before thread stacks, GC structures, direct buffers
and the JDBC driver. `ExitOnOutOfMemoryError` only covers the Java heap; a native overrun is a
kernel OOM kill of the API. The sums asked for check out (736 MB steady, 896 MB with migrate, under
the 900 MB slice), but there is no headroom anywhere. Measure RSS on the Pi after warm-up and
either drop heap to 45 percent or metaspace to 128 MB.

### L7. State files are written in place on an SD card

`state/current_tag` (by `deploy.sh`) and `state/last_failed_tag` are written with `>`. A power
loss (home site, no UPS mentioned) can leave `current_tag` empty; the puller then redeploys, and if
that fails there is no previous tag to roll back to. Write to a temp file, `sync`, then `mv`.

### L8. More self-hosted surface than needed

`adr-citation-guard.yml` and `architecture-model.yml` trigger on `push` to any branch, so every
branch push also executes on the host. `architecture-model.yml` runs the unpinned
`ghcr.io/avisi-cloud/structurizr-site-generatr:latest` with the workspace mounted on that same
persistent runner, a supply-chain input into the machine that later holds the deploy token (H1).
Pin it by digest and restrict `push` to `main`.

### L9. `--user 0:0` is safe only while the daemon really is rootless

Under rootless Docker, container uid 0 maps to the runner user, so `--user 0:0` gives nothing the
runner user does not already have; I found no escalation beyond it. But the safety depends on host
configuration outside the repo. If `DOCKER_HOST` or the socket ever points at the system daemon,
the same step bind-mounts the workspace as real root. Add a first step that fails unless
`docker info --format '{{.SecurityOptions}}'` contains `name=rootless`.

---

## What holds up

- `TenantHostPattern.slugFromHost`: exact prefix and suffix match, lower-cased, with the slug run
  through the FR-TEN-02 regex, so a trailing dot, extra labels on either side, a different zone, a
  suffix-plus-attacker-domain, and uppercase are all handled; `getServerName()` drops the port.
  The startup check that the platform host does not match the pattern is correct, and the
  platform gate in `AuthenticationFilter` is exact and case-insensitive. Apart from M1, I found no
  Host value that resolves to the wrong tenant or crosses platform and tenant.
- One-time links come only from `tenantOrigin(slug)` built from the pattern, never from the
  request Host.
- The puller cannot be injected through labels: `revision` must be 40 hex, `tag` must equal
  `sha-` plus its first seven characters, and `deploy.sh` re-validates the tag. `docker inspect`
  format strings take no attacker input. Locking (`pull.lock`, `deploy.lock`, exit 3 handling) is
  sound, and an unchanged pointer is a no-op.
- `compose.pi-staging.yml` publishes no ports; every secret is interpolated from `.env` with `:?`;
  the memory arithmetic in the header is correct (176 + 448 + 32 + 32 + 48 = 736, plus 160).
- `deploy.yml` never runs on `pull_request`; PR workflows declare `contents: read` only.
- The frontend host classifier mirrors the backend rules and has tests.

## Docs that contradict the code

- ADR-018 "passing the original `Host` through" (M1).
- ADR-018 "pull request jobs get read access only" presented as the wall between PRs and package
  write (H1: true for the token, not for the machine).
- ADR-018 and SDD 9.13 "`MemoryMax=900M` is the hard cap for the whole stack" (L5).
- ADR-018 "Nothing reaches into the host" and "no credential that lets anything outside reach the
  host": GHCR write reaches into the host as root (H2).
