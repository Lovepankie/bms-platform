-- StockHealthRepository.stocktakes, a quarter (issue #149); usage() reads retail_usage_reports_branch the same way
-- tenant: tenant
SELECT branch_id, count(*) FILTER (WHERE qty < 0) AS short_lines, count(*) FILTER (WHERE qty > 0) AS over_lines,
       coalesce(sum(round(abs(qty) * unit_cost_minor)) FILTER (WHERE qty < 0), 0) AS loss,
       coalesce(sum(round(abs(qty) * unit_cost_minor)) FILTER (WHERE qty > 0), 0) AS gain
  FROM retail_stock_movements
 WHERE kind = 'adjustment' AND source_type = 'retail.stocktake' AND business_date BETWEEN '2026-07-01' AND '2026-09-30'
 GROUP BY branch_id
