# ADR-024: Self-onboarding with operator verification, per-module subscriptions and manual payments

## Status

Proposed (2026-10-06). Builds on ADR-016 (platform operations through definer functions) and
ADR-008 (scheduler). Extends FR-TEN-03 to FR-TEN-06. Specification: `docs/specs/self-onboarding-and-subscriptions.md`.

## Context

Tenants are created by a platform operator through the platform API, with no screen. The product
needs a path where a business applies, is verified, pays or tries one month free, and then runs its own
shops, staff and modules; what it can use must follow what it has subscribed to. Payment gateways are
not available yet: customers pay by mobile money or bank transfer to the operator. Some customers come
through agents, who are paid a share that differs per deal and can change over time. The staging
host is one small shared machine, so an unverified stranger must not be able to create tenants there.

## Decision

1. **An application is not a tenant.** A public form creates an application row; nothing is
   provisioned until the operator verifies it (and, for the paid way, confirms a payment). The
   operator's verification is the abuse control, with email verification and rate limits in front.
2. **Two ways in, one mechanism.** "Subscribe now" activates on a confirmed payment; "one month free"
   activates on verification with a `trial` subscription whose 30 days start at the admin's first
   sign-in. Both create the tenant through the same platform function that FR-TEN-01 uses. One free
   month per business and per module. An unpaid trial ends in `suspended` (read only, exportable,
   never deleted).
3. **Price per module, with a price book and per-subscription deal prices.** The price book prices
   the first, second and later modules and one annual factor; a subscription may carry an agreed deal
   price instead. Plans keep the limits (branches, staff, records) and no longer carry a price. All
   prices are operator-entered configuration, never seed data or repository content.
4. **Manual payments are first-class records.** A payment is claimed by the tenant or entered by the
   operator and confirmed or rejected by the operator; confirmation is idempotent, audited and creates
   the effects (period, state, commission) in one transaction. The record shape is the one a gateway
   would fill, so a gateway can confirm payments later without a model change.
5. **Commissions are effective-dated rules snapshotted at confirmation.** A rule (percent of payment,
   flat per payment, or one-off) belongs to a subscription and an agent and applies from its
   effective date; confirming a payment stores a commission line with the rule it used. Rules change
   only forward, lines are immutable, and commissions never reach anything the customer sees.
6. **Automatic suspension is opt-in and guarded.** Moving a tenant to read only stops its till, so the
   scheduled transitions are off by default per tenant, run only after reminders have been sent, skip
   complimentary and pilot tenants, and hand a tenant with recent trading to the operator to confirm
   instead of suspending it. The transitions ship after the reminders.
7. **The operator portal is a new frontend area on the platform host**, backed by the platform
   endpoints and definer functions of ADR-016; tenant-facing billing lives inside the tenant.

## Consequences

Better: a customer can start without a conversation about money; the operator keeps a human gate that
also protects the shared host; agent arrangements can vary by deal and over time without touching the
price the customer sees; the payment and commission history is auditable; moving to a gateway later is
an adapter, not a redesign.

Worse: the operator confirms every payment by hand, so confirmation speed is the main source of
friction (the portal queue and a forwarded alert keep it short); a free month invites repeat claims
(matched on phone, email and business name, with an operator warning); a trial clock that starts at
first sign-in lets an unused trial linger (the operator can expire it).

A tenant may stay `active` past its due date until the operator or an opted-in job acts; that is a
deliberate cost, chosen over cutting a shop off by surprise.

To watch: the number of concurrent trials on the staging host (a global switch stops new ones), the
volume of mail the new adapter sends, and that no commercial figures or agent terms are ever written to
the repository or to application logs.
