-- StockRepository.lines, a stock-take
-- tenant: tenant
SELECT l.product_id, p.code, p.description, l.counted_qty, l.expected_qty,
       l.committed_variance_qty, l.unit_cost_minor
  FROM retail_stocktake_lines l JOIN retail_products p ON p.id = l.product_id
 WHERE l.stocktake_id = :'stocktake' ORDER BY p.code, l.product_id
