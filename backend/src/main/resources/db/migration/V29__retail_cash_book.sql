-- V29: the retail cash book (ADR-022, issue #147; FR-RET-17 to FR-RET-32): expense categories,
-- items and cash parties, daily savings, cash banked, withdrawals from the bank, expenses,
-- advances to the owner or related parties and their repayments, the ten permissions, and the
-- chart additions (savings reserve, advances receivable, operating expenses).
--
-- Additive except for the widened CHECK of retail_import_refs (chapter 6 section 6.9). Flyway runs
-- with outOfOrder off, so a new migration takes a number above the highest on any open branch:
-- V24 to V28 were held by open pull requests when this was written, so this file is V29 and the
-- owner session renumbers it at merge time if another one lands first.

-- ---------------------------------------------------------------------------------------------
-- Permissions (chapter 8 section 8.3.2; FR-RET-32). The tenant admin holds all ten. The sales role
-- holds read, savings record, banking record and expense record. retail.savings.overwrite is
-- withheld from the sales role pending ADR-022 open question 5. PermissionMatrixIT compares these
-- rows with the matrix in chapter 8.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.cashbook.read', 'retail', 'Cash book: read lists, reports and the daily cash summary', false),
    ('retail.savings.record', 'retail', 'Cash book: record the day''s savings', true),
    ('retail.savings.overwrite', 'retail', 'Cash book: overwrite the suggested savings amount', true),
    ('retail.banking.record', 'retail', 'Cash book: record cash banked', true),
    ('retail.expense.record', 'retail', 'Cash book: record an expense', true),
    ('retail.expense.manage', 'retail', 'Cash book: manage expense categories, items and parties', false),
    ('retail.withdrawal.record', 'retail', 'Cash book: record cash withdrawn from the bank', true),
    ('retail.advance.create', 'retail', 'Cash book: record an advance to the owner or a related party', true),
    ('retail.advance.repay', 'retail', 'Cash book: record a repayment of an advance', true),
    ('retail.cashbook.void', 'retail', 'Cash book: void any cash book record', true);

INSERT INTO role_permissions (role_key, permission_key)
SELECT 'tenant_admin', key FROM permissions
 WHERE key IN ('retail.cashbook.read', 'retail.savings.record', 'retail.savings.overwrite', 'retail.banking.record',
               'retail.expense.record', 'retail.expense.manage', 'retail.withdrawal.record', 'retail.advance.create',
               'retail.advance.repay', 'retail.cashbook.void');

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('retail_sales', 'retail.cashbook.read'),
    ('retail_sales', 'retail.savings.record'),
    ('retail_sales', 'retail.banking.record'),
    ('retail_sales', 'retail.expense.record');

-- ---------------------------------------------------------------------------------------------
-- Chart additions (ADR-022 decision 7): 1015 savings reserve, 1250 advances to owner and related
-- parties, 5900 operating expenses (the lending chart's code and key, reused). The seed function
-- is V22's with three rows added; a code or system key the tenant already has is kept. Tenants
-- that switched retail on earlier get the accounts now.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION bms_seed_retail_chart(p_tenant_id uuid) RETURNS integer
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
        ('1000', 'Assets',                                'asset',     NULL,                     false, false),
        ('1010', 'Cash on hand',                          'asset',     'cash_on_hand',           true,  false),
        ('1015', 'Savings reserve (restricted cash)',     'asset',     'savings_reserve',        true,  false),
        ('1020', 'Bank',                                  'asset',     'bank',                   true,  false),
        ('1035', 'Mobile money',                          'asset',     'mobile_money',           true,  false),
        ('1190', 'Inter-branch clearing',                 'asset',     'interbranch_clearing',   true,  true),
        ('1200', 'Trade debtors',                         'asset',     'trade_debtors',          true,  true),
        ('1250', 'Advances to owner and related parties', 'asset',     'owner_advances',         true,  true),
        ('1300', 'Inventory',                             'asset',     'inventory',              true,  true),
        ('2000', 'Liabilities',                           'liability', NULL,                     false, false),
        ('2100', 'Trade creditors',                       'liability', 'trade_creditors',        true,  true),
        ('3000', 'Equity',                                'equity',    NULL,                     false, false),
        ('3030', 'Opening balance equity',                'equity',    'opening_balance_equity', true,  false),
        ('4000', 'Income',                                'income',    NULL,                     false, false),
        ('4100', 'Sales revenue',                         'income',    'sales_revenue',          true,  false),
        ('5000', 'Expenses',                              'expense',   NULL,                     false, false),
        ('5100', 'Cost of goods sold',                    'expense',   'cost_of_goods_sold',     true,  false),
        ('5110', 'Stock shrinkage',                       'expense',   'stock_shrinkage',        true,  false),
        ('5900', 'Operating expenses',                    'expense',   'operating_expenses',     true,  false)
      ) AS c (code, name, account_type, system_key, postable, system_controlled)
     WHERE NOT EXISTS (SELECT 1 FROM gl_accounts a
                        WHERE a.tenant_id = p_tenant_id
                          AND (a.code = c.code OR a.system_key = c.system_key))
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS v_count = ROW_COUNT;

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
REVOKE ALL ON FUNCTION bms_seed_retail_chart(uuid) FROM PUBLIC;

SELECT bms_seed_retail_chart(tenant_id) FROM tenant_modules WHERE module_key = 'retail';

-- ---------------------------------------------------------------------------------------------
-- The one UPDATE guard of the record tables: only the void columns change, and only once. On
-- retail_advances repaid_minor may change too (see the deferred check below). The owner's
-- bms.allow_mutation switch of reject_mutation applies here as well.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION retail_cashbook_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_skip text[] := ARRAY['voided_at', 'voided_by', 'void_reason'];
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF TG_TABLE_NAME = 'retail_advances' THEN
        v_skip := v_skip || ARRAY['repaid_minor'];
    END IF;
    IF (to_jsonb(NEW) - v_skip) IS DISTINCT FROM (to_jsonb(OLD) - v_skip) OR OLD.voided_at IS NOT NULL THEN
        RAISE EXCEPTION '%: only the void may change, once', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- retail_expense_categories and retail_expense_items (FR-RET-17): the owner's lists, never
-- deleted (active is cleared). A category names the expense account its expenses debit; null
-- means operating_expenses. A name may be edited: records snapshot the names they were written with.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_expense_categories (
    id                 uuid         NOT NULL PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants (id),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz,
    version            integer      NOT NULL DEFAULT 1,
    name               varchar(100) NOT NULL CHECK (btrim(name) <> ''),
    expense_account_id uuid,
    active             boolean      NOT NULL DEFAULT true,
    sort_order         integer      NOT NULL DEFAULT 0,
    created_by         uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, expense_account_id) REFERENCES gl_accounts (tenant_id, id)
);
CREATE UNIQUE INDEX retail_expense_categories_name ON retail_expense_categories (tenant_id, lower(name));
SELECT bms_apply_tenant_rls('retail_expense_categories');
SELECT bms_grant_app('retail_expense_categories', 'SELECT, INSERT, UPDATE');

