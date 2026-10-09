-- DashboardRepository.positions: stock value and out of stock or low counts per branch (issue #149);
-- the balances of the branches in scope by retail_stock_balances' primary key, no date involved
-- tenant: tenant
SELECT b.branch_id, coalesce(sum(round(b.qty * p.sell_minor)) FILTER (WHERE b.qty > 0), 0) AS at_price,
       coalesce(sum(round(b.qty * p.cost_minor)) FILTER (WHERE b.qty > 0), 0) AS at_cost,
       count(*) FILTER (WHERE b.qty <= 0) AS out_of_stock, count(*) FILTER (WHERE b.qty > 0 AND b.qty <= 5.000) AS low_stock
  FROM retail_stock_balances b JOIN retail_products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id
 WHERE p.active GROUP BY b.branch_id
