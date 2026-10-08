-- V32: lending investments (issue #152, increment 10; FR-INV-01 to FR-INV-12, R-INV; ADR-031).
--
-- Additive only (chapter 6 section 6.9): four new tables, two new permissions and their role
-- grants, and one new account in the lending chart (4060 investment penalty income), added to the
-- seeding function and to every tenant that already has the lending chart. No existing row changes.

-- ---------------------------------------------------------------------------------------------
-- Chart of accounts: early withdrawal penalties are income (chapter 6 section 6.6.2).
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION bms_seed_lending_chart(p_tenant_id uuid) RETURNS integer
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    v_currency char(3);
    v_count    integer;
BEGIN
    SELECT currency INTO STRICT v_currency FROM tenants WHERE id = p_tenant_id;

    INSERT INTO gl_accounts (id, tenant_id, code, name, account_type, normal_balance, currency,
                             system_key, is_postable, is_system_controlled)
    SELECT gen_random_uuid(), p_tenant_id, c.code, c.name, c.account_type,
           CASE WHEN c.account_type IN ('asset', 'expense') THEN 'debit' ELSE 'credit' END,
           v_currency, c.system_key, c.postable, c.system_controlled
      FROM (VALUES
        ('1000', 'Assets',                        'asset',     NULL,                         false, false),
        ('1010', 'Cash on hand',                  'asset',     'cash_on_hand',               true,  false),
        ('1020', 'Bank',                          'asset',     'bank',                       true,  false),
        ('1030', 'Mobile money: MTN',             'asset',     'mobile_money_mtn',           true,  false),
        ('1031', 'Mobile money: Airtel',          'asset',     'mobile_money_airtel',        true,  false),
        ('1040', 'Payment gateway clearing',      'asset',     'gateway_clearing',           true,  false),
        ('1100', 'Loans receivable',              'asset',     'loans_receivable',           true,  true),
        ('1190', 'Inter-branch clearing',         'asset',     'interbranch_clearing',       true,  true),
        ('2000', 'Liabilities',                   'liability', NULL,                         false, false),
        ('2010', 'Member savings',                'liability', 'member_savings',             true,  true),
        ('2020', 'Member investments payable',    'liability', 'investments_payable',        true,  true),
        ('2021', 'Investment returns payable',    'liability', 'investment_returns_payable', true,  true),
        ('2030', 'Member overpayments',           'liability', 'member_overpayments',        true,  true),
        ('2040', 'Unallocated receipts',          'liability', 'unallocated_receipts',       true,  true),
        ('3000', 'Equity',                        'equity',    NULL,                         false, false),
        ('3010', 'Capital',                       'equity',    'capital',                    true,  false),
        ('3020', 'Retained earnings',             'equity',    'retained_earnings',          true,  false),
        ('3030', 'Opening balance equity',        'equity',    'opening_balance_equity',     true,  false),
        ('4000', 'Income',                        'income',    NULL,                         false, false),
        ('4010', 'Loan interest income',          'income',    'loan_interest_income',       true,  false),
        ('4020', 'Loan penalty income',           'income',    'loan_penalty_income',        true,  false),
        ('4030', 'Loan fee income',               'income',    'loan_fee_income',            true,  false),
        ('4040', 'Bad debt recovered',            'income',    'bad_debt_recovered',         true,  false),
        ('4050', 'Savings fee income',            'income',    'savings_fee_income',         true,  false),
        ('4060', 'Investment penalty income',     'income',    'investment_penalty_income',  true,  false),
        ('5000', 'Expenses',                      'expense',   NULL,                         false, false),
        ('5010', 'Savings interest expense',      'expense',   'savings_interest_expense',   true,  false),
        ('5020', 'Investment return expense',     'expense',   'investment_return_expense',  true,  false),
        ('5030', 'Loan write-off expense',        'expense',   'loan_write_off_expense',     true,  false),
        ('5040', 'Payment gateway charges',       'expense',   'gateway_charges',            true,  false),
        ('5900', 'Operating expenses',            'expense',   'operating_expenses',         true,  false)
      ) AS c (code, name, account_type, system_key, postable, system_controlled)
    ON CONFLICT (tenant_id, code) DO NOTHING;
    GET DIAGNOSTICS v_count = ROW_COUNT;

    -- Every postable account hangs under the header of its class (1000, 2000, ...).
    UPDATE gl_accounts child
       SET parent_id = header.id
      FROM gl_accounts header
     WHERE child.tenant_id = p_tenant_id
       AND header.tenant_id = p_tenant_id
       AND child.is_postable
       AND child.parent_id IS NULL
       AND header.code = left(child.code, 1) || '000';
    RETURN v_count;
END
$$;

-- Tenants that already hold the lending chart get the new account; the function skips the rest.
SELECT bms_seed_lending_chart(t.id)
  FROM tenants t
 WHERE EXISTS (SELECT 1 FROM gl_accounts a WHERE a.tenant_id = t.id AND a.system_key = 'investment_return_expense');

-- ---------------------------------------------------------------------------------------------
-- Permissions (chapter 8 section 8.3): the checker of a funding above the threshold and of a
-- reversal. The maker permissions are the existing lending.investments.fund and .payout.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('lending.investments.fund_approve', 'lending', 'Investments: fund approve', true),
    ('lending.investments.reverse_approve', 'lending', 'Investments: reverse approve', true);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'lending.investments.fund_approve'),
    ('branch_manager', 'lending.investments.fund_approve'),
    ('accountant', 'lending.investments.fund_approve'),
    ('tenant_admin', 'lending.investments.reverse_approve'),
    ('branch_manager', 'lending.investments.reverse_approve'),
    ('accountant', 'lending.investments.reverse_approve');

