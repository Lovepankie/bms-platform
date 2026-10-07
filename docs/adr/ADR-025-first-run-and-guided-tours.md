# ADR-025: First run signs the invitee in, and guided tours are data with per-user progress on the server

## Status

Proposed (2026-10-06, issues #19 and #86). Amends FR-IAM-01 (accepting an invitation now also
signs the user in). Works with the two-step policy of issue #93 (recommended, not required) without
deciding it: a role that still requires the second factor is enrolled first, as today (ADR-014).

## Context

A new user who accepts an invitation set a password, was sent to the sign-in page, typed their email
or phone and the password again, and then met a raw setup key with no steps. The dev lead's first run
on staging: "don't know what to do next". After sign-in nothing explained the screens. Issue #19 asks
for a replayable spotlight tour that is role aware and remembers completion per user across devices;
issue #86 asks for a styled, step by step first run with the second factor offered, not forced
(issue #93).

Three questions needed an answer: how the invitee gets from "password set" to "signed in" without
retyping it; where tours live; and where "this user finished the tour" is stored.

## Decision

1. **Accepting an invitation signs the user in.** `POST /api/v1/auth/staff/invitations/accept`
   returns the same `SignInResponse` as a login instead of 204. The one-time token and the new
   password have just proved who the user is, so the password is not checked twice; every other
   sign-in rule applies unchanged: a user whose role requires a second factor gets
   `mfa_enrolment_required` and an MFA token, not a session; a locked or inactive account is refused;
   the sign-in is audited with method `invitation`. A refused acceptance (used, replaced or expired
   link) signs nobody in.
2. **The second factor is offered, not forced, in the first run** when the role does not require it:
   a "Recommended, not required" step with Turn on and Skip for now, using the existing signed-in
   enrolment endpoints. The policy itself stays with issue #93.
3. **Tours are data in the PWA.** A tour is an id, a version and a list of steps; each step names a
   `data-tour` anchor (never a CSS class), an optional page, a title and a body, and may require
   permissions, a module, or a feature flag. Who sees a step is computed from `/me`, so nobody is
   walked to a screen their role cannot use. Steps for features not built yet (billing, payments, the
   trial clock, agents, a staff screen) are registered behind flags that are off, so a later slice
   builds its screen, switches its flag on and bumps the tour version. A component test renders
   every step's screen and fails when an anchor is missing.
4. **Per-user progress lives in `users.preferences`**, a JSON object on the existing row (one
   additive column, migration `V31`), under `tours`: per tour id, `completed` or `dismissed`, the
   tour version and the time. `GET /api/v1/me` returns it and `PUT /api/v1/me/tours/{tour_id}` writes
   the caller's own entry only; it is a convenience, not a security decision, and is not audited.
   "Do not show this again" is a dismissal and holds for good; finishing is per version, so a new
   version with new steps shows once more; closing without either only hides the tour until the next
   page load. Help replays any tour at any time.
5. **The spotlight is drawn with CSS classes.** A click shield, a hole whose spread shadow dims the
   page, and a card docked to the top or bottom edge, away from the target. The hole's box is set as
   CSS custom properties through the CSSOM, so the production CSP (`style-src 'self'`) holds.

## Consequences

**Better:** a new user goes from the invitation link to their home screen in one flow, with one
action per screen; the second factor is a clear choice; a tour follows the user to any device;
adding steps for a new module is a data change with a test that keeps anchors honest; later slices
switch steps on without touching the framework.

**Worse:** the invitation link is now as strong as a password sign-in for the moment it is used (it
always was, since it sets the password); `users` carries a small JSON document that the database
does not type beyond "an object" (the identity module validates writes, and caps tours at 64).

**Watch for:** copy that drifts from the screens (the anchor test catches moved elements, not stale
words); tours growing long (keep each under about a dozen steps and use page tours for detail);
#93 removing `roles.mfa_required` for tenant admins, after which the first run offers the second
factor to them too with no change here.
