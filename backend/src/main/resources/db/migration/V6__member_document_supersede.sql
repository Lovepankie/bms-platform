-- V6: member documents can be superseded (issue #29, review of pull request #38).
--
-- Expand only (chapter 6 section 6.9): two nullable columns. A member keeps at most ten active
-- documents of a kind; an upload beyond that supersedes the oldest active one of the same kind
-- instead of being refused, so a wrong ID image can always be replaced. The superseded row stays
-- for the audit trail and its file stays downloadable by those who may read it.

ALTER TABLE lending_member_documents
    ADD COLUMN superseded_at timestamptz,
    ADD COLUMN superseded_by uuid,
    ADD FOREIGN KEY (tenant_id, superseded_by) REFERENCES lending_member_documents (tenant_id, id),
    ADD CHECK ((superseded_at IS NULL) = (superseded_by IS NULL)),
    ADD CHECK (superseded_by IS NULL OR superseded_by <> id);

CREATE INDEX lending_member_documents_active
    ON lending_member_documents (tenant_id, member_id, doc_kind, created_at)
    WHERE superseded_at IS NULL;

-- The rows stay insert-only except for these two columns, written once when a newer upload
-- replaces the row; no other column can be changed by the application.
GRANT UPDATE (superseded_at, superseded_by) ON lending_member_documents TO bms_app;
