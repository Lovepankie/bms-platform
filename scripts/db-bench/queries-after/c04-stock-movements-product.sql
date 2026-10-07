-- StockRepository.movements (after: instant range added), one product, a month
-- tenant: tenant
SELECT id, occurred_at, branch_id, product_id, kind, qty, unit_cost_minor, source_type,
       source_id, reverses_movement_id, historical, note, recorded_by
  FROM retail_stock_movements WHERE true
   AND product_id = :'product'
   AND (occurred_at AT TIME ZONE 'Africa/Kampala')::date >= '2026-09-01'
   AND occurred_at >= CAST(CAST('2026-09-01' AS date) - 1 AS timestamp) AT TIME ZONE 'Africa/Kampala'
   AND (occurred_at AT TIME ZONE 'Africa/Kampala')::date <= '2026-09-30'
   AND occurred_at < CAST(CAST('2026-09-30' AS date) + 2 AS timestamp) AT TIME ZONE 'Africa/Kampala'
 ORDER BY occurred_at, id LIMIT 101
