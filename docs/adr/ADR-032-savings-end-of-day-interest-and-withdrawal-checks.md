# ADR-032: Savings: end-of-day balances with interest rounded once per posting, movements only after the closed day, withdrawals checked again at execution, and receipts queued as SMS that expire unsent

## Status

Proposed (issue #151, MVP increment 9). Builds on ADR-004 (integer money, the ledger), ADR-008
(jobs), ADR-015 (approval actions), ADR-024 (the outbox) and ADR-026 (default payment method
accounts, the servicing port pattern, the idempotency copy). Implements FR-SAV-01 to FR-SAV-07
with migration `V30` (the number is confirmed when the pull request is marked ready, as Flyway
runs with `outOfOrder` off). The number 030 is held by the insights pull request (#156, pending ADR-030)
and 031 by the investments work (#152), so this record is ADR-032.

## Context

Increment 9 gives lending tenants savings: products, accounts (any number per member), deposits,
withdrawals with a checker above the tenant's threshold, interest, dormancy, freeze, closure,
statements and the two savings reports of chapter 14. Chapters 3 and 6 fix the formulas
(FR-SAV-05) and the tables, but eight questions were open:

1. Where the daily accrual lives and how interest is rounded, given that R-ROUND rounds once per
   computed line and a posting covers a month, a quarter or a year.
2. What `minimum_monthly_balance` does with a month the account was open for only part of, or that
   a closure cuts short.
3. How a back-dated movement interacts with end-of-day balances already used for interest.
4. What a pending withdrawal holds while it waits for a checker, given that an approval action has
   no hook on rejection or expiry (ADR-015).
5. How freeze, dormancy and reactivation map onto the permission matrix, which has no freeze
   permission.
6. How a reversal is checked, and what it does to a withdrawal's fee.
7. What happens when interest falls due for a period end in a closed accounting month, given that
   a refused `post_entry` marks the whole job transaction for rollback.
8. How the savings receipts of chapter 11 section 11.3.1 reach a member while no SMS aggregator is
   chosen (pending ADR-013), and the outbox is a platform table.

## Decision

1. **The nightly end of day writes one end-of-day balance per account and day; interest is computed
   from those rows exactly and rounded once, at posting.** `lending.savings-end-of-day` runs at
   00:20 for every tenant with lending, through yesterday. For each open account it writes
   `lending_savings_daily_balances` for each day after the last one written (catching up missed
   nights), and at each product period end (monthly, quarterly, yearly) it posts the period's
   interest: one integer numerator (balance x rate in basis points, summed over the days or the
   months) divided once by 10 000 x 365 (or x 12) and rounded half up (`Interest`, pure, golden
   tests). Nothing rounded is stored per day; the account shows "interest earned, not yet posted"
   computed the same way. The interest is dated the period end and credited after that day's
   balance is computed, so the period end's stored balance includes it and the next period earns
   on it. `lending_savings_interest_postings` has one row per account and period end, so a rerun
   posts nothing twice; a period that earned nothing writes the row with no transaction. Interest is
   recognised as expense at posting; there is no daily accrual journal, because the chart has no
   interest payable account and a month's accrual would be posted and then cleared the same night.
2. **A minimum monthly balance month earns only when the account was open on every day of it and
   the period covers all of it.** The account's first part month and a closure's last part month
   earn nothing under that method. A product's `min_balance_for_interest_minor` excludes a day
   (daily balance) or a month (minimum monthly balance) below it.
3. **A movement is dated after the last day the end of day has written, today or earlier, and not
   before the account opened** (`value_date_closed`, `value_date_in_future`, `before_opening`).
   After the nightly run that means today, so the balances interest was computed on never change.
   Statements and reports compute balances by value date from the movements and agree with the
   stored end-of-day balances. Each movement also stores its running balance in recording order
   (`seq`, FR-SAV-04).
4. **A withdrawal holds nothing while it waits; every check runs again at execution, and the money
   moves on the day of execution.** The request is checked (status, per-withdrawal and per-month
   limits, balance less hold, minimum balance and fee) and stored as `savings_withdrawal` with the
   account's version. The checker's approval runs the same checks on the account as it is then; a
   failure leaves the request pending with the error (FR-APR-06), and any movement in between makes
   it stale (FR-APR-08). A closure is the same action with `close` set: it posts interest to
   yesterday (dated the closing day), pays the whole balance out with no fee and closes the account,
   and the threshold applies to the balance. `hold_minor` stays for later uses (a savings-secured
   loan, open question 2 of the scope) and is not set by this increment.
5. **Freeze, unfreeze and reactivation need `lending.savings.withdraw_approve`, the checker
   permission of `savings_withdrawal`, and take a reason.** They are direct actions, audited, not
   maker-checker: a branch manager or accountant acts on the account, which is what FR-SAV-06 asks
   for reactivation. A frozen account takes deposits but pays nothing out; a dormant one takes
   deposits and stays dormant until reactivated. Dormancy is set by the end of day when no member
   deposit or withdrawal happened for the product's dormancy days.
6. **A reversal is the new action `savings_reversal` (maker `lending.savings.withdraw`, checker
   `lending.savings.withdraw_approve`, never below a threshold).** Only a deposit or a withdrawal
   that is not reversed, on an account that is not closed, can be reversed; a deposit only while the
   account still holds it. A withdrawal's fee is reversed with it. Each reversal is a new movement
   dated the day it executes, with the mirror journal of the original (ADR-004); interest already
   posted on a reversed deposit is not clawed back.
7. **Interest for a period end in a closed accounting month posts on the run date.** The interest
   movement keeps the period end as its value date; only the journal entry is dated the run date.
   The job asks the ledger first (`LedgerPosting.periodOpen`, added here) instead of catching a
   refused post.
8. **Receipts are queued in the existing outbox on a new `sms` channel, expire after two days and
   are never sent until an SMS sender exists.** `V30` widens the outbox channel CHECK. A staff
   deposit, withdrawal or closure queues `savings.deposit` or `savings.withdrawal` in its own
   transaction, keyed by tenant and transaction so a retry never queues a second copy. The text
   carries the tenant's name, the account number, the amount, the receipt and the balance, never
   the member's name. No sender is registered for `sms`, so the dispatcher leaves the rows pending;
   the nightly purge marks them failed and clears their parameters once they expire. The seed and
   the import never message members.

Also: the posting rules of chapter 6 section 6.6.3 (deposit Dr payment method Cr `member_savings`;
withdrawal the reverse; fee Dr `member_savings` Cr `savings_fee_income`; interest Dr
`savings_interest_expense` Cr `member_savings`) with the account as subledger on every
`member_savings` line; the receipt and voucher sequences shared with loans per branch (`RC-`,
`VC-`); `SavingsServicing`, the port for the seed and later the import, as `LoanServicing` is
(ADR-026); a third copy of the idempotency protocol until #177 lifts it into core; and
`SavingsMetrics`, a public interface shaped like the insights module's `InsightsPanel` (pending ADR-030),
so the insights page can add the savings panel with a one-line adapter once #156 is on `main`.

## Consequences

**Better:**

- Interest is exact to the minor unit and reproducible from stored rows: the golden tests work
  examples by hand, and a rerun of the job changes nothing.
- No back-dating rule has to reason about re-computing interest: a day the job has closed is never
  changed, so the end-of-day balances are the record interest was paid on.
- A withdrawal approved hours later cannot overdraw: the row lock and the checks at execution
  decide, and the `balance_minor >= 0` CHECK backs them.
- The member savings control account equals the sum of the accounts' balances, account by account,
  because every line on it carries the account as subledger; the tests check it after every case.

**Worse:**

- Staff cannot back-date a savings movement past the nightly run. A deposit received yesterday and
  keyed in today is dated today; a correction is a reversal and a new movement.
- The end of day writes a row per open account and day, about 365 rows a year per account.
- A pending withdrawal does not reserve the money, so another withdrawal can spend it first; the
  checker then sees the refusal recorded on the request.
- A deposit reversed after interest was posted on it leaves that interest with the member.
- Savings receipts are queued but not delivered until the SMS aggregator decision
  (pending ADR-013) adds a sender. The expired rows keep the masked recipient in the platform outbox list.

**Watch for:**

- When the SMS sender lands, it should send only rows still inside their two days; older ones are
  already marked failed by the purge.
- Done in #177: `SavingsIdempotency` is removed; savings uses the shared `Idempotency` in `core.operations`.
- When the insights pull request (#156) is on `main`, register a savings `InsightsPanel` that adapts
  `SavingsMetrics`.
- A savings-secured loan (open question 2) will need `hold_minor` set and released by the loans
  module through a port on this module; the withdrawal checks already subtract it.
- Investments (FR-INV-04, #152) credit monthly returns to a member's savings account: that needs a
  `transfer_in` movement through this module's port, not a write to its tables.
