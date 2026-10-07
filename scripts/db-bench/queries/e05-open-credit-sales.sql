-- SalesRepository.openSales, a buyer's debts
-- tenant: tenant
SELECT id, sale_no, branch_id, sale_date, due_date, total_minor, paid_minor FROM retail_sales
 WHERE customer_id = :'customer' AND payment_method = 'credit' AND status = 'completed'
   AND paid_minor < total_minor ORDER BY sale_date, created_at, id
