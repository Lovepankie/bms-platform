-- LoanRepository.page, active loans
-- tenant: lending_tenant
SELECT l.* FROM lending_loans l WHERE true AND l.status IN ('active') ORDER BY l.created_at, l.id LIMIT 51
