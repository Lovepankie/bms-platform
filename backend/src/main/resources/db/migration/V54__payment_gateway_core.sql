-- V54: payment gateway core (extras slice A2, issue #213; ADR-035 and its 2026-10-14 and 2026-10-15 amendments,
-- docs/specs/client-extras.md section 3, chapter 6 `payment_intents` and `payment_callbacks`, chapter 12 section 12.4).
--
-- Expand only, safe on a populated database. It adds: two tenant tables (`payment_intents`, `payment_callbacks`, each with
-- its indexes, the standard forced row-level security and the guard triggers below), two platform tables that no tenant
-- connection can read or write (`payment_unmatched_callbacks`, `payment_callback_counters`), and six SECURITY DEFINER
-- functions. Nothing else. No existing table, column, constraint, function or row is touched. No permission is added
-- (`core.payments.read`, `core.payments.collect` and `core.payments.allocate` were seeded by V2; `core.payments.manage`
-- arrives with the statement upload, slice #224) and no tenant is switched on: collections are the key
-- `collections_enabled` of the tenant settings group `payments` in the existing jsonb column, absent meaning off, and the
-- merchant account is bound to one tenant by an environment value, so nothing here changes what any tenant can do.
--
-- Locks, said plainly. Both tenant tables are created empty. The only locks are the ones CREATE TABLE ... REFERENCES takes
-- on the tables it points at: SHARE ROW EXCLUSIVE on tenants, users and branches until the commit, which blocks writes to
-- those three tables, but not reads, for the few milliseconds the file takes; no row is scanned, no index is built on
-- existing data and nothing is rewritten. The functions only add catalogue rows. Flyway runs this file in one transaction,
-- so those locks are held to the commit, and a long transaction on tenants, users or branches would make this wait with
-- every writer queued behind it: the first statement sets lock_timeout to five seconds (as V26, V48 and V49 do), so if a
-- lock cannot be had at once the migration fails and is retried, and the application is never held behind it.
-- Flyway runs with outOfOrder off, so the number is assigned at merge time above the highest one on main.
--
-- PRIVACY: a payment intent holds the payer's phone number (E.164, needed to build the order) and nothing else about the
-- payer. `payment_callbacks` and the unmatched queue hold identifiers the provider sent (an order tracking id and our own
-- random merchant reference) and never a request body, a header or an address. No table here holds a key, a secret or a
-- token of the provider, and no function returns one.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- payment_intents: one row per collection the platform asked the provider for (FR-PAY-01). The row is written before
-- anything external happens and is never deleted: a failed or ambiguous provider call ends in a state, not in a lost row.
--
-- States (spec 3.4 and 3.8, amended in ADR-035's 2026-10-15 section):
--   created             written, no order at the provider yet (or the answer was lost and a creator retries)
--   pending             the order exists; the payer has a link
--   paid                the provider's status query said COMPLETED for this order and the amount was stored; the purpose
--                       handler has not booked it yet (or a technical failure is being retried)
--   booked              the purpose handler booked it (booked_subject_txn_id names the transaction)
--   booked_unallocated  the amount went to unallocated receipts because the handler refused it (the posting is slice #216)
--   needs_attention     a person must look: a refusal this slice cannot post, five technical failures, or a status
--                       answer that disagrees with the order (reference, currency, a fraction of a shilling)
--   failed / cancelled  the payer's attempt failed, or the order was cancelled / left unpaid
--   expired             30 minutes passed unpaid: the payer is no longer offered the order, polling continues
--   reversed_pending_review  the provider reports a reversal of a booked payment; a checker request follows (slice #224)
--   reversed            the payment was reversed and nothing stands in the books for it
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payment_intents (
    id                    uuid         NOT NULL PRIMARY KEY,
    tenant_id             uuid         NOT NULL REFERENCES tenants (id),
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    branch_id             uuid         NOT NULL,
    -- What the money is for and what it is for exactly: the owning module registers the purpose (a handler), the subject
    -- is that module's own row (a loan, a savings account). The merchant reference identifies the intent; the intent
    -- identifies the subject; nothing is ever matched on free text (spec 3.4).
    purpose               text         NOT NULL CHECK (purpose ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    subject_type          text         NOT NULL CHECK (subject_type ~ '^[a-z][a-z0-9_]{1,39}$'),
    subject_id            uuid         NOT NULL,
    payer_phone_e164      varchar(16)  NOT NULL CHECK (payer_phone_e164 ~ '^\+[1-9][0-9]{7,14}$'),
    amount_minor          bigint       NOT NULL CHECK (amount_minor > 0),
    currency              char(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    provider              text         NOT NULL DEFAULT 'pesapal' CHECK (provider IN ('pesapal')),
    -- tenant: the merchant account bound to this tenant; platform: the operator's own account (subscription payments, #228).
    scope                 text         NOT NULL DEFAULT 'tenant' CHECK (scope IN ('tenant', 'platform')),
    -- BMS-<env>-<32 hex of the intent id>: random (the id is a version 4 UUID), carries no tenant, amount or person, and
    -- is unique across every tenant so a notification can be resolved to one row without trusting the request.
    merchant_reference    text         NOT NULL CHECK (merchant_reference ~ '^BMS-(ST|PR|DV)-[0-9a-f]{32}$'),
    provider_reference    text         CHECK (provider_reference ~ '^[0-9a-fA-F-]{36}$'),
    redirect_url          text         CHECK (redirect_url ~ '^https://[^[:space:]]+$' AND char_length(redirect_url) <= 2000),
    status                text         NOT NULL CHECK (status IN ('created', 'pending', 'paid', 'booked', 'booked_unallocated',
                                                                 'needs_attention', 'failed', 'cancelled', 'expired',
                                                                 'reversed_pending_review', 'reversed')),
    -- A closed vocabulary of lower case words, never provider text.
    failure_reason        varchar(60)  CHECK (failure_reason ~ '^[a-z][a-z0-9_:]{1,59}$'),
    refusal_code          varchar(60)  CHECK (refusal_code ~ '^[a-z][a-z0-9_]{1,59}$'),
    initiated_by          uuid         NOT NULL,
    idempotency_key       varchar(100) NOT NULL CHECK (char_length(idempotency_key) >= 8),
    -- 30 minutes after creation: after it the payer is not offered the order, but a completed payment still books.
    expires_at            timestamptz  NOT NULL,
    -- What the verified status query reported. A row cannot be paid, booked or reversed without them, and a payment in
    -- another currency than the order's can never be paid or booked (the unallocated path takes it, slice #216).
    paid_amount_minor     bigint       CHECK (paid_amount_minor > 0),
    paid_currency         char(3)      CHECK (paid_currency ~ '^[A-Z]{3}$'),
    confirmation_code     varchar(100),
    reported_method       varchar(40),
    paid_at               timestamptz,
    verified_at           timestamptz,
    booked_subject_txn_id uuid,
    booked_at             timestamptz,
    -- The checker's schedule: the next time the status of this order is due (the poller's backoff, an IPN's request, a
    -- lease while a check runs); null when nothing more is due. last_checked_at is the throttle's reference.
    last_checked_at       timestamptz,
    next_check_at         timestamptz,
    -- The last time a notification asked for a check. A check that began before it must run again: the checker clears
    -- this only when it saw the request before it asked the provider.
    check_requested_at    timestamptz,
    check_failures        integer      NOT NULL DEFAULT 0 CHECK (check_failures >= 0),
    technical_failures    integer      NOT NULL DEFAULT 0 CHECK (technical_failures >= 0),
    -- The single locked creator (spec 3.5): one worker holds the lease while it calls the provider.
    creation_lease_until  timestamptz,
    creation_attempts     integer      NOT NULL DEFAULT 0 CHECK (creation_attempts >= 0),
    -- The first status answer repeated the amount and currency of the order: the check that a repeated submit did not
    -- hand back an order made with another body (spec 3.5 step 2).
    order_verified_at     timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (merchant_reference),
    UNIQUE (tenant_id, initiated_by, idempotency_key),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, initiated_by) REFERENCES users (tenant_id, id),
    CHECK (expires_at > created_at),
    CHECK (status <> 'pending' OR (provider_reference IS NOT NULL AND redirect_url IS NOT NULL)),
    CHECK (status <> 'failed' OR failure_reason IS NOT NULL),
    CHECK (status NOT IN ('paid', 'booked', 'booked_unallocated', 'reversed_pending_review')
           OR (paid_amount_minor IS NOT NULL AND paid_currency IS NOT NULL AND confirmation_code IS NOT NULL
               AND paid_at IS NOT NULL AND verified_at IS NOT NULL AND provider_reference IS NOT NULL)),
    CHECK (status NOT IN ('paid', 'booked') OR paid_currency = currency),
    CHECK (status <> 'booked' OR (booked_subject_txn_id IS NOT NULL AND booked_at IS NOT NULL))
);
-- One order per provider reference, across tenants: an order tracking id can never serve two intents.
CREATE UNIQUE INDEX payment_intents_provider_reference
    ON payment_intents (provider, provider_reference) WHERE provider_reference IS NOT NULL;
-- The poller's queue: only intents with something due.
CREATE INDEX payment_intents_due ON payment_intents (next_check_at) WHERE next_check_at IS NOT NULL;
-- The initiator's and the staff list, newest first.
CREATE INDEX payment_intents_by_tenant ON payment_intents (tenant_id, created_at DESC);
-- The subject's own payments (a loan's or an account's page).
CREATE INDEX payment_intents_by_subject ON payment_intents (tenant_id, subject_type, subject_id, created_at DESC);

SELECT bms_apply_tenant_rls('payment_intents');
-- No DELETE: an intent is a record that money was asked for, whatever became of it.
SELECT bms_grant_app('payment_intents', 'SELECT, INSERT, UPDATE');

CREATE FUNCTION payment_intents_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    moves text[][] := ARRAY[
        ARRAY['created', 'pending'], ARRAY['created', 'failed'], ARRAY['created', 'cancelled'], ARRAY['created', 'expired'],
        ARRAY['pending', 'paid'], ARRAY['pending', 'failed'], ARRAY['pending', 'cancelled'], ARRAY['pending', 'expired'],
        ARRAY['pending', 'needs_attention'],
        ARRAY['expired', 'paid'], ARRAY['expired', 'failed'], ARRAY['expired', 'cancelled'], ARRAY['expired', 'needs_attention'],
        -- A card payer may retry on the same hosted order after a failure, and a cancel can lose a race with a payment.
        ARRAY['failed', 'paid'], ARRAY['failed', 'needs_attention'],
        ARRAY['cancelled', 'paid'], ARRAY['cancelled', 'needs_attention'],
        ARRAY['paid', 'booked'], ARRAY['paid', 'booked_unallocated'], ARRAY['paid', 'needs_attention'], ARRAY['paid', 'reversed'],
        ARRAY['needs_attention', 'booked'], ARRAY['needs_attention', 'booked_unallocated'], ARRAY['needs_attention', 'reversed'],
        ARRAY['booked', 'reversed_pending_review'], ARRAY['booked', 'reversed'],
        ARRAY['booked_unallocated', 'reversed_pending_review'], ARRAY['booked_unallocated', 'reversed'],
        ARRAY['reversed_pending_review', 'reversed']];
    i integer;
    ok boolean := false;
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    -- What was asked for never changes: who, what for, how much, which reference, and when it lapses.
    IF (NEW.id, NEW.tenant_id, NEW.created_at, NEW.branch_id, NEW.purpose, NEW.subject_type, NEW.subject_id,
        NEW.payer_phone_e164, NEW.amount_minor, NEW.currency, NEW.provider, NEW.scope, NEW.merchant_reference,
        NEW.initiated_by, NEW.idempotency_key, NEW.expires_at)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.created_at, OLD.branch_id, OLD.purpose, OLD.subject_type, OLD.subject_id,
        OLD.payer_phone_e164, OLD.amount_minor, OLD.currency, OLD.provider, OLD.scope, OLD.merchant_reference,
        OLD.initiated_by, OLD.idempotency_key, OLD.expires_at)
    THEN
        RAISE EXCEPTION 'payment_intents: the request columns of an intent never change'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    -- What the provider told us and what was booked is written once.
    IF (OLD.provider_reference IS NOT NULL AND NEW.provider_reference IS DISTINCT FROM OLD.provider_reference)
       OR (OLD.redirect_url IS NOT NULL AND NEW.redirect_url IS DISTINCT FROM OLD.redirect_url)
       OR (OLD.paid_amount_minor IS NOT NULL AND NEW.paid_amount_minor IS DISTINCT FROM OLD.paid_amount_minor)
       OR (OLD.paid_currency IS NOT NULL AND NEW.paid_currency IS DISTINCT FROM OLD.paid_currency)
       OR (OLD.confirmation_code IS NOT NULL AND NEW.confirmation_code IS DISTINCT FROM OLD.confirmation_code)
       OR (OLD.reported_method IS NOT NULL AND NEW.reported_method IS DISTINCT FROM OLD.reported_method)
       OR (OLD.paid_at IS NOT NULL AND NEW.paid_at IS DISTINCT FROM OLD.paid_at)
       OR (OLD.verified_at IS NOT NULL AND NEW.verified_at IS DISTINCT FROM OLD.verified_at)
       OR (OLD.booked_subject_txn_id IS NOT NULL AND NEW.booked_subject_txn_id IS DISTINCT FROM OLD.booked_subject_txn_id)
       OR (OLD.booked_at IS NOT NULL AND NEW.booked_at IS DISTINCT FROM OLD.booked_at)
    THEN
        RAISE EXCEPTION 'payment_intents: what the provider reported and what was booked is written once'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        FOR i IN 1 .. array_length(moves, 1) LOOP
            IF moves[i][1] = OLD.status AND moves[i][2] = NEW.status THEN
                ok := true;
            END IF;
        END LOOP;
        IF NOT ok THEN
            RAISE EXCEPTION 'payment_intents: % to % is not an allowed transition', OLD.status, NEW.status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER payment_intents_guard BEFORE UPDATE ON payment_intents
    FOR EACH ROW EXECUTE FUNCTION payment_intents_guard();

-- ---------------------------------------------------------------------------------------------
-- payment_callbacks: one row per notification that named a known order (FR-PAY-02). It holds the identifiers the provider
-- sent and the outcome of the status query the notification caused, never a body, a header or an address: the
-- notification is a hint (ADR-035 decision 4). A row is written as `queued` (or `throttled`) and finished once, by the
-- checker, with the closed outcome and the shape of the verified answer; nothing else about it ever changes.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payment_callbacks (
    id                  uuid         NOT NULL PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    intent_id           uuid         NOT NULL,
    provider            text         NOT NULL CHECK (provider IN ('pesapal')),
    -- ipn: the server to server notification; return: the payer came back to the callback url.
    kind                text         NOT NULL CHECK (kind IN ('ipn', 'return')),
    notification_type   varchar(20)  CHECK (notification_type ~ '^[A-Z]{3,20}$'),
    order_tracking_id   text         CHECK (order_tracking_id ~ '^[0-9a-fA-F-]{36}$'),
    merchant_reference  text         NOT NULL CHECK (merchant_reference ~ '^BMS-(ST|PR|DV)-[0-9a-f]{32}$'),
    outcome             text         NOT NULL DEFAULT 'queued'
                                     CHECK (outcome IN ('queued', 'throttled', 'paid', 'failed', 'reversed', 'duplicate',
                                                        'unchanged', 'rejected', 'status_mismatch')),
    -- {"status": "pending", ...}: the closed words of the verified answer; no provider text.
    status_query_result jsonb        CHECK (jsonb_typeof(status_query_result) = 'object'),
    checked_at          timestamptz,
    FOREIGN KEY (tenant_id, intent_id) REFERENCES payment_intents (tenant_id, id)
);
CREATE INDEX payment_callbacks_by_intent ON payment_callbacks (tenant_id, intent_id, created_at DESC);
CREATE INDEX payment_callbacks_by_day ON payment_callbacks (created_at);

SELECT bms_apply_tenant_rls('payment_callbacks');
SELECT bms_grant_app('payment_callbacks', 'SELECT, INSERT');
GRANT UPDATE (outcome, status_query_result, checked_at) ON payment_callbacks TO bms_app;

CREATE FUNCTION payment_callbacks_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'DELETE' THEN
        -- Retention is 90 days: the nightly function deletes older rows, nothing deletes a younger one.
        IF OLD.created_at > now() - interval '90 days' THEN
            RAISE EXCEPTION 'payment_callbacks: a row is kept 90 days' USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.outcome <> 'queued' OR NEW.outcome = 'queued'
       OR (NEW.id, NEW.tenant_id, NEW.created_at, NEW.intent_id, NEW.provider, NEW.kind, NEW.notification_type,
           NEW.order_tracking_id, NEW.merchant_reference)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.created_at, OLD.intent_id, OLD.provider, OLD.kind, OLD.notification_type,
           OLD.order_tracking_id, OLD.merchant_reference)
    THEN
        RAISE EXCEPTION 'payment_callbacks: a notification is finished once and its identifiers never change'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER payment_callbacks_guard_update BEFORE UPDATE ON payment_callbacks
    FOR EACH ROW EXECUTE FUNCTION payment_callbacks_guard();
CREATE TRIGGER payment_callbacks_guard_delete BEFORE DELETE ON payment_callbacks
    FOR EACH ROW EXECUTE FUNCTION payment_callbacks_guard();

-- ---------------------------------------------------------------------------------------------
-- The platform's own tables: notifications that name no order we know, and the daily counts that feed the alerts.
-- No tenant owns either, so neither has row-level security and bms_app has no grant on either: they are reached only
-- through the definer functions below (ADR-016, the pattern of V23). The unmatched queue is bounded (5,000 distinct rows,
-- a repeat only raises a count), so an attacker making up identifiers cannot grow it without limit.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE payment_unmatched_callbacks (
    id                 uuid         NOT NULL PRIMARY KEY,
    first_seen_at      timestamptz  NOT NULL,
    last_seen_at       timestamptz  NOT NULL,
    provider           text         NOT NULL CHECK (provider IN ('pesapal')),
    scope              text         NOT NULL CHECK (scope IN ('tenant', 'platform')),
    -- unknown_reference: no intent has this merchant reference; tracking_mismatch: the reference is known but the tracking
    -- id is another order's; scope_mismatch: the path token belongs to the other credential pair.
    reason             text         NOT NULL CHECK (reason IN ('unknown_reference', 'tracking_mismatch', 'scope_mismatch')),
    order_tracking_id  text         CHECK (order_tracking_id ~ '^[0-9a-fA-F-]{36}$'),
    merchant_reference text         NOT NULL CHECK (merchant_reference ~ '^BMS-(ST|PR|DV)-[0-9a-f]{32}$'),
    hits               integer      NOT NULL DEFAULT 1 CHECK (hits >= 1),
    UNIQUE (provider, scope, reason, merchant_reference)
);
REVOKE ALL ON payment_unmatched_callbacks FROM PUBLIC;

CREATE TABLE payment_callback_counters (
    day     date   NOT NULL,
    outcome text   NOT NULL CHECK (outcome ~ '^[a-z][a-z_]{1,39}$'),
    hits    bigint NOT NULL DEFAULT 0 CHECK (hits >= 0),
    PRIMARY KEY (day, outcome)
);
REVOKE ALL ON payment_callback_counters FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- app_resolve_payment_intent: the tenant and intent a notification names, found by the merchant reference alone (the
-- request's host and headers play no part). The order tracking id, when the intent already knows its own, must match;
-- the credential pair the path token selected (the scope) must be the intent's. Returns nothing otherwise. A suspended
-- tenant is returned with its status so the caller can acknowledge without processing; a closed one is not.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION app_resolve_payment_intent(p_provider text, p_scope text, p_tracking_id text, p_reference text)
RETURNS TABLE (tenant_id uuid, intent_id uuid, tenant_status text, match text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT i.tenant_id, i.id, t.status,
           CASE WHEN i.scope <> p_scope THEN 'scope_mismatch'
                WHEN p_tracking_id IS NOT NULL AND i.provider_reference IS NOT NULL
                     AND lower(i.provider_reference) <> lower(p_tracking_id) THEN 'tracking_mismatch'
                ELSE 'ok' END
      FROM payment_intents i JOIN tenants t ON t.id = i.tenant_id
     WHERE i.provider = p_provider AND i.merchant_reference = p_reference AND t.status IN ('active', 'suspended')
$$;

-- Active tenants with at least one intent whose check is due, the tenant whose oldest due check is oldest first, so a
-- tenant with a thousand due intents cannot keep a tenant with one waiting (the poller takes a few from each).
CREATE FUNCTION app_tenants_with_due_payment_checks(p_now timestamptz, p_limit integer) RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT i.tenant_id
      FROM payment_intents i JOIN tenants t ON t.id = i.tenant_id
     WHERE i.next_check_at IS NOT NULL AND i.next_check_at <= p_now AND t.status = 'active'
     GROUP BY i.tenant_id
     ORDER BY min(i.next_check_at), i.tenant_id
     LIMIT p_limit
$$;

-- Records a notification that resolved to nothing. Idempotent per (provider, scope, reason, reference); past the bound of
-- 5,000 distinct rows only the day's counter moves.
CREATE FUNCTION app_record_unmatched_payment_callback(
    p_provider text, p_scope text, p_reason text, p_tracking_id text, p_reference text, p_now timestamptz) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE payment_unmatched_callbacks
       SET hits = hits + 1, last_seen_at = p_now
     WHERE provider = p_provider AND scope = p_scope AND reason = p_reason AND merchant_reference = p_reference;
    IF NOT FOUND AND (SELECT count(*) FROM payment_unmatched_callbacks) < 5000 THEN
        INSERT INTO payment_unmatched_callbacks (id, first_seen_at, last_seen_at, provider, scope, reason,
                                                 order_tracking_id, merchant_reference)
        VALUES (gen_random_uuid(), p_now, p_now, p_provider, p_scope, p_reason, p_tracking_id, p_reference)
        ON CONFLICT (provider, scope, reason, merchant_reference) DO UPDATE SET hits = payment_unmatched_callbacks.hits + 1;
    END IF;
END
$$;

-- Counts one notification outcome for the day (received, unknown_reference, malformed, throttled, ...): the number the
-- operator's alert reads. The outcome is a word of ours, never the request's.
CREATE FUNCTION app_count_payment_callback(p_day date, p_outcome text) RETURNS void
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    INSERT INTO payment_callback_counters (day, outcome, hits) VALUES (p_day, p_outcome, 1)
    ON CONFLICT (day, outcome) DO UPDATE SET hits = payment_callback_counters.hits + 1
$$;

-- Nightly retention across every tenant, suspended and closed ones included (retention is a duty): finished notification
-- rows older than 90 days, unmatched rows and counters older than 30 days and 400 days. Returns the rows deleted.
CREATE FUNCTION payments_housekeeping(p_now timestamptz) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    a integer;
    b integer;
    c integer;
BEGIN
    DELETE FROM payment_callbacks WHERE created_at < p_now - interval '90 days' AND outcome <> 'queued';
    GET DIAGNOSTICS a = ROW_COUNT;
    DELETE FROM payment_unmatched_callbacks WHERE last_seen_at < p_now - interval '30 days';
    GET DIAGNOSTICS b = ROW_COUNT;
    DELETE FROM payment_callback_counters WHERE day < (p_now - interval '400 days')::date;
    GET DIAGNOSTICS c = ROW_COUNT;
    RETURN a + b + c;
END
$$;

REVOKE ALL ON FUNCTION app_resolve_payment_intent(text, text, text, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_tenants_with_due_payment_checks(timestamptz, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_record_unmatched_payment_callback(text, text, text, text, text, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_count_payment_callback(date, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION payments_housekeeping(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_resolve_payment_intent(text, text, text, text) TO bms_app;
GRANT EXECUTE ON FUNCTION app_tenants_with_due_payment_checks(timestamptz, integer) TO bms_app;
GRANT EXECUTE ON FUNCTION app_record_unmatched_payment_callback(text, text, text, text, text, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION app_count_payment_callback(date, text) TO bms_app;
GRANT EXECUTE ON FUNCTION payments_housekeeping(timestamptz) TO bms_app;
