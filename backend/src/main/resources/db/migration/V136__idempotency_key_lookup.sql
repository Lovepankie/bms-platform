-- V136: an Idempotency-Key is read across the people of a business, so a replay answers only the person who stored it
-- (issue #852, ADR-026 decision 5).
--
-- V136 is the highest number on main plus one (AGENTS.md: Flyway runs with outOfOrder off; V135 is the staff email
-- spaces of #823, merged first); the merger renumbers at merge time if the order changes.
--
-- Every stored row already names its person: principal_id is NOT NULL and part of the primary key since V1, so nothing
-- is filled in and no row changes. What changes is the read: JdbcIdempotency now looks a key up in the whole business,
-- under a transaction lock on the business and the key, and refuses a key another person stored (422
-- idempotency_key_other_user) without reading their answer. The primary key (tenant_id, principal_id, key) cannot serve
-- that read, so this index does.
--
-- Not unique: rows stored before this migration may hold one key under two people (each request ran its own work, as
-- the old protocol allowed). They stay as they are and stay safe: each of those people still replays only their own
-- row, and anyone else is refused. Keys are kept 7 days (CoreJobs), so such rows are gone a week after the deploy.
--
-- A plain CREATE INDEX inside Flyway's transaction, not CONCURRENTLY. CONCURRENTLY waits for every open transaction to
-- end, and Flyway holds its migration lock in an open transaction on another connection, so the build waited on Flyway
-- forever (the CI run of fd91332 stopped here for over an hour; a deploy would too). The table holds 7 days of keys, so
-- the build takes moments; while it runs, a keyed write waits on its SHARE lock like any request waits on a key held in
-- progress. lock_timeout bounds the wait for the lock itself: if a long transaction holds the table, the migration fails
-- after 10 seconds with a lock timeout and changes nothing, and the deploy is simply run again.

SET LOCAL lock_timeout = '10s';

CREATE INDEX IF NOT EXISTS idempotency_keys_tenant_key ON idempotency_keys (tenant_id, key);
