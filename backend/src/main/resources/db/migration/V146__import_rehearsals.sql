-- V146: the rehearsal of customer data onboarding (issue #138, ADR-027 decision 9, spec docs/specs/customer-data-onboarding.md
-- section 5.5, chapter 6 section 6.9). Directly after V145 (#135); Flyway runs with outOfOrder off, so this is renumbered at
-- merge time above the highest number on main if that moves.
--
-- Expand only, safe on a populated database while the previous release serves (it reads neither the column nor the table):
--
--   1. import_runs gains `fabricated`, false for every run before this one: the uploader's word, given when the run opens,
--      that its files hold fabricated data only. On staging the rehearsal (deploy/restore-drill.sh --rehearse-import) takes
--      only such a run. It never changes after the run opens: the trigger import_runs_fabricated_fixed refuses it.
--   2. import_rehearsals: one row per rehearsal of a run on last night's backup. The rehearse-import command writes it in the
--      throwaway database, and the script copies it onto the live run as bms_app, under the business's row-level security:
--      the restored backup, the build that committed, the outcome, the commit report, the reconciliation, whether the report
--      says what the run's stored dry run said, and the timings of each step. Append-only. No personal value: the report and
--      the reconciliation hold codes, counts and amounts only, as import_runs.dry_run_report and import_reconciliations do.
--
-- Locks, said plainly. The column has a constant default, so no rewrite: ACCESS EXCLUSIVE on import_runs for milliseconds,
-- a small table that only imports write. The trigger takes SHARE ROW EXCLUSIVE on import_runs for as long. The new table is
-- empty; its foreign keys take SHARE ROW EXCLUSIVE on tenants and import_runs until the commit, so a business's requests
-- that write neither table never wait on it. Every lock is taken under lock_timeout 5s, so the migration fails fast behind a
-- long transaction instead of queueing requests behind it. No CONCURRENTLY: the index is on the new, empty table.

SET LOCAL lock_timeout = '5s';

ALTER TABLE import_runs ADD COLUMN fabricated boolean NOT NULL DEFAULT false;

CREATE FUNCTION import_runs_fabricated_fixed() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.fabricated IS DISTINCT FROM OLD.fabricated THEN
        RAISE EXCEPTION 'import_runs_fabricated_fixed: whether a run holds fabricated data is said when it opens'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER import_runs_fabricated_fixed BEFORE UPDATE OF fabricated ON import_runs
    FOR EACH ROW EXECUTE FUNCTION import_runs_fabricated_fixed();

CREATE TABLE import_rehearsals (
    id              uuid         NOT NULL PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenants (id),
    run_id          uuid         NOT NULL,
    -- The moment the rehearsal's commit ended, by the throwaway database's clock (the host's).
    created_at      timestamptz  NOT NULL DEFAULT now(),
    -- The restored backup's object name (bms-<environment>-<UTC stamp>.dump.enc) and the API build that committed.
    backup          varchar(200) NOT NULL,
    build           varchar(300) NOT NULL,
    outcome         varchar(20)  NOT NULL CHECK (outcome IN ('committed', 'halted', 'failed')),
    -- Why it did not commit: an error code or the kind of failure, never a message, which may quote a row.
    failure         varchar(300),
    report          jsonb        CHECK (report IS NULL OR jsonb_typeof(report) = 'object'),
    reconciliation  jsonb        CHECK (reconciliation IS NULL OR jsonb_typeof(reconciliation) = 'object'),
    -- Whether the commit report says what the run's stored dry run said (CommitReport.sameAs); NULL without either.
    matches_dry_run boolean,
    -- Milliseconds per step: download, restore, migrate and copy on the host, then the API's start, dry run, commit and
    -- reconciliation.
    timings         jsonb        NOT NULL CHECK (jsonb_typeof(timings) = 'object'),
    UNIQUE (tenant_id, id),
    CONSTRAINT import_rehearsals_failure_named CHECK ((outcome = 'committed') = (failure IS NULL)),
    FOREIGN KEY (tenant_id, run_id) REFERENCES import_runs (tenant_id, id)
);
CREATE INDEX import_rehearsals_by_run ON import_rehearsals (tenant_id, run_id, created_at);
SELECT bms_apply_tenant_rls('import_rehearsals');
SELECT bms_grant_app('import_rehearsals', 'SELECT, INSERT');
SELECT bms_make_append_only('import_rehearsals');
