# Runbook: monitoring and alerts

**Applies to:** staging and production · **Requirements:** NFR-AVL-01, NFR-BAK-01, NFR-BAK-04, NFR-OBS-04 · **Design:**
`docs/sdd/09-infrastructure-design.md` section 9.11 · **Decision:** ADR-058 · **Issue:** #425

Two layers, both posting to the Telegram operator chat the API already uses (`BMS_TELEGRAM_BOT_TOKEN`,
`BMS_TELEGRAM_OPERATOR_CHAT_ID` in `/opt/bms/.env`, never in git):

| Layer | Runs where | Catches |
|---|---|---|
| External uptime monitor | A hosted service, outside our hosts | The site or a tenant page down for 3 minutes, the host silent (no heartbeat) |
| `deploy/monitor.sh` every 5 minutes (`bms-monitor.timer`) | On the host, as the deploy user | Site down, failed, stuck or overdue jobs, disk over 70 percent, certificate expiry within 14 days, failed or stale backups, failed, stale or slow restore drill |
| `deploy/restore-drill.sh` weekly (`bms-restore-drill.timer`) | On the host | Produces the drill result that `monitor.sh` reports |

Who reads the chat and who is paged out of hours is in `docs/runbooks/support-and-on-call.md`.

## 1. The external uptime monitor (Hillary sets it up once per environment)

The provider is Hillary's choice; any free tier that does all of the following is enough:

- HTTP checks every **1 minute** from at least two regions, alerting after **3 consecutive failures** (NFR-OBS-04):
  - `https://bms.rincoltech.com/healthz`, expect status 200;
  - `https://<pilot slug>-bms.rincoltech.com/`, expect status 200 (the tenant sign-in page).
  - Staging: the same two under `bms-staging.rincoltech.com` and `<slug>-bms-staging.rincoltech.com`, at a 5 minute interval if
    the free tier is short of checks.
