-- PurchasingRepository.withLines, branch quantities: before, one statement per purchase line (153 per page)
-- tenant: tenant
SELECT branch_id, qty FROM retail_stock_movements
 WHERE source_type = 'retail.purchase' AND source_line_id = :'purchase_line'
 ORDER BY created_at, id
