-- V85: the daily reconciliation of gateway payments and the merchant statement upload (extras slice A5/7, second part, issue #224,
-- task #317; ADR-035 and its amendment of 2026-10-19 for slice A5; docs/specs/client-extras.md sections 3.8, 3.9 and 3.17;
-- FR-PAY-05, FR-PAY-06, FR-PAY-11). Flyway runs with outOfOrder off: V79 is the last on main, the
-- lending pull requests #270, #271, #272 and #290 take V80 to V83, V84 is the unallocated receipts queue (#316, part 1)
-- and this file takes V85. The number is checked again at merge time.
--
-- Expand only, safe on a populated database. Five tenant tables, all created empty, one permission, and two indexes on
-- `payment_intents` (item 5); no existing column, constraint or row is touched:
--
--   1. `payment_statements`: one row per merchant statement a person uploaded (CSV). Identified by the SHA-256 of its bytes, unique
--      per tenant, so the same file uploaded twice is one statement.
--   2. `payment_statement_rows`: one row per statement line, with how it matched the tenant's own payment intents and, when its fee
--      was posted to the gateway charges account, the journal entry that did it. A fee is posted once per order, ever: a unique index
--      over (tenant, intent) where a fee entry exists, however many overlapping statements name the order (FR-PAY-06).
--      A statement also records the days it covers (`covered_from`, `covered_to`): the days the uploader says the export is complete
--      for, or, when none is stated, the days strictly between its first and last line (an export may begin or end mid-day).
--   3. `payment_reconciliation_runs` and `payment_reconciliation_findings`: one run per night (or per manual request) and the
--      differences it listed, kept 90 days. A run posts nothing to a ledger and changes no order's status; its one write besides its own
--      rows is to schedule an immediate look (`next_check_at`, `updated_at`) at an order that is paid and unbooked or unpaid after a
--      day and has nothing scheduled, which the poller then does. A run says when it could not read everything (`truncated`).
--   4. `payment_statement_reviews`: two people's agreement that a statement line is money for something outside this system (one
--      marks it, a second confirms it through the approval `statement_line_outside`), so that the reconciliation stops listing it
--      (audited; the line itself is never changed).
--   5. Two expression indexes on `payment_intents`, over `(tenant_id, lower(provider_reference))` and `(tenant_id,
--      upper(confirmation_code))`, which the statement matching reads through (a lookup by a lower-cased value cannot use the plain
--      unique index).
--   6. The permission `core.payments.manage` (tenant admin and accountant): upload a statement, run the reconciliation now, and mark a
--      line as outside this system; all three need the permission in every branch. Reading the report needs `core.payments.read`,
--      which V2 seeded.
--
-- Statement rows that match no payment intent are listed in the report as differences, not posted to unallocated receipts: the
-- lender's own merchant account can receive money for things that never went through this system, and booking those as a
-- liability would invent one (ADR-035 amendment, decision 52).
--
-- Locks, said plainly. The new tables have foreign keys to `tenants`, `users`, `payment_intents` and `journal_entries`: each
-- takes SHARE ROW EXCLUSIVE on its target until the commit, which blocks writes (not reads) to those tables for the few
-- milliseconds this file takes. The two indexes are built on `payment_intents` itself: CREATE INDEX takes SHARE on it, which blocks
-- its writes (not reads) while the index is built, and Flyway holds that to the commit; the table holds the first lenders' orders
-- (thousands of rows at most, a few milliseconds each). `lock_timeout` makes the migration fail and be retried rather than queue
-- every writer behind it.
--
-- PRIVACY: a statement row holds the provider's order tracking id and confirmation code, amounts and a date; never the payer's
-- phone number or masked account, which the upload ignores if a statement carries them. The file name is stored without any path.

SET LOCAL lock_timeout = '5s';

CREATE TABLE payment_statements (
    id              uuid         NOT NULL PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenants (id),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    uploaded_by     uuid         NOT NULL,
    file_name       varchar(120) NOT NULL CHECK (file_name ~ '^[A-Za-z0-9 ._()+-]{1,120}$'),
    content_sha256  char(64)     NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    row_count       integer      NOT NULL CHECK (row_count BETWEEN 1 AND 5000),
    period_from     date         NOT NULL,
    period_to       date         NOT NULL CHECK (period_to >= period_from),
    currency        char(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    matched_count   integer      NOT NULL CHECK (matched_count >= 0),
    different_count integer      NOT NULL CHECK (different_count >= 0),
    unmatched_count integer      NOT NULL CHECK (unmatched_count >= 0),
    fees_posted_minor bigint     NOT NULL CHECK (fees_posted_minor >= 0),
    -- The days this export is complete for; null when it covers none (a single day, no stated period).
    covered_from    date,
    covered_to      date,
    CHECK ((covered_from IS NULL) = (covered_to IS NULL)),
    CHECK (covered_to IS NULL OR covered_to >= covered_from),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, content_sha256),
    FOREIGN KEY (tenant_id, uploaded_by) REFERENCES users (tenant_id, id)
);
CREATE INDEX payment_statements_by_tenant ON payment_statements (tenant_id, created_at DESC);
SELECT bms_apply_tenant_rls('payment_statements');
SELECT bms_grant_app('payment_statements', 'SELECT, INSERT');

