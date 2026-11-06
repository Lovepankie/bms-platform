-- V142: customer data onboarding 6/16, the preview and the dry run (issue #132, ADR-027 decision 5, spec
-- docs/specs/customer-data-onboarding.md sections 3.6 and 3.11, FR-IMP-06, SDD chapter 13 sections 13.3 and 13.14.1).
-- Directly after V141. Expand only, safe on a populated database: new nullable columns on import_runs and import_batches,
-- tables that are empty or nearly so on every server (import runs are not yet reachable from a screen). No index, no new
-- table, no grant: bms_app already has SELECT, INSERT and UPDATE on both tables (V127), and both are under forced
-- row-level security (V127), so the stored report is the business's own and no other business reads it.
--
-- `import_runs` gains the stored dry run: the report (`dry_run_report`, the same shape a real run stores: per batch read,
-- written, existing and skipped, the anomalies with file and line, the opening journals with their debit and credit totals,
-- the balances written, the notes and the balances checksum), when and by whom it ran, the stamp of the run's inputs it ran
-- on (`dry_run_stamp`, an MD5 over the application's version, every batch's status, file, mapping version, parse and row
-- count, every issue's status and version, every resolution, every staged row's review columns and typed values, the
-- cutover moment, the policy decisions and the last batch any run of the business committed), whether the preview's
-- balances equalled the dry run's (`dry_run_preview_matches`) and the writer it rehearsed (`dry_run_writer`). A run becomes `ready` only with a stored dry run whose stamp is the run's stamp now, that
-- did not fail and whose preview matched: a batch, mapping version or resolution changed after it makes the stamp differ,
-- and the stored report no longer counts. The dry run writes nothing else: its commit runs inside one outer transaction
-- that is rolled back, so no business table, no ledger table and no audit row changes (the test counts every table).
--
-- `import_batches` gains `report`: the part of the run's report a batch's commit produced (its counts, anomalies, opening
-- journals, notes and checksum), written in the batch's own commit transaction, so the report of a real run, resumed or not,
-- is its committed batches' parts in order.
--
-- Personal data: none. Every text a writer gives the report (source names, anomalies, journal fields, notes, the checksum)
-- is checked against the personal values of every staged row of the run before it is kept, and one that holds such a value
-- is stored as hidden; counts, codes and amounts are not personal.
--
-- Locks, said plainly: ADD COLUMN without a default is a catalogue change under ACCESS EXCLUSIVE on each table for
-- milliseconds, behind the lock_timeout below; no row is rewritten.

SET LOCAL lock_timeout = '5s';

ALTER TABLE import_runs
    ADD COLUMN dry_run_report          jsonb        CHECK (dry_run_report IS NULL OR jsonb_typeof(dry_run_report) = 'object'),
    ADD COLUMN dry_run_at              timestamptz,
    -- The staff user or platform operator who ran it, as import_runs.created_by.
    ADD COLUMN dry_run_by              uuid,
    ADD COLUMN dry_run_stamp           char(32)     CHECK (dry_run_stamp ~ '^[0-9a-f]{32}$'),
    ADD COLUMN dry_run_preview_matches boolean,
    -- The writers the dry run rehearsed, one line per batch: the template and version, its class, the entity set and the
    -- loader's identity. A commit or resume whose writers are not these is refused. Template code names, never data.
    ADD COLUMN dry_run_writer          text,
    ADD CONSTRAINT import_runs_dry_run_whole
        CHECK ((dry_run_report IS NULL) = (dry_run_at IS NULL)
               AND (dry_run_report IS NULL) = (dry_run_by IS NULL)
               AND (dry_run_report IS NULL) = (dry_run_stamp IS NULL)
               AND (dry_run_report IS NULL) = (dry_run_preview_matches IS NULL)
               AND (dry_run_report IS NULL) = (dry_run_writer IS NULL));

ALTER TABLE import_batches
    ADD COLUMN report jsonb CHECK (report IS NULL OR jsonb_typeof(report) = 'object');
