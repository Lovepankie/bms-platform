-- StockRepository.stock, negative only
-- tenant: tenant
SELECT p.id, p.code, p.description, u.name AS unit, p.sell_minor, p.cost_minor, coalesce(b.qty, 0) AS qty
  FROM retail_products p
  JOIN retail_units u ON u.id = p.unit_id
  LEFT JOIN retail_stock_balances b ON b.product_id = p.id AND b.branch_id = :'branch'
 WHERE (p.active OR coalesce(b.qty, 0) <> 0) AND coalesce(b.qty, 0) < 0 ORDER BY p.code, p.id LIMIT 101
