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
- **Exports.** CSV for every report; PDF for every report; XLSX for financial statements.

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

## 14.8 Test datasets

Each report has a fabricated dataset (a handful of loans, repayments and journals) with a
hand-computed expected output committed next to its test (chapter 15 section 15.7). The
PAR worked example above is one of them.
