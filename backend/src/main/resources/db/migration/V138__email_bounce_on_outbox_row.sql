-- V138: a bounce or a complaint from Resend marks its own outbox row (issue #767, ADR-117 decision 8 and its note of
-- 6 Nov 2026).
--
-- V138 is the highest number on main plus one (AGENTS.md: Flyway runs with outOfOrder off; V137 is the SMS delivery
-- report follow-ups of #808, merged first). The merger renumbers at merge time if the order changes.
--
-- Expand only (chapter 6 section 6.9): one new definer function and its grant. No table, column, index, row or other
-- grant changes; no new column (ADR-117 decision 8: the row's existing status and last_error hold the outcome).
--
-- Lock note. Creating a function takes no lock on any table, so lock_timeout only bounds the wait for the catalogue: past
-- 10 seconds the migration fails with a lock timeout and changes nothing, and the deploy is simply run again.
--
-- What it holds: notification_outbox_mark_undelivered, which the Resend webhook calls once the event's Svix signature
-- has checked and its env tag is this environment's. The application role cannot update notification_outbox (row-level
-- security forced, no policy, no grant), and notification_outbox_record_result touches only a pending row, so a sent
-- row that later bounced needs a function of its own. Platform step: an event names no session's business, so the
-- function refuses a transaction bound to one. The row is found by its id AND its tenant, both from the tags the sender
-- put on the email; a platform row has tenant_id null and matches only a null p_tenant_id, so an event whose tags name
-- one business can never mark another business's row (IS NOT DISTINCT FROM). Only an email row moves, and only from
-- sent: to failed, failed_at the caller's time, last_error 'bounced' or 'complained'. sent_at is kept (it was sent;
-- the mailbox refused it or the reader complained), so the row shows on "Messages not sent". A row with no parameters
-- cannot be sent again from there (notification_outbox_retry answers too_old), which is right: its link was cleared.
-- Answers, for the log line:
--   marked       the row moved from sent to failed;
--   repeated     the row is already failed with this reason (a replay with a new message id);
--   not_sent     the row is pending or failed for another reason (an earlier bounce and then a complaint, a refusal):
--                nothing changes, the first outcome stays;
--   unknown_id   no email row of this id and tenant (purged after 30 days, another environment's id, or a tag that
--                names another business); nothing changes.

SET LOCAL lock_timeout = '10s';

CREATE FUNCTION notification_outbox_mark_undelivered(
    p_id uuid, p_tenant_id uuid, p_reason text, p_now timestamptz)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_status text;
    v_error  text;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'bounces and complaints are applied by the platform only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF p_reason IS NULL OR p_reason NOT IN ('bounced', 'complained') THEN
        RAISE EXCEPTION 'not a bounce or complaint reason: %', p_reason USING ERRCODE = 'check_violation';
    END IF;
    -- The row lock makes two events at once agree: the second re-reads the row after the first commits.
    SELECT o.status, o.last_error INTO v_status, v_error
      FROM notification_outbox o
     WHERE o.id = p_id AND o.channel = 'email' AND o.tenant_id IS NOT DISTINCT FROM p_tenant_id
       FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'unknown_id';
    END IF;
    IF v_status = 'sent' THEN
        UPDATE notification_outbox o
           SET status = 'failed', failed_at = p_now, last_error = p_reason, updated_at = p_now
         WHERE o.id = p_id;
        RETURN 'marked';
    END IF;
    RETURN CASE WHEN v_status = 'failed' AND v_error = p_reason THEN 'repeated' ELSE 'not_sent' END;
END
$$;

-- Grants: a definer function only, executable by the application role and no one else (ADR-016).
REVOKE ALL ON FUNCTION notification_outbox_mark_undelivered(uuid, uuid, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notification_outbox_mark_undelivered(uuid, uuid, text, timestamptz) TO bms_app;
