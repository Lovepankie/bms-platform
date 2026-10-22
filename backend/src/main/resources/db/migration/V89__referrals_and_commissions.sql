-- V89: referral links and commission tracking (issue #464, ADR-069). V81 to V88 are claimed by open pull requests (lending
-- V81 to V84, payments V85 and V86, offline sales V87, shop logos V88); Flyway runs with outOfOrder off, so this file is renamed
-- at merge if those land after it.
--
-- Expand only, safe on a populated database. Every business gets a referral code (a trigger on tenants, and a backfill for the
-- businesses that exist); the public sign-up records the code an applicant brings; Activate turns it into a referral; the
-- platform operator sets or corrects the referrer of a business, sets the commission rule (a platform default and an override
-- per referral), and records and marks paid the commission lines. The app records commissions and never pays them. No rate,
-- no amount and no rule is written here: the default rule starts empty and nothing is worked out until the operator sets one.
--
-- Tables:
--   referral_codes             the code of each business (tenant-owned, forced RLS, SELECT for bms_app: a business reads its own)
--   tenant_referrals           who referred whom; tenant_id is the REFERRER, so a business reads only the referrals it made
--   referral_commission_lines  what a referrer earned; tenant_id is the REFERRER; never deleted, paid once
--   referral_commission_rules  the rules the operator typed (platform table, closed to bms_app, append-only)
--   referral_settings          the one row holding the platform default rule (platform table, closed to bms_app)
-- and two columns on onboarding_applications (the code an applicant typed and the business it belongs to).
--
-- Every write goes through a SECURITY DEFINER function owned by bms_owner with a fixed search_path (ADR-016). bms_owner has
-- BYPASSRLS, so these functions see every business; each one that acts across businesses refuses a transaction bound to a
-- tenant, except the commission worked out for a payment, which runs bound to the paying business and checks the payment is
-- that business's own.
--
-- Locks, said plainly. CREATE TRIGGER on tenants and ALTER TABLE onboarding_applications ADD COLUMN (no default, so no
-- rewrite) take SHARE ROW EXCLUSIVE and ACCESS EXCLUSIVE until the commit; the backfill writes one small row per business. The
-- five second lock_timeout makes a busy table fail the deploy instead of queueing sign-ins behind it.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- 1. Referral codes. Up to five letters of the slug and two characters from an alphabet without 0, 1, I and O (read out on
--    the phone), for example DODOS7K; when ten tries are taken, four characters; then eight random characters. Unique without
--    case: codes are stored in upper case and every lookup upper-cases what was typed.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE referral_codes (
    tenant_id  uuid        NOT NULL PRIMARY KEY REFERENCES tenants (id),
    code       varchar(10) NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9]{4,10}$'),
    created_at timestamptz NOT NULL DEFAULT now()
);
SELECT bms_apply_tenant_rls('referral_codes');
SELECT bms_grant_app('referral_codes', 'SELECT');
SELECT bms_make_append_only('referral_codes');

CREATE FUNCTION bms_referral_code_candidate(p_slug text, p_attempt integer) RETURNS text
LANGUAGE plpgsql VOLATILE SET search_path = public, pg_temp AS $$
DECLARE
    v_alphabet constant text := '23456789ABCDEFGHJKLMNPQRSTUVWXYZ';
    v_prefix   text := left(upper(regexp_replace(coalesce(p_slug, ''), '[^a-z]', '', 'g')), 5);
    v_random   integer;
    v_code     text;
BEGIN
    IF length(v_prefix) < 2 OR p_attempt >= 20 THEN
        v_prefix := '';
        v_random := 8;
    ELSIF p_attempt >= 10 THEN
        v_random := 4;
    ELSE
        v_random := 2;
    END IF;
    v_code := v_prefix;
    FOR i IN 1 .. v_random LOOP
        v_code := v_code || substr(v_alphabet, 1 + floor(random() * length(v_alphabet))::integer, 1);
    END LOOP;
    RETURN v_code;
END
$$;

-- Gives one business its code, unless it has one. Returns the code.
CREATE FUNCTION bms_assign_referral_code(p_tenant_id uuid, p_slug text) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_code text;
BEGIN
    SELECT code INTO v_code FROM referral_codes WHERE tenant_id = p_tenant_id;
    IF FOUND THEN
        RETURN v_code;
    END IF;
    FOR v_attempt IN 0 .. 60 LOOP
        v_code := bms_referral_code_candidate(p_slug, v_attempt);
        INSERT INTO referral_codes (tenant_id, code) VALUES (p_tenant_id, v_code) ON CONFLICT DO NOTHING;
        IF FOUND THEN
            RETURN v_code;
        END IF;
    END LOOP;
    RAISE EXCEPTION 'no free referral code for tenant %', p_tenant_id;
END
$$;

CREATE FUNCTION bms_referral_code_on_new_tenant() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    PERFORM bms_assign_referral_code(NEW.id, NEW.slug);
    RETURN NULL;
