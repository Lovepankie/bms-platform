-- V67: two-step sign-in is optional by default (issue #348, ADR-046).
--
-- V2 seeded `roles.mfa_required = true` for the tenant admin, so on a fresh database a tenant administrator with no second factor was
-- sent to enrolment at sign-in and never got a session, while the staging database had the flag cleared by a hand edit of reference
-- data. The product decision is that two-step sign-in is optional for everyone, tenant administrators included, and that a tenant can
-- choose to require it for everyone or for chosen roles (settings `require_mfa_all_staff`, `require_mfa_roles`, `mfa_grace_days`,
-- written by PATCH /settings; no table or column is added because the tenant settings row already holds them).
--
-- This migration makes the optional state the same on every database. It is idempotent and changes nothing on staging, where the flag
-- is already false. The column and the rule stay: a role flagged here is required for every tenant that has it, which is how a
-- platform-wide mandate would be written later. Nobody who has enrolled is affected: a user with a factor still gives the code at
-- every sign-in. Platform operators are a separate table and always enrol.
--
-- One UPDATE on a seven-row reference table: no lock of note, expand-only in effect (the application reads the flag the same way).

SET LOCAL lock_timeout = '5s';

UPDATE roles SET mfa_required = false WHERE key = 'tenant_admin' AND mfa_required;

-- A session that is not renewed because the business now requires two-step sign-in of its holder (ADR-046) ends with its own reason.
-- The check is replaced by a looser one (it only gains 'two_step_required'), added NOT VALID so no row is scanned: every existing row
-- satisfies the looser check. DROP and ADD CONSTRAINT take ACCESS EXCLUSIVE on auth_sessions, so they are last, under the lock_timeout above.
ALTER TABLE auth_sessions DROP CONSTRAINT auth_sessions_revoked_reason_check;
ALTER TABLE auth_sessions ADD CONSTRAINT auth_sessions_revoked_reason_check
    CHECK (revoked_reason IN ('sign_out', 'rotation_reuse', 'user_deactivated', 'admin', 'mfa_reset', 'password_reset', 'two_step_required')) NOT VALID;
