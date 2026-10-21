-- V70: retail customer orders with delivery (issue #385, ADR-052): an order taken by phone, WhatsApp, Instagram, Facebook or at
-- the counter, recorded without payment, that keeps its stock aside, moves through packed and out for delivery, and on delivery
-- becomes an ordinary sale through the sale path, with the delivery fee posted on its own.
--
-- Numbered V70: main is at V69 (the installable app, #380, took V68; item variants, #384, took V69). Flyway runs with outOfOrder
-- off, so the file is renamed at merge if another number lands first.
--
-- Expand only, safe on a populated database. Three new tables, one new column on retail_stock_balances, one nullable column and a
-- wider price check on retail_sale_lines, one chart account and one permission. No existing row changes and a tenant that never takes
-- an order writes nothing.
--
-- Locks, said plainly. Flyway runs this file in one transaction, so the locks are held to the commit. ADD COLUMN with a constant
-- default is a catalogue change in PostgreSQL 16 (no table rewrite), but it takes ACCESS EXCLUSIVE on retail_stock_balances for the
-- moment it runs, which blocks sales, restocks and transfers until the commit. The check on the new column is added NOT VALID (no
-- scan under that lock) and validated after, which takes only SHARE UPDATE EXCLUSIVE. CREATE TABLE with foreign keys takes SHARE
-- ROW EXCLUSIVE on each referenced table (tenants, branches, retail_customers, retail_products, retail_units, retail_sales,
-- journal_entries) until the commit, which blocks writes to them, not reads; the new tables are empty. The chart seed touches only
-- gl_accounts.
--
-- retail_sale_lines: ADD COLUMN without a default is a catalogue change; the price check is dropped and added again NOT VALID (no
-- scan), then validated. Flyway runs the file in one transaction, so the ACCESS EXCLUSIVE lock the ALTERs take is held until the
-- commit, through the VALIDATE scan: sales wait for it. On staging the table is about 22 thousand rows (11 MB), a scan of well under
-- a second, so that is accepted; a much larger table would want the check validated in a migration of its own.
--
-- The first statement sets lock_timeout to five seconds (as V26, V48, V49, V59 and V64 do): if a lock cannot be had the migration
-- fails and is retried, rather than every writer on the table queueing behind it. SET LOCAL lasts to the commit.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- Stock kept for orders. reserved_qty is the quantity of a branch's balance that open customer orders keep aside, in base units.
-- It is changed only by StockLedger (reserve and release), under the balance row's lock, in the transaction of the order step.
-- A sale, usage, damage, transfer out or goods sent back to a supplier may not take qty below reserved_qty (insufficient_stock),
-- so a unit kept for an order is never sold at the till. A stock-take may (the count is what is on the shelf); the order then
-- shows that it cannot be filled from the shelf until stock arrives.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_stock_balances ADD COLUMN reserved_qty numeric(14,3) NOT NULL DEFAULT 0;
ALTER TABLE retail_stock_balances ADD CONSTRAINT retail_stock_balances_reserved_positive CHECK (reserved_qty >= 0) NOT VALID;
ALTER TABLE retail_stock_balances VALIDATE CONSTRAINT retail_stock_balances_reserved_positive;

-- ---------------------------------------------------------------------------------------------
-- Chart: 4120 delivery fees (income), seeded by the seed function of V64 with one row added. A code or system key a tenant
-- already has is kept, and tenants that switched retail on earlier get the account now; it stays at zero until a fee is taken.
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
        ('4120', 'Delivery fees',                         'income',    'delivery_income',             true,  false),
        ('5000', 'Expenses',                              'expense',   NULL,                          false, false),
        ('5100', 'Cost of goods sold',                    'expense',   'cost_of_goods_sold',          true,  false),
        ('5110', 'Stock shrinkage',                       'expense',   'stock_shrinkage',             true,  false),
        ('5120', 'Purchase price rounding',               'expense',   'purchase_rounding',           true,  false),
        ('5130', 'Purchase price variance',               'expense',   'purchase_price_variance',     true,  false),
        ('5140', 'Bad debt loss',                         'expense',   'bad_debt_loss',               true,  false),
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
-- Permission (chapter 8 section 8.3.2; PermissionMatrixIT compares these rows with the matrix). retail.order.manage takes, lists,
-- moves, delivers and cancels customer orders and reads their reports. It goes to the administrator and to every role that holds
-- retail.sale.create (the shop staff who answer the phone), as retail.price.override did in V41. Delivering an order also needs
-- retail.sale.create at its branch, which the sale it makes checks as any sale does. Money moving: delivery records a sale and a fee.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.order.manage', 'retail', 'Customer orders: take, pack, send, deliver and cancel orders, and read their reports', true);

INSERT INTO role_permissions (role_key, permission_key)
SELECT DISTINCT role_key, 'retail.order.manage' FROM role_permissions WHERE permission_key = 'retail.sale.create'
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- retail_customer_orders: one order. source is where it came from (whatsapp, instagram, facebook, phone, walk_in, other). The
-- customer is a credit buyer on the list (customer_id) or a name; the phone is what the shop rings and what finds the customer
-- next time (customer_phone, matched on its digits). delivery_place empty means the customer collects at the shop. The prices
-- agreed are on the lines; items_total_minor is their sum and delivery_fee_minor is charged on top, posted to 4120 on delivery.
-- status moves forward only (the guard below): ordered, packed, out_for_delivery, then delivered (sale_id set) or cancelled (with
-- a reason). delivered_by is who takes it out (a rider's name, often not a staff user). fee_method and fee_entry_id say how the fee
-- was paid and its journal entry: cash, mobile money or bank at the door, or account (a credit buyer's account, debiting trade
-- debtors against the sale); a fee on account is paid later once (fee_paid_*, its own entry crediting trade debtors), or settled
-- by the write-off of its sale (fee_paid_method written_off, the write-off's entry).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_customer_orders (
    id                 uuid         NOT NULL PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants (id),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         uuid         NOT NULL,
    updated_at         timestamptz,
    version            integer      NOT NULL DEFAULT 1,
    order_no           varchar(20)  NOT NULL,
    branch_id          uuid         NOT NULL,
    ordered_on         date         NOT NULL,
    source             text         NOT NULL CHECK (source IN ('whatsapp', 'instagram', 'facebook', 'phone', 'walk_in', 'other')),
    customer_id        uuid,
    customer_name      varchar(200) NOT NULL CHECK (btrim(customer_name) <> ''),
    customer_phone     varchar(100),
    delivery_place     varchar(300),
    delivery_date      date,
    delivery_fee_minor bigint       NOT NULL DEFAULT 0 CHECK (delivery_fee_minor >= 0 AND delivery_fee_minor <= 10000000000000),
    currency           char(3)      NOT NULL REFERENCES currencies (code),
    items_total_minor  bigint       NOT NULL CHECK (items_total_minor >= 0 AND items_total_minor <= 10000000000000),
    notes              varchar(500),
    status             text         NOT NULL DEFAULT 'ordered'
                                    CHECK (status IN ('ordered', 'packed', 'out_for_delivery', 'delivered', 'cancelled')),
    packed_at          timestamptz,
    packed_by          uuid,
    sent_at            timestamptz,
    sent_by            uuid,
    delivered_by       varchar(120),
    delivered_at       timestamptz,
    delivered_on       date,
    delivered_by_user  uuid,
    sale_id            uuid,
    fee_method         text         CHECK (fee_method IN ('cash', 'mobile_money', 'bank', 'account')),
    fee_entry_id       uuid,
    fee_paid_at        timestamptz,
    fee_paid_on        date,
    fee_paid_method    text         CHECK (fee_paid_method IN ('cash', 'mobile_money', 'bank', 'written_off')),
    fee_paid_by        uuid,
    fee_payment_entry_id uuid,
    cancelled_at       timestamptz,
    cancelled_on       date,
    cancelled_by       uuid,
    cancel_reason      varchar(300),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, order_no),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES retail_customers (tenant_id, id),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES retail_sales (tenant_id, id),
    FOREIGN KEY (tenant_id, fee_entry_id) REFERENCES journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, fee_payment_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK ((fee_paid_at IS NULL) = (fee_paid_on IS NULL) AND (fee_paid_at IS NULL) = (fee_paid_method IS NULL)
           AND (fee_paid_at IS NULL) = (fee_paid_by IS NULL) AND (fee_paid_at IS NULL) = (fee_payment_entry_id IS NULL)),
    CHECK (fee_paid_at IS NULL OR fee_method = 'account'),
    CHECK ((status = 'delivered') = (sale_id IS NOT NULL AND delivered_at IS NOT NULL AND delivered_on IS NOT NULL
                                     AND delivered_by_user IS NOT NULL)),
    CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL AND cancelled_on IS NOT NULL AND cancelled_by IS NOT NULL
                                     AND cancel_reason IS NOT NULL AND btrim(cancel_reason) <> '')),
    CHECK (sent_at IS NULL OR (sent_by IS NOT NULL AND delivered_by IS NOT NULL AND btrim(delivered_by) <> '')),
    CHECK (packed_at IS NULL OR packed_by IS NOT NULL),
    CHECK ((fee_entry_id IS NULL) OR (fee_method IS NOT NULL))
);
-- The list pages on created_at DESC, id DESC within a branch and a step; today's deliveries read by the delivery date.
CREATE INDEX retail_customer_orders_list ON retail_customer_orders (tenant_id, branch_id, status, created_at DESC, id DESC);
CREATE INDEX retail_customer_orders_due ON retail_customer_orders (tenant_id, branch_id, delivery_date)
    WHERE status IN ('ordered', 'packed', 'out_for_delivery');
