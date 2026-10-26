-- V106: a platform session ended because its holder has no second factor (issue #377, from the review of #361; ADR-046).
--
-- The refresh (AuthFlow.refresh) ends a session whose holder must have two-step sign-in and has none, with the reason
-- 'two_step_required'. V67 added that reason to auth_sessions only. A platform operator always needs a second factor
-- (PlatformAccounts.mfaRequired is true), so a platform user whose mfa_enabled is false, with a live session, made the
-- refresh fail on this check (a 500) instead of ending the session. No rule changes: who must have a second factor, and
-- what happens to such a session, are as before; the platform table now accepts the reason the code already writes.
--
-- The check is replaced by a looser one (it only gains 'two_step_required'), added NOT VALID so no row is scanned: every
-- existing row satisfies the looser check. DROP and ADD CONSTRAINT take ACCESS EXCLUSIVE on platform_sessions, a small
-- table, under the lock_timeout below. The previous release never writes the new value, so it runs unchanged on this schema.
--
-- Flyway runs with outOfOrder off: V105 (issue #502) is merged and V99 stays the unused placeholder, so this is V106.

SET LOCAL lock_timeout = '5s';

ALTER TABLE platform_sessions DROP CONSTRAINT platform_sessions_revoked_reason_check;
ALTER TABLE platform_sessions ADD CONSTRAINT platform_sessions_revoked_reason_check
    CHECK (revoked_reason IN ('sign_out', 'rotation_reuse', 'user_deactivated', 'admin', 'mfa_reset', 'two_step_required')) NOT VALID;
