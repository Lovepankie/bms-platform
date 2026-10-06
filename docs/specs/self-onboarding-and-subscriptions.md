# Self-onboarding, subscriptions and agents

**Status:** Draft for review · **Owner:** Hillary · **Decision record:** ADR-024 · **Applies to:** every vertical (lending, retail, later others)

All worked examples use fabricated round figures in UGX. The real list prices, discounts and any
agent arrangement are configuration entered by the platform operator in the operator portal. They
are not in this repository (no commercial terms in git).

## 1. Why

Today a platform operator creates every tenant through the platform API, with no screen, and a tenant
cannot show interest, pay or change what it uses without a message to the operator. The goal is the
usual product path: a business applies on a public page, the operator verifies it, the business pays
or tries one month free, receives a signed activation link, and then runs its own shops, staff and
modules from its admin dashboard. The set of modules a tenant has paid for decides what it can use
(FR-TEN-03, FR-TEN-04).

Payment is manual in this release: mobile money or a bank transfer to the operator, claimed by the
tenant and confirmed by the operator. A payment gateway is out of scope but the design keeps the
payment record gateway-ready.

Non-goals: card payments and gateways, tax invoicing, refunds, a third-module price, per-branch or
per-staff add-on pricing, agent logins.

## 2. Actors

| Actor | What they do |
|---|---|
| Applicant | Fills the public sign-up form; has no account yet. |
| Platform operator | Verifies applications, agrees terms, confirms payments, sets prices, manages agents and commissions. Uses the operator portal on the platform host. |
| Tenant admin | After activation: manages branches, staff and roles inside the plan's limits, sees billing, claims payments. |
| Agent | An external person who brought a customer. Has no login in this release; exists as a record linked to subscriptions. |

## 3. Pricing model (FR-SUB-01 to FR-SUB-04)

- A **price book** holds, per number of modules (1, 2, 3 or more), a monthly price, and one
  **annual factor** (months charged for a year; the annual price is the monthly price times the
  factor). Example: one module 40,000 a month; two modules 60,000 a month (the second module is
  discounted); annual factor 10, so one module is 400,000 a year and two modules 600,000 a year.
- The price of a subscription is the price book entry for its number of modules at the chosen term,
  **unless the operator sets a deal price** on that subscription (for example for a customer an agent
  negotiated). A deal price is stored on the subscription as an agreed amount per term; the price
  book is not changed. The customer sees one flat figure.
- Adding a module mid-term re-prices the subscription from the next period; the operator can charge
  the difference once as a separate payment. Enabling a module is idempotent: its default chart,
  roles and permissions are seeded through the same platform function as at tenant creation, and
  enabling it twice changes nothing. Removing a module hides it (404 `module_not_enabled`) and keeps
  all its data, so it can be enabled again (FR-TEN-03).
- Entitlement is the tenant's enabled-module row, checked before any permission. The price book is
  keyed by module key, so a new vertical needs a key and no other change.
- Limits (branches, staff users, active records) come from the plan as today (FR-TEN-04, chapter 6
  `plans`). A plan no longer carries a price.
- A **complimentary** subscription (price 0, no payments expected) is allowed and audited, for
  internal and partner tenants.

## 4. Applications and the two ways in

An application is a row in its own table, not a tenant. Nothing is provisioned until the operator
acts, so a stranger cannot consume tenant resources (the staging host is one small machine).

Statuses: `submitted` -> `needs_info` -> `verified` -> `activated`, or `rejected` / `expired`
(unverified after 14 days).

**Form fields:** business name, contact name, contact email, contact phone, country, modules wanted,
term (monthly or annual), the way in (below), optional agent code, a short free text. Email is
verified by a one-time link before the application reaches the operator queue.

### 4.1 Way A: subscribe now

1. The operator verifies the applicant (call or message) and confirms the terms: modules, term,
   price (list or deal), agent if any.
2. The applicant sees the payment instructions and a payment reference on their application page
   (opened through the email link). They pay and enter the reference, amount and date: "I have paid".
3. The operator confirms the payment in the portal. **One action** creates the tenant, enables the
   modules, starts the subscription as `active` for the paid period, records the payment, accrues the
   agent commission if any, and sends the activation link.
4. The admin opens the link, sets a password and enrols two-factor, and lands in the dashboard.

### 4.2 Way B: one month free

1. Same verification and terms as Way A, but no payment.
2. The operator activates: the tenant is created, the subscription starts as `trial`, the activation
   link is sent.
3. The **trial clock starts when the admin first signs in**, not when the link is sent, so a delay in
   getting started does not burn the free month. The trial lasts 30 days.
4. Rules: one free month per business and per module, matched on verified phone, email and the
   normalised business name; the operator sees a warning on a possible repeat. The operator can switch
   off new trials (a global switch) when capacity is short.
5. During the trial the tenant admin can pay at any time. A payment during a trial starts the paid
   period at the end of the trial: no free day is lost.
6. Reminders go to the admin at day 20, day 27 and the last day.
7. If the trial ends unpaid the tenant becomes `suspended`: read only, sign in and export work, no
   data is deleted (FR-TEN-06). The operator can resume it on payment.

