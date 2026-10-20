-- V66: the Controls page (issue #357, group 1; ADR-047). The merge order of the open pull requests fixes the number (V64, V65,
-- then this one), since Flyway runs with outOfOrder off.
--
-- `retail.controls.read`: read the Controls page, which counts voids, returns, changed prices, discounts, sales below cost
-- and sales cancelled at once, by shop, by named person, by day, by reason and by approver. It names staff, so it is the
-- tenant administrator's alone by default (system roles are global rows, so every existing tenant has it from this
-- migration; a business gives it to a custom role such as an owner's or an auditor's). It is a read, not money moving, and
-- it is scoped by branch like every retail permission.
--
-- No index is added: the voids of a window are found by `retail_sales_voided (tenant_id, branch_id, voided_at) WHERE
-- voided_at IS NOT NULL` (V34), and every other metric is read by the sale date, which `retail_sales_by_date` serves.

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.controls.read', 'retail', 'Controls: see voids, returns, changed prices and corrections by shop, person, day and reason', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.controls.read')
ON CONFLICT DO NOTHING;