CREATE INDEX retail_customer_orders_delivered ON retail_customer_orders (tenant_id, branch_id, delivered_on)
    WHERE status = 'delivered';
CREATE INDEX retail_customer_orders_cancelled ON retail_customer_orders (tenant_id, branch_id, cancelled_on)
    WHERE status = 'cancelled';
-- The quick entry finds the customer by the last nine digits of the phone (bms_digits of V40), so +256 700 000 011 and
-- 0700 000 011 are one number.
CREATE INDEX retail_customer_orders_phone ON retail_customer_orders (tenant_id, right(bms_digits(customer_phone), 9), created_at DESC)
    WHERE customer_phone IS NOT NULL;
CREATE UNIQUE INDEX retail_customer_orders_sale ON retail_customer_orders (tenant_id, sale_id) WHERE sale_id IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_customer_orders');
SELECT bms_grant_app('retail_customer_orders', 'SELECT, INSERT');
GRANT UPDATE (updated_at, version, customer_id, customer_name, customer_phone, delivery_place, delivery_date, delivery_fee_minor,
              notes, status, packed_at, packed_by, sent_at, sent_by, delivered_by, delivered_at, delivered_on, delivered_by_user,
              sale_id, fee_method, fee_entry_id, fee_paid_at, fee_paid_on, fee_paid_method, fee_paid_by, fee_payment_entry_id,
              cancelled_at, cancelled_on, cancelled_by, cancel_reason)
    ON retail_customer_orders TO bms_app;

