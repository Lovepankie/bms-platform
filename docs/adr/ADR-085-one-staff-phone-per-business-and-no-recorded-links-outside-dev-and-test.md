# ADR-085: One staff phone per business in the database, and no recorded links outside dev and test

## Status

Proposed (issues #323 and #324, found by the review of PR #321 on 19 Oct 2026), accepted on merge. Amends ADR-014 (staff
authentication), the invitations bullet: "until a provider is chosen the port has only a fake adapter that records messages".
Migration `V98` (one partial unique index on `users`). The sign-in flow itself is unchanged.

## Context

Two gaps in the staff invitation path were found before go-live.

1. **Staff phones were unique only in the application.** V1 made the staff email unique per business (`users_staff_email`) and
   the member phone unique (`users_member_phone`). A staff phone was only checked by `UserService.checkUnique`, a plain count
   with no lock. Two administrators, or one in two tabs, inviting the same phone at the same moment both passed the check and
   made two staff users with one phone, and neither could then sign in by phone.
2. **The recording adapter ran in production.** ADR-014 put a recording fake behind the `Notifier` port so staging never waited
   for a provider. That adapter keeps the last 500 messages in server memory, and an invitation message carries the raw one-time
   link with its token. Chapter 7 section 7.11.4 says the link is never kept; that held for the screen only. A heap dump, a
   debugger or a future endpoint that lists `sent()` would expose live links.

Options considered for the phone: a row lock on something per business before the check (serialises every invitation, and the
check still misses a row written by another path); an advisory lock on the phone (works, but only for callers that remember to
take it); a unique index (the database refuses whoever comes second, whatever the path). For the adapter: redact the link in the
recording adapter everywhere (the tests that follow a link through the port lose it); keep it but cap it at a few messages (still
keeps live links); limit it to the dev and test profiles and give every other profile an adapter that keeps nothing.

## Decision

1. **A partial unique index**, `users_staff_phone` on `users (tenant_id, phone_e164)` where `kind = 'staff'`, the phone is set
   and the status is `invited` or `active`. Phones are stored normalised to E.164 by the application, so two spellings of one
   number meet in the index. A deactivated user keeps their row and phone and is outside the index; the application check is
   unchanged and still refuses a phone a deactivated user holds.
2. **The same 409.** An insert or update the index refuses is answered as the check answers it: 409 `duplicate_phone`, "A staff
   user has this phone." (and `duplicate_email` when it is the email index, which had the same race).
3. **Existing duplicates stop the migration, and nothing is changed.** V98 first counts phones held by more than one invited or
   active staff user in a business. If there are any it raises, naming the business ids and how many phones are shared, never
   the phone, and the deploy stops with the old version still serving. No user is deleted, deactivated or edited by the
   migration. `docs/runbooks/staff-duplicate-phones.md` is the manual fix: an administrator of that business gives each person
   their own phone or deactivates the extra user through the Users screen (audited), then the deploy is retried.
4. **The recording adapter exists only in the dev and test profiles** (`@Profile({"dev", "test"})`). Every other profile,
   including `server` on staging and production, gets `LogOnlyNotifier`, which sends nothing, keeps nothing and logs only the
   channel, the template key and the last four characters of the recipient; it never reads the parameters. The inviting
   administrator still sees the link once on screen (FR-IAM-01 as amended); a real provider will replace the log-only adapter
   behind the same port.

## Consequences

**Better:** two invitations at once can no longer make two staff users with one phone, whichever path writes the row. No raw
invitation or reset link is held in server memory outside dev and test. The deploy cannot hide a data problem: it stops with a
message an operator can act on, and the phone never reaches the deploy log.

**Worse:** a business with an existing shared phone blocks every deploy until an administrator fixes it by hand. Staging no
longer has a list of sent messages to inspect; an operator who needs a link asks the administrator who saw it.

**Watch for:** a new staff write path must still call `checkUnique` for the friendly answer and catch `DuplicateKeyException`
for the race, mapping it through `UserService.duplicateContact`. No path moves a deactivated user back to active today; one
that does (a reactivation) puts the user back in the index, so it must do both, or it answers 500, with a test that
reactivating a user whose phone an active user now holds gives 409 `duplicate_phone` (issue #573). `DriverArchitectureTest`
keeps `org.postgresql` out of every class but `UserService`. V98's `lock_timeout` bounds only the migration's own wait for
its lock; a write that arrives meanwhile waits for that wait to end, or for the migration to commit. V98 also sets
`row_security` back to `on` rather than to what it found; both are noted here because an applied migration is never
edited (its checksum would stop the deploy). A new `Notifier` adapter must keep the same rule: no parameter that carries a link is stored or logged.
`NotifierProfileIT` fails if a recording adapter is reachable in the `server` profile.
