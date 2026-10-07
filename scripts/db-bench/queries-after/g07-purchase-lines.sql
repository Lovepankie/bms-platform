-- PurchasingRepository.withLines, lines: after, one statement for a page of 51 purchases
-- tenant: tenant
SELECT l.purchase_id, l.id, l.line_no, l.product_id, pr.code, pr.description, l.cost_minor,
       l.sell_minor, l.qty_total, l.line_total_minor
  FROM retail_purchase_lines l JOIN retail_products pr ON pr.id = l.product_id
 WHERE l.purchase_id IN (:purchase_page) ORDER BY l.purchase_id, l.line_no
