# Runbook: provision a host

**Applies to:** the production VM (sections 1 to 7) and the ARM64 staging host (section 8) · **Design:** `docs/sdd/09-infrastructure-design.md` · **Pipeline:** `docs/sdd/10-cicd-pipeline.md` · **Decisions:** ADR-006, ADR-018

Staging no longer runs on a cloud VM: it runs on a shared ARM64 host behind a Cloudflare Tunnel
and pulls its releases (ADR-018). Provision it with section 8 only. Sections 1 to 7 are the
production VM. Nothing is shared between the two environments.

## 1. Create the VM

- 2 vCPU, 4 GB RAM, 40 GB disk, Ubuntu LTS (for example Hetzner Cloud CX22), in the region
  confirmed for NFR-DP-07.
- Firewall: inbound 22 from the team's addresses only; 80 and 443 (TCP) and 443 (UDP) from
  anywhere. Nothing else.
- Add the dev lead's SSH key at creation.

## 2. Prepare the OS

```bash
apt-get update && apt-get -y upgrade
apt-get -y install ca-certificates curl unattended-upgrades
# Docker Engine and the Compose plugin from Docker's apt repository (docs.docker.com/engine/install/ubuntu)
adduser --disabled-password --gecos "" deploy
usermod -aG docker deploy
install -d -o deploy -g deploy -m 750 /opt/bms
```

Create an SSH key pair for GitHub Actions on your own machine
(`ssh-keygen -t ed25519 -f bms-prod-deploy -C bms-prod-deploy`), put the public half in
`/home/deploy/.ssh/authorized_keys`, and keep the private half for step 5. Disable password
authentication in `sshd_config`.

## 3. DNS and the Cloudflare token

In the Cloudflare zone `rincoltech.com` (DNS only, not proxied):

- `A bms`, `A bms-callbacks` and, per tenant, `A <slug>-bms`, pointing to the VM's IPv4 (and
  `AAAA` records if IPv6 is used). Do not point the apex or a wildcard of the company zone at the
  VM.
- An API token with **Zone.DNS:Edit on that zone only**, for Caddy's DNS-01 challenge (the
  wildcard certificate `*.rincoltech.com` cannot be issued any other way).

## 4. Write `/opt/bms/.env`

As `deploy`, copy `.env.example` from the repository to `/opt/bms/.env`, fill every value, then
`chmod 600 /opt/bms/.env`. Generate each password and key with `openssl rand -base64 32`; they must
differ per environment. Set `BMS_ENVIRONMENT=production`,
`BMS_TENANT_HOST_PATTERN={slug}-bms.rincoltech.com`, `BMS_PLATFORM_HOST=bms.rincoltech.com`,
`BMS_DNS_ZONE=rincoltech.com`, `BMS_CALLBACK_HOST=bms-callbacks.rincoltech.com`, `ACME_EMAIL`,
`CLOUDFLARE_API_TOKEN`, the R2 values (a token scoped to the backup bucket) and
`BMS_OPENAPI_ENABLED` (`true` on staging, `false` on production). Put a copy of
`BACKUP_ENCRYPTION_KEY` in the offline store the dev lead keeps (chapter 8 section 8.7).

Add the sign-in keys (ADR-014): run the API image once with the `keys` command and paste its three
lines into `/opt/bms/.env`:

```bash
docker run --rm ghcr.io/<owner>/bms-platform-api:<tag> keys
# BMS_TOKEN_SIGNING_JWK='{"kty":"EC",...}'
# BMS_DATA_KEY=...
# BMS_DATA_KEY_ID=k1
```

The API refuses to start without them. Generate them per environment and never copy them between
staging and production; losing `BMS_DATA_KEY` makes every enrolled TOTP secret unreadable, so keep
a copy with the backup key in the offline store.

## 5. GitHub

In the repository settings, Environments:

- `production`: secrets `PROD_HOST`, `PROD_SSH_KEY` (the private key from step 2),
  `PROD_SSH_KNOWN_HOSTS` (run `ssh-keyscan <host>` from a trusted network); optional variable
  `PROD_SSH_USER` if the user is not `deploy`; required reviewer Hillary Arinda; deployment
  branches and tags limited to `v*.*.*` tags.

Packages: the three packages stay **private** (ADR-018 finding M5: the api image is the whole
application jar, the web image the whole bundle, and the proxy image carries the deploy scripts and
SQL, none of which a private repository should publish just to save one credential). The staging
host's `bms` user logs in to GHCR once with a token scoped to `read:packages` only
(section 8.1a); Docker stores it under `bms`'s `~/.docker/config.json` and the puller and
`deploy.sh` reuse it for every pull and every cosign verification. The production deploy still logs
in with the workflow's own short-lived token.

