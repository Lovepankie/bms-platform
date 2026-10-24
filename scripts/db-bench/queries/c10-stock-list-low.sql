-- StockRepository.stock with stock_level=low, the branch stock screen's Low stock tab (issue #490, ADR-077): only items the
-- branch has held, at or below the branch's minimum, else the item's, else 5.
-- tenant: tenant
SELECT p.id, p.code, p.description, u.name AS unit, p.sell_minor, p.cost_minor, coalesce(b.qty, 0) AS qty
  FROM retail_products p
  JOIN retail_units u ON u.id = p.unit_id
  LEFT JOIN retail_stock_balances b ON b.tenant_id = p.tenant_id AND b.product_id = p.id AND b.branch_id = :'branch'
 WHERE p.active
   AND (coalesce(b.qty, 0) <= coalesce((SELECT min_b.min_qty FROM retail_stock_minimums min_b WHERE min_b.tenant_id = p.tenant_id
             AND min_b.product_id = p.id AND min_b.branch_id = :'branch'),
           (SELECT min_a.min_qty FROM retail_stock_minimums min_a WHERE min_a.tenant_id = p.tenant_id
             AND min_a.product_id = p.id AND min_a.branch_id IS NULL), 5.000)
        AND (EXISTS (SELECT 1 FROM retail_stock_balances held_b WHERE held_b.tenant_id = p.tenant_id
                      AND held_b.branch_id = :'branch' AND held_b.product_id = p.id)
             OR EXISTS (SELECT 1 FROM retail_stock_movements held_m WHERE held_m.tenant_id = p.tenant_id
                      AND held_m.branch_id = :'branch' AND held_m.product_id = p.id)))
 ORDER BY p.code, p.id LIMIT 101
