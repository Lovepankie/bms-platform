# Retail cash book: the pilot app and what we build

**Status:** Proposed (design only, issue #147) · **For:** the Owner's sign-off · **Decision record:** ADR-022

No code and no migration exist. This page sets, for each tab of the pilot app, what it has today,
what the platform will build, and what is left out and why. Figures here are fabricated. The rules
for every tab: one record belongs to one shop (a branch); nothing is edited or deleted, a wrong
record is voided and re-entered; amounts are whole money units; dates are the shop's business date
in the tenant's time zone; every record shows who entered it.

## 1. Daily savings (the pilot's "savings" tab)

| The pilot app has | We build | We leave out, and why |
|---|---|---|
| Per shop per day: user (auto), shop, amount saved, date | The same, plus the instant it was entered; one active record per shop per day; a wrong one is voided and re-entered | Duplicate records for one day: the pilot allows them, which double counts |
| Amount saved defaults to 0.5 x the shop's profit for the day (changed Sep 2026 from a tiered percentage of sales), user can overwrite | The default is a suggestion computed by the server from the day's profit times a rate kept as a setting (50% today), so the next change is a setting, not a rebuild. An overwrite needs a reason and is audited | The tiered percentage of sales: replaced by the rule above |
| Read-only: TotalAmountSold (the shop's sales that day) | Shown on the screen, stored with the record | |
| Daily Profit, shown only to the owner and admins | Shown only with the profit permission. Caveat: if shop staff see the default amount, half the profit can be inferred from it (question 5) | |
| The money is not on any ledger | A transfer from cash to a **savings reserve** account: not an expense, so profit is unchanged | Treating savings as an expense: it would understate profit (question 4) |

## 2. Cash banked (the "Banked" tab)

| The pilot app has | We build | We leave out, and why |
|---|---|---|
| user (auto), shop, ExpectedAmountToBank (virtual: the shop's total sold that day less that day's savings), amount banked (defaults to the expected amount), datetime, date | The same fields. The expected amount is computed by the server from the day's **cash** takings (cash sales and cash paid on credit sales), less savings, less cash expenses and advances paid from the till; the amount banked is prefilled with it. The expected figure is stored with the record | Counting credit sales as cash: credit is not cash until paid (question 2). Mobile money and bank sales: they never enter the till |
| No check against what was taken | The difference is flagged: shortfall, surplus, ok (within a tolerance, default none), or not banked. Part deposits on one day are allowed | Refusing a deposit larger than the cash on record: it is flagged instead, because the opening cash may be wrong |
| Menu view "Banked cash records", grouped by shop, year and month | The same grouping, and a **banking report** per shop per day: expected, banked, difference, running total of unbanked cash, who entered | |
| Not on a ledger | A transfer from cash to bank | |

## 3. Cash withdrawn from the bank

| The pilot app has | We build | We leave out, and why |
|---|---|---|
| user, amount withdrawn, date; no shop; admin only | The same, admin or owner only, plus the shop that receives the cash (default the head office) so the ledger can balance | A shop-less withdrawal: every ledger entry needs a branch (question 7) |
| Not on a ledger | A transfer from bank to cash. A withdrawal above the bank balance is accepted with a warning | An approval step: not in version one (question 3) |

## 4. Company expenses (the "expenses" tab)

| The pilot app has | We build | We leave out, and why |
|---|---|---|
| user (auto), shop, Category (required), Expense Item (required, depends on the category), Beneficiary (optional list), Total Cost, date | The same. Categories and items are lists the owner manages; each category maps to an expense account so reports and the books agree. Beneficiaries can be added on the fly | |
| An explanation is required when the item is "others" | Any item can be flagged "needs an explanation"; "others" is flagged | |
| Lists come from the "expense categories" tab | The list is exported and imported as it is (question 1); nothing is retyped | A category list invented by us |
| Not on a ledger | A debit to the category's expense account, credit to cash; it reduces the day's cash and shows in the cash summary | Paying an expense from the bank or mobile money: version one is a till payment |
| Menu view "Expense records" | The same, and an **expenses report** per shop and period by category and item with totals; an optional receipt photo | |

## 5. Loan disbursement and loan payments (the "loan" tab): advances

| The pilot app has | We build | We leave out, and why |
|---|---|---|
| Loan id (sequential), source of money (shop), customer name from a list (name, contact), principal, who took the money (a list), purpose | **Advances to the owner or company**, with a sequential number, the same fields, a party list and who took the money | Reusing the lending module: these are not customer loans (no schedule, interest, member or collateral) |
| Processing fee (hidden column) | Not modelled. A non-zero value in the history is reported and kept in the note | A fee on an advance to oneself (question 6 covers whether this is right) |
| Balance (virtual) | Principal less repayments, kept in step by the database | |
| Loan payments: loan id (only loans with a balance), amount paid, payment channel, balance | **Repayments** against an advance that still has a balance, by cash, mobile money or bank; an overpayment is refused | |
| Open to every signed-in user | Owner or admin only to record an advance or a repayment (question 6) | Staff recording advances: a change from the pilot |
| Not on a ledger | Advance: receivable account debit, cash credit. Repayment: the reverse | Treating an advance as drawings: it stays a receivable the owner can write off later |
| Menu view "loan disbursement and payment records" | The same, plus an outstanding advances report by party | |

## 6. Navigation and views

| The pilot app has | We build |
|---|---|
| Bottom bar: Sales, Banked, savings, expenses, loan; menu views grouped by shop, year and month | Same order on the phone: Sales, Banked, Savings, Expenses, Advances; the same records lists grouped by shop, year and month, shown by permission. A **daily cash summary** per shop per day (opening, takings, savings, expenses, advances, banked, closing, unbanked running total) that agrees with the books |

## 7. Moving the history

Savings, expenses, banking, withdrawals, advances and their payments, and the expense lists move across
as history: no ledger entries for old rows, and one opening entry per shop for the cash, bank, savings
reserve and outstanding advances at the cutover date, the same way the sales history moved. A row that
cannot be read is listed, never guessed or dropped.

## 8. For the Owner to sign off

Tick or correct each line. The build does not start on lines 1 to 4 until they are answered.

1. [ ] The real expense categories and items, which need an explanation, and the account each maps to.
2. [ ] Credit sales are not cash, so they are left out of the expected amount to bank until they are paid in cash. Cash expenses and cash advances paid from the till reduce it.
3. [ ] Withdrawals and advances need no approval in version one (owner or admin only). If they do: above what amount?
4. [ ] Savings go to a savings reserve account (an asset), not an expense. Where is that money kept?
5. [ ] Shop staff may record savings and overwrite the amount with a reason, and see the default. Or not?
6. [ ] Only owner or admin record advances and repayments. The hidden processing fee is dropped.
7. [ ] A withdrawal is recorded against a shop (default the head office).

Questions are listed with their reasons at the end of ADR-022.
