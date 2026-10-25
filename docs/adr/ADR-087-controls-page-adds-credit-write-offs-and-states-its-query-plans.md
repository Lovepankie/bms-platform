# ADR-087: The Controls page counts credit write-offs, and its query plans are stated for a large tenant

## Status

Proposed (2026-10-25, issue #357, owner request of 20 Oct 2026, go-live priority 25 Oct). Amends ADR-047 (the Controls page)
and builds on ADR-043 (the write-off of a credit sale, migration `V64`) and ADR-028 (query plans before indexes). Migration `V101`
(two partial indexes on sale lines).

## Context

ADR-047 built the Controls page (issue #358, group 1 of #357) and left one metric out because its data was not on `main`:
credit write-offs (#349). `retail_sale_writeoffs` (`V64`, ADR-043) is now on `main`, one row per credit sale written off, with
the business day, the unpaid amount, the reason, who wrote it off and, when the tenant's threshold asked for one, the second
administrator who approved it. The owner's request lists write-offs among the first cards, beside voids, returns, corrections
and changed prices. ADR-047 also asked that the statements be watched on a large tenant; the go-live review asks for the plans
to be stated rather than assumed.

## Decision

- **One more metric, `credit_writeoffs`, read from `retail_sale_writeoffs`; nothing else changes shape.** It is one more
  statement in `ControlsRepository.events`, in the same columns as the others, so its card, its groups, its records and its CSV
  agree in the way ADR-047 describes. Nothing is written, posted or audited by a read.
- **What an event is.** A write-off of a sale that is not imported history, on its `written_off_on` (the business day in the
  tenant's zone, set by the write-off itself, so the Kampala day is never recomputed from UTC). The sale number and the link
  open the sale. The reason is the reason typed when it was written off.
- **The value is the unpaid amount, a sale price, never the loss.** `unpaid_minor` is what the buyer still owed at the prices
  of the sale. `loss_minor` and `cost_share_minor` are cost-based under the "when the buyer pays" rule (ADR-043), so they never
  leave the module on this page; the card needs no `retail.profit.read`.
- **The person is whose sale it was; the approver is who wrote it off.** The same rule as a void (ADR-047): a write-off is a
  sale on credit that was never paid, so the seller who gave the credit is the person and is compared by a rate; the
  administrator who wrote it off is the approver (the record reads "Written off by ..."). When a second administrator
  approved it, the approver is still the one who made the write-off; the approval is on the approvals page.
- **A rate and a share, like the other sale metrics.** Sales written off per 100 sales entered by the same shop, person or day,
  and the unpaid value as a share of the completed sales of the period. A write-off is counted on the day it was made against
  the sales entered that day, the same accepted untidiness as a void of last month's sale (ADR-047).
- **Not in the repeat patterns, for now.** A write-off is rare, decided by an administrator and usually months after the sale,
  so "3 cases over 10 sales" in one window would flag almost nobody and the wrong window for the rest. The groups by person
  already compare sellers by rate. Revisit with real data.
- **Always tracked.** Credit sales need no capability, so the card never reads "not switched on".
- **The statements turn JIT off for their own transaction.** `ControlsRepository` runs `SET LOCAL jit = off` before the cards,
  groups, records and patterns. The union of the events is a large plan, and PostgreSQL's default JIT spent about 4.9 seconds
  compiling a year's cards that then ran in a fraction of that. `SET LOCAL` ends with the read-only transaction; nothing else in
  the API is affected.
- **Two partial indexes, `V101`.** `retail_sale_lines_price_changed (tenant_id, sale_id) WHERE price_source = 'override'` and
  `retail_sale_lines_below_cost (tenant_id, sale_id) WHERE line_total_minor < line_cost_minor`. Every other metric already had
  an index on its own day (ADR-047), but the three sale line metrics (changed prices, discounts, below cost) read the tenant's
  whole line history. The lines they want are a small part of all lines, so the indexes stay small.
- **The query plans, stated.** `ControlsQueryPlansIT` (off by default, `-Dbms.controls.plans=true`) makes real rows through the
  API, copies them to one tenant of 10 shops, 31 people and two years (1,020,030 sales, 2,040,060 lines, 2,182,072 stock
  movements, 1,077,951 journal entries; 68,002 each of voids, returns and write-offs, a deliberately heavy 6.7 in 100 sales each) and explains every
  statement as `bms_app` under row-level security. The full plans are in `docs/specs/controls-query-plans-357.md`. In short:

  | Statement (every shop unless named) | Before | After | Access path after |
  |---|---|---|---|
  | Sales entered, a month | 24 ms | 20 ms | `retail_sales_by_date` |
  | Every card, a month | 956 ms | 392 ms | voids by `retail_sales_voided`; changed prices, discounts and below cost by the new partial indexes joined to the month's sales by `retail_sales_by_date`; write-offs and returns by their day; restocks voided through `retail_stock_movements_source` |
  | Every card, a month, one shop | 143 ms | 63 ms | the same, with the shop in every index condition |
  | Every card, a year (366 days, the widest window) | 6,039 ms | 2,563 ms | the same; 208,000 events sorted for the distinct sales |
  | Groups by person, a month (voids, write-offs, changed prices, restocks voided) | 39 to 293 ms | 31 to 86 ms | as the cards, then the sales of the window by `retail_sales_by_date` for the rate |
  | Groups of returns by day, a year | 374 ms | 569 ms | returns by day, sales of the year for the rate |
  | Records, 51 a page | up to 30 ms | up to 25 ms | as the cards, ordered and limited |
  | Patterns, people, a month | 417 ms | 162 ms | as the cards, per shop and person |
  | Patterns, items, a month | 744 ms | 848 ms | every line of the month's sales (the denominator is the lines each item sold) |

  "Before" is main's code with JIT on; "after" is this change, with the reversal check of the write-offs, measured on a journal
  of 1,077,951 entries (one in a hundred reversed): the check is an anti-join on `journal_entries_tenant_id_reverses_entry_id_key`
  (index only, about 2 ms over the year's write-offs). The two columns come from separate runs on a shared machine, where the same
  statement varies by about a third between runs; the access paths, not the last millisecond, are the finding. Every statement is bounded by its window (at most 366 days), by the
  caller's shops and, for groups and records, by a page. The returns and write-offs of a year across every shop are joined to the
  sales by a hash join over the year's sales, because there are tens of thousands of them in this seed; at a real rate they are
  read by their own day.
- **Where it is found.** The page stays at `/staff/retail/controls` behind `retail.controls.read` (ADR-047). In the grouped menu
  of issue #564 (pull request #569, ADR-086) it is the Controls row of the Business group, after Reports, for a session that may
  open the page; on the page, Controls is the current row and Reports is not. The Retail page keeps listing it.

## Consequences

**Better:**

- The owner sees every exception the request names on one page: voids, quick cancels, returns, write-offs, changed prices,
  discounts, sales below cost, restocks voided and quantities corrected, each with the trend against the period before.
- No cost leaves the module: the write-off card uses the amount owed, not the loss.

**Worse:**

- A write-off's day is the day it was made, so a sale of March written off in October lifts October's rate. That is the day
  someone decided it, which is the day to see it.
- The approver of a write-off approved by a second administrator is the maker, not the checker. The approvals page has both.

- `lock_timeout` (5 s) in `V101` bounds only the wait for the SHARE lock on `retail_sale_lines`; once the two builds start, every
  sale waits for both full scans until the commit (reports do not). Staging has 22,110 rows (11 MB, measured by the maintainer on
  25 Oct 2026), so well under a second; the harness measured each build at 229 to 440 ms on 2,040,060 rows over three runs. At about 2 million lines a
  deploy needs a window, or a separate migration that builds the indexes CONCURRENTLY outside Flyway's transaction.
- The item patterns still read every line of the window's sales, about 0.6 to 0.9 s for a month on this seed; they are one request a page.

**Watch for:**

- Re-run `ControlsQueryPlansIT` when a metric is added; a new metric read from a large table needs an index on its own day or a
  partial index on its exception, as here.
- If the patterns are extended to write-offs, use a longer window than the page's, not the same thresholds.
- Credit write-offs and returns for every shop at once are read through their day indexes, which lead with the branch
  (`retail_sale_writeoffs_by_day`, V64), so without a shop they read the whole table: about 1,800 buffers on the heavy seed. Accepted
  while the tables are small (one row per write-off or return); add `(tenant_id, written_off_on)` or its returns twin when a plan shows
  it matters.
