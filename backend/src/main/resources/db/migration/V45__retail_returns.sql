-- V45: retail customer returns and sales credit notes (ADR-033, issue #201;
-- docs/specs/trade-capabilities.md section 5.3).
--
-- Expand only, safe on a populated database. Two new tables (a return and its lines), one helper function, a
-- replacement body for the retail_sales update guard, the chart account 4110 sales returns and one permission.
-- Nothing is dropped, renamed or rewritten, no ALTER touches retail_sales, retail_sale_lines,
-- retail_sale_payments or retail_stock_movements (the large tables), and no tenant is switched on: the returns
-- capability lives in the tenant settings group retail.capabilities and is off until an administrator turns it on.
-- A tenant that never records a return never writes a row to the new tables, and with the capability off the sale
-- path, the reports and the cash book read the new tables as empty.
--
-- Locks, said plainly. Flyway runs this file in one transaction, so every lock below is held to the commit.
-- CREATE TABLE with foreign keys takes SHARE ROW EXCLUSIVE on each referenced table (tenants, branches, currencies,
-- retail_sales, retail_sale_lines, retail_products, retail_units, journal_entries) until the commit, which blocks
-- writes to those tables, but not reads, while the file runs; the new tables are empty, so the cost is the lock wait
-- and not a scan. The statements that take no such lock (the chart seed, which inserts at most one row per retail tenant
-- into gl_accounts, and the permission rows) come first and the tables last, so the referenced tables are locked for
-- the shortest time the single transaction allows. The seed is not moved to a migration of its own (a V46): it never
-- touches journal_entries, so a second file would not shorten that lock, and it would leave a window in which a tenant has
-- the capability but no account 4110. CREATE OR REPLACE FUNCTION takes no lock on retail_sales (the trigger stays as it is).
-- Flyway runs with outOfOrder off, so the number is assigned at merge time above the highest one on main.

-- ---------------------------------------------------------------------------------------------
-- Chart addition (trade-capabilities.md section 12.1): 4110 sales_returns, the contra income account that
-- takes the value of returned goods, so sales revenue keeps what was sold and the return shows beside it. It
-- is the seed function of V39 with one row added, and a debit normal balance. A code or system key a tenant
-- already has is kept. Tenants that switched retail on earlier get the account now; it stays at zero until a
-- return is recorded.
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
           CASE WHEN c.account_type IN ('asset', 'expense') OR c.system_key = 'sales_returns' THEN 'debit' ELSE 'credit' END,
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
        ('4110', 'Sales returns',                         'income',    'sales_returns',          true,  false),
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

-- ---------------------------------------------------------------------------------------------
-- Permission (chapter 8 section 8.3.2; PermissionMatrixIT compares these rows with the matrix). retail.sale.return
-- goes to administrators only (issue #201): the sales role sells and does not take goods back. It is consulted
-- only while the returns capability is on. A return at or above the tenant's approval threshold for the action
-- retail_sale_return needs a second administrator (maker-checker, ADR-015); with no threshold set there is no
-- checker, like every other optional threshold of ADR-033.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.sale.return', 'retail', 'Sales: take goods back from a customer and give a refund or a credit note', true);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.sale.return');

