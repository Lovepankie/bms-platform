-- StockHealthRepository.deadTotals, 90 days (issue #149); deadItems reads the same rows with a limit
-- tenant: tenant
WITH sold AS (SELECT DISTINCT s.branch_id, l.product_id
                FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
               WHERE s.status = 'completed' AND s.sale_date BETWEEN '2026-07-03' AND '2026-09-30')
SELECT b.branch_id, count(*) AS items, sum(round(b.qty * p.sell_minor)) AS at_price, sum(round(b.qty * p.cost_minor)) AS at_cost
  FROM retail_stock_balances b
  JOIN retail_products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id
 WHERE b.qty > 0 AND p.active
   AND NOT EXISTS (SELECT 1 FROM sold WHERE sold.branch_id = b.branch_id AND sold.product_id = b.product_id)
 GROUP BY b.branch_id ORDER BY b.branch_id
