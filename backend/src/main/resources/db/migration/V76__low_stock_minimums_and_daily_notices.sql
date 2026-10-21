-- V76: the low-stock minimum of an item, per shop or store if wanted, and the record of the daily push notices
-- (issue #412, ADR-057).
--
-- Main is at V75 (the operator screens, #413, issue #407, after the retail roles V73 and V74 of #403 and #404), so this
-- takes V76, directly after it. Flyway runs with outOfOrder off (AGENTS.md): if another migration lands on main first, this
-- file is renamed to the highest number on main plus one at merge.
--
-- Expand only: two new, empty tables. Nothing that exists is changed, so the previous release runs unchanged on this schema.
--
--   1. retail_stock_minimums: "warn me when stock is below" for one item (a product, so each size and colour of an item
--      with sizes has its own). A row with no branch is the level for every shop and store; a row with a branch is that
--      branch's own level and wins over the one for every branch. No row at all means the item has no minimum, and only the
--      pace rule (days of cover at the recent selling pace) says it is low.
--   2. retail_notice_runs: one row per business, kind and day once a daily notice (the credit payments of the morning, the
--      closing summary of the evening) was prepared, so the job that wakes every 15 minutes prepares each one once a day.

-- ---------------------------------------------------------------------------------------------
-- 1. The low-stock minimum. min_qty is in the item's base unit with three places, like the balances (numeric(14,3)).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_stock_minimums (
    id          uuid          NOT NULL PRIMARY KEY,
    tenant_id   uuid          NOT NULL REFERENCES tenants (id),
    product_id  uuid          NOT NULL,
    branch_id   uuid,
    min_qty     numeric(14,3) NOT NULL CHECK (min_qty > 0),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  uuid,
    UNIQUE (tenant_id, id),
    -- One level for every branch and one per branch: NULLS NOT DISTINCT makes the row with no branch unique too.
    UNIQUE NULLS NOT DISTINCT (tenant_id, product_id, branch_id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_stock_minimums');
SELECT bms_grant_app('retail_stock_minimums', 'SELECT, INSERT, UPDATE, DELETE');

-- ---------------------------------------------------------------------------------------------
-- 2. The daily notices already prepared: kind is credit_due or daily_summary, run_on the business day.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_notice_runs (
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    kind       text        NOT NULL CHECK (kind IN ('credit_due', 'daily_summary')),
    run_on     date        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, kind, run_on)
);
SELECT bms_apply_tenant_rls('retail_notice_runs');
SELECT bms_grant_app('retail_notice_runs', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_notice_runs');