CREATE TABLE payment_statement_rows (
    id                uuid         NOT NULL PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenants (id),
    statement_id      uuid         NOT NULL,
    row_no            integer      NOT NULL CHECK (row_no >= 1),
    order_tracking_id text         CHECK (order_tracking_id ~ '^[0-9a-fA-F-]{36}$'),
    confirmation_code varchar(100),
    amount_minor      bigint       NOT NULL CHECK (amount_minor > 0),
    fee_minor         bigint       NOT NULL DEFAULT 0 CHECK (fee_minor >= 0),
    settled_minor     bigint       CHECK (settled_minor >= 0),
    currency          char(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    paid_on           date         NOT NULL,
    payment_method    varchar(40),
    -- matched: the order is ours, paid, and the amount and currency agree
    -- amount_mismatch / currency_mismatch: the order is ours but the statement says another amount or currency
    -- not_paid_here: the order is ours but this system does not hold it as paid (the provider says it is)
    -- ambiguous: the order tracking id is absent and the confirmation code names more than one of our orders
    -- unmatched: no order of ours has this tracking id or confirmation code
    match_status      text         NOT NULL CHECK (match_status IN ('matched', 'amount_mismatch', 'currency_mismatch', 'not_paid_here', 'ambiguous', 'unmatched')),
    intent_id         uuid,
    fee_entry_id      uuid,
    -- A fee above the automatic rate of the line's amount waits for a second person: the approval request that holds it
    -- (`statement_fee`, ADR-035 decision 65). The fee is posted, and fee_entry_id set, only when a checker approves.
    fee_approval_id   uuid,
    CHECK (order_tracking_id IS NOT NULL OR confirmation_code IS NOT NULL),
    CHECK (fee_approval_id IS NULL OR match_status = 'matched'),
    CHECK (match_status IN ('ambiguous', 'unmatched') OR intent_id IS NOT NULL),
    CHECK (fee_entry_id IS NULL OR match_status = 'matched'),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, statement_id, row_no),
    FOREIGN KEY (tenant_id, statement_id) REFERENCES payment_statements (tenant_id, id),
    FOREIGN KEY (tenant_id, intent_id) REFERENCES payment_intents (tenant_id, id),
    FOREIGN KEY (tenant_id, fee_entry_id) REFERENCES journal_entries (tenant_id, id)
);
-- A fee is posted once per order, whatever statements name it (FR-PAY-06); the journal's own idempotency key says the same.
CREATE UNIQUE INDEX payment_statement_rows_one_fee_per_order
    ON payment_statement_rows (tenant_id, intent_id) WHERE fee_entry_id IS NOT NULL;
CREATE INDEX payment_statement_rows_by_intent ON payment_statement_rows (tenant_id, intent_id) WHERE intent_id IS NOT NULL;
SELECT bms_apply_tenant_rls('payment_statement_rows');
SELECT bms_grant_app('payment_statement_rows', 'SELECT, INSERT');
-- The one change the application makes to a line: the fee a checker approved is posted and its entry recorded, once.
GRANT UPDATE (fee_entry_id) ON payment_statement_rows TO bms_app;

CREATE FUNCTION payment_statement_rows_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.fee_entry_id IS NOT NULL OR NEW.fee_entry_id IS NULL OR OLD.fee_approval_id IS NULL THEN
        RAISE EXCEPTION 'payment_statement_rows: only a held fee is posted, once, by its approval'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    -- The column grant lets the application change nothing else; the owner is held to the same.
    IF NEW.id <> OLD.id OR NEW.intent_id IS DISTINCT FROM OLD.intent_id OR NEW.fee_minor <> OLD.fee_minor
       OR NEW.match_status <> OLD.match_status OR NEW.fee_approval_id IS DISTINCT FROM OLD.fee_approval_id THEN
        RAISE EXCEPTION 'payment_statement_rows: nothing but the fee entry of a line changes'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER payment_statement_rows_guard BEFORE UPDATE ON payment_statement_rows
    FOR EACH ROW EXECUTE FUNCTION payment_statement_rows_guard();

