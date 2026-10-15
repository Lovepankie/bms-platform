-- V52: the tenant SMS settings (issue #218, slice B3 of the client extras, ADR-036, spec section 4.8 to 4.10).
-- Numbered above the highest on main and on the open branches at the time of writing (V51 core.ai calls, #220);
-- the coordinator renumbers at merge, because outOfOrder is off.
--
-- Expand only (chapter 6 section 6.9): new tables, new functions, one replaced function body (the enqueue keeps its
-- argument list), and a relaxation of the two V50 tables that no code has written to yet (sms_opt_outs). Nothing is
-- dropped, renamed or rewritten except the single check constraint on sms_opt_outs.source that is replaced by a wider one.
--
-- Lock note, stated honestly. Flyway runs this whole file as ONE transaction, so every lock taken below is held until
-- the final COMMIT, and SET LOCAL lock_timeout makes a statement that cannot get its lock in 5 seconds fail the
-- migration (and roll all of it back) instead of queueing behind a long query and blocking everyone behind it.
--   * ALTER TABLE sms_opt_outs takes ACCESS EXCLUSIVE on that table until commit. The table was created by V50 and is
--     written only by the staff screens this migration enables, so it is empty or tiny; nothing else reads it but the
--     SMS channel, which would wait for the commit (seconds at most).
--   * The two new tables carry a foreign key to tenants (and platform_users), each of which takes SHARE ROW
--     EXCLUSIVE on the referenced table until commit: writes to tenants (and to platform_users) wait for the migration,
--     reads do not. The new tables are empty, so nothing is scanned.
--   * CREATE OR REPLACE FUNCTION takes no table lock. The legacy copy (section 6) updates only tenant_settings rows that
--     carry an SMS key, row locks held until commit; a tenant that has never set an SMS key is not touched.
-- The migrate step runs before the application containers switch (ADR-006), so the old containers queue behind it for
-- the length of the file, which is well under a second.
--
-- What this migration holds:
--   1. platform_sms_sender_ids: the operator's allow-list of approved sender ids, per tenant (a tenant admin chooses only
--      from it; an id belongs to one tenant at a time), and the definer functions around it.
--   2. sms_opt_outs: a number-only row (no member), a masked number for display, one active row per member and per
--      number, and the 'import' and 'number' sources.
--   3. sms_template_overrides: the tenant's own wording per event type.
--   4. The enqueue now also requires the tenant's own SMS switch (module_groups.sms.enabled in the tenant settings).
--   5. sms_tenant_switched_on: switching on drops what was queued before, so a tenant never releases a backlog.
--   6. A one-time copy of the legacy flat keys (window, balance) into the new settings group.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- 1. The approved sender ids (spec 4.8, ADR-036 decision 10). A platform table like platform_sms_controls: no
--    grant to bms_app, row-level security forced with no policy, reached only through the definer functions below.
--    A sender id is 3 to 11 ASCII letters and digits with at least one letter (an all-digit string is a number or a
--    short code, not a name). It belongs to one tenant at a time (a unique index on the lower-cased id among the
--    active rows), so a tenant cannot take another business's name. The tenant admin picks one of its own approved
--    ids in the settings; the operator approves and withdraws.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE platform_sms_sender_ids (
    id           uuid        NOT NULL PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants (id),
    sender_id    varchar(11) NOT NULL CHECK (sender_id ~ '^[A-Za-z0-9]{3,11}$' AND sender_id ~ '[A-Za-z]'),
    approved_by  uuid        REFERENCES platform_users (id),
    approved_at  timestamptz NOT NULL,
    withdrawn_by uuid        REFERENCES platform_users (id),
    withdrawn_at timestamptz
);
CREATE UNIQUE INDEX platform_sms_sender_ids_one_owner
    ON platform_sms_sender_ids (lower(sender_id)) WHERE withdrawn_at IS NULL;
CREATE INDEX platform_sms_sender_ids_by_tenant ON platform_sms_sender_ids (tenant_id, approved_at);
ALTER TABLE platform_sms_sender_ids ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_sms_sender_ids FORCE ROW LEVEL SECURITY;
REVOKE ALL ON platform_sms_sender_ids FROM PUBLIC;

-- The operator's list for one tenant: active ids, oldest first. Platform only: a transaction bound to a tenant is
-- refused, so a tenant session cannot read another business's approvals.
CREATE FUNCTION sms_sender_ids_for_tenant(p_tenant_id uuid)
RETURNS TABLE (sender_id text, approved_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'sender ids are listed by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
        SELECT s.sender_id::text, s.approved_at FROM platform_sms_sender_ids s
         WHERE s.tenant_id = p_tenant_id AND s.withdrawn_at IS NULL
         ORDER BY s.approved_at, s.sender_id;
END
$$;

-- Approve one id for a tenant. Returns 'approved', 'already' (the tenant already holds it) or 'taken' (another
-- tenant holds it). A concurrent approval of the same id by two operators ends in one winner through the unique index.
CREATE FUNCTION sms_sender_id_approve(
    p_tenant_id uuid, p_sender_id text, p_platform_user_id uuid, p_now timestamptz)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_owner uuid;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'sender ids are approved by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM tenants WHERE id = p_tenant_id) THEN
        RAISE EXCEPTION 'unknown tenant' USING ERRCODE = 'no_data_found';
    END IF;
    SELECT s.tenant_id INTO v_owner FROM platform_sms_sender_ids s
     WHERE lower(s.sender_id) = lower(p_sender_id) AND s.withdrawn_at IS NULL;
    IF FOUND THEN
        RETURN CASE WHEN v_owner = p_tenant_id THEN 'already' ELSE 'taken' END;
    END IF;
    BEGIN
        INSERT INTO platform_sms_sender_ids (id, tenant_id, sender_id, approved_by, approved_at)
        VALUES (gen_random_uuid(), p_tenant_id, p_sender_id, p_platform_user_id, p_now);
    EXCEPTION WHEN unique_violation THEN
        RETURN 'taken';
    END;
    RETURN 'approved';
END
$$;

-- Withdraw an id from a tenant. Returns whether an active approval was withdrawn. The tenant's settings may still
-- name it; the channel then sends from the shared sender (it re-checks the approval on every message).
CREATE FUNCTION sms_sender_id_withdraw(
    p_tenant_id uuid, p_sender_id text, p_platform_user_id uuid, p_now timestamptz)
RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'sender ids are withdrawn by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    UPDATE platform_sms_sender_ids
       SET withdrawn_at = p_now, withdrawn_by = p_platform_user_id
     WHERE tenant_id = p_tenant_id AND lower(sender_id) = lower(p_sender_id) AND withdrawn_at IS NULL;
    RETURN FOUND;
END
$$;

-- The bound tenant's own approved ids (the choices of its settings screen); reads app.tenant_id, so a tenant
-- transaction can only ever see its own.
CREATE FUNCTION sms_sender_ids_current()
RETURNS TABLE (sender_id text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT s.sender_id::text FROM platform_sms_sender_ids s
     WHERE s.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid AND s.withdrawn_at IS NULL
     ORDER BY s.approved_at, s.sender_id
$$;

-- The stored spelling of an id the bound tenant holds an active approval for, else null. The settings validation and
-- the channel both ask it: the channel on every message, so a withdrawal bites at once.
CREATE FUNCTION sms_sender_id_approved(p_sender_id text)
RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT s.sender_id::text FROM platform_sms_sender_ids s
     WHERE s.tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
       AND lower(s.sender_id) = lower(p_sender_id) AND s.withdrawn_at IS NULL
$$;

-- ---------------------------------------------------------------------------------------------
-- 2. The opt-out table learns what the staff screens need (spec 4.9, FR-NTF-14).
--    * member_id may be null: a number-only row (a STOP list imported, a number a person asked to stop) blocks that
--      number for any message, whoever it is about, matched by the keyed hash. A row needs a member or a hash.
--    * recipient_masked shows which number a row is about (+256*****1234); the number itself is never stored.
--    * source gains 'number' (one number added by an administrator) and 'import' (a pasted list).
--    * at most one active row per member and per number, so a double click or two staff at once do not stack rows.
--    The table was created empty by V50 and nothing wrote to it, so these statements scan nothing.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE sms_opt_outs
    ALTER COLUMN member_id DROP NOT NULL,
    ADD COLUMN recipient_masked varchar(20),
    DROP CONSTRAINT sms_opt_outs_source_check,
    ADD CONSTRAINT sms_opt_outs_source_check CHECK (source IN ('staff', 'member', 'number', 'import')),
    ADD CONSTRAINT sms_opt_outs_target_check CHECK (member_id IS NOT NULL OR recipient_hash IS NOT NULL);

CREATE UNIQUE INDEX sms_opt_outs_one_active_per_member
    ON sms_opt_outs (tenant_id, member_id) WHERE removed_at IS NULL AND member_id IS NOT NULL;
CREATE UNIQUE INDEX sms_opt_outs_one_active_per_number
    ON sms_opt_outs (tenant_id, recipient_hash) WHERE removed_at IS NULL AND member_id IS NULL;
-- The channel looks a number up by its hash on every message.
CREATE INDEX sms_opt_outs_by_number ON sms_opt_outs (tenant_id, recipient_hash)
    WHERE removed_at IS NULL AND recipient_hash IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 3. The tenant's own wording per event type (spec 4.10, FR-NTF-03). One row per tenant and event; the text is a
--    plain template with {placeholders} from the event's whitelist, validated at save and again at render. The
--    version is the If-Match of a change. A removal deletes the row (the audit log keeps what it said).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE sms_template_overrides (
    id         uuid          NOT NULL PRIMARY KEY,
    tenant_id  uuid          NOT NULL REFERENCES tenants (id),
    event_key  varchar(100)  NOT NULL,
    body       varchar(480)  NOT NULL CHECK (length(body) BETWEEN 1 AND 480),
    version    integer       NOT NULL DEFAULT 1,
    created_at timestamptz   NOT NULL DEFAULT now(),
    updated_at timestamptz   NOT NULL DEFAULT now(),
    updated_by uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, event_key)
);
SELECT bms_apply_tenant_rls('sms_template_overrides');
SELECT bms_grant_app('sms_template_overrides', 'SELECT, INSERT, UPDATE, DELETE');

-- ---------------------------------------------------------------------------------------------
-- 4. The enqueue, now also behind the tenant's own switch. Body as in V50 plus one check: an SMS row is not written
--    while the tenant's settings group sms is not switched on (module_groups.sms.enabled), exactly as it is not
--    written while the operator's switch is off, so nothing sits in the outbox for a tenant that did not choose SMS.
--    The privileges of V50 stay (CREATE OR REPLACE keeps the grants).
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION notification_outbox_enqueue(
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
        IF NOT EXISTS (SELECT 1 FROM tenant_settings s
                        WHERE s.tenant_id = v_tenant AND s.settings #>> '{module_groups,sms,enabled}' = 'true') THEN
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
-- 5. Switching a tenant on drops its backlog (ADR-036 decision 14, the tenant half). While the tenant switch is
--    off no row is written, and a row written before it was switched off is refused by the channel; this function
--    closes the remaining gap: on the move to on, every SMS row of the bound tenant still pending and queued up to p_now
--    is marked failed 'backlog_dropped' with its parameters cleared, so switching on never releases an old receipt or
--    reminder. Returns the number dropped. The tenant is the bound one, never a parameter.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION sms_tenant_switched_on(p_now timestamptz)
RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant  uuid := nullif(current_setting('app.tenant_id', true), '')::uuid;
    v_dropped integer;
BEGIN
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'a tenant must be bound' USING ERRCODE = 'check_violation';
    END IF;
    UPDATE notification_outbox
       SET status = 'failed', failed_at = p_now, last_error = 'backlog_dropped', params = '{}'::jsonb, updated_at = p_now
     WHERE channel = 'sms' AND tenant_id = v_tenant AND status = 'pending' AND created_at <= p_now;
    GET DIAGNOSTICS v_dropped = ROW_COUNT;
    RETURN v_dropped;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- 6. The legacy flat keys. Before this slice the window and the balance switch were plain keys of the tenant
--    settings (sms_window_start, sms_window_end, sms_include_balance), written through PATCH /settings. They are now
--    the settings group sms, written only by PUT /settings/sms. A tenant that had set any of them keeps its value:
--    copied once into the group, for a tenant that has no group yet. The flat keys stay where they are (expand
--    only: the containers of the previous release still read them until the switch). The sender name is not
--    copied: it was never approved, and a tenant picks an approved id now. The marked block is run again, on its
--    own, by the migration test against fabricated rows.
-- ---------------------------------------------------------------------------------------------

-- legacy-copy:begin
UPDATE tenant_settings ts
   SET settings = jsonb_set(
           CASE WHEN jsonb_typeof(ts.settings -> 'module_groups') = 'object'
                THEN ts.settings
                ELSE jsonb_set(ts.settings, '{module_groups}', '{}'::jsonb) END,
           '{module_groups,sms}',
           jsonb_strip_nulls(jsonb_build_object(
               'window_start', CASE WHEN w.touched AND w.ok THEN to_jsonb(w.s) END,
               'window_end', CASE WHEN w.touched AND w.ok THEN to_jsonb(w.e) END,
               'include_balance', ts.settings -> 'sms_include_balance')))
  FROM (
      -- The legacy window is clamped to the hard limits of the new screen (06:00 to 21:00, at least 60 minutes) while it
      -- is copied: a value outside them is pulled in, and what is left of a window that is not one is not copied at all,
      -- so the default (08:00 to 20:00) applies. A value that is not HH:MM is the default for its side.
      SELECT q.id, q.touched, q.s, q.e,
             (q.s < q.e AND (q.e::time - q.s::time) >= interval '60 minutes') AS ok
        FROM (
            SELECT t.id,
                   (t.settings -> 'sms_window_start' IS NOT NULL OR t.settings -> 'sms_window_end' IS NOT NULL) AS touched,
                   greatest(coalesce(CASE WHEN t.settings ->> 'sms_window_start' ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
                                          THEN t.settings ->> 'sms_window_start' END, '08:00') COLLATE "C",
                            '06:00' COLLATE "C") AS s,
                   least(coalesce(CASE WHEN t.settings ->> 'sms_window_end' ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
                                       THEN t.settings ->> 'sms_window_end' END, '20:00') COLLATE "C",
                         '21:00' COLLATE "C") AS e
              FROM tenant_settings t) q) w
 WHERE ts.id = w.id
   AND (w.touched OR ts.settings -> 'sms_include_balance' IS NOT NULL)
   AND ts.settings #> '{module_groups,sms}' IS NULL;
-- legacy-copy:end

-- ---------------------------------------------------------------------------------------------
-- Grants: definer functions only, executable by the application role and no one else (ADR-016).
-- ---------------------------------------------------------------------------------------------

REVOKE ALL ON FUNCTION sms_sender_ids_for_tenant(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION sms_sender_id_approve(uuid, text, uuid, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION sms_sender_id_withdraw(uuid, text, uuid, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION sms_sender_ids_current() FROM PUBLIC;
REVOKE ALL ON FUNCTION sms_sender_id_approved(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION sms_tenant_switched_on(timestamptz) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION sms_sender_ids_for_tenant(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION sms_sender_id_approve(uuid, text, uuid, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION sms_sender_id_withdraw(uuid, text, uuid, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION sms_sender_ids_current() TO bms_app;
GRANT EXECUTE ON FUNCTION sms_sender_id_approved(text) TO bms_app;
GRANT EXECUTE ON FUNCTION sms_tenant_switched_on(timestamptz) TO bms_app;
