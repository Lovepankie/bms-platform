# ADR-022: Retail cash book: daily savings, banking, expenses, withdrawals and owner advances

## Status

Proposed (2026-10-06, issue #147). Design only: no code and no migration come with this record.
It becomes Accepted on merge of the pull request that carries it, and the accounting treatment in
decisions 3 to 7 stays provisional until the Owner confirms it (open questions 1 to 4 at the end).
Completes the cash book that ADR-020 left out of the first release ("pending ADR-022"). Amends
nothing in ADR-020.

## Context

The retail pilot runs its cash handling in the pilot app, next to sales, stock and restocks. Five
tabs carry it (`docs/specs/retail-cash-book.md` compares each field by field):

- **Daily savings.** Per shop and day, an amount set aside. It defaults to half of that shop's
  profit for the day (changed in September 2026 from a tiered percentage of sales), the user can
  overwrite it, and the screen shows the day's total sold. The daily profit is shown to the owner
  and admins only.
- **Cash banked.** Per shop and day, the amount taken to the bank, with an expected amount shown
  from the day's sales less that day's savings.
- **Cash withdrawn from the bank.** An admin form with no shop.
- **Company expenses.** A category and a dependent item from a list, an optional beneficiary, a
  cost, and an explanation required when the item is "others".
- **Loan disbursement and loan payments.** Money advanced to the owner or to the company, with
  sequential ids and payments against the balance. These are not customer loans.

They are in daily use, so they are needed before cutover. The first retail release deliberately
left them out (ADR-020 decision text, `docs/specs/retail-mvp-scope.md` section 4), and the
`import-retail` command skips their tabs. The pilot app's weaknesses here are the ones ADR-020
already named for stock: totals are virtual columns recomputed on the fly, a saved record can be
edited with no trace, savings and banked cash live outside any ledger so nobody can say where the
money is, and the profit-gated figure leaks through an editable default.

Lending needs cash handling too, but its needs differ (tellers, till sessions, member
transactions), and a concern enters the core only when two verticals share it (ADR-001). Today
only retail has this need.

## Decision

1. **The cash book is a sub-domain of the retail module**: the package `retail.cashbook`, tables
   prefixed `retail_`, copying the shape of `lending.members` and of `retail.sales`. It uses core
   modules only and never touches lending packages. Its declared `allowedDependencies` (Spring
   Modulith, `ModularityTest`) are exactly: `kernel`, `core.tenancy`, `core.audit`, `core.ledger`,
   `core.documents`, `retail.sales` (the day's cash sales and payments), `retail.purchasing` (cash
   restocks), `retail.reports` (the daily profit, read only for callers with `retail.profit.read`) and
   `retail.stock` (the posting and idempotency helpers); it must not depend on `lending.*`, and
   `retail.imports` adds `retail.cashbook` to its own allowed list for the cash book import. No
   retail module depends on `retail.cashbook` except `retail.imports`, so no cycle forms.
   When lending needs the same concepts, the shared part is extracted to core by a new ADR. This
   keeps `retailNeverDependsOnLending` true and answers ADR-020's "likely partly core".
2. **Advances are not lending.** Money advanced to the owner or the company is a retail cash book
   advance with repayments. It has no schedule, interest, member, collateral or approval chain of
   loans, and it does not reuse `lending.loans`. The pilot's word "loan" becomes "advance" in the
   product and the API.
3. **Savings are a transfer to a savings reserve, not an expense** (recommended, open question 4).
   Setting money aside changes where the company's money is, not how much it has, so it must not
   reduce profit. Debit `savings_reserve`, credit `cash_on_hand`, in the shop's branch. The reserve
   is a new asset account, **Savings reserve (restricted cash)**, `system_key` `savings_reserve`,
   code 1015. The alternative, an equity reserve, would still need an asset to hold the cash, so it
   only moves the question.
4. **Banked cash is a transfer from till cash to bank** (debit `bank`, credit `cash_on_hand`);
   **a withdrawal from the bank is the reverse** (debit `cash_on_hand`, credit `bank`). Neither
   touches profit. A withdrawal carries a branch in the platform (the pilot form has no shop; the
   ledger needs one). The default is the caller's one branch when the scope has exactly one, else
   the tenant's head office branch when it is within the caller's scope; otherwise the request must
   name a branch (422 `branch_required`). **Opening and settings records** (the cash opening journal
   and an imported row with no shop) use the tenant's single head office branch (exactly one exists,
   chapter 6 `branches`), regardless of any caller's scope, because an importer has no branch scope.