Also add repository secrets `COSIGN_PRIVATE_KEY` and `COSIGN_PASSWORD` (ADR-018 finding H2): see
section 8.1a for how to generate them.

## 6. First deploy

- Production: push a `vX.Y.Z` tag on a commit that is green on staging and approve it.

The first deploy creates the database volume; the postgres container runs
`postgres/initdb/01-roles.sh`, which creates `bms_owner` and `bms_app`; then migrations run and the
application starts. Caddy obtains the wildcard certificate on first start (watch
`docker compose --project-name bms -f /opt/bms/compose.yml logs proxy`).

## 7. Backups and checks

```bash
crontab -u deploy -e
15 1 * * * /opt/bms/backup.sh >> /opt/bms/state/backup.log 2>&1
```

Run `/opt/bms/backup.sh` once by hand and confirm the file appears in R2. Then set up the
monitoring of chapter 9 section 9.11, onboard a fabricated tenant
(`docs/runbooks/onboard-tenant.md`), and on staging run the isolation checks of chapter 15 against
two fabricated tenants.

## 8. The ARM64 staging host (shared, behind a Cloudflare Tunnel)

**Host:** the existing Raspberry Pi 4 (8 GB, Debian 13, `linux/arm64`, SD card) at the Rincol home
site, shared with other workloads. BMS gets about 900 MB of memory, hard-capped by `bms.slice`.
No inbound port is opened, now or later (ADR-018, chapter 9 section 9.13). Run everything below
as root unless it says otherwise.

### 8.1 Docker and the `bms` user

```bash
apt-get update && apt-get -y install ca-certificates curl
# Docker Engine and the Compose plugin from Docker's apt repository for Debian
# (docs.docker.com/engine/install/debian); the Debian 13 default is cgroup v2 with the systemd
# cgroup driver, which bms.slice needs:
docker info --format '{{.CgroupDriver}} cgroup v{{.CgroupVersion}}'   # expect: systemd cgroup v2
# The dedicated service user: no password, no SSH key, no login shell. It is in the docker
# group (not rootless Docker, ADR-018), which makes it root-equivalent on this host: use it for
# the timer only.
adduser --system --group --home /opt/bms --shell /usr/sbin/nologin bms
usermod -aG docker bms
install -d -o bms -g bms -m 750 /opt/bms /opt/bms/state
```

### 8.1a Image signing and the registry token (ADR-018 findings H2, M5)

Generate the cosign key pair once, on a machine that is not this repository's CI (the private key
must never be committed or pasted into a chat session or a pull request):

```bash
cosign generate-key-pair
# writes cosign.key (password protected) and cosign.pub in the current directory
```

Then:

1. Commit the generated `cosign.pub` as `deploy/cosign.pub` in a pull request (it replaces the
   placeholder that says, in the file itself, that it is not a real key and must fail closed).
2. Add repository secrets `COSIGN_PRIVATE_KEY` (the full contents of `cosign.key`) and
   `COSIGN_PASSWORD` (the password chosen above), so `deploy.yml`'s build job can sign every image
   it pushes. Delete the local `cosign.key` once the secret is stored; keep the password in the
   team's secret manager, not in this repository.
