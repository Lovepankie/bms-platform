# ADR-112: The AI assistant is a paid add-on on any plan, switched on per business by the operator

## Status

Proposed (2026-11-02, issue #423). Amends ADR-037 decision 7 (the platform allow-list) and builds on ADR-056 (the price book by
plan), ADR-075 (what a business owes, the reminders and the list for suspension) and ADR-016 (platform changes through definer
functions). Migration `V126`, directly after `V125` (the retail AI brief, issue #422, ADR-037 amendment of 2026-11-02).

## Context

The AI assistant (ADR-037, the lending brief and, since issue #422, the shop brief) was opened to a business by naming its id in the
environment value `BMS_AI_ENABLED_TENANTS` and restarting the API. The product owner decided (21 Oct 2026) that the assistant is
part of the subscription as an add-on with its own monthly price on every plan (Starter, Growing, Business or Wholesale, Lending,
Lending unlimited), switched on by the operator, tried free in the free month, and free for the demo businesses. A price is a
commercial term: like a plan's price (ADR-056 decision 2), it is typed by the operator and never kept in the repository.

## Decision

1. **The add-on is a mark on the subscription.** `subscriptions.ai_addon` (on) and `subscriptions.ai_addon_no_charge` (on without
   charge), both false for every business: the migration switches nobody on. The operator sets them on the Business page with a
   reason (`PUT /platform/tenants/{tenant_id}/ai-addon`, `platform_set_ai_addon`), audited as `platform.tenant.ai_addon_changed`
   with before and after, the before being what the function read under the row lock; the same state again writes nothing.
   Switching off clears the no charge mark. As every operator change (ADR-016, V88, V105), the functions that switch the add-on,
   price it and count it refuse a caller bound to a business and a platform user who is not active; the read of a business's
   marks lets a bound caller (the Owner's billing page) read its own business only.
2. **The add-on is the source of truth for the assistant.** `core.ai`'s allow-list reads `subscriptions.ai_addon` of the bound
   business on every call, so a business switched off stops at once, as a business removed from the list did. The business's own
   switch, its acknowledgement and its daily caps (ADR-037) are unchanged: the add-on lets the administrator switch the assistant
   on; it does not switch it on.
3. **The environment stays as the platform's control.** `BMS_AI_ENABLED_TENANTS=off` is the kill switch (no business, whatever
   its add-on); `*` lets every business in, and the API refuses to start with it outside the `dev`, `test` and `demo` profiles,
   as it refuses the recording stub, so a production host can never let every business in unbilled; tenant ids are still let in
   beside the add-on, so a
   host that named businesses before keeps them until the operator moves them to the add-on. Empty (the default) leaves it to the
   add-on. `BMS_AI_PROVIDER=off` still stops every call.
4. **Its own price, over time.** `addon_prices` (append-only, like `plan_prices`): a monthly price in minor units and the day it
   starts, entered on Plans and prices (`POST /platform/addons/ai_assistant/prices`), audited as `platform.addon.price_added`, with
   the same guards as a plan's price (never in the past, at most UGX 50,000,000, a change of more than a quarter needs
   `confirm_large_change`). Nothing is seeded, and the figure is kept nowhere in the repository: tests use invented prices.
5. **What a business owes.** When the add-on is charged (on, not no charge, the business not a pilot) its monthly price in force is
   added to the plan's: in the reminders, the Owner's Plan and billing page, the list due for suspension and the check of a payment
   against the price (ADR-056 decision 3, ADR-075 decision 5). A year adds twelve months of it: the plan's months free belong to the
   plan. A charged add-on with no price entered adds nothing. A payment of exactly the plan's price alone, while the add-on is
   charged, is within the quarter of ADR-075 but is named and needs the operator's confirmation, so the add-on is never left
   out of a payment silently.
6. **Tried free in the free month.** Nothing is charged in the free month (ADR-056 decision 4), so a business can try the add-on
   then; it is first charged on the first month paid after it. The reminders before the free month ends already name the total.
7. **Demo and pilot businesses.** A pilot business (ADR-056 decision 7) is never charged for it. A demo business (`retaildemo`,
   `lendingdemo`, `tradedemo`, `demo` on staging) is switched on by the operator with **no charge**, by hand with a reason; no
   migration switches a business on by its slug, since a slug is a name a business chooses.

## Consequences

**Better:** the operator switches the assistant on without a restart, every change has a reason and an audit row, the price is
data the operator changes from a date, and what a business owes includes the add-on everywhere it is said. **Worse:** the
allow-list now reads the database on each call (one indexed read of the business's own subscription row), and one price applies
to every plan; a demo host that wants `*` runs with the `demo` profile added. **Watch for:** a second add-on widens the check on
`addon_prices.addon_code` and needs its own mark.