-- ---------------------------------------------------------------------------------------------
-- lending_investment_products (FR-INV-01, FR-INV-08). Every investment copies the terms it was
-- opened on, so an edit here changes only investments opened afterwards (and rollovers, which take
-- the product's current terms, FR-INV-05).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_investment_products (
    id                          uuid         NOT NULL PRIMARY KEY,
    tenant_id                   uuid         NOT NULL REFERENCES tenants (id),
    created_at                  timestamptz  NOT NULL DEFAULT now(),
    updated_at                  timestamptz,
    version                     integer      NOT NULL DEFAULT 1,
    code                        varchar(20)  NOT NULL,
    name                        varchar(100) NOT NULL,
    currency                    char(3)      NOT NULL REFERENCES currencies (code),
    product_type                text         NOT NULL CHECK (product_type IN ('fixed_term', 'recurring')),
    allowed_terms_months        integer[]    NOT NULL,
    return_rate_bp              integer      NOT NULL CHECK (return_rate_bp BETWEEN 0 AND 100000),
    return_method               text         NOT NULL CHECK (return_method IN ('flat', 'compound')),
    payout_frequency            text         NOT NULL CHECK (payout_frequency IN ('at_maturity', 'monthly', 'quarterly')),
    min_amount_minor            bigint       NOT NULL CHECK (min_amount_minor > 0),
    max_amount_minor            bigint,
    early_withdrawal_allowed    boolean      NOT NULL DEFAULT false,
    early_withdrawal_rule       text         CHECK (early_withdrawal_rule IN ('forfeit_return', 'reduced_rate')),
    early_withdrawal_rate_bp    integer      CHECK (early_withdrawal_rate_bp BETWEEN 0 AND 100000),
    early_withdrawal_penalty_bp integer      NOT NULL DEFAULT 0 CHECK (early_withdrawal_penalty_bp BETWEEN 0 AND 10000),
    status                      text         NOT NULL CHECK (status IN ('active', 'archived')),
    created_by                  uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code),
    CHECK (cardinality(allowed_terms_months) BETWEEN 1 AND 24
           AND 1 <= ALL (allowed_terms_months) AND 120 >= ALL (allowed_terms_months)),
    CHECK (max_amount_minor IS NULL OR max_amount_minor >= min_amount_minor),
    -- R-INV-3: a compounding return is paid at maturity; a periodic payout would take it away.
    CHECK (return_method = 'flat' OR payout_frequency = 'at_maturity'),
    CHECK (early_withdrawal_allowed = (early_withdrawal_rule IS NOT NULL)),
    CHECK ((early_withdrawal_rule = 'reduced_rate') = (early_withdrawal_rate_bp IS NOT NULL)),
    CHECK (early_withdrawal_rate_bp IS NULL OR early_withdrawal_rate_bp <= return_rate_bp)
);
SELECT bms_apply_tenant_rls('lending_investment_products');
SELECT bms_grant_app('lending_investment_products', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- lending_investments (FR-INV-02 to FR-INV-06). Terms are copied from the product at opening.
-- Balances: principal_held_minor is the investments_payable subledger of the investment;
-- return_accrued_minor - return_paid_minor is its investment_returns_payable subledger;
-- return_due_minor is the accrued return whose payout date has come (R-INV-4).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_investments (
    id                          uuid         NOT NULL PRIMARY KEY,
    tenant_id                   uuid         NOT NULL REFERENCES tenants (id),
    created_at                  timestamptz  NOT NULL DEFAULT now(),
    updated_at                  timestamptz,
    version                     integer      NOT NULL DEFAULT 1,
    branch_id                   uuid         NOT NULL,
    account_no                  varchar(20)  NOT NULL,
    member_id                   uuid         NOT NULL,
    product_id                  uuid         NOT NULL,
    currency                    char(3)      NOT NULL REFERENCES currencies (code),
    status                      text         NOT NULL
                                             CHECK (status IN ('pending_funding', 'active', 'matured', 'paid_out',
                                                               'rolled_over', 'withdrawn_early', 'cancelled')),
    principal_minor             bigint       NOT NULL CHECK (principal_minor > 0),
    return_rate_bp              integer      NOT NULL CHECK (return_rate_bp BETWEEN 0 AND 100000),
    return_method               text         NOT NULL CHECK (return_method IN ('flat', 'compound')),
    term_months                 integer      NOT NULL CHECK (term_months BETWEEN 1 AND 120),
    payout_frequency            text         NOT NULL CHECK (payout_frequency IN ('at_maturity', 'monthly', 'quarterly')),
    product_type                text         NOT NULL CHECK (product_type IN ('fixed_term', 'recurring')),
    early_withdrawal_allowed    boolean      NOT NULL,
    early_withdrawal_rule       text         CHECK (early_withdrawal_rule IN ('forfeit_return', 'reduced_rate')),
    early_withdrawal_rate_bp    integer,
    early_withdrawal_penalty_bp integer      NOT NULL DEFAULT 0,
    start_date                  date,
    maturity_date               date,
    agreed_return_minor         bigint,
    principal_held_minor        bigint       NOT NULL DEFAULT 0,
    return_accrued_minor        bigint       NOT NULL DEFAULT 0,
    return_due_minor            bigint       NOT NULL DEFAULT 0,
    return_paid_minor           bigint       NOT NULL DEFAULT 0,
    maturity_instruction        text         CHECK (maturity_instruction IN ('payout', 'rollover_principal', 'rollover_all')),
    rolled_over_from_id         uuid,
    rolled_over_to_id           uuid,
    certificate_no              varchar(30),
    channel                     text         NOT NULL CHECK (channel IN ('staff', 'portal', 'import', 'system')),
    pre_maturity_reminded_on    date,
    post_maturity_reminded_on   date,
    closed_on                   date,
    created_by                  uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, account_no),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, member_id) REFERENCES lending_members (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES lending_investment_products (tenant_id, id),
    FOREIGN KEY (tenant_id, rolled_over_from_id) REFERENCES lending_investments (tenant_id, id),
    FOREIGN KEY (tenant_id, rolled_over_to_id) REFERENCES lending_investments (tenant_id, id),
    CHECK (principal_held_minor >= 0 AND return_accrued_minor >= 0 AND return_due_minor >= 0
           AND return_paid_minor >= 0),
    CHECK (return_paid_minor <= return_due_minor AND return_due_minor <= return_accrued_minor),
    CHECK ((status = 'pending_funding') = (start_date IS NULL)),
    CHECK (start_date IS NULL OR (maturity_date > start_date AND agreed_return_minor IS NOT NULL))
);
CREATE INDEX lending_investments_member ON lending_investments (tenant_id, member_id);
CREATE INDEX lending_investments_branch_status ON lending_investments (tenant_id, branch_id, status);
CREATE INDEX lending_investments_maturity ON lending_investments (tenant_id, status, maturity_date);
CREATE UNIQUE INDEX lending_investments_certificate ON lending_investments (tenant_id, certificate_no)
    WHERE certificate_no IS NOT NULL;
