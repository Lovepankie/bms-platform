-- V22: stock transfers between branches (issue #84, ADR-020 amendment of 2026-10-06): the
-- permission, the inter-branch clearing account in the retail chart, the transfer header and its
-- lines, and the two movement kinds that link both branches' history to one transfer.
--
-- Additive except for replaced CHECK constraints (chapter 6 section 6.9). Flyway runs with
-- outOfOrder off, so this file takes the next number free on main at merge time; V21 is lending.

-- ---------------------------------------------------------------------------------------------
-- The permission (chapter 8 section 8.3.2; FR-RET-16). It moves inventory value between branch
-- ledgers, so it is money-moving like a restock, and it goes to the roles that restock: in the
-- default roles that is tenant_admin only.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.stock.transfer', 'retail', 'Stock: move stock between branches', true);

INSERT INTO role_permissions (role_key, permission_key)
SELECT DISTINCT rp.role_key, 'retail.stock.transfer'
  FROM role_permissions rp
 WHERE rp.permission_key = 'retail.purchase.create'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- Inter-branch clearing (ADR-004): every journal entry belongs to one branch, so a transfer posts
-- one entry at each branch, each balanced through this account; in a consolidation it nets to
-- zero. The lending chart has it already (code 1190, the same system_key), so a tenant with both
-- verticals keeps one. The seed function is V10's with the one row added; tenants that switched
-- retail on before this migration get the account now.
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
        ('1000', 'Assets',                 'asset',     NULL,                     false, false),
        ('1010', 'Cash on hand',           'asset',     'cash_on_hand',           true,  false),
        ('1020', 'Bank',                   'asset',     'bank',                   true,  false),
        ('1035', 'Mobile money',           'asset',     'mobile_money',           true,  false),
        ('1190', 'Inter-branch clearing',  'asset',     'interbranch_clearing',   true,  true),
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

SELECT bms_seed_retail_chart(tenant_id) FROM tenant_modules WHERE module_key = 'retail';

-- ---------------------------------------------------------------------------------------------
-- retail_transfers (FR-RET-16): one row per transfer from a source branch to a different
-- destination branch, valued at the source's cost when it was made (no profit or loss). The two
-- entry ids are the source's and the destination's journal entries; both are null when the
-- transfer's cost is zero. Only the void columns change, once, from completed to voided.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_transfers (
    id               uuid         NOT NULL PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    from_branch_id   uuid         NOT NULL,
    to_branch_id     uuid         NOT NULL,
    transfer_date    date         NOT NULL,
    note             varchar(300),
    currency         char(3)      NOT NULL REFERENCES currencies (code),
    cost_total_minor bigint       NOT NULL CHECK (cost_total_minor >= 0),
    out_entry_id     uuid,
    in_entry_id      uuid,
    status           text         NOT NULL CHECK (status IN ('completed', 'voided')),
    created_by       uuid         NOT NULL,
    voided_at        timestamptz,
    voided_by        uuid,
    void_reason      varchar(300),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, from_branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, to_branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, out_entry_id) REFERENCES journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, in_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK (from_branch_id <> to_branch_id),
    CHECK ((out_entry_id IS NULL) = (in_entry_id IS NULL)),
    CHECK ((status = 'voided') = (voided_at IS NOT NULL)),
    CHECK (status = 'completed' OR (voided_by IS NOT NULL AND btrim(void_reason) <> ''))
);
CREATE INDEX retail_transfers_list ON retail_transfers (tenant_id, created_at, id);
CREATE INDEX retail_transfers_from ON retail_transfers (tenant_id, from_branch_id, transfer_date);
CREATE INDEX retail_transfers_to ON retail_transfers (tenant_id, to_branch_id, transfer_date);
SELECT bms_apply_tenant_rls('retail_transfers');
SELECT bms_grant_app('retail_transfers', 'SELECT, INSERT, UPDATE');

