-- V144: customer data onboarding 8/16, the reconciliation report and the customer's sign-off in the app (issue #134,
-- ADR-027 decision 8, spec docs/specs/customer-data-onboarding.md sections 3.9 and 3.10, FR-IMP-08, SDD chapter 13 sections
-- 13.11 and 13.14). Directly after V143. Expand only, safe on a populated database: one permission, two new tables, one
-- trigger function and one trigger. Nothing existing is changed.
--
-- `core.imports.sign_off`: sign the reconciliation of an import run for the business (spec section 3.10: the tenant admin
-- ticks each section and types their name and the date). Held by the tenant administrator only; it moves no money. System
-- roles are global rows, so every business has it from this migration. PermissionMatrixIT compares the row with the matrix
-- of chapter 8 section 8.3.2.
--
-- `import_reconciliations`: the reconciliation of a committed run, stored as a document (spec section 3.9). One row per
-- generation, numbered per run; the newest is the one shown and signed. `report` is the whole document: per section the
-- checks with their source value, system value and difference as exact decimal strings (money in minor units, never
-- rounded), and every itemised difference with its file, line and reason (`excluded`, `overridden`, `policy`). It holds
-- codes, counts and amounts only, never a personal value of a row. `sha256` is the hash of the document as stored, so the
-- signed report can be checked later. `signable` is false when any difference is not itemised: such a reconciliation cannot
-- be signed (the trigger below). Append-only under forced row-level security: no update path, ever.
--
-- `import_signoffs`: the customer's sign-off, one per run: the reconciliation signed (its id and its sha256 as it was
-- shown), the sections the signer ticked, the name and the date the signer typed, who signed and when. Append-only under
-- forced row-level security, so the signed report never changes after the sign-off. The trigger
-- `import_signoff_rules` refuses, for every role, a sign-off of a run that is not `committed`, of a reconciliation that is
-- not the run's newest, of one with an unitemised difference, of a sha256 that is not the stored one, and any new
-- reconciliation of a run that is not committed or is already signed (the signed document stays the newest for good). It
-- takes the run FOR UPDATE in both cases, so the two never race past each other.
--
-- `imports_erase_personal_data()` is this module's part of removing a closed business (ADR-090): the typed name of a
-- sign-off becomes "Removed"; the signed document holds no personal value and stays for the purge.
--
-- Personal data: the name the signer typed in `import_signoffs.signed_name`, the signature itself; nowhere else.
--
-- Locks, said plainly: CREATE TABLE and CREATE INDEX on new, empty tables take locks nobody else waits for; CREATE TRIGGER on
-- the new tables likewise. The permission rows are two inserts. The lock_timeout below bounds any wait.

SET LOCAL lock_timeout = '5s';

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('core.imports.sign_off', 'core', 'Imports: sign the reconciliation of an import for the business', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'core.imports.sign_off')
ON CONFLICT DO NOTHING;

CREATE TABLE import_reconciliations (
    id           uuid        NOT NULL PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    run_id       uuid        NOT NULL,
    -- 1, 2, ... per run: a generation after the first (asked for again) is the next version.
    version      integer     NOT NULL CHECK (version > 0),
    -- The staff user who asked for it, or NULL when the commit made it as it finished.
    generated_by uuid,
    -- The day the arrears buckets are aged to: the cutover moment's day in the business's zone.
    as_at        date        NOT NULL,
    report       jsonb       NOT NULL CHECK (jsonb_typeof(report) = 'object'),
    sha256       char(64)    NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- False while any difference is not itemised: sign-off is refused.
    signable     boolean     NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (run_id, version),
    FOREIGN KEY (tenant_id, run_id) REFERENCES import_runs (tenant_id, id)
);
SELECT bms_apply_tenant_rls('import_reconciliations');
SELECT bms_grant_app('import_reconciliations', 'SELECT, INSERT');
SELECT bms_make_append_only('import_reconciliations');

