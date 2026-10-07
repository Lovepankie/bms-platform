-- LoanRepository.overdueApprovals (nightly job)
-- tenant: lending_tenant
SELECT * FROM lending_loans WHERE status = 'approved' AND approved_at < now() - interval '30 days' FOR UPDATE
