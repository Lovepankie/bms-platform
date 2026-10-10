-- The statements SalesService.record issues for one three-line cash sale, in order, as bms_app
-- (pgbench script, run by `run.sh write`). Product ids are looked up by code first because pgbench
-- cannot hold a list of ids; that lookup is not part of the application's path.
\set r1 random(1, 3000)
\set r2 random(1, 3000)
\set r3 random(1, 3000)
BEGIN;
SELECT set_config('app.tenant_id', ':tenant', true) AS bound \gset
SELECT id AS p1 FROM retail_products WHERE lower(code) = lower('P' || lpad(':r1', 5, '0')) \gset
SELECT id AS p2 FROM retail_products WHERE lower(code) = lower('P' || lpad(':r2', 5, '0')) \gset
SELECT id AS p3 FROM retail_products WHERE lower(code) = lower('P' || lpad(':r3', 5, '0')) \gset
SELECT gen_random_uuid() AS sale, gen_random_uuid() AS l1, gen_random_uuid() AS l2, gen_random_uuid() AS l3,
       gen_random_uuid() AS idem \gset
-- Idempotency claim (JdbcIdempotency (core.operations)).
SET LOCAL lock_timeout = '5s';
INSERT INTO idempotency_keys (tenant_id, principal_id, key, method, path, request_hash, status)
VALUES (current_setting('app.tenant_id')::uuid, ':user', ':idem', 'POST', '/api/v1/retail/sales', repeat('c', 64), 'in_progress')
ON CONFLICT (tenant_id, principal_id, key) DO NOTHING;
-- Product snapshots (CatalogueRepository.find).
SELECT p.id, p.code, p.description, p.sell_minor, p.cost_minor, p.currency, p.active
  FROM retail_products p JOIN retail_categories c ON c.id = p.category_id JOIN retail_units u ON u.id = p.unit_id
 WHERE p.id = ':p1';
SELECT p.id, p.code, p.description, p.sell_minor, p.cost_minor, p.currency, p.active
  FROM retail_products p JOIN retail_categories c ON c.id = p.category_id JOIN retail_units u ON u.id = p.unit_id
 WHERE p.id = ':p2';
SELECT p.id, p.code, p.description, p.sell_minor, p.cost_minor, p.currency, p.active
  FROM retail_products p JOIN retail_categories c ON c.id = p.category_id JOIN retail_units u ON u.id = p.unit_id
 WHERE p.id = ':p3';
-- Sale number, header and lines.
INSERT INTO tenant_sequences (tenant_id, sequence_key) VALUES (current_setting('app.tenant_id')::uuid, 'retail_sale_no')
ON CONFLICT DO NOTHING;
UPDATE tenant_sequences SET next_value = next_value + 1 WHERE sequence_key = 'retail_sale_no' RETURNING next_value - 1 AS no \gset
INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, customer_id, buyer_name,
    buyer_contact, due_date, currency, total_minor, cost_total_minor, paid_minor, status, created_by)
VALUES (':sale', current_setting('app.tenant_id')::uuid, ':branch', 'RS' || lpad(':no', 8, '0'), current_date, 'cash',
    NULL, NULL, NULL, NULL, 'UGX', 30000, 24000, 30000, 'completed', ':user');
INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor, unit_cost_minor,
    line_total_minor, line_cost_minor)
VALUES (':l1', current_setting('app.tenant_id')::uuid, ':sale', 1, ':p1', 1, 10000, 8000, 10000, 8000);
INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor, unit_cost_minor,
    line_total_minor, line_cost_minor)
VALUES (':l2', current_setting('app.tenant_id')::uuid, ':sale', 2, ':p2', 1, 10000, 8000, 10000, 8000);
INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor, unit_cost_minor,
    line_total_minor, line_cost_minor)
VALUES (':l3', current_setting('app.tenant_id')::uuid, ':sale', 3, ':p3', 1, 10000, 8000, 10000, 8000);
-- Stock (JdbcStockLedger.record): lock each balance, append a movement, move the balance.
INSERT INTO retail_stock_balances (tenant_id, branch_id, product_id) VALUES (current_setting('app.tenant_id')::uuid, ':branch', ':p1')
ON CONFLICT (tenant_id, branch_id, product_id) DO NOTHING;
SELECT qty FROM retail_stock_balances WHERE branch_id = ':branch' AND product_id = ':p1' FOR UPDATE;
INSERT INTO retail_stock_balances (tenant_id, branch_id, product_id) VALUES (current_setting('app.tenant_id')::uuid, ':branch', ':p2')
ON CONFLICT (tenant_id, branch_id, product_id) DO NOTHING;
SELECT qty FROM retail_stock_balances WHERE branch_id = ':branch' AND product_id = ':p2' FOR UPDATE;
INSERT INTO retail_stock_balances (tenant_id, branch_id, product_id) VALUES (current_setting('app.tenant_id')::uuid, ':branch', ':p3')
ON CONFLICT (tenant_id, branch_id, product_id) DO NOTHING;
SELECT qty FROM retail_stock_balances WHERE branch_id = ':branch' AND product_id = ':p3' FOR UPDATE;
INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, business_date, branch_id, product_id, kind, qty,
    unit_cost_minor, source_type, source_id, source_line_id, recorded_by)
VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, now(), current_date, ':branch', ':p1', 'sale', -1, 8000,
    'retail.sale', ':sale', ':l1', ':user');