CREATE FUNCTION retail_expense_category_account_check() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.expense_account_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM gl_accounts a
             WHERE a.tenant_id = NEW.tenant_id AND a.id = NEW.expense_account_id
               AND a.account_type = 'expense' AND a.is_postable AND a.is_active) THEN
        RAISE EXCEPTION 'retail_expense_categories: the account must be an active, postable expense account'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_expense_category_account_check BEFORE INSERT OR UPDATE ON retail_expense_categories
    FOR EACH ROW EXECUTE FUNCTION retail_expense_category_account_check();

CREATE TABLE retail_expense_items (
    id                   uuid         NOT NULL PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenants (id),
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz,
    version              integer      NOT NULL DEFAULT 1,
    category_id          uuid         NOT NULL,
    name                 varchar(100) NOT NULL CHECK (btrim(name) <> ''),
    requires_explanation boolean      NOT NULL DEFAULT false,
    active               boolean      NOT NULL DEFAULT true,
    created_by           uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, category_id) REFERENCES retail_expense_categories (tenant_id, id)
);
CREATE UNIQUE INDEX retail_expense_items_name ON retail_expense_items (tenant_id, category_id, lower(name));
SELECT bms_apply_tenant_rls('retail_expense_items');
SELECT bms_grant_app('retail_expense_items', 'SELECT, INSERT, UPDATE');

