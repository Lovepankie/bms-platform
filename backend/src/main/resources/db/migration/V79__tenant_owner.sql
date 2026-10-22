-- The Owner of a business (ADR-062, FR-IAM-18).
--
-- Numbered V79: V78 is the last on main. Flyway runs with outOfOrder off, so a number is taken above the highest on main at
-- merge time.
--
-- The owner's rule: the owner and the administrators do everything and see every shop, aggregation, KPI and analytics, so
-- nothing an administrator may see or do day to day changes. The Owner exists only to protect the business from an
-- administrator who leaves on bad terms: exactly one Owner per business, who alone adds, removes, deactivates, demotes or
-- promotes an administrator, whom nobody inside the business can remove, deactivate or demote, and who hands the role on
-- by a transfer to another active administrator (or a platform operator does, with a reason).
--
-- The Owner is a flag on the staff user (users.is_owner), not a role: the Owner holds the tenant_admin role like every
-- administrator, so every permission check, every branch scope and the permission matrix are unchanged, and only the
-- user administration rules read the flag. users already has forced row-level security (V1), so the flag is the tenant's
-- own and no business can read or change another's.
--
-- What the database holds, whatever the application does:
--   1. at most one Owner per business (the partial unique index users_one_owner);
--   2. the Owner is a staff user who is not deactivated (the check users_owner_is_staff);
--   3. the Owner holds the tenant_admin role when made the Owner (the trigger users_owner_is_admin), and that role cannot be
--      revoked while they are the Owner (the trigger user_role_assignments_owner_keeps_admin).
--
-- Backfill: every business gets exactly one Owner, the active administrator whose email is the email of the sign-up
-- application the business was activated from (the recorded owner email, onboarding_applications.contact_email, ADR-024),
-- and otherwise the earliest-created active administrator. A business with no active administrator is left with no Owner,
-- with a WARNING in the migration log naming its slug; the application tolerates that (the administrators keep the rules
-- of before this change) until a platform operator assigns an Owner. Each assignment writes a system audit row in the
-- tenant's log and a row in the platform log.
--
-- users.contact_changed_at: when the user's email or phone last changed; users.second_factor_changed_at: when two-step sign-in
-- was last set up or reset. Both set by the application. The Owner's own transfer is refused for 24 hours after the Owner's
-- contact, password or second factor changed (ADR-062 decision 4), so a stolen session, or a password with a second factor reset
-- or enrolled by someone else, cannot hand the business over at once.
--
-- Locking: ADD COLUMN takes ACCESS EXCLUSIVE on users and holds it until the migration commits; with a constant default (or
-- none) it rewrites nothing, and the check, its validation and the index then run on the small table under that same lock.
-- So sign-ins wait for the few milliseconds the migration takes; the five second lock_timeout makes a busy table fail the
-- deploy instead of queueing sign-ins behind it.

SET LOCAL lock_timeout = '5s';

ALTER TABLE users ADD COLUMN is_owner boolean NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN contact_changed_at timestamptz;
ALTER TABLE users ADD COLUMN second_factor_changed_at timestamptz;
ALTER TABLE users ADD CONSTRAINT users_owner_is_staff
    CHECK (NOT is_owner OR (kind = 'staff' AND status <> 'deactivated'));
CREATE UNIQUE INDEX users_one_owner ON users (tenant_id) WHERE is_owner;

-- The Owner must hold the administrator role at the moment they are made the Owner. A user is inserted before any role can
-- be given (the foreign key), so a user is never inserted as the Owner: they are made the Owner by an update.
CREATE FUNCTION bms_owner_must_be_admin() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        RAISE EXCEPTION 'users_owner_is_admin: a user is made the Owner after their administrator role is given'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT OLD.is_owner AND NOT EXISTS (
            SELECT 1 FROM user_role_assignments a
             WHERE a.tenant_id = NEW.tenant_id AND a.user_id = NEW.id AND a.role_key = 'tenant_admin'
               AND a.revoked_at IS NULL) THEN
        RAISE EXCEPTION 'users_owner_is_admin: the Owner must be an administrator of the business'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION bms_owner_must_be_admin() FROM PUBLIC;

CREATE TRIGGER users_owner_is_admin BEFORE INSERT OR UPDATE OF is_owner ON users
    FOR EACH ROW WHEN (NEW.is_owner) EXECUTE FUNCTION bms_owner_must_be_admin();

-- The administrator role of the Owner is never revoked, moved or deleted: the Owner hands ownership on first. An update
-- that leaves the row an active administrator row of the same user (a version bump) passes.
CREATE FUNCTION bms_owner_keeps_admin() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.role_key = 'tenant_admin' AND NEW.revoked_at IS NULL AND NEW.user_id = OLD.user_id
       AND NEW.tenant_id = OLD.tenant_id THEN
        RETURN NEW;
    END IF;
    IF EXISTS (SELECT 1 FROM users u WHERE u.tenant_id = OLD.tenant_id AND u.id = OLD.user_id AND u.is_owner) THEN
        RAISE EXCEPTION 'user_role_assignments_owner_keeps_admin: the Owner stays an administrator until ownership is handed on'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;
REVOKE ALL ON FUNCTION bms_owner_keeps_admin() FROM PUBLIC;

CREATE TRIGGER user_role_assignments_owner_keeps_admin BEFORE UPDATE OR DELETE ON user_role_assignments
    FOR EACH ROW
    WHEN (OLD.role_key = 'tenant_admin' AND OLD.revoked_at IS NULL)
    EXECUTE FUNCTION bms_owner_keeps_admin();

-- ---------------------------------------------------------------------------------------------
-- Backfill: one Owner per business that has an active administrator.
-- ---------------------------------------------------------------------------------------------

