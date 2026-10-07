# Lending UI notes

**Status:** Draft · **Increments:** 5, disbursement and repayments (#108); 9, savings (#151) · **Requirements:** FR-DIS-01, FR-DIS-04, FR-REP-01 to FR-REP-06, FR-LCL-02, FR-LCL-03, FR-SAV-01 to FR-SAV-07 · **API:** `docs/sdd/07-api-design.md` sections 7.11.13 and 7.11.15

Phone-first staff screens for loan servicing, in `frontend/src/areas/staff/lending/`, on the real API through the
generated client (`src/api/lending.ts`, typed by `src/api/schema.d.ts`). They follow the retail screens
(`docs/specs/retail-ui-notes.md`): the same page frame and shared classes, styled in `src/app/ui/lending.css` with the
design tokens, no inline styles (the production CSP refuses them), 44px tap targets, labelled inputs and tables in
focusable scroll regions.

## Screens and routes

| Route | Screen | Permissions needed |
|---|---|---|
| `/staff/lending` | Loans: search by loan number, member number or name, status filter, cursor "Load more"; each row shows loan no, member, status, outstanding, days past due and next due date | `lending.loans.read` |
| `/staff/lending/loans/$loanId` | One loan: header (status, member, product, principal), balances, schedule with its totals row, transactions | `lending.loans.read` (member name: `lending.members.read`) |

The actions open in place on the loan page, offered only for the loan statuses the server accepts:

| Action | Permission | Loan status | Result shown |
|---|---|---|---|
| Disburse (date, method, reference) | `lending.disbursements.request` | approved | 201: disbursed; 202: sent for approval to a checker |
| Record repayment (amount, value date, method, reference) | `lending.repayments.create` | active, written_off (a recovery) | the receipt number and the allocation by component; the loan reloads |
| Payoff quote (value date) | `lending.loans.read` | active | principal, interest, fees, penalties, rebate, total |
| Reverse a repayment (reason) | `lending.repayments.reverse_request` | repayment on an active or closed loan, or a recovery, not yet reversed | waiting for a checker |
| Request write-off (reason) | `lending.loans.write_off_request` | active | waiting for a checker |

The list follows the branch chosen in the staff header. Dates default to today (the browser's local date) and the
date inputs do not offer a future date. Payment methods are `cash`, `bank`, `mtn_momo` and `airtel_money`.

## Behaviour worth knowing

- **Gating.** `lending/permissions.ts` maps each action to the permission its route declares and to the statuses it
  applies to. The Loans menu entry shows with `lending.loans.read` (and, once `/me` lists modules, only when lending
  is among them). The API stays the authority.
- **Money.** Amounts are parsed into integer minor units with `parseMinor` in the loan's currency; no float touches
  an amount. Every figure shown is the server's.
- **Idempotency.** Each money-moving form keeps its draft and one `Idempotency-Key` in the tab's `sessionStorage`
  (`retail/idempotency.ts`) until the server answers with success, so a retry or reload posts once.
- **Errors.** `lendingMessage` in `src/api/lending.ts` maps lending codes (`invalid_status_transition`,
  `value_date_in_future`, `before_disbursement`, `before_last_repayment`, `payment_method_unmapped`,
  `approval_already_pending`, `already_reversed` and others) to plain words and falls back to the retail mapping for
  shared codes, validation field messages and unknown codes (the server's own message).

## Savings (increment 9, #151)

Built on the same frame (`src/api/savings.ts`, `lending/savings*.tsx`, `lending/member-savings.tsx`), with a few
`sv-` classes in `lending.css` for the sub-navigation, the confirmation step and the statement's date range. The
staff header shows **Savings** with `lending.savings.read`.

| Route | Screen | Permissions needed |
|---|---|---|
| `/staff/lending/savings` | Savings accounts: search by account number, member number or name, status filter, "Load more" | `lending.savings.read` |
| `/staff/lending/savings/accounts/$accountId` | One account: status, member (links to the member's savings), product and interest rule, balance, what can be paid out now, minimum balance, fee, interest earned and not yet posted; actions; movements with running balances | `lending.savings.read` |
| `/staff/lending/savings/accounts/$accountId/statement` | Statement for a date range (three months to today by default): opening, lines, totals, closing | `lending.savings.read` |
| `/staff/lending/members/$memberId/savings` | The member's savings tab: every account, the total, and "Open a savings account" on an active product | `lending.savings.read`; opening `lending.savings.open` |
| `/staff/lending/savings/products` | Savings products: list, new product, edit (interest terms locked once accounts use it), archive | `lending.savings.read`; editing `lending.savings_products.manage` |

The loan page links to the member's savings for a session holding `lending.savings.read`.

| Action | Permission | Account status | Result shown |
|---|---|---|---|
| Deposit (amount, method, reference) | `lending.savings.deposit` | active, dormant, frozen | review step, then the receipt number and the new balance |
| Withdraw (amount, method, reference) | `lending.savings.withdraw` | active | review step with fee and balance after; 201 the voucher, 202 sent for approval |
| Close account (method, reason) | `lending.savings.withdraw` | active | review step saying interest is added first and the whole balance paid out; 201 closed, 202 sent for approval |
| Freeze, unfreeze, reactivate (reason) | `lending.savings.withdraw_approve` | freeze: active or dormant; unfreeze: frozen; reactivate: dormant | the new status |
| Reverse a deposit or withdrawal (reason) | `lending.savings.withdraw` | not closed; a movement not yet reversed | waiting for a checker; a withdrawal's fee goes with it |

- **Confirmations.** Every money form has two steps: the details, then a read-back of the amount, the account and
  member, the method, the fee and the balance after, with "Confirm" and "Change". A cashier reads it to the member
  before anything moves. The withdrawal form shows the most that can be paid out now and refuses more before sending.
- **Gating** is `lending/savings-permissions.ts`; refusal codes (`insufficient_balance`, `account_dormant`,
  `account_frozen`, `withdrawal_limit_exceeded`, `withdrawal_count_exceeded`, `below_minimum_opening`,
  `value_date_closed`, `product_in_use` and others) are in plain words in `src/api/savings.ts`.
- Deposits are dated today: once the nightly end of day has run, an earlier day is closed (ADR-032).

## Deferred

- Member self-service savings screens (increment 11), USSD and online payments (phase 2), transfers between accounts.
- A savings receipt or statement PDF (with the loan documents follow-up).
- Loan origination screens (application, appraisal, decision), waivers, restructures and credit refunds.
- A pending reversal or write-off is not marked on the loan page; the server refuses a second request with
  `approval_already_pending`.
- Printing or sending a receipt, a member statement, and a mock switch like `VITE_RETAIL_MOCK`.
- The tests render static markup (no DOM); a browser walk against the real API is still to do.
