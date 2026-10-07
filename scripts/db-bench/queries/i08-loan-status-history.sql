-- loan status history of a loan
-- tenant: lending_tenant
SELECT * FROM lending_loan_status_history WHERE loan_id = :'loan' ORDER BY created_at
