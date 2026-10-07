-- PurchasingRepository.page, one branch
-- tenant: tenant
SELECT p.id, p.purchase_no, p.supplier_id, p.purchased_on, p.payment_method, p.currency, p.total_minor,
       p.note, p.created_at, p.created_by
  FROM retail_purchases p WHERE true
   AND EXISTS (SELECT 1 FROM retail_stock_movements m WHERE m.source_type = 'retail.purchase'
               AND m.source_id = p.id AND m.branch_id IN (:'branch'))
 ORDER BY p.created_at, p.id LIMIT 51
