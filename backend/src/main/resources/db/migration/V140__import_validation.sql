-- V140: customer data onboarding 4/16, the validation engine (issue #130, ADR-027, spec docs/specs/customer-data-onboarding.md
-- sections 3.3, 3.4, 3.4.1 and 6.5, SDD chapter 13 sections 13.4, 13.7 and 13.14.1). Directly after V139. Expand only, safe on
-- a populated database: one new table, two new nullable columns on import_rows with a column grant, and two guard triggers, on
-- tables that are empty or nearly so on every server (import runs are not yet reachable from a screen).
--
-- `import_rows` gains what the validation derives from a row, never its cells: `normalised`, the typed values by target field
-- (NULL until the row is validated), and `source_ref`, the reference a re-run recognises the row by (spec section 3.7).
-- `raw` stays as read: bms_app may UPDATE these two columns only (a column grant), and only while the row's batch is not
-- committed (trigger import_rows_validation_kept).
--
-- `import_issues`: the issues of one validation of a batch, one row per issue, in the order the engine found them
-- (`ordinal`). A code of the template's catalogue, its severity, the target field, the message built from the code's template
-- with field, file and line and never a personal value, the original cell text where the issue concerns one (shown only in
-- the review screen to a holder of core.imports.view_raw, spec section 6.3), and the status (`info` is created resolved). A new
-- validation of a batch deletes its issues and writes them again; a new parse deletes its rows and so, by the cascade, their
-- issues. No issue of a committed batch is inserted or deleted (trigger import_issues_batch_kept), so a validation never
-- changes a committed batch. The resolutions of chapter 13 section 13.4 come with the review queue of #131.
--
-- Personal data: `import_rows.normalised` and `import_issues.original_value` may hold a customer's names, phones and NINs, like
-- `import_rows.raw`: the quarantine, read by no business module and purged by #139. `message` never holds one (the catalogue
-- test of #130); the removal of a business sets it to 'Removed' with every other `message` column (V102).
--
-- Both guards keep V102's bypass for a migration and for the removal and the purge of a closed business (bms_erasing), as V102
-- asks of every later migration that writes a guard of a business's table.
--
-- Locks, said plainly. The new columns and the triggers take ACCESS EXCLUSIVE on `import_rows` and SHARE ROW EXCLUSIVE on
-- `import_batches` (the foreign key) until the migration commits; both are empty or nearly so everywhere, so it is
-- milliseconds. Plain CREATE INDEX inside Flyway's transaction (never CONCURRENTLY), behind the lock_timeout below.

SET LOCAL lock_timeout = '5s';

ALTER TABLE import_rows
    -- {"<field>": value} in the template's field order; text, whole numbers, decimals as numbers, dates and moments as ISO text.
    ADD COLUMN normalised jsonb        CHECK (normalised IS NULL OR jsonb_typeof(normalised) = 'object'),
    ADD COLUMN source_ref varchar(300);
GRANT UPDATE (normalised, source_ref) ON import_rows TO bms_app;
-- The target of the issues' foreign key, so an issue's row is of its business and of its batch.
ALTER TABLE import_rows ADD CONSTRAINT import_rows_tenant_batch_id UNIQUE (tenant_id, batch_id, id);

CREATE TABLE import_issues (
    id             uuid         NOT NULL PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenants (id),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    batch_id       uuid         NOT NULL,
    -- The row the issue is on, of the same business and batch (the foreign key below); its issues go with it when a new
    -- parse, a cancel or the purge removes the batch's rows.
    row_id         uuid         NOT NULL,
    source_row_no  integer      NOT NULL CHECK (source_row_no > 0),
    -- The order the engine found it in within the batch: line, then the template's field order, then the row's own checks.
    ordinal        integer      NOT NULL CHECK (ordinal > 0),
    code           text         NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
    severity       text         NOT NULL CHECK (severity IN ('info', 'warning', 'blocking')),
    -- The target field, or NULL for an issue of the whole row.
    field          text         CHECK (field ~ '^[a-z][a-z0-9_]{0,39}$'),
    message        varchar(500) NOT NULL,
    original_value jsonb,
    status         text         NOT NULL CHECK (status IN ('open', 'resolved')),
    UNIQUE (tenant_id, id),
    UNIQUE (batch_id, ordinal),
    CHECK (severity <> 'info' OR status = 'resolved'),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES import_batches (tenant_id, id),
    FOREIGN KEY (tenant_id, batch_id, row_id) REFERENCES import_rows (tenant_id, batch_id, id) ON DELETE CASCADE
);
CREATE INDEX import_issues_by_row ON import_issues (row_id);
CREATE INDEX import_issues_by_code ON import_issues (tenant_id, batch_id, code);
SELECT bms_apply_tenant_rls('import_issues');
SELECT bms_grant_app('import_issues', 'SELECT, INSERT, DELETE');

-- No issue of a committed batch is written or removed, by a validation or by a cascade from its row. The purge of #139,
-- which removes a committed batch's staged rows, needs its own way past this guard and adds it with its migration (Lead
-- ruling on #867).
CREATE FUNCTION import_issues_batch_kept() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_batch uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF (session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on')
           OR bms_erasing(to_jsonb(OLD)) THEN
            RETURN OLD;
        END IF;
        v_batch := OLD.batch_id;
    ELSE
        v_batch := NEW.batch_id;
    END IF;
    IF EXISTS (SELECT 1 FROM import_batches b WHERE b.id = v_batch AND b.status = 'committed') THEN
        RAISE EXCEPTION 'the issues of a committed import batch never change'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_issues_batch_kept';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER import_issues_batch_kept BEFORE INSERT OR DELETE ON import_issues
    FOR EACH ROW EXECUTE FUNCTION import_issues_batch_kept();

-- The validation's columns of a row of a committed batch never change.
CREATE FUNCTION import_rows_validation_kept() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on')
       OR bms_erasing(to_jsonb(OLD)) THEN
        RETURN NEW;
    END IF;
    IF (NEW.normalised IS DISTINCT FROM OLD.normalised OR NEW.source_ref IS DISTINCT FROM OLD.source_ref)
       AND EXISTS (SELECT 1 FROM import_batches b WHERE b.id = OLD.batch_id AND b.status = 'committed') THEN
        RAISE EXCEPTION 'the rows of a committed import batch never change'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_rows_validation_kept';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER import_rows_validation_kept BEFORE UPDATE ON import_rows
    FOR EACH ROW EXECUTE FUNCTION import_rows_validation_kept();
