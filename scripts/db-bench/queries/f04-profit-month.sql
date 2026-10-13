-- ReportsRepository.daily, a month (with customer returns, issue #201)
-- tenant: tenant
SELECT branch_id, day, sum(sales) AS sales, sum(cost) AS cost, sum(usage) AS usage FROM (
    SELECT s.branch_id, s.sale_date AS day, l.line_total_minor AS sales,
           l.line_cost_minor AS cost, 0 AS usage
      FROM retail_sale_lines l JOIN retail_sales s ON s.id = l.sale_id
     WHERE s.status = 'completed' AND s.sale_date BETWEEN '2026-09-01' AND '2026-09-30'
    UNION ALL
    SELECT branch_id, occurred_on, 0, 0, cost_total_minor FROM retail_usage_reports
     WHERE occurred_on BETWEEN '2026-09-01' AND '2026-09-30'
    UNION ALL
    SELECT r.branch_id, r.returned_on, -r.total_minor, -r.cost_minor,
           coalesce((SELECT sum(l.line_cost_minor) FROM retail_sale_return_lines l
                      WHERE l.return_id = r.id AND l.disposition = 'write_off'), 0)
      FROM retail_sale_returns r
     WHERE r.status = 'completed' AND r.returned_on BETWEEN '2026-09-01' AND '2026-09-30'
) f GROUP BY branch_id, day ORDER BY day, branch_id
