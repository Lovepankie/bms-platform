-- V117: retail posts and reads cash and bank through the payment methods mapping (issue #236, ADR-105), after V114
-- (#127), V115 (#676) and V116 (#239, the transfer receipt checks, which does not touch platform_set_tenant_modules).
--
-- Retail's till (cash_on_hand) and bank now resolve through core.ledger's PaymentMethods at the posting's branch, as
-- lending's do, and the cash book reads the same mapped accounts. A tenant that remaps cash or bank therefore keeps all
-- its cash on one account, so the V43 check that refused to switch retail on while cash or bank was mapped to another
-- account (payment_methods_remap_blocks_retail) has nothing left to protect. platform_set_tenant_modules is replaced
-- with its V10 body, without that check and without the advisory lock it took to serialise with a mapping save.
--
-- Checked against every definition (review of #677): the function is created in V2 and replaced only in V10 and V43, on
-- main up to V113 and on the branches of V114 (#127), V115 (#676) and V116 (#239); V90 names it in a comment only. The
-- current body is V43's, which is V10's plus the lock and the check, so the body below is V43's less exactly those lines
-- and nothing later is lost. What happens after a module is switched on that the function does not do (the default units
-- of V109, the packs switched on at start) runs in core.tenancy's ModuleSetUp from PlatformService, which this leaves
-- alone; PlatformIT.retailIsSwitchedOnWhileCashIsRemapped asserts both halves.
-- Checked against V2, V10, V43, V114 (#127, not merged), V115 (#676) and V116 (#678): none of the later ones touches it.
-- PlatformIT.platformSetTenantModulesIsStillTheBodyOfV117 fails if a later migration replaces it without redoing this check.
--
-- A function replaced in place: no table is touched, no lock is held beyond the catalogue row, safe on a populated
-- database. Its owner and the grants from V2 stay.

CREATE OR REPLACE FUNCTION platform_set_tenant_modules(p_tenant_id uuid, p_modules text[], p_platform_user_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_allowed text[];
BEGIN
    SELECT p.allowed_modules INTO v_allowed FROM tenants t JOIN plans p ON p.id = t.plan_id WHERE t.id = p_tenant_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown tenant' USING ERRCODE = 'no_data_found';
    END IF;
    IF NOT (coalesce(p_modules, '{}') <@ v_allowed) THEN
        RAISE EXCEPTION 'module not allowed by plan' USING ERRCODE = 'check_violation';
    END IF;
    DELETE FROM tenant_modules WHERE tenant_id = p_tenant_id AND NOT (module_key = ANY (coalesce(p_modules, '{}')));
    INSERT INTO tenant_modules (tenant_id, module_key, enabled_by_platform_user_id)
    SELECT p_tenant_id, m, p_platform_user_id FROM unnest(coalesce(p_modules, '{}')) AS m
    ON CONFLICT (tenant_id, module_key) DO NOTHING;
    IF 'lending' = ANY (coalesce(p_modules, '{}')) THEN
        PERFORM bms_seed_lending_chart(p_tenant_id);
    END IF;
    IF 'retail' = ANY (coalesce(p_modules, '{}')) THEN
        PERFORM bms_seed_retail_chart(p_tenant_id);
    END IF;
END
$$;
