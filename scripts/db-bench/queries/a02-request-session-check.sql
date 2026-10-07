-- AuthenticationFilter.staffPrincipal (every request)
-- tenant: tenant
SELECT count(*) FROM auth_sessions s JOIN users u ON u.id = s.user_id
 WHERE s.id = :'session' AND s.user_id = :'user' AND s.revoked_at IS NULL AND u.status = 'active'
