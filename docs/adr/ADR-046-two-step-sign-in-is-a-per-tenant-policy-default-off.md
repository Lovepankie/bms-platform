# ADR-046: Two-step sign-in is optional by default and a tenant policy can require it, for everyone or for chosen roles, after a grace period

## Status

Proposed (2026-10-20, issue #348; not yet accepted by the product owner). Builds on ADR-014 (staff authentication) and ADR-025 (the first run offers
the second factor), and completes the caveat that ADR-042 (forgot password, accepted 2026-10-20) recorded against itself: two-step sign-in was optional
only where the database had been edited by hand. Does not supersede any of them. The migration is `V67` (#368 takes `V65` and #359 `V66`).

## Context

The product owner decided that two-step sign-in (a code from the phone as well as the password) stays **optional** for staff, tenant administrators
included, and that a tenant administrator can choose to require it. Until now the code said the opposite in one place and the opposite of that in another:

- The V2 seed set `roles.mfa_required = true` for `tenant_admin`, so on any fresh database (production, CI, a new tenant) a tenant administrator
  without a factor was sent to enrolment at sign-in and never got a session.
- The staging database had it `false` by a hand edit of reference data, which is not a migration and would be lost the day staging is rebuilt.
- A tenant setting `require_mfa_all_staff` existed, but nothing in the product explained it, and switching it on locked every unenrolled person out at
  their next sign-in with no warning and no time to prepare.

ADR-042 (the forgotten password) built on the staging behaviour and recorded the consequence: where no factor is required, whoever reads a no-factor
administrator's mailbox can reset the password. The owner accepted that for staging until this ticket. This ticket makes the optional state a real,
supported, migration-backed one, and gives a business that wants the stricter rule a safe way to turn it on.

Four things had to be decided: where the optional default lives, what a business can choose, how a business that turns it on avoids locking its own staff
out, and how the platform recommends the stricter setting without forcing it.

## Decision

1. **The default is off, in a migration.** `V67` sets `roles.mfa_required = false` for `tenant_admin` (an `UPDATE`, idempotent, and a no-op on staging where
   the hand edit already made it so). The column and the rule stay: a role flagged there is required for every tenant that has the role, which is how a
   future platform-wide mandate would be expressed. No seeded staff role carries the flag after `V67`. Platform operators are unaffected: an operator
   always enrols (ADR-014), and the operator reset routes keep asking for the operator's authenticator code.
2. **A tenant can require it for everyone, or for chosen roles.** Three keys join the tenant settings (`tenant_settings.settings`, chapter 6), changed
   through the existing `PATCH /settings` by `core.settings.manage` (the tenant administrator), audited with before and after like every settings change:
   `require_mfa_all_staff` (existing: everyone), `require_mfa_roles` (new: a list of staff role keys; `member` is refused) and `mfa_grace_days` (new: 0 to
   90, default 0). A user is **covered** when the tenant requires it for everyone, or the user holds an active assignment of a chosen role, or a role
   flagged in `roles.mfa_required` (decision 1). No new permission: the people who may change settings may change this, and everyone else may read it
   (`GET /settings` needs `core.settings.read`).
3. **A grace period, one deadline, rounded to the end of a day.** A grace of N days means a covered person may sign in until the END of the day N
   days from today in the business's own time zone (`tenants.timezone`, Africa/Kampala for a Ugandan business; `/me` and the settings carry it): the server stores the start of the day after (`mfa_enforce_from`), the screens say "by 3
   Nov" and mean the whole of 3 Nov, and the reminder adds that the person can sign in until the end of that day. 0 days means at once (no deadline).
   **The deadline moves only when a grace period is sent**, and the screen sends it only when the policy widens (a role added, everyone, or turned on from
   off: new people need a date) or the administrator changed the grace period. Narrowing, an unrelated save, or a save that sends no grace period never
   moves it, so narrowing after the date has passed does not re-open it. An explicit grace period may move the date earlier than the one now set (or
   clear it, for 0); the review says so in plain words ("This moves the date earlier than the one now set"). Turning the policy off clears it. A person
   already enrolled is never affected. A user invited after the deadline is taken through enrolment by the first run, which already offers it (ADR-025).
   **A person given a covered role after the deadline has no grace:** coverage is read when they sign in or refresh, and the deadline has passed.
4. **Enforced now, and due by, are two different answers, and a session cannot outlive the deadline.** `Accounts.mfaRequired(user)` means "must have a factor to sign in **now**": it is true for a
   role flag, or for a covered user once the deadline has passed. Every existing caller keeps that meaning: the sign-in, the first run and the sign-in after
   a reset send a required user to enrolment, and the forgotten-password request is refused by email for a required user with no factor (ADR-042
   decision 7). During the grace period a covered user is not yet required, so the mailbox reset still works for them; that is the same exposure as for any
   optional user, for the grace period only. `GET /me` adds `mfa_due_by` (the deadline, only for a covered user without a factor whose deadline has not
   passed, otherwise null) and `two_step_policy` (`off`, `roles` or `everyone`), so the app can show the reminder and the recommendation without a second
   call. `mfa_required` on `/me` keeps its meaning. **After the deadline a refresh with no factor does not renew the session:** `AuthFlow.refresh` ends
   that session family (audited `core.auth.session_ended_two_step_required`) and answers `sign_in_required` with **no MFA token** (a stolen refresh cookie must not be able to enrol the thief's authenticator), revoked
   with the new reason `two_step_required` (V67 widens the check), so the person signs in again with the password, which leads to enrolment; an access token still alive (at most 15 minutes) meets a blocking "Set up two-step sign-in now" screen in the app, which keeps the reminder
   words, while `me.mfa_required && !me.mfa_enabled`.
5. **The forgotten-password and admin-reset rules do not change, and a link from an administrator is not refused for a covered person.** Only the self-service mailbox link is refused for a user who is required now with no factor (ADR-042 decision 7). A link an administrator or the operator hands over (the Reset sign-in button of the Users screen, #339) is issued as before, because it travels out of band; when it is used after the deadline, the sign-in that follows (`afterReset`) sends the person to enrolment, never to a session, exactly as a normal sign-in does. Before the deadline it signs them in and the reminder shows. The step-up of the admin reset of a sign-in (ADR-042 decision 9(c)) stays: a current
   authenticator code when the admin has one, otherwise the admin's own current password. The operator routes stay code-only. A tenant administrator can
   still not reset another tenant administrator or themselves.
6. **A plain-words warning before it takes effect, a proof and an email when it is relaxed, and a recommendation while it is off.** The Two-step
   sign-in screen (Business set-up) shows the three choices (optional for everyone, required for chosen roles, required for everyone), the grace period
   (default 14 days on the screen, 0 meaning at once), and before saving a review that says in plain words who will be asked, until when, what happens to
   the date already set, that the person saving will be asked too, and that someone who loses their phone is helped by an administrator (or by the
   platform operator when it is the only administrator). **Turning it off, narrowing it** (everyone to roles, or a role dropped) **or putting a date back** (a later date than the one set, or a date for people who were required at once) **needs the same proof as
   the admin reset of a sign-in** (the current authenticator code when the administrator has one, otherwise their own password: `totp_code` or `password`
   in the settings body, 422 `totp_required` or `password_required`, a wrong one counted toward the lock), checked before the change, and **every administrator
   who may change the settings is emailed, in the same transaction as the change** (the row is read again under its lock and a relaxation without the proof is refused) (`identity.two_step_policy_weakened`: what changed, who, when; the actor included, so a business with one
   administrator hears of a hijacked session's change). The port `core.tenancy.PolicyChangeGuard` is implemented in identity (tenancy cannot depend on it).
   Widening needs neither. While the policy is `off`, a banner on the home and set-up screens recommends turning it on to everyone who may change settings;
   it can be put away for 14 days (kept in the browser only). A covered person not yet enrolled sees a reminder on every staff screen until they set it up.
7. **A signed-in set-up screen.** `/staff/two-step` lets a signed-in user turn two-step sign-in on from the app (the existing `mfa/enrol` and `mfa/confirm`
   routes, which already accept a session; **starting asks for the person's own password**, so a stolen session cannot enrol its own authenticator) and shows the recovery codes once. Until now only the invitation's first run and forced enrolment could do it.

## Consequences

**Better:**

- The optional state is the same on every database, written in a migration, and the staging hand edit becomes redundant instead of load-bearing.
- A business that wants the stricter rule can turn it on without an operator, with a warning and time, and a business that does not want it is left alone.
- No new table, permission or route: the policy rides on the settings row, its version check (`If-Match`), its validation and its audit.
- Enrolled users keep their second factor everywhere; this decision only changes who is **made** to enrol.

**Worse:**

- Optional by default means a tenant administrator who never enrols has an account that is as safe as their mailbox and password, and on a fresh database
  that is now true everywhere, not only on staging. That is the owner's decision; the recommendation banner, the first-run offer and the reminder are the
  mitigation, and the ADR-042 consequence about a no-factor administrator's mailbox now applies to every tenant until it turns the policy on.
- One deadline for the whole policy means widening the policy gives people who were already past their date a new one. The alternative (a deadline per
  person or per role) is more to explain and to store, and the lenient direction was preferred.
- Existing tenants keep what they have: V67 changes the reference flag only, and no tenant's policy is switched on for it. The owner accepted the mailbox
  risk of an optional account; it is stated here and in SDD 8.2a, and the recommendation banner is the nudge.
- Several existing integration tests assumed the V2 seed: they now turn the policy on for the tenant they test (a test-support helper) instead of relying on
  the global role flag.

**Watch for:**

- `roles.mfa_required` is still read. Setting it for a role in a later migration forces that role everywhere with no grace period and no tenant choice;
  that is a platform decision and needs its own ADR.
- A new way to sign in (a passkey, single sign-on) must say whether it counts as a second factor for a covered user.
- The deadline is an instant, shown to the user as a date in their language; a deadline "end of day Kampala" is not promised.
- Do not lower the grace to 0 for a business whose only administrator has not enrolled: they would be sent to enrolment at their next sign-in. The warning
  says so; the operator reset is the way back for a lost phone.
