-- CreditRepository.overdue, biggest first (issue #149)
-- tenant: tenant
SELECT s.id, s.sale_no, s.branch_id, s.customer_id, coalesce(c.name, s.buyer_name) AS buyer, s.sale_date, s.due_date,
       (DATE '2026-09-30' - s.due_date) AS days, s.total_minor, s.total_minor - s.paid_minor AS outstanding, count(*) OVER () AS total
  FROM retail_sales s LEFT JOIN retail_customers c ON c.tenant_id = s.tenant_id AND c.id = s.customer_id
 WHERE s.payment_method = 'credit' AND s.status = 'completed' AND s.total_minor > s.paid_minor AND s.due_date < DATE '2026-09-30'
 ORDER BY s.total_minor - s.paid_minor DESC, s.due_date, s.id LIMIT 20
