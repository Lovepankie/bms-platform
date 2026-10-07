-- StaffAccounts.byLogin (sign-in)
-- tenant: tenant
SELECT u.id, coalesce(u.email, u.phone_e164) AS login, u.status, c.password_hash, u.mfa_enabled,
       c.totp_secret_enc, c.totp_pending_secret_enc, c.totp_last_step, u.failed_login_count, u.locked_until
  FROM users u LEFT JOIN user_credentials c ON c.user_id = u.id
 WHERE u.kind = 'staff' AND lower(u.email) = lower('staff2' || '@' || 'bench-1.test')
