-- After every migrate: say exactly what is left over from V135 and how to fix it (issue #857).
--
-- A Flyway callback, not a migration: it has no version, is never recorded in flyway_schema_history, and runs at the end of
-- every migrate, so the deploy's migrate log repeats the WARNING on each deploy until the rows are fixed. It only reads and
-- raises a WARNING; it never changes data or the schema, and it never fails a deploy. V135 itself is applied and is not
-- changed.
--
-- V135 removed whitespace from stored staff emails but left a row as it was when its cleaned email is another staff user's
-- in the same business, or when nothing but whitespace was left. Since issue #823 the application cleans a typed email the
-- same way (kernel.EmailAddresses), so a person whose stored email still holds a space is looked up by the cleaned form,
-- which finds the other person's account: the password is checked against it and can lock it. Each reason is counted on
-- its own, with the business ids and never an email (personal data would reach the deploy log). Operator accounts
-- (platform_users, created by deploy/sql/create-platform-user.sql, which cleans the email the same way since issue #857) are
-- checked too. The manual fix is docs/runbooks/staff-email-left-over.md.
--
-- The character set is EmailAddresses' since issue #857: the ASCII whitespace, the Unicode space separators, U+0085, U+180E,
-- U+200B, U+2060 and U+FEFF. The zero width non-joiner and joiner (U+200C, U+200D) are no longer removed, so they are not
-- counted here.
--
-- bms_owner has BYPASSRLS (deploy/postgres/initdb/01-roles.sh), so the reads see every business. An owner without it (a test
-- replay) would read nothing through the forced policy on users, so the check says it was skipped instead. Before V2 the
-- tables do not exist yet and there is nothing to check.

DO $$
DECLARE
    spaces constant text := '[\s\x00a0\x0085\x1680\x180e\x2000-\x200b\x2028\x2029\x202f\x205f\x2060\x3000\xfeff]';
    blank integer;
    blank_in text;
    colliding integer;
    colliding_in text;
    other integer;
    other_in text;
    operators integer;
BEGIN
    IF to_regclass('users') IS NULL OR to_regclass('platform_users') IS NULL THEN
        RETURN;
    END IF;
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        RAISE NOTICE 'staff email check skipped: % cannot read every business', current_user;
        RETURN;
    END IF;

    WITH staff AS (
            SELECT u.tenant_id, u.id, u.email ~ spaces AS spaced,
                   lower(regexp_replace(u.email, spaces, '', 'g')) AS cleaned
              FROM users u
             WHERE u.kind = 'staff' AND u.email IS NOT NULL),
         left_over AS (
            SELECT s.tenant_id,
                   CASE WHEN s.cleaned = '' THEN 'blank'
                        WHEN EXISTS (SELECT 1 FROM staff x
                                      WHERE x.tenant_id = s.tenant_id AND x.id <> s.id AND x.cleaned = s.cleaned)
                            THEN 'colliding'
                        ELSE 'other' END AS reason
              FROM staff s
             WHERE s.spaced)
    SELECT count(*) FILTER (WHERE reason = 'blank'),
           string_agg(DISTINCT tenant_id::text, ', ') FILTER (WHERE reason = 'blank'),
           count(*) FILTER (WHERE reason = 'colliding'),
           string_agg(DISTINCT tenant_id::text, ', ') FILTER (WHERE reason = 'colliding'),
           count(*) FILTER (WHERE reason = 'other'),
           string_agg(DISTINCT tenant_id::text, ', ') FILTER (WHERE reason = 'other')
      INTO blank, blank_in, colliding, colliding_in, other, other_in
      FROM left_over;

    SELECT count(*) INTO operators FROM platform_users WHERE email ~ spaces;

    IF colliding > 0 THEN
        RAISE WARNING 'staff email: % staff email(s) hold whitespace and, without it, are the email of another staff user of the same business, in the business(es) %. Signing in as that person checks the password against the other person''s account and can lock it. Fix: an administrator of that business edits the person''s card on Users and puts in their own email, or deactivates the row if it is the same person twice; never delete a user. See docs/runbooks/staff-email-left-over.md.', colliding, colliding_in;
    END IF;
    IF blank > 0 THEN
        RAISE WARNING 'staff email: % staff email(s) hold nothing but whitespace, in the business(es) %. That person cannot sign in by email. Fix: an administrator of that business edits the person''s card on Users and puts in their own email, or deactivates the row if nobody uses it; never delete a user. See docs/runbooks/staff-email-left-over.md.', blank, blank_in;
    END IF;
    IF other > 0 THEN
        RAISE WARNING 'staff email: % staff email(s) hold whitespace that can be removed without a clash, in the business(es) %. Fix: an administrator of that business edits the person''s card on Users and saves the email again, which removes it. See docs/runbooks/staff-email-left-over.md.', other, other_in;
    END IF;
    IF operators > 0 THEN
        RAISE WARNING 'operator email: % platform operator email(s) hold whitespace. Fix: as bms_owner, put in the cleaned email and write the audit row, as docs/runbooks/staff-email-left-over.md says.', operators;
    END IF;
END
$$;
