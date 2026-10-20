-- V61 (the first free number after V60 on main; Flyway runs with outOfOrder off): closing a branch and marking a branch as not
-- stocking (issue #329).
--
-- Expand only, safe on a populated database. Three nullable-or-defaulted columns on `branches` and nothing else:
--
--   stocks_goods  boolean, default true. A branch that does not stock goods (a head office that only keeps accounts) is not
--                 offered for sales, restocks, purchase orders, goods receipts, transfers into it, stock-takes or the
--                 restock of a return; the retail module refuses them with 422 `branch_not_stocking`. Goods can still
--                 leave it (it may be a transfer source and report usage and damage) and voids stay allowed (ADR-041).
--                 Every existing branch keeps the default, so nothing changes until an administrator switches it.
--   closed_at     when the branch was closed, for the Branches screen. The status stays `inactive`: closing a branch is the
--                 deactivation FR-BR-01 already defines, and its history, stock and sales are never touched (ADR-041).
--   closed_reason why it was closed, kept only when the administrator had to confirm over stock or transfers in flight.
--                 The audit row carries the same words.
--
-- No existing row is rewritten, no check is replaced, no index is added: the table holds a handful of rows per tenant.
--
-- lock_timeout: ALTER TABLE takes an ACCESS EXCLUSIVE lock, and every sale reads a branch row. If the lock cannot be had within
-- five seconds the migration fails and is retried, and the shop is never kept waiting behind it (as V26, V48 and V49).

SET LOCAL lock_timeout = '5s';

ALTER TABLE branches
    ADD COLUMN stocks_goods boolean     NOT NULL DEFAULT true,
    ADD COLUMN closed_at    timestamptz,
    ADD COLUMN closed_reason text;
