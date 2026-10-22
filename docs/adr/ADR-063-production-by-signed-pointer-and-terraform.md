# ADR-063: Production is created by Terraform on production day and updated by a signed pointer that the owner moves

## Status

Proposed, and it stays Proposed until three things that are still proposals are done: the owner chooses the provider, the
location or region and the server size (the region is the data protection decision of NFR-DP-07, Uganda PDPO, and needs its
own record); the promote command of decision 3 is built and reviewed; and the `promote` and `deploy-production` jobs leave
`deploy.yml`. Until then the documented production path is still ADR-006's. Nothing in `infra/production/` has been applied.

First written as ADR-041 in pull request #301 (issue #300) and renumbered at rebase: ADR-041 on `main` is the branch closing
record. The owner decided on 2026-10-18 that the server is created when production is ready and not before, that DNS and storage
stay in the existing Cloudflare account, and that for now only the Terraform is prepared. Supersedes the production half of
ADR-006 once accepted (a version tag deploys over SSH from a GitHub Environment on a hosted runner). Builds on ADR-018 (pull-based
deploy, cosign verification by the host), ADR-040 (no hosted minutes; signing on the maintainer's Mac) and ADR-058 (host checks,
restore drill, backups the host cannot delete). Design: chapter 9 section 9.3.1. Runbook: `docs/runbooks/terraform-production.md`.

## Context

There is no production server; it is the launch blocker. The documented path (a `vX.Y.Z` tag, a `production` GitHub Environment
with a required reviewer, an SSH key held as a repository secret, a job on a hosted runner) needs a standing credential that lets
CI log in to the production host, the kind ADR-018 removed for staging. Staging shows the alternative every day: the host pulls a
signed pointer and verifies the signature with a key only it holds; nothing reaches into the host.

Two providers are open: a Rincol Tech Hetzner Cloud project and a Rincol Tech AWS account. Neither is chosen, and the region is
a data protection question as much as a price one. The repository is public, so nothing that identifies the accounts or hosts
can be written in it.

The launch audit (#416, item M12) named the costliest single risk: the host's R2 token could delete every backup. ADR-058 decided
the fix (lock and lifecycle rules in the bucket, a host token that cannot change them) but left it as dashboard steps for a
person to remember.

## Decision

1. **Terraform in `infra/production/`, applied once on production day.** Two roots create the same host, and the owner applies
   only the one chosen: `hetzner/` (a server with delete and rebuild protection, Hetzner backups only if the owner chooses them, a Hetzner firewall) and
   `aws/` (an EC2 instance with termination protection, IMDSv2 only, an encrypted disk and an Elastic IP, a security group).
   The firewall is the same on both: 22 from an admin address list only (the whole internet is refused by validation), 80 and
   443 TCP and 443 UDP from anywhere, nothing else in. The location or region, the server size and the R2 location have no
   default, so a plan cannot run until the owner has decided them.
2. **One cloud-init for both** (`modules/cloud-init`): the `ops` user for people with the team's SSH keys and sudo, sshd with
   no passwords and no root, Docker from Docker's apt repository, unattended upgrades, fail2ban, the `bms` service user (no
   password, no key, no login shell, in the docker group) and the units the repository ships in `deploy/systemd`: the puller,
   the host checks and the weekly restore drill. They are installed but not enabled; the runbook enables them once the host has
   its `.env`. No secret goes into cloud-init, because user data is readable from the provider's metadata service.
3. **The Cloudflare side is one module** (`modules/cloudflare`) in the existing account: the DNS records (`bms`,
   `bms-callbacks`, `<slug>-bms`, DNS only), the R2 backup and documents buckets, and the backup protection of ADR-058 as code:
   lock rules (`production/daily/` 30 days, `production/monthly/` 365 days), lifecycle rules a day after each lock, and
   `prevent_destroy` on both buckets and the lock.