CREATE TABLE import_signoffs (
    id                uuid         NOT NULL PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenants (id),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    run_id            uuid         NOT NULL,
    reconciliation_id uuid         NOT NULL,
    -- The sha256 of the reconciliation as the signer saw it.
    report_sha256     char(64)     NOT NULL CHECK (report_sha256 ~ '^[0-9a-f]{64}$'),
    -- The section keys the signer ticked: every section of the reconciliation (the service checks the set).
    sections          text[]       NOT NULL CHECK (cardinality(sections) > 0),
    -- The name and the date the signer typed (spec section 3.10).
    signed_name       varchar(120) NOT NULL CHECK (btrim(signed_name) <> '' AND signed_name = btrim(signed_name)),
    signed_on         date         NOT NULL,
    -- The staff user who signed, as journal_entries.created_by.
    signed_by         uuid         NOT NULL,
    UNIQUE (tenant_id, id),
    -- One sign-off per run.
    UNIQUE (run_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES import_runs (tenant_id, id),
    FOREIGN KEY (tenant_id, reconciliation_id) REFERENCES import_reconciliations (tenant_id, id)
);
SELECT bms_apply_tenant_rls('import_signoffs');
SELECT bms_grant_app('import_signoffs', 'SELECT, INSERT');
SELECT bms_make_append_only('import_signoffs');

-- The rules of a sign-off and of a reconciliation after it, for every role.
CREATE FUNCTION import_signoff_rules() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_status   text;
    v_rec      import_reconciliations%ROWTYPE;
    v_newest   integer;
BEGIN
    -- Both branches take the run FOR UPDATE first, so a reconciliation and a sign-off of one run never pass each other
    -- unseen (review of #873): the second waits for the first and then sees its row.
    SELECT status INTO v_status FROM import_runs WHERE id = NEW.run_id FOR UPDATE;
    IF TG_TABLE_NAME = 'import_reconciliations' THEN
        IF EXISTS (SELECT 1 FROM import_signoffs s WHERE s.run_id = NEW.run_id) THEN
            RAISE EXCEPTION 'the reconciliation of a signed off import never changes'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'import_reconciliation_after_sign_off';
        END IF;
        IF v_status IS DISTINCT FROM 'committed' THEN
            RAISE EXCEPTION 'only a committed import is reconciled'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'import_reconciliation_run_committed';
        END IF;
        RETURN NEW;
    END IF;
    IF v_status IS DISTINCT FROM 'committed' THEN
        RAISE EXCEPTION 'only a committed import is signed off'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_signoff_run_committed';
    END IF;
    SELECT * INTO v_rec FROM import_reconciliations WHERE id = NEW.reconciliation_id;
    SELECT max(version) INTO v_newest FROM import_reconciliations WHERE run_id = NEW.run_id;
    IF v_rec.run_id IS DISTINCT FROM NEW.run_id OR v_rec.version IS DISTINCT FROM v_newest THEN
        RAISE EXCEPTION 'a sign-off names the newest reconciliation of its run'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_signoff_newest';
    END IF;
    IF NOT v_rec.signable THEN
        RAISE EXCEPTION 'a reconciliation with a difference that is not itemised is never signed'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_signoff_itemised';
    END IF;
    IF v_rec.sha256 <> NEW.report_sha256 THEN
        RAISE EXCEPTION 'a sign-off names the reconciliation as it is stored'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_signoff_sha256';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER import_signoff_rules BEFORE INSERT ON import_signoffs
    FOR EACH ROW EXECUTE FUNCTION import_signoff_rules();
CREATE TRIGGER import_signoff_rules BEFORE INSERT ON import_reconciliations
    FOR EACH ROW EXECUTE FUNCTION import_signoff_rules();

-- The removal of a closed business (V102): the signer's typed name goes; the rest of the sign-off stays for the purge. Runs
-- as bms_owner inside the removal the operator confirmed (bms_removing_tenant), which reject_mutation lets through
-- (bms_erasing).
CREATE FUNCTION imports_erase_personal_data() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid := bms_removing_tenant();
    v_rows   integer;
BEGIN
    UPDATE import_signoffs SET signed_name = 'Removed' WHERE tenant_id = v_tenant AND signed_name <> 'Removed';
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows;
END
$$;
REVOKE ALL ON FUNCTION imports_erase_personal_data() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION imports_erase_personal_data() TO bms_app;
