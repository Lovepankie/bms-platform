-- V74: retail roles, slice 2 (issue #399, ADR-055 decision 5): sellers see only their own figures. The number is the next one
-- after V73 (slice 1, #403); if another migration lands on main first, this file is renamed to the next free number at merge
-- (Flyway runs with outOfOrder off).
--
-- `retail.sale.all_sellers`: see the sales of every seller at the branch, and the reports that add them up (the owner
-- dashboard and the sales analysis). A holder of `retail.sale.read` without it sees only the sales they made: in the sales
-- list, the grouped summary, one sale and its payments. The tenant administrator holds it, as every retail permission; no
-- other seeded role does, so the Sales role sees its own figures only. It is a read, not money moving, and it is scoped by
-- branch like every retail permission (system roles are global rows, so every tenant has it from this migration).
--
-- PermissionMatrixIT compares this row with the matrix in chapter 8 section 8.3.2.

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.sale.all_sellers', 'retail', 'Sales: see the sales and totals of every seller, not only one''s own', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.sale.all_sellers')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- The till (the owner's decision of 21 October 2026, ADR-055): the cash book is hidden from ordinary sellers.
-- `retail.till.close` ("Can close the till and do the banking") is given per person through the role `retail_till`, at the
-- branches the person works in, together with the cash book permissions the Sales role held since V34 (read the cash book,
-- record the day's savings, record cash banked, record an expense). The Sales role loses those four: a seller sees no daily
-- cash summary, takings, expected banking or savings suggestion. Administrators keep the whole cash book and get the new
-- permission everywhere. No non-administrator gets the till by default; the owner ticks it on the Users screen.
--
-- The Supervisor (the owner's decision of 21 October 2026): at the branches it is given for it sees every seller's sales and
-- totals, the dashboard and the sales analysis (`retail.sale.all_sellers` scoped by branch), and stock, with no profit, cost,
-- margin, stock value, Users or Controls. It is combined with the Sales role or the till by giving those roles too.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.till.close', 'retail', 'Cash book: close the till and do the banking (the daily cash summary, savings and banking)', true);

INSERT INTO roles (key, name, kind, mfa_required) VALUES
    ('retail_till', 'Till and banking', 'staff', false),
    ('retail_supervisor', 'Supervisor', 'staff', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.till.close'),
    ('retail_till', 'retail.till.close'),
    ('retail_till', 'retail.cashbook.read'),
    ('retail_till', 'retail.savings.record'),
    ('retail_till', 'retail.banking.record'),
    ('retail_till', 'retail.expense.record'),
    ('retail_supervisor', 'retail.sale.read'),
    ('retail_supervisor', 'retail.sale.all_sellers'),
    ('retail_supervisor', 'retail.stock.read')
ON CONFLICT DO NOTHING;

DELETE FROM role_permissions
 WHERE role_key = 'retail_sales'
   AND permission_key IN ('retail.cashbook.read', 'retail.savings.record', 'retail.banking.record', 'retail.expense.record');
