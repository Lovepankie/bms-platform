-- JdbcStockLedger.bySource (void)
-- tenant: tenant
SELECT id, branch_id, product_id, kind, qty, unit_cost_minor, source_line_id
  FROM retail_stock_movements WHERE source_type = 'retail.sale' AND source_id = :'sale'
 ORDER BY created_at, id
