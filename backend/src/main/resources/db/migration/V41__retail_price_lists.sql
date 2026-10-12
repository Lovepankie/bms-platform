-- V41: retail price lists, quantity breaks, effective dates and customer groups (ADR-033, issue #195;
-- docs/specs/trade-capabilities.md section 3).
--
-- Expand only, safe on a populated database. Three new tables (price lists, their items, customer
-- groups), two nullable columns on retail_customers, six nullable columns on retail_sale_lines, one on
-- retail_sales, and three permissions. Nothing is dropped, renamed or rewritten, and no tenant is
-- switched on: the price_lists capability lives in the tenant settings group retail.capabilities and is
-- off until an administrator turns it on. The retail price stays the product's sell_minor (it is not
-- copied into a list); a sale priced without a list, or with the capability off, is priced as before and
-- its line keeps null in every new column.
--
-- Locks, said plainly. ALTER TABLE ... ADD COLUMN and ADD CONSTRAINT ... CHECK take an ACCESS
-- EXCLUSIVE lock on the table; ADD CONSTRAINT ... FOREIGN KEY takes SHARE ROW EXCLUSIVE on it (and on
-- the table it references). Flyway runs this file in one transaction, so every one of those locks is
-- held to the commit: retail_sale_lines, retail_sales and retail_customers cannot be read or written
-- while the migration runs, and so sale history and the daily profit report wait for it. NOT VALID does
-- not change that here. The checks and foreign keys are still added NOT VALID and then validated, and
-- the validation is kept, for this reason: the scan is one pass over rows whose new columns are all null
-- (it cannot fail), the table is already locked against reads by the ADD COLUMN in the same transaction
-- so the scan blocks nothing more, and leaving a constraint NOT VALID would leave it unvalidated for
-- good (pg_constraint.convalidated false, unused by the planner). At the size of a small or medium
-- business the migration is brief; a very large tenant would want the new columns added in one
-- migration and the constraints validated in a later one (the transaction boundary is the file).
-- Flyway runs with outOfOrder off, so the number is assigned at merge time above the highest one on main.

-- ---------------------------------------------------------------------------------------------
-- retail_price_lists: a named list (wholesale, a trade partner, a contractor). The currency is the
-- tenant's and is checked by the service (multi-currency lists are deferred). strict means a product
-- missing from the list is refused at sale time instead of falling back to the retail price. A list
-- is switched off, never deleted; a list that is off is not applied and cannot be chosen.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_price_lists (
    id         uuid         NOT NULL PRIMARY KEY,
    tenant_id  uuid         NOT NULL REFERENCES tenants (id),
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz,
    version    integer      NOT NULL DEFAULT 1,
    name       varchar(100) NOT NULL CHECK (btrim(name) <> ''),
    currency   char(3)      NOT NULL,
    strict     boolean      NOT NULL DEFAULT false,
    active     boolean      NOT NULL DEFAULT true,
    created_by uuid,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX retail_price_lists_name ON retail_price_lists (tenant_id, lower(name));
SELECT bms_apply_tenant_rls('retail_price_lists');
SELECT bms_grant_app('retail_price_lists', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- retail_price_list_items: the price of one product in one unit (its base unit or a pack) from a
-- quantity (min_qty, in that unit; 0 means from any quantity) and for a date range (valid_to is the
-- last day it applies, null means open ended). Items are versioned like prices are: a change inserts
-- a new item and closes the old one by setting its valid_to, which is the only column an UPDATE may
-- change, and only once. Nothing is deleted. Two items for the same list, product, unit and min_qty may
-- not overlap in time: the unique index below covers the open ended row and the service checks closed
-- ranges under a lock on the list row (an exclusion constraint would need the btree_gist extension).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_price_list_items (
    id            uuid          NOT NULL PRIMARY KEY,
    tenant_id     uuid          NOT NULL REFERENCES tenants (id),
    created_at    timestamptz   NOT NULL DEFAULT now(),
    price_list_id uuid          NOT NULL,
    product_id    uuid          NOT NULL,
    unit_id       uuid          NOT NULL,
    min_qty       numeric(14,3) NOT NULL CHECK (min_qty >= 0),
    price_minor   bigint        NOT NULL CHECK (price_minor >= 0 AND price_minor <= 10000000000000),
    valid_from    date          NOT NULL,
    valid_to      date,
    created_by    uuid,
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, price_list_id) REFERENCES retail_price_lists (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    FOREIGN KEY (tenant_id, unit_id) REFERENCES retail_units (tenant_id, id)
);
CREATE UNIQUE INDEX retail_price_list_items_open
    ON retail_price_list_items (tenant_id, price_list_id, product_id, unit_id, min_qty)
    WHERE valid_to IS NULL;
CREATE INDEX retail_price_list_items_lookup
    ON retail_price_list_items (tenant_id, price_list_id, product_id, valid_from DESC);
CREATE INDEX retail_price_list_items_product ON retail_price_list_items (tenant_id, product_id);
SELECT bms_apply_tenant_rls('retail_price_list_items');
SELECT bms_grant_app('retail_price_list_items', 'SELECT, INSERT');
GRANT UPDATE (valid_to) ON retail_price_list_items TO bms_app;

CREATE FUNCTION retail_price_list_items_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.created_at, NEW.price_list_id, NEW.product_id, NEW.unit_id, NEW.min_qty,
        NEW.price_minor, NEW.valid_from, NEW.created_by)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.created_at, OLD.price_list_id, OLD.product_id, OLD.unit_id, OLD.min_qty,
        OLD.price_minor, OLD.valid_from, OLD.created_by)
       OR OLD.valid_to IS NOT NULL
       OR NEW.valid_to IS NULL
    THEN
        RAISE EXCEPTION 'retail_price_list_items: only valid_to may be set, once'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_price_list_items_guard_update BEFORE UPDATE ON retail_price_list_items
    FOR EACH ROW EXECUTE FUNCTION retail_price_list_items_guard_update();
