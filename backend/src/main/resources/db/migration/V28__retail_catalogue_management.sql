-- ---------------------------------------------------------------------------------------------
-- Retail catalogue management (issue #146). Categories, units, suppliers and credit buyers could
-- only be added; a shop now renames them, and deactivates a category or unit it no longer uses
-- (nothing is deleted: products and history keep pointing at the row). Expand only: new columns
-- with defaults and one extra privilege per table. Suppliers already carry active (V12).
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_categories ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE retail_categories ADD COLUMN updated_at timestamptz;
SELECT bms_grant_app('retail_categories', 'UPDATE');

ALTER TABLE retail_units ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE retail_units ADD COLUMN updated_at timestamptz;
SELECT bms_grant_app('retail_units', 'UPDATE');

ALTER TABLE retail_suppliers ADD COLUMN updated_at timestamptz;
SELECT bms_grant_app('retail_suppliers', 'UPDATE');

ALTER TABLE retail_customers ADD COLUMN updated_at timestamptz;
SELECT bms_grant_app('retail_customers', 'UPDATE');