END
$$;
REVOKE ALL ON FUNCTION bms_referral_code_candidate(text, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION bms_assign_referral_code(uuid, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION bms_referral_code_on_new_tenant() FROM PUBLIC;
CREATE TRIGGER referral_code_on_new_tenant AFTER INSERT ON tenants
    FOR EACH ROW EXECUTE FUNCTION bms_referral_code_on_new_tenant();

-- Every business that exists gets its code now, oldest first.
DO $$
DECLARE
    v record;
BEGIN
    FOR v IN SELECT id, slug FROM tenants ORDER BY created_at, id LOOP
        PERFORM bms_assign_referral_code(v.id, v.slug);
    END LOOP;
END
$$;

-- The business a code belongs to, for the public sign-up (no tenant is bound there). Null for an unknown code.
CREATE FUNCTION referral_code_tenant(p_code text) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT c.tenant_id FROM referral_codes c WHERE c.code = upper(btrim(p_code))
$$;

-- ---------------------------------------------------------------------------------------------
-- 2. The code on an application. Kept with the application until Activate makes it a referral; the business it belongs to is
--    resolved when the applicant submits, so a code typed wrong is refused there and then.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE onboarding_applications
    ADD COLUMN referral_code      varchar(10),
    ADD COLUMN referrer_tenant_id uuid REFERENCES tenants (id);

-- Sets the referrer of an application (the caller resolved the code to the business): one just created, or the open one a
-- returning applicant brings a code to (ADR-069 decision 13). Only while it has no referrer and is still submitted (not yet
-- in review), so a code typed later never replaces the first one.
CREATE FUNCTION onboarding_application_set_referrer(p_id uuid, p_tenant_id uuid, p_code text) RETURNS void
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    UPDATE onboarding_applications SET referral_code = upper(btrim(p_code)), referrer_tenant_id = p_tenant_id
     WHERE id = p_id AND referrer_tenant_id IS NULL AND status = 'submitted'
$$;

-- True when the email or the phone is that of the Owner or an administrator (not deactivated) of the business: a business is
-- not referred by its own people (ADR-069 decision 10). Emails compare as the sign-up compares them (onboarding_email_key),
-- phones in E.164.
CREATE FUNCTION referral_is_self(p_referrer uuid, p_email text, p_phone text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM users u
         WHERE u.tenant_id = p_referrer AND u.kind = 'staff' AND u.status <> 'deactivated'
           AND (u.is_owner OR EXISTS (SELECT 1 FROM user_role_assignments ra
                                       WHERE ra.tenant_id = u.tenant_id AND ra.user_id = u.id
                                         AND ra.role_key = 'tenant_admin' AND ra.revoked_at IS NULL))
           AND ((u.email IS NOT NULL AND onboarding_email_key(u.email) = onboarding_email_key(p_email))
                OR (u.phone_e164 IS NOT NULL AND u.phone_e164 = p_phone)))
$$;

-- The referrer of one application for the operator, or no row.
CREATE FUNCTION onboarding_application_referrer(p_id uuid)
RETURNS TABLE (code text, tenant_id uuid, tenant_slug text, tenant_name text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.referral_code::text, t.id, t.slug::text, t.name
      FROM onboarding_applications a JOIN tenants t ON t.id = a.referrer_tenant_id
     WHERE a.id = p_id
$$;

-- ---------------------------------------------------------------------------------------------
-- 3. Commission rules (ADR-069 decision 4). A rule is typed by the operator and never changed: a change is a new rule.
--    percent_of_payment: percent_bp (basis points, 1000 = 10%) of each subscription payment, for the first `months` paid
--      months of the referred business (null: no end), with an optional cap on the referral's total in `currency`.
--    fixed_first_payment: `amount_minor` once, on the referred business's first payment.
--    fixed_per_month: `amount_minor` for each paid month, for the first `months` paid months (null: no end).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE referral_commission_rules (
    id                          uuid        NOT NULL PRIMARY KEY,
    kind                        text        NOT NULL
        CHECK (kind IN ('percent_of_payment', 'fixed_first_payment', 'fixed_per_month')),
    percent_bp                  integer     CHECK (percent_bp BETWEEN 1 AND 10000),
    amount_minor                bigint      CHECK (amount_minor BETWEEN 1 AND 50000000),
    currency                    char(3)     REFERENCES currencies (code),
    months                      integer     CHECK (months BETWEEN 1 AND 120),
    cap_minor                   bigint      CHECK (cap_minor BETWEEN 1 AND 1000000000),
    created_by_platform_user_id uuid        NOT NULL REFERENCES platform_users (id),
    created_at                  timestamptz NOT NULL DEFAULT now(),
    CHECK (CASE kind
        WHEN 'percent_of_payment' THEN percent_bp IS NOT NULL AND amount_minor IS NULL
            AND (cap_minor IS NULL OR currency IS NOT NULL)
        WHEN 'fixed_first_payment' THEN amount_minor IS NOT NULL AND currency IS NOT NULL AND percent_bp IS NULL
            AND months IS NULL AND cap_minor IS NULL
        ELSE amount_minor IS NOT NULL AND currency IS NOT NULL AND percent_bp IS NULL AND cap_minor IS NULL
    END)
);
ALTER TABLE referral_commission_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE referral_commission_rules FORCE ROW LEVEL SECURITY;
REVOKE ALL ON referral_commission_rules FROM PUBLIC;
SELECT bms_make_append_only('referral_commission_rules');

-- The platform default rule: one row, empty until the operator sets a rule.
CREATE TABLE referral_settings (
    id                          boolean     NOT NULL PRIMARY KEY DEFAULT true CHECK (id),
    default_rule_id             uuid        REFERENCES referral_commission_rules (id),
    updated_by_platform_user_id uuid        REFERENCES platform_users (id),
    updated_at                  timestamptz
);
INSERT INTO referral_settings (id) VALUES (true);
ALTER TABLE referral_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE referral_settings FORCE ROW LEVEL SECURITY;
REVOKE ALL ON referral_settings FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- 4. Referrals. One current referrer per business (ended_at null); a correction ends the current row and starts a new one, so
--    the lines a referrer already earned stay theirs. A business never refers itself (a CHECK, as well as the service).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE tenant_referrals (
    id                          uuid        NOT NULL PRIMARY KEY,
    -- The referrer: the business this row belongs to, and the only one that reads it.
    tenant_id                   uuid        NOT NULL REFERENCES tenants (id),
    referred_tenant_id          uuid        NOT NULL REFERENCES tenants (id),
    code                        varchar(10) NOT NULL,
    source                      text        NOT NULL CHECK (source IN ('sign_up', 'operator')),
    application_id              uuid,
    -- The operator's override for this referral; null follows the platform default.
    rule_id                     uuid        REFERENCES referral_commission_rules (id),
    started_at                  timestamptz NOT NULL DEFAULT now(),
    ended_at                    timestamptz,
    created_by_platform_user_id uuid        REFERENCES platform_users (id),
    UNIQUE (tenant_id, id),
    CHECK (tenant_id <> referred_tenant_id),
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);
CREATE UNIQUE INDEX tenant_referrals_one_current ON tenant_referrals (referred_tenant_id) WHERE ended_at IS NULL;
CREATE INDEX tenant_referrals_by_referrer ON tenant_referrals (tenant_id, started_at DESC);
SELECT bms_apply_tenant_rls('tenant_referrals');
SELECT bms_grant_app('tenant_referrals', 'SELECT');

-- Only the override and the end of a referral change, and a referral ends once.
CREATE FUNCTION bms_referral_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a referral is never deleted' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.ended_at IS NOT NULL AND NEW.ended_at IS DISTINCT FROM OLD.ended_at
       OR (to_jsonb(NEW) - ARRAY['rule_id', 'ended_at']) IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['rule_id', 'ended_at']) THEN
        RAISE EXCEPTION 'only the rule and the end of a referral change' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER referral_guard BEFORE UPDATE OR DELETE ON tenant_referrals
    FOR EACH ROW EXECUTE FUNCTION bms_referral_guard();

-- ---------------------------------------------------------------------------------------------
-- 5. Commission lines. kind payment: worked out from the rule in force when the operator recorded the referred business's
--    payment, with a snapshot of that rule; manual: an amount the operator typed; reversal: the exact opposite of one line.
--    Never deleted, never edited; the paid columns are set once.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE referral_commission_lines (
    id                          uuid         NOT NULL PRIMARY KEY,
    -- The referrer, as on the referral.
    tenant_id                   uuid         NOT NULL REFERENCES tenants (id),
    referral_id                 uuid         NOT NULL,
    kind                        text         NOT NULL CHECK (kind IN ('payment', 'manual', 'reversal')),
    payment_id                  uuid         REFERENCES subscription_payments (id),
    reverses_line_id            uuid         UNIQUE,
    amount_minor                bigint       NOT NULL CHECK (amount_minor <> 0 AND abs(amount_minor) <= 1000000000),
    currency                    char(3)      NOT NULL REFERENCES currencies (code),
    months_counted              integer      CHECK (months_counted >= 0),
    rule_snapshot               jsonb,
    -- The referrer business was suspended or cancelled when the line was worked out: the operator decides before paying.
    referrer_inactive           boolean      NOT NULL DEFAULT false,
    note                        varchar(500),
    paid_on                     date,
    paid_method                 text         CHECK (paid_method IN ('mtn_momo', 'airtel_money', 'bank_transfer', 'cash', 'other')),
    paid_reference              varchar(100),
    paid_note                   varchar(500),
    paid_at                     timestamptz,
    paid_by_platform_user_id    uuid         REFERENCES platform_users (id),
    created_by_platform_user_id uuid         REFERENCES platform_users (id),
    created_at                  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, referral_id) REFERENCES tenant_referrals (tenant_id, id),
    FOREIGN KEY (tenant_id, reverses_line_id) REFERENCES referral_commission_lines (tenant_id, id),
    CHECK (CASE kind
        WHEN 'payment' THEN payment_id IS NOT NULL AND rule_snapshot IS NOT NULL AND amount_minor > 0
            AND reverses_line_id IS NULL
        WHEN 'manual' THEN payment_id IS NULL AND amount_minor > 0 AND reverses_line_id IS NULL AND note IS NOT NULL
        ELSE reverses_line_id IS NOT NULL AND amount_minor < 0 AND payment_id IS NULL AND note IS NOT NULL
    END),
    CHECK ((paid_on IS NULL) = (paid_method IS NULL) AND (paid_on IS NULL) = (paid_at IS NULL))
);
CREATE UNIQUE INDEX referral_commission_lines_one_per_payment ON referral_commission_lines (payment_id) WHERE kind = 'payment';
CREATE INDEX referral_commission_lines_by_referral ON referral_commission_lines (tenant_id, referral_id, created_at);
SELECT bms_apply_tenant_rls('referral_commission_lines');
SELECT bms_grant_app('referral_commission_lines', 'SELECT');

-- Nothing is deleted, and an update only marks a line paid, once.
CREATE FUNCTION bms_commission_line_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_paid constant text[] := ARRAY['paid_on', 'paid_method', 'paid_reference', 'paid_note', 'paid_at',
                                    'paid_by_platform_user_id'];
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a commission line is never deleted' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF OLD.paid_on IS NOT NULL OR NEW.paid_on IS NULL OR (to_jsonb(NEW) - v_paid) IS DISTINCT FROM (to_jsonb(OLD) - v_paid) THEN
        RAISE EXCEPTION 'a commission line is only marked paid, once' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER commission_line_guard BEFORE UPDATE OR DELETE ON referral_commission_lines
    FOR EACH ROW EXECUTE FUNCTION bms_commission_line_guard();

-- What each payment of a referred business gave under its referral (ADR-069 decision 9): kept so that a payment that earned
-- nothing because its currency differed from the rule's never uses up the rule's months later, whatever the rule is then.
-- Platform table, closed to bms_app, append-only.
CREATE TABLE referral_payment_outcomes (
    payment_id  uuid        NOT NULL PRIMARY KEY REFERENCES subscription_payments (id),
    referral_id uuid        NOT NULL REFERENCES tenant_referrals (id),
    outcome     text        NOT NULL CHECK (outcome IN ('added', 'no_rule', 'not_first_payment', 'months_used',
                                                        'currency_differs', 'cap_reached', 'zero')),
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX referral_payment_outcomes_by_referral ON referral_payment_outcomes (referral_id);
ALTER TABLE referral_payment_outcomes ENABLE ROW LEVEL SECURITY;
ALTER TABLE referral_payment_outcomes FORCE ROW LEVEL SECURITY;
REVOKE ALL ON referral_payment_outcomes FROM PUBLIC;
SELECT bms_make_append_only('referral_payment_outcomes');

-- ---------------------------------------------------------------------------------------------
-- 6. Referral reads. Each returns the totals per currency as a JSON array of {currency, earned_minor, paid_minor, due_minor}:
--    earned is every line (reversals count against it), paid the lines marked paid, due the difference.
--    status: applied (an open application), active (a business with no payment yet), paying (it has paid), suspended or
--    cancelled (the referred business's subscription, ADR-069 decision 12), ended (the operator moved the referral to another
--    referrer; shown only while it carries lines).
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION bms_referral_status(p_ended_at timestamptz, p_referred uuid) RETURNS text
LANGUAGE sql STABLE SET search_path = public, pg_temp AS $$
    SELECT CASE WHEN p_ended_at IS NOT NULL THEN 'ended'
                WHEN t.status = 'suspended' THEN 'suspended'
                WHEN s.status IN ('suspended', 'cancelled') THEN s.status
                WHEN EXISTS (SELECT 1 FROM subscription_payments sp
                              WHERE sp.tenant_id = p_referred AND sp.status = 'confirmed') THEN 'paying'
                ELSE 'active' END
      FROM tenants t
      LEFT JOIN LATERAL (SELECT x.status FROM subscriptions x WHERE x.tenant_id = t.id
                          ORDER BY x.created_at DESC LIMIT 1) s ON true
     WHERE t.id = p_referred
$$;
REVOKE ALL ON FUNCTION bms_referral_status(timestamptz, uuid) FROM PUBLIC;

CREATE FUNCTION bms_referral_totals(p_referral_id uuid) RETURNS jsonb
LANGUAGE sql STABLE SET search_path = public, pg_temp AS $$
    SELECT coalesce(jsonb_agg(jsonb_build_object(
               'currency', t.currency, 'earned_minor', t.earned, 'paid_minor', t.paid, 'due_minor', t.earned - t.paid)
               ORDER BY t.currency), '[]'::jsonb)
      FROM (SELECT l.currency::text AS currency, sum(l.amount_minor) AS earned,
                   coalesce(sum(l.amount_minor) FILTER (WHERE l.paid_on IS NOT NULL), 0) AS paid
              FROM referral_commission_lines l WHERE l.referral_id = p_referral_id
             GROUP BY l.currency) t
$$;
REVOKE ALL ON FUNCTION bms_referral_totals(uuid) FROM PUBLIC;

-- Every referral and every open application with a code, newest first, for the operator. Filters: one referrer, one referred
-- business (its referrals only), one status, commission 'due' (something left to pay) or 'paid' (something paid), one referral.
CREATE FUNCTION platform_referrals_list(
    p_referrer uuid, p_referred uuid, p_status text, p_commission text, p_referral_id uuid, p_limit integer,
    p_offset integer)
RETURNS TABLE (
    referral_id uuid, application_id uuid, referrer_tenant_id uuid, referrer_slug text, referrer_name text, code text,
    referred_tenant_id uuid, referred_slug text, referred_name text, status text, source text, joined_at timestamptz,
    started_at timestamptz, ended_at timestamptz, rule_id uuid, totals jsonb)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'referrals are read across businesses by the platform operator only'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
    WITH refs AS (
        SELECT r.id AS referral_id, r.application_id, r.tenant_id AS referrer_tenant_id, rt.slug::text AS referrer_slug,
               rt.name AS referrer_name, r.code::text AS code, r.referred_tenant_id, dt.slug::text AS referred_slug,
               dt.name AS referred_name,
               bms_referral_status(r.ended_at, r.referred_tenant_id) AS status,
               r.source, dt.created_at AS joined_at, r.started_at, r.ended_at, r.rule_id,
               bms_referral_totals(r.id) AS totals
          FROM tenant_referrals r
          JOIN tenants rt ON rt.id = r.tenant_id
          JOIN tenants dt ON dt.id = r.referred_tenant_id
         WHERE (p_referral_id IS NULL OR r.id = p_referral_id) AND (p_referred IS NULL OR r.referred_tenant_id = p_referred)
        UNION ALL
        SELECT NULL, a.id, a.referrer_tenant_id, rt.slug::text, rt.name, a.referral_code::text, NULL, NULL,
               a.business_name::text, 'applied', 'sign_up', a.created_at, a.created_at, NULL, NULL, '[]'::jsonb
          FROM onboarding_applications a
          JOIN tenants rt ON rt.id = a.referrer_tenant_id
         WHERE a.status IN ('submitted', 'needs_info', 'verified') AND a.email_verified_at IS NOT NULL
           AND p_referral_id IS NULL AND p_referred IS NULL)
    SELECT * FROM refs x
     WHERE (p_referrer IS NULL OR x.referrer_tenant_id = p_referrer)
       AND (p_status IS NULL OR x.status = p_status)
       AND (p_commission IS NULL
            OR (p_commission = 'due' AND EXISTS (SELECT 1 FROM jsonb_array_elements(x.totals) e
                                                  WHERE (e ->> 'due_minor')::bigint <> 0))
            OR (p_commission = 'paid' AND EXISTS (SELECT 1 FROM jsonb_array_elements(x.totals) e
                                                   WHERE (e ->> 'paid_minor')::bigint <> 0)))
     ORDER BY x.started_at DESC, x.referral_id, x.application_id
     LIMIT least(greatest(p_limit, 1), 10000) OFFSET greatest(p_offset, 0);
END
$$;

-- The referrals the bound business made, for its owner and administrators: the referred business's name, when it joined, its
-- status and the commission. Nothing else of the referred business. An ended referral is listed only while it carries lines.
CREATE FUNCTION tenant_referrals_mine() RETURNS TABLE (
    referral_id uuid, referred_name text, joined_at timestamptz, status text, totals jsonb)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT x.referral_id, x.referred_name, x.joined_at, x.status, x.totals
      FROM (SELECT r.id AS referral_id, dt.name AS referred_name, dt.created_at AS joined_at,
                   bms_referral_status(r.ended_at, r.referred_tenant_id) AS status,
                   bms_referral_totals(r.id) AS totals, r.started_at AS sort_at
              FROM tenant_referrals r JOIN tenants dt ON dt.id = r.referred_tenant_id
             WHERE r.tenant_id = current_setting('app.tenant_id')::uuid
               AND (r.ended_at IS NULL
                    OR EXISTS (SELECT 1 FROM referral_commission_lines l WHERE l.referral_id = r.id))
            UNION ALL
            SELECT NULL, a.business_name::text, a.created_at, 'applied', '[]'::jsonb, a.created_at
              FROM onboarding_applications a
             WHERE a.referrer_tenant_id = current_setting('app.tenant_id')::uuid
               AND a.status IN ('submitted', 'needs_info', 'verified') AND a.email_verified_at IS NOT NULL) x
     ORDER BY x.sort_at DESC, x.referral_id
$$;

-- The lines of one referral, oldest first, for the operator. The payment's amount is added to the snapshot here: the row the
-- referrer can read never carries the referred business's payment.
CREATE FUNCTION platform_referral_lines(p_referral_id uuid) RETURNS TABLE (
    id uuid, kind text, payment_id uuid, reverses_line_id uuid, reversed_by_line_id uuid, amount_minor bigint,
    currency text, months_counted integer, rule_snapshot jsonb, referrer_inactive boolean, note text, paid_on date,
    paid_method text, paid_reference text, paid_note text, paid_at timestamptz, paid_by_name text, created_by_name text,
    created_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'referrals are read across businesses by the platform operator only'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
    SELECT l.id, l.kind, l.payment_id, l.reverses_line_id, (SELECT x.id FROM referral_commission_lines x
                                                              WHERE x.reverses_line_id = l.id),
           l.amount_minor, l.currency::text, l.months_counted,
           CASE WHEN sp.id IS NULL THEN l.rule_snapshot
                ELSE l.rule_snapshot || jsonb_build_object('payment_amount_minor', sp.amount_minor) END,
           l.referrer_inactive, l.note::text, l.paid_on, l.paid_method, l.paid_reference::text, l.paid_note::text, l.paid_at,
           pu.full_name, cu.full_name, l.created_at
      FROM referral_commission_lines l
      LEFT JOIN subscription_payments sp ON sp.id = l.payment_id
      LEFT JOIN platform_users pu ON pu.id = l.paid_by_platform_user_id
      LEFT JOIN platform_users cu ON cu.id = l.created_by_platform_user_id
     WHERE l.referral_id = p_referral_id
     ORDER BY l.created_at, l.id;
END
$$;

-- One rule, or the default rule when p_rule_id is null and p_default is true. No row when there is none.
CREATE FUNCTION platform_referral_rule(p_rule_id uuid, p_default boolean) RETURNS TABLE (
    id uuid, kind text, percent_bp integer, amount_minor bigint, currency text, months integer, cap_minor bigint,
    created_by_name text, created_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'commission rules are the platform operator''s' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
    SELECT r.id, r.kind, r.percent_bp, r.amount_minor, r.currency::text, r.months, r.cap_minor, u.full_name, r.created_at
      FROM referral_commission_rules r
      LEFT JOIN platform_users u ON u.id = r.created_by_platform_user_id
     WHERE r.id = CASE WHEN p_default THEN (SELECT s.default_rule_id FROM referral_settings s) ELSE p_rule_id END;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 7. Referral writes.
-- ---------------------------------------------------------------------------------------------

-- Activate: the application's referrer, if any, becomes the new business's referral. outcome: recorded (referral_id is the
-- new referral), none (no code, or the business already has a referrer), or self (the applicant is the Owner or an
-- administrator of the referrer, checked again now: no referral is stored).
CREATE FUNCTION platform_referral_from_application(p_application_id uuid, p_tenant_id uuid, p_platform_user_id uuid)
RETURNS TABLE (referral_id uuid, referrer_tenant_id uuid, outcome text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_code  text;
    v_email text;
    v_phone text;
BEGIN
    SELECT a.referrer_tenant_id, a.referral_code, a.contact_email, a.contact_phone_e164
      INTO referrer_tenant_id, v_code, v_email, v_phone
      FROM onboarding_applications a WHERE a.id = p_application_id;
    IF referrer_tenant_id IS NULL
       OR EXISTS (SELECT 1 FROM tenant_referrals r WHERE r.referred_tenant_id = p_tenant_id AND r.ended_at IS NULL) THEN
        outcome := 'none';
    ELSIF referrer_tenant_id = p_tenant_id OR referral_is_self(referrer_tenant_id, v_email, v_phone) THEN
        outcome := 'self';
    ELSE
        referral_id := gen_random_uuid();
        INSERT INTO tenant_referrals (id, tenant_id, referred_tenant_id, code, source, application_id,
                                      created_by_platform_user_id)
        VALUES (referral_id, referrer_tenant_id, p_tenant_id, v_code, 'sign_up', p_application_id, p_platform_user_id);
        outcome := 'recorded';
    END IF;
    RETURN NEXT;
END
$$;

-- The operator sets, corrects or clears (p_referrer null) the referrer of a business. The current referral ends and a new one
-- starts, so lines already earned stay with the referrer who earned them. The same referrer again changes nothing. Returns
-- the referral that ended and the one that started (either may be null).
CREATE FUNCTION platform_referral_set_referrer(p_tenant_id uuid, p_referrer uuid, p_platform_user_id uuid, p_now timestamptz)
RETURNS TABLE (ended_referral_id uuid, previous_referrer uuid, started_referral_id uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_current tenant_referrals%ROWTYPE;
    v_code    text;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'the referrer is set by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF p_referrer = p_tenant_id THEN
        RAISE EXCEPTION 'referral_self' USING ERRCODE = 'check_violation';
    END IF;
    PERFORM 1 FROM tenants WHERE id = p_tenant_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown tenant' USING ERRCODE = 'no_data_found';
    END IF;
    SELECT * INTO v_current FROM tenant_referrals r WHERE r.referred_tenant_id = p_tenant_id AND r.ended_at IS NULL FOR UPDATE;
    IF v_current.tenant_id IS NOT DISTINCT FROM p_referrer THEN
        RETURN;
    END IF;
    IF v_current.id IS NOT NULL THEN
        UPDATE tenant_referrals SET ended_at = greatest(p_now, v_current.started_at) WHERE id = v_current.id;
        ended_referral_id := v_current.id;
        previous_referrer := v_current.tenant_id;
    END IF;
    IF p_referrer IS NOT NULL THEN
        SELECT c.code INTO v_code FROM referral_codes c WHERE c.tenant_id = p_referrer;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'unknown referrer' USING ERRCODE = 'no_data_found';
        END IF;
        started_referral_id := gen_random_uuid();
        INSERT INTO tenant_referrals (id, tenant_id, referred_tenant_id, code, source, started_at,
                                      created_by_platform_user_id)
        VALUES (started_referral_id, p_referrer, p_tenant_id, v_code, 'operator', p_now, p_platform_user_id);
    END IF;
    RETURN NEXT;
END
$$;

-- A rule as the operator typed it; the table's CHECKs refuse a shape that does not fit its kind.
CREATE FUNCTION platform_referral_add_rule(
    p_id uuid, p_kind text, p_percent_bp integer, p_amount_minor bigint, p_currency text, p_months integer,
    p_cap_minor bigint, p_platform_user_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'commission rules are the platform operator''s' USING ERRCODE = 'insufficient_privilege';
    END IF;
    INSERT INTO referral_commission_rules (id, kind, percent_bp, amount_minor, currency, months, cap_minor,
                                           created_by_platform_user_id)
    VALUES (p_id, p_kind, p_percent_bp, p_amount_minor, p_currency, p_months, p_cap_minor, p_platform_user_id);
END
$$;

-- Sets or clears the platform default rule. Returns the previous one.
CREATE FUNCTION platform_referral_set_default_rule(p_rule_id uuid, p_platform_user_id uuid, p_now timestamptz) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_previous uuid;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'commission rules are the platform operator''s' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT default_rule_id INTO v_previous FROM referral_settings FOR UPDATE;
    UPDATE referral_settings
       SET default_rule_id = p_rule_id, updated_by_platform_user_id = p_platform_user_id, updated_at = p_now;
    RETURN v_previous;
END
$$;

-- Sets or clears (null: the default applies) the rule of one current referral. Returns 'not_found', 'ended', or the previous
-- rule id as text ('' for none).
CREATE FUNCTION platform_referral_set_rule(p_referral_id uuid, p_rule_id uuid) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_ref tenant_referrals%ROWTYPE;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'commission rules are the platform operator''s' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT * INTO v_ref FROM tenant_referrals WHERE id = p_referral_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'not_found';
    END IF;
    IF v_ref.ended_at IS NOT NULL THEN
        RETURN 'ended';
    END IF;
    UPDATE tenant_referrals SET rule_id = p_rule_id WHERE id = p_referral_id;
    RETURN coalesce(v_ref.rule_id::text, '');
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 8. The commission of one payment (ADR-069 decision 5). Runs in the transaction that records the payment, bound to the paying
--    business; the payment must be that business's own. Paid months are counted over every other confirmed payment of the
--    referred business recorded since this referral started (ADR-069 decision 9), leaving out the payments whose outcome was
--    currency_differs (each outcome is kept in referral_payment_outcomes); recording a payment locks the business's subscription first (V75), so two payments recorded at once
--    are counted one after the other and never both as the first. Amounts are whole minor units, rounded half up. A line
--    worked out while the referrer business is suspended or cancelled is flagged referrer_inactive. Outcomes: added,
--    no_referral, no_rule, not_first_payment, months_used, currency_differs, cap_reached, zero.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION platform_referral_commission_for_payment(p_payment_id uuid, p_platform_user_id uuid)
RETURNS TABLE (line_id uuid, referral_id uuid, referrer_tenant_id uuid, amount_minor bigint, currency text, outcome text)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant   uuid := current_setting('app.tenant_id')::uuid;
    v_pay      subscription_payments%ROWTYPE;
    v_ref      tenant_referrals%ROWTYPE;
    v_rule     referral_commission_rules%ROWTYPE;
    v_source   text;
    v_before   integer;
    v_eligible integer;
    v_amount   bigint;
    v_earned   bigint;
    v_inactive boolean;
BEGIN
    SELECT * INTO v_pay FROM subscription_payments sp
     WHERE sp.id = p_payment_id AND sp.tenant_id = v_tenant AND sp.status = 'confirmed';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown payment' USING ERRCODE = 'no_data_found';
    END IF;
    SELECT * INTO v_ref FROM tenant_referrals r WHERE r.referred_tenant_id = v_tenant AND r.ended_at IS NULL FOR UPDATE;
    IF NOT FOUND THEN
        outcome := 'no_referral';
        RETURN NEXT;
        RETURN;
    END IF;
    referral_id := v_ref.id;
    referrer_tenant_id := v_ref.tenant_id;
    IF v_ref.rule_id IS NOT NULL THEN
        SELECT * INTO v_rule FROM referral_commission_rules r WHERE r.id = v_ref.rule_id;
        v_source := 'override';
    ELSE
        SELECT r.* INTO v_rule FROM referral_settings s JOIN referral_commission_rules r ON r.id = s.default_rule_id;
        v_source := 'default';
    END IF;
    IF v_rule.id IS NULL THEN
        outcome := 'no_rule';
        INSERT INTO referral_payment_outcomes (payment_id, referral_id, outcome) VALUES (v_pay.id, v_ref.id, outcome);
        RETURN NEXT;
        RETURN;
    END IF;
    SELECT coalesce(sum(sp.months), 0) INTO v_before FROM subscription_payments sp
     WHERE sp.tenant_id = v_tenant AND sp.status = 'confirmed' AND sp.id <> v_pay.id AND sp.created_at >= v_ref.started_at
       AND NOT EXISTS (SELECT 1 FROM referral_payment_outcomes o
                        WHERE o.payment_id = sp.id AND o.outcome = 'currency_differs');
    IF v_rule.kind <> 'percent_of_payment' AND v_rule.currency <> v_pay.currency THEN
        outcome := 'currency_differs';
        INSERT INTO referral_payment_outcomes (payment_id, referral_id, outcome) VALUES (v_pay.id, v_ref.id, outcome);
        RETURN NEXT;
        RETURN;
    END IF;
    v_eligible := CASE WHEN v_rule.months IS NULL THEN v_pay.months
                       ELSE greatest(0, least(v_pay.months, v_rule.months - v_before)) END;
    IF v_rule.kind = 'fixed_first_payment' THEN
        IF v_before > 0 THEN
            outcome := 'not_first_payment';
            INSERT INTO referral_payment_outcomes (payment_id, referral_id, outcome) VALUES (v_pay.id, v_ref.id, outcome);
            RETURN NEXT;
            RETURN;
        END IF;
        v_eligible := v_pay.months;
        v_amount := v_rule.amount_minor;
    ELSIF v_eligible = 0 THEN
        outcome := 'months_used';
        INSERT INTO referral_payment_outcomes (payment_id, referral_id, outcome) VALUES (v_pay.id, v_ref.id, outcome);
        RETURN NEXT;
        RETURN;
    ELSIF v_rule.kind = 'fixed_per_month' THEN
        v_amount := v_rule.amount_minor * v_eligible;
    ELSE
        v_amount := round(v_pay.amount_minor::numeric * v_rule.percent_bp * v_eligible / (10000::numeric * v_pay.months));
        IF v_rule.cap_minor IS NOT NULL THEN
            IF v_rule.currency <> v_pay.currency THEN
                outcome := 'currency_differs';
                INSERT INTO referral_payment_outcomes (payment_id, referral_id, outcome) VALUES (v_pay.id, v_ref.id, outcome);
                RETURN NEXT;
                RETURN;
            END IF;
            SELECT coalesce(sum(l.amount_minor), 0) INTO v_earned FROM referral_commission_lines l
             WHERE l.referral_id = v_ref.id AND l.currency = v_pay.currency;
            v_amount := least(v_amount, v_rule.cap_minor - v_earned);
            IF v_amount <= 0 THEN
                outcome := 'cap_reached';
                INSERT INTO referral_payment_outcomes (payment_id, referral_id, outcome) VALUES (v_pay.id, v_ref.id, outcome);
                RETURN NEXT;
                RETURN;
            END IF;
        END IF;
    END IF;
    IF v_amount <= 0 THEN
        outcome := 'zero';
        INSERT INTO referral_payment_outcomes (payment_id, referral_id, outcome) VALUES (v_pay.id, v_ref.id, outcome);
        RETURN NEXT;
        RETURN;
    END IF;
    v_inactive := EXISTS (SELECT 1 FROM tenants t WHERE t.id = v_ref.tenant_id AND t.status = 'suspended')
               OR EXISTS (SELECT 1 FROM subscriptions s
                           WHERE s.tenant_id = v_ref.tenant_id AND s.status IN ('suspended', 'cancelled'));
    line_id := gen_random_uuid();
    -- The snapshot is on a row the referrer reads: it names the rule, never the referred business's payment amount.
    INSERT INTO referral_commission_lines (id, tenant_id, referral_id, kind, payment_id, amount_minor, currency,
                                           months_counted, rule_snapshot, referrer_inactive, created_by_platform_user_id)
    VALUES (line_id, v_ref.tenant_id, v_ref.id, 'payment', v_pay.id, v_amount, v_pay.currency, v_eligible,
            jsonb_build_object(
                'rule_id', v_rule.id, 'source', v_source, 'kind', v_rule.kind, 'percent_bp', v_rule.percent_bp,
                'amount_minor', v_rule.amount_minor, 'currency', v_rule.currency, 'months', v_rule.months,
                'cap_minor', v_rule.cap_minor, 'payment_months', v_pay.months, 'paid_months_before', v_before),
            v_inactive, p_platform_user_id);
    amount_minor := v_amount;
    currency := v_pay.currency;
    outcome := 'added';
    INSERT INTO referral_payment_outcomes (payment_id, referral_id, outcome) VALUES (v_pay.id, v_ref.id, outcome);
    RETURN NEXT;
END
$$;

-- A line the operator typed for one referral (current or ended). Returns the referrer. It runs bound to the referrer, so its
-- Idempotency-Key row is that business's (as a payment's is the paying business's), and only for an active platform user.
CREATE FUNCTION platform_referral_add_manual_line(
    p_id uuid, p_referral_id uuid, p_amount_minor bigint, p_currency text, p_note text, p_platform_user_id uuid)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_bound    uuid := nullif(current_setting('app.tenant_id', true), '')::uuid;
    v_referrer uuid;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM platform_users u WHERE u.id = p_platform_user_id AND u.is_active) THEN
        RAISE EXCEPTION 'commission lines are the platform operator''s' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT tenant_id INTO v_referrer FROM tenant_referrals WHERE id = p_referral_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    IF v_bound IS NOT NULL AND v_bound <> v_referrer THEN
        RAISE EXCEPTION 'commission lines are the platform operator''s' USING ERRCODE = 'insufficient_privilege';
    END IF;
    INSERT INTO referral_commission_lines (id, tenant_id, referral_id, kind, amount_minor, currency, note,
                                           created_by_platform_user_id)
    VALUES (p_id, v_referrer, p_referral_id, 'manual', p_amount_minor, p_currency, p_note, p_platform_user_id);
    RETURN v_referrer;
END
$$;

-- The exact opposite of one line. Returns 'not_found', 'is_reversal', 'already_reversed', or the referral id as text.
CREATE FUNCTION platform_referral_reverse_line(p_id uuid, p_line_id uuid, p_note text, p_platform_user_id uuid)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_line referral_commission_lines%ROWTYPE;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'commission lines are the platform operator''s' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT * INTO v_line FROM referral_commission_lines WHERE id = p_line_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'not_found';
    END IF;
    IF v_line.kind = 'reversal' THEN
        RETURN 'is_reversal';
    END IF;
    IF EXISTS (SELECT 1 FROM referral_commission_lines WHERE reverses_line_id = p_line_id) THEN
        RETURN 'already_reversed';
    END IF;
    INSERT INTO referral_commission_lines (id, tenant_id, referral_id, kind, reverses_line_id, amount_minor, currency,
                                           note, created_by_platform_user_id)
    VALUES (p_id, v_line.tenant_id, v_line.referral_id, 'reversal', v_line.id, -v_line.amount_minor, v_line.currency,
            p_note, p_platform_user_id);
    RETURN v_line.referral_id::text;
END
$$;

-- Marks one line paid (the app records it; the money moves outside it). Returns 'not_found', 'already_paid', 'reversed' (an
-- unpaid line that was reversed is never paid, ADR-069 decision 11), or the referral id as text.
CREATE FUNCTION platform_referral_mark_paid(
    p_line_id uuid, p_paid_on date, p_method text, p_reference text, p_note text, p_platform_user_id uuid,
    p_now timestamptz)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_line referral_commission_lines%ROWTYPE;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'commission lines are the platform operator''s' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT * INTO v_line FROM referral_commission_lines WHERE id = p_line_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN 'not_found';
    END IF;
    IF v_line.paid_on IS NOT NULL THEN
        RETURN 'already_paid';
    END IF;
    IF EXISTS (SELECT 1 FROM referral_commission_lines WHERE reverses_line_id = p_line_id) THEN
        RETURN 'reversed';
    END IF;
    UPDATE referral_commission_lines
       SET paid_on = p_paid_on, paid_method = p_method, paid_reference = p_reference, paid_note = p_note, paid_at = p_now,
           paid_by_platform_user_id = p_platform_user_id
     WHERE id = p_line_id;
    RETURN v_line.referral_id::text;
END
$$;

-- The referral a line belongs to, for the operator.
CREATE FUNCTION platform_referral_of_line(p_line_id uuid) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT l.referral_id FROM referral_commission_lines l WHERE l.id = p_line_id
$$;

REVOKE ALL ON FUNCTION referral_code_tenant(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_set_referrer(uuid, uuid, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION referral_is_self(uuid, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_referrer(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_referrals_list(uuid, uuid, text, text, uuid, integer, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION tenant_referrals_mine() FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_referral_lines(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_referral_rule(uuid, boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_referral_from_application(uuid, uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_referral_set_referrer(uuid, uuid, uuid, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_referral_add_rule(uuid, text, integer, bigint, text, integer, bigint, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_referral_set_default_rule(uuid, uuid, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_referral_set_rule(uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_referral_commission_for_payment(uuid, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_referral_add_manual_line(uuid, uuid, bigint, text, text, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_referral_reverse_line(uuid, uuid, text, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_referral_mark_paid(uuid, date, text, text, text, uuid, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_referral_of_line(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION referral_code_tenant(text) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_set_referrer(uuid, uuid, text) TO bms_app;
GRANT EXECUTE ON FUNCTION referral_is_self(uuid, text, text) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_referrer(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_referrals_list(uuid, uuid, text, text, uuid, integer, integer) TO bms_app;
GRANT EXECUTE ON FUNCTION tenant_referrals_mine() TO bms_app;
GRANT EXECUTE ON FUNCTION platform_referral_lines(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_referral_rule(uuid, boolean) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_referral_from_application(uuid, uuid, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_referral_set_referrer(uuid, uuid, uuid, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_referral_add_rule(uuid, text, integer, bigint, text, integer, bigint, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_referral_set_default_rule(uuid, uuid, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_referral_set_rule(uuid, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_referral_commission_for_payment(uuid, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_referral_add_manual_line(uuid, uuid, bigint, text, text, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_referral_reverse_line(uuid, uuid, text, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_referral_mark_paid(uuid, date, text, text, text, uuid, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_referral_of_line(uuid) TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- 9. The permission to read the business's own referrals (owner and administrators).
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('core.referrals.read', 'core', 'Referrals: the business''s own referral link and the businesses it referred', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'core.referrals.read')
ON CONFLICT DO NOTHING;
