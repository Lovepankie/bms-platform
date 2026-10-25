-- V102: the rest of the Owner's powers of issue #325 (issue #571, ADR-090): a billing contact separate from the Owner's own
-- sign-in contact, the Owner's request to close the business (read only at once, a full data export, removal by the
-- platform operator after a cooling-off period, cancellable until then), the removal itself (personal data deleted or
-- anonymised, money records kept anonymised until a date), and the operator's list of businesses with no Owner.
--
-- V102 is the next number after V101 (the Controls indexes, #577, merged; V99 stays unused, placeholder-migration-guard;
-- AGENTS.md, the migration ledger). Flyway runs with outOfOrder off, so this
-- file is renamed at merge if the order changes.
--
-- Expand only, safe on a populated database: two new tenant tables, one widened check on tenants.status (a new value,
-- 'closed'), and SECURITY DEFINER functions (ADR-016). The guard trigger functions named in section 6 gain one exception, for
-- the removal of a business the operator confirmed; nothing else in them changes. Nothing is backfilled.
--
-- Locks, said plainly. Flyway runs this file in one transaction. CREATE TABLE with foreign keys takes SHARE ROW EXCLUSIVE on
-- tenants, users and platform_users until the commit. Replacing tenants' status check takes ACCESS EXCLUSIVE on tenants for
-- the check's validation (a scan of a small table). CREATE OR REPLACE FUNCTION takes no table lock. lock_timeout is five
-- seconds as in V90 and V92.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- 1. billing_contacts: at most one row per business. The person billing is written to (reminders, receipts), when the Owner
--    named one: an accountant, say. No row means billing goes to the Owner, as before. It is never a sign-in contact: no
--    sign-in code, reset link or security notice ever reads this table.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE billing_contacts (
    tenant_id   uuid         NOT NULL PRIMARY KEY REFERENCES tenants (id),
    full_name   varchar(120) NOT NULL CHECK (btrim(full_name) <> ''),
    email       varchar(254) NOT NULL CHECK (email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
    phone_e164  varchar(16)  CHECK (phone_e164 ~ '^\+[1-9][0-9]{7,14}$'),
    updated_by  uuid         NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES users (tenant_id, id)
);
SELECT bms_apply_tenant_rls('billing_contacts');
SELECT bms_grant_app('billing_contacts', 'SELECT, INSERT, UPDATE, DELETE');

-- ---------------------------------------------------------------------------------------------
-- 2. tenant_closures: the Owner's request to close the business. requested (the business is read only, the cooling-off
--    runs to remove_after), then cancelled by the Owner, or removing and removed by the platform operator in one
--    transaction; once its kept money records are past keep_until, purging and purged by the daily job (owner decision D46).
--    One open request per business. The application may insert a request and cancel it; only the definer functions of
--    sections 5 and 8 (running as bms_owner) move a request further, so no request of the business itself can start a
--    removal or a purge.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE tenant_closures (
    id                         uuid         NOT NULL PRIMARY KEY,
    tenant_id                  uuid         NOT NULL REFERENCES tenants (id),
    status                     text         NOT NULL DEFAULT 'requested'
                                            CHECK (status IN ('requested', 'cancelled', 'removing', 'removed', 'purging',
                                                              'purged')),
    requested_by               uuid         NOT NULL,
    requested_at               timestamptz  NOT NULL DEFAULT now(),
    remove_after               date         NOT NULL,
    export_run_id              uuid,
    cancelled_by               uuid,
    cancelled_at               timestamptz,
    removed_by_platform_user_id uuid        REFERENCES platform_users (id),
    removal_reason             varchar(300),
    removed_at                 timestamptz,
    keep_until                 date,
    purged_at                  timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, requested_by) REFERENCES users (tenant_id, id),
    FOREIGN KEY (tenant_id, cancelled_by) REFERENCES users (tenant_id, id),
    CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL)),
    CHECK ((status IN ('removed', 'purging', 'purged')) = (removed_at IS NOT NULL AND keep_until IS NOT NULL)),
    CHECK ((status = 'purged') = (purged_at IS NOT NULL)),
    CHECK (status NOT IN ('removing', 'removed', 'purging', 'purged') OR removed_by_platform_user_id IS NOT NULL)
);
CREATE UNIQUE INDEX tenant_closures_one_open ON tenant_closures (tenant_id) WHERE status IN ('requested', 'removing');
CREATE INDEX tenant_closures_open ON tenant_closures (remove_after) WHERE status = 'requested';
SELECT bms_apply_tenant_rls('tenant_closures');
SELECT bms_grant_app('tenant_closures', 'SELECT, INSERT, UPDATE');

