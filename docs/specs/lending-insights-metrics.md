# Lending insights: metrics dictionary

**Status:** Draft · **Issue:** #153 (expectation 7 of the pilot brief) · **Decision:** ADR-030 ·
**Design:** SDD chapter 14 section 14.9, chapter 7 section 7.11.21, chapter 8 section 8.3.2 ·
**Code:** `backend/src/main/java/com/rincoltech/bms/lending/insights/` (`Metrics.java` holds the same keys and the
tooltip texts; `InsightsRulesTest` fails when this file and the code drift)

Every number on the staff Insights page, what it means, how it is computed and which tables it reads. The rules of
chapter 14 section 14.1 apply throughout: amounts are integer minor units summed exactly in the database;
percentages are basis points computed from those integers and shown to one decimal place, rounded half up; a rate
over nothing (a zero denominator) is empty, never zero.

## Common rules

- **Filter.** Every figure takes the page filter: a date range `[from, to]` (inclusive, default the first of this
  month to today, at most five years, never in the future), branches (narrowed to the caller's scope of
  `lending.insights.read`), officer (the responsible officer of the loan; a caller without
  `lending.insights.all_officers` always sees only their own loans) and product. Member figures take the
  member's own branch and officer.
- **Live and history.** Flows (disbursed, collected, expected, revenue) are always computed from the transaction,
  schedule and ledger rows for the range. Stocks (outstanding, PAR, ageing, breakdowns) are read **live** when the
  range ends today, and from **`lending_loan_daily_snapshots`** when it ends on a past date; a past date with no
  snapshot yet shows "no snapshot" instead of a guess.
- **Reversals.** A reversed repayment or recovery and its reversal are both left out of "collected" (chapter 14
  section 14.1). Allocations are read by the repayment they now belong to (`applies_to_txn_id`), so a reversed
  repayment's allocations and their negatives cancel and a re-allocated later repayment carries its final split.
  Ledger figures include both the original and the reversing lines, which cancel.
- **Active loan.** Status `active`. Written-off and closed loans are not in the stock figures.
- **DPD.** R-DPD of chapter 3: the date minus the due date of the oldest schedule item due before the date that
  still has principal, interest or fees unpaid; 0 when none. Buckets: `current` (0), `1_30`, `31_60`, `61_90`,
  `over_90` (91 and above).
