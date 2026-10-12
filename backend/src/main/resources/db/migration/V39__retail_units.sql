-- V39: retail units of measure (ADR-033, issue #194; docs/specs/trade-capabilities.md section 4).
--
-- Expand only, safe on a populated database. Stock stays in the product's base unit, so no stock
-- table changes. One new table holds the pack sizes of a product (a unit and the number of base
-- units in one pack). Sale lines and purchase lines gain nullable columns that record what was
-- entered (the unit, the quantity in that unit, the price or cost in that unit, the factor in force):
-- every existing row keeps null, which reads as "entered in the base unit". The new checks and
-- foreign keys are added NOT VALID and validated in the same file. Flyway runs the
-- file in one transaction, so NOT VALID does not shorten the lock: the ACCESS EXCLUSIVE lock of the
-- ALTER TABLE is held through VALIDATE to the commit, and validation scans the table once. That is
-- accepted here because the new columns are null on every existing row (validation cannot fail) and the
-- two line tables of a small or medium business are modest; a very large tenant would want VALIDATE
-- moved to a later migration. The chart gains the account 5120 purchase_rounding
-- for the cost remainder of a restock priced per pack. Nothing is dropped or renamed, no tenant is
-- switched on: the units capability lives in the tenant settings group retail.capabilities and is
-- off until an administrator turns it on. Flyway runs with outOfOrder off, so the number is
-- assigned at merge time above the highest one on main.

-- ---------------------------------------------------------------------------------------------
-- retail_product_units: the pack sizes of a product. One pack per unit per product. The pack's
-- unit must differ from the product's base unit and a base unit cannot change while an active pack
-- exists; both are rules of the catalogue service (they span two tables). A row is never deleted: a pack
-- that is no longer sold is switched off. A factor is at least 1: a pack holds at least one base unit (a
-- smaller unit is the base unit and the larger one the pack), because a fraction would drift stock.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_product_units (
    id         uuid          NOT NULL PRIMARY KEY,
    tenant_id  uuid          NOT NULL REFERENCES tenants (id),
    created_at timestamptz   NOT NULL DEFAULT now(),
    updated_at timestamptz,
    version    integer       NOT NULL DEFAULT 1,
    product_id uuid          NOT NULL,
    unit_id    uuid          NOT NULL,
    factor     numeric(14,3) NOT NULL CHECK (factor >= 1),
    active     boolean       NOT NULL DEFAULT true,
    created_by uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, product_id, unit_id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    FOREIGN KEY (tenant_id, unit_id) REFERENCES retail_units (tenant_id, id)
);
CREATE INDEX retail_product_units_unit ON retail_product_units (tenant_id, unit_id);
SELECT bms_apply_tenant_rls('retail_product_units');
SELECT bms_grant_app('retail_product_units', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- retail_sale_lines: what was entered. qty and unit_price_minor stay in base units, so every
-- report that sums qty and line_total_minor is unchanged; line_total_minor = sale_qty x
-- sale_unit_price_minor, rounded half up once. All four columns are null or all four are set.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_sale_lines
    ADD COLUMN sale_unit_id          uuid,
    ADD COLUMN sale_qty              numeric(14,3),
    ADD COLUMN sale_unit_price_minor bigint,
    ADD COLUMN unit_factor           numeric(14,3);

ALTER TABLE retail_sale_lines
    ADD CONSTRAINT retail_sale_lines_unit_chk CHECK (
        (sale_unit_id IS NULL AND sale_qty IS NULL AND sale_unit_price_minor IS NULL AND unit_factor IS NULL)
        OR (sale_unit_id IS NOT NULL AND sale_qty > 0 AND unit_factor >= 1 AND qty = sale_qty * unit_factor
            AND sale_unit_price_minor >= 0 AND sale_unit_price_minor <= 10000000000000)) NOT VALID,
    ADD CONSTRAINT retail_sale_lines_unit_fk FOREIGN KEY (tenant_id, sale_unit_id)
        REFERENCES retail_units (tenant_id, id) NOT VALID;
ALTER TABLE retail_sale_lines VALIDATE CONSTRAINT retail_sale_lines_unit_chk;
ALTER TABLE retail_sale_lines VALIDATE CONSTRAINT retail_sale_lines_unit_fk;

-- ---------------------------------------------------------------------------------------------
-- retail_purchase_lines: what was entered. cost_minor and qty_total stay per base unit and in base
-- units (the movement snapshot and the product cost); line_total_minor stays the amount paid or
-- owed as entered. unit_cost_minor is the cost per pack as entered.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_purchase_lines
    ADD COLUMN unit_id        uuid,
    ADD COLUMN unit_qty_total numeric(14,3),
    ADD COLUMN unit_cost_minor bigint,
    ADD COLUMN unit_factor    numeric(14,3);

ALTER TABLE retail_purchase_lines
    ADD CONSTRAINT retail_purchase_lines_unit_chk CHECK (
        (unit_id IS NULL AND unit_qty_total IS NULL AND unit_cost_minor IS NULL AND unit_factor IS NULL)
        OR (unit_id IS NOT NULL AND unit_qty_total > 0 AND unit_factor >= 1 AND qty_total = unit_qty_total * unit_factor
            AND unit_cost_minor >= 0 AND unit_cost_minor <= 10000000000000)) NOT VALID,
    ADD CONSTRAINT retail_purchase_lines_unit_fk FOREIGN KEY (tenant_id, unit_id)
        REFERENCES retail_units (tenant_id, id) NOT VALID;
ALTER TABLE retail_purchase_lines VALIDATE CONSTRAINT retail_purchase_lines_unit_chk;
ALTER TABLE retail_purchase_lines VALIDATE CONSTRAINT retail_purchase_lines_unit_fk;

-- ---------------------------------------------------------------------------------------------
-- Chart addition (trade-capabilities.md section 12.1): 5120 purchase_rounding, the visible home of
-- the difference between the amount paid for a restock in packs and the movements at the rounded
-- cost per base unit. The seed function is V34's with one row added; a code or system key a tenant
-- already has is kept. Tenants that switched retail on earlier get the account now; it stays at
-- zero until a restock names a pack.
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
        ('5120', 'Purchase price rounding',               'expense',   'purchase_rounding',      true,  false),
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
