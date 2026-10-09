-- EvaluationRepository.rows: per branch, category and item, the period's sales snapshots beside the stock at
-- today's prices, top 10 per category, category sums by window (issue #149)
-- tenant: tenant
WITH sold AS (SELECT s.branch_id, l.product_id, sum(l.line_total_minor) AS sales, sum(l.line_cost_minor) AS cost
                FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
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
