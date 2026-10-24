-- DashboardRepository.counts: the Home "Low on stock" figure and the dashboard's out and low counts over every branch
-- (issue #490, ADR-077): out when the summed balance is zero or less and a branch has held the item, low when it is low at a
-- branch that has held it; over the branches the positions report, active or closed and still holding stock.
-- tenant: tenant
SELECT count(*) FILTER (WHERE (t.total <= 0 AND EXISTS (SELECT 1 FROM branches lb WHERE lb.tenant_id = t.tenant_id
           AND (lb.status = 'active' OR EXISTS (SELECT 1 FROM retail_stock_balances h WHERE h.tenant_id = lb.tenant_id
                AND h.branch_id = lb.id AND h.qty <> 0))
           AND (EXISTS (SELECT 1 FROM retail_stock_balances held_b WHERE held_b.tenant_id = t.tenant_id
                         AND held_b.branch_id = lb.id AND held_b.product_id = t.id)
                OR EXISTS (SELECT 1 FROM retail_stock_movements held_m WHERE held_m.tenant_id = t.tenant_id
                         AND held_m.branch_id = lb.id AND held_m.product_id = t.id))))) AS out_of_stock,
       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM branches lb LEFT JOIN retail_stock_balances lq
                ON lq.tenant_id = lb.tenant_id AND lq.branch_id = lb.id AND lq.product_id = t.id
            WHERE lb.tenant_id = t.tenant_id
           AND (lb.status = 'active' OR EXISTS (SELECT 1 FROM retail_stock_balances h WHERE h.tenant_id = lb.tenant_id
                AND h.branch_id = lb.id AND h.qty <> 0))
              AND (coalesce(lq.qty, 0) <= coalesce((SELECT min_b.min_qty FROM retail_stock_minimums min_b
                          WHERE min_b.tenant_id = t.tenant_id AND min_b.product_id = t.id AND min_b.branch_id = lb.id),
                        (SELECT min_a.min_qty FROM retail_stock_minimums min_a WHERE min_a.tenant_id = t.tenant_id
                          AND min_a.product_id = t.id AND min_a.branch_id IS NULL), 5.000)
                   AND (EXISTS (SELECT 1 FROM retail_stock_balances held_b WHERE held_b.tenant_id = t.tenant_id
                                 AND held_b.branch_id = lb.id AND held_b.product_id = t.id)
                        OR EXISTS (SELECT 1 FROM retail_stock_movements held_m WHERE held_m.tenant_id = t.tenant_id
                                 AND held_m.branch_id = lb.id AND held_m.product_id = t.id))))) AS low_stock
  FROM (SELECT p.tenant_id, p.id, coalesce(sum(b.qty), 0) AS total
          FROM retail_products p
          LEFT JOIN retail_stock_balances b ON b.tenant_id = p.tenant_id AND b.product_id = p.id
         WHERE p.active
         GROUP BY p.tenant_id, p.id) t
