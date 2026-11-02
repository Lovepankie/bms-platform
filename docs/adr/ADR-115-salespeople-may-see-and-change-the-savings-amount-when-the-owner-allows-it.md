# ADR-115: Salespeople may see and change the savings amount when the owner allows it

## Status

Proposed (issue #768, 2 Nov 2026; FR-RET-18, FR-RET-19). Relaxes ADR-022 decision 13 for the savings amount only, and only
when the Owner turns the new setting on; it answers ADR-022 open question 8 for one figure. ADR-022 decision 12 (the audit
carries flags, never the amount) and ADR-080 (the cap) are unchanged. No migration. Renumbered at merge if needed.

## Context

A shop owner trialling the cash book (fabricated here as "the owner") showed the Savings screen as a salesperson sees it: "Savings
will be recorded using the standard amount", with no amount before or after saving, and "Savings recorded" in the list. In the
spreadsheet app the shop used before, the salesperson saw the amount and could change it before saving.

ADR-022 decision 13 hides every savings amount from a caller without `retail.profit.read`, because the suggestion is half the
day's profit (less its expenses since ADR-080). That is right as a default, but it is the Owner's profit to share: some owners
want the person closing the till to set the savings aside by hand and to check the figure.

## Decision

1. **A business setting, off by default, that only the Owner changes.** `salespeople_see_savings_amount` (boolean) in the
   settings document (`tenant_settings.settings`, no migration), read through `GET /settings` and written through
   `PATCH /settings`, which refuses anyone but the Owner with 403 `owner_only` (as `financial_year_end_month` does, ADR-090),
   and audited by `core.settings.updated` with before and after. Business set-up shows it to a retail business as "Salespeople
   can see and change the savings amount" with one plain line: "The suggested amount is half of the day's profit, so salespeople
   will be able to work out the profit." Another administrator sees it greyed out with "Only the Owner of the business changes
   this."
2. **Not backwards in time.** Turning it on stores the moment (`salespeople_see_savings_amount_since`); turning it off clears it.
   Only records saved at or after that moment show their amount under the setting. Records saved before stay as they were, so
   turning it on does not hand out the amounts of past days.
3. **When it is on**, for a caller with `retail.savings.record` and without `retail.profit.read`: the suggestion carries
   `suggested_minor`, and `existing_amount_minor` for a record saved since; the caller may send `amount_minor` and
   `overwrite_reason`; the create response, and the Savings records rows saved since in a branch where the caller holds
   `retail.savings.record`, carry `amount_minor` and `overwritten`. A 409 `suggestion_changed` carries the new `suggested_minor`
   too. A reader who holds only `retail.cashbook.read` is not given any amount by the setting.
4. **One rule for changing the amount.** With the setting on, anyone who may record savings in the branch may change the amount
   within the cap, with or without `retail.profit.read` and `retail.savings.overwrite`; with it off, `retail.savings.overwrite` is
   needed as before. The suggestion says which applies (`may_change_amount`), so the form never offers what the server refuses.
5. **The cap without the figures.** The available amount (profit less expenses) is never sent to a caller without
   `retail.profit.read`: a salesperson records the day's expenses, and the two would add back up to the profit. An amount above
   the cap is refused with 422 `savings_above_available` and the plain words "That is more than this day allows. Save a smaller
   amount, or the suggested one.", with no figure in the body. The screen shows no profit, no expenses and no available amount.
6. **Still hidden** from that caller, setting on or off: `daily_profit_minor`, `day_expenses_minor`, `available_minor`, the
   record's `suggested_minor`, and every other profit-gated figure of ADR-022 decision 13 (the savings report, the
   expected-to-bank answer, `savings_minor`, cash purchases).
7. **The audit.** The create's audit row keeps the flags of ADR-022 decision 12 (`overwritten`, `default_applied`, the reason if
   given) and adds `salespeople_setting: true` when the record was made by a salesperson under the setting, or changed under it
   by someone without `retail.savings.overwrite`; the actor is on the row. No amount is written, so a reader of the audit log
   learns no more than before.
8. **When it is off**, nothing changes: an amount from a caller without `retail.profit.read` is 422
   `amount_requires_profit_access` for every value, and no amount is shown or echoed. The server reads the setting once per
   request.

## Consequences

**Better:** the Owner chooses whether the person closing the till sees and sets the savings, as the shop did before; the cap and
the audit still apply; a business that leaves it off is unaffected, and turning it on does not reveal past days.

**Worse:** with it on, the issue's own words hold: a salesperson can work out the day's profit less expenses from the suggestion
(twice it at the default rate), and an amount equal to the suggestion is no longer a secret. That is the trade the Owner accepts,
and the setting says so in plain words. The cap refusal still tells, for any amount, whether it is above the cap; it adds nothing
the suggestion does not already give.

**Watch for:** an Owner who turns it on and later off: the amounts disappear again from the screens, but what a salesperson saw
while it was on cannot be taken back. A request to show the savings report or the expected-to-bank figures to salespeople is a
further relaxation of ADR-022 decision 13 and needs its own decision.
