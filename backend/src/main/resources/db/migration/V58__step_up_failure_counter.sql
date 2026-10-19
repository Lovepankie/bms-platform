-- Issue #309 (S1): a signed-in step-up (a TOTP code re-entered for a sensitive action) keeps its own failure counter and lock,
-- apart from the password counter (failed_login_count / locked_until). Reaching the step-up threshold ends the user's sessions;
-- password failures never do, and never count toward it, so mixing the two cannot hide a guessing session nor let a stranger's
-- wrong passwords plus one real slip log everyone out.
ALTER TABLE users
    ADD COLUMN step_up_failed_count integer NOT NULL DEFAULT 0,
    ADD COLUMN step_up_locked_until timestamptz;
ALTER TABLE platform_users
    ADD COLUMN step_up_failed_count integer NOT NULL DEFAULT 0,
    ADD COLUMN step_up_locked_until timestamptz;
