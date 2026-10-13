-- V43: payment methods mapped to ledger accounts (FR-GL-08, issue #160). V43 follows V42
-- (#176, the loan pledge guard), which merges first; outOfOrder is off.
--
-- Chapter 6 table payment_methods: a tenant maps each payment method to a postable asset
-- account, for the whole tenant (branch_id NULL) or for one branch. A branch row wins over the
-- tenant row. A method with no row keeps posting to its seeded account (cash to cash_on_hand
-- and so on, ADR-026), so tenants that never touch the mapping keep working; a row with
-- is_active = false switches the method off (payment_method_unmapped).

CREATE TABLE payment_methods (
    id            uuid        NOT NULL PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz,
    version       integer     NOT NULL DEFAULT 1,
    method_key    text        NOT NULL
                              CHECK (method_key IN ('cash', 'bank', 'mtn_momo', 'airtel_money', 'gateway', 'savings_transfer')),
    branch_id     uuid,
    gl_account_id uuid        NOT NULL,
    is_active     boolean     NOT NULL DEFAULT true,
    updated_by    uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, gl_account_id) REFERENCES gl_accounts (tenant_id, id)
);
CREATE UNIQUE INDEX payment_methods_one_per_scope
    ON payment_methods (tenant_id, method_key, coalesce(branch_id, '00000000-0000-0000-0000-000000000000'::uuid));
SELECT bms_apply_tenant_rls('payment_methods');
SELECT bms_grant_app('payment_methods', 'SELECT, INSERT, UPDATE, DELETE');

-- Retail posts and reads cash_on_hand and bank by system key, not through payment_methods (ADR-026,
-- #236). The API refuses to map cash or bank elsewhere while retail is on; this is the other half:
-- retail is not switched on for a tenant that already has an active cash or bank remap, because that
-- would move lending cash out of the balance the retail cash book reads. Body as in V10, plus the check.
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
    -- The same lock PaymentMethodController.set takes: a mapping save and this switch run one after the
    -- other, so neither sees the other half done. Released when the transaction ends.
    PERFORM pg_advisory_xact_lock(hashtextextended('payment_methods:' || p_tenant_id::text, 0));
    IF 'retail' = ANY (coalesce(p_modules, '{}'))
       AND EXISTS (SELECT 1 FROM payment_methods pm JOIN gl_accounts a ON a.id = pm.gl_account_id
                    WHERE pm.tenant_id = p_tenant_id AND pm.is_active
                      AND ((pm.method_key = 'cash' AND a.system_key IS DISTINCT FROM 'cash_on_hand')
                        OR (pm.method_key = 'bank' AND a.system_key IS DISTINCT FROM 'bank'))) THEN
        RAISE EXCEPTION 'payment_methods_remap_blocks_retail' USING ERRCODE = 'integrity_constraint_violation';
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
