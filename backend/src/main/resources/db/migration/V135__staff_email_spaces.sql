-- V135: no whitespace inside a stored staff email (issue #823).
--
-- From this release the application removes every whitespace character from a typed email, at the ends and inside, and
-- lower cases it, before it looks a staff user up (sign-in, forgot password) or saves one (invitation, edit), with
-- kernel.EmailAddresses. Before it only trimmed the ends, and the @Email check lets a non-breaking space (U+00A0) or another
-- Unicode space through inside the local part, so an email saved before this release may hold one. Such a user could not
-- sign in any more by the cleaned form, so this cleans those rows with the same character set as EmailAddresses: the ASCII
-- whitespace, the Unicode space separators, U+0085, U+180E, U+200B to U+200D, U+2060 and U+FEFF (written below as the
-- regular expression escapes \xHHHH).
--
-- Data safe: only staff rows whose email holds such a character change, and only when no other staff user of the same
-- business has, or would get, the same cleaned email (users_staff_email is unique on (tenant_id, lower(email))). A row that
-- would collide is left exactly as it was and counted in a WARNING naming the business ids, never the email (personal data
-- would reach the deploy log); a person fixes it by hand. Each change writes a system audit row in the business's own log
-- with the email before and after, as an edit by an administrator does. Running it again changes nothing.
--
-- No row lock beyond the UPDATE's own. users and audit_log have FORCE ROW LEVEL SECURITY and no app.tenant_id is bound
-- here. bms_owner has BYPASSRLS (deploy/postgres/initdb/01-roles.sh), so the statements see every business; row_security =
-- off makes PostgreSQL raise rather than silently see no rows should a policy still apply. As in V98, an owner without
-- BYPASSRLS drops FORCE on those two tables for this transaction only, and only where a table has it, and puts back exactly
-- what it found before the commit (bms.v135_unforced records it); with BYPASSRLS nothing is altered. That ALTER TABLE takes
-- an ACCESS EXCLUSIVE lock, so lock_timeout is five seconds as in V96 and V98: the migration fails and is retried rather than
-- every sign-in queueing behind it.

SET LOCAL lock_timeout = '5s';
SET LOCAL row_security = off;
DO $$
DECLARE
    t text;
    unforced text := '';
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        FOREACH t IN ARRAY ARRAY['users', 'audit_log'] LOOP
            IF (SELECT relforcerowsecurity FROM pg_class WHERE oid = t::regclass) THEN
                EXECUTE format('ALTER TABLE %I NO FORCE ROW LEVEL SECURITY', t);
                unforced := unforced || t || ',';
            END IF;
        END LOOP;
    END IF;
    PERFORM set_config('bms.v135_unforced', unforced, true);
END
$$;

WITH staff AS (
        SELECT u.tenant_id, u.id, u.email,
               u.email ~ '[\s\x00a0\x0085\x1680\x180e\x2000-\x200d\x2028\x2029\x202f\x205f\x2060\x3000\xfeff]'
                   AS spaced,
               lower(regexp_replace(u.email,
                   '[\s\x00a0\x0085\x1680\x180e\x2000-\x200d\x2028\x2029\x202f\x205f\x2060\x3000\xfeff]', '', 'g'))
                   AS cleaned
          FROM users u
         WHERE u.kind = 'staff' AND u.email IS NOT NULL),
     spaced AS (
        SELECT s.tenant_id, s.id, s.email AS before, s.cleaned AS after FROM staff s WHERE s.spaced),
     safe AS (
        SELECT s.* FROM spaced s
         WHERE s.after <> ''
           AND NOT EXISTS (SELECT 1 FROM staff x
                            WHERE x.tenant_id = s.tenant_id AND x.id <> s.id AND x.cleaned = s.after)),
     cleaned AS (
        UPDATE users u SET email = s.after, version = u.version + 1, updated_at = now()
          FROM safe s
         WHERE u.tenant_id = s.tenant_id AND u.id = s.id
        RETURNING u.tenant_id, u.id, s.before, s.after)
INSERT INTO audit_log (id, tenant_id, actor_kind, action, entity_type, entity_id, data)
SELECT gen_random_uuid(), c.tenant_id, 'system', 'core.user.email_cleaned', 'core.user', c.id,
       jsonb_build_object(
           'before', jsonb_build_object('email', c.before),
           'after', jsonb_build_object('email', c.after),
           'reason', 'V135: whitespace removed from the email (issue #823)')
  FROM cleaned c;

DO $$
DECLARE
    left_over integer;
    businesses text;
BEGIN
    SELECT count(*), string_agg(DISTINCT u.tenant_id::text, ', ')
      INTO left_over, businesses
      FROM users u
     WHERE u.kind = 'staff' AND u.email IS NOT NULL
       AND u.email ~ '[\s\x00a0\x0085\x1680\x180e\x2000-\x200d\x2028\x2029\x202f\x205f\x2060\x3000\xfeff]';
    IF left_over > 0 THEN
        RAISE WARNING 'V135: % staff email(s) hold whitespace and were left as they were, because the cleaned email is another staff user''s in the same business, in the business(es) %', left_over, businesses;
    END IF;
END
$$;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY string_to_array(rtrim(current_setting('bms.v135_unforced', true), ','), ',') LOOP
        IF t <> '' THEN
            EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        END IF;
    END LOOP;
END
$$;
SET LOCAL row_security = on;
