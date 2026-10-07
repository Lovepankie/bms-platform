-- StockRepository.movements, every product, a week
-- tenant: tenant
SELECT id, occurred_at, branch_id, product_id, kind, qty, unit_cost_minor, source_type,
       source_id, reverses_movement_id, historical, note, recorded_by
  FROM retail_stock_movements WHERE true
   AND (occurred_at AT TIME ZONE 'Africa/Kampala')::date >= '2026-09-20'
   AND (occurred_at AT TIME ZONE 'Africa/Kampala')::date <= '2026-09-27'
 ORDER BY occurred_at, id LIMIT 101
