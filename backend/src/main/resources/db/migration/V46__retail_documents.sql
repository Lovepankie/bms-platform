-- V46: retail optional documents: quotation, invoice and delivery note with numbered series per tenant and branch (ADR-033, issue #197;
-- docs/specs/trade-capabilities.md section 6; ADR-034 for the stored PDF).
--
-- Expand only, safe on a populated database. Six new tables, one new permission, and one replaced CHECK on the
-- core documents table. Nothing existing is dropped, renamed or rewritten, no tenant is switched on (the documents
-- capability lives in the tenant settings group retail.capabilities and is off until an administrator turns it on)
-- and a tenant that never issues a document never writes a row to any of the new tables. A sale, a transfer and a
-- restock never read or write them: a document is an after-step that reads a committed record (spec 6.1).
--
-- Locks, said plainly. Every new table is created empty, so the only cost is the lock wait on the tables its foreign
-- keys reference (branches, retail_customers, retail_sales, retail_transfers, retail_products, retail_units,
-- retail_price_lists, currencies, tenants): CREATE TABLE ... REFERENCES takes SHARE ROW EXCLUSIVE on each until the
-- commit, which blocks writes to those tables, but not reads, while the file runs; no row is scanned. The one
-- replaced check is on documents (one row per uploaded or generated file, thousands at most): ADD CONSTRAINT ... NOT
-- VALID is a metadata change, but Flyway runs the file in one transaction, so its ACCESS EXCLUSIVE lock is held to the
-- commit and the VALIDATE that follows scans the table under it. That blocks reads and writes of documents for the
-- scan, which is milliseconds at this size; the old check is dropped last so the table is never without a rule. No
-- ALTER touches retail_sales, retail_sale_lines or any large table.
-- Flyway runs with outOfOrder off, so the number is assigned at merge time above the highest one on main.

-- ---------------------------------------------------------------------------------------------
-- documents.doc_type: the generated files of this slice. The list is V4's with four more kinds. The customer
-- statement is rendered on each request and never stored (ADR-034), so it needs no kind.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE documents
    ADD CONSTRAINT documents_doc_type_trade_chk
        CHECK (doc_type IN ('receipt', 'voucher', 'loan_agreement', 'schedule', 'loan_statement',
                            'member_statement', 'savings_statement', 'investment_certificate',
                            'report', 'upload', 'invoice', 'delivery_note', 'quotation', 'credit_note')) NOT VALID;
ALTER TABLE documents VALIDATE CONSTRAINT documents_doc_type_trade_chk;
ALTER TABLE documents DROP CONSTRAINT documents_doc_type_check;

-- ---------------------------------------------------------------------------------------------
-- retail_document_prefixes: the prefix of a series, one per tenant and document type. A document number is
-- prefix, branch code and a six digit counter (INV-HQ-000001). A type with no row uses its default (QT, INV, DN). A credit
-- note has no series here: the return numbers it itself (V45) and its PDF carries that number.
-- Changing a prefix changes the numbers of later documents only; a number already issued is never rewritten.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_document_prefixes (
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    type       text        NOT NULL CHECK (type IN ('quotation', 'invoice', 'delivery_note')),
    prefix     varchar(8)  NOT NULL CHECK (prefix ~ '^[A-Z][A-Z0-9]{0,7}$'),
    changed_by uuid        NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, type)
);
SELECT bms_apply_tenant_rls('retail_document_prefixes');
SELECT bms_grant_app('retail_document_prefixes', 'SELECT, INSERT');
GRANT UPDATE (prefix, changed_by, changed_at) ON retail_document_prefixes TO bms_app;
CREATE TRIGGER retail_document_prefixes_reject_delete BEFORE DELETE ON retail_document_prefixes
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------------------------
-- retail_document_series: the counter of a series, one row per tenant, document type and branch, created when the
-- first document of that series is issued. The number is allocated by locking this row (SELECT ... FOR UPDATE) in the
-- same transaction that writes the document, so a failed issue rolls the counter back and leaves no gap. The guard
-- makes the rule a database rule: the counter can only go up by exactly one, and a row is never deleted.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_document_series (
    tenant_id uuid    NOT NULL REFERENCES tenants (id),
    type      text    NOT NULL CHECK (type IN ('quotation', 'invoice', 'delivery_note')),
    branch_id uuid    NOT NULL,
    last_no   integer NOT NULL DEFAULT 0 CHECK (last_no >= 0),
    PRIMARY KEY (tenant_id, type, branch_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_document_series');
SELECT bms_grant_app('retail_document_series', 'SELECT, INSERT');
GRANT UPDATE (last_no) ON retail_document_series TO bms_app;

CREATE FUNCTION retail_document_series_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF NEW.last_no <> OLD.last_no + 1
       OR (NEW.tenant_id, NEW.type, NEW.branch_id) IS DISTINCT FROM (OLD.tenant_id, OLD.type, OLD.branch_id)
    THEN
        RAISE EXCEPTION 'retail_document_series: a counter only goes up by one'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_document_series_guard_update BEFORE UPDATE ON retail_document_series
    FOR EACH ROW EXECUTE FUNCTION retail_document_series_guard_update();
CREATE TRIGGER retail_document_series_reject_delete BEFORE DELETE ON retail_document_series
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------------------------
-- retail_quotations and its lines: prices offered to a buyer, valid until a date. A quotation reserves no stock and
-- posts nothing. Its lines are append-only and the header changes only once, from open to converted (to the sale it
-- became) or to cancelled, so what was offered is always what was written. Whether it has expired is a question of
-- valid_until against the business date and is never stored.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_quotations (
    id                uuid         NOT NULL PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenants (id),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    created_by        uuid         NOT NULL,
    version           integer      NOT NULL DEFAULT 1,
    branch_id         uuid         NOT NULL,
    quote_date        date         NOT NULL,
    valid_until       date         NOT NULL,
    customer_id       uuid,
    buyer_name        varchar(200),
    buyer_contact     varchar(100),
    price_list_id     uuid,
    currency          char(3)      NOT NULL REFERENCES currencies (code),
    total_minor       bigint       NOT NULL CHECK (total_minor >= 0 AND total_minor <= 10000000000000),
    notes             varchar(300),
    status            text         NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'converted', 'cancelled')),
    converted_sale_id uuid,
    converted_at      timestamptz,
    converted_by      uuid,
    cancelled_at      timestamptz,
    cancelled_by      uuid,
    cancel_reason     varchar(300),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES retail_customers (tenant_id, id),
    FOREIGN KEY (tenant_id, price_list_id) REFERENCES retail_price_lists (tenant_id, id),
    FOREIGN KEY (tenant_id, converted_sale_id) REFERENCES retail_sales (tenant_id, id),
    CHECK (valid_until >= quote_date),
    CHECK (customer_id IS NOT NULL OR (buyer_name IS NOT NULL AND btrim(buyer_name) <> '')),
    CHECK ((status = 'converted') = (converted_sale_id IS NOT NULL AND converted_at IS NOT NULL AND converted_by IS NOT NULL)),
    CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL
                                     AND cancel_reason IS NOT NULL AND btrim(cancel_reason) <> ''))
);
-- The list pages on created_at DESC, id DESC (QuotationsRepository), so the index is on exactly that.
CREATE INDEX retail_quotations_list ON retail_quotations (tenant_id, branch_id, created_at DESC, id DESC);
CREATE UNIQUE INDEX retail_quotations_converted_sale ON retail_quotations (tenant_id, converted_sale_id)
    WHERE converted_sale_id IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_quotations');
