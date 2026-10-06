-- V23: self-onboarding slice 1 (issue #89, ADR-024, docs/specs/self-onboarding-and-subscriptions.md
-- sections 4, 10, 11 and 12). Additive only.
--
-- Two platform tables with no tenant_id: onboarding_applications (an application is not a tenant)
-- and notification_outbox (one row per message to send, chapter 6 section 6.4). Both hold
-- personal data or one-time links, so, unlike the platform tables of V2, bms_app holds no
-- privilege on them at all: every read and write goes through the SECURITY DEFINER functions
-- below, owned by bms_owner with a fixed search_path, executable by bms_app only (ADR-016).
-- Row-level security is enabled and forced, with no policy, as on every table (NFR-ISO-01):
-- bms_owner has BYPASSRLS, so its functions see every row, and any other role is refused every
-- row even if a grant is added by mistake.
--
-- V23 is the next number after the retail stock transfers (V22, #84); Flyway runs with outOfOrder
-- off, so a later migration takes V24.

-- ---------------------------------------------------------------------------------------------
-- Business name normalisation for the duplicate warning (spec section 4.2 item 4): lower case,
-- punctuation removed, the usual company suffixes dropped, spaces collapsed.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION onboarding_normalise_name(p_name text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE SET search_path = pg_catalog AS $$
    SELECT btrim(regexp_replace(
               regexp_replace(
                   regexp_replace(lower(coalesce(p_name, '')), '[^a-z0-9 ]+', ' ', 'g'),
                   '(^| )(ltd|limited|co|company|smc|enterprises?|the)( |$)', ' ', 'g'),
               ' +', ' ', 'g'))
$$;
GRANT EXECUTE ON FUNCTION onboarding_normalise_name(text) TO bms_app;

-- The mailbox an email address reaches, for "one open application per email" and the per-email
-- bound (review N5): lower case, a +tag in the local part dropped, and for Gmail the dots in the
-- local part dropped and googlemail.com read as gmail.com.
CREATE FUNCTION onboarding_email_key(p_email text) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE SET search_path = pg_catalog AS $$
    SELECT CASE
             WHEN d IN ('gmail.com', 'googlemail.com') THEN replace(l, '.', '') || '@gmail.com'
             ELSE l || '@' || d
           END
      FROM (SELECT split_part(split_part(e, '@', 1), '+', 1) AS l, split_part(e, '@', 2) AS d
              FROM (SELECT lower(btrim(coalesce(p_email, ''))) AS e) x) y
$$;
GRANT EXECUTE ON FUNCTION onboarding_email_key(text) TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- Applications (spec section 4). Status flow: submitted -> needs_info -> verified -> activated,
-- or rejected, or expired (email not verified within 14 days). email_verified_at decides whether
-- the application is in the operator's queue.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE onboarding_applications (
    id                       uuid          NOT NULL PRIMARY KEY,
    reference                varchar(12)   NOT NULL UNIQUE,
    status                   text          NOT NULL DEFAULT 'submitted'
        CHECK (status IN ('submitted', 'needs_info', 'verified', 'activated', 'rejected', 'expired')),
    business_name            varchar(200)  NOT NULL,
    business_name_normalised text          GENERATED ALWAYS AS (onboarding_normalise_name(business_name)) STORED,
    contact_name             varchar(200)  NOT NULL,
    contact_email            varchar(320)  NOT NULL,
    contact_email_key        text          GENERATED ALWAYS AS (onboarding_email_key(contact_email)) STORED,
    contact_phone_e164       varchar(20)   NOT NULL,
    country                  char(2)       NOT NULL,
    modules                  text[]        NOT NULL,
    term                     text          NOT NULL CHECK (term IN ('monthly', 'annual')),
    way_in                   text          NOT NULL CHECK (way_in IN ('trial', 'paid')),
    -- Recorded and ignored in this slice: agents arrive in build step 4.
    agent_code               varchar(40),
    message                  varchar(1000),
    -- One applicant link at a time (verification and status page): SHA-256 of 256 random bits.
    link_token_hash          char(64)      UNIQUE,
    link_token_expires_at    timestamptz,
    email_verified_at        timestamptz,
    -- The operator's note the applicant sees (needs_info), and the applicant's answer.
    operator_note            varchar(1000),
    applicant_reply          varchar(1000),
    reject_reason            varchar(1000),
    decided_by               uuid          REFERENCES platform_users (id),
    decided_at               timestamptz,
    activated_by             uuid          REFERENCES platform_users (id),
    activated_at             timestamptz,
    activated_tenant         uuid          REFERENCES tenants (id),
    activation_way           text          CHECK (activation_way IN ('trial', 'paid')),
    activation_term          text          CHECK (activation_term IN ('monthly', 'annual')),
    -- Slice 1: the operator's free text note of the payment received (no amounts are modelled).
    activation_note          varchar(500),
    closed_at                timestamptz,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    CHECK (status <> 'activated' OR (activated_tenant IS NOT NULL AND activated_at IS NOT NULL))
);
-- One open application per email (spec section 10).
CREATE UNIQUE INDEX onboarding_applications_one_open_per_email ON onboarding_applications (contact_email_key)
    WHERE status IN ('submitted', 'needs_info', 'verified');
CREATE INDEX onboarding_applications_queue ON onboarding_applications (status, created_at DESC);
CREATE INDEX onboarding_applications_by_phone ON onboarding_applications (contact_phone_e164);
CREATE INDEX onboarding_applications_by_name ON onboarding_applications (business_name_normalised);
CREATE INDEX onboarding_applications_closed ON onboarding_applications (closed_at) WHERE status IN ('rejected', 'expired');
ALTER TABLE onboarding_applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE onboarding_applications FORCE ROW LEVEL SECURITY;
REVOKE ALL ON onboarding_applications FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- The outbox (spec section 11, FR-NTF-01, FR-NTF-10). A row is written in the same transaction
-- as its cause; the sender job claims pending rows with FOR UPDATE SKIP LOCKED, sends and records
-- the result. A Telegram row's recipient is the literal 'operator': the chat id stays in the
-- environment. The parameters can hold a one-time link: they are cleared once the row is sent,
-- and the nightly purge clears them on any row older than 7 days, the longest link lifetime
-- (review N1). throttle_key groups rows for a volume bound (the per-mailbox verification limit).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE notification_outbox (
    id              uuid         NOT NULL PRIMARY KEY,
    channel         text         NOT NULL CHECK (channel IN ('email', 'telegram')),
    recipient       varchar(320) NOT NULL,
    template_key    varchar(100) NOT NULL,
    params          jsonb        NOT NULL DEFAULT '{}',
    status          text         NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'failed')),
    attempts        integer      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    idempotency_key varchar(200) NOT NULL UNIQUE,
    throttle_key    varchar(400),
    -- When the link the row carries stops working: never sent, retried or kept after it.
    expires_at      timestamptz,
    last_error      varchar(300),
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    sent_at         timestamptz,
    failed_at       timestamptz
);
CREATE INDEX notification_outbox_pending ON notification_outbox (next_attempt_at) WHERE status = 'pending';
CREATE INDEX notification_outbox_failed ON notification_outbox (failed_at DESC) WHERE status = 'failed';
CREATE INDEX notification_outbox_throttle ON notification_outbox (throttle_key, created_at) WHERE throttle_key IS NOT NULL;
CREATE INDEX notification_outbox_by_template ON notification_outbox (template_key, created_at);
ALTER TABLE notification_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_outbox FORCE ROW LEVEL SECURITY;
REVOKE ALL ON notification_outbox FROM PUBLIC;