- **Estimates.** The cash forecast is labelled an estimate on the page and in its tooltip.
- **Snapshot.** One row per loan active at the end of a business date (chapter 6, FR-ARR-02). The nightly job
  (`lending.insights-snapshot`, 00:45 Africa/Kampala) fills every date after the last one written up to yesterday
  and always rewrites yesterday. A past position is rebuilt from the allocation rows dated by their own
  transaction's value date, so the snapshot of a date shows the book as it stood that evening (a repayment reversed
  later still counts on the days in between). Each snapshot row keeps the branch, officer and
  product the loan had that evening, and a filter on a past date uses those. Until the arrears job of increment 6 (#109) exists this job writes
  the table; that job will take it over.

## Morning brief (business date only, read live)

| Key | Formula | Sources |
|---|---|---|
| `brief.disbursed_today` | Sum of `amount_minor` of `disbursement` transactions with `value_date` = today. It is principal; fees deducted at disbursement are part of it. | `lending_loan_transactions`, `lending_loans` |
| `brief.disbursed_loans_today` | Count of those transactions. | same |
| `brief.collected_today` | Sum of `repayment` and `recovery` transactions dated today with no reversal. | `lending_loan_transactions` |
| `brief.expected_today` | Sum of `principal_due + interest_due + fees_due` of schedule items due today, of loans active at some point today. | `lending_schedule_items`, `lending_loans` |
| `brief.collected_on_due_today` | Sum of principal, interest and fee allocations to those items from repayments dated today or earlier. | `lending_repayment_allocations`, `lending_loan_transactions` |
| `brief.collection_rate_today` | `collected_on_due_today / expected_today`. | derived |
| `brief.new_arrears` | Active loans with DPD exactly 1 today: the oldest unpaid item was due yesterday. | `lending_schedule_items`, `lending_loans` |
| `brief.new_arrears_amount` | Arrears (unpaid principal, interest and fees due before today) of those loans. | same |
| `brief.going_bad` | Active loans with DPD 24 to 30: unless paid they pass 30 days within 7 days. | same |
| `brief.going_bad_principal` | Principal outstanding of those loans. | same |

Each card has one plain sentence; disbursed and collected compare with the same weekday a week earlier.

## Loan portfolio

| Key | Formula | Sources |
|---|---|---|
| `portfolio.principal_outstanding` | Sum over active loans of `principal_due - principal_paid` of their schedule items (live) or `principal_outstanding_minor` of the snapshot. | `lending_schedule_items` or `lending_loan_daily_snapshots` |
| `portfolio.interest_receivable` | Sum over active loans of `interest_due - interest_paid - interest_waived`: scheduled interest not yet paid or waived. A memo figure: the ledger books interest income when it is paid (ADR-004, chapter 6 section 6.6.3). | same |
| `portfolio.active_loans` | Count of active loans. | same |
| `portfolio.borrowers` | Distinct members with an active loan. | same |
| `portfolio.arrears` | Sum of unpaid principal, interest and fees of items due before the date, on active loans. | same |
| `portfolio.par1` | Principal outstanding of active loans with DPD above 0, over all principal outstanding. | same |
| `portfolio.par30` | The same with DPD above 30. | same |
| `portfolio.par60` | The same with DPD above 60. | same |
| `portfolio.par90` | The same with DPD above 90. | same |
| `portfolio.disbursed` | Sum of `disbursement` amounts with `value_date` in the range. | `lending_loan_transactions` |
| `portfolio.disbursed_count` | Count of those. | same |
| `portfolio.collected` | Sum of `repayment` and `recovery` amounts in the range, a reversed one excluded. | same |
| `portfolio.expected` | Sum of `principal_due + interest_due + fees_due` of items due in the range, of loans active at some point in it (disbursed, not written off before `from`, not closed before `from`). Penalties are not scheduled and are left out. | `lending_schedule_items`, `lending_loans` |
| `portfolio.collected_on_due` | Sum of principal, interest and fee allocations to those items, from repayments with `value_date <= to`. | `lending_repayment_allocations`, `lending_loan_transactions` |
| `portfolio.collection_rate` | `collected_on_due / expected`. In the period series each period counts what was paid by the end of that period. | derived |
| `portfolio.forecast_7` | Estimate: unpaid principal, interest and fees of items of active loans due after today and within 7 days. Assumes on-time payment and no prepayment. | `lending_schedule_items` |
| `portfolio.forecast_30` | The same within 30 days. | same |
| `portfolio.written_off` | Sum of `write_off` amounts (principal written off) in the range. | `lending_loan_transactions` |
| `portfolio.written_off_count` | Count of those. | same |
| `portfolio.recovered` | Sum of `recovery` amounts in the range, a reversed one excluded. | same |
| `portfolio.repeat_borrowers` | Members disbursed a loan in the range who had a loan disbursed before their first one in the range. | `lending_loan_transactions`, `lending_loans` |
| `portfolio.repeat_share` | `repeat_borrowers` over all members disbursed a loan in the range. | derived |
| `portfolio.average_loan_size` | `disbursed / disbursed_count`, rounded half up. | derived |
| `portfolio.average_tenor_days` | Mean of `maturity_date - disbursed_on` for loans disbursed in the range, rounded half up. | `lending_loans` |
| `portfolio.yield` | Interest and fee income posted in the range (as `revenue.interest + revenue.fees`) over the average daily principal outstanding, times `365 / days in range`. The average is over the days with a snapshot in the range, plus today live when the range ends today. | `journal_lines`, `lending_loan_daily_snapshots` |

Also on the page: the period series (disbursed, collected, expected, collected on due, rate) by day, week (Monday to
Sunday), month or year, with partial periods at both ends of the range; the ageing table; breakdowns of the stock by
product, branch and officer (principal outstanding, loans, PAR 30) and of all loans by status (count and approved,
else requested, principal); the ten largest arrears with a link to the loan; the daily forecast for 30 days; and the
trend of principal outstanding and PAR 30 at the last 11 month ends (snapshots) and today (live).

## Revenue (posted journal lines only)

Only entries posted by lending for a loan transaction count (`source_module = 'lending'`, `source_type =
'loan_transaction'`), dated (`entry_date`) in the range; the entry's `source_id` names the loan transaction and so
the loan, its product and branch. Nothing is estimated from schedules.

| Key | Formula | Sources |
|---|---|---|
| `revenue.interest` | Credits less debits on `loan_interest_income`. | `journal_entries`, `journal_lines`, `gl_accounts`, `lending_loan_transactions`, `lending_loans` |
| `revenue.fees` | Credits less debits on `loan_fee_income` (deducted, upfront and scheduled fees as they post). | same |
| `revenue.penalties` | Credits less debits on `loan_penalty_income` (zero until increment 6 posts penalties). | same |
| `revenue.recovered` | Credits less debits on `bad_debt_recovered`. | same |
| `revenue.write_off_expense` | Debits less credits on `loan_write_off_expense`. | same |
| `revenue.contribution` | `interest + fees + penalties + recovered - write_off_expense`. No cost of funds or operating cost is allocated (ADR-030). | derived |
| `revenue.effective_yield` | `interest + fees + penalties` over the average daily principal outstanding (as in `portfolio.yield`), times `365 / days in range`; per product with that product's balance. | derived, `lending_loan_daily_snapshots` |

Shown by product and by branch for the range, and by month for the 12 months ending with the range's last month.

## Member activity

| Key | Formula | Sources |
|---|---|---|
| `members.new_members` | Members whose `created_at` falls in the range (tenant time zone). | `lending_members` |
| `members.active_borrowers` | Distinct members with an active loan today. | `lending_loans`, `lending_schedule_items` |
| `members.applied` | Applications with `submitted_at` in the range. | `lending_loans` |
| `members.appraised` | Of those, the ones with at least one appraisal. | `lending_loan_appraisals` |
| `members.approved` | Of those, the ones with `approved_at` set, whatever happened after. | `lending_loans` |
| `members.disbursed` | Of those, the ones disbursed. | `lending_loans` |
| `members.median_decision_hours` | Median of hours from `submitted_at` to `approved_at`, or to the first move to `rejected`, for decisions made in the range. | `lending_loans`, `lending_loan_status_history` |
| `members.kyc_verified_share` | Active members with `kyc_status = verified` over all active members. | `lending_members` |
| `members.dormant` | Active members with no active loan and no loan transaction in the last 90 days. | `lending_members`, `lending_loans`, `lending_loan_transactions` |

Also shown: new members by period, the funnel with each stage's conversion from the one before, KYC status counts,
the credit score bands (A to D) of the latest appraisal of each application submitted in the range, and staff
activity in the range per user (members registered, applications submitted, appraisals, disbursements and
repayments recorded).

## Savings and investments

Panels come from the `InsightsPanel` registry (`GET /lending/insights/panels`, one tab each on the page). Two
adapters in `lending.insights` map `SavingsMetrics` (#151) and `InvestmentMetrics` (#152); the figures are computed
by those modules from their own posted rows, with the page's range and branches. A panel appears only for a tenant
that holds savings accounts (savings) or investments (investments), and not under a one-officer filter: neither
module records a responsible officer, so a branch total would show more than the officer may see. Panel figures
have no drill-down table.

Savings (`savings`):

| Key | Definition | Kind |
|---|---|---|
| `savings.balances` | Sum of every savings account's balance at the end of the last day of the range. | money |
| `savings.inflows` | Deposits dated in the range, less reversals of deposits dated in the range. | money |
| `savings.outflows` | Withdrawals dated in the range, less reversals of withdrawals and fees dated in the range. | money |
| `savings.interest_paid` | Interest credited to savings accounts with a value date in the range. | money |
| `savings.dormant_accounts` | Accounts dormant now: no member deposit or withdrawal for the product's dormancy days. | count |

Investments (`investments`):

| Key | Definition | Kind |
|---|---|---|
| `investments.balance` | Balance of member investments payable (2020) at the end date. | money |
| `investments.returns_payable` | Balance of investment returns payable (2021) at the end date: accrued, not yet paid. | money |
| `investments.inflows` | Fundings in the range by value date, less fundings reversed; rollovers are not new money. | money |
| `investments.outflows` | Return payouts, maturity payouts and early withdrawals paid in the range, less reversals. | money |
| `investments.returns_accrued` | Net debits to investment return expense (5020) in the range. | money |
| `investments.returns_paid` | The return part of payouts and early withdrawals in the range, less reversals. | money |
| `investments.returns_reinvested` | Unpaid returns moved into a new investment by a rollover in the range. | money |
| `investments.penalties` | Net credits to investment penalty income (4060) in the range. | money |
| `investments.open_count` | Investments active or matured with principal still held, now. | count |
| `investments.investor_count` | Members holding at least one open investment, now. | count |
| `investments.maturing_overdue` | Maturity ladder from today: principal held plus return owed on investments matured and not yet paid. | money |
| `investments.maturing_0_7` | The same for investments maturing in the next 7 days. | money |
| `investments.maturing_8_30` | The same for investments maturing in 8 to 30 days. | money |
| `investments.maturing_31_90` | The same for investments maturing in 31 to 90 days. | money |

## Drill-down tables

Every number links to the table of rows that make it up, with the page filter. A table shows at most 200 rows on
the page; the CSV export (`lending.insights.export`, audited as `lending.insights.exported`) returns up to 10,000.
A member is identified by member number; a name appears only in the action lists (`arrears`, `forecast`,
`dormant_members`) and is reduced to initials in an export for a caller without `lending.members.read`. No table
carries a phone number or an ID number.

| Table | Rows | Parameters |
|---|---|---|
| `disbursements` | Disbursements in the range | `date` |
| `collections` | Repayments and recoveries in the range, not reversed | `date` |
| `expected` | Items due in the range, with collected on due and unpaid now | `date` |
| `arrears` | Active loans in arrears, largest first | `min_dpd`, `max_dpd`, `bucket` |
| `active_loans` | Active loans | `group` (`product`, `branch`, `officer`, `bucket`) and `key` |
| `loans_by_status` | Loans in one status | `status` |
| `forecast` | Items of active loans due in the next days (estimate) | `days` (1 to 92) |
| `write_offs` | Write-offs in the range | `date` |
| `recoveries` | Recoveries in the range, not reversed | `date` |
| `revenue_lines` | Posted lines on the revenue accounts | `account` (system key), `date` |
| `new_members` | Members registered in the range | |
| `applications` | Applications submitted in the range at a funnel stage | `stage` |
| `repeat_borrowers` | Members disbursed in the range with an earlier loan | |
| `dormant_members` | Dormant members | |
| `staff_activity` | Work per staff user in the range | |

## Daily digest

Off by default (`lending_insights_digest_settings`). When on, at or after the tenant's chosen hour the job
`lending.insights-digest` (every 15 minutes) queues once a day, through the notification outbox, the brief's five
sentences and two portfolio lines (principal outstanding on active loans and PAR 30) for the whole tenant, as plain
text: to each email recipient (at most five) and to the named Telegram chat. The WhatsApp relay is a later slice.

## Performance

Target: the page renders in under a second on the staging stack with the fabricated seed scaled up 20 times. The
queries are tenant scoped by row-level security and indexed (V33 adds `lending_repayment_allocations (tenant_id, schedule_item_id)`,
`lending_loan_transactions (tenant_id, txn_type, value_date)`, `lending_members (tenant_id, created_at)`, `lending_loans (tenant_id, submitted_at)` and
`journal_entries (tenant_id, entry_date)`). Measure with `InsightsPerformanceIT`
(`mvn verify -Dit.test=InsightsPerformanceIT -Dinsights.perf=true`), which seeds `--insights-demo --scale 20` and
times each insights route; the results are recorded in the pull request of #153. First measurement
(cloud build container, x86, scale 20: 4,128 loans, 12,494 repayments, 326,137 snapshot rows): brief
90 ms, portfolio this month 168 ms, portfolio 12 months 764 ms, revenue 12 months 493 ms, members
12 months 212 ms, drill-down tables about 50 ms (medians). The Pi staging host is still to be
measured.
