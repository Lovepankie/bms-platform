-- V47: optional retail purchasing, the `purchasing` capability (ADR-033, issue #198;
-- docs/specs/trade-capabilities.md sections 7, 10 and 12.3): purchase orders, goods received notes, supplier
-- invoices (bills) with a three-way match, and supplier payments.
--
-- Expand only, safe on a populated database. Eleven new tables, two new chart accounts, two permissions and a
-- replacement body for the retail chart seeder. Nothing is dropped, renamed or rewritten and no ALTER touches any
-- existing table, so the plain restock (retail_purchases), the stock tables and the ledger tables are not locked for
-- longer than the creation of a foreign key from a new table takes. The capability lives in the tenant settings
-- group retail.capabilities and is off until an administrator turns it on: a tenant that never switches it on never
-- writes a row to any table below, and the restock path (POST /retail/purchases) is untouched whatever the flag.
--
-- Locks, said plainly. Flyway runs this file in one transaction, so every lock below is held to the commit.
-- CREATE TABLE with foreign keys takes SHARE ROW EXCLUSIVE on each referenced table (tenants, branches, currencies,
-- retail_suppliers, retail_products, retail_units, retail_purchases, journal_entries) until the commit, which blocks
-- writes to those tables, but not reads, while the file runs. The new tables are empty, so the cost is the lock wait
-- and not a scan. The two statements that touch existing rows (the chart seed, which inserts at most two rows per
-- retail tenant into gl_accounts, and the permission rows) come first and the tables last, so the referenced tables
-- are locked for the shortest time one transaction allows. CREATE OR REPLACE FUNCTION takes no table lock.
-- Flyway runs with outOfOrder off, so the number is assigned at merge time above the highest one on main.
--
-- Landed cost (freight, duty; the next slice, issue #199) attaches later without touching these tables: a landed cost
-- bill is a retail_supplier_bills row of kind landed_cost (the value is already allowed by the check), and its
-- allocation to receipt lines is a new table keyed (tenant_id, receipt_line_id) that #199 adds. The receipt lines are
-- append-only on purpose; the landed unit cost lives in that allocation table, not here.

-- ---------------------------------------------------------------------------------------------
-- Chart additions (trade-capabilities.md section 12.1): 2110 goods received not invoiced (a liability: what has
-- been received and not yet billed, credited at the receipt and cleared by the supplier invoice) and 5130 purchase
-- price variance (the difference between the supplier's invoice and the receipt it clears). It is the seed
-- function of V45 with two rows added. A code or system key a tenant already has is kept. Tenants that switched
-- retail on earlier get the accounts now; both stay at zero until a receipt or a bill is recorded.
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
        ('1000', 'Assets',                                'asset',     NULL,                          false, false),
        ('1010', 'Cash on hand',                          'asset',     'cash_on_hand',                true,  false),
        ('1015', 'Savings reserve (restricted cash)',     'asset',     'savings_reserve',             true,  false),
        ('1020', 'Bank',                                  'asset',     'bank',                        true,  false),
        ('1035', 'Mobile money',                          'asset',     'mobile_money',                true,  false),
        ('1190', 'Inter-branch clearing',                 'asset',     'interbranch_clearing',        true,  true),
        ('1200', 'Trade debtors',                         'asset',     'trade_debtors',               true,  true),
        ('1250', 'Advances to owner and related parties', 'asset',     'owner_advances',              true,  true),
        ('1300', 'Inventory',                             'asset',     'inventory',                   true,  true),
        ('2000', 'Liabilities',                           'liability', NULL,                          false, false),
        ('2100', 'Trade creditors',                       'liability', 'trade_creditors',             true,  true),
        ('2110', 'Goods received not invoiced',           'liability', 'goods_received_not_invoiced', true,  true),
        ('3000', 'Equity',                                'equity',    NULL,                          false, false),
        ('3030', 'Opening balance equity',                'equity',    'opening_balance_equity',      true,  false),
        ('4000', 'Income',                                'income',    NULL,                          false, false),
        ('4100', 'Sales revenue',                         'income',    'sales_revenue',               true,  false),
        ('4110', 'Sales returns',                         'income',    'sales_returns',               true,  false),
        ('5000', 'Expenses',                              'expense',   NULL,                          false, false),
        ('5100', 'Cost of goods sold',                    'expense',   'cost_of_goods_sold',          true,  false),
        ('5110', 'Stock shrinkage',                       'expense',   'stock_shrinkage',             true,  false),
        ('5120', 'Purchase price rounding',               'expense',   'purchase_rounding',           true,  false),
        ('5130', 'Purchase price variance',               'expense',   'purchase_price_variance',     true,  false),
        ('5900', 'Operating expenses',                    'expense',   'operating_expenses',          true,  false)
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
-- Permissions (chapter 8 section 8.3.2; PermissionMatrixIT compares these rows with the matrix). Both go to
-- administrators only. retail.payable.manage covers supplier invoices, supplier payments and the payables
-- reports (money owed to suppliers); retail.purchase.approve is the checker's permission for a large purchase
-- order (the action retail_purchase_order, maker-checker, ADR-015): the maker raises with retail.purchase.create,
-- a different holder of retail.purchase.approve decides. Orders and receipts are made with the existing
-- retail.purchase.create. All are consulted only while the purchasing capability is on.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.payable.manage', 'retail', 'Purchasing: record supplier invoices and payments, read what is owed to suppliers', true),
    ('retail.purchase.approve', 'retail', 'Purchasing: approve a large purchase order', true);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.payable.manage'),
    ('tenant_admin', 'retail.purchase.approve');

-- ---------------------------------------------------------------------------------------------
-- retail_purchasing_settings: the two tolerances of the capability, one row per tenant, read as zero when absent.
-- over_receipt_bp is how far above the ordered quantity a receipt line may go (basis points of the ordered
-- quantity, 0 means a receipt can never exceed its order). bill_tolerance_bp is how far a supplier invoice may
-- differ in value from the receipts it clears, in basis points of the value cleared, before the bill needs a
-- reason (0 means any difference needs one). Edited under If-Match on version, by core.settings.manage.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_purchasing_settings (
    tenant_id         uuid        NOT NULL PRIMARY KEY REFERENCES tenants (id),
    over_receipt_bp   integer     NOT NULL DEFAULT 0 CHECK (over_receipt_bp BETWEEN 0 AND 10000),
    bill_tolerance_bp integer     NOT NULL DEFAULT 0 CHECK (bill_tolerance_bp BETWEEN 0 AND 10000),
    version           integer     NOT NULL DEFAULT 1,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    updated_by        uuid
);
SELECT bms_apply_tenant_rls('retail_purchasing_settings');
SELECT bms_grant_app('retail_purchasing_settings', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- retail_purchase_orders and lines. An order posts nothing and moves no stock. It is numbered
-- PO + branch code + six digits per tenant and branch (the branch that expects the goods). Lines are in base units
-- (qty, cost_minor per base unit) with the unit they were entered in kept beside them (V39 shape): unit_qty,
-- unit_factor and unit_cost_minor are all null for a line entered in the base unit. line_total_minor is the amount as
-- entered, rounded half up once. While the order is a draft its header and lines are edited (the lines are replaced,
-- under the order's row lock); once sent they never change, only the status moves: sent, partly_received or
-- received as receipts are posted and voided, cancelled for what is left. sent_by and approved_by are the maker and
-- the checker when a threshold made the send a maker-checker action.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_purchase_orders (
    id           uuid         NOT NULL PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenants (id),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz,
    version      integer      NOT NULL DEFAULT 1,
    order_no     varchar(24)  NOT NULL,
    branch_seq   bigint       NOT NULL CHECK (branch_seq >= 1),
    branch_id    uuid         NOT NULL,
    supplier_id  uuid         NOT NULL,
    ordered_on   date         NOT NULL,
    expected_on  date,
    currency     char(3)      NOT NULL REFERENCES currencies (code),
    total_minor  bigint       NOT NULL CHECK (total_minor >= 0 AND total_minor <= 10000000000000),
    note         varchar(300),
    status       text         NOT NULL DEFAULT 'draft'
                              CHECK (status IN ('draft', 'sent', 'partly_received', 'received', 'cancelled')),
    created_by   uuid         NOT NULL,
    updated_by   uuid,
    sent_at      timestamptz,
    sent_by      uuid,
    approval_id  uuid,
    approved_by  uuid,
    cancelled_at timestamptz,
    cancelled_by uuid,
    cancel_reason varchar(300),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, order_no),
    UNIQUE (tenant_id, branch_id, branch_seq),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES retail_suppliers (tenant_id, id),
    CHECK (expected_on IS NULL OR expected_on >= ordered_on),
    CHECK (status <> 'draft' OR (sent_at IS NULL AND sent_by IS NULL)),
    CHECK (status NOT IN ('sent', 'partly_received', 'received') OR (sent_at IS NOT NULL AND sent_by IS NOT NULL)),
    CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL)),
    CHECK ((status = 'cancelled') = (cancelled_by IS NOT NULL)),
    CHECK ((status = 'cancelled') = (cancel_reason IS NOT NULL AND btrim(cancel_reason) <> ''))
);
CREATE INDEX retail_purchase_orders_branch ON retail_purchase_orders (tenant_id, branch_id, created_at DESC, id);
CREATE INDEX retail_purchase_orders_supplier ON retail_purchase_orders (tenant_id, supplier_id, status);
SELECT bms_apply_tenant_rls('retail_purchase_orders');
SELECT bms_grant_app('retail_purchase_orders', 'SELECT, INSERT, UPDATE');

CREATE FUNCTION retail_purchase_orders_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.created_at, NEW.order_no, NEW.branch_seq, NEW.currency, NEW.created_by)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.created_at, OLD.order_no, OLD.branch_seq, OLD.currency, OLD.created_by)
    THEN
        RAISE EXCEPTION 'retail_purchase_orders: the identity of an order never changes'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- Only a draft is edited.
    IF OLD.status <> 'draft'
       AND (NEW.supplier_id, NEW.branch_id, NEW.ordered_on, NEW.expected_on, NEW.total_minor, NEW.note, NEW.updated_by)
           IS DISTINCT FROM
           (OLD.supplier_id, OLD.branch_id, OLD.ordered_on, OLD.expected_on, OLD.total_minor, OLD.note, OLD.updated_by)
    THEN
        RAISE EXCEPTION 'retail_purchase_orders: an order that has been sent is not edited'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- The moves: a draft is sent or cancelled; a sent order moves between sent, partly received and received as
    -- receipts are posted and voided, or is cancelled before it is fully received; a cancelled order stays.
    IF NOT (OLD.status = NEW.status
            OR (OLD.status = 'draft' AND NEW.status IN ('sent', 'cancelled'))
            OR (OLD.status IN ('sent', 'partly_received', 'received') AND NEW.status IN ('sent', 'partly_received', 'received'))
            OR (OLD.status IN ('sent', 'partly_received') AND NEW.status = 'cancelled'))
    THEN
        RAISE EXCEPTION 'retail_purchase_orders: no move from % to %', OLD.status, NEW.status
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- The send columns are set on the way out of draft and fixed after; the cancel columns on the way to cancelled.
    IF OLD.status <> 'draft'
       AND (NEW.sent_at, NEW.sent_by, NEW.approval_id, NEW.approved_by)
           IS DISTINCT FROM (OLD.sent_at, OLD.sent_by, OLD.approval_id, OLD.approved_by)
    THEN
        RAISE EXCEPTION 'retail_purchase_orders: who sent an order is fixed'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.status = 'cancelled'
       AND (NEW.cancelled_at, NEW.cancelled_by, NEW.cancel_reason)
           IS DISTINCT FROM (OLD.cancelled_at, OLD.cancelled_by, OLD.cancel_reason)
    THEN
        RAISE EXCEPTION 'retail_purchase_orders: a cancellation is fixed'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_purchase_orders_guard_update BEFORE UPDATE ON retail_purchase_orders
    FOR EACH ROW EXECUTE FUNCTION retail_purchase_orders_guard_update();
