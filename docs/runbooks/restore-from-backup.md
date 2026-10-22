# Runbook: restore from backup

**Applies to:** staging and production · **Requirements:** NFR-BAK-01 to NFR-BAK-05 · **Design:** `docs/sdd/09-infrastructure-design.md` section 9.10

Backups are encrypted `pg_dump` custom-format files in
`r2:<R2_BACKUP_BUCKET>/<environment>/daily/` (30 days) and `monthly/` (12 months). Restoring
production is a decision for the dev lead; this runbook is also the quarterly drill
(NFR-BAK-04), which restores onto a fresh VM and is timed against the two hour RTO.

## 1. Get the file

On the host that will be restored (a fresh VM provisioned with
`docs/runbooks/provision-host.md`, with its `.env` holding the same `BACKUP_ENCRYPTION_KEY` and R2
credentials):

```bash
cd /opt/bms
set -a; . ./.env; set +a
rc() { docker run --rm -v "$PWD/backups:/backups" \
  -e RCLONE_CONFIG_R2_TYPE=s3 -e RCLONE_CONFIG_R2_PROVIDER=Cloudflare \
  -e RCLONE_CONFIG_R2_ENDPOINT="$R2_ENDPOINT" -e RCLONE_CONFIG_R2_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
  -e RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" "$RCLONE_IMAGE" "$@"; }
rc ls "r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}/daily/" | sort -k2 | tail -n 3
rc copy "r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}/daily/<file>.dump.enc" /backups/
```

## 2. Decrypt

```bash
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_ENCRYPTION_KEY \
  -in backups/<file>.dump.enc -out backups/restore.dump
```

## 3. Restore

The roles must exist before `pg_restore`, with the same names: the postgres container's first
start creates them (`postgres/initdb/01-roles.sh`). The dump carries the schema, the data, the
Flyway history and every grant to `bms_app`.

**Fresh VM (drill, or the old host is lost).** Start only PostgreSQL, restore into the empty
`bms` database, then deploy:

```bash
# compose.yml names its application images by API_IMAGE, WEB_IMAGE and PROXY_IMAGE (ADR-018
# finding H2); postgres does not need a verified digest to be started or restored into, so a plain
# tag reference is enough here (deploy.sh, in step 4, resolves and verifies them properly).
export IMAGE_TAG=<the tag to run, the one live at backup time or later>
export API_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-api:${IMAGE_TAG}"
export WEB_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-web:${IMAGE_TAG}"
export PROXY_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-proxy:${IMAGE_TAG}"
docker compose --project-name bms -f compose.yml up --detach --wait postgres
docker compose --project-name bms -f compose.yml exec -T postgres \
  pg_restore -U bms_owner -d bms --exit-on-error < backups/restore.dump
```

**Existing host (data damaged, host healthy).** Restore next to the live database, then swap
names while the application is stopped. Nothing is dropped; the damaged copy is kept for
investigation until the dev lead decides otherwise.

```bash
export IMAGE_TAG="$(cat state/current_tag)"
export API_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-api:${IMAGE_TAG}"
export WEB_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-web:${IMAGE_TAG}"
export PROXY_IMAGE="ghcr.io/rincoltech-solutions-ltd/bms-platform-proxy:${IMAGE_TAG}"
C=(docker compose --project-name bms -f compose.yml)
"${C[@]}" exec -T postgres psql -U postgres -d postgres -c "CREATE DATABASE bms_restored OWNER bms_owner"
"${C[@]}" exec -T postgres pg_restore -U bms_owner -d bms_restored --exit-on-error < backups/restore.dump
"${C[@]}" stop api proxy
"${C[@]}" exec -T postgres psql -U postgres -d postgres \
  -c "ALTER DATABASE bms RENAME TO bms_damaged_$(date -u +%Y%m%d)" \
  -c "ALTER DATABASE bms_restored RENAME TO bms" \
  -c "REVOKE ALL ON DATABASE bms FROM PUBLIC" \
  -c "GRANT CONNECT, TEMPORARY ON DATABASE bms TO bms_app"
```

(Database-level grants are not in a `pg_dump` of one database, so the last two lines repeat
what `01-roles.sh` did for the original.)

