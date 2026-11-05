# Runbook: a staff email still holds a space after V135

**Applies to:** staging and production · **Decisions:** FR-IAM-04, chapter 8 section 8.2 · **Issues:** #823, #857

## What you see

The deploy succeeds, and the migrate log holds one or more of these WARNINGs (they come from
`afterMigrate__staff_email_left_over.sql`, which runs after every migrate and repeats them on each deploy until the rows are
fixed):

```
WARNING: staff email: 1 staff email(s) hold whitespace and, without it, are the email of another staff user of the same
business, in the business(es) <tenant id>. ...
WARNING: staff email: 1 staff email(s) hold nothing but whitespace, in the business(es) <tenant id>. ...
WARNING: staff email: 1 staff email(s) hold whitespace that can be removed without a clash, in the business(es) <tenant id>. ...
WARNING: operator email: 1 platform operator email(s) hold whitespace. ...
```

The V135 migration (issue #823) left such a row exactly as it was; the check counts each reason on its own and names the
businesses, never an email.

## Why it matters

Since #823 the server removes every whitespace character from a typed email before it looks a staff user up. A person whose
stored email is `test.ow ner@example.test` while a colleague's is `test.owner@example.test` is therefore looked up as the
colleague: their password is checked against the colleague's account, never matches, and five tries lock the colleague out
for 15 minutes. A stored email of nothing but whitespace can never be typed at all. Fix every row the same day.

## A joiner V135 removed

V135 also removed the zero width non-joiner and joiner (U+200C, U+200D) from stored staff emails. Since #857 the server keeps
them, so a person whose real address holds one, and whose stored email V135 changed, now types an address that no longer
matches the stored one and cannot sign in by email. No WARNING finds this, because the stored email holds no whitespace. The
`core.user.email_cleaned` audit rows V135 wrote list every email it changed, with the email before and after:

```sql
SELECT t.slug, a.entity_id AS user_id
  FROM audit_log a
  JOIN tenants t ON t.id = a.tenant_id
 WHERE a.action = 'core.user.email_cleaned'
   AND a.data -> 'before' ->> 'email' ~ '[\x200c\x200d]';
```

For each row, an administrator of that business puts the person's real address back on their card on Users (the server
keeps the joiner now). The same rules apply: read the output on the host only.

## Find the rows

On the host, as `bms_owner` (it reads every business). The output is personal data: read it on the host, never paste it into
a ticket, a chat or a pull request. The pattern is the set the server removes (`kernel.EmailAddresses`).

```sql
WITH staff AS (
        SELECT u.tenant_id, u.id, u.full_name, u.status, u.last_login_at, u.created_at, u.email,
               u.email ~ '[\s\x00a0\x0085\x1680\x180e\x2000-\x200b\x2028\x2029\x202f\x205f\x2060\x3000\xfeff]' AS spaced,
               lower(regexp_replace(u.email,
                   '[\s\x00a0\x0085\x1680\x180e\x2000-\x200b\x2028\x2029\x202f\x205f\x2060\x3000\xfeff]', '', 'g'))
                   AS cleaned
          FROM users u
         WHERE u.kind = 'staff' AND u.email IS NOT NULL)
SELECT t.slug,
       CASE WHEN s.cleaned = '' THEN 'whitespace only'
            WHEN twin.id IS NOT NULL THEN 'clashes with another user'
            ELSE 'can be saved again' END AS reason,
       s.id, s.full_name, s.status, s.last_login_at, s.created_at,
       twin.id AS other_user_id, twin.full_name AS other_user_name, twin.status AS other_user_status
  FROM staff s
  JOIN tenants t ON t.id = s.tenant_id
  LEFT JOIN staff twin ON twin.tenant_id = s.tenant_id AND twin.id <> s.id AND twin.cleaned = s.cleaned AND s.cleaned <> ''
 WHERE s.spaced
 ORDER BY t.slug, reason, s.created_at;
```

Whitespace only and clashes are two different reasons with two different fixes; count them apart:

```sql
SELECT count(*) FILTER (WHERE cleaned = '') AS whitespace_only,
       count(*) FILTER (WHERE cleaned <> '' AND EXISTS (SELECT 1 FROM users x
                         WHERE x.kind = 'staff' AND x.tenant_id = s.tenant_id AND x.id <> s.id AND x.email IS NOT NULL
                           AND lower(regexp_replace(x.email,
                               '[\s\x00a0\x0085\x1680\x180e\x2000-\x200b\x2028\x2029\x202f\x205f\x2060\x3000\xfeff]',
                               '', 'g')) = s.cleaned)) AS clashes
  FROM (SELECT u.tenant_id, u.id,
               lower(regexp_replace(u.email,
                   '[\s\x00a0\x0085\x1680\x180e\x2000-\x200b\x2028\x2029\x202f\x205f\x2060\x3000\xfeff]', '', 'g'))
                   AS cleaned
          FROM users u
         WHERE u.kind = 'staff' AND u.email IS NOT NULL
           AND u.email ~ '[\s\x00a0\x0085\x1680\x180e\x2000-\x200b\x2028\x2029\x202f\x205f\x2060\x3000\xfeff]') s;
```

## Fix a staff row (never delete a user)

For each row, ask an administrator of that business who the person is. Then, in the app, signed in as that administrator, on
Users, open the person's card (the row the query lists, not the other user):

- **Clashes with another user:** if it is a different person, put in their own email and save. If it is the same person
  twice (usually an invitation sent again with a typing slip), deactivate this row; the other one stays.
- **Whitespace only:** put in the person's real email and save, or deactivate the row if nobody uses it.
- **Can be saved again:** save the card once; the server removes the whitespace as it saves.

If the other user's account was locked by the wrong tries, it opens by itself after 15 minutes; sooner, the operator lifts the
lock with `POST /platform/tenants/{tenant_id}/users/{user_id}/unlock` and a reason (chapter 7), which is audited in both logs. Every change is audited. Do not edit staff rows with SQL and never delete a user: sessions, audit rows and
role assignments point at them.

## Fix an operator row

Operators have no edit screen. Since #857 `deploy/sql/create-platform-user.sql` removes whitespace from the email it is given,
so only an operator created before that can hold one. As `bms_owner`, list them:

```sql
SELECT id, full_name, created_at
  FROM platform_users
 WHERE email ~ '[\s\x00a0\x0085\x1680\x180e\x2000-\x200b\x2028\x2029\x202f\x205f\x2060\x3000\xfeff]';
```

For each one, confirm the address with the operator, check that no other operator holds the cleaned email (the unique index
`platform_users_email` refuses it otherwise; then deactivate the extra account with `is_active = false` instead), and put it
in, with its audit row, in one transaction:

```sql
BEGIN;
UPDATE platform_users
   SET email = lower(regexp_replace(email,
           '[\s\x00a0\x0085\x1680\x180e\x2000-\x200b\x2028\x2029\x202f\x205f\x2060\x3000\xfeff]', '', 'g')),
       updated_at = now()
 WHERE id = '<operator id>';
INSERT INTO platform_audit_log (id, action, data)
VALUES (gen_random_uuid(), 'platform.user.email_cleaned',
        jsonb_build_object('platform_user_id', '<operator id>', 'reason', 'whitespace removed (issue #857)'));
COMMIT;
```

## Check

Run the queries again: they must return no rows. The next deploy's migrate log then has no `staff email` or `operator email`
WARNING.