CREATE TRIGGER retail_purchase_orders_reject_delete BEFORE DELETE ON retail_purchase_orders
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TABLE retail_purchase_order_lines (
    id               uuid          NOT NULL PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenants (id),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    order_id         uuid          NOT NULL,
    line_no          smallint      NOT NULL CHECK (line_no >= 1),
    product_id       uuid          NOT NULL,
    qty              numeric(14,3) NOT NULL CHECK (qty > 0),
    unit_id          uuid,
    unit_qty         numeric(14,3) CHECK (unit_qty > 0),
    unit_factor      numeric(14,3) CHECK (unit_factor >= 1),
    unit_cost_minor  bigint        CHECK (unit_cost_minor >= 0),
    cost_minor       bigint        NOT NULL CHECK (cost_minor >= 0),
    line_total_minor bigint        NOT NULL CHECK (line_total_minor >= 0 AND line_total_minor <= 10000000000000),
    UNIQUE (tenant_id, id),
    UNIQUE (order_id, line_no),
    FOREIGN KEY (tenant_id, order_id) REFERENCES retail_purchase_orders (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    FOREIGN KEY (tenant_id, unit_id) REFERENCES retail_units (tenant_id, id),
    CHECK ((unit_id IS NULL) = (unit_qty IS NULL) AND (unit_id IS NULL) = (unit_factor IS NULL)
           AND (unit_id IS NULL) = (unit_cost_minor IS NULL))
);
CREATE INDEX retail_purchase_order_lines_order ON retail_purchase_order_lines (tenant_id, order_id);
SELECT bms_apply_tenant_rls('retail_purchase_order_lines');
SELECT bms_grant_app('retail_purchase_order_lines', 'SELECT, INSERT, DELETE');

-- A line is inserted or deleted only while its order is a draft (a draft edit replaces the lines); a sent order's lines
-- are as good as append-only. There is no UPDATE privilege on the table at all.
CREATE FUNCTION retail_purchase_order_lines_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_order uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.order_id ELSE NEW.order_id END;
    v_tenant uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
    v_status text;
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    SELECT status INTO v_status FROM retail_purchase_orders WHERE tenant_id = v_tenant AND id = v_order;
    IF v_status IS DISTINCT FROM 'draft' THEN
        RAISE EXCEPTION 'retail_purchase_order_lines: the lines of an order that has been sent do not change'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;
CREATE TRIGGER retail_purchase_order_lines_guard BEFORE INSERT OR UPDATE OR DELETE ON retail_purchase_order_lines
    FOR EACH ROW EXECUTE FUNCTION retail_purchase_order_lines_guard();

-- ---------------------------------------------------------------------------------------------
-- retail_goods_receipts and lines: goods received into one branch (normally a store), against an order or blind.
-- Numbered GR + branch code + six digits per tenant and branch. A receipt is the same event as a plain restock
-- for stock, cost and price (purchase movements, the product's cost and history row) and posts inventory against
-- goods received not invoiced instead of against cash, bank or trade creditors. total_minor is the value as
-- entered (what goes to goods received not invoiced); the lines keep, per base unit, the cost the movement carries
-- (cost_minor, rounded half up from the entered cost per pack) and stocked_minor, so the difference
-- (total_minor less the stocked sum) is exactly what was posted to purchase rounding. Only the void changes a row,
-- once. Receipt lines are append-only; a bill clears them by quantity (retail_supplier_bill_lines).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_goods_receipts (
    id               uuid         NOT NULL PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz,
    version          integer      NOT NULL DEFAULT 1,
    receipt_no       varchar(24)  NOT NULL,
    branch_seq       bigint       NOT NULL CHECK (branch_seq >= 1),
    branch_id        uuid         NOT NULL,
    supplier_id      uuid         NOT NULL,
    order_id         uuid,
    received_on      date         NOT NULL,
    currency         char(3)      NOT NULL REFERENCES currencies (code),
    total_minor      bigint       NOT NULL CHECK (total_minor >= 0 AND total_minor <= 10000000000000),
    delivery_ref     varchar(60),
    note             varchar(300),
    status           text         NOT NULL DEFAULT 'posted' CHECK (status IN ('posted', 'voided')),
    journal_entry_id uuid,
    created_by       uuid         NOT NULL,
    voided_at        timestamptz,
    voided_on        date,
    voided_by        uuid,
    void_reason      varchar(300),
    void_entry_id    uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, receipt_no),
    UNIQUE (tenant_id, branch_id, branch_seq),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES retail_suppliers (tenant_id, id),
    FOREIGN KEY (tenant_id, order_id) REFERENCES retail_purchase_orders (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, void_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK ((status = 'voided') = (voided_at IS NOT NULL)),
    CHECK ((status = 'voided') = (voided_on IS NOT NULL)),
    CHECK ((status = 'voided') = (voided_by IS NOT NULL)),
    CHECK ((status = 'voided') = (void_reason IS NOT NULL AND btrim(void_reason) <> ''))
);
CREATE INDEX retail_goods_receipts_branch ON retail_goods_receipts (tenant_id, branch_id, received_on DESC, created_at DESC, id);
CREATE INDEX retail_goods_receipts_supplier ON retail_goods_receipts (tenant_id, supplier_id, received_on);
CREATE INDEX retail_goods_receipts_order ON retail_goods_receipts (tenant_id, order_id) WHERE order_id IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_goods_receipts');
SELECT bms_grant_app('retail_goods_receipts', 'SELECT, INSERT');
GRANT UPDATE (updated_at, version, status, voided_at, voided_on, voided_by, void_reason, void_entry_id)
    ON retail_goods_receipts TO bms_app;

CREATE FUNCTION retail_goods_receipts_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF OLD.status <> 'posted' OR NEW.status <> 'voided'
       OR (NEW.id, NEW.tenant_id, NEW.created_at, NEW.receipt_no, NEW.branch_seq, NEW.branch_id, NEW.supplier_id,
           NEW.order_id, NEW.received_on, NEW.currency, NEW.total_minor, NEW.delivery_ref, NEW.note,
           NEW.journal_entry_id, NEW.created_by)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.created_at, OLD.receipt_no, OLD.branch_seq, OLD.branch_id, OLD.supplier_id,
           OLD.order_id, OLD.received_on, OLD.currency, OLD.total_minor, OLD.delivery_ref, OLD.note,
           OLD.journal_entry_id, OLD.created_by)
    THEN
        RAISE EXCEPTION 'retail_goods_receipts: a receipt can only be voided, once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE FUNCTION retail_goods_receipts_guard_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF NEW.status <> 'posted' OR NEW.voided_at IS NOT NULL OR NEW.void_entry_id IS NOT NULL OR NEW.version <> 1 THEN
        RAISE EXCEPTION 'retail_goods_receipts: a receipt is recorded posted and is voided afterwards'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_goods_receipts_guard_insert BEFORE INSERT ON retail_goods_receipts
    FOR EACH ROW EXECUTE FUNCTION retail_goods_receipts_guard_insert();
CREATE TRIGGER retail_goods_receipts_guard_update BEFORE UPDATE ON retail_goods_receipts
    FOR EACH ROW EXECUTE FUNCTION retail_goods_receipts_guard_update();
CREATE TRIGGER retail_goods_receipts_reject_delete BEFORE DELETE ON retail_goods_receipts
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TABLE retail_goods_receipt_lines (
    id                 uuid          NOT NULL PRIMARY KEY,
    tenant_id          uuid          NOT NULL REFERENCES tenants (id),
    created_at         timestamptz   NOT NULL DEFAULT now(),
    receipt_id         uuid          NOT NULL,
    line_no            smallint      NOT NULL CHECK (line_no >= 1),
    order_line_id      uuid,
    product_id         uuid          NOT NULL,
    qty                numeric(14,3) NOT NULL CHECK (qty > 0),
    unit_id            uuid,
    unit_qty           numeric(14,3) CHECK (unit_qty > 0),
    unit_factor        numeric(14,3) CHECK (unit_factor >= 1),
    entered_cost_minor bigint        NOT NULL CHECK (entered_cost_minor >= 0),
    cost_minor         bigint        NOT NULL CHECK (cost_minor >= 0),
    sell_minor         bigint        CHECK (sell_minor >= 0),
    line_total_minor   bigint        NOT NULL CHECK (line_total_minor >= 0 AND line_total_minor <= 10000000000000),
    stocked_minor      bigint        NOT NULL CHECK (stocked_minor >= 0 AND stocked_minor <= 10000000000000),
    UNIQUE (tenant_id, id),
    UNIQUE (receipt_id, line_no),
    FOREIGN KEY (tenant_id, receipt_id) REFERENCES retail_goods_receipts (tenant_id, id),
    FOREIGN KEY (tenant_id, order_line_id) REFERENCES retail_purchase_order_lines (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    FOREIGN KEY (tenant_id, unit_id) REFERENCES retail_units (tenant_id, id),
    CHECK ((unit_id IS NULL) = (unit_qty IS NULL) AND (unit_id IS NULL) = (unit_factor IS NULL)),
    CHECK (unit_factor IS NULL OR qty = unit_qty * unit_factor)
);
CREATE INDEX retail_goods_receipt_lines_receipt ON retail_goods_receipt_lines (tenant_id, receipt_id);
CREATE INDEX retail_goods_receipt_lines_order_line ON retail_goods_receipt_lines (tenant_id, order_line_id)
    WHERE order_line_id IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_goods_receipt_lines');
SELECT bms_grant_app('retail_goods_receipt_lines', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_goods_receipt_lines');

-- ---------------------------------------------------------------------------------------------
-- retail_supplier_bills and lines: the supplier's invoice, matched to the receipt lines it bills. Numbered SB +
-- branch code + six digits per tenant and branch (the branch of its first receipt). supplier_ref is the supplier's
-- own invoice number, unique per supplier among bills that stand (a voided bill's number can be entered again).
-- Each line bills a quantity of one receipt line at the supplier's price; cleared_minor is the part of the receipt
-- line's value (goods received not invoiced) that quantity clears, the last piece of a line taking the remainder so
-- the pieces add up exactly; variance_minor is the line total less that, posted to purchase price variance.
-- total_minor = cleared_minor + variance_minor holds for the bill and for every line. A bill never bills more than
-- was received and not yet billed. kind is goods; landed_cost is allowed by the check for the next slice (#199) and
-- is not written by this one. Only the void changes a row, once, and not while a payment stands against it.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_supplier_bills (
    id              uuid         NOT NULL PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenants (id),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz,
    version         integer      NOT NULL DEFAULT 1,
    bill_no         varchar(24)  NOT NULL,
    branch_seq      bigint       NOT NULL CHECK (branch_seq >= 1),
    branch_id       uuid         NOT NULL,
    supplier_id     uuid         NOT NULL,
    supplier_ref    varchar(60)  NOT NULL CHECK (btrim(supplier_ref) <> ''),
    bill_date       date         NOT NULL,
    due_date        date         NOT NULL,
    currency        char(3)      NOT NULL REFERENCES currencies (code),
    kind            text         NOT NULL DEFAULT 'goods' CHECK (kind IN ('goods', 'landed_cost')),
    total_minor     bigint       NOT NULL CHECK (total_minor > 0 AND total_minor <= 10000000000000),
    cleared_minor   bigint       NOT NULL CHECK (cleared_minor >= 0),
    variance_minor  bigint       NOT NULL,
    variance_reason varchar(300),
    note            varchar(300),
    status          text         NOT NULL DEFAULT 'posted' CHECK (status IN ('posted', 'voided')),
    created_by      uuid         NOT NULL,
    voided_at       timestamptz,
    voided_on       date,
    voided_by       uuid,
    void_reason     varchar(300),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, bill_no),
    UNIQUE (tenant_id, branch_id, branch_seq),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES retail_suppliers (tenant_id, id),
    CHECK (due_date >= bill_date),
    CHECK (total_minor = cleared_minor + variance_minor),
    CHECK ((status = 'voided') = (voided_at IS NOT NULL)),
    CHECK ((status = 'voided') = (voided_on IS NOT NULL)),
    CHECK ((status = 'voided') = (voided_by IS NOT NULL)),
    CHECK ((status = 'voided') = (void_reason IS NOT NULL AND btrim(void_reason) <> ''))
);
CREATE UNIQUE INDEX retail_supplier_bills_ref ON retail_supplier_bills (tenant_id, supplier_id, lower(supplier_ref))
    WHERE status = 'posted';
CREATE INDEX retail_supplier_bills_supplier ON retail_supplier_bills (tenant_id, supplier_id, due_date)
    WHERE status = 'posted';
CREATE INDEX retail_supplier_bills_branch ON retail_supplier_bills (tenant_id, branch_id, created_at DESC, id);
SELECT bms_apply_tenant_rls('retail_supplier_bills');
SELECT bms_grant_app('retail_supplier_bills', 'SELECT, INSERT');
GRANT UPDATE (updated_at, version, status, voided_at, voided_on, voided_by, void_reason)
    ON retail_supplier_bills TO bms_app;

CREATE FUNCTION retail_supplier_bills_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF OLD.status <> 'posted' OR NEW.status <> 'voided'
       OR (NEW.id, NEW.tenant_id, NEW.created_at, NEW.bill_no, NEW.branch_seq, NEW.branch_id, NEW.supplier_id,
           NEW.supplier_ref, NEW.bill_date, NEW.due_date, NEW.currency, NEW.kind, NEW.total_minor, NEW.cleared_minor,
           NEW.variance_minor, NEW.variance_reason, NEW.note, NEW.created_by)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.created_at, OLD.bill_no, OLD.branch_seq, OLD.branch_id, OLD.supplier_id,
           OLD.supplier_ref, OLD.bill_date, OLD.due_date, OLD.currency, OLD.kind, OLD.total_minor, OLD.cleared_minor,
           OLD.variance_minor, OLD.variance_reason, OLD.note, OLD.created_by)
    THEN
        RAISE EXCEPTION 'retail_supplier_bills: a bill can only be voided, once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE FUNCTION retail_supplier_bills_guard_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF NEW.status <> 'posted' OR NEW.voided_at IS NOT NULL OR NEW.version <> 1 THEN
        RAISE EXCEPTION 'retail_supplier_bills: a bill is recorded posted and is voided afterwards'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_supplier_bills_guard_insert BEFORE INSERT ON retail_supplier_bills
    FOR EACH ROW EXECUTE FUNCTION retail_supplier_bills_guard_insert();
CREATE TRIGGER retail_supplier_bills_guard_update BEFORE UPDATE ON retail_supplier_bills
    FOR EACH ROW EXECUTE FUNCTION retail_supplier_bills_guard_update();
CREATE TRIGGER retail_supplier_bills_reject_delete BEFORE DELETE ON retail_supplier_bills
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TABLE retail_supplier_bill_lines (
    id               uuid          NOT NULL PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenants (id),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    bill_id          uuid          NOT NULL,
    line_no          smallint      NOT NULL CHECK (line_no >= 1),
    receipt_line_id  uuid          NOT NULL,
    qty              numeric(14,3) NOT NULL CHECK (qty > 0),
    unit_qty         numeric(14,3) CHECK (unit_qty > 0),
    unit_price_minor bigint        NOT NULL CHECK (unit_price_minor >= 0),
    line_total_minor bigint        NOT NULL CHECK (line_total_minor >= 0 AND line_total_minor <= 10000000000000),
    cleared_minor    bigint        NOT NULL CHECK (cleared_minor >= 0),
    variance_minor   bigint        NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (bill_id, line_no),
    UNIQUE (bill_id, receipt_line_id),
    FOREIGN KEY (tenant_id, bill_id) REFERENCES retail_supplier_bills (tenant_id, id),
    FOREIGN KEY (tenant_id, receipt_line_id) REFERENCES retail_goods_receipt_lines (tenant_id, id),
    CHECK (variance_minor = line_total_minor - cleared_minor)
);
CREATE INDEX retail_supplier_bill_lines_bill ON retail_supplier_bill_lines (tenant_id, bill_id);
CREATE INDEX retail_supplier_bill_lines_receipt_line ON retail_supplier_bill_lines (tenant_id, receipt_line_id);
SELECT bms_apply_tenant_rls('retail_supplier_bill_lines');
SELECT bms_grant_app('retail_supplier_bill_lines', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_supplier_bill_lines');

-- The journal entries of a bill, one per branch of the receipts it clears (the entry of a branch clears that
-- branch's goods received not invoiced), kept so a void can reverse each of them.
CREATE TABLE retail_supplier_bill_entries (
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    bill_id    uuid        NOT NULL,
    branch_id  uuid        NOT NULL,
    entry_id   uuid        NOT NULL,
    PRIMARY KEY (tenant_id, bill_id, branch_id),
    FOREIGN KEY (tenant_id, bill_id) REFERENCES retail_supplier_bills (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES journal_entries (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_supplier_bill_entries');
SELECT bms_grant_app('retail_supplier_bill_entries', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_supplier_bill_entries');

-- ---------------------------------------------------------------------------------------------
-- retail_supplier_payments and allocations: money paid to a supplier, from the till, mobile money or bank of one
-- branch, numbered SP + branch code + six digits per tenant and branch. A payment is split, when it is recorded,
-- over the supplier's open items: supplier bills and credit restocks (retail_purchases paid by credit, which until
-- now only raised trade creditors and nothing could pay). Every unit of the amount is allocated; nothing is held
-- as a deposit. An allocation row is per item and per branch: a credit restock credits trade creditors in each branch its
-- stock went into, so what a payment pays on it is split over those branches (branch_id) in proportion to what each still
-- owes, and the payment debits each branch's creditors by its part (a supplier invoice is one row, in the bill's branch).
-- A payment is never edited: it is reversed as a whole, once, and its allocations then stop counting.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_supplier_payments (
    id                uuid         NOT NULL PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenants (id),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz,
    version           integer      NOT NULL DEFAULT 1,
    payment_no        varchar(24)  NOT NULL,
    branch_seq        bigint       NOT NULL CHECK (branch_seq >= 1),
    branch_id         uuid         NOT NULL,
    supplier_id       uuid         NOT NULL,
    paid_on           date         NOT NULL,
    currency          char(3)      NOT NULL REFERENCES currencies (code),
    method            text         NOT NULL CHECK (method IN ('cash', 'mobile_money', 'bank')),
    amount_minor      bigint       NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 10000000000000),
    allocation        text         NOT NULL CHECK (allocation IN ('oldest_first', 'manual')),
    reference         varchar(60),
    note              varchar(300),
    status            text         NOT NULL DEFAULT 'posted' CHECK (status IN ('posted', 'reversed')),
    journal_entry_id  uuid         NOT NULL,
    created_by        uuid         NOT NULL,
    reversed_at       timestamptz,
    reversed_on       date,
    reversed_by       uuid,
    reverse_reason    varchar(300),
    reversal_entry_id uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, payment_no),
    UNIQUE (tenant_id, branch_id, branch_seq),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES retail_suppliers (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, reversal_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK ((status = 'reversed') = (reversed_at IS NOT NULL)),
    CHECK ((status = 'reversed') = (reversed_on IS NOT NULL)),
    CHECK ((status = 'reversed') = (reversed_by IS NOT NULL)),
    CHECK ((status = 'reversed') = (reverse_reason IS NOT NULL AND btrim(reverse_reason) <> '')),
    CHECK ((status = 'reversed') = (reversal_entry_id IS NOT NULL))
);
CREATE INDEX retail_supplier_payments_supplier ON retail_supplier_payments (tenant_id, supplier_id, paid_on DESC, id);
CREATE INDEX retail_supplier_payments_branch ON retail_supplier_payments (tenant_id, branch_id, paid_on);
SELECT bms_apply_tenant_rls('retail_supplier_payments');
SELECT bms_grant_app('retail_supplier_payments', 'SELECT, INSERT');
GRANT UPDATE (updated_at, version, status, reversed_at, reversed_on, reversed_by, reverse_reason, reversal_entry_id)
    ON retail_supplier_payments TO bms_app;

CREATE FUNCTION retail_supplier_payments_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF OLD.status <> 'posted' OR NEW.status <> 'reversed'
       OR (NEW.id, NEW.tenant_id, NEW.created_at, NEW.payment_no, NEW.branch_seq, NEW.branch_id, NEW.supplier_id,
           NEW.paid_on, NEW.currency, NEW.method, NEW.amount_minor, NEW.allocation, NEW.reference, NEW.note,
           NEW.journal_entry_id, NEW.created_by)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.created_at, OLD.payment_no, OLD.branch_seq, OLD.branch_id, OLD.supplier_id,
           OLD.paid_on, OLD.currency, OLD.method, OLD.amount_minor, OLD.allocation, OLD.reference, OLD.note,
           OLD.journal_entry_id, OLD.created_by)
    THEN
        RAISE EXCEPTION 'retail_supplier_payments: a payment can only be reversed, once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE FUNCTION retail_supplier_payments_guard_insert() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF NEW.status <> 'posted' OR NEW.reversed_at IS NOT NULL OR NEW.version <> 1 THEN
        RAISE EXCEPTION 'retail_supplier_payments: a payment is recorded posted and is reversed afterwards'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_supplier_payments_guard_insert BEFORE INSERT ON retail_supplier_payments
    FOR EACH ROW EXECUTE FUNCTION retail_supplier_payments_guard_insert();
CREATE TRIGGER retail_supplier_payments_guard_update BEFORE UPDATE ON retail_supplier_payments
    FOR EACH ROW EXECUTE FUNCTION retail_supplier_payments_guard_update();
CREATE TRIGGER retail_supplier_payments_reject_delete BEFORE DELETE ON retail_supplier_payments
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TABLE retail_supplier_payment_allocations (
    id           uuid        NOT NULL PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    payment_id   uuid        NOT NULL,
    line_no      smallint    NOT NULL CHECK (line_no >= 1),
    bill_id      uuid,
    purchase_id  uuid,
    branch_id    uuid        NOT NULL,
    amount_minor bigint      NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 10000000000000),
    UNIQUE (tenant_id, id),
    UNIQUE (payment_id, line_no),
    FOREIGN KEY (tenant_id, payment_id) REFERENCES retail_supplier_payments (tenant_id, id),
    FOREIGN KEY (tenant_id, bill_id) REFERENCES retail_supplier_bills (tenant_id, id),
    FOREIGN KEY (tenant_id, purchase_id) REFERENCES retail_purchases (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    CHECK ((bill_id IS NULL) <> (purchase_id IS NULL))
);
CREATE UNIQUE INDEX retail_supplier_payment_allocations_bill ON retail_supplier_payment_allocations (payment_id, bill_id, branch_id)
    WHERE bill_id IS NOT NULL;
CREATE UNIQUE INDEX retail_supplier_payment_allocations_purchase ON retail_supplier_payment_allocations (payment_id, purchase_id, branch_id)
    WHERE purchase_id IS NOT NULL;
CREATE INDEX retail_supplier_payment_allocations_bill_open ON retail_supplier_payment_allocations (tenant_id, bill_id)
    WHERE bill_id IS NOT NULL;
CREATE INDEX retail_supplier_payment_allocations_purchase_open ON retail_supplier_payment_allocations (tenant_id, purchase_id)
    WHERE purchase_id IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_supplier_payment_allocations');
SELECT bms_grant_app('retail_supplier_payment_allocations', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_supplier_payment_allocations');

-- The journal entries of a payment in branches other than the paying one (the debt sits in the branch of each invoice;
-- the paying branch clears through inter-branch clearing), kept so a reversal can reverse each of them.
CREATE TABLE retail_supplier_payment_entries (
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    payment_id uuid        NOT NULL,
    branch_id  uuid        NOT NULL,
    entry_id   uuid        NOT NULL,
    PRIMARY KEY (tenant_id, payment_id, branch_id),
    FOREIGN KEY (tenant_id, payment_id) REFERENCES retail_supplier_payments (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES journal_entries (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_supplier_payment_entries');
SELECT bms_grant_app('retail_supplier_payment_entries', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_supplier_payment_entries');