5. **An expense debits an expense account chosen by its category and credits cash.** Each expense
   category maps to a ledger expense account (default code 5900 `operating_expenses`, **Operating
   expenses**, the lending chart's code and key, reused exactly as decision 4 reuses 1190 for
   transfers so a tenant with both verticals keeps one account; the Owner may add accounts and map
   categories to them). Items inherit the account of
   their category. The credit is `cash_on_hand` in version one: the pilot form has no payment
   channel, so an expense is a till payment.
6. **An advance debits `owner_advances` and credits the source shop's cash; a repayment is the
   reverse.** New asset account **Advances to owner and related parties**, code 1250, `system_key`
   `owner_advances`, with the advance as subledger. The party of an advance is an `owner`, `staff` or `related_entity` cash party; there is no `company` kind, because an advance to the tenant's own business is not a receivable from anyone (open question 9). It is a receivable, not drawings: the pilot
   records payments back, so it is expected to clear. If the Owner treats unreturned advances as
   drawings, that is a later write-off by manual journal (FR-GL), not a different design. A
   repayment may arrive by `cash`, `mobile_money` or `bank` and debits that account **in the
   repayment's own `branch_id`**: the branch that receives the money, defaulting to the advance's
   branch and within the caller's scope. The credit to `owner_advances` posts in the same branch, so
   each entry balances per branch; the advance's source branch keeps its debit and the receiving
   branch carries a credit, which nets to zero in consolidation and needs no clearing account.
7. **Chart additions** (seeded by the idempotent `bms_seed_retail_chart` like ADR-020's chart, and
   for tenants that switched retail on earlier): 1015 `savings_reserve`, 1250 `owner_advances`,
   and 5900 `operating_expenses` (added to the retail chart only where the tenant has not got it
   already from lending, with the lending chart's code and key). A code or key the tenant already
   has is kept.
8. **Posting rules.** Every cash book event posts one balanced entry through `post_entry`, in the
   transaction of the event, with `source_module = 'retail'` and an idempotency key
   `retail.<kind>:<id>` (the table is in chapter 6 section 6.11.5). A void posts the reversal of
   each entry. Entries of amount zero are not posted (a savings amount of zero is a valid record).
9. **Cash position per shop and day is derived, never stored.** For a branch and business date:
   opening (the `cash_on_hand` balance on the previous day), plus cash takings (cash sales and cash
   payments on credit sales), less cash sale voids dated that day, less cash purchases (restocks
   paid from the till, which credit `cash_on_hand`, ADR-020 decision 7), less savings set aside,
   less cash expenses, less advances paid out, plus advance repayments received in cash, plus
   withdrawals from the bank, less cash banked, equals closing. **A sale void is dated the day it
   is made, not the sale's day** (the reversal posts on the void's business date, as ADR-020
   requires): the original day's takings are not restated, and the void shows as its own line,
   `cash_sale_voids_minor`, on the void date. Justification: closing must equal the ledger at the
   end of every day, and the ledger holds the sale on its day and the reversal on the void day; a
   restated past day would stop agreeing with the ledger as of that day. A sale voided the same
   day nets to zero. The cost of this rule is that a late void shows as a lower expected amount
   on the void day, and the unbanked running total nets the two days out. Closing must equal the ledger's `cash_on_hand` balance at the end of the day;
   any difference is shown as `other_movements_minor` (a manual journal or an unlisted source),
   never hidden. The **unbanked running total** is the cumulative sum, from the shop's first live
   cash book day, of expected less banked per day. For days imported from the pilot (no journals) the
   position is built from the rows alone and marked `ledger_basis: false`. The unbanked running
   total starts at the first live day and excludes imported days, so the pilot era's drift is never
   carried forward.
