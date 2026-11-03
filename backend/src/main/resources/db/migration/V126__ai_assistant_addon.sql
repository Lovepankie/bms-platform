-- V126: the AI assistant as a paid add-on (issue #423, ADR-112). Numbered V126, directly after V125 (the retail AI brief,
-- issue #422), in the merge order the coordinator set (AGENTS.md). Flyway runs with outOfOrder off, so the
-- merger renumbers at merge time if the order changes.
--
-- Expand only, safe on a populated database:
--   1. addon_prices: the add-on's monthly price over time, append-only, like plan_prices (V75). NOTHING IS SEEDED: the price is
--      a commercial term the operator types in Plans and prices, and a later price never changes what was charged before.
--   2. Two marks on the subscription, both false for every business: ai_addon (the operator switched the add-on on for the
--      business) and ai_addon_no_charge (on without charge: a demo or a pilot). No business is switched on here.
--   3. platform_set_ai_addon, the operator's switch, and platform_add_addon_price, through SECURITY DEFINER functions as every
--      operator change (ADR-016); each returns what was there before so the application audits before and after. Both refuse
--      a caller bound to a business and a platform user who is not active (as V88 and V105); the read of the marks lets a
--      bound caller read its own business only.
--
-- The add-on is the source of truth for whether a business may use the assistant (ADR-037 decision 7 as amended): the
-- environment list BMS_AI_ENABLED_TENANTS stays as the platform's kill switch (off) and a demo host's allow-all (*).

SET LOCAL lock_timeout = '5s';

CREATE TABLE addon_prices (
    id                          uuid         NOT NULL PRIMARY KEY,
    -- One add-on today; the check names it so a typo is refused, and a later add-on widens the check.
    addon_code                  varchar(40)  NOT NULL CHECK (addon_code IN ('ai_assistant')),
    currency                    char(3)      NOT NULL REFERENCES currencies (code),
    -- At most 50,000,000 minor units, as a plan's price (V75): a typing slip is refused, not stored.
    monthly_minor               bigint       NOT NULL CHECK (monthly_minor BETWEEN 0 AND 50000000),
    effective_from              date         NOT NULL,
    reason                      varchar(300),
    created_by_platform_user_id uuid         NOT NULL REFERENCES platform_users (id),
    created_at                  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX addon_prices_by_code ON addon_prices (addon_code, currency, effective_from DESC, created_at DESC);
GRANT SELECT ON addon_prices TO bms_app;
SELECT bms_make_append_only('addon_prices');

ALTER TABLE subscriptions
    ADD COLUMN ai_addon           boolean NOT NULL DEFAULT false,
    ADD COLUMN ai_addon_no_charge boolean NOT NULL DEFAULT false;

-- Switches the add-on on or off for a business and marks it charged or not. Returns the marks before, as
-- (ai_addon, ai_addon_no_charge). Switching off clears the no charge mark, so a later switch on starts charged unless the
-- operator says otherwise.
CREATE FUNCTION platform_set_ai_addon(p_tenant_id uuid, p_on boolean, p_no_charge boolean, p_platform_user_id uuid)
RETURNS TABLE (was_on boolean, was_no_charge boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    -- The operator's alone (ADR-016, as V88 and V105): never under a tenant binding, only for an active platform user.
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'the add-on is switched by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform_users u WHERE u.id = p_platform_user_id AND u.is_active) THEN
        RAISE EXCEPTION 'the add-on is switched by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT s.ai_addon, s.ai_addon_no_charge INTO was_on, was_no_charge
      FROM subscriptions s WHERE s.tenant_id = p_tenant_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown tenant' USING ERRCODE = 'no_data_found';
    END IF;
    UPDATE subscriptions s
       SET ai_addon = p_on,
           ai_addon_no_charge = p_on AND p_no_charge,
           updated_by_platform_user_id = p_platform_user_id, updated_at = now()
     WHERE s.tenant_id = p_tenant_id;
    RETURN NEXT;
END
$$;

-- The add-on marks of one business, for the operator's Business page and the billing job, which run bound to no business, and
-- for the Owner's billing page, bound to its own business: a bound caller reads its own business only, never another.
CREATE FUNCTION platform_tenant_ai_addon(p_tenant_id uuid)
RETURNS TABLE (ai_addon boolean, ai_addon_no_charge boolean)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_bound text := nullif(current_setting('app.tenant_id', true), '');
BEGIN
    IF v_bound IS NOT NULL AND v_bound::uuid IS DISTINCT FROM p_tenant_id THEN
        RAISE EXCEPTION 'another business''s add-on is the platform operator''s' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY SELECT s.ai_addon, s.ai_addon_no_charge FROM subscriptions s WHERE s.tenant_id = p_tenant_id;
END
$$;

-- How many live businesses have the add-on on, for Plans and prices: one read, closed businesses left out.
CREATE FUNCTION platform_ai_addon_on_count()
RETURNS bigint
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'the add-on count is the platform operator''s' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN (SELECT count(*) FROM subscriptions s JOIN tenants t ON t.id = s.tenant_id
             WHERE s.ai_addon AND t.status <> 'closed');
END
$$;

CREATE FUNCTION platform_add_addon_price(
    p_id uuid, p_addon_code text, p_currency text, p_monthly_minor bigint, p_effective_from date, p_reason text,
    p_platform_user_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'an add-on price is typed by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform_users u WHERE u.id = p_platform_user_id AND u.is_active) THEN
        RAISE EXCEPTION 'an add-on price is typed by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    INSERT INTO addon_prices (id, addon_code, currency, monthly_minor, effective_from, reason, created_by_platform_user_id)
    VALUES (p_id, p_addon_code, p_currency, p_monthly_minor, p_effective_from, p_reason, p_platform_user_id);
END
$$;

REVOKE ALL ON FUNCTION platform_set_ai_addon(uuid, boolean, boolean, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_tenant_ai_addon(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_ai_addon_on_count() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_ai_addon_on_count() TO bms_app;
REVOKE ALL ON FUNCTION platform_add_addon_price(uuid, text, text, bigint, date, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_set_ai_addon(uuid, boolean, boolean, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_tenant_ai_addon(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_add_addon_price(uuid, text, text, bigint, date, text, uuid) TO bms_app;
