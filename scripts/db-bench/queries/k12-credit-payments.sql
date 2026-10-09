-- CreditRepository.payments, a month by day and method (issue #149). retail_sale_payments_sale leads with
-- the sale, so this reads the tenant's credit payments and filters by date: measured here.
-- tenant: tenant
SELECT p.paid_on, p.method, count(*) AS payments, sum(p.amount_minor) AS amount
  FROM retail_sale_payments p JOIN retail_sales s ON s.tenant_id = p.tenant_id AND s.id = p.sale_id
 WHERE p.paid_on BETWEEN '2026-09-01' AND '2026-09-30' AND s.status = 'completed'
 GROUP BY p.paid_on, p.method ORDER BY p.paid_on, p.method
