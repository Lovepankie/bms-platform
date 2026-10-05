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
- One-time codes, invitation and setup tokens, recovery codes and refresh tokens are stored as
  SHA-256 hashes only.
- TOTP secrets are encrypted at rest with the application data key (8.7). TOTP is RFC 6238
  (HMAC-SHA1, 6 digits, 30 seconds); one step of drift is accepted and each code works once.
  Enrolment is forced at first sign-in for roles that require it (`roles.mfa_required`: the
  tenant admin) and for platform operators, and for all staff when the tenant setting
  `require_mfa_all_staff` is on (FR-IAM-06).
- **Recovery codes** (FR-IAM-11): enrolment issues ten single-use codes, shown once. A code is
  accepted in place of a TOTP code; the user can replace all of them with a current TOTP code.
  Failed codes count toward the lockout.
- **Lost second factor** (FR-IAM-12): another tenant admin resets it (`POST
  /users/{user_id}/mfa/reset`, `core.users.manage`); a tenant admin cannot reset their own. For a
  tenant whose only admin is locked out, a platform operator resets that admin through the
  platform API. A reset clears the secret and the codes, ends every session of the user, and is
  audited. It is deliberately not a maker-checker action (section 8.4, ADR-014).
- **Invitations** (FR-IAM-01): a 256 bit one-time token, valid 72 hours, in the link's fragment so
  it never reaches a server log. The link is shown once to the inviting admin (audited as
  `core.invitation.link_revealed`, without the token) and sent through the notification port;
  until a provider is chosen that port records messages only, so no flow waits for one.
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

## 8.3 Role-based access control

### 8.3.1 Roles

| Role | Kind | Typical holder | Branch scope |
|---|---|---|---|
| Super admin | platform | Platform operator (not a tenant user) | n/a |
| Tenant admin | staff | Owner or general manager of the tenant | All branches |
| Branch manager | staff | Manager of one or more branches | Assigned branches |
| Loan officer | staff | Field or desk officer | Assigned branches |
| Cashier / teller | staff | Handles cash and mobile money in and out | Assigned branches |
| Accountant | staff | Books, reconciliation, period close | Usually all branches |
| Auditor | staff | Internal or external auditor, read only | Usually all branches |
| Member | member | Borrower, saver, investor | Own records only |

A user may hold several roles, each with its own branch scope. The effective permission
set is the union; the branch scope applies per role (a user who is branch manager at A
and loan officer at B approves loans only at A).

### 8.3.2 Permission matrix

`Y` = granted. Blank = not granted. TA tenant admin, BM branch manager, LO loan officer,
CA cashier, AC accountant, AU auditor, ME member. Super admin acts only through the
platform API and holds no tenant permissions.

