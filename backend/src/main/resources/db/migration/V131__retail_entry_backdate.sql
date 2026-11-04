-- V131: the app sets the date of every entry (issue #811, ADR-120). The number is the next one after V130 (the SMS delivery
-- reports, #806) on main; if another migration lands on main first, this file is renamed to the next free number at merge
-- (Flyway runs with outOfOrder off).
--
-- `retail.entry.backdate`: date an expense, a savings record, a banking, a withdrawal, an advance or its repayment, a
-- purchase (Buy stock) or a stock move earlier than today, with a reason, audited as `retail.entry.backdated`. Without it the
-- server dates the entry today on the business clock and refuses any earlier date with 403 `date_not_today`. The tenant
-- administrator holds it, as every retail permission; no other seeded role does. It is scoped by branch like every retail
-- permission, and it moves no money by itself (system roles are global rows, so every tenant has it from this migration).
--
-- PermissionMatrixIT compares this row with the matrix in chapter 8 section 8.3.2.

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.entry.backdate', 'retail', 'Entries: date an entry earlier than today, with a reason (the owner)', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.entry.backdate')
ON CONFLICT DO NOTHING;
