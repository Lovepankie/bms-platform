-- V11: the retail vertical, increment R2 (ADR-020, issue #52): stock movements and balances,
-- stock-takes, credit buyers, sales and their lines.
--
-- Additive only (chapter 6 section 6.9). Quantities are numeric(14,3) so metres and rolls are
-- exact; money is bigint minor units (ADR-004).

-- ---------------------------------------------------------------------------------------------
-- retail_stock_movements (FR-RET-03), append-only: stock is a ledger of movements (ADR-020
-- decision 3). The sign of the quantity follows the kind; adjustments and legacy balances may go
-- either way. unit_cost_minor is the product's cost when the movement was recorded.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_stock_movements (
    id                   uuid          NOT NULL PRIMARY KEY,
    tenant_id            uuid          NOT NULL REFERENCES tenants (id),
    created_at           timestamptz   NOT NULL DEFAULT now(),
    occurred_at          timestamptz   NOT NULL,
    branch_id            uuid          NOT NULL,
    product_id           uuid          NOT NULL,
    kind                 text          NOT NULL
                                       CHECK (kind IN ('opening', 'purchase', 'sale', 'usage', 'damage', 'adjustment',
                                                       'return', 'legacy_balance')),
    qty                  numeric(14,3) NOT NULL CHECK (qty <> 0),
    unit_cost_minor      bigint        NOT NULL CHECK (unit_cost_minor >= 0),
    source_type          varchar(50)   NOT NULL,
    source_id            uuid,
    source_line_id       uuid,
    reverses_movement_id uuid,
    historical           boolean       NOT NULL DEFAULT false,
    note                 varchar(300),
    recorded_by          uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, reverses_movement_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    FOREIGN KEY (tenant_id, reverses_movement_id) REFERENCES retail_stock_movements (tenant_id, id),
    CHECK ((kind IN ('opening', 'purchase', 'return') AND qty > 0)
        OR (kind IN ('sale', 'usage', 'damage') AND qty < 0)
        OR kind IN ('adjustment', 'legacy_balance'))
);
CREATE INDEX retail_stock_movements_balance ON retail_stock_movements (tenant_id, branch_id, product_id, created_at, id);
CREATE INDEX retail_stock_movements_source ON retail_stock_movements (tenant_id, source_type, source_id);
CREATE INDEX retail_stock_movements_list ON retail_stock_movements (tenant_id, occurred_at, id);
SELECT bms_apply_tenant_rls('retail_stock_movements');
SELECT bms_grant_app('retail_stock_movements', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_stock_movements');

-- ---------------------------------------------------------------------------------------------
-- retail_stock_balances (FR-RET-03): one row per branch and product, changed only by the module in
-- the same transaction as the movement, under the row's lock. The reconciliation job compares it
-- with the sum of movements.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_stock_balances (
    tenant_id  uuid          NOT NULL REFERENCES tenants (id),
    branch_id  uuid          NOT NULL,
    product_id uuid          NOT NULL,
    qty        numeric(14,3) NOT NULL DEFAULT 0,
    updated_at timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, branch_id, product_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id)
);
CREATE INDEX retail_stock_balances_negative ON retail_stock_balances (tenant_id, branch_id) WHERE qty < 0;
SELECT bms_apply_tenant_rls('retail_stock_balances');
SELECT bms_grant_app('retail_stock_balances', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- retail_stocktakes and lines (FR-RET-08). A draft records counts; commit writes one adjustment
-- movement per line whose count differs from the balance at that moment.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_stocktakes (
    id                  uuid         NOT NULL PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz,
    version             integer      NOT NULL DEFAULT 1,
    branch_id           uuid         NOT NULL,
    status              text         NOT NULL CHECK (status IN ('draft', 'committed')),
    note                varchar(300),
    created_by          uuid         NOT NULL,
    committed_by        uuid,
    committed_at        timestamptz,
    adjustment_entry_id uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, adjustment_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK ((status = 'committed') = (committed_at IS NOT NULL))
);
CREATE INDEX retail_stocktakes_branch ON retail_stocktakes (tenant_id, branch_id, created_at);
SELECT bms_apply_tenant_rls('retail_stocktakes');
SELECT bms_grant_app('retail_stocktakes', 'SELECT, INSERT, UPDATE');

CREATE TABLE retail_stocktake_lines (
    id                     uuid          NOT NULL PRIMARY KEY,
    tenant_id              uuid          NOT NULL REFERENCES tenants (id),
    created_at             timestamptz   NOT NULL DEFAULT now(),
    stocktake_id           uuid          NOT NULL,
    product_id             uuid          NOT NULL,
    counted_qty            numeric(14,3) NOT NULL CHECK (counted_qty >= 0),
    expected_qty           numeric(14,3) NOT NULL,
    committed_variance_qty numeric(14,3),
    unit_cost_minor        bigint        CHECK (unit_cost_minor >= 0),
    UNIQUE (tenant_id, id),
    UNIQUE (stocktake_id, product_id),
    FOREIGN KEY (tenant_id, stocktake_id) REFERENCES retail_stocktakes (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_stocktake_lines');
SELECT bms_grant_app('retail_stocktake_lines', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- retail_customers (FR-RET-05): credit buyers, tenant-wide. Contacts are kept as entered.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_customers (
    id         uuid         NOT NULL PRIMARY KEY,
    tenant_id  uuid         NOT NULL REFERENCES tenants (id),
    created_at timestamptz  NOT NULL DEFAULT now(),
    name       varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    contact    varchar(100),
    created_by uuid,
    UNIQUE (tenant_id, id)
);
CREATE INDEX retail_customers_name ON retail_customers (tenant_id, lower(name));
SELECT bms_apply_tenant_rls('retail_customers');
SELECT bms_grant_app('retail_customers', 'SELECT, INSERT');

-- ---------------------------------------------------------------------------------------------
-- retail_sales (FR-RET-04, FR-RET-05). paid_minor equals total_minor except on a credit sale,
-- where payments (increment R3) raise it. A void sets status and the void columns only; the
-- reversing movements and journals carry the rest.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_sales (
    id                    uuid         NOT NULL PRIMARY KEY,
    tenant_id             uuid         NOT NULL REFERENCES tenants (id),
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz,
    version               integer      NOT NULL DEFAULT 1,
    branch_id             uuid         NOT NULL,
    sale_no               varchar(20)  NOT NULL,
    sale_date             date         NOT NULL,
    payment_method        text         NOT NULL CHECK (payment_method IN ('cash', 'mobile_money', 'bank', 'credit')),
    customer_id           uuid,
    buyer_name            varchar(200),
    buyer_contact         varchar(100),
    due_date              date,
    currency              char(3)      NOT NULL REFERENCES currencies (code),
    total_minor           bigint       NOT NULL CHECK (total_minor >= 0),
    cost_total_minor      bigint       NOT NULL CHECK (cost_total_minor >= 0),
    paid_minor            bigint       NOT NULL CHECK (paid_minor >= 0),
    status                text         NOT NULL CHECK (status IN ('completed', 'voided')),
    historical            boolean      NOT NULL DEFAULT false,
    sale_entry_id         uuid,
    cost_entry_id         uuid,
    voided_at             timestamptz,
    voided_by             uuid,
    void_reason           varchar(300),
    created_by            uuid         NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, sale_no),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES retail_customers (tenant_id, id),
    FOREIGN KEY (tenant_id, sale_entry_id) REFERENCES journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, cost_entry_id) REFERENCES journal_entries (tenant_id, id),
    CHECK (paid_minor <= total_minor),
    CHECK (payment_method = 'credit' OR paid_minor = total_minor),
    CHECK (payment_method = 'credit' OR due_date IS NULL),
    CHECK (payment_method <> 'credit' OR customer_id IS NOT NULL OR buyer_name IS NOT NULL),
    CHECK ((status = 'voided') = (voided_at IS NOT NULL))
);
CREATE INDEX retail_sales_list ON retail_sales (tenant_id, branch_id, sale_date, created_at, id);
CREATE INDEX retail_sales_customer ON retail_sales (tenant_id, customer_id) WHERE customer_id IS NOT NULL;
SELECT bms_apply_tenant_rls('retail_sales');
SELECT bms_grant_app('retail_sales', 'SELECT, INSERT, UPDATE');

-- Lines hold the unit cost and unit price snapshots that profit is computed from (ADR-020
-- decision 5), so they are append-only.
CREATE TABLE retail_sale_lines (
    id               uuid          NOT NULL PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenants (id),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    sale_id          uuid          NOT NULL,
    line_no          smallint      NOT NULL CHECK (line_no >= 1),
    product_id       uuid          NOT NULL,
    qty              numeric(14,3) NOT NULL CHECK (qty > 0),
    unit_price_minor bigint        NOT NULL CHECK (unit_price_minor >= 0),
    unit_cost_minor  bigint        NOT NULL CHECK (unit_cost_minor >= 0),
    line_total_minor bigint        NOT NULL CHECK (line_total_minor >= 0),
    line_cost_minor  bigint        NOT NULL CHECK (line_cost_minor >= 0),
    UNIQUE (tenant_id, id),
    UNIQUE (sale_id, line_no),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES retail_sales (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id)
);
CREATE INDEX retail_sale_lines_sale ON retail_sale_lines (tenant_id, sale_id);
SELECT bms_apply_tenant_rls('retail_sale_lines');
SELECT bms_grant_app('retail_sale_lines', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_sale_lines');
