-- V29: lending increment 5, disbursement and repayments (issue #108; FR-DIS-01 to FR-DIS-04,
-- FR-REP-01 to FR-REP-06, FR-LCL-01 to FR-LCL-03, FR-DOC-04; ADR-026).
--
-- Additive only (chapter 6 section 6.9): three new tables and CHECKs on lending_loans columns
-- that V8 created with a default of 0 and nothing has written since, so every existing row (on
-- staging, retail tenants' databases included) already satisfies them. No existing row changes.

-- ---------------------------------------------------------------------------------------------
-- lending_loans: balances are never negative.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE lending_loans
    ADD CONSTRAINT lending_loans_balances_not_negative CHECK (
        principal_disbursed_minor >= 0 AND principal_outstanding_minor >= 0 AND interest_outstanding_minor >= 0
        AND fees_outstanding_minor >= 0 AND penalties_outstanding_minor >= 0 AND arrears_minor >= 0
        AND days_past_due >= 0 AND total_paid_minor >= 0 AND credit_balance_minor >= 0);

-- ---------------------------------------------------------------------------------------------
-- lending_schedule_items (FR-DIS-02, FR-DIS-04). Written once at disbursement; afterwards only the
-- paid, waived, written-off and status columns move (and penalties_due_minor, from increment 6).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_schedule_items (
    id                     uuid        NOT NULL PRIMARY KEY,
    tenant_id              uuid        NOT NULL REFERENCES tenants (id),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz,
    version                integer     NOT NULL DEFAULT 1,
    loan_id                uuid        NOT NULL,
    item_no                smallint    NOT NULL CHECK (item_no >= 1),
    due_date               date        NOT NULL,
    principal_due_minor    bigint      NOT NULL DEFAULT 0 CHECK (principal_due_minor >= 0),
    interest_due_minor     bigint      NOT NULL DEFAULT 0 CHECK (interest_due_minor >= 0),
    fees_due_minor         bigint      NOT NULL DEFAULT 0 CHECK (fees_due_minor >= 0),
    penalties_due_minor    bigint      NOT NULL DEFAULT 0 CHECK (penalties_due_minor >= 0),
    principal_paid_minor   bigint      NOT NULL DEFAULT 0 CHECK (principal_paid_minor >= 0),
    interest_paid_minor    bigint      NOT NULL DEFAULT 0 CHECK (interest_paid_minor >= 0),
    fees_paid_minor        bigint      NOT NULL DEFAULT 0 CHECK (fees_paid_minor >= 0),
    penalties_paid_minor   bigint      NOT NULL DEFAULT 0 CHECK (penalties_paid_minor >= 0),
    interest_waived_minor  bigint      NOT NULL DEFAULT 0 CHECK (interest_waived_minor >= 0),
    fees_waived_minor      bigint      NOT NULL DEFAULT 0 CHECK (fees_waived_minor >= 0),
    penalties_waived_minor bigint      NOT NULL DEFAULT 0 CHECK (penalties_waived_minor >= 0),
    written_off_minor      bigint      NOT NULL DEFAULT 0 CHECK (written_off_minor >= 0),
    carried_arrears_minor  bigint      NOT NULL DEFAULT 0 CHECK (carried_arrears_minor >= 0),
    status                 text        NOT NULL
                                       CHECK (status IN ('pending', 'due', 'overdue', 'partially_paid', 'paid', 'waived',
                                                         'written_off')),
    paid_on                date,
    UNIQUE (tenant_id, id),
    UNIQUE (loan_id, item_no),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    CHECK (principal_paid_minor <= principal_due_minor),
    CHECK (interest_paid_minor + interest_waived_minor <= interest_due_minor),
    CHECK (fees_paid_minor + fees_waived_minor <= fees_due_minor),
    CHECK (penalties_paid_minor + penalties_waived_minor <= penalties_due_minor)
);
CREATE INDEX lending_schedule_items_due ON lending_schedule_items (tenant_id, due_date, status);
CREATE INDEX lending_schedule_items_loan ON lending_schedule_items (tenant_id, loan_id, item_no);
SELECT bms_apply_tenant_rls('lending_schedule_items');
SELECT bms_grant_app('lending_schedule_items', 'SELECT, INSERT, UPDATE');

-- The contracted amounts and dates of an item never change once written (FR-PRD-04 carried into
-- the loan): a correction is a restructure (FR-LCL-04), not an edit.
CREATE FUNCTION lending_schedule_items_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.loan_id <> OLD.loan_id OR NEW.item_no <> OLD.item_no OR NEW.due_date <> OLD.due_date
            OR NEW.principal_due_minor <> OLD.principal_due_minor OR NEW.interest_due_minor <> OLD.interest_due_minor
            OR NEW.fees_due_minor <> OLD.fees_due_minor THEN
        RAISE EXCEPTION 'a schedule item''s due date and contracted amounts do not change'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER lending_schedule_items_guard BEFORE UPDATE ON lending_schedule_items
    FOR EACH ROW EXECUTE FUNCTION lending_schedule_items_guard();

-- ---------------------------------------------------------------------------------------------
-- lending_loan_transactions (append-only): every money event on a loan, each with its journal.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_loan_transactions (
    id                  uuid         NOT NULL PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    branch_id           uuid         NOT NULL,
    loan_id             uuid         NOT NULL,
    txn_type            text         NOT NULL
                                     CHECK (txn_type IN ('disbursement', 'fee_upfront', 'repayment', 'recovery', 'reversal',
                                                         'waiver', 'write_off', 'restructure_out', 'restructure_in',
                                                         'credit_refund', 'credit_to_savings')),
    amount_minor        bigint       NOT NULL CHECK (amount_minor > 0),
    currency            char(3)      NOT NULL REFERENCES currencies (code),
    value_date          date         NOT NULL,
    payment_method_key  text         CHECK (payment_method_key IN ('cash', 'bank', 'mtn_momo', 'airtel_money')),
    external_reference  varchar(100),
    -- The receipt number of a repayment or recovery, or the voucher number of a disbursement
    -- (FR-DOC-04: RC- or VC-, the branch code, six digits, gap-free per branch).
    receipt_no          varchar(30),
    reason              text,
    reverses_txn_id     uuid,
    journal_entry_id    uuid,
    approval_request_id uuid,
    idempotency_key     varchar(100),
    source              text         NOT NULL CHECK (source IN ('staff', 'portal', 'gateway', 'import', 'system')),
    is_historic         boolean      NOT NULL DEFAULT false,
    recorded_by         uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, reverses_txn_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    FOREIGN KEY (tenant_id, reverses_txn_id) REFERENCES lending_loan_transactions (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, approval_request_id) REFERENCES approval_requests (tenant_id, id),
    CHECK (is_historic OR journal_entry_id IS NOT NULL),
    CHECK ((txn_type = 'reversal') = (reverses_txn_id IS NOT NULL))
);
CREATE UNIQUE INDEX lending_loan_transactions_receipt ON lending_loan_transactions (tenant_id, receipt_no)
    WHERE receipt_no IS NOT NULL;
-- FR-DIS-03: one disbursement per loan in the MVP.
CREATE UNIQUE INDEX lending_loan_transactions_one_disbursement ON lending_loan_transactions (tenant_id, loan_id)
    WHERE txn_type = 'disbursement';
CREATE INDEX lending_loan_transactions_loan ON lending_loan_transactions (tenant_id, loan_id, value_date);
CREATE INDEX lending_loan_transactions_branch ON lending_loan_transactions (tenant_id, branch_id, value_date, txn_type);
SELECT bms_apply_tenant_rls('lending_loan_transactions');
SELECT bms_grant_app('lending_loan_transactions', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_loan_transactions');

-- ---------------------------------------------------------------------------------------------
-- lending_repayment_allocations (append-only; R-ALLOC). A row is written by one transaction
-- (transaction_id) for the money of one repayment (applies_to_txn_id): the two are equal on a
-- repayment's own rows; a reversal writes the negatives of the reversed repayment's rows and, when
-- later repayments are re-allocated (FR-REP-05), their differences, attributed to each of them.
-- interest_rebate rows are the early settlement rebate of R-PAYOFF: interest waived, not paid.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_repayment_allocations (
    id                uuid        NOT NULL PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenants (id),
    created_at        timestamptz NOT NULL DEFAULT now(),
    transaction_id    uuid        NOT NULL,
    applies_to_txn_id uuid        NOT NULL,
    schedule_item_id  uuid,
    component         text        NOT NULL
                                  CHECK (component IN ('penalty', 'fee', 'interest', 'principal', 'overpayment',
                                                       'interest_rebate')),
    amount_minor      bigint      NOT NULL CHECK (amount_minor <> 0),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, transaction_id) REFERENCES lending_loan_transactions (tenant_id, id),
    FOREIGN KEY (tenant_id, applies_to_txn_id) REFERENCES lending_loan_transactions (tenant_id, id),
    FOREIGN KEY (tenant_id, schedule_item_id) REFERENCES lending_schedule_items (tenant_id, id),
    CHECK ((component = 'overpayment') = (schedule_item_id IS NULL))
);
CREATE INDEX lending_repayment_allocations_txn ON lending_repayment_allocations (tenant_id, transaction_id);
CREATE INDEX lending_repayment_allocations_applies ON lending_repayment_allocations (tenant_id, applies_to_txn_id);
SELECT bms_apply_tenant_rls('lending_repayment_allocations');
SELECT bms_grant_app('lending_repayment_allocations', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_repayment_allocations');
