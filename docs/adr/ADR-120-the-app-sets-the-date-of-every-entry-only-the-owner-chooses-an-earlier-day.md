# ADR-120: The app sets the date of every entry; only the owner chooses an earlier day, with a reason

## Status

Proposed (issue #811, 3 Nov 2026; FR-RET-85). Amends ADR-022 (the cash book, whose records took any business date up to
today), the date fields of Buy stock (FR-RET-06) and Move stock (issue #84), and ADR-115 decision 8 (issue #799: sales
staff opened any day of this month; an earlier day is now the owner's only, decision 2). Adds the permission `retail.entry.backdate`
in migration `V131`, the next number after `V130` on main; renumbered at merge if needed. Leaves ADR-067 (offline sales)
unchanged.

## Context

The owner compared the old app with BMS screen by screen on 3 Nov 2026. In the old app the date and time of every entry were
set by the app, as a sale's still is in BMS. In BMS, expenses, savings, banking, withdrawals, advances and their repayments,
Buy stock and Move stock showed a date box that took any day up to today. Anyone recording one of them could therefore date it
into a day whose cash was already counted and banked, which changes that day's figures without anyone noticing. The owner
asked, months ago, that the app set the date, and that only the owner may put an entry on an earlier day.

## Decision

1. **The server dates the entry.** On `POST /retail/expenses`, `/savings`, `/bankings`, `/withdrawals`, `/advances`,
   `/advances/{advance_id}/repayments`, `/purchases` and `/transfers` (and the sales staff's `POST /retail/savings/sales-staff`,
   which keeps its own refusal of a day before this month, and the Edit routes of issue #830, `POST /retail/expenses/{id}/replace`
   and `POST /retail/purchases/{id}/replace`, whose new entry is dated like any other), a date left out is today on the business clock in the
   tenant's time zone. `purchased_on` is no longer required. A future date is refused as before.
2. **An earlier day is the owner's.** A date before today is taken only from a holder of the new permission
   `retail.entry.backdate` at every branch the entry dates on that day: the entry's branch; for a repayment, the branch that
   receives the money (the advance's own branch is not written to on that day); every branch the lines of a purchase stock;
   the source of a transfer, and its destination when the move completes at once (with the two-step transfer of ADR-033 the
   destination is written at the receipt, dated the day of the receipt, so a dispatch is checked at the source only). Anyone
   else gets 403 `date_not_today` naming the date field, and nothing is written. The time of a banking (`banked_at`) and of a
   withdrawal (`withdrawn_at`) is part of the date: a time on an earlier day follows the same rule and, when the business date
   itself is today, is the earlier day the audit row of decision 3 records. The check is one shared component,
   `retail.stock.EntryDates`, which every service calls before it writes.
3. **With a reason, audited.** The holder sends `date_reason` (3 to 300 characters, else 422 on `date_reason`); a reason never
   stands in for the permission. The entry's transaction writes the audit row `retail.entry.backdated` with the entry type,
   the date, the day it was entered and the reason, next to the entry's own created row.
4. **Who holds it.** The tenant admin holds `retail.entry.backdate`, as every retail permission; no other seeded role does. It
   is a permission rather than the Owner flag of ADR-062 so that it is scoped by branch, shows in the permission matrix and
   `/me` like every other, and can be given or not given per person on the Users screen. The owner and the administrators do
   everything (ADR-062 context), so the owner level is the administrators' column.
5. **The forms.** The PWA shows the date box only to a holder at the form's branch (for Buy stock, at every branch of its
   lines; for Move stock, at the source and, for a move that completes at once, the chosen destination). For anyone else the box is gone and the request carries no date, so a draft kept from an earlier day cannot bring
   its date back. An earlier day shows "Why an earlier date" and the form does not save until a reason is typed; today sends
   no date.
6. **Approvals.** An expense that waits for approval (ADR-091) keeps the date and reason checked when the maker asked; the
   approver is not asked for the permission again, and the audit row is written when the expense is recorded.
7. **Not changed.** Sales are dated by the server online and keep the time they were made offline (ADR-067). The receipt date
   of a goods receipt, a supplier payment's `paid_on` and imports of the old app's history are outside this decision.

## Consequences

- A seller or a till person can no longer change a closed day by dating an entry into it; a late entry is the owner's, with a
  reason anyone reading the audit log can see.
- The owner keeps the way to enter what was forgotten, one entry at a time.
- Integrations and tests that sent past dates as an administrator now also send a reason; the test support adds a fabricated
  one, and `RetailEntryDatesIT` proves the rule on every route with bodies sent as written.
- A new route that creates a dated retail entry must call `EntryDates` (pending a guard test that finds such routes).