UPDATE retail_stock_balances SET qty = qty + -1, updated_at = now() WHERE branch_id = ':branch' AND product_id = ':p1';
INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, business_date, branch_id, product_id, kind, qty,
    unit_cost_minor, source_type, source_id, source_line_id, recorded_by)
VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, now(), current_date, ':branch', ':p2', 'sale', -1, 8000,
    'retail.sale', ':sale', ':l2', ':user');
UPDATE retail_stock_balances SET qty = qty + -1, updated_at = now() WHERE branch_id = ':branch' AND product_id = ':p2';
INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, business_date, branch_id, product_id, kind, qty,
    unit_cost_minor, source_type, source_id, source_line_id, recorded_by)
VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, now(), current_date, ':branch', ':p3', 'sale', -1, 8000,
    'retail.sale', ':sale', ':l3', ':user');
UPDATE retail_stock_balances SET qty = qty + -1, updated_at = now() WHERE branch_id = ':branch' AND product_id = ':p3';
-- Two journal entries (JdbcLedgerPosting.post), each: branch, accounts, period, number, entry, lines.
SELECT id FROM branches WHERE id = ':branch' AND status = 'active';
SELECT id AS cash FROM gl_accounts WHERE system_key = 'cash_on_hand' \gset
SELECT id AS revenue FROM gl_accounts WHERE system_key = 'sales_revenue' \gset
SELECT id AS cogs FROM gl_accounts WHERE system_key = 'cost_of_goods_sold' \gset
SELECT id AS inventory FROM gl_accounts WHERE system_key = 'inventory' \gset
SELECT currency, is_postable, is_active FROM gl_accounts WHERE id = ':cash';
SELECT currency, is_postable, is_active FROM gl_accounts WHERE id = ':revenue';
INSERT INTO gl_periods (id, tenant_id, year, month, status)
VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, 2026, 10, 'open')
ON CONFLICT (tenant_id, year, month) DO NOTHING;
SELECT id AS period, status FROM gl_periods WHERE year = 2026 AND month = 10 \gset
UPDATE tenant_sequences SET next_value = next_value + 1 WHERE sequence_key = 'journal_no' RETURNING next_value - 1 AS je1 \gset
SELECT gen_random_uuid() AS e1, gen_random_uuid() AS e2 \gset
INSERT INTO journal_entries (id, tenant_id, branch_id, entry_no, entry_date, period_id, reference, memo, source_module,
    source_type, source_id, idempotency_key, created_by)
VALUES (':e1', current_setting('app.tenant_id')::uuid, ':branch', 'JE' || lpad(':je1', 8, '0'), current_date, ':period',
    'RS' || lpad(':no', 8, '0'), 'Sale', 'retail', 'retail.sale', ':sale', 'retail.sale:' || ':sale', ':user');
INSERT INTO journal_lines (id, tenant_id, entry_id, line_no, account_id, debit, credit, currency)
VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, ':e1', 1, ':cash', 30000, 0, 'UGX');
INSERT INTO journal_lines (id, tenant_id, entry_id, line_no, account_id, debit, credit, currency)
VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, ':e1', 2, ':revenue', 0, 30000, 'UGX');
SELECT id FROM branches WHERE id = ':branch' AND status = 'active';
SELECT currency, is_postable, is_active FROM gl_accounts WHERE id = ':cogs';
SELECT currency, is_postable, is_active FROM gl_accounts WHERE id = ':inventory';
INSERT INTO gl_periods (id, tenant_id, year, month, status)
VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, 2026, 10, 'open')
ON CONFLICT (tenant_id, year, month) DO NOTHING;
SELECT id, status FROM gl_periods WHERE year = 2026 AND month = 10;
UPDATE tenant_sequences SET next_value = next_value + 1 WHERE sequence_key = 'journal_no' RETURNING next_value - 1 AS je2 \gset
INSERT INTO journal_entries (id, tenant_id, branch_id, entry_no, entry_date, period_id, reference, memo, source_module,
    source_type, source_id, idempotency_key, created_by)
VALUES (':e2', current_setting('app.tenant_id')::uuid, ':branch', 'JE' || lpad(':je2', 8, '0'), current_date, ':period',
    'RS' || lpad(':no', 8, '0'), 'Cost of sale', 'retail', 'retail.sale', ':sale', 'retail.sale_cost:' || ':sale', ':user');
INSERT INTO journal_lines (id, tenant_id, entry_id, line_no, account_id, debit, credit, currency)
VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, ':e2', 1, ':cogs', 24000, 0, 'UGX');
INSERT INTO journal_lines (id, tenant_id, entry_id, line_no, account_id, debit, credit, currency)
VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, ':e2', 2, ':inventory', 0, 24000, 'UGX');
UPDATE retail_sales SET sale_entry_id = ':e1', cost_entry_id = ':e2' WHERE id = ':sale';
-- Audit row, the read-back and the idempotency completion.
INSERT INTO audit_log (id, tenant_id, actor_user_id, actor_kind, branch_id, action, entity_type, entity_id, data)
VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, ':user', 'staff', ':branch', 'retail.sale.created',
    'retail.sale', ':sale', '{"after": {"lines": 3}}');
SELECT id, sale_no, status, total_minor FROM retail_sales WHERE id = ':sale';
SELECT l.id, l.line_no, p.code FROM retail_sale_lines l JOIN retail_products p ON p.id = l.product_id
 WHERE l.sale_id = ':sale' ORDER BY l.line_no;
UPDATE idempotency_keys SET status = 'completed', response_status = 201, response_body = '{"id": "x"}'::jsonb
 WHERE principal_id = ':user' AND key = ':idem';
COMMIT;
