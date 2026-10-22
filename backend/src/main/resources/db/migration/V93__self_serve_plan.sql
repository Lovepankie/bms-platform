-- V93: owners change their own plan (issue #472, ADR-073): ask for a module, stop using one, and cancel the subscription,
-- each change effective at the end of the paid period, and the business read only after a cancellation's end date.
--
-- V93 is the number this work claimed: V80 to V84 are merged and V85 to V91 are claimed by open pull requests (AGENTS.md, the migration ledger).
-- Flyway runs with outOfOrder off, so this file is renamed at merge if those numbers land after it.
--
-- Expand only, safe on a populated database. Two new tenant tables, subscription_module_requests (the Owner asks for a
-- module; the operator approves or declines) and subscription_scheduled_changes (a module stopped or the subscription
-- cancelled, from a date), one new column subscriptions.ended_on, and the SECURITY DEFINER functions the operator and the
-- nightly job act through (ADR-016). Nothing existing is backfilled.
--
-- Locks, said plainly. Flyway runs this file in one transaction. CREATE TABLE with foreign keys takes SHARE ROW EXCLUSIVE on
-- tenants, users and platform_users until the commit; ALTER TABLE subscriptions ADD COLUMN (nullable, no default) takes
-- ACCESS EXCLUSIVE on subscriptions for a moment and rewrites nothing. lock_timeout is five seconds as in V26 and V80.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- 1. subscription_module_requests. The Owner asks for a module the business does not use. A module the plan already
--    includes is approved at once by the system (decided_automatically, no operator); any other waits for the operator,
--    who approves (possibly moving the plan first) or declines with a reason. The row is a record of the request only:
--    approving it here switches nothing on; the module is switched on by platform_set_tenant_modules (ADR-016), which
--    checks the plan, so a row written by hand grants nothing. One pending request per module.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE subscription_module_requests (
    id                          uuid         NOT NULL PRIMARY KEY,
    tenant_id                   uuid         NOT NULL REFERENCES tenants (id),
    module_key                  text         NOT NULL CHECK (module_key ~ '^[a-z]{2,30}$'),
    status                      text         NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'approved', 'declined')),
    requested_by                uuid         NOT NULL,
    created_at                  timestamptz  NOT NULL DEFAULT now(),
    decided_at                  timestamptz,
    decided_by_platform_user_id uuid         REFERENCES platform_users (id),
    decided_automatically       boolean      NOT NULL DEFAULT false,
    decision_reason             varchar(300),
    plan_before                 text,
    plan_after                  text,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, requested_by) REFERENCES users (tenant_id, id),
    CHECK ((status = 'pending') = (decided_at IS NULL)),
    CHECK (status <> 'declined' OR decision_reason IS NOT NULL)
);
CREATE UNIQUE INDEX subscription_module_requests_one_pending
    ON subscription_module_requests (tenant_id, module_key) WHERE status = 'pending';
CREATE INDEX subscription_module_requests_by_tenant ON subscription_module_requests (tenant_id, created_at DESC);
CREATE INDEX subscription_module_requests_pending ON subscription_module_requests (created_at) WHERE status = 'pending';
SELECT bms_apply_tenant_rls('subscription_module_requests');
SELECT bms_grant_app('subscription_module_requests', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- 2. subscription_scheduled_changes. A module stopped (stop_module) or the whole subscription cancelled (cancel), in force
--    from effective_on: the day after the last day paid for, or the day it was asked for when nothing is paid ahead (a free
--    month, a pilot, a late business), when it is applied at once. The nightly job applies a scheduled row on its day, once:
--    the row moves to applied in the same transaction as the change. The Owner may undo a scheduled row before its day.
--    One scheduled row per module and one scheduled cancellation per business.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE subscription_scheduled_changes (
    id           uuid        NOT NULL PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants (id),
    kind         text        NOT NULL CHECK (kind IN ('stop_module', 'cancel')),
    module_key   text        CHECK (module_key ~ '^[a-z]{2,30}$'),
    effective_on date        NOT NULL,
    status       text        NOT NULL DEFAULT 'scheduled' CHECK (status IN ('scheduled', 'applied', 'undone')),
    requested_by uuid        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    applied_at   timestamptz,
    undone_at    timestamptz,
    undone_by    uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, requested_by) REFERENCES users (tenant_id, id),
    FOREIGN KEY (tenant_id, undone_by) REFERENCES users (tenant_id, id),
    CHECK ((kind = 'stop_module') = (module_key IS NOT NULL)),
    CHECK ((status = 'applied') = (applied_at IS NOT NULL)),
    CHECK ((status = 'undone') = (undone_at IS NOT NULL))
);
CREATE UNIQUE INDEX subscription_scheduled_changes_one_scheduled
    ON subscription_scheduled_changes (tenant_id, kind, coalesce(module_key, '')) WHERE status = 'scheduled';
