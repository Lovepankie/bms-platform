-- V30: lending increment 9, savings (issue #151; FR-SAV-01 to FR-SAV-07; ADR-032).
--
-- Additive only (chapter 6 section 6.9): five new tables, and the outbox's channel CHECK widened
-- to accept 'sms' so the savings receipts of chapter 11 section 11.3 can be queued. No existing
-- row changes. Flyway runs with outOfOrder off, so this takes the number after the highest one on
-- main and on any open branch when the pull request is marked ready.

-- ---------------------------------------------------------------------------------------------
-- notification_outbox: an SMS row may be queued. No SMS sender exists until the aggregator is
-- chosen (pending ADR-013), so such rows stay pending until they expire and the nightly purge
-- clears their parameters (ADR-032 decision 8).
-- ---------------------------------------------------------------------------------------------

ALTER TABLE notification_outbox DROP CONSTRAINT notification_outbox_channel_check;
ALTER TABLE notification_outbox
    ADD CONSTRAINT notification_outbox_channel_check CHECK (channel IN ('email', 'telegram', 'sms'));

-- ---------------------------------------------------------------------------------------------
-- lending_savings_products (FR-SAV-01). The interest fields never change once an account uses the
-- product (a change is a new product); the service refuses it and the trigger below enforces it.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_savings_products (
    id                             uuid        NOT NULL PRIMARY KEY,
    tenant_id                      uuid        NOT NULL REFERENCES tenants (id),
    created_at                     timestamptz NOT NULL DEFAULT now(),
    updated_at                     timestamptz,
    version                        integer     NOT NULL DEFAULT 1,
    code                           varchar(20) NOT NULL CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{1,19}$'),
    name                           text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    currency                       char(3)     NOT NULL REFERENCES currencies (code),
    -- Per year, in basis points; 0 with interest_calc 'none'.
    interest_rate_bp               integer     NOT NULL DEFAULT 0 CHECK (interest_rate_bp BETWEEN 0 AND 10000),
    interest_calc                  text        NOT NULL CHECK (interest_calc IN ('none', 'daily_balance', 'minimum_monthly_balance')),
    interest_posting               text        NOT NULL CHECK (interest_posting IN ('monthly', 'quarterly', 'yearly')),
    -- A day (daily_balance) or a month (minimum_monthly_balance) below this earns nothing.
    min_balance_for_interest_minor bigint      NOT NULL DEFAULT 0 CHECK (min_balance_for_interest_minor >= 0),
    min_opening_balance_minor      bigint      NOT NULL DEFAULT 0 CHECK (min_opening_balance_minor >= 0),
    min_balance_minor              bigint      NOT NULL DEFAULT 0 CHECK (min_balance_minor >= 0),
    withdrawal_fee_minor           bigint      NOT NULL DEFAULT 0 CHECK (withdrawal_fee_minor >= 0),
    -- Withdrawal limits: the most one withdrawal may take, and how many a calendar month allows.
    max_withdrawal_minor           bigint      CHECK (max_withdrawal_minor > 0),
    max_withdrawals_per_month      integer     CHECK (max_withdrawals_per_month > 0),
    dormancy_days                  integer     CHECK (dormancy_days > 0),
    status                         text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'archived')),
    created_by                     uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code),
    CHECK ((interest_calc = 'none') = (interest_rate_bp = 0))
);
SELECT bms_apply_tenant_rls('lending_savings_products');
SELECT bms_grant_app('lending_savings_products', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- lending_savings_accounts (FR-SAV-02). balance_minor moves only under the row lock, with the
-- transaction that records it (FR-SAV-04). balances_through is the last day whose end-of-day
-- balance the nightly job has written; a movement is never dated on or before it.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_savings_accounts (
    id                     uuid        NOT NULL PRIMARY KEY,
    tenant_id              uuid        NOT NULL REFERENCES tenants (id),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz,
    version                integer     NOT NULL DEFAULT 1,
    branch_id              uuid        NOT NULL,
    account_no             varchar(20) NOT NULL CHECK (account_no ~ '^SV[0-9]{6}$'),
    member_id              uuid        NOT NULL,
    product_id             uuid        NOT NULL,
    currency               char(3)     NOT NULL REFERENCES currencies (code),
    status                 text        NOT NULL CHECK (status IN ('active', 'dormant', 'frozen', 'closed')),
    balance_minor          bigint      NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    hold_minor             bigint      NOT NULL DEFAULT 0 CHECK (hold_minor >= 0),
    opened_on              date        NOT NULL,
    closed_on              date,
    last_member_txn_on     date,
    last_interest_posted_to date,
    balances_through       date,
    -- The movement count, so the statement order and running balance are exact (FR-SAV-04).
    txn_count              integer     NOT NULL DEFAULT 0 CHECK (txn_count >= 0),
    status_reason          text,
    created_by             uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, account_no),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, member_id) REFERENCES lending_members (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES lending_savings_products (tenant_id, id),
    CHECK ((status = 'closed') = (closed_on IS NOT NULL)),
    CHECK (status <> 'closed' OR balance_minor = 0)
);
CREATE INDEX lending_savings_accounts_member ON lending_savings_accounts (tenant_id, member_id);
CREATE INDEX lending_savings_accounts_branch ON lending_savings_accounts (tenant_id, branch_id, status);
CREATE INDEX lending_savings_accounts_product ON lending_savings_accounts (tenant_id, product_id);
SELECT bms_apply_tenant_rls('lending_savings_accounts');
SELECT bms_grant_app('lending_savings_accounts', 'SELECT, INSERT, UPDATE');

