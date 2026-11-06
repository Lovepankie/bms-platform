-- V139: customer data onboarding 3/16, canonical templates and versioned mappings (issue #129, ADR-027 decision 3, spec
-- docs/specs/customer-data-onboarding.md section 4, SDD chapter 13 section 13.14.1). V138 is claimed by issue #767, which
-- merges first. Expand only, safe on a populated database: one new table, new nullable columns on import_batches, a CHECK,
-- a foreign key, one plain index and one trigger on a table that is empty or nearly so on every server (import runs are not
-- yet reachable from a screen).
--
-- `import_mappings`: a business's mapping documents, one row per version, append-only (SELECT and INSERT for bms_app, and the
-- reject_mutation trigger for every role). A change to a mapping is a new row under the same name with the next version, so
-- a stored version never changes. The document names its template key and version, which the row repeats for the foreign
-- key below; the service validates it against the template's JSON Schema before it is stored.
--
-- `import_batches` gains the mapping a batch was parsed with (`mapping_id` and the source of it, `mapping_source`), the
-- template key and version that mapping is for, and `source_headers`, the header names its parse found, which the validator
-- needs to report every column neither mapped nor ignored. The four mapping columns are all set or all empty, and a
-- composite foreign key makes the batch's template the mapping's own. Once a batch is committed its mapping columns never
-- change (trigger import_batches_mapping_kept), so a new mapping version never changes a committed batch.
--
-- Personal data: none. A mapping holds header names and value labels, never a cell of a customer's records (spec section
-- 4.2); `source_headers` holds header names only.
--
-- Locks, said plainly. The new columns, the CHECK, the foreign key, the index and the trigger take ACCESS EXCLUSIVE on
-- `import_batches` until the migration commits; the table is empty or nearly so everywhere, so it is milliseconds. The new
-- table takes SHARE ROW EXCLUSIVE on `tenants` (its foreign key) until the commit; reads go on. A plain CREATE INDEX inside
-- Flyway's transaction (never CONCURRENTLY), behind the lock_timeout below.

SET LOCAL lock_timeout = '5s';

CREATE TABLE import_mappings (
    id               uuid         NOT NULL PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    -- The business's own name for one source shape, never a person's name.
    name             varchar(80)  NOT NULL CHECK (name ~ '^[a-z0-9][a-z0-9_-]{0,79}$'),
    version          integer      NOT NULL CHECK (version > 0),
    template_key     text         NOT NULL CHECK (template_key ~ '^[a-z][a-z0-9_]{0,39}$'),
    template_version integer      NOT NULL CHECK (template_version > 0),
    document         jsonb        NOT NULL CHECK (jsonb_typeof(document) = 'object'),
    -- The staff user or platform operator who stored this version, as import_runs.created_by.
    created_by       uuid         NOT NULL,
    -- Why this version exists, for example the review question it answers.
    note             varchar(500),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name, version),
    -- The target of the batches' composite foreign key.
    UNIQUE (tenant_id, id, template_key, template_version),
    CHECK (document ->> 'template' = template_key AND document ->> 'template_version' = template_version::text)
);
SELECT bms_apply_tenant_rls('import_mappings');
SELECT bms_grant_app('import_mappings', 'SELECT, INSERT');
SELECT bms_make_append_only('import_mappings');

ALTER TABLE import_batches
    ADD COLUMN mapping_id       uuid,
    -- The key of the source in the mapping's document that described this file.
    ADD COLUMN mapping_source   varchar(40),
    ADD COLUMN template_key     text,
    ADD COLUMN template_version integer,
    -- The header names the parse found, in order; NULL before a parse.
    ADD COLUMN source_headers   text[],
    ADD CONSTRAINT import_batches_mapping_complete CHECK (
        (mapping_id IS NULL) = (mapping_source IS NULL)
        AND (mapping_id IS NULL) = (template_key IS NULL)
        AND (mapping_id IS NULL) = (template_version IS NULL)),
    -- NOT VALID: every batch stored before this migration has no mapping, so the scan that validation runs could find
    -- nothing, and that scan is a query under import_batches' forced row security, which raises for an owner without
    -- BYPASSRLS (no app.tenant_id is bound here; MigrationOrderIT migrates that way). Every row inserted or updated from now
    -- on is checked by the constraint's triggers.
    ADD CONSTRAINT import_batches_mapping_fk
        FOREIGN KEY (tenant_id, mapping_id, template_key, template_version)
        REFERENCES import_mappings (tenant_id, id, template_key, template_version) NOT VALID;

CREATE INDEX import_batches_by_mapping ON import_batches (tenant_id, mapping_id) WHERE mapping_id IS NOT NULL;

-- A committed batch keeps the mapping it was parsed with: a new mapping version is a new row and never reaches it. The guard
-- keeps V102's bypass for a migration and for the removal and the purge of a closed business (bms_erasing), as V102 asks of
-- every later migration that writes a guard of a business's table. import_mappings uses reject_mutation, which V102 already
-- extended.
CREATE FUNCTION import_batches_mapping_kept() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on') OR bms_erasing(to_jsonb(OLD)) THEN
        RETURN NEW;
    END IF;
    -- NEW.status too, so one UPDATE cannot commit the batch and move its mapping at once (review of #866).
    IF (OLD.status = 'committed' OR NEW.status = 'committed')
       AND (NEW.mapping_id IS DISTINCT FROM OLD.mapping_id
            OR NEW.mapping_source IS DISTINCT FROM OLD.mapping_source
            OR NEW.template_key IS DISTINCT FROM OLD.template_key
            OR NEW.template_version IS DISTINCT FROM OLD.template_version
            OR NEW.source_headers IS DISTINCT FROM OLD.source_headers) THEN
        RAISE EXCEPTION 'the mapping of a committed import batch never changes'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_batches_mapping_kept';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER import_batches_mapping_kept BEFORE UPDATE ON import_batches
    FOR EACH ROW EXECUTE FUNCTION import_batches_mapping_kept();
