-- Invite a tenant admin from the host (FR-IAM-01), for a tenant created by create-tenant.sql or
-- when a tenant has lost every admin. The platform console does the same through the API
-- (FR-TEN-01). Runs as bms_owner. Prints the one-time link, valid 72 hours; only its SHA-256 is
-- stored. Idempotent for an email that is already a staff user of the tenant.
--
--   psql -v slug=demo -v email=owner@example.test -v name='Test Owner' -v origin=https://demo.<base> \
--        -f invite-tenant-admin.sql
\set ON_ERROR_STOP on

SELECT id AS tenant_id FROM tenants WHERE slug = :'slug' \gset
SELECT EXISTS (SELECT 1 FROM users WHERE tenant_id = :'tenant_id' AND kind = 'staff'
                AND lower(email) = lower(:'email')) AS user_exists \gset
\if :user_exists
  \echo :email is already a staff user of :slug, nothing changed
  \quit
\endif

SELECT replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '') AS token,
       gen_random_uuid() AS user_id \gset

BEGIN;
INSERT INTO users (id, tenant_id, kind, full_name, email, status)
VALUES (:'user_id', :'tenant_id', 'staff', :'name', lower(:'email'), 'invited');
INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, branch_id, granted_by)
VALUES (gen_random_uuid(), :'tenant_id', :'user_id', 'tenant_admin', NULL, :'user_id');
INSERT INTO user_invitations (id, tenant_id, user_id, token_hash, expires_at, invited_by)
VALUES (gen_random_uuid(), :'tenant_id', :'user_id', encode(sha256(convert_to(:'token', 'UTF8')), 'hex'),
        now() + interval '72 hours', :'user_id');
INSERT INTO audit_log (id, tenant_id, actor_kind, action, entity_type, entity_id, data)
VALUES (gen_random_uuid(), :'tenant_id', 'platform', 'core.user.invited', 'core.user', :'user_id',
        jsonb_build_object('after', jsonb_build_object('email', lower(:'email'), 'roles', '["tenant_admin@all"]'::jsonb)));
INSERT INTO audit_log (id, tenant_id, actor_kind, action, entity_type, entity_id, data)
VALUES (gen_random_uuid(), :'tenant_id', 'platform', 'core.invitation.link_revealed', 'core.user', :'user_id',
        '{"before": {}, "after": {}}'::jsonb);
COMMIT;

\echo invitation for :email (72 hours): :origin/accept-invitation#token=:token
