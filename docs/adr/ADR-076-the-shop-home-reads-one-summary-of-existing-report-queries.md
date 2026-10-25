# ADR-076: The shop Home reads one summary built from the existing report queries

## Status

Proposed (owner request of 23 Oct 2026, issue #485, FR-UXN-06). Supersedes decision 2 of ADR-072 (the Shop card's read of the
30 day dashboard and of the orders board); the rest of ADR-072 stands. Builds on ADR-055 (sellers who see only their own figures),
ADR-017 (per-permission branch scope), ADR-029 (the low stock threshold) and ADR-043 (credit sales counted when the buyer pays).
Renumbered at merge if needed (pending ADR-074 and pending ADR-075 are claimed by open pull requests).

## Context

The owner wants the shop Home to say more than today's sales, the low stock count and the open orders: today's takings split by
cash and mobile money, what customers owe (the total, how many customers, the oldest due date), the five items that sold best today
or this week, and one line per shop for whoever sees more than one. Each figure already has a full page: the cash summary and the
sales list, credit control, the sales analysis and the dashboard's table by shop.

Under ADR-072 the Shop card made two reads: the dashboard with `period=today`, which always reads 30 days of sales, the shops and the
stock position, and the orders board. Adding four cards by calling the four full reports would make six reads on every Home visit,
most of them for windows far longer than a day, and the credit control and sales analysis reports are refused or trimmed by other
rules than the cards need.

## Decision

1. **One new read, `GET /api/v1/retail/reports/home`** (`retail.sale.read`, optional `branch_id`, the branch chosen in the header),
   in `retail.reports`, answers every shop figure on Home in one read-only transaction: `sales_today`, `takings`, `credit_owed`,
   `top_items` (today and this week), `branches`, `low_stock` and `open_orders`. The Home asks it once; the Top items switch between
   today and this week reads nothing more.
2. **It reuses the report queries, over today or this week only.** Sales today and the line per shop are the dashboard's sales query
   for one day, unchanged (still grouped by the credit flag; the takings do not read it);
   credit owed is credit control's total row, with the earliest due date added; the top items are the sales analysis's item ranking
   with a limit of five; the low stock count is the dashboard's. Three queries are new: the takings (decision 4), the active shops
   in scope (for a line with no sale yet) and the open orders as one count. No table, column, index, permission or migration is
   added.
3. **The money parts follow the dashboard's scope and are left out, not refused.** `sales_today`, `takings`, `credit_owed`,
   `top_items` and `branches` need `retail.sale.all_sellers` in every branch they cover (ADR-055); without a branch a Supervisor gets
   the shops it supervises, as on the dashboard. A seller gets 200 with those parts absent, so the stock count (`retail.stock.read`)
   and the open orders (`retail.order.manage`), each in its own branch scope, still reach them. `branches` is present only when the
   summary covers two or more shops, so a session with one branch, or with one branch chosen in the header, gets no line per shop.
   A branch asked for outside the caller's scope of a part leaves that part out instead of answering zeros, and the PWA takes a
   card away when its part is absent, so no card says "nothing" about a shop the caller cannot see.
   `takings` also needs what the cash summary needs: `retail.cashbook.read` in every branch it covers and `retail.till.close`
   (ADR-055, the cash book is the till's). The takings are the cash summary's figures (refunds and reversed payments on account
   included), so `retail.sale.all_sellers` alone, which a Supervisor holds, must not show what the cash summary hides; the other
   money parts stay on `retail.sale.all_sellers`, as on the dashboard. The cash book's other figures (savings, banking, expenses,
   withdrawals, advances, the expected cash) are not on Home.
4. **Takings are read the way the cash book reads a day, for every way of paying.** The cash book (`JdbcCashSales`) is what the
   cash summary shows, and the card links there, so the two must agree: in, the sales paid at the sale dated today (cancelled later
   or not), the payments on credit sales dated today at the branch that took them, and the delivery fees taken at the door today or
   paid today on an account; out, the sales paid at the sale cancelled today, payments on account reversed today, money refunded
   today on goods taken back (at the till that paid it, a return cancelled today bringing it back) and the door fee of an order whose
   sale was cancelled today. Each way (cash and mobile money always, bank when it moved money) gives its in, its out and the
   difference. Payments are chosen by `paid_on` alone, as the cash book chooses them, whenever they were entered: the payment
   routes refuse a `paid_on` after the day of entry, but an import or a replay can still write one, and the card must not lose it.
   No index led on `retail_sale_payments.paid_on`, so that part read the tenant's payments as the cash summary's own query does
   (bench query k17); the index `(tenant_id, paid_on)` is the fix, added by `V100` (issue #488: at 25 times the staging data
   the payments part went from 300 buffers to 14, k17 from 2.25 to 1.33 ms). They are not Received of ADR-043, which counts a
   sale on the day it was entered.
5. **Home dates are the business day in the tenant's zone** (Africa/Kampala by default); this week starts on Monday.

## Consequences

**Better:** one round trip for the whole Shop part of Home, each query bounded to a day or a week; the cards
agree with the pages they link to because they run the same SQL; a seller now sees the low stock count and the open orders on Home
without any money figure.

**Worse:** the Home summary is a second place that reads the dashboard and credit control queries, so a change to either rule
changes Home too (on purpose). The takings repeat the rules of `JdbcCashSales` in `retail.reports` (which may not read
`retail.sales`), so a change to how the cash book counts a kind of money must change both; `RetailHomeSummaryIT` compares the cash
line with `GET /retail/reports/cash/daily` and fails if they part. A session with `retail.order.manage` and no sales read still asks the orders board.

**Watch for:** the reversals of payments on account, the refunds and the fees paid on an account have no index on their day; those
tables hold one row per receipt, return or order and are read by bench query k17, though its seed holds none of them yet (#488). The top items of the week scan the sale lines
of up to seven days; if it shows in the database benchmarks, the week
can move to the sales summary's grouped read. A new way of paying appears on the takings card on its own, labelled by its key until
the PWA names it.
