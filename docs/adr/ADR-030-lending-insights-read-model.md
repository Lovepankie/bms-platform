# ADR-030: Lending insights: a read model over the loan tables and the ledger, live today and snapshots for history, polling, and a plain-text digest through the outbox

## Status

Proposed (issue #153, expectation 7 of the pilot brief: live insights on loan and investment
performance, member activity and operational trends). Builds on ADR-002 (module boundaries),
ADR-003 (row-level security), ADR-004 (integer money, the ledger), ADR-008 (jobs), ADR-024 (the
outbox) and ADR-026 (loan servicing). Adds migration `V33` (the number is confirmed when the pull
request is marked ready, as Flyway runs with `outOfOrder` off).

## Context

The pilot tenant asked for live insights: what went out and came in today, collections against
what was due, arrears and portfolio at risk, revenue by product, member activity and trends. The
SDD defines most of the formulas (chapter 14) but not where the numbers are computed, how history
is kept before the arrears job of increment 6 exists, how the page stays live, or how the owner
gets a daily summary. Six questions needed an answer:

1. Where the insights code lives, given that it reads the tables of `lending.loans`,
   `lending.members` and `core.ledger`, and modules talk only through public interfaces.
2. Which source each number comes from: the loan subledger columns, the schedule and transaction
   rows, or the ledger.
3. How a past date is answered. Chapter 14 names `lending_loan_daily_snapshots` (FR-ARR-02), to be
   written by the nightly arrears job of increment 6 (#109), which is not built.
4. How the page stays live without websockets, on a small staging host (ADR-018).
5. What a loan officer sees, and what an export may carry.
6. How the daily digest reaches the owner while the WhatsApp relay is a later slice.

## Decision

1. **A read-only module, `lending.insights`, queries the other lending modules' tables with plain
   SQL.** It never writes them, and every query runs as `bms_app` under row-level security
   (ADR-003), in a read-only transaction. A Java interface per figure on each owning module would
   cost a dozen ports and many round trips for what is one aggregate query each; the
   module boundary test still forbids Java calls into their `internal` packages. The coupling is
   to table and column names, which the migrations already treat as a contract (expand and
   contract, chapter 6 section 6.9).
2. **Each number comes from the rows that define it.** Flows (disbursed, collected, expected,
   collected on due, write-offs, recoveries) from `lending_loan_transactions`,
   `lending_schedule_items` and `lending_repayment_allocations`, with reversals handled as chapter
   14 says. Stocks (outstanding, interest receivable, arrears, DPD, PAR) from the schedule items,
   not from the loan's cached `days_past_due`, which is refreshed only at money events until the
   nightly job of increment 6 exists. **Revenue only from posted journal lines** on the five
   lending revenue accounts, joined to the loan through the entry's source: no estimate from
   schedules. Profit contribution is income less write-off expense; no cost of funds or operating
   cost is allocated, and the page says so.
3. **Today is live; history is the snapshot table of chapter 6, written here until increment 6.**
   `V33` creates `lending_loan_daily_snapshots` exactly as chapter 6 defines it. A nightly job
   (`lending.insights-snapshot`, 00:45) fills every date after the last one written up to
   yesterday and always rewrites yesterday. A past position is rebuilt from the allocation rows
   dated by their own transaction's value date, so a date shows the book as it stood that evening;
   the same rebuild serves the fabricated seed's backfill. When the arrears job of increment 6
   lands it writes the same rows and this job is removed. A past date with no snapshot shows "no
   snapshot", never a guess.
4. **Polling every 60 seconds, no websockets.** Each response is computed per request with indexed
   queries (`V33` adds five indexes) and sent with `Cache-Control: no-store`. The page loads the
   brief, the portfolio and the panels at once and the other sections when opened. The target is
   under a second on the staging stack with the fabricated seed scaled 20 times, measured by
   `InsightsPerformanceIT`.
5. **Permissions: read, all officers, export.** `lending.insights.read` opens the page in the
   caller's branch scope. Without `lending.insights.all_officers` the server narrows every figure
   to loans the caller is the responsible officer for, whatever the request asks (loan officers).
   `lending.insights.export` exports a drill-down table as CSV, audited. Tables identify a member
   by member number; a name appears only in the action lists (arrears, the due forecast, dormant
   members) and is reduced to initials in an export for a caller without `lending.members.read`;
   no table carries a phone or an ID number.
6. **The digest is plain text queued in the outbox, off by default.** A tenant admin
   (`core.settings.manage`) names up to five email addresses and one numeric Telegram chat and an
   hour. A job checks every 15 minutes and queues the brief's sentences and two portfolio lines
   once a day, idempotent by tenant, date and recipient. The Telegram sender now accepts a
   `chat:<id>` recipient as well as the operator chat; the bot can only write to a chat that started
   it. The text has no formatting, so the WhatsApp relay of a later slice can send it unchanged.
7. **Savings and investments plug in.** `InsightsPanel` is a public interface; the savings (#151)
   and investments (#152) modules expose `SavingsMetrics` and `InvestmentMetrics`, and two adapters
   in `lending.insights` turn them into panels (`lending.insights` may depend on both modules; neither
   depends on it). A panel is shown only to a tenant with savings accounts or investments, and not
   under a one-officer filter, since neither carries a responsible officer.

## Consequences

**Better:**

- Every number is recomputed from raw rows in the golden tests and has its definition on the page
  and in `docs/specs/lending-insights-metrics.md`; revenue reconciles to the ledger by construction.
- History exists before increment 6, in the table increment 6 will own, with no second format.
- No new infrastructure: no cache, no websocket server, no chart library.

**Worse:**

- `lending.insights` breaks if a lending table is renamed or a column changes meaning; a
  migration that does so must update these queries in the same pull request.
- Each refresh recomputes from rows: cost grows with the book. Fine at the pilot's size and at 20
  times the demo seed; a much larger tenant may need materialised daily aggregates.
- The digest's figures sit in the platform outbox (not tenant scoped) until sent, then the
  parameters are cleared, as for every outbox row (ADR-024).

**Watch for:**

- Increment 6 must take over the snapshot job and delete `lending.insights-snapshot`, and its
  penalty charges and waivers must be dated so a past position stays exact (waivers are treated
  as undated today; none exist yet).
- The average daily balance under the yields counts only days with a snapshot; for a range that
  starts before the first snapshot the yield is over fewer days than the range, as its definition
  on the page says.
- A Telegram chat id typed wrong sends nothing and the row fails in the operator's outbox view.
