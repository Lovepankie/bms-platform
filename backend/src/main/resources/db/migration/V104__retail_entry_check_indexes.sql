-- V104: three indexes for the entry warnings (issue #353, ADR-093; docs/sdd/06-database-design.md section 6.12).
--
-- Indexes only: no table, column, constraint, policy, grant or row changes, so the previous release runs unchanged on this
-- schema. GET /retail/products/{id}/entry-norms reads an item's last 20 purchase lines and last 20 sale lines, newest first,
-- and its last 20 move lines, to give the median quantity, the usual unit and the last selling price that Buy stock, Sell,
-- Move stock and Count stock warn against. No index on the sale or purchase lines led on the product (both are keyed by their
-- sale or purchase), so each read would scan the tenant's whole line history, and retail_transfer_lines_product
-- (tenant_id, product_id) has no order, so a move read would take every move the item ever had and sort them. With
-- (tenant_id, product_id, created_at DESC, id DESC), the order the reads ask for, each read walks at most 20 entries of one
-- item, newest first, with no sort.
--
-- Locks. Flyway runs this file in one transaction. lock_timeout bounds only the wait for the SHARE lock on each table
-- (behind a transaction writing lines at that moment); once a build starts, writers (a sale, a purchase, a move) wait for it
-- to finish. Staging held 22,110 sale lines on 2026-10-25 (V101), so each build takes well under a second there. A plain
-- CREATE INDEX, not CONCURRENTLY, which cannot run inside Flyway's transaction (ADR-028); a database of millions of lines
-- on a slow host needs a deploy window, as V101 says.
--
-- Flyway runs with outOfOrder off: V102 (pull request #585) and V103 (pull request #589) are merged, so this is V104.

SET LOCAL lock_timeout = '5s';

CREATE INDEX retail_sale_lines_product_recent ON retail_sale_lines (tenant_id, product_id, created_at DESC, id DESC);
CREATE INDEX retail_purchase_lines_product_recent ON retail_purchase_lines (tenant_id, product_id, created_at DESC, id DESC);
CREATE INDEX retail_transfer_lines_product_recent ON retail_transfer_lines (tenant_id, product_id, created_at DESC, id DESC);
