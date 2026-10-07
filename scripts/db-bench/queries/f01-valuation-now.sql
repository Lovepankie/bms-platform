-- ReportsRepository.holdings, valuation now
-- tenant: tenant
SELECT q.branch_id, q.product_id, p.code, p.description, u.name AS unit, q.qty, p.cost_minor, p.sell_minor
  FROM (SELECT branch_id, product_id, qty FROM retail_stock_balances) q
  JOIN retail_products p ON p.id = q.product_id JOIN retail_units u ON u.id = p.unit_id
 WHERE q.qty <> 0 ORDER BY q.branch_id, p.code, p.id