10. **Expected amount to bank is computed on the server** for a branch and date, as the day's cash
    takings, less cash sale voids dated the day, less cash purchases (restocks paid in cash from the
    till), less the day's savings, less cash expenses and advances paid out of the till, plus cash
    advance repayments received. Cash takings are sales of method `cash` by
    `sale_date` (a sale voided later is still counted on its own day and reversed on the void day,
    decision 9), plus payments on credit sales of method `cash` by `paid_on`. Credit sales are not
    cash and are excluded until paid (recommended, open question 2). Mobile money and bank sales are
    excluded too: they do not enter the till. The client may display the figure but never supplies
    it; the form prefills the amount banked with it, as the pilot does, and the record stores the
    expected figure as a snapshot (`expected_minor`) so a later back-dated sale does not rewrite
    what the user saw. Several banking records for one shop and day are allowed (partial deposits);
    the report sums them. The **difference** is banked less expected: a shortfall below minus the
    tolerance, a surplus above the tolerance, `ok` within it. The tolerance is a tenant setting
    (`retail.cashbook.tolerance_minor`, default 0). A day with expected above zero and nothing
    banked reads `not_banked`. Banking is never refused for exceeding cash on hand (the opening
    position may be wrong); it is flagged.
11. **The default savings amount is a server-side suggestion.** For a branch and date it is the
    day's profit (FR-RET-10) times a tenant rate, `retail.cashbook.savings_rate_bp` (default 5000,
    half), rounded half up, and zero when the profit is not positive. The rate is a setting, not
    code, because it has already changed once. If the request omits the amount, the server uses the
    suggestion. The record stores `suggested_minor` and `amount_minor`. A savings amount is half
    the day's profit, so it **is** profit-derived and falls under the one rule of decision 13. The
    suggestion is read in one call that returns an opaque `suggestion_token` (a keyed hash of the
    tenant, branch, date and suggested amount, so it reveals nothing); the create call echoes the
    token it was shown, and the server compares it with the suggestion recomputed in the create's
    transaction. A suggestion that changed since the form was shown (a sale, a void or a cost
    change in between) is 409 `suggestion_changed` carrying the new token (and the new
    `suggested_minor` for a caller with `retail.profit.read`), never a silent overwrite. **One active savings record
    per branch per business date**, a partial unique index on rows not voided; a correction is a
    void and a new record.
