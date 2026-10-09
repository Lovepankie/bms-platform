-- MarginRepository.priceChanges, a month (issue #149). The history is read by tenant and created_at;
-- retail_price_history_product leads with the product, so this is a filter over the tenant's history
-- (one row per price event), measured here before any index is considered.
-- tenant: tenant
WITH changed AS (
    SELECT h.product_id,
           (array_agg(h.old_sell_minor ORDER BY h.created_at, h.id))[1] AS old_sell,
           (array_agg(h.new_sell_minor ORDER BY h.created_at DESC, h.id DESC))[1] AS new_sell,
           (max(h.created_at) AT TIME ZONE 'Africa/Kampala')::date AS changed_on
      FROM retail_price_history h
     WHERE h.created_at >= '2026-08-31 21:00:00+00' AND h.created_at < '2026-09-30 21:00:00+00'
       AND h.source <> 'initial' AND h.old_sell_minor IS DISTINCT FROM h.new_sell_minor
     GROUP BY h.product_id
), sold AS (
    SELECT l.product_id, sum(l.qty) FILTER (WHERE s.sale_date < c.changed_on) AS qty_before,
           sum(l.qty) FILTER (WHERE s.sale_date >= c.changed_on) AS qty_after
      FROM retail_sales s
      JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
      JOIN changed c ON c.product_id = l.product_id
     WHERE s.status = 'completed' AND s.sale_date BETWEEN '2026-09-01' AND '2026-09-30'
     GROUP BY l.product_id
)
SELECT c.product_id, p.code, c.changed_on, c.old_sell, c.new_sell, d.qty_before, d.qty_after, count(*) OVER () AS total
  FROM changed c JOIN retail_products p ON p.id = c.product_id LEFT JOIN sold d ON d.product_id = c.product_id
 ORDER BY c.changed_on DESC, p.code, c.product_id LIMIT 10
