-- Staff forgot password and the admin reset of sign-in (issue #320, FR-IAM-15, ADR-042).
--
-- Numbered V62: V61 is the branch close (#347), the last on main when this merged. Flyway runs with outOfOrder off, so a number
-- is taken above the highest on main at merge time.
--
-- Locking, for the rollout (chapter 6 section 6.9, expand and contract):
--  1. CREATE TABLE user_password_resets takes SHARE ROW EXCLUSIVE on tenants and users (the foreign keys) until the commit, which
--     blocks writes to those two tables, not reads, while the file runs. The new table is empty, so the cost is the lock wait and
--     not a scan.
--  2. The auth_sessions revoked_reason check is replaced by a looser one (it only gains 'password_reset'), added NOT VALID so no
--     row is scanned: every existing row satisfies the looser check. DROP and ADD CONSTRAINT take ACCESS EXCLUSIVE on
--     auth_sessions, so that statement is last and lock_timeout is five seconds: if a lock cannot be had at once the migration
--     fails, and deploy/deploy.sh then stops with the old application containers still running (it does not retry by itself;
--     the deploy is run again). The same timeout bounds the foreign key locks of the CREATE TABLE on tenants and users in
--     point 1: a long transaction on users makes the deploy fail rather than stall every sign-in.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- One row per reset link, whether the user asked for it on the sign-in page ('self') or a tenant admin issued it ('admin').
-- Only the SHA-256 hash of the token is stored; the token itself is in the emailed link and, for an admin reset, shown once to the
-- admin. A link is single use (used_at), lives one hour (expires_at) and is replaced by a newer one (revoked_at).
-- ---------------------------------------------------------------------------------------------
CREATE TABLE user_password_resets (
    id           uuid        NOT NULL PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    user_id      uuid        NOT NULL,
    token_hash   char(64)    NOT NULL UNIQUE,
    expires_at   timestamptz NOT NULL,
    origin       text        NOT NULL CHECK (origin IN ('self', 'admin', 'operator')),
    -- The tenant admin who issued an 'admin' link, or the platform operator who issued an 'operator' link (for a tenant admin);
    -- null for a link the user asked for. No foreign key, as for user_invitations.invited_by, so the row survives whatever
    -- happens to that account.
    requested_by uuid,
    -- Set when the link sets a password.
    used_at      timestamptz,
    -- Set when a newer link replaces this one, or the user is deactivated.
    revoked_at   timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id),
    CHECK ((origin IN ('admin', 'operator')) = (requested_by IS NOT NULL)),
    CHECK (used_at IS NULL OR revoked_at IS NULL)
);
-- Open links of a user (revoked by the next request) and the per-account request count of the last hour.
CREATE INDEX user_password_resets_by_user ON user_password_resets (tenant_id, user_id, created_at DESC);
SELECT bms_apply_tenant_rls('user_password_resets');
SELECT bms_grant_app('user_password_resets', 'SELECT, INSERT, UPDATE');

-- The nightly retention of the table (the job core.identity.password-reset-purge): a link is useless an hour after it was made,
-- the row stays 30 days for the audit trail's sake. A definer function, like ai_calls_housekeeping: a job has no tenant bound,
-- so a plain DELETE by the application role would see no row under the tenant policy, and the role has no DELETE grant. The
-- function is callable by the application role and deletes across every tenant, so it cannot be told to delete anything younger
-- than 30 days, whatever cutoff it is given (even 'infinity').
CREATE FUNCTION user_password_resets_purge(p_before timestamptz) RETURNS integer
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH d AS (DELETE FROM user_password_resets WHERE created_at < least(p_before, now() - interval '30 days') RETURNING 1)
    SELECT count(*)::integer FROM d
$$;
REVOKE ALL ON FUNCTION user_password_resets_purge(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION user_password_resets_purge(timestamptz) TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- Sessions ended by a reset carry their own reason. Last statement of the file; see the lock note above.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE auth_sessions DROP CONSTRAINT auth_sessions_revoked_reason_check;
ALTER TABLE auth_sessions ADD CONSTRAINT auth_sessions_revoked_reason_check
    CHECK (revoked_reason IN ('sign_out', 'rotation_reuse', 'user_deactivated', 'admin', 'mfa_reset', 'password_reset')) NOT VALID;
