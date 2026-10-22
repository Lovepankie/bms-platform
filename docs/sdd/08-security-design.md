# 8. Security Design

**Status:** Draft · **Owner:** Hillary

## 8.1 Overview

The platform holds national ID numbers, phone numbers, family relationships, incomes and
debts of the tenants' members, and moves the tenants' money. The controls in this chapter
serve four goals, in priority order:

1. **Tenant isolation.** No tenant ever sees another tenant's data (ADR-003).
2. **Money integrity.** No single person can move money alone above a threshold, and
   every movement is balanced, immutable and attributable (ADR-004, section 8.4).
3. **Least privilege.** Every endpoint requires one named permission; every role holds
   only what its job needs (section 8.3).
4. **Accountability.** Every state change is audited (section 8.6).

Non-functional targets that measure these are in chapter 4 (NFR-SEC, NFR-ISO, NFR-DP).

## 8.2 Authentication

Summarised from chapter 7 section 7.4 and chapter 3 section 3.7.

| Principal | Factor(s) | Lockout | Session |
|---|---|---|---|
| Platform super admin | Email plus password plus TOTP (mandatory) | 5 failures, 15 minutes | Access 15 minutes, refresh idle 2 hours |
| Tenant admin | Email or phone plus password plus TOTP (mandatory) | 5 failures, 15 minutes | Access 15 minutes, refresh idle 12 hours |
| Other staff | Email or phone plus password; TOTP optional or tenant-mandated | 5 failures, 15 minutes | Access 15 minutes, refresh idle 12 hours |
| Member | Phone plus 5 digit PIN, set after an SMS one-time code | 5 failures, 30 minutes | Access 15 minutes, refresh idle 30 days |

- Passwords and PINs are hashed with argon2id (memory 64 MiB, iterations 3,
  parallelism 1, tuned so one hash takes about 100 ms on the production host). Passwords are
  10 to 128 characters and are refused when they are on the common password list
  (`backend/src/main/resources/common-passwords.txt`) or equal the sign-in name (ADR-014).
  Hashing is bounded (ADR-059, issue #426): at most `BMS_AUTH_ARGON2_MAX_CONCURRENT` hashes run at once (4 by default, 256
  MiB), and a caller that waits two seconds for a slot is answered 503 `sign_in_busy` with a Retry-After of 2 to 5
  seconds, so a flood of sign-ins slows sign-in down instead of filling the heap. No hash runs, or waits for its slot,
  inside a transaction: every caller reads in one short transaction, hashes with no database connection held, and
  writes in another, so the flood cannot take the connection pool from other tenants either.
