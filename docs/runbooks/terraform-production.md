# Runbook: create the production server with Terraform

**Decision:** ADR-063 (proposed) · **Design:** `docs/sdd/09-infrastructure-design.md` section 9.3.1 · **Code:** `infra/production/` ·
**Related:** `provision-host.md`, `monitoring-and-alerts.md`, `restore-from-backup.md`, `deploy.md`

Nothing here has been run. Cost starts at apply, not before. Read the whole runbook once before step 1.

## What it creates

| | Hetzner root (`infra/production/hetzner`) | AWS root (`infra/production/aws`) |
|---|---|---|
| VM | Server with delete and rebuild protection, Hetzner backups if chosen (step 1), IPv4 and IPv6 | EC2 instance with termination protection, IMDSv2 only, encrypted 40 GB gp3 disk kept on termination, Elastic IP (IPv4 only) |
| Firewall | Hetzner firewall | Security group |
| Both | 22 from `admin_cidrs` only; 80, 443 TCP and 443 UDP from anywhere; nothing else in; outbound open ||
| Both | Cloud-init: `ops` (people, keys only, sudo), `bms` (service user, docker group), sshd without passwords or root, Docker, fail2ban, unattended upgrades, the `bms-pull`, `bms-monitor` and `bms-restore-drill` units installed, none enabled ||
| Both | Cloudflare, existing account: A (and AAAA on Hetzner) records `bms`, `bms-callbacks`, `<slug>-bms`; R2 backup bucket with lock (daily 30 days, monthly 365) and lifecycle rules; R2 documents bucket; three host tokens limited to the VM's addresses if Cloudflare enforces the IP condition (the DNS-01 one reaches the whole zone, step 1.10) ||

It does not create: the Terraform state bucket (step 3), `/opt/bms/.env` beyond the lines Terraform knows, `cosign.pub` on the
host, the registry login, the external uptime monitor, or the promote command of ADR-063 decision 7.

## 1. Decide first (the owner)

Items 1 to 4 and 7 have no default in the Terraform, so `terraform plan` cannot run until they are decided; the others are
recorded decisions the plan cannot check.

1. **Provider:** Hetzner Cloud or AWS. Apply only that root; leave the other directory alone.
2. **Location or region**, recorded in its own ADR against NFR-DP-07 (Uganda PDPO: processing outside Uganda). Hetzner:
   `location` (for example `fsn1`, `hel1`). AWS: `region` (an opt-in region such as `af-south-1` must be enabled in the account first).
3. **R2 location** of the backups and documents (`r2_location`: `weur`, `eeur`, `apac`, `enam`, `wnam` or `oc`): the same data
   protection decision, for the copies.
4. **Size:** 2 vCPU, 4 GB, 40 GB (chapter 9 section 9.4). Hetzner `server_type` (for example `cx23` where it is sold); AWS
   `instance_type` (for example `t3.medium`, or `t4g.medium` with `architecture = "arm64"`).
5. **Who pays** for the provider account and R2, named before the first apply.
6. **Who may SSH:** the team's public keys and the fixed addresses SSH is allowed from (never the whole internet: validation
   refuses an IPv4 prefix shorter than /16 and an IPv6 prefix shorter than /48; on AWS it refuses IPv6 entries altogether,
   because the default VPC has no IPv6 and they would silently do nothing). On Hetzner, a key whose fingerprint is already in
   the project makes `hcloud_ssh_key` fail: remove it there first or `terraform import` it.
7. **Hetzner backups** (Hetzner root only, `backups`, no default): Hetzner's daily disk images cost about a fifth of the server and
   hold the whole disk unencrypted, the database and `/opt/bms/.env` included, outside the encrypted and locked backups of
   ADR-058 and in Hetzner's location. Choose `true` only if that is acceptable under the same data protection decision.
