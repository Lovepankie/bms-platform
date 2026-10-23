# ADR-075: Subscription reminders, overdue with a grace period, a list for suspension (never automatic), receipts and the Owner's billing page

## Status

Proposed (2026-10-22, issue #480; the owner's decision D2 of 23 Oct 2026). Builds the reminders and the first scheduled transition
that ADR-024 decision 6 and ADR-056 decision 4 left to come, and narrows decision 6: the only automatic move is to `past_due`, and
suspension stays a person's act. Builds on ADR-016 (platform operations through definer functions), ADR-034 (generated PDF
documents), ADR-008 (background jobs on PostgreSQL) and ADR-062 (the Owner of a business). Migration `V92` (the agreed merge order:
`V85` to `V91` are claimed by open pull requests).

## Context

Since ADR-056 the operator records every subscription payment by hand and the Business page shows the free month and the last day
paid for, but nothing happens on those days: no business is told that its subscription is ending, a business that does not pay
stays `active` for ever, the operator has to scan every business to find who is late, a payment leaves no receipt the owner can
keep, and the owner sees nothing of their plan inside the app. Pesapal for subscriptions stays parked; payments are still entered by
the operator.

The owner (Hillary, decision D2) asked for reminders, an overdue state with a grace period, a list of businesses due for suspension
and not automatic suspension, an emailed receipt, and a Plan and billing page for the owner, generic for every business.

## Decision

1. **Reminders to the Owner, once each.** 7 days before the last day paid for, the day before and on the day; a business in its
   free month gets its own three (the free month ends in 7 days, tomorrow, today). The amount is the plan's monthly price in force
   the day after, from the price book (`plan_prices`); how to pay is the operator's own text. Each reminder is a row in
   `subscription_reminders` (unique per business, kind and due date) written in the same transaction as its outbox rows, so it
   goes once whatever the job does. Email to the Owner (invited or active); SMS as well only when the platform switches billing
   SMS on (`BMS_PLATFORM_BILLING_SMS_ENABLED`, off by default), and then through the SMS channel's own checks, the business's own
   SMS switch included. A reminder goes at the first tick from 08:00 in the business's own time zone, on its day only: a day
   missed while the server was down is not sent late. Nothing goes to a pilot, a suspended or cancelled business, or a business
   already overdue. A business with no Owner gets the row and no message, so the operator sees that nothing went.
2. **Overdue the day after, automatically, and nothing more.** A job every quarter hour (`core.platform.subscription-billing`,
   switched off by `bms.platform.billing-job-enabled=false`) moves a business in its free month or paid, not a pilot, whose last
   day is before its own today in its own time zone, to `past_due` (shown as overdue), at the first tick after its midnight.
   `platform_mark_subscription_overdue` checks the status and the date again under the row lock, so a payment that lands meanwhile
   wins, and writes `platform.tenant.subscription_overdue` to the platform audit log with no operator. `past_due` changes nothing
   the business can do. A free month that ends unpaid becomes overdue the same way. A subscription with a status change the
   operator scheduled (`next_status_change_on`, for example an end chosen for the end of the period) gets no reminder, is
   never moved and never listed for suspension, and its date is never touched.
3. **The grace period is a platform setting.** `platform_billing_settings.grace_days` (0 to 60, 7 at first) and `how_to_pay`, one
   row, changed by the operator with a reason (`platform.billing.settings_changed`). The grace period is read when it is needed,
   never stored per business, so a change applies to every overdue business at once. While a business is overdue and within it,
   every signed-in member of its staff sees the banner "Your subscription is overdue since <day>; pay by <day> to keep using the
   app"; the last day of the grace period is the last day paid for plus the grace days.
4. **A list, not a suspension.** `GET /platform/billing/due-for-suspension` lists every overdue business past the grace period,
   not a pilot, most overdue first, with the days overdue and the plan's monthly price. The operator suspends from the Business page
   with the existing route and a reason. No job, function or route added here suspends anything; ADR-024 decision 6 ("automatic
   suspension is opt-in and guarded") is narrowed to never, until a later decision says otherwise.
5. **What is owed.** A late payment's paid period starts on the day it is recorded (`platform_record_subscription_payment`, V75),
   so the late days are not charged and what is due is the price of the next month. The list, the reminders and the page say that
   amount; they never add the late days.
6. **A receipt for every payment.** When the operator records a payment, the same transaction numbers its receipt
   (`subscription_receipts`, `RT-<year>-<six digits>` from one sequence across the platform) and queues the email to the Owner. The
   receipt row keeps the business's and the plan's names as they were, so the PDF is the same whenever it is rendered (ADR-034).
   The PDF is in the house letterhead of Rincol Tech (the issuer, not the business's own): business, plan, period covered, amount
   in figures and words, method, reference, number and date. The issuer's address, contact and tax number are environment values
   (`BMS_PLATFORM_BILLING_ISSUER_*`), never repository content. The email carries the PDF as an attachment read when it is sent,
   through the attachment port `EmailAttachments` of `core.notifications` (the same port, byte for byte, as pending ADR-074 adds,
   so the two changes merge without a conflict). Payments recorded before `V92` get their receipt numbers in the migration, in
   the order they were recorded, and no email.
7. **The Owner's Plan and billing page.** Business set-up > Plan and billing (`/staff/setup/billing`, `GET /api/v1/billing` with the
   new permission `core.billing.read`, held by the administrators, the Owner among them): the plan, its price, the state (free
   month, paid, overdue in grace, overdue, suspended, cancelled, complimentary), the dates, how to pay and every payment with its
   receipt (`GET /api/v1/billing/payments/{id}/receipt`). Read only; payments are still the operator's. The banner is
   `GET /api/v1/billing/notice`, open to every signed-in member of staff.
8. **Additive next to the self-serve plan (pending ADR-073).** A cancelled business gets no reminder (its status is `cancelled`).
   A business whose cancellation is scheduled for the end of its paid period is still `active` until then: whichever of the two
   changes merges second adds the condition that skips it in the reminder candidates (`subscription_scheduled_changes` with kind
   `cancel` and status `scheduled`), so it is not asked to pay for a period it has cancelled.

## Consequences

**Better:** owners are told before they run out, in plain words, with the amount and how to pay; a late business is marked and
audited the morning after in its own time zone; the operator sees in one list who to call and decides; every payment has a numbered
receipt the owner gets by email and can download again; the owner sees their plan and payments without asking.

**Worse:** the operator still records every payment and still suspends by hand; a reminder missed while the server was down is not
sent later; the late days are not charged (the V75 rule); the receipt's issuer details come from the environment, so a change there
changes how old receipts render (the business and plan names do not change).

**Watch for:** an Owner with no email (no reminder or receipt email goes; the operator sees it in the outbox list of the Business
page); a business with no price entered for its plan (the reminders say the amount will be given; the list shows no amount); the
grace setting lowered, which lists more businesses at once; the scheduled cancellation of pending ADR-073 until the condition of
decision 8 is in.
