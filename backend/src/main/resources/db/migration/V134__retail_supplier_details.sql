-- V134: the useful details of a supplier and the index behind its deliveries (issue #820, ADR-125).
--
-- V134 is the highest number on main plus one (AGENTS.md: Flyway runs with outOfOrder off); the merger renumbers at
-- merge time if the order changes.
--
-- Expand only (chapter 6 section 6.9): eight nullable columns on retail_suppliers with no default, so adding them
-- rewrites nothing and every existing supplier keeps what it has, every new field empty; and one new index on
-- retail_purchases. Nothing is dropped and no check changes. The date added is the existing created_at; the last
-- delivery date is never stored, it is read from the supplier's restocks that are not voided.
--
-- The lengths are the ones the API checks first (PurchasingApi), so a request too long is a 422 in words before it is
-- ever a database error. The bank account number is shown only to those who may edit suppliers (retail.catalogue.manage);
-- the column itself is read like the rest of the row, under the table's row-level security.

ALTER TABLE retail_suppliers
    ADD COLUMN email         varchar(254),
    ADD COLUMN address       varchar(300),
    ADD COLUMN country       varchar(100),
    ADD COLUMN website       varchar(300),
    ADD COLUMN payment_terms varchar(100),
    ADD COLUMN bank_account  varchar(60),
    ADD COLUMN supplier_type varchar(60),
    ADD COLUMN notes         varchar(1000);

-- A supplier's deliveries, newest first, and its last delivery date: the restocks naming it, by day.
CREATE INDEX retail_purchases_supplier ON retail_purchases (tenant_id, supplier_id, purchased_on DESC, created_at DESC)
    WHERE supplier_id IS NOT NULL;
