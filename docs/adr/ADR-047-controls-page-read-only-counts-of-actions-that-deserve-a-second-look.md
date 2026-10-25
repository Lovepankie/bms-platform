# ADR-047: The Controls page: read-only counts of the actions that deserve a second look, by shop, person, day, reason and approver

## Status

Proposed (2026-10-20, issue #357 group 1, task #358; not yet accepted by the product owner). Builds on ADR-003, ADR-017 and
ADR-020. The migration is `V66`, by the merge order of the open pull requests (V64, V65, then this one). Amended by ADR-087
(credit write-offs are counted, and the query plans are stated for a large tenant).

## Context

A business owner cannot see, in one place, how often sales are cancelled, goods are taken back or a price is changed at the
till, who does it, where, and whether it is more than usual. The data exists (sales carry who voided them and why, returns
carry who took them, sale lines carry how the price was chosen and why it was overridden) but every question needs several
lists and a calculator. Issue #357 asks for a controls dashboard, "metrics first, details on drill-down", with three rules:
people are compared by a rate and never only by a count; wording is plain; named staff appear only for people who may see
them, with no punitive ranking by default. This is group 1 of six; the others (sales quality, cash and credit, stock health,
discipline, the delivery side for the dev team) are separate decisions and pull requests.

## Decision

- **A read-only module surface in `retail.reports`, no new table.** Four `GET` routes under `/api/v1/retail/controls`:
  `summary` (cards), `groups` (a metric grouped by a chain of levels), `records` (the individual events behind a group)
  and `patterns` (people and items that stand out). Nothing writes, posts or audits a read. The queries read the existing
  retail tables, in one statement per metric that returns the same columns, so a card, a group and a record list always
  agree.
- **Six metrics, each defined by what the data records today.** `voided_sales` (a sale with a void, on the business day it
  was voided, with the reason typed), `quick_cancels` (a void within N minutes of the sale being entered, N from 1 to 120,
  default 10), `returns` (a completed customer return, on its return date, with its note), `price_overrides` (a sale line
  whose `price_source` is `override`, with the reason typed), `discounts` (an override line sold below the price the system
  resolved; the value is the money given up) and `below_cost` (a sale line whose total is under its cost snapshot). Overrides
  and discounts exist only while the `price_lists` capability is on and returns only while `returns` is on; a card whose
  capability is off and which has never counted anything says "not switched on" instead of reading as all clear. Imported
  history (`historical`) is neither an event nor a sale in a rate: staff in this system did not do it.
- **A void or a return belongs to the person whose sale it was, not to the person who made it.** By default only the tenant
  administrator can void, so crediting the voider would blame the administrator for the cashier's mistakes and hide the
  cashier. The person level, the rate denominators and the patterns therefore use the sale's `created_by`. Who cancelled the
  sale or took the goods back is the **approver**: a separate level of the chain ("Approver"), a line on every record ("Cancelled
  by ...", "Taken back by ..."), and never part of the patterns. An approver entered no sales of their own to set a rate
  against, so the Approver level shows counts and values and no rate. The lines of a sale (changed prices, discounts, below
  cost) have no approver, so the level is not offered for them.
- **Two stock metrics from #356 (ADR-044), with no rate.** `restock_voids` (a restock voided by reversal: one event for each restock
  and branch it stocked, from its purchase movements, so a restock of 10 at head office and 5 at a second shop is two events and each
  shop's reader sees only its own; the person is who entered the restock, the approver who voided it, the value that branch's quantity
  at the item's selling price now, never the restock's cost; the day the day it was voided, the reason typed) and
  `quantity_corrections` (a quantity corrected on the stock list: the person who corrected it, the reason code and note, the value the
  change at the selling price now, never a cost). They are not about sales, so there are no sales of a person or a shop to set them
  against: they have counts and values, no rate, no share of sales and no pattern, and a correction has no approver. Asking for the
  Approver level of a metric that has none (changed prices, discounts, below cost, corrections) is a 422.
- **Left out until their data is on `main`.** Credit write-offs (#349). It becomes one more metric in `ControlsRepository.events`
  and one more card when its table lands; no other code changes. Done by ADR-087 (`credit_writeoffs`, from `V64`).
- **A rate per 100 sales, never only a count, and in the same units.** A rate is the **sales with such an event** over the
  sales entered by the same shop, person or day in the same days (any status, imported history left out), one decimal, half up.
  A sale with two changed lines counts once ("sales with a changed price per 100 sales"); the line count and the value of the
  lines are shown beside it (the card says "3 lines in 2 sales, lines worth ..."), never divided by sales. A person who entered
  no sales has no rate ("no sales of their own to compare with"). Every card also shows the trend against the previous period
  of the same length and the value as a share of the completed sales of the period (capped at the largest int, since a voided
  value is not part of the completed total and may exceed it).
- **No league tables.** The person, shop and approver levels are ordered by name, not by count or rate; the patterns by shop and
  name, not by how far anyone stands out. Days are newest first and reasons by their count.
- **Repeat patterns: more than twice the shop's own rate, from enough cases.** A person (per shop, the seller whose sales they were) or an item (per shop) is
  flagged when the rate is more than 2 times the shop's rate for the same thing, with at least 3 sales with an event over at least 10
  sales (or, for an item, 10 sale lines). The comparison is made on whole numbers (events times the shop's sales against twice
  the shop's events times the person's), so no rounding decides it. The page words it as a reason to look, not a finding, and
  never ranks people on any other basis. An item's pattern is read by the date of the sale; a person's void by the date of the
  void.
- **Permission: a new `retail.controls.read`, held by the tenant administrator and nobody else by default.** The page names
  staff and compares them, so it is not part of `retail.sale.read`. It is scoped by branch like every retail permission
  (ADR-017): every figure is a sum over the shops of the caller's scope only. Migration `V66` inserts the permission and
  grants it to the global `tenant_admin` role (system roles are global rows, so every existing tenant has it from the
  migration). A business gives it to an owner's or an auditor's custom role.
- **Cost stays behind profit read.** Every value on the page is a sale price. `below_cost` is a judgement against cost, so its
  card, its groups, its records and its patterns are sent only to a caller who holds `retail.profit.read` in every branch the
  figures cover; asking for it without that is 403, and no response ever carries a cost.
- **Names only here.** User names, status (active, invited, deactivated) and reasons appear only on these routes, which need the
  permission. A deactivated person is shown as such. A missing user row reads "Unknown person", is counted, and is not opened (opening it would list everybody).
- **The screen follows the sales lists' Group by pattern (#341, #344).** The chain is shop, person, day, reason and approver, in any order
  the person chooses and keeps (per user, in the browser); each group opens onto the next level and, last of all, onto the
  records, each with its sale number, time, person, approver, reason and amount, and a button that opens the sale. The CSV export is of
  what is on screen, built in the browser so it can never hold more than the person may see; a text that begins like a
  spreadsheet formula is written as plain text.
- **No new index.** The voids of a window are found by `retail_sales_voided (tenant_id, branch_id, voided_at) WHERE voided_at IS
  NOT NULL` (V34); the other metrics are read by the sale date, which `retail_sales_by_date` already serves. `V66` adds the
  permission only.

## Consequences

**Better:**

- An owner sees in one phone screen what was cancelled, taken back or repriced, and whether a person or an item stands out
  from their own shop, without a calculator and without ranking colleagues by a count that only reflects how busy they are.
- The same statements feed the cards, the tables, the records and the patterns, so they cannot disagree, and the CSV is the
  table on screen.
- The cost rule is unchanged: no cost leaves the module without `retail.profit.read`, and the one cost-based metric is simply
  absent otherwise.

**Worse:**

- A pattern is statistics on small numbers and says nothing about intent; the thresholds (3 cases, 10 sales, 2 times) are
  constants of the module, not settings, and will be tuned from use.
- A void is counted on the day it was made, while the sales it is compared with are counted on the day they were entered, so a
  void of last month's sale lifts today's rate. This is the right day to see it, and the less tidy rate is accepted.
- Price overrides are only recorded while the `price_lists` capability is on. Before it, a typed price left no mark, so the
  page cannot see it.

**Watch for:**

- The groups and patterns statements scan the sales and lines of the window (at most 366 days) for the caller's shops; the
  indexes are the existing date indexes. If a large tenant makes them slow, add a covering index in a later migration, not a
  summary table.
- When #349 and #356 merge, add their metrics rather than a second page, and re-check the matrix and the ADR index.
- Groups 2 to 6 of #357 must not reuse `retail.controls.read` for a different audience: sales quality per seller has its own
  rule (profit read at every branch covered, as in #344).
