-- V69: retail items with sizes and colours (variants; issue #384; ADR-051; docs/specs/retail-ui-notes.md, Sizes and colours).
--
-- V69 is the first free number after V68 (the push subscriptions of PR #380, merged). Flyway runs with outOfOrder off
-- (AGENTS.md), so if another migration reaches main first this file is renamed to the next free number at merge.
--
-- A variant is a product. The romper in size 0-3m and print Blue is one row of retail_products with its own code, its own
-- cost and selling price, its own balances and movements, its own sale, restock, transfer, stock-take and return lines, so
-- every path that already works per product works per variant without a change. What is new is the item the variants
-- belong to: a row of retail_product_families with the shared name, category and unit, which the reports roll the variants
-- up to.
--
-- Expand only. Nothing here changes a row that exists:
--   1. retail_product_families, empty: the item with sizes and colours.
--   2. Four nullable columns on retail_products: the family, the size, the colour and the place in the family's list.
--      Every existing product keeps all four null: it is an item without variants, which is its own single default variant
--      (same id, same balances, same history), so nothing is copied and the previous release runs unchanged on this
--      schema. A check keeps the four columns all null or a complete variant.
--   3. A unique index: one variant per size and colour in an item, ignoring case. It is partial, so it holds no entry for
--      a product without a family, and it also serves the lookup of an item's variants.
--
-- retail_products is a catalogue (thousands of rows). Adding nullable columns without a default rewrites nothing; the
-- check and the foreign key read the table once to validate, which is brief, and the five second lock_timeout makes the
-- migration fail fast rather than queue behind a long transaction.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- 1. The item with sizes and colours. name is what every variant's description starts with; base_code is what new
-- variant codes start with. category_id and unit_id are shared by every variant (the service keeps the variants' own
-- columns equal to them, so every existing query that reads a product's category or unit stays right).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_product_families (
    id          uuid         NOT NULL PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenants (id),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz,
    version     integer      NOT NULL DEFAULT 1,
    name        varchar(200) NOT NULL CHECK (name = btrim(name) AND name <> ''),
    base_code   varchar(24)  NOT NULL CHECK (base_code = btrim(base_code) AND base_code <> ''),
    category_id uuid         NOT NULL,
    unit_id     uuid         NOT NULL,
    -- ADR-044 one level up: an administrator who names an item like another item or product of the same unit says why.
    duplicate_reason varchar(300) CHECK (duplicate_reason IS NULL OR btrim(duplicate_reason) <> ''),
    created_by  uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, category_id) REFERENCES retail_categories (tenant_id, id),
    FOREIGN KEY (tenant_id, unit_id) REFERENCES retail_units (tenant_id, id)
);
CREATE INDEX retail_product_families_category ON retail_product_families (tenant_id, category_id);
SELECT bms_apply_tenant_rls('retail_product_families');
SELECT bms_grant_app('retail_product_families', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- 2. A product's place in an item. variant_sort orders the variants as they were chosen (the sizes in the order given,
-- then the colours), not alphabetically: 0-3m, 3-6m, 6-12m.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_products
    ADD COLUMN family_id      uuid,
    ADD COLUMN variant_size   varchar(30),
    ADD COLUMN variant_colour varchar(30),
    ADD COLUMN variant_sort   integer;

ALTER TABLE retail_products
    ADD CONSTRAINT retail_products_family_fk
        FOREIGN KEY (tenant_id, family_id) REFERENCES retail_product_families (tenant_id, id),
    ADD CONSTRAINT retail_products_variant_chk CHECK (
        (family_id IS NULL AND variant_size IS NULL AND variant_colour IS NULL AND variant_sort IS NULL)
        OR (family_id IS NOT NULL AND variant_sort IS NOT NULL AND variant_sort >= 0
            AND (variant_size IS NOT NULL OR variant_colour IS NOT NULL))),
    ADD CONSTRAINT retail_products_variant_size_chk
        CHECK (variant_size IS NULL OR (variant_size = btrim(variant_size) AND variant_size <> '')),
    ADD CONSTRAINT retail_products_variant_colour_chk
        CHECK (variant_colour IS NULL OR (variant_colour = btrim(variant_colour) AND variant_colour <> ''));

-- ---------------------------------------------------------------------------------------------
-- 3. One variant per size and colour in an item (case ignored); also the index of an item's variants.
-- ---------------------------------------------------------------------------------------------

CREATE UNIQUE INDEX retail_products_variant
    ON retail_products (tenant_id, family_id, lower(coalesce(variant_size, '')), lower(coalesce(variant_colour, '')))
    WHERE family_id IS NOT NULL;
