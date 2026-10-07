# 3. Functional Requirements

**Status:** Draft · **Owner:** Hillary

## 3.1 Purpose and how to read this chapter

This chapter is the build contract for the platform core and the lending vertical. Each
requirement states one testable behaviour and its acceptance criteria. A developer (or a
coding agent) implements a requirement, writes the tests that prove its acceptance
criteria, and cites the requirement ID in the pull request.

**IDs.** Every requirement has an ID `FR-<AREA>-NN`. Once this chapter is accepted an ID
is stable: a retired requirement keeps its ID, marked Retired, and the ID is not reused.
A letter suffix (`FR-REP-04a`) splits one statement into separately testable parts.

| Area | Meaning | Section |
|---|---|---|
| TEN | Tenancy, plans, subscriptions, module switching | 3.5 |
| BR | Branches and branch context | 3.6 |
| IAM | Users, roles, permissions, authentication | 3.7 |
| AUD | Audit log | 3.8 |
| APR | Maker-checker approvals | 3.9 |
| GL | General ledger | 3.10 |
| NTF | Notifications (SMS, email) | 3.11 |
| DOC | Document generation | 3.12 |
| RPT | Reporting framework | 3.13 |
| IMP | Import framework | 3.14 |
| PAY | Payment intake from gateways | 3.15 |
| MEM | Members, KYC, next of kin, relationships | 3.16 |
| PRD | Loan products | 3.17 |
| ORG | Loan origination: application, appraisal, approval | 3.18 |
| DIS | Disbursement and repayment schedule | 3.19 |
| REP | Repayments | 3.20 |
| ARR | Arrears and penalties | 3.21 |
| LCL | Loan closure, write-off, restructure | 3.22 |
| COL | Collateral register | 3.23 |
| SAV | Savings | 3.24 |
| INV | Investments | 3.25 |
| CLN | Collections | 3.26 |
| MSS | Member self-service | 3.27 |
| RET | Retail vertical: catalogue, stock, sales, purchasing, valuation (ADR-020) and the cash book (ADR-022, proposed) | 3.28 |
| ONB | Self-onboarding: sign-up, applications, operator activation (ADR-024) | 3.29 |

**Phase column.** `MVP` is in the first release for the pilot tenant
(`docs/specs/lending-mvp-scope.md` orders them). `P2` is planned after the MVP. `Later`
is recorded so the design does not preclude it, and is not scheduled. `Cash book` is the retail cash
book increment (ADR-022, proposed), built before the retail pilot cutover.

**Normative calculation rules** (schedules, allocation, days past due, penalties,
payoff) are in section 3.4. Requirements refer to them rather than restating them.

Non-functional requirements (security, performance, availability, backup, offline
behaviour, data protection) are in chapter 4 and are referenced, not repeated. The data
each requirement touches is in chapter 6, the endpoints in chapter 7, the permissions in
chapter 8.

## 3.2 Scope boundary

In scope: the platform core (ADR-001) and the lending vertical for a licensed money
lender (Tier 4, Uganda) with loans, savings and investment products.

The retail vertical (ADR-020) is in scope from section 3.28; its build order is
`docs/specs/retail-mvp-scope.md`, where `MVP` in the phase column means the retail first release.

Out of scope for this chapter: synchronisation with the
offline single-device product (ADR-007), accrual accounting and expected credit loss
provisioning (ADR-004), and regulator return formats (open question in
`docs/specs/lending-mvp-scope.md`).

## 3.3 Terms

| Term | Meaning |
|---|---|
| Tenant | One client business using the platform. Has one subdomain slug. |
| Branch | A physical office of a tenant. Every financial record belongs to one branch. |
| Staff user | A tenant employee who signs in to the staff area. |
| Member | A person who borrows, saves or invests with a tenant. May have portal access. |
| Next of kin | A person named by a member as their contact of last resort. May or may not be a member. |
| Guarantor | A member who guarantees part of another member's loan. |
| Loan product | A configured set of loan terms (section 3.17). |
| Schedule item | One instalment of a loan: a due date and the principal, interest and fees due on it. |
| Business date | The calendar date in the tenant's timezone (default `Africa/Kampala`). |
| DPD | Days past due, per rule R-DPD in section 3.4. |
| PAR | Portfolio at risk, defined in chapter 14. |
| Maker, checker | The user who requests an action, and a different user who approves it (section 3.9). |
| Minor units | Integer amounts in the currency's smallest unit; for UGX, whole shillings (ADR-004). |
| bp | Basis points. 1 bp = 0.01 percent. A rate of 0.2 (20 percent) is 2000 bp. |

## 3.4 Lending calculation rules (normative)

All worked examples use fabricated round figures in UGX.

### R-ROUND: rounding

Every computed amount is rounded half up to the currency's minor unit once, at the line
it is computed for. Rounding residue is placed on the last schedule item, so the sum of
the schedule equals the total exactly. Intermediate arithmetic uses arbitrary-precision
decimal arithmetic with at least 28 significant digits. Floats are never used (ADR-004).

### R-TERM: term, frequency and due dates

- `term_unit` is `day`, `week` or `month`. `term_count` is a positive integer.
- `repayment_pattern` is `bullet` (one schedule item at the end of the term) or
  `instalments`.
- For `instalments`, `instalment_frequency` is `daily`, `weekly`, `fortnightly` or
  `monthly`, and the term must divide into a whole number of instalments:
  `month` terms allow `monthly`; `week` terms allow `weekly` and, for an even count,
  `fortnightly`; `day` terms allow `daily`, and `weekly` when the count is a multiple of 7.
  Anything else is rejected at application time with `invalid_term_frequency`.
- Number of schedule items `n`: `bullet` gives 1; otherwise term length divided by the
  frequency length.
- Due date of item `k` (1 based) = disbursement date plus `k` frequency steps. A step of
  `daily` is 1 day, `weekly` 7 days, `fortnightly` 14 days, `monthly` one calendar month.
  For a bullet loan the single due date is disbursement date plus the whole term.
- Adding months clamps to the last day of the target month: 31 January plus one month is
  28 February (29 in a leap year). Each item is computed from the disbursement date, not
  from the previous item, so clamping does not drift (31 January, 28 February, 31 March).
- A due date that falls on a non-working day is not moved in the MVP. A tenant holiday
  calendar is `Later`.

### R-RATE: rate units

- A product rate is `interest_rate_bp` per `rate_unit`, where `rate_unit` is `per_term`,
  `per_day`, `per_week`, `per_month` or `per_year`.
- Rate for the whole term, `r_term`:
  - `per_term`: `r_term = rate`.
  - `per_day`, `per_week`, `per_month`: allowed only when it matches `term_unit`
    (`per_month` with a term in months, and so on); `r_term = rate x term_count`.
  - `per_year`: `r_term = rate x term_days / 365` for day and week terms
    (`term_days = term_count` or `7 x term_count`), and `rate x term_count / 12` for month
    terms.
  - Any other combination is rejected at product save time with `invalid_rate_unit`.
- Rate per instalment period, `i` (declining method only) = `r_term / n`.

### R-FLAT: flat interest schedule

- Total interest `I = round(P x r_term)`.
- For each item `k < n`: principal `floor(P / n)`, interest `floor(I / n)`. The last item
  takes the remainders, so principals sum to `P` and interests sum to `I`.
- Fees added to the loan (section 3.17) are spread the same way and shown as a separate
  column.

