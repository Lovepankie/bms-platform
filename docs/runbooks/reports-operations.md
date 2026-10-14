# Report runs: operating them

For ADR-038 and issue #214 (extras slice D1). Design: `docs/specs/client-extras.md` section 6; the data in chapter 6
(`report_runs`); the routes in chapter 7 section 7.11.8. Read this when a report is stuck, slow or refused, when a limit needs
changing, or when files fill the disk.

## What runs, and where

A report run is a row in `report_runs` and a job. `POST /report-runs` queues the row; the job `core.reporting.report-runner`
(every 15 seconds, inside the API) claims the oldest queued run of a tenant that has none running, so at most one run per tenant
and at most `BMS_REPORTS_MAX_CONCURRENT_RUNS` across the platform. The run then:

1. reads in one read-only `REPEATABLE READ` transaction and writes its rows to a spool file on disk, then **commits** (the
   snapshot and the old row versions it pinned are released here);
2. renders the JSON or CSV file from the spool, stopping at the size cap;
3. writes the object to storage, and in one short transaction records the document and marks the run `ready`;
4. removes the spool directory, whatever happened.

The spool is `BMS_REPORTS_SPOOL_DIR`, or `bms-report-spool` in the container's temp directory when unset: one folder per run, named
`<tenant id>-<run id>`, removed when the run ends. The API removes any folder left behind when it starts. On the staging Pi the temp
directory is the container's own writable layer, so a run's spool counts against the host's disk while it runs (the largest staging
table, 20,000 rows, is a few megabytes).

A file is kept 30 days. The nightly job `core.reporting.report-expiry` (03:30 Kampala time) deletes the stored object of every ready run
past `expires_at` and sets `files_deleted_at`. The `documents` row and the run row stay; a download then answers 410
`report_expired`.

## Switching it on for a tenant

Report runs are off for every tenant until its administrator turns them on: the "Reports" box in Business set-up (a user with
`core.settings.manage`), or `PATCH /api/v1/settings` with `{"reports_enabled": true}` and the current `If-Match`. The change is
audited (`core.settings.updated`, with the old and new value). Turning it off again hides the Reports tab (`GET /me` says `reports_enabled: false`), makes the catalogue and every run route answer
404 `reports_not_enabled`, stops every file download (404), and fails any run still queued with `reports_disabled`; files already made stay until they expire.

## The limits

| Setting | Production | Staging (Pi, 224 MB heap) | Effect |
|---|---|---|---|
| `BMS_REPORTS_MAX_CONCURRENT_RUNS` | 2 | 1 | Runs executing at once, platform wide |
| `BMS_REPORTS_XLSX_ROW_CAP` | 200,000 | 20,000 | Rows in the largest table of one output (Excel sheet, CSV, JSON); over it: `report_too_large` |
| `BMS_REPORTS_PDF_ROW_CAP` | 3,000 | 500 | Rows of one table in a PDF (read from slice D2) |
| `BMS_REPORTS_MAX_FILE_BYTES` | 64 MiB | 8 MiB | Bytes of one output file; over it: `report_too_large` |
| timeout | 10 minutes | 10 minutes | Over it: `timeout` (property `bms.reports.timeout`, ISO 8601) |
| retention | 30 days | 30 days | Property `bms.reports.retention-days` |

They are plain environment variables of the API container (`deploy/compose.yml`, `deploy/compose.pi-staging.yml`, `.env.example`);
change one in `/opt/bms/.env` and restart the API. A run records the caps it was queued under and applies the smaller of those and
the limits in force when it runs, so lowering a cap protects a host at once, including runs already queued; raising one applies to
runs queued after. Tune from measurements (run time and file size in `report_runs`), not by guessing. The staging numbers exist because
the API container has a 448 MB limit and about 224 MB of heap; `ReportMemoryIT` runs the reports in a child JVM at that heap, and must
pass before any change that makes a run hold more in memory.

## A run is stuck, slow or failed

