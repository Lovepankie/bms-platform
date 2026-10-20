-- V65 (the merge order is #349 V64, this one V65, #359 V66, #361 V67; Flyway runs with outOfOrder off): a branch that
-- holds stock but does not sell, called a Store (issue #366, ADR-048).
--
-- Expand only, safe on a populated database. One defaulted column and one check on `branches`:
--
--   sells_goods  boolean, default true. A branch with sells_goods = false is a Store: it holds stock, receives purchases (restocks,
--                goods receipts) and takes transfers in and out, but a sale, a payment taken at its till and a refund paid out of
--                it are refused with 422 `branch_does_not_sell`. Every existing branch keeps the default, so nothing changes
--                until an administrator switches it.
--   branches_store_stocks  a Store must keep goods: sells_goods = false needs stocks_goods = true. What remains is a shop (sells
--                and stocks), a Store (stocks, does not sell) and a head office that only keeps accounts (ADR-041).
--
-- No existing row is rewritten (every row is sells_goods = true, so the check holds for all of them) and no index is added: the
-- table holds a handful of rows per tenant.
--
-- lock_timeout: ALTER TABLE takes an ACCESS EXCLUSIVE lock, and every sale reads a branch row. If the lock cannot be had within
-- five seconds the migration fails and is retried, and the shop is never kept waiting behind it (as V61).

SET LOCAL lock_timeout = '5s';

ALTER TABLE branches
    ADD COLUMN sells_goods boolean NOT NULL DEFAULT true,
    ADD CONSTRAINT branches_store_stocks CHECK (sells_goods OR stocks_goods);
