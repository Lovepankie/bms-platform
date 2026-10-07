-- LoanRepository.page, newest loans list
-- tenant: lending_tenant
SELECT l.* FROM lending_loans l WHERE true ORDER BY l.created_at, l.id LIMIT 51
