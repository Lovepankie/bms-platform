-- JdbcStockLedger.positions (import)
-- tenant: tenant
SELECT b.branch_id, b.product_id, b.qty,
       coalesce(sum(m.qty) FILTER (WHERE m.historical AND m.kind <> 'legacy_balance'), 0) AS hist,
       bool_or(m.kind = 'legacy_balance') IS TRUE AS legacy
  FROM retail_stock_balances b
  LEFT JOIN retail_stock_movements m ON m.branch_id = b.branch_id AND m.product_id = b.product_id
 GROUP BY b.branch_id, b.product_id, b.qty
