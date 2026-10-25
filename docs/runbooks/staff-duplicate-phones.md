# Runbook: a deploy stopped at V98 because two staff users share a phone

**Applies to:** staging and production · **Decisions:** ADR-085 · **Issue:** #323

## What you see

The deploy exits 1 with `failed-migration` in `state/history.log`, and the migrate log says:

```
ERROR: V98: 2 phone number(s) are each held by more than one invited or active staff user, in the business(es)
<tenant id>, <tenant id>. Nothing was changed.
HINT: Give each of those staff users their own phone, or deactivate the extra user, then deploy again. Never delete a user.
```

Nothing was switched: the previous release keeps serving, and V98 made no change (it runs in one transaction). The message names
the businesses and counts the shared phones; it never prints a phone number.

## Find the users

On the host, as `bms_owner` (it reads every business), list the users who share a phone. The output is personal data: read it on
the host, never paste it into a ticket, a chat or a pull request.

```sql
SELECT t.slug, u.id, u.full_name, u.status, u.last_login_at, u.created_at
  FROM users u
  JOIN tenants t ON t.id = u.tenant_id
 WHERE u.kind = 'staff' AND u.status IN ('invited', 'active')
   AND (u.tenant_id, u.phone_e164) IN (SELECT tenant_id, phone_e164
                                          FROM users
                                         WHERE kind = 'staff' AND phone_e164 IS NOT NULL
                                           AND status IN ('invited', 'active')
                                         GROUP BY tenant_id, phone_e164
                                        HAVING count(*) > 1)
 ORDER BY t.slug, u.phone_e164, u.created_at;
```

## Fix it (never delete a user)

For each pair, ask an administrator of that business which person the phone belongs to. Then, in the app, signed in as that
administrator, on Users:

- if the other person has a phone of their own, edit their card and put their phone in; or
- if the other row is a mistake (usually an invitation sent twice that nobody accepted), deactivate it.

Both changes are audited. Do not edit the rows with SQL and never delete a user: sessions, audit rows and role assignments point
at them. A pending invitation that is deactivated stops working.

## Deploy again

Run the query again: it must return no rows. Then retry the deploy (`docs/runbooks/deploy.md`). V98 now builds the index and,
from then on, a second invitation with the same phone is refused with 409 `duplicate_phone` ("A staff user has this phone."),
which the Users screen shows as "Someone on your staff already uses this phone number."
