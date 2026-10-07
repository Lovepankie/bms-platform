-- SessionStore.revokeAllOf (deactivate, MFA reset)
-- tenant: tenant
UPDATE auth_sessions SET revoked_at = now(), revoked_reason = 'admin' WHERE user_id = :'user' AND revoked_at IS NULL
