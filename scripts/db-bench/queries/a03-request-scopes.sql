-- Grants.scopes (every request)
-- tenant: tenant
SELECT rp.permission_key, a.branch_id
  FROM user_role_assignments a
  JOIN role_permissions rp ON rp.role_key = a.role_key
 WHERE a.user_id = :'user' AND a.revoked_at IS NULL
   AND (a.branch_id IS NULL OR EXISTS (SELECT 1 FROM branches b WHERE b.id = a.branch_id AND b.status = 'active'))
