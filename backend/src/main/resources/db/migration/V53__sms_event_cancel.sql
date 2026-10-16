-- Issue #221 (ADR-036 third amendment): the member event SMS.
--
--   1. A reversal withdraws the member SMS of the transaction it reverses while that message is still waiting in the outbox
--      and no sender holds it. A receipt for an entry made on the wrong loan or account would otherwise tell the wrong
--      person about it. To know whether a sender holds a row, the claim records its lease (`leased_until`).
--   2. An investment remembers whether its investor is to be texted (`notify_member`), so a register imported with
--      notify_member false (or by the seed or the import port) never gets a maturity reminder later.
--
-- Expand only (chapter 6 section 6.9): two nullable or defaulted columns, one new function and three replaced function
-- bodies with their signatures unchanged. Nothing is dropped or renamed, and the previous release's code runs against this
-- schema (it ignores both columns).
--
-- Lock notes (honest). Flyway runs this file in one transaction. SET LOCAL lock_timeout bounds any wait for a lock to 5
-- seconds, so a busy system fails this migration cleanly (and it is run again) instead of queueing behind long
-- transactions. The statements are ordered so that the hot table is locked last and the shortest time: every lock taken
-- here is held until commit.
--   * ALTER TABLE lending_investments ADD COLUMN notify_member boolean NOT NULL DEFAULT true takes ACCESS EXCLUSIVE on the
--     investments table (small: hundreds of rows a tenant, read by the staff screens and the nightly job) until commit. A
--     constant default is stored in the catalogue (PostgreSQL 11 and later), so no row is rewritten by the ALTER. The
--     backfill UPDATE that follows reads the table once and rewrites only the rows it changes.
--   * ALTER TABLE notification_outbox ADD COLUMN leased_until (nullable, no default) is the last table statement: it takes
--     ACCESS EXCLUSIVE on the outbox, rewrites nothing, and the lock is held only for the function statements after it (no
--     table work). The outbox is a small, hot table (rows are deleted 30 days after sending); the sender job and the enqueue
--     wait for those milliseconds.
--   * CREATE FUNCTION and CREATE OR REPLACE FUNCTION take no lock on any table.
SET LOCAL lock_timeout = '5s';

-- The small table first.
ALTER TABLE lending_investments ADD COLUMN notify_member boolean NOT NULL DEFAULT true;

-- Backfill: an investment that exists before this release was recorded without a text in mind, so its investor must not
-- get a maturity reminder the day SMS is switched on. False for any investment whose funding did not come from a staff
-- request (the seed, an import, the portal or the gateway) and for any funded more than 3 days ago on the tenant's own
-- calendar (the default of BMS_SMS_EVENT_MAX_AGE_DAYS: the same cutoff that stops an old event being texted). An
-- investment still waiting for its funding keeps true, as does one funded in the last 3 days by staff.
UPDATE lending_investments i
   SET notify_member = false
 WHERE i.start_date IS NOT NULL
   AND (EXISTS (SELECT 1 FROM lending_investment_transactions x
                 WHERE x.tenant_id = i.tenant_id AND x.investment_id = i.id AND x.txn_type = 'funding'
                   AND x.source <> 'staff')
        OR i.start_date < (now() AT TIME ZONE coalesce((SELECT t.timezone FROM tenants t WHERE t.id = i.tenant_id),
                                                       'Africa/Kampala'))::date - 3);

-- The hot table last.
ALTER TABLE notification_outbox ADD COLUMN leased_until timestamptz;

-- ---------------------------------------------------------------------------------------------
-- 1. The claim records its lease. Body as in V50 plus one assignment: leased_until = the database's clock now plus the length of
--    the lease the sender asked for (p_lease_until - p_now), so that the lease and the cancel's comparison use one clock, the
--    database's, whatever the application servers' clocks say. A row is "held by a sender" while leased_until is in the future; a row waiting to be retried (a clean failure recorded, pending again
--    with a retry time) is not held, and neither is one whose sender died and whose lease ran out.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION notification_outbox_claim(
    p_channels text[], p_max_attempts integer, p_now timestamptz, p_lease_until timestamptz)