If a dump is ever restored into a cluster whose roles have other names, reassign ownership to
`bms_owner` and re-run the grants of the migrations before starting the API: the API refuses to
start as an owner, and cannot read tables it has no grant on.

## 4. Verify and start

```bash
./deploy.sh "$IMAGE_TAG"      # migrate is a no-op, or applies migrations newer than the backup
curl -s https://<platform host>/readyz
```

Check, as `bms_owner`: row counts of `tenants`, `lending_members` and `journal_entries` against
the source; the trial balance of one tenant balances (sum of debits equals sum of credits).

## 5. The weekly drill and the backup alert

`deploy/restore-drill.sh` (`bms-restore-drill.timer`, Sunday 03:30 host time, as the deploy user; installed with
`docs/runbooks/monitoring-and-alerts.md` section 2) does steps 1 to 4 for you without touching the live database: it fetches the
newest `daily/` dump, decrypts it, restores it into a throwaway PostgreSQL (no network, 384 MB, inside `bms.slice` on staging; on production the 384 MB and 64 MB container caps apply, and the drill reads the init scripts from `/opt/bms/postgres/initdb`, on staging from `releases/<tag>/postgres/initdb`) and
checks the Flyway history, the row counts and the application role's row-level-security access. It removes the throwaway
database and the decrypted dump whatever the outcome, and records one line, timed from its start:

```
<time> OK <file> seconds=<n> rto_ok=yes flyway=<v> live_flyway=<v> tenants=<n> users=<n> audit=<n> app_visible_users=<n>
<time> FAILED seconds=<n> <reason>
```

in `state/last_restore_drill` or `state/last_restore_drill_error`, appended to `state/restore-drill-history.log`, and uploaded to
`r2:<bucket>/<environment>/drill/last_restore_drill.txt` so the record survives the host. A failed pg_restore is recorded as the fixed text "pg_restore failed, see state/restore-drill.log": the error itself can quote row values, so it stays on the host. Its output is in
`state/restore-drill.log`. Run it by hand with `sudo systemctl start bms-restore-drill.service` after changing the backup key or the
R2 credentials.

`deploy/monitor.sh` alerts in the operator chat when the last drill failed, when no drill succeeded in 9 days, when a drill took
longer than the 2 hour RTO, when `backup.sh` failed, and when the newest backup on the host or in R2 is older than 26 hours. The
external heartbeat (`monitoring-and-alerts.md` section 1) alerts when the host stops reporting at all; it replaces the
workstation watchdog this section used to describe.

The weekly drill proves the data and the grants restore and gives a lower bound on the time. It does not prove the two hour RTO
for a lost host; that stays the quarterly drill onto a fresh VM (sections 1 to 4, recorded as in section 6).

## 6. Clean up and record

```bash
rm -f backups/restore.dump backups/*.dump.enc
```

Record the quarterly drill in `docs/meetings/<date>/restore-drill.md`, with fabricated or no tenant figures (counts only):

```markdown
# Restore drill <date>

- Environment restored: production backup onto a fresh VM (<provider, size, region>)
- Run by: <name> · Signed off by: <Hillary>
- Backup file: bms-production-<stamp>.dump.enc (age at the decision: <h> hours)
- Decision to restore: <HH:MM UTC> · /readyz UP: <HH:MM UTC> · Total: <minutes> (RTO 120)
- Checks: tenants <n>, lending_members <n>, journal_entries <n> match the source: yes/no; one trial balance balances: yes/no
- Problems found and their issues: <none, or #NNN>
```

The two hour RTO is measured from the decision to restore to `/readyz` returning UP. A drill over two hours is a finding: open an
issue before the next release.

## 7. Backups the host cannot delete (Hillary, once per bucket)

Today `backup.sh` deletes old copies itself, so the host's R2 token can delete every backup. These steps move retention into the
bucket and lock the objects, so neither the host nor anyone holding its token can delete or overwrite a backup inside its
retention window (ADR-058). They need a Cloudflare account administrator. For production built with `infra/production/`
(ADR-063), Terraform creates the lock and lifecycle rules and the narrow host token and `host_env` sets
`BACKUP_RETENTION_ON_HOST=false`, so steps 1 to 4 are done there (tick them in the table); run step 5, the lock test, from
`terraform-production.md` step 10, and step 6 as written. For staging they stay manual. Do them on the
staging bucket first, then production. Cloudflare's dashboard labels change from time to time: if a step does not match, follow
the R2 documentation pages "Bucket locks" and "Object lifecycles" and note the difference here.