-- ---------------------------------------------------------------------------------------------
-- retail_sale_returns: the return of goods against one sale, which is also the numbered credit note. Never an
-- edit of the sale. branch_id is the sale's branch: the credit note is numbered per tenant and branch, the
-- scope of the route is the sale's branch. Money leaves the till of refund_branch_id, the branch the caller chose within their
-- scope (the sale's branch unless said otherwise): when it is not the sale's branch the money leg is its own entry there
-- (refund_entry_id), through inter-branch clearing, and the cash book reads the day and the till from refund_branch_id. total_minor is what the returned lines were charged for (the price actually charged on
-- the sale line, never the price now) and splits into account_credit_minor (taken off what the customer owes on
-- a credit sale, through paid_minor) and refund_minor (money out by refund_method). cost_minor is the cost of
-- the returned lines at the sale line's own snapshot. Only the void can change a row, once.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_sale_returns (
    id                   uuid         NOT NULL PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenants (id),
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz,
    version              integer      NOT NULL DEFAULT 1,
    credit_note_no       varchar(24)  NOT NULL,
    branch_seq           bigint       NOT NULL CHECK (branch_seq >= 1),
    sale_id              uuid         NOT NULL,
    branch_id            uuid         NOT NULL,
    customer_id          uuid,
    returned_on          date         NOT NULL,
    currency             char(3)      NOT NULL REFERENCES currencies (code),
    refund_branch_id     uuid         NOT NULL,
    total_minor          bigint       NOT NULL CHECK (total_minor >= 0 AND total_minor <= 10000000000000),
    cost_minor           bigint       NOT NULL CHECK (cost_minor >= 0 AND cost_minor <= 10000000000000),
    account_credit_minor bigint       NOT NULL DEFAULT 0 CHECK (account_credit_minor >= 0),
    refund_minor         bigint       NOT NULL DEFAULT 0 CHECK (refund_minor >= 0),
    refund_method        text         CHECK (refund_method IN ('cash', 'mobile_money', 'bank')),
    refund_choice        text         NOT NULL CHECK (refund_choice IN ('cash', 'mobile_money', 'bank', 'account')),
    note                 varchar(300),
    status               text         NOT NULL DEFAULT 'completed' CHECK (status IN ('completed', 'voided')),
    journal_entry_id     uuid,
    cost_entry_id        uuid,
    refund_entry_id      uuid,
    approval_id          uuid,
    created_by           uuid         NOT NULL,
    approved_by          uuid,
    voided_at            timestamptz,
    voided_on            date,
    voided_by            uuid,
    void_reason          varchar(300),
    void_entry_id        uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, credit_note_no),
    UNIQUE (tenant_id, branch_id, branch_seq),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES retail_sales (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES retail_customers (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, cost_entry_id) REFERENCES journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, refund_branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, refund_entry_id) REFERENCES journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, void_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK (account_credit_minor + refund_minor = total_minor),
    CHECK ((refund_minor > 0) = (refund_method IS NOT NULL)),
    CHECK ((status = 'voided') = (voided_at IS NOT NULL)),
    CHECK ((status = 'voided') = (voided_on IS NOT NULL)),
    CHECK ((status = 'voided') = (voided_by IS NOT NULL)),
    CHECK ((status = 'voided') = (void_reason IS NOT NULL AND btrim(void_reason) <> ''))
);
CREATE INDEX retail_sale_returns_sale ON retail_sale_returns (tenant_id, sale_id);
CREATE INDEX retail_sale_returns_branch ON retail_sale_returns (tenant_id, branch_id, returned_on, created_at, id);
CREATE INDEX retail_sale_returns_customer ON retail_sale_returns (tenant_id, customer_id, returned_on)
    WHERE customer_id IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_sale_returns');
SELECT bms_grant_app('retail_sale_returns', 'SELECT, INSERT');
GRANT UPDATE (updated_at, version, status, voided_at, voided_on, voided_by, void_reason, void_entry_id)
    ON retail_sale_returns TO bms_app;

CREATE FUNCTION retail_sale_returns_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF OLD.status <> 'completed' OR NEW.status <> 'voided'
       OR (NEW.id, NEW.tenant_id, NEW.created_at, NEW.credit_note_no, NEW.branch_seq, NEW.sale_id, NEW.branch_id,
           NEW.customer_id, NEW.returned_on, NEW.currency, NEW.total_minor, NEW.cost_minor, NEW.account_credit_minor,
           NEW.refund_minor, NEW.refund_method, NEW.refund_choice, NEW.note, NEW.journal_entry_id, NEW.cost_entry_id,
           NEW.approval_id, NEW.created_by, NEW.approved_by, NEW.refund_branch_id, NEW.refund_entry_id)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.created_at, OLD.credit_note_no, OLD.branch_seq, OLD.sale_id, OLD.branch_id,
           OLD.customer_id, OLD.returned_on, OLD.currency, OLD.total_minor, OLD.cost_minor, OLD.account_credit_minor,
           OLD.refund_minor, OLD.refund_method, OLD.refund_choice, OLD.note, OLD.journal_entry_id, OLD.cost_entry_id,
           OLD.approval_id, OLD.created_by, OLD.approved_by, OLD.refund_branch_id, OLD.refund_entry_id)
    THEN
        RAISE EXCEPTION 'retail_sale_returns: a return can only be voided, once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
