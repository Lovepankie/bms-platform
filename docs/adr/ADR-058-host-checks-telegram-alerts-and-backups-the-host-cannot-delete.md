# ADR-058: Host checks post to the Telegram operator chat, an external monitor watches the host, and the host cannot delete its backups

## Status

Proposed (issue #425, from the launch readiness audit #416, items M6, M7 and M12). Builds on ADR-018 (the staging host and its
systemd units), ADR-024 (the Telegram operator chat) and ADR-006 (the delivery pipeline). Does not supersede any of them. No
migration. The requirements are NFR-AVL-01, NFR-BAK-01 to NFR-BAK-04, NFR-DP-05 and NFR-OBS-04 (chapter 4); the design is chapter 9
sections 9.10 and 9.11; the runbooks are `docs/runbooks/monitoring-and-alerts.md`, `restore-from-backup.md`,
`security-incident.md` and `support-and-on-call.md`. Renumbered at merge if needed.

## Context

Before this decision nothing told anyone that the site, a scheduled job, the disk, the certificate or the backups had failed.
Chapter 9 named a watchdog on the dev lead's workstation that read R2, but it was never in the repository. The restore drill wrote
one line to a host file nobody read and was never scheduled on the production runbook. Retention ran on the host as
`rclone delete`, so the host's R2 token could delete every daily and monthly backup: a compromised or mistaken host could take the
backups down with the live database. There was no breach response runbook and no support or on-call arrangement.

The company is small: one product owner who is also the dev lead, two developers, no operations team, no budget for a monitoring
service, and a single production VM. The operator chat on Telegram already exists (`BMS_TELEGRAM_BOT_TOKEN`,
`BMS_TELEGRAM_OPERATOR_CHAT_ID`) and the team reads it on their phones.

## Decision

1. **Host checks are a script and a systemd timer, not a monitoring stack.** `deploy/monitor.sh` runs every five minutes from
   `bms-monitor.timer` as the deploy user. It checks the site (`/healthz` and one tenant's sign-in page), db-scheduler executions
   that failed, are stuck without a heartbeat or are overdue, disk above 70 percent, certificates expiring within 14 days, a failed
   or stale backup (26 hours, on the host and in R2 once an hour), and a failed, stale (9 days) or slow (over the 2 hour RTO) restore
   drill. No Prometheus, Grafana or agent: one more service on a 4 GB VM costs more than it buys at one tenant.
2. **One channel: the existing Telegram operator chat**, through the same bot as the API's operator alerts, from the host env file
   only. `site` and `jobs` need two bad runs in a row before the first PROBLEM (staging deploys recreate the containers; the external monitor already waits for three failures). A problem is sent once when it starts, again every six hours while it lasts, and once when it clears. A message that could
   not be sent is not remembered, so the next run tries again, the unit fails so the journal shows it, and that run does not ping the heartbeat. Every probe has a timeout so a wedged Docker daemon still ends in an alert. A job counts as stuck after 30 minutes without a heartbeat: db-scheduler's default heartbeat is 5 minutes and it declares an execution dead at about 20, so 30 stays quiet through an API restart. Messages carry check
   names, hosts and numbers, never personal data.
3. **The outside is watched from outside.** A host that is down cannot report itself, so an external uptime service checks
   `https://<platform host>/healthz` and one tenant sign-in page every minute and alerts after three consecutive failures
   (NFR-OBS-04), and every completed `monitor.sh` run pings a heartbeat on that service, which alerts when the pings stop. The
   provider is Hillary's choice among free tiers that alert to Telegram (the runbook lists what it must do); it sees only public
   URLs.
4. **The restore drill is timed, recorded and scheduled.** `restore-drill.sh` runs weekly from `bms-restore-drill.timer`, records
   its time in seconds against the RTO on the host, in a history file and in R2 under `<environment>/drill/`, and `monitor.sh`
   reports it. The weekly drill restores into a throwaway database, capped at 384 MB (PostgreSQL) and 64 MB (rclone) per container on production as well as staging, reading its init scripts from `postgres/initdb` on production and `releases/<tag>/postgres/initdb` on staging. A failed restore is recorded as a fixed text; the pg_restore error, which can quote row values, stays in the host's log and is never sent to Telegram or uploaded; the quarterly timed restore onto a fresh VM (NFR-BAK-04) stays a
   human drill recorded in `docs/meetings/<date>/`.
5. **The host cannot delete its backups.** R2 bucket lock rules keep every object under `daily/` for 30 days and under `monthly/`
   for 365 days, bucket lifecycle rules delete them afterwards, and `backup.sh` deletes nothing once `BACKUP_RETENTION_ON_HOST=false`.
   The host keeps an object read and write token scoped to the backup bucket, which cannot change bucket settings. Only a person
   with the Cloudflare account can remove a lock rule, and that account is protected by two-step sign-in. Until Hillary applies the
   rules the host still runs retention, so nothing is lost by merging first.
6. **Incidents and support are runbooks with named owners.** `security-incident.md` covers a suspected breach from detection to the
   tenant notice and a PDPO notice draft, with an incident log; `support-and-on-call.md` covers the support channels, hours, the
   rota and how an alert is answered. The names are placeholders until Hillary fills them.

## Consequences

- The alerts of NFR-OBS-04 for health, backup, disk and nightly jobs exist; the worker queue age, the reconciliation difference and
  the outbox and SMS balance alerts (audit item S14) do not yet.
- A five-minute check means a short outage can be seen first by the external monitor; that is by design.
- The checks share the host's fate for everything but the site and the heartbeat; the external monitor covers that gap.
- Locked objects cannot be removed early even on purpose. Removing a backup before 30 days (for example after an erasure request
  that must reach backups) needs a rule change by a Cloudflare account admin, which is recorded in the incident or request log.
- Peak disk during the drill is the encrypted dump plus the decrypted dump plus the throwaway database, roughly three times the dump size in `/opt/bms/backups` and the Docker volume; both are removed at the end. With the 70 percent disk alert, a dump above about a fifth of the free space needs a larger disk first. The drill and history logs are cut to their last 200 lines.
- The workstation watchdog of the old chapter 9 is retired; `monitor.sh` and the external heartbeat replace it.

## Alternatives considered

- **Prometheus, Alertmanager and node exporter on the VM.** Rejected for now: memory and upkeep on a single 4 GB VM for six checks.
  Revisit with a second host or a second tenant tier.
- **Alerts through the API's outbox.** Rejected: an alert about the API or the database must not depend on them.
- **A put-only token instead of bucket locks.** R2 tokens are read, or read and write; there is no put-only object permission, and
  rclone needs to list. The lock gives the same guarantee for the retention window.
- **An append-only restic or Borg remote.** A sound alternative on another provider, but it adds a second store and a second key to
  look after; R2 is already where the backups are.