4. **The host's tokens are made by Terraform.** Three account-owned tokens, each meant to be usable only from the VM's
   addresses if Cloudflare enforces the IP condition (the runbook checks whether it does on R2's S3 requests; if not, the lock
   below still holds): object read and write on the backup bucket only, the same on the documents bucket only, and DNS edit
   plus zone read on the whole `rincoltech.com` zone for Caddy's DNS-01 challenge. That last token is not narrow: whoever takes
   over the host could also change MX or the Resend DKIM records and send mail as Rincol Tech. Accepted for launch on 22 Oct 2026
   by the Dev Lead under the owner's delegation to decide in his place; the owner can reverse it by buying a separate validation
   domain (runbook, "Decide first", item 10). The fix is not built now; the fix is a separate validation domain that `_acme-challenge` names point to by
   CNAME, with Caddy's `override_domain` and a token for that domain only (runbook, "Decide first"). The backup token cannot change the lock or lifecycle rules, and the lock
   refuses its deletes and overwrites under `daily/` and `monthly/`; with `BACKUP_RETENTION_ON_HOST=false` the host deletes
   nothing. R2 has no put-only permission, so "the host can write but not delete a backup" is this combination, proven on the
   day with a throwaway object. The lock stops the host's token, not everyone: R2 bucket locks have no mode an account
   administrator cannot lift, and the Terraform token can mint tokens, so it is administrator-equivalent. Both are guarded as
   root credentials: few people, two-step sign-in, kept on the maintainer's side, never on the host. `prevent_destroy` does not
   stop an in-place edit of the lock rules, so the retention variables refuse windows under 30 and 365 days.
5. **State.** The token values are in Terraform state, so state never goes in git. Before the first apply it moves to a private
   R2 state bucket in the existing account with its own access key; the host receives its values once, through
   `terraform output -raw host_env`.
6. **The host pulls; nothing pushes** (still to build). The production host runs the staging puller with
   `BMS_POINTER_TAG=production` and `BMS_COMPOSE_SOURCE=compose.yml` once three changes to `pull-staging.sh` are made: the edge
   services it recreates are not hard-wired to `cloudflared` (production has none), the pointer may name a `vX.Y.Z` release and
   not only `sha-<7>`, and the image prune keeps `vX.Y.Z` tags. Until then cloud-init installs the units with the production
   drop-in but leaves the timer off, and releases go out by hand with `deploy.sh` (runbook step 7). It verifies the cosign signatures of the api, web and proxy
   digests against its own `/opt/bms/cosign.pub`, runs migrations before the swap and keeps its automatic rollback. There is no
   deploy SSH key anywhere. The `promote` and `deploy-production` jobs and the version-tag trigger leave `deploy.yml`.
7. **The owner moves the pointer** (still to build). Promotion is a command run on the maintainer's Mac,
   `deploy/signer/bms_promote.py`, reviewed like the signer. It refuses unless the release is signed with `deploy/cosign.pub`,
   its commit is on `main`, that commit is the one live on staging (`/version`) and the release is not older than the current
   `production` pointer; it then waits for the owner to confirm, tags the three images `vX.Y.Z` and moves `production` by digest.
   This replaces the required reviewer of the GitHub Environment. Rollback is a promote of an earlier signed release with an
   explicit flag; the host's downgrade guard is adjusted to accept a pointer move the owner made on purpose.

## Consequences

Better: the server, its firewall, DNS, buckets, backup lock and host tokens are reproducible and reviewable before any cost; the
host's backup token can no longer delete a backup inside its window, and that no longer depends on a person remembering dashboard
steps; no standing credential reaches the production host once decisions 6 and 7 are built; a typo cannot destroy the server or
the backup bucket; the provider choice is one directory, not a rewrite.

Worse: two provider roots to keep in step (the modules hold everything they share); host token values live in Terraform state,
so the state bucket's key is as sensitive as the tokens; a production release needs the owner's Mac (the same dependency the
signer already has); first-day secrets (`.env`, `cosign.pub`, the registry login) are manual steps; a locked backup cannot be
removed early by the host or anyone holding its token, and only an account administrator or the Terraform token can lift the
lock, so an erasure request that must reach backups waits up to a year (ADR-058; an owner decision in the runbook); the DNS-01
token reaches the whole company zone (accepted risk, decision 4).

To watch: the Hetzner backups switch, which has no default because those images hold the whole disk unencrypted outside the
locked backups; whether Cloudflare enforces the token address condition on R2's S3 requests (the runbook checks it and records the answer
here; the lock holds either way); the IPv6 address a Hetzner host uses for outgoing requests, which must fall in the token's allowed network; the
puller's downgrade guard against a deliberate rollback; the cutover rehearsal (apply, deploy a release, back up, run the restore
drill, test the lock) before any tenant's real data moves.
