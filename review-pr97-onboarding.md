# Review of PR #97: sign-up, applications, operator portal and activation (issue #89)

Reviewer: independent adversarial review (automated, scheduled), 2026-10-06.
Head reviewed: `808eae8` on `feat/89-self-onboarding-slice-1`; diff `origin/main...808eae8`
(62 files, the four #89 commits only; #87 is already on `main`). Spec:
`docs/specs/self-onboarding-and-subscriptions.md` and ADR-024 (identical on `main` and the branch).
Method: code reading of every changed backend, migration, frontend and docs file; no test run
(CI is running). The PR description was not relied on.

## Verdict: CHANGES REQUESTED

Most of the security design holds up: links are 256 bits, SHA-256 at rest, in the URL fragment,
sent in a POST body and compared in constant time; definer functions are owned by `bms_owner`,
pinned `search_path`, no dynamic SQL, EXECUTE revoked from PUBLIC and granted to `bms_app` only,
and `bms_app` holds no table privilege; the platform host check reads `getServerName()` with
X-Forwarded-Host neutralised (pre-existing ADR-018 M1 setting); a staff token, no token and the
development headers are refused on every operator route (`OnboardingIT`); Activate goes through the
same `PlatformService.create` path, locks the application row inside the creating transaction and
writes tenant, invitation, application status, audit and outbox row in one transaction, with an
idempotency key that gives exactly one activation email; email and Telegram are plain text (no HTML
body, no `parse_mode`); audit payloads mask email and phone; no em or en dash in added lines
(Python scan); no `Co-Authored-By` trailer in any of the four commits.

Two problems block the merge, because `main` deploys to staging, the staging host already holds
live SMTP credentials (chapter 9 as changed by this PR), and the sign-up form is public and
anonymous: the form can be used to make the platform send attacker-written text to arbitrary
addresses, and the volume bounds that are meant to stop that can be bypassed.

## Blocking

### B1. The verification email is a phishing relay: attacker-written text sent to an unverified address from the platform's sender

- Where: `backend/src/main/java/com/rincoltech/bms/core/notifications/internal/OutboxTemplates.java:24`
  (template `onboarding.verify_email`: `Hello {contact_name}, ... for {business_name}`), filled at
  `backend/src/main/java/com/rincoltech/bms/core/onboarding/internal/ApplicationService.java:163-182`.
- Exploit: an anonymous visitor posts `/api/v1/platform/sign-up/applications` with
  `contact_email` = victim, `contact_name` = "Customer, your account is suspended. Restore it at
  pay-example.test/restore today" and a similar 200 character `business_name`. `line()` strips
  control characters only, so URLs, digits and arbitrary wording survive. The platform sends a
  DKIM-signed email from `BMS_MAIL_FROM` to the victim containing that text, next to a genuine
  platform link that makes it look legitimate. The address has not been verified (that is what the
  email is for), so the recipient never consented. Each new victim address takes one request; a
  repeated sign-up for the same address reuses the stored names (`onboarding_application_relink`),
  so the first request is the one that counts. This is the standard abuse of sign-up forms and it
  burns the sender domain's reputation (and the SMTP account) quickly.
- Fix: the email to an unverified address must carry no applicant-supplied free text. Greet
  generically ("Hello,"), name the application by its server-generated reference only, and keep
  `contact_name` and `business_name` for the emails sent after the operator has looked at the
  application (needs info, rejected, activation), or at least after the email is verified.
  Add a render test that the verify template has no placeholder other than `{link}` and
  `{reference}`.

### B2. The volume bounds on outbound email can be bypassed: no global cap, IPv6 per-address keys, evictable per-email buckets

- Where: `backend/src/main/java/com/rincoltech/bms/core/onboarding/internal/SignUpRateLimiter.java:32,37,59`;
  key source `backend/src/main/java/com/rincoltech/bms/kernel/RequestContext.java:47`
  (`getRemoteAddr()` after Tomcat's RemoteIpValve).
- What is right: behind the tunnel the chain is Cloudflare (appends the visitor to
  X-Forwarded-For) to cloudflared to Caddy (trusted, appends the cloudflared address) to Tomcat,
  whose RemoteIpValve walks from the right past private addresses, so a spoofed X-Forwarded-For
  value on the left is ignored and the key is the real visitor address. Spoofing the header does
  not work.
- Exploit 1 (per address): the key is the full address. Cloudflare serves IPv6, and any VPS or
  home line has a /64, that is 2^64 distinct keys. Rotating the source address inside one /64
  gives unlimited fresh `create-ip` buckets of 5 an hour each.
- Exploit 2 (per email): all three limits share one LRU map of 10,000 keys. Every request from a
  fresh IPv6 address with a fresh throwaway email adds two keys, so about 5,000 requests evict
  every other bucket, including the `create-email` bucket of a victim, which then starts full
  again. The "3 an hour per email" bound therefore holds only while nobody floods the map. The same
  flood also resets every legitimate visitor's bucket, which is harmless, but it means the map is
  not a bound at all under attack.
- Exploit 3 (global): nothing bounds the total number of verification emails per hour or per day.
  Combined with B1 the form sends attacker text to as many addresses as the attacker has source
  addresses, and Exploit 1 gives effectively unlimited source addresses. The class comment defers
  the real volume control to "an edge rule in front (Cloudflare)", but no such rule exists in the
  repository, in `deploy/` or in the runbook, so it is not part of what this PR ships.
- Exploit 4 (lock-out and harassment, lower severity): 3 requests an hour with a victim's email
  keep the victim's `create-email` bucket empty, so the victim's own sign-up answers 429 for as
  long as the attacker keeps going, and each of those requests rotates the victim's link and sends
  them another email (72 a day from one IPv4 address, more with Exploit 2). Rotation also
  invalidates the link the operator just sent in a needs info or rejected email.
- Fix (all three needed): (a) key IPv6 clients by their /64 (and IPv4 by /32); (b) keep the
  per-email bound in the database, not in the evictable map: refuse to enqueue a
  `onboarding.verify_email` row when the outbox already holds N rows for that recipient in the last
  24 hours (a count over `notification_outbox` by recipient and template, index on
  `(recipient, created_at)`), and when the bound is hit still answer 202 (so it is not an oracle)
  but send nothing; (c) add a global cap on new applications and verification emails per hour
  (configuration, with a platform audit row and an operator Telegram alert when it trips), so the
  worst case is bounded whatever the edge does. If the Cloudflare rule is meant to be the primary
  control, add it as a documented deploy step in `docs/runbooks/` and chapter 9 in this PR.

## Non-blocking

### N1. Failed and unsent outbox rows keep live one-time links in clear text indefinitely; the docs say otherwise

- Where: `backend/src/main/resources/db/migration/V23__onboarding_applications_and_outbox.sql:372`
  (params cleared only when `p_ok`), `:415` (purge deletes `sent` rows only),
  `:400-409` (retry); docs `docs/sdd/08-security-design.md:455,481`.
- Scenario: the activation email row holds the tenant admin's invitation URL (set password and
  enrol MFA for a brand new tenant). Spec section 12 requires the activation link "hashed at rest".
  The row keeps it in `params` while pending (forever if SMTP is unset, which the PR presents as a
  normal mode) and after three failures (failed rows are never purged). Anyone with read access to
  a backup or the owner role during the 72 hours can take over the first admin account. Chapter 8
  states the outbox keeps parameters "only until it is sent" and "cleared from the outbox once
  sent", which is not true for failed rows. `retry` resets attempts without checking expiry, so it
  can also send an expired link.
- Fix: clear `params` when a row becomes `failed`, and purge or clear pending rows older than the
  longest link lifetime (7 days); refuse `retry` for a row older than that (answer 409 and tell the
  operator to re-issue the link); correct the two chapter 8 sentences.

### N2. A bot token with a stray character is copied into `last_error`, the WARN log and the operator portal

- Where: `TelegramChannel.java:53` builds `URI.create(API + botToken + "/sendMessage")` outside the
  try; `OutboxDispatcher.java:142-143` keeps the message of any `IllegalArgumentException`.
- Scenario: a `.env` saved with CRLF line endings or a pasted token with a trailing space makes
  `URI.create` throw `IllegalArgumentException: Illegal character in path at index N:
  https://api.telegram.org/bot<token>/sendMessage`. `safeError` keeps that message, so the full
  token is logged, stored in `notification_outbox.last_error` and shown in "Messages not sent".
- Fix: validate the token shape once at startup (`^\d+:[A-Za-z0-9_-]+$`, and disable the channel
  if it does not match), build the URI in the constructor, and make `safeError` keep messages only
  from an exception type the senders own (a private `SendFailure`), not from the JDK's
  `IllegalArgumentException`.

### N3. Delivery attempts are counted only if the claiming transaction commits: a poison row loops forever and blocks the queue

- Where: `OutboxDispatcher.java:80,110` (claim, send and record in one transaction).
- Scenario: the row lock is held across the network send. Any `Throwable` that is not an
  `Exception` (for example a `NoClassDefFoundError` from a missing mail provider class, or
  `StackOverflowError`), or a lost database connection after a successful send, rolls the
  transaction back with `attempts` unchanged. The row stays `pending` with the oldest
  `next_attempt_at`, the job throws, and every 30 seconds the same row is claimed first and sent
  again: an unbounded resend loop to one recipient, and every row behind it waits. The documented
  "three failures then failed" never triggers. Holding a pooled connection and a transaction open
  for up to 50 seconds per row (10 s connect plus 20 s plus 20 s) is also costly on the Pi.
- Fix: claim in a short transaction that increments `attempts` and sets a lease
  (`next_attempt_at = now + 2 minutes`) and commits; send outside any transaction; record the
  result in a second short transaction. A crash then retries after the lease, and the attempt
  bound holds.

### N4. Opening the emailed link verifies without any user action, so link scanners verify for the recipient

- Where: `frontend/src/areas/onboarding/applicant.tsx:113` calls `verifyApplicant` on page load.
- Scenario: enterprise mail scanners that render pages and run JavaScript (common in Microsoft 365
  and Google Workspace) open the link and the POST runs, so an address that never consented (B1)
  becomes "verified", joins the operator queue and triggers the operator email and Telegram alert.
  Operator verification remains the real gate, so this is noise and alert spam, not provisioning.
- Fix: show the application read-only on load (`/status`, which does not verify) and verify only
  on an explicit "Confirm my email" button.

### N5. "One open application per email" is defeated by trivial address variants; non-ASCII addresses fail only at send time

- Where: `V23...sql:76-77` (unique on `lower(contact_email)`), `ApplicationService.java:127`
  (trim and lower case only), `SignUpController.java:44` (`@Email`, which accepts non-ASCII local
  parts).
- Scenario: `name+1@`, `name+2@` (and Gmail dot variants) each get an open application for the
  same mailbox, each with its own verification email (this also multiplies B2's per-email bound).
  A non-ASCII local part passes validation but needs SMTPUTF8; `new InternetAddress(..., true)` or
  the server refuses it, the row fails three times and the applicant hears nothing.
- Fix: refuse non-ASCII addresses at validation with a clear message, and either normalise
  plus-tags for the uniqueness key (a generated `contact_email_key` column) or accept the variant
  and rely on the duplicates panel, but then count the B2 per-email bound on the normalised key.

### N6. Production edge: the per-address key may be a Cloudflare edge address

- Where: `deploy/caddy/Caddyfile:19` trusts private ranges only and reads no client IP header.
- Scenario: if the production records are proxied through Cloudflare without the tunnel, Caddy's
  peer is a Cloudflare edge address, Caddy replaces X-Forwarded-For with it, and every visitor
  behind one edge shares one bucket of 5 applications an hour (a sign-up denial of service). The
  staging tunnel path is correct (see B2).
- Fix: state the production topology in chapter 9; if proxied, add Cloudflare's published ranges to
  `trusted_proxies` with `client_ip_headers Cf-Connecting-Ip` as in `Caddyfile.tunnel`, and add an
  `OnboardingIT` case that a spoofed left-most X-Forwarded-For value does not change the bucket.

### N7. A slug race with the platform API answers 422 `invalid_tenant` instead of 409 `duplicate_slug`

- Where: `backend/src/main/java/com/rincoltech/bms/core/platform/internal/PlatformService.java:210`.
- Scenario: the slug count runs inside the transaction but without a lock; a tenant created
  concurrently with the same slug through `POST /platform/tenants` makes `platform_create_tenant`
  hit the unique index, and the catch maps every integrity violation to "The plan, currency or
  modules are not valid". Exactly one tenant is created (correct), but the operator is told the
  wrong thing. Fix: check the constraint name and answer `duplicate_slug`.

### N8. Small timing difference between a new and a known email

- Where: `ApplicationService.java:140-183`. A new email costs an INSERT and an audit INSERT; a
  known one an INSERT that conflicts and an UPDATE. Bodies, status and headers are identical; the
  difference is one statement and is hard to measure at 5 requests an hour per address, but B2's
  bypass removes that bound. Fix (optional): always perform the same statements, or enqueue the
  work and answer before it runs.

### N9. RLS on the two new tables is enabled but not forced

- Where: `V23...sql:82,110`. `bms_owner` has BYPASSRLS, so FORCE costs the definer functions
  nothing and keeps the "every table forced" invariant (NFR-ISO-01, chapter 6) without an
  exception to explain. Fix: add `FORCE ROW LEVEL SECURITY` and update the comment at `:9-10`.

## Checked and found sound (no finding)

- Migration: definer functions owned by `bms_owner` (Flyway runs as the owner), `SET search_path =
  public, pg_temp` with `public` owned by `bms_owner`; the normaliser pins `pg_catalog`; no
  `EXECUTE format` or string building from input; `REVOKE ALL ... FROM PUBLIC` and `GRANT EXECUTE
  ... TO bms_app` for every function; `REVOKE ALL` on both tables and no grant to `bms_app`
  (`theApplicationRoleReachesTheTablesOnlyThroughTheDefinerFunctions`); generated normalised name
  column with an IMMUTABLE function; expiry (unverified `submitted` after 14 days) and purge
  (`rejected` and `expired` 90 days after closing) match spec sections 4 and 12; additive only.
- Numbering: V23 is provisional (V22 is on open `feat/84-retail-stock-transfers`). The number is
  hard-coded in `MigrationOrderIT.java:32,43,81` and named in `AGENTS.md:54`,
  `docs/sdd/06-database-design.md:1253`, `docs/sdd/15-test-strategy.md:135-139`,
  `docs/workspace.dsl:220`, `JdbcOutbox.java:14`, `onboarding/package-info.java:8` and
  `ApplicationService.java:61`; all must change at renumbering. The branch is behind `main` (#87
  merge) but `git merge-tree` reports no conflict.
- Applicant link: 32 bytes from `SecureRandom`, base64url, shape-checked, SHA-256 stored, 7 day
  expiry, `MessageDigest.isEqual` after the indexed lookup, fragment only (`replaceState` removes it,
  so no Referer or server log carries it), kept in `sessionStorage` for the tab. Reuse within 7 days
  is by design (status page); verification is idempotent and alerts operators exactly once.
- Status page IDOR: lookup by token hash only; the view carries no contact details.
- Header injection: names pass `line()` (controls removed) and go only into bodies; subjects are
  constants plus the server reference; recipient parsed strictly; `@Email` admits no CR or LF.
- Stored XSS: React escapes every applicant field; no `dangerouslySetInnerHTML`, no `innerHTML`,
  no applicant data in an `href`. Email and Telegram bodies are plain text.
- Activate: `platform.tenants.manage` on the platform host; bearer token in a header, so no CSRF
  surface; `CurrentPrincipal` recorded as activator; the row lock in `Steps.before()` plus the
  `onboarding.activation:<id>` key give one tenant and one email (`concurrentActivationsCreateOneTenant`);
  slug rules and reserved labels via `hosts.isValidSlug`; links built from `PlatformHost`
  (configuration), never from request headers; audit `contact_email` masked by `JdbcAuditLog.mask`.
- Senders: SMTPS with `ssl.checkserveridentity=true` and the default trust store (no trust-all);
  Telegram over HTTPS with the JDK client, token not echoed on I/O errors (but see N2); both off
  when unset; `toString` of the properties hides values; new variables in `.env.example` (empty
  placeholders) and both compose files with no values.
- Dependency: `org.eclipse.angus:angus-mail`, no explicit version, so it relies on the Spring Boot parent's dependency management (not
  resolved locally in this review; CI's build confirms it), EDL 1.0
  (BSD-3) or EPL-2.0, compatible; justified (the JDK has no SMTP client). The new comment block is
  inserted between the existing WebP comment and its dependency, so the WebP comment now sits above
  the mail dependency; move it back.
- Frontend: access token in memory only, platform refresh cookie scoped to
  `/api/v1/platform/auth`, MFA token in component state only, portal routes redirect to sign-in
  without a session, no console output, no mock data imported by production code; the new CSS uses
  neutral greys and status colours, the brand colour stays the `--brand` token.
- Docs: FR-ONB and FR-NTF rows, chapters 5, 6, 7, 8, 9 and 15, `workspace.dsl`, `openapi.json` and
  `schema.d.ts` match the code, apart from the chapter 8 outbox sentences in N1. No commercial
  figures, client names or agent terms beyond the fabricated examples already on `main` in the spec.
