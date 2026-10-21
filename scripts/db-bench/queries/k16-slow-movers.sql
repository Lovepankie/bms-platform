-- StockHealthRepository.slow, 90 days, 4 months, no minimum, top 10 (issue #414); paceOf reads the same sales for the listed items
-- The item's days (ageDays) are one index lookup per item and branch on retail_stock_movements_position.
-- tenant: tenant
WITH sold AS (SELECT s.branch_id, l.product_id, sum(l.qty) AS sold, max(s.sale_date) AS last_sold, min(s.sale_date) AS first_sold
                FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
               WHERE s.status = 'completed' AND s.sale_date BETWEEN '2026-07-03' AND '2026-09-30'
               GROUP BY s.branch_id, l.product_id)
SELECT sold.branch_id, p.id, p.code, p.description,
       (SELECT u.name FROM retail_units u WHERE u.tenant_id = p.tenant_id AND u.id = p.unit_id) AS unit,
       b.qty, sold.sold, sold.last_sold, age.days, round(b.qty * p.cost_minor) AS at_cost,
       count(*) OVER () AS total, sum(round(b.qty * p.cost_minor)) OVER () AS total_at_cost
  FROM sold
  JOIN retail_products p ON p.tenant_id = current_setting('app.tenant_id')::uuid AND p.id = sold.product_id
  JOIN retail_stock_balances b ON b.tenant_id = p.tenant_id AND b.branch_id = sold.branch_id AND b.product_id = sold.product_id
  CROSS JOIN LATERAL (SELECT least(90, DATE '2026-09-30' - least(sold.first_sold, (SELECT min(m.business_date) FROM retail_stock_movements m
                       WHERE m.tenant_id = p.tenant_id AND m.branch_id = sold.branch_id AND m.product_id = sold.product_id)) + 1) AS days) age
 WHERE p.active AND b.qty > 0 AND sold.sold > 0
   AND (b.qty * age.days > 4 * 30 * sold.sold OR sold.sold < 0)
 ORDER BY b.qty * age.days / sold.sold DESC, p.code, sold.branch_id, p.id LIMIT 10