-- Beneficiaries and advance parties: one list. There is deliberately no 'company' kind (ADR-022
-- open question 9): an advance to the tenant's own business is not a receivable from anyone.
CREATE TABLE retail_cash_parties (
    id         uuid         NOT NULL PRIMARY KEY,
    tenant_id  uuid         NOT NULL REFERENCES tenants (id),
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz,
    version    integer      NOT NULL DEFAULT 1,
    name       varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    contact    varchar(100),
    kind       text         NOT NULL CHECK (kind IN ('owner', 'staff', 'related_entity', 'supplier', 'other')),
    active     boolean      NOT NULL DEFAULT true,
    created_by uuid,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX retail_cash_parties_name ON retail_cash_parties (tenant_id, kind, lower(name));
SELECT bms_apply_tenant_rls('retail_cash_parties');
SELECT bms_grant_app('retail_cash_parties', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- retail_daily_savings (FR-RET-18 to FR-RET-20): one active record per branch and business date.
-- amount_minor and suggested_minor are profit-derived (ADR-022 decision 13); the API omits them
-- without retail.profit.read. A historical row has no suggestion, so it is never overwritten.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_daily_savings (
    id               uuid         NOT NULL PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    branch_id        uuid         NOT NULL,
    business_date    date         NOT NULL,
    amount_minor     bigint       NOT NULL CHECK (amount_minor >= 0 AND amount_minor <= 10000000000000),
    currency         char(3)      NOT NULL REFERENCES currencies (code),
    suggested_minor  bigint       CHECK (suggested_minor >= 0 AND suggested_minor <= 10000000000000),
    overwritten      boolean      NOT NULL DEFAULT false,
    overwrite_reason varchar(300),
    total_sold_minor bigint       NOT NULL DEFAULT 0 CHECK (total_sold_minor >= 0),
    occurred_at      timestamptz  NOT NULL,
    recorded_by      uuid         NOT NULL,
    journal_entry_id uuid,
    historical       boolean      NOT NULL DEFAULT false,
    voided_at        timestamptz,
    voided_by        uuid,
    void_reason      varchar(300),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK ((suggested_minor IS NULL AND NOT overwritten)
        OR (suggested_minor IS NOT NULL AND overwritten = (amount_minor <> suggested_minor))),
    CHECK (NOT overwritten OR (overwrite_reason IS NOT NULL AND char_length(btrim(overwrite_reason)) >= 5)),
    CHECK ((voided_at IS NULL) = (voided_by IS NULL)),
    CHECK (voided_at IS NULL OR btrim(void_reason) <> '')
);
CREATE UNIQUE INDEX retail_daily_savings_active ON retail_daily_savings (tenant_id, branch_id, business_date)
    WHERE voided_at IS NULL;
CREATE INDEX retail_daily_savings_list ON retail_daily_savings (tenant_id, branch_id, business_date);
CREATE INDEX retail_daily_savings_voided ON retail_daily_savings (tenant_id, branch_id, voided_at)
    WHERE voided_at IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_daily_savings');
SELECT bms_grant_app('retail_daily_savings', 'SELECT, INSERT, UPDATE');
CREATE TRIGGER retail_daily_savings_guard_update BEFORE UPDATE ON retail_daily_savings
    FOR EACH ROW EXECUTE FUNCTION retail_cashbook_guard_update();
CREATE TRIGGER retail_daily_savings_reject_delete BEFORE DELETE ON retail_daily_savings
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------------------------
-- retail_cash_bankings (FR-RET-21, FR-RET-22): several rows per branch and date are allowed.
-- expected_minor is the server's snapshot at entry, net of savings and cash purchases, so it is
-- profit-derived and may be negative.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_cash_bankings (
    id               uuid         NOT NULL PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    branch_id        uuid         NOT NULL,
    business_date    date         NOT NULL,
    amount_minor     bigint       NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 10000000000000),
    currency         char(3)      NOT NULL REFERENCES currencies (code),
    expected_minor   bigint       NOT NULL,
    banked_at        timestamptz  NOT NULL,
    reference        varchar(100),
    recorded_by      uuid         NOT NULL,
    journal_entry_id uuid,
    historical       boolean      NOT NULL DEFAULT false,
    voided_at        timestamptz,
    voided_by        uuid,
    void_reason      varchar(300),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK ((voided_at IS NULL) = (voided_by IS NULL)),
    CHECK (voided_at IS NULL OR btrim(void_reason) <> '')
);
CREATE INDEX retail_cash_bankings_list ON retail_cash_bankings (tenant_id, branch_id, business_date);
CREATE INDEX retail_cash_bankings_voided ON retail_cash_bankings (tenant_id, branch_id, voided_at)
    WHERE voided_at IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_cash_bankings');
SELECT bms_grant_app('retail_cash_bankings', 'SELECT, INSERT, UPDATE');
CREATE TRIGGER retail_cash_bankings_guard_update BEFORE UPDATE ON retail_cash_bankings
    FOR EACH ROW EXECUTE FUNCTION retail_cashbook_guard_update();
CREATE TRIGGER retail_cash_bankings_reject_delete BEFORE DELETE ON retail_cash_bankings
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------------------------
-- retail_cash_withdrawals (FR-RET-25): cash taken out of the bank into a shop's till.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_cash_withdrawals (
    id               uuid         NOT NULL PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    branch_id        uuid         NOT NULL,
    business_date    date         NOT NULL,
    amount_minor     bigint       NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 10000000000000),
    currency         char(3)      NOT NULL REFERENCES currencies (code),
    withdrawn_at     timestamptz  NOT NULL,
    purpose          varchar(300),
    recorded_by      uuid         NOT NULL,
    journal_entry_id uuid,
    historical       boolean      NOT NULL DEFAULT false,
    voided_at        timestamptz,
    voided_by        uuid,
    void_reason      varchar(300),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK ((voided_at IS NULL) = (voided_by IS NULL)),
    CHECK (voided_at IS NULL OR btrim(void_reason) <> '')
);
CREATE INDEX retail_cash_withdrawals_list ON retail_cash_withdrawals (tenant_id, branch_id, business_date);
CREATE INDEX retail_cash_withdrawals_voided ON retail_cash_withdrawals (tenant_id, branch_id, voided_at)
    WHERE voided_at IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_cash_withdrawals');