CREATE INDEX subscription_scheduled_changes_by_tenant ON subscription_scheduled_changes (tenant_id, created_at DESC);
CREATE INDEX subscription_scheduled_changes_due ON subscription_scheduled_changes (effective_on) WHERE status = 'scheduled';
SELECT bms_apply_tenant_rls('subscription_scheduled_changes');
SELECT bms_grant_app('subscription_scheduled_changes', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- 3. subscriptions.ended_on: the day the Owner's cancellation took effect. From that day the business is read only with
--    the problem subscription_ended (sign-in, reading and exports still work); data is kept. Cleared when the operator
--    moves the subscription out of cancelled (a resume).
-- ---------------------------------------------------------------------------------------------

ALTER TABLE subscriptions ADD COLUMN ended_on date;

-- V75's function, now clearing ended_on whenever the subscription leaves cancelled; otherwise unchanged.
CREATE OR REPLACE FUNCTION platform_set_subscription_status(
    p_tenant_id uuid, p_status text, p_next_status_change_on date, p_platform_user_id uuid)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_previous text;
BEGIN
    SELECT status INTO v_previous FROM subscriptions WHERE tenant_id = p_tenant_id FOR UPDATE;
    IF NOT FOUND THEN
        INSERT INTO subscriptions (id, tenant_id, plan_id, status, updated_by_platform_user_id)
        SELECT gen_random_uuid(), t.id, t.plan_id, 'active', p_platform_user_id FROM tenants t WHERE t.id = p_tenant_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'unknown tenant' USING ERRCODE = 'no_data_found';
        END IF;
        v_previous := 'active';
    END IF;
    UPDATE subscriptions s
       SET status = p_status, next_status_change_on = p_next_status_change_on,
           suspended_from = CASE
               WHEN p_status NOT IN ('suspended', 'cancelled') THEN NULL
               WHEN v_previous IN ('suspended', 'cancelled') THEN s.suspended_from
               ELSE v_previous END,
           ended_on = CASE WHEN p_status = 'cancelled' THEN s.ended_on END,
           updated_by_platform_user_id = p_platform_user_id, updated_at = now()
     WHERE s.tenant_id = p_tenant_id;
    UPDATE tenants
       SET status = CASE WHEN p_status IN ('suspended', 'cancelled') THEN 'suspended' ELSE 'active' END,
           updated_at = now()
     WHERE id = p_tenant_id;
    RETURN v_previous;
END
$$;

-- The Owner's cancellation taking effect (the nightly job, or at once when nothing is paid ahead). Runs in a transaction
-- bound to that tenant only. Cancelled with ended_on, the tenant read only; suspended_from keeps what a resume brings back.
-- A business that already ended changes nothing. Returns the previous status.
CREATE FUNCTION platform_end_subscription(p_tenant_id uuid, p_on date)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_sub subscriptions%ROWTYPE;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'a subscription is ended inside its own tenant only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT * INTO v_sub FROM subscriptions WHERE tenant_id = p_tenant_id FOR UPDATE;
    IF NOT FOUND THEN
        INSERT INTO subscriptions (id, tenant_id, plan_id, status)
        SELECT gen_random_uuid(), t.id, t.plan_id, 'active' FROM tenants t WHERE t.id = p_tenant_id;
        SELECT * INTO v_sub FROM subscriptions WHERE tenant_id = p_tenant_id FOR UPDATE;
    END IF;
    IF v_sub.status = 'cancelled' AND v_sub.ended_on IS NOT NULL THEN
        RETURN v_sub.status;
    END IF;
    UPDATE subscriptions s
       SET status = 'cancelled', ended_on = p_on, next_status_change_on = NULL,
           suspended_from = CASE WHEN v_sub.status IN ('suspended', 'cancelled') THEN s.suspended_from ELSE v_sub.status END,
           updated_by_platform_user_id = NULL, updated_at = now()
     WHERE s.tenant_id = p_tenant_id;
    UPDATE tenants SET status = 'suspended', updated_at = now() WHERE id = p_tenant_id;
    RETURN v_sub.status;
END
$$;

-- The Owner's plan change takes the subscription's row lock before it checks and writes (bms_app may only read
-- subscriptions, and SELECT ... FOR UPDATE needs UPDATE), so two changes sent at once are made one after the other and the
-- second sees the first. A business with no subscription row yet takes a transaction advisory lock on its id instead.
-- Bound to its own tenant only. Returns the subscription's status, or null.
CREATE FUNCTION app_lock_subscription(p_tenant_id uuid) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_status text;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS DISTINCT FROM p_tenant_id::text THEN
        RAISE EXCEPTION 'a subscription is locked inside its own tenant only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT status INTO v_status FROM subscriptions WHERE tenant_id = p_tenant_id FOR UPDATE;
    IF NOT FOUND THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(p_tenant_id::text, 0));
    END IF;
    RETURN v_status;
