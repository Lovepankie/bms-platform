# ADR-080: Savings are capped at the day's profit less its expenses, and the savings reason is optional

## Status

Proposed (issue #538, 24 Oct 2026). Amends ADR-022 decision 11 (the suggestion is now worked out from the day's available amount,
not from its profit alone) and adds a cap it did not have; ADR-022 decisions 12 and 13 (the audit flags and the profit gate) are
unchanged. Migration `V95`. Renumbered at merge if needed (ADR-079, average cost, is merged).

## Context

A shop owner trialling the cash book (fabricated here as "the owner") asked for three things after recording a day's savings:

1. The screen did not confirm the save clearly.
2. The app let them save more than the day had earned. They asked for the day's profit less the day's expenses as the limit.
3. Fewer typed reasons: "let it be optional or a drop down".

ADR-022 decision 11 made the default savings amount the day's profit (FR-RET-10: sales less cost of sales, usage and bad debt)
times the tenant rate, and let a holder of `retail.savings.overwrite` type any amount with a reason of at least 5 characters
(`retail_daily_savings_check1` in `V34`). Profit there leaves out the cash book's own expenses (rent, transport, airtime), so on a
day with large expenses the suggestion could exceed what was really left, and nothing at all stopped a typed amount above the
profit.

Many other screens also force a typed reason. For most of them the reason is the audit record of why money or stock moved, so
they are left for the business owner to decide one by one.

## Decision

1. **The day's available amount** for a branch and business date is its profit (FR-RET-10, `DailyProfits`) less the cash book
   expenses recorded for that branch and date that are not voided (`retail_expenses`), and never below 0.
2. **The suggestion** is the available amount times `retail.cashbook.savings_rate_bp`, rounded half up, and never more than the
   available amount. The suggestion token binds the suggestion as before, so an expense entered after the form was shown gives
   409 `suggestion_changed` only when it changes the suggestion. A small one may not (rounding half up: available 5,000 and
   4,999 both suggest 2,500), and then the form's figures are stale, but the server still checks the fresh cap (decision 3).
3. **The cap.** A savings amount above the available amount is refused by the server with 422 `savings_above_available`, a field
   problem on `amount_minor`, and plain words: "You can save at most UGX X today (profit UGX P minus expenses UGX E)." ("for 22 Oct
   2026" on another day). The body carries `available_minor`, `daily_profit_minor` and `day_expenses_minor`. Only a caller with
   `retail.profit.read` can type an amount, so the words reveal nothing that caller cannot read. The form says the same under the
   field on blur, before anything is sent, and the cap is checked before the overwrite permission.

   **It is checked when the savings are saved.** The savings create, the expense create and the expense void take one
   transaction-scoped advisory lock per tenant, branch and business date (`pg_advisory_xact_lock`) before they read or change
   the day's expenses, so an expense committing at the same moment is either seen or waits; savings never commit above the
   available amount of that moment. Two savings creates are already serialised by the one active record per day.
   **A later change does not undo savings:** an expense entered afterwards (or back-dated to that day), a sale voided
   afterwards or a cost correction can leave a saved amount above the day's new available amount, and the record stays. The
   Savings screen then says so for that day: "Saved UGX X; profit less expenses is now UGX Y" when X is above Y, from the
   suggestion's `existing_amount_minor*`. To correct it the owner voids the savings record and saves again. The import of a
   past cash book (historical expenses) takes no lock and is such a later change; expenses are never edited, only voided.
   The lock relies on READ COMMITTED, the database default the services keep: each statement after it reads what committed
   before it.
4. **The screen** shows the three figures (profit, expenses, available) to a caller with `retail.profit.read`, from the new
   suggestion fields `day_expenses_minor*` and `available_minor*` (absent without it, as `daily_profit_minor` is).
5. **The overwrite reason is optional.** The form offers "Add a reason (optional)", which opens a short pick list (Paying a debt,
   Buying stock, Owner took money, Slow day, Other, with a few words under Other). The server keeps a reason when one is given and
   drops a blank one. `V95` replaces `retail_daily_savings_check1` with `retail_daily_savings_overwrite_reason_chk`: on an
   overwritten record a reason, when present, is not blank. Like the check it replaces it says nothing about records that are
   not overwritten, so it is strictly weaker and cannot fail on existing rows. The overwrite still needs `retail.savings.overwrite`, and the audit row still records that the amount was
   overwritten.
6. **Other typed reasons stay as they are.** Only the savings reason becomes optional. Every other screen that forces a typed
   reason (voids, reversals and write-offs, stock corrections and usage, price changes, transfers, quotation and order cancels,
   lending, SMS opt-outs, operator changes) keeps it, with its server rule and its database check, because those reasons are the
   audit record a business relies on. Each is listed in the pull request of issue #538 (screen, endpoint, check) to be decided one
   by one later; any change to one is its own decision.

## Consequences

**Better:** The suggestion and the limit match what the day really left after its expenses, on the screen and on the server. A
changed savings amount no longer stops the person to type.

**Worse:** The suggestion endpoint reads the day's expenses as well as its profit. An expense entered or voided after the form was
shown can change the suggestion and send the person back to look again. An overwrite without a reason tells a later reader less.
The cap holds at save time: savings saved in the morning are not undone when that day's expenses grow in the afternoon; the
screen only says the day now has less. An expense create or void for a branch and day waits for a savings create on the same
day (and the other way round); both are short.

**Watch for:** Expenses dated on another day than the money was spent move the cap with them; the cap reads `business_date`. A
tenant rate above 10,000 basis points would suggest more than the day has; the suggestion is clamped to the available amount.
The other forced reasons are decided one by one, each as its own change.
