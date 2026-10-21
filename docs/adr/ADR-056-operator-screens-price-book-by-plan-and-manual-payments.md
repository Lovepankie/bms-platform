# ADR-056: Operator screens: the price book by plan, manual payments entered by the operator, and the trial clock at first sign-in

## Status

Proposed (2026-10-21, issue #407). Amends ADR-024 decision 3 (the price book) and builds parts of its build steps 2 and 3 (prices,
payments, the trial clock) without the tenant billing screen, the reminders or any scheduled state change. Builds on ADR-016
(platform operations through definer functions). Migration `V75` (renamed at merge if `V73` and `V74` of the retail roles pull
requests land after it).

## Context

Until now the operator portal showed only Applications and Messages not sent. Everything else about a business (its plan, its
modules, its subscription status, suspension, the owner's sign-in rescue) was API only, and there was no price at all: ADR-024
decided that prices are operator-entered configuration but no table held them, the `plans` table carried limits only, and a payment
received was a free text note at activation.

The owner (Hillary, 21 Oct 2026) priced the product by plan, not by the number of modules ADR-024 decision 3 described: a few
retail plans, each with its own shop and staff limits, and lending plans by member count, each with a monthly price and a yearly
price of fewer than twelve months; the free month runs from the first sign-in. The figures are commercial terms and stay out of the
repository: the operator types them in the portal.

## Decision

1. **The price book is per plan.** A plan keeps its limits (shops and stores, staff users, lending members, the modules it allows)
   and gains a description and a sort order. Its price lives in a new append-only table `plan_prices`: a monthly price in minor
   units, the months charged for a year (`annual_months`: fewer than twelve gives months free) and the day it starts. The
   price in force on a day is the newest row starting on or before it; a new price starts today or later, so a price never changes
   what was charged before. ADR-024's "price per number of modules" is replaced; a per-subscription deal price stays possible later
   as a column on the subscription.
2. **Nothing is seeded.** The migration writes no plan, no limit and no price. The operator creates and changes plans by code
   (`PUT /platform/plans/{code}`, an upsert) and adds prices (`POST /platform/plans/{code}/prices`) in Plans and prices. A plan that
   a business is on cannot lose a module that business has switched on; a plan taken out of offer keeps its businesses.
3. **A payment received is entered by the operator and confirmed at once.** `subscription_payments` (tenant-owned, forced row-level
   security, append-only) has the shape ADR-024 decision 4 describes (amount, method, the sender's reference, the day paid, the
   months covered, the period, who recorded it) and a status that already allows the tenant's own claim later. Recording one is one
   definer function in a transaction bound to the tenant: the row, the subscription's paid-until date and, for a free month or a
   late payment, the move to `active`, behind the `Idempotency-Key` protocol, so a second tap records nothing. The paid period starts
   the day after what is already covered (a free month still running, an earlier payment) or today, so no free or paid day is lost.
   A suspended or cancelled business stays read only until the operator resumes it, and the payment sets the status the resume
   brings back to `active`, so a business that pays while suspended comes back active, not late and not in its free month. A
   payment carries a reason (5 to 300 characters) that the audit row keeps, as every operator change does. A mistake is corrected by moving the paid-until
   date by hand with a reason; a confirmed payment is never edited. A payment and a monthly price are at most 50,000,000 minor units
   (UGX 50 million), in the database and in the API. A payment more than 25% away from the plan's price for those months (each
   whole year at the yearly price, the months left at the monthly price), or on a plan with no price entered, is refused with
   `payment_amount_unusual` until the operator sends `confirm_unusual_amount` after checking it; the message gives both amounts as
   people write them ("UGX 30,000"). The Business page keeps the same `Idempotency-Key` while only that confirmation box changes, so
   a resend after a lost answer is answered from the first and never recorded twice.
4. **The free month starts at the first staff sign-in, or at a payment that comes first.** A trigger on `auth_sessions` sets the last
   day of the trial once, one month from the first session a staff user of the business opens, in the business's own time zone, and
   writes a platform audit row; existing trials are backfilled from their first session, with an audit row each. **A payment recorded
   before anyone has signed in never burns the free month:** the free month starts on the day of the payment (audited as started by
   the payment), and the paid months start the day after it ends; a later first sign-in changes nothing. Nothing happens when the month ends: the reminders and the guarded
   scheduled transitions of ADR-024 decision 6 are still to come, and the operator can move the last day by hand.
