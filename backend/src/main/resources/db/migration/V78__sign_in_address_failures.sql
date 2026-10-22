-- Failed sign-ins for an address that matches no account (ADR-059, issue #426, review of PR #433 finding 1).
--
-- Numbered V78: V76 is the last on main and V77 is taken by PR #421. Flyway runs with outOfOrder off, so a number is taken
-- above the highest on main at merge time.
--
-- An address that matches no account must lock after the same five failures, for the same 15 minutes, with the same answer
-- as a real account, or the lock itself tells a stranger which addresses are accounts. The account's count and lock are
-- users.failed_login_count / locked_until (platform_users for operators); this table holds the same two values for every
-- other address, so the lock survives a restart and no flood can evict it. The application runs the same single-statement
-- count and lock on it as on an account row (SignInAddressFailures).
--
-- No address is stored: the key is the SHA-256 of "<kind>:<tenant id>:<address>", the address in lower case or in E.164.
-- No tenant owns a row (the tenant is inside the hash and operators have none), so the table has no tenant_id and no
-- row-level security, like the other platform tables (chapter 6 section 6.4); nothing in a row names a person or a tenant.
--
-- The key is text, not char(64): a lookup by a string parameter then compares text with text and uses the key's index.
--
-- Locking: CREATE TABLE of a new, empty table with no foreign key takes no lock on any existing table.

CREATE TABLE sign_in_address_failures (
    address_hash       text        NOT NULL PRIMARY KEY CHECK (address_hash ~ '^[0-9a-f]{64}$'),
    failed_login_count integer     NOT NULL CHECK (failed_login_count >= 0),
    locked_until       timestamptz,
    last_failed_at     timestamptz NOT NULL
);
-- The nightly purge finds the rows nobody has failed on lately.
CREATE INDEX sign_in_address_failures_by_last_failure ON sign_in_address_failures (last_failed_at);
REVOKE ALL ON sign_in_address_failures FROM PUBLIC;
SELECT bms_grant_app('sign_in_address_failures', 'SELECT, INSERT, UPDATE');

-- The nightly retention (the job core.identity.sign-in-failure-purge): a row with no failure for 7 days and no lock in force
-- is deleted. A definer function, as user_password_resets_purge (V62): the application role has no DELETE grant. It never
-- deletes a row younger than 7 days or one still locked, whatever cutoff it is given.
CREATE FUNCTION sign_in_address_failures_purge(p_before timestamptz) RETURNS integer
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH d AS (
        DELETE FROM sign_in_address_failures
         WHERE last_failed_at < least(p_before, now() - interval '7 days')
           AND (locked_until IS NULL OR locked_until < now())
        RETURNING 1)
    SELECT count(*)::integer FROM d
$$;
REVOKE ALL ON FUNCTION sign_in_address_failures_purge(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION sign_in_address_failures_purge(timestamptz) TO bms_app;