SELECT bms_apply_tenant_rls('lending_investments');
SELECT bms_grant_app('lending_investments', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- lending_investment_schedule_items (FR-INV-09): the accrual and payout schedule, one row per
-- month of the term, written once at funding (R-INV-1 to R-INV-3). Afterwards only the status and
-- the transaction that accrued the row move.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_investment_schedule_items (
    id                uuid        NOT NULL PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenants (id),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz,
    version           integer     NOT NULL DEFAULT 1,
    investment_id     uuid        NOT NULL,
    period_no         smallint    NOT NULL CHECK (period_no >= 1),
    period_start      date        NOT NULL,
    period_end        date        NOT NULL,
    return_minor      bigint      NOT NULL CHECK (return_minor >= 0),
    -- The principal the period's return was computed on (grows month by month when compounding).
    opening_balance_minor bigint  NOT NULL CHECK (opening_balance_minor > 0),
    is_payout         boolean     NOT NULL,
    status            text        NOT NULL CHECK (status IN ('scheduled', 'accrued', 'cancelled')),
    accrued_txn_id    uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (investment_id, period_no),
    FOREIGN KEY (tenant_id, investment_id) REFERENCES lending_investments (tenant_id, id),
    CHECK (period_end > period_start),
    -- A month whose return rounds to zero is accrued with no transaction (nothing to post).
    CHECK (accrued_txn_id IS NULL OR status = 'accrued'),
    CHECK (status <> 'accrued' OR accrued_txn_id IS NOT NULL OR return_minor = 0)
);
CREATE INDEX lending_investment_schedule_items_due ON lending_investment_schedule_items (tenant_id, status, period_end);
SELECT bms_apply_tenant_rls('lending_investment_schedule_items');
SELECT bms_grant_app('lending_investment_schedule_items', 'SELECT, INSERT, UPDATE');

CREATE FUNCTION lending_investment_schedule_items_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.investment_id <> OLD.investment_id OR NEW.period_no <> OLD.period_no
            OR NEW.period_start <> OLD.period_start OR NEW.period_end <> OLD.period_end
            OR NEW.return_minor <> OLD.return_minor OR NEW.opening_balance_minor <> OLD.opening_balance_minor
            OR NEW.is_payout <> OLD.is_payout THEN
        RAISE EXCEPTION 'a schedule period''s dates and agreed return do not change'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER lending_investment_schedule_items_guard BEFORE UPDATE ON lending_investment_schedule_items
    FOR EACH ROW EXECUTE FUNCTION lending_investment_schedule_items_guard();

-- ---------------------------------------------------------------------------------------------
-- lending_investment_transactions (append-only): every money event on an investment, each with
-- its journal. principal_minor, return_minor and penalty_minor break the amount down for the
-- statement; the accrual of a period is unique per investment and period (FR-INV-04, idempotent).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_investment_transactions (
    id                  uuid         NOT NULL PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    branch_id           uuid         NOT NULL,
    investment_id       uuid         NOT NULL,
    txn_type            text         NOT NULL
                                     CHECK (txn_type IN ('funding', 'return_accrual', 'return_payout', 'maturity_payout',
                                                         'early_withdrawal', 'rollover_out', 'rollover_in', 'reversal')),
    amount_minor        bigint       NOT NULL CHECK (amount_minor > 0),
    principal_minor     bigint       NOT NULL DEFAULT 0 CHECK (principal_minor >= 0),
    return_minor        bigint       NOT NULL DEFAULT 0,
    penalty_minor       bigint       NOT NULL DEFAULT 0 CHECK (penalty_minor >= 0),
    currency            char(3)      NOT NULL REFERENCES currencies (code),
    value_date          date         NOT NULL,
    period_no           smallint,
    payment_method_key  text         CHECK (payment_method_key IN ('cash', 'bank', 'mtn_momo', 'airtel_money')),
    external_reference  varchar(100),
    -- The receipt number of a funding or the voucher number of a payout (FR-DOC-04).
    receipt_no          varchar(30),
    reason              text,
    reverses_txn_id     uuid,
    journal_entry_id    uuid         NOT NULL,
    approval_request_id uuid,
    source              text         NOT NULL CHECK (source IN ('staff', 'portal', 'gateway', 'import', 'system')),
    recorded_by         uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, reverses_txn_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, investment_id) REFERENCES lending_investments (tenant_id, id),
    FOREIGN KEY (tenant_id, reverses_txn_id) REFERENCES lending_investment_transactions (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, approval_request_id) REFERENCES approval_requests (tenant_id, id),
    CHECK ((txn_type = 'reversal') = (reverses_txn_id IS NOT NULL)),
    CHECK ((txn_type = 'return_accrual') = (period_no IS NOT NULL))
);
CREATE UNIQUE INDEX lending_investment_transactions_receipt ON lending_investment_transactions (tenant_id, receipt_no)
    WHERE receipt_no IS NOT NULL;
CREATE UNIQUE INDEX lending_investment_transactions_one_funding ON lending_investment_transactions (tenant_id, investment_id)
    WHERE txn_type = 'funding';
CREATE UNIQUE INDEX lending_investment_transactions_one_accrual ON lending_investment_transactions
    (tenant_id, investment_id, period_no) WHERE txn_type = 'return_accrual';
CREATE INDEX lending_investment_transactions_investment ON lending_investment_transactions
    (tenant_id, investment_id, value_date);
CREATE INDEX lending_investment_transactions_branch ON lending_investment_transactions
    (tenant_id, branch_id, value_date, txn_type);
SELECT bms_apply_tenant_rls('lending_investment_transactions');
SELECT bms_grant_app('lending_investment_transactions', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_investment_transactions');

ALTER TABLE lending_investment_schedule_items
    ADD FOREIGN KEY (tenant_id, accrued_txn_id) REFERENCES lending_investment_transactions (tenant_id, id);