SELECT bms_grant_app('retail_cash_withdrawals', 'SELECT, INSERT, UPDATE');
CREATE TRIGGER retail_cash_withdrawals_guard_update BEFORE UPDATE ON retail_cash_withdrawals
    FOR EACH ROW EXECUTE FUNCTION retail_cashbook_guard_update();
CREATE TRIGGER retail_cash_withdrawals_reject_delete BEFORE DELETE ON retail_cash_withdrawals
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------------------------
-- retail_expenses (FR-RET-24): category and item names are snapshots.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_expenses (
    id                  uuid         NOT NULL PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    branch_id           uuid         NOT NULL,
    business_date       date         NOT NULL,
    category_id         uuid         NOT NULL,
    item_id             uuid         NOT NULL,
    category_name       varchar(100) NOT NULL,
    item_name           varchar(100) NOT NULL,
    party_id            uuid,
    amount_minor        bigint       NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 10000000000000),
    currency            char(3)      NOT NULL REFERENCES currencies (code),
    explanation         varchar(500),
    receipt_document_id uuid,
    occurred_at         timestamptz  NOT NULL,
    recorded_by         uuid         NOT NULL,
    journal_entry_id    uuid,
    historical          boolean      NOT NULL DEFAULT false,
    voided_at           timestamptz,
    voided_by           uuid,
    void_reason         varchar(300),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, category_id) REFERENCES retail_expense_categories (tenant_id, id),
    FOREIGN KEY (tenant_id, item_id) REFERENCES retail_expense_items (tenant_id, id),
    FOREIGN KEY (tenant_id, party_id) REFERENCES retail_cash_parties (tenant_id, id),
    FOREIGN KEY (tenant_id, receipt_document_id) REFERENCES documents (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK ((voided_at IS NULL) = (voided_by IS NULL)),
    CHECK (voided_at IS NULL OR btrim(void_reason) <> '')
);
CREATE INDEX retail_expenses_list ON retail_expenses (tenant_id, branch_id, business_date);
CREATE INDEX retail_expenses_category ON retail_expenses (tenant_id, category_id, business_date);
CREATE INDEX retail_expenses_voided ON retail_expenses (tenant_id, branch_id, voided_at)
    WHERE voided_at IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_expenses');
