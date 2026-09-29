# ADR-014: Staff authentication: argon2id, TOTP with recovery codes, signed access tokens and server-side sessions

## Status

Accepted (2026-09-29). Implements chapter 7 section 7.4 and chapter 8 section 8.2 for tenant staff
and platform operators in MVP increment 1 (issue #7). Takes in review items 1 and 2 of
`docs/meetings/2026-09-29/spec-review-recommendations.md`, accepted on PR #8. Member sign-in (phone
and PIN, FR-IAM-09) is phase 2 and will reuse the session design below.

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
  reset take effect on the next request. Staff: idle 12 hours, absolute 7 days. Platform
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