| Permission | TA | BM | LO | CA | AC | AU | ME |
|---|---|---|---|---|---|---|---|
| `core.settings.read` | Y | Y | | | Y | Y | |
| `core.settings.manage` | Y | | | | | | |
| `core.branches.read` | Y | Y | Y | Y | Y | Y | |
| `core.branches.manage` | Y | | | | | | |
| `core.users.read` | Y | Y | | | | Y | |
| `core.users.manage` | Y | | | | | | |
| `core.audit.read` | Y | Y | | | Y | Y | |
| `core.audit.export` | Y | | | | | Y | |
| `core.approvals.read` | Y | Y | Y | Y | Y | Y | |
| `core.ledger.read` | Y | Y | | | Y | Y | |
| `core.ledger_accounts.manage` | Y | | | | Y | | |
| `core.journals.create` | Y | | | | Y | | |
| `core.journals.approve` | Y | | | | Y | | |
| `core.periods.close` | Y | | | | Y | | |
| `core.periods.approve_close` | Y | | | | Y | | |
| `core.payment_methods.manage` | Y | | | | Y | | |
| `core.notifications.read` | Y | Y | | | | Y | |
| `core.notification_templates.manage` | Y | | | | | | |
| `core.imports.manage` | Y | | | | Y | | |
| `core.imports.approve_commit` | Y | | | | Y | | |
| `core.payments.read` | Y | Y | | Y | Y | Y | |
| `core.payments.collect` | Y | Y | | Y | | | |
| `core.payments.allocate` | Y | | | Y | Y | | |
| `core.reports.financial` | Y | Y | | | Y | Y | |
| `core.reports.audit` | Y | | | | | Y | |
| `lending.members.read` | Y | Y | Y | Y | Y | Y | |
| `lending.members.create` | Y | Y | Y | | | | |
| `lending.members.update` | Y | Y | Y | | | | |
| `lending.members.verify_kyc` | Y | Y | | | | | |
| `lending.members.blacklist` | Y | Y | | | | | |
| `lending.members.transfer_approve` | Y | | | | | | |
| `lending.products.read` | Y | Y | Y | Y | Y | Y | |
| `lending.products.manage` | Y | | | | | | |
| `lending.loans.read` | Y | Y | Y | Y | Y | Y | |
| `lending.loans.create` | Y | Y | Y | | | | |
| `lending.loans.appraise` | Y | Y | Y | | | | |
| `lending.loans.approve` | Y | Y | | | | | |
| `lending.loans.cancel` | Y | Y | Y | | | | |
| `lending.disbursements.request` | Y | Y | | Y | | | |
| `lending.disbursements.authorise` | Y | Y | | | Y | | |
| `lending.repayments.create` | Y | Y | | Y | | | |
| `lending.repayments.reverse_request` | Y | Y | | Y | Y | | |
| `lending.repayments.reverse_approve` | Y | Y | | | Y | | |
| `lending.credits.refund_approve` | Y | Y | | | Y | | |
| `lending.charges.waive_request` | Y | Y | | | Y | | |
| `lending.charges.waive_approve` | Y | Y | | | Y | | |
| `lending.loans.write_off_request` | Y | | | | Y | | |
| `lending.loans.write_off_approve` | Y | | | | | | |
| `lending.loans.restructure_request` | Y | Y | | | | | |
| `lending.loans.restructure_approve` | Y | | | | Y | | |
| `lending.collateral.read` | Y | Y | Y | Y | Y | Y | |
| `lending.collateral.manage` | Y | Y | Y | | | | |
| `lending.collateral.release_request` | Y | Y | Y | | | | |
| `lending.collateral.release_approve` | Y | Y | | | | | |
| `lending.savings.read` | Y | Y | Y | Y | Y | Y | |
| `lending.savings.open` | Y | Y | Y | | | | |
| `lending.savings.deposit` | Y | Y | | Y | | | |
| `lending.savings.withdraw` | Y | Y | | Y | | | |
| `lending.savings.withdraw_approve` | Y | Y | | | Y | | |
| `lending.savings_products.manage` | Y | | | | | | |
| `lending.investments.read` | Y | Y | Y | Y | Y | Y | |
| `lending.investments.open` | Y | Y | Y | | | | |
| `lending.investments.fund` | Y | Y | | Y | | | |
| `lending.investments.payout` | Y | Y | | Y | | | |
| `lending.investments.early_withdraw_approve` | Y | Y | | | Y | | |
| `lending.investment_products.manage` | Y | | | | | | |
| `lending.collections.read` | Y | Y | Y | Y | Y | Y | |
| `lending.collections.log_action` | Y | Y | Y | Y | | | |
| `lending.collections.assign` | Y | Y | | | | | |
| `lending.reports.portfolio` | Y | Y | | | Y | Y | |
| `lending.reports.collections` | Y | Y | Y | Y | Y | Y | |
| `lending.reports.members` | Y | Y | | | | Y | |
| `lending.reports.compliance` | Y | | | | Y | Y | |
| `member.self.read` | | | | | | | Y |
| `member.self.apply` | | | | | | | Y |
| `member.self.pay` | | | | | | | Y |

Notes:

- A loan officer running `lending.reports.collections` sees only loans where they are
  the responsible officer; branch managers and above see their branch scope.
