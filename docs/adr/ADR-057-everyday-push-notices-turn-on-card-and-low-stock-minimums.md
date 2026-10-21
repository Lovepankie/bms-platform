# ADR-057: Everyday push notices, a one-tap turn-on card, and a low-stock minimum per item with the pace rule beside it

## Status

Proposed (owner request, issue #412, 21 Oct 2026). Extends ADR-050 (Web Push) without changing any of its decisions, and
supersedes the "per-product reorder level can follow" part of ADR-029 (the fixed threshold of 5 on the Stock list's Low stock tab
is unchanged). Builds on ADR-008 (db-scheduler), ADR-017 (per-permission branch scope), ADR-033 (tenant settings groups), ADR-048
(stores) and ADR-052 (customer orders). The number is the next free one; if another pull request takes 057 first, this file is
renumbered at merge. The migration is `V76` and is renamed at merge in the same way.

## Context

ADR-050 made BMS installable and sent six safeguard notices (a sale voided, a write-off alone, an approval limit raised, two-step
sign-in relaxed, stock on its way, an approval waiting). The owner asked for the things a shop hears about every day: a new order
that came in by phone or WhatsApp, an order the person took going out and arriving, stock running low, credit payments to collect,
and a closing summary at the end of the day. Two gaps stood in the way.

First, hardly anyone turned notifications on. The switch lives on Notifications and app, a page people open once. Browsers only
show the permission question after a tap, and a question nobody asked for is refused by habit.

Second, "low" had no meaning per item. The Stock list uses one fixed number (ADR-029), and Stock health judges by days of cover
at the pace of the last 30 days. An owner knows the number below which a fast mover must be reordered; a slow mover with no recent
sales has no pace at all; and neither rule alone catches both.

## Decision

1. **The turn-on card.** The staff area shows a card "Get told when something needs you?" with Turn on and Not now, the first
   time someone opens the installed app (display mode standalone) or right after they accept the install in the page
   (`install.ts` records `accepted`). It shows only while this device can have notifications and has none (push state `off`),
   so never when they are on, blocked, impossible on this browser or not offered by the server. Turn on is the tap the browser
   requires: `push.turnOn()` asks for permission before awaiting anything (Safari drops a request made after a network wait),
   then subscribes as the switch does. Not now asks again after 3 days, at most 3 times; turning on or a refusal ends the asking.
   What was answered is kept in `localStorage` on this device (`bms.push.ask`), as the install offer is, because the question is
   about the device. Once allowed, the existing quiet `sync` re-registers the device on every visit. The Notifications and app
   page is unchanged.
2. **Five everyday notices**, published as `PushNotice` events in the transaction of their cause (ADR-050 decision 9), under the
   lock-screen rule (no customer's name or phone, no amount; counts and item names are allowed):
   - `retail.order_new`: an order taken with a source other than walk-in, to the staff of the order's shop who hold
     `retail.order.manage` there, without the person who took it. Administrators, whose roles have no branch, are not the
     shop's staff and are not told (the new audience `BranchStaff`).
   - `retail.order_out_for_delivery` and `retail.order_delivered`: to the person who took the order, unless they did the step.
   - `retail.low_stock`: an item below its level at a branch (decision 4), to the holders of `retail.stock.read` at that branch
     and the administrators, the seller who made the sale among them on purpose (they work at that shelf), at most once per
     item, branch and day (the outbox key carries the day). Judged after any movement
     that takes stock out, inside a savepoint, so a failure never fails the sale. A branch with nothing left reads "out of stock"
     whichever rule tripped.
   - `retail.credit_due` and `retail.credit_due_branch`: each morning between 08:00 and 12:00 on the business's clock (the
     notice says "today", so a server down all morning does not send it late; the next morning's counts carry what is overdue),
     when a credit sale falls due today or fell overdue yesterday: one notice to the administrators over the whole business, and one per shop to the
     holders of `retail.sale.all_sellers` given for that shop (the Supervisor of #404, migration `V74`; sellers and storekeepers do not hold it, and the administrators, who hold it through a branchless role, hear only the business-wide notice). Counts
     only, in words that leave out a zero ("3 credit sales are overdue in all" when nothing falls due today).
   - `retail.daily_summary`: at the time the business sets (a quarter hour up to 23:30, 20:00 by default), to the
     administrators: the number of sales of the day at each shop. A summary whose time passed while the server was down goes after
     midnight, before 12:00, as `retail.daily_summary_late` ("Yesterday's sales"), only for a business that has had a summary
     before, so a first release sends none; a day the business had switched the summary off is recorded at its time without a
     notice, so switching it back on never sends that day late. No amount, because a notification lies on a lock screen; a tap opens the day's sales, where the totals show
     to those who may see them, and cost and profit never appear in any of these notices.
3. **Per-business settings** are the tenant settings group `notifications` (ADR-033's mechanism, stored in the settings row,
   versioned and audited): `new_orders`, `order_updates`, `low_stock`, `credit_due`, `daily_summary` (on unless switched off) and
   `daily_summary_time` (`HH:mm`). `GET` and `PUT /settings/notifications` (`core.settings.read`, `core.settings.manage`,
   `If-Match`), and the screen Business set-up > Notifications for the business. The notifications module applies the switches
   itself (a template maps to its switch), so a publisher never asks first; the safeguard notices of ADR-050 have no switch.
4. **The low-stock minimum and the rule.** Table `retail_stock_minimums` (V76): an item's level for every branch (no branch) and
   a branch's own level, which wins. A variant is a product (ADR-051), so each size and colour has its own. An item at a branch is
   low when **either** rule trips, whichever comes first (`LowStockRule`, shared by the push and Stock health): below the level
   that applies, or, at the pace of the last 30 days, fewer than the lead days of cover (14 unless Stock health asks for another
   number), the pace read as Stock health reads it, from the lines of completed sales, so a voided sale never counts. No level
   means the pace rule alone, no sales means the level alone, so nothing goes silent. The words say which rule
   tripped: "below your minimum of 10", "about 4 days from running out at this pace". The level for every branch is set by holders
   of `retail.catalogue.manage` in every branch (a branch-scoped holder is refused, since the level reaches branches they do not
   cover); a branch's own level also by whoever may commit a stock-take at that branch
   (`retail.stocktake.commit`, the Storekeeper's permission in #403). Routes `GET`, `PUT /retail/products/{id}/stock-minimum` and
   `PUT .../branches/{branch_id}`; removing a level that does not exist, or saving the same one, writes nothing and no audit row;
   the item's page and each reorder row on Stock health have "Warn me when stock is below".
5. **Stock health's reorder list** uses the same rule and says which rule put each row there (`low_by`, `low_words`, `min_qty`);
   the suggestion reaches the cover days at the pace and at least the level. The days-of-cover list is unchanged.
6. **Each daily notice is prepared once a day.** The job `retail.daily-notices` runs on a cron of every quarter hour on the
   Kampala clock, so a quarter-hour time is always met and never skipped by a drifting delay (nothing at all while the server
   has no push keys) and, per active retail tenant, takes a row of `retail_notice_runs` (tenant, kind, day) before it
   prepares a notice, so later ticks of the day cost one insert that does nothing.

## Consequences

**Better:**

- People are asked at the moment they install, with one tap, and asked again a few times at most; the permission question is
  never shown without a tap.
- The shop hears about the work of the day, each person only about their own shop or their own orders, and the owner gets a short
  closing line without opening the app.
- "Low" means what the owner said for the items that matter and still catches the rest by their pace; the push and Stock health
  can never disagree because they share one rule.

**Worse:**

- The daily summary shows counts, not money. An owner who wants the figures taps through; putting amounts on a lock screen would
  break ADR-050 decision 11.
- The low-stock check adds two indexed reads per item that leaves a branch in a sale, inside the sale's transaction.
- The Notifications and app page still lists only the safeguard notices in its words, since the owner asked that page to stay as
  it is; the card and the business screen describe the new ones.

**Watch for:**

- A shop's credit notice reaches only the supervisors given for that shop; a shop without one hears nothing of its own, and the
  administrators' notice still carries the whole business.
- A business that sells around midnight: the summary and the morning notice use the business's own clock and day.
- The Stock list's Low stock tab still uses ADR-029's fixed 5; moving it to this rule is a separate change.