-- An order moves forward only, by one version at a time, and nothing changes once it is delivered or cancelled, except that a
-- delivery fee left on a credit buyer's account is marked paid, once.
CREATE FUNCTION retail_customer_orders_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'retail_customer_orders: the version rises by one per change';
    END IF;
    IF OLD.status = 'delivered' AND NEW.status = 'delivered' AND OLD.fee_method = 'account' AND OLD.fee_paid_at IS NULL
       AND NEW.fee_paid_at IS NOT NULL AND NEW.sale_id = OLD.sale_id AND NEW.fee_entry_id = OLD.fee_entry_id
       AND NEW.fee_method = OLD.fee_method AND NEW.delivery_fee_minor = OLD.delivery_fee_minor THEN
        RETURN NEW;
    END IF;
    IF OLD.status IN ('delivered', 'cancelled') THEN
        RAISE EXCEPTION 'retail_customer_orders: a % order does not change', OLD.status;
    END IF;
    IF NOT ((OLD.status = NEW.status)
            OR (OLD.status = 'ordered' AND NEW.status IN ('packed', 'out_for_delivery', 'delivered', 'cancelled'))
            OR (OLD.status = 'packed' AND NEW.status IN ('out_for_delivery', 'delivered', 'cancelled'))
            OR (OLD.status = 'out_for_delivery' AND NEW.status IN ('delivered', 'cancelled'))) THEN
        RAISE EXCEPTION 'retail_customer_orders: % cannot become %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_customer_orders_guard_update BEFORE UPDATE ON retail_customer_orders
    FOR EACH ROW EXECUTE FUNCTION retail_customer_orders_guard_update();

