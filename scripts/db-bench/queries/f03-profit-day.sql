-- ReportsRepository.daily, one day (with customer returns, issue #201, and landed cost and supplier credit notes, issue #199)
-- tenant: tenant
SELECT branch_id, day, sum(sales) AS sales, sum(cost) AS cost, sum(usage) AS usage FROM (
    SELECT s.branch_id, s.sale_date AS day, l.line_total_minor AS sales,
           l.line_cost_minor AS cost, 0 AS usage
      FROM retail_sale_lines l JOIN retail_sales s ON s.id = l.sale_id
     WHERE s.status = 'completed' AND s.sale_date BETWEEN '2026-09-29' AND '2026-09-29'
    UNION ALL
    SELECT branch_id, occurred_on, 0, 0, cost_total_minor FROM retail_usage_reports
     WHERE occurred_on BETWEEN '2026-09-29' AND '2026-09-29'
    UNION ALL
    SELECT r.branch_id, r.returned_on, -r.total_minor, -r.cost_minor,
           coalesce((SELECT sum(l.line_cost_minor) FROM retail_sale_return_lines l
                      WHERE l.return_id = r.id AND l.disposition = 'write_off'), 0)
      FROM retail_sale_returns r
     WHERE r.status = 'completed' AND r.returned_on BETWEEN '2026-09-29' AND '2026-09-29'
    UNION ALL
    SELECT l.branch_id, d.cost_date, 0, l.to_cogs_minor, 0
      FROM retail_landed_cost_lines l JOIN retail_landed_costs d ON d.id = l.landed_cost_id
     WHERE d.status = 'posted' AND d.cost_date BETWEEN '2026-09-29' AND '2026-09-29'
    UNION ALL
    SELECT l.branch_id, c.credit_date, 0, -l.to_cogs_minor, 0
      FROM retail_supplier_credit_lines l JOIN retail_supplier_credits c ON c.id = l.credit_id
     WHERE c.status = 'posted' AND l.to_cogs_minor > 0 AND c.credit_date BETWEEN '2026-09-29' AND '2026-09-29'
) f GROUP BY branch_id, day ORDER BY day, branch_id
