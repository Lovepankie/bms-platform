-- SalesAnalysisRepository.slowMovers, 30 days (issue #149)
-- tenant: tenant
WITH sold AS (SELECT DISTINCT l.product_id
                FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
               WHERE s.status = 'completed' AND s.sale_date BETWEEN '2026-09-01' AND '2026-09-30')
SELECT p.id, p.code, p.description, u.name AS unit, sum(b.qty) AS qty, p.sell_minor, count(*) OVER () AS total
  FROM retail_stock_balances b
  JOIN retail_products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id
  JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id
 WHERE b.qty > 0 AND p.active
   AND NOT EXISTS (SELECT 1 FROM sold WHERE sold.product_id = p.id)
 GROUP BY p.id, p.code, p.description, u.name, p.sell_minor
 ORDER BY sum(b.qty) * p.sell_minor DESC, p.code, p.id LIMIT 10