8. **Tenants** that go live on day one (`tenant_slugs`).
9. **Backup retention against erasure** (data protection, ADR-058). Daily backups are LOCKED for 30 days and monthly backups
   for 365 days. A customer's erasure request under the Uganda PDPO therefore cannot reach the backups for up to a year: the copy
   ages out at the end of its window and nobody can remove it earlier without an account administrator lifting the lock. The
   owner accepts this, with the wording the privacy notice and processing agreement will use, or asks for shorter windows in a
   new ADR (the Terraform refuses anything under 30 and 365 days today). It also commits a year of R2 storage for every monthly
   backup from the day it is written.
10. **The DNS-01 token's reach** (accepted risk, ADR-063 decision 4). Caddy's token can edit every DNS record of
    `rincoltech.com`, so whoever takes over the host could also change MX or the Resend DKIM records and send mail as Rincol Tech.
    Accepted for launch on 22 Oct 2026 by the Dev Lead under the owner's delegation to decide in his place; the owner can reverse
    it by buying a separate validation domain (this item). The fix, when he chooses it: buy a separate validation domain,
    point `_acme-challenge.rincoltech.com` (and the per-host names) at it with a CNAME, give Caddy a token for that domain only
    and set Caddy's `override_domain`, which removes the risk.

## 2. Secrets: where each one comes from

No secret is written in a file of this repository, in `terraform.tfvars`, in a plaintext credentials file, or in a chat. Each
lives in the maintainer's Keychain: tokens are exported in the shell for the session only, and the two S3-style keys (the state
bucket and AWS) are read by a `credential_process` helper, so `~/.aws/credentials` holds nothing.

| What | How Terraform gets it | Scope |
|---|---|---|
| Hetzner token | `HCLOUD_TOKEN` | Read and write on the Rincol Tech Hetzner project only |
| AWS credentials | `AWS_PROFILE=bms-aws`, a profile in `~/.aws/config` whose `credential_process` reads the Keychain | EC2 in the chosen region; no console password needed |
| Cloudflare Terraform token | `CLOUDFLARE_API_TOKEN` | A NEW account token, not the broad one the account already has: Account, Workers R2 Storage: Edit; Account, Account API Tokens: Edit; Zone, DNS: Edit and Zone: Read on the `rincoltech.com` zone only. It can mint tokens and lift the backup lock, so it is a root credential: guarded like the account administrator's sign-in, never on the host |
| Cloudflare account id | `TF_VAR_cloudflare_account_id` | Marked sensitive, so plans mask it; kept out of the public repository |
| State bucket key | `profile = "bms-tfstate"` in `backend.tf`, a profile in `~/.aws/config` whose `credential_process` reads the Keychain | Object read and write on the state bucket only. The state holds the three host token values, so this key is a root credential too |

```sh
export HCLOUD_TOKEN="$(security find-generic-password -s bms-prod-hcloud -w)"          # Hetzner root only
export AWS_PROFILE=bms-aws                                                              # AWS root only
export CLOUDFLARE_API_TOKEN="$(security find-generic-password -s bms-prod-cf-terraform -w)"
export TF_VAR_cloudflare_account_id="$(security find-generic-password -s bms-prod-cf-account -w)"
```

The two S3-style keys go into the Keychain once, typed at the prompt (`-w` last makes `security` ask for the value):

```sh
security add-generic-password -a "$USER" -s bms-tfstate-key-id -w
security add-generic-password -a "$USER" -s bms-tfstate-secret -w
security add-generic-password -a "$USER" -s bms-aws-key-id -w        # AWS root only
security add-generic-password -a "$USER" -s bms-aws-secret -w        # AWS root only
```

A helper prints them in the form `credential_process` expects, `~/bin/keychain-aws-creds` (mode 700):

```sh
#!/bin/sh
# keychain-aws-creds <name>: the S3-style key pair stored as <name>-key-id and <name>-secret in the Keychain.
set -eu
id="$(security find-generic-password -s "$1-key-id" -w)"
secret="$(security find-generic-password -s "$1-secret" -w)"
printf '{"Version":1,"AccessKeyId":"%s","SecretAccessKey":"%s"}\n' "$id" "$secret"
```