1. **Lock rules.** Cloudflare dashboard, R2, the backup bucket, Settings, Bucket lock rules, Add rule:
   - name `daily-30d`, prefix `<environment>/daily/`, retention by age, 30 days;
   - name `monthly-365d`, prefix `<environment>/monthly/`, retention by age, 365 days.
   Leave `<environment>/drill/` unlocked: the drill overwrites its result there. With Wrangler (logged in as the administrator):
   ```bash
   npx wrangler r2 bucket lock add <bucket> --name daily-30d --prefix production/daily/ --retention-days 30
   npx wrangler r2 bucket lock add <bucket> --name monthly-365d --prefix production/monthly/ --retention-days 365
   npx wrangler r2 bucket lock list <bucket>
   ```
   (Check the flags with `npx wrangler r2 bucket lock add --help` first.)
2. **Lifecycle rules**, so locked objects still go when their time is up. Settings, Object lifecycle rules, Add rule:
   - `daily-expire`, prefix `<environment>/daily/`, delete objects 31 days after upload;
   - `monthly-expire`, prefix `<environment>/monthly/`, delete objects 366 days after upload.
   The extra day keeps the lifecycle from racing the lock. With Wrangler:
   `npx wrangler r2 bucket lifecycle add <bucket> --name daily-expire --prefix production/daily/ --expire-days 31` and the same
   for monthly with 366.
3. **The host's token.** R2, Manage API tokens: the host's token must be **Object Read & Write**, applied to the backup bucket only,
   never Admin Read & Write (an admin token can change the lock rules). Replace it if it is anything wider, then update
   `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` in `/opt/bms/.env`.
4. **Turn host retention off.** In `/opt/bms/.env` set `BACKUP_RETENTION_ON_HOST=false`. The next `backup.sh` logs
   "retention is the bucket's lifecycle rules; this host deletes nothing".
5. **Prove it.** From the host, upload a throwaway object under `daily/` and try to delete THAT object with the host's own
   credentials; the delete must be refused. Never test against a real backup: if the lock is not on, the delete succeeds.
   ```bash
   cd /opt/bms && set -a && . ./.env && set +a
   rc() { docker run --rm -v /tmp:/tmp -e RCLONE_CONFIG_R2_TYPE=s3 -e RCLONE_CONFIG_R2_PROVIDER=Cloudflare \
     -e RCLONE_CONFIG_R2_ENDPOINT="$R2_ENDPOINT" -e RCLONE_CONFIG_R2_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
     -e RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" -e RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true "$RCLONE_IMAGE" "$@"; }
   echo lock-test > /tmp/lock-test.txt
   rc copyto /tmp/lock-test.txt "r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}/daily/0-lock-test-$(date -u +%Y%m%dT%H%M%SZ).txt"
   rc lsf "r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}/daily/" | grep 0-lock-test      # note the exact name
   rc deletefile "r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}/daily/<the lock-test name>"
   ```
   Expect an access denied or object locked error and the throwaway still listed. If the delete succeeds, the lock is not on: only
   the throwaway was lost, fix step 1 before anything else. The throwaway stays for its 30 days (the lock, once on, holds it); it is
   its name starts with `0-` so it sorts before every `bms-` backup and neither `monitor.sh` nor the drill picks it as the newest.
6. **Protect the account.** Two-step sign-in on every Cloudflare account member, and as few administrators as possible: an
   administrator can still remove a lock rule. Record the date, the rule names and who applied them in this section's table.

| Environment | Bucket | Lock rules applied (date, by) | Lifecycle rules applied | Host token checked | Host retention off | Delete refused |
|---|---|---|---|---|---|---|
| staging | `<bucket>` | not yet | not yet | not yet | not yet | not yet |
| production | `<bucket>` | not yet | not yet | not yet | not yet | not yet |

An erasure request that must reach backups (audit item S11) cannot shorten a lock; the copy ages out at the end of its window.
Record that in the request's reply.
