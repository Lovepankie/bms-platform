# ADR-026: Loan servicing: allocation rows by repayment, replay on reversal, default payment method accounts and a servicing port for commands

## Status

Proposed (issue #108, MVP increment 5). Builds on ADR-004 (ledger), ADR-015 (approval actions) and
ADR-002 (module boundaries). Implements FR-DIS-01 to FR-DIS-04, FR-REP-01 to FR-REP-06 and
FR-LCL-01 to FR-LCL-03 with migration `V29`.

## Context

Increment 5 turns an approved application into a loan that moves money: a disbursement with a
checker, a schedule, repayments allocated by R-ALLOC, a payoff quote by R-PAYOFF, reversal with a
checker, closure, write-off and recovery, each with its journal through `post_entry`. Five
questions are not settled by chapters 3 and 6 as written:

1. FR-REP-05 says the reversal of a repayment that is not the latest "re-allocates the later
   repayments in value date order and posts the net difference". Allocation rows are append-only,
   so a re-allocation cannot edit the later repayment's rows, and a row written by the reversal
   must still say whose money it moves, or the next reversal cannot find what a repayment holds now.
2. FR-GL-08 maps each payment method to a ledger account per tenant and branch, but the
   `payment_methods` table and its admin screen belong to the general ledger increment, which is
   not built. Disbursements and repayments need an account for the method now.
3. A repayment dated before the loan's latest repayment would change how that later repayment
   should have been allocated.
4. The fabricated staging seed (and, from increment 7, the pilot import) must disburse and repay
   with the same rules and postings as staff, but runs as a command with no request principal,
   and only `core.identity` may set one (ADR-017).
5. Retail already has an idempotency helper for its money routes, inside `retail.stock`; lending
   may not depend on retail (ADR-020).

## Decision

1. **Allocation rows carry the repayment they belong to.** `lending_repayment_allocations` has
   `transaction_id` (the transaction that wrote the row) and `applies_to_txn_id` (the repayment
   whose money it moves). A repayment writes its own rows with both set to itself. A reversal
   **replays** the surviving repayments in value date order on the schedule as contracted
   (`Servicing.replay`, pure and unit tested), compares each repayment's new rows with what it holds
   now, and writes, under the reversal's own transaction, the negatives of the reversed repayment's
   rows and the differences of every repayment that moved, each attributed by `applies_to_txn_id`.
   When nothing else moves, the journal is the mirror of the original entry through
   `LedgerPosting.reverse` (linked by `reverses_entry_id`); otherwise one entry posts the net
   difference by component against the payment method. The early settlement rebate of R-PAYOFF is
   an allocation row with component `interest_rebate`, so a replay restores it too.
2. **Default method accounts until FR-GL-08.** `cash`, `bank`, `mtn_momo` and `airtel_money` post
   to the seeded accounts `cash_on_hand`, `bank`, `mobile_money_mtn` and `mobile_money_airtel`
   (chapter 6 section 6.6.2). A method whose account is missing or inactive is refused with
   `payment_method_unmapped`, as FR-GL-08 states. When `payment_methods` is built, `LoanBooks`
   looks the method up there first and keeps this mapping as the default.
3. **Repayments are recorded in value date order.** A value date before the loan's latest
   repayment is refused with `before_last_repayment`; staff record receipts in the order they were
   taken. Allocation at the time of recording then always equals what a replay would produce.
4. **`LoanServicing`, a port for commands.** `lending.loans` exposes `disburseApproved` (maker and
   checker named, and different) and `recordRepayment` for callers that have no request principal.
   Both run the same code as the approval actions and the staff routes (`LoanServicer`). The
   `seed-lending` command (module `lending.seed`) uses it to give a staging tenant 15 fabricated
   members, 4 products, 12 applications in mixed states and 6 disbursed loans with repayments. The
   command writes the reference rows directly, refuses `BMS_ENVIRONMENT=production`, refuses any
   tenant that already holds members, products, loans or journals, and records the audit action
   `lending.seed.fabricated` as the marker that refuses a second run. It runs only when named,
   never at startup (`docs/runbooks/seed-lending.md`).
5. **Lending first kept its own copy of the idempotency protocol** (`LoanIdempotency`), byte for
   byte the behaviour of chapter 7 section 7.8 that retail implements. Lifting the copies into
   one core helper was a follow-up refactor that touches retail, kept out of this increment.
   Issue #177 did it: the one `Idempotency` in `core.operations` now serves retail and lending
   (see the note under Consequences).

Also decided here, within the rules of chapter 3:

- **Write-off** posts the outstanding principal to `loan_write_off_expense`, records every unpaid
  component per item in `written_off_minor` with status `written_off`, and sets the loan's
  outstanding columns to zero, so the subledger reconciles with `loans_receivable`. A loan with no
  principal outstanding is refused with `nothing_to_write_off`.
- **Recoveries** (FR-LCL-03) write no allocation rows and do not touch the schedule.
- **A reversal's approval subject is the transaction**, and its version is its loan's: any money
  event on the loan between request and decision makes the request stale (FR-APR-08).
- **Receipt and voucher numbers** (FR-DOC-04) are `RC-` and `VC-`, the branch code and six digits
  from the row-locked tenant sequences `receipt:<code>` and `voucher:<code>`, stored in
  `lending_loan_transactions.receipt_no`. The PDFs are deferred.

## Consequences

**Better:**

- Every reversal, however far back, leaves allocation rows whose sum per repayment is exactly
  what that repayment holds, and the ledger moves by exactly the net difference.
- The fabricated seed and the later import cannot drift from staff postings: they call the same
  code.
- Disbursement and repayment work today on every lending tenant, whose chart already has the
  four method accounts.

**Worse:**

- A replay reads every repayment of the loan; cost grows with the number of repayments (tens per
  loan in practice).
- Two copies of the idempotency protocol until the follow-up refactor. (Done in #177: retail
  stock, sales, purchasing and the cash book, and lending loans, savings and investments all use
  the one `Idempotency` in `core.operations`; `RetailIdempotency`, `LoanIdempotency`,
  `SavingsIdempotency` and `InvestmentIdempotency` are removed. Behaviour is unchanged.)
- Receipts taken out of order must be keyed in order; a late keyed older receipt cannot be
  recorded with its true value date once a newer one is in.
- `LoanServicing` lets code without a principal disburse without an approval request. Only
  `lending.seed` declares it as a dependency today; any new caller is a review question.

**Watch for:**

- A closed loan releases its pledges (FR-COL-04); a reversal that reopens it did not re-pledge
  them, because pledges changed only on a draft. Done in #176 (V42): the reopened loan takes back
  each item still held and free; an item handed back or pledged elsewhere is not restored, the
  reversal request shows it to the checker, and the reopen is audited as partly unsecured. A
  reversal is never refused for collateral, because handing the item back after payoff is normal.
  The unsecured reopen is approved by a checker who holds `lending.collateral.release_approve` (the
  existing seeded permission for letting collateral go; tenant administrator and branch manager),
  not by the accountant who approves other reversals; and the run refuses, writing nothing, when the
  reopen would leave out an item the request did not list: 409 `collateral_facts_changed` with the full
  current list, the request ends as stale (audited) and the maker requests again. The approvals SPI
  gained `extraCheckerPermission(payload)` for this, which also hides the decision from approvers
  who lack it, and `RequestSupersededException` for the stale ending.
- Penalty charges and waivers arrive in increment 6; the replay starts from the contracted
  schedule and must then add penalty charges and charge waivers before re-applying repayments.
- When FR-GL-08's `payment_methods` arrives, the default mapping must stay as the fallback or be
  seeded as rows, so existing tenants keep posting. Done in #160 (V43): the defaults stay the
  fallback, in one place (`core.ledger`'s `PaymentMethods`), and the three lending copies of the
  default table are gone. That is lending only. Retail still hard-codes its accounts: sales,
  purchases, the cash book postings and the cash figures use `cash_on_hand` and `bank` by system key
  and do not read the mapping (#236). On a tenant with both verticals those two accounts are shared,
  so remapping lending's `cash` or `bank` would take lending money out of the retail cash book. The
  mapping therefore refuses it: while the retail module is on, `cash` and `bank` can be mapped only
  to those two seeded accounts (`account_used_by_retail`), at the tenant or a branch; mobile money,
  the gateway and savings transfer stay free, and so does switching a method off. The other
  direction is closed too: `platform_set_tenant_modules` (replaced in V43, body as in V10 plus the
  check) refuses to switch retail on for a tenant that has an active cash or bank mapping to any
  other account (`payment_methods_remap_blocks_retail`, 422 on the platform console); the operator
  clears the mapping first. A save and the module switch take one advisory lock per tenant
  (`payment_methods:<tenant id>`), so they run one after the other and cannot both pass their check.
  The platform console is the only way retail is switched on, and the function is the only place it
  is done. Saving or removing a mapping needs the manage permission in the branch concerned, and
  authority over every branch for the tenant-wide row. A mapping can be removed (`DELETE /ledger/payment-methods/{method_key}`,
  optional `branch_id`), which returns the method to its seeded account. Lifting the restrictions
  needs retail to post and read through `PaymentMethods` (#236). A correction credits the account the original entry
  debited, never the method's mapping at the time of the correction. A request is checked at the
  branch of the loan, account or investment, the same row that execution resolves.

## Amendment (2026-10-19, issue #216): gateway repayments through the port

Additive; the decisions above stand. `LoanServicing` was written for callers without a request principal (the seed, the
import). It now also serves the payments module booking a verified gateway payment (ADR-035, spec 3.4.1):

- `recordRepayment(loanId, amountMinor, valueDate, paymentMethodKey, recordedBy, externalReference, origin, queueReceiptNotice)` is a
  second overload. It runs the same code as the cash repayment (the same checks, allocation order R-ALLOC, receipt number,
  journal and audit), so a repayment paid on a phone is indistinguishable in the books from one a cashier typed, apart from the
  account the money came in on (the mapping of the `gateway` method, normally `gateway_clearing`) and the transaction's `source`
  (`gateway`).
- Idempotent for the method `gateway`: a second call with the same `externalReference` (the provider's order tracking id, unique per payment; a short confirmation code is not) for the same loan and amount returns
  the first transaction and writes nothing, under the loan row lock; a unique partial index over (tenant, method, reference) for
  repayments and recoveries backs it (migration V59). A reversal copies the method but never the reference.
- `origin` becomes the transaction's `source` (`gateway`, `system`, `import`, `portal`). `queueReceiptNotice` queues the repayment
  receipt text in the same transaction (issue #221's event, only for a tenant with SMS events on); the seed and the import pass
  false and still message nobody. A gateway booking tells the member; `LoanNotices.sends` now accepts the source `gateway`.
- Business refusals are the cash route's `ApiException` codes (`before_last_repayment`, `value_date_in_future`,
  `payment_method_unmapped`, `invalid_status_transition`). The registered purpose handler turns them into a refusal the payments
  module does not strand: `before_last_repayment` is retried once dated today, anything else posts the amount to unallocated
  receipts (ADR-035 decision 14).
- The javadoc of the port now says "the seed, the import and gateway bookings".
