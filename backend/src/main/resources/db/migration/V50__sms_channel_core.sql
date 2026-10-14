-- V50: the SMS channel core (issue #212, slice B1 of the client extras, ADR-036, spec section 4).
-- Numbered above the highest on main and on the open branches at the time of writing (V48 landed
-- cost #199, V49 report runs #214); the coordinator renumbers at merge, because outOfOrder is off.
--
-- Expand only (chapter 6 section 6.9): new nullable or defaulted columns, new tables, new
-- functions. The one replaced function is notification_outbox_claim: it keeps its argument list and
-- gains result columns, which an older reader that selects columns by name does not notice; the
-- drop and the create run in this one migration transaction, so no caller sees it missing.
--
-- Lock note, stated honestly. Flyway runs this whole file as ONE transaction, so every lock taken
-- below is held until the final COMMIT. The first ALTER TABLE takes ACCESS EXCLUSIVE on
-- notification_outbox: from then until commit nothing can read or write the outbox (the sender, the
-- enqueue of a savings receipt or a sign-up email, the operator portal wait), and the foreign key
-- adds SHARE ROW EXCLUSIVE on tenants, which blocks writes to tenants for the same time. ADD COLUMN
-- with a constant default is a catalogue change (no rewrite), but the CHECK constraints and the foreign
-- key scan the table, and so does the index build. The table is purged 30 days after sending, so this is
-- seconds at the current size, not minutes; it is a short outage of the outbox and of tenant writes, not
-- a zero-lock change, and the migrate step runs before the application containers switch (ADR-006), so
-- the old containers queue behind it. (NOT VALID followed by VALIDATE inside this one transaction gains
-- nothing, so the constraints are added validated.) The index on notification_outbox(updated_at) is
-- built without CONCURRENTLY, which a migration transaction cannot do. Every new table is empty. The
-- functions replaced here (the claim and the failed-rows list gain result columns, the retry and the
-- purge gain a statement) are dropped and created, or replaced, in this same transaction.
--
-- What this migration holds:
--   1. notification_outbox learns its tenant, a category and an optional subject, and the enqueue
--      overload that sets them (the tenant is read from the bound transaction, never a parameter).
--   2. The claim: transactional rows first, then round-robin across tenants (rank within tenant, plus what
--      the tenant was given in the last minute), then due time.
--   3. notification_outbox_record_result: Sent, a clean failure, Deferred (attempt given back),
--      Unknown (failed, never retried) and Refused (failed, final, reason only).
--   4. sms_channel_state and the backlog drop when the channel moves to a sending mode.
--   5. platform_sms_controls: the operator-only per tenant switch and daily cap.
--   6. sms_deliveries (row-level security): the delivery and cost log.
--   7. sms_opt_outs (row-level security).
--   8. Platform helpers: the platform-wide daily count, the per tenant count for the operator, the
--      13 month retention purge, and the outbox purge now deleting a failed SMS row 30 days after it failed.

-- ---------------------------------------------------------------------------------------------
-- 1. The outbox row learns its tenant, its category and its subject (ADR-036 decision 2).
--    Platform rows keep tenant_id null. category: transactional (receipts, payment links; never
--    held by the window), reminder, arrears. subject: the member or the account the message is
--    about, for the opt-out and the delivery log; it is an identifier, never a name or a phone.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE notification_outbox
    ADD COLUMN tenant_id    uuid,
    ADD COLUMN category     text NOT NULL DEFAULT 'transactional',
    ADD COLUMN subject_type text,
    ADD COLUMN subject_id   uuid;

ALTER TABLE notification_outbox
    ADD CONSTRAINT notification_outbox_category_check
        CHECK (category IN ('transactional', 'reminder', 'arrears')),
    ADD CONSTRAINT notification_outbox_subject_check
        CHECK ((subject_type IS NULL AND subject_id IS NULL)
            OR (subject_type IN ('member', 'account') AND subject_id IS NOT NULL)),
    ADD CONSTRAINT notification_outbox_tenant_fk
        FOREIGN KEY (tenant_id) REFERENCES tenants (id);

-- ---------------------------------------------------------------------------------------------
-- 6 (created early because the enqueue function reads the controls). The operator-only per tenant
-- SMS controls: a switch (off by default) and a daily cap. A platform table like the outbox: no
-- grant to bms_app, row-level security forced with no policy, reached only through the definer
-- functions below. The tenant admin has no write path to it (ADR-036 decision 13).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE platform_sms_controls (
    tenant_id  uuid        NOT NULL PRIMARY KEY REFERENCES tenants (id),
    enabled    boolean     NOT NULL DEFAULT false,
    daily_cap  integer     NOT NULL CHECK (daily_cap BETWEEN 0 AND 100000),
    updated_by uuid        REFERENCES platform_users (id),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE platform_sms_controls ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_sms_controls FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform_sms_controls FROM PUBLIC;

-- The bound tenant's controls; no row means off. Reads app.tenant_id, so a tenant transaction can
-- only ever see its own row.
CREATE FUNCTION sms_tenant_control_current()
RETURNS TABLE (enabled boolean, daily_cap integer)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT c.enabled, c.daily_cap FROM platform_sms_controls c
     WHERE c.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
$$;

-- The operator's read and write (platform console). The read gives off and a cap of 200 for a tenant
-- with no row yet, and no row at all for an unknown tenant.
CREATE FUNCTION sms_tenant_control_get(p_tenant_id uuid)
RETURNS TABLE (enabled boolean, daily_cap integer, updated_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT coalesce(c.enabled, false), coalesce(c.daily_cap, 200), c.updated_at
      FROM tenants t LEFT JOIN platform_sms_controls c ON c.tenant_id = t.id
     WHERE t.id = p_tenant_id
$$;

CREATE FUNCTION sms_tenant_control_set(
    p_tenant_id uuid, p_enabled boolean, p_daily_cap integer, p_platform_user_id uuid, p_now timestamptz)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    -- Platform only: a transaction bound to a tenant (a tenant admin's request, a tenant job) can
    -- never change a cap or a switch, even through a mistake in the code above it.
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'SMS controls are set by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM tenants WHERE id = p_tenant_id) THEN
        RAISE EXCEPTION 'unknown tenant' USING ERRCODE = 'no_data_found';
    END IF;
    INSERT INTO platform_sms_controls (tenant_id, enabled, daily_cap, updated_by, updated_at)
    VALUES (p_tenant_id, p_enabled, p_daily_cap, p_platform_user_id, p_now)
    ON CONFLICT (tenant_id) DO UPDATE
        SET enabled = excluded.enabled, daily_cap = excluded.daily_cap,
            updated_by = excluded.updated_by, updated_at = excluded.updated_at;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 1 (continued). The enqueue overload. The tenant is read from the bound transaction
-- (app.tenant_id), never from a parameter (standing rule 1); outside a tenant transaction it is
-- null. An SMS row needs a bound tenant and an expiry, and is not written at all while the tenant's
-- SMS is switched off (returns false, as for a repeated key): nothing sits in the outbox for a
-- tenant that does not use SMS. A transactional SMS expires at most one hour after it is queued
-- (ADR-036 decision 14). p_not_before holds the first attempt back (a reminder to its window).
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION notification_outbox_enqueue(
    p_id uuid, p_channel text, p_recipient text, p_template_key text, p_params jsonb, p_idempotency_key text,
    p_throttle_key text, p_expires_at timestamptz, p_now timestamptz,
    p_category text, p_subject_type text, p_subject_id uuid, p_not_before timestamptz)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant  uuid := nullif(current_setting('app.tenant_id', true), '')::uuid;
    v_expires timestamptz := p_expires_at;
BEGIN
    IF p_channel = 'sms' THEN
        IF v_tenant IS NULL THEN
            RAISE EXCEPTION 'an SMS row needs a bound tenant' USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM platform_sms_controls c WHERE c.tenant_id = v_tenant AND c.enabled) THEN
            RETURN false;
        END IF;
        IF p_category = 'transactional' THEN
            v_expires := least(coalesce(v_expires, p_now + interval '1 hour'), p_now + interval '1 hour');
        END IF;
        IF v_expires IS NULL THEN
            RAISE EXCEPTION 'an SMS row needs an expiry' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    INSERT INTO notification_outbox (id, channel, recipient, template_key, params, idempotency_key, throttle_key,
                                     expires_at, next_attempt_at, created_at, updated_at,
                                     tenant_id, category, subject_type, subject_id)
    VALUES (p_id, p_channel, p_recipient, p_template_key, coalesce(p_params, '{}'), p_idempotency_key, p_throttle_key,
            v_expires, coalesce(p_not_before, p_now), p_now, p_now,
            v_tenant, coalesce(p_category, 'transactional'), p_subject_type, p_subject_id)
    ON CONFLICT (idempotency_key) DO NOTHING;
    RETURN FOUND;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 2. The claim (ADR-036 decision 5). Same arguments, more result columns. Order: transactional
-- rows first; within a priority, round-robin across tenants; then by due time. A tenant's turn is its
-- rank among its own due rows (its first row has rank 1) plus the rows it was given in the last
-- minute (attempted and updated since then, whatever came of them): one row is claimed per call, so
-- a rank alone would be 1 again for the tenant that was just served and one tenant's thousand
-- reminders would run before another's one. With the recent count, tenants alternate: A, B, A, B.
-- Platform rows, with no tenant, are one group. The best few candidates are locked in that order
-- with SKIP LOCKED, so a row another sender holds is skipped and the next is taken; FOR UPDATE
-- cannot sit on the window query itself.
-- ---------------------------------------------------------------------------------------------

DROP FUNCTION notification_outbox_claim(text[], integer, timestamptz, timestamptz);

CREATE FUNCTION notification_outbox_claim(
    p_channels text[], p_max_attempts integer, p_now timestamptz, p_lease_until timestamptz)
RETURNS TABLE (id uuid, channel text, recipient text, template_key text, params jsonb, idempotency_key text,
               attempts integer, tenant_id uuid, category text, subject_type text, subject_id uuid,
               created_at timestamptz, expires_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE notification_outbox d
       SET status = 'failed', failed_at = p_now, updated_at = p_now,
           last_error = CASE WHEN d.expires_at IS NOT NULL AND d.expires_at <= p_now THEN 'expired'
                             ELSE coalesce(d.last_error, 'attempts used up') END
     WHERE d.status = 'pending' AND d.next_attempt_at <= p_now AND d.channel = ANY (p_channels)
       AND (d.attempts >= p_max_attempts OR (d.expires_at IS NOT NULL AND d.expires_at <= p_now));
    RETURN QUERY
    WITH recent AS (
        SELECT r.tenant_id AS rtenant, count(*) AS given
          FROM notification_outbox r
         WHERE r.updated_at >= p_now - interval '1 minute' AND r.attempts > 0 AND r.channel = ANY (p_channels)
         GROUP BY r.tenant_id
    ), due AS (
        SELECT c.id AS cid, (c.category <> 'transactional') AS low_priority, c.next_attempt_at AS due_at,
               row_number() OVER (PARTITION BY c.tenant_id, (c.category <> 'transactional')
                                  ORDER BY c.next_attempt_at, c.id) - 1 + coalesce(rc.given, 0) AS turn
          FROM notification_outbox c LEFT JOIN recent rc ON rc.rtenant IS NOT DISTINCT FROM c.tenant_id
         WHERE c.status = 'pending' AND c.next_attempt_at <= p_now AND c.channel = ANY (p_channels)
           AND c.attempts < p_max_attempts
    ), candidates AS (
        SELECT due.cid, row_number() OVER (ORDER BY due.low_priority, due.turn, due.due_at, due.cid) AS pos
          FROM due
         ORDER BY due.low_priority, due.turn, due.due_at, due.cid
         LIMIT 20
    ), picked AS (
        SELECT x.id AS pid
          FROM candidates k JOIN notification_outbox x ON x.id = k.cid
         -- PostgreSQL re-evaluates this on the locked row if another sender claimed and committed it
         -- first: by then its lease has moved next_attempt_at into the future, so it is no longer due.
         WHERE x.status = 'pending' AND x.next_attempt_at <= p_now AND x.attempts < p_max_attempts
           AND x.channel = ANY (p_channels)
         ORDER BY k.pos
         LIMIT 1
           FOR UPDATE OF x SKIP LOCKED
    )
    UPDATE notification_outbox o
       SET attempts = o.attempts + 1, next_attempt_at = p_lease_until, updated_at = p_now
      FROM picked
     WHERE o.id = picked.pid
    RETURNING o.id, o.channel, o.recipient::text, o.template_key::text, o.params, o.idempotency_key::text, o.attempts,
              o.tenant_id, o.category, o.subject_type, o.subject_id, o.created_at, o.expires_at;
END
$$;

-- The recent count above reads by time; the table is purged 30 days after sending, so the index is small.
-- CREATE INDEX (not CONCURRENTLY, which cannot run in a migration transaction) adds nothing beyond the
-- ACCESS EXCLUSIVE lock this transaction already holds on the outbox until commit (see the lock note).
CREATE INDEX notification_outbox_by_updated ON notification_outbox (updated_at);

-- ---------------------------------------------------------------------------------------------
-- 3. Recording a result (ADR-036 decision 6). p_outcome:
--   sent      the message went; parameters cleared.
--   failed    a clean failure: pending again at p_retry_at, or failed once p_max_attempts is reached.
--   deferred  not sent and not a failure (outside the window, over a cap): the attempt is given
--             back and the row waits until p_retry_at; p_expires_at may move its expiry later (bounded).
--   unknown   the outcome cannot be known (a read timeout after the request was written): failed
--             with last_error 'unknown_outcome', parameters kept, never retried automatically.
--   refused   the channel will never send it (opt-out, switched off, a number it will not text):
--             failed with the reason as last_error, parameters cleared, no retry.
-- Only a pending (claimed) row is touched. Returns the new status, or null.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION notification_outbox_record_result(
    p_id uuid, p_outcome text, p_error text, p_max_attempts integer, p_retry_at timestamptz, p_now timestamptz,
    p_expires_at timestamptz DEFAULT NULL)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_status text;
BEGIN
    IF p_outcome NOT IN ('sent', 'failed', 'deferred', 'unknown', 'refused') THEN
        RAISE EXCEPTION 'not an outcome: %', p_outcome USING ERRCODE = 'check_violation';
    END IF;
    UPDATE notification_outbox o
       SET status = CASE p_outcome
                        WHEN 'sent' THEN 'sent'
                        WHEN 'deferred' THEN 'pending'
                        WHEN 'unknown' THEN 'failed'
                        WHEN 'refused' THEN 'failed'
                        ELSE CASE WHEN o.attempts >= p_max_attempts THEN 'failed' ELSE 'pending' END
                    END,
           attempts = CASE WHEN p_outcome = 'deferred' THEN greatest(o.attempts - 1, 0) ELSE o.attempts END,
           sent_at = CASE WHEN p_outcome = 'sent' THEN p_now ELSE NULL END,
           failed_at = CASE WHEN p_outcome IN ('unknown', 'refused')
                                 OR (p_outcome = 'failed' AND o.attempts >= p_max_attempts) THEN p_now ELSE NULL END,
           params = CASE WHEN p_outcome IN ('sent', 'refused') THEN '{}'::jsonb ELSE o.params END,
           last_error = CASE p_outcome
                            WHEN 'sent' THEN NULL
                            WHEN 'deferred' THEN o.last_error
                            WHEN 'unknown' THEN 'unknown_outcome'
                            ELSE left(p_error, 300)
                        END,
           next_attempt_at = CASE WHEN p_outcome IN ('failed', 'deferred') THEN p_retry_at ELSE o.next_attempt_at END,
           -- A deferral may name a later expiry (a reminder over its tenant's cap waits for tomorrow's window and
           -- must outlive today's): it only ever moves the expiry later, and at most 2 days after the row was queued.
           expires_at = CASE WHEN p_outcome = 'deferred' AND p_expires_at IS NOT NULL AND o.expires_at IS NOT NULL
                                  AND p_expires_at > o.expires_at
                             THEN greatest(o.expires_at, least(p_expires_at, o.created_at + interval '2 days'))
                             ELSE o.expires_at END,
           updated_at = p_now
     WHERE o.id = p_id AND o.status = 'pending'
    RETURNING o.status INTO v_status;
    RETURN v_status;
END
$$;

-- The operator's "send again" (body as in V23, plus one statement). For an SMS whose outcome was unknown it
-- is the deliberate duplicate of ADR-036 decision 6: the delivery log row is moved from unknown back to
-- failed ('operator_resend'), so the channel, which never resends an unknown message by itself, may send it
-- once more. Only unknown: a message that was sent, rejected or blocked by the opt-out stays so.
CREATE OR REPLACE FUNCTION notification_outbox_retry(p_id uuid, p_created_after timestamptz, p_now timestamptz)
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
    IF v.channel = 'sms' THEN
        UPDATE sms_deliveries
           SET status = 'failed', failure_reason = 'operator_resend', updated_at = p_now
         WHERE outbox_id = p_id AND status = 'unknown';
    END IF;
    RETURN 'retried';
END
$$;

-- The operator's failed rows now say which tenant and category a row belongs to.
DROP FUNCTION notification_outbox_failures(integer);

CREATE FUNCTION notification_outbox_failures(p_limit integer)
RETURNS TABLE (id uuid, channel text, recipient text, template_key text, attempts integer, last_error text,
               created_at timestamptz, failed_at timestamptz, tenant_id uuid, category text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT o.id, o.channel, o.recipient::text, o.template_key::text, o.attempts, o.last_error::text, o.created_at,
           o.failed_at, o.tenant_id, o.category
      FROM notification_outbox o WHERE o.status = 'failed'
     ORDER BY o.failed_at DESC, o.id
     LIMIT p_limit
$$;

-- ---------------------------------------------------------------------------------------------
-- 4. The mode state and the backlog drop (ADR-036 decision 14). The application tells the database
-- its SMS mode at start. When the channel moves from a non-sending mode (off, sink, or never seen)
-- to a sending one (allowlist, live), every SMS row still pending is marked failed
-- 'backlog_dropped' and not sent, so switching SMS on never releases old receipts. Returns the
-- number of rows dropped. One row of state; the lock makes two instances starting together agree.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE sms_channel_state (
    id         boolean     NOT NULL PRIMARY KEY DEFAULT true CHECK (id),
    mode       text        NOT NULL,
    sending    boolean     NOT NULL,
    changed_at timestamptz NOT NULL
);
ALTER TABLE sms_channel_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE sms_channel_state FORCE ROW LEVEL SECURITY;
REVOKE ALL ON sms_channel_state FROM PUBLIC;

CREATE FUNCTION sms_channel_mode_changed(p_mode text, p_now timestamptz)
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_was boolean;
    v_is  boolean := p_mode IN ('allowlist', 'live');
    v_dropped integer := 0;
BEGIN
    IF p_mode NOT IN ('off', 'sink', 'allowlist', 'live') THEN
        RAISE EXCEPTION 'not an SMS mode: %', p_mode USING ERRCODE = 'check_violation';
    END IF;
    INSERT INTO sms_channel_state (id, mode, sending, changed_at) VALUES (true, 'off', false, p_now)
    ON CONFLICT (id) DO NOTHING;
    SELECT sending INTO v_was FROM sms_channel_state WHERE id FOR UPDATE;
    IF v_is AND NOT v_was THEN
        UPDATE notification_outbox
           SET status = 'failed', failed_at = p_now, last_error = 'backlog_dropped', params = '{}'::jsonb,
               updated_at = p_now
         WHERE channel = 'sms' AND status = 'pending' AND created_at <= p_now;
        GET DIAGNOSTICS v_dropped = ROW_COUNT;
    END IF;
    UPDATE sms_channel_state
       SET mode = p_mode, sending = v_is, changed_at = CASE WHEN mode = p_mode THEN changed_at ELSE p_now END
     WHERE id;
    RETURN v_dropped;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 6. The delivery and cost log (ADR-036 decision 3, FR-NTF-13, FR-NTF-15). One row per outbox row:
-- written 'queued' before the network call (so a crash in the middle is recognised and never
-- resent), then moved to its outcome. The recipient is kept masked to its last four digits plus a
-- keyed hash (HMAC with a key derived from the application data key) for the opt-out match; the
-- number itself and the message text are never stored here. Cost is the provider's string parsed to
-- integer minor units, with the raw string kept (UGX has no minor unit). Statuses move forward
-- only; the delivery report slice (#215) adds the definer function that moves sent to delivered.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE sms_deliveries (
    id                  uuid         NOT NULL PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenants (id),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    outbox_id           uuid         NOT NULL,
    template_key        varchar(100) NOT NULL,
    category            text         NOT NULL CHECK (category IN ('transactional', 'reminder', 'arrears')),
    subject_type        text         CHECK (subject_type IN ('member', 'account')),
    subject_id          uuid,
    recipient_masked    varchar(20)  NOT NULL,
    recipient_hash      char(64)     NOT NULL,
    provider            text         NOT NULL CHECK (provider IN ('africastalking', 'sink', 'fake')),
    provider_message_id varchar(100),
    segments            integer      CHECK (segments > 0),
    provider_segments   integer      CHECK (provider_segments > 0),
    cost_minor          bigint       CHECK (cost_minor >= 0),
    currency            char(3),
    cost_raw            varchar(40),
    status              text         NOT NULL
        CHECK (status IN ('queued', 'sent', 'delivered', 'undelivered', 'failed', 'rejected',
                          'blocked_optout', 'sunk', 'unknown')),
    failure_reason      varchar(100),
    attempts            integer      NOT NULL DEFAULT 1 CHECK (attempts > 0),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, outbox_id)
);
CREATE INDEX sms_deliveries_by_provider_id ON sms_deliveries (tenant_id, provider_message_id)
    WHERE provider_message_id IS NOT NULL;
CREATE INDEX sms_deliveries_by_time ON sms_deliveries (tenant_id, created_at);
-- The platform-wide daily count and the retention purge read across tenants by time.
CREATE INDEX sms_deliveries_platform_by_time ON sms_deliveries (created_at);
SELECT bms_apply_tenant_rls('sms_deliveries');
SELECT bms_grant_app('sms_deliveries', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- 7. The opt-out (ADR-036 decision 8, FR-NTF-14). Scope 'reminders' blocks reminder and arrears
-- messages; 'all' blocks every SMS. The match is by member and by the keyed hash of the number it
-- was recorded for, so a number reused by another member is not blocked; a null hash means the
-- member whatever the number. A removal sets removed_at. The screens that write it come with #218;
-- the channel honours the table from this slice. member_id has no foreign key: the member table
-- belongs to a vertical and core tables never reference one (ADR-002), as documents.subject_id.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE sms_opt_outs (
    id             uuid        NOT NULL PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenants (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    member_id      uuid        NOT NULL,
    recipient_hash char(64),
    scope          text        NOT NULL CHECK (scope IN ('reminders', 'all')),
    source         text        NOT NULL CHECK (source IN ('staff', 'member')),
    created_by     uuid,
    removed_at     timestamptz,
    removed_by     uuid,
    UNIQUE (tenant_id, id)
);
CREATE INDEX sms_opt_outs_active ON sms_opt_outs (tenant_id, member_id) WHERE removed_at IS NULL;
SELECT bms_apply_tenant_rls('sms_opt_outs');
SELECT bms_grant_app('sms_opt_outs', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- 8. Platform helpers. Counts of a day's SMS for the platform-wide ceiling (every tenant, so a
-- definer function) and for the operator's view of one tenant; rows that count are those that cost
-- or could cost: queued (in flight), sent, delivered, undelivered, unknown, and sunk.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION sms_platform_count_since(p_since timestamptz, p_until timestamptz)
RETURNS bigint
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT count(*) FROM sms_deliveries d
     WHERE d.created_at >= p_since AND d.created_at < p_until
       AND d.status IN ('queued', 'sent', 'delivered', 'undelivered', 'unknown', 'sunk')
$$;

CREATE FUNCTION sms_tenant_count_since(p_tenant_id uuid, p_since timestamptz, p_until timestamptz)
RETURNS bigint
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT count(*) FROM sms_deliveries d
     WHERE d.tenant_id = p_tenant_id AND d.created_at >= p_since AND d.created_at < p_until
       AND d.status IN ('queued', 'sent', 'delivered', 'undelivered', 'unknown', 'sunk')
$$;

-- The nightly outbox purge (body as in V23, plus one clause): a failed SMS row is deleted at the same age as a sent
-- one (30 days after it failed), because its recipient column holds a phone number that nothing else clears; the
-- operator's failed list is a recent list. Email and Telegram rows are unchanged.
CREATE OR REPLACE FUNCTION notification_outbox_purge(p_sent_before timestamptz, p_links_before timestamptz, p_now timestamptz)
RETURNS integer
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH d AS (DELETE FROM notification_outbox
                WHERE (status = 'sent' AND sent_at < p_sent_before)
                   OR (channel = 'sms' AND status = 'failed' AND failed_at < p_sent_before)
                RETURNING 1),
         c AS (UPDATE notification_outbox
                  SET params = '{}'::jsonb,
                      status = 'failed',
                      failed_at = coalesce(failed_at, p_now),
                      last_error = CASE WHEN status = 'pending' THEN 'expired' ELSE last_error END,
                      updated_at = p_now
                WHERE status IN ('pending', 'failed')
                  AND (created_at < p_links_before OR (expires_at IS NOT NULL AND expires_at < p_now))
                  AND (params <> '{}'::jsonb OR status = 'pending')
                  AND NOT (channel = 'sms' AND status = 'failed' AND failed_at < p_sent_before)
               RETURNING 1)
    SELECT ((SELECT count(*) FROM d) + (SELECT count(*) FROM c))::integer
$$;

-- Nightly: the delivery log is kept 13 months (ADR-036 decision 3). Returns the rows deleted.
CREATE FUNCTION sms_deliveries_purge(p_before timestamptz)
RETURNS integer
LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp AS $$
    WITH d AS (DELETE FROM sms_deliveries WHERE created_at < p_before RETURNING 1)
    SELECT count(*)::integer FROM d
$$;

-- Whether a tenant is active, for the sender that binds the tenant a stored outbox row names
-- (TenantJobs.callForTenant): a suspended or closed tenant's messages are refused, not sent.
CREATE FUNCTION app_tenant_is_active(p_tenant_id uuid)
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT EXISTS (SELECT 1 FROM tenants WHERE id = p_tenant_id AND status = 'active')
$$;

-- ---------------------------------------------------------------------------------------------
-- Grants: definer functions only, executable by the application role and no one else (ADR-016).
-- ---------------------------------------------------------------------------------------------

REVOKE ALL ON FUNCTION sms_tenant_control_current() FROM PUBLIC;
REVOKE ALL ON FUNCTION sms_tenant_control_get(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION sms_tenant_control_set(uuid, boolean, integer, uuid, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_enqueue(uuid, text, text, text, jsonb, text, text, timestamptz, timestamptz, text, text, uuid, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_claim(text[], integer, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_record_result(uuid, text, text, integer, timestamptz, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION notification_outbox_failures(integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION sms_channel_mode_changed(text, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION sms_platform_count_since(timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION sms_tenant_count_since(uuid, timestamptz, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION sms_deliveries_purge(timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_tenant_is_active(uuid) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION sms_tenant_control_current() TO bms_app;
GRANT EXECUTE ON FUNCTION sms_tenant_control_get(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION sms_tenant_control_set(uuid, boolean, integer, uuid, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_enqueue(uuid, text, text, text, jsonb, text, text, timestamptz, timestamptz, text, text, uuid, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_claim(text[], integer, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_record_result(uuid, text, text, integer, timestamptz, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION notification_outbox_failures(integer) TO bms_app;
GRANT EXECUTE ON FUNCTION sms_channel_mode_changed(text, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION sms_platform_count_since(timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION sms_tenant_count_since(uuid, timestamptz, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION sms_deliveries_purge(timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION app_tenant_is_active(uuid) TO bms_app;
