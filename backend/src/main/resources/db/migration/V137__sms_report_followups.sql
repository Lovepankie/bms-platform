-- V137: follow-ups of the SMS delivery reports (issue #808, from the review of #806; ADR-036 decision 7).
--
-- V137 is the highest number on main plus one (AGENTS.md: Flyway runs with outOfOrder off; V136 is the Idempotency-Key
-- lookup of #852, merged first); the merger renumbers at merge time if the order changes. V130 itself is not edited:
-- Flyway checksums every applied file, so a change to it would stop every deploy. What V130 got wrong is put right here.
--
-- Expand only (chapter 6 section 6.9): two function bodies replaced with the same arguments and result type. No table,
-- column, row or grant changes; CREATE OR REPLACE keeps each function's owner and grants (ADR-016).
--
-- Lock note. Replacing a function body takes no lock on any table. lock_timeout bounds the wait for the catalogue row of a
-- function a running statement is using: past 10 seconds the migration fails with a lock timeout and changes nothing, and
-- the deploy is simply run again.
--
-- What this migration holds:
--   1. The nightly purge answers the delivery rows it deleted, as V50's did. V130 made it answer delivery rows plus
--      counter rows, so the nightly figure grew by about a dozen counter rows a day once counters were 13 months old.
--      It still clears the report counters with the delivery rows.
--   2. app_count_sms_report refuses a transaction bound to a business, as sms_delivery_report_apply does: the counters are
--      the platform's, written only while a report is applied, never from a business's request.
--
-- A correction of V130's header. Its item 4 says the purge "clears counters older than 400 days". The code it ships
-- clears counters of a day before the purge's p_before, which the caller sets at 13 months (OutboxDispatcher,
-- DELIVERY_RETENTION_MONTHS), the retention chapter 6 and ADR-036 describe. The code is right and is kept below; the
-- header was wrong.

SET LOCAL lock_timeout = '10s';

-- ---------------------------------------------------------------------------------------------
-- 1. The purge: same arguments and result type; the answer is the delivery rows deleted only.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION sms_deliveries_purge(p_before timestamptz)
RETURNS integer
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH d AS (DELETE FROM sms_deliveries WHERE created_at < p_before RETURNING 1),
         c AS (DELETE FROM sms_report_counters WHERE day < p_before::date RETURNING 1)
    -- c runs to completion although its count is not read: a data-modifying WITH always runs in PostgreSQL.
    SELECT (SELECT count(*) FROM d)::integer
$$;

-- ---------------------------------------------------------------------------------------------
-- 2. The counter: refuses a business's transaction, then counts as V130 did.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION app_count_sms_report(p_day date, p_outcome text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'delivery report counters are written by the platform only'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    INSERT INTO sms_report_counters (day, outcome, hits) VALUES (p_day, p_outcome, 1)
    ON CONFLICT (day, outcome) DO UPDATE SET hits = sms_report_counters.hits + 1;
END
$$;