CREATE TABLE payment_reconciliation_runs (
    id                uuid         NOT NULL PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenants (id),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    ran_on            date         NOT NULL,
    trigger           text         NOT NULL CHECK (trigger IN ('job', 'manual')),
    started_by        uuid,
    window_from       date         NOT NULL,
    intents_examined  integer      NOT NULL CHECK (intents_examined >= 0),
    statements_used   integer      NOT NULL CHECK (statements_used >= 0),
    finding_count     integer      NOT NULL CHECK (finding_count >= 0),
    -- The run could not read everything it looks at (more orders or statement lines than it holds in memory): what it lists is
    -- what it could see, and it lists nothing that needs the whole.
    truncated         boolean      NOT NULL DEFAULT false,
    UNIQUE (tenant_id, id)
);
CREATE INDEX payment_reconciliation_runs_latest ON payment_reconciliation_runs (tenant_id, created_at DESC);
-- The nightly job writes one run per business and day; a job run again the same day (a retry, a second node) writes none.
CREATE UNIQUE INDEX payment_reconciliation_runs_one_job_a_day ON payment_reconciliation_runs (tenant_id, ran_on) WHERE trigger = 'job';
SELECT bms_apply_tenant_rls('payment_reconciliation_runs');
SELECT bms_grant_app('payment_reconciliation_runs', 'SELECT, INSERT, DELETE');

CREATE TABLE payment_reconciliation_findings (
    id                uuid         NOT NULL PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenants (id),
    run_id            uuid         NOT NULL,
    -- completed_not_booked        the provider says paid (verified) and nothing is booked, over an hour ago
    -- pending_over_24h            an order still unpaid a day after it was made
    -- paid_differs                paid less or more than ordered
    -- reversed_awaiting_review    the provider reports a booked payment reversed and no person has decided yet
    -- reversal_handed_over        the same, handed to a person (three requests that stood, or a rejection): nothing is waiting
    -- reversal_blocked            the reversal was approved-attempted and refused (a deposit already withdrawn): a shortfall
    -- reversed_after_refund       the provider reports reversed money the lender already sent back by hand: a loss
    -- manual_receipt_duplicate    a receipt a cashier keyed by hand has the reference of a gateway confirmation code
    -- in_provider_not_in_bms      a statement line no order of ours has, or one this system does not hold as paid
    -- in_bms_not_in_provider      a payment booked here on a day a statement covers, missing from every statement that covers it
    -- amount_mismatch / currency_mismatch   the statement and this system disagree on the money of one order
    -- ambiguous_statement_row     a statement line whose confirmation code names more than one order
    kind              text         NOT NULL CHECK (kind IN ('completed_not_booked', 'pending_over_24h', 'paid_differs',
                                       'reversed_awaiting_review', 'reversal_handed_over', 'reversal_blocked',
                                       'reversed_after_refund',
                                       'manual_receipt_duplicate', 'in_provider_not_in_bms', 'in_bms_not_in_provider',
                                       'amount_mismatch', 'currency_mismatch', 'ambiguous_statement_row')),
    intent_id         uuid,
    statement_row_id  uuid,
    -- the manual loan or savings transaction of a duplicate, and the transaction the gateway booked
    other_txn_id      uuid,
    order_tracking_id text         CHECK (order_tracking_id ~ '^[0-9a-fA-F-]{36}$'),
    confirmation_code varchar(100),
    bms_amount_minor  bigint,
    provider_amount_minor bigint,
    currency          char(3)      CHECK (currency ~ '^[A-Z]{3}$'),
    branch_id         uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES payment_reconciliation_runs (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX payment_reconciliation_findings_by_run ON payment_reconciliation_findings (tenant_id, run_id, kind);
SELECT bms_apply_tenant_rls('payment_reconciliation_findings');
SELECT bms_grant_app('payment_reconciliation_findings', 'SELECT, INSERT');

CREATE TABLE payment_statement_reviews (
    id            uuid         NOT NULL PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    row_id        uuid         NOT NULL,
    -- Who marked the line (the maker of the statement_line_outside request) and who confirmed it (its checker): never the
    -- same person (ADR-035 decision 66). The row is written only when the second person confirms.
    reviewed_by   uuid         NOT NULL,
    confirmed_by  uuid         NOT NULL,
    note          varchar(300) NOT NULL CHECK (char_length(btrim(note)) >= 3),
    CHECK (confirmed_by <> reviewed_by),
    UNIQUE (tenant_id, row_id),
    FOREIGN KEY (tenant_id, row_id) REFERENCES payment_statement_rows (tenant_id, id),
    FOREIGN KEY (tenant_id, reviewed_by) REFERENCES users (tenant_id, id),
    FOREIGN KEY (tenant_id, confirmed_by) REFERENCES users (tenant_id, id)
);
SELECT bms_apply_tenant_rls('payment_statement_reviews');
SELECT bms_grant_app('payment_statement_reviews', 'SELECT, INSERT');

CREATE INDEX payment_intents_lower_provider_reference ON payment_intents (tenant_id, lower(provider_reference))
    WHERE provider_reference IS NOT NULL;
CREATE INDEX payment_intents_upper_confirmation_code ON payment_intents (tenant_id, upper(confirmation_code))
    WHERE confirmation_code IS NOT NULL;

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('core.payments.manage', 'core', 'Payments: upload the merchant statement and run the reconciliation', true);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'core.payments.manage'),
    ('accountant', 'core.payments.manage')
ON CONFLICT DO NOTHING;
