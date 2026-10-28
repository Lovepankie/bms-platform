# ADR-014: Staff authentication: argon2id, TOTP with recovery codes, signed access tokens and server-side sessions

## Status

Accepted (2026-09-29). Implements chapter 7 section 7.4 and chapter 8 section 8.2 for tenant staff
and platform operators in MVP increment 1 (issue #7). Takes in review items 1 and 2 of
`docs/meetings/2026-09-29/spec-review-recommendations.md`, accepted on PR #8. Member sign-in (phone
and PIN, FR-IAM-09) is phase 2 and will reuse the session design below. Amended 2026-10-28 (issue #644): the
staff idle limit is 7 days. Amended again 2026-10-28 (issue #647): staff idle 30 days, absolute 90 days, and sign out
everywhere for oneself and for one staff member.

## Context

Increment 1 replaces the development header stub with real sign-in. Chapter 8 already fixes the
shape: passwords hashed with argon2id, TOTP for tenant admins and super admins, a 15 minute access
token, a rotating refresh token whose reuse revokes the family, revocation checked on every
request, lockout after five failures. What was open: the libraries, the token format and key,
where the TOTP secret lives, what happens when an admin loses the phone, and how an invitation
reaches someone before any email or SMS provider is chosen (pending ADR-013 for SMS; no email
provider yet).

Options considered for each part:

- Password hashing: argon2id through Spring Security's crypto module (Bouncy Castle underneath);
  bcrypt; PBKDF2. Chapter 8 names argon2id.
- Access tokens: a signed JWT (ES256 or EdDSA) through Nimbus JOSE; an opaque token looked up on
  every request; Spring Security's resource server with its whole filter chain.
- TOTP: a library; RFC 6238 written in place (about 40 lines, tested against the RFC vectors).
- Lost second factor: recovery codes only; an admin reset only; both.
- Invitation delivery: wait for providers; show the link to the inviting admin as well.

## Decision

- **Passwords** are hashed with argon2id (memory 64 MiB, 3 iterations, parallelism 1, 16 byte
  salt, 32 byte hash) by `Argon2PasswordEncoder` from `spring-security-crypto`, stored in PHC
  string form so the parameters travel with each hash. Only the crypto module is used; there is
  no Spring Security filter chain. Passwords are 10 to 128 characters and are checked against a
  common password list (`common-passwords.txt`). A sign-in for an unknown account costs the same
  hash as a real one, and the error never says which it was.
- **Access tokens** are ES256 JWS (Nimbus JOSE) with a `kid`, 15 minutes, claims `sub`, `tid`,
  `knd`, `sid`, `iat`, `exp`, `iss`, plus `pur` (purpose). The same key signs the 5 minute MFA
  token handed out between the password and the second factor (`pur=mfa`, no session). The key is
  an EC P-256 private JWK in `BMS_TOKEN_SIGNING_JWK`; `java -jar bms-api.jar keys` generates one.
  Outside the dev and test profiles the API refuses to start without it.
- **Sessions are server side.** Each refresh token is 256 random bits in an
  `HttpOnly; Secure; SameSite=Strict` cookie scoped to the auth path, stored as a SHA-256 hash, one
  `auth_sessions` row per token. Rotation marks the row rotated and inserts the next row of the
  family; presenting a rotated token revokes the whole family. The authentication filter checks
  the session row and the user's status on every request, so sign-out, deactivation and an MFA
  reset take effect on the next request. Staff: idle 30 days, absolute 90 days (idle 12 hours and
  absolute 7 days until the amendments of 2026-10-28, issues #644 and #647). Platform
  operators: idle 2 hours, absolute 12 hours (`platform_sessions`).
- **TOTP** is RFC 6238 with the defaults every authenticator app supports (HMAC-SHA1, 6 digits,
  30 seconds, 160 bit secret), implemented in place and tested against the RFC vectors. One step
  of drift either side is accepted, and a code is accepted at most once (the last used step is
  stored, and advanced only by a conditional update that fails for a step already used, so the
  rule holds for concurrent requests; the failure counter and the lock are likewise one atomic
  statement, chapter 8 section 8.2). The secret is encrypted with AES-256-GCM under the application data key
  (`BMS_DATA_KEY`, key id in each ciphertext, chapter 8 section 8.7). Tenant admins and platform
  operators must enrol, at first sign-in if not before; a tenant setting can require it of all
  staff (FR-IAM-06).
- **Recovery codes (FR-IAM-11).** Enrolment issues ten single-use codes (50 random bits each,
  `XXXXX-XXXXX`, no ambiguous characters), shown once and stored as SHA-256 hashes. A code works
  in place of a TOTP code; using one is audited. The user can replace all codes with a current TOTP
  code. Failed codes count toward the lockout like wrong passwords.
- **Admin MFA reset (FR-IAM-12).** A tenant admin can clear another staff user's second factor;
  every session of that user ends and the next sign-in enrols again. A tenant admin cannot reset
  their own. When a tenant's only admin is locked out, a platform operator resets that admin
  through the platform API, audited in both audit logs. This is **not** a maker-checker action:
  with a single admin locked out there is no second person who can sign in to check it. It is an
  audited security event (FR-AUD-03) and the user sees it at their next sign-in.
- **Invitations (FR-IAM-01 as amended).** An invitation is a 256 bit one-time token, stored as a
  SHA-256 hash, valid 72 hours, carried in the link's fragment (`/accept-invitation#token=...`)
  so it never reaches a server log. The link is returned once to the inviting admin in the API
  response and shown in the UI (audited as `core.invitation.link_revealed`, without the token),
  and is also sent through the notification port. Until a provider is chosen the port has only a
  fake adapter that records messages, so staging never blocks on a provider. A new link replaces
  the previous one.
- The development header stub stays for curl and tests in the dev and test profiles; a bearer
  token always takes precedence over it.

## Consequences

**Better:**

- Revocation is immediate without Redis (ADR-008): one primary key lookup per request.
- A lost phone no longer locks a tenant out of its admin-only actions.
- Invitations work on staging and in demos today, with or without a provider.
- The token format and key rotation (`kid`) follow a widely used standard; the cryptographic
  primitives come from maintained libraries and the JDK.

**Worse:**

- Two new libraries (Nimbus JOSE, Bouncy Castle through Spring Security crypto) to keep patched.
- Every host needs two new secrets in its env file, and losing the data key makes every enrolled
  TOTP secret unreadable (everyone would enrol again after an admin reset).
- The admin who invites sees a working link for 72 hours; the reveal is audited, but a careless
  admin can leak it.
- The per-request session and permission lookups cost two small queries per request; chapter 7
  allows a 60 second cache, not built yet.

**Watch for:**

- The in-process per-login rate limit of chapter 7 section 7.10 is not built yet; the lockout is
  the only brake on guessing until it is.
- Rotating the signing key needs two keys valid during the change; today one key is configured.
- When the email provider is chosen, the invitation keeps showing the link to the admin unless
  chapter 3 is changed again.

## Amendment of 2026-10-28 (issue #644): staff stay signed in for a week

A tester on a phone was sent back to Staff sign-in every day: a shop owner who opens the app each morning has been idle
for more than 12 hours overnight, so every morning began with the password. The owner asked on 28 October 2026 that the
app remember the device for about a week.

1. **The staff idle limit is 7 days**, the same as the absolute limit (`StaffAccounts.idleTtl`). A staff session now
   lasts 7 days from sign-in however rarely it is used; a refresh still rotates the token, and the rotated session keeps
   the family's absolute expiry, so using the app never extends the week. The refresh cookie's `Max-Age` was already the
   absolute 7 days and is unchanged. No migration: `auth_sessions.idle_expires_at` is written by the application, and a
   session open at release keeps its 12 hour idle expiry until its next refresh.
2. **Platform operators are unchanged**: idle 2 hours, absolute 12 hours.
3. **What ends a session at once is unchanged**: sign-out (the family), deactivation, a password reset, an
   administrator's or operator's MFA reset and the step-up lock (every session of the user), and a reused refresh token
   (the family). The authentication filter still checks the session row and the user on every request.
4. **The trade-off.** An unlocked phone left with someone else, or a copied refresh cookie, now stays usable for up to
   7 days instead of 12 idle hours. The mitigations are those of point 3: anyone can sign out, an administrator can
   deactivate a person or reset their sign-in, and a password reset ends every session. The phone's own screen lock is
   the first line, and the sign-in help says so.
5. **Tests**: `SessionLimitsTest` pins both principals' limits; `StaffAuthIT` shows that a session used again after 13
   hours still refreshes without moving the week on, and that one past its 7 days absolute is refused with
   `session_expired`.

## Amendment of 2026-10-28 (issue #647): stay signed in while used, and sign out everywhere

The week of issue #644 still sent a shop owner back to the password every seven days, however often the phone was used. The
owner decided on 28 October 2026 (D71) that a phone in use stays signed in, with a way to sign a lost one out.

1. **Staff: idle 30 days, absolute 90 days** (`StaffAccounts.idleTtl`, `absoluteTtl`). Every refresh rotates the token and
   gives the new row another 30 idle days, so a phone that opens BMS at least once a month stays signed in; the family keeps
   the absolute expiry of its sign-in, so after 90 days the person types the password again, however often they used it. The
   refresh cookie's `Max-Age` follows the absolute limit and is now 90 days. This replaces point 1 of the amendment of issue
   #644. No migration: both expiries are written by the application, and a session open at release keeps its 7 day absolute
   expiry.
2. **Platform operators are unchanged**: idle 2 hours, absolute 12 hours.
3. **Sign out everywhere, for oneself.** `POST /auth/logout-everywhere` (any signed-in staff user) revokes every session of
   the caller, this one included, with `revoked_reason = sign_out`, clears the refresh cookie and is audited
   `core.auth.signed_out_everywhere` with `sessions_ended`. The person menu offers it as Sign out everywhere, after a question;
   with no answer from the server nothing is signed out on the phone either, since only the server can end the other devices.
   It is allowed for a suspended business, like sign-out.
4. **Sign out one staff member's devices.** `POST /users/{user_id}/sign-out` (`core.users.manage`) revokes every session of
   another staff user with `revoked_reason = admin` and is audited `core.user.signed_out_everywhere` with `sessions_ended`,
   by the acting administrator. The person stays active and signs in again with the password; it is for a lost or stolen
   phone. Another administrator's devices only by the Owner (403 `owner_only`, `owner_protected` on the Owner), as every
   other change to an administrator (ADR-062); oneself is refused (422 `cannot_sign_out_self_here`), since the person menu
   does that. An unknown id, another business's user and a person outside the caller's branches are 404 before anything is
   revoked (the branch rule of the reset of a sign-in, ADR-017); someone invited or deactivated is 409, having no session. Not a maker-checker action: like the MFA reset it must work with one administrator.
5. **Both act at once**: the authentication filter checks the session row on every request, so a still-valid access token
   of a signed-out device is refused (`session_revoked`) on its next request, and its refresh cookie too. A refresh now locks
   the user's row before its session row, the order in which every revocation of all of a user's sessions locks (sign out
   everywhere, deactivation, a password or MFA reset), so a refresh running at the same moment either finishes first and its
   new session is revoked, or waits and finds its session revoked; before, a refresh could add a session the revocation did
   not see.
6. **What ends every session is unchanged**: a password reset, deactivation, an MFA reset and the step-up lock.
7. **The trade-off.** An unlocked phone, or a copied refresh cookie, stays usable for up to 30 idle days and 90 in all, not 7.
   The controls are the phone's screen lock, Sign out everywhere from any other device, an administrator's Sign out their
   devices, deactivation and a new password. The help article "My phone was lost. How do I sign it out?" says so.
8. **Tests**: `SessionLimitsTest` pins both principals' limits; `StaffAuthIT` shows a session used again after 29 days still
   refreshing with its 90 days unmoved and a 90 day cookie, one idle for 30 days refused, one past 90 days refused however
   recently used, Sign out everywhere ending every device (a refreshed session too) and its audit row, and the per-staff
   sign-out with and without the permission, outside the caller's branches, for another business's user and for someone not
   active, and its audit row; `SuspendedTenantInterceptorTest` lets both sign-outs through for a suspended business;
   `TenantOwnerIT` holds the Owner rule; the deactivation, MFA reset and password reset tests
   still show every session ending.