CREATE FUNCTION lending_savings_products_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.currency, NEW.interest_rate_bp, NEW.interest_calc, NEW.interest_posting, NEW.min_balance_for_interest_minor)
            IS DISTINCT FROM
       (OLD.currency, OLD.interest_rate_bp, OLD.interest_calc, OLD.interest_posting, OLD.min_balance_for_interest_minor)
       AND EXISTS (SELECT 1 FROM lending_savings_accounts a WHERE a.tenant_id = OLD.tenant_id AND a.product_id = OLD.id) THEN
        RAISE EXCEPTION 'the interest terms of a savings product in use do not change'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER lending_savings_products_guard BEFORE UPDATE ON lending_savings_products
    FOR EACH ROW EXECUTE FUNCTION lending_savings_products_guard();

-- ---------------------------------------------------------------------------------------------
-- lending_savings_transactions (append-only): every movement, each with its journal entry and
-- the running balance after it (FR-SAV-04). A fee row names the withdrawal it was charged on
-- (related_txn_id); a reversal names the row it reverses, at most once.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_savings_transactions (
    id                  uuid         NOT NULL PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    branch_id           uuid         NOT NULL,
    account_id          uuid         NOT NULL,
    seq                 integer      NOT NULL CHECK (seq >= 1),
    txn_type            text         NOT NULL
                                     CHECK (txn_type IN ('deposit', 'withdrawal', 'interest', 'fee', 'transfer_in',
                                                         'transfer_out', 'reversal')),
    amount_minor        bigint       NOT NULL CHECK (amount_minor > 0),
    -- True when the money went into the account; a reversal goes the other way to the row it reverses.
    is_credit           boolean      NOT NULL,
    currency            char(3)      NOT NULL REFERENCES currencies (code),
    balance_after_minor bigint       NOT NULL CHECK (balance_after_minor >= 0),
    value_date          date         NOT NULL,
    payment_method_key  text         CHECK (payment_method_key IN ('cash', 'bank', 'mtn_momo', 'airtel_money')),
    external_reference  varchar(100),
    -- RC- (deposit) or VC- (withdrawal), the branch code and six digits, gap-free per branch.
    receipt_no          varchar(30),
    reason              text,
    related_txn_id      uuid,
    reverses_txn_id     uuid,
    journal_entry_id    uuid         NOT NULL,
    approval_request_id uuid,
    idempotency_key     varchar(100),
    source              text         NOT NULL CHECK (source IN ('staff', 'portal', 'gateway', 'import', 'system')),
    recorded_by         uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, account_id, seq),
    UNIQUE (tenant_id, reverses_txn_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES lending_savings_accounts (tenant_id, id),
    FOREIGN KEY (tenant_id, related_txn_id) REFERENCES lending_savings_transactions (tenant_id, id),
    FOREIGN KEY (tenant_id, reverses_txn_id) REFERENCES lending_savings_transactions (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, approval_request_id) REFERENCES approval_requests (tenant_id, id),
    CHECK ((txn_type = 'reversal') = (reverses_txn_id IS NOT NULL)),
    CHECK (txn_type = 'reversal' OR is_credit = (txn_type IN ('deposit', 'interest', 'transfer_in')))
);
CREATE UNIQUE INDEX lending_savings_transactions_receipt ON lending_savings_transactions (tenant_id, receipt_no)
    WHERE receipt_no IS NOT NULL;
CREATE INDEX lending_savings_transactions_account ON lending_savings_transactions (tenant_id, account_id, value_date, seq);
CREATE INDEX lending_savings_transactions_branch ON lending_savings_transactions (tenant_id, branch_id, value_date, txn_type);
SELECT bms_apply_tenant_rls('lending_savings_transactions');
SELECT bms_grant_app('lending_savings_transactions', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_savings_transactions');

-- ---------------------------------------------------------------------------------------------
-- lending_savings_daily_balances: the end-of-day balance of each account, written once per day by
-- the nightly job (the daily accrual of FR-SAV-05). Interest is derived from these rows exactly
-- and rounded once, at posting.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_savings_daily_balances (
    tenant_id             uuid   NOT NULL REFERENCES tenants (id),
    account_id            uuid   NOT NULL,
    business_date         date   NOT NULL,
    closing_balance_minor bigint NOT NULL CHECK (closing_balance_minor >= 0),
    PRIMARY KEY (tenant_id, account_id, business_date),
    FOREIGN KEY (tenant_id, account_id) REFERENCES lending_savings_accounts (tenant_id, id)
);
SELECT bms_apply_tenant_rls('lending_savings_daily_balances');
SELECT bms_grant_app('lending_savings_daily_balances', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_savings_daily_balances');

-- ---------------------------------------------------------------------------------------------
-- lending_savings_interest_postings (append-only): one row per account and period end, so a
-- posting runs once however often the job runs (FR-SAV-05). transaction_id is null when the
-- period earned nothing.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_savings_interest_postings (
    id             uuid        NOT NULL PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenants (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    account_id     uuid        NOT NULL,
    period_start   date        NOT NULL,
    period_end     date        NOT NULL,
    interest_minor bigint      NOT NULL CHECK (interest_minor >= 0),
    transaction_id uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, account_id, period_end),
    FOREIGN KEY (tenant_id, account_id) REFERENCES lending_savings_accounts (tenant_id, id),
    FOREIGN KEY (tenant_id, transaction_id) REFERENCES lending_savings_transactions (tenant_id, id),
    CHECK (period_start <= period_end),
    CHECK ((interest_minor = 0) = (transaction_id IS NULL))
);
SELECT bms_apply_tenant_rls('lending_savings_interest_postings');
SELECT bms_grant_app('lending_savings_interest_postings', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_savings_interest_postings');
