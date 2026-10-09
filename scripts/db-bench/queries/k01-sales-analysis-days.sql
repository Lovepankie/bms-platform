-- SalesAnalysisRepository.days, a month (issue #149); total() and grouped(BRANCH) read the same rows
-- tenant: tenant
SELECT s.sale_date AS day, sum(l.line_total_minor) AS sales, sum(l.line_cost_minor) AS cost,
       count(DISTINCT s.id) AS sale_count
  FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
 WHERE s.status = 'completed' AND s.sale_date BETWEEN '2026-09-01' AND '2026-09-30'
 GROUP BY s.sale_date ORDER BY s.sale_date
