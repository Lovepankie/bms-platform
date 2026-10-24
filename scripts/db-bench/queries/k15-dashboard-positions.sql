-- DashboardRepository.positions: stock value and out of stock or low counts per branch (issue #149), every active item judged
-- only at a branch that has held it, low at or below the branch's minimum, else the item's, else 5 (issue #490, ADR-077).
-- Branches times active items; the held and minimum probes seek (tenant_id, branch_id, product_id) on the balances, the
-- movements and the minimums. The MATERIALIZED CTE keeps x apart so each probe runs once per row.
-- tenant: tenant
WITH x AS MATERIALIZED (
SELECT br.id AS branch_id, p.sell_minor, p.cost_minor, coalesce(b.qty, 0) AS qty,
       (EXISTS (SELECT 1 FROM retail_stock_balances held_b WHERE held_b.tenant_id = p.tenant_id
                 AND held_b.branch_id = br.id AND held_b.product_id = p.id)
        OR EXISTS (SELECT 1 FROM retail_stock_movements held_m WHERE held_m.tenant_id = p.tenant_id
                 AND held_m.branch_id = br.id AND held_m.product_id = p.id)) AS held,
       coalesce((SELECT min_b.min_qty FROM retail_stock_minimums min_b WHERE min_b.tenant_id = p.tenant_id
                  AND min_b.product_id = p.id AND min_b.branch_id = br.id),
                (SELECT min_a.min_qty FROM retail_stock_minimums min_a WHERE min_a.tenant_id = p.tenant_id
                  AND min_a.product_id = p.id AND min_a.branch_id IS NULL), 5.000) AS level
  FROM branches br
  CROSS JOIN retail_products p
  LEFT JOIN retail_stock_balances b ON b.tenant_id = p.tenant_id AND b.product_id = p.id AND b.branch_id = br.id
 WHERE p.active AND p.tenant_id = br.tenant_id
   AND (br.status = 'active' OR EXISTS (SELECT 1 FROM retail_stock_balances h
        WHERE h.tenant_id = br.tenant_id AND h.branch_id = br.id AND h.qty <> 0)))
SELECT x.branch_id,
       coalesce(sum(round(x.qty * x.sell_minor)) FILTER (WHERE x.qty > 0), 0) AS at_price,
       coalesce(sum(round(x.qty * x.cost_minor)) FILTER (WHERE x.qty > 0), 0) AS at_cost,
       CASE WHEN bool_or(x.held) THEN count(*) FILTER (WHERE x.held AND x.qty <= 0)::int END AS out_of_stock,
       CASE WHEN bool_or(x.held) THEN count(*) FILTER (WHERE x.held AND x.qty <= x.level)::int END AS low_stock
  FROM x GROUP BY x.branch_id
