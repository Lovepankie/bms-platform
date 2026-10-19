-- V56: each lender's own payment provider account (extras slice A7, issue #233; ADR-035 amendment of 2026-10-18).
--
-- Money owed to a tenant lands only in that tenant's own Pesapal merchant account, never in the platform's (the licensing
-- reason is in the ADR; KTA's confirmation is still to be asked before the first live repayment). This migration adds the
-- place those accounts live and the three guards around them. Expand only, safe on a populated database:
--
--   1. `payment_gateway_accounts`: one row per tenant and provider, tenant RLS forced. The consumer key and secret are
--      stored only as AES-256-GCM boxes sealed with the application data key (`data_key_id` says which key, so a rotation
--      job can re-seal row by row). The path token that selects the account on the callback host is stored only as the
--      SHA-256 of a random value, so a database reader cannot replay a notification url. No endpoint returns a secret.
--   2. Four SECURITY DEFINER functions for what has to cross tenants: the token lookup of the callback host, the list of
--      tenants that still hold a row sealed with a retired key (the rotation job), the operator's read of an account's
--      status (status words and times only, never a credential), and the existing `app_resolve_payment_intent` with one
--      more argument (the tenant the token belongs to), so a notification sent to one lender's url can never be matched
--      to another lender's order.
--   3. A CHECK on `payment_intents`, in both directions: an intent of the platform scope carries a platform purpose and an
--      intent of the tenant scope does not. Every loan repayment and savings deposit purpose is tenant scope, so none can ever
--      name the platform account, and no platform subscription can be paid into a lender's account. It is added NOT VALID
--      here (it binds every new row at once) and validated by V57 in a transaction of its own.
--   4. The old four-argument `app_resolve_payment_intent` is dropped: it matched an order with no check of whose address the
--      notification arrived on. Only the five-argument form (with the tenant of the address) remains.
--
-- Locks: the table is created empty; CREATE TABLE ... REFERENCES takes SHARE ROW EXCLUSIVE on tenants and users for the
-- few milliseconds the file takes (reads continue). ADD CONSTRAINT ... NOT VALID takes ACCESS EXCLUSIVE on payment_intents
-- for an instant (no row is scanned) and holds it to the commit of this file; that is why the scan is not here but in V57.
-- DROP FUNCTION takes no table lock. The first statement bounds the wait, as V26, V48, V49 and V54 do.

SET LOCAL lock_timeout = '5s';

CREATE TABLE payment_gateway_accounts (
    id                 uuid         NOT NULL PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants (id),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    provider           text         NOT NULL DEFAULT 'pesapal' CHECK (provider IN ('pesapal')),
    consumer_key_enc   bytea        NOT NULL CHECK (octet_length(consumer_key_enc) BETWEEN 30 AND 1024),
    consumer_secret_enc bytea       NOT NULL CHECK (octet_length(consumer_secret_enc) BETWEEN 30 AND 1024),
    -- A keyed digest (HMAC under a key derived from the data key) of the consumer key, unique across tenants: two lenders
    -- can never be connected to one merchant account, whatever either of them types. Recomputed when the row is re-sealed.
    -- Which merchant account this is: new when the consumer key changes (not when only the secret does). Intents record it, so an order
    -- made under one merchant account is never checked with the credentials of another.
    merchant_id        uuid         NOT NULL DEFAULT gen_random_uuid(),
    consumer_key_hmac  char(64)     NOT NULL UNIQUE CHECK (consumer_key_hmac ~ '^[0-9a-f]{64}$'),
    key_hint           varchar(4)   NOT NULL,
    data_key_id        varchar(64)  NOT NULL CHECK (data_key_id <> ''),
    status             text         NOT NULL CHECK (status IN ('saved', 'verified', 'failed')),
    ipn_id             text         CHECK (ipn_id ~ '^[0-9a-fA-F-]{36}$'),
    ipn_token_hash     char(64)     CHECK (ipn_token_hash ~ '^[0-9a-f]{64}$'),
    ipn_registered_at  timestamptz,
    last_checked_at    timestamptz,
    failure_reason     varchar(60)  CHECK (failure_reason ~ '^[a-z][a-z0-9_:]{1,59}$'),
    updated_by         uuid         NOT NULL,
    UNIQUE (tenant_id, provider),
    UNIQUE (ipn_token_hash),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES users (tenant_id, id),
    CHECK (status <> 'verified' OR (ipn_id IS NOT NULL AND ipn_token_hash IS NOT NULL
                                    AND ipn_registered_at IS NOT NULL AND last_checked_at IS NOT NULL)),
    CHECK (status <> 'failed' OR (failure_reason IS NOT NULL AND last_checked_at IS NOT NULL))
);
CREATE INDEX payment_gateway_accounts_by_key ON payment_gateway_accounts (data_key_id);

SELECT bms_apply_tenant_rls('payment_gateway_accounts');
SELECT bms_grant_app('payment_gateway_accounts', 'SELECT, INSERT, UPDATE, DELETE');

-- Who the account belongs to and when it was made never change.
CREATE FUNCTION payment_gateway_accounts_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.created_at, NEW.provider) IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.created_at, OLD.provider) THEN
        RAISE EXCEPTION 'payment_gateway_accounts: the owner and the provider of an account never change'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER payment_gateway_accounts_guard BEFORE UPDATE ON payment_gateway_accounts
    FOR EACH ROW EXECUTE FUNCTION payment_gateway_accounts_guard();

