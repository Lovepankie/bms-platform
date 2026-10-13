-- CreditRepository.payments, a month by day and method (issue #149, #196). retail_sale_payments_sale leads with
-- the sale, so this reads the tenant's credit payments and filters by date: measured here. A payment on account
-- is joined to its receipt (the receiving branch) and stays on the day it was received even when reversed; the
-- reversal is a negative row on its own day (ap.reversed_on), as in the repository.
-- tenant: tenant
SELECT m.d AS paid_on, m.method, sum(m.n) AS payments, sum(m.amt) AS amount FROM (
  SELECT p.paid_on AS d, p.method, 1 AS n, p.amount_minor AS amt
    FROM retail_sale_payments p JOIN retail_sales s ON s.tenant_id = p.tenant_id AND s.id = p.sale_id
    LEFT JOIN retail_account_payment_allocations al ON al.tenant_id = p.tenant_id AND al.sale_payment_id = p.id
    LEFT JOIN retail_account_payments ap ON ap.tenant_id = al.tenant_id AND ap.id = al.receipt_id
   WHERE p.paid_on BETWEEN '2026-09-01' AND '2026-09-30' AND s.status = 'completed'
  UNION ALL
  SELECT ap.reversed_on, p.method, -1, -p.amount_minor
    FROM retail_account_payments ap
    JOIN retail_account_payment_allocations al ON al.tenant_id = ap.tenant_id AND al.receipt_id = ap.id
    JOIN retail_sale_payments p ON p.tenant_id = al.tenant_id AND p.id = al.sale_payment_id
    JOIN retail_sales s ON s.tenant_id = p.tenant_id AND s.id = p.sale_id
   WHERE ap.status = 'reversed' AND ap.reversed_on BETWEEN '2026-09-01' AND '2026-09-30' AND s.status = 'completed'
) m GROUP BY m.d, m.method HAVING sum(m.n) <> 0 OR sum(m.amt) <> 0 ORDER BY m.d, m.method
