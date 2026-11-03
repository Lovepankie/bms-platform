-- V130: SMS delivery reports (issue #215, slice B2 of the client extras, ADR-036 decision 7, spec section 4.7).
-- Numbered the highest on main plus one at the time of writing (V129 is the quarantined upload, #128); the merger
-- renumbers at merge time if another lands first, because outOfOrder is off.
--
-- Expand only (chapter 6 section 6.9): one index, one platform table, two new functions and one function body
-- replaced with the same arguments. Nothing is dropped and no column changes.
--
-- Lock note. Flyway runs this file as one transaction. The index on sms_deliveries is built without CONCURRENTLY
-- (a migration transaction cannot), so it holds a SHARE lock on sms_deliveries until commit: the SMS channel's
-- writes of delivery rows wait for the build, reads do not. The table holds at most 13 months of messages (the
-- nightly purge), so the build is seconds; the migrate step runs before the application containers switch
-- (ADR-006). The new table is empty.
--
-- What this migration holds:
--   1. An index to find a delivery row by the provider's message id alone (a report names no tenant).
--   2. sms_report_counters: a platform table of the day's delivery report outcomes for the operator's alerts.
--   3. sms_delivery_report_apply: the definer function that finds the row by provider message id and moves its
--      status forward only.
--   4. The 13 month purge also clears counters older than 400 days.

-- ---------------------------------------------------------------------------------------------
-- 1. A delivery report carries the provider's message id and nothing that names a business, so the row is found
-- across tenants by (provider, provider_message_id). The existing index leads with tenant_id and cannot serve it.
-- ---------------------------------------------------------------------------------------------

CREATE INDEX sms_deliveries_by_provider_message ON sms_deliveries (provider, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 2. The day's counts of report outcomes (moved, repeated, not_forward, unknown_id, bad_token, malformed,
-- throttled...): a word of ours and a number per Kampala day, never a message id, a number or a tenant. A
-- platform table like platform_sms_controls: no grant to bms_app, row-level security forced with no policy,
-- reached only through the definer function below.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE sms_report_counters (
    day     date   NOT NULL,
    outcome text   NOT NULL CHECK (outcome ~ '^[a-z_]{1,40}$'),
    hits    bigint NOT NULL DEFAULT 0 CHECK (hits >= 0),
    PRIMARY KEY (day, outcome)
);
ALTER TABLE sms_report_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE sms_report_counters FORCE ROW LEVEL SECURITY;
REVOKE ALL ON sms_report_counters FROM PUBLIC;

CREATE FUNCTION app_count_sms_report(p_day date, p_outcome text) RETURNS void
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    INSERT INTO sms_report_counters (day, outcome, hits) VALUES (p_day, p_outcome, 1)
    ON CONFLICT (day, outcome) DO UPDATE SET hits = sms_report_counters.hits + 1
$$;

-- ---------------------------------------------------------------------------------------------
-- 3. Applying one delivery report (ADR-036 decision 7). Platform step: a report names no tenant, so the function
-- refuses a transaction bound to one. p_status is ours, already mapped from the provider's word by the adapter:
--   sent         the provider still holds the message (an interim report); never moves a row;
--   delivered    the handset took it;
--   undelivered  the provider gave up (p_reason is the provider's short reason code, or null).
-- Forward only: a row moves from sent to delivered or undelivered once, and a final status never changes again,
-- so a replay, a late interim report or a contradicting final report changes nothing. Answers, for the counters:
--   moved        the row moved;
--   repeated     the row already has this status (a replay);
--   not_forward  the report would move the row back or sideways (a late sent, a final after another final, or a
--                row that was never sent: sunk, failed, rejected, unknown);
--   unknown_id   no row has this id (another environment's message, a forged id, or a report that arrived before
--                the sender recorded the id); ignored;
--   ambiguous_id more than one row has this id; nothing moves.
-- Nothing money-related reads this status (spec 4.14).
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION sms_delivery_report_apply(
    p_provider text, p_message_id text, p_status text, p_reason text, p_now timestamptz)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_rows   integer;
    v_status text;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'delivery reports are applied by the platform only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF p_status NOT IN ('sent', 'delivered', 'undelivered') THEN
        RAISE EXCEPTION 'not a delivery report status: %', p_status USING ERRCODE = 'check_violation';
    END IF;
    SELECT count(*) INTO v_rows
      FROM sms_deliveries d
     WHERE d.provider = p_provider AND d.provider_message_id = p_message_id;
    IF v_rows = 0 THEN
        RETURN 'unknown_id';
    END IF;
    IF v_rows > 1 THEN
        RETURN 'ambiguous_id';
    END IF;
    IF p_status IN ('delivered', 'undelivered') THEN
        -- The row lock makes two reports at once agree: the second re-reads the row after the first commits and
        -- finds it no longer sent.
        UPDATE sms_deliveries d
           SET status = p_status,
               failure_reason = CASE WHEN p_status = 'undelivered' THEN left(p_reason, 100) ELSE NULL END,
               updated_at = p_now
         WHERE d.provider = p_provider AND d.provider_message_id = p_message_id AND d.status = 'sent';
        IF FOUND THEN
            RETURN 'moved';
        END IF;
    END IF;
    SELECT d.status INTO v_status
      FROM sms_deliveries d
     WHERE d.provider = p_provider AND d.provider_message_id = p_message_id;
    RETURN CASE WHEN v_status = p_status THEN 'repeated' ELSE 'not_forward' END;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 4. The nightly purge (body as in V50, plus one statement): the report counters are kept as long as the delivery
-- rows they describe (13 months). Same arguments and result, so the caller is unchanged.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION sms_deliveries_purge(p_before timestamptz)
RETURNS integer
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH d AS (DELETE FROM sms_deliveries WHERE created_at < p_before RETURNING 1),
         c AS (DELETE FROM sms_report_counters WHERE day < p_before::date RETURNING 1)
    SELECT ((SELECT count(*) FROM d) + (SELECT count(*) FROM c))::integer
$$;

-- ---------------------------------------------------------------------------------------------
-- Grants: definer functions only, executable by the application role and no one else (ADR-016).
-- ---------------------------------------------------------------------------------------------

REVOKE ALL ON FUNCTION app_count_sms_report(date, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION sms_delivery_report_apply(text, text, text, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_count_sms_report(date, text) TO bms_app;
GRANT EXECUTE ON FUNCTION sms_delivery_report_apply(text, text, text, text, timestamptz) TO bms_app;
