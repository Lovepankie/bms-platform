-- CreditRepository.buyers: open credit by buyer with ageing buckets as of a date (issue #149). The predicate
-- is that of SalesRepository.page owing=owing; no date range bounds it, the open debts do. Measured here.
-- tenant: tenant
SELECT max(s.customer_id::text)::uuid AS customer_id, coalesce(max(c.name), min(s.buyer_name)) AS name, count(*) AS sales,
       sum(s.total_minor - s.paid_minor) AS owed,
       sum(CASE WHEN s.due_date IS NULL OR (DATE '2026-09-30' - s.due_date) <= 0 THEN s.total_minor - s.paid_minor ELSE 0 END) AS not_due,
       sum(CASE WHEN (DATE '2026-09-30' - s.due_date) BETWEEN 1 AND 30 THEN s.total_minor - s.paid_minor ELSE 0 END) AS d30,
       sum(CASE WHEN (DATE '2026-09-30' - s.due_date) > 90 THEN s.total_minor - s.paid_minor ELSE 0 END) AS over90
  FROM retail_sales s LEFT JOIN retail_customers c ON c.tenant_id = s.tenant_id AND c.id = s.customer_id
 WHERE s.payment_method = 'credit' AND s.status = 'completed' AND s.total_minor > s.paid_minor
 GROUP BY coalesce(s.customer_id::text, 'n:' || lower(btrim(s.buyer_name)))
 ORDER BY sum(s.total_minor - s.paid_minor) DESC, 2, 1 LIMIT 20