-- The callback host selects a lender's account by the path token of its notification url: the tenant is not known until
-- the token is looked up (ADR-016). Takes the digest, returns ids and the tenant's status, nothing else.
CREATE FUNCTION app_resolve_payment_account(p_provider text, p_token_hash text)
RETURNS TABLE (tenant_id uuid, account_id uuid, tenant_status text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.tenant_id, a.id, t.status
      FROM payment_gateway_accounts a JOIN tenants t ON t.id = a.tenant_id
     WHERE a.provider = p_provider AND a.ipn_token_hash = p_token_hash AND t.status IN ('active', 'suspended')
$$;

-- The rotation job's worklist: every tenant, suspended ones too (a suspended tenant is resumed one day and its secrets must
-- open then), that still holds a secret sealed with a key other than the current one: a payment account, or a second-factor
-- seed (the key id is inside each box: byte 2 is its length, then the id).
CREATE FUNCTION app_tenants_with_sealed_secrets_to_reseal(p_current_key_id text, p_limit integer) RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT tenant_id FROM (
        SELECT a.tenant_id
          FROM payment_gateway_accounts a
         WHERE a.data_key_id <> p_current_key_id
        UNION
        SELECT u.tenant_id
          FROM user_credentials u
         WHERE (u.totp_secret_enc IS NOT NULL
                AND substring(u.totp_secret_enc FROM 3 FOR get_byte(u.totp_secret_enc, 1)) <> convert_to(p_current_key_id, 'UTF8'))
            OR (u.totp_pending_secret_enc IS NOT NULL
                AND substring(u.totp_pending_secret_enc FROM 3 FOR get_byte(u.totp_pending_secret_enc, 1)) <> convert_to(p_current_key_id, 'UTF8'))
    ) w
    JOIN tenants t ON t.id = w.tenant_id AND t.status IN ('active', 'suspended')
    ORDER BY tenant_id
    LIMIT p_limit
$$;

-- How many sealed secrets (payment accounts, second-factor seeds of every tenant and of the platform operators) are still
-- sealed with a key other than the current one: the number a data key retirement waits for, logged by every run.
CREATE FUNCTION app_count_sealed_secrets_to_reseal(p_current_key_id text) RETURNS bigint
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT (SELECT count(*) FROM payment_gateway_accounts WHERE data_key_id <> p_current_key_id)
         + (SELECT count(*) FROM user_credentials
             WHERE totp_secret_enc IS NOT NULL
               AND substring(totp_secret_enc FROM 3 FOR get_byte(totp_secret_enc, 1)) <> convert_to(p_current_key_id, 'UTF8'))
         + (SELECT count(*) FROM user_credentials
             WHERE totp_pending_secret_enc IS NOT NULL
               AND substring(totp_pending_secret_enc FROM 3 FOR get_byte(totp_pending_secret_enc, 1)) <> convert_to(p_current_key_id, 'UTF8'))
         + (SELECT count(*) FROM platform_users
             WHERE totp_secret_enc IS NOT NULL
               AND substring(totp_secret_enc FROM 3 FOR get_byte(totp_secret_enc, 1)) <> convert_to(p_current_key_id, 'UTF8'))
         + (SELECT count(*) FROM platform_users
             WHERE totp_pending_secret_enc IS NOT NULL
               AND substring(totp_pending_secret_enc FROM 3 FOR get_byte(totp_pending_secret_enc, 1)) <> convert_to(p_current_key_id, 'UTF8'))
$$;

-- Whether a consumer key is already connected by any tenant other than this one. The caller passes the digest of the key under the
-- current data key and under every retired one: a row sealed before a rotation keeps its old digest until it is re-sealed.
CREATE FUNCTION app_payment_account_key_in_use(p_provider text, p_key_hmacs text[], p_tenant uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM payment_gateway_accounts a
                    WHERE a.provider = p_provider AND a.consumer_key_hmac = ANY (p_key_hmacs) AND a.tenant_id <> p_tenant)
