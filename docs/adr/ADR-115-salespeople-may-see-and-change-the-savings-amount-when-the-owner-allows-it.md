# ADR-115: Salespeople may see and change the savings amount when the owner allows it

## Status

Proposed (issue #768, 2 Nov 2026; FR-RET-18, FR-RET-19). Relaxes ADR-022 decision 13 for the savings amount only, and only
when the owner turns the new setting on; it answers ADR-022 open question 8 for one figure. ADR-022 decision 12 (the audit
carries flags, never the amount) and ADR-080 (the cap) are unchanged. No migration. Renumbered at merge if needed.

## Context

A shop owner trialling the cash book (fabricated here as "the owner") showed the Savings screen as a salesperson sees it: "Savings
will be recorded using the standard amount", with no amount before or after saving, and "Savings recorded" in the list. In the
spreadsheet app the shop used before, the salesperson saw the amount and could change it before saving.

ADR-022 decision 13 hides every savings amount from a caller without `retail.profit.read`, because the suggestion is half the
day's profit (less its expenses since ADR-080). That is right as a default, but it is the owner's profit to share: some owners
want the person closing the till to set the savings aside by hand and to check the figure.

## Decision

1. **A business setting, off by default.** `salespeople_see_savings_amount` (boolean) in the settings document
   (`tenant_settings.settings`, no migration), read and written through `GET` and `PATCH /settings` like `reports_enabled`, and
   audited by `core.settings.updated` with before and after. Business set-up shows it to a retail business as "Salespeople can see
   and change the savings amount" with one plain line: "The suggested amount is half of the day's profit, so salespeople will be
   able to work out the profit."
2. **When it is on**, for a caller with `retail.savings.record` and without `retail.profit.read`:
   the suggestion carries `suggested_minor`, `available_minor` (the most the day may save) and `existing_amount_minor`; the
   caller may send `amount_minor` and `overwrite_reason`; an amount different from the suggestion needs no
   `retail.savings.overwrite` (the owner's setting is the permission); the cap of ADR-080 holds, and its refusal says only "You
   can save at most UGX X today." with `available_minor`; the create response and the Savings records rows carry `amount_minor`
   and `overwritten`. A 409 `suggestion_changed` carries the new `suggested_minor` too.
3. **Still hidden** from that caller, setting on or off: `daily_profit_minor`, `day_expenses_minor`, the record's
   `suggested_minor`, and every other profit-gated figure of ADR-022 decision 13 (the savings report, the expected-to-bank answer,
   `savings_minor`, cash purchases). The screen shows no profit and no expenses.
4. **The audit.** The create's audit row keeps the flags of ADR-022 decision 12 (`overwritten`, `default_applied`, the reason if
   given) and adds `salespeople_setting: true` when the record was made under the setting; the actor is on the row. No amount is
   written, so a reader of the audit log learns no more than before.
5. **When it is off**, nothing changes: an amount from such a caller is 422 `amount_requires_profit_access` for every value, and
   no amount is shown or echoed. The server decides on each request from the stored setting; the screen follows what the
   suggestion carries (an amount without the profit means the setting is on).

## Consequences

**Better:** the owner chooses whether the person closing the till sees and sets the savings, as the shop did before; the cap and
the audit still apply; a business that leaves it off is unaffected.

**Worse:** with it on, a salesperson can work out the day's profit less expenses from the suggestion (twice it at the default rate)
or from the cap, and an amount equal to the suggestion is no longer a secret. The setting says so in plain words.

**Watch for:** an owner who turns it on and later off: the amounts disappear again from the screens, but what a salesperson saw
while it was on cannot be taken back. A request to show the savings report or the expected-to-bank figures to salespeople is a
further relaxation of ADR-022 decision 13 and needs its own decision.
