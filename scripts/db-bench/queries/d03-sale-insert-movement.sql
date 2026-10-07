-- JdbcStockLedger.record, movement insert (record a sale)
-- tenant: tenant
INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, business_date, branch_id, product_id, kind, qty,
    unit_cost_minor, source_type, source_id, source_line_id, reverses_movement_id, note, recorded_by, transfer_id)
VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, now(), current_date, :'branch', :'product', 'sale', -1,
    1000, 'retail.sale', gen_random_uuid(), gen_random_uuid(), NULL, NULL, :'user', NULL)
