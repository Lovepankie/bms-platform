-- Create one platform operator (super admin, chapter 8 section 8.2) and print a one-time setup
-- token, valid 24 hours. Runs as bms_owner (docs/runbooks/onboard-tenant.md). Idempotent: an
-- existing email is reported and left alone.
--
--   psql -v email=operator@example.test -v name='Test Operator' -f create-platform-user.sql
--
-- The operator then sets a password with POST /api/v1/platform/auth/setup {token, password} on
-- the platform host (BMS_PLATFORM_HOST), and enrols TOTP at first sign-in (mandatory). Only the SHA-256 of the token
-- is stored; the token is shown once, here.
\set ON_ERROR_STOP on

SELECT EXISTS (SELECT 1 FROM platform_users WHERE lower(email) = lower(:'email')) AS user_exists \gset
\if :user_exists
  \echo platform user :email already exists, nothing changed
  \quit
\endif

SELECT replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '') AS setup_token \gset

BEGIN;
INSERT INTO platform_users (id, email, full_name, setup_token_hash, setup_token_expires_at)
VALUES (gen_random_uuid(), lower(:'email'), :'name', encode(sha256(convert_to(:'setup_token', 'UTF8')), 'hex'),
        now() + interval '24 hours');
INSERT INTO platform_audit_log (id, action, data)
VALUES (gen_random_uuid(), 'platform.user.created', jsonb_build_object('email', lower(:'email')));
COMMIT;

\echo created platform user :email; one-time setup token (24 hours): :setup_token
