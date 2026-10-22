-- V90: barcodes on retail products (ADR-070, issue #345; FR-RET-68).
--
-- Expand only, safe on a populated database: one new table, nothing altered, dropped or renamed.
-- A product carries any number of barcodes, each for the product's base unit or for one of its
-- pack sizes (V39). The application validates the format and its check digit (EAN-13, EAN-8,
-- UPC-A, Code 128) before a row is written; the checks below keep the shapes honest. A slash or a
-- backslash is refused, and so is a code made only of dots, since the lookup carries the code in
-- its path.
--
-- A code is unique per tenant: lookup_key folds a code stored as UPC-A (format upca) into the
-- thirteen digit EAN-13 form a scanner may report for it, so the same printed code can never sit
-- on two rows. Twelve digits stored as Code 128 are a different code and keep their own key.
-- "Unique among active products" is the catalogue's rule on top of that index: a code held by a
-- product that is switched off is released (its row deleted, audited) when an active product
-- takes it, so the unique index holds at every moment.
--
-- generated marks a Code 128 code the system made for a product that had none, for its labels;
-- its number comes from tenant_sequences ('retail_barcode'). Flyway runs with outOfOrder off, so
-- the number is assigned at merge time above the highest one on main (V85 to V89, V91 and V93 are
-- claimed by open pull requests).

CREATE TABLE retail_product_barcodes (
    id          uuid        NOT NULL PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenants (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  uuid,
    product_id  uuid        NOT NULL,
    unit_id     uuid,
    code        text        NOT NULL CHECK (code ~ '^[!-~]{1,48}$' AND code !~ '[/\\]' AND code !~ '^[.]+$'),
    format      text        NOT NULL CHECK (format IN ('ean13', 'ean8', 'upca', 'code128')),
    generated   boolean     NOT NULL DEFAULT false,
    lookup_key  text        GENERATED ALWAYS AS (
                    CASE WHEN format = 'upca' THEN '0' || code ELSE code END) STORED,
    UNIQUE (tenant_id, id),
    CHECK ((format = 'ean13' AND code ~ '^[0-9]{13}$')
        OR (format = 'ean8' AND code ~ '^[0-9]{8}$')
        OR (format = 'upca' AND code ~ '^[0-9]{12}$')
        OR format = 'code128'),
    CHECK (NOT generated OR format = 'code128'),
    FOREIGN KEY (tenant_id, product_id) REFERENCES retail_products (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id, unit_id) REFERENCES retail_product_units (tenant_id, product_id, unit_id)
);
CREATE UNIQUE INDEX retail_product_barcodes_key ON retail_product_barcodes (tenant_id, lookup_key);
CREATE INDEX retail_product_barcodes_product ON retail_product_barcodes (tenant_id, product_id);
SELECT bms_apply_tenant_rls('retail_product_barcodes');
SELECT bms_grant_app('retail_product_barcodes', 'SELECT, INSERT, DELETE');
