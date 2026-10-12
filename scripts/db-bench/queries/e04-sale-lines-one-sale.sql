-- SalesRepository.withLines, per sale (N+1: 51 per page)
-- tenant: tenant
SELECT l.id, l.line_no, l.product_id, p.code, p.description, l.qty, l.unit_price_minor,
       l.line_total_minor, l.unit_cost_minor, l.line_cost_minor, l.sale_unit_id, su.name AS sale_unit,
       l.sale_qty, l.sale_unit_price_minor, l.unit_factor
  FROM retail_sale_lines l JOIN retail_products p ON p.id = l.product_id
  LEFT JOIN retail_units su ON su.id = l.sale_unit_id
 WHERE l.sale_id = :'sale' ORDER BY l.line_no