CREATE FUNCTION tenant_closures_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_user = 'bms_owner' THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'requested' OR NEW.removed_by_platform_user_id IS NOT NULL OR NEW.removed_at IS NOT NULL THEN
            RAISE EXCEPTION 'tenant_closures: a request starts as requested' USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'requested' AND NEW.status = 'cancelled'
       AND (NEW.id, NEW.tenant_id, NEW.requested_by, NEW.requested_at, NEW.remove_after, NEW.removed_by_platform_user_id,
            NEW.removed_at, NEW.keep_until)
           IS NOT DISTINCT FROM
           (OLD.id, OLD.tenant_id, OLD.requested_by, OLD.requested_at, OLD.remove_after, OLD.removed_by_platform_user_id,
            OLD.removed_at, OLD.keep_until) THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'requested' AND NEW.status = 'requested'
       AND (NEW.id, NEW.tenant_id, NEW.requested_by, NEW.requested_at, NEW.remove_after)
           IS NOT DISTINCT FROM (OLD.id, OLD.tenant_id, OLD.requested_by, OLD.requested_at, OLD.remove_after)
       AND OLD.export_run_id IS NULL THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'tenant_closures: only the Owner''s cancel is changed by the business; removal is the operator''s'
        USING ERRCODE = 'insufficient_privilege';
END
$$;
CREATE TRIGGER tenant_closures_guard BEFORE INSERT OR UPDATE ON tenant_closures
    FOR EACH ROW EXECUTE FUNCTION tenant_closures_guard();

-- ---------------------------------------------------------------------------------------------
-- 3. tenants.status gains 'closed': a business whose removal is done. The resolver serves only active and suspended
--    businesses, so its host resolves nothing; every job lists active (or suspended) businesses only.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE tenants DROP CONSTRAINT tenants_status_check;
ALTER TABLE tenants ADD CONSTRAINT tenants_status_check CHECK (status IN ('active', 'suspended', 'closed'));

