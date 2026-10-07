-- PurchasingRepository.withLines, lines: before, one statement per purchase (51 per page)
-- tenant: tenant
SELECT l.id, l.line_no, l.product_id, pr.code, pr.description, l.cost_minor, l.sell_minor,
       l.qty_total, l.line_total_minor
  FROM retail_purchase_lines l JOIN retail_products pr ON pr.id = l.product_id
 WHERE l.purchase_id = :'purchase' ORDER BY l.line_no
