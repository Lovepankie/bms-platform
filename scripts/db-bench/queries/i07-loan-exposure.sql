-- LoanRepository.exposureOfMembers (appraisal)
-- tenant: lending_tenant
SELECT l.id, l.member_id, l.loan_no, l.status, l.days_past_due,
       l.principal_outstanding_minor + l.interest_outstanding_minor + l.fees_outstanding_minor
           + l.penalties_outstanding_minor AS outstanding
  FROM lending_loans l
 WHERE l.member_id IN (:'member') AND l.status IN ('submitted', 'appraised', 'approved', 'active')
   AND l.id IS DISTINCT FROM CAST(NULL AS uuid) ORDER BY l.created_at
