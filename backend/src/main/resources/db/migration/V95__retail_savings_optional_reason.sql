-- V95: the reason for changing the suggested savings amount is optional (issue #538, ADR-080).
--
-- Expand only, safe on a populated database: one check of retail_daily_savings is replaced. V34 made an
-- overwritten savings record carry a reason of at least 5 characters (retail_daily_savings_check1, found
-- below by its definition). Owners asked for fewer typed reasons, so the form now offers a short pick
-- list or nothing; the server keeps the reason when one is given. A reason that is sent on an overwritten record must still say something: not
-- blank. No other
-- screen's reason or check changes here (ADR-080 decision 6).
--
-- Flyway runs this file in one transaction; SET LOCAL lock_timeout makes it fail after 5 seconds, to be
-- retried, rather than queue the savings screen behind a long report.
--
-- Flyway runs with outOfOrder off: V95 is the next number after V94 (average cost, #529, merged). The
-- payments pull requests (#315 and #318) take the numbers after it.

SET LOCAL lock_timeout = '5s';

-- The V34 check has no name of its own (PostgreSQL called it retail_daily_savings_check1); find it by what it says, so
-- the overwritten and suggested_minor consistency check next to it can never be the one dropped.
DO $$
DECLARE
    v_name text;
BEGIN
    SELECT conname INTO STRICT v_name
      FROM pg_constraint
     WHERE conrelid = 'retail_daily_savings'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%overwrite_reason%';
    EXECUTE format('ALTER TABLE retail_daily_savings DROP CONSTRAINT %I', v_name);
END
$$;
-- Like the V34 check it replaces, it constrains overwritten rows only, so it is strictly weaker: every row that passed
-- the old check passes this one, and adding it cannot fail on existing data (an imported or historical row that is not
-- overwritten may hold any reason, as before).
ALTER TABLE retail_daily_savings
    ADD CONSTRAINT retail_daily_savings_overwrite_reason_chk
        CHECK (NOT overwritten OR overwrite_reason IS NULL OR btrim(overwrite_reason) <> '');
