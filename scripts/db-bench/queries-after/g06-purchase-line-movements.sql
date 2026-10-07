-- PurchasingRepository.withLines, branch quantities: after, one statement for a page of 51 purchases
-- tenant: tenant
SELECT source_line_id, branch_id, qty FROM retail_stock_movements
 WHERE source_type = 'retail.purchase' AND source_id IN (:purchase_page)
 ORDER BY created_at, id