and `~/.aws/config` names it, with nothing in `~/.aws/credentials`:

```ini
[profile bms-tfstate]
credential_process = /Users/<you>/bin/keychain-aws-creds bms-tfstate

[profile bms-aws]
region = <the region of step 1>
credential_process = /Users/<you>/bin/keychain-aws-creds bms-aws
```

Check with `aws sts get-caller-identity --profile bms-aws` (AWS only). The Keychain item names are suggestions; use the same
ones every time. Never paste plan or apply output anywhere public (an issue, a pull request, a chat): it names addresses and
resources even with the sensitive values masked.

## 3. The state bucket (once, before the first plan)

Terraform state holds the host token values and the addresses, so it never goes in git and never stays only on one laptop.

1. In the existing Cloudflare account, R2: create a private bucket `bms-tfstate` (same `r2_location`).
2. R2, Manage API tokens: an **Object Read & Write** token applied to `bms-tfstate` only. Put its access key id and secret in
   the Keychain as `bms-tfstate-key-id` and `bms-tfstate-secret` (step 2); never in `~/.aws/credentials`.
3. In the chosen root, create `backend.tf` (git-ignored, never committed):

   ```hcl
   terraform {
     backend "s3" {
       bucket                      = "bms-tfstate"
       key                         = "production/terraform.tfstate"
       region                      = "auto"
       profile                     = "bms-tfstate"
       use_lockfile                = true # Terraform 1.10 or later; if R2 refuses it, remove it and let one person apply
       endpoints                   = { s3 = "https://<account id>.r2.cloudflarestorage.com" }
       skip_credentials_validation = true
       skip_region_validation      = true
       skip_requesting_account_id  = true
       skip_metadata_api_check     = true
       skip_s3_checksum            = true
       use_path_style              = true
     }
   }
   ```

   The named profile keeps the state key apart from the AWS provider's own credentials.
4. R2 has no object versioning, so a corrupted or deleted state file cannot be recovered from the bucket. After every apply,
   keep an encrypted copy in the offline store (chapter 8 section 8.7), the same place as the backup key:

   ```sh
   mkdir -p "$HOME/bms-offline" && chmod 700 "$HOME/bms-offline"
   terraform state pull | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt \
     -out "$HOME/bms-offline/tfstate-$(date -u +%Y%m%dT%H%M%SZ).enc"
   ```

   The copy is written OUTSIDE the repository on purpose: this repository is public, and a stray `git add` of an encrypted
   state would publish every host token behind a passphrase anyone can attack offline (`infra/production/.gitignore` also
   refuses `*.enc`, as a second guard). It asks for a passphrase; that passphrase is kept with the backup key. Move the file to
   the offline store and delete it from the Mac. The state holds the host token values in clear.

## 4. Clear the host names first

Cloudflare accepts several A records with the same name. If `bms`, `bms-callbacks` or a `<slug>-bms` record already exists, the
apply adds a second one beside it and visitors are sent to the old target and the new VM in turn, while the plan still looks
clean (it only lists what it creates). So, before the first plan, list every name Terraform will manage, in the dashboard
(DNS, Records, search each name) or with the Terraform token:

```sh
ZONE_ID=<the rincoltech.com zone id from the dashboard>
for h in bms bms-callbacks <each slug>-bms; do
  curl -s -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
    "https://api.cloudflare.com/client/v4/zones/$ZONE_ID/dns_records?name=$h.rincoltech.com" | jq -r '.result[] | "\(.type) \(.name) \(.id)"'
done
```

Each record found is either deleted (when nothing serves on it yet) or brought under Terraform with `terraform import
'module.cloudflare.cloudflare_dns_record.a["<host>"]' '<zone id>/<record id>'` after `terraform init`, so the plan shows it
being updated, not a second one being created. Do the same for the AAAA records on Hetzner. Staging's names
(`bms-staging`, `<slug>-bms-staging`) are different names and are left alone.

