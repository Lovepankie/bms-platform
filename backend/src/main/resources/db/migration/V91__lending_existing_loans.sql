-- V91: existing loans brought in from a paper register with their real dates and past repayments (issue #466,
-- ADR-071; FR-EXL-01 to FR-EXL-07). Additive only (chapter 6 section 6.9): one status, five nullable columns and their
-- checks on lending_loans, one new table, one permission. No existing row changes: every check holds for a row with
-- the new columns null, and the widened status check accepts every status a row can hold today.
--
-- V81 to V90 are claimed by open branches (lending V81 to V84, payments V85 and V86, offline sales V87, shop logos V88,
-- referrals V89, barcodes V90), so this takes V91.

-- ---------------------------------------------------------------------------------------------
-- lending_loans: the brought_in status (recorded, waiting for its checker; it counts nowhere until approved) and what
-- the bring-in needs to keep. The real disbursement date waits in proposed_disbursement_date until the approval writes
-- disbursed_on; the maker is submitted_by and the checker approved_by, so the V8 check keeps the checker from being
-- the maker in the database as well as in approval_requests.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE lending_loans DROP CONSTRAINT lending_loans_status_check;
ALTER TABLE lending_loans ADD CONSTRAINT lending_loans_status_check
    CHECK (status IN ('draft', 'submitted', 'appraised', 'approved', 'rejected', 'cancelled', 'active', 'closed',
                      'written_off', 'restructured', 'brought_in')) NOT VALID;
ALTER TABLE lending_loans VALIDATE CONSTRAINT lending_loans_status_check;

ALTER TABLE lending_loans
    -- The business's go-live date the loan was checked against when recorded: its disbursement is before it, and the
    -- opening entry is dated on it (ADR-071 decisions 2 and 4).
    ADD COLUMN go_live_on         date,
    -- The first business date the app counts the loan for penalties: the later of go-live and the approval's date.
    -- No penalty period dated before it is ever charged; days overdue still count from the real due dates.
    ADD COLUMN brought_in_on      date,
    -- The loan's own number in the paper register, if it had one; one live loan per number.
    ADD COLUMN register_ref       varchar(40),
    -- The total the borrower agreed to repay (principal and interest), when the register records that instead of the
    -- product's rate; the schedule then spreads the difference as flat interest.
    ADD COLUMN agreed_total_minor bigint,
    -- The opening entry at go-live (debit loans receivable, credit opening balance equity), when principal was owed.
    ADD COLUMN opening_entry_id   uuid,
    ADD CONSTRAINT lending_loans_opening_entry_fk FOREIGN KEY (tenant_id, opening_entry_id)
        REFERENCES journal_entries (tenant_id, id),
    ADD CONSTRAINT lending_loans_agreed_total_positive CHECK (agreed_total_minor IS NULL OR agreed_total_minor > 0),
    ADD CONSTRAINT lending_loans_brought_in_shape CHECK (
        status <> 'brought_in'
        OR (go_live_on IS NOT NULL AND proposed_disbursement_date IS NOT NULL
            AND proposed_disbursement_date < go_live_on AND submitted_by IS NOT NULL)),
    ADD CONSTRAINT lending_loans_brought_in_before_go_live CHECK (
        go_live_on IS NULL OR disbursed_on IS NULL OR disbursed_on < go_live_on),
    ADD CONSTRAINT lending_loans_brought_in_on_after_disbursement CHECK (
        brought_in_on IS NULL OR (disbursed_on IS NOT NULL AND brought_in_on >= go_live_on));

CREATE UNIQUE INDEX lending_loans_register_ref ON lending_loans (tenant_id, lower(register_ref))
    WHERE register_ref IS NOT NULL AND status NOT IN ('cancelled', 'rejected');

-- ---------------------------------------------------------------------------------------------
-- lending_brought_in_repayments (append-only): the repayments the register shows, exactly as the maker entered them,
-- for the checker to see. On approval each becomes a historic lending_loan_transactions row (is_historic, no journal)
-- with its allocation rows, applied in value date order and then line order.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_brought_in_repayments (
    id                 uuid        NOT NULL PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES tenants (id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    loan_id            uuid        NOT NULL,
    line_no            smallint    NOT NULL CHECK (line_no >= 1),
    value_date         date        NOT NULL,
    amount_minor       bigint      NOT NULL CHECK (amount_minor > 0),
    payment_method_key text        NOT NULL CHECK (payment_method_key IN ('cash', 'bank', 'mtn_momo', 'airtel_money')),
    note               varchar(100),
    UNIQUE (tenant_id, id),
    UNIQUE (loan_id, line_no),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id)
);
CREATE INDEX lending_brought_in_repayments_loan ON lending_brought_in_repayments (tenant_id, loan_id, line_no);
SELECT bms_apply_tenant_rls('lending_brought_in_repayments');
SELECT bms_grant_app('lending_brought_in_repayments', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_brought_in_repayments');

-- ---------------------------------------------------------------------------------------------
-- Permission (chapter 8 section 8.3.2): record an existing loan, by the form or the CSV import. Administrators and
-- branch managers hold it; the checker of loan_bring_in needs lending.disbursements.authorise, as for a disbursement.
-- Money moving, so a refusal is audited.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('lending.loans.bring_in', 'lending', 'Loans: record an existing loan with its past repayments', true);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'lending.loans.bring_in'),
    ('branch_manager', 'lending.loans.bring_in')
ON CONFLICT DO NOTHING;
