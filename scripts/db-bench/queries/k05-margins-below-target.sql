-- SalesAnalysisRepository.grouped(PRODUCT, MARGIN, below target 2000 bp), a month (issue #149)
-- tenant: tenant
SELECT p.id AS k, p.code, p.description AS label, u.name AS unit, sum(l.qty) AS qty,
       sum(l.line_total_minor) AS sales, sum(l.line_cost_minor) AS cost, count(DISTINCT s.id) AS sale_count,
       count(*) OVER () AS total
  FROM retail_sales s JOIN retail_sale_lines l ON l.tenant_id = s.tenant_id AND l.sale_id = s.id
  JOIN retail_products p ON p.tenant_id = l.tenant_id AND p.id = l.product_id
  JOIN retail_units u ON u.tenant_id = p.tenant_id AND u.id = p.unit_id
 WHERE s.status = 'completed' AND s.sale_date BETWEEN '2026-09-01' AND '2026-09-30'
 GROUP BY p.id, p.code, p.description, u.name
HAVING (sum(l.line_total_minor) - sum(l.line_cost_minor))::numeric * 10000 < 2000::numeric * sum(l.line_total_minor)
 ORDER BY (sum(l.line_total_minor) - sum(l.line_cost_minor))::numeric / nullif(sum(l.line_total_minor), 0) ASC NULLS FIRST,
          sum(l.line_total_minor) DESC, 1 LIMIT 10