-- ---------------------------------------------------------------------------------------------
-- Application functions. Times come from the caller (the kernel clock), never from now().
-- ---------------------------------------------------------------------------------------------

-- Returns true when the application was created, false when the email already has an open one.
CREATE FUNCTION onboarding_application_create(
    p_id uuid, p_reference text, p_business_name text, p_contact_name text, p_email text, p_phone text,
    p_country text, p_modules text[], p_term text, p_way_in text, p_agent_code text, p_message text,
    p_link_hash text, p_link_expires_at timestamptz, p_now timestamptz)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO onboarding_applications (
        id, reference, business_name, contact_name, contact_email, contact_phone_e164, country, modules, term,
        way_in, agent_code, message, link_token_hash, link_token_expires_at, created_at, updated_at)
    VALUES (p_id, p_reference, p_business_name, p_contact_name, p_email, p_phone, p_country, p_modules, p_term,
            p_way_in, p_agent_code, p_message, p_link_hash, p_link_expires_at, p_now, p_now)
    ON CONFLICT (contact_email_key) WHERE status IN ('submitted', 'needs_info', 'verified') DO NOTHING;
    RETURN FOUND;
END
$$;

-- A new applicant link for the open application of this mailbox (a repeated sign-up): returns
-- the application's reference, or nothing when the mailbox has no open application. The link goes
-- to the address just submitted (round 2 review item 2), which the key treats as the same mailbox;
-- while unconfirmed, that address also becomes the stored one.
CREATE FUNCTION onboarding_application_relink(
    p_email text, p_link_hash text, p_link_expires_at timestamptz, p_now timestamptz)
