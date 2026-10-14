# 14. Reporting

**Status:** Draft · **Owner:** Hillary

## 14.1 Purpose and common rules

Every report the core and the lending module provide: what it answers, where its data
comes from, and the exact formula. A report is correct when a hand calculation on a
fabricated dataset matches it (FR-RPT-05).

Common rules for every report:

- **Parameters.** An as-of date or a date range (inclusive), a branch selection (one,
  several, or all branches in the user's scope), and report-specific filters. Parameters
  are printed on every output (FR-RPT-02).
- **Branch consolidation.** "All branches" is the sum over the selected branches. For
  ledger reports the inter-branch clearing account nets to zero in a full consolidation
  and is shown when it does not (partial selection).
- **Money.** Amounts are integer minor units summed exactly; percentages are computed from
  those integers and shown to one decimal place, rounded half up.
- **As-of dates.** For lending balances "as at today" the report reads the live loan
  tables. For a past date it reads `lending_loan_daily_snapshots` (FR-ARR-02) and says so.
  Snapshots exist from the first nightly run; a past date before that returns
  `snapshot_unavailable`.
- **Reversals.** A reversed transaction and its reversal are both excluded from
  "collected" and "disbursed" measures; ledger reports include both lines, which cancel.
- **Permissions.** Each report names its permission (chapter 8). Loan officers see only
  loans where they are the responsible officer in collections reports.
- **Exports.** CSV for every report; PDF for every report; XLSX for financial statements and, under ADR-038, for the seven
  formal reports of the pack (section 14.10).
- **Runs.** Reports expected to take over five seconds run asynchronously in one read-only `REPEATABLE READ` snapshot
  (section 14.10).

Report keys below are the `report_key` values in the API (chapter 7 section 7.11.8).

## 14.2 Financial reports (core, `core.reports.financial`)

All read `journal_entries` and `journal_lines` only. For an account, over a set of
lines: `D = sum(debit)`, `C = sum(credit)`.

### `core.trial_balance`: Trial balance

Parameters: `from`, `to`, branches.

Per postable account, with lines whose entry `branch_id` is selected:

- opening = `D - C` over lines with `entry_date < from`, presented on the debit side if
  positive and the credit side if negative;
- period debits = `D`, period credits = `C` over `from <= entry_date <= to`;
- closing = opening plus period debits minus period credits, presented like opening.

Check row: total closing debits equal total closing credits; total period debits equal
total period credits. A difference is a defect and the report shows it in red rather
than hiding it.

### `core.income_statement`: Profit and loss

Parameters: `from`, `to`, branches. Income accounts: `C - D` in the period. Expense
accounts: `D - C` in the period. Grouped by parent account. Net profit = total income
minus total expenses. A comparison column for the same length of the previous period is
optional.

### `core.balance_sheet`: Balance sheet

Parameter: `as_at`, branches. Cumulative to `as_at`:

- assets: `D - C` per asset account;
- liabilities: `C - D` per liability account;
- equity: `C - D` per equity account, plus two computed lines, because the MVP posts no
  year-end closing entries:
  - **retained earnings brought forward** = cumulative (income `C - D` minus expense
    `D - C`) for entries dated before the start of the financial year containing
    `as_at`;
  - **current year earnings** = the same for entries from the financial year start to
    `as_at`.

The financial year is the calendar year (a tenant setting for another year end is
`Later`). Check: total assets equal total liabilities plus total equity.

### `core.gl_detail`: Account activity

Parameters: account, `from`, `to`, branches. Opening balance, each line (date, entry
number, reference, memo, debit, credit, running balance), closing balance.

### `core.cash_book`: Cash and mobile money book

Parameters: payment method account (cash, bank, MTN, Airtel, gateway clearing), date
range, branch. Per day: opening, receipts (`D`), payments (`C`), closing. Used for the
daily cash count by the cashier and branch manager.

### `core.subledger_reconciliation`

Parameter: `as_at`. Per branch and control account (`loans_receivable`,
`member_savings`, `investments_payable`, `investment_returns_payable`,
`member_overpayments`): ledger balance, subledger total, difference.

| Control account | Subledger total |
|---|---|
| `loans_receivable` | Sum of `principal_outstanding_minor` over loans with status `active` (and not `written_off`) |
| `member_savings` | Sum of `balance_minor` over savings accounts |
| `investments_payable` | Sum of `principal_minor` over investments `active` or `matured` |
| `investment_returns_payable` | Sum of `return_due_minor - return_paid_minor` over investments |
| `member_overpayments` | Sum of `credit_balance_minor` over loans |

Any non-zero difference raises the FR-GL-10 alert.

## 14.3 Portfolio reports (lending, `lending.reports.portfolio`)

Definitions used below, for an as-of date `A`:

- **Active loan**: status `active` on `A`.
- **Principal outstanding** (`PO`): `principal_outstanding_minor` on `A`.
- **DPD**: chapter 3 rule R-DPD on `A`.
- **PAR bucket**: `current` (DPD 0), `1_30` (1 to 30), `31_60` (31 to 60), `61_90`
  (61 to 90), `over_90` (91 and above). This is the "90+" bucket of the product brief.

### `lending.portfolio_outstanding`

Parameters: `as_at`, branches, group by (`branch`, `product`, `officer`, `purpose`,
`none`). Per group: number of active loans, number of distinct borrowers, `PO`, scheduled
interest outstanding (`interest_outstanding_minor`), fees and penalties outstanding,
arrears, average `PO` per loan.

**Built (extras slice D2, issue #217), with two differences from the list above.** Grouping is `none`, `branch`, `product` or
`officer`; and fees and penalties outstanding are not in the table. Both are limits of the figures the insights module holds, which the
report must not recompute (section 14.10): a loan's position carries principal, interest and arrears, and the nightly snapshot
(`lending_loan_daily_snapshots`) has no column for fees or penalties and no loan purpose, so a past date could not be served. They arrive
with the arrears job of increment 6, which owns the snapshot, and the report says so in a note. The table (`portfolio`) has, per group
and in a last total row: active loans, borrowers, principal outstanding, interest outstanding, arrears, and the average principal per
loan (`PO` divided by the loans, rounded half up, empty when there are none). Borrowers are the distinct members with an active loan
in the group, so a member with loans in two branches counts once in each and once in the total, and the groups can add up to more than
the total; the report says so in a note.

### `lending.par`: Portfolio at risk and ageing

Parameters: `as_at`, branches, group by.

- `PAR_x` = (sum of `PO` over active loans with DPD > x) / (sum of `PO` over all active
  loans), for x = 0 (reported as PAR1), 30, 60 and 90.
- Ageing table per bucket: loan count, `PO`, percentage of total `PO`, arrears amount.
- Written-off loans are excluded from both numerator and denominator.
- Restructured loans (the new loan) are flagged and also shown as a separate line for 12
  months after restructure (FR-LCL-04).

Worked example (fabricated): active `PO` totals 10,000,000; loans with DPD 1 to 30 hold
1,000,000, DPD 31 to 60 hold 500,000, over 90 hold 300,000. PAR1 = 18.0 percent,
PAR30 = 8.0 percent, PAR60 = 3.0 percent, PAR90 = 3.0 percent.

### `lending.disbursements`

Parameters: date range, branches, group by. Loans with a `disbursement` transaction whose
`value_date` is in the range and which is not reversed: count, total principal
disbursed, fees deducted at disbursement, average and median loan size.

### `lending.loans_by_status`

Parameters: `as_at`, branches. Count and requested or approved principal per status, and
applications' average days from `submitted` to `approved` and from `approved` to
`active` (from `lending_loan_status_history`).

### `lending.maturity_profile`

Parameters: `as_at`, branches. Unpaid principal and interest of schedule items of active
loans, grouped by due month: overdue (before `as_at`), then each of the next 12 months,
then later.

### `lending.write_offs_recoveries`

Parameters: date range, branches. Loans written off in the range (count, principal
written off, interest, fees and penalties written off in the subledger); recoveries in
the range (sum of `recovery` transactions).

### `lending.portfolio_trend`

Parameters: months (default 12), branches. For each month end: active loans, `PO`,
PAR30, disbursements in the month, collections in the month. Month-end values come from
`lending_loan_daily_snapshots` for the last day of the month.

## 14.4 Collections reports (lending, `lending.reports.collections`)

### `lending.due_list` (FR-CLN-02)

Parameters: date range (default today), branches, officer. One line per schedule item of
an active loan with `due_date` in range: member number and name, phone, loan number, due
date, amount due (`principal + interest + fees + penalties` due), paid, waived,
outstanding, officer. Sorted by officer then due date.

### `lending.arrears_list` (FR-CLN-03)

Parameters: `as_at`, branches, officer, bucket. One line per active loan with DPD above
0: member, phone, loan, DPD, bucket, arrears (principal, interest, fees), penalties
outstanding, `PO`, last repayment date, last collection action and its date, open
promise to pay. Grouped by bucket.

### `lending.expected_vs_collected`

Parameters: date range `[from, to]`, branches, group by (`branch`, `officer`, `product`).

For schedule items of loans that were active at any point in the range, and repayment
allocations from transactions not reversed:

- **Expected** = sum of `principal_due + interest_due + fees_due` of items with `due_date`
  in `[from, to]` (penalties excluded, since they are not scheduled).
- **Collected on due** = sum of allocations (principal, interest, fees) to those same
  items, from transactions with `value_date <= to`.
- **Collection rate** = collected on due / expected.
- **Arrears recovered** = allocations in the range to items with `due_date < from`.
- **Prepayments** = allocations in the range to items with `due_date > to`.
- **Penalties collected** = penalty allocations in the range.
- **Total collected** = sum of repayment transactions in the range; it equals collected
  on due (for allocations inside the range) plus arrears recovered plus prepayments plus
  penalties collected plus overpayments, and the report shows that equation.

### `lending.officer_performance` (FR-CLN-06)

Parameters: date range, branches. Per officer: active loans and `PO` at range end,
disbursements in range, expected and collected on due (as above), collection rate, PAR30
at range end, collection actions logged, promises kept and broken.

### `lending.promises_to_pay`

Parameters: date range, branches, officer, status. Promises with their outcome
(FR-CLN-05).

## 14.5 Member, savings, investment and collateral reports (`lending.reports.members`)

### `lending.member_register`

Parameters: `as_at`, branches. Members by status, KYC status, gender, and with or without
portal access; new members in the range when a range is given.

### `lending.member_activity`

Parameters: date range, branches. New members; members with a disbursement, a repayment,
a savings transaction or an investment transaction in the range; members with no
activity in 90 days.

### `lending.savings_balances`

Parameters: `as_at`, branches, product. Per account: member, balance, status, last member
transaction. Totals per product and branch reconcile to `member_savings`. Served as JSON by
`GET /lending/savings-reports/balances` until the report catalogue serves files (#151): balances
by value date from the movements, so they agree with the end-of-day balances of chapter 6.

### `lending.savings_movements`

Parameters: date range, branches, product. Deposits, withdrawals, interest posted, fees,
net movement; opening and closing totals. Served as JSON by `GET /lending/savings-reports/movements`
(#151), per branch and product, with reversals into and out of accounts shown apart.

The savings figures of the insights page (balances, inflows net of reversed deposits, outflows net
of reversed withdrawals and fees, interest paid, dormant accounts) come from the same queries
through the `SavingsMetrics` interface (ADR-032).

### `lending.investments_register`

Parameters: `as_at`, branches, status. Per investment: member, principal, rate, start,
maturity, agreed return, return due, return paid. A "maturing within N days" filter
drives the payout planning view.

Increment 10 (#152) serves it as the investments list (`GET /lending/investments` with
`status`, `branch_id` and `maturing_within_days`); the catalogue entry and its CSV arrive with the
reports increment.

### `lending.investment_maturities` and the investment metrics (FR-INV-12)

The maturity ladder: investments `active` or `matured` with principal held, by when they fall due
from today: `overdue` (matured, not yet paid out), `0_7`, `8_30` and `31_90` days; per bucket the
count, principal held, the return still owed at maturity (the agreed return less return paid; once
matured, accrued less paid) and the total, so the owner can plan cash for payouts. The metrics for a
date range and branches, each from posted rows only (ADR-031 decision 8):

| Metric | Definition |
|---|---|
| `investments.balance` | Balance of `investments_payable` (2020) at the end date |
| `investments.returns_payable` | Balance of `investment_returns_payable` (2021) at the end date |
| `investments.inflows` | Fundings by value date in the range, less fundings reversed; rollovers are not new money |
| `investments.outflows` | Return payouts, maturity payouts and early withdrawals paid in the range, less reversals |
| `investments.returns_accrued` | Net debits to `investment_return_expense` (5020) in the range |
| `investments.returns_paid` | The return part of payouts and early withdrawals in the range, less reversals |
| `investments.returns_reinvested` | Unpaid returns moved into a new investment by a rollover in the range |
| `investments.penalties` | Net credits to `investment_penalty_income` (4060) in the range |
| `investments.open_count`, `investments.investor_count` | Open investments and the members holding them, now |

Concentration: the ten largest investors and every product by principal held now, each with its
share of the total in basis points. Served by `GET /lending/investments/maturities` and
`GET /lending/investments/metrics`, and to other modules (the insights page of #153) through the
module's `InvestmentMetrics` interface.

### `lending.collateral_register` (FR-COL-06)

Parameters: `as_at`, branches, type, custody status. Per item: member, type, reference,
latest value, custody status and location, linked loans and their status. Flags: held in
custody while every linked loan is closed ("release overdue"); imported items not yet
verified (13.6.8); items without a valuation.

## 14.6 Operational and audit reports

### `lending.operational_trend` (`lending.reports.portfolio`)

Last 12 months: disbursements, collections, new members, PAR30, SMS segments sent, portal
sign-ins.

### `core.audit_log` (`core.reports.audit`)

The audit search of FR-AUD-04 as an export.

### `core.approvals_log` (`core.reports.audit`)

Parameters: date range, action type. Every approval request: maker, checker, requested
and decided times, amount, outcome, time to decision.

### `core.sms_usage` (`core.reports.financial`)

Segments sent per month and per event type (FR-NTF-07).

## 14.7 Compliance reports (`lending.reports.compliance`)

The regulator's return formats for the pilot tenant are an open question
(`docs/specs/lending-mvp-scope.md`). Until they are known, one report provides the data
such returns usually need, and the format-specific returns are added once the formats
are in hand:

### `lending.regulatory_summary`

Parameters: period end, branches. Number of active borrowers by gender; loans disbursed in
the period by count and amount; portfolio outstanding by product and purpose; PAR30 and
PAR90; interest rate range and weighted average per product (in bp, weighted by `PO`);
loans written off in the period; savings held from members (total, number of accounts);
investments held from members (total principal, number); number of branches and staff.

## 14.9 Live insights (`lending.insights.*`, #153, ADR-030)

The staff Insights page is not a catalogue report: it is computed per request, live for today,
and refreshed every 60 seconds. It uses the definitions of this chapter: PAR, buckets and DPD of
section 14.3, expected, collected on due and collection rate of `lending.expected_vs_collected`
(section 14.4), with the reversal rule of section 14.1, and for a past date the snapshots of
`lending_loan_daily_snapshots`. Its revenue figures come only from posted journal lines on the
lending revenue accounts. In a series by period, a period's collection rate counts what was paid by
the end of that period; the headline for the whole range counts what was paid by its end, as
`lending.expected_vs_collected` does. Every metric, its formula and its source tables are listed in
`docs/specs/lending-insights-metrics.md`; each has a golden test (chapter 15 section 15.7). Until
the report runs of FR-RPT-04 exist, the insights drill-down tables are the CSV exports of these
figures (FR-INS-06).

## 14.10 The reports pack and report runs (ADR-038, issue #111)

Design only. The seven formal reports are defined in sections 14.3, 14.4, 14.5 and 14.7 and are not redefined here:

| Report | Key | Section |
|---|---|---|
| Portfolio | `lending.portfolio_outstanding` | 14.3 |
| Portfolio at risk and ageing | `lending.par` | 14.3 |
| Collections | `lending.expected_vs_collected`, `lending.arrears_list` | 14.4 |
| Disbursements | `lending.disbursements` | 14.3 |
| Officer performance | `lending.officer_performance` | 14.4 |
| Collateral register | `lending.collateral_register` | 14.5 |
| Regulator summary | `lending.regulatory_summary` | 14.7 |

Rules added by the pack:

- **One implementation per figure.** The reports reach the insights figures through the public read port `InsightsFigures`
  of `lending.insights` (the insights queries and `Scope` stay internal to it), so the reports and the page share the metric
  definitions of section 14.9; only the reports the page does not compute have their own queries. A
  cross check test asserts PAR30, PAR90, principal outstanding, collection rate and disbursed equal the insights values for
  the same range.
- **One snapshot, then render.** A run queries in a single read-only `REPEATABLE READ` transaction into a disk spool and commits
  before rendering; it records `as_of` and `snapshot_at`, and a pack's seven reports agree with each other. On staging (about
  224 MB of heap) one run at a time, 500 PDF rows and 20,000 sheet rows, each report rendered to its own PDF and merged on disk.
- **Outputs.** (Built in slice D2, issue #217: see the paragraph below.) PDF through the shared document templates (landscape above seven columns, header repeated, a parameters box,
  tables over 3,000 rows cut with a pointer to the Excel file) and Excel through a streaming writer (numeric cells, real
  dates, explicit string cells for text starting with `=`, `+`, `-` or `@`, 200,000 row cap per sheet). Totals come from one
  piece of code, so the files cannot differ.
- **The pack** is a combined PDF (cover with parameters and a sign-off block, contents, the seven reports) and one workbook
  (a sheet per report and a Parameters sheet) from one snapshot. It needs every report's permission.
- **Regulator summary.** Labelled "Data summary. Not a filed return." until the client's return formats are known.
- **Retention.** Run files are deleted after 30 days; downloads then answer 410 `report_expired`.

**Built (extras slice D1, issue #214).** The run framework (`core.reporting`: queued runs, the runner job, one snapshot
transaction per run into a disk spool, the JSON and CSV renderers, cancel, the 30 day file expiry, the catalogue and the run
routes of chapter 7 section 7.11.8, a minimal Reports screen) and the first report, `lending.par`, as JSON and CSV. `lending.par`
takes `as_at` (today by default; a past date reads `lending_loan_daily_snapshots` and is refused with `snapshot_unavailable`
when none was written), `branch_id` (repeatable) and `group_by` (`none`, `branch`, `product` or `officer`), and writes two
tables: `par` (per group and a total row: active loans, principal outstanding, arrears, PAR 1, 30, 60 and 90 in basis points
of the group's principal) and `ageing` (per group and bucket: loans, principal outstanding, share of the group's principal,
arrears). The figures come from the public `InsightsFigures` port (`lending.insights`); slice D2 (issue #217) added the portfolio
figures to it and the remaining reports add theirs. A test asserts the report's principal outstanding, arrears, active loans and PAR 1,
30, 60 and 90 equal the insights portfolio metrics for the same date. The restructured loan's separate line (FR-LCL-04) is not
printed, because loans cannot be restructured yet. The business date is the one of the request, and the figures are the book of that date: a run asked for at 23:59 and executed
after midnight no longer finds that book in the loan tables (they hold the new day's disbursements and repayments), so it reads the
date's nightly snapshot when the job has written it and is refused with `report_stale_as_of` when it has not (the user asks again
once the snapshot exists, or for today). The figures are totalled as the loans are read, in
blocks, so memory grows with the number of groups, not of loans; a cancelled or timed out run stops within 1,000 loans. The tenant
switch is the setting `reports_enabled` (chapter 6 `tenant_settings`), also in `GET /me`.

**Built (extras slice D2, issue #217).** The PDF and Excel renderers, and the second report, `lending.portfolio_outstanding`. No
migration (the `format` check of `report_runs` already allowed `pdf` and `xlsx`) and no permission (both reports run under
`lending.reports.portfolio`, and need `lending.insights.read` as well, as `lending.par` does).

- **Formats.** `lending.par` and `lending.portfolio_outstanding` are offered as `pdf`, `xlsx`, `csv` and `json`; a report writes its
  tables once and every format is rendered from the same spool, so the figures cannot differ. `lending.portfolio_outstanding` takes
  `as_at`, `branch_id` and `group_by` like `lending.par`, and writes one table (section 14.3). Both read the figures through
  `InsightsFigures` (the new `portfolio` method beside `par`, over the same positions, the same bucket rule and the same basis point
  rounding as the insights page); tests assert that the total and every branch, product and officer row equal the insights endpoints
  for the same filter and caller, in every format, and that a branch limited or officer limited caller gets their own scope in the
  PDF and the workbook.
- **PDF.** The shared `report` template over the ADR-034 letterhead: the tenant's logo, name and brand colour, a readable number
  `RPT-yyyymmdd-xxxxxx` (the date the file was made in the tenant's time zone and six characters of the run's id, kept as the
  document's number), a centred title, a parameters box (the date the figures stand at, each parameter in words, the currency, who
  made the file and when, when the data was read, the notes), then each table with its heading repeated on every page, tinted rows and a
  total row set off between rules, and "page x of y" in the footer. A4, landscape when a table has more than seven columns, Open Sans
  embedded. Money is in whole currency units with the currency in the column heading, a rate is a percentage to one decimal made from
  the integer basis points half up (so 755 basis points reads 7.6%, not 7.5%), a date is `8 Oct 2026`, a missing figure is a dash, and
  a long unbroken word wraps inside its cell. A table longer than `BMS_REPORTS_PDF_ROW_CAP` (3,000; 500 on staging) is cut to that many
  rows with the printed note "Showing the first 500 of 900 rows. The full detail is in the Excel file." and keeps its total row. A
  character the font lacks prints as "?" (ADR-034). The layout is read from the spool after the snapshot has committed and holds no
  transaction and no connection; a run waits for a render place up to its own deadline.
- **Excel.** A minimal streaming writer in `core.reporting` (Apache POI is not in the build, ADR-038 amendment): a `Parameters` sheet
  first (the report, the date as a real date, when the data was read and the file made, by whom, the currency, the parameters in words,
  the notes), then a sheet for each table named by its title. Money is a numeric cell holding the exact decimal of the integer minor
  units with a thousands format; a rate is the exact fraction (0.1389) shown as `0.0%`; a date is a real date; a count is a number; a
  missing figure is an empty cell. The header row is frozen, filled in the brand colour and repeated on every printed page, the total
  row is bold between rules, and the sheet prints landscape above seven columns with "Page x of y". Totals are the report's own values,
  not formulas. Text is always a string cell, never a formula, and text that begins with `=`, `+`, `-`, `@`, a tab or a carriage
  return also carries the quote prefix style, so it stays text when edited; characters XML cannot hold are dropped and a cell is cut at
  32,767 characters. The workbook is streamed to disk a row at a time.
- **Memory.** `ReportMemoryIT` runs both reports as PDF and Excel on the seeded book, a sheet of 20,000 rows of eight columns (the staging
  cap) and a PDF of a table of 20,000 rows cut at 500, in a child JVM on 224 MB of heap, with the old generation's peak under nine tenths
  of its limit (it measured 118 and 124 of 149 MB in two runs, the application alone holding 51 MB). At the production cap of 3,000 PDF rows the same run
  finishes but the old generation fills its limit, so staging keeps 500 and a production host with more heap takes 3,000
  (`-Dreports.memory.pdf3000=true` repeats the measurement).
- **Business date.** The figures are those of the request's business date in Kampala, whichever format: a PDF or workbook asked for at
  23:59:30 and made after midnight says "As at 8 Oct 2026" and "Made 9 Oct 2026 00:01" and holds the 8th's book, read from the 8th's
  snapshot. The loan tables are the 9th's by then, so a run whose date has passed without its snapshot is failed `report_stale_as_of`
  rather than printing the 9th's figures under the 8th (a test lands a disbursement of the 9th between the queue and the run). Tests queue
  across 20:59 and 21:01 UTC. The time the data was read is the business clock's, taken as the snapshot transaction starts.
- **Scope in words.** The first lines of the PDF (the letterhead's second line and the parameters box) and of the Parameters sheet say
  what the figures cover, from the frozen scope: "All branches in your access", "1 branch selected", "2 branches selected", and ", your
  own loans only" for a caller without `lending.insights.all_officers`.
- **Render places.** A report PDF takes at most one of the document renderer's two places and builds its model only once it holds it, so
  a receipt or a statement asked for while two reports are made still finds a place (a test holds a report in its layout and renders a
  document beside it).

## 14.8 Test datasets

Each report has a fabricated dataset (a handful of loans, repayments and journals) with a
hand-computed expected output committed next to its test (chapter 15 section 15.7). The
PAR worked example above is one of them.
