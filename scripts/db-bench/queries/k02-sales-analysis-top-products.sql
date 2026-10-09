-- SalesAnalysisRepository.grouped(PRODUCT), top 10 by sales over a year (issue #149)
-- tenant: tenant
SELECT p.id AS k, p.code, p.description AS label, u.name AS unit, sum(l.qty) AS qty,
       sum(l.line_total_minor) AS sales, sum(l.line_cost_minor) AS cost, count(DISTINCT s.id) AS sale_count
  FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
  JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
  JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id
 WHERE s.status = 'completed' AND s.sale_date BETWEEN '2025-10-01' AND '2026-09-30'
 GROUP BY p.id, p.code, p.description, u.name
 ORDER BY sum(l.line_total_minor) DESC, sum(l.qty) DESC, 1 LIMIT 10
