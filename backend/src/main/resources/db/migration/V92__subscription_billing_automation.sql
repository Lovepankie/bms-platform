-- V92: subscription billing automation (issue #480, ADR-075): the reminders to the Owner of a business before the paid-until
-- date or the end of the free month, the move to past_due (shown as overdue) the day after, with a grace period the operator
-- sets, the operator's list of businesses due for suspension, a numbered receipt for every subscription payment, and the
-- Owner's Plan and billing page.
--
-- V92 is the number this work was given in the agreed merge order (AGENTS.md, the migration ledger): main ends at V86 and
-- V87 to V91 are claimed by open pull requests (#470, #467, #475, #474, #478). Flyway runs with outOfOrder off,
-- so this file is renamed at merge if those numbers land after it.
--
-- Expand only, safe on a populated database. Nothing is ever suspended by this migration or by anything it adds: after the
-- grace period a business only appears on the operator's list, and the operator suspends by hand with the existing route.
--
-- Locks, said plainly. Flyway runs this file in one transaction. The new tables take SHARE ROW EXCLUSIVE on tenants,
-- platform_users and subscription_payments (their foreign keys) until the commit; no existing table is altered or rewritten.
-- The receipt backfill reads subscription_payments, a small table. lock_timeout is five seconds as in V75 and V80.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- 1. platform_billing_settings: one row. grace_days is how long a business stays overdue and working before it appears on
--    the operator's list (0 to 60, 7 by default, issue #480); how_to_pay is the operator's own words on paying (a mobile
--    money number, a bank account), shown in every reminder and on the Owner's page. Payment details are configuration the
--    operator types, never repository content: the row starts empty. Changed only through platform_set_billing_settings.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE platform_billing_settings (
    id                          smallint     NOT NULL PRIMARY KEY CHECK (id = 1),
    grace_days                  integer      NOT NULL DEFAULT 7 CHECK (grace_days BETWEEN 0 AND 60),
    how_to_pay                  varchar(1000),
    updated_at                  timestamptz,
    updated_by_platform_user_id uuid         REFERENCES platform_users (id)
);
INSERT INTO platform_billing_settings (id, grace_days) VALUES (1, 7);
GRANT SELECT ON platform_billing_settings TO bms_app;

-- Sets both values; returns the previous ones. The service checks the bounds and that something changed.
CREATE FUNCTION platform_set_billing_settings(p_grace_days integer, p_how_to_pay text, p_platform_user_id uuid)
RETURNS TABLE (previous_grace_days integer, previous_how_to_pay text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_row platform_billing_settings%ROWTYPE;
BEGIN
    SELECT * INTO v_row FROM platform_billing_settings WHERE id = 1 FOR UPDATE;
    UPDATE platform_billing_settings
       SET grace_days = p_grace_days, how_to_pay = p_how_to_pay,
           updated_at = now(), updated_by_platform_user_id = p_platform_user_id
     WHERE id = 1;
    previous_grace_days := v_row.grace_days;
    previous_how_to_pay := v_row.how_to_pay;
    RETURN NEXT;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 2. subscription_reminders: one row per business, kind and due date once its reminder was queued, written in the same
--    transaction as the outbox rows, so a reminder is sent once whatever the job does (the unique key). due_on is the last
--    day paid for, or the last day of the free month for the trial kinds. channels says what was queued (empty when the
--    business has no Owner to write to). Append-only.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE subscription_reminders (
    id           uuid        NOT NULL PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants (id),
    kind         text        NOT NULL CHECK (kind IN ('due_in_7', 'due_in_1', 'due_today',
                                                      'trial_in_7', 'trial_in_1', 'trial_today')),
    due_on       date        NOT NULL,
    amount_minor bigint      CHECK (amount_minor BETWEEN 0 AND 50000000),
    currency     char(3)     REFERENCES currencies (code),
    channels     text[]      NOT NULL DEFAULT '{}',
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, kind, due_on),
    CHECK ((amount_minor IS NULL) = (currency IS NULL))
);
SELECT bms_apply_tenant_rls('subscription_reminders');
SELECT bms_grant_app('subscription_reminders', 'SELECT, INSERT');
SELECT bms_make_append_only('subscription_reminders');

-- ---------------------------------------------------------------------------------------------
-- 3. subscription_receipts: the receipt of a subscription payment, one per payment, numbered across the platform
--    (RT-<year>-<six digits>, from one sequence; a number is never reused, a gap is a rolled back payment). It keeps what
--    the receipt shows that could change later (the business's name, the plan's name), so a receipt opened a year later
--    reads as it did on the day (ADR-034). Written in the payment's own transaction. Append-only.
-- ---------------------------------------------------------------------------------------------

CREATE SEQUENCE subscription_receipt_numbers;
GRANT USAGE ON SEQUENCE subscription_receipt_numbers TO bms_app;

CREATE TABLE subscription_receipts (
    id            uuid         NOT NULL PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    payment_id    uuid         NOT NULL,
    receipt_no    varchar(20)  NOT NULL UNIQUE CHECK (receipt_no ~ '^RT-[0-9]{4}-[0-9]{6,}$'),
    business_name varchar(200) NOT NULL,
    plan_name     varchar(200) NOT NULL,
    issued_on     date         NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, payment_id),
    FOREIGN KEY (tenant_id, payment_id) REFERENCES subscription_payments (tenant_id, id)
);
SELECT bms_apply_tenant_rls('subscription_receipts');
SELECT bms_grant_app('subscription_receipts', 'SELECT, INSERT');
SELECT bms_make_append_only('subscription_receipts');

-- Every payment recorded before this migration gets its receipt now, numbered in the order the payments were recorded and
-- dated the day each was recorded in its business's time zone. No email is sent for them. The numbers come from
-- row_number() over that order (nextval in a SELECT need not follow its ORDER BY); the sequence then continues after them.
INSERT INTO subscription_receipts (id, tenant_id, payment_id, receipt_no, business_name, plan_name, issued_on, created_at)
SELECT gen_random_uuid(), x.tenant_id, x.id,
       'RT-' || to_char(x.issued_on, 'YYYY') || '-' || lpad(x.n::text, 6, '0'),
       x.business_name, x.plan_name, x.issued_on, x.created_at
  FROM (SELECT sp.tenant_id, sp.id, sp.created_at, left(t.name, 200) AS business_name, left(p.name, 200) AS plan_name,
               (sp.created_at AT TIME ZONE t.timezone)::date AS issued_on,
               row_number() OVER (ORDER BY sp.created_at, sp.id) AS n
          FROM subscription_payments sp
          JOIN tenants t ON t.id = sp.tenant_id
          JOIN plans p ON p.id = sp.plan_id
         WHERE sp.status = 'confirmed') x;
SELECT setval('subscription_receipt_numbers', count(*)) FROM subscription_receipts HAVING count(*) > 0;

-- ---------------------------------------------------------------------------------------------
-- 4. The one automatic status change (issue #480 point 2): a business in its free month or paid, not a pilot, whose last
--    day (of the free month, or paid for) is before its own today becomes past_due, which the screens call overdue. Its
--    tenant stays active: past_due changes nothing the business can do. Nothing here or anywhere else suspends. The job
--    passes the business's today in its own time zone and the due date it saw; the function checks both again under the
--    row lock, so a payment that landed meanwhile wins. A subscription with a status change scheduled by the operator (an
--    end chosen for the end of the period) is left alone and keeps its date. Audited with no operator. Returns true when
--    it moved the status.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION platform_mark_subscription_overdue(p_tenant_id uuid, p_due_on date, p_today date)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_sub   subscriptions%ROWTYPE;
    v_due   date;
    v_grace integer;
BEGIN
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

-- ---------------------------------------------------------------------------------------------
-- 5. The permission behind the Owner's Plan and billing page: read only, held by the administrators (the Owner is one,
--    ADR-062). Payments are still recorded by the operator only.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('core.billing.read', 'core', 'Plan and billing: see the plan, its price, what is paid, the payments and their receipts', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'core.billing.read')
ON CONFLICT DO NOTHING;

REVOKE ALL ON FUNCTION platform_set_billing_settings(integer, text, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_mark_subscription_overdue(uuid, date, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_set_billing_settings(integer, text, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_mark_subscription_overdue(uuid, date, date) TO bms_app;