CREATE FUNCTION retail_transfers_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.created_at, NEW.from_branch_id, NEW.to_branch_id, NEW.transfer_date, NEW.note,
        NEW.currency, NEW.cost_total_minor, NEW.out_entry_id, NEW.in_entry_id, NEW.created_by)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.created_at, OLD.from_branch_id, OLD.to_branch_id, OLD.transfer_date, OLD.note,
        OLD.currency, OLD.cost_total_minor, OLD.out_entry_id, OLD.in_entry_id, OLD.created_by)
       OR OLD.status = 'voided'
    THEN
        RAISE EXCEPTION 'retail_transfers: only the void may change, once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_transfers_guard_update BEFORE UPDATE ON retail_transfers
    FOR EACH ROW EXECUTE FUNCTION retail_transfers_guard_update();
CREATE TRIGGER retail_transfers_reject_delete BEFORE DELETE ON retail_transfers
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------------------------
-- retail_transfer_lines (FR-RET-16), append-only: one line per product, with the source cost
-- snapshot the movements carry.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_transfer_lines (
    id              uuid          NOT NULL PRIMARY KEY,
    tenant_id       uuid          NOT NULL REFERENCES tenants (id),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    transfer_id     uuid          NOT NULL,
    line_no         smallint      NOT NULL CHECK (line_no >= 1),
    product_id      uuid          NOT NULL,
    qty             numeric(14,3) NOT NULL CHECK (qty > 0),
    unit_cost_minor bigint        NOT NULL CHECK (unit_cost_minor >= 0 AND unit_cost_minor <= 10000000000000),
    line_cost_minor bigint        NOT NULL CHECK (line_cost_minor >= 0),
    UNIQUE (tenant_id, id),
    UNIQUE (transfer_id, line_no),
    UNIQUE (transfer_id, product_id),
    FOREIGN KEY (tenant_id, transfer_id) REFERENCES retail_transfers (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id)
);
CREATE INDEX retail_transfer_lines_product ON retail_transfer_lines (tenant_id, product_id);
SELECT bms_apply_tenant_rls('retail_transfer_lines');
SELECT bms_grant_app('retail_transfer_lines', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_transfer_lines');

-- ---------------------------------------------------------------------------------------------
-- Movements: transfer_out (negative, at the source) and transfer_in (positive, at the
-- destination), both carrying the transfer's id, so each branch's history shows a transfer and
-- not an adjustment. A void writes the opposite kind at each branch, reversing the original
-- movement, with the same transfer id. The link is checked at commit, because the movements are
-- written under their balance locks before the header.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_stock_movements ADD COLUMN transfer_id uuid;
ALTER TABLE retail_stock_movements
    ADD CONSTRAINT retail_stock_movements_transfer_fk FOREIGN KEY (tenant_id, transfer_id)
        REFERENCES retail_transfers (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE retail_stock_movements DROP CONSTRAINT retail_stock_movements_kind_check;
ALTER TABLE retail_stock_movements ADD CONSTRAINT retail_stock_movements_kind_check
    CHECK (kind IN ('opening', 'purchase', 'sale', 'usage', 'damage', 'adjustment', 'return', 'legacy_balance',
                    'transfer_out', 'transfer_in'));
ALTER TABLE retail_stock_movements DROP CONSTRAINT retail_stock_movements_check;
ALTER TABLE retail_stock_movements ADD CONSTRAINT retail_stock_movements_check
    CHECK ((kind IN ('opening', 'purchase', 'return', 'transfer_in') AND qty > 0)
        OR (kind IN ('sale', 'usage', 'damage', 'transfer_out') AND qty < 0)
        OR kind IN ('adjustment', 'legacy_balance'));
ALTER TABLE retail_stock_movements ADD CONSTRAINT retail_stock_movements_transfer_link
    CHECK ((kind IN ('transfer_out', 'transfer_in')) = (transfer_id IS NOT NULL));
CREATE INDEX retail_stock_movements_transfer ON retail_stock_movements (tenant_id, transfer_id)
    WHERE transfer_id IS NOT NULL;
