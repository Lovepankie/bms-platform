-- V12: the retail vertical, increment R3 (ADR-020, issue #53): suppliers, purchases (restocks),
-- usage and damage reports, and payments against credit sales.
--
-- Additive only (chapter 6 section 6.9).

-- ---------------------------------------------------------------------------------------------
-- retail_suppliers (FR-RET-06): tenant-wide; contacts kept as entered.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_suppliers (
    id         uuid         NOT NULL PRIMARY KEY,
    tenant_id  uuid         NOT NULL REFERENCES tenants (id),
    created_at timestamptz  NOT NULL DEFAULT now(),
    name       varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    contact    varchar(100),
    active     boolean      NOT NULL DEFAULT true,
    created_by uuid,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX retail_suppliers_name ON retail_suppliers (tenant_id, lower(name));
SELECT bms_apply_tenant_rls('retail_suppliers');
SELECT bms_grant_app('retail_suppliers', 'SELECT, INSERT');

-- ---------------------------------------------------------------------------------------------
-- retail_purchases and lines (FR-RET-06). A line's cost and optional sell price are written to the
-- product, with a history row naming the purchase, in the same transaction as its movements
-- (ADR-020 decision 5). The quantities per branch are the purchase movements themselves
-- (source_type retail.purchase, source_line_id the line).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_purchases (
    id             uuid        NOT NULL PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenants (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    purchase_no    varchar(20) NOT NULL,
    supplier_id    uuid,
    purchased_on   date        NOT NULL,
    payment_method text        NOT NULL CHECK (payment_method IN ('cash', 'bank', 'credit')),
    currency       char(3)     NOT NULL REFERENCES currencies (code),
    total_minor    bigint      NOT NULL CHECK (total_minor >= 0),
    historical     boolean     NOT NULL DEFAULT false,
    note           varchar(300),
    created_by     uuid        NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, purchase_no),
    FOREIGN KEY (tenant_id, supplier_id) REFERENCES retail_suppliers (tenant_id, id),
    CHECK (payment_method <> 'credit' OR supplier_id IS NOT NULL)
);
CREATE INDEX retail_purchases_list ON retail_purchases (tenant_id, purchased_on, created_at, id);
SELECT bms_apply_tenant_rls('retail_purchases');
SELECT bms_grant_app('retail_purchases', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_purchases');

CREATE TABLE retail_purchase_lines (
    id               uuid          NOT NULL PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenants (id),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    purchase_id      uuid          NOT NULL,
    line_no          smallint      NOT NULL CHECK (line_no >= 1),
    product_id       uuid          NOT NULL,
    cost_minor       bigint        NOT NULL CHECK (cost_minor >= 0),
    sell_minor       bigint        CHECK (sell_minor >= 0),
    qty_total        numeric(14,3) NOT NULL CHECK (qty_total > 0),
    line_total_minor bigint        NOT NULL CHECK (line_total_minor >= 0),
    UNIQUE (tenant_id, id),
    UNIQUE (purchase_id, line_no),
    FOREIGN KEY (tenant_id, purchase_id) REFERENCES retail_purchases (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id)
);
CREATE INDEX retail_purchase_lines_purchase ON retail_purchase_lines (tenant_id, purchase_id);
SELECT bms_apply_tenant_rls('retail_purchase_lines');
SELECT bms_grant_app('retail_purchase_lines', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_purchase_lines');

-- ---------------------------------------------------------------------------------------------
-- retail_usage_reports and lines (FR-RET-07): usage and damage, valued at the product's cost at the
-- time (the snapshot on each line).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_usage_reports (
    id               uuid         NOT NULL PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenants (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    branch_id        uuid         NOT NULL,
    kind             text         NOT NULL CHECK (kind IN ('used', 'damaged')),
    reason           varchar(300) NOT NULL CHECK (btrim(reason) <> ''),
    occurred_on      date         NOT NULL,
    currency         char(3)      NOT NULL REFERENCES currencies (code),
    cost_total_minor bigint       NOT NULL CHECK (cost_total_minor >= 0),
    historical       boolean      NOT NULL DEFAULT false,
    journal_entry_id uuid,
    created_by       uuid         NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id)
);
CREATE INDEX retail_usage_reports_branch ON retail_usage_reports (tenant_id, branch_id, occurred_on);
SELECT bms_apply_tenant_rls('retail_usage_reports');
SELECT bms_grant_app('retail_usage_reports', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_usage_reports');

CREATE TABLE retail_usage_lines (
    id              uuid          NOT NULL PRIMARY KEY,
    tenant_id       uuid          NOT NULL REFERENCES tenants (id),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    report_id       uuid          NOT NULL,
    line_no         smallint      NOT NULL CHECK (line_no >= 1),
    product_id      uuid          NOT NULL,
    qty             numeric(14,3) NOT NULL CHECK (qty > 0),
    unit_cost_minor bigint        NOT NULL CHECK (unit_cost_minor >= 0),
    line_cost_minor bigint        NOT NULL CHECK (line_cost_minor >= 0),
    UNIQUE (tenant_id, id),
    UNIQUE (report_id, line_no),
    FOREIGN KEY (tenant_id, report_id) REFERENCES retail_usage_reports (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_usage_lines');
SELECT bms_grant_app('retail_usage_lines', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_usage_lines');

-- ---------------------------------------------------------------------------------------------
-- retail_sale_payments (FR-RET-05): payments against a credit sale, partial allowed. Each raises
-- retail_sales.paid_minor under the sale's row lock, never past the total.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_sale_payments (
    id               uuid        NOT NULL PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenants (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    sale_id          uuid        NOT NULL,
    amount_minor     bigint      NOT NULL CHECK (amount_minor > 0),
    currency         char(3)     NOT NULL REFERENCES currencies (code),
    method           text        NOT NULL CHECK (method IN ('cash', 'mobile_money', 'bank')),
    paid_on          date        NOT NULL,
    journal_entry_id uuid        NOT NULL,
    historical       boolean     NOT NULL DEFAULT false,
    created_by       uuid        NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES retail_sales (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id)
);
CREATE INDEX retail_sale_payments_sale ON retail_sale_payments (tenant_id, sale_id, paid_on);
SELECT bms_apply_tenant_rls('retail_sale_payments');
SELECT bms_grant_app('retail_sale_payments', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_sale_payments');