RETURNS TABLE (id uuid, reference text, contact_email text)
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    UPDATE onboarding_applications a
       SET link_token_hash = p_link_hash, link_token_expires_at = p_link_expires_at, updated_at = p_now,
           -- Until the address is confirmed, the last submitted form of the mailbox is the one the
           -- link goes to, so a variant submitted first (which may bounce) cannot squat it.
           contact_email = CASE WHEN a.email_verified_at IS NULL THEN p_email ELSE a.contact_email END
     WHERE a.contact_email_key = onboarding_email_key(p_email) AND a.status IN ('submitted', 'needs_info', 'verified')
    RETURNING a.id, a.reference::text, a.contact_email::text
$$;

-- New applications since a time, for the global sign-up cap (review B2).
CREATE FUNCTION onboarding_applications_created_since(p_since timestamptz, p_until timestamptz)
RETURNS integer
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT count(*)::integer FROM onboarding_applications WHERE created_at >= p_since AND created_at <= p_until
$$;

-- What the applicant may see (spec section 10), by link token. With p_verify the email is marked
-- verified on first use; newly_verified is then true exactly once, for the operator alert.
CREATE FUNCTION onboarding_application_by_link(p_link_hash text, p_now timestamptz, p_verify boolean)
RETURNS TABLE (
    id uuid, reference text, status text, business_name text, modules text[], term text, way_in text,
    operator_note text, reject_reason text, link_token_hash text, email_verified boolean,
    newly_verified boolean, created_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v onboarding_applications%ROWTYPE;
    v_new boolean := false;
BEGIN
    SELECT * INTO v FROM onboarding_applications a
     WHERE a.link_token_hash = p_link_hash AND a.link_token_expires_at > p_now
     FOR UPDATE;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    IF p_verify AND v.email_verified_at IS NULL AND v.status IN ('submitted', 'needs_info') THEN
        UPDATE onboarding_applications SET email_verified_at = p_now, updated_at = p_now WHERE onboarding_applications.id = v.id;
        v.email_verified_at := p_now;
        v_new := true;
    END IF;
    RETURN QUERY SELECT v.id, v.reference::text, v.status, v.business_name::text, v.modules, v.term, v.way_in,
                        v.operator_note::text, v.reject_reason::text, v.link_token_hash::text,
                        v.email_verified_at IS NOT NULL, v_new, v.created_at;
END
$$;

-- The applicant's answer to a needs_info note: back to submitted, in the queue again.
CREATE FUNCTION onboarding_application_reply(p_link_hash text, p_reply text, p_now timestamptz)
RETURNS uuid
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    UPDATE onboarding_applications
       SET applicant_reply = p_reply, status = 'submitted', updated_at = p_now
     WHERE link_token_hash = p_link_hash AND link_token_expires_at > p_now AND status = 'needs_info'
    RETURNING id
$$;

-- The operator's view (spec section 8): no token columns.
CREATE FUNCTION onboarding_applications_list(p_statuses text[], p_id uuid, p_limit integer)
RETURNS TABLE (
    id uuid, reference text, status text, business_name text, contact_name text, contact_email text,
    contact_phone_e164 text, country text, modules text[], term text, way_in text, agent_code text,
    message text, email_verified_at timestamptz, operator_note text, applicant_reply text,
    reject_reason text, decided_by uuid, decided_at timestamptz, activated_by uuid, activated_at timestamptz,
    activated_tenant uuid, activated_tenant_slug text, activation_way text, activation_term text,
    activation_note text, created_at timestamptz, updated_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.id, a.reference::text, a.status, a.business_name::text, a.contact_name::text, a.contact_email::text,
           a.contact_phone_e164::text, a.country::text, a.modules, a.term, a.way_in, a.agent_code::text,
           a.message::text, a.email_verified_at, a.operator_note::text, a.applicant_reply::text,
           a.reject_reason::text, a.decided_by, a.decided_at, a.activated_by, a.activated_at,
           a.activated_tenant, (SELECT t.slug::text FROM tenants t WHERE t.id = a.activated_tenant), a.activation_way, a.activation_term, a.activation_note::text,
           a.created_at, a.updated_at
      FROM onboarding_applications a
     WHERE (p_statuses IS NULL OR a.status = ANY (p_statuses))
       AND (p_id IS NULL OR a.id = p_id)
       -- An application reaches the queue only once its email is verified (spec section 4).
       AND (a.email_verified_at IS NOT NULL OR a.status = 'expired')
     ORDER BY a.created_at DESC, a.id
     LIMIT p_limit
$$;

CREATE FUNCTION onboarding_application_counts()
RETURNS TABLE (status text, total bigint)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.status, count(*) FROM onboarding_applications a
     WHERE a.email_verified_at IS NOT NULL OR a.status = 'expired'
     GROUP BY a.status
$$;

-- Possible repeats for the operator's warning (spec section 4.2 item 4): other applications with
-- the same email, phone or normalised business name, and tenants with the same normalised name.
CREATE FUNCTION onboarding_application_duplicates(p_id uuid)
RETURNS TABLE (kind text, id uuid, reference text, name text, status text, matched_on text[])
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH me AS (SELECT * FROM onboarding_applications WHERE onboarding_applications.id = p_id)
    SELECT 'application', o.id, o.reference::text, o.business_name::text, o.status,
           array_remove(ARRAY[
               CASE WHEN o.contact_email_key = me.contact_email_key THEN 'email' END,
               CASE WHEN o.contact_phone_e164 = me.contact_phone_e164 THEN 'phone' END,
               CASE WHEN o.business_name_normalised = me.business_name_normalised THEN 'business_name' END], NULL)
      FROM onboarding_applications o, me
     WHERE o.id <> me.id
       AND (o.contact_email_key = me.contact_email_key
            OR o.contact_phone_e164 = me.contact_phone_e164
            OR o.business_name_normalised = me.business_name_normalised)
    UNION ALL
    SELECT 'tenant', t.id, t.slug::text, t.name, t.status, ARRAY['business_name']
      FROM tenants t, me
     WHERE onboarding_normalise_name(t.name) = me.business_name_normalised
       AND t.id IS DISTINCT FROM me.activated_tenant
$$;

-- An operator decision (Verify, Needs info, Reject). Returns the previous status, or nothing
-- when the application is not in one of p_from (the caller answers 409). A new applicant link
-- is set when the applicant is told about the decision.
CREATE FUNCTION onboarding_application_decide(
    p_id uuid, p_from text[], p_to text, p_platform_user_id uuid, p_note text,
    p_link_hash text, p_link_expires_at timestamptz, p_now timestamptz)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_previous text;
BEGIN
    IF p_to NOT IN ('verified', 'needs_info', 'rejected') THEN
        RAISE EXCEPTION 'not a decision: %', p_to USING ERRCODE = 'check_violation';
    END IF;
    SELECT status INTO v_previous FROM onboarding_applications
     WHERE id = p_id AND email_verified_at IS NOT NULL FOR UPDATE;
    IF NOT FOUND OR NOT (v_previous = ANY (p_from)) THEN
        RETURN NULL;
    END IF;
    UPDATE onboarding_applications
       SET status = p_to,
           operator_note = CASE WHEN p_to = 'needs_info' THEN p_note ELSE operator_note END,
           reject_reason = CASE WHEN p_to = 'rejected' THEN p_note ELSE reject_reason END,
           applicant_reply = CASE WHEN p_to = 'needs_info' THEN NULL ELSE applicant_reply END,
           decided_by = p_platform_user_id, decided_at = p_now,
           closed_at = CASE WHEN p_to = 'rejected' THEN p_now ELSE NULL END,
           link_token_hash = coalesce(p_link_hash, link_token_hash),
           link_token_expires_at = CASE WHEN p_link_hash IS NULL THEN link_token_expires_at ELSE p_link_expires_at END,
           updated_at = p_now
     WHERE id = p_id;
    RETURN v_previous;
END
$$;

-- Locks the application for Activate, so two concurrent activations serialise and the second one
-- sees the first one's result (idempotency, spec section 4).
CREATE FUNCTION onboarding_application_lock(p_id uuid)
RETURNS TABLE (status text, activated_tenant uuid, email_verified boolean)
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT a.status, a.activated_tenant, a.email_verified_at IS NOT NULL
      FROM onboarding_applications a WHERE a.id = p_id FOR UPDATE
$$;

CREATE FUNCTION onboarding_application_activated(
    p_id uuid, p_tenant_id uuid, p_platform_user_id uuid, p_way text, p_term text, p_note text, p_now timestamptz)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE onboarding_applications
       SET status = 'activated', activated_tenant = p_tenant_id, activated_by = p_platform_user_id,
           activated_at = p_now, activation_way = p_way, activation_term = p_term, activation_note = p_note,
           link_token_hash = NULL, link_token_expires_at = NULL, closed_at = p_now, updated_at = p_now
     WHERE id = p_id AND status = 'verified';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'application % is not verified', p_id USING ERRCODE = 'check_violation';
    END IF;
END
$$;

-- Scheduled housekeeping (spec sections 4 and 12): an application whose email is not verified
-- within 14 days expires; rejected and expired applications are deleted 90 days after closing.
CREATE FUNCTION onboarding_applications_expire(p_unverified_before timestamptz, p_now timestamptz)
RETURNS integer
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH e AS (
        UPDATE onboarding_applications
           SET status = 'expired', closed_at = p_now, link_token_hash = NULL, link_token_expires_at = NULL,
               updated_at = p_now
         WHERE status = 'submitted' AND email_verified_at IS NULL AND created_at < p_unverified_before
        RETURNING 1)
    SELECT count(*)::integer FROM e
$$;

CREATE FUNCTION onboarding_applications_purge(p_closed_before timestamptz)
RETURNS integer
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH d AS (
        DELETE FROM onboarding_applications
         WHERE status IN ('rejected', 'expired') AND closed_at < p_closed_before
        RETURNING 1)
    SELECT count(*)::integer FROM d
$$;

-- ---------------------------------------------------------------------------------------------
-- Outbox functions.
-- ---------------------------------------------------------------------------------------------

-- Returns true when the row was written, false when the idempotency key was already used.
CREATE FUNCTION notification_outbox_enqueue(
    p_id uuid, p_channel text, p_recipient text, p_template_key text, p_params jsonb, p_idempotency_key text,
    p_throttle_key text, p_expires_at timestamptz, p_now timestamptz)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO notification_outbox (id, channel, recipient, template_key, params, idempotency_key, throttle_key,
                                     expires_at, next_attempt_at, created_at, updated_at)
    VALUES (p_id, p_channel, p_recipient, p_template_key, coalesce(p_params, '{}'), p_idempotency_key, p_throttle_key,
            p_expires_at, p_now, p_now, p_now)
    ON CONFLICT (idempotency_key) DO NOTHING;
    RETURN FOUND;
END
$$;

-- Rows written since a time, by throttle key or, with a null key, by template: the volume bounds
-- of the sign-up endpoints are counted here, in the database, not in an evictable map (review B2).
CREATE FUNCTION notification_outbox_count_since(
    p_throttle_key text, p_template_key text, p_since timestamptz, p_until timestamptz)
RETURNS integer
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT count(*)::integer FROM notification_outbox o
     WHERE o.created_at >= p_since AND o.created_at <= p_until
       AND (p_throttle_key IS NULL OR o.throttle_key = p_throttle_key)
       AND (p_template_key IS NULL OR o.template_key = p_template_key)
$$;

-- Claims the next due row of an enabled channel in its own short transaction (review N3): the
-- attempt is counted and a lease set (next_attempt_at = p_lease_until) before anything is sent, so
-- a crash, a lost connection or an Error during the send still uses up an attempt and the row comes
-- back only after the lease. Before claiming, due rows that have used p_max_attempts (an attempt
-- that died before it could record a failure) or whose link has expired become failed, so the
-- attempt bound holds whatever the send does (round 2 review item 3). A concurrent sender skips a
-- locked row (SKIP LOCKED) while the claim runs, and the lease keeps it away afterwards.
CREATE FUNCTION notification_outbox_claim(
    p_channels text[], p_max_attempts integer, p_now timestamptz, p_lease_until timestamptz)
RETURNS TABLE (id uuid, channel text, recipient text, template_key text, params jsonb, idempotency_key text, attempts integer)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE notification_outbox d
       SET status = 'failed', failed_at = p_now, updated_at = p_now,
           last_error = CASE WHEN d.expires_at IS NOT NULL AND d.expires_at <= p_now THEN 'expired'
                             ELSE coalesce(d.last_error, 'attempts used up') END
     WHERE d.status = 'pending' AND d.next_attempt_at <= p_now AND d.channel = ANY (p_channels)
       AND (d.attempts >= p_max_attempts OR (d.expires_at IS NOT NULL AND d.expires_at <= p_now));
    RETURN QUERY
    UPDATE notification_outbox o
       SET attempts = o.attempts + 1, next_attempt_at = p_lease_until, updated_at = p_now
     WHERE o.id = (SELECT c.id FROM notification_outbox c
                    WHERE c.status = 'pending' AND c.next_attempt_at <= p_now AND c.channel = ANY (p_channels)
                      AND c.attempts < p_max_attempts
                    ORDER BY c.next_attempt_at, c.id
                    LIMIT 1
                      FOR UPDATE SKIP LOCKED)
    RETURNING o.id, o.channel, o.recipient::text, o.template_key::text, o.params, o.idempotency_key::text, o.attempts;
END
$$;

-- Records the result of a claimed attempt (attempts already counted). Success: sent, parameters
-- cleared. Failure: pending again at p_retry_at, or failed once p_max_attempts is reached.
-- Returns the new status.
CREATE FUNCTION notification_outbox_record(
    p_id uuid, p_ok boolean, p_error text, p_max_attempts integer, p_retry_at timestamptz, p_now timestamptz)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_status text;
BEGIN
    UPDATE notification_outbox o
       SET status = CASE WHEN p_ok THEN 'sent' WHEN o.attempts >= p_max_attempts THEN 'failed' ELSE 'pending' END,
           sent_at = CASE WHEN p_ok THEN p_now ELSE NULL END,
           failed_at = CASE WHEN NOT p_ok AND o.attempts >= p_max_attempts THEN p_now ELSE NULL END,
           params = CASE WHEN p_ok THEN '{}'::jsonb ELSE o.params END,
           last_error = CASE WHEN p_ok THEN NULL ELSE left(p_error, 300) END,
           next_attempt_at = CASE WHEN p_ok THEN o.next_attempt_at ELSE p_retry_at END,
           updated_at = p_now
     WHERE o.id = p_id AND o.status = 'pending'
    RETURNING o.status INTO v_status;
    RETURN v_status;
END
$$;

-- Failed rows for the operator portal: the recipient is masked by the caller, no parameters.
CREATE FUNCTION notification_outbox_failures(p_limit integer)
RETURNS TABLE (id uuid, channel text, recipient text, template_key text, attempts integer, last_error text,
               created_at timestamptz, failed_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT o.id, o.channel, o.recipient::text, o.template_key::text, o.attempts, o.last_error::text, o.created_at, o.failed_at
      FROM notification_outbox o WHERE o.status = 'failed'
     ORDER BY o.failed_at DESC, o.id
     LIMIT p_limit
$$;

CREATE FUNCTION notification_outbox_counts()
RETURNS TABLE (status text, total bigint)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT o.status, count(*) FROM notification_outbox o GROUP BY o.status
$$;

-- The operator sends a failed row again: pending, attempts reset. Only a row created after
-- p_created_after (the longest link lifetime ago), whose own link has not expired (an activation
-- link lasts 72 hours) and whose parameters are still there; returns 'retried', 'too_old' or
-- 'not_failed' (review N1, round 2 item 5: an expired link is never sent).
CREATE FUNCTION notification_outbox_retry(p_id uuid, p_created_after timestamptz, p_now timestamptz)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v notification_outbox%ROWTYPE;
BEGIN
    SELECT * INTO v FROM notification_outbox WHERE notification_outbox.id = p_id FOR UPDATE;
    IF NOT FOUND OR v.status <> 'failed' THEN
        RETURN 'not_failed';
    END IF;
    IF v.created_at < p_created_after OR v.params = '{}'::jsonb
       OR (v.expires_at IS NOT NULL AND v.expires_at <= p_now) THEN
        RETURN 'too_old';
    END IF;
    UPDATE notification_outbox
       SET status = 'pending', attempts = 0, failed_at = NULL, next_attempt_at = p_now, updated_at = p_now
     WHERE notification_outbox.id = p_id;
    RETURN 'retried';
END
$$;

-- Nightly: sent rows are deleted 30 days after sending; any pending or failed row older than the
-- longest link lifetime has its parameters cleared, and a pending one becomes failed ('expired'),
-- so no one-time link outlives its use in the outbox (review N1). Returns the rows touched.
CREATE FUNCTION notification_outbox_purge(p_sent_before timestamptz, p_links_before timestamptz, p_now timestamptz)
RETURNS integer
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH d AS (DELETE FROM notification_outbox WHERE status = 'sent' AND sent_at < p_sent_before RETURNING 1),
         c AS (UPDATE notification_outbox
                  SET params = '{}'::jsonb,
                      status = 'failed',
                      failed_at = coalesce(failed_at, p_now),
                      last_error = CASE WHEN status = 'pending' THEN 'expired' ELSE last_error END,
                      updated_at = p_now
                WHERE status IN ('pending', 'failed')
                  AND (created_at < p_links_before OR (expires_at IS NOT NULL AND expires_at < p_now))
                  AND (params <> '{}'::jsonb OR status = 'pending')
               RETURNING 1)
    SELECT ((SELECT count(*) FROM d) + (SELECT count(*) FROM c))::integer
$$;

REVOKE ALL ON FUNCTION onboarding_application_create(uuid, text, text, text, text, text, text, text[], text, text, text, text, text, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_relink(text, text, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_by_link(text, timestamptz, boolean) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_reply(text, text, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_applications_list(text[], uuid, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_counts() FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_duplicates(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_decide(uuid, text[], text, uuid, text, text, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_lock(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_application_activated(uuid, uuid, uuid, text, text, text, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_applications_expire(timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_applications_purge(timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_enqueue(uuid, text, text, text, jsonb, text, text, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_claim(text[], integer, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_record(uuid, boolean, text, integer, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_failures(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_counts() FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_retry(uuid, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_purge(timestamptz, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_count_since(text, text, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION onboarding_applications_created_since(timestamptz, timestamptz) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION onboarding_application_create(uuid, text, text, text, text, text, text, text[], text, text, text, text, text, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_relink(text, text, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_by_link(text, timestamptz, boolean) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_reply(text, text, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_applications_list(text[], uuid, integer) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_counts() TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_duplicates(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_decide(uuid, text[], text, uuid, text, text, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_lock(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_application_activated(uuid, uuid, uuid, text, text, text, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_applications_expire(timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_applications_purge(timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_enqueue(uuid, text, text, text, jsonb, text, text, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_claim(text[], integer, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_record(uuid, boolean, text, integer, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_failures(integer) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_counts() TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_retry(uuid, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_purge(timestamptz, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_count_since(text, text, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION onboarding_applications_created_since(timestamptz, timestamptz) TO bms_app;
