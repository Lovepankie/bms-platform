-- V84: the arrears notices the nightly run queued (#227, pull request #290) and the cancelled_settled label of a
-- withdrawn reminder, directly after V83 (the collection actions of #266). Additive, except that the outbox failures
-- listing is replaced to leave the new label out, as it leaves out cancelled_reversed.

-- ---------------------------------------------------------------------------------------------
-- lending_loan_notices (chapter 6 section 6.7; FR-NTF-02). One row per arrears notice the run queued
-- (loan.overdue_1, _7 or _30), so a loan is not sent another about a different instalment within
-- seven days (an always-late borrower is not texted many times a month). The message itself lives in
-- the outbox; this is only the loan's record of it. Append-only.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_loan_notices (
    id               uuid        NOT NULL PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenants (id),
    created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    loan_id          uuid        NOT NULL,
    schedule_item_id uuid        NOT NULL,
    template_key     text        NOT NULL CHECK (template_key IN ('loan.overdue_1', 'loan.overdue_7', 'loan.overdue_30')),
    business_date    date        NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    FOREIGN KEY (tenant_id, schedule_item_id) REFERENCES lending_schedule_items (tenant_id, id)
);
CREATE INDEX lending_loan_notices_loan ON lending_loan_notices (tenant_id, loan_id, business_date);
SELECT bms_apply_tenant_rls('lending_loan_notices');
SELECT bms_grant_app('lending_loan_notices', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_loan_notices');

-- ---------------------------------------------------------------------------------------------
-- The review of #290: a reminder withdrawn because the member paid the instalment, or the loan was
-- closed or written off, is labelled cancelled_settled, not cancelled_reversed, so the SMS incident
-- runbook reads it right. The two-argument function of V53 is unchanged (a reversal); this one takes
-- the label. Neither label is offered as a failure to try again.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION notification_outbox_cancel_unclaimed(p_event_ref text, p_now timestamptz, p_reason text)
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid := nullif(current_setting('app.tenant_id', true), '')::uuid;
    v_count  integer;
BEGIN
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'cancelling an SMS row needs a bound tenant' USING ERRCODE = 'check_violation';
    END IF;
    IF p_reason IS NULL OR p_reason NOT IN ('cancelled_reversed', 'cancelled_settled') THEN
        RAISE EXCEPTION 'not a cancel reason: %', p_reason USING ERRCODE = 'check_violation';
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
       SET status = 'failed', failed_at = p_now, updated_at = p_now, last_error = p_reason, params = '{}',
           leased_until = NULL
      FROM c WHERE o.id = c.id;
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END
$$;

REVOKE ALL ON FUNCTION notification_outbox_cancel_unclaimed(text, timestamptz, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notification_outbox_cancel_unclaimed(text, timestamptz, text) TO bms_app;

CREATE OR REPLACE FUNCTION notification_outbox_failures(p_limit integer)
RETURNS TABLE (id uuid, channel text, recipient text, template_key text, attempts integer, last_error text,
               created_at timestamptz, failed_at timestamptz, tenant_id uuid, category text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT o.id, o.channel, o.recipient::text, o.template_key::text, o.attempts, o.last_error::text, o.created_at,
           o.failed_at, o.tenant_id, o.category
      FROM notification_outbox o
     WHERE o.status = 'failed' AND o.last_error IS DISTINCT FROM 'cancelled_reversed'
       AND o.last_error IS DISTINCT FROM 'cancelled_settled'
     ORDER BY o.failed_at DESC, o.id
     LIMIT p_limit
$$;
