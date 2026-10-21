-- V71: a photo on an item, and optionally on each of its sizes and colours (issue #384, slice 2; ADR-053).
--
-- V71 is the first free number above every open branch: V69 is the item variants (PR #388, which this branch builds on) and V70
-- the customer orders (PR #389). Flyway runs with outOfOrder off (AGENTS.md), so this file is renamed at merge if the numbers
-- below it land in another order.
--
-- Expand only. The photo itself is a row of documents (V4) stored through core.documents in the private bucket under
-- tenants/<tenant_id>/upload/, re-encoded so no camera metadata survives, and served only as a five minute signed URL after
-- the retail module says the caller may read the catalogue. These two nullable columns point at it; every existing item has none.

SET LOCAL lock_timeout = '5s';

ALTER TABLE retail_products ADD COLUMN photo_document_id uuid;
ALTER TABLE retail_products
    ADD CONSTRAINT retail_products_photo_fk
        FOREIGN KEY (tenant_id, photo_document_id) REFERENCES documents (tenant_id, id);

ALTER TABLE retail_product_families ADD COLUMN photo_document_id uuid;
ALTER TABLE retail_product_families
    ADD CONSTRAINT retail_product_families_photo_fk
        FOREIGN KEY (tenant_id, photo_document_id) REFERENCES documents (tenant_id, id);
