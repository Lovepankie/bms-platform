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

`deploy/restore-drill.sh` (cron, Sunday 03:30 host time, as the deploy user) does steps 1 to 4 for
you without touching the live database: it fetches the newest `daily/` dump, decrypts it, restores it
into a throwaway PostgreSQL (no network, 384 MB, inside `bms.slice` on staging) and checks the Flyway
history, the row counts and the application role's row-level-security access. It writes one line to
`state/last_restore_drill` (`<time> OK <file> flyway=<v> live_flyway=<v> tenants=<n> users=<n> ...`) or
`state/last_restore_drill_error`, and removes the throwaway database and the decrypted dump. Run it by
hand with `sudo -u bms /opt/bms/restore-drill.sh` after changing the backup key or the R2 credentials.

It proves the data and the grants restore. It does not time a full restore onto a fresh VM against the
two hour RTO; that stays the quarterly drill below.

The backup alert is a separate watchdog on the dev lead's workstation (not on the host, so it also fires
when the host is down). It checks R2 every hour and sends a message when the newest daily backup is
older than 26 hours or under half the recent median size, when the last drill failed, or when no drill
succeeded in 9 days.

## 6. Clean up and record

```bash
rm -f backups/restore.dump backups/*.dump.enc
```

Record the drill in `docs/meetings/` (date, backup file, time from decision to serving, issues
found). The two hour RTO is measured from the decision to restore to `/readyz` returning UP.
