-- V49: report runs (extras slice D1, issue #214; ADR-038, docs/specs/client-extras.md section 6, chapter 6 `report_runs`).
--
-- Expand only, safe on a populated database. One new table, three small functions the runner uses to see across
-- tenants, and no change to any existing table, constraint or row. No permission is added (the four
-- lending.reports.* permissions of V2 already exist), and no tenant is switched on: reports are off until a tenant
-- administrator sets `reports_enabled` in the tenant settings, which is a key of the existing jsonb document and
-- needs no column.
--
-- Locks, said plainly. The table is created empty. The only locks are the ones CREATE TABLE ... REFERENCES takes on
-- the tables it points at: SHARE ROW EXCLUSIVE on tenants and documents until the commit, which blocks writes to
-- those two tables, but not reads, for the few milliseconds the file takes; no row is scanned and nothing is
-- rewritten. The functions only add catalogue rows. Flyway runs this file in one transaction, so those locks are held
-- to the commit, and a long transaction on tenants or documents would make this wait with every writer queued behind it:
-- the first statement sets lock_timeout to five seconds (as V26 and V48 do), so if a lock cannot be had at once the
-- migration fails and is retried, and the application is never held behind it.
-- Flyway runs with outOfOrder off, so the number is assigned at merge time above the highest one on main.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- report_runs: one row per requested run (FR-RPT-04, FR-RPT-08). The request is a row and the runner is a job: the row
-- carries what the run needs and nothing the caller could use to pick another tenant. `scope` is the branch and officer
-- scope resolved from the requester's permissions when the run was queued, so the job (which has no signed-in user)
-- reads exactly what the requester could see on the insights page. `params` is the normalised request, printed on the
-- output (FR-RPT-02). `params_hash` is its digest, for the dedupe of identical queued or running requests. The caps are
-- the row and file size limits the run was queued under. A file is written once to `documents` (type `report`) and the
-- object is deleted at `expires_at`; the document row stays and a download then answers 410 `report_expired`.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE report_runs (
    id                  uuid        NOT NULL PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenants (id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz,
    report_key          text        NOT NULL CHECK (report_key ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    params              jsonb       NOT NULL,
    params_hash         text        NOT NULL CHECK (params_hash ~ '^[0-9a-f]{64}$'),
    scope               jsonb       NOT NULL,
    format              text        NOT NULL CHECK (format IN ('json', 'csv', 'pdf', 'xlsx', 'pack')),
    status              text        NOT NULL CHECK (status IN ('queued', 'running', 'ready', 'failed', 'cancelled')),
    requested_by        uuid        NOT NULL,
    as_of               date        NOT NULL,
    snapshot_at         timestamptz,
    started_at          timestamptz,
    -- The process that claimed the run ("host/instance") and the last time it said it was alive: a run whose
    -- process died is failed by the next runner tick that sees no heartbeat (a few beats old at a process start).
    owner               text,
    heartbeat_at        timestamptz,
    finished_at         timestamptz,
    cancel_requested_at timestamptz,
    document_id         uuid,
    row_count           integer     CHECK (row_count >= 0),
    size_bytes          bigint      CHECK (size_bytes >= 0),
    row_cap             integer     NOT NULL CHECK (row_cap > 0),
    size_cap_bytes      bigint      NOT NULL CHECK (size_cap_bytes > 0),
    error               text        CHECK (error ~ '^[a-z][a-z0-9_]{2,60}$'),
    expires_at          timestamptz,
    files_deleted_at    timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, document_id) REFERENCES documents (tenant_id, id),
    -- A run that is ready names its file and its expiry; one that failed names why; nothing else carries either.
    CHECK ((status = 'ready') = (document_id IS NOT NULL AND expires_at IS NOT NULL)),
    CHECK ((status = 'failed') = (error IS NOT NULL))
);

-- The requester's list, newest first.
CREATE INDEX report_runs_by_requester ON report_runs (tenant_id, requested_by, created_at DESC);
-- The runner's queue: only open runs, oldest first.
CREATE INDEX report_runs_open ON report_runs (tenant_id, created_at, id) WHERE status IN ('queued', 'running');
-- One run at a time per tenant (ADR-038 decision 7), enforced by the database, not by the claim query alone.
CREATE UNIQUE INDEX report_runs_one_running ON report_runs (tenant_id) WHERE status = 'running';
-- Two identical requests by one user give one run while it is queued or running (FR-RPT-04).
CREATE UNIQUE INDEX report_runs_dedupe ON report_runs (tenant_id, requested_by, params_hash)
    WHERE status IN ('queued', 'running');
-- The nightly expiry finds the files that are due without scanning every run.
CREATE INDEX report_runs_expiry ON report_runs (tenant_id, expires_at) WHERE files_deleted_at IS NULL AND status = 'ready';

SELECT bms_apply_tenant_rls('report_runs');
-- A run is changed as it moves through its states and never deleted: the row is the record that it was asked for.
SELECT bms_grant_app('report_runs', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- What the runner needs to see across tenants (the pattern of app_list_active_tenants_with_module in V2): ids and a
-- count, nothing of any run's content. Each is read only, owned by bms_owner (which bypasses row-level security) and
-- executable by bms_app only.
-- ---------------------------------------------------------------------------------------------

-- Runs executing now, platform wide: the runner's concurrency limit (BMS_REPORTS_MAX_CONCURRENT_RUNS).
CREATE FUNCTION app_count_running_report_runs() RETURNS integer
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT count(*)::integer FROM report_runs r JOIN tenants t ON t.id = r.tenant_id
     WHERE r.status = 'running' AND t.status = 'active'
$$;

-- Active tenants with a queued run and none running (one run per tenant), oldest request first.
CREATE FUNCTION app_tenants_with_queued_report_runs() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT r.tenant_id FROM report_runs r JOIN tenants t ON t.id = r.tenant_id
     WHERE r.status = 'queued' AND t.status = 'active'
       AND NOT EXISTS (SELECT 1 FROM report_runs x WHERE x.tenant_id = r.tenant_id AND x.status = 'running')
     GROUP BY r.tenant_id
     ORDER BY min(r.created_at), r.tenant_id
$$;

-- Active tenants with a run whose process is gone: no heartbeat since the cutoff (a run that never beat is judged by its
-- start). The caller chooses the cutoff: a few beats for a process that has just started (the old one cannot be beating),
-- longer in steady state.
CREATE FUNCTION app_tenants_with_stale_report_runs(p_cutoff timestamptz) RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT DISTINCT r.tenant_id FROM report_runs r JOIN tenants t ON t.id = r.tenant_id
     WHERE r.status = 'running' AND t.status = 'active' AND coalesce(r.heartbeat_at, r.started_at) < p_cutoff
$$;

REVOKE ALL ON FUNCTION app_count_running_report_runs() FROM PUBLIC;
REVOKE ALL ON FUNCTION app_tenants_with_queued_report_runs() FROM PUBLIC;
REVOKE ALL ON FUNCTION app_tenants_with_stale_report_runs(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_count_running_report_runs() TO bms_app;
GRANT EXECUTE ON FUNCTION app_tenants_with_queued_report_runs() TO bms_app;
GRANT EXECUTE ON FUNCTION app_tenants_with_stale_report_runs(timestamptz) TO bms_app;