SELECT bms_grant_app('retail_quotations', 'SELECT, INSERT');
GRANT UPDATE (version, status, converted_sale_id, converted_at, converted_by, cancelled_at, cancelled_by, cancel_reason)
    ON retail_quotations TO bms_app;

CREATE FUNCTION retail_quotations_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF OLD.status <> 'open' OR NEW.status NOT IN ('converted', 'cancelled') OR NEW.version <> OLD.version + 1
       OR (NEW.id, NEW.tenant_id, NEW.created_at, NEW.created_by, NEW.branch_id, NEW.quote_date, NEW.valid_until,
           NEW.customer_id, NEW.buyer_name, NEW.buyer_contact, NEW.price_list_id, NEW.currency, NEW.total_minor, NEW.notes)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.created_at, OLD.created_by, OLD.branch_id, OLD.quote_date, OLD.valid_until,
           OLD.customer_id, OLD.buyer_name, OLD.buyer_contact, OLD.price_list_id, OLD.currency, OLD.total_minor, OLD.notes)
    THEN
        RAISE EXCEPTION 'retail_quotations: an open quotation can only be converted or cancelled, once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_quotations_guard_update BEFORE UPDATE ON retail_quotations
    FOR EACH ROW EXECUTE FUNCTION retail_quotations_guard_update();