- A **heartbeat** (also called a cron or dead man's switch check) that expects a ping every 5 minutes with a 15 minute grace, one
  per environment. Its URL goes in `/opt/bms/.env` as `MONITOR_HEARTBEAT_URL`; `monitor.sh` pings it at the end of every run.
- Alerts to **Telegram** (the operator chat) and to the on-call person's **email**, and one message on recovery.
- A certificate expiry warning on the same checks, if the provider has one (a second opinion to `monitor.sh`).

It sees only public URLs; it needs no credential of ours. Record the provider, the account owner and the check names in the
support runbook's contacts table. Test it: stop the proxy on staging for four minutes
(`docker compose --project-name bms -f /opt/bms/compose.yml stop proxy`, then `start proxy`) and confirm the problem and the
recovery arrive.

## 2. Install the host checks and the drill timer

The files arrive with each release: on staging the puller refreshes `/opt/bms/monitor.sh`, `restore-drill.sh` and
`/opt/bms/systemd/`; on production the tag deploy copies them to `/opt/bms/`. The units are installed by hand once, as root:

```bash
cd /opt/bms
install -m 644 systemd/bms-monitor.service systemd/bms-monitor.timer \
  systemd/bms-restore-drill.service systemd/bms-restore-drill.timer /etc/systemd/system/
# Production built by hand (provision-host.md) only: the deploy user is `deploy`, not `bms`. A host built by
# infra/production already has these units from cloud-init, running as bms (terraform-production.md step 11).
for u in bms-monitor bms-restore-drill; do
  mkdir -p /etc/systemd/system/$u.service.d
  printf '[Service]\nUser=deploy\nGroup=deploy\n' > /etc/systemd/system/$u.service.d/user.conf
done
systemctl daemon-reload
systemctl enable --now bms-monitor.timer bms-restore-drill.timer
systemctl list-timers 'bms-*'
```

On production, remove the old drill line from the deploy user's crontab if one was added by hand; keep the `backup.sh` cron line.

Add to `/opt/bms/.env` (all optional except the Telegram pair):

```bash
MONITOR_TENANT_SLUG=<the pilot tenant's slug>
MONITOR_HEARTBEAT_URL=<from section 1>
# Defaults, change only with a reason: MONITOR_DISK_LIMIT=70 MONITOR_DISK_PATHS="/ /opt/bms" MONITOR_TLS_DAYS=14
# MONITOR_BACKUP_HOURS=26 MONITOR_DRILL_DAYS=9 MONITOR_REPEAT_HOURS=6 MONITOR_STUCK_MINUTES=15 MONITOR_OVERDUE_MINUTES=30
```

### Test that alerts arrive

```bash
sudo systemctl start bms-monitor.service && journalctl -u bms-monitor.service -n 20 --no-pager
# Force one alert: a disk limit nobody meets, then put it back.
sudo -u bms env MONITOR_DISK_LIMIT=1 /opt/bms/monitor.sh      # production: sudo -u deploy
sudo -u bms /opt/bms/monitor.sh                                # the RESOLVED message follows
```

Expect `[BMS staging] PROBLEM disk: / at <n> percent (limit 1)` in the operator chat, then `RESOLVED disk`. A brand-new host
reports `backup` and `drill` problems until the first backup and the first drill have run; run both once by hand
(`sudo -u bms /opt/bms/backup.sh`, `sudo systemctl start bms-restore-drill.service`).

## 3. What each alert means and what to do

Every message starts `[BMS <environment>] PROBLEM <check>:`, then `STILL FAILING` every 6 hours while it lasts, then `RESOLVED`.
State lives in `/opt/bms/state/alerts/<check>` (the first-seen and last-sent times); deleting a file makes the next run send the
problem again.

| Check | Meaning | First steps |
|---|---|---|
| `site` | `/healthz` or the tenant sign-in page did not answer 200 from the host itself, on two runs in a row (a staging deploy recreates the containers every two minutes at most) | `docker compose --project-name bms -f /opt/bms/compose.yml ps`; `logs --tail 200 api proxy`; the checks in `docs/runbooks/deploy.md`. A bad release: `docs/runbooks/rollback.md` |
| `jobs` | On two runs in a row, a db-scheduler task has `consecutive_failures > 0` (`failed_<n>_times`), is picked with no heartbeat for 30 minutes (`stuck`; db-scheduler beats every 5 minutes and declares an execution dead at about 20, so an API restart does not alert), or was due 30 minutes ago and not picked (`overdue`, usually the API is down or its scheduler stopped) | `docker compose ... logs api \| grep -i '<task name>'`; a tenant job names the failing tenant in its log line. `overdue` on every task: check `site` first |
| `disk` | A path is over 70 percent | `df -h`; `docker system df`; old images (`docker image prune` keeps the running ones); `state/*.log` sizes; the database volume. Do not delete anything under the postgres volume |
| `tls` | A certificate expires within 14 days, or none could be read | Production: `logs proxy \| grep -i acme`; the usual cause is the Cloudflare DNS token (`CLOUDFLARE_API_TOKEN`, Zone.DNS:Edit) revoked or expired. Staging: the Cloudflare edge certificate, check the zone's SSL page |
| `backup` | The last `backup.sh` failed (`state/last_backup_error`), no good backup in 26 hours, or the newest object in R2 is older than 26 hours | `tail -50 /opt/bms/state/backup.log`; run `backup.sh` by hand; R2 credentials and the bucket lock (`restore-from-backup.md` section 7) |
| `drill` | The last drill failed, none succeeded in 9 days, or it took longer than the 2 hour RTO | `tail -80 /opt/bms/state/restore-drill.log` (the pg_restore error text is only there, never in Telegram or R2); `state/restore-drill-history.log`; both are cut to their last 200 lines on each run; `restore-from-backup.md` section 5 |

A run that could not send an alert does not ping the heartbeat, so the external monitor also notices a host that cannot reach Telegram. Every probe has a timeout (20 s for TLS, 30 s for the database, 60 s for R2), so a wedged Docker daemon still ends in an alert.

If the external monitor alerts and `monitor.sh` is silent, the host itself is down or cannot reach Telegram: reach the VM
through the provider's console.

## 4. Pausing an alert

For planned work, stop the timer and say so in the operator chat: `sudo systemctl stop bms-monitor.timer`, and pause the external
checks in the provider. Start both again when done (`sudo systemctl start bms-monitor.timer`). Never leave a timer stopped
overnight.

## 5. What is not covered yet

- The worker queue age, the reconciliation difference, the outbox failures and the SMS balance (audit item S14).
- Log shipping and an error tracker (chapter 9, open item).
- Per-request latency against the performance targets.

## 6. Tests

`bash deploy/tests/monitor-test.sh` runs every check against stubs (no network, no Docker), including the repeat and recovery
rules and the retry of a message that could not be sent. CI runs it and shellcheck on every pull request.
