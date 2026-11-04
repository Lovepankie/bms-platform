-- V133: undo a wrong "used in the shop" or damage entry (issue #818, ADR-123).
--
-- V133 is the highest number on main plus one (AGENTS.md: Flyway runs with outOfOrder off); the merger renumbers at
-- merge time if the order changes.
--
-- Expand only (chapter 6 section 6.9): one new, empty table. Nothing is dropped, no column or check changes, and no
-- existing table is locked beyond the foreign key checks of the new table.
--
-- retail_usage_reports is append-only, so an undo is never an edit of the entry: it is one more row that names it, with
-- who, when, why and the journal reversal, at most once per entry. The unique key is also the guard against two undos
-- at once. The stock goes back with return movements of source type retail.usage_undo, each naming the movement it
-- reverses (the unique reverses_movement_id of V11) at the same unit cost; the shrinkage entry is reversed by the
-- ledger and found by journal_entries.reverses_entry_id, so the row is written first, whole, and is the lock. Both are
-- dated undone_on, the business day of the undo, so a closed day keeps its figures, as a sale void does.

CREATE TABLE retail_usage_undos (
    id               uuid         NOT NULL PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    report_id        uuid         NOT NULL,
    undone_on        date         NOT NULL,
    reason           varchar(300) NOT NULL CHECK (btrim(reason) <> ''),
    undone_by        uuid         NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, report_id),
    FOREIGN KEY (tenant_id, report_id) REFERENCES retail_usage_reports (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_usage_undos');
SELECT bms_grant_app('retail_usage_undos', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_usage_undos');
