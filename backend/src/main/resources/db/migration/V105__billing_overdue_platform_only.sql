-- V105: the billing review follow-ups (issue #502, from the review of #482; ADR-075 decision 2).
--
-- platform_mark_subscription_overdue (V92) trusted its caller's p_today: the job passes the business's today in its own
-- time zone, and the function only checked the due date against it. bms_app may execute it, so a caller bound to a
-- business (app.tenant_id set, any request of that business's staff) could pass a later day and mark it past_due early.
-- The function now refuses when app.tenant_id is set, as platform_tenants_cancelling (V92) does: only the platform job,
-- which runs unbound, calls it. Computing today inside from tenants.timezone was the other choice and is not taken: the
-- job's clock is the application's BusinessClock (moved by the integration tests, and the one every other billing date
-- reads), and a database clock here would let the job and the function disagree on the day at midnight.
--
-- It also refuses a business that asked to close (V102, ADR-090): the job already leaves such a business out through
-- platform_tenants_cancelling, and the function now says the same under the row lock, as it does for the Owner's
-- cancellation. Otherwise unchanged: the row lock, the checks of the status and the date, the audit row.
--
-- Function only: no table, column, constraint, policy or row changes, so the previous release runs unchanged on this schema.
-- CREATE OR REPLACE keeps the function's owner and grants; they are stated again below. lock_timeout is five seconds as in
-- V92.
--
-- Flyway runs with outOfOrder off: V104 (issue #353) is merged and V99 stays the unused placeholder, so this is V105.

SET LOCAL lock_timeout = '5s';

CREATE OR REPLACE FUNCTION platform_mark_subscription_overdue(p_tenant_id uuid, p_due_on date, p_today date)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_sub   subscriptions%ROWTYPE;
    v_due   date;
    v_grace integer;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'made overdue by the platform job only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT * INTO v_sub FROM subscriptions WHERE tenant_id = p_tenant_id FOR UPDATE;
    IF NOT FOUND OR v_sub.is_pilot OR v_sub.status NOT IN ('trial', 'active') OR v_sub.next_status_change_on IS NOT NULL THEN
        RETURN false;
    END IF;
    v_due := CASE WHEN v_sub.status = 'trial' THEN v_sub.trial_ends_on ELSE v_sub.current_period_end END;
    IF v_due IS NULL OR v_due IS DISTINCT FROM p_due_on OR v_due >= p_today THEN
        RETURN false;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM tenants t WHERE t.id = p_tenant_id AND t.status = 'active') THEN
        RETURN false;
    END IF;
    -- The Owner's cancellation (V90, ADR-073) takes effect the day after the last day paid for: nothing is owed. The Owner's
    -- cancel takes this same row lock (app_lock_subscription) before it inserts, and each statement here reads after the
    -- lock was granted, so a cancellation that committed while this call waited is seen.
    IF EXISTS (SELECT 1 FROM subscription_scheduled_changes c
                WHERE c.tenant_id = p_tenant_id AND c.kind = 'cancel' AND c.status = 'scheduled') THEN
        RETURN false;
    END IF;
    -- A business that asked to close (V102, ADR-090) is never made overdue.
    IF EXISTS (SELECT 1 FROM tenant_closures k WHERE k.tenant_id = p_tenant_id AND k.status IN ('requested', 'removing')) THEN
        RETURN false;
    END IF;
    SELECT grace_days INTO v_grace FROM platform_billing_settings WHERE id = 1;
    UPDATE subscriptions s
       SET status = 'past_due', updated_by_platform_user_id = NULL, updated_at = now()
     WHERE s.tenant_id = p_tenant_id;
    INSERT INTO platform_audit_log (id, action, tenant_id, data)
    VALUES (gen_random_uuid(), 'platform.tenant.subscription_overdue', p_tenant_id,
            jsonb_build_object('before', v_sub.status, 'after', 'past_due', 'due_on', v_due,
                               'overdue_since', v_due + 1, 'grace_days', v_grace, 'pay_by', v_due + v_grace,
                               'business_date', p_today, 'by', 'billing job'));
    RETURN true;
END
$$;

REVOKE ALL ON FUNCTION platform_mark_subscription_overdue(uuid, date, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_mark_subscription_overdue(uuid, date, date) TO bms_app;
