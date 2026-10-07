-- StockRepository.mismatches, reconciliation
-- tenant: tenant
SELECT branch_id, product_id, coalesce(b.qty, 0) AS balance, coalesce(m.total, 0) AS movements
  FROM retail_stock_balances b
  FULL JOIN (SELECT branch_id, product_id, sum(qty) AS total
               FROM retail_stock_movements GROUP BY branch_id, product_id) m
 USING (branch_id, product_id)
 WHERE coalesce(b.qty, 0) <> coalesce(m.total, 0)
 ORDER BY branch_id, product_id