- Branch managers approve loans, but never one they submitted or appraised (FR-APR-03).
- Member ID images (`id_front`, `id_back`) need `lending.members.verify_kyc` in the member's
  branch, so tenant admins and branch managers, the people who verify KYC; photos and other member
  documents need `lending.members.read`. Cashiers and auditors get 403 on ID images. Every
  download URL issued is audited with the user, the member and the document, and so is every
  denied attempt (`core.document.access_denied`, written in its own transaction). The member's
  document list shows ID images only to those who may open them, and a document with no known
  kind is denied (fail closed). If auditors need the
  images later, a read-only audit permission is added then (#29, decided by the dev lead).
- Write-off approval is deliberately restricted to the tenant admin, the most senior
  role, because write-off removes an asset from the books.
- The matrix is seeded by migration V2 and `PermissionMatrixIT` asserts the seeded
  `role_permissions` equal this table, read from this file (FR-IAM-02). Changing a cell is a
  pull request that changes this chapter and a new migration together.

Platform permissions, held only by platform operators (super admins) on the platform host,
never by a tenant role:

| Permission | Grants |
|---|---|
| `platform.tenants.read` | List plans and tenants, read one tenant |
| `platform.tenants.manage` | Create tenants, switch modules, move subscriptions, suspend and resume, reset a tenant admin's second factor |

### 8.3.3 Enforcement

- Every route declares exactly one permission (`@RequiresPermission`), or is explicitly marked
  public (`@PublicEndpoint`), or open to any signed-in principal of one kind
  (`@AuthenticatedEndpoint`, for `/me`, sign-out and the caller's own recovery codes). A route
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
| `investment_early_withdrawal` | `lending.investments.payout` | `lending.investments.early_withdraw_approve` | No |
| `collateral_release` | `lending.collateral.release_request` | `lending.collateral.release_approve` | No |
| `member_branch_transfer` | `lending.members.update` | `lending.members.transfer_approve` | No |
| `import_commit` | `core.imports.manage` | `core.imports.approve_commit` | No |
| `member_credit_refund` | `lending.repayments.create` | `lending.credits.refund_approve` | Yes |

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
- `audit_log`, `journal_entries`, `journal_lines` and the other append-only tables in
  chapter 6 section 6.2.4 reject UPDATE and DELETE for the application role.
- Platform operator actions go to `platform_audit_log`, which tenants cannot read and
  which survives tenant deletion.
- Security events (sign-in outcomes, lockouts, MFA enrolment, failed codes, recovery code use
  and replacement, MFA resets, invitation links shown, refresh token reuse, permission denials
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
| Backup encryption key | Host env file and an offline copy held by the dev lead | On staff change |

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
- NIN and phone are masked in lists, logs and audit payloads.
- Staff free text on a loan (purpose text, return, rejection and cancellation notes, visit notes)
  can hold personal data about the member or third parties. It is staff-only: the member area
  never returns it.
- Application logs never contain request bodies of member endpoints, NINs, phone numbers,
  tokens or passwords; the logger has a redaction filter with a unit test.
- Test and development environments never hold production data. Fixtures are fabricated
  (chapter 15 section 15.8).

## 8.10 Threats considered

| Threat | Control |
|---|---|
| A bug omits the tenant filter | RLS fails the query; isolation tests |
| A staff user moves money alone | Maker-checker, thresholds, audit |
| A staff user edits history to hide theft | Append-only tables, reversal-only corrections, audit |
| Credential stuffing on staff sign-in | Lockout, rate limits, MFA for admins |
| SIM swap used to take over a member portal | PIN plus OTP only at activation and reset; SMS notice to the member on PIN reset; staff can revoke portal access |
| Forged payment callback | Signature verification plus status query before booking (FR-PAY-02) |
| Replayed payment callback | Idempotency on the provider reference (FR-PAY-03) |
| Leaked backup | Backups encrypted before upload; private bucket; 30-day retention |
| Enumeration of member records across the member portal | Ownership check returns 404, UUIDs not sequential |

## 8.11 Open items

- Whether the pilot tenant's licence conditions require any control beyond these (for
  example a specific audit log retention); tracked in `docs/specs/lending-mvp-scope.md`.
- Whether to add field-level encryption for NIN at rest beyond disk and backup
  encryption; decision deferred until the hosting data-protection review in NFR-DP-07.
