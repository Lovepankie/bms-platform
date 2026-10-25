# ADR-072: Home shows a card per module, and lending gets one small loans summary

## Status

Accepted (owner request of 22 Oct 2026, issue #471, FR-UXN-04 and FR-UXN-05, after the review of pull request #473). Builds on
ADR-001 (core plus vertical modules), ADR-003 (row-level security), ADR-055 (sellers who see only their own figures), ADR-064
(a loan officer sees their own portfolio) and on the side menu of issue #455. Decision 2 (the Shop card's reads) is superseded by
ADR-076 (issue #485): the Shop card and the shop's summary cards read one Home summary. The side menu's headed Shop and Lending
sections of issue #471 are replaced by the grouped menu of ADR-086 (issue #564); the Home cards are unchanged.

## Context

A business that uses both Shop (retail) and Lending got one long mixed side menu and a Home page that only listed lending members.
The owner wants Home to say, per module the business uses, what needs attention today: for the shop, today's sales, the items low on
stock and the open customer orders; for lending, the loans due today, those in arrears with what is owed, and the requests waiting
for approval.

Most of that is already served. The retail dashboard (`GET /retail/reports/dashboard`) has today's sales total and count and the
low-stock count; the orders board (`GET /retail/orders/board`) has the open orders at each step; the approvals inbox
(`GET /approvals`) lists the pending requests. The collection lists of FR-CLN-02 and FR-CLN-03 (`GET /lending/collections/due`
and `/arrears`, ADR-064) now carry each loan due and in arrears, with the borrower's name and phone, but they need
`lending.collections.read`, page at most 500 lines, and the arrears list reads the nightly `days_past_due` of the arrears run, so
adding them up on the phone would be both heavy and a day old. The portfolio of insights needs `lending.insights.read` and reads the
nightly snapshot, and the loan list would have to be paged in full.

## Decision

1. **Home draws a card per module the tenant has switched on** (`/me` `modules`), Shop first and Lending second, as in the side
   menu. A business with one module sees only its card. Each figure is drawn only for a session holding what its endpoint
   requires, and a card with no figure the session may see is not drawn; the server still decides every figure.
2. **The Shop card reuses what exists.** Today's sales and the low-stock count come from the retail dashboard, so they need
   `retail.sale.read` with `retail.sale.all_sellers` (ADR-055: the total is everybody's) and, for the stock count,
   `retail.stock.read`; a seller who sees only their own sales does not get the shop total here, nor does a supervisor in a branch
   where they lack `retail.sale.all_sellers`, and the stock count shows only in a branch where they hold `retail.stock.read`
   (both checked per branch, so a partial holder never gets a 403 card or another branch's count). Home asks with
   `period=today` under the dashboard screen's own query key, so the two share one answer. The dashboard has no today-only form:
   every call reads the last 30 days of sales (the 7 and 30 day figures and the shops), a cost Home accepts rather than add a
   second endpoint; if it shows in the database benchmarks, a `today` short path in `DashboardService` is the fix. The open orders
   come from the orders board (`retail.order.manage`).
3. **One new endpoint for lending: `GET /api/v1/lending/loans/summary`** (`lending.loans.read`, optional `branch_id`, the caller's
   branch scope of that permission applied as on the loan list). It answers the business date, the currency, and the count and the
   amount still owed of the active loans with an instalment due today and with an instalment due before today, read live from
   `lending_schedule_items` (what is due less what is paid, waived and written off), in the business's currency. It is tenant-scoped
   by RLS like every read, adds no table, column, migration or permission, and is a lending route, not a core one, so the core never
   learns a vertical's figures. It applies the ADR-064 officer limit exactly as the collection lists do (the same filter,
   `CollectionsRepository.filters`): in a branch where the caller lacks `lending.collections.all_officers`, a loan officer there,
   it counts only the loans they are the responsible officer for, so the card never shows a figure the lists would hide.
4. **The waiting requests come from the approvals inbox**, asked with its existing `action_type` filter for the lending action
   types only (`LENDING_APPROVALS` in `areas/staff/home-cards.tsx`), so the server counts them and another module's requests never
   fill the page; shown with a `+` when the inbox has more pages. A test reads each `ApprovalAction`'s `TYPE` in the backend's
   lending packages and fails when the list and the backend differ.

## Consequences

- The arrears on Home are live: a repayment taken this morning lowers them at once. They can differ from the nightly
  `days_past_due` and `arrears_minor` of the loan, which the arrears run (FR-ARR-01) recomputes once a day and insights read.
- A new lending approval action has to be added to `LENDING_APPROVALS` to be counted on the card; the test above fails until it is.
- Each Home visit with the Shop card costs one 30 day dashboard read (shared with the dashboard screen's Today range).
- A core, vertical-neutral summary that every module contributes to was considered and left out: it needs a new port in the core
  and a response shape that cannot be typed per module, for three figures one lending route can give.
