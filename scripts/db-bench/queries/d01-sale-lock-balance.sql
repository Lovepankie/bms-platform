-- JdbcStockLedger.lockBalance (record a sale)
-- tenant: tenant
SELECT qty FROM retail_stock_balances WHERE branch_id = :'branch' AND product_id = :'product' FOR UPDATE
