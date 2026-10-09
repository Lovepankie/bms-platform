-- SalesAnalysisRepository.noSales, a month (issue #149)
-- tenant: tenant
WITH sold AS (SELECT DISTINCT l.product_id
                FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
               WHERE s.status = 'completed' AND s.sale_date BETWEEN '2026-09-01' AND '2026-09-30')
SELECT p.id, p.code, p.description, u.name AS unit, coalesce(st.qty, 0) AS qty, p.sell_minor, count(*) OVER () AS total
  FROM retail_products p
  JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id
  LEFT JOIN (SELECT product_id, sum(qty) AS qty FROM retail_stock_balances GROUP BY product_id) st ON st.product_id = p.id
 WHERE p.active AND NOT EXISTS (SELECT 1 FROM sold WHERE sold.product_id = p.id)
 ORDER BY p.code, p.id LIMIT 10