## 5. Plan and read it

```sh
cd infra/production/<hetzner|aws>
cp terraform.tfvars.example terraform.tfvars     # fill the decisions of step 1; git-ignored
terraform init
terraform fmt -check -recursive .. && terraform validate
terraform plan -out plan.tfplan
```

Read the plan before anything else. Expect, and nothing more:

- Hetzner: 1 `hcloud_server` (delete and rebuild protection true), 1 `hcloud_firewall` with exactly four inbound rules, one
  `hcloud_ssh_key` per key. AWS: 1 `aws_instance` (`disable_api_termination = true`, `http_tokens = "required"`), 1
  `aws_security_group` with four ingress blocks, 1 `aws_eip`.
- One A record per host name (plus one AAAA on Hetzner with IPv6), never the apex and never a wildcard.
- 2 `cloudflare_r2_bucket`, 1 `cloudflare_r2_bucket_lock` with `daily-30d` and `monthly-365d`, 1 `cloudflare_r2_bucket_lifecycle`.
- 3 `cloudflare_account_token`, each with a single policy on one bucket or the one zone, and a `request_ip` condition.
- Nothing to change or destroy, except the records imported in step 4.

A plan that shows anything else is stopped and discussed, not applied.

## 6. Apply (production day only, by the owner)

```sh
terraform apply plan.tfplan
terraform output                                  # ipv4, ipv6, hostnames, bucket names; host_env is hidden
```

Then keep the encrypted state copy (step 3.4).

Before the first SSH, verify the host key out of band: the provider console shows the server's boot log, where cloud-init
prints the host key fingerprints between `BEGIN SSH HOST KEY FINGERPRINTS` and `END` (AWS: the instance, Actions, Monitor and
troubleshoot, Get system log). Compare the ED25519 line with the fingerprint `ssh` shows on first connect, and answer `yes`
only if they match; otherwise stop, someone is between the Mac and the server.

Hetzner has no boot-log view, only the live console (the server, Console). Nobody can sign in there (no user has a password),
so the console cannot run `ssh-keyscan localhost`; instead open it right after the apply and read the fingerprint block that
cloud-init prints at the end of first boot, which stays on screen above the login prompt. If it has already scrolled away,
`sudo reboot` is not a fix (the block is printed once); fall back to comparing `ssh-keyscan -t ed25519 <ipv4> | ssh-keygen -lf -`
from the Mac and from a second network (a phone hotspot): both must match the key `ssh` offers. That is a weaker check; record
in the cutover notes that the console check was missed.

Then wait for cloud-init: `ssh ops@<ipv4> cloud-init status --wait` should end in `status: done`. Check the hardening:
`ssh root@<ipv4>` and a password sign-in are refused; `sudo sshd -T | grep -E 'passwordauth|permitroot'` shows `no`.

## 7. The host's `.env` (the steps that carry secrets)

1. As `ops`, start from the repository's `.env.example` and fill everything Terraform does not know, per
   `provision-host.md` section 4: the database passwords, `BACKUP_ENCRYPTION_KEY`, the sign-in keys, SMTP (required in production,
   ADR-059), the Telegram pair, `BMS_ENVIRONMENT=production` and the host names. Delete from it the lines `host_env` provides.
   Install it as `sudo install -o bms -g bms -m 600 <file> /opt/bms/.env` and remove the copy.
2. Add the lines Terraform knows, straight from the Mac to the host without writing them to a file:

   ```sh
   terraform output -raw host_env | ssh ops@<ipv4> 'sudo -u bms tee -a /opt/bms/.env > /dev/null'
   ```

   They are the R2 endpoint, bucket and keys of the backup and documents tokens, `BACKUP_RETENTION_ON_HOST=false` and the
   DNS-01 `CLOUDFLARE_API_TOKEN`.