12. **Overwriting the savings amount** means the amount differs from the suggestion the caller was
    shown (judged against the echoed `suggestion_token`, decision 11, not against a suggestion
    recomputed later, so a changed suggestion is a 409 and never a false overwrite). It needs a
    reason of at least 5 characters and `retail.savings.overwrite`. **Every** savings create writes
    an audit row naming the actor, the date and the branch, and its payload carries only flags:
    `overwritten` (with the reason when true) and `default_applied` (true when the amount was
    omitted and the suggestion used). It never carries `amount_minor`, `suggested_minor` or the
    profit, because an amount equal to the default would hand the profit to any reader of the audit
    log without `retail.profit.read` (the class of issue #77). Whether shop staff may overwrite is
    open question 5.
13. **Permissions** (chapter 8 has the matrix). `retail.cashbook.read` reads every cash book list
    and the daily cash summary, scoped by branch (ADR-017). Writes have their own permission:
    `retail.savings.record`, `retail.savings.overwrite`, `retail.banking.record`,
    `retail.expense.record`, `retail.expense.manage` (categories, items, beneficiaries),
    `retail.withdrawal.record`, `retail.advance.create`, `retail.advance.repay`, and
    `retail.cashbook.void`. Withdrawals and advances (both ways) are owner or admin only, in line
    with the pilot's admin-only withdrawals and the instruction for advances. **One profit rule
    (chosen here, open question 8 asks the Owner to confirm it):** a savings amount is profit-derived,
    so every response field that carries a savings amount or the suggestion is profit-gated (marked
    `*` in chapter 7) and absent, not null, without `retail.profit.read`. That covers
    `suggested_minor`, `suggestion` and the daily profit, `amount_minor` on savings rows and in the
    savings report, `savings_minor` and the figures that embed it (`expected_minor`,
    `expected_to_bank_minor`, `difference_minor`, `unbanked_running_minor`, `opening_minor`,
    `closing_minor`, `other_movements_minor`) in the expected-to-bank answer, the banking report,
    stored bankings and the daily summary. The one exception is the writer's own just-entered amount,
    which the create response echoes back to the person who typed it. A caller without
    `retail.profit.read` is shown the cash expected figure only, `cash_expected_minor`: takings less
    voids, cash purchases, cash expenses and advances paid out, plus cash repayments, before savings.
    The cost is that a shop user cannot see the net amount to bank or the difference flag; the
    tenant admin can, and a savings reserve held apart from the till is the admin's concern.
14. **Maker-checker is not applied in version one** (open question 3). The design leaves room: the
    actions `retail_cash_withdrawal` and `retail_advance` could be registered through the action
    registry (ADR-015) with a threshold (FR-APR-04), with no change to the tables. A registered
    action with the default threshold of 0 would make every instance need a checker, so the
    threshold is set before the action is registered, never after.
15. **Voiding, not deleting.** Every cash book table is append-only except the void columns
    (`voided_at`, `voided_by`, `void_reason`), changed once by a guard trigger like
    `retail_sales_guard_update`. A void reverses the journal entries and frees the savings unique
    slot. Corrections never edit an amount.
16. **Idempotency, money and dates.** Every cash book POST is money-moving and requires
    `Idempotency-Key` (chapter 7 section 7.8). Amounts are integer minor units with a `currency`
    (ADR-004), at most 10^13. Every record carries a `branch_id`, and every list is filtered by the
    caller's branch scope. Business dates are in the tenant's time zone (`tenants.timezone`,
    default `Africa/Kampala`) through the kernel clock, not in the future; the instant (`occurred_at`)
    is stored separately from the business date.
17. **Expense categories and items are tenant data.** `retail_expense_categories` and
    `retail_expense_items` hold the lists (the pilot's expense categories tab). An item may be
    flagged `requires_explanation` (the pilot's "others"); the expense then needs an explanation
    of at least 3 characters. Beneficiaries and advance parties come from one list,
    `retail_cash_parties`, which a user with `retail.expense.manage` can extend on the fly. The real
    lists are not guessed (open question 1).
18. **Importer support.** The `import-retail` command is extended with the cash book tabs, on the
    same normalised JSON Lines contract (data dictionary section 5): expense categories and items,
    parties, savings, expenses, banking, withdrawals, advances and advance payments, and one
    `cash_balances` file. Historical rows follow ADR-020 decision 9: rows marked `historical`,
    **no journals**, keyed by tab, source reference in `retail_import_refs` so a re-run adds
    nothing, and one opening journal per branch that records the cash, bank, savings reserve and
    outstanding advances against `opening_balance_equity`, **dated the day before the first live
    day** so the first live day's `opening_minor` shows the carried balance and not
    `other_movements_minor`. The advances part has **one line per outstanding advance, with the
    advance as its subledger**, like the lending opening import, never one figure per branch. An
    imported advance takes the next value of the live `retail_advance_no` sequence (in business date
    then source id order); the pilot's id is kept in the import reference and the advance's note and
    is never reused as `advance_no`, so no imported number can collide with a live one. Imported
    days are shown apart in the banking report and are excluded from the unbanked running total,
    which starts at the first live day with nothing carried. A category or item in
    a row that is not in the lists is created as written and reported, never guessed. The pilot's
    hidden processing fee on an advance is not modelled; a non-zero value is reported and kept in
    the advance's note.
19. **Reports** (chapter 7 section 7.11.21): the daily cash summary per shop, the banking report
    (expected, banked, difference, flag, running unbanked total, who entered), the expenses report
    by category and item, the savings report, and the outstanding advances report. Savings amounts,
    the suggestion, daily profit and every figure derived from them are profit-gated by the rule in
    decision 13.
20. **Migration numbering.** No migration is written or numbered by this record. The cash book
    migration takes the next free Flyway version at merge time, above the highest on any open
    branch (Flyway runs with `outOfOrder` off), announced on issue #50 as the other retail
    migrations were. It includes the chart seed for existing retail tenants, the new permissions
    and their role mapping, and the tables of section 6.11.5.

## Consequences

**Better:** money set aside, banked, withdrawn, spent and advanced is on the ledger, so "where is
the cash" has one answer per shop and day. Savings stop being a number in a tab that anyone can
retype: one active record per shop and day, overwrites need a reason and are audited, and the
rate is a setting. The expected amount to bank is computed from the day's cash takings instead of
a virtual column that counts credit sales as cash. Advances to the owner stay out of customer
lending. The pilot's history imports without journals and an opening balance, like the sales.

**Worse:** one more sub-domain with nine tables and about ten permissions to seed and maintain.
Expected amount to bank depends on cash expenses and advances being entered the same day, so a
shop that records them late sees a surplus flag that later clears (the stored snapshot keeps what
the user saw, the report recomputes). Advances in the receivable account never clear if the Owner
never repays; that is information, not an error. A savings amount reveals half the day's
profit, so shop staff see no savings amounts and only the cash expected figure (open question 8),
which costs them the net amount to bank; the design chooses the gate over the leak.

**Watch for:** the cash position must reconcile with the ledger's `cash_on_hand`; a manual journal
to cash shows as `other_movements_minor` and should be rare. Tenant time zone matters at midnight:
a sale at 23:50 local belongs to that day. A tenant that never records savings keeps a zero
reserve, which is valid. Voiding a savings record after the day's banking was entered changes the
expected figure of that day, so the report recomputes and may show a surplus. The tolerance and
the rate are tenant settings; their defaults (0 and 5000) are not a policy decision. `ModularityTest`
must keep `retail.cashbook` off lending. When lending adds its own cash handling, extract the shared
parts to core with a new ADR instead of reaching across.

## Open questions for the Owner

Do not guess these; nothing in the build starts until 1, 2 and 4 are answered.

1. **The real expense categories and items.** The pilot app's expense categories tab holds the
   list and which items need an explanation. It is exported with the cash book import
   (`expense_categories.jsonl`) and never typed from memory into this repository. Which ledger
   expense account does each category map to?
2. **Are credit sales excluded from the expected amount to bank?** Recommended: yes, credit is not
   cash, and a credit sale enters the expected figure on the day its payment is received in cash.
   The pilot's check from its app definition is still needed (the sale type list has cash and
   credit only). Also confirm that cash expenses and cash advances paid out of the till reduce the
   expected amount (recommended), which the pilot app does not do.
3. **Do withdrawals and advances need an approval (maker-checker) above a threshold?** Recommended:
   no in version one, owner or admin only. If yes: which amount, and does it apply to repayments?
4. **The savings reserve's account treatment.** Recommended: a restricted cash asset
   (`savings_reserve`), not an expense and not equity. Where is the reserve held physically (a
   separate box, the bank, a mobile money wallet)? Are there withdrawals from the reserve, and for
   what?
5. **Who may overwrite the savings amount, and may shop staff see the default?** The pilot lets any
   user overwrite it and shows the default, which reveals half the profit. Recommended: any holder
   of `retail.savings.record` may record, overwrites need a reason and are audited, and the profit
   itself stays admin only. Say if staff should not see or change the amount.
6. **Are advances recorded by admins only, and who may record repayments?** The pilot lets any
   signed-in user do both; this design makes both owner or admin only.
7. **Should the withdrawal form have a shop?** The pilot form has none; the ledger needs a branch.
8. **May roles without `retail.profit.read` see savings amounts?** The design applies one rule:
   a savings amount is half the day's profit, so it is profit-gated like the profit itself
   (decision 13), and the audit log never carries it (decision 12). The cost is that the sales role
   sees that savings were recorded but not how much, and sees only the cash expected figure, not the
   net amount to bank or the difference. If the Owner would rather let shop staff see the amounts,
   the leak of half the day's profit is accepted knowingly and the gate on those fields is removed.
   Recommended: keep the gate.
9. **Who is "the company" in the pilot's advances?** The pilot records money advanced to the owner or
   to the company. If the company is the tenant's own business, the advance is a transfer inside one
   entity, not a receivable, and should not sit in an asset account. If it is a separate legal
   entity (a sister company), it is a receivable from a related entity. Until the Owner answers,
   the design offers the party kinds `owner`, `staff` and `related_entity` and no `company` kind,
   and an imported party marked company is reported, not mapped. Do not decide this for the Owner.
