-- ReportsRepository.daily, a year
-- tenant: tenant
SELECT branch_id, day, sum(sales) AS sales, sum(cost) AS cost, sum(usage) AS usage FROM (
    SELECT s.branch_id, s.sale_date AS day, l.line_total_minor AS sales,
           l.line_cost_minor AS cost, 0 AS usage
      FROM retail_sale_lines l JOIN retail_sales s ON s.id = l.sale_id
     WHERE s.status = 'completed' AND s.sale_date BETWEEN '2025-10-01' AND '2026-09-30'
    UNION ALL
    SELECT branch_id, occurred_on, 0, 0, cost_total_minor FROM retail_usage_reports
     WHERE occurred_on BETWEEN '2025-10-01' AND '2026-09-30'
) f GROUP BY branch_id, day ORDER BY day, branch_id