CREATE TRIGGER retail_quotations_reject_delete BEFORE DELETE ON retail_quotations
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TABLE retail_quotation_lines (
    id                    uuid          NOT NULL PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenants (id),
    created_at            timestamptz   NOT NULL DEFAULT now(),
    quotation_id          uuid          NOT NULL,
    line_no               smallint      NOT NULL CHECK (line_no >= 1),
    product_id            uuid          NOT NULL,
    qty                   numeric(14,3) NOT NULL CHECK (qty > 0),
    unit_price_minor      bigint        NOT NULL CHECK (unit_price_minor >= 0 AND unit_price_minor <= 10000000000000),
    line_total_minor      bigint        NOT NULL CHECK (line_total_minor >= 0 AND line_total_minor <= 10000000000000),
    sale_unit_id          uuid,
    sale_qty              numeric(14,3),
    sale_unit_price_minor bigint,
    unit_factor           numeric(14,3),
    UNIQUE (tenant_id, id),
    UNIQUE (quotation_id, line_no),
    FOREIGN KEY (tenant_id, quotation_id) REFERENCES retail_quotations (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    FOREIGN KEY (tenant_id, sale_unit_id) REFERENCES retail_units (tenant_id, id),
    CHECK ((sale_unit_id IS NULL AND sale_qty IS NULL AND sale_unit_price_minor IS NULL AND unit_factor IS NULL)
        OR (sale_unit_id IS NOT NULL AND sale_qty > 0 AND unit_factor >= 1 AND qty = sale_qty * unit_factor
            AND sale_unit_price_minor >= 0 AND sale_unit_price_minor <= 10000000000000))
);
SELECT bms_apply_tenant_rls('retail_quotation_lines');
SELECT bms_grant_app('retail_quotation_lines', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_quotation_lines');

-- ---------------------------------------------------------------------------------------------
-- retail_documents: one row per issued document, immutable. The snapshot is everything the document shows (business
-- header, buyer, lines as sold, totals, currency, the price list that applied), so a reprint is identical after a
-- price, a product or a customer changes, and the stored PDF is a rendering of it. The row is never updated: whether a
-- document is void is read from its source (a voided sale voids its invoice and delivery note, a cancelled quotation
-- its document) and the number is kept, never reused. seq_no is the counter value the number was built from; the unique
-- key on it is the database's own check that a series has no duplicate. A sale holds at most one invoice and one
-- delivery note, a transfer at most one delivery note, a quotation exactly one document. A credit note is not a row here:
-- the return numbers it (V45) and its PDF is stored in documents under that number (subject type retail.credit_note).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_documents (
    id                 uuid         NOT NULL PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants (id),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         uuid         NOT NULL,
    type               text         NOT NULL CHECK (type IN ('quotation', 'invoice', 'delivery_note')),
    number             varchar(40)  NOT NULL,
    seq_no             integer      NOT NULL CHECK (seq_no >= 1),
    branch_id          uuid         NOT NULL,
    issue_date         date         NOT NULL,
    sale_id            uuid,
    transfer_id        uuid,
    quotation_id       uuid,
    source_document_id uuid,
    buyer_name         varchar(200),
    currency           char(3)      NOT NULL REFERENCES currencies (code),
    total_minor        bigint       CHECK (total_minor >= 0),
    snapshot           jsonb        NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, type, number),
    UNIQUE (tenant_id, type, branch_id, seq_no),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES retail_sales (tenant_id, id),
    FOREIGN KEY (tenant_id, transfer_id) REFERENCES retail_transfers (tenant_id, id),
    FOREIGN KEY (tenant_id, quotation_id) REFERENCES retail_quotations (tenant_id, id),
    FOREIGN KEY (tenant_id, source_document_id) REFERENCES retail_documents (tenant_id, id),
    CHECK ((type = 'quotation') = (quotation_id IS NOT NULL)),
    CHECK (num_nonnulls(sale_id, transfer_id) <= 1),
    CHECK (type <> 'invoice' OR sale_id IS NOT NULL),
    CHECK (type <> 'delivery_note' OR num_nonnulls(sale_id, transfer_id) = 1)
);
CREATE UNIQUE INDEX retail_documents_sale ON retail_documents (tenant_id, sale_id, type)
    WHERE sale_id IS NOT NULL AND type IN ('invoice', 'delivery_note');
CREATE UNIQUE INDEX retail_documents_transfer ON retail_documents (tenant_id, transfer_id, type)
    WHERE transfer_id IS NOT NULL;
CREATE UNIQUE INDEX retail_documents_quotation ON retail_documents (tenant_id, quotation_id)
    WHERE quotation_id IS NOT NULL;
CREATE INDEX retail_documents_list ON retail_documents (tenant_id, branch_id, created_at DESC, id DESC);
SELECT bms_apply_tenant_rls('retail_documents');
SELECT bms_grant_app('retail_documents', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_documents');

-- ---------------------------------------------------------------------------------------------
-- retail_credit_note_issues: who issued the credit note PDF of a return, on which business day, and the buyer's name and phone
-- as they read then. The return's own rows are the record of the goods and the money; this is only the facts of the document that
-- the return does not keep (the issuer, the issue date, the buyer as named at the time), so that the copy marked void after a void
-- carries the issuer, the issue date and the buyer of the original and not those of whoever asks later. One row per return,
-- written with the first PDF, append-only.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_credit_note_issues (
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    return_id     uuid         NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    issued_by     uuid         NOT NULL,
    issued_on     date         NOT NULL,
    buyer_name    varchar(200) NOT NULL,
    buyer_contact varchar(100),
    PRIMARY KEY (tenant_id, return_id),
    FOREIGN KEY (tenant_id, return_id) REFERENCES retail_sale_returns (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_credit_note_issues');
SELECT bms_grant_app('retail_credit_note_issues', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_credit_note_issues');

-- ---------------------------------------------------------------------------------------------
-- The permission (chapter 8 section 8.3.2; SDD matrix equals the seeds). Issuing and reprinting a document, making a
-- quotation and producing a customer statement PDF. Administrators only, as the spec says (6.2): the sales role gets
-- it only if an administrator chooses, and there is no per-role switch yet. Converting a quotation also needs
-- retail.sale.create, which the conversion makes use of. Not money-moving: a document posts nothing.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.document.issue', 'retail', 'Documents: issue and reprint quotations, invoices, delivery notes and credit note PDFs, and customer statements', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.document.issue');
