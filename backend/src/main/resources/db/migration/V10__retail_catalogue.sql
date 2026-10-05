-- V10: the retail vertical, increment R1 (ADR-020, issue #51): the module switch, the retail
-- permission catalogue and sales role, categories, units, products, the append-only price history
-- and the default retail chart of accounts seeded when the module is switched on.
--
-- Additive only (chapter 6 section 6.9). Flyway versions are one sequence shared with the lending
-- work; this file takes the next number free on main and the open lending pull requests.

-- ---------------------------------------------------------------------------------------------
-- The module may be switched on for any plan; whether a plan includes it commercially is the
-- platform operator's call and is not recorded here.
-- ---------------------------------------------------------------------------------------------

UPDATE plans SET allowed_modules = allowed_modules || '{retail}'::text[] WHERE NOT ('retail' = ANY (allowed_modules));

-- ---------------------------------------------------------------------------------------------
-- Permissions and the sales role (chapter 8 section 8.3.2; FR-RET-13). PermissionMatrixIT
-- compares these rows with the matrix in chapter 8.
-- ---------------------------------------------------------------------------------------------

INSERT INTO roles (key, name, kind, mfa_required) VALUES
    ('retail_sales', 'Sales', 'staff', false);

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.catalogue.manage', 'retail', 'Catalogue: manage categories, units and products', false),
    ('retail.price.edit', 'retail', 'Prices: manual edit', false),
    ('retail.customer.manage', 'retail', 'Credit buyers: manage', false),
    ('retail.sale.create', 'retail', 'Sales: record a sale or a payment against one', true),
    ('retail.sale.read', 'retail', 'Sales: read', false),
    ('retail.sale.void', 'retail', 'Sales: void', true),
    ('retail.stock.read', 'retail', 'Stock and catalogue: read', false),
    ('retail.stocktake.commit', 'retail', 'Stock-take: commit', false),
    ('retail.purchase.create', 'retail', 'Purchases: record a restock', true),
    ('retail.usage.report', 'retail', 'Usage and damage: report', false),
    ('retail.profit.read', 'retail', 'Cost, valuation at cost and profit: read', false);

INSERT INTO role_permissions (role_key, permission_key)
SELECT 'tenant_admin', key FROM permissions WHERE module = 'retail';

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('retail_sales', 'retail.sale.create'),
    ('retail_sales', 'retail.sale.read'),
    ('retail_sales', 'retail.stock.read'),
    ('retail_sales', 'retail.usage.report'),
    ('retail_sales', 'retail.customer.manage');

-- ---------------------------------------------------------------------------------------------
-- retail_categories and retail_units (FR-RET-01): managed per tenant, unique by name ignoring case.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_categories (
    id         uuid         NOT NULL PRIMARY KEY,
    tenant_id  uuid         NOT NULL REFERENCES tenants (id),
    created_at timestamptz  NOT NULL DEFAULT now(),
    name       varchar(100) NOT NULL CHECK (btrim(name) <> ''),
    created_by uuid,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX retail_categories_name ON retail_categories (tenant_id, lower(name));
SELECT bms_apply_tenant_rls('retail_categories');
SELECT bms_grant_app('retail_categories', 'SELECT, INSERT');

CREATE TABLE retail_units (
    id         uuid        NOT NULL PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    name       varchar(30) NOT NULL CHECK (btrim(name) <> ''),
    created_by uuid,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX retail_units_name ON retail_units (tenant_id, lower(name));
SELECT bms_apply_tenant_rls('retail_units');
SELECT bms_grant_app('retail_units', 'SELECT, INSERT');

-- ---------------------------------------------------------------------------------------------
-- retail_products (FR-RET-01). The current cost and sell price live here and change only inside
-- the event that carries them: a manual edit (R1) or a restock (R3), each writing a history row in
-- the same transaction (ADR-020 decision 5). A product code is unique ignoring case and spaces at
-- the ends (data dictionary rule 1).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_products (
    id          uuid         NOT NULL PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenants (id),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    version     integer      NOT NULL DEFAULT 1,
    code        varchar(40)  NOT NULL CHECK (code = btrim(code) AND code <> ''),
    description varchar(300) NOT NULL CHECK (btrim(description) <> ''),
    category_id uuid         NOT NULL,
    unit_id     uuid         NOT NULL,
    cost_minor  bigint       NOT NULL CHECK (cost_minor >= 0),
    sell_minor  bigint       NOT NULL CHECK (sell_minor >= 0),
    currency    char(3)      NOT NULL REFERENCES currencies (code),
    active      boolean      NOT NULL DEFAULT true,
    created_by  uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, category_id) REFERENCES retail_categories (tenant_id, id),
    FOREIGN KEY (tenant_id, unit_id) REFERENCES retail_units (tenant_id, id)
);
CREATE UNIQUE INDEX retail_products_code ON retail_products (tenant_id, lower(code));
CREATE INDEX retail_products_category ON retail_products (tenant_id, category_id);
CREATE INDEX retail_products_search ON retail_products USING gin (description gin_trgm_ops);
SELECT bms_apply_tenant_rls('retail_products');
SELECT bms_grant_app('retail_products', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- retail_price_history (FR-RET-02), append-only: no UPDATE or DELETE privilege for bms_app and
-- the same rejecting trigger as the journals (ADR-004, ADR-020 decision 5).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_price_history (
    id             uuid        NOT NULL PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenants (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    product_id     uuid        NOT NULL,
    source         text        NOT NULL CHECK (source IN ('initial', 'manual', 'purchase', 'import')),
    source_id      uuid,
    old_cost_minor bigint      CHECK (old_cost_minor >= 0),
    new_cost_minor bigint      NOT NULL CHECK (new_cost_minor >= 0),
    old_sell_minor bigint      CHECK (old_sell_minor >= 0),
    new_sell_minor bigint      NOT NULL CHECK (new_sell_minor >= 0),
    currency       char(3)     NOT NULL REFERENCES currencies (code),
    reason         varchar(300),
    changed_by     uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    CHECK ((source = 'initial') = (old_cost_minor IS NULL AND old_sell_minor IS NULL))
);
CREATE INDEX retail_price_history_product ON retail_price_history (tenant_id, product_id, created_at, id);
SELECT bms_apply_tenant_rls('retail_price_history');
SELECT bms_grant_app('retail_price_history', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_price_history');

-- ---------------------------------------------------------------------------------------------
-- Default chart of accounts for a retail tenant (chapter 6 section 6.6.2; ADR-020 decision 7).
-- A tenant that also runs lending shares the accounts both charts name (cash, bank, opening
-- balance equity, the class headers): a code or system key that exists already is kept, never
-- duplicated. Called by the platform functions below when the module is switched on.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION bms_seed_retail_chart(p_tenant_id uuid) RETURNS integer
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
        ('1000', 'Assets',                 'asset',     NULL,                     false, false),
        ('1010', 'Cash on hand',           'asset',     'cash_on_hand',           true,  false),
        ('1020', 'Bank',                   'asset',     'bank',                   true,  false),
        ('1035', 'Mobile money',           'asset',     'mobile_money',           true,  false),
        ('1200', 'Trade debtors',          'asset',     'trade_debtors',          true,  true),
        ('1300', 'Inventory',              'asset',     'inventory',              true,  true),
        ('2000', 'Liabilities',            'liability', NULL,                     false, false),
        ('2100', 'Trade creditors',        'liability', 'trade_creditors',        true,  true),
        ('3000', 'Equity',                 'equity',    NULL,                     false, false),
        ('3030', 'Opening balance equity', 'equity',    'opening_balance_equity', true,  false),
        ('4000', 'Income',                 'income',    NULL,                     false, false),
        ('4100', 'Sales revenue',          'income',    'sales_revenue',          true,  false),
        ('5000', 'Expenses',               'expense',   NULL,                     false, false),
        ('5100', 'Cost of goods sold',     'expense',   'cost_of_goods_sold',     true,  false),
        ('5110', 'Stock shrinkage',        'expense',   'stock_shrinkage',        true,  false)
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

-- The two platform functions of V2, unchanged except that switching retail on seeds its chart.
CREATE OR REPLACE FUNCTION platform_create_tenant(
    p_tenant_id uuid, p_slug text, p_name text, p_plan_code text, p_currency text, p_timezone text,
    p_modules text[], p_branch_id uuid, p_branch_code text, p_branch_name text, p_platform_user_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_plan plans%ROWTYPE;
BEGIN
    SELECT * INTO v_plan FROM plans WHERE code = p_plan_code AND is_active;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown plan %', p_plan_code USING ERRCODE = 'check_violation';
    END IF;
    IF NOT (coalesce(p_modules, '{}') <@ v_plan.allowed_modules) THEN
        RAISE EXCEPTION 'module not allowed by plan %', p_plan_code USING ERRCODE = 'check_violation';
    END IF;

    INSERT INTO tenants (id, slug, name, currency, timezone, plan_id)
    VALUES (p_tenant_id, p_slug, p_name, p_currency, p_timezone, v_plan.id);
    INSERT INTO subscriptions (id, tenant_id, plan_id, status, updated_by_platform_user_id)
    VALUES (gen_random_uuid(), p_tenant_id, v_plan.id, 'trial', p_platform_user_id);
    INSERT INTO tenant_settings (id, tenant_id) VALUES (gen_random_uuid(), p_tenant_id);
    INSERT INTO branches (id, tenant_id, code, name, is_head_office)
    VALUES (p_branch_id, p_tenant_id, p_branch_code, p_branch_name, true);
    INSERT INTO tenant_modules (tenant_id, module_key, enabled_by_platform_user_id)
    SELECT p_tenant_id, m, p_platform_user_id FROM unnest(coalesce(p_modules, '{}')) AS m;
    IF 'lending' = ANY (coalesce(p_modules, '{}')) THEN
        PERFORM bms_seed_lending_chart(p_tenant_id);
    END IF;
    IF 'retail' = ANY (coalesce(p_modules, '{}')) THEN
        PERFORM bms_seed_retail_chart(p_tenant_id);
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION platform_set_tenant_modules(p_tenant_id uuid, p_modules text[], p_platform_user_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_allowed text[];
BEGIN
    SELECT p.allowed_modules INTO v_allowed FROM tenants t JOIN plans p ON p.id = t.plan_id WHERE t.id = p_tenant_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown tenant' USING ERRCODE = 'no_data_found';
    END IF;
    IF NOT (coalesce(p_modules, '{}') <@ v_allowed) THEN
        RAISE EXCEPTION 'module not allowed by plan' USING ERRCODE = 'check_violation';
    END IF;
    DELETE FROM tenant_modules WHERE tenant_id = p_tenant_id AND NOT (module_key = ANY (coalesce(p_modules, '{}')));
    INSERT INTO tenant_modules (tenant_id, module_key, enabled_by_platform_user_id)
    SELECT p_tenant_id, m, p_platform_user_id FROM unnest(coalesce(p_modules, '{}')) AS m
    ON CONFLICT (tenant_id, module_key) DO NOTHING;
    IF 'lending' = ANY (coalesce(p_modules, '{}')) THEN
        PERFORM bms_seed_lending_chart(p_tenant_id);
    END IF;
    IF 'retail' = ANY (coalesce(p_modules, '{}')) THEN
        PERFORM bms_seed_retail_chart(p_tenant_id);
    END IF;
END
$$;
