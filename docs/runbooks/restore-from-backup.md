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

**Before you start the API**, set `BMS_DOCUMENTS_ORPHAN_SWEEP_DRY_RUN=true` in `/opt/bms/.env`. A restore to an earlier
point leaves objects in the documents bucket that were uploaded after the backup and have no row now; the nightly orphan
sweep (issue #39, chapter 12 section 12.5) would delete them for good, and they may be needed to reconcile. Keep the dry
run until those uploads are dealt with, then set it back to `false`.

```bash
./deploy.sh "$IMAGE_TAG"      # migrate is a no-op, or applies migrations newer than the backup
curl -s https://<platform host>/readyz
```

Check, as `bms_owner`: row counts of `tenants`, `lending_members` and `journal_entries` against
the source; the trial balance of one tenant balances (sum of debits equals sum of credits).

## 5. The weekly drill and the backup alert

### 5.1 The weekly drill

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

### 5.2 Rehearsing an import on last night's backup (`--rehearse-import`)

Before a cutover the operator rehearses the customer's import run against last night's backup (spec
`docs/specs/customer-data-onboarding.md` section 5.5, ADR-027 decision 9, issue #138; the cutover runbook
`docs/runbooks/customer-data-cutover.md` section 2.4 says when). The same script does it, only when asked:

```
cd /opt/bms && ./restore-drill.sh --rehearse-import <run id>
```

Run it by hand as the user that runs the drill (`bms` on staging, `deploy` on production), never from the timer or
cron: those run the script with no argument, which is the weekly drill above, unchanged. What it does, in order:

1. Reads the run on the live database as `bms_owner`, read only: it must be a run of the import screens (origin
   `framework`), in review or ready, and on staging marked fabricated by its uploader (`import_runs.fabricated`).
2. Runs alone: it takes `state/rehearsal.lock` and the puller's and the deploy's locks (`state/pull.lock`,
   `state/deploy.lock`) and holds them to the end, so no deploy starts beside it (the puller just ends its run and the
   next one deploys); it refuses while a deploy, the puller, the weekly drill (by its process or container) or another
   rehearsal runs; it starts nothing from Saturday 21:30 to Sunday 07:00 host time, and asks again before the
   migrations and the commit. Its deadline, logged at the start, is six hours after the start, or the next Sunday 03:20
   host time (ten minutes before the drill) if that comes first, so it never keeps a deploy waiting more than six
   hours. A watchdog enforces it on every step, a hung one included: at the deadline it removes the rehearsal's
   containers, ends the steps still running and every session the rehearsal opened on the live database, and the
   rehearsal fails (exit 1) with nothing stored and its locks released. Each live session is named
   `bms-rehearsal-<pid>` in `pg_stat_activity` (through `PGAPPNAME`: psql would override a name given in
   `PGOPTIONS`), has its own statement and idle-in-transaction timeouts on the server, and runs under
   `timeout -s KILL` inside the live container, so it ends even if its `docker exec` client is gone. The watchdog ends
   only sessions with that name, of `bms_owner`, `bms_app` or `postgres`, opened since the rehearsal started. If a
   stop (the deadline, Ctrl-C or TERM) comes as the row is being stored, the script reads the row back from the live
   database and says whether it was stored; if that read fails it says the outcome is unknown and prints the query to
   check it with. No commit starts with less than 30 minutes left.
   A rehearsal that needs longer is a sign to split the run's files. On staging it starts nothing
   unless `bms.slice` has 704 MB free below `MemoryHigh` (in use counted as `memory.current` less inactive page cache),
   and 448 MB again before the API container.
3. Fetches and decrypts the newest backup, as the drill does, into a mode-0700 temp dir `backups/rehearsal.XXXXXX`; the
   decrypted dump is deleted as soon as it is restored.
4. Starts a throwaway PostgreSQL (`bms-rehearsal-db-<pid>`, 256 MB, 1 CPU, inside `bms.slice` on staging) on an internal
   Docker network of its own (`bms-rehearsal-net-<pid>`, no route out, the live database not on it) with a volume of its
   own, restores into it, and refuses to go on unless it is provably not the live database: another name, another
   container, another cluster (`pg_control_system()`'s identifier), this rehearsal's label, this network only. It asks
   again before the commit.
5. Migrates it with the live API's own image, refuses if its Flyway version is then not the live one, and copies the
   run's staged rows from the live database in one `REPEATABLE READ READ ONLY` transaction: the run, its batches, rows,
   issues, resolutions and the mappings its batches name. The throwaway side loads them in one transaction that commits
   only if the live read ended well, and then every table's count and ids are compared, live against throwaway, before
   anything else runs. The encrypted source comes only as its object keys and the run's wrapped data key: the commit
   reads the staged rows, so the source is never fetched or decrypted. In the same transaction it marks the throwaway
   database with a one-time token, the only thing that lets the application commit without the checker and the
   pre-import dump. No key, password or token is ever on a command line: they go in env files in the temp dir, and the
   container's label is the token's SHA-256.
6. Runs `rehearse-import` in a short-lived API container (`bms-rehearsal-api-<pid>`, 448 MB, 1 CPU, the staging api's
   224 MB heap) with the live API's image and settings, the database switched to the throwaway one, the scheduler and
   the clean-ups off, no import key, every key of an outside service (object storage, mail, SMS, payments, push, AI,
   chat) replaced by a placeholder (the data and signing keys stay: the application needs them to start), on the
   internal network only. A placeholder has the shape its setting is checked for at start, and its address is on
   `.invalid`, which no resolver answers (the storage endpoint is `http://rehearsal-storage.invalid`, issue #889); a
   value empty on the live API stays empty, so a service off there stays off; push is off. The Resend and AI URLs are
   always set to `.invalid`, since empty means the provider's real host. Base URLs the startup guards hold to the
   provider's own host (SMS, payments, Ask BMS) are kept, with placeholder keys, and rely on the internal network,
   which `rehearse-import-test.sh` checks. CI starts the application with exactly these settings
   (`RehearsalSettingsIT`). It moves the run back to review there, takes the dry run again on the restored state and
   this build, makes the run ready and commits it with the real commit.
7. Stores the rehearsal on the live run: one `import_rehearsals` row, inserted as `bms_app` under the business's
   row-level security. It is the only write the rehearsal makes on the live database.
8. Removes the containers, the network, the volume and the temp dir on success, on failure, on Ctrl-C (INT) and on
   TERM, and says so in its last line. The restore, the migrations and the commit run in the background while the
   script waits, so a Ctrl-C or a stop removes their container at once rather than after they end. A KILL (or a power
   loss) leaves them behind: see below.

Exit status: 0 rehearsed and stored; 1 failed (a commit that ran and failed or halted is stored with its failure's code;
anything earlier stores nothing, and so does an API container that exits 1 without starting: the script then says
"without recording an outcome", or "did not say whether it recorded an outcome" when the throwaway database does not
answer); 2 usage; 3 refused (the run, the target or the room). It writes none of the drill's
state files, so `monitor.sh` never reads a rehearsal as a drill. The output (the report, codes, counts and amounts) has
no personal value; keep it with the cutover notes.

Read the stored rehearsal:

```
docker exec bms-postgres-1 psql -U bms_owner -d bms -c "SELECT created_at, backup, outcome, failure, matches_dry_run, timings
  FROM import_rehearsals WHERE run_id = '<run id>' ORDER BY created_at DESC LIMIT 1"
```

`timings` holds milliseconds per step: `download_ms`, `restore_ms`, `migrate_ms`, `copy_ms` on the host, then `start_ms`,
`dry_run_ms` and `commit_ms` (the commit includes the reconciliation) in the API. Plan the cutover window from
`dry_run_ms` and `commit_ms`. `matches_dry_run` says whether the commit's report is the run's stored dry run's, apart
from its mode; journal entry numbers differ when the business posted a journal after last night's backup.

If the host loses power during a rehearsal, remove what is left (names all start `bms-rehearsal-`):

```
docker rm -f -v $(docker ps -aq --filter name=bms-rehearsal-) 2> /dev/null
docker volume rm $(docker volume ls -q --filter name=bms-rehearsal-) 2> /dev/null
docker network rm $(docker network ls -q --filter name=bms-rehearsal-) 2> /dev/null
rm -rf /opt/bms/backups/rehearsal.*
```

#### 5.2.1 Acceptance check on staging (issue #138, run by the Lead after the merge)

On the Pi, as `bms`, from `/opt/bms`, with nobody using staging meanwhile, not between Saturday 21:30 and Sunday 07:00.

The run to rehearse. Until the operator screens (#141) can upload one, `stage-import-run` stages it: the fabricated
`fixtures/retail/import-sample/` of this repository as a run of the import screens, marked fabricated, reviewed, dry run
on live staging and ready. It needs a staging business with retail switched on that has not traded (a new one,
`docs/runbooks/onboard-tenant.md`), whose slug is `<slug>`, and two more things (issue #885):

- **A plan with room for the sample's three branches**, for example `business`, not Starter (one shop). On a plan
  without room, the plan limit refuses the branches' batch and `stage-import-run` answers
  `refused: dry_run_failed: batch N failed (plan_limit_reached: the plan's limit refused this batch; move the business
  to a plan with room)`. Move the business with the
  operator's `PUT .../plan` (`docs/runbooks/onboard-tenant.md`) and stage again: the refused run stays in review and
  blocks nothing; rehearse only the new run id the second staging prints, never the refused one.
- **The business must be in last night's backup**: create it at least one nightly backup before the check. The
  rehearsal refuses a business created today (`business <slug> is not in <backup>; rehearse after the next nightly
  backup`).

It runs only where `BMS_ENVIRONMENT` is exactly `staging`
(unset, blank or anything else is refused) and stages only that sample, every file present and byte for byte the
SHA-256 the code holds, so no real export can be marked fabricated. `docker compose run` starts it from the `api`
service, so it gets the staging api's limits (448 MB inside `bms.slice`); run it, like the rehearsal, when no deploy runs.

```
# From a checkout of the repository, on your machine: the fabricated sample onto the Pi.
scp -r fixtures/retail/import-sample <pi>:/opt/bms/import/fab-sample
```

```
cd /opt/bms
docker compose --project-name bms -f compose.yml run --rm --no-deps -v /opt/bms/import/fab-sample:/import:ro \
  -e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -XX:TieredStopAtLevel=1' \
  api stage-import-run --tenant <slug> --dir /import
# prints: staged fabricated import run <run id> (ready) for business <slug>
RUN=<run id>
# Live staging before: the row count of every table but import_rehearsals, the whole Flyway history, and the run itself
# by content: its row, the md5 of each of its tables' rows, and its audit trail.
snapshot() {
  docker exec -i bms-postgres-1 psql -U bms_owner -d bms -tA <<SQL
SELECT format('SELECT %L || ''|'' || count(*) FROM %I', relname, relname) FROM pg_class
 WHERE relkind = 'r' AND relnamespace = 'public'::regnamespace AND relname <> 'import_rehearsals' ORDER BY relname
\gexec
SELECT 'flyway|' || installed_rank || '|' || version || '|' || checksum || '|' || success FROM flyway_schema_history ORDER BY installed_rank;
SELECT 'run|' || status || '|' || version || '|' || md5(r::text) FROM import_runs r WHERE id = '$RUN';
SELECT 'batches|' || count(*) || '|' || coalesce(md5(string_agg(b::text, '|' ORDER BY id)), '-') FROM import_batches b WHERE run_id = '$RUN';
SELECT 'rows|' || count(*) || '|' || coalesce(md5(string_agg(w::text, '|' ORDER BY id)), '-') FROM import_rows w
 WHERE batch_id IN (SELECT id FROM import_batches WHERE run_id = '$RUN');
SELECT 'issues|' || count(*) || '|' || coalesce(md5(string_agg(i::text, '|' ORDER BY id)), '-') FROM import_issues i
 WHERE batch_id IN (SELECT id FROM import_batches WHERE run_id = '$RUN');
SELECT 'resolutions|' || count(*) || '|' || coalesce(md5(string_agg(s::text, '|' ORDER BY id)), '-') FROM import_issue_resolutions s WHERE run_id = '$RUN';
SELECT 'audit|' || count(*) || '|' || coalesce(max(created_at)::text, '-') FROM audit_log WHERE entity_type = 'import_run' AND entity_id = '$RUN';
SQL
}
snapshot > state/rehearsal-check-before.txt
docker exec bms-postgres-1 psql -U bms_owner -d bms -tAc "SELECT count(*) FROM import_rehearsals WHERE run_id = '$RUN'"

# 1. The rehearsal. Watch it fit in the slice from a second terminal:
#    watch -n 5 "cat /sys/fs/cgroup/bms.slice/memory.current; docker stats --no-stream --format '{{.Name}} {{.MemUsage}}'"
./restore-drill.sh --rehearse-import "$RUN" 2>&1 | tee "state/rehearsal-$RUN.log"; echo "exit ${PIPESTATUS[0]}"   # exit 0

# 2. The live database is untouched: no difference at all (counts, Flyway, the run's status, version and content, its
#    rows, issues and audit trail), and one more import_rehearsals row.
snapshot > state/rehearsal-check-after.txt
diff state/rehearsal-check-before.txt state/rehearsal-check-after.txt && echo "live database untouched"
docker exec bms-postgres-1 psql -U bms_owner -d bms -tAc "SELECT count(*) FROM import_rehearsals WHERE run_id = '$RUN'"

# 3. The rehearsal's report is the dry run's on live staging, apart from its mode: t|t|committed
docker exec bms-postgres-1 psql -U bms_owner -d bms -tAc "SELECT h.matches_dry_run, (h.report - 'mode') = (r.dry_run_report - 'mode'), h.outcome
  FROM import_rehearsals h JOIN import_runs r ON r.id = h.run_id WHERE h.run_id = '$RUN' ORDER BY h.created_at DESC LIMIT 1"

# 4. Nothing is left: four zeros.
docker ps -aq --filter name=bms-rehearsal- | wc -l; docker network ls -q --filter name=bms-rehearsal- | wc -l
docker volume ls -q --filter name=bms-rehearsal- | wc -l; ls -d backups/rehearsal.* 2> /dev/null | wc -l

# 5. A refusal exits 3 and starts nothing: a run that is not there (staging has no run without the fabricated mark to
#    try; the tests refuse one). The log has no "starting a throwaway postgres", and nothing named bms-rehearsal- exists.
./restore-drill.sh --rehearse-import 00000000-0000-4000-8000-000000000000 2>&1 | tee state/rehearsal-refused.log
echo "exit ${PIPESTATUS[0]}"   # exit 3
grep -c "starting a throwaway" state/rehearsal-refused.log   # 0
docker ps -aq --filter name=bms-rehearsal- | wc -l; docker network ls -q --filter name=bms-rehearsal- | wc -l   # 0 0
# And while a deploy holds its lock, the rehearsal is refused too:
flock state/deploy.lock ./restore-drill.sh --rehearse-import "$RUN"; echo "exit $?"   # exit 3, "a deploy is running"

# 6. The weekly drill still runs as before, with no argument, as cron runs it.
./restore-drill.sh >> state/restore-drill.log 2>&1; echo "exit $?"; cat state/last_restore_drill
```

The live API, `scheduled_tasks` and `audit_log` keep working during the check; a row count that moved in step 2 is
explained by what the stack did meanwhile (a sign-in, a scheduled job) or the check is repeated when staging is quiet.

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
