-- V20: the retail vertical, increment R5 (ADR-020 decision 9, issue #55): the source references of
-- the import-retail command, so a second run of the same export adds nothing.
--
-- Additive only (chapter 6 section 6.9). Flyway versions are one sequence shared with the lending
-- work and the open retail fixes; V20 is deliberately clear of the numbers those pull requests take.

-- ---------------------------------------------------------------------------------------------
-- retail_import_refs (FR-RET-12): one row per imported source row of the history files, and one
-- per branch for the opening journal. The key is the export's own reference (source_ref), so a
-- row already present is skipped on a re-run. source_user is the source system's user, kept as
-- entered; it is not a staff account. Append-only.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_import_refs (
    tenant_id   uuid         NOT NULL REFERENCES tenants (id),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    source_file varchar(30)  NOT NULL CHECK (source_file IN ('sales', 'purchases', 'usage', 'opening')),
    source_ref  varchar(100) NOT NULL CHECK (btrim(source_ref) <> ''),
    target_type varchar(50)  NOT NULL,
    target_id   uuid,
    source_user varchar(200),
    PRIMARY KEY (tenant_id, source_file, source_ref)
);
SELECT bms_apply_tenant_rls('retail_import_refs');
SELECT bms_grant_app('retail_import_refs', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_import_refs');
