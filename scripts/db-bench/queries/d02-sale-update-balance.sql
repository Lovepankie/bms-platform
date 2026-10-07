-- JdbcStockLedger.record, balance update (record a sale)
-- tenant: tenant
UPDATE retail_stock_balances SET qty = qty + -1, updated_at = now()
 WHERE branch_id = :'branch' AND product_id = :'product'