CREATE TRIGGER retail_price_list_items_reject_delete BEFORE DELETE ON retail_price_list_items
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ---------------------------------------------------------------------------------------------
-- retail_customer_groups: a name and, optionally, the list every customer of the group is priced
-- from unless the customer has a list of their own. A group is switched off, never deleted.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_customer_groups (
    id            uuid         NOT NULL PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz,
    version       integer      NOT NULL DEFAULT 1,
    name          varchar(100) NOT NULL CHECK (btrim(name) <> ''),
    price_list_id uuid,
    active        boolean      NOT NULL DEFAULT true,
    created_by    uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, price_list_id) REFERENCES retail_price_lists (tenant_id, id)
);
CREATE UNIQUE INDEX retail_customer_groups_name ON retail_customer_groups (tenant_id, lower(name));
CREATE INDEX retail_customer_groups_list ON retail_customer_groups (tenant_id, price_list_id)
    WHERE price_list_id IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_customer_groups');
SELECT bms_grant_app('retail_customer_groups', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- retail_customers: the group a credit buyer belongs to and the list of their own. Both null on every
-- existing row, which reads as "priced at the retail price".
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_customers
    ADD COLUMN customer_group_id uuid,
    ADD COLUMN price_list_id     uuid;

ALTER TABLE retail_customers
    ADD CONSTRAINT retail_customers_group_fk FOREIGN KEY (tenant_id, customer_group_id)
        REFERENCES retail_customer_groups (tenant_id, id) NOT VALID,
    ADD CONSTRAINT retail_customers_price_list_fk FOREIGN KEY (tenant_id, price_list_id)
        REFERENCES retail_price_lists (tenant_id, id) NOT VALID;
ALTER TABLE retail_customers VALIDATE CONSTRAINT retail_customers_group_fk;
ALTER TABLE retail_customers VALIDATE CONSTRAINT retail_customers_price_list_fk;
CREATE INDEX retail_customers_price_list ON retail_customers (tenant_id, price_list_id)
    WHERE price_list_id IS NOT NULL;
CREATE INDEX retail_customers_group ON retail_customers (tenant_id, customer_group_id)
    WHERE customer_group_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- retail_sales: the list the sale was priced from, when one applied. A line carries its own.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_sales ADD COLUMN price_list_id uuid;
ALTER TABLE retail_sales
    ADD CONSTRAINT retail_sales_price_list_fk FOREIGN KEY (tenant_id, price_list_id)
        REFERENCES retail_price_lists (tenant_id, id) NOT VALID;
ALTER TABLE retail_sales VALIDATE CONSTRAINT retail_sales_price_list_fk;

-- ---------------------------------------------------------------------------------------------
-- retail_sale_lines: how the price was chosen, stored with the line so that editing a list never
-- changes history. price_source is retail (the product's sell price), list (an item of a list; the list
-- and the quantity break that applied are kept) or override (the seller charged another price: the price
-- the system resolved and the reason are kept, and the list that applied when there was one). The
-- resolved price is per sale unit, as sale_unit_price_minor is. Every column is null on a line made
-- without the capability, which is every line that existed. The columns are all null or follow one of
-- the three shapes below.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_sale_lines
    ADD COLUMN price_source              text,
    ADD COLUMN price_list_id             uuid,
    ADD COLUMN price_break_min_qty       numeric(14,3),
    ADD COLUMN resolved_unit_price_minor bigint,
    ADD COLUMN price_override_reason     text;

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
        OR (price_source = 'override' AND resolved_unit_price_minor IS NOT NULL AND resolved_unit_price_minor >= 0
            AND price_override_reason IS NOT NULL AND btrim(price_override_reason) <> ''
            AND char_length(price_override_reason) <= 300
            AND (price_list_id IS NULL) = (price_break_min_qty IS NULL))) NOT VALID,
    ADD CONSTRAINT retail_sale_lines_price_list_fk FOREIGN KEY (tenant_id, price_list_id)
        REFERENCES retail_price_lists (tenant_id, id) NOT VALID;
ALTER TABLE retail_sale_lines VALIDATE CONSTRAINT retail_sale_lines_price_chk;
ALTER TABLE retail_sale_lines VALIDATE CONSTRAINT retail_sale_lines_price_list_fk;

-- ---------------------------------------------------------------------------------------------
-- Permissions (chapter 8 section 8.3.2; PermissionMatrixIT compares these rows with the matrix).
-- retail.pricelist.manage and retail.pricelist.use go to administrators only: managing lists, and
-- choosing a list other than the customer's own or the customer group's, are administrator acts.
-- retail.price.override guards behaviour the sales role already has today (a unit price on a sale
-- line), so it goes to every role that holds retail.sale.create and nothing changes until an
-- administrator removes it. It is consulted only while the price_lists capability is on.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.pricelist.manage', 'retail', 'Create and edit price lists, their items and customer groups', false),
    ('retail.pricelist.use', 'retail', 'Sales: price a sale from a list other than the customer''s own', false),
    ('retail.price.override', 'retail', 'Sales: charge a price other than the one a price list resolved', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.pricelist.manage'),
    ('tenant_admin', 'retail.pricelist.use');

INSERT INTO role_permissions (role_key, permission_key)
SELECT role_key, 'retail.price.override' FROM role_permissions WHERE permission_key = 'retail.sale.create';