WITH admins AS (
        SELECT u.tenant_id, u.id, lower(u.email) AS email, u.created_at
          FROM users u
         WHERE u.kind = 'staff' AND u.status = 'active'
           AND EXISTS (SELECT 1 FROM user_role_assignments a
                        WHERE a.tenant_id = u.tenant_id AND a.user_id = u.id AND a.role_key = 'tenant_admin'
                          AND a.revoked_at IS NULL)),
     recorded AS (
        SELECT DISTINCT ON (o.activated_tenant) o.activated_tenant AS tenant_id, lower(o.contact_email) AS email
          FROM onboarding_applications o
         WHERE o.status = 'activated' AND o.activated_tenant IS NOT NULL
         ORDER BY o.activated_tenant, o.activated_at DESC NULLS LAST, o.id),
     chosen AS (
        SELECT DISTINCT ON (a.tenant_id) a.tenant_id, a.id,
               coalesce(a.email = r.email, false) AS by_recorded_email
          FROM admins a LEFT JOIN recorded r ON r.tenant_id = a.tenant_id
         WHERE NOT EXISTS (SELECT 1 FROM users x WHERE x.tenant_id = a.tenant_id AND x.is_owner)
         ORDER BY a.tenant_id, coalesce(a.email = r.email, false) DESC, a.created_at, a.id),
     marked AS (
        UPDATE users u SET is_owner = true
          FROM chosen c
         WHERE u.tenant_id = c.tenant_id AND u.id = c.id
        RETURNING u.tenant_id, u.id, c.by_recorded_email),
     tenant_rows AS (
        INSERT INTO audit_log (id, tenant_id, actor_kind, action, entity_type, entity_id, data)
        SELECT gen_random_uuid(), m.tenant_id, 'system', 'core.user.owner_assigned', 'core.user', m.id,
               jsonb_build_object(
                   'before', jsonb_build_object('owner', NULL),
                   'after', jsonb_build_object(
                       'owner', m.id,
                       'reason', CASE WHEN m.by_recorded_email
                                      THEN 'V79: the administrator with the email the business signed up with (ADR-062)'
                                      ELSE 'V79: the earliest active administrator (ADR-062)' END))
          FROM marked m
        RETURNING 1)
INSERT INTO platform_audit_log (id, action, tenant_id, data)
SELECT gen_random_uuid(), 'platform.tenant.owner_assigned', m.tenant_id,
       jsonb_build_object('before', NULL, 'after', m.id, 'by', 'V79 backfill',
                          'reason', CASE WHEN m.by_recorded_email
                                         THEN 'V79: the administrator with the email the business signed up with'
                                         ELSE 'V79: the earliest active administrator' END)
  FROM marked m;

DO $$
DECLARE
    v_slug text;
BEGIN
    FOR v_slug IN
        SELECT t.slug FROM tenants t
         WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.tenant_id = t.id AND u.is_owner)
         ORDER BY t.slug
    LOOP
        RAISE WARNING 'V79: business % has no active administrator, so it has no Owner; a platform operator assigns one', v_slug;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------------------------------
-- The operator's reads: the owner of a business is now its Owner (null while it has none), and the administrators list
-- says which one is the Owner.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION platform_tenant_overview(p_tenant_id uuid DEFAULT NULL) RETURNS TABLE (
    id uuid, slug text, name text, status text, plan_code text, plan_name text, currency text, timezone text,
    subscription_status text, next_status_change_on date, trial_ends_on date, paid_from date, paid_until date,
    suspended_from text, is_pilot boolean,
    modules text[], created_at timestamptz, owner_name text, owner_email text, last_sign_in_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.id, t.slug::text, t.name, t.status, p.code, p.name, t.currency::text, t.timezone,
           s.status, s.next_status_change_on, s.trial_ends_on, s.current_period_start, s.current_period_end,
           s.suspended_from, coalesce(s.is_pilot, false),
           coalesce((SELECT array_agg(m.module_key ORDER BY m.module_key) FROM tenant_modules m WHERE m.tenant_id = t.id), '{}'),
           t.created_at,
           o.full_name, o.email::text,
           (SELECT max(u.last_login_at) FROM users u WHERE u.tenant_id = t.id AND u.kind = 'staff')
      FROM tenants t
      JOIN plans p ON p.id = t.plan_id
      LEFT JOIN subscriptions s ON s.tenant_id = t.id
      LEFT JOIN users o ON o.tenant_id = t.id AND o.is_owner
     WHERE p_tenant_id IS NULL OR t.id = p_tenant_id
     ORDER BY t.name, t.slug
$$;

DROP FUNCTION platform_tenant_admins(uuid);
CREATE FUNCTION platform_tenant_admins(p_tenant_id uuid) RETURNS TABLE (
    id uuid, full_name text, email text, phone text, status text, mfa_enabled boolean, last_login_at timestamptz,
    created_at timestamptz, is_owner boolean)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT u.id, u.full_name, u.email::text, u.phone_e164::text, u.status, u.mfa_enabled, u.last_login_at, u.created_at,
           u.is_owner
      FROM users u
     WHERE u.tenant_id = p_tenant_id AND u.kind = 'staff'
       AND EXISTS (SELECT 1 FROM user_role_assignments a
                    WHERE a.tenant_id = u.tenant_id AND a.user_id = u.id AND a.role_key = 'tenant_admin' AND a.revoked_at IS NULL)
     ORDER BY u.is_owner DESC, u.created_at, u.id
$$;
REVOKE ALL ON FUNCTION platform_tenant_overview(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_tenant_admins(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_tenant_overview(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_tenant_admins(uuid) TO bms_app;
