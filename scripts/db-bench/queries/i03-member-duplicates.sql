-- MemberRepository.duplicateCandidates (create a member)
-- tenant: lending_tenant
SELECT id, member_no, full_name, branch_id, phone_e164, national_id,
       coalesce(national_id = 'CMTEST00000001', false) AS by_nin,
       coalesce(phone_e164 = '+256700000001', false) AS by_phone,
       coalesce(full_name % 'Test Borrower steel cement 9' AND similarity(full_name, 'Test Borrower steel cement 9') >= 0.6, false) AS by_name
  FROM lending_members
 WHERE (national_id = 'CMTEST00000001' OR phone_e164 = '+256700000001'
        OR (full_name % 'Test Borrower steel cement 9' AND similarity(full_name, 'Test Borrower steel cement 9') >= 0.6))
   AND id IS DISTINCT FROM CAST(NULL AS uuid)
 ORDER BY by_nin DESC, by_phone DESC, member_no
 LIMIT 20