The operator can also create a tenant directly, as today, for assisted customers; it uses the same
records so nothing is special-cased.

## 5. Subscription lifecycle (extends FR-TEN-05)

States stay `trial`, `active`, `past_due`, `suspended`, `cancelled`. New columns: term, agreed price,
period start and end, trial end. Cancelling takes effect at the end of the paid period. No refunds in this release.

**Automatic transitions are safe by construction, because a read-only tenant cannot sell at its
till.** A scheduled job (db-scheduler, ADR-008) may move `active` to `past_due` and `past_due` to
`suspended` only when all of these hold:

1. The tenant has automatic transitions switched on (a per-tenant setting, **off by default**; the
   operator switches it on for tenants that agreed to it).
2. The tenant has been sent reminders: at 7 days and 1 day before the period end, on the due day, and
   on each of the first days of `past_due`, by email to the admin. The scheduled transitions ship
   **after** the reminders do (build step 3), never before.
3. The tenant is not complimentary and not a pilot tenant (those are exempt, a flag on the
   subscription).
4. For a tenant with recent trading (a sale, restock, loan or repayment in the last 14 days), the job
   does not suspend by itself: it puts the tenant on the operator's "confirm before suspending" list
   with the last trading date, and the operator confirms or extends the grace. The grace period
   before `suspended` is a platform setting (default 7 days).

A tenant can always be moved by hand by the operator, as today (FR-TEN-05), with the reason audited.

## 6. Payments (FR-PAY-01 to FR-PAY-05)

A payment is a record, whatever the channel: `claimed` by the tenant (or entered by the operator),
then `confirmed` or `rejected` by the operator. Fields: amount, method (`mtn_momo`, `airtel_money`,
`bank_transfer`, `other`), the sender's reference, date, the period it covers, who confirmed and when.
Confirmation is idempotent and audited. A confirmed payment is never edited; a mistake is corrected
with a reversing entry and a new payment. The same record shape lets a gateway confirm payments later.

## 7. Agents and commissions (FR-AGT-01 to FR-AGT-05)

- An **agent** is a record (name, phone, status). A subscription links to zero or more agents.
- A **commission rule** belongs to a subscription and an agent and is effective-dated. Kinds:
  `percent_of_payment` (basis points), `flat_per_payment` (amount) and `one_off` (a fixed amount on
  the first confirmed payment only). The operator can add a new rule with an effective date, for
  example at the start of a month, so a rate can change over time. A rule never changes the past.
- When the operator confirms a payment, the rule in force on that date is applied and the result is
  stored as a **commission line** (amount and a snapshot of the rule), `owed` until the operator
  marks it `paid` with a reference. Trial months produce no payment and so no commission.
- Commissions are internal. They never appear on anything the customer sees. A minimum price can be
  set per module so a deal price cannot go below it.

## 8. The operator portal

A new area of the frontend on the platform host, for platform operators only (today there is no UI):
applications queue; tenants with subscription state and period; payments to confirm; commissions owed
and paid; price book; agents; the global new-trials switch. Every action is written to
`platform_audit_log`. A new application or a payment claim is also exposed on a queue endpoint so a
notifier outside this repository (for example the operator's phone) can forward it.

## 9. The tenant billing screen

Inside every tenant, for the tenant admin: plan, modules, period and next due date, the payment
instructions with this tenant's reference, "I have paid" (amount, method, reference, date), payment
history, and, in a trial, the days left. Changing modules is a request to the operator in this release.

## 10. Sign-up page and applicant page

A public page on the platform host (no sign-in): the form of section 4 and, after email verification,
an applicant page showing the application status and, when due, the payment instructions. Protection
against abuse: email verification, one open application per email, rate limit per address, a hidden
field check, and the operator's verification as the real gate.

## 11. Notifications

An email adapter for the existing notification port (activation link, verification link, reminders,
operator alerts). The provider is configuration; no provider is chosen in this spec.

## 12. Security and data

- The public endpoints are rate limited and return the same response for known and unknown emails.
- Applications hold personal data: mask phone and email in audit payloads as everywhere (chapter 8.9);
  delete `rejected` and `expired` applications after 90 days.
- The activation link is a signed one-time token, valid 72 hours, hashed at rest, as invitations are
  today (FR-IAM-01).
- Only platform operators can price, confirm, activate, suspend and manage agents (ADR-016: through
  definer functions, tenant data stays behind row-level security).

## 13. Build order

1. Subscription model, price book, payments and the operator portal: tenant list, confirm a payment,
   activate (enough for assisted customers, and the base of everything below).
2. Applications, the public sign-up page, email verification and the activation email.
3. The tenant billing screen, the trial clock and the reminders; then, only once reminders are live,
   the scheduled state changes with the safeguards of section 5.
4. Agents and commissions.

Each step carries its migration and docs; migration numbers for this work are claimed on issue #50
before use (proposal: V30 to V39), one migration pull request at a time.

## 14. Open points

- The price of a third module, and any per-branch or per-staff add-on.
- Receipts or invoices for confirmed payments (a PDF the tenant can download).
- Whether agents later get a read-only portal of their own customers and commissions.
- Notification provider, and whether a WhatsApp channel is added after email.
