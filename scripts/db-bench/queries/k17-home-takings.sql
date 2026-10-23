-- HomeRepository.takings: today's takings by way of paying for the shop Home (issue #485, ADR-076), read as the cash book
-- reads a day for every way. Sales by retail_sales_by_date, voids by retail_sales_voided, deliveries by
-- retail_customer_orders_delivered. Payments are chosen by paid_on alone, as the cash book chooses them; no index leads on
-- paid_on (retail_sale_payments_sale is (tenant_id, sale_id, paid_on)), so that part reads the tenant's payments, as the cash
-- summary's own query does. The reversals of payments on account (reversed_on), the refunds (returned_on, voided_on) and the
-- fees paid on an account (fee_paid_on) have no index of their own either. All are measured here.
-- tenant: tenant
SELECT t.method, t.kind, sum(t.amount) AS amount FROM (
  SELECT s.payment_method AS method, 'sales' AS kind, s.total_minor AS amount FROM retail_sales s
   WHERE s.sale_date = DATE '2026-09-30' AND s.payment_method <> 'credit'
  UNION ALL SELECT s.payment_method, 'out', s.total_minor FROM retail_sales s
   WHERE s.payment_method <> 'credit' AND s.voided_at >= TIMESTAMPTZ '2026-09-30 00:00+03' AND s.voided_at < TIMESTAMPTZ '2026-10-01 00:00+03'
  UNION ALL SELECT p.method, 'payments', p.amount_minor FROM retail_sale_payments p
    JOIN retail_sales s ON s.tenant_id = p.tenant_id AND s.id = p.sale_id
    LEFT JOIN retail_account_payment_allocations al ON al.tenant_id = p.tenant_id AND al.sale_payment_id = p.id
    LEFT JOIN retail_account_payments ap ON ap.tenant_id = al.tenant_id AND ap.id = al.receipt_id
   WHERE p.paid_on = DATE '2026-09-30'
  UNION ALL SELECT ap.method, 'out', ap.amount_minor FROM retail_account_payments ap
   WHERE ap.method IN ('cash', 'mobile_money', 'bank') AND ap.status = 'reversed' AND ap.reversed_on = DATE '2026-09-30'
  UNION ALL SELECT r.refund_method, 'out', r.refund_minor FROM retail_sale_returns r
   WHERE r.refund_method IN ('cash', 'mobile_money', 'bank') AND r.refund_minor > 0 AND r.returned_on = DATE '2026-09-30'
  UNION ALL SELECT r.refund_method, 'out', -r.refund_minor FROM retail_sale_returns r
   WHERE r.refund_method IN ('cash', 'mobile_money', 'bank') AND r.refund_minor > 0 AND r.status = 'voided'
     AND r.voided_on = DATE '2026-09-30'
  UNION ALL SELECT o.fee_method, 'fees', o.delivery_fee_minor FROM retail_customer_orders o
   WHERE o.status = 'delivered' AND o.fee_method IN ('cash', 'mobile_money', 'bank') AND o.fee_entry_id IS NOT NULL
     AND o.delivered_on = DATE '2026-09-30'
  UNION ALL SELECT o.fee_paid_method, 'fees', o.delivery_fee_minor FROM retail_customer_orders o
   WHERE o.fee_paid_method IN ('cash', 'mobile_money', 'bank') AND o.fee_paid_on = DATE '2026-09-30'
  UNION ALL SELECT o.fee_method, 'out', o.delivery_fee_minor FROM retail_customer_orders o
    JOIN retail_sales s ON s.tenant_id = o.tenant_id AND s.id = o.sale_id
   WHERE o.fee_method IN ('cash', 'mobile_money', 'bank') AND o.fee_entry_id IS NOT NULL AND s.status = 'voided'
     AND s.voided_at >= TIMESTAMPTZ '2026-09-30 00:00+03' AND s.voided_at < TIMESTAMPTZ '2026-10-01 00:00+03'
) t GROUP BY t.method, t.kind
