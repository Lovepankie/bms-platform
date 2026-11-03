-- V129: customer data onboarding 2/16, the quarantined upload (issue #128, ADR-027 decision 2, spec
-- docs/specs/customer-data-onboarding.md sections 3.1, 3.2, 6.1 and 6.2, SDD chapter 13 section 13.14.1). Expand only, safe on
-- a populated database: one new table, new nullable columns, a status check replaced by a superset and two indexes on tables
-- that are empty on every server today (import runs are not yet reachable from a screen).
--
-- `import_runs.data_key_wrapped`: the run's data key, made at its first upload, encrypted with the platform import key (an
-- environment secret, BMS_IMPORT_KEY) and bound to the business and the run, so it opens for this run only. Every file of the
-- run is encrypted with it before it reaches object storage. Purging the run (#139) clears it, which makes every copy of the
-- run's files unreadable.
--
-- `import_batches` gains the upload columns of chapter 13 section 13.4 that the upload knows (the template, the mapping and
-- the branch come with #129 and #136) and its status list widens: `uploaded` (stored, not yet read), `parsed` (its rows are in
-- `import_rows`), `failed` (a whole-file problem, with its code; no rows), `cancelled`. `pending` and `committed` keep their
-- meaning for the commit of V127. One file is uploaded once per run: a partial unique index on the SHA-256.
--
-- `import_rows`: one row per source row below the header, its cells exactly as read, never modified (SELECT, INSERT and
-- DELETE only: a new parse of the batch replaces them, and the purge of #139 removes them). The other columns of chapter 13
-- section 13.4 come with the validation of #130.
--
-- Personal data: `import_rows.raw` holds a customer's cells, which may be personal; that is the quarantine, read by no business
-- module and purged by #139. `source_filename` is the customer's file name as given. No other new column holds personal data.
--
-- Locks, said plainly. The new columns and the replaced checks take ACCESS EXCLUSIVE on `import_runs` and `import_batches`,
-- held until the migration commits, like every lock of one Flyway transaction; both tables are empty or nearly so on every
-- server (no screen reaches them yet), so each check is validated in milliseconds. The new table and its foreign key take
-- SHARE ROW EXCLUSIVE on `tenants` and `import_batches` until the commit; reads go on.

SET LOCAL lock_timeout = '5s';

ALTER TABLE import_runs ADD COLUMN data_key_wrapped bytea;

ALTER TABLE import_batches
    ADD COLUMN source_filename     varchar(255),
    ADD COLUMN source_sha256       char(64)     CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    ADD COLUMN size_bytes          bigint       CHECK (size_bytes >= 0),
    ADD COLUMN file_type           text         CHECK (file_type IN ('csv', 'xlsx', 'jsonl')),
    -- The encrypted file in object storage, under tenants/<tenant_id>/quarantine/imports/; NULL once deleted.
    ADD COLUMN object_key          varchar(512),
    ADD COLUMN encoding            text         CHECK (encoding IN ('utf-8', 'windows-1252')),
    ADD COLUMN uploaded_by         uuid,
    ADD COLUMN uploaded_at         timestamptz,
    ADD COLUMN sheet_name          varchar(100),
    ADD COLUMN header_row_no       integer      CHECK (header_row_no > 0),
    ADD COLUMN row_count           integer      CHECK (row_count >= 0),
    ADD COLUMN column_count        integer      CHECK (column_count >= 0),
    -- Codes only, for example ENCODING_CONVERTED; never a value from the file.
    ADD COLUMN parse_warnings      text[]       NOT NULL DEFAULT '{}',
    ADD COLUMN parsed_at           timestamptz,
    -- The whole-file refusal (FILE_TOO_LARGE, TOO_MANY_ROWS, TOO_MANY_COLUMNS, HEADER_NOT_FOUND, FILE_UNREADABLE) and its
    -- plain reason, which names a limit, a line or a column number, never a cell's value.
    ADD COLUMN failure_code        text,
    ADD COLUMN failure_reason      varchar(300),
    ADD COLUMN failed_at           timestamptz,
    ADD COLUMN cancelled_at        timestamptz,
    ADD COLUMN quarantine_deleted_at timestamptz,
    -- A failed batch has its code; a batch cancelled after it failed keeps it.
    ADD CONSTRAINT import_batches_failed_has_code CHECK (
        (status <> 'failed' OR failure_code IS NOT NULL)
        AND (failure_code IS NULL OR status IN ('failed', 'cancelled'))),
    ADD CONSTRAINT import_batches_upload_complete CHECK (
        (source_sha256 IS NULL) = (size_bytes IS NULL)
        AND (source_sha256 IS NULL) = (file_type IS NULL)
        AND (source_sha256 IS NULL) = (uploaded_at IS NULL));

ALTER TABLE import_batches DROP CONSTRAINT import_batches_status_check;
ALTER TABLE import_batches ADD CONSTRAINT import_batches_status_check
    CHECK (status IN ('pending', 'uploaded', 'parsed', 'failed', 'cancelled', 'committed'));

-- One file once per run (a second upload of the same bytes is refused with FILE_ALREADY_UPLOADED).
CREATE UNIQUE INDEX import_batches_one_file_per_run ON import_batches (run_id, source_sha256)
    WHERE source_sha256 IS NOT NULL AND status NOT IN ('failed', 'cancelled');
-- The cleanup of abandoned uploads finds the files still held.
CREATE INDEX import_batches_held_files ON import_batches (tenant_id, run_id) WHERE object_key IS NOT NULL;

CREATE TABLE import_rows (
    id            uuid         NOT NULL PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    batch_id      uuid         NOT NULL,
    -- 1-based row number in the source sheet (CSV record, XLSX row, JSON Lines line); the header is its own number.
    source_row_no integer      NOT NULL CHECK (source_row_no > 0),
    -- Per column kept by the mapping: {"<header>": {"value": ..., "type": "text" | "number" | "date" | "datetime" | "time"
    -- | "bool" | "error" | "json"}}. A column that is absent is empty in the source. Never modified.
    raw           jsonb        NOT NULL CHECK (jsonb_typeof(raw) = 'object'),
    UNIQUE (batch_id, source_row_no),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES import_batches (tenant_id, id)
);
SELECT bms_apply_tenant_rls('import_rows');
SELECT bms_grant_app('import_rows', 'SELECT, INSERT, DELETE');
