-- CatalogueRepository.page, catalogue list
-- tenant: tenant
SELECT p.id, p.code, p.description, p.category_id, c.name AS category, p.unit_id, u.name AS unit,
       p.sell_minor, p.cost_minor, p.currency, p.active, p.created_at, p.updated_at, p.version
  FROM retail_products p
  JOIN retail_categories c ON c.id = p.category_id
  JOIN retail_units u ON u.id = p.unit_id
 WHERE true ORDER BY p.code, p.id LIMIT 51