3. Copy the same `cosign.pub` onto the staging host, outside any release (the puller must verify a
   release's signature against a key that release cannot supply):

   ```bash
   scp deploy/cosign.pub bms@<staging-host>:/opt/bms/cosign.pub
   ```

   `pull-staging.sh` refuses every release until this file exists; it is never overwritten by the
   puller. Rotating the key later is the same two steps, done by hand, never automated.

Packages stay private (ADR-018 finding M5), so `bms` also logs in to GHCR once, with a token scoped
to `read:packages` only (a classic PAT or a fine-grained token on this repository), which Docker
remembers:

```bash
echo "$READ_PACKAGES_TOKEN" | sudo -u bms docker login ghcr.io --username <github-user> --password-stdin
```

### 8.2 The tunnel

In the Cloudflare dashboard, Zero Trust, Networks, Tunnels: create a tunnel named
`bms-staging` of type cloudflared and copy its **connector token** (it goes into `.env`, step 8.3;
nothing is installed on the host outside Docker). Then add its public hostnames, each with
service `http://proxy:8080`:

| Public hostname | Service |
|---|---|
| `bms-staging.rincoltech.com` | `http://proxy:8080` |
| `demo-bms-staging.rincoltech.com` | `http://proxy:8080` (and one per tenant, `docs/runbooks/onboard-tenant.md`) |

The dashboard creates the proxied CNAMEs `bms-staging` and `demo-bms-staging` to
`<tunnel id>.cfargotunnel.com`. Leave SSL/TLS on Full and the edge certificate as the free
Universal certificate, which covers `*.rincoltech.com`. Record the tunnel id and the account id;
onboarding uses them.

### 8.3 Write `/opt/bms/.env`

Copy `.env.example` to `/opt/bms/.env` (owner `bms`, mode 600) and fill it:

```bash
install -o bms -g bms -m 600 /dev/null /opt/bms/.env
# then edit it; the staging values:
BMS_ENVIRONMENT=staging
BMS_TENANT_HOST_PATTERN={slug}-bms-staging.rincoltech.com
BMS_PLATFORM_HOST=bms-staging.rincoltech.com
BMS_OPENAPI_ENABLED=true
BMS_DB_POOL_SIZE=5
POSTGRES_IMAGE=postgres:16.15-alpine
RCLONE_IMAGE=rclone/rclone:1.75.1
CLOUDFLARED_IMAGE=cloudflare/cloudflared:2026.9.3
CLOUDFLARE_TUNNEL_TOKEN=<connector token from 8.2>
# passwords (openssl rand -base64 32 each), the three sign-in keys (section 4), the backup key
# and the R2 values (prefix staging/), exactly as for any environment
```

`ACME_EMAIL`, `CLOUDFLARE_API_TOKEN`, `BMS_DNS_ZONE` and `BMS_CALLBACK_HOST` are not used on this
host: nothing here issues a certificate.

### 8.4 First files and the systemd units

The puller replaces the host files from each release's proxy image, but the first run needs the
puller itself and the unit files. Take them from the current staging image, as `bms` since the
packages are private and only `bms`'s Docker config holds the `read:packages` login (section 8.1a):

```bash
IMG=ghcr.io/rincoltech-solutions-ltd/bms-platform-proxy:staging
sudo -u bms docker pull "$IMG" && cid="$(sudo -u bms docker create "$IMG")"
sudo -u bms docker cp "$cid:/usr/share/bms-deploy/pull-staging.sh" /opt/bms/pull-staging.sh
docker cp "$cid:/usr/share/bms-deploy/systemd/." /etc/systemd/system/
sudo -u bms docker rm "$cid"
chown bms:bms /opt/bms/pull-staging.sh && chmod 755 /opt/bms/pull-staging.sh
chmod 644 /etc/systemd/system/bms.slice /etc/systemd/system/bms-pull.service /etc/systemd/system/bms-pull.timer
systemctl daemon-reload
systemctl start bms.slice && systemctl show bms.slice -p MemoryMax    # MemoryMax=943718400
systemctl enable --now bms-pull.timer
```

Confirm `/opt/bms/cosign.pub` exists (section 8.1a) before starting the timer: the puller refuses
every release without it, by design.

Later changes to the unit files arrive in `/opt/bms/systemd/` with each release; copy them into
`/etc/systemd/system/` and `systemctl daemon-reload` when a release changes them (`bms-pull.service`
now logs a notice when the shipped units differ from the installed ones, ADR-018 finding L2).

### 8.5 First deploy and checks

If the hosted runners are unavailable (for example the organisation's private-repo minutes are used up),
the first images can be built without them and without putting any secret on the shared Pi. Ship the
exact commit to the host with `git archive`, `docker build --platform linux/arm64` there as `bms` with
the labels the puller reads (`org.opencontainers.image.version=sha-<7>` and `revision=<40 hex>`), then
`docker save` each image and copy it to a trusted workstation. From the workstation: `crane push` it, sign the
pushed digest with **cosign v2.4.1** (the puller verifies with that version; a newer cosign writes a format
it cannot read) and `--tlog-upload=false`, verify, and only then `crane tag` the three `staging` pointers.
The registry write token and the signing key never leave the workstation.

The first timer run (two minutes after enabling, or `systemctl start bms-pull.service`) pulls
`bms-platform-api:staging`, installs the host files of that commit, creates the database volume
(the postgres container runs `postgres/initdb/01-roles.sh`), migrates and starts the stack.

```bash
journalctl -u bms-pull.service -n 50 --no-pager
cat /opt/bms/state/current_tag
sudo -u bms docker compose --project-name bms -f /opt/bms/compose.yml ps     # no published ports
systemd-cgls /bms.slice                                                         # every container is here
curl -s https://bms-staging.rincoltech.com/version
curl -s -o /dev/null -w '%{http_code}\n' https://demo-bms-staging.rincoltech.com/api/v1/me
```

Then create the first platform operator and the fabricated `demo` tenant
(`docs/runbooks/onboard-tenant.md`), and add the nightly backup for the `bms` user:

```bash
crontab -u bms -e
15 1 * * * /opt/bms/backup.sh >> /opt/bms/state/backup.log 2>&1
```

Run `/opt/bms/backup.sh` once as `bms` and confirm the file appears in R2 under `staging/`.