5. **Every operator change carries a reason and changes nothing when repeated.** Suspend, resume, a status move, a plan move and a
   date change take a reason of 5 to 300 characters that the platform audit log keeps with the operator's id; a request that would
   change nothing writes nothing. Suspended means read only and never delete (FR-TEN-06, unchanged). The same holds for saving a
   plan, adding a price, switching modules on or off and the pilot mark. **A resume restores the status from before the suspension**
   (`subscriptions.suspended_from`, kept while the business moves between suspended and cancelled): a business suspended in its free
   month comes back to its free month with the same last day, and a late one comes back late. Dates set by hand are from 2026-01-01
   to five years ahead, and either date can be cleared. Resetting a tenant admin's sign-in or second factor (the rescue routes of
   ADR-042) takes a reason too, besides the operator's own fresh authenticator code, kept in `platform_audit_log`. A plan move is
   refused when the business has more open shops and stores, staff or active lending members than the plan allows
   (`plan_too_small_branches`, `plan_too_small_staff`, `plan_too_small_members`). Lowering a plan's shop, staff or member limit
   below what businesses on it already use is refused with `plan_limit_below_use`, naming how many are over, until the operator
   confirms it (`confirm_below_use`); the audit row keeps the counts. A new monthly price more than 25% away from the one in force
   is refused with `price_change_unusual` until the operator confirms it (`confirm_large_change`), so a slip of a zero is caught.
6. **The screens.** The portal gains Businesses (search by name, address or owner email, filters by status and module, one card per
   business so a phone reads it), a Business page (details and owner, subscription, modules, a payment, the plan, the dates, suspend
   and resume, the owner sign-in rescue through the existing routes, the emails the outbox holds for it and the operator history),
   and Plans and prices. Every change is confirmed in a plain-words sentence first. Each page has a page tour that starts only from
   its own button and saves nothing, breadcrumbs and links to the pages it refers to (ADR-049).
7. **Pilot (complimentary) businesses (ADR-024 decision 6).** `subscriptions.is_pilot` marks a business that is free with no free month
   end until a paid start is agreed: marking it (on the Business page, with a reason, audited as `platform.tenant.pilot_changed`)
   clears the last day of the free month and any scheduled status change, the first sign-in starts no clock, the dates screen refuses
   a free month end on a pilot, and the scheduled transitions to come must skip it. On the owner's decision of 21 Oct 2026, V75 marks
   the three businesses agreed as pilots, by slug, on every database it runs on (staging and production alike) wherever a tenant with
   that slug exists, with an audit row each; a slug with no tenant is skipped with a NOTICE in the migration log.
8. **Commissions are deferred.** ADR-024 decision 5 (agents, effective-dated commission rules and the commission line stored when a
   payment is confirmed) and the tenant's own claim and the operator's confirm step of decision 4 are not built. A payment confirmed
   now gets its commission line later from the same record: when agents arrive, a one-off job applies the rule in force on each
   payment's `paid_on` to the confirmed `subscription_payments` rows that have no commission line yet, and stores the line with the
   rule's snapshot, exactly as a confirmation will from then on. Nothing is lost by recording payments before then.
9. **Platform permissions are unchanged.** Reads need `platform.tenants.read`, changes `platform.tenants.manage`, which no tenant role
   holds; the routes are served only on the platform host. Reads that cross tenants (the overview with owner and last sign-in, a
   tenant's administrators, its outbox rows) are definer functions owned by `bms_owner`; a payment is read and written only inside
   a transaction bound to its own tenant.

## Consequences

**Better:** the operator runs a business's whole subscription from the portal, on a phone; prices are configuration with a history,
entered by the person who sets them and never committed; a payment is a record with the gateway-ready shape and cannot be counted
twice; the free month no longer burns while an owner has not started.

**Worse:** the operator still confirms every payment by hand and a trial that ends unpaid does not suspend by itself; a wrong
payment is corrected by moving a date, not by a reversing entry; the price of a plan is shown in UGX only in this release.

**Watch for:** a plan whose limits are lowered below what its businesses already use, once the operator confirmed the warning (they
keep working; the next branch or staff user is refused); prices or agent terms appearing in the repository, logs or tests (tests use fabricated round figures only); the
tenant billing screen, the reminders and the scheduled transitions, which must read the same `plan_prices` and
`subscription_payments` rows rather than add a second record.
