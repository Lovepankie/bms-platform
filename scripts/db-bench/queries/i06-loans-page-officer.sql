-- LoanRepository.page, an officer's loans
-- tenant: lending_tenant
SELECT l.* FROM lending_loans l WHERE true AND l.officer_user_id = (SELECT officer_user_id FROM lending_loans WHERE id = :'loan')
 ORDER BY l.created_at, l.id LIMIT 51
