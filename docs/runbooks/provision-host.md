# Runbook: provision a host

**Applies to:** a new staging or production VM · **Design:** `docs/sdd/09-infrastructure-design.md` · **Pipeline:** `docs/sdd/10-cicd-pipeline.md`

Do staging first, then production, one environment at a time. Nothing here is shared between the
two environments.

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
(`ssh-keygen -t ed25519 -f bms-staging-deploy -C bms-staging-deploy`), put the public half in
`/home/deploy/.ssh/authorized_keys`, and keep the private half for step 5. Disable password
authentication in `sshd_config`.

## 3. DNS and the Cloudflare token

In the Cloudflare zone of the environment's base domain (DNS only, not proxied):

- `A <base>` and `A *.<base>` pointing to the VM's IPv4 (and `AAAA` records if IPv6 is used).
- An API token with **Zone.DNS:Edit on that zone only**, for Caddy's DNS-01 challenge (the
  wildcard certificate cannot be issued any other way).

## 4. Write `/opt/bms/.env`

As `deploy`, copy `.env.example` from the repository to `/opt/bms/.env`, fill every value, then
`chmod 600 /opt/bms/.env`. Generate each password and key with `openssl rand -base64 32`; they must
differ per environment. Set `BMS_ENVIRONMENT`, `BMS_BASE_DOMAIN`, `ACME_EMAIL`,
`CLOUDFLARE_API_TOKEN`, the R2 values (a token scoped to the backup bucket) and
`BMS_OPENAPI_ENABLED` (`true` on staging, `false` on production). Put a copy of
`BACKUP_ENCRYPTION_KEY` in the offline store the dev lead keeps (chapter 8 section 8.7).

## 5. GitHub

In the repository settings, Environments:

- `staging` (or `production`): secrets `STAGING_HOST` (or `PROD_HOST`), `STAGING_SSH_KEY` (the
  private key from step 2), `STAGING_SSH_KNOWN_HOSTS` (run `ssh-keyscan <host>` from a trusted
  network); optional variable `STAGING_SSH_USER` if the user is not `deploy`.
- `production` only: required reviewer Hillary Arinda; deployment branches and tags limited to
  `v*.*.*` tags.

Packages: the images are pulled with the workflow's own token during each deploy, so private
packages work without any credential on the host. Making the three packages public (the
repository is public and the images hold no secrets) also works and allows manual pulls.

## 6. First deploy

- Staging: re-run the latest `Deploy` workflow on `main`, or merge any pull request.
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
