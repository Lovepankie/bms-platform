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
| Sales | staff | Retail shop staff: sells, reports usage, reads stock (ADR-020) | Assigned branches |

A user may hold several roles, each with its own branch scope. The effective permission
set is the union; the branch scope applies per role (a user who is branch manager at A
and loan officer at B approves loans only at A).

### 8.3.2 Permission matrix

`Y` = granted. Blank = not granted. TA tenant admin, BM branch manager, LO loan officer,
CA cashier, AC accountant, AU auditor, ME member, SA sales (the retail shop role, ADR-020). Super admin acts only through the
platform API and holds no tenant permissions.

| Permission | TA | BM | LO | CA | AC | AU | ME | SA |
|---|---|---|---|---|---|---|---|---|
| `core.settings.read` | Y | Y | | | Y | Y | | |
| `core.settings.manage` | Y | | | | | | | |
| `core.branches.read` | Y | Y | Y | Y | Y | Y | | |
| `core.branches.manage` | Y | | | | | | | |
| `core.users.read` | Y | Y | | | | Y | | |
| `core.users.manage` | Y | | | | | | | |
| `core.audit.read` | Y | Y | | | Y | Y | | |
| `core.audit.export` | Y | | | | | Y | | |
| `core.approvals.read` | Y | Y | Y | Y | Y | Y | | |
| `core.ledger.read` | Y | Y | | | Y | Y | | |
| `core.ledger_accounts.manage` | Y | | | | Y | | | |
| `core.journals.create` | Y | | | | Y | | | |
| `core.journals.approve` | Y | | | | Y | | | |
| `core.periods.close` | Y | | | | Y | | | |
| `core.periods.approve_close` | Y | | | | Y | | | |
| `core.payment_methods.manage` | Y | | | | Y | | | |
| `core.notifications.read` | Y | Y | | | | Y | | |
| `core.notification_templates.manage` | Y | | | | | | | |
| `core.imports.manage` | Y | | | | Y | | | |
| `core.imports.approve_commit` | Y | | | | Y | | | |
| `core.payments.read` | Y | Y | | Y | Y | Y | | |
| `core.payments.collect` | Y | Y | | Y | | | | |
| `core.payments.allocate` | Y | | | Y | Y | | | |
| `core.reports.financial` | Y | Y | | | Y | Y | | |
| `core.reports.audit` | Y | | | | | Y | | |
| `lending.members.read` | Y | Y | Y | Y | Y | Y | | |
| `lending.members.create` | Y | Y | Y | | | | | |
| `lending.members.update` | Y | Y | Y | | | | | |
| `lending.members.verify_kyc` | Y | Y | | | | | | |
| `lending.members.blacklist` | Y | Y | | | | | | |
| `lending.members.transfer_approve` | Y | | | | | | | |
| `lending.products.read` | Y | Y | Y | Y | Y | Y | | |
| `lending.products.manage` | Y | | | | | | | |
| `lending.loans.read` | Y | Y | Y | Y | Y | Y | | |
| `lending.loans.create` | Y | Y | Y | | | | | |
| `lending.loans.appraise` | Y | Y | Y | | | | | |
| `lending.loans.approve` | Y | Y | | | | | | |
| `lending.loans.cancel` | Y | Y | Y | | | | | |
| `lending.disbursements.request` | Y | Y | | Y | | | | |
| `lending.disbursements.authorise` | Y | Y | | | Y | | | |
| `lending.repayments.create` | Y | Y | | Y | | | | |
| `lending.repayments.reverse_request` | Y | Y | | Y | Y | | | |
| `lending.repayments.reverse_approve` | Y | Y | | | Y | | | |
| `lending.credits.refund_approve` | Y | Y | | | Y | | | |
| `lending.charges.waive_request` | Y | Y | | | Y | | | |
| `lending.charges.waive_approve` | Y | Y | | | Y | | | |
| `lending.loans.write_off_request` | Y | | | | Y | | | |
| `lending.loans.write_off_approve` | Y | | | | | | | |
| `lending.loans.restructure_request` | Y | Y | | | | | | |
| `lending.loans.restructure_approve` | Y | | | | Y | | | |
| `lending.collateral.read` | Y | Y | Y | Y | Y | Y | | |
| `lending.collateral.manage` | Y | Y | Y | | | | | |
| `lending.collateral.release_request` | Y | Y | Y | | | | | |
| `lending.collateral.release_approve` | Y | Y | | | | | | |
| `lending.savings.read` | Y | Y | Y | Y | Y | Y | | |
| `lending.savings.open` | Y | Y | Y | | | | | |
| `lending.savings.deposit` | Y | Y | | Y | | | | |
| `lending.savings.withdraw` | Y | Y | | Y | | | | |
| `lending.savings.withdraw_approve` | Y | Y | | | Y | | | |
| `lending.savings_products.manage` | Y | | | | | | | |
| `lending.investments.read` | Y | Y | Y | Y | Y | Y | | |
| `lending.investments.open` | Y | Y | Y | | | | | |
| `lending.investments.fund` | Y | Y | | Y | | | | |
| `lending.investments.payout` | Y | Y | | Y | | | | |
| `lending.investments.early_withdraw_approve` | Y | Y | | | Y | | | |
| `lending.investment_products.manage` | Y | | | | | | | |
| `lending.collections.read` | Y | Y | Y | Y | Y | Y | | |
| `lending.collections.log_action` | Y | Y | Y | Y | | | | |
| `lending.collections.assign` | Y | Y | | | | | | |
| `lending.reports.portfolio` | Y | Y | | | Y | Y | | |
| `lending.reports.collections` | Y | Y | Y | Y | Y | Y | | |
| `lending.reports.members` | Y | Y | | | | Y | | |
| `lending.reports.compliance` | Y | | | | Y | Y | | |
| `member.self.read` | | | | | | | Y | |
| `member.self.apply` | | | | | | | Y | |
| `member.self.pay` | | | | | | | Y | |
| `retail.catalogue.manage` | Y | | | | | | | |
| `retail.price.edit` | Y | | | | | | | |
| `retail.price.below_cost` | | | | | | | | |
| `retail.customer.manage` | Y | | | | | | | Y |
| `retail.sale.create` | Y | | | | | | | Y |
| `retail.sale.read` | Y | | | | | | | Y |
| `retail.sale.void` | Y | | | | | | | |
| `retail.stock.read` | Y | | | | | | | Y |
| `retail.stock.transfer` | Y | | | | | | | |
| `retail.stocktake.commit` | Y | | | | | | | |
| `retail.purchase.create` | Y | | | | | | | |
| `retail.usage.report` | Y | | | | | | | Y |
| `retail.profit.read` | Y | | | | | | | |

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
- The retail CSV product import (#146) is for administrators only: the route declares `retail.catalogue.manage` and the service also requires `core.settings.manage`, which only the tenant admin holds, so a custom role granted the catalogue permission alone cannot run it; its cost column needs `retail.profit.read`.
- Retail (ADR-020): the admin role is the tenant admin, who holds every `retail.*` permission
  except `retail.price.below_cost`; the sales role (`retail_sales`) holds sale create and read,
  stock read, usage report and customer manage, scoped to its assigned branches. Lending roles hold no retail permission. Cost, cost
  snapshot, valuation at cost and profit fields are omitted from responses (absent, not null)
  unless the caller holds `retail.profit.read`, and the profit routes refuse without it. On a
  branch-bound row the permission must cover that row's branch (`principal.may`, ADR-017), so a
  user with profit read at branch A only sees no cost on branch B's stock, movements, sales or
  valuation (review F5); tenant-wide products and price history need it in any branch. The sales
  role holds no `core.*` permission; the PWA reads the user's branches from `/me`.
- Retail cash book (ADR-022, **proposed**, FR-RET-32): the ten proposed `retail.cashbook.*`,
  `retail.savings.*`, `retail.banking.record`, `retail.expense.*`, `retail.withdrawal.record` and
  `retail.advance.*` permissions are listed in the separate table "Proposed cash book permissions"
  below this list, **not** in the matrix above, because they are not seeded: `PermissionMatrixIT`
  parses every row of the matrix above that has a backticked three-part key and eight cells and
  compares it with `role_permissions`, so a proposed row there would fail the build on `main`. The
  cash book migration moves the ten rows into the matrix in the same pull request that seeds them.
  The tenant admin holds all ten. The sales role holds read, savings record, banking record and
  expense record for its assigned branches, as the pilot does; it does **not** hold
  `retail.savings.overwrite` by default, which is a default pending the Owner's answer to open
  question 5, not a decision. Withdrawals and advances (both ways) are owner or admin only, a
  deliberate change from the pilot, where every signed-in user could record advances and their
  payments (open question 6). One rule covers every profit-derived figure: the day's profit, the
  savings suggestion, `suggested_minor` and **every response field that carries a savings amount**
  need `retail.profit.read` and are absent, not null, without it (a savings amount is half the
  profit, so it leaks it), and so does `cash_purchases_minor` and every figure embedding it, because a restock total is a purchase total at cost (a caller without `retail.profit.read`, the sales role included, is shown `cash_expected_minor` only: takings less voids, expenses and advances paid out, plus repayments, before cash purchases and savings; ADR-022 decision 13, relaxable by the Owner, open question 8); a caller without `retail.profit.read` cannot send a savings amount at all (422 `amount_requires_profit_access`) and never sees `overwritten`; the audit payload of a savings record carries neither the amount nor
  the suggestion (FR-RET-19, ADR-022 decision 12). Voiding needs `retail.cashbook.void`. No cash
  book approval action is registered (open question 3); if one is, its row joins section 8.4 with a
  threshold set first.
- `retail.stock.transfer` (issue #84, migration V22, FR-RET-16) moves stock from one branch to
  another. It is money-moving (it posts an inventory entry at each branch) and goes to the roles
  that restock, which in the default roles is the tenant admin only. Its branch scope is checked
  on the source branch, for the transfer and for its void; the destination may be any active
  branch of the tenant. Listing and reading transfers needs `retail.stock.read` in the source or
  the destination branch, and the cost fields need `retail.profit.read` in either of them, because
  the same cost is posted to both branches' inventory accounts.
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

Proposed cash book permissions (ADR-022, not yet seeded, so deliberately outside the matrix
above and not read by `PermissionMatrixIT`; this table has four columns, not eight):

| Permission (proposed) | Tenant admin | Sales role, default | Note |
|---|---|---|---|
| `retail.cashbook.read` | Y | Y | Cash book lists and the daily cash summary, branch scoped |
| `retail.savings.record` | Y | Y | Record the day's savings |
| `retail.savings.overwrite` | Y | no | Pending open question 5; the sales role does not hold it until the Owner answers |
| `retail.banking.record` | Y | Y | Record cash banked |
| `retail.expense.record` | Y | Y | Record an expense |
| `retail.expense.manage` | Y | no | Categories, items and parties |
| `retail.withdrawal.record` | Y | no | Owner or admin only |
| `retail.advance.create` | Y | no | Owner or admin only (open question 6) |
| `retail.advance.repay` | Y | no | Owner or admin only (open question 6) |
| `retail.cashbook.void` | Y | no | Void any cash book record |

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
| Outbox senders: `BMS_SMTP_PASSWORD` (with `BMS_SMTP_HOST`, `BMS_SMTP_PORT`, `BMS_SMTP_USER`, `BMS_MAIL_FROM`) and `BMS_TELEGRAM_BOT_TOKEN` (with `BMS_TELEGRAM_OPERATOR_CHAT_ID`) | Host env file; optional, a sender is off when unset | Per provider policy; the bot token through BotFather |
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
| Sign-up flood or a stranger consuming the shared host | Rate limits, honeypot, email confirmation, operator verification before anything is provisioned (ADR-024) |
| Sign-up used to learn which emails are customers | Same answer for known and unknown emails; a known email gets the link by email only |
| A leaked activation or applicant link | Hashed at rest in its own table, single purpose, 72 hours (activation) or 7 days (applicant), in the URL fragment only; in the outbox only until sent, at most 7 days |
| The sign-up form used to send attacker-written text (phishing relay) | The confirmation email carries only the server's reference and link; volume bounded per mailbox and globally in the database |

## 8.11 Open items

- Whether the pilot tenant's licence conditions require any control beyond these (for
  example a specific audit log retention); tracked in `docs/specs/lending-mvp-scope.md`.
- Whether to add field-level encryption for NIN at rest beyond disk and backup
  encryption; decision deferred until the hosting data-protection review in NFR-DP-07.