RETURNS TABLE (id uuid, channel text, recipient text, template_key text, params jsonb, idempotency_key text,
               attempts integer, tenant_id uuid, category text, subject_type text, subject_id uuid,
               created_at timestamptz, expires_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE notification_outbox d
       SET status = 'failed', failed_at = p_now, updated_at = p_now,
           last_error = CASE WHEN d.expires_at IS NOT NULL AND d.expires_at <= p_now THEN 'expired'
                             ELSE coalesce(d.last_error, 'attempts used up') END
     WHERE d.status = 'pending' AND d.next_attempt_at <= p_now AND d.channel = ANY (p_channels)
       AND (d.attempts >= p_max_attempts OR (d.expires_at IS NOT NULL AND d.expires_at <= p_now));
    RETURN QUERY
    WITH recent AS (
        SELECT r.tenant_id AS rtenant, count(*) AS given
          FROM notification_outbox r
         WHERE r.updated_at >= p_now - interval '1 minute' AND r.attempts > 0 AND r.channel = ANY (p_channels)
         GROUP BY r.tenant_id
    ), due AS (
        SELECT c.id AS cid, (c.category <> 'transactional') AS low_priority, c.next_attempt_at AS due_at,
               row_number() OVER (PARTITION BY c.tenant_id, (c.category <> 'transactional')
                                  ORDER BY c.next_attempt_at, c.id) - 1 + coalesce(rc.given, 0) AS turn
          FROM notification_outbox c LEFT JOIN recent rc ON rc.rtenant IS NOT DISTINCT FROM c.tenant_id
         WHERE c.status = 'pending' AND c.next_attempt_at <= p_now AND c.channel = ANY (p_channels)
           AND c.attempts < p_max_attempts
    ), candidates AS (
        SELECT due.cid, row_number() OVER (ORDER BY due.low_priority, due.turn, due.due_at, due.cid) AS pos
          FROM due
         ORDER BY due.low_priority, due.turn, due.due_at, due.cid
         LIMIT 20
    ), picked AS (
        SELECT x.id AS pid
          FROM candidates k JOIN notification_outbox x ON x.id = k.cid
         -- PostgreSQL re-evaluates this on the locked row if another sender claimed and committed it
         -- first: by then its lease has moved next_attempt_at into the future, so it is no longer due.
         WHERE x.status = 'pending' AND x.next_attempt_at <= p_now AND x.attempts < p_max_attempts
           AND x.channel = ANY (p_channels)
         ORDER BY k.pos
         LIMIT 1
           FOR UPDATE OF x SKIP LOCKED
    )
    UPDATE notification_outbox o
       SET attempts = o.attempts + 1, next_attempt_at = p_lease_until,
           leased_until = clock_timestamp() + (p_lease_until - p_now), updated_at = p_now
      FROM picked
     WHERE o.id = picked.pid
    RETURNING o.id, o.channel, o.recipient::text, o.template_key::text, o.params, o.idempotency_key::text, o.attempts,
              o.tenant_id, o.category, o.subject_type, o.subject_id, o.created_at, o.expires_at;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 2. Recording a result releases the lease. Body as in V50 plus `leased_until = NULL`.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION notification_outbox_record_result(
    p_id uuid, p_outcome text, p_error text, p_max_attempts integer, p_retry_at timestamptz, p_now timestamptz,
    p_expires_at timestamptz DEFAULT NULL)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_status text;
BEGIN
    IF p_outcome NOT IN ('sent', 'failed', 'deferred', 'unknown', 'refused') THEN
        RAISE EXCEPTION 'not an outcome: %', p_outcome USING ERRCODE = 'check_violation';
    END IF;
    UPDATE notification_outbox o
       SET status = CASE p_outcome
                        WHEN 'sent' THEN 'sent'
                        WHEN 'deferred' THEN 'pending'
                        WHEN 'unknown' THEN 'failed'
                        WHEN 'refused' THEN 'failed'
                        ELSE CASE WHEN o.attempts >= p_max_attempts THEN 'failed' ELSE 'pending' END
                    END,
           attempts = CASE WHEN p_outcome = 'deferred' THEN greatest(o.attempts - 1, 0) ELSE o.attempts END,
           sent_at = CASE WHEN p_outcome = 'sent' THEN p_now ELSE NULL END,
           failed_at = CASE WHEN p_outcome IN ('unknown', 'refused')
                                 OR (p_outcome = 'failed' AND o.attempts >= p_max_attempts) THEN p_now ELSE NULL END,
           params = CASE WHEN p_outcome IN ('sent', 'refused') THEN '{}'::jsonb ELSE o.params END,
           last_error = CASE p_outcome
                            WHEN 'sent' THEN NULL
                            WHEN 'deferred' THEN o.last_error
                            WHEN 'unknown' THEN 'unknown_outcome'
                            ELSE left(p_error, 300)
                        END,
           next_attempt_at = CASE WHEN p_outcome IN ('failed', 'deferred') THEN p_retry_at ELSE o.next_attempt_at END,
           -- A deferral may name a later expiry (a reminder over its tenant's cap waits for tomorrow's window and
           -- must outlive today's): it only ever moves the expiry later, and at most 2 days after the row was queued.
           expires_at = CASE WHEN p_outcome = 'deferred' AND p_expires_at IS NOT NULL AND o.expires_at IS NOT NULL
                                  AND p_expires_at > o.expires_at
                             THEN greatest(o.expires_at, least(p_expires_at, o.created_at + interval '2 days'))
                             ELSE o.expires_at END,
           leased_until = NULL,
           updated_at = p_now
     WHERE o.id = p_id AND o.status = 'pending'
    RETURNING o.status INTO v_status;
    RETURN v_status;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 3. Cancel the SMS rows of one event of the bound tenant that no sender holds. A row key is
--    sms:<template>:<tenant>:<event reference>:<member>, so the event reference is the fourth part. A pending row is
--    cancelled whatever its attempt count: one that failed once and waits to be retried would otherwise still go out,
--    and a receipt lives only an hour, which is also how long a cashier takes to notice a wrong entry. A row under a
--    live lease (leased_until in the future) is held by a sender that may be at the provider with it, and is left;
--    FOR UPDATE SKIP LOCKED also skips a row being claimed at this moment. The row is marked failed 'cancelled_reversed'
--    and its parameters are cleared. The tenant comes from the bound tenant, never from a parameter, so no tenant can
--    cancel another's row. Returns the number of rows cancelled.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION notification_outbox_cancel_unclaimed(p_event_ref text, p_now timestamptz)
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid := nullif(current_setting('app.tenant_id', true), '')::uuid;
    v_count  integer;
BEGIN
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'cancelling an SMS row needs a bound tenant' USING ERRCODE = 'check_violation';
    END IF;
    WITH c AS (
        SELECT x.id FROM notification_outbox x
         WHERE x.channel = 'sms' AND x.tenant_id = v_tenant AND x.status = 'pending'
           AND (x.leased_until IS NULL OR x.leased_until <= clock_timestamp())
           AND split_part(x.idempotency_key, ':', 1) = 'sms'
           AND split_part(x.idempotency_key, ':', 3) = v_tenant::text
           AND split_part(x.idempotency_key, ':', 4) = p_event_ref
         ORDER BY x.id
           FOR UPDATE SKIP LOCKED)
    UPDATE notification_outbox o
       SET status = 'failed', failed_at = p_now, updated_at = p_now, last_error = 'cancelled_reversed', params = '{}',
           leased_until = NULL
      FROM c WHERE o.id = c.id;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END
$$;

REVOKE ALL ON FUNCTION notification_outbox_cancel_unclaimed(text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notification_outbox_cancel_unclaimed(text, timestamptz) TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- 4. Whether a row is still waiting to be sent. The SMS channel asks immediately before it calls the provider: a send that
--    has run past its lease can have been cancelled in the meantime by a reversal, and must then not go out. Answers only
--    true or false for an id; the channel's own row.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION notification_outbox_is_pending(p_id uuid)
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM notification_outbox o WHERE o.id = p_id AND o.status = 'pending')
$$;

REVOKE ALL ON FUNCTION notification_outbox_is_pending(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notification_outbox_is_pending(uuid) TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- 5. The operator's list of failed messages does not show a cancelled one: it was withdrawn on purpose, and "send
--    again" must never be offered for a message about a reversed entry. Same signature and columns as V50.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION notification_outbox_failures(p_limit integer)
RETURNS TABLE (id uuid, channel text, recipient text, template_key text, attempts integer, last_error text,
               created_at timestamptz, failed_at timestamptz, tenant_id uuid, category text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT o.id, o.channel, o.recipient::text, o.template_key::text, o.attempts, o.last_error::text, o.created_at,
           o.failed_at, o.tenant_id, o.category
      FROM notification_outbox o
     WHERE o.status = 'failed' AND o.last_error IS DISTINCT FROM 'cancelled_reversed'
     ORDER BY o.failed_at DESC, o.id
     LIMIT p_limit
$$;
