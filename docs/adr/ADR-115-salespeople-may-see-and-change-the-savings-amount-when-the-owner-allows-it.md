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
day's profit (less its expenses since ADR-080). That is right as a default, but it is the owner's profit to share: some owners
want the person closing the till to set the savings aside by hand and to check the figure.

## Decision

1. **A business setting, off by default, that only the Owner changes.** `salespeople_see_savings_amount` (boolean) in the
   settings document (`tenant_settings.settings`, no migration), read through `GET /settings` and written through
   `PATCH /settings`, audited by `core.settings.updated` with before and after. Like the go-live date (ADR-071) and the end of the
   financial year (ADR-090), anyone but the Owner sending it is refused 403 `owner_only`, an Admin holding `core.settings.manage`
   included. Business set-up shows the switch to the Owner of a retail business only, as "Salespeople can see and change the
   savings amount", with one plain line: "A salesperson who sees the savings amount can work out the day's profit from it. Only
   you, the owner, can change this."
2. **Only what the request needs.** With the setting on, a caller with `retail.savings.record` and without `retail.profit.read`
   gets the suggested amount (`suggested_minor`) and may send `amount_minor` and `overwrite_reason`. That caller never gets
   `available_minor` (the cap), `daily_profit_minor` or `day_expenses_minor`. The cap of ADR-080 still holds, and that caller
   may lower or raise the amount up to it: an amount above it is 422 `savings_above_available` with the plain words "That amount
   is more than this shop can save today. Enter a smaller amount.", which state neither the cap, the profit nor the expenses,
   and the problem carries none of them. The cap is checked last, after the permission check (`may_change_amount`) and the
   existing-record check (`savings_exists`), so a request that is refused anyway never says whether its amount was above the
   cap. A 409
   `suggestion_changed` carries the new `suggested_minor`, since the caller sees the suggestion anyway.
3. **Amounts only on records made while the setting is on, and only to recorders of savings.** Turning the setting on stores the
   moment in `salespeople_see_savings_amount_since` (the database clock, the one that stamps the records); turning it off removes
   it. A savings record shows `amount_minor` and `overwritten` to a caller without `retail.profit.read` only when it was made
   after that moment, while the setting is still on, and the caller holds `retail.savings.record` for its branch: in the create
   response, the void response, the Savings records rows and the suggestion's `existing_amount_minor`. Records made before, and a
   reader holding `retail.cashbook.read` alone, keep the amounts hidden. Turned off and on again, only records made from the new
   moment show.
4. **The same rule for everyone to change the amount.** With the setting on, changing the suggested amount before the day is
   saved needs `retail.savings.record`, for a caller with profit read as much as for one without; no caller gets more by holding
   less. Replacing a saved amount (the day has a voided record, so the new amount takes the place of a saved one) needs
   `retail.savings.overwrite` for everyone, and so does any change while the setting is off. The suggestion carries
   `may_change_amount`, so the screen follows the server instead of guessing.
5. **Still hidden**, setting on or off: the record's `suggested_minor` and every other profit-gated figure of ADR-022 decision 13
   (the savings report, the expected-to-bank answer, `savings_minor`, cash purchases). The screen shows no profit, no expenses
   and no cap to that caller.
6. **The audit.** The create's audit row keeps the flags of ADR-022 decision 12 (`overwritten`, `default_applied`, the reason if
   given) and adds `salespeople_setting: true` when the record was made while the setting was on; the actor is on the row. No
   amount is written, so a reader of the audit log learns no more than before.
7. **When it is off**, nothing changes: an amount from such a caller is 422 `amount_requires_profit_access` for every value, and
   no amount is shown or echoed. The server reads the setting once per request.

## Consequences

**Better:** the owner chooses whether the person closing the till sees and sets the savings, as the shop did before; no one but
the Owner can make that choice; the cap and the audit still apply; earlier days stay hidden; a business that leaves it off is
unaffected.

**Worse:** with it on, a salesperson can work out the day's profit from the savings amount: the suggestion is a share of the
day's profit less its expenses (half at the default rate), and a salesperson knows or records the day's expenses. By trying
amounts, a salesperson can also find the most that may be saved, which is the day's profit less its expenses: an amount above
it is refused and one at or below it is accepted. Leaving the cap out of the answers does not prevent either. The owner
accepted this (owner decision D124). The setting is off by default, only the Owner turns it on, and its help text says plainly that a
salesperson who sees the savings amount can work out the day's profit.

**Watch for:** an owner who turns it on and later off: the amounts disappear again from the screens, but what a salesperson saw
while it was on cannot be taken back. Turning it on again does not show records made in an earlier period. A request to show
the savings report or the expected-to-bank figures to salespeople is a further relaxation of ADR-022 decision 13 and needs its
own decision.