END
$$;

-- V2's resolver: a business whose Owner's cancellation took effect answers 'ended' instead of 'suspended', so the read
-- only refusal names the end of the subscription rather than a suspension by the operator. A scheduled cancellation whose
-- day has come in the business's own time zone answers 'ended' too, so read only never waits for the quarter-hour job.
CREATE OR REPLACE FUNCTION app_resolve_tenant_status(p_slug text) RETURNS TABLE (id uuid, status text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.id,
           CASE WHEN t.status = 'suspended' AND s.status = 'cancelled' AND s.ended_on IS NOT NULL THEN 'ended'
                WHEN EXISTS (SELECT 1 FROM subscription_scheduled_changes c
                              WHERE c.tenant_id = t.id AND c.kind = 'cancel' AND c.status = 'scheduled'
                                AND c.effective_on <= (now() AT TIME ZONE t.timezone)::date) THEN 'ended'
                ELSE t.status END
      FROM tenants t
      LEFT JOIN subscriptions s ON s.tenant_id = t.id
     WHERE t.slug = p_slug AND t.status IN ('active', 'suspended')
$$;

-- V49's report runner lists, now also taking a business whose Owner's cancellation took effect: the exports still work
-- after the end (ADR-073), so a run asked for then, or one running when the job ended the business, must finish. A business
-- suspended by the operator is still left out.
CREATE OR REPLACE FUNCTION app_count_running_report_runs() RETURNS integer
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT count(*)::integer FROM report_runs r JOIN tenants t ON t.id = r.tenant_id
      LEFT JOIN subscriptions s ON s.tenant_id = t.id
     WHERE r.status = 'running'
       AND (t.status = 'active' OR (t.status = 'suspended' AND s.status = 'cancelled' AND s.ended_on IS NOT NULL))
$$;

CREATE OR REPLACE FUNCTION app_tenants_with_queued_report_runs() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT r.tenant_id FROM report_runs r JOIN tenants t ON t.id = r.tenant_id
      LEFT JOIN subscriptions s ON s.tenant_id = t.id
     WHERE r.status = 'queued'
       AND (t.status = 'active' OR (t.status = 'suspended' AND s.status = 'cancelled' AND s.ended_on IS NOT NULL))
       AND NOT EXISTS (SELECT 1 FROM report_runs x WHERE x.tenant_id = r.tenant_id AND x.status = 'running')
     GROUP BY r.tenant_id
     ORDER BY min(r.created_at), r.tenant_id
$$;

CREATE OR REPLACE FUNCTION app_tenants_with_stale_report_runs(p_cutoff timestamptz) RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT DISTINCT r.tenant_id FROM report_runs r JOIN tenants t ON t.id = r.tenant_id
      LEFT JOIN subscriptions s ON s.tenant_id = t.id
     WHERE r.status = 'running' AND coalesce(r.heartbeat_at, r.started_at) < p_cutoff
       AND (t.status = 'active' OR (t.status = 'suspended' AND s.status = 'cancelled' AND s.ended_on IS NOT NULL))
$$;

-- ---------------------------------------------------------------------------------------------
-- 4. The operator's reads across businesses and the nightly job's list (ADR-016). Each refuses a transaction bound to a
--    tenant, so a tenant request can never call them to read another business.
-- ---------------------------------------------------------------------------------------------

-- Module requests, newest first, optionally of one status, with the business's name, slug and plan and whether that plan
-- already includes the module.
CREATE FUNCTION platform_module_requests(p_status text) RETURNS TABLE (
    id uuid, tenant_id uuid, tenant_slug text, tenant_name text, plan_code text, plan_includes boolean, module_key text,
    status text, requested_by_name text, created_at timestamptz, decided_at timestamptz, decided_automatically boolean,
    decision_reason text, plan_before text, plan_after text)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'module requests are read across businesses by the platform operator only'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
    SELECT r.id, r.tenant_id, t.slug::text, t.name::text, p.code::text, r.module_key = ANY (p.allowed_modules), r.module_key,
           r.status, u.full_name::text, r.created_at, r.decided_at, r.decided_automatically, r.decision_reason::text,
           r.plan_before, r.plan_after
      FROM subscription_module_requests r
      JOIN tenants t ON t.id = r.tenant_id
      JOIN plans p ON p.id = t.plan_id
      JOIN users u ON u.tenant_id = r.tenant_id AND u.id = r.requested_by
     WHERE p_status IS NULL OR r.status = p_status
     ORDER BY r.created_at DESC, r.id
     LIMIT 500;
END
$$;

-- The business a module request belongs to, so the operator's decision can bind that tenant.
CREATE FUNCTION platform_module_request_tenant(p_id uuid) RETURNS uuid
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'read by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN (SELECT r.tenant_id FROM subscription_module_requests r WHERE r.id = p_id);
END
$$;

-- Scheduled changes still to apply, soonest first, with the business's name and slug.
CREATE FUNCTION platform_scheduled_changes() RETURNS TABLE (
    id uuid, tenant_id uuid, tenant_slug text, tenant_name text, kind text, module_key text, effective_on date,
    requested_by_name text, created_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'scheduled changes are read across businesses by the platform operator only'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
    SELECT c.id, c.tenant_id, t.slug::text, t.name::text, c.kind, c.module_key, c.effective_on, u.full_name::text,
           c.created_at
      FROM subscription_scheduled_changes c
      JOIN tenants t ON t.id = c.tenant_id
      JOIN users u ON u.tenant_id = c.tenant_id AND u.id = c.requested_by
     WHERE c.status = 'scheduled'
     ORDER BY c.effective_on, c.created_at, c.id
     LIMIT 500;
END
$$;

-- The nightly job's list: every business with a scheduled change on or before p_through (the latest business date
-- anywhere), with its time zone; the job compares each change with that business's own date.
CREATE FUNCTION platform_tenants_with_due_changes(p_through date) RETURNS TABLE (tenant_id uuid, timezone text)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'listed by the platform job only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
    SELECT t.id, t.timezone::text
      FROM tenants t
     WHERE t.status IN ('active', 'suspended')
       AND EXISTS (SELECT 1 FROM subscription_scheduled_changes c
                    WHERE c.tenant_id = t.id AND c.status = 'scheduled' AND c.effective_on <= p_through)
     ORDER BY t.id;
END
$$;

REVOKE ALL ON FUNCTION platform_end_subscription(uuid, date) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_lock_subscription(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_module_requests(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_module_request_tenant(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_scheduled_changes() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_tenants_with_due_changes(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_end_subscription(uuid, date) TO bms_app;
GRANT EXECUTE ON FUNCTION app_lock_subscription(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_module_requests(text) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_module_request_tenant(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_scheduled_changes() TO bms_app;
GRANT EXECUTE ON FUNCTION platform_tenants_with_due_changes(date) TO bms_app;
