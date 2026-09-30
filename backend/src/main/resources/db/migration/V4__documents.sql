-- V4: documents and member documents (MVP increment 3, issue #12).
--
-- Additive only (chapter 6 section 6.9). A document row is written once and never changed, so
-- bms_app holds SELECT and INSERT only on both tables.

-- ---------------------------------------------------------------------------------------------
-- documents (chapter 6 section 6.5; FR-DOC-02). The object key always starts with
-- tenants/<tenant_id>/, and the CHECK ties it to the row's own tenant.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE documents (
    id           uuid        NOT NULL PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz,
    branch_id    uuid,
    doc_type     text        NOT NULL
                             CHECK (doc_type IN ('receipt', 'voucher', 'loan_agreement', 'schedule', 'loan_statement',
                                                 'member_statement', 'savings_statement', 'investment_certificate',
                                                 'report', 'upload')),
    doc_number   text,
    subject_type text,
    subject_id   uuid,
    object_key   text        NOT NULL UNIQUE,
    content_type text        NOT NULL,
    size_bytes   bigint      NOT NULL CHECK (size_bytes >= 0),
    sha256       char(64)    NOT NULL,
    created_by   uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    CHECK (object_key LIKE 'tenants/' || tenant_id::text || '/%')
);
CREATE INDEX documents_subject ON documents (tenant_id, subject_type, subject_id);
SELECT bms_apply_tenant_rls('documents');
SELECT bms_grant_app('documents', 'SELECT, INSERT');

-- ---------------------------------------------------------------------------------------------
-- lending_member_documents (chapter 6 section 6.7; FR-MEM-09)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_member_documents (
    id          uuid        NOT NULL PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenants (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    member_id   uuid        NOT NULL,
    doc_kind    text        NOT NULL CHECK (doc_kind IN ('id_front', 'id_back', 'photo', 'other')),
    document_id uuid        NOT NULL,
    uploaded_by uuid        NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, document_id),
    FOREIGN KEY (tenant_id, member_id) REFERENCES lending_members (tenant_id, id),
    FOREIGN KEY (tenant_id, document_id) REFERENCES documents (tenant_id, id)
);
CREATE INDEX lending_member_documents_member ON lending_member_documents (tenant_id, member_id, doc_kind);
SELECT bms_apply_tenant_rls('lending_member_documents');
SELECT bms_grant_app('lending_member_documents', 'SELECT, INSERT');