Worked example A (the pilot tenant's existing product shape). `P = 500,000`, rate
2000 bp `per_term`, term 1 month, bullet, disbursed 16 March 2026. One item due
16 April 2026: principal 500,000, interest 100,000, total 600,000. This is the
spreadsheet's "amount to be returned = principal x (1 + rate)".

Worked example B. `P = 1,200,000`, rate 1000 bp `per_month`, term 3 months, monthly
instalments. `r_term = 0.30`, `I = 360,000`, `n = 3`. Each item: principal 400,000,
interest 120,000, total 520,000.

### R-DECL: declining balance schedule (equal instalments)

- If `i = 0`: equal principal, no interest, remainder on the last item.
- Otherwise instalment `A = round(P x i / (1 - (1 + i)^-n))`.
- For each item: interest `= round(opening balance x i)`; principal `= A - interest`;
  closing balance `= opening balance - principal`.
- The last item's principal is the whole remaining balance, and its total is
  principal plus interest (it may differ from `A` by the rounding residue).
- `bullet` with the declining method is identical to flat (no amortisation), and the
  product form says so.

Worked example C. `P = 1,000,000`, rate 1000 bp `per_month`, term 3 months, monthly.
`i = 0.10`, `A = round(402,114.80) = 402,115`.

| k | Opening | Interest | Principal | Total | Closing |
|---|---|---|---|---|---|
| 1 | 1,000,000 | 100,000 | 302,115 | 402,115 | 697,885 |
| 2 | 697,885 | 69,789 | 332,326 | 402,115 | 365,559 |
| 3 | 365,559 | 36,556 | 365,559 | 402,115 | 0 |

These three examples are required unit test cases.

### R-ALLOC: repayment allocation

1. A repayment is first compared with the payoff amount (R-PAYOFF) as at the value
   date. If it is equal to or greater than the payoff amount, it is a **payoff**: it
   settles every outstanding component, any unearned future interest is rebated per
   R-PAYOFF, the loan closes, and any excess follows step 4.
2. Otherwise it is applied to schedule items in due date order (oldest first), and
   within each item to components in the product's `allocation_order`, default
   `penalty, fee, interest, principal`. Items not yet due are reached only after every
   due and overdue item is fully paid (prepayment).
3. Allocation writes one `lending_repayment_allocations` row per item and component
   touched. The sum of allocation rows equals the repayment amount minus any excess.
4. Excess after the loan is fully settled is credited to the member overpayments
   liability account and shown as a refundable credit on the member. It is never
   silently applied to another loan. Refund or transfer to savings is a separate action.

Worked example D. Loan from example B, item 1 due (interest 120,000, principal 400,000),
no penalties. A repayment of 300,000 allocates interest 120,000 then principal 180,000.
Item 1 still owes principal 220,000; items 2 and 3 are untouched.

### R-DPD: days past due and arrears

- An item is **overdue** on business date `D` if its due date is earlier than `D` and any
  of its principal, interest or fee is unpaid. Unpaid penalties alone do not make an
  item overdue.
- A loan's `days_past_due` on `D` = `D` minus the due date of its oldest overdue item,
  in days; 0 if none.
- Arrears amount = unpaid principal, interest and fees on overdue items. Unpaid
  penalties are reported separately.
- DPD is recomputed in the same transaction as every repayment, reversal and schedule
  change, and for every open loan by the nightly job (FR-ARR-01).

### R-PEN: penalties

A product's penalty rule has these fields; the pilot tenant's actual policy is an open
question, so the default for a new product is `penalty_method = none`.

| Field | Meaning |
|---|---|
| `penalty_method` | `none`, `flat_per_period` or `percent_of_overdue_per_period` |
| `penalty_grace_days` | Days after the due date before the first penalty |
| `penalty_period_unit` | `day`, `week` or `month`: how often a penalty is charged while overdue |
| `penalty_flat_minor` | Amount charged per period for `flat_per_period` |
| `penalty_rate_bp` | Rate on the item's overdue principal plus interest, per period, for `percent_of_overdue_per_period` |
| `penalty_cap_bp` | Maximum total penalty on one item, as bp of that item's original principal plus interest; null means no cap |

- Penalty periods for an item are counted from `due date + grace days`. Period 1 is
  charged on the first business date after the grace days end, period 2 one
  `penalty_period_unit` later, and so on, while the item remains overdue.
- Each charge is one `lending_loan_charges` row keyed by `(schedule_item_id, period_no)`,
  so rerunning the job never double-charges.
- A charge is never larger than the remaining room under the cap.
- Penalties are memorandum items until paid: they are not posted to the general ledger
  when charged, and are credited to penalty income when a repayment is allocated to them
  (ADR-004 cash basis).
- A waiver is a maker-checker action (FR-ARR-04).

### R-PAYOFF: payoff amount

Payoff amount as at value date `V` =
unpaid penalties and fees on all items, plus unpaid principal on all items, plus interest:

- **flat method**: all unpaid scheduled interest (the whole contracted interest is due on
  early settlement), unless the product sets `flat_early_settlement_rebate = true`, in
  which case interest on items due after `V` is rebated and only interest on items due
  on or before `V`, plus the next item's interest, is due;
- **declining method**: unpaid interest on items due on or before `V`, plus the interest of
  the next item after `V` (the current period's interest). Interest on later items is
  rebated.

The rebate is recorded as a `waived` amount on the affected items, with reason
`early_settlement`.

## 3.5 Tenancy, plans and module switching (TEN)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-TEN-01 | A super admin shall create a tenant with name, slug, plan, currency (default UGX), timezone (default Africa/Kampala), enabled modules, a head office branch and a first tenant admin user. | Given valid input, the tenant, its head office branch, its default chart of accounts for each enabled module and an invitation to the tenant admin are created in one transaction. The tenant is reachable at its host under the tenant host pattern (chapter 7 section 7.2). An audit row records the creation. | MVP |
| FR-TEN-02 | The system shall enforce slug rules: lower case letters, digits and hyphens, 3 to 63 characters, not starting or ending with a hyphen, unique, and not a reserved label (`www`, `api`, `app`, `admin`, `static`, `mail`). | Each rule has a rejecting test; a valid slug is accepted. Slugs cannot be changed after creation in the MVP. | MVP |
| FR-TEN-03 | A super admin shall enable or disable a module (`lending`, later `retail`) for a tenant. | When `lending` is disabled, every `/api/v1/lending/...` request for that tenant returns 404 with code `module_not_enabled`, and scheduled lending jobs skip the tenant. Data is kept. | MVP |
| FR-TEN-04 | The system shall hold plans as configuration with limits: maximum branches, maximum active staff users, maximum active members, and which modules are allowed. | Creating the branch, user or member that would exceed a limit fails with `plan_limit_reached` and names the limit. Prices are not stored in this repository's seed data. | MVP |
| FR-TEN-05 | The system shall track subscription status per tenant: `trial`, `active`, `past_due`, `suspended`, `cancelled`, with the date of the next change. | A super admin can move a tenant between statuses; each move is audited. | MVP |
| FR-TEN-06 | A `suspended` tenant shall be read only: staff can sign in and read and export, but every state-changing request returns 423 with code `tenant_suspended`, and member portal sign-in is refused. | Tested for one read, one write and one member sign-in. Scheduled jobs that move money (penalties, interest postings) do not run for a suspended tenant; reminders do not send. | MVP |
| FR-TEN-07 | A super admin shall open an audited support session on a tenant with a stated reason and an expiry of at most 2 hours. | Support session reads are possible only while the session is open; opening and every request are audited with the reason. | P2 |
| FR-TEN-08 | A tenant admin shall edit tenant settings: display name, logo upload, theme colour, receipt footer text, SMS sender name (subject to aggregator approval), maker-checker thresholds, appraisal weights, business hours for reminders. The logo and colour are the business's brand (detail below). | Each setting has validation; each change is audited with before and after values. | MVP |
| FR-TEN-09 | A super admin shall export all of one tenant's data as a set of CSV files plus generated documents, and shall be able to schedule deletion after offboarding. | The export contains every tenant-owned table. Deletion is a two-person action on the platform side and is logged outside the tenant's own data. | Later |

**FR-TEN-08 detail: tenant branding.** The business set-up screen (staff area, `core.settings.manage`)
holds the display name, the logo (upload, replace, remove, with a preview), the theme colour and the
receipt footer, and a checklist (name, logo, colour, receipt footer, first branch besides the head
office, first staff user besides the admin) that a tenant admin lands on at first sign-in until it
is dismissed (`setup_dismissed`, tenant wide).

- **Logo rules.** PNG, JPEG or WebP by content, never by file name; no SVG (it can carry script)
  and no GIF; at most 1 MB; at least 128 px on the short side; re-encoded on the server (metadata
  stripped; a WebP is stored as PNG) and scaled down to at most 512 px on the long edge. It is a
  core document of the tenant (subject `core.tenant`). A replaced logo is superseded: the previous
  document stays and only `logo_document_id` moves, audited with the old and new id.
- **Theme colour.** `theme_primary`, `#RRGGBB`. Text on it must reach WCAG 2.x contrast 4.5:1 with
  white or with near-black (`#111111`); the better of the two becomes the text colour
  automatically, and a colour where neither reaches 4.5 is refused with `insufficient_contrast`
  (a narrow band of mid greys). On upload the screen suggests the logo's dominant colour, nudged
  darker until it is readable; the admin can change it with a live preview.
- **Shown where.** The logo (alt text: the display name) is in the app header and on the sign-in
  and accept-invitation pages of the tenant host; the colour drives the accent through CSS custom
  properties (chapter 11 section 11.2.5). Everything else keeps the platform look. The platform
  host shows the Rincoltech brand.
- **Footer.** "Powered by" and the Rincoltech logo, linked to the Rincoltech site, with an
  accessible link name, on every screen of every host (sign-in, accept-invitation, staff, member
  portal, platform console). A tenant cannot remove it in this release.
- **Public by design.** `GET /api/v1/branding` and `GET /api/v1/branding/logo` need no sign-in so
  the sign-in page can show the brand (chapter 7 section 7.11.4). The branding also lists the
  enabled module keys, and the landing page's copy, module cards and buttons follow them: a retail
  only tenant shows sales and stock, no members or loans copy and no Member portal button (#99).
- Out of scope: receipts and PDFs with the logo (FR-DOC-01), removing the footer (white label),
  dark mode, per-branch branding.

## 3.6 Branches and branch context (BR)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-BR-01 | A tenant admin shall create, rename and deactivate branches, each with a unique short code (2 to 10 upper case letters or digits), name and location. | Exactly one branch is the head office. A branch with open loans, savings or investments cannot be deactivated (`branch_has_open_accounts`). | MVP |
| FR-BR-02 | Every financial record (loan, repayment, savings and investment account and transaction, journal entry, collateral item, import batch) shall belong to exactly one branch. | `branch_id` is NOT NULL on those tables (chapter 6). | MVP |
| FR-BR-03 | A staff user shall work in one **active branch** at a time, chosen from the branches their role assignments cover, switchable in the header without signing out. | New records default to the active branch. The URL does not change on switch. The session starts on the last branch the user chose in this browser for this tenant, else their only branch, else a branch that holds retail stock, and never on a head office without stock while other branches exist (#103). A branch reads "Name (CODE)", or the name alone when the code is the name in other letters (#105). | MVP |
| FR-BR-04 | Lists and reports shall filter by branch, defaulting to the active branch, and users whose scope covers all branches shall be able to select "All branches" for a consolidated view. | A user scoped to branch A never sees branch B rows in any list, detail, search, report or export (tested per list endpoint). | MVP |
| FR-BR-05 | A member shall have a home branch. A loan, savings or investment account is opened at the member's home branch unless a user with scope over both branches chooses otherwise. | Account `branch_id` defaults to the member's home branch. | MVP |
| FR-BR-06 | A tenant admin shall transfer a member and their open accounts to another branch. | Transfer is a maker-checker action; it posts inter-branch journal entries moving each account's balance and is audited. | P2 |

## 3.7 Users, roles, permissions and authentication (IAM)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-IAM-01 | A tenant admin shall invite a staff user by email or phone, assign one or more roles, and set each role's branch scope (one or more branches, or all branches). The one-time link is shown once to the inviting admin in the API response and the UI, in addition to being sent through the notification port, so no flow waits for an email or SMS provider (accepted review item 1, PR #8). | The invitee receives a one-time link valid for 72 hours to set a password; the inviting admin sees the same link once, and showing it is audited (`core.invitation.link_revealed`, without the token). Until the link is used the user is `invited` and cannot sign in. A new link replaces the previous one. | MVP |
| FR-IAM-02 | The system shall provide the fixed role catalogue in chapter 8 (tenant admin, branch manager, loan officer, cashier, accountant, auditor) and the member role. Permissions per role are fixed in the MVP. | The permission matrix in chapter 8 is seeded and a test asserts each role's permission set equals the matrix. Custom roles are `Later`. | MVP |
| FR-IAM-03 | Every API endpoint shall declare exactly one required permission (or be explicitly public), and the check shall apply the principal's branch scope to the target record. | An architecture test fails if a route has no declared permission. For each permission, a test proves a role without it gets 403. | MVP |
| FR-IAM-04 | Staff shall sign in with email or phone plus password. Passwords are at least 10 characters and are checked against a list of common passwords. | Wrong credentials return a generic error that does not reveal whether the account exists. | MVP |
| FR-IAM-05 | After 5 consecutive failed sign-in attempts an account shall be locked for 15 minutes. | The 6th attempt within the lock returns `account_locked` even with the right password; the lock is audited. Parallel failed attempts are all counted. | MVP |
| FR-IAM-06 | Tenant admins and super admins shall use time-based one-time password (TOTP) second factor; other staff roles may opt in; a tenant setting can require it for all staff. | A tenant admin without TOTP enrolled is forced to enrol at first sign-in. | MVP |
| FR-IAM-07 | Sessions shall use a short-lived access token (15 minutes) and a rotating refresh token (idle expiry 12 hours for staff, 30 days for members). Reuse of a rotated refresh token revokes the whole session family. | Tested: refresh, rotation, reuse detection, sign-out. | MVP |
| FR-IAM-08 | A tenant admin shall deactivate a staff user, which revokes their sessions immediately. | The deactivated user's next request with a still-valid access token is refused (session revocation is checked on every request). | MVP |
| FR-IAM-09 | A member shall activate portal access by phone number: the system sends a 6-digit one-time code by SMS (valid 10 minutes, 5 attempts), then the member sets a 5 digit PIN. Later sign-in is phone plus PIN. | Portal activation requires a member record with that verified phone. PIN lockout: 5 failures lock for 30 minutes. PIN reset repeats the SMS code step. | P2 |
| FR-IAM-10 | A user who is both staff and a member of the same tenant shall have two separate identities; staff permissions never apply in the member area and the reverse. | Tested with one person holding both identities. | P2 |
| FR-IAM-11 | TOTP enrolment shall issue ten single-use recovery codes, shown once and stored as hashes only. A recovery code may be entered instead of a TOTP code at sign-in; the user may replace all codes with a current TOTP code (accepted review item 2, PR #8). | A used code is refused the second time; replacing the codes invalidates every old one; using a code and replacing the codes are audited. Failed codes count toward the lockout of FR-IAM-05. | MVP |
| FR-IAM-12 | A tenant admin shall reset another staff user's second factor after a lost device; a tenant admin cannot reset their own. When a tenant's only admin is locked out, a platform operator resets that admin's second factor through the platform API (accepted review item 2, PR #8). | The reset clears the secret and the recovery codes and ends every session of the user at once; the next sign-in enrols again when the role requires it. The reset is audited in the tenant's audit log, and a platform reset also in the platform audit log. Not a maker-checker action (ADR-014). | MVP |

## 3.8 Audit log (AUD)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-AUD-01 | Every state-changing action shall write one audit row in the same transaction: actor, actor kind, tenant, branch, action, entity type and id, request id, and a data payload with the changed fields' before and after values. | Tested for one action per module: the audit row exists and rolls back with the action. | MVP |
| FR-AUD-02 | Audit rows shall be append only. | The application role has no UPDATE or DELETE on `audit_log`; a test proves both fail. | MVP |
| FR-AUD-03 | Sign-in successes, failures, lockouts, MFA events (enrolment, failed codes, recovery code use and replacement, MFA resets), invitation links shown, permission denials on money-moving endpoints, and document downloads of member statements shall be audited. | One test per event type. | MVP |
| FR-AUD-04 | An auditor or tenant admin shall search the audit log by date range, actor, entity type, entity id and action, and export results to CSV. | Search respects branch scope. Exports are themselves audited. | MVP |
| FR-AUD-05 | National ID numbers and phone numbers shall appear in audit payloads masked except for the last 4 characters. | A test inspects a member update audit row. | MVP |

## 3.9 Maker-checker approvals (APR)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-APR-01 | The system shall provide one approval mechanism for actions that require a second person: loan disbursement, repayment reversal, penalty waiver, write-off, restructure, manual journal, period close, savings withdrawal above threshold, investment early withdrawal, collateral release, member branch transfer, member credit refund, and import commit. | Each listed action, when requested, creates an `approval_requests` row in `pending` and has no financial effect until approved. | MVP |
| FR-APR-02 | The checker shall never be the maker. | Enforced by a database CHECK (`decided_by <> requested_by`) and by the service. A test attempts self-approval and gets `self_approval_forbidden`. | MVP |
| FR-APR-03 | For loan approval (FR-ORG-06) the approver shall be neither the user who submitted the application nor the user who ran the appraisal. | Tested with each forbidden combination. | MVP |
| FR-APR-04 | A tenant admin shall set a threshold per action type below which the action executes without a checker (default 0, meaning every instance needs a checker). Loan approval has no threshold. | With threshold 1,000,000 a disbursement of 999,999 executes immediately and one of 1,000,000 creates a pending request. | MVP |
| FR-APR-05 | Checkers shall see a queue of pending requests they are permitted to decide, scoped to their branches, with the maker, amount, subject and a link to the subject. | Queue contents tested per role. | MVP |
| FR-APR-06 | A checker shall approve or reject with a note (required on reject). On approve, the action executes in the same transaction as the decision; if execution fails, the request stays pending and the error is shown. | Tested: approve executes; failed execution leaves `pending`; reject records the note. | MVP |
| FR-APR-07 | A pending request shall expire after 7 days, and the maker may cancel their own pending request. | Expired and cancelled requests cannot be approved. | MVP |
| FR-APR-08 | Each request shall store a snapshot of the action payload, and approval shall execute exactly that payload. | If the subject changed since the request (for example the loan was already disbursed), approval fails with `subject_changed` and the request is marked `stale`. | MVP |

## 3.10 General ledger (GL)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-GL-01 | On tenant creation the system shall seed the default chart of accounts for every enabled module (chapter 6, section 6.6.2). Seeded accounts carry a `system_key` used by posting rules and cannot be deleted. | A new lending tenant has every `system_key` in 6.6.2. | MVP |
| FR-GL-02 | An accountant shall add, rename and deactivate non-system accounts with a unique code, a type (asset, liability, equity, income, expense) and an optional parent. | An account with postings cannot be deleted, only deactivated. A deactivated account rejects new postings. | MVP |
| FR-GL-03 | Every financial event shall post exactly one balanced journal entry per branch affected, in the same transaction as the event, through the ledger's posting operation `post_entry` (ADR-004). | For each event type in 6.6.3 a test asserts the exact lines. A deliberately unbalanced entry is rejected at commit by the deferred trigger. | MVP |
| FR-GL-04 | Journal entries and lines shall be immutable; corrections are reversing entries that reference the original. | UPDATE and DELETE fail for the application role. Reversing a reversal is refused (`already_reversed`). | MVP |
| FR-GL-05 | An accountant shall raise a manual journal with at least two lines, a memo and an entry date in an open period; it posts only after checker approval. | Manual journals cannot use accounts flagged `system_controlled` (loans receivable, member savings, investments payable), which change only through their subledger. | MVP |
| FR-GL-06 | The ledger shall have monthly periods. Posting to a closed period is refused (`period_closed`). Closing a period is maker-checker. Reopening is a super admin action, audited with a reason. | Tested: post into closed period fails; close and reopen flows. | MVP |
| FR-GL-07 | The system shall produce a trial balance for any date range and branch selection, per chapter 14. | Total debits equal total credits for every branch and for the consolidated view. | MVP |
| FR-GL-08 | A tenant admin shall map each payment method (cash, bank, MTN mobile money, Airtel mobile money, payment gateway) to a GL asset account, per branch where needed. | A repayment by a method with no mapping is refused (`payment_method_unmapped`). | MVP |
| FR-GL-09 | The system shall post opening balances for an imported book against the opening balance equity account (chapter 13). | The import commit posts one opening entry per branch. | MVP |
| FR-GL-10 | A nightly job shall reconcile each subledger to its control account per branch (loans receivable, member savings, investments payable, member overpayments) and raise an alert to tenant admins and accountants on any difference. | A seeded mismatch produces an alert naming the account, branch and difference. | MVP |

## 3.11 Notifications (NTF)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-NTF-01 | Notifications shall be written to an outbox in the same transaction as the event that causes them, and sent by the worker after commit. | A rolled-back repayment sends no receipt SMS. | MVP |
| FR-NTF-02 | The system shall send SMS for: loan disbursed, repayment received (receipt), instalment due in 3 days, instalment due today, loan overdue at 1, 7 and 30 DPD, loan closed, savings deposit and withdrawal, investment funded, investment maturing in 7 days, investment paid out, portal one-time codes. | Each event has a default template per tenant; a test renders each with fabricated data within 160 GSM-7 characters or explicitly allows 2 segments. | MVP (loan events); P2 (one-time codes, savings and investment events) |
| FR-NTF-03 | A tenant admin shall edit SMS templates using named placeholders (for example `{member_first_name}`, `{amount}`, `{due_date}`, `{loan_no}`, `{balance}`, `{tenant_name}`). Unknown placeholders are rejected at save. | Save with `{unknown}` fails with `unknown_placeholder`. | MVP |
| FR-NTF-04 | Reminder and arrears messages shall be sent only inside the tenant's configured sending window (default 08:00 to 20:00 local time); messages due outside it wait for the next window. Receipts and one-time codes are sent immediately. | Tested with a clock at 21:00. | MVP |
| FR-NTF-05 | The worker shall retry a failed send with backoff (1, 5, 30 minutes), then mark it `failed`. Delivery reports from the aggregator update status to `delivered` or `undelivered`. | Status history is visible per message. | MVP |
| FR-NTF-06 | The system shall not send the same reminder twice for the same schedule item and reminder type. | Idempotency key `(schedule_item_id, reminder_type)`; rerunning the reminder job sends nothing new. | MVP |
| FR-NTF-07 | The system shall count SMS segments sent per tenant per month for plan reporting. | The count appears in the super admin tenant view. | MVP |
| FR-NTF-08 | Email notifications (staff invitations, password reset, report ready) shall use the same outbox. | Tested with a fake provider. | MVP |
| FR-NTF-09 | The platform outbox (`notification_outbox`, ADR-024, spec section 11) shall be delivered at least once: a sender job claims a due row with `FOR UPDATE SKIP LOCKED` in a short transaction that counts the attempt and sets a 2 minute lease, sends outside any transaction, and records the result in a second one; each row has a unique idempotency key; a failure is retried after 1 and 5 minutes and the third attempt marks the row `failed`, listed in the operator portal with a masked recipient and a "send again" action, refused (`outbox_too_old`) for a row older than 7 days or whose own link has expired (`expires_at`: 72 hours for an activation link, 7 days for an applicant link); a row whose link has expired is never sent, and a due row whose three attempts are used up (an attempt that died before recording a failure) is marked `failed` by the next claim. A sent row's parameters are cleared; the nightly purge clears them on any unsent row older than 7 days (a pending one becomes `failed`, `expired`) and deletes rows sent more than 30 days ago. | `OutboxIT`: one row per key, a locked row is skipped and not sent twice, an attempt that dies with an Error is counted and leased, three failures then `failed`, retry back to `pending`, an old row refused and cleared. | MVP |
| FR-NTF-10 | Email shall be sent over SMTP with implicit TLS (default port 465) using `BMS_SMTP_HOST`, `BMS_SMTP_PORT`, `BMS_SMTP_USER`, `BMS_SMTP_PASSWORD` and `BMS_MAIL_FROM`. With any of them unset the email sender is off, its rows stay `pending` and nothing fails. Secrets, message texts, links and addresses are never logged. | `OutboxIT` with fake senders; the test profile sets none of the variables. | MVP |
| FR-NTF-11 | Operator alerts shall go to Telegram through the Bot API `sendMessage` (`BMS_TELEGRAM_BOT_TOKEN`, `BMS_TELEGRAM_OPERATOR_CHAT_ID`) and to the email of every active platform operator. Unset, the Telegram sender is off as in FR-NTF-10. The WhatsApp relay is build step 3 of the spec. | `OnboardingIT`: a newly confirmed application writes one Telegram row and one email row per operator, once. | MVP |

The SMS aggregator is not yet chosen (pending ADR-013). Chapter 12 defines the adapter
interface so the choice does not change these requirements.

## 3.12 Document generation (DOC)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-DOC-01 | The system shall generate PDF documents: repayment receipt, disbursement voucher, loan offer and agreement, repayment schedule, loan statement, member statement, savings statement, investment certificate, and report exports. | Each document type has a template and a golden-file test with fabricated data. | MVP (receipt, voucher, schedule, loan statement); P2 (others) |
| FR-DOC-02 | Generated documents shall be stored in object storage under a tenant-prefixed key and recorded in `documents` with type, subject, checksum and creator. | The key starts with `tenants/<tenant_id>/`. | MVP |
| FR-DOC-03 | Downloads shall be served through short-lived signed URLs (5 minutes) issued after a permission check. | An expired URL fails; a user without the subject's permission gets 403 before any URL is issued. Every issued URL is audited. | MVP |
| FR-DOC-04 | Receipts and vouchers shall carry a tenant-unique, gap-free sequential number per branch (for example `RC-<branch code>-000123`). | Concurrent receipt creation never duplicates or skips a number (row-locked sequence). | MVP |
| FR-DOC-05 | A document shall be reproducible: regenerating it from the same records produces the same content. | Regeneration test compares text content. | P2 |

## 3.13 Reporting framework (RPT)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-RPT-01 | The system shall offer a report catalogue listing each report the tenant's modules provide, with its parameters and the permission required. | The catalogue shows only reports the user may run. | MVP |
| FR-RPT-02 | Every report shall accept an as-of date or date range and a branch selection (one branch, several, or all within scope), and show the parameters on its output. | Parameter echo tested per report. | MVP |
| FR-RPT-03 | A report shall be viewable on screen and exportable to CSV and PDF; financial statements also to XLSX. | One export test per format. | MVP |
| FR-RPT-04 | Reports expected to take longer than 5 seconds shall run in the worker; the user is notified when the file is ready. | A report run has status `queued`, `running`, `ready` or `failed`. | MVP |
| FR-RPT-05 | Report definitions (source, formula, rounding) shall follow chapter 14 exactly. | Each report has a test on a fabricated dataset with hand-computed expected output. | MVP |

## 3.14 Import framework (IMP)

The import framework is generic; the pilot loan register template is its first user.
Chapter 13 is the full specification.

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-IMP-01 | A tenant admin shall upload an XLSX or CSV file against a named import template and a target branch. | The original file is stored in object storage with its SHA-256; uploading the same file twice for the same tenant is refused (`duplicate_import`) unless the earlier batch was cancelled. | MVP |
| FR-IMP-02 | Every source row shall become exactly one `import_rows` record with its raw cell values and source row number, including blank, sub-header and placeholder rows. | Row count in the batch equals the source row count below the header. | MVP |
| FR-IMP-03 | Each row shall be classified (`data`, `subheader`, `placeholder`, `amounts_only`, `blank`) and normalised per the template's rules. | Classification rules in chapter 13 section 13.5 each have a test. | MVP |
| FR-IMP-04 | Anomalies shall be recorded as issues with a code, severity (`info`, `warning`, `blocking`), field, message and, where a rule allows, a suggested value. The importer never guesses a value silently. | Every issue code in chapter 13 section 13.7 has a fixture row that produces it. | MVP |
| FR-IMP-05 | A reviewer shall work a review queue: accept a suggestion, edit a normalised value, exclude a row with a reason, or link a row to another row or an existing member. Bulk accept is allowed per issue code. | Every resolution is audited with before and after values. | MVP |
| FR-IMP-06 | The system shall show a commit preview: members to create and to match, loans to create by status, opening balances per branch, and excluded rows with reasons. | Preview figures equal what commit produces (tested). | MVP |
| FR-IMP-07 | Commit shall be refused while any blocking or warning issue is unresolved (warnings may be resolved in bulk), and shall run in one transaction after checker approval. | A failed commit leaves no partial members, loans or journals. | MVP |
| FR-IMP-08 | After commit the batch shall have a reconciliation report: source rows, excluded rows with reasons, created and matched records, and control totals (principal, amount to be returned, outstanding balance) from source versus system. | Control totals differ only by excluded rows, which are itemised. | MVP |
| FR-IMP-09 | A committed batch cannot be re-committed or edited; a mistake is corrected by normal transactions, or by a super admin rollback that is allowed only while no later transaction touches the imported records. | Rollback refused after a repayment on an imported loan. | P2 |

## 3.15 Payment intake from gateways (PAY)

The payment gateway is not yet chosen (pending ADR-011). These requirements are
gateway-neutral; chapter 12 defines the adapter.

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-PAY-01 | A member (portal) or a staff user shall start a mobile money collection for a purpose (loan repayment, savings deposit, investment funding) and an amount; the system creates a payment intent and asks the gateway to prompt the payer's phone. | Intent states: `created`, `pending`, `succeeded`, `failed`, `expired`. | P2 |
| FR-PAY-02 | A gateway callback shall be accepted only after signature verification, and shall be confirmed by querying the gateway's transaction status before any money is booked. | A callback with a bad signature is rejected and logged; a valid callback whose status query disagrees is not booked. | P2 |
| FR-PAY-03 | Booking a succeeded intent shall be idempotent on the gateway's transaction reference. | Replaying the same callback 3 times books one repayment. | P2 |
| FR-PAY-04 | Money received that cannot be matched to an intent or account shall be booked to unallocated receipts and appear in a queue for a cashier to allocate. | Allocation from the queue creates the repayment or deposit and clears the unallocated line. | P2 |
| FR-PAY-05 | A daily reconciliation shall compare the gateway's settlement report with booked receipts and list differences. | Differences listed with gateway reference and amount. | P2 |
| FR-PAY-06 | Gateway charges borne by the tenant shall be posted to the gateway charges expense account. | Tested with a fabricated charge. | P2 |

## 3.16 Members, KYC, next of kin and relationships (MEM)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-MEM-01 | A loan officer shall register a member with: full name (required), phone (required), national ID number (NIN), date of birth, gender, marital status, location (district, sub-county or town, village or street), occupation, other source of income, declared monthly income, home branch, and assigned officer. | The member gets a tenant-unique member number `M` plus 6 digits from a per-tenant sequence. | MVP |
| FR-MEM-02 | Phone numbers shall be stored in E.164 form. Input in any common local form (`07XXXXXXXX`, `7XXXXXXXX`, `2567XXXXXXXX`, `+256 7XX XXX XXX`) shall be normalised; anything else is rejected with `invalid_phone`. | Normalisation cases from chapter 13 section 13.6 are shared tests. | MVP |
| FR-MEM-03 | A NIN shall be stored upper case without spaces and validated against the pattern `^C[MF][A-Z0-9]{12}$`. A NIN is unique per tenant. | Duplicate NIN returns `duplicate_nin` with the existing member number. Members without a NIN may record another ID type (passport, refugee ID, other) with its number. | MVP |
| FR-MEM-04 | Before creating a member, the system shall search for likely duplicates by NIN, phone and name similarity and show them to the user. | Registering a phone already on another member shows a warning; saving requires confirming it is a different person (`confirmed_not_duplicate`, audited). Name similarity is trigram similarity of at least 0.6, so word order does not matter. | MVP |
| FR-MEM-05 | KYC status shall be `incomplete`, `pending_verification`, `verified` or `rejected`. A member is `pending_verification` when name, phone, NIN or other ID, location and at least one next of kin are present, and an ID document image is uploaded. A branch manager marks it `verified`. The move from `incomplete` is automatic, checked after each change that can complete it; "location" is any of location, district, sub-county or village; the ID image is an `id_front` document. | A loan cannot be submitted for a member whose KYC is not `verified` unless the tenant setting `allow_loans_before_kyc_verified` is on (default off). | MVP |
| FR-MEM-06 | A member shall have one or more next of kin, each with full name, phone, NIN, relationship (spouse, parent, child, sibling, relative, friend, employer, other) and location. | At least one next of kin is required for KYC completeness. | MVP |
| FR-MEM-07 | When a next of kin's NIN matches an existing member of the tenant, the system shall link the next of kin to that member automatically. When only the phone matches, it shall suggest the link for a user to confirm. | Link method `nin` is automatic; `phone` is `suggested` until confirmed, and only when exactly one other member has that phone. Both are visible on the member's relationship view; a rejected suggestion stays out of the graph. The link is resolved both ways: when the next of kin is saved, and when a member is registered or their NIN or phone changes (a NIN link replaces a phone suggestion). | MVP |
| FR-MEM-08 | The member view shall show a relationship panel: people this member names as next of kin, members who name this member as their next of kin, loans this member guarantees, and guarantors of this member's loans, each with outstanding balance and DPD. | Tested with the fixture relationship (a borrower who is another borrower's next of kin). The guarantee lists and each party's outstanding balance and DPD arrive with loans (increment 4). A member outside the viewer's branch scope shows only the member number. | MVP |
| FR-MEM-09 | Staff shall upload member documents (ID front and back, photo, other) as images or PDF up to 5 MB each. | Stored per FR-DOC-02; listed on the member. A member keeps at most 10 active documents per kind; a newer upload supersedes the oldest, which is kept for audit. Downloading an ID image (`id_front`, `id_back`) needs `lending.members.verify_kyc`; a cashier or auditor gets 403. | MVP |
| FR-MEM-10 | A member's status shall be `active`, `inactive` or `exited`. A member with any open loan, savings or investment account cannot be set to `exited`. | Tested. | MVP |
| FR-MEM-11 | Staff shall search members by member number, name, phone (any input form), NIN, or loan number. | Search respects branch scope; results in under 1 second for 50,000 members (chapter 4). | MVP |
| FR-MEM-12 | A member statement shall list all loans, savings and investment accounts with balances as at a date, and all transactions in a range. | PDF and screen views agree. | P2 |
| FR-MEM-13 | A tenant admin shall flag a member as `blacklisted` with a reason; a blacklisted member's applications cannot be approved. | Approval of a blacklisted member's loan fails with `member_blacklisted`. | MVP |

## 3.17 Loan products (PRD)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-PRD-01 | A tenant admin shall create a loan product with: code, name, currency, interest method (`flat` or `declining`), `interest_rate_bp`, `rate_unit`, `term_unit`, minimum, maximum and default `term_count`, `repayment_pattern`, `instalment_frequency`, minimum and maximum principal, allocation order, penalty rule (R-PEN), `flat_early_settlement_rebate`, whether collateral is required, minimum collateral cover in bp, whether a guarantor is required, and GL account overrides if any. | Invalid combinations per R-TERM and R-RATE are rejected at save with the rule code. | MVP |
| FR-PRD-02 | A product shall have zero or more fees, each with a name, type (`application`, `processing`, `insurance`, `other`), calculation (`flat` amount or `percent_of_principal` in bp), and timing: `deducted_at_disbursement` (member receives principal minus fee), `added_to_loan` (spread over the schedule), or `paid_upfront` (collected before disbursement). | Each timing has a posting test (chapter 6 section 6.6.3). | MVP |
| FR-PRD-03 | The product form shall show a live schedule preview for an example principal and term, computed by the same code as real schedules. | The preview for worked examples A, B and C equals section 3.4. | MVP |
| FR-PRD-04 | A product in use shall not change retroactively: editing a product creates a new version; existing loans keep the version they were created with. | A loan stores `product_version_id`; editing the product does not alter an existing loan's schedule. | MVP |
| FR-PRD-05 | A product can be archived, which stops new applications but keeps existing loans. | Tested. | MVP |
| FR-PRD-06 | A product can be restricted to certain branches. | An application at another branch cannot select it. | P2 |

Product rules that follow from the table above:

- Bounds. Money fields accept at most 10^15 minor units and a percentage fee at most 10,000 bp
  (the whole principal); an amount the arithmetic cannot hold is refused with
  `amount_out_of_range`, never a server error.
- Fees and disbursement (FR-PRD-02). At save, the fees deducted at disbursement, computed at the
  product's minimum principal, must total strictly less than that principal
  (`fees_exceed_principal` on the fee that crosses it), so no product can be saved whose smallest
  loan disburses nothing.
- Cover (FR-PRD-01, FR-ORG-07). A product that requires collateral must state
  `min_collateral_cover_bp`.
- No effective dating in the MVP (FR-PRD-04). A new version is current the moment it is saved:
  applications created from then on copy it, and loans already created keep theirs. Scheduling a
  version for a future date is `Later`.

## 3.18 Loan origination: application, appraisal, approval (ORG)

Loan statuses and transitions:

| From | To | Trigger | Who |
|---|---|---|---|
| (none) | `draft` | Create application | Loan officer, member (portal) |
| `draft` | `submitted` | Submit | Loan officer |
| `submitted` | `appraised` | Record appraisal | Loan officer, branch manager |
| `submitted`, `appraised` | `draft` | Return for correction, with note | Branch manager |
| `appraised` | `approved` | Approve | Branch manager (checker, FR-APR-03) |
| `appraised` | `rejected` | Reject, with reason | Branch manager |
| `approved` | `active` | Disbursement executed | Cashier (maker) plus checker per FR-APR-04 |
| `draft`, `submitted`, `appraised`, `approved` | `cancelled` | Cancel, with reason | Loan officer (own draft), branch manager |
| `active` | `closed` | Fully repaid | System |
| `active` | `written_off` | Write-off approved | Accountant request plus checker |
| `active` | `restructured` | Restructure approved; a new loan replaces it | Branch manager request plus checker |

Any other transition is refused with `invalid_status_transition`. Every transition writes
a `lending_loan_status_history` row.

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-ORG-01 | A loan officer shall create a loan application for a member with product, principal requested, term, purpose (free text plus a purpose category: business, school fees, medical, agriculture, household, construction, other), and proposed disbursement date. | The loan gets a tenant-unique number `LN` plus 6 digits. Principal and term are validated against the product. | MVP |
| FR-ORG-02 | The application shall record guarantors (members, with guaranteed amount) and collateral items pledged (from the collateral register, with pledged value). | Required when the product says so; submission fails with `guarantor_required` or `collateral_required` otherwise. Guarantors are other members of the tenant; pledged items come from the borrower's register, are held (pledged or in custody) and secure one open loan at a time (`collateral_already_pledged`). | MVP |
| FR-ORG-03 | Submitting shall freeze the application's terms and generate a provisional schedule for display. | After submit, only a return-for-correction reopens editing. | MVP |
| FR-ORG-04 | Appraisal shall capture declared monthly income and monthly obligations, the officer's visit notes, and shall compute the credit score in section 3.18.1 with its components and flags. | The appraisal stores a snapshot of every input and the result, so later changes to the member do not change a recorded appraisal. | MVP |
| FR-ORG-05 | The appraisal shall show the member's exposure: own open loans, loans they guarantee, and linked parties' loans (FR-MEM-08), with outstanding balances and DPD. | Tested with the fixture relationship. | MVP |
| FR-ORG-06 | A branch manager shall approve (optionally at a lower principal or shorter term than requested, never higher) or reject with a reason. Approval stores the approved terms and a final schedule preview. | Approval above requested principal fails. Approver rules per FR-APR-03. | MVP |
| FR-ORG-07 | Approval shall check: member not blacklisted, KYC verified (FR-MEM-05), collateral cover meets the product minimum, and the tenant's optional maximum number of concurrent active loans per member (default no limit). | Each failing check returns its code and blocks approval. | MVP |
| FR-ORG-08 | An approved loan not disbursed within the tenant setting `approval_validity_days` (default 14) shall expire to `cancelled` with reason `approval_expired`. | Nightly job tested. | MVP |
| FR-ORG-09 | A member shall apply for a loan from the portal, creating a `draft` with channel `portal` that appears in the assigned officer's queue. | Portal applications cannot skip appraisal. | P2 |

Origination rules that follow from the table above:

- One open loan per item (FR-COL-01, ADR-019). A collateral item secures at most one loan that is
  not cancelled, rejected or closed. A draft holds its pledges from the moment they are set, and a
  loan returned for correction keeps them: the officer is still assembling that application.
  Cancelling, rejecting or closing the loan releases them. A written-off loan keeps its
  collateral, because it is being recovered.
- Pledged value (FR-ORG-02). A pledge states at most the item's value (latest forced sale value,
  else the estimate), so only a valued item can be pledged (`collateral_not_valued`), on any
  product. A loan takes at most 20 guarantors and 20 pledged items.
- What submit checks and what it leaves (FR-ORG-03, FR-ORG-07). Submit checks again, as they
  stand at that moment, the borrower and each guarantor (active, not blacklisted) and each
  pledged item (the borrower's, held, in the loan's currency, valued, free). It does not measure
  adequacy: the collateral cover against the product minimum is checked at approval (FR-ORG-07),
  and a guarantor's capacity is not measured at all in the MVP. A limit on how much one member may
  guarantee across loans is an open question for the product owner.
- Loans per member (FR-ORG-07). `max_active_loans_per_member` counts the member's loans that are
  `approved` or `active`: an approval not yet disbursed takes a place, so several appraised loans
  cannot all be approved before one is disbursed. Approval locks the member's row before it
  counts, so two approvals for one member at the same moment run one after the other and the
  second is refused with `max_active_loans_reached`. An approval that expires (FR-ORG-08) frees
  its place.
- Dates. A proposed disbursement date is today or later. A loan submitted without one takes the
  submission date, so the provisional schedule stops moving.

### 3.18.1 Credit score (default model)

The scoring model is a rules-based default, pending ADR-012 for any statistical model.
The score is a recommendation shown to the approver; it never approves or rejects on its
own. Weights are tenant settings; defaults below.

| Component | Max points | Rule |
|---|---|---|
| Repayment history | 40 | No prior loans: 20. Otherwise `40 x (loans closed with max DPD <= 7) / (closed loans)`, minus 10 per loan ever written off, floor 0. |
| Affordability | 30 | Ratio `q = (this loan's largest instalment converted to a monthly amount + existing monthly obligations) / declared monthly income`. `q <= 0.30`: 30. `q >= 0.60`: 0. Linear between. No declared income: 0, flag `INCOME_NOT_DECLARED`. |
| Collateral cover | 20 | Cover `c = pledged collateral value / principal`. `c >= 1.5`: 20. `c = 0`: 0. Linear between. |
| Exposure | 10 | Start at 10. Minus 5 if the member has another active loan with DPD > 0. Minus 5 if any linked party (FR-MEM-08) has a loan with DPD > 30. Floor 0. |

Monthly conversion of an instalment: daily x 30, weekly x 52 / 12, fortnightly x 26 / 12,
monthly x 1, bullet: total due divided by the term in months (minimum 1).

Band: A 75 to 100, B 60 to 74, C 45 to 59, D below 45. Flags are shown with the score:
`INCOME_NOT_DECLARED`, `EXISTING_LOAN_IN_ARREARS`, `LINKED_PARTY_IN_ARREARS`,
`MULTIPLE_ACTIVE_LOANS`, `COLLATERAL_BELOW_PRODUCT_MINIMUM`, `NEW_MEMBER` (registered
less than 30 days ago).

Recommendation stored with the score: band A or B `approve`, band C `review`, band D `decline`.
Exposure (FR-ORG-05) counts loans in `submitted`, `appraised`, `approved` or `active`; linked
parties are the FR-MEM-08 links plus this loan's guarantors and the borrowers of loans the member
guarantees. Until the daily DPD snapshots exist (increment 6) no maximum DPD is recorded per
loan, so every closed loan counts as closed on time in the repayment history rule.

## 3.19 Disbursement and repayment schedule (DIS)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-DIS-01 | A cashier shall request disbursement of an approved loan with disbursement date (today or earlier within the open period), payment method and reference. This is a money-moving endpoint and requires an idempotency key (chapter 7). | Creates an approval request unless below threshold (FR-APR-04). | MVP |
| FR-DIS-02 | On execution the system shall, in one transaction: generate the schedule from the disbursement date (R-TERM, R-FLAT or R-DECL), collect or deduct fees per FR-PRD-02, post the disbursement journal (6.6.3), set the loan `active`, write status history, queue the disbursement SMS and the voucher PDF, and audit. | A failure at any step leaves the loan `approved` and nothing posted. | MVP |
| FR-DIS-03 | Only one disbursement per loan is allowed in the MVP. | A second disbursement request on an `active` loan fails with `invalid_status_transition`. Tranche disbursement is `Later`. | MVP |
| FR-DIS-04 | The schedule view shall show each item's due date, principal, interest, fees, penalties, amounts paid, waived, outstanding, and status (`pending`, `due`, `overdue`, `paid`, `partially_paid`, `waived`). | Totals row equals loan totals. | MVP |
| FR-DIS-05 | A disbursement shall be reversible only by a maker-checker reversal within the same open period and only while no repayment exists; reversal posts a reversing journal and returns the loan to `approved`. | Reversal after a repayment fails with `has_repayments`. | P2 |

Built in increment 5 (#108, ADR-026): FR-DIS-01 to FR-DIS-04. The disbursement date is today or
earlier and its period must be open (`value_date_in_future`, `period_closed`); the payment method
must have an active account (`payment_method_unmapped`). Deducted fees that would leave nothing to
disburse are refused (`fees_exceed_principal`). An upfront fee posts its own entry and receipt in the
disbursement's transaction. The voucher number is `VC-<branch code>-NNNNNN` (FR-DOC-04). The
disbursement SMS and the voucher PDF are deferred (increment 6 and a documents follow-up).

## 3.20 Repayments (REP)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-REP-01 | A cashier shall record a repayment on an active loan with amount, value date (today or earlier within the open period), payment method and external reference. Requires an idempotency key. | Amount must be greater than 0. Value date after today fails. | MVP |
| FR-REP-02 | Partial repayments shall be allowed. The repayment is allocated per R-ALLOC. | Worked example D is a test. | MVP |
| FR-REP-03 | The repayment shall, in one transaction: write the repayment and its allocations, update schedule items and loan balances, recompute DPD, post the repayment journal (6.6.3), close the loan if fully paid, queue the receipt SMS and receipt PDF, and audit. | Crash injection after the journal write leaves nothing committed. | MVP |
| FR-REP-04 | An excess repayment shall be handled per R-ALLOC step 4. | Excess appears as a member credit and in the member overpayments account. | MVP |
| FR-REP-04a | A cashier shall refund a member credit in cash or transfer it to the member's savings account, as a maker-checker action. | Posts the matching journal and clears the credit. | P2 |
| FR-REP-05 | A repayment shall be reversible (for example a bounced cheque or keying error) by a maker-checker action with a reason. Reversal un-applies its allocations, restores item balances, re-opens the loan if it had closed, recomputes DPD, and posts a reversing journal. | Reversal of a repayment that is not the latest on the loan re-allocates the later repayments in value date order and posts the net difference; tested. | MVP |
| FR-REP-06 | The payoff quote shall be available for any active loan and value date (R-PAYOFF). | Worked examples in tests for both methods. | MVP |
| FR-REP-07 | Repayments may be recorded against a loan from any branch the cashier is scoped to; if the cashier's branch differs from the loan's branch, the journal follows ADR-004 (two entries through inter-branch clearing). | Both branch trial balances balance after a cross-branch repayment. | P2 |
| FR-REP-08 | A member may repay from their savings balance by a transfer, recorded as a repayment with method `savings_transfer` and a matching savings withdrawal in one transaction. | Both ledgers move together. | P2 |

Built in increment 5 (#108, ADR-026): FR-REP-01 to FR-REP-06. A value date after today
(`value_date_in_future`), before the disbursement (`before_disbursement`) or before the loan's latest
repayment (`before_last_repayment`, so allocation always follows value date order) is refused. The
receipt number is `RC-<branch code>-NNNNNN`. A reversal is requested with a reason and always needs
a checker; it replays the surviving repayments on the contracted schedule and writes the
differences as allocation rows of the reversal; the journal is the mirror of the original when no
other repayment moves, else one entry with the net difference. DPD, arrears and item statuses are
recomputed at every money event; the nightly recomputation is increment 6 (FR-ARR-01). The receipt
SMS and PDF are deferred.

## 3.21 Arrears and penalties (ARR)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-ARR-01 | A nightly job (00:30 tenant local time) shall, for every active loan: update item statuses, recompute DPD and arrears (R-DPD), assess penalties (R-PEN), and expire stale approvals (FR-ORG-08). | Rerunning for the same business date changes nothing. | MVP |
| FR-ARR-02 | The job shall record a daily snapshot per loan (business date, principal outstanding, arrears, DPD) for portfolio trend and PAR history reports. | One row per active loan per business date; a rerun upserts. | MVP |
| FR-ARR-03 | The loan view shall show arrears amount, DPD, PAR bucket (chapter 14) and assessed penalties. | Tested with fixed clocks. | MVP |
| FR-ARR-04 | A branch manager shall request a waiver of all or part of an assessed penalty (or, with accountant role, of fees or interest) with a reason; the waiver is maker-checker and is recorded on the item as `waived`. | Waived penalties are never posted to income. Waiving principal is not allowed (that is a write-off). | MVP |

## 3.22 Closure, write-off and restructure (LCL)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-LCL-01 | A loan whose principal, interest and fees are all paid or waived and whose penalties are paid or waived shall close automatically, record the closing date, and queue the closure SMS. | Collateral linked only to closed loans shows "eligible for release". | MVP |
| FR-LCL-02 | An accountant shall request write-off of an active loan with a reason. On approval: the outstanding principal is posted to write-off expense against loans receivable (6.6.3), unpaid interest, fees and penalties are recorded as written off in the subledger, and the loan becomes `written_off`. | The loan stays visible in reports with its written-off amounts. | MVP |
| FR-LCL-03 | Repayments on a written-off loan shall be recorded as recoveries and posted to bad debt recovered income. | Tested. | MVP |
| FR-LCL-04 | A branch manager shall request a restructure of an active loan: a new loan is created for the member with principal equal to the old loan's outstanding principal, with new terms; the old loan's unpaid interest, fees and penalties are carried to the new loan's first schedule item as memorandum `carried_arrears` (never capitalised into principal); on approval the old loan becomes `restructured` and links to the new loan. | Journal: principal moves between the two loans within loans receivable (no cash). Restructured loans are flagged in portfolio reports for 12 months. | P2 |
| FR-LCL-05 | A top-up (new loan that repays an existing loan from its proceeds) shall be supported as a disbursement whose proceeds are partly applied to the old loan's payoff. | Both loans' journals post in one transaction. | Later |

Built in increment 5 (#108, ADR-026): FR-LCL-01 to FR-LCL-03. Closure is automatic when a
repayment settles every item; a reversal that leaves something unpaid reopens the loan (its released
pledges stay released). Write-off is requested by an accountant with a reason (`loan_write_off`, no
threshold), posts the outstanding principal, records every unpaid component in the items'
`written_off_minor`, and zeroes the loan's outstanding columns; with no principal outstanding it is
refused (`nothing_to_write_off`). A repayment on a written-off loan is recorded as a `recovery`
with no allocation rows. The closure SMS is deferred to increment 6.

## 3.23 Collateral register (COL)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-COL-01 | A loan officer shall register a collateral item for a member with: type (`land_title`, `vehicle_logbook`, `vehicle`, `national_id`, `household_item`, `other`), description, reference number (title number, logbook number, plate number, or ID number), owner name and owner relationship to the member (self, spouse, other), estimated value, and photos or scans. | `vehicle` requires a plate number, normalised to upper case without spaces. A plate or title number already pledged on an open loan in the tenant raises `collateral_already_pledged`. Until loans exist (increment 4), `collateral_already_pledged` applies to any item of the same type and normalised reference that is not `released` or `disposed`. | MVP |
| FR-COL-02 | A collateral item shall have valuations over time (valuer, date, market value, forced sale value); the latest valuation's forced sale value, or the estimated value if none, is the collateral value used for cover. | Cover in FR-ORG-07 uses this value. | MVP |
| FR-COL-03 | Custody status shall be `pledged` (owner keeps the item), `in_custody` (the tenant holds the item or document, with storage location), `released`, `seized` or `disposed`. Every change is an event with date, user, location and note. | Events are append only and shown as a timeline. Allowed moves: `received_into_custody` pledged to in_custody, `moved` within in_custody, `seized` from pledged or in_custody, `disposed` from seized, `note` with no change; release has its own route (FR-COL-04). Anything else is 409 `invalid_status_transition`. | MVP |
| FR-COL-04 | Release shall be a maker-checker action allowed only when every loan it secures is `closed`, `cancelled` or `rejected`; release records who collected the item and when. | Release while a linked loan is active fails with `collateral_secures_open_loan`. Built as the `collateral_release` approval action; the open-loan check joins with loans (increment 4). Enforced since #41: `collateral_secures_open_loan` while a draft, submitted, appraised, approved or active loan holds the item. | MVP |
| FR-COL-05 | The tenant shall be able to disable collateral types it does not accept (setting). | A disabled type cannot be registered. | MVP |
| FR-COL-06 | A collateral register report shall list items by status, type, branch and linked loan status, including items `in_custody` on closed loans (release overdue). | Chapter 14. | MVP |

## 3.24 Savings (SAV)

How the pilot tenant's savings products work is an open question
(`docs/specs/lending-mvp-scope.md`). These requirements define a configurable product
that covers common voluntary savings.

**Built in increment 9 (issue #151, ADR-032, migration `V30`).** The rules below as built:

- A product also carries withdrawal limits (`max_withdrawal_minor` per withdrawal and
  `max_withdrawals_per_month` per calendar month, both optional) and a minimum balance for interest
  (`min_balance_for_interest_minor`: a day or a month below it earns nothing). Its interest terms
  (currency, rate, calculation, posting frequency, minimum for interest) cannot change once an
  account uses it (`product_in_use`); a change is a new product. Archiving stops new accounts.
- The first deposit on an account must reach the product's minimum opening balance
  (`below_minimum_opening`). Opening an account moves no money.
- A movement is dated today or earlier, not before the account opened, and after the last day the
  nightly end of day has written (`value_date_closed`); a withdrawal is dated the day it executes.
- A withdrawal may take at most the balance less any hold, the minimum balance and the withdrawal
  fee (`insufficient_balance`), within the product's limits (`withdrawal_limit_exceeded`,
  `withdrawal_count_exceeded`). The fee is its own movement and journal, linked to the withdrawal.
  At or above the tenant's threshold it is the `savings_withdrawal` action; every check runs again
  when the checker approves.
- A deposit or a withdrawal (with its fee) can be reversed through `savings_reversal`, always with
  a checker, while the account is not closed and, for a deposit, still holds it. Interest and fees
  are not reversed on their own.
- An account may be frozen and unfrozen with a reason by a holder of
  `lending.savings.withdraw_approve`; a frozen or dormant account takes deposits but pays nothing out.
- Interest: `minimum_monthly_balance` counts a month only when the account was open on every day of
  it and the period covers all of it. Interest is dated the period end; when that month is closed
  in the ledger the journal is dated the run date. Closing (FR-SAV-07) posts interest to yesterday,
  dated the closing day, then pays out the whole balance with no fee.
- Deposits and withdrawals by staff queue the `savings.deposit` and `savings.withdrawal` SMS of
  chapter 11 section 11.3.1 in the outbox; no SMS is sent until an SMS sender exists
  (pending ADR-013).

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-SAV-01 | A tenant admin shall create a savings product with: code, name, currency, interest rate (bp per year), interest calculation (`none`, `daily_balance`, `minimum_monthly_balance`), interest posting frequency (`monthly`, `quarterly`, `yearly`), minimum opening balance, minimum balance, withdrawal fee (flat), and dormancy days. | Validation per field. | P2 |
| FR-SAV-02 | A loan officer shall open a savings account for a member on a product; the account number is `SV` plus 6 digits. | A member may hold several accounts. | P2 |
| FR-SAV-03 | A cashier shall record deposits (idempotency key required) and withdrawals. A withdrawal may not take the balance below the minimum balance plus any hold. Withdrawals at or above the tenant threshold are maker-checker. | Tests for limit and threshold. | P2 |
| FR-SAV-04 | Each transaction shall store the running balance after it, and the account balance shall be updated under a row lock. | 50 concurrent deposits produce the exact final balance. | P2 |
| FR-SAV-05 | Interest shall be computed per product: `daily_balance` sums end-of-day balance x rate / 365 over the period; `minimum_monthly_balance` uses the lowest end-of-day balance in each month x rate / 12. Interest is rounded once per posting (R-ROUND) and posted on the last day of the posting period by the nightly job. | Worked examples with fabricated balances in tests; posting is idempotent per account and period. The nightly end of day writes one end-of-day balance per account and day and catches up missed days. | P2 |
| FR-SAV-06 | An account with no member-initiated transaction for the product's dormancy days shall become `dormant`; withdrawals from a dormant account require a branch manager to reactivate it. | Tested. | P2 |
| FR-SAV-07 | A member may close an account, withdrawing the whole balance after any final interest posting. | Closing posts interest to date first. | P2 |

## 3.25 Investments (INV)

How the pilot tenant's investment products work is an open question. These requirements
define a fixed-term investment where the member places money for an agreed term and
return.

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-INV-01 | A tenant admin shall create an investment product with: code, name, currency, allowed terms (in months), return rate (bp per year), return payout (`at_maturity` or `monthly`), minimum and maximum amount, whether early withdrawal is allowed, and the early withdrawal rule (`forfeit_return`, or `reduced_rate_bp`). | Validation per field. | P2 |
| FR-INV-02 | A loan officer, or a member from the portal, shall open an investment with product, amount and term; the account number is `IV` plus 6 digits and the status is `pending_funding`. | Portal-opened investments appear in a staff queue. | P2 |
| FR-INV-03 | A cashier shall record funding (idempotency key required); the investment becomes `active` with a start date and a maturity date (start plus term, R-TERM month rule), and the agreed return is computed as `round(amount x rate x term_months / 12)`. | Posting per 6.6.3; investment certificate PDF queued. | P2 |
| FR-INV-04 | For `monthly` payout, the nightly job shall pay the monthly return on each monthly anniversary as a payable to the member (credited to the member's savings account if they have one, else to investments payable for cash collection). | Idempotent per investment and month. | P2 |
| FR-INV-05 | On the maturity date the nightly job shall mark the investment `matured` and post the return due. The member chooses `payout` (cashier pays principal plus unpaid return) or `rollover` (a new investment is created from principal, or principal plus return, on the current product terms). | Default when no choice is recorded within 7 days: stays `matured`, and a reminder is sent. | P2 |
| FR-INV-06 | Early withdrawal, if the product allows it, shall be maker-checker and apply the product's early withdrawal rule. | Worked example for each rule. | P2 |
| FR-INV-07 | The system shall send a maturity reminder 7 days before maturity. | Tested with fixed clock. | P2 |

## 3.26 Collections (CLN)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-CLN-01 | Every active loan shall have a responsible officer (default: the officer who created the application). A branch manager shall reassign loans individually or in bulk. | Reassignment is audited. | MVP |
| FR-CLN-02 | The due list shall show, for a date or date range and branch or officer, every schedule item due, with member, phone, amount due, amount paid and outstanding. | Chapter 14 definition. | MVP |
| FR-CLN-03 | The arrears list shall show every loan with DPD > 0, grouped by PAR bucket (1 to 30, 31 to 60, 61 to 90, over 90), with officer, arrears, DPD, last payment date and last collection action. | Chapter 14 definition. | MVP |
| FR-CLN-04 | An officer shall log a collection action on a loan: `call`, `visit`, `sms`, `letter`, `promise_to_pay` (with promised date and amount), `other`, with a note. | Actions are append only and shown as a timeline on the loan. | MVP |
| FR-CLN-05 | A promise to pay shall be marked `kept` when repayments between the promise and the promised date reach the promised amount, else `broken` the day after the promised date. | Nightly job tested. | MVP |
| FR-CLN-06 | Officer performance shall be reported: portfolio, disbursements, collections due versus collected, PAR by officer. | Chapter 14. | MVP |

## 3.27 Member self-service (MSS)

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-MSS-01 | A signed-in member shall see their loans with balance, next due date and amount, DPD, and the full schedule. | Only the member's own records are reachable; a test requests another member's loan id and gets 404. | P2 |
| FR-MSS-02 | A member shall see savings balances and transactions, and investments with maturity and return. | As above. | P2 |
| FR-MSS-03 | A member shall apply for a loan (FR-ORG-09) and for an investment (FR-INV-02). | Creates the staff queue item. | P2 |
| FR-MSS-04 | A member shall pay a loan instalment, deposit to savings or fund an investment by mobile money (FR-PAY-01). | Depends on pending ADR-011. | P2 |
| FR-MSS-05 | A member shall download statements and receipts. | Signed URL per FR-DOC-03. | P2 |
| FR-MSS-06 | The member area shall work on a slow connection and show the last synced data offline (chapter 4 NFR-OFF). | Tested with the network disabled after one sync. | P2 |
| FR-MSS-07 | Members shall use a USSD menu for balance, next due amount, mini statement and pay (chapter 11). | Depends on pending ADR-013. | Later |

## 3.28 Retail (RET)

The retail vertical (ADR-020): a generic module for shops that sell stock from several branches.
Stock is a ledger of append-only movements; prices live on the product and change only inside the
event that carries them; every financial event posts to the general ledger. Build order and open
questions are in `docs/specs/retail-mvp-scope.md`; the import of a pilot spreadsheet is described
by `docs/specs/retail-pilot-data-dictionary.md`. `MVP` here is the retail first release.

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-RET-01 | Products shall have a code, description, category, unit of measure, current cost and sell price in minor units with the tenant currency, and an active flag. Categories and units are managed per tenant. | Codes are unique ignoring case and surrounding spaces, Unicode spaces included, after NFKC; a code with a control character or a Unicode space inside is refused; names of categories and units are unique ignoring case; amounts are integers (ADR-004); a non-price edit never changes a price. | MVP |
| FR-RET-02 | Every change to a product's cost or sell price shall record the old and new values, who, when, the source (`initial`, `manual`, `purchase`, `import`) and its reason or source document. | The history table is append-only for the application and rejects UPDATE and DELETE by trigger; a manual edit needs `retail.price.edit`; the price and its history row commit together. | MVP |
| FR-RET-03 | Stock movements shall be append-only, with a signed quantity per branch and product. The balance per branch and product is derived from them, kept in the same transaction, and reconciled against the sum of movements. A sale, usage or damage movement that would take a branch's balance below zero is always refused; there is no setting to allow it (ADR-020 decision 4). Imported history (`legacy_balance`) and stock-take adjustments are not guarded; a negative balance can only come from imported history and is flagged on every stock view until the first stock-take. | No route or job edits a balance; the reconciliation job reports any branch and product whose balance differs from the sum of its movements; a sale, usage or damage past the branch's stock is refused with `insufficient_stock` and leaves nothing behind; two concurrent sales of the last unit yield exactly one success and one refusal. | MVP |
| FR-RET-04 | A sale shall have lines, a branch from the user's context, a payment method (`cash`, `mobile_money`, `bank`) or `credit`, an optional buyer, and snapshots of unit cost and unit price on each line. A line whose unit price is not above the product's current cost is refused unless the user holds `retail.price.below_cost` (ADR-020 decision 5). A void reverses the stock movements and the journals. | A later price change does not change a recorded sale or its profit; a line priced at or below cost is refused with `price_below_cost`, whose message never carries the cost; a void writes reversing movements and reversing journal entries and needs `retail.sale.void`; a sale is idempotent on its `Idempotency-Key`. | MVP |
| FR-RET-05 | A credit sale shall record the buyer and a proposed payment date. Payments against it reduce the trade debtor balance and may be partial. | A payment above the balance is refused; the sale balance and the debtors account move together. | MVP |
| FR-RET-06 | A restock shall record the supplier and the quantity per branch. A cost and sell price on its lines update the product and its price history in the same transaction as the movements. | The latest restock line wins, including two lines for one product in one request; the history rows name the purchase. | MVP |
| FR-RET-07 | Usage and damage reports shall reduce stock, record a reason and be valued at the product's cost at the time. | Each line carries its unit cost snapshot; the shrinkage journal equals the sum of the lines. | MVP |
| FR-RET-08 | A stock-take shall record counted quantities per branch and product with the balance at the time of the count (`expected_qty`), show the variance, and on commit write an adjustment movement per line of counted less `expected_qty`, so sales, restocks, usage and voids recorded between count and commit stay in the balance. | Committing needs `retail.stocktake.commit`; the adjustment is applied under the balance lock; a sale between count and commit is not undone and posts no gain; when movements since the count would make the adjusted balance negative, the commit is refused with 409 `stock_moved_since_count` naming the lines to recount and nothing is written; a committed stock-take cannot be committed again. | MVP |
| FR-RET-09 | Valuation shall show, per branch and product, the quantity times cost and the quantity times sell price, with totals. | Cost columns are absent for a user without `retail.profit.read`. | MVP |
| FR-RET-10 | Daily profit per branch and day shall equal sale line totals less their cost snapshots less usage and damage at cost plus the net stock-take difference at cost (a loss is negative, a gain positive, shown as its own line on the day the stock-take is committed, #121), and be readable only with `retail.profit.read`. | Voided sales are excluded; profit is read from the append-only sale lines, not the sale header, and a changed header leaves it unchanged; a test with fabricated figures checks the arithmetic; a sales user gets 403. | MVP |
| FR-RET-11 | Sales, their cost, restocks, usage, stock-take adjustments and voids shall post balanced entries to the right branch through `post_entry`, in the same transaction as the event. | One entry per branch per event; the database balance trigger holds; a void posts a reversing entry for each original entry. | MVP |
| FR-RET-12 | The pilot spreadsheet and a generic catalogue template shall be importable; history marked historical posts no journals; a legacy balance movement ties each balance to the source; re-running is idempotent. | Golden test with a fabricated fixture (increment R5). | MVP |
| FR-RET-13 | Retail permissions and the sales and admin role mapping shall be seeded, with branch scope applied per permission (ADR-017). | `PermissionMatrixIT` compares the seeded rows with chapter 8; a sales user holds exactly the sales column. | MVP |
| FR-RET-14 | Every retail write shall be audited. | Each create, edit, price change, sale, void, payment, purchase, usage report, stock-take commit, transfer and transfer void writes an `audit_log` row in its transaction. | MVP |
| FR-RET-15 | Phone-first PWA screens shall cover the flows above, gated by permission. | Built in increment R6 against chapter 7 section 7.11.20. | MVP |
| FR-RET-16 | A stock transfer shall move one or more products from a source branch to a different, active destination branch of the tenant in one transaction, at the source's current cost, with a business date (default today in the tenant's zone, not in the future) and an optional note. The caller acts on the source branch (`retail.stock.transfer`, ADR-017). Each line writes a `transfer_out` movement at the source and a `transfer_in` movement at the destination, both linked to the transfer, and the transfer posts one entry per branch through inter-branch clearing (ADR-004). A void moves the stock back and reverses both entries while the destination still holds what arrived (issue #84, ADR-020 amendment). | A quantity above the source's balance is refused with `insufficient_stock` and nothing is written; opposite transfers of the same products sent at the same time neither deadlock nor oversell; both branches' history shows a transfer, not an adjustment; each entry balances, the tenant's valuation at cost is unchanged and each branch's moves by the transfer's cost; the audit row and any response for a caller without `retail.profit.read` carry no cost; a replayed `Idempotency-Key` moves nothing again; a void after the destination sold, used or moved part of it is refused with `transfer_stock_moved`. | MVP |
| FR-RET-17 | Expense categories and items shall be tenant data: a category maps to a ledger expense account, an item belongs to one category and may be flagged `requires_explanation` (the pilot's "others"). Beneficiaries and advance parties shall be one list of cash parties (name, optional contact, kind) that a permitted user can extend when recording. Nothing is deleted; a category, item or party is deactivated. | Names are unique ignoring case within their parent; an item of another category is refused (422 `item_not_in_category`); an inactive category or item cannot be chosen but still shows on old records; the lists need `retail.expense.manage`; no category list is hard coded (ADR-022 decision 17). | Cash book |
| FR-RET-18 | Daily savings shall record, per branch and business date, the amount set aside. The server suggests the day's profit (FR-RET-10) times the tenant rate `retail.cashbook.savings_rate_bp` (default 5000), rounded half up and never below zero, and uses it when the request omits the amount. The suggestion is read through a call that returns an opaque `suggestion_token`, and the create echoes that token (FR-RET-19). The screen shows the day's total sold. One active record exists per branch per date. | A second active record for a branch and date is refused (409 `savings_exists`); a voided record frees the slot; the suggestion is worked by hand with fabricated figures including a loss day (suggestion zero) and a rate change; a branch outside the caller's scope is 404; an amount of zero is valid and posts nothing; the profit, the suggestion and every savings amount (`suggested_minor`, `amount_minor` on savings rows and in reports, `savings_minor`) are absent from every response without `retail.profit.read`, except the amount the writer just typed, echoed to that writer; a default taken by a caller without it is not echoed. | Cash book |
| FR-RET-19 | A savings amount that differs from the suggestion (an overwrite) shall need a reason and `retail.savings.overwrite`, and shall be audited with its actor, branch, date and reason, without the amount, the suggestion or the profit. The overwrite is judged against the suggestion the caller was shown (the echoed `suggestion_token`), and a suggestion that changed since is refused with 409 `suggestion_changed`, never recorded as an overwrite. | A request with a changed amount and no reason is 422 `reason_required`; the audit row exists in the same transaction and carries only the flags `overwritten` and `default_applied` (a non-overwritten create stores `default_applied: true` and no amount, so no audit payload equals half the profit); a sale added between the form and the create changes the suggestion and the create is 409 `suggestion_changed` with the new token and writes nothing; a caller without `retail.profit.read` gets the token but no `suggested_minor`, and sending `amount_minor` (equal to the suggestion or not) is 422 `amount_requires_profit_access` with nothing written, so the answer is the same for every value and no amount can be probed; a caller with `retail.profit.read` but without `retail.savings.overwrite` sending a different amount gets 403 and sending the equal amount gets 201; `overwritten` is absent from the create response, the list rows and the savings report without `retail.profit.read`; a reader holding `core.audit.read` but not `retail.profit.read` cannot derive the profit from any audit payload (same method as RetailCostVisibilityIT); the record keeps `suggested_minor`, readable only with `retail.profit.read`. | Cash book |
| FR-RET-20 | Recording savings shall post a transfer from cash on hand to the savings reserve, not an expense, in the record's branch, in the same transaction. | Debit `savings_reserve`, credit `cash_on_hand` for the amount; profit and the income statement are unchanged by it; a void posts the reversal; the two entries balance (database trigger). | Cash book |
| FR-RET-21 | The system shall compute, per branch and business date, the expected amount to bank on the server: cash sales by `sale_date` plus cash payments on credit sales by `paid_on`, less cash sales voided on that day (every cash book record counts on its own business date and its reversal on the void date, so a past day is not restated; the voids of savings, expenses and advances add back, and of repayments subtract, on the void day), less cash purchases (restocks paid from the till, a total at cost) dated that day, less that day's savings, less cash expenses and advances paid out, plus cash advance repayments received. Credit, mobile money and bank sales are excluded. The banking form shall show it and prefill the amount banked with it. A caller without `retail.profit.read` is shown `cash_expected_minor` only, which is takings less cash sale voids, less cash expenses and advances paid out, plus cash repayments (with the expense, advance and repayment voids of the day), before cash purchases and before savings (purchase cost and savings are profit-derived, ADR-022 decision 13), and no figure net of them; `cash_purchases_minor`, `savings_minor` and the net `expected_minor` need `retail.profit.read`. | A day with one cash, one credit, one mobile money and one bank sale expects the cash sale only; a sale or an expense voided the same day nets to zero, a sale voided on a later day counts on its own day and reduces the void day through the `cash_sale_voids_minor` line, and a savings or expense record voided on a later day raises the void day through `savings_voids_minor` or `expense_voids_minor` and leaves its own day alone; the figure is identical in the form, the report and a direct computation from the rows; a client-sent expected amount is ignored; a back-dated sale changes the report but not the snapshot already stored on a banking record (`expected_minor`). | Cash book |
| FR-RET-22 | Cash banked shall record the branch, business date, amount banked, the instant it was entered and the user, and post a transfer from cash on hand to bank. Several records per branch and date are allowed. The difference between banked and expected shall be flagged: `shortfall`, `surplus`, `ok` (within the tenant tolerance `retail.cashbook.tolerance_minor`, default 0) or `not_banked`. | Debit `bank`, credit `cash_on_hand`; banking above the cash on hand is accepted and flagged, never refused; two partial deposits sum in the report; the flag is worked for each of the four states with fabricated figures; a void posts the reversal. | Cash book |
| FR-RET-23 | A banking report shall list, per branch and day, expected, banked, difference, flag, the running total of unbanked cash since the branch's first live day, and who entered each record. Days imported from the pilot are listed separately and are excluded from the running total, which starts at the first live day with nothing carried in. | The running total after each day equals the sum of expected less banked up to that day; imported historical days are shown separately, carry no running total and do not move the running total of the live days; the report respects branch scope; a user without `retail.cashbook.read` gets 403. | Cash book |
| FR-RET-24 | Company expenses shall record the branch, business date, category, item, optional beneficiary, total cost, an explanation (required when the item requires one) and an optional receipt document, and post a debit to the category's expense account and a credit to cash on hand. | An expense with an item that requires explanation and none is refused (422 `explanation_required`); the expense account is the category's, or `operating_expenses` when none is mapped; the entry balances; a void posts the reversal; amounts are integers and at most 10^13. | Cash book |
| FR-RET-25 | Cash withdrawn from the bank shall record the branch, business date and amount, need `retail.withdrawal.record` (owner or admin), and post a transfer from bank to cash on hand. | Debit `cash_on_hand`, credit `bank`; a sales user gets 403; a withdrawal above the bank balance is accepted with a `bank_balance_negative` warning in the response, never refused; a void posts the reversal. | Cash book |
| FR-RET-26 | Advances to the owner or the company shall record the source branch, a party, who took the money (optional), principal, purpose and business date, with a sequential number, and shall not use the lending module. Repayments shall be recorded against an advance whose balance is above zero, by cash, mobile money or bank. The balance is principal less `repaid_minor`; `repaid_minor` is a stored running total that only the repayment insert and void change, in the same transaction, and the database refuses any value other than the sum of the non-voided repayments. | Debit `owner_advances` and credit the branch's cash on hand on payout; the reverse on a repayment; a repayment above the balance is refused (422 `repayment_exceeds_balance`) and a repayment against a settled advance is refused (422 `advance_settled`); the balance and the account move together, and `repaid_minor` equals the sum of non-voided repayments for every advance after concurrent repayments and voids (a direct UPDATE to another value is refused); an advance with repayments cannot be voided (409 `advance_has_repayments`); the module has no dependency on `lending.*` (`ModularityTest`); needs `retail.advance.create` and `retail.advance.repay` (owner or admin). | Cash book |
| FR-RET-27 | A daily cash summary per branch and date shall show opening, cash takings, cash sale voids, the voids of the other cash book kinds (`savings_voids_minor`, `expense_voids_minor`, `advance_voids_minor`, `repayment_voids_minor`, `banking_voids_minor`, `withdrawal_voids_minor`, each on the day the void is made), cash purchases, savings set aside, expenses paid, advances paid out, advance repayments received, withdrawals received, cash banked and closing, the expected amount to bank, and the unbanked running total. Closing shall equal the ledger's cash on hand balance for the branch at the end of the day, any difference being shown as `other_movements_minor`. `cash_purchases_minor` (a total at cost) and every figure that embeds savings (`savings_minor`, opening, closing, `other_movements_minor`, expected to bank and the unbanked running total) is absent without `retail.profit.read`. | With fabricated data the closing equals the ledger balance and `other_movements_minor` is zero, including on a day holding a savings void and an expense void made for an earlier day, and on that earlier day; a manual journal to cash on hand shows as `other_movements_minor`; imported days show `ledger_basis: false`; profit, `cash_purchases_minor` and every figure embedding purchases or savings appear only with `retail.profit.read`; a cash restock changes `cash_expected_minor` for no caller without it (the figure is identical with and without the restock). | Cash book |
| FR-RET-28 | Cash book records shall be voided, never deleted or edited. A void needs `retail.cashbook.void` and a reason, reverses each of the record's journal entries, and is audited. A void posts on the day it is made, never on the record's day, and a back-dated record posts on its own business date. Every cash book write shall be money-moving: it requires an `Idempotency-Key`, a branch within the caller's scope, a business date in the tenant's time zone that is not in the future, and integer minor units. | A replayed key returns the first response and posts once (`Idempotent-Replayed`); a missing key is 422 `idempotency_key_missing`; a second void is 409; no UPDATE or DELETE of an amount is possible for `bms_app` (guard trigger); a date after today in the tenant's zone is 422 `future_date`; a record at 23:50 local belongs to that local date. | Cash book |
| FR-RET-29 | Reports shall cover the expenses by category and item for a branch and period with totals, the savings by branch and day, and the outstanding advances by party, each within the caller's branch scope. | Totals equal the sum of non-voided rows; every savings amount and any profit-derived column are absent without `retail.profit.read`; voided rows are excluded and a flag includes them; a worked example with fabricated figures is a golden test. | Cash book |
| FR-RET-30 | The `import-retail` command shall import the pilot's cash book tabs (expense categories and items, parties, daily savings, expenses, cash banked, withdrawals, advances and their payments) as historical rows with no journals, and one opening journal per branch, dated the day before the first live day, for the cash, bank and savings reserve balances and one line per outstanding advance (with the advance as subledger). An imported advance takes the next live `advance_no`; the source id is never reused as the number. Re-running adds nothing. | A golden test on a fabricated fixture checks counts and sums, that no journal exists for a historical row, that the opening entry balances against `opening_balance_equity`, has one subledger line per outstanding advance and is dated the day before the first live day (so the first live day opens at the carried balance with `other_movements_minor` zero), that the next live advance number is above every imported one and no number repeats, and that a second run adds nothing; an unknown category or item is created as written and reported; a row with a bad date or amount is skipped and listed with its file and line. | Cash book |
| FR-RET-31 | Phone-first PWA screens shall cover daily savings, cash banked, expenses, withdrawals, advances and repayments, the daily cash summary and the cash book reports, gated by permission, with the pilot's bottom bar order (Sales, Banked, Savings, Expenses, Advances) and the records grouped by shop, year and month. | Built after the API in the cash book increment; a user without a permission does not see its tab; the expense item list depends on the chosen category; the explanation field appears when the item requires it. | Cash book |
| FR-RET-32 | Cash book permissions shall be seeded and mapped to the roles with branch scope (ADR-017): `retail.cashbook.read`, `retail.savings.record`, `retail.savings.overwrite`, `retail.banking.record`, `retail.expense.record`, `retail.expense.manage`, `retail.withdrawal.record`, `retail.advance.create`, `retail.advance.repay`, `retail.cashbook.void`. By default the tenant admin holds all ten and the sales role holds only read, savings record, banking record and expense record; `retail.savings.overwrite` is withheld from the sales role pending ADR-022 open question 5. Every write shall be audited. | `PermissionMatrixIT` compares the seeded rows with chapter 8; the sales role holds exactly its column; each create, void, overwrite and repayment writes an `audit_log` row in its transaction. | Cash book |

FR-RET-17 to FR-RET-32 are the retail cash book (ADR-022, status Proposed; design only until the Owner confirms its open questions). Their phase `Cash book` means the cash book increment that precedes the pilot cutover; it is not built yet. The pilot comparison is `docs/specs/retail-cash-book.md`.

## 3.29 Self-onboarding (ONB)

A business applies on a public page of the platform host; the platform operator verifies it and
activates it, which creates the tenant and sends the first admin a one-time link (ADR-024,
`docs/specs/self-onboarding-and-subscriptions.md`). This section is build step 1 of the spec: the
payment is a free text note by the operator; the price book, payment records, the trial clock,
reminders and agents are later steps. An application is not a tenant.

| ID | Requirement | Acceptance criteria | Phase |
|---|---|---|---|
| FR-ONB-01 | A public sign-up form on the platform host shall take the business name, contact name, email and phone, country (`UG`, `KE`, `TZ`, `RW`), modules wanted (the module keys, `lending`, `retail`), term (`monthly`, `annual`), the way in (`trial`, `paid`), an optional agent code (recorded, not used) and a short free text. Input is trimmed, normalised (NFC, no control characters) and capped; a Uganda phone in any local form is stored as E.164, other countries in international form. | Each invalid field is refused with `validation_failed` or `invalid_phone`; an unknown field is refused. | MVP |
| FR-ONB-02 | The email shall be confirmed by a one-time link (256 random bits, stored as SHA-256 only, valid 7 days, in the URL fragment only) and a "Confirm my email" button on the page it opens, before the application reaches the operator queue; opening the link alone confirms nothing, so a mail scanner cannot. The confirmation email carries only the server's reference and the link, never text the applicant typed. The same link opens the applicant page. Addresses are ASCII only. | An unconfirmed application is not in the queue; the confirmation happens once; the email's only placeholders are `reference` and `link`. | MVP |
| FR-ONB-03 | The public endpoints shall resist abuse: 5 applications an hour per client address (an IPv6 client counted by its /64) and 30 link reads per 10 minutes per address (429 `rate_limited`); counted in the database, at most 3 confirmation emails per mailbox in 24 hours and global caps of new applications and confirmation emails per hour (`BMS_SIGNUP_MAX_APPLICATIONS_PER_HOUR`, default 30, and `BMS_SIGNUP_MAX_VERIFICATION_EMAILS_PER_HOUR`, default 60), over which a request is answered 202 and nothing is created or sent, and the first request over a global cap writes an audit row and one Telegram alert for the hour; a hidden field that, when filled, drops the request with the normal answer; one open application per mailbox (lower case, +tags and Gmail dots ignored); and the same answer for a known and an unknown email (a known mailbox gets a fresh link at the address just submitted, which while unconfirmed becomes the stored one, so a variant submitted first cannot squat the mailbox). | `OnboardingIT` covers each limit, a spoofed X-Forwarded-For, the /64 grouping, the mailbox and global bounds, the honeypot, the address variants and the identical answers. | MVP | | MVP |
| FR-ONB-04 | The applicant page shall show the reference, the status in plain words, the modules, the operator's question while `needs_info` with a form to answer it (the answer puts the application back to `submitted`), and the reason when rejected. It shows no contact details. | A malformed, unknown or replaced link gets 404 `link_invalid`. | MVP |
| FR-ONB-05 | The operator portal shall list confirmed applications newest first with a status filter and the count per status, and show one application with its possible repeats: other applications with the same email, phone or normalised business name, and tenants with the same normalised name (spec section 4.2 item 4). | Normalisation ignores case, punctuation and the suffixes Ltd, Limited, Co, Company, SMC, Enterprise(s). | MVP |
| FR-ONB-06 | The operator shall Verify (from `submitted` or `needs_info`), ask for more information with a note the applicant sees (from `submitted` or `verified`) or Reject with a reason (from any open status). Needs info and Reject email the applicant a fresh link. | A move from any other status is 409 `application_state`; each decision writes `platform_audit_log`. | MVP |
| FR-ONB-07 | Activate (operator only, `verified` only) shall take the modules, term, way in (`trial`, or `paid` with a required note of the payment received), an optional plan (default `starter`), an editable slug suggested from the business name (FR-TEN-02) and an ignored agent code. In one transaction it creates the tenant through the same path as FR-TEN-01 with the applicant as the invited first tenant admin (72 hour link, FR-IAM-01), starts the subscription as `trial` or `active`, marks the application `activated` with who and when, writes `platform_audit_log`, and queues the activation email. The links use `BMS_TENANT_HOST_PATTERN` and `BMS_PLATFORM_HOST`, never a request header. A second Activate does nothing and says so. | `OnboardingIT`: exactly one tenant, a working invitation, one email row, one audit row, also under four concurrent calls; a taken slug rolls everything back. | MVP |
| FR-ONB-08 | A newly confirmed application shall write an operator alert to the outbox (FR-NTF-11) in the same transaction. | One alert set per application. | MVP |
| FR-ONB-09 | A scheduled job shall expire applications whose email is not confirmed within 14 days and delete `rejected` and `expired` applications 90 days after they closed. | `OnboardingIT` with backdated rows. | MVP |

