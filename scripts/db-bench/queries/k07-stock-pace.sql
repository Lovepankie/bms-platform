-- StockHealthRepository.pace, lowest cover first over 30 days of sales (issue #149)
-- tenant: tenant
WITH sold AS (SELECT s.branch_id, l.product_id, sum(l.qty) AS sold
                FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
               WHERE s.status = 'completed' AND s.sale_date BETWEEN '2026-09-01' AND '2026-09-30'
               GROUP BY s.branch_id, l.product_id)
SELECT sold.branch_id, p.id, p.code, p.description, u.name AS unit, coalesce(b.qty, 0) AS qty, sold.sold, count(*) OVER () AS total
  FROM sold
  JOIN retail_products p ON p.tenant_id = current_setting('app.tenant_id')::uuid AND p.id = sold.product_id
  JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id
  LEFT JOIN retail_stock_balances b ON b.tenant_id = p.tenant_id AND b.branch_id = sold.branch_id AND b.product_id = sold.product_id
 WHERE p.active AND greatest(coalesce(b.qty, 0), 0) * 30 < 14 * sold.sold
 ORDER BY greatest(coalesce(b.qty, 0), 0) / sold.sold, p.code, sold.branch_id, p.id LIMIT 10