-- A return is born completed: the only way to a voided row is the one UPDATE above, so a row inserted already voided, or
-- with the void columns set, is refused whoever inserts it (the owner's maintenance escape hatch aside).
CREATE FUNCTION retail_sale_returns_guard_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF NEW.status <> 'completed' OR NEW.voided_at IS NOT NULL OR NEW.void_entry_id IS NOT NULL OR NEW.version <> 1 THEN
        RAISE EXCEPTION 'retail_sale_returns: a return is recorded completed and is voided afterwards'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_sale_returns_guard_insert BEFORE INSERT ON retail_sale_returns
    FOR EACH ROW EXECUTE FUNCTION retail_sale_returns_guard_insert();
CREATE TRIGGER retail_sale_returns_guard_update BEFORE UPDATE ON retail_sale_returns
    FOR EACH ROW EXECUTE FUNCTION retail_sale_returns_guard_update();
CREATE TRIGGER retail_sale_returns_reject_delete BEFORE DELETE ON retail_sale_returns
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------------------------
-- retail_sale_return_lines: what came back, per sale line, append-only. qty is in base units (stock is held in
-- base units); unit_id, unit_qty and unit_factor keep the unit the quantity was entered in (null: the base unit).
-- line_total_minor and line_cost_minor are the shares of the sale line's total and cost snapshot, so the credit
-- is the price actually charged (the resolved price stored on the line, V41) and the cost reversed is the
-- original cost of the line; the last piece of a line takes the remainder, so the pieces add up to the line
-- exactly. disposition is restock (back into restock_branch_id, any branch or store) or write_off (damaged
-- goods never enter stock; their cost goes to stock shrinkage). stock_entry_id is the journal entry that holds
-- the stock leg of a line restocked in a branch other than the sale's (null otherwise), kept so a void can
-- reverse it.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_sale_return_lines (
    id               uuid          NOT NULL PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenants (id),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    return_id        uuid          NOT NULL,
    line_no          smallint      NOT NULL CHECK (line_no >= 1),
    sale_line_id     uuid          NOT NULL,
    product_id       uuid          NOT NULL,
    qty              numeric(14,3) NOT NULL CHECK (qty > 0),
    unit_id          uuid,
    unit_qty         numeric(14,3) CHECK (unit_qty > 0),
    unit_factor      numeric(14,3) CHECK (unit_factor >= 1),
    unit_cost_minor  bigint        NOT NULL CHECK (unit_cost_minor >= 0),
    line_total_minor bigint        NOT NULL CHECK (line_total_minor >= 0 AND line_total_minor <= 10000000000000),
    line_cost_minor  bigint        NOT NULL CHECK (line_cost_minor >= 0 AND line_cost_minor <= 10000000000000),
    reason           text          NOT NULL CHECK (reason IN ('defective', 'damaged', 'wrong_item', 'not_as_described',
                                                              'unwanted', 'price_error', 'other')),
    disposition      text          NOT NULL CHECK (disposition IN ('restock', 'write_off')),
    restock_branch_id uuid,
    stock_entry_id   uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (return_id, line_no),
    FOREIGN KEY (tenant_id, return_id) REFERENCES retail_sale_returns (tenant_id, id),
    FOREIGN KEY (tenant_id, sale_line_id) REFERENCES retail_sale_lines (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    FOREIGN KEY (tenant_id, unit_id) REFERENCES retail_units (tenant_id, id),
    FOREIGN KEY (tenant_id, restock_branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, stock_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK ((disposition = 'restock') = (restock_branch_id IS NOT NULL)),
    CHECK ((unit_id IS NULL) = (unit_qty IS NULL) AND (unit_id IS NULL) = (unit_factor IS NULL))
);
CREATE INDEX retail_sale_return_lines_return ON retail_sale_return_lines (tenant_id, return_id);
CREATE INDEX retail_sale_return_lines_sale_line ON retail_sale_return_lines (tenant_id, sale_line_id);
SELECT bms_apply_tenant_rls('retail_sale_return_lines');
SELECT bms_grant_app('retail_sale_return_lines', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_sale_return_lines');

-- ---------------------------------------------------------------------------------------------
-- What a sale's paid_minor must at least come to: its payments that were not reversed (V44) plus the account
-- credit of its returns that were not voided. paid_minor is the amount of the sale that has been settled, by
-- money or by a credit note against the customer's balance, so every reader of the balance (the customer
-- balance, credit control, ageing, the credit limit, the allocation of a payment on account) keeps reading
-- total_minor less paid_minor and a return needs no change in any of them.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION retail_sale_settled_minor(p_tenant uuid, p_sale uuid) RETURNS bigint
LANGUAGE sql STABLE SET search_path = public, pg_temp AS $$
    SELECT coalesce((SELECT sum(p.amount_minor) FROM retail_sale_payments p
                      WHERE p.tenant_id = p_tenant AND p.sale_id = p_sale
                        AND NOT EXISTS (SELECT 1 FROM retail_account_payment_allocations a2
                                          JOIN retail_account_payments r2
                                            ON r2.tenant_id = a2.tenant_id AND r2.id = a2.receipt_id
                                         WHERE a2.tenant_id = p.tenant_id
                                           AND a2.sale_payment_id = p.id
                                           AND r2.status = 'reversed')), 0)
         + coalesce((SELECT sum(r.account_credit_minor) FROM retail_sale_returns r
                      WHERE r.tenant_id = p_tenant AND r.sale_id = p_sale AND r.status = 'completed'), 0)
$$;

-- ---------------------------------------------------------------------------------------------
-- retail_sales guard (V14, replaced by V44): paid_minor may rise (a payment, or the account credit of a return),
-- and may fall only inside one of two named paths, each bound to a record that already reads reversed or voided:
--   1. the reversal of a payment on account (V44, unchanged in its binding): the transaction-local setting
--      bms.reverse_account_payment holds the id of a reversed receipt that allocated to this sale, and the new
--      paid amount is exactly the old one less that allocation;
--   2. the void of a return (new): the transaction-local setting bms.void_sale_return holds the id of a voided
--      return of this very sale, and the new paid amount is exactly the old one less the account credit that
--      return gave.
-- Both also require the new paid amount not to fall below retail_sale_settled_minor, so a second lowering for
-- the same record is refused, and a plain UPDATE that lowers paid_minor, or one with the setting naming another
-- record, a record of another sale or a record that is not reversed or voided, is refused as it was before V44.
-- Compared with V44, the floor of the first path now counts the account credit of returns too, which can only
-- make it stricter. The identity columns are compared as in V44.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION retail_sales_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.created_at, NEW.branch_id, NEW.sale_no, NEW.sale_date, NEW.payment_method,
        NEW.customer_id, NEW.buyer_name, NEW.buyer_contact, NEW.due_date, NEW.currency, NEW.total_minor,
        NEW.cost_total_minor, NEW.historical, NEW.created_by, NEW.price_list_id)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.created_at, OLD.branch_id, OLD.sale_no, OLD.sale_date, OLD.payment_method,
        OLD.customer_id, OLD.buyer_name, OLD.buyer_contact, OLD.due_date, OLD.currency, OLD.total_minor,
        OLD.cost_total_minor, OLD.historical, OLD.created_by, OLD.price_list_id)
       OR (NEW.paid_minor < OLD.paid_minor
           AND NOT EXISTS (SELECT 1 FROM retail_account_payment_allocations a
                             JOIN retail_account_payments r ON r.tenant_id = a.tenant_id AND r.id = a.receipt_id
                            WHERE a.tenant_id = NEW.tenant_id AND a.sale_id = NEW.id
                              AND a.receipt_id::text = coalesce(current_setting('bms.reverse_account_payment', true), '')
                              AND r.status = 'reversed'
                              AND NEW.paid_minor = OLD.paid_minor - a.amount_minor
                              AND NEW.paid_minor >= retail_sale_settled_minor(NEW.tenant_id, NEW.id))
           AND NOT EXISTS (SELECT 1 FROM retail_sale_returns x
                            WHERE x.tenant_id = NEW.tenant_id AND x.sale_id = NEW.id
                              AND x.id::text = coalesce(current_setting('bms.void_sale_return', true), '')
                              AND x.status = 'voided'
                              AND x.account_credit_minor > 0
                              AND NEW.paid_minor = OLD.paid_minor - x.account_credit_minor
                              AND NEW.paid_minor >= retail_sale_settled_minor(NEW.tenant_id, NEW.id)))
       OR (OLD.sale_entry_id IS NOT NULL AND NEW.sale_entry_id IS DISTINCT FROM OLD.sale_entry_id)
       OR (OLD.cost_entry_id IS NOT NULL AND NEW.cost_entry_id IS DISTINCT FROM OLD.cost_entry_id)
       OR (OLD.status = 'voided' AND (NEW.status, NEW.voided_at, NEW.voided_by, NEW.void_reason)
                                     IS DISTINCT FROM (OLD.status, OLD.voided_at, OLD.voided_by, OLD.void_reason))
       OR (OLD.status = 'completed' AND NEW.status = 'completed'
           AND (NEW.voided_at, NEW.voided_by, NEW.void_reason) IS DISTINCT FROM (OLD.voided_at, OLD.voided_by, OLD.void_reason))
    THEN
        RAISE EXCEPTION 'retail_sales: only the paid amount, the void, the entry ids (once) and the version may change'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