3. Keep `BACKUP_ENCRYPTION_KEY` and `BMS_DATA_KEY` in the offline store (chapter 8 section 8.7).
4. `cosign.pub`, outside any release: `scp deploy/cosign.pub ops@<ipv4>:/tmp/ && ssh ops@<ipv4> 'sudo install -o bms -g bms -m 644 /tmp/cosign.pub /opt/bms/cosign.pub'`.
5. The registry login, once, as `bms`, with a token scoped to `read:packages` only (`provision-host.md` section 8.1a).

## 8. First files and the first deploy

Until the promote command of ADR-063 exists there is no `production` pointer, so `bms-pull.timer` stays disabled and the first
release is deployed by hand: the release that is live on staging (`curl -s https://bms-staging.rincoltech.com/version`).

The host files come from the repository at that release's commit, the way the SSH deploy copies them (never from the image
before it is verified). `cosign.pub` is left out on purpose: the host keeps the copy of step 7.4, never one a release brings. On the Mac, in a clean checkout:

```sh
T=sha-<7>                                         # the tag live on staging
git fetch origin && git archive "${T#sha-}" deploy \
  | ssh ops@<ipv4> 'sudo -u bms tar -x --strip-components=1 -C /opt/bms \
      --exclude=deploy/cosign.pub --exclude=cosign.pub --exclude=deploy/runner --exclude=deploy/signer --exclude=deploy/tests'
ssh ops@<ipv4> "cd /opt/bms && sudo -u bms ./deploy.sh $T"
```

`deploy.sh` resolves the api, web and proxy images to digests and refuses the release unless every cosign signature verifies
against `/opt/bms/cosign.pub` (ADR-018 finding H2), before it starts anything. The first run creates the database volume, runs
`postgres/initdb/01-roles.sh`, migrates and starts the stack; Caddy obtains the wildcard certificate with the DNS-01 token. Check:
`curl -s https://bms.rincoltech.com/version` shows the commit, and `docker compose --project-name bms -f /opt/bms/compose.yml ps`
publishes no port but the proxy's.

Every later release is the same `deploy.sh <tag>` by hand (`deploy.md`, "Manual deploy", as `bms`) until the promote command
is built and reviewed; then `systemctl enable --now bms-pull.timer`.

## 9. The first backup

Open the `bms` user's crontab with `sudo crontab -u bms -e` and add this one line (it is a crontab entry, not a command):
`15 1 * * * /opt/bms/backup.sh >> /opt/bms/state/backup.log 2>&1`. Then run the first backup by hand:

```sh
sudo -u bms /opt/bms/backup.sh && sudo tail -5 /opt/bms/state/backup.log
```