SELECT bms_grant_app('retail_expenses', 'SELECT, INSERT, UPDATE');
CREATE TRIGGER retail_expenses_guard_update BEFORE UPDATE ON retail_expenses
    FOR EACH ROW EXECUTE FUNCTION retail_cashbook_guard_update();
CREATE TRIGGER retail_expenses_reject_delete BEFORE DELETE ON retail_expenses
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------------------------
-- retail_advances and retail_advance_repayments (FR-RET-26). repaid_minor is a stored running
-- total, not an independent balance: it changes only with a repayment insert or void, in the same
-- transaction under the advance's row lock, and the deferred trigger below refuses any commit in
-- which it differs from the sum of the advance's non-voided repayments.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_advances (
    id                 uuid         NOT NULL PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants (id),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    advance_no         varchar(20)  NOT NULL,
    branch_id          uuid         NOT NULL,
    party_id           uuid         NOT NULL,
    taken_by_party_id  uuid,
    principal_minor    bigint       NOT NULL CHECK (principal_minor > 0 AND principal_minor <= 10000000000000),
    currency           char(3)      NOT NULL REFERENCES currencies (code),
    purpose            varchar(300),
    business_date      date         NOT NULL,
    repaid_minor       bigint       NOT NULL DEFAULT 0,
    note               varchar(500),
    occurred_at        timestamptz  NOT NULL,
    recorded_by        uuid         NOT NULL,
    journal_entry_id   uuid,
    historical         boolean      NOT NULL DEFAULT false,
    voided_at          timestamptz,
    voided_by          uuid,
    void_reason        varchar(300),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, advance_no),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, party_id) REFERENCES retail_cash_parties (tenant_id, id),
    FOREIGN KEY (tenant_id, taken_by_party_id) REFERENCES retail_cash_parties (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK (repaid_minor >= 0 AND repaid_minor <= principal_minor),
    CHECK ((voided_at IS NULL) = (voided_by IS NULL)),
    CHECK (voided_at IS NULL OR btrim(void_reason) <> '')
);
CREATE INDEX retail_advances_list ON retail_advances (tenant_id, branch_id, business_date);
CREATE INDEX retail_advances_party ON retail_advances (tenant_id, party_id);
CREATE INDEX retail_advances_voided ON retail_advances (tenant_id, branch_id, voided_at)
    WHERE voided_at IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_advances');
