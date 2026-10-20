-- V63: catalogue corrections (issues #351 and #352; ADR-044; docs/specs/retail-ui-notes.md).
--
-- V63 is the first free number after V62 on main (AGENTS.md: Flyway runs with outOfOrder off).
--
-- Expand only. Nothing here changes a row that exists, and the previous release runs unchanged on this schema:
--   1. A product name key, so a second product with the same name is refused (#352): one immutable function, a
--      generated column on retail_products and a plain btree index. The column is added with a short lock_timeout; the
--      catalogue is a small table (thousands of rows), so the rewrite a stored generated column needs is brief. No
--      trigram index is created: a text search operator is not leakproof, so the application role could not use one
--      under row-level security (V40); the near-duplicate check reads one tenant's products and takes a few
--      milliseconds.
--   2. The reason an administrator gave for adding a product whose name exists (retail_products.duplicate_reason).
--   3. retail_purchase_voids (#351): a restock is never edited or deleted (retail_purchases is append-only); a void is
--      one more row that names it, with who, when, the reason and the reversal entries, at most once per restock.
--   4. retail_stock_corrections (#351): the correction of a quantity on the stock list, append-only, with the old
--      and the new quantity, the reason and its movement. The movement is a normal adjustment (the kind already
--      exists) whose source type is retail.stock_correction, so the reports that count shrinkage, which read stock-take
--      adjustments by source type, never see it.
--
-- No table or column of the large movement and balance tables changes, and no check constraint is replaced.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- 1. The name key (#352): the description folded to lower case without accents (bms_fold, V40), with every run of spaces
-- and punctuation collapsed to one space and the ends trimmed. "Double Socket, 13A" and "double  socket 13a" share a key;
-- "2.5mm" keeps its point as a space, so "cable 2 5mm" and "cable 25mm" stay apart. Every name is schema qualified and no
-- SET clause is used, so the planner can inline it (as for V40).
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION bms_name_key(t text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$ SELECT pg_catalog.btrim(pg_catalog.regexp_replace(public.bms_fold(t), '[^[:alnum:]]+', ' ', 'g')) $$;

ALTER TABLE retail_products
    ADD COLUMN name_key text GENERATED ALWAYS AS (public.bms_name_key(description)) STORED;
CREATE INDEX retail_products_name_key ON retail_products (tenant_id, name_key);

-- ---------------------------------------------------------------------------------------------
-- 2. Why a second product with the same name was added anyway (administrators only, #352).
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_products
    ADD COLUMN duplicate_reason varchar(300) CHECK (duplicate_reason IS NULL OR btrim(duplicate_reason) <> '');

-- ---------------------------------------------------------------------------------------------
-- 3. A restock voided (#351). retail_purchases is append-only, so a void is a row of its own, at most one per restock
-- (the unique key is also the guard against two voids at once). The stock reversals are adjustment movements of source type
-- retail.purchase_void and the journal reversals are the ledger's; this row says who, when, why. voided_on is the business
-- date the cash book gives the cash back on.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_purchase_voids (
    id          uuid         NOT NULL PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenants (id),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    purchase_id uuid         NOT NULL,
    voided_on   date         NOT NULL,
    reason      varchar(300) NOT NULL CHECK (btrim(reason) <> ''),
    voided_by   uuid         NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, purchase_id),
    FOREIGN KEY (tenant_id, purchase_id) REFERENCES retail_purchases (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_purchase_voids');
SELECT bms_grant_app('retail_purchase_voids', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_purchase_voids');

-- ---------------------------------------------------------------------------------------------
-- 4. A quantity corrected on the stock list (#351). One row per correction, next to its adjustment movement (source type
-- retail.stock_correction) and its journal entry: the old and the new quantity, why, who and when. Append-only, so the
-- history of a product's stock shows every correction for good.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_stock_corrections (
    id               uuid          NOT NULL PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenants (id),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    branch_id        uuid          NOT NULL,
    product_id       uuid          NOT NULL,
    movement_id      uuid          NOT NULL,
    old_qty          numeric(14,3) NOT NULL,
    new_qty          numeric(14,3) NOT NULL CHECK (new_qty >= 0 AND new_qty <> old_qty),
    reason_code      text          NOT NULL CHECK (reason_code IN ('entry_mistake', 'miscount')),
    note             varchar(300),
    unit_cost_minor  bigint        NOT NULL CHECK (unit_cost_minor >= 0),
    journal_entry_id uuid,
    created_by       uuid          NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, movement_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    FOREIGN KEY (tenant_id, movement_id) REFERENCES retail_stock_movements (tenant_id, id),
    FOREIGN KEY (tenant_id, journal_entry_id) REFERENCES journal_entries (tenant_id, id)
);
CREATE INDEX retail_stock_corrections_product ON retail_stock_corrections (tenant_id, product_id, branch_id, created_at);
SELECT bms_apply_tenant_rls('retail_stock_corrections');
SELECT bms_grant_app('retail_stock_corrections', 'SELECT, INSERT');
SELECT bms_make_append_only('retail_stock_corrections');
