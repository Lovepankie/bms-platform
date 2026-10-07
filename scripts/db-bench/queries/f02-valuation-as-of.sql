-- ReportsRepository.holdings, valuation as of a date
-- tenant: tenant
SELECT q.branch_id, q.product_id, p.code, p.description, u.name AS unit, q.qty, p.cost_minor, p.sell_minor
  FROM (SELECT branch_id, product_id, sum(qty) AS qty FROM retail_stock_movements
         WHERE business_date <= '2026-06-30'
         GROUP BY branch_id, product_id) q
  JOIN retail_products p ON p.id = q.product_id JOIN retail_units u ON u.id = p.unit_id
 WHERE q.qty <> 0 ORDER BY q.branch_id, p.code, p.id