-- ---------------------------------------------------------------------------------------------
-- retail_customer_order_lines: what was ordered, in the unit it was ordered in (unit_id, a pack of the units capability, or null
-- for the base unit), at the price agreed per that unit; base_qty is what the order keeps aside in base units. While price lists are
-- on, how the price was chosen is kept as on a sale line (price_source, the list, the break, the resolved price) and a price the
-- seller changed when taking the order is an override with its reason and who made it (overridden_by); on delivery that override is
-- carried to the sale line once, for that person. Fixed once written: a change of items is a cancelled order and a new one.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_customer_order_lines (
    id               uuid          NOT NULL PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenants (id),
    order_id         uuid          NOT NULL,
    line_no          integer       NOT NULL CHECK (line_no > 0),
    product_id       uuid          NOT NULL,
    unit_id          uuid,
    qty              numeric(14,3) NOT NULL CHECK (qty > 0),
    base_qty         numeric(14,3) NOT NULL CHECK (base_qty > 0),
    unit_price_minor bigint        NOT NULL CHECK (unit_price_minor >= 0 AND unit_price_minor <= 10000000000000),
    line_total_minor bigint        NOT NULL CHECK (line_total_minor >= 0 AND line_total_minor <= 10000000000000),
    price_source              text CHECK (price_source IN ('retail', 'list', 'override')),
    price_list_id             uuid,
    price_break_min_qty       numeric(14,3),
    resolved_unit_price_minor bigint CHECK (resolved_unit_price_minor >= 0),
    price_override_reason     varchar(300),
    overridden_by             uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, price_list_id) REFERENCES retail_price_lists (tenant_id, id),
    CHECK ((price_source = 'override') = (price_override_reason IS NOT NULL AND btrim(price_override_reason) <> ''
                                          AND overridden_by IS NOT NULL)),
    CHECK ((price_source IS NULL) = (resolved_unit_price_minor IS NULL)),
    UNIQUE (tenant_id, order_id, line_no),
    FOREIGN KEY (tenant_id, order_id) REFERENCES retail_customer_orders (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    FOREIGN KEY (tenant_id, unit_id) REFERENCES retail_units (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_customer_order_lines');
SELECT bms_grant_app('retail_customer_order_lines', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_customer_order_lines');

-- ---------------------------------------------------------------------------------------------
-- retail_customer_order_events: the order's history, who did what and when (ordered, changed, packed, out_for_delivery,
-- delivered, cancelled, and after delivery fee_paid for a fee on account and sale_voided when the sale it became is voided),
-- with the note of the step. Append-only.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_customer_order_events (
    id         uuid         NOT NULL PRIMARY KEY,
    tenant_id  uuid         NOT NULL REFERENCES tenants (id),
    created_at timestamptz  NOT NULL DEFAULT now(),
    order_id   uuid         NOT NULL,
    step       text         NOT NULL CHECK (step IN ('ordered', 'changed', 'packed', 'out_for_delivery', 'delivered', 'cancelled',
                                                     'fee_paid', 'fee_written_off', 'sale_voided')),
    note       varchar(500),
    by_user    uuid         NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, order_id) REFERENCES retail_customer_orders (tenant_id, id)
);
CREATE INDEX retail_customer_order_events_order ON retail_customer_order_events (tenant_id, order_id, created_at);
SELECT bms_apply_tenant_rls('retail_customer_order_events');
SELECT bms_grant_app('retail_customer_order_events', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_customer_order_events');

-- The quick entry also finds a credit buyer by the last nine digits of the contact. CREATE INDEX takes a SHARE lock on
-- retail_customers while it builds (reads go on, a new buyer waits); the table is small.
CREATE INDEX retail_customers_phone ON retail_customers (tenant_id, right(bms_digits(contact), 9)) WHERE contact IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- retail_sale_lines: the sale line of a delivered order. price_source order_agreed is a price agreed on the order that today's price
-- list would have priced differently: kept with the resolved price and the reason ("Price agreed on order ..."), and not counted by
-- the Controls page as a changed price. A price changed when the order was taken stays override, and price_overridden_by names who
-- changed it (the person who took the order), so the Controls page counts it once, for that person, and never for whoever delivered.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_sale_lines ADD COLUMN price_overridden_by uuid;
ALTER TABLE retail_sale_lines DROP CONSTRAINT retail_sale_lines_price_chk;
ALTER TABLE retail_sale_lines
    ADD CONSTRAINT retail_sale_lines_price_chk CHECK (
        (price_source IS NULL AND price_list_id IS NULL AND price_break_min_qty IS NULL
            AND resolved_unit_price_minor IS NULL AND price_override_reason IS NULL)
        OR (price_source = 'retail' AND price_list_id IS NULL AND price_break_min_qty IS NULL
            AND resolved_unit_price_minor IS NOT NULL AND resolved_unit_price_minor >= 0
            AND price_override_reason IS NULL)
        OR (price_source = 'list' AND price_list_id IS NOT NULL AND price_break_min_qty IS NOT NULL
            AND price_break_min_qty >= 0 AND resolved_unit_price_minor IS NOT NULL
            AND resolved_unit_price_minor >= 0 AND price_override_reason IS NULL)
        OR (price_source IN ('override', 'order_agreed') AND resolved_unit_price_minor IS NOT NULL
            AND resolved_unit_price_minor >= 0
            AND price_override_reason IS NOT NULL AND btrim(price_override_reason) <> ''
            AND char_length(price_override_reason) <= 300
            AND (price_list_id IS NULL) = (price_break_min_qty IS NULL))) NOT VALID;
ALTER TABLE retail_sale_lines VALIDATE CONSTRAINT retail_sale_lines_price_chk;

-- ---------------------------------------------------------------------------------------------
-- retail_sale_writeoffs: the part of a write-off that was a customer order's delivery fee left on the account (issue #385), so the
-- daily profit of goods leaves it out of its bad debt. 0 on every existing row. A small, append-only table; ADD COLUMN with a constant
-- default rewrites nothing, and the check is validated at once.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_sale_writeoffs ADD COLUMN fee_minor bigint NOT NULL DEFAULT 0;
ALTER TABLE retail_sale_writeoffs ADD CONSTRAINT retail_sale_writeoffs_fee_chk
    CHECK (fee_minor >= 0 AND fee_minor <= unpaid_minor AND fee_minor <= loss_minor);