The log must end with the upload to `production/daily/` and no `rclone delete` (retention is the bucket's). Look for the object
in the dashboard, R2, the backup bucket.

## 10. Prove the lock refuses a delete (a throwaway object, never a real backup)

On the host, in a shell as `bms` (`sudo -u bms bash`, since `bms` has no login shell), with the host's own credentials, exactly as `restore-from-backup.md` section 7 step 5:

```sh
set -a; . /opt/bms/.env; set +a
rc() { docker run --rm -v /tmp:/tmp -e RCLONE_CONFIG_R2_TYPE=s3 -e RCLONE_CONFIG_R2_PROVIDER=Cloudflare \
  -e RCLONE_CONFIG_R2_ENDPOINT="$R2_ENDPOINT" -e RCLONE_CONFIG_R2_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
  -e RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" -e RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true "$RCLONE_IMAGE" "$@"; }
echo lock-test > /tmp/lock-test.txt
N="0-lock-test-$(date -u +%Y%m%dT%H%M%SZ).txt"
rc copyto /tmp/lock-test.txt "r2:${R2_BACKUP_BUCKET}/production/daily/$N"
rc deletefile "r2:${R2_BACKUP_BUCKET}/production/daily/$N"      # must FAIL (access denied or object locked)
echo lock-test-changed > /tmp/lock-test.txt                     # different content, so rclone really uploads
rc copyto --ignore-times /tmp/lock-test.txt "r2:${R2_BACKUP_BUCKET}/production/daily/$N"   # an overwrite must FAIL too
rc lsf "r2:${R2_BACKUP_BUCKET}/production/daily/" | grep "$N"   # still listed
rm /tmp/lock-test.txt
```

If the delete succeeds, the lock is not on: stop, only the throwaway was lost; check `terraform state show
module.cloudflare.cloudflare_r2_bucket_lock.backups` and the dashboard before anything else. The throwaway stays for its 30 days
and the lifecycle removes it on day 31. Record the date and who ran the test in `restore-from-backup.md` section 7's table.

Also check the token address condition from the Mac (no Docker there; the AWS CLI is enough), with the host's keys read from
Terraform inside a subshell so they stay out of the shell history and environment:

```sh
( eval "$(terraform output -raw host_env | grep -E '^R2_(ENDPOINT|ACCESS_KEY_ID|SECRET_ACCESS_KEY|BACKUP_BUCKET)=')"
  AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" AWS_PROFILE= \
    aws s3 ls "s3://$R2_BACKUP_BUCKET/production/daily/" --endpoint-url "$R2_ENDPOINT" --region auto )
```

It should be refused if Cloudflare enforces the address condition on R2's S3 requests. If it is accepted, Cloudflare does not
enforce it there: the host's keys then work from anywhere they leak to, the lock still refuses their deletes and overwrites,
and the finding is recorded in ADR-063 decision 4 before carrying on.

## 11. Host checks and the first restore drill

Follow `monitoring-and-alerts.md` sections 1 and 2, with one difference: on this host the units are already in
`/etc/systemd/system/` from cloud-init and run as `bms`, so skip the install and the `User=deploy` drop-in and only add the
`MONITOR_*` lines to `.env`, then:

```sh
sudo systemctl enable --now bms-monitor.timer bms-restore-drill.timer
sudo systemctl start bms-restore-drill.service      # the first drill now, not next Sunday
sudo tail -20 /opt/bms/state/restore-drill.log; sudo cat /opt/bms/state/last_restore_drill
```

The drill must finish under the 2 hour RTO before any tenant's real data moves (`customer-data-cutover.md`).

## Change, add a tenant, remove

- Always `terraform plan` first and read it. A new tenant is one more slug in `tenant_slugs`: clear its name first (step 4),
  then the plan adds its A record (and AAAA on Hetzner).
- A change of `user_data` or of the image does not replace the server (`ignore_changes`); change the host by hand and fold it
  back into `modules/cloud-init` in a pull request.
- The server is protected (Hetzner delete and rebuild protection; AWS termination protection) and the buckets and lock have
  `prevent_destroy`. To remove anything on purpose: a reviewed change that lifts the protection, apply, and only then destroy.
- The lock stops the host's token, not everyone. R2 bucket locks have no mode an account administrator cannot lift, and the
  Terraform token can mint tokens, so it can too. Both are root credentials: few people, two-step sign-in, never on a server.
  `prevent_destroy` does not stop an in-place edit of the lock rules; the retention variables refuse windows under 30 and 365
  days, and any change to `cloudflare_r2_bucket_lock` in a plan is stopped and discussed (ADR-058).
- Keep the encrypted state copy (step 3.4) after every apply.
- Rotating a host token: `terraform apply -replace=module.cloudflare.cloudflare_account_token.host_backups` (or the other two).
  Then replace the changed lines in `/opt/bms/.env` in place (edit as `bms`; do not append step 7.2 again, which would leave the
  old lines behind) and restart what reads them: nothing for the backup token (each run reads `.env`), the api for the documents
  token, the proxy for the DNS token (`docker compose --project-name bms -f /opt/bms/compose.yml up -d --force-recreate api proxy`).
