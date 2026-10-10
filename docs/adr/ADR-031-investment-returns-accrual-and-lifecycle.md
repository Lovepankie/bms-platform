# ADR-031: Investments: month-based returns accrued monthly, recurring as auto-renewal, early withdrawal settled in one entry

## Status

Proposed (issue #152, MVP increment 10). Builds on ADR-004 (ledger), ADR-008 (jobs), ADR-015
(approval actions) and ADR-026 (payment method accounts, servicing port). Implements FR-INV-01 to
FR-INV-12 and rules R-INV-1 to R-INV-6 (chapter 3 section 3.25.1) with migration `V32`.

## Context

The pilot tenant's second expectation is that the investment lifecycle runs itself from
application to returns. Chapter 3 described one fixed-term product with a flat return; the
increment asks for more, and open question 3 of the MVP scope (how the pilot's products work) is
still open. These questions are not settled by the chapters as written:

1. **How a return is computed** when a term is whole months but an early withdrawal, or a period
   that starts on the 31st, is not: per month, per day, or both, and how leap years count.
2. **When a return becomes an expense.** Booking it only when paid shows a profit the business has
   already promised away; booking the whole agreed return at funding overstates the liability if
   the member withdraws early.
3. **What "recurring" means.** In banking it can mean a monthly contribution plan or a deposit that
   renews itself at maturity.
4. **How compounding interacts with a periodic payout.**
5. **How an early withdrawal settles** when returns were already accrued, and some paid, at the
   full rate, and the product takes a penalty.
6. **What can be reversed.** Accruals are written by the job, rollovers move money between two
   investments, and an early withdrawal trues the accruals up or down.
7. **Who checks a funding.** Chapter 8 had a checker only for early withdrawal.
8. **Where the owner's figures live.** The insights page of issue #153 (pending ADR-030, open as #156)
   defines a panel registry that is not on `main`.

## Decision

1. **Returns are month-based, with days only for a part month (R-INV-1 to R-INV-5).** Period `k`
   runs from start plus `k-1` months to start plus `k` months by the R-TERM month rule (clamped to
   the month end, never drifting). A flat return is `round(P x rate x k / 12)` cumulated, each month
   taking the difference, so the months sum exactly to FR-INV-03's agreed return. A part month
   (early withdrawal) counts its days over the length of their own calendar year (365 or 366), split
   at 1 January, and is rounded once, half up, with exact rational arithmetic. Whole months earn the
   same in a leap year; a day of a leap year earns 1/366.
2. **Returns accrue monthly, on each period's end date.** The nightly job posts return expense /
   returns payable for every period ended, on its own end date; a period of a payout frequency
   (monthly, quarterly or at maturity) makes everything accrued so far due. The cashier pays a due
   return (returns payable / cash). The liability for returns therefore grows month by month, the
   profit and loss shows the cost in the month it is earned, and nothing is booked ahead of time.
   One accrual per investment and period is enforced by a unique index; the job locks each
   investment, re-reads its periods and skips what is accrued, so repeated or concurrent runs post
   nothing twice (tested with three runs at once).
3. **Recurring means auto-renewal.** A `recurring` product, at maturity with no instruction from the
   member, rolls principal and return over into a new investment on the product's current terms; a
   `fixed_term` product waits for the member's choice (FR-INV-05). A rollover, by instruction or
   automatic, is one journal: investments payable (old), returns payable if the return rolls /
   investments payable (new), dated on the maturity date. A monthly contribution plan is deferred
   until the pilot answers open question 3; the schema leaves room for it (a new `product_type`).
4. **Compounding is monthly and only with payout at maturity.** Each month earns on the opening
   balance plus the return already earned, rounded per month. A periodic payout would pay the return
   away, so the combination is refused at product save (`compounding_needs_maturity_payout`) and by
   a CHECK.
5. **An early withdrawal settles in one entry (R-INV-6).** The return earned is none
   (`forfeit_return`) or R-INV-5 at the reduced rate for the time held (`reduced_rate`); the penalty
   is basis points of principal, capped so the cash is never negative. One journal clears the
   investment's two liabilities, trues return expense up or down to the earned return, credits the
   penalty to a new income account (4060 `investment_penalty_income`, added to every tenant with
   the lending chart by `V32`) and pays the cash; returns already paid above what was earned are
   taken back from the principal. It is maker-checker with no threshold, and settles on the
   business date of the approval, not of the request.
6. **Reversals are narrow.** A funding (only while nothing else has moved), a return payout and a
   maturity payout can be reversed, each once, by the mirror of its journal and with a checker
   (`investment_reversal`). Accruals, rollovers and early withdrawals are not reversed by staff:
   their effects chain into later rows, and a correction is a manual journal by an accountant.
7. **A funding above the tenant's threshold needs a checker.** `investment_funding` is a
   threshold action like `loan_disbursement`; two checker permissions are added,
   `lending.investments.fund_approve` and `lending.investments.reverse_approve`, granted to tenant
   admin, branch manager and accountant (chapter 8).
8. **The figures are a public interface of the module.** `InvestmentMetrics` (balances from the
   ledger, flows from the transactions net of reversals, returns accrued from the expense account,
   the maturity ladder and the concentration) is shaped like the insights panel's metric, so when
   the insights module lands an adapter there is a mapping, not a computation; until then the
   staff route `/lending/investments/metrics` and the maturities screen serve it.
9. **Reminders are recorded, not yet sent.** The job records the reminder 7 days before maturity
   (FR-INV-07) and 7 days after a maturity with no instruction (FR-INV-05) on the investment and in
   the audit log, once each; delivery waits for the SMS adapter (pending ADR-013). The maturities
   screen is the staff's list until then.
10. **The idempotency helper is copied, once more.** `lending.investments` may not depend on
    `lending.loans` internals (ADR-002); issue #177 lifted the copies into `core.operations`. Done in #177.

## Consequences

**Better:** the books show each month's return cost when it is earned and the liability the
business owes at any date, and both investment liabilities reconcile to the investments one by one
(the integration tests check it after every case). Golden tests fix every return to the unit for
flat, compounding, leap-year, cross-year and part-month cases. The owner sees what falls due in 7,
30 and 90 days without a report run. A recurring deposit needs no staff action at maturity.

**Worse:** an early withdrawal after months of full-rate accrual reverses expense in the month it
happens, so a month's return expense can be negative. The month-based rule pays the same return
for February as for March, which a strictly daily product would not. Correcting an accrual or a
rollover takes an accountant's manual journal. The idempotency helper is the shared one in `core.operations` since #177.

**Watch for:** the pilot's answer to open question 3 (a contribution plan would be a new product
type with its own schedule rule); a GL period closed before the job posts a period's accrual (the
job then fails for the tenant and retries; run it before closing a period); and the insights
adapter once #153 merges.
