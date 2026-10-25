-- V98: one invited or active staff user per phone number in a business (issue #323, ADR-085).
--
-- V1 made the staff email unique per business (users_staff_email) and the member phone unique (users_member_phone), but a
-- staff phone was only checked by the application (UserService.checkUnique) with no lock, so two invitations sent at the same
-- moment, by two administrators or from two tabs, could both pass the check and create two staff users with one phone. Neither
-- could then sign in by phone. The partial unique index below closes that gap in the database: the second insert waits for
-- the first to commit and is then refused, and the application answers 409 duplicate_phone. Phones are stored normalised to
-- E.164 by the application (kernel.PhoneNumbers), so the index is on phone_e164 as stored. A deactivated user keeps their row
-- and phone and is left out of the index, as the issue asks; the application still refuses a phone a deactivated user holds.
--
-- Existing duplicates are never fixed here: no user is deleted, deactivated or changed by this migration. When a business
-- already has two invited or active staff users with one phone, the migration stops with a message naming the business ids and
-- how many phones are shared (never the phone itself, which is personal data and would reach the deploy log), and nothing is
-- changed. docs/runbooks/staff-duplicate-phones.md is the manual fix; the deploy is retried once it is done.
--
-- Expand only. The SHARE lock on users is taken before the count and held to the commit, so the old release, still serving
-- during the deploy, cannot commit a duplicate between the count and the index build (whose own error would print the phone).
-- It blocks every write to users for that moment (staging and the pilot: tens of staff rows), so a sign-in, which writes
-- last_login_at, waits; reads go on. Under an owner without BYPASSRLS the ALTER TABLE below raises it to ACCESS EXCLUSIVE,
-- which blocks reads of users too. The first statement sets lock_timeout to five seconds as V96 does, so a write waits at most
-- that long and the migration fails and is retried rather than every sign-in queueing behind it.

SET LOCAL lock_timeout = '5s';
LOCK TABLE users IN SHARE MODE;

-- users has FORCE ROW LEVEL SECURITY and no app.tenant_id is bound here. bms_owner has BYPASSRLS
-- (deploy/postgres/initdb/01-roles.sh), so the check reads every business; row_security = off makes PostgreSQL raise rather
-- than silently see no rows should a policy still apply. As in V92, an owner without BYPASSRLS drops FORCE on users for this
-- transaction only, and only when users has it, and puts back exactly what it found before the commit (bms.v98_unforced
-- records it); with BYPASSRLS nothing is altered.
SET LOCAL row_security = off;
DO $$
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user)
       AND (SELECT relforcerowsecurity FROM pg_class WHERE oid = 'users'::regclass) THEN
        ALTER TABLE users NO FORCE ROW LEVEL SECURITY;
        PERFORM set_config('bms.v98_unforced', 'on', true);
    END IF;
END
$$;

DO $$
DECLARE
    shared_phones integer;
    businesses text;
BEGIN
    SELECT count(*), string_agg(DISTINCT d.tenant_id::text, ', ')
      INTO shared_phones, businesses
      FROM (SELECT tenant_id
              FROM users
             WHERE kind = 'staff' AND phone_e164 IS NOT NULL AND status IN ('invited', 'active')
             GROUP BY tenant_id, phone_e164
            HAVING count(*) > 1) d;
    IF shared_phones > 0 THEN
        RAISE EXCEPTION 'V98: % phone number(s) are each held by more than one invited or active staff user, in the business(es) %. Nothing was changed.', shared_phones, businesses
            USING ERRCODE = 'unique_violation',
                  HINT = 'Give each of those staff users their own phone, or deactivate the extra user, then deploy again. Never delete a user. See docs/runbooks/staff-duplicate-phones.md.';
    END IF;
END
$$;

DO $$
BEGIN
    IF current_setting('bms.v98_unforced', true) = 'on' THEN
        ALTER TABLE users FORCE ROW LEVEL SECURITY;
    END IF;
END
$$;
SET LOCAL row_security = on;

CREATE UNIQUE INDEX users_staff_phone ON users (tenant_id, phone_e164)
    WHERE kind = 'staff' AND phone_e164 IS NOT NULL AND status IN ('invited', 'active');

COMMENT ON INDEX users_staff_phone IS
    'One invited or active staff user per phone in a business (issue #323, ADR-085). The application maps a refusal to 409 duplicate_phone.';