- **Sign-in limits** (ADR-059, issue #426, revised after the review of #433).
  - **The lock is the limit.** Five wrong passwords in a row lock a sign-in address for 15 minutes (FR-IAM-05). An
    account keeps its count and lock in its own row; any other address typed (the email in lower case or the phone in
    E.164, within one tenant and one kind of account) keeps them in `sign_in_address_failures` (V78), keyed by the
    SHA-256 of the address, with the same statement. **What is true:** after five failures an address of nobody answers
    exactly as an account (the same 423 `account_locked` and words, for the same time, before any hash), across
    restarts and floods; both paths do the same database work. **What is not hidden:** an address of nobody forgets its
    count after seven days without a failure, an account only at a right password, so failures spread over more than a
    week can tell them apart; and the work is the same, not timed to the microsecond.
  - **Only failures count per client IP** (an IPv6 /64): staff and operator sign-in, two-step (verify, enrol, confirm),
    invitation and first-password routes, `BMS_AUTH_SIGN_IN_FAILURES_PER_IP` failures in 10 minutes (300 by default),
    429 `rate_limited` with Retry-After (the refill time) while the budget is spent. A success costs nothing, which is
    what carrier-grade NAT on MTN and Airtel needs (many subscribers behind one public IPv4). The reset routes keep 20
    requests an hour and 30 link uses in 10 minutes per IP, and 10 requests an hour per sign-in address; the second
    factor 30 attempts in 15 minutes per account. These buckets are in memory, per instance; a bucket that remembers
    anything is never evicted.
  - **Lifting a lock**, audited as `core.auth.unlocked`: a completed password reset; a tenant admin
    (`POST /users/{user_id}/unlock`) and the platform operator, each with a reason. Once a lock is in force every sign-in
    answer is 423, the right password included, even for guesses already past the first look. None lifts an operator's lock.
  - **The lock is a denial of service by design**: anyone who knows a sign-in name can keep it locked with five wrong
    passwords every 15 minutes. It is accepted because without it the same person could guess the password; the owner
    gets back in through the reset, an admin or the operator (ADR-059, Consequences).
  Member sign-in (FR-IAM-09, P2) is not built; it will use the same lock and per-IP budget.
- One-time codes, invitation and setup tokens, recovery codes and refresh tokens are stored as
  SHA-256 hashes only.
- TOTP secrets are encrypted at rest with the application data key (8.7). TOTP is RFC 6238
  (HMAC-SHA1, 6 digits, 30 seconds); one step of drift is accepted and each code works once.
  Enrolment is forced at first sign-in for platform operators and for a role flagged in
  `roles.mfa_required` (no seeded role is since `V67`). For tenant staff, tenant admins included, it is optional by default
  and a tenant administrator can require it for everyone (`require_mfa_all_staff`) or for chosen roles (`require_mfa_roles`)
  with a grace period (`mfa_grace_days`, `mfa_enforce_from`): until the date a covered user without a factor signs in and is
  reminded, after it they are sent to enrolment (FR-IAM-06, FR-IAM-16, ADR-046). Changing the policy needs `core.settings.manage`
  and is audited with before and after.
- **First run** (FR-IAM-13, ADR-025): accepting an invitation sets the password and signs the user
  in through the same rules as a sign-in, so a role that requires the second factor is sent to
  enrolment, never past it; a refused link signs nobody in. The PWA screens are: 1. Choose a
  password (live checklist of the server's rules, show or hide). 2. Two-step sign-in: "Recommended,
  not required" with Turn on and Skip for now, or, when the role requires it, the set-up with no Skip
  (QR code drawn in the browser, Open in authenticator, the key in groups of four with Copy, a 6 digit
  field with `inputmode=numeric` and `autocomplete=one-time-code`). 3. The ten recovery codes in a
  grid with Copy all, Download as text and a required "I have saved these codes". Then the staff
  home, where a new tenant admin lands on the set-up checklist and the first-run tour starts
  (FR-IAM-14). The invitation token is removed from the address bar once used; no key or code is
  logged. Whether tenants must require it is the tenant's own policy (issue #348, ADR-046): optional by default.
- **Recovery codes** (FR-IAM-11): enrolment issues ten single-use codes, shown once. A code is
  accepted in place of a TOTP code; the user can replace all of them with a current TOTP code.
  Failed codes count toward the lockout.
- **Step-up by a signed-in session** (issue #233, #309): a sensitive action (saving the payment account, replacing the
  recovery codes) asks again for a current TOTP code. Wrong codes here count on their own counter and lock
  (`step_up_failed_count`, `step_up_locked_until`, migration V58), never on the password's `failed_login_count`. The fifth
  wrong code locks step-up for 15 minutes and ends every session of the user, so a stolen session cannot keep guessing. The
  two counters never feed each other: a wrong password is unauthenticated (anyone who knows a login name can send one), so it
  locks the sign-in but neither counts toward the step-up lock nor ends a session; and a step-up slip never counts toward the
  password lock. Four wrong codes plus one wrong password, or four wrong passwords plus one wrong code, therefore end nothing
  (tested by `MerchantAccountStepUpIT`). A correct code clears the step-up count; a step-up is also refused while the
  password lock holds.
- **Lost second factor** (FR-IAM-12): another tenant admin resets it (`POST
  /users/{user_id}/mfa/reset`, `core.users.manage`); a tenant admin cannot reset their own. For a
  tenant whose only admin is locked out, a platform operator resets that admin through the
  platform API. A reset clears the secret and the codes, ends every session of the user, and is
  audited. It is deliberately not a maker-checker action (section 8.4, ADR-014).
- **Invitations** (FR-IAM-01): a 256 bit one-time token, valid 72 hours, in the link's fragment so
  it never reaches a server log. The link is shown once to the inviting admin (audited as
  `core.invitation.link_revealed`, without the token) and sent through the notification port;
  until a provider is chosen that port records messages only, so no flow waits for one.
- **Forgotten password** (FR-IAM-15, ADR-042): on the sign-in page the user types an email or phone and, if an active account has
  an email on file, gets a link there through the outbox (`identity.password_reset`; SMS stays off). A tenant admin can instead
  issue a link for an active user who is not a tenant admin and not themselves (`POST /users/{user_id}/reset-sign-in`,
  `core.users.manage`; a fresh authenticator code in the body, or the admin's own password when they have none enrolled), and the
  platform operator one for a tenant admin (`POST /platform/tenants/{tenant_id}/users/{user_id}/reset-sign-in`, with the operator's own fresh authenticator code, as the platform's `mfa/reset` now also needs): shown once,
  never emailed, no-store. The token is 256 random bits in the link's fragment, stored as a SHA-256 hash only and found by that hash
  (no comparison of secrets in application code), single use, valid one hour; a newer link revokes the older ones. The link's
  host is built from the tenant's slug under `BMS_TENANT_HOST_PATTERN` (`PlatformHost.tenantOrigin`), never from a request
  header. Using it sets the password under the invitation's rules and hashing, revokes every session and refresh token of the
  user (`revoked_reason = password_reset`), queues an email that the password changed (no link) and then signs in through
  the same rules as a sign-in (a link issued by an administrator or the operator also emails the user at once, without
  the link): a user with TOTP is asked for the code (`mfa_required`), a role that requires a second factor
  is sent to enrolment, and the failed-attempt lock is not cleared (the user is told to sign in later, `sign_in_required`), so
  a reset can never switch the second factor off, skip it or reset its guess counter. A user whom a role flag or the tenant's two-step policy requires, now, to have a second factor (`roles.mfa_required`, or `require_mfa_all_staff` / `require_mfa_roles` once the grace period has ended, ADR-046) that is not set up gets no link by email; otherwise a user with none resets by mailbox, and a deactivated user
  (the open links are revoked and the link is refused anyway) and a user still invited get no reset. Audited as `core.password_reset.requested`,
  `.refused` (with a reason, for a known account that was not acted on), `.link_revealed` (admin path) and `.used`; the token
  is in no audit row, log line or error. The reset page takes the token out of the address bar and the browser history the moment it reads it and keeps it in memory only (never in storage) until the server accepts the password. Section 8.2a gives the threat model.
- Access tokens are ES256 JWS signed with the key of section 8.7; the 5 minute MFA token between
  the password and the second factor is signed with the same key and cannot be used as an access
  token.
- Refresh token rotation with reuse detection: presenting a rotated token revokes every
  session in its family (FR-IAM-07).
- Sign-in errors never reveal whether an account exists; a sign-in for an unknown account costs
  the same password hash as a real one.
- **Concurrency guarantees.** The factor and lockout rules hold when the same request is sent
  many times at once (tested by `AuthConcurrencyIT`):
  - Each failure increments `failed_login_count` and derives `locked_until` in one `UPDATE ...
    RETURNING` that reads only the row being updated, so parallel failures are all counted and
    the fifth sets the lock; the decision and the audit use the returned values. A failure after
    the lock has expired starts a new count. A correct password is checked again against the
    locked row before a session or MFA token is issued, so it cannot slip past a lock set by
    parallel failures.
  - The second factor steps lock the account row and, for staff, the `user_credentials` row
    (`FOR UPDATE OF u, c`); a waiting request then reads the committed factor state.
  - A TOTP step is recorded with `UPDATE ... WHERE totp_last_step IS NULL OR totp_last_step < ?`
    and a code whose update changes no row is refused, so one code signs in or replaces the
    recovery codes once. A recovery code is consumed with `UPDATE ... WHERE used_at IS NULL` and
    works only when that update changed a row.
  - A refresh locks its session row, so of N parallel refreshes with one token exactly one
    rotates; the others find it rotated and revoke the family as a reuse.
  - Approving locks the approval request row and executes only while it is pending, so parallel
    approvals execute the action once (`ApprovalsIT`).

### 8.2a Threat model of the forgotten password (ADR-042)

| Threat | Control |
|---|---|
| Finding out who has an account by asking for a reset | One generic 202 body for every login, a response floor of 400 ms so the work that differs (a lookup, or a lookup, a link and an outbox row) is not visible in the time, the work done inside a transaction that is not held during the wait, and every difference (no account, not active, no email, too many links) visible only in the audit log. The address limit (429) depends on the caller alone |
| Flooding a mailbox or the outbox | 3 links an hour per account, counted in the database and answered like any other request; 20 requests an hour per address; the message is queued once per link with an idempotency key and lapses with the token |
| Guessing a link | 256 random bits; looked up by SHA-256, so no secret comparison and no partial match; 30 attempts per 10 minutes per address; single use; one hour |
| Reading a link from the database, a backup or a log | The token is stored as a hash. The outbox row of an emailed link holds it in plain text until the email is sent or the link expires; a job every 5 minutes then clears the parameters, so the window is the hour plus 5 minutes at most, or until sent (ADR-042 decision 11); a backup taken in that window holds it, one hour old and single use. No log line, audit row or error holds a token |
| A link sent to the wrong place or a phishing host | Sent only to the email on file (never to the address typed on the form); the host is the tenant's own, from configuration, never a request header; the token travels in the fragment, so it reaches no server log or Referer |
| Account takeover by whoever reads the mailbox | An account with TOTP still needs the code, and the reset does not disable it. The reset lifts the failed-attempt lock (ADR-059, amending ADR-042 decision 7), so a mailbox reader gets up to five more guesses at the code per reset, at most three resets an hour (35 guesses an hour instead of 20), each reset changing the owner's password, ending their sessions and emailing them. Two-step sign-in is optional by default on every database (migration `V67`, ADR-046): where no role flag or tenant policy requires a factor, a user with none resets by mailbox as before. **V67 changes no existing tenant's policy.** **Whoever reads a no-factor tenant admin's mailbox can take the account over, for as long as the tenant has not required two-step sign-in (the owner's decision of 20 October 2026, now true of every tenant, not only staging); the mitigations are the recommendation to turn the policy on, the first run's offer, the notice email when a password changes and every device signed out. A tenant that requires it closes the gap once the grace period has ended, and not before: inside the grace period a covered user with no factor is as exposed as an optional one.** Where `roles.mfa_required` or the tenant's policy (`require_mfa_all_staff`, `require_mfa_roles`) requires, now, a factor the user has not set up, no link is sent by email (the request is answered like any other and refused inside, decided by that data and not by a role name), because the reader of the mailbox could otherwise choose the password and enrol their own authenticator; that user is helped by an administrator or the operator. What is left is an account with no second factor and no requirement, which is as safe as its mailbox; the owner of the mailbox is emailed when a password is changed and every device is signed out |
| A stale or stolen session surviving a reset | Every session and refresh token of the user ends in the transaction that sets the password |
| A reset for an account that should not get back in | Only active users; a deactivated user's open links are revoked and refused; an invited user keeps the invitation path |
| One tenant's link or admin reaching another's user | The table has forced row-level security with the tenant policy; the lookup runs inside the tenant of the host, so a link of tenant A used on tenant B's host is not found, and an admin of B cannot issue a link for A's user (`PasswordResetIT`, `RlsIsolationIT`) |
| An admin resetting users outside their branches | The route needs `core.users.manage`; an admin whose scope is not every branch reaches only users whose every role is inside it |
| A hijacked tenant admin session chaining the MFA reset and the sign-in reset to take an account over | The tenant route refuses oneself and any tenant admin (a locked-out admin is recovered by the platform operator), checks the target first, then asks the acting admin to prove they are still themselves: a fresh authenticator code when they have one enrolled, otherwise their own current password (two-step sign-in is optional only where the `tenant_admin` role's `mfa_required` was hand-edited off, which is staging alone; the V2 seed requires it, so elsewhere an admin with no factor never gets a session and the password branch is not reached; whoever reads a no-factor tenant admin's mailbox can take the account over on staging until #348 lands, a consequence of the optional setting listed for Hillary's explicit acceptance); a stolen session or token has neither. A wrong password counts on the sign-in lock like one at sign-in, and the fifth wrong password a session sends here ends that session (and only it), so a thief with a stolen token cannot keep guessing. The user is emailed when a link is issued (ADR-042 decision 9) |
| A stolen platform operator token resetting an admin's second factor and then issuing a link for the same admin, on any tenant | Both platform reset routes need the operator's own fresh, single-use authenticator code and fail closed without one; five wrong codes lock the step-up and end the operator's sessions; both audit logs record the act; the admin is emailed when a link is issued, before it is used, which is the detective control (ADR-042 decision 9(b2)) |
| A link in the admin's hand used by the admin | Possible by design for an ordinary user (an admin who can reset a sign-in could also deactivate and invite): the issue and the reveal are audited with the admin's identity, the user is emailed when it is issued and again when the password changes |

### 8.2b Threat model of push notifications (ADR-050)

A push subscription is per user per device (`push_subscriptions`, forced row-level security, composite foreign key to the user).

| Threat | Control |
|---|---|
| The server posts to an address a caller chose (server-side request forgery) | The endpoint must be `https` on 443, no credentials, a name and not an address, at a known push service host (or one in `BMS_PUSH_EXTRA_ENDPOINT_HOSTS`); checked at subscribe and before every send; redirects are never followed |
| Someone else's device or notifications | The routes are the caller's own: the user is the principal, never a parameter; a device of another person or tenant is 404; the API never returns an endpoint or a key |
| A shared phone keeps telling the last person's news | The endpoint is unique in the tenant, so subscribing moves the device to the new person; sign-out removes the device's row; a deactivated user is never a recipient |
| A notification read on a lock screen | Templates carry no name, phone, identity number or amount (a test fails one that does); text is cut to 80 and 200 characters; a tap opens an app route only |
| An invalid-curve or oversized key from a client | The subscription point is checked to be on P-256 and the secret to be 16 bytes at subscribe; a payload over one record is refused |
| Theft of the VAPID private key | It lives only in the host environment (`BMS_PUSH_VAPID_PRIVATE_KEY`), never in git, logs or the API; rotation is in `docs/runbooks/push-notifications.md` |
| A push failure harming a business event | The notice is an event handled in a savepoint; the sender runs outside any transaction; failures follow the outbox retry and expiry |
| A notice telling people outside the branch or the role (issue #412, ADR-057) | Recipients are read from role assignments at publish time: a new order reaches only holders of `retail.order.manage` given for that shop (`BranchStaff`), low stock the holders of `retail.stock.read` at that branch and the branchless administrators, credit due the administrators and the holders of `retail.sale.all_sellers` given for that shop, the daily summary the administrators; no notice carries cost, profit or any amount, so `retail.profit.read` is never needed to read one |
| A permission prompt nobody asked for (issue #412) | The turn-on card asks the browser only inside the tap on Turn on, and asks at most three times on a device |

## 8.3 Role-based access control

### 8.3.1 Roles

| Role | Kind | Typical holder | Branch scope |
|---|---|---|---|
| Super admin | platform | Platform operator (not a tenant user) | n/a |
| Tenant admin | staff | Owner or general manager of the tenant; exactly one of them is the Owner (`users.is_owner`, ADR-062), who alone manages the other administrators | All branches |
| Branch manager | staff | Manager of one or more branches | Assigned branches |
| Loan officer | staff | Field or desk officer | Assigned branches |
| Cashier / teller | staff | Handles cash and mobile money in and out | Assigned branches |
| Accountant | staff | Books, reconciliation, period close | Usually all branches |
| Auditor | staff | Internal or external auditor, read only | Usually all branches |
| Member | member | Borrower, saver, investor | Own records only |
| Sales | staff | Retail shop staff: sells, reports usage, reads stock (ADR-020) | Assigned branches |
| Storekeeper | staff | Retail store staff: moves stock between stores and shops, receives it, counts it, reports usage; no selling, prices, money or cost (ADR-055) | Assigned branches |
| Purchasing | staff | Given on top of another role to chosen people: records purchases and restocks (ADR-055) | Assigned branches |
| Till and banking | staff | Given on top of another role to chosen people: closes the till and does the banking, the cash book of its branches (ADR-055, V74) | Assigned branches |
| Supervisor | staff | Sees every seller's sales and totals, the dashboard and the sales analysis at its branches; no profit, cost, stock value, Users or Controls (ADR-055, V74) | Assigned branches |

A user may hold several roles, each with its own branch scope. The effective permission
set is the union; the branch scope applies per role (a user who is branch manager at A
and loan officer at B approves loans only at A). A role that applies in several branches is
one assignment per branch: the owner assigns each staff member to the shops and stores they
work in, and everything the person sees and does through that role is limited to those
branches (ADR-055).

The tenant admin is never limited by branch: the assignment has no branch (the API refuses one
and, since migration `V73`, the check `user_role_assignments_admin_every_branch` refuses one in
the database), so an administrator sees and acts in every shop and store, every aggregation
across branches, every report, Controls, profit and cost, whatever other role or branch the same
person is also given; a further role only adds to the union and never narrows it (ADR-055
decision 4).

**The Owner (ADR-062, FR-IAM-18, migration `V79`).** Every business has exactly one Owner: one of
its tenant admins, marked `users.is_owner`, not a role of its own, so the Owner holds exactly the
tenant admin's permissions and the matrix below is unchanged. What the Owner adds is protection,
checked in `core.identity` (`Owners`) on top of `core.users.manage`:

| Act on the Users routes | Owner | Other tenant admin |
|---|---|---|
| Invite, change roles, deactivate, send a new link, reset two-step sign-in, unlock: a staff member who is not an administrator | Y | Y |
| Invite an administrator, make someone an administrator | Y | 403 `owner_only` |
| Change another administrator's roles or contact, deactivate them, reset their two-step sign-in, send them a new link | Y | 403 `owner_only` |
| Any of those on the Owner | own contact only; not their own administrator role (422 `owner_must_transfer_first`) | 403 `owner_protected` |
| Change one's own email or phone | Y, with the step-up (authenticator code, else own password); the old address is told | Y |
| Transfer ownership to another active administrator (step-up: authenticator code, else own password; not within 24 hours of the Owner's email, phone or password change) | Y | 403 `owner_only` |
| Lift a sign-in lock (any staff member) | Y | Y |

The database holds it too: one Owner per business (`users_one_owner`), never a deactivated user
(`users_owner_is_staff`), made the Owner only while an administrator (`users_owner_is_admin`), and
the Owner's administrator role cannot be revoked (`user_role_assignments_owner_keeps_admin`). A
platform operator makes an active administrator the Owner with a reason and the operator's own
authenticator code (`POST /platform/tenants/{tenant_id}/users/{user_id}/make-owner`), audited on
both sides, and sends a new invitation link to an invited Owner who never joined (`.../invitation`).
One advisory lock per business serialises everything that makes or moves the Owner against every
owner rule (ADR-062 decision 9). A business with no Owner yet (one with no active administrator when V79 ran) keeps the
rules of before until the operator assigns one. Two-step sign-in is not forced on the Owner; the
Users screen prompts the Owner to turn it on.

### 8.3.2 Permission matrix

`Y` = granted. Blank = not granted. TA tenant admin, BM branch manager, LO loan officer,
CA cashier, AC accountant, AU auditor, ME member, SA sales (the retail shop role, ADR-020), SK storekeeper, PU purchasing, TL till and banking and
SV supervisor (the retail roles of ADR-055). Super admin acts only through the
platform API and holds no tenant permissions.

| Permission | TA | BM | LO | CA | AC | AU | ME | SA | SK | PU | TL | SV |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `core.settings.read` | Y | Y | | | Y | Y | | | | | | |
| `core.settings.manage` | Y | | | | | | | | | | | |
| `core.payment_account.manage` | Y | | | | | | | | | | | |
| `core.branches.read` | Y | Y | Y | Y | Y | Y | | | | | | |
| `core.branches.manage` | Y | | | | | | | | | | | |
| `core.users.read` | Y | Y | | | | Y | | | | | | |
| `core.users.manage` | Y | | | | | | | | | | | |
| `core.audit.read` | Y | Y | | | Y | Y | | | | | | |
| `core.audit.export` | Y | | | | | Y | | | | | | |
| `core.approvals.read` | Y | Y | Y | Y | Y | Y | | | | | | |
| `core.ledger.read` | Y | Y | | | Y | Y | | | | | | |
| `core.ledger_accounts.manage` | Y | | | | Y | | | | | | | |
| `core.journals.create` | Y | | | | Y | | | | | | | |
| `core.journals.approve` | Y | | | | Y | | | | | | | |
| `core.periods.close` | Y | | | | Y | | | | | | | |
| `core.periods.approve_close` | Y | | | | Y | | | | | | | |
| `core.payment_methods.manage` | Y | | | | Y | | | | | | | |
| `core.notifications.read` | Y | Y | | | | Y | | | | | | |
| `core.notification_templates.manage` | Y | | | | | | | | | | | |
| `core.imports.manage` | Y | | | | Y | | | | | | | |
| `core.imports.approve_commit` | Y | | | | Y | | | | | | | |
| `core.payments.read` | Y | Y | | Y | Y | Y | | | | | | |
| `core.payments.collect` | Y | Y | | Y | | | | | | | | |
| `core.payments.allocate` | Y | | | Y | Y | | | | | | | |
| `core.reports.financial` | Y | Y | | | Y | Y | | | | | | |
| `core.reports.audit` | Y | | | | | Y | | | | | | |
| `lending.members.read` | Y | Y | Y | Y | Y | Y | | | | | | |
| `lending.members.create` | Y | Y | Y | | | | | | | | | |
| `lending.members.update` | Y | Y | Y | | | | | | | | | |
| `lending.members.verify_kyc` | Y | Y | | | | | | | | | | |
| `lending.members.blacklist` | Y | Y | | | | | | | | | | |
| `lending.members.transfer_approve` | Y | | | | | | | | | | | |
| `lending.products.read` | Y | Y | Y | Y | Y | Y | | | | | | |
| `lending.products.manage` | Y | | | | | | | | | | | |
| `lending.loans.read` | Y | Y | Y | Y | Y | Y | | | | | | |
| `lending.loans.create` | Y | Y | Y | | | | | | | | | |
| `lending.loans.appraise` | Y | Y | Y | | | | | | | | | |
| `lending.loans.approve` | Y | Y | | | | | | | | | | |
| `lending.loans.cancel` | Y | Y | Y | | | | | | | | | |
| `lending.disbursements.request` | Y | Y | | Y | | | | | | | | |
| `lending.disbursements.authorise` | Y | Y | | | Y | | | | | | | |
| `lending.repayments.create` | Y | Y | | Y | | | | | | | | |
| `lending.repayments.reverse_request` | Y | Y | | Y | Y | | | | | | | |
| `lending.repayments.reverse_approve` | Y | Y | | | Y | | | | | | | |
| `lending.credits.refund_approve` | Y | Y | | | Y | | | | | | | |
| `lending.charges.waive_request` | Y | Y | | | Y | | | | | | | |
| `lending.charges.waive_approve` | Y | Y | | | Y | | | | | | | |
| `lending.loans.write_off_request` | Y | | | | Y | | | | | | | |
| `lending.loans.write_off_approve` | Y | | | | | | | | | | | |
| `lending.loans.restructure_request` | Y | Y | | | | | | | | | | |
| `lending.loans.restructure_approve` | Y | | | | Y | | | | | | | |
| `lending.collateral.read` | Y | Y | Y | Y | Y | Y | | | | | | |
| `lending.collateral.manage` | Y | Y | Y | | | | | | | | | |
| `lending.collateral.release_request` | Y | Y | Y | | | | | | | | | |
| `lending.collateral.release_approve` | Y | Y | | | | | | | | | | |
| `lending.savings.read` | Y | Y | Y | Y | Y | Y | | | | | | |
| `lending.savings.open` | Y | Y | Y | | | | | | | | | |
| `lending.savings.deposit` | Y | Y | | Y | | | | | | | | |
| `lending.savings.withdraw` | Y | Y | | Y | | | | | | | | |
| `lending.savings.withdraw_approve` | Y | Y | | | Y | | | | | | | |
| `lending.savings_products.manage` | Y | | | | | | | | | | | |
| `lending.investments.read` | Y | Y | Y | Y | Y | Y | | | | | | |
| `lending.investments.open` | Y | Y | Y | | | | | | | | | |
| `lending.investments.fund` | Y | Y | | Y | | | | | | | | |
| `lending.investments.payout` | Y | Y | | Y | | | | | | | | |
| `lending.investments.early_withdraw_approve` | Y | Y | | | Y | | | | | | | |
| `lending.investments.fund_approve` | Y | Y | | | Y | | | | | | | |
| `lending.investments.reverse_approve` | Y | Y | | | Y | | | | | | | |
| `lending.investment_products.manage` | Y | | | | | | | | | | | |
| `lending.collections.read` | Y | Y | Y | Y | Y | Y | | | | | | |
| `lending.collections.log_action` | Y | Y | Y | Y | | | | | | | | |
| `lending.collections.assign` | Y | Y | | | | | | | | | | |
| `lending.collections.all_officers` | Y | Y | | Y | Y | Y | | | | | | |
| `lending.members.contact` | Y | Y | Y | | | | | | | | | |
| `lending.reports.portfolio` | Y | Y | | | Y | Y | | | | | | |
| `lending.reports.collections` | Y | Y | Y | Y | Y | Y | | | | | | |
| `lending.reports.members` | Y | Y | | | | Y | | | | | | |
| `lending.reports.compliance` | Y | | | | Y | Y | | | | | | |
| `lending.insights.read` | Y | Y | Y | | Y | Y | | | | | | |
| `lending.insights.all_officers` | Y | Y | | | Y | Y | | | | | | |
| `lending.insights.export` | Y | Y | Y | | Y | Y | | | | | | |
| `lending.advisor.use` | Y | Y | | | Y | Y | | | | | | |
| `lending.advisor.configure` | Y | | | | | | | | | | | |
| `member.self.read` | | | | | | | Y | | | | | |
| `member.self.apply` | | | | | | | Y | | | | | |
| `member.self.pay` | | | | | | | Y | | | | | |
| `retail.catalogue.manage` | Y | | | | | | | | | | | |
| `retail.price.edit` | Y | | | | | | | | | | | |
| `retail.price.below_cost` | | | | | | | | | | | | |
| `retail.customer.manage` | Y | | | | | | | Y | | | | |
| `retail.sale.create` | Y | | | | | | | Y | | | | |
| `retail.sale.read` | Y | | | | | | | Y | | | | Y |
| `retail.sale.void` | Y | | | | | | | | | | | |
| `retail.sale.all_sellers` | Y | | | | | | | | | | | Y |
| `retail.till.close` | Y | | | | | | | | | | Y | |
| `retail.stock.read` | Y | | | | | | | Y | Y | Y | | Y |
| `retail.stock.transfer` | Y | | | | | | | | Y | | | |
| `retail.stocktake.commit` | Y | | | | | | | | Y | | | |
| `retail.purchase.create` | Y | | | | | | | | | Y | | |
| `retail.usage.report` | Y | | | | | | | Y | Y | | | |
| `retail.profit.read` | Y | | | | | | | | | | | |
| `retail.cashbook.read` | Y | | | | | | | | | | Y | |
| `retail.savings.record` | Y | | | | | | | | | | Y | |
| `retail.savings.overwrite` | Y | | | | | | | | | | | |
| `retail.banking.record` | Y | | | | | | | | | | Y | |
| `retail.expense.record` | Y | | | | | | | | | | Y | |
| `retail.expense.manage` | Y | | | | | | | | | | | |
| `retail.withdrawal.record` | Y | | | | | | | | | | | |
| `retail.advance.create` | Y | | | | | | | | | | | |
| `retail.advance.repay` | Y | | | | | | | | | | | |
| `retail.cashbook.void` | Y | | | | | | | | | | | |
| `retail.branch_role.manage` | Y | | | | | | | | | | | |
| `retail.pricelist.manage` | Y | | | | | | | | | | | |
| `retail.pricelist.use` | Y | | | | | | | | | | | |
| `retail.price.override` | Y | | | | | | | Y | | | | |
| `retail.credit.manage` | Y | | | | | | | | | | | |
| `retail.credit.write_off` | Y | | | | | | | | | | | |
| `retail.credit.override` | Y | | | | | | | | | | | |
| `retail.sale.return` | Y | | | | | | | | | | | |
| `retail.payable.manage` | Y | | | | | | | | | | | |
| `retail.purchase.approve` | Y | | | | | | | | | | | |
| `retail.document.issue` | Y | | | | | | | | | | | |
| `retail.controls.read` | Y | | | | | | | | | | | |
| `retail.order.manage` | Y | | | | | | | Y | | | | |

The staff member and collateral screens (issue #280) offer an action only when the session holds its permission in the record's branch, from `permission_scopes`; the API remains the authority. An ID image is listed and opened only with `lending.members.verify_kyc`. No permission was added.

Notes:

- A loan officer running `lending.reports.collections` sees only loans where they are
  the responsible officer; branch managers and above see their branch scope.
- Insights (#153, ADR-030): `lending.insights.read` opens the page in the user's branch scope.
  Without `lending.collections.all_officers` (the loan officer) the due, arrears and promise lists cover only the
  caller's own portfolio, and a borrower's phone on them is whole only with `lending.members.contact` (administrators,
  branch managers, loan officers), masked for the cashier, the accountant and the auditor (ADR-064).
  Without `lending.insights.all_officers` (the loan officer) every figure, chart and table covers
  only loans the user is the responsible officer for, and members they are the officer of; the
  server enforces it whatever the request asks. `lending.insights.export` downloads a drill-down
  table as CSV, audited as `lending.insights.exported`; a member name in an export is reduced to
  initials unless the user holds `lending.members.read`, and no insights table carries a phone or
  an ID number. The daily digest settings need `core.settings.manage`.
- Client extras (issue #210): the permissions `lending.advisor.use` and `lending.advisor.configure` are in the matrix above (migration `V55`, issue #226); `core.payments.manage` is not yet (its migration adds the row, with #224). The grants: `core.payments.manage`
  to the tenant admin and accountant, `lending.advisor.use` to the tenant admin, branch manager, accountant and auditor,
  `lending.advisor.configure` to the tenant admin. `core.payments.collect` starts a gateway collection (the loan page offers "Collect by mobile money or card" to a holder of it together with `lending.loans.read`, on an active loan; slice #216, and the savings account page offers it with `lending.savings.read` on an active or dormant account; slice #219) and `core.payments.manage` uploads the
  merchant statement and reads the reconciliation (ADR-035); switching collections on needs `core.settings.manage`. The
  AI assistant (ADR-037) needs `lending.advisor.use` to read the brief and ask, `lending.advisor.configure` to read the
  call log and set caps, and `core.settings.manage` to switch it on after the acknowledgement. The stored daily brief covers every branch, so it is returned only to a user whose scope covers every branch of the tenant in `lending.advisor.use`, `lending.insights.read` and `lending.insights.all_officers` and who holds the last (a branch manager holds all officers for their own branches only, so is not one); everyone else gets the automatic summary computed for their own scope. The tenant's SMS settings (its own switch with the acknowledgement, the sender
  choice, the window, the lowered caps, the balance switch, the opt-out list and its import) need `core.settings.manage`, which only the tenant
  administrator holds, and are read with `core.settings.read` (the lists with `core.notifications.read`); the SMS wording, its
  preview and the test message need `core.notification_templates.manage` (the tenant administrator). The operator's switch, the
  operator's daily SMS cap and the approval of sender ids are the platform operator's only (issues #212 and #218 seed no new
  permission: the operator's `PUT /platform/tenants/{tenant_id}/sms` and `.../sms/sender-ids/{sender_id}` use
  `platform.tenants.manage` and their reads `platform.tenants.read`, and the database refuses these changes from any transaction
  bound to a tenant). A tenant can lower its caps under the operator's and choose only a sender id the operator approved for it;
  a test message goes only to the sink or the environment's allow-list, never to a number a user typed in a live environment; the assistant can be switched on only for tenants in `BMS_AI_ENABLED_TENANTS`; collections can be
  switched on only while the tenant's own payment account is verified (`merchant_account_not_verified`); saving, testing and removing that account need `core.payment_account.manage` (migration V57; the tenant admin only, because it redirects where every repayment is paid; saving and removing also ask for a current authenticator code, and the other holders are emailed), reading its status `core.settings.read` (the key's last four characters only go to a holder of the account permission), the platform console reads its status through `platform.tenants.read` and may release an account with `platform.tenants.manage` (audited); a member's SMS opt-out is added with `lending.members.update`, but narrowing it (`all` to `reminders`) or lifting it needs `core.settings.manage` and a reason that goes into the audit row (stopping less is the administrator's; the screens ask for a confirmation first); a test message that would really go out obeys the operator's switch, the tenant's switch, the opt-out list and the caps, checked and reserved under one lock per business so concurrent tests cannot pass a cap together. Running a report run needs
  the report's own permission (a pack needs all seven); loan officers still see only their own loans.
  Built (extras slice D1, issue #214): report runs add no permission and no seeded row (the matrix and the seeds are unchanged;
  `PermissionMatrixIT` still compares them). `POST /report-runs` is open to signed-in staff and the service checks the report's own
  permission, because the permission belongs to the report, not to the route; a run is read by its requester or a holder of
  `core.audit.read`, and its file by its requester alone, judged when it is asked for: while reports are on, while they still hold the
  report's permission, and while their branches and officers still cover the frozen scope (a user moved from all branches to one
  cannot keep downloading the tenant-wide file); every refusal is 404. The branch and
  officer scope is resolved from the requester's permissions when the run is queued and stored on the run, so the job that executes
  it (no signed-in user) can neither widen nor lose it; `lending.par` takes it from the insights module, the same code as the
  insights endpoints, and needs `lending.insights.read` as well. Report runs are off until a tenant administrator sets
  `reports_enabled` in the tenant settings (`core.settings.manage`).
  Built (extras slice D2, issue #217): the PDF and Excel renderers and `lending.portfolio_outstanding` add no permission and no seeded
  row either (both lending reports run under `lending.reports.portfolio`); `PermissionMatrixIT` still passes and this matrix equals the
  seeds. A spreadsheet cell is never a formula: text is written as a string cell and text that begins with `=`, `+`, `-`, `@`, a tab or a
  carriage return carries the quote prefix style (FR-RPT-07, threat "formula injection through a name"). The PDF prints every value as
  escaped text through the XML-mode templates and loads nothing but `data:` URIs (ADR-034). A file name served to a browser is
  `report-<id>.<ext>`, and no log line, run row or audit entry carries a name or a figure.
- Branch managers approve loans, but never one they submitted or appraised (FR-APR-03).
- Member ID images (`id_front`, `id_back`) need `lending.members.verify_kyc` in the member's
  branch, so tenant admins and branch managers, the people who verify KYC; photos and other member
  documents need `lending.members.read`. Cashiers and auditors get 403 on ID images. Every
  download URL issued is audited with the user, the member and the document, and so is every
  denied attempt (`core.document.access_denied`, written in its own transaction). The member's
  document list shows ID images only to those who may open them, and a document with no known
  kind is denied (fail closed). If auditors need the
  images later, a read-only audit permission is added then (#29, decided by the dev lead).
- The retail CSV product import (#146) is for administrators only: the route declares `retail.catalogue.manage` and the service also requires `core.settings.manage`, which only the tenant admin holds, so a custom role granted the catalogue permission alone cannot run it; its cost column needs `retail.profit.read`.
- Retail (ADR-020): the admin role is the tenant admin, who holds every `retail.*` permission
  except `retail.price.below_cost`; the sales role (`retail_sales`) holds sale create and read,
  stock read, usage report and customer manage, scoped to its assigned branches. Lending roles hold no retail permission. Cost, cost
  snapshot, valuation at cost and profit fields are omitted from responses (absent, not null)
  unless the caller holds `retail.profit.read`, and the profit routes refuse without it. On a
  branch-bound row the permission must cover that row's branch (`principal.may`, ADR-017), so a
  user with profit read at branch A only sees no cost on branch B's stock, movements, sales or
  valuation (review F5); tenant-wide products and price history need it in any branch. The sales
  summary (`GET /retail/sales/summary`, issues #328 and #341) is a sum at every level of its grouping chain (shop, year, month, salesperson, day, category, item), so its `profit_minor` is offered only when the caller holds
  `retail.profit.read` in every branch the figures cover (the requested branches within the `retail.sale.read` scope, or
  every branch when none were asked for and the scope is all of them) and is absent otherwise: a profit summed over some
  branches whose profit the caller may not read would otherwise read as the whole; a caller who holds `retail.profit.read` at one branch only sees the profit of a level only after choosing that branch, and sees it at no level while the shop level lists the others. The salesperson level shows the name of the user who made each sale (from `users`, a deactivated user included) and ranks colleagues by what they sold, so it is management information: it is available only to a caller who holds `retail.profit.read` in every branch the figures cover (the permission the matrix already uses for the retail margins and analysis, and the rule of `profit_minor`), so a manager with profit read at branch A only can rank sellers at A (`branch_id=A`) and not at B or across both; any other caller gets 403 `permission_denied` for the `seller` level or the `seller_id` filter of the summary, `available_levels` leaves `seller` out, and the PWA hides it from Group by and from the default chain. The sales list is no way round it: its `seller_id` filter has the same rule (403), and a row or a sale carries `created_by` and `voided_by` only for a caller who holds `retail.profit.read` at that sale's branch, or when the id is the caller's own, else they are null, so a cashier cannot group the list by person and sum a colleague's takings. It needs no new permission.
  The restocks summary (`GET /retail/purchases/summary`, issue #350) and the stock summary (`GET /retail/stock/summary`) follow the same
  rule for what they add up at cost: `total_minor` and `cost_minor` are present only when the caller holds `retail.profit.read` in every
  branch the figures cover, and the counts, quantities and the value at the selling price need only the list's own permission
  (`retail.purchase.create` and `retail.stock.read`) and its branch scope. The restock cards under a group are the existing restocks list, which already shows each line's cost and the total to a holder of `retail.purchase.create`; the summaries add no new exposure there, and their cost figures follow the stricter `retail.profit.read` rule only because they are sums across restocks. Probing the list with `product_id` or `category_id` finds only restocks that put that product into a branch the caller may read.
  The sales role holds no `core.*` permission; the PWA reads the user's branches from `/me`.
- Storekeeper and Purchasing (issue #399, ADR-055, migration `V73`). The storekeeper (`retail_storekeeper`) holds
  `retail.stock.read`, `retail.stock.transfer`, `retail.stocktake.commit` and `retail.usage.report`: it sends stock from the
  stores and shops it works in, receives what arrives there (with the `transfer_receipt` capability), counts stock and
  corrects a counted quantity, and reports usage and damage, each at its own branches only (the scope rules of
  `retail.stock.transfer` above: the source for a send, a void and a cancel, the destination for a receipt). It holds no
  sale, price, purchase, cash book, payable, document or profit permission, so it cannot sell, set a price, touch money or
  see a cost: every cost field is already absent without `retail.profit.read`, and so, since ADR-055, is the value of the
  stock at the selling price (`expected_sales_minor` of the valuation, `value_minor` of the stock summary, the dead stock
  `value_at_price_minor` of stock health), which is the owner's figure. The owner decided on 21 October 2026 that a storekeeper sees no
  selling prices: `sell_minor` is in an item, stock, stock by location or valuation response only for a caller holding
  `retail.sale.create`, `retail.price.edit`, `retail.sale.all_sellers` (the supervisor) or `retail.profit.read` (`RetailCatalogue.maySeePrices`, prices are tenant wide),
  and `GET /retail/products/{id}/price-history` is 403 otherwise. It reads names, codes, quantities and the stock movements of its
  branches with their quantities (a sale movement says how many left the shelf, not for how much).
  Purchasing (`retail_purchasing`) holds `retail.stock.read` and `retail.purchase.create` and is given on top of another
  role to the people the owner chooses (by default only the tenant admin holds `retail.purchase.create`). At its branches it
  records restocks, and with the `purchasing` capability purchase orders and goods received; it reads the restocks, orders and
  receipts it recorded itself with their buying prices and totals (without `retail.profit.read`, `GET /retail/purchases`, `/purchases/summary`,
  `/purchase-orders`, `/goods-receipts` and each record answer only the caller's own, the owner's decision of 21 October 2026); a restock paid in cash or by bank posts that money out of the branch's cash or
  bank account, as any restock does; and a restock's buying price becomes the item's cost for the whole business (the
  latest purchase sets the cost, ADR-020 decision 5). A new selling price on a restock or a goods receipt applies in every
  shop, so it needs `retail.price.edit` too (403 otherwise; the price the item already has may be sent back unchanged, as
  the restock form does). Supplier invoices and payments stay with `retail.payable.manage`. Neither role is
  limited by a role name in code: every check is the permission and its branch scope, so a role given to an
  administrator adds nothing and takes nothing away. Tests: `RetailRolesIT` (a signed-in user of each role, allowed or
  refused per route and branch, and an administrator with no branch assignment seeing every branch), `PermissionMatrixIT`,
  `MigrationOrderIT`.
- `retail.sale.all_sellers` (issue #399 slice 2, ADR-055, migration `V74`): see the sales and totals of every seller, not only one's
  own; the tenant admin's alone by default, scoped by branch like every retail permission, a read. A holder of `retail.sale.read`
  without it in every branch the figures cover sees only the sales they made, enforced in the queries: the sales list and its
  grouped summary filter on `created_by` (a `seller_id` other than one's own is 403, the salesperson level is 403), one sale and its
  payments are 404 for someone else's, and the owner dashboard, the sales analysis and the two order reports (delivery fees, cancellations) are 403. One
  exception keeps the till working: the debts still owed (`owing=owing` or `overdue` on the sales list, never the summary, and opening a credit sale that still owes)
  are shown whoever made the sale, so the person at the till can take a buyer's payment; a debt is the buyer's figure, not a
  colleague's takings. Cost, margins, profit and the value of the stock are absent for the Sales role already (`retail.profit.read`,
  slice 1). The owner's decisions of 21 October 2026 (ADR-055): the Sales role holds no cash book permission since `V74`;
  `retail.till.close` ("Can close the till and do the banking", the role `retail_till`, given per person at that person's branches, with
  `retail.cashbook.read`, `retail.savings.record`, `retail.banking.record` and `retail.expense.record`) is also required by every cash book
  route, checked in code for each request (403 otherwise), and the tenant admin holds it;
  a buyer's account, statement, payments on account, credit control and customer ageing stay open to the sellers of the branch, with
  `created_by` and `reversed_by` of a payment shown only to a holder of `retail.profit.read` there or to the person; an order of the
  branch is listed with its values for every seller, its `sale_id` and `sale_no` (and the sale number in its history) only for the seller who made that sale or a holder of
  `retail.sale.all_sellers`, and its `created_by` and the history's `by_user` and `by_name` only for the caller's own steps; the
  payments of a sale name `created_by` only for the owner or the person; and the Supervisor (`retail_supervisor`: `retail.sale.read`, `retail.sale.all_sellers`,
  `retail.stock.read`) sees every seller's sales, totals, the dashboard and the sales analysis at its branches only. Tests:
  `RetailOwnerDecisionsIT`. Left as they are, and named for the owner to decide: the cash book's daily summary (the till's cash, which the person
  who banks it needs; profit-derived figures were already hidden, ADR-022 decision 13), the buyer's account (credit control, customer ageing, the statement and its PDF, payments on
  account: what buyers owe, listing their sales whoever made them), and the order list (shared work of the shop). Tests: `RetailSellerFiguresIT` (two sellers and the
  owner, signed in: own list, totals, sale and payments; colleague's 404; the owner reports 403; no cost, profit, margin or stock
  value key in any seller response; the owner with no branch assignment sees every sale and figure), `RetailSalesGroupingChainIT`.
  The range of the Retail home (issue #428, ADR-060) adds no permission: the dashboard with a range keeps the 403 above and the
  Supervisor's branches; every range figure follows the same branch filter; profit needs `retail.profit.read` in every branch
  covered, and so does the top salesperson (a ranking of colleagues, the rule of the summary's salesperson level); a seller's own
  card reads the summary, which is their own sales. Tests: `RetailDashboardRangeIT`, `RetailSellerFiguresIT`, `RetailOwnerDecisionsIT`.
  The receipt to share (issue #439, ADR-061) adds no permission: `GET /retail/sales/{sale_id}/receipt/pdf` and its facts
  `GET /retail/sales/{sale_id}/receipt` need `retail.sale.read` and, without `retail.sale.all_sellers` at the sale's branch, a
  sale the caller made (a caller with no user never counts as its maker), with no exception for a debt still owed (404 otherwise), so a storekeeper (no `retail.sale.read`, 403) and a colleague get none. It writes nothing and is not audited; the
  receipt leaves from the seller's own phone and no server sends it. The number typed for WhatsApp is never logged and is stored only
  when the person ticks the save and holds `retail.customer.manage`. A deliberate, narrow exception to ADR-055 (ADR-061 decision 3):
  the printed receipt is signed "Issued by" the seller who made the sale, read as stored, though ADR-055 hides the maker from a
  caller without `retail.profit.read`. The name appears only in that printed signature of a receipt the caller may already fetch,
  never in any API field; the owner may reverse it. Tests: `SaleReceiptPdfIT`.
- Retail cash book (ADR-022, FR-RET-32, migration V34): the ten `retail.cashbook.*`,
  `retail.savings.*`, `retail.banking.record`, `retail.expense.*`, `retail.withdrawal.record` and
  `retail.advance.*` permissions are rows of the matrix above, seeded by the cash book migration.
  The tenant admin holds all ten. The sales role holds read, savings record, banking record and
  expense record for its assigned branches, as the pilot does; it does **not** hold
  `retail.savings.overwrite` by default, which is a default pending the Owner's answer to open
  question 5, not a decision. Withdrawals and advances (both ways) are owner or admin only, a
  deliberate change from the pilot, where every signed-in user could record advances and their
  payments (open question 6). Adding a cash party of kind `owner`, `staff` or `related_entity` needs
  `retail.expense.manage`; the sales role may add a beneficiary of kind `supplier` or `other` on the fly with
  `retail.expense.record`. One rule covers every profit-derived figure: the day's profit, the
  savings suggestion, `suggested_minor` and **every response field that carries a savings amount**
  need `retail.profit.read` and are absent, not null, without it (a savings amount is half the
  profit, so it leaks it), and so does `cash_purchases_minor` and every figure embedding it, because a restock total is a purchase total at cost (a caller without `retail.profit.read`, the sales role included, is shown `cash_expected_minor` only: takings less voids, expenses and advances paid out, plus repayments, before cash purchases and savings; ADR-022 decision 13, relaxable by the Owner, open question 8); a caller without `retail.profit.read` cannot send a savings amount at all (422 `amount_requires_profit_access`) and never sees `overwritten`; the audit payload of a savings record carries neither the amount nor
  the suggestion (FR-RET-19, ADR-022 decision 12). Voiding needs `retail.cashbook.void`. No cash
  book approval action is registered (open question 3); if one is, its row joins section 8.4 with a
  threshold set first.
- Trade capabilities (ADR-033). Built: `retail.branch_role.manage` (issue #191, migration V37), a row of
  the matrix above, administrators only. It sets a branch's role. A role can only refuse a sale at a
  store; it never grants a permission or widens branch scope. Built with the price lists (issue #195,
  migration V41): `retail.pricelist.manage` (create and edit lists, their items and the customer groups, and
  set a buyer's group and own list) and `retail.pricelist.use` (price a sale from a list other than the
  customer's own or the customer group's), both administrators only, and `retail.price.override`, which
  guards behaviour the sales role already has (a typed unit price), so the migration grants it to every role
  that holds `retail.sale.create`; it is consulted only while the `price_lists` capability is on, and then
  also needs a reason on the line (the changed price is written to the audit entry of the sale). Reading a price list, its items or the customer groups, and seeing a buyer's group and list, needs `retail.pricelist.manage` too, because they are negotiated prices; the sales role asks `POST /retail/prices/resolve` for what it needs. Built with the customer accounts (issue #196, migration V44):
  `retail.credit.manage` (set a buyer's credit limit and payment terms) and `retail.credit.override` (sell on credit above a
  buyer's limit, with a reason of at least five characters, written to the audit trail), both administrators only; payment on
  account needs `retail.sale.create` at the receiving branch and its reversal `retail.sale.void`, so no permission is added for
  them. Both are consulted only while the `customer_accounts` capability is on. Built with the customer returns (issue #201,
  migration V45): `retail.sale.return` (take goods back against a sale, void a return, read the credit notes), administrators
  only and consulted only while the `returns` capability is on. Its scope is the sale's branch, and a line may be restocked
  only in a branch inside that scope. A return worth at least the tenant's approval threshold for the action
  `retail_sale_return` (section 8.4) waits for a second holder of the permission; with no threshold set there is no checker, so
  a business with one administrator can take goods back.   Built with the optional documents (issue #197, migration V46):
  `retail.document.issue` (issue and reprint an invoice, a delivery note or a quotation, make and convert a quotation, produce a customer statement PDF),
  administrators only, not money-moving, consulted only while the `documents` capability is on, in the branch scope of the permission (the
  source branch for the delivery note of a stock move); converting a quotation also needs `retail.sale.create`, which the sale it makes checks as any
  sale does, and changing a series prefix needs `core.settings.manage`; the credit note PDF of a return needs `retail.sale.return` as well, in the return's branch. The stored PDF of a document is readable through the core download route by
  whoever holds the permission in the document's branch (FR-DOC-03) and only while the capability is on. Built with optional purchasing (issue #198, migration V47): `retail.payable.manage` (record supplier invoices and
  payments, reverse and void them, read what is owed to suppliers and the payables report) and `retail.purchase.approve` (decide a
  large purchase order), both administrators only and consulted only while the `purchasing` capability is on; orders and receipts
  use the existing `retail.purchase.create`. Their scope is the order's, the receipt's, the invoice's and the payment's branch (an
  invoice may bill receipts of several branches only when the caller's scope covers all of them), and the author, the last editor and the sender of an order above
  the tenant's threshold for `retail_purchase_order` (section 8.4) cannot be its checker. Landed cost and supplier credit notes (issue #199, migration V48) add no permission: they use `retail.payable.manage` in the branch of every received line and of the invoice, and the cost columns of a line (the parts for inventory and cost of goods sold, the cost before and after, the landed amount on a receipt line) need `retail.profit.read` in the line's branch and are absent otherwise; no maker-checker action is registered for them.
  Each becomes a row of the matrix in the slice that seeds it, because `PermissionMatrixIT` compares
  the seeded permissions with the matrix; all of them go to administrators only.
  `retail.credit.write_off` (issue #340, ADR-043, migration `V64`) writes off a debt that will never be paid: its own
  permission, administrators only, kept apart from `retail.credit.manage` because a write-off cannot be taken back in this release; above the
  tenant's threshold for `retail_sale_write_off` a second holder must approve it (section 8.4). `retail.credit.manage` alone sets credit limits and terms: `retail.customer.manage`, which the sales
  role holds, edits name and contact only. A branch role can only refuse an action, never grant a
  permission or widen scope; cost, margin and landed unit cost fields need `retail.profit.read` in
  the row's branch.
- `retail.stock.transfer` (issue #84, migration V22, FR-RET-16) moves stock from one branch to
  another. It is money-moving (it posts an inventory entry at each branch) and goes to the roles
  that restock, which in the default roles is the tenant admin only. Its branch scope is checked
  on the source branch, for the transfer and for its void; the destination may be any active
  branch of the tenant. Listing and reading transfers needs `retail.stock.read` in the source or
  the destination branch, and the cost fields need `retail.profit.read` in either of them, because
  the same cost is posted to both branches' inventory accounts. With the `transfer_receipt`
  capability (issue #193, FR-RET-40) the same permission guards the two further steps, and the scope
  follows who acts: the destination branch for a receipt, the source branch for a cancel (404
  otherwise). The reports `stock-by-location` and `transfers-in-flight` need `retail.stock.read`
  only; their cost and value fields need `retail.profit.read` in every branch of the report (a
  per-branch value where the permission covers that branch). No permission is added by this slice,
  so the matrix is unchanged.
- Units of measure (issue #194, FR-RET-35) add no permission, so the matrix is unchanged: the pack routes use `retail.stock.read` (list) and
  `retail.catalogue.manage` (add, change), a pack sale uses `retail.sale.create` and a pack restock `retail.purchase.create`, each with the
  branch scope it had. A pack line is judged against `retail.price.below_cost` on its line total. A restock line's pack cost and the
  purchase response's `unit_cost_minor` are as sensitive as the existing `cost_minor` and are shown to the same callers.
- Barcodes (issue #345, ADR-070, FR-RET-68, migration V90) add no permission, so the matrix is unchanged. Scanning and the lookup
  `GET /retail/products/by-barcode/{code}` need `retail.stock.read` (sellers, storekeepers, administrators, in their branch scope for
  `branch_id`). Adding and removing a barcode, making label codes and the Barcode labels page need `retail.catalogue.manage`
  (administrators): a barcode decides which item a scan sells, so it is a catalogue change like a code or a pack. A seller who scans an
  unknown code sees the message without "Add this barcode to a product". The label shows the selling price, which an administrator may
  see. The camera stays under `Permissions-Policy: camera=(self)`; no CSP change (the fallback decoder is pure JavaScript, no WebAssembly).
- Corrections of entry mistakes (issues #351 and #352, ADR-044, FR-RET-44 to FR-RET-46) add no permission, so the matrix is unchanged. Voiding a
  restock (`POST /retail/purchases/{id}/void`) and reading one restock need `retail.purchase.create`; the void needs it at **every** branch the
  restock added stock to (403 `permission_denied` when one is outside the caller's scope), because it takes stock out of all of them. Correcting a
  quantity (`POST /retail/stock/corrections`) needs `retail.stocktake.commit` at the branch, the permission that already adjusts quantities; the
  issue called it `retail.stock.manage`, which does not exist. Adding a product whose name another product of the same unit has needs
  `core.settings.manage` (administrators) and a reason, kept on the product and in the audit entry; `GET /retail/products/similar` needs
  `retail.catalogue.manage`. All three of those permissions are administrator-only today. No maker-checker action is registered for the void or
  the correction (ADR-044 decision 5): a tenant approval threshold for them is a follow-up, so section 8.4 is unchanged. The audit entries
  `retail.purchase.voided`, `retail.stock.corrected` and `retail.product.updated` (with the old and new category) carry the reason.
- `retail.controls.read` (issue #357, ADR-047, migration V66) opens the read-only Controls page: voids, returns, changed prices,
  discounts, sales below cost and sales cancelled at once, by shop, by named person, by day and by reason. It names staff and compares
  them by a rate (a cancelled sale is credited to the person whose sale it was, the approver is shown apart and never ranked), so it is the tenant administrator's alone by default and a business gives it to an owner's or an auditor's custom
  role. It is a read (not money-moving), scoped by branch like every retail permission: a holder sees only the shops of its scope,
  and the figures it sees are sums over those shops only. Every value is a sale price; the one card judged against cost (sales
  below cost) and its repeat patterns are sent only when the caller also holds `retail.profit.read` in every branch the figures
  cover, and asking for that metric without it is 403. No cost field is ever returned. Imported history is neither an event nor a
  sale in a rate.
- `retail.order.manage` (issue #385, ADR-052, migration V70) takes, lists, changes the items of (issue #393), packs, sends, delivers and cancels customer orders and
  reads their two reports (delivery fees, cancellations). It goes to the administrator and to every role that holds
  `retail.sale.create`, the shop staff who answer the phone and the chats. It is money moving: delivering an order records a sale and
  posts its delivery fee. Delivering also needs `retail.sale.create` at the order's shop (403 otherwise), and the sale it makes
  applies every rule of a sale (the price floor, a price list override, the credit limit and its override). An order carries the
  prices agreed and never a cost. The customer's phone is personal data: the audit entry of a change says the phone or the place
  changed, never what it is, and no log line carries it. Scoped by branch like every retail permission.
- `retail.price.below_cost` (issue #64, ADR-020 decision 5) lets a sale line be priced at or below
  the product's cost. It is in the catalogue (migration V13) but no default role holds it, not even
  the tenant admin: it is meant for a custom role, and roles are not yet tenant-editable, so today
  no user holds it. Without it such a sale is refused with 422 `price_below_cost`, and the message
  never carries the cost.
- Write-off approval is deliberately restricted to the tenant admin, the most senior
  role, because write-off removes an asset from the books.
- The matrix is seeded by migration V2 and `PermissionMatrixIT` asserts the seeded
  `role_permissions` equal this table, read from this file (FR-IAM-02). Changing a cell is a
  pull request that changes this chapter and a new migration together.
- The staff Users screen (issue #319, section 7.11.4) follows the two rows above without changing a
  cell: `core.users.read` shows the screen, the staff menu entry and the list (tenant admin, branch
  manager, auditor); `core.users.manage` alone shows Invite someone, Send a new link, Change roles, Reset sign-in
  (issue #339; for an active person who is not an administrator and not oneself) and
  Deactivate (tenant admin). The API still decides every call; the screen only hides what a user
  cannot do, from the permissions in `/me`.

Platform permissions, held only by platform operators (super admins) on the platform host,
never by a tenant role:

| Permission | Grants |
|---|---|
| `platform.tenants.read` | List plans and tenants, read one tenant |
| `platform.tenants.read` (also) | The applications queue and one application with its possible repeats; the outbox counts and failed rows (ADR-024) |
| `platform.tenants.manage` | Create tenants, switch modules, move subscriptions, suspend and resume, reset a tenant admin's second factor |
| `platform.tenants.manage` (also) | Verify, Needs info, Reject and Activate an application; send a failed outbox row again (ADR-024) |

The two platform permissions cover onboarding because Activate creates a tenant: a separate
`platform.applications.*` pair would split one operator duty across two keys with the same holders.
The platform principal carries both, and no tenant token reaches a `/platform` route
(`OnboardingIT` sends a tenant admin's token, no token, the development headers and the tenant
host to every onboarding and outbox route).

**Public sign-up endpoints (ADR-024, spec sections 10 and 12).** `/platform/sign-up/*` is public on
the platform host only. Controls: the Cloudflare rate limiting rule in front (chapter 9 section
9.8); per-address token buckets, IPv6 by /64; in the database, at most 3 confirmation emails per
mailbox in 24 hours and global hourly caps that alert the operator (chapter 7 section 7.10); a
hidden `website` field whose value drops the request silently; one open application per mailbox
(+tags and Gmail dots ignored), enforced by a unique index; the same 202 answer for a new, a known,
a bounded and a dropped request; ASCII-only addresses, input length caps, trimming and NFC
normalisation; a confirmation email that carries no text the applicant typed (only the server's
reference and the link), so the form cannot relay someone's words from the platform's sender; a
"Confirm my email" button, so a mail scanner that opens the link confirms nothing; and the
operator's verification as the real gate, so nothing is provisioned for a stranger. The applicant link is 256 random bits, stored as SHA-256
only, valid 7 days, replaced by every new email, carried in the URL fragment and sent in a request
body, and compared digest to digest in constant time after the indexed lookup.

`core.branches.manage` covers every branch change: create, rename, close, reopen and the stocking switch (FR-BR-01, FR-BR-07, FR-BR-08, issue #329, ADR-041). No permission was added for closing: only the tenant admin closes a branch, and the call is scoped to the caller's branches like the other branch routes (a branch outside the scope is 404). The retail refusals `branch_not_stocking` and `branch_does_not_sell` (a Store, FR-RET-50, ADR-048, issue #366) are not permissions: they apply to every caller, administrators included. Making a branch a Store is the same `core.branches.manage` call; no permission was added.

`core.settings.manage` also covers the tenant's two-step sign-in policy (`require_mfa_all_staff`, `require_mfa_roles`, `mfa_grace_days` in `PATCH /settings`, FR-IAM-16, issue #348, ADR-046). No permission was added: the people who may change the settings may change who must use a second factor, and `core.settings.read` (the tenant admin, branch manager, accountant and auditor) may read it. A user's own set-up of two-step sign-in (`/auth/staff/mfa/enrol` and `/mfa/confirm`) is open to every signed-in staff user.

In-app feedback (issue #447, ADR-065) adds no permission, so the matrix is unchanged: sending feedback and reading one's own (`/feedback`) is
open to every signed-in member of staff, like `/me`, and an administrator does not read a colleague's; the operator's list and screenshot
read need `platform.tenants.read` and a status change or a linked issue `platform.tenants.manage`, on the platform host only. A screenshot's
`DocumentAccess` lets only its sender and an operator read it (a refusal is a 404). The operator reads across businesses only through
the definer functions of `V80`, which refuse a tenant-bound transaction.

### 8.3.3 Enforcement

- Every route declares exactly one permission (`@RequiresPermission`), or is explicitly marked
  public (`@PublicEndpoint`), or open to any signed-in principal of one kind
  (`@AuthenticatedEndpoint`, for `/me`, the caller's own guided tour progress, sign-out, the
  caller's own recovery codes and the caller's own feedback, `/feedback`, ADR-065). A route
  test enumerates all routes and fails on any undeclared route, and `PermissionMatrixIT` proves
  that for every route a principal holding every permission except the route's own gets 403
  (FR-IAM-03).
- The permission check happens before any business logic; the branch scope check applies the
  scope of that permission to the target record (a record outside it answers 404), or to the list
  filter (NFR-ISO-04). Branch scope is per permission: the scope of the role assignment that
  grants it.
- A denial on a money-moving permission (`permissions.is_money_moving`) is audited
  (`core.permission.denied`, FR-AUD-03).
- The member area checks ownership on every read: the record's `member_id` must equal the
  principal's member. Records not owned return 404.
- The frontend hides what the user cannot do, based on `/me`. That is a convenience; the
  API is the authority.
- Cross-module checks go through registered hooks, not shared tables (chapter 5 section 5.4.3).
  The collateral register refuses a release while `CollateralPledges` (registered by the loans
  module) reports an open pledge, and both sides take the item's row lock before they check, so a
  pledge and a release of the same item cannot both succeed (FR-COL-04).
- Guarantor disclosure. A loan shows each guarantor's member number even when that member's home
  branch is outside the reader's scope. That is deliberate: the reader is entitled to the loan,
  and the loan cannot be assessed without knowing who guarantees it. Nothing else about the
  guarantor is disclosed; opening the guarantor's record still needs scope over their branch.

## 8.4 Maker-checker

The approval mechanism (FR-APR-01 to FR-APR-08) enforces the four-eyes rule. The table
maps each action to the permission required to request it (maker) and to decide it
(checker). The checker must be a different user from the maker, enforced by a database
CHECK constraint as well as the service.

| Action type | Maker permission | Checker permission | Threshold applies |
|---|---|---|---|
| `loan_disbursement` | `lending.disbursements.request` | `lending.disbursements.authorise` | Yes |
| `repayment_reversal` | `lending.repayments.reverse_request` | `lending.repayments.reverse_approve` | No (always checked) |
| `charge_waiver` | `lending.charges.waive_request` | `lending.charges.waive_approve` | Yes |
| `loan_write_off` | `lending.loans.write_off_request` | `lending.loans.write_off_approve` | No |
| `loan_restructure` | `lending.loans.restructure_request` | `lending.loans.restructure_approve` | No |
| `manual_journal` | `core.journals.create` | `core.journals.approve` | No |
| `period_close` | `core.periods.close` | `core.periods.approve_close` | No |
| `savings_withdrawal` | `lending.savings.withdraw` | `lending.savings.withdraw_approve` | Yes |
| `savings_reversal` | `lending.savings.withdraw` | `lending.savings.withdraw_approve` | No (always checked) |
| `investment_funding` | `lending.investments.fund` | `lending.investments.fund_approve` | Yes |
| `investment_early_withdrawal` | `lending.investments.payout` | `lending.investments.early_withdraw_approve` | No |
| `investment_reversal` | `lending.investments.payout` | `lending.investments.reverse_approve` | No (always checked) |
| `collateral_release` | `lending.collateral.release_request` | `lending.collateral.release_approve` | No |
| `member_branch_transfer` | `lending.members.update` | `lending.members.transfer_approve` | No |
| `import_commit` | `core.imports.manage` | `core.imports.approve_commit` | No |
| `member_credit_refund` | `lending.repayments.create` | `lending.credits.refund_approve` | Yes |
| `retail_sale_return` | `retail.sale.return` | `retail.sale.return` | Yes, counted over the returns of the sale, and no checker at all while no threshold is set |
| `retail_sale_return_void` | `retail.sale.return` | `retail.sale.return` | Reads the threshold of `retail_sale_return`: needed for a return that was approved or is worth it alone; none while no threshold is set |
| `retail_purchase_order` | `retail.purchase.create` | `retail.purchase.approve` | Yes, on the order's total, and no checker at all while no threshold is set; it is the send of a draft order |
| `retail_sale_write_off` | `retail.credit.write_off` | `retail.credit.write_off` | Yes, on the unpaid amount written off; with no positive threshold every write-off needs a second administrator when the tenant has two or more administrators who count (active, locked, or removed or deactivated within 30 days), and one alone may write off alone; any write-off done without a second person, even below a raised threshold, is flagged in the audit row (`only_one_active_administrator` or `below_threshold`) with the number told, and the administrators who count other than the maker are emailed (for a sole administrator a notice to themselves; accepted risk, ADR-043); default rule awaiting confirmation (issue #340) |

Registered so far: `collateral_release` (#13); `retail_sale_write_off` (#340, `retail.sales`, ADR-043: writing off the unpaid part of a credit sale, with the same open default as returns); `retail_purchase_order` (#198, `retail.purchasing`, ADR-033: sending a purchase order, with the same open default as returns); `retail_sale_return` and `retail_sale_return_void` (#201, `retail.sales`, ADR-033: unlike the lending actions an unset threshold means no checker, so the
action pends only at or above a threshold the tenant sets); `loan_disbursement`, `repayment_reversal` and
`loan_write_off` (#108, `lending.loans`, ADR-026); `investment_funding`, `investment_early_withdrawal`
and `investment_reversal` (#152, `lending.investments`, ADR-031). Increment 10 adds two checker
permissions, `lending.investments.fund_approve` and `lending.investments.reverse_approve` (migration
`V32`), granted like `lending.investments.early_withdraw_approve`; every other investment route
declares a permission already in the matrix, and the route permission test covers them. Increment 5 needed no new permission: every route
it adds declares a permission already in the matrix above (`lending.disbursements.request`,
`lending.repayments.create`, `lending.repayments.reverse_request`, `lending.loans.write_off_request`,
and `lending.loans.read` for the schedule, transactions and payoff quote), and the route permission
test covers them. The `seed-lending` command has no route and no permission: it is refused in
production and on any tenant holding data (`docs/runbooks/seed-lending.md`).

Savings (#151, `lending.savings`, ADR-032) registers `savings_withdrawal` (a closure is the same
action with `close` set, the threshold applying to the balance) and `savings_reversal`, added to the
table above. It needed no new permission: freeze, unfreeze and reactivation of an account are direct
actions with a reason that need `lending.savings.withdraw_approve`, the checker permission of
`savings_withdrawal`; the two savings reports need `lending.reports.members`. A withdrawal's checks
run again when the checker approves, on the account as it is then.

Action types are contributed by the modules that own them (ADR-015): each is a registered
`ApprovalAction` whose maker and checker permissions must equal its row above. Every
`/approvals` route declares `core.approvals.read`; approving and rejecting then need the action's
checker permission in the request's branch, and cancelling needs to be the maker. A pending
request expires 7 days after it was made (a nightly task marks it `expired`).

Resetting a user's second factor (FR-IAM-12) is not in this table on purpose: a tenant whose only
admin lost their phone has no second person who can sign in to check the reset (ADR-014).

Loan approval itself (`lending.loans.approve`) is the checker step of loan origination,
with the stricter rule that the approver is neither the submitter nor the appraiser.

A tenant with only one staff user cannot operate maker-checker actions. Tenant
onboarding requires at least two active staff users with the relevant permissions before
the lending module is switched on (runbook `docs/runbooks/`, onboarding a tenant).

## 8.5 Tenant isolation controls

- Row-level security on every tenant-owned table, forced, fail-loud predicate (ADR-003,
  chapter 6 section 6.3).
- Composite foreign keys on `(tenant_id, x_id)` make cross-tenant references impossible.
- The application's database role cannot bypass RLS and owns no tables; the process
  refuses to start otherwise.
- The token's tenant claim must equal the host's tenant.
- Object storage keys are prefixed `tenants/<tenant_id>/`, and a signed URL is issued only
  after a permission check on the owning record, never from a client-supplied key.
- In-process cache and rate-limit keys include the tenant id for every tenant-scoped value
  (permission cache, rate limits); there is no Redis (ADR-008). Revoked sessions are read from
  `auth_sessions`, which is under RLS like every tenant-owned table.
- Background jobs bind each tenant through `TenantJobs` before any query, one transaction per
  tenant (ADR-008).
- Actuator is locked down: every endpoint is disabled except `health` and `info`, which exist
  only on the internal management port; the public port serves `/healthz` and `/readyz` with
  status only (chapter 7 section 7.11.1).
- The isolation test suite (chapter 15 section 15.4) runs on every pull request.

## 8.6 Audit

- Every state-changing action writes an `audit_log` row in the same transaction
  (FR-AUD-01), with masked NIN and phone values (FR-AUD-05).
- Retail audit payloads carry no cost, cost total, loss, gain or purchase total (all at cost),
  because audit readers such as the auditor and branch manager do not hold `retail.profit.read`
  (#77, ADR-020 decision 10). They record facts at selling value, counts and ids (sale number and
  total, line counts, adjusted lines, payment method, supplier; a transfer's branches, date and
  line count); a price change records the sell price diff and its source only. Cost stays in the documents and journals, whose reads apply the
  permission.
- `audit_log`, `journal_entries`, `journal_lines` and the other append-only tables in
  chapter 6 section 6.2.4 reject UPDATE and DELETE for the application role.
- Platform operator actions go to `platform_audit_log`, which tenants cannot read and
  which survives tenant deletion.
- Security events (sign-in outcomes, lockouts, MFA enrolment, failed codes, recovery code use
  and replacement, MFA resets, invitation links shown, password reset requests, refusals, links shown and uses, refresh token reuse, permission denials
  on money-moving endpoints, role changes, deactivations) are audited (FR-AUD-03). Platform
  operators' sign-in events and tenant operations go to `platform_audit_log`.
- The auditor role has read access to the audit log and every report but no write
  permission anywhere.

## 8.7 Secrets and keys

| Secret | Where it lives | Rotation |
|---|---|---|
| Database passwords (`bms_owner`, `bms_app`, `bms_platform`) | Host env file, mode 600, owned by the deploy user | On staff change, and yearly |
| Access token signing key pair (`BMS_TOKEN_SIGNING_JWK`, an EC P-256 private JWK with its `kid`) | Host env file | Yearly; two keys valid during rotation (one is configured today) |
| Application data key (`BMS_DATA_KEY`, 32 bytes base64, and `BMS_DATA_KEY_ID`; encrypts TOTP secrets and any other field-level encrypted value) | Host env file | Key id stored with each ciphertext; re-encryption job on rotation |
| Object storage credentials | Host env file; bucket-scoped token | Yearly |
| SMS aggregator and payment gateway credentials | Host env file | Per provider policy |
| Web Push (ADR-050): `BMS_PUSH_VAPID_PRIVATE_KEY` (with `BMS_PUSH_VAPID_PUBLIC_KEY`, `BMS_PUSH_SUBJECT`, `BMS_PUSH_EXTRA_ENDPOINT_HOSTS`) | Host env file; optional, push is off when unset | Made once per environment with `npx web-push generate-vapid-keys`; rotating strands every device until its next staff page load |
| Outbox senders: `BMS_SMTP_PASSWORD` (with `BMS_SMTP_HOST`, `BMS_SMTP_PORT`, `BMS_SMTP_USER`, `BMS_MAIL_FROM`) and `BMS_TELEGRAM_BOT_TOKEN` (with `BMS_TELEGRAM_OPERATOR_CHAT_ID`) | Host env file; optional, a sender is off when unset | Per provider policy; the bot token through BotFather |
| The platform's own Pesapal pair (`BMS_PESAPAL_PLATFORM_*`) and its notification path token | Host env file (ADR-035) | Yearly, on staff change, on suspected exposure; the token whenever the IPN URL is re-registered |
| A lender's own Pesapal consumer key and secret | Table `payment_gateway_accounts`, AES-256-GCM sealed with `BMS_DATA_KEY` (the id of the key is inside the box); entered by the lender's administrator, write-only: no endpoint returns it, no log line, audit row or error carries it; the notification token is stored only as its SHA-256 (ADR-035 amendment of 2026-10-18) | When the lender rotates it at Pesapal (a new secret for the same key is allowed with payments in flight); data key rotation re-seals the rows and the second-factor seeds (`BMS_DATA_KEY_PREVIOUS`, runbook `payments.md` section 4a) |
| SMS provider key and callback token (`BMS_SMS_AT_API_KEY`, `BMS_SMS_AT_USERNAME`, `BMS_SMS_CALLBACK_TOKEN`, `BMS_SMS_ALLOWLIST`) | Host env file (ADR-036) | Yearly, on staff change |
| Language model provider key (`BMS_AI_API_KEY`, with `BMS_AI_BASE_URL`, `BMS_AI_MODEL`) | Host env file (ADR-037) | Yearly, on staff change |
| Backup encryption key | Host env file and an offline copy held by the dev lead | On staff change |

A lender's Pesapal consumer key (issue #309): the refused-key check and the cross-tenant uniqueness digest compare the key
lower cased (nothing recorded says whether Pesapal treats letter case as significant, so the two checks assume it may not; the
key sent to Pesapal and the sealed copy are exactly as pasted); an account whose key box cannot be opened counts as refused
(fails closed, never open); and intent creation reads the account row `FOR SHARE` so it cannot race a key change, which holds
the row `FOR UPDATE` (`docs/runbooks/payments.md` section 4c).

No secret is ever committed. `.env.example` lists every variable with a placeholder.
`java -jar bms-api.jar keys` prints a new signing key and data key for a host env file; outside
the dev and test profiles the API refuses to start without them (ADR-014).
GitHub Actions deploy secrets live in the `staging` and `production` environments
(`docs/sdd/09-infrastructure-design.md`, `docs/sdd/10-cicd-pipeline.md`).

## 8.8 Transport and web security

- TLS 1.2 or later on every host; HTTP redirects to HTTPS; HSTS with a one year max age.
- The SPA and the API are same-origin per tenant host, so CORS is not enabled. Callbacks
  on the callback host accept only server-to-server POSTs.
- Response headers: `Content-Security-Policy` (default-src 'self'; no inline scripts),
  `X-Content-Type-Options: nosniff`, `Referrer-Policy: same-origin`,
  `Permissions-Policy` denying camera, microphone and geolocation except where a screen
  needs the camera for document capture.
- The refresh cookie is `HttpOnly; Secure; SameSite=Strict; Path=/`, named with the `__Host-`
  prefix (ADR-018 finding M2), which removes the cross-site request forgery route to it and, since
  staging, production and the company site share one zone, stops a sibling host on that zone from
  setting a same-named cookie that would reach it.
- Uploads: allowed types JPEG, PNG, PDF; maximum 5 MB; type detected from content, not
  the file name; images are re-encoded to strip metadata (JPEG at quality 0.92, so small print
  stays legible); a canvas over 16 megapixels is refused before decoding (`image_too_large`), at most two images
  are re-encoded at once (a caller that waits 10 seconds gets 503 `uploads_busy`), the re-encoded
  bytes obey the same 5 MB limit, an object whose transaction is known to have rolled back is deleted again (an
  unknown outcome keeps it, and a sweeper for the remaining orphans is issue #39), and a member keeps at
  most 10 active documents per kind: a newer upload supersedes the oldest, which stays for the audit
  trail, so a wrong file can always be replaced, so a
  small compressed file cannot exhaust memory; files are stored in object storage, never on the
  application host's disk. Tomcat reads past an over-limit upload (`max-swallow-size` 8 MB) so
  the client gets the 413 body instead of a reset connection.
- The tenant logo (FR-TEN-08, `PUT /settings/logo`) has a narrower rule set on the same pipeline:
  PNG, JPEG or WebP by content only, at most 1 MB (checked from the multipart size before the bytes
  are read), at most 4 megapixels (checked before decoding, so a small file with a huge canvas cannot
  exhaust the heap), shorter side at least 128 px, long edge scaled to 512 px in halving steps, a
  JPEG's EXIF orientation applied before the metadata is dropped, always re-encoded (a WebP is decoded with a read-only ImageIO plugin and stored as
  PNG), so no metadata and no appended bytes survive. SVG is refused with 422 `svg_not_allowed`
  (text starting with or containing an `<svg` element, UTF-16 included), never re-encoded or
  stored; a GIF or anything else is 415. The tenant admin needs `core.settings.manage`; the image
  is a `core.tenant` document that `core.settings.read` staff holders may fetch through the signed
  URL route like any document (`TenantDocumentAccess`). The public route does not use `DocumentAccess`:
  the documents module offers a separate `publicAssetMeta` and `publicAssetBytes` that return a
  document only when its subject type is `core.tenant` and its subject is the current tenant, and an
  architecture test allows only `core.tenancy` to call them.
- Public endpoints for branding (`GET /branding`, `GET /branding/logo`) serve data that is public
  by design, resolved from the host only (never a parameter or a header in production; an unknown
  host is 404 `unknown_tenant`, so no tenant is revealed or confused). The logo route never
  serves bytes with a script-capable type: the content type is the stored, re-encoded value, only
  `image/png` and `image/jpeg` are ever sent (anything else is 404), and the response carries
  `X-Content-Type-Options: nosniff`, `Content-Disposition: inline`, `Content-Security-Policy:
  default-src 'none'; sandbox`, `Cache-Control: public, max-age=3600`, `Vary: Host` and an `ETag` that
  is the stored checksum, compared (weak tags, lists and `*` included) before any storage read: a
  conditional request costs the database only, and a logo is held in a small in-process cache (16
  entries, keyed by the immutable document id) so the 200 path rarely reads storage either. A storage
  failure is a 503 `logo_unavailable`, never a 500. These routes are unauthenticated, so the edge
  must rate limit them per client address (a Cloudflare rule on `/api/v1/branding*`, for example 60
  requests a minute), and the edge must never apply "Cache Everything" or ignore the query string
  or host for `/api/v1/branding*`. That a tenant exists is not a secret: sign-in pages already show it.
  `GET /branding` also lists the tenant's enabled module keys (#99): the landing page already shows
  which modules a tenant runs, so the keys add nothing secret; no counts, plan or limits are exposed.
- PDFs are stored as uploaded: only images are re-encoded, so a PDF's embedded JavaScript,
  launch actions or links are not removed. The control is how files are served: every signed
  download URL (R2 presign and the test fake alike) carries `Content-Disposition: attachment` with
  a server-generated name (`<doc_type>-<document id>.<ext>`), the sniffed content type and
  `Cache-Control: private, no-store`, so nothing renders inline from the storage origin and a PDF
  opens only in the reader the user chooses. Rejecting PDFs with `/JavaScript` or `/Launch` is a
  possible future tightening.
- When a server has no R2 settings at all, the application still starts: document upload and
  download answer 503 `storage_unavailable` until R2 is configured, so an automatic deploy to a
  host without a bucket cannot crash-loop the service. A partial R2 setting still fails startup.

## 8.9 Personal data handling

Detailed requirements are NFR-DP-01 to NFR-DP-08 in chapter 4. Design consequences:

- Data minimisation: the member form collects only the fields in FR-MEM-01; free-text
  fields carry a hint not to record health or other special personal data.
- NIN, phone and email are masked in lists, logs and audit payloads. The masked audit keys
  (FR-AUD-05) are `national_id`, `other_id_number`, `phone`, `phone_e164`, `alt_phone_e164`,
  `payer_phone_e164`, `contact_phone`, `contact_phone_e164`, `login`, `email`, `contact_email` and
  `recipient`; a masked value keeps its last 4 characters. Email joined the list with ADR-024, so
  the `email` of a `core.user.invited` row is masked from then on.
- Applications (ADR-024) hold a business contact's name, email and phone. Only platform operators
  read them; the applicant page shows none of them; `rejected` and `expired` applications are
  deleted 90 days after they closed (FR-ONB-09). An outbox row keeps its parameters, which can hold
  a one-time link, until it is sent or, if it is never sent (a sender off, or three failures), at
  most 7 days, the longest link lifetime, and never past the link's own expiry (72 hours for an
  activation link): such a row is not sent, the nightly purge clears its parameters, and "send
  again" is refused. The links are therefore readable in the database (and in a backup)
  while a row waits, which is the cost of sending later; they expire on their own (72 hours for an
  activation, 7 days for an applicant link). The portal shows a masked recipient and an error
  class or a sender's own safe message, never a text. A Telegram bot token that is not in the
  token shape switches the sender off, so it cannot reach an error message. Senders log the row
  id, channel and template only.
- Client extras (issue #210). **Payments:** the payer's phone number is held on the intent and masked everywhere else; a
  notification stores identifiers only; the status response's masked payer account is kept as the provider gives it; no
  card data ever reaches the platform (the hosted page takes it). **SMS:** the delivery log keeps the recipient masked to
  the last four digits plus a keyed hash, never the message text; the platform outbox row keeps the full number and the
  parameters only until sent (ADR-024) and the number until the 30 day purge; an opted-out member is never texted for
  reminders. **AI:** only aggregates and aliases for branches and products leave the platform, with groups under five suppressed and no officer dimension; the call log holds no personal data by construction
  and by test; the tenant acknowledges what is sent and where (ADR-037; spec section 5.11 lists the Data Protection and
  Privacy Act considerations). **Reports:** files with member names and balances are deleted after 30 days; downloads
  are audited.
- Staff free text on a loan (purpose text, return, rejection and cancellation notes, visit notes)
  can hold personal data about the member or third parties. It is staff-only: the member area
  never returns it.
- Application logs never contain request bodies of member endpoints, NINs, phone numbers,
  tokens or passwords. On staging and production (the `server` profile) every string of every JSON log line (message,
  exception text and stack trace, MDC values) passes through `kernel.LogRedaction`, named as
  `logging.structured.json.customizer` (NFR-SEC-08, ADR-059, issue #426): the value after a secret key, alone or ending
  a longer name (`token=`, `resetToken=`, `"newPassword":`, `client_secret=`, `x-api-key:`, `pin=`), a quoted value to
  its closing quote, the credential after `Authorization:`, `Bearer` or `Basic`, signed tokens, emails, NINs in any
  case, Uganda mobile and landline numbers (spaced, dashed, `+`, `%2B` or 0) and any standalone run of 9 to 15 digits
  that is not a labelled amount, time, count or id (`amount=`, `_minor=`, `id=`) or an epoch or compact timestamp, and
  the value after a name key (`name=`, `full_name=`, `customerName=`, a record's `Customer[name=...]`) become
  `[redacted]`, `[email]`, `[nin]`, `[phone]` or `[name]`. Every pattern is linear in the line's length.
  `LogRedactionTest` runs Spring Boot's ECS encoder with it. A name in free text has no key to find, so code still never
  logs one. PostgreSQL logs tersely (`log_error_verbosity=terse`), so a refused duplicate's `DETAIL` with its key value
  never reaches its log. Container logs are capped and rotated by Docker (chapter 9).
- Test and development environments never hold production data. Fixtures are fabricated
  (chapter 15 section 15.8).

## 8.10 Threats considered

| Threat | Control |
|---|---|
| A bug omits the tenant filter | RLS fails the query; isolation tests |
| A staff user moves money alone | Maker-checker, thresholds, audit |
| A staff user edits history to hide theft | Append-only tables, reversal-only corrections, audit |
| Credential stuffing on staff sign-in | The five-failure lock in the database for every address (an address of nobody exactly as an account, across restarts), failed requests limited per IP, second-factor attempts per account, MFA for admins (ADR-059) |
| A stranger keeping an owner locked out | Accepted by design (the lock is what stops guessing); the owner's way back is a password reset, which lifts the lock, a tenant admin's or the operator's audited unlock (ADR-059) |
| An administrator who leaves on bad terms locks the owner out (deactivates or demotes them, changes their email, or adds administrators of their own) | One Owner per business whom nobody inside the business can remove, deactivate, demote or change, and who alone adds, removes or changes administrators, held in the database too; the Owner hands over by a transfer with a step-up; an operator rescue with a reason for a lost Owner (ADR-062) |
| A flood of sign-ins exhausting the API's memory or the database pool | At most four argon2id hashes at once, 503 `sign_in_busy` with Retry-After beyond, and no connection held while a request waits for or runs a hash (ADR-059) |
| The forgotten-password form used to learn which staff have accounts, to flood a mailbox, or to take an account over | One generic answer with a time floor, limits per address and per account, a hashed single-use one hour token sent only to the email on file, every session ended on use, the second factor untouched and the failed-attempt lock lifted (ADR-059); the threat model is section 8.2a and ADR-042 |
| SIM swap used to take over a member portal | PIN plus OTP only at activation and reset; SMS notice to the member on PIN reset; staff can revoke portal access |
| Forged payment callback | Signature verification plus status query before booking (FR-PAY-02) |
| Replayed payment callback | Idempotency on the provider reference (FR-PAY-03) |
| Leaked backup | Backups encrypted before upload; private bucket; 30-day retention |
| Enumeration of member records across the member portal | Ownership check returns 404, UUIDs not sequential |
| Sign-up flood or a stranger consuming the shared host | Rate limits, honeypot, email confirmation, operator verification before anything is provisioned (ADR-024) |
| Sign-up used to learn which emails are customers | Same answer for known and unknown emails; a known email gets the link by email only |
| A leaked activation or applicant link | Hashed at rest in its own table, single purpose, 72 hours (activation) or 7 days (applicant), in the URL fragment only; in the outbox only until sent, at most 7 days |
| The sign-up form used to send attacker-written text (phishing relay) | The confirmation email carries only the server's reference and link; volume bounded per mailbox and globally in the database |
| A tenant admin switches on collections or the assistant ahead of the operator | Collections need the tenant's own verified payment account, with no shared merchant to receive, and the assistant needs the platform allow-list (ADR-035 and its 2026-10-18 amendment, ADR-037) |
| A lender's money reaches the platform's account, or one lender's credentials or notifications reach another lender | A gateway call names an account: the platform's carries no tenant and is refused for any purpose that is not a platform purpose, in code and by a database CHECK; a tenant's credentials are read only for the tenant bound to the request, under forced row-level security; a notification address selects one lender and can match only its own orders (ADR-035 amendment of 2026-10-18; `AccountGuardTest`, `PaymentGatewayIT`) |
| A phished session or a manager redirects repayments to a personal merchant account | A dedicated permission of the tenant admin only, a second-factor code on save and remove, the platform's key and any key another tenant uses refused, an audit row and an email to the other administrators for every change (ADR-035 amendment, review of 2026-10-18) |
| A lender's stored provider secret is read from the database, a backup or a response | Sealed with the data key, never selected by an API, never logged or audited; a response carries only the last four characters of the key; the notification token is stored as a digest |
| Forged or replayed Pesapal notification | The notification carries no authority: only an authenticated status query books, once per provider reference (ADR-035, spec section 3.13) |
| Staging sends real SMS or prompts a real payer | SMS sink by default and `live` refused on staging; the Pesapal live URL refused on staging; sandbox accounts only (ADR-036, ADR-035) |
| Personal data reaches the language model provider | Typed aggregate facts and aliases only, question scrub, leak tests, tenant acknowledgement, off by default (ADR-037, spec section 5.12) |
| The model states a number that is not in the data | Cited-fact validation in code, fallback to the deterministic brief, advisory label (ADR-037) |
| A report run exposes another tenant's data or a formula in a name executes in Excel | Row-level security in the run transaction, explicit string cells for dangerous leading characters (ADR-038) |
| A tenant admin sends under a name that is not its own, or another business's name | The sender id is chosen from the operator's allow-list, one tenant per id at a time, checked at save and again on every message (ADR-036 second amendment) |
| A tenant admin raises its own SMS cap, or sets 0 and gets the default | A cap can only be lowered under the operator's cap (422 `above_ceiling`), the lower of the stored cap and the ceiling applies to every message, and 0 is stored and enforced as stop |
| A tenant's SMS wording runs code, spoofs a sender with hidden characters or costs five messages each | A placeholder language with a whitelist and no expressions, invisible and direction characters refused, a worst-case segment limit, one-pass rendering of plain text (ADR-036 second amendment) |
| The test message form is used to text a real person | Sink or the environment's allow-list only, never in a live mode, a fresh sink for any unlisted number, ten an hour, audited with a masked number |

## 8.11 Open items

- Whether the pilot tenant's licence conditions require any control beyond these (for
  example a specific audit log retention); tracked in `docs/specs/lending-mvp-scope.md`.
- Whether to add field-level encryption for NIN at rest beyond disk and backup
  encryption; decision deferred until the hosting data-protection review in NFR-DP-07.
