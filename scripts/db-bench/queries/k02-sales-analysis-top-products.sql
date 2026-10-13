-- SalesAnalysisRepository.grouped(PRODUCT), top 10 by sales over a year (issue #149) (net of customer returns, issue #201)
-- tenant: tenant
SELECT p.id AS k, p.code, p.description AS label, u.name AS unit, sum(l.qty) AS qty,
       sum(l.line_total_minor) AS sales, sum(l.line_cost_minor) AS cost, count(DISTINCT s.id) AS sale_count
  FROM (SELECT s0.tenant_id, s0.id, s0.sale_date, s0.branch_id, s0.created_by, s0.status, s0.payment_method,
                      l0.product_id, l0.qty, l0.line_total_minor, l0.line_cost_minor
                 FROM retail_sales s0
                 JOIN retail_sale_lines l0 ON l0.tenant_id = s0.tenant_id AND l0.sale_id = s0.id
                WHERE s0.status = 'completed'
               UNION ALL
               SELECT s0.tenant_id, CAST(NULL AS uuid), r0.returned_on, r0.branch_id, s0.created_by, s0.status,
                      s0.payment_method, rl0.product_id, -rl0.qty, -rl0.line_total_minor,
                      CASE WHEN rl0.disposition = 'write_off' THEN 0 ELSE -rl0.line_cost_minor END
                 FROM retail_sale_returns r0
                 JOIN retail_sales s0 ON s0.tenant_id = r0.tenant_id AND s0.id = r0.sale_id
                 JOIN retail_sale_return_lines rl0 ON rl0.tenant_id = r0.tenant_id AND rl0.return_id = r0.id
                WHERE r0.status = 'completed' AND s0.status = 'completed') s
              JOIN LATERAL (VALUES (s.tenant_id, s.product_id, s.qty, s.line_total_minor, s.line_cost_minor))
                   AS l (tenant_id, product_id, qty, line_total_minor, line_cost_minor) ON true
  JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
  JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id
 WHERE s.status = 'completed' AND s.sale_date BETWEEN '2025-10-01' AND '2026-09-30'
 GROUP BY p.id, p.code, p.description, u.name
 ORDER BY sum(l.line_total_minor) DESC, sum(l.qty) DESC, 1 LIMIT 10