Look from the database as `bms_owner` (it sees every tenant; the application's role sees one tenant at a time):

```sql
-- What is open, oldest first, and how long it has been going.
SELECT id, tenant_id, report_key, format, status, created_at, started_at, now() - coalesce(started_at, created_at) AS age
  FROM report_runs WHERE status IN ('queued', 'running') ORDER BY created_at;

-- What failed lately, by code.
SELECT error, count(*), max(finished_at) FROM report_runs
 WHERE status = 'failed' AND finished_at > now() - interval '7 days' GROUP BY error ORDER BY 2 DESC;
```

| Error code on the run | Meaning | What to do |
|---|---|---|
| `report_too_large` | A table passed the row cap or the file passed the byte cap | Ask for fewer branches or a shorter range; raise the cap only if the host can hold it |
| `snapshot_unavailable` | A past date has no nightly snapshot | The snapshot job (`lending.insights-snapshot`) has not written that date; ask for a later date, or check the job |
| `timeout` | The run passed 10 minutes | Narrow the report; for a big book measure the query (`EXPLAIN` it as the owner) before raising the timeout |
| `interrupted` | The process holding the run died or was stopped (a restart, a kill, out of memory) | Run it again. The runner fails any run whose heartbeat is a minute old, or which an earlier process of the host left |
| `reports_disabled` | The tenant switched reports off before the run started | Switch them on and ask again |
| `requester_deactivated` | The user was deactivated before the run started | None needed |
| `run_failed` | An unexpected error | Find the line `report run <id> failed:` in the API log: it names the exception classes, the first application frame and, for a database error, the SQLSTATE, never a message or a value. Fix the cause; the user can ask again |

Every run is stamped with the process that holds it (`report_runs.owner`, `host/instance`) and beaten every 10 seconds
(`heartbeat_at`). What happens on a deploy, exactly: the compose files give the api container `stop_grace_period: 30s`; on the stop
the runner refuses new claims, interrupts the workers, waits up to 15 seconds for them to close their runs as `interrupted` (each is
audited as `core.reports.run_failed`), and fails and audits any run still open itself. If the process is killed instead (an out of memory
error, a power cut) nothing is closed, and the runs are judged by their heartbeat: a recreated container has a new host name, so who
owned a run does not matter, only whether it still beats. The first runner tick of a new process fails every running run whose last beat
is more than two beats (20 seconds) old, since whatever was beating before the process started is dead (a startup takes about a minute on
the staging Pi, so the old runs are well past it); from then on a run is failed when its beat is a minute old, so a live run of another
instance is never touched. Either way the single staging place is free again within about a minute of the new process starting. The
properties are `bms.reports.heartbeat` (PT10S) and `bms.reports.heartbeat-stale` (PT1M). To stop a healthy run, the requester cancels it in the Reports page (or `POST /report-runs/{id}/cancel`); the worker notices at
its next checkpoint, within a few seconds. An operator who must end one now can do it as the owner:

```sql
UPDATE report_runs SET status = 'cancelled', finished_at = now() WHERE id = :id AND status IN ('queued', 'running');
```

and the worker, finding its row no longer `running`, stops at its next checkpoint without storing a file.

To stop all report work at once: set `BMS_SCHEDULER_ENABLED=false` (stops every job in that instance) or switch `reports_enabled` off for
the tenants concerned. Neither touches a request.

A failed run never holds data: it keeps an error code and no file. The only thing a failure can leave is a spool folder, which the run's
own clean-up removes and a restart sweeps.

## Storage and files

Files live in object storage under `tenants/<tenant id>/report/<year>/<month>/<id>.json` or `.csv`, as documents of type `report`
whose subject is the run (`core.report_run`). They contain figures and, for reports that list members, names: they are fetched only
through the 5 minute signed URL of `POST /documents/{id}/download-url`, by the requester, and each issue is audited. If storage fills,
reduce `bms.reports.retention-days` and let the nightly job catch up (2,000 runs per tenant per night, in batches of 50, storage called with no transaction open), or delete objects by key for
`documents` rows of type `report` older than the retention; leave the rows.

## What to watch (chapter 9 section 9.11)

Run time and failures by code (above), the age of the oldest queued run (the runner takes a run every 15 seconds when a place is free,
so a queue older than a minute with nothing running means the runner is not ticking: look at `scheduled_tasks` for
`core.reporting.report-runner` and its `consecutive_failures`), the age of the oldest open transaction during a run
(`pg_stat_activity`, `xact_start`), and storage used by documents of type `report`.