SELECT bms_grant_app('retail_advances', 'SELECT, INSERT, UPDATE');
CREATE TRIGGER retail_advances_guard_update BEFORE UPDATE ON retail_advances
    FOR EACH ROW EXECUTE FUNCTION retail_cashbook_guard_update();
CREATE TRIGGER retail_advances_reject_delete BEFORE DELETE ON retail_advances
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TABLE retail_advance_repayments (
    id               uuid         NOT NULL PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    advance_id       uuid         NOT NULL,
    branch_id        uuid         NOT NULL,
    amount_minor     bigint       NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 10000000000000),
    currency         char(3)      NOT NULL REFERENCES currencies (code),
    method           text         NOT NULL CHECK (method IN ('cash', 'mobile_money', 'bank')),
    paid_on          date         NOT NULL,
    recorded_by      uuid         NOT NULL,
    journal_entry_id uuid,
    historical       boolean      NOT NULL DEFAULT false,
    voided_at        timestamptz,
    voided_by        uuid,
    void_reason      varchar(300),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, advance_id) REFERENCES retail_advances (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK ((voided_at IS NULL) = (voided_by IS NULL)),
    CHECK (voided_at IS NULL OR btrim(void_reason) <> '')
);
CREATE INDEX retail_advance_repayments_advance ON retail_advance_repayments (tenant_id, advance_id);
CREATE INDEX retail_advance_repayments_day ON retail_advance_repayments (tenant_id, branch_id, paid_on);
CREATE INDEX retail_advance_repayments_voided ON retail_advance_repayments (tenant_id, branch_id, voided_at)
    WHERE voided_at IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_advance_repayments');
SELECT bms_grant_app('retail_advance_repayments', 'SELECT, INSERT, UPDATE');
CREATE TRIGGER retail_advance_repayments_guard_update BEFORE UPDATE ON retail_advance_repayments
    FOR EACH ROW EXECUTE FUNCTION retail_cashbook_guard_update();
CREATE TRIGGER retail_advance_repayments_reject_delete BEFORE DELETE ON retail_advance_repayments
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE FUNCTION retail_advance_repaid_check() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_advance uuid := (to_jsonb(NEW) ->> CASE WHEN TG_TABLE_NAME = 'retail_advances' THEN 'id' ELSE 'advance_id' END)::uuid;
    v_stored  bigint;
    v_sum     bigint;
BEGIN
    SELECT repaid_minor INTO v_stored FROM retail_advances
     WHERE tenant_id = NEW.tenant_id AND id = v_advance;
    SELECT coalesce(sum(amount_minor), 0) INTO v_sum FROM retail_advance_repayments
     WHERE tenant_id = NEW.tenant_id AND advance_id = v_advance AND voided_at IS NULL;
    IF v_stored IS DISTINCT FROM v_sum THEN
        RAISE EXCEPTION 'retail_advances: repaid_minor must equal the sum of the non-voided repayments'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER retail_advances_repaid_check AFTER INSERT OR UPDATE ON retail_advances
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION retail_advance_repaid_check();
CREATE CONSTRAINT TRIGGER retail_advance_repayments_repaid_check AFTER INSERT OR UPDATE ON retail_advance_repayments
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION retail_advance_repaid_check();

-- ---------------------------------------------------------------------------------------------
-- Reads of the daily cash summary look up sales by their void instant, like the cash book tables.
-- ---------------------------------------------------------------------------------------------

CREATE INDEX retail_sales_voided ON retail_sales (tenant_id, branch_id, voided_at) WHERE voided_at IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- The import references of the cash book tabs (ADR-022 decision 18): the CHECK of V20 is widened.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_import_refs DROP CONSTRAINT retail_import_refs_source_file_check;
ALTER TABLE retail_import_refs ADD CONSTRAINT retail_import_refs_source_file_check
    CHECK (source_file IN ('sales', 'purchases', 'usage', 'opening', 'savings', 'expenses', 'banking', 'withdrawals',
                           'advances', 'advance_payments', 'cash_opening'));
