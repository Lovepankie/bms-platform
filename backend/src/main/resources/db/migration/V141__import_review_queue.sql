-- V141: customer data onboarding 5/16, the review queue (issue #131, ADR-027, spec docs/specs/customer-data-onboarding.md
-- section 3.5, FR-IMP-05, SDD chapter 13 sections 13.4 and 13.14.1). Directly after V140. Expand only, safe on a populated
-- database: one new table, new columns with defaults or NULL on import_issues and import_rows with column grants, one
-- unique key replaced, and the two V140 guards widened, on tables that are empty or nearly so on every server (import runs
-- are not yet reachable from a screen).
--
-- `import_issue_resolutions` (append-only): one row per resolution an operator makes on an issue, with who, when, the kind,
-- the value before and after, and the note. A row is never changed or removed by bms_app (SELECT and INSERT, and the
-- reject_mutation trigger for every role). It names the issue it was made on but holds no foreign key to it: a validation
-- replaces a batch's issues, and a new parse or a cancel removes a batch's rows, while the resolution stays. It is found
-- again by the row it was made on, by `row_key` (the row's reference without its content hash: the source and the column
-- value, or the source and the line), `raw_sha256` (the SHA-256 of the row's cells as stored) and `occurrence` (which copy,
-- when lines repeat verbatim), with the code and field of the issue. A validation applies the resolutions of a row whose cells are identical, and reports a row whose cells changed
-- since it was resolved as ROW_CHANGED_SINCE_REVIEW (spec section 10.1).
--
-- `import_issues` gains `version` (optimistic concurrency: a second resolver of the same version gets version_conflict),
-- `ask_customer` (the issue is a question for the customer), `suggested_value` (a value the engine proposes, never a personal
-- one) and `resolution_id` (the resolution that resolved it). bms_app may UPDATE only `status`, `version`, `ask_customer` and
-- `resolution_id` (a column grant), and never on a committed batch (trigger import_issues_batch_kept, now on UPDATE too).
-- Ordinals are counted per row from now on (UNIQUE (batch_id, source_row_no, ordinal) replaces UNIQUE (batch_id, ordinal)),
-- so the issues of one row can be written again without renumbering the batch; the order of a batch is unchanged (line,
-- then the engine's order within the line).
--
-- `import_rows` gains what the review decided for a row, rewritten by each validation from the resolutions: the resolution
-- that excluded it, the row of the run it is linked to and the existing record it is linked to. bms_app may UPDATE these
-- three columns only, and never on a committed batch (trigger import_rows_validation_kept, widened).
--
-- Personal data: `before_value` and `after_value` of an `edit_value` or `accept_suggestion` may hold a customer's name,
-- phone or NIN, as `import_rows.raw` does: the quarantine, read by no business module, deleted with the business's other
-- rows by the purge of a closed business (platform_purge_closed_tenant, through reject_mutation's V102 bypass) and replaced
-- by their hash by the purge of #139 (spec section 6.4), which brings its own way past the append-only guard as it does for
-- the issues (Lead ruling on #867). `note` is free text the removal of a business sets to 'Removed' with every other note
-- (V102). `row_key` holds the source key and either a line number or the reference column's value, as
-- `import_rows.source_ref` does. No message and no audit row holds a value (the review tests).
--
-- Locks, said plainly. The new columns, the replaced key and the triggers take ACCESS EXCLUSIVE on `import_issues` and
-- `import_rows` until the migration commits; both are empty or nearly so everywhere, so it is milliseconds. The new table
-- takes SHARE ROW EXCLUSIVE on `tenants`, `import_runs` and `import_batches` (its foreign keys) until the commit; reads go
-- on. Plain CREATE INDEX inside Flyway's transaction (never CONCURRENTLY), behind the lock_timeout below.

SET LOCAL lock_timeout = '5s';

CREATE TABLE import_issue_resolutions (
    id            uuid          NOT NULL PRIMARY KEY,
    tenant_id     uuid          NOT NULL REFERENCES tenants (id),
    created_at    timestamptz   NOT NULL DEFAULT now(),
    -- The order resolutions were made in; a later one of the same row and field wins.
    seq           bigint        GENERATED ALWAYS AS IDENTITY,
    run_id        uuid          NOT NULL,
    batch_id      uuid          NOT NULL,
    -- The issue it was made on, as it was then; no foreign key, since a validation replaces a batch's issues.
    issue_id      uuid          NOT NULL,
    row_key       varchar(400)  NOT NULL,
    raw_sha256    char(64)      NOT NULL CHECK (raw_sha256 ~ '^[0-9a-f]{64}$'),
    -- Which copy it was made on when the run holds the same identity with the same cells more than once (a line repeated
    -- verbatim): the n-th such row in the run's order (batch, then line), so a decision on one copy never reaches another.
    occurrence    integer       NOT NULL DEFAULT 1 CHECK (occurrence > 0),
    -- How many such copies the run held then. When a new upload holds another number, the copies are not matched by
    -- guess: they come back to the queue as changed (Lead ruling on #868).
    copies        integer       NOT NULL DEFAULT 1,
    CHECK (copies >= occurrence),
    source_row_no integer       NOT NULL CHECK (source_row_no > 0),
    code          text          NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
    field         text          CHECK (field ~ '^[a-z][a-z0-9_]{0,39}$'),
    resolution    text          NOT NULL CHECK (resolution IN ('edit_value', 'exclude_row', 'accept_suggestion', 'link_row',
                                                               'link_member', 'override', 'map_value', 'ask_customer')),
    before_value  jsonb,
    after_value   jsonb,
    note          varchar(500),
    -- The staff user or platform operator who made it, as import_runs.created_by.
    resolved_by   uuid          NOT NULL,
    -- The bulk resolution it was one of, or NULL for one made on its own.
    bulk_id       uuid,
    UNIQUE (tenant_id, id),
    -- An override and an exclusion always say why (chapter 13 section 13.4).
    CHECK (resolution NOT IN ('override', 'exclude_row') OR char_length(btrim(note)) >= 3),
    FOREIGN KEY (tenant_id, run_id) REFERENCES import_runs (tenant_id, id),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES import_batches (tenant_id, id)
);
CREATE INDEX import_issue_resolutions_by_row ON import_issue_resolutions (tenant_id, run_id, row_key);
CREATE INDEX import_issue_resolutions_by_issue ON import_issue_resolutions (tenant_id, issue_id);
SELECT bms_apply_tenant_rls('import_issue_resolutions');
SELECT bms_grant_app('import_issue_resolutions', 'SELECT, INSERT');
SELECT bms_make_append_only('import_issue_resolutions');

ALTER TABLE import_issues
    ADD COLUMN version         integer NOT NULL DEFAULT 1 CHECK (version > 0),
    ADD COLUMN ask_customer    boolean NOT NULL DEFAULT false,
    -- {"value": "<text>"} for a value to put in the field, or {"review": "as_uploaded"} for a changed row; never personal.
    ADD COLUMN suggested_value jsonb   CHECK (suggested_value IS NULL OR jsonb_typeof(suggested_value) = 'object'),
    ADD COLUMN resolution_id   uuid,
    -- NOT VALID, as V139's batch key: every issue stored before this migration has no resolution, and the scan validation
    -- runs is a query under the forced row security of import_issues, which raises for an owner without BYPASSRLS and no
    -- app.tenant_id bound (MigrationOrderIT migrates that way). Every row inserted or updated from now on is checked.
    ADD CONSTRAINT import_issues_resolution_fk
        FOREIGN KEY (tenant_id, resolution_id) REFERENCES import_issue_resolutions (tenant_id, id) NOT VALID;
ALTER TABLE import_issues DROP CONSTRAINT import_issues_batch_id_ordinal_key;
ALTER TABLE import_issues ADD CONSTRAINT import_issues_row_ordinal UNIQUE (batch_id, source_row_no, ordinal);
GRANT UPDATE (status, version, ask_customer, resolution_id) ON import_issues TO bms_app;

ALTER TABLE import_rows
    ADD COLUMN excluded_by      uuid,
    -- Rewritten by each validation from the resolutions, so no foreign key: a new parse replaces the rows it names.
    ADD COLUMN linked_row_id    uuid,
    -- A record of the business the template's vertical holds; the core has no table to point at.
    ADD COLUMN linked_member_id uuid,
    -- NOT VALID for the same reason: no row stored before this migration is excluded.
    ADD CONSTRAINT import_rows_excluded_fk
        FOREIGN KEY (tenant_id, excluded_by) REFERENCES import_issue_resolutions (tenant_id, id) NOT VALID;
GRANT UPDATE (excluded_by, linked_row_id, linked_member_id) ON import_rows TO bms_app;

-- No issue of a committed batch is written, changed or removed, by a validation, a resolution or a cascade from its row. The
-- body of V140 with UPDATE added; it keeps V102's bypass for a migration and for the removal and the purge of a closed
-- business (bms_erasing).
CREATE OR REPLACE FUNCTION import_issues_batch_kept() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_batch uuid;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        IF (session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on')
           OR bms_erasing(to_jsonb(OLD)) THEN
            IF TG_OP = 'DELETE' THEN
                RETURN OLD;
            END IF;
            RETURN NEW;
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

DROP TRIGGER import_issues_batch_kept ON import_issues;
CREATE TRIGGER import_issues_batch_kept BEFORE INSERT OR UPDATE OR DELETE ON import_issues
    FOR EACH ROW EXECUTE FUNCTION import_issues_batch_kept();

-- The validation's and the review's columns of a row of a committed batch never change.
CREATE OR REPLACE FUNCTION import_rows_validation_kept() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on')
       OR bms_erasing(to_jsonb(OLD)) THEN
        RETURN NEW;
    END IF;
    IF (NEW.normalised IS DISTINCT FROM OLD.normalised OR NEW.source_ref IS DISTINCT FROM OLD.source_ref
        OR NEW.excluded_by IS DISTINCT FROM OLD.excluded_by OR NEW.linked_row_id IS DISTINCT FROM OLD.linked_row_id
        OR NEW.linked_member_id IS DISTINCT FROM OLD.linked_member_id)
       AND EXISTS (SELECT 1 FROM import_batches b WHERE b.id = OLD.batch_id AND b.status = 'committed') THEN
        RAISE EXCEPTION 'the rows of a committed import batch never change'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_rows_validation_kept';
    END IF;
    RETURN NEW;
END
$$;
