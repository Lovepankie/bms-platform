-- MemberRepository.countActive, plan limit check on every member create
-- tenant: lending_tenant
SELECT count(*) FROM lending_members WHERE status = 'active'
