-- StockRepository.movements, one product, a month
-- tenant: tenant
SELECT id, occurred_at, branch_id, product_id, kind, qty, unit_cost_minor, source_type,
       source_id, reverses_movement_id, historical, note, recorded_by
  FROM retail_stock_movements WHERE true
   AND product_id = :'product'
   AND (occurred_at AT TIME ZONE 'Africa/Kampala')::date >= '2026-09-01'
   AND (occurred_at AT TIME ZONE 'Africa/Kampala')::date <= '2026-09-30'
 ORDER BY occurred_at, id LIMIT 101
