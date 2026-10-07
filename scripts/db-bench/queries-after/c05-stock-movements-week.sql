-- StockRepository.movements (after: instant range added), every product, a week
-- tenant: tenant
SELECT id, occurred_at, branch_id, product_id, kind, qty, unit_cost_minor, source_type,
       source_id, reverses_movement_id, historical, note, recorded_by
  FROM retail_stock_movements WHERE true
   AND (occurred_at AT TIME ZONE 'Africa/Kampala')::date >= '2026-09-20'
   AND occurred_at >= CAST(CAST('2026-09-20' AS date) - 1 AS timestamp) AT TIME ZONE 'Africa/Kampala'
   AND (occurred_at AT TIME ZONE 'Africa/Kampala')::date <= '2026-09-27'
   AND occurred_at < CAST(CAST('2026-09-27' AS date) + 2 AS timestamp) AT TIME ZONE 'Africa/Kampala'
 ORDER BY occurred_at, id LIMIT 101
