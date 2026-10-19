-- V59: loan repayment by mobile money into the lender's own account (extras slice A3, issue #216; ADR-035 and its
-- amendments of 2026-10-15 and 2026-10-18; docs/specs/client-extras.md sections 3.4, 3.4.1 and 3.7). Flyway runs with
-- outOfOrder off: this number was the next free one on main and on every open branch when the work began.
--
-- Expand only, safe on a populated database. Four things and nothing else:
--
--   1. `lending_loan_transactions.payment_method_key` accepts `gateway` (the payment method of a repayment the payments
--      module booked, mapped to a ledger account by the tenant, FR-GL-08). The old check is replaced by a superset, so
--      every existing row satisfies it. It is added NOT VALID and validated in the same transaction: that gains no lock
--      relief (Flyway holds the ACCESS EXCLUSIVE lock to the commit) and is kept only so the validation is explicit.
--   2. A unique index over (tenant, payment method, external reference) for the method `gateway`, so an order tracking id
--      (the provider's reference, unique per payment; the short confirmation code is not) books one repayment however
--      many times it is presented (spec 3.4.1). It is partial: no existing row has the
--      method `gateway` (the check above refused it until now), so building it scans the table once and finds nothing to
--      collide with. A reversal row copies the method but never the reference, and the index names the two types that
--      carry one.
--   3. `unallocated_receipts`: money the provider confirmed but a purpose handler refused (a closed loan, a value date
--      the books cannot take, an unmapped method). It is posted out of `gateway_clearing` into the liability
--      `unallocated_receipts` in the same transaction that ends the intent `booked_unallocated`, and waits here for a
--      cashier to allocate or refund it (FR-PAY-04, a later slice). The table is append-only for now: the application role
--      gets SELECT and INSERT, and the slice that allocates a receipt adds the update with its own guard.
--
-- Locks, said plainly: the check swap takes ACCESS EXCLUSIVE on `lending_loan_transactions` and holds it to the commit
-- (one transaction), so the validation scan and the index build both run with writes to that table blocked. Both are short on the tables of
-- the first tenants; `lock_timeout` makes the migration fail and be retried rather than queue every writer behind it.
--
-- PRIVACY: the table holds identifiers and an amount, never the payer's phone number or a provider body.

SET LOCAL lock_timeout = '5s';

ALTER TABLE lending_loan_transactions DROP CONSTRAINT lending_loan_transactions_payment_method_key_check;
ALTER TABLE lending_loan_transactions ADD CONSTRAINT lending_loan_transactions_payment_method_key_check
    CHECK (payment_method_key IN ('cash', 'bank', 'mtn_momo', 'airtel_money', 'gateway')) NOT VALID;
ALTER TABLE lending_loan_transactions VALIDATE CONSTRAINT lending_loan_transactions_payment_method_key_check;

CREATE UNIQUE INDEX lending_loan_transactions_gateway_reference
    ON lending_loan_transactions (tenant_id, payment_method_key, external_reference)
    WHERE payment_method_key = 'gateway' AND external_reference IS NOT NULL
      AND txn_type IN ('repayment', 'recovery');

-- Backstop of the booker's one-booking-per-intent rule: no repayment, deposit or other subject transaction is the booking of
-- two intents. Partial, and `payment_intents` holds no booked row yet on any server, so the build scans nothing that can collide.
CREATE UNIQUE INDEX payment_intents_one_booking_per_txn
    ON payment_intents (tenant_id, booked_subject_txn_id) WHERE booked_subject_txn_id IS NOT NULL;

CREATE TABLE unallocated_receipts (
    id                uuid         NOT NULL PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenants (id),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    branch_id         uuid         NOT NULL,
    -- The payment intent whose money this is: one receipt per intent, whatever happens to the booking after it.
    intent_id         uuid         NOT NULL,
    purpose           text         NOT NULL CHECK (purpose ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    subject_type      text         NOT NULL CHECK (subject_type ~ '^[a-z][a-z0-9_]{1,39}$'),
    subject_id        uuid         NOT NULL,
    amount_minor      bigint       NOT NULL CHECK (amount_minor > 0),
    currency          char(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    value_date        date         NOT NULL,
    confirmation_code varchar(100),
    -- Why the handler refused: a closed vocabulary of lower case words, never provider text.
    refusal_code      varchar(60)  NOT NULL CHECK (refusal_code ~ '^[a-z][a-z0-9_]{1,59}$'),
    status            text         NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'allocated', 'refunded')),
    journal_entry_id  uuid         NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, intent_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, intent_id) REFERENCES payment_intents (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id)
);
-- The cashier's queue: what is still open, oldest first.
CREATE INDEX unallocated_receipts_open ON unallocated_receipts (tenant_id, created_at) WHERE status = 'open';

SELECT bms_apply_tenant_rls('unallocated_receipts');
SELECT bms_grant_app('unallocated_receipts', 'SELECT, INSERT');
