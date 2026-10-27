-- V111: the usual size and purpose of a pack, kept on the unit (issue #625, ADR-100; docs/specs/units-packs-and-costing.md
-- section 9, step 5). V110 is selling packs with their own prices (#624, PR #627), which merges first.
--
-- Set up your shop adds the categories, units and packs a kind of shop usually has. A pack there is a unit with its usual size
-- in a smaller unit (Roll = 100 Metre, Box = 50 Piece) and its purpose: buy, sell or both, the same values and words as the
-- purpose of a pack on an item (retail_product_units.purpose, V110), so the app has one idea of what a pack is for. Until now a
-- pack size lived only on an item (retail_product_units, V39), so a shop with no item had nowhere to keep it. These three
-- columns keep it on the unit, as a starting point: adding the pack to an item fills in the size when the item is counted in
-- that smaller unit, and the item's own pack (its factor and purpose) stays the one that counts. Prices are never kept here.
--
-- Expand only, safe on a populated database. ADD COLUMN with no default is a catalogue change in PostgreSQL 16, with no table
-- rewrite, and every existing unit stays a plain unit (all three null). The check is added NOT VALID and validated in the same
-- file: the columns are null on every existing row, so validation cannot fail. Flyway runs the file in one
-- transaction, so the ACCESS EXCLUSIVE lock of the ALTER on retail_units is held to the commit; the table holds tens of rows a
-- business, and lock_timeout is five seconds as in V96 and V109, so the migration fails and is retried rather than queueing
-- every reader of the catalogue behind it. bms_app keeps its SELECT, INSERT and UPDATE on retail_units and its forced RLS.

SET LOCAL lock_timeout = '5s';

ALTER TABLE retail_units ADD COLUMN pack_size numeric(14,3);
ALTER TABLE retail_units ADD COLUMN pack_of_unit_id uuid;
ALTER TABLE retail_units ADD COLUMN pack_purpose text;

-- All three or none: a pack holds at least one of another unit, and is for buying (buy), selling (sell) or both.
ALTER TABLE retail_units ADD CONSTRAINT retail_units_pack_chk CHECK (
    (pack_size IS NULL AND pack_of_unit_id IS NULL AND pack_purpose IS NULL)
    OR (pack_size >= 1 AND pack_of_unit_id IS NOT NULL AND pack_of_unit_id <> id
        AND pack_purpose IN ('buy', 'sell', 'both'))) NOT VALID;
ALTER TABLE retail_units VALIDATE CONSTRAINT retail_units_pack_chk;

-- The foreign key stays NOT VALID: it holds for every row written from now on, and every existing row has a null
-- pack_of_unit_id, so there is nothing to validate. Validating it would read retail_units through its forced row-level security
-- with no tenant bound, which fails for a migrating owner without BYPASSRLS (MigrationOrderIT runs that case).
ALTER TABLE retail_units ADD CONSTRAINT retail_units_pack_of_fk
    FOREIGN KEY (tenant_id, pack_of_unit_id) REFERENCES retail_units (tenant_id, id) NOT VALID;

COMMENT ON COLUMN retail_units.pack_size IS
    'The usual number of pack_of_unit_id in one of this unit, such as 100 for a roll of metres (issue #625, ADR-100). A starting number only: an item''s own pack size counts.';
COMMENT ON COLUMN retail_units.pack_of_unit_id IS
    'The smaller unit the usual pack size counts, such as Metre for a roll (issue #625, ADR-100).';
COMMENT ON COLUMN retail_units.pack_purpose IS
    'What the pack is for: buy, sell or both, as retail_product_units.purpose (issue #625, ADR-100). Never a price.';
