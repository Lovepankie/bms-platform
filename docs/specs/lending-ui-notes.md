# Lending UI notes

**Status:** Draft · **Increment:** 5, disbursement and repayments (branch `feat/108-lending-disbursement-repayments`) · **Requirements:** FR-DIS-01, FR-DIS-04, FR-REP-01 to FR-REP-06, FR-LCL-02, FR-LCL-03 · **API:** `docs/sdd/07-api-design.md` section 7.11.13

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

## Deferred

- Loan origination screens (application, appraisal, decision), waivers, restructures and credit refunds.
- A pending reversal or write-off is not marked on the loan page; the server refuses a second request with
  `approval_already_pending`.
- Printing or sending a receipt, a member statement, and a mock switch like `VITE_RETAIL_MOCK`.
- The tests render static markup (no DOM); a browser walk against the real API is still to do.
