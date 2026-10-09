-- DashboardRepository.sales: thirty days by branch, day and way of paying (issue #149)
-- tenant: tenant
SELECT s.branch_id, s.sale_date AS day, (s.payment_method = 'credit') AS credit, sum(l.line_total_minor) AS sales,
       sum(l.line_cost_minor) AS cost, count(DISTINCT s.id) AS sale_count
  FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
 WHERE s.status = 'completed' AND s.sale_date BETWEEN '2026-09-01' AND '2026-09-30'
 GROUP BY s.branch_id, s.sale_date, (s.payment_method = 'credit')
