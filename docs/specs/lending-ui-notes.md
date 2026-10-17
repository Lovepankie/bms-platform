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

## Breadcrumbs (issue #240, FR-UXN-01)

The lending screens carry the same trail as the retail ones (Home / Lending / Savings / Account, Home / Lending /
Investments / Member investments). The loan, investment and savings account pages name their last crumb after the
number they have already loaded. See `docs/specs/retail-ui-notes.md`, section "Breadcrumbs and category groups".

## Deferred

- Member self-service savings screens (increment 11), USSD and online payments (phase 2), transfers between accounts.
- A savings receipt or statement PDF (with the loan documents follow-up).
- Loan origination screens (application, appraisal, decision; issue #280 slices 3 and 4), waivers, restructures and credit refunds. The member and collateral screens are built (section "Members and collateral").
- A pending reversal or write-off is not marked on the loan page; the server refuses a second request with
  `approval_already_pending`.
- Printing or sending a receipt, a member statement, and a mock switch like `VITE_RETAIL_MOCK`.
- The tests render static markup (no DOM); a browser walk against the real API is still to do.

## Members and collateral (issue #280, slices 1 and 2)

**Requirements:** FR-MEM-01 to FR-MEM-10, FR-COL-01 to FR-COL-05 · **API:** `docs/sdd/07-api-design.md` sections 7.11.11 and 7.11.14 · no backend change

Phone-first staff screens on the generated client (`src/api/members.ts`, `src/api/collateral.ts`, typed by `schema.d.ts`; the screens use the `MembersApi` and `CollateralApi` interfaces through `LendingApiContext`, so the in-memory `members-mock.ts` stands in for them in tests). Styled in `lending.css` (`mf-` form classes, `ph-` photo classes). The staff header shows **Members** with `lending.members.read` (and **Collateral** to a user who may read collateral but not members); both screens carry the small Members and Collateral menu.

| Route | Screen | Permissions needed |
|---|---|---|
| `/staff/lending/members` | Members: live search by number, name, phone or national ID (never in the address), status filter, "Load more", Add a member, empty state | `lending.members.read` (add: `lending.members.create`) |
| `/staff/lending/members/new` | Add a member: branch, name, phone, ID, date of birth, gender, marital status, work, income, address, ID photos | `lending.members.create` in the chosen branch |
| `/staff/lending/members/$memberId` | The member's page: details, checking the details (what is missing; accept or refuse), next of kin, documents, who names the member as kin, loans, collateral, savings and investments links, text-message choice | `lending.members.read`; changes `lending.members.update`; the check `lending.members.verify_kyc` |
| `/staff/lending/members/$memberId/edit` | Change details, and the member's status | `lending.members.update` in the member's branch |
| `/staff/lending/collateral` | The register: search over the loaded items, filter by kind and by who keeps it, Register collateral | `lending.collateral.read` (register: `lending.collateral.manage`) |
| `/staff/lending/collateral/new` | Register collateral: member (found by searching; `?memberId=` from the member's page), kind, description, title, logbook or plate number, owner, estimate, who keeps it, photos | `lending.collateral.manage` |
| `/staff/lending/collateral/$collateralId` | One item: facts, photos and scans, valuations (add), history of custody and the changes allowed from where it is now | read; changes `lending.collateral.manage`; release `lending.collateral.release_request` |
| `/staff/lending/collateral/$collateralId/edit` | Change details | `lending.collateral.manage` |

- **Branch scope.** Offered actions follow `me.permission_scopes` per permission and branch (`member-permissions.ts`, `holds`), not only the permission name; the API stays the authority.
- **Phone.** The form normalises as the server does (`PhoneNumbers.normaliseUganda`: `07...`, `+256 7...`, `2567...`) and is stricter in one way: a bare nine digits is refused with a hint to start with 0 or +256, since it cannot be told from a number missing a digit. The shared cases of chapter 13 section 13.6 are in `member-state.test.ts`.
- **Duplicates before saving (FR-MEM-04).** After a short pause the form asks `duplicate-check` for the typed phone, national ID and name and lists likely matches (masked, with why). The same national ID stops the save; a shared phone stops it until the person ticks that it is a different person, which sends `confirmed_not_duplicate`. A refusal from the server (`duplicate_nin`, `duplicate_phone`) shows on its own input with the other member's number.
- **Editing.** An edit sends the version it loaded as `If-Match` and only the fields that changed. A stale version (409) keeps what was typed and offers to reload the other version. "Not said" for gender and marital status is sent as `unspecified` and `unknown`; a saved date of birth or income cannot be emptied (the server keeps an omitted field), so the form says so instead of doing nothing.
- **Idempotency and a lost answer.** One `Idempotency-Key` per form instance, stable across retries. The member and collateral create routes do not read it today (they are not money routes, chapter 7 section 7.8), and a collateral item with no reference number, a next of kin and a valuation have nothing on the server that stops a second one. So the clients wrap every failure as a `FormError` (`guarded` in `lending-forms.ts`): a dropped connection, a timeout or a stopped request is status 0, and a 5xx is also an unknown outcome (`unknownOutcome`). After one, the screen (`save-guard.tsx`) looks the record up (a duplicate check by phone for a member, the member's collateral, the member's next of kin, the item's valuations) and blocks every further save until that look has SUCCEEDED: found says it is already saved and links to it, not found frees the save, and a look that itself failed keeps the block with "Check again". Tests use the real clients with a fetch that rejects (`members-real.test.ts`).
- **Photos.** `photo-queue.tsx` and `image-prep.ts`: "Take a photo" opens the phone camera (`capture`), "Choose a file" the gallery or files (JPEG, PNG, WebP, PDF; HEIC is not offered because most browsers cannot draw it). A wrong kind, an empty file, a PDF over 5 MB or a file over 30 MB is refused in words; a photo is drawn at most 2000 pixels on its longest side and written as JPEG under the server's 5 MB, trying smaller until it fits. Progress shows while sending; a failed send keeps the shrunk picture, so "Send again" does not shrink or ask again, and a second tap while one is on its way sends nothing more. A new member's ID photos are held and sent after the member is saved; if one fails, the member stays saved and the page offers to send it again. ID images are listed by the server only to staff holding `lending.members.verify_kyc`; others see a line saying so.
- **Release.** "Ask to give it back" only sends the request (`collateral_release`, FR-COL-04). A second person agrees or refuses in the approvals inbox; the item stays held until then. A loan that the item secures blocks it (`collateral_secures_open_loan`). Linking collateral to a loan is slice 3.
- **Errors** are in plain words (`src/api/lending-forms.ts`); each field problem shows under its input, tied with `aria-describedby`, with `aria-invalid`, and focus moves to the first one. No phone, national ID or name is logged or sent anywhere but the API.
- **Tours** (`src/tour/tours-customers.ts`, each a page tour from Help, audience by permission): `page-members`, `page-member-new`, `page-member`, `page-collateral`, `page-collateral-new`, `page-collateral-item`. `tours.test.tsx` renders each anchor.
- **Shared phone.** The "different person who shares the phone" box shows from the check's candidates or from the server's `duplicate_phone` refusal (an update-only user gets no check, as that route needs `lending.members.create`); on an edit it applies only when the phone was changed. Phone and national ID are sent normalised, not as typed.
- **Approvals inbox.** A `collateral_release` request shows the item (kind, description, estimate, where it is) instead of its identifier. The list carries no payload and no member name; those are a follow-up.
- **Deferred:** the tenant's currency instead of UGX on these forms (a follow-up), collateral free-text search on the server (the register filters the loaded items on the device), a member statement, moving a member to another branch (FR-BR-06), HEIC photos, and the loan application, appraisal and decision screens (issue #280 slices 3 and 4).

## Investments (increment 10, issue #152)

**Requirements:** FR-INV-01 to FR-INV-12 · **API:** `docs/sdd/07-api-design.md` section 7.11.16 · **Decision:** ADR-031

Phone-first staff screens in the same folder, on the generated client (`src/api/investments.ts`), styled in
`src/app/ui/lending.css` (the `iv-` classes) with the tokens and no inline styles. The staff header shows
**Investments** to a session with `lending.investments.read`; every investment screen carries a sub-menu (All,
Maturities, New investment, Products) as 44px links.

| Route | Screen | Permissions needed |
|---|---|---|
| `/staff/lending/investments` | Investments: search by account number, member number or name, status filter, "Load more"; each row shows the amount, product, rate, term, maturity and any return due | `lending.investments.read` |
| `/staff/lending/investments/member/$memberId` | The member investments tab: one member's investments, what they hold now, and "New investment for this member" | `lending.investments.read` (new: `lending.investments.open`) |
| `/staff/lending/investments/new`, `/staff/lending/investments/member/$memberId/new` | Subscribe: find the member (unless given), product, amount, term (the product's terms as radio buttons), the maturity choice, and the server's return preview before opening | `lending.investments.open` |
| `/staff/lending/investments/$investmentId` | One investment: terms, balances (return due now in bold), the actions, the schedule table and the statement with the balances after each line | `lending.investments.read` |
| `/staff/lending/investments/$investmentId/certificate` | Certificate and statement laid out to print (Print button; printing hides the staff header and menus) | `lending.investments.read` |
| `/staff/lending/investments/maturities` | Cash needed for payouts: matured and unpaid, within 7 days, 8 to 30 and 31 to 90 days (one column on a phone, four from 720px), the list of investments maturing, then the month's figures and the largest investors and products | `lending.investments.read` |
| `/staff/lending/investments/products` | Products setup: the list for every reader; New, Edit (investments already opened keep their terms) and Archive for an admin. Rates are typed as a percentage a year and sent as basis points | `lending.investments.read` (manage: `lending.investment_products.manage`) |

The actions on the investment page, offered only for the statuses the server accepts:

| Action | Permission | Status | Result shown |
|---|---|---|---|
| Record funding (date, method, reference) | `lending.investments.fund` | pending funding, no request waiting | 201: funded, schedule shown; 202: waiting for a checker above the threshold |
| Pay return due (method, reference) | `lending.investments.payout` | active, matured or rolled over, with a return due | the voucher number and amount |
| Pay out (method, reference) | `lending.investments.payout` | matured | principal plus unpaid return, the voucher number |
| Roll over (principal and return, or principal only) | `lending.investments.payout` | matured | opens the new investment |
| Early withdrawal (the day's quote, method, reason) | `lending.investments.payout` | active, product allows it, no request waiting | always waits for a checker; worked out again on the approval day |
| Maturity choice | `lending.investments.open` | pending funding, active, matured | saved |
| Reverse (reason), on a statement line | `lending.investments.payout` | a funding, return payout or maturity payout not yet reversed | waits for a checker |

Money is parsed with `parseAmount` into integer minor units and every figure is the server's; each money form keeps
its draft and Idempotency-Key in the tab (`retail/idempotency.ts`). Investment refusal codes get plain words in
`src/api/investments.ts` and fall back to the lending and retail lists.

Deferred: member self-service (the member portal's Invest screen, increment 11), online payments, USSD, SMS reminders,
the certificate PDF in document storage, and a member page with tabs (the member tab is the filtered list above until
member screens exist).

## Reports (extras slices D1 and D2, issues #214 and #217, ADR-038)

A minimal staff page, `/staff/reports` (tab "Reports", shown to a user of a lending tenant who holds one of the
`lending.reports.*` permissions and only while `GET /me` says `reports_enabled`; a business administrator turns reports on with the
"Reports" box in Business set-up, a `PATCH /settings` with `reports_enabled`); the full report centre is slice D5. A page opened while
reports are off (the catalogue answers 404) says so. The page lists each report the user may run, a small form per report (its parameters from the catalogue: dates,
a choice such as "Group by", the branch chosen in the staff bar, the file type) and "Run report", which queues a run. Under "Your reports"
the user's own runs show a status (Waiting, Running, Ready, Failed, Cancelled), the date the figures stand at, the size of a ready file
and, for a failed run, the reason in words (`src/api/reports.ts` maps the stable error codes). The list refreshes every 3 seconds while
any run is open, and a polite notice says when one becomes ready (the in-app notice of FR-RPT-04). Download fetches a 5 minute signed
link; a file older than 30 days reads "Deleted after 30 days". Cancel stops a waiting or running run. The page never shows a row of a
report; it hands the file over. The file type is chosen from what the report offers, with the PDF (to read and print) first, then Excel
(a workbook), CSV and JSON; a run in the list names its type in words ("Excel", not "XLSX"). Slice D2 added the PDF and Excel types and the
report "Loan portfolio outstanding" beside "Portfolio at risk and ageing".