$$;

-- As the four-argument form of V54 (dropped below), and additionally: when the notification came in on a lender's own
-- url (p_tenant is that lender), an order that belongs to any other tenant is a scope mismatch, so one lender's url can
-- never reach another lender's order. p_tenant is null for the platform url and for the payer's return.
CREATE FUNCTION app_resolve_payment_intent(
    p_provider text, p_scope text, p_tracking_id text, p_reference text, p_tenant uuid)
RETURNS TABLE (tenant_id uuid, intent_id uuid, tenant_status text, match text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT i.tenant_id, i.id, t.status,
           CASE WHEN i.scope <> p_scope THEN 'scope_mismatch'
                WHEN p_tenant IS NOT NULL AND i.tenant_id <> p_tenant THEN 'scope_mismatch'
                WHEN p_tracking_id IS NOT NULL AND i.provider_reference IS NOT NULL
                     AND lower(i.provider_reference) <> lower(p_tracking_id) THEN 'tracking_mismatch'
                ELSE 'ok' END
      FROM payment_intents i JOIN tenants t ON t.id = i.tenant_id
     WHERE i.provider = p_provider AND i.merchant_reference = p_reference AND t.status IN ('active', 'suspended')
$$;

-- The platform console shows whether a lender is connected, never how: status words and times only. It answers only to a
-- connection with no tenant bound (a platform request); from a tenant's own transaction it returns nothing.
CREATE FUNCTION app_payment_account_status(p_tenant uuid)
RETURNS TABLE (status text, ipn_registered_at timestamptz, last_checked_at timestamptz, failure_reason varchar)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.status, a.ipn_registered_at, a.last_checked_at, a.failure_reason
      FROM payment_gateway_accounts a
     WHERE a.tenant_id = p_tenant AND a.provider = 'pesapal'
       AND nullif(current_setting('app.tenant_id', true), '') IS NULL
$$;

-- The operator's way out when a lender's account must go while a payment still waits on it (a compromised merchant account,
-- a stuck order): removes the row and returns the status it had, or null when there was none. Answers only to a connection
-- with no tenant bound (a platform request); the console audits it. In-flight orders of that account are then left waiting
-- (the poller skips an intent whose account has no credentials and never closes it) until a new account is connected.
CREATE FUNCTION app_release_payment_account(p_tenant uuid) RETURNS text
LANGUAGE sql VOLATILE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH gone AS (
        DELETE FROM payment_gateway_accounts a
         WHERE a.tenant_id = p_tenant AND a.provider = 'pesapal' AND nullif(current_setting('app.tenant_id', true), '') IS NULL
        RETURNING a.status)
    SELECT status FROM gone
$$;

REVOKE ALL ON FUNCTION app_resolve_payment_account(text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_release_payment_account(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_release_payment_account(uuid) TO bms_app;
REVOKE ALL ON FUNCTION app_payment_account_status(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_payment_account_status(uuid) TO bms_app;
REVOKE ALL ON FUNCTION app_tenants_with_sealed_secrets_to_reseal(text, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_count_sealed_secrets_to_reseal(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_payment_account_key_in_use(text, text[], uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_resolve_payment_intent(text, text, text, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_resolve_payment_account(text, text) TO bms_app;
GRANT EXECUTE ON FUNCTION app_tenants_with_sealed_secrets_to_reseal(text, integer) TO bms_app;
GRANT EXECUTE ON FUNCTION app_count_sealed_secrets_to_reseal(text) TO bms_app;
GRANT EXECUTE ON FUNCTION app_payment_account_key_in_use(text, text[], uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION app_resolve_payment_intent(text, text, text, text, uuid) TO bms_app;

-- The old four-argument resolver matched an order with no check of whose notification address it arrived on.
DROP FUNCTION app_resolve_payment_intent(text, text, text, text);

-- The merchant account an intent was created under (null for intents made before this migration: they are checked with whatever
-- account the tenant has, as before).
ALTER TABLE payment_intents ADD COLUMN merchant_id uuid;

-- The platform's purposes (subscriptions and add-ons, slice #228) belong to the platform scope and only to it; lender money
-- (loan repayments, savings deposits) is never of that scope, and a platform purpose is never paid into a lender's account.
-- NOT VALID: binds every new row now; V57 checks the rows already there.
ALTER TABLE payment_intents
    ADD CONSTRAINT payment_intents_scope_purpose
    CHECK ((scope = 'platform') = (purpose LIKE 'platform.%')) NOT VALID;
