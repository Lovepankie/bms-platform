-- SalesRepository.page headers, no filter
-- tenant: tenant
SELECT id, sale_no, branch_id, sale_date, payment_method, customer_id, buyer_name, buyer_contact,
       due_date, status, currency, total_minor, paid_minor, cost_total_minor, historical, created_at,
       created_by, voided_at, voided_by, void_reason, version, sale_entry_id, cost_entry_id
  FROM retail_sales WHERE true ORDER BY created_at, id LIMIT 51
