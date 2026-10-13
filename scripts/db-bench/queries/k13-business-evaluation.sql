-- EvaluationRepository.rows: per branch, category and item, the period's sales snapshots beside the stock at (net of customer returns, issue #201)
-- today's prices, top 10 per category, category sums by window (issue #149)
-- tenant: tenant
WITH sold AS (SELECT s.branch_id, l.product_id, sum(l.line_total_minor) AS sales, sum(l.line_cost_minor) AS cost
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
               WHERE s.status = 'completed' AND s.sale_date BETWEEN '2026-09-01' AND '2026-09-30'
               GROUP BY s.branch_id, l.product_id),
     stock AS (SELECT b.branch_id, b.product_id, b.qty, round(b.qty * p.sell_minor) AS at_price, round(b.qty * p.cost_minor) AS at_cost
                 FROM retail_stock_balances b JOIN retail_products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id
                WHERE b.qty > 0),
     merged AS (SELECT branch_id, product_id, coalesce(sold.sales, 0) AS sales, coalesce(sold.cost, 0) AS cost,
                       coalesce(stock.qty, 0) AS qty, coalesce(stock.at_price, 0) AS at_price, coalesce(stock.at_cost, 0) AS at_cost
                  FROM sold FULL JOIN stock USING (branch_id, product_id)),
     ranked AS (SELECT m.*, p.code, p.description, p.category_id, c.name AS category, u.name AS unit,
                       row_number() OVER (PARTITION BY m.branch_id, p.category_id
                                          ORDER BY m.at_price - m.at_cost DESC, m.sales - m.cost DESC, p.code, m.product_id) AS rn,
                       count(*) OVER w AS cat_items, sum(m.sales) OVER w AS cat_sales, sum(m.cost) OVER w AS cat_cost,
                       sum(m.at_price) OVER w AS cat_price, sum(m.at_cost) OVER w AS cat_at_cost
                  FROM merged m
                  JOIN retail_products p ON p.id = m.product_id
                  JOIN retail_categories c ON c.tenant_id = p.tenant_id AND c.id = p.category_id
                  JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id
                WINDOW w AS (PARTITION BY m.branch_id, p.category_id))
SELECT * FROM ranked WHERE rn <= 10 ORDER BY branch_id, category, category_id, rn