-- ---------------------------------------------------------------------------------------------
-- 4. The resolver answers 'closing' while a request is open, for a business that is active or whose Owner's cancellation
--    ended it (V90): the business is read only at once and can still take its data away and cancel the request. A business
--    the operator suspended stays 'suspended': it takes nothing away (ADR-073, ADR-074).
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION app_resolve_tenant_status(p_slug text) RETURNS TABLE (id uuid, status text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.id,
           CASE WHEN EXISTS (SELECT 1 FROM tenant_closures k
                              WHERE k.tenant_id = t.id AND k.status IN ('requested', 'removing'))
                     AND (t.status = 'active' OR (s.status = 'cancelled' AND s.ended_on IS NOT NULL)) THEN 'closing'
                WHEN t.status = 'suspended' AND s.status = 'cancelled' AND s.ended_on IS NOT NULL THEN 'ended'
                WHEN t.status = 'active' AND EXISTS (SELECT 1 FROM subscription_scheduled_changes c
                              WHERE c.tenant_id = t.id AND c.kind = 'cancel' AND c.status = 'scheduled'
                                AND c.effective_on <= (now() AT TIME ZONE t.timezone)::date) THEN 'ended'
                ELSE t.status END
      FROM tenants t
      LEFT JOIN subscriptions s ON s.tenant_id = t.id
     WHERE t.slug = p_slug AND t.status IN ('active', 'suspended')
$$;

-- A business that is closing is read only for its jobs too, as a suspended one is: the scheduled jobs that iterate the active
-- businesses (the lending arrears run and its penalty charges, interest, returns, the daily notices, the approvals' expiry)
-- leave it out, and the outbox's channels, which check app_tenant_is_active, send no SMS or push for it. The exports, the
-- report runner and the weekly copy keep their own lists and still serve it.
CREATE OR REPLACE FUNCTION app_list_active_tenants() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.id FROM tenants t
     WHERE t.status = 'active'
       AND NOT EXISTS (SELECT 1 FROM tenant_closures k WHERE k.tenant_id = t.id AND k.status IN ('requested', 'removing'))
     ORDER BY t.id
$$;

CREATE OR REPLACE FUNCTION app_list_active_tenants_with_module(p_module text) RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.id FROM tenants t
     WHERE t.status = 'active'
       AND EXISTS (SELECT 1 FROM tenant_modules m WHERE m.tenant_id = t.id AND m.module_key = p_module)
       AND NOT EXISTS (SELECT 1 FROM tenant_closures k WHERE k.tenant_id = t.id AND k.status IN ('requested', 'removing'))
     ORDER BY t.id
$$;

CREATE OR REPLACE FUNCTION app_tenant_is_active(p_tenant_id uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM tenants t WHERE t.id = p_tenant_id AND t.status = 'active'
                     AND NOT EXISTS (SELECT 1 FROM tenant_closures k
                                      WHERE k.tenant_id = t.id AND k.status IN ('requested', 'removing')))
$$;

-- A business that is closing gets no reminder and is never made overdue (ADR-075 decision 8 skips a cancelling business the
-- same way): it is not told to pay to keep using BMS.
CREATE OR REPLACE FUNCTION platform_tenants_cancelling() RETURNS TABLE (tenant_id uuid)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'listed by the platform job only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
    SELECT c.tenant_id FROM subscription_scheduled_changes c WHERE c.kind = 'cancel' AND c.status = 'scheduled'
    UNION
    SELECT k.tenant_id FROM tenant_closures k WHERE k.status IN ('requested', 'removing');
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 5. Removal, in two steps. The operator confirms after the cooling-off: platform_begin_tenant_removal, in a transaction bound
--    to no business, authenticates the operator (an active operator with a live platform session who used an authenticator
--    code in the last two minutes), checks the cooling-off against the business's own date computed here, and moves the
--    request to removing. Then, in one transaction bound to the business, app_mark_tenant_removal marks the transaction, each
--    module's eraser runs and platform_finish_tenant_removal ends it; a failure there rolls the erasure back and leaves the
--    request removing, so the operator removes again. Only a request the operator began can be erased: the business itself,
--    bound to its own host, can neither begin nor erase. bms_owner has BYPASSRLS, so every statement below names the tenant.
-- ---------------------------------------------------------------------------------------------

-- True only inside a removal or a purge: the caller runs as bms_owner (a definer function of sections 5 and 8), the transaction
-- was marked for this business by app_mark_tenant_removal or platform_purge_closed_tenant, and the business's request is being
-- removed or purged. SECURITY INVOKER on
-- purpose: current_user is the caller's.
CREATE FUNCTION bms_erasing(p_row jsonb) RETURNS boolean
LANGUAGE plpgsql STABLE SET search_path = public, pg_temp AS $$
BEGIN
    IF current_user <> 'bms_owner' OR p_row IS NULL THEN
        RETURN false;
    END IF;
    IF coalesce(current_setting('bms.erasing_tenant', true), '') = '' OR p_row ->> 'tenant_id' IS NULL
       OR p_row ->> 'tenant_id' <> current_setting('bms.erasing_tenant', true) THEN
        RETURN false;
    END IF;
    RETURN EXISTS (SELECT 1 FROM tenant_closures k
                    WHERE k.tenant_id = (p_row ->> 'tenant_id')::uuid AND k.status IN ('removing', 'purging'));
END
$$;
REVOKE ALL ON FUNCTION bms_erasing(jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION bms_erasing(jsonb) TO bms_app;

-- The business bound to the transaction, when its removal is under way; raises otherwise. Every eraser calls it first.
CREATE FUNCTION bms_removing_tenant() RETURNS uuid
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid := nullif(current_setting('app.tenant_id', true), '')::uuid;
BEGIN
    IF v_tenant IS NULL OR coalesce(current_setting('bms.erasing_tenant', true), '') <> v_tenant::text
       OR NOT EXISTS (SELECT 1 FROM tenant_closures k WHERE k.tenant_id = v_tenant AND k.status = 'removing') THEN
        RAISE EXCEPTION 'personal data is erased only during a removal the operator confirmed'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN v_tenant;
END
$$;
REVOKE ALL ON FUNCTION bms_removing_tenant() FROM PUBLIC;

-- Begins (or resumes) the removal of a request, outside any business's transaction. Returns the business, or null when the
-- request is not open (cancelled meanwhile, or removed already). Raises cooling_off before its day in the business's own time
-- zone, and operator_not_verified unless the operator is active, the session is theirs and live, and they used an authenticator
-- code in the last two minutes (the API's step-up, which records the code's step as used).
CREATE FUNCTION platform_begin_tenant_removal(p_closure uuid, p_operator uuid, p_session uuid, p_reason text)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_row   tenant_closures%ROWTYPE;
    v_today date;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'a removal is begun by the platform operator, never from a business' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM platform_users u
                    WHERE u.id = p_operator AND u.is_active AND u.mfa_enabled AND u.totp_last_step IS NOT NULL
                      AND u.totp_last_step >= floor(extract(epoch FROM now()) / 30)::bigint - 4)
       OR NOT EXISTS (SELECT 1 FROM platform_sessions x
                       WHERE x.id = p_session AND x.platform_user_id = p_operator AND x.revoked_at IS NULL
                         AND x.expires_at > now()) THEN
        RAISE EXCEPTION 'operator_not_verified' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF p_reason IS NULL OR char_length(btrim(p_reason)) < 5 THEN
        RAISE EXCEPTION 'a removal needs a reason' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO v_row FROM tenant_closures k WHERE k.id = p_closure FOR UPDATE;
    IF NOT FOUND OR v_row.status NOT IN ('requested', 'removing') THEN
        RETURN NULL;
    END IF;
    SELECT (now() AT TIME ZONE t.timezone)::date INTO v_today FROM tenants t WHERE t.id = v_row.tenant_id;
    IF v_row.remove_after > v_today THEN
        RAISE EXCEPTION 'cooling_off' USING ERRCODE = 'check_violation', DETAIL = v_row.remove_after::text;
    END IF;
    UPDATE tenant_closures k
       SET status = 'removing', removed_by_platform_user_id = p_operator, removal_reason = left(btrim(p_reason), 300)
     WHERE k.id = p_closure;
    RETURN v_row.tenant_id;
END
$$;

-- In the removal's transaction bound to the business: marks it for the erasers, only for a request the operator began.
CREATE FUNCTION app_mark_tenant_removal(p_closure uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid := nullif(current_setting('app.tenant_id', true), '')::uuid;
BEGIN
    IF v_tenant IS NULL OR NOT EXISTS (SELECT 1 FROM tenant_closures k
                                        WHERE k.id = p_closure AND k.tenant_id = v_tenant AND k.status = 'removing') THEN
        RAISE EXCEPTION 'personal data is erased only during a removal the operator began'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    PERFORM set_config('bms.erasing_tenant', v_tenant::text, true);
    RETURN true;
END
$$;

-- Personal data out of a JSON value, for the audit logs (owner decision D46): every key that names, reaches or describes a
-- person, and every string that looks like a phone number, an email or a national ID, wherever it sits. Amounts, dates, ids,
-- codes and statuses stay, so the trail of a money change is kept.
CREATE FUNCTION bms_strip_personal(p jsonb) RETURNS jsonb
LANGUAGE plpgsql IMMUTABLE SET search_path = public, pg_temp AS $$
DECLARE
    v_out jsonb;
BEGIN
    CASE jsonb_typeof(p)
        WHEN 'object' THEN
            SELECT coalesce(jsonb_object_agg(e.key, bms_strip_personal(e.value)), '{}'::jsonb) INTO v_out
              FROM jsonb_each(p) e
             WHERE e.key !~* '(^|_)(name|names|phone|phones|msisdn|email|emails|contact|contacts|nin|national|id_number|birth|dob|village|district|county|location|address|occupation|income|note|notes|memo|reason|message|recipient|recipients|ip|agent|buyer|customer|kin|valuer|counterparty|description|delivered|reference|explanation|purpose|tin)($|_)';
            RETURN v_out;
        WHEN 'array' THEN
            SELECT coalesce(jsonb_agg(bms_strip_personal(e.value)), '[]'::jsonb) INTO v_out FROM jsonb_array_elements(p) e;
            RETURN v_out;
        WHEN 'string' THEN
            IF (p #>> '{}') ~ '(\+?256|\m0)7[0-9]{8}|[^@[:space:]]+@[^@[:space:]]+|C[MF][A-Z0-9]{12}' THEN
                RETURN 'null'::jsonb;
            END IF;
            RETURN p;
        ELSE
            RETURN p;
    END CASE;
END
$$;
REVOKE ALL ON FUNCTION bms_strip_personal(jsonb) FROM PUBLIC;

-- The core's own tables: staff and member sign-in rows anonymised and their credentials, sessions, devices and links
-- deleted; messages, SMS log recipients and opt-outs, feedback text, payment request phones and the billing contact removed;
-- the personal data in the business's audit rows and its rows in the platform's log stripped; and the free text people typed
-- on any record of the business. Returns the rows touched.
CREATE FUNCTION app_erase_core_personal_data() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid := bms_removing_tenant();
    v_rows   integer := 0;
    v_n      integer;
    v_col    record;
BEGIN
    DELETE FROM auth_sessions WHERE tenant_id = v_tenant;              GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    DELETE FROM user_credentials WHERE tenant_id = v_tenant;           GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    DELETE FROM user_recovery_codes WHERE tenant_id = v_tenant;        GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    DELETE FROM user_invitations WHERE tenant_id = v_tenant;           GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    DELETE FROM user_password_resets WHERE tenant_id = v_tenant;       GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    DELETE FROM push_subscriptions WHERE tenant_id = v_tenant;         GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    DELETE FROM billing_contacts WHERE tenant_id = v_tenant;           GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    DELETE FROM sms_opt_outs WHERE tenant_id = v_tenant;               GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    -- The Owner flag first: an Owner is never deactivated (users_owner_is_staff).
    UPDATE users SET is_owner = false WHERE tenant_id = v_tenant AND is_owner;
    UPDATE users
       SET full_name = CASE WHEN kind = 'staff' THEN 'Removed staff' ELSE 'Removed member' END,
           email = 'removed-' || id::text || '@removed.invalid', phone_e164 = NULL, status = 'deactivated',
           preferences = '{}'::jsonb, last_login_at = NULL, updated_at = now()
     WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE notification_outbox
       SET recipient = 'removed', params = '{}'::jsonb, last_error = NULL,
           status = CASE WHEN status = 'pending' THEN 'failed' ELSE status END,
           failed_at = CASE WHEN status = 'pending' THEN now() ELSE failed_at END
     WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE sms_deliveries SET recipient_masked = 'removed', recipient_hash = repeat('0', char_length(recipient_hash))
     WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE feedback_items SET message = 'Removed', user_agent = NULL, reply_note = NULL WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    -- A placeholder number of the right shape: the column is required and checked.
    UPDATE payment_intents SET payer_phone_e164 = '+100000000' WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    -- The audit trail keeps who did what and when and every amount and date of a money change; the keys and values that name
    -- or reach a person go (bms_strip_personal), and so do the addresses. The platform's own log of this business the same.
    UPDATE audit_log SET data = bms_strip_personal(data), ip = NULL WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE platform_audit_log SET data = bms_strip_personal(data), ip = NULL WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    -- Free text people typed on any record of the business, money records included (owner decision D46): a note, a memo, a
    -- reason, an explanation, a purpose or a message becomes 'Removed', which every non-blank and minimum-length check
    -- accepts. Columns are read from the catalogue; one whose check lists codes or patterns holds a code, not text, and
    -- stays. A table added later is covered without a change here.
    FOR v_col IN
        SELECT c.relname AS tbl, a.attname AS col
          FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
          JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
         WHERE ns.nspname = 'public' AND c.relkind = 'r'
           AND a.atttypid IN ('text'::regtype, 'varchar'::regtype)
           AND EXISTS (SELECT 1 FROM pg_attribute t WHERE t.attrelid = c.oid AND t.attname = 'tenant_id' AND NOT t.attisdropped)
           AND c.relname NOT IN ('tenant_closures', 'users', 'tenants')
           AND a.attname ~ '^(note|notes|memo|explanation|message|reason|purpose|purpose_text|visit_notes)$|_(note|notes|reason)$'
           AND a.attname NOT IN ('failure_reason', 'revoked_reason')
           AND NOT EXISTS (SELECT 1 FROM pg_constraint k
                            WHERE k.conrelid = c.oid AND k.contype = 'c' AND a.attnum = ANY (k.conkey)
                              AND (pg_get_constraintdef(k.oid) ~ 'ANY \(ARRAY\[' OR pg_get_constraintdef(k.oid) ~ ' ~ '))
         ORDER BY 1, 2
    LOOP
        EXECUTE format('UPDATE %I SET %I = ''Removed'' WHERE tenant_id = $1 AND %I IS NOT NULL AND %I <> ''Removed''',
                       v_col.tbl, v_col.col, v_col.col, v_col.col) USING v_tenant;
        GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    END LOOP;
    RETURN v_rows;
END
$$;

-- Ends the removal: the request removed with the day its money records may be deleted, the business closed (its host
-- resolves nothing) and its subscription ended, the application it was activated from anonymised, and a row in the
-- platform's log. Returns the tenant's slug.
CREATE FUNCTION platform_finish_tenant_removal(p_closure uuid, p_keep_until date, p_summary jsonb) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant   uuid := bms_removing_tenant();
    v_operator uuid;
    v_slug     text;
BEGIN
    IF p_keep_until IS NULL OR p_keep_until < current_date THEN
        RAISE EXCEPTION 'keep_until is a day to come' USING ERRCODE = 'check_violation';
    END IF;
    UPDATE tenant_closures k SET status = 'removed', removed_at = now(), keep_until = p_keep_until
     WHERE k.id = p_closure AND k.tenant_id = v_tenant AND k.status = 'removing'
    RETURNING k.removed_by_platform_user_id INTO v_operator;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'no removal under way for this request' USING ERRCODE = 'insufficient_privilege';
    END IF;
    UPDATE tenants SET status = 'closed', updated_at = now() WHERE id = v_tenant RETURNING slug INTO v_slug;
    UPDATE subscriptions SET status = 'cancelled', ended_on = coalesce(ended_on, current_date), updated_at = now()
     WHERE tenant_id = v_tenant;
    UPDATE onboarding_applications
       SET contact_name = 'Removed', contact_email = 'removed-' || id::text || '@removed.invalid',
           contact_phone_e164 = '+100000000', message = NULL, operator_note = NULL, applicant_reply = NULL,
           activation_note = NULL, link_token_hash = NULL, updated_at = now()
     WHERE activated_tenant = v_tenant;
    INSERT INTO platform_audit_log (id, action, platform_user_id, tenant_id, data)
    SELECT gen_random_uuid(), 'platform.tenant.removed', v_operator, v_tenant,
           jsonb_build_object('closure_id', p_closure, 'keep_until', p_keep_until, 'erased', p_summary,
                              'reason', k.removal_reason)
      FROM tenant_closures k WHERE k.id = p_closure;
    PERFORM set_config('bms.erasing_tenant', '', true);
    RETURN v_slug;
END
$$;

-- The vertical erasers. They live here with the core's because their tables are append-only or guarded and only a definer
-- function may change them; each is called by its own module's eraser bean, so the core never names a vertical in code.

CREATE FUNCTION lending_erase_personal_data() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid := bms_removing_tenant();
    v_rows   integer := 0;
    v_n      integer;
BEGIN
    DELETE FROM lending_next_of_kin WHERE tenant_id = v_tenant;  GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE lending_members
       SET full_name = 'Member ' || member_no, first_name = NULL, last_name = NULL, phone_e164 = '+100000000',
           alt_phone_e164 = NULL, id_type = 'none', national_id = NULL, other_id_number = NULL, date_of_birth = NULL,
           gender = 'unspecified', marital_status = 'unknown', district = NULL, sub_county = NULL, village = NULL,
           location = NULL, occupation = NULL, other_income_source = NULL, monthly_income_minor = NULL,
           blacklist_reason = NULL, updated_at = now()
     WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE lending_collateral_items
       SET description = 'Removed', owner_name = NULL,
           reference_no = CASE WHEN reference_no IS NULL THEN NULL ELSE 'REMOVED-' || id::text END,
           reference_no_normalised = CASE WHEN reference_no_normalised IS NULL THEN NULL ELSE 'REMOVED-' || id::text END,
           storage_location = CASE WHEN storage_location IS NULL THEN NULL ELSE 'Removed' END
     WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE lending_collateral_valuations SET valuer_name = NULL, note = NULL WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE lending_collateral_events SET counterparty_name = NULL, note = NULL, location = NULL WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE lending_collection_actions SET note = NULL WHERE tenant_id = v_tenant AND note IS NOT NULL;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE lending_loan_appraisals SET visit_notes = NULL WHERE tenant_id = v_tenant AND visit_notes IS NOT NULL;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE lending_insights_digest_settings SET email_recipients = '{}', telegram_chat_id = NULL WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    RETURN v_rows;
END
$$;

CREATE FUNCTION retail_erase_personal_data() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid := bms_removing_tenant();
    v_rows   integer := 0;
    v_n      integer;
BEGIN
    -- Names become numbered placeholders, so the kept money records still tell one customer, supplier or person from another.
    UPDATE retail_customers c SET name = 'Customer ' || lpad(n.no::text, 4, '0'), contact = NULL, updated_at = now()
      FROM (SELECT id, row_number() OVER (ORDER BY created_at, id) AS no FROM retail_customers WHERE tenant_id = v_tenant) n
     WHERE c.tenant_id = v_tenant AND c.id = n.id;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE retail_suppliers c SET name = 'Supplier ' || lpad(n.no::text, 4, '0'), contact = NULL, updated_at = now()
      FROM (SELECT id, row_number() OVER (ORDER BY created_at, id) AS no FROM retail_suppliers WHERE tenant_id = v_tenant) n
     WHERE c.tenant_id = v_tenant AND c.id = n.id;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE retail_cash_parties c SET name = 'Person ' || lpad(n.no::text, 4, '0'), contact = NULL, updated_at = now()
      FROM (SELECT id, row_number() OVER (ORDER BY created_at, id) AS no FROM retail_cash_parties WHERE tenant_id = v_tenant) n
     WHERE c.tenant_id = v_tenant AND c.id = n.id;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE retail_sales SET buyer_name = CASE WHEN buyer_name IS NULL THEN NULL ELSE 'Buyer' END, buyer_contact = NULL
     WHERE tenant_id = v_tenant AND (buyer_name IS NOT NULL OR buyer_contact IS NOT NULL);
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE retail_quotations
       SET buyer_name = CASE WHEN buyer_name IS NULL THEN NULL ELSE 'Buyer' END, buyer_contact = NULL, notes = NULL
     WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    -- The document keeps its printed snapshot (lines, totals, the business's own header) without the buyer's name and contact;
    -- any other key of the buyer, the TIN a tax invoice must show among them (owner decision D46), stays.
    UPDATE retail_documents
       SET buyer_name = CASE WHEN buyer_name IS NULL THEN NULL ELSE 'Buyer' END,
           snapshot = CASE WHEN jsonb_typeof(snapshot -> 'buyer') = 'object'
                           THEN jsonb_set(jsonb_set(snapshot, '{buyer,name}',
                                                    CASE WHEN snapshot -> 'buyer' ->> 'name' IS NULL THEN 'null'::jsonb
                                                         ELSE '"Buyer"'::jsonb END),
                                          '{buyer,contact}', 'null'::jsonb)
                           ELSE snapshot END
     WHERE tenant_id = v_tenant AND (buyer_name IS NOT NULL OR snapshot ? 'buyer');
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE retail_credit_note_issues SET buyer_name = 'Buyer', buyer_contact = NULL WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE retail_customer_orders
       SET customer_name = 'Customer', customer_phone = NULL, notes = NULL,
           delivered_by = CASE WHEN delivered_by IS NULL THEN NULL ELSE 'Removed' END
     WHERE tenant_id = v_tenant;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE retail_customer_order_events SET note = NULL WHERE tenant_id = v_tenant AND note IS NOT NULL;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    UPDATE retail_advances SET note = NULL WHERE tenant_id = v_tenant AND note IS NOT NULL;
    GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    RETURN v_rows;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 6. The guard triggers of the business's tables: the bypass that only a migration had (session_user bms_owner with
--    bms.allow_mutation) also lets a removal or a purge through, for that business only (bms_erasing). The functions are
--    read from the catalogue, rewritten from their current definitions with exactly that one condition added, and checked
--    afterwards. A later migration that replaces one of them must keep it.
-- ---------------------------------------------------------------------------------------------

DO $$
DECLARE
    v_fn  text;
    v_def text;
    v_old constant text := 'session_user = ''bms_owner'' AND coalesce(current_setting(''bms.allow_mutation'', true), '''') = ''on''';
BEGIN
    -- Every function of a BEFORE UPDATE or DELETE row trigger on a business's table (tenant_closures' own guard aside, it lets
    -- bms_owner through already). One that has the owner bypass gets bms_erasing beside it; any other gets, as its first
    -- statement, a return of the row for a change made inside a removal or a purge. Nothing else in them changes.
    FOR v_fn IN
        SELECT DISTINCT p.oid::regproc::text
          FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_proc p ON p.oid = t.tgfoid
          JOIN pg_namespace ns ON ns.oid = c.relnamespace
         WHERE NOT t.tgisinternal AND ns.nspname = 'public'
           AND (t.tgtype & 1) = 1 AND (t.tgtype & 2) = 2 AND (t.tgtype & 24) <> 0
           AND p.proname <> 'tenant_closures_guard'
           AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname IN ('tenant_id', 'id')
                                                      AND NOT a.attisdropped)
         ORDER BY 1
    LOOP
        v_def := pg_get_functiondef(v_fn::regproc);
        IF position('bms_erasing(' IN v_def) > 0 THEN
            CONTINUE;
        END IF;
        IF position(v_old IN v_def) > 0 THEN
            v_def := replace(v_def, v_old, '(' || v_old || ') OR bms_erasing(to_jsonb(OLD))');
        ELSIF v_def ~ E'\nBEGIN\n' THEN
            v_def := regexp_replace(v_def, E'\nBEGIN\n',
                E'\nBEGIN\n    IF TG_OP IN (''UPDATE'', ''DELETE'') AND bms_erasing(to_jsonb(OLD)) THEN\n        IF TG_OP = ''DELETE'' THEN\n            RETURN OLD;\n        END IF;\n        RETURN NEW;\n    END IF;\n');
        ELSE
            RAISE EXCEPTION 'V102: % has no body to extend', v_fn;
        END IF;
        EXECUTE v_def;
        IF position('bms_erasing(to_jsonb(OLD))' IN pg_get_functiondef(v_fn::regproc)) = 0 THEN
            RAISE EXCEPTION 'V102: % was not extended', v_fn;
        END IF;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 8. The purge (owner decision D46): once a removed business is past keep_until, the daily job deletes every row it still
--    has, money records included, except its tenants row, its users (anonymised placeholders the request points at) and its
--    tenant_closures rows, which the platform's own history refers to. Unbound (bms_owner bypasses row-level security, so every
--    statement names the business). Tables are read from the catalogue and deleted in passes, a table whose rows are still
--    referenced waiting for a later pass, so a table added later is purged without a change here. Returns the rows deleted.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION platform_closures_due_for_purge() RETURNS SETOF uuid
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'listed by the platform job only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY SELECT k.id FROM tenant_closures k JOIN tenants t ON t.id = k.tenant_id
                  WHERE k.status = 'removed' AND k.keep_until < (now() AT TIME ZONE t.timezone)::date
                  ORDER BY k.keep_until, k.id;
END
$$;

CREATE FUNCTION platform_purge_closed_tenant(p_closure uuid) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant  uuid;
    v_table   text;
    v_total   integer := 0;
    v_n       integer;
    v_pass    integer := 0;
    v_blocked integer;
    v_moved   integer;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'purged by the platform job only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    UPDATE tenant_closures k SET status = 'purging'
      FROM tenants t
     WHERE k.id = p_closure AND t.id = k.tenant_id AND k.status = 'removed'
       AND k.keep_until < (now() AT TIME ZONE t.timezone)::date
    RETURNING k.tenant_id INTO v_tenant;
    IF v_tenant IS NULL THEN
        RETURN 0;
    END IF;
    PERFORM set_config('bms.erasing_tenant', v_tenant::text, true);
    LOOP
        v_pass := v_pass + 1;
        v_blocked := 0;
        v_moved := 0;
        FOR v_table IN
            SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
              JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
             WHERE n.nspname = 'public' AND c.relkind = 'r'
               AND c.relname NOT IN ('tenants', 'users', 'tenant_closures', 'platform_audit_log')
             ORDER BY c.relname
        LOOP
            BEGIN
                EXECUTE format('DELETE FROM %I WHERE tenant_id = $1', v_table) USING v_tenant;
                GET DIAGNOSTICS v_n = ROW_COUNT;
                v_moved := v_moved + v_n;
            EXCEPTION WHEN foreign_key_violation THEN
                v_blocked := v_blocked + 1;
            END;
        END LOOP;
        v_total := v_total + v_moved;
        EXIT WHEN v_blocked = 0;
        IF v_moved = 0 OR v_pass > 50 THEN
            RAISE EXCEPTION 'the purge of tenant % is stuck on % table(s) still referenced', v_tenant, v_blocked;
        END IF;
    END LOOP;
    UPDATE tenant_closures k SET status = 'purged', purged_at = now() WHERE k.id = p_closure;
    INSERT INTO platform_audit_log (id, action, tenant_id, data)
    VALUES (gen_random_uuid(), 'platform.tenant.purged', v_tenant,
            jsonb_build_object('closure_id', p_closure, 'rows_deleted', v_total));
    PERFORM set_config('bms.erasing_tenant', '', true);
    RETURN v_total;
END
$$;

REVOKE ALL ON FUNCTION platform_closures_due_for_purge() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_purge_closed_tenant(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_closures_due_for_purge() TO bms_app;
GRANT EXECUTE ON FUNCTION platform_purge_closed_tenant(uuid) TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- 7. The operator's lists: the closure requests across businesses, the business of one request, and the businesses with no
--    Owner with their active administrators. Read across businesses, so a tenant-bound transaction is refused.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION platform_closures() RETURNS TABLE (
    id uuid, tenant_id uuid, tenant_slug text, tenant_name text, status text, requested_at timestamptz, remove_after date,
    requested_by_name text, cancelled_at timestamptz, removed_at timestamptz, keep_until date, removed_by_name text,
    timezone text)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'closure requests are read across businesses by the platform operator only'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
    SELECT k.id, k.tenant_id, t.slug::text, t.name::text, k.status, k.requested_at, k.remove_after, u.full_name::text,
           k.cancelled_at, k.removed_at, k.keep_until, p.full_name::text, t.timezone
      FROM tenant_closures k
      JOIN tenants t ON t.id = k.tenant_id
      JOIN users u ON u.tenant_id = k.tenant_id AND u.id = k.requested_by
      LEFT JOIN platform_users p ON p.id = k.removed_by_platform_user_id
     WHERE k.status IN ('requested', 'removing') OR k.requested_at > now() - interval '180 days'
     ORDER BY k.status = 'requested' DESC, k.remove_after, k.requested_at DESC, k.id
     LIMIT 500;
END
$$;

CREATE FUNCTION platform_closure_tenant(p_id uuid) RETURNS uuid
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'read by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN (SELECT k.tenant_id FROM tenant_closures k WHERE k.id = p_id);
END
$$;

-- Every active or suspended business with no Owner (ADR-062 decision 7 left some, named only in the migration log), newest first
-- and at most 500, one row per active administrator (any one of whom the operator may make the Owner), or one row with no administrator.
CREATE FUNCTION platform_tenants_without_owner() RETURNS TABLE (
    tenant_id uuid, tenant_slug text, tenant_name text, tenant_status text, created_at timestamptz, admin_id uuid,
    admin_name text, admin_email text, admin_last_login_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'read across businesses by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
    SELECT t.id, t.slug::text, t.name::text, t.status, t.created_at, a.id, a.full_name::text, a.email::text, a.last_login_at
      FROM (SELECT * FROM tenants x
             WHERE x.status IN ('active', 'suspended')
               AND NOT EXISTS (SELECT 1 FROM users o WHERE o.tenant_id = x.id AND o.is_owner)
             ORDER BY x.created_at DESC, x.id
             LIMIT 500) t
      LEFT JOIN LATERAL (
            SELECT u.id, u.full_name, u.email, u.last_login_at, u.created_at FROM users u
             WHERE u.tenant_id = t.id AND u.kind = 'staff' AND u.status = 'active'
               AND EXISTS (SELECT 1 FROM user_role_assignments r
                            WHERE r.tenant_id = u.tenant_id AND r.user_id = u.id AND r.role_key = 'tenant_admin'
                              AND r.revoked_at IS NULL)) a ON true
     ORDER BY t.created_at DESC, t.id, a.created_at, a.id;
END
$$;

REVOKE ALL ON FUNCTION platform_begin_tenant_removal(uuid, uuid, uuid, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_mark_tenant_removal(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_erase_core_personal_data() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_finish_tenant_removal(uuid, date, jsonb) FROM PUBLIC;
REVOKE ALL ON FUNCTION lending_erase_personal_data() FROM PUBLIC;
REVOKE ALL ON FUNCTION retail_erase_personal_data() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_closures() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_closure_tenant(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_tenants_without_owner() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_begin_tenant_removal(uuid, uuid, uuid, text) TO bms_app;
GRANT EXECUTE ON FUNCTION app_mark_tenant_removal(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION app_erase_core_personal_data() TO bms_app;
GRANT EXECUTE ON FUNCTION platform_finish_tenant_removal(uuid, date, jsonb) TO bms_app;
GRANT EXECUTE ON FUNCTION lending_erase_personal_data() TO bms_app;
GRANT EXECUTE ON FUNCTION retail_erase_personal_data() TO bms_app;
GRANT EXECUTE ON FUNCTION platform_closures() TO bms_app;
GRANT EXECUTE ON FUNCTION platform_closure_tenant(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_tenants_without_owner() TO bms_app;
