-- CatalogueRepository.history, one product
-- tenant: tenant
SELECT id, created_at, changed_by, source, source_id, old_cost_minor, new_cost_minor,
       old_sell_minor, new_sell_minor, currency, reason
  FROM retail_price_history WHERE product_id = :'product' ORDER BY created_at, id
