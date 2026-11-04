# ADR-115: Salespeople may see and change the savings amount when the owner allows it

## Status

Accepted (issue #768, 2 Nov 2026, owner decision D124; FR-RET-18, FR-RET-19). Relaxes ADR-022 decision 13 for the savings amount only, and only
when the Owner turns the new setting on; it answers ADR-022 open question 8 for one figure. ADR-022 decision 12 (the audit
carries flags, never the amount) and ADR-080 (the cap) are unchanged. No migration. Renumbered at merge if needed.
Amended by issue #799 (3 Nov 2026): the setting reaches sales staff, who hold `retail.sale.create` but not
`retail.savings.record` or the till, and shows the whole month's amounts (decision 8; decision 3 changed). No migration.
Amended by issue #827 (3 Nov 2026), as the owner's old app did: every month's records, not only this month's, show their
amounts to whoever may record savings or sell at the branch, and sellers see them grouped by shop, year, month and person with
the sum at each level (decisions 3 and 8 changed, decision 10 added). No migration.
Amended by ADR-120 (issue #811): sales staff save today's savings; an earlier day of the month is the owner's, with a reason.
Amended by issue #814 (4 Nov 2026), as the owner's old app did: while the setting is on, whoever may bank sees on Banking the
day's savings and the cash expected less them, and the amount is filled with it; cash purchases stay hidden (decision 5
changed, decision 11 added; FR-RET-21). No migration.

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
   reader holding `retail.cashbook.read` alone, keep the amounts hidden. Turned off and on again, of the records of earlier
   months only those made from the new moment show. **Changed by issue #799:** besides those, while the setting is on, every record dated in the current business
   month shows its amount and `overwritten`, whenever it was made, to a caller who holds `retail.savings.record` or
   `retail.sale.create` for its branch (the owner asked for the whole month). A record of an earlier month made before the
   moment, and a reader holding `retail.cashbook.read` alone, still keep the amounts hidden. **Changed again by issue #827**
   (as the owner's old app did): while the setting is on, every record of any month, whenever it was made, shows its amount
   and `overwritten` to a caller who holds `retail.savings.record` or `retail.sale.create` for its branch; the this-month rule
   and the made-while-on rule above no longer limit it. A reader holding `retail.cashbook.read` alone still sees no amount, and
   with the setting off every amount is hidden again.
4. **The same rule for everyone to change the amount.** With the setting on, changing the suggested amount before the day is
   saved needs `retail.savings.record`, for a caller with profit read as much as for one without; no caller gets more by holding
   less. Replacing a saved amount (the day has a voided record, so the new amount takes the place of a saved one) needs
   `retail.savings.overwrite` for everyone, and so does any change while the setting is off. The suggestion carries
   `may_change_amount`, so the screen follows the server instead of guessing. **Since issue #799** the right to record a
   branch's savings is `retail.savings.record` through the till's routes, or `retail.sale.create` through the sales staff
   routes (decision 8); whichever route is used, changing the suggestion before the day is saved needs no more than that
   right, and replacing a saved amount needs `retail.savings.overwrite`.
5. **Still hidden**, setting on or off: the record's `suggested_minor` and every other profit-gated figure of ADR-022 decision 13
   (the savings report, the net `expected_minor` of the expected-to-bank answer, cash purchases). The screen shows no profit, no
   expenses and no cap to that caller. Since issue #814 the expected-to-bank answer carries the day's savings while the setting
   is on (decision 11).
6. **The audit.** The create's audit row keeps the flags of ADR-022 decision 12 (`overwritten`, `default_applied`, the reason if
   given) and adds `salespeople_setting: true` when the record was made while the setting was on; the actor is on the row. No
   amount is written, so a reader of the audit log learns no more than before.
7. **When it is off**, nothing changes: an amount from such a caller is 422 `amount_requires_profit_access` for every value, and
   no amount is shown or echoed. The server reads the setting once per request.
8. **Sales staff (issue #799).** In the pilot shop the sales people take the money out and put it aside, but the Sales role holds
   neither `retail.savings.record` nor `retail.till.close` (ADR-055), so the setting did nothing for them. While the setting is
   on, a caller who may sell at a branch (`retail.sale.create` there) may open that branch's savings, see the suggested amount,
   change it and save it, and see this month's savings records for the branches where they may sell, with their amounts. They
   use their own routes, `GET /retail/savings/sales-staff/suggestion`, `POST /retail/savings/sales-staff` and
   `GET /retail/savings/sales-staff`, declared with `retail.sale.create`, so the till's routes and their checks are untouched.
   With the setting off these routes answer 403 `permission_denied`, the answer a seller already gets from the till's routes, so
   nothing changes for sales staff. With it on, after the branch is resolved by `retail.sale.create`, the checks run in the same
   order as on the till's route (token, amount, lock, token match, `may_change_amount`, `savings_exists`, then the cap), the same
   figures are sent (never `available_minor`, `daily_profit_minor` or `day_expenses_minor`), and replacing a saved amount still
   needs `retail.savings.overwrite`. The owner gave them the month, so the sales staff routes take only days of the current
   business month: an earlier `date` or `business_date` is 422 `outside_this_month` ("Savings can be opened and saved for days
   of this month only."), so no earlier day's suggestion reaches them and nothing they save falls outside what they see; the
   till keeps every day. The list offers no void; since issue #827 it no longer moves an earlier `from` to the first day of the
   business month, so a seller reads every month's records (decision 10), while opening and saving stay this month's. The
   switch is checked before an idempotent replay, so a key replayed after the Owner turned it off is 403. `/me` carries `salespeople_see_savings_amount`, and the PWA shows the Savings screen in Money to a seller without the
   till only while it is true. Whoever holds the till's savings permissions keeps the till's routes.
9. **A saved day shows only what was saved (issue #799, the owner's request, as in the shop's old spreadsheet app).** Once a
   branch's savings for a day are recorded (an active record), the suggestion for that day is no longer sent to anyone, the
   Owner included, setting on or off: the suggestion answer leaves out `suggested_minor` and carries `may_change_amount` false,
   and a 409 `suggestion_changed` for that day leaves out `suggested_minor` (the refusal order is unchanged). An active savings
   record carries no `suggested_minor` either, in the create and void answers, the Savings records rows and the savings report,
   for the Owner as for everyone; a voided record no longer saves its day and keeps it for `retail.profit.read`. The Savings
   screen then shows the note that the day is saved and the saved amount (`existing_amount_minor`, where the caller may see
   it), with no suggestion and no amount field. Voiding the record brings the suggestion back. The day's profit, expenses and
   available amount still reach a caller with `retail.profit.read`, and the ADR-080 note "Saved UGX X; profit less expenses is
   now UGX Y" stays. The suggestion is shown on no other screen: Home, Money and the cash summary never carried it.
10. **Every month, grouped with totals, for sellers (issue #827, as the owner's old app did).** The old app's "Daily savings
    records" had no filter: everyone saw every month's records, grouped by shop, year, month and the person who saved, with
    the sum of the amount saved at each level. While the setting is on, `GET /retail/savings/sales-staff` returns records of
    every month (still only the branches where the caller may sell; a named branch outside them stays 404 `unknown_branch`),
    and `GET /retail/savings/sales-staff/groups` returns the active records of those branches in that shape:
    `{total_minor, shops: [{branch_id, total_minor, years: [{year, total_minor, months: [{month, total_minor, people:
    [{user_id, name, total_minor, records: [{id, business_date, amount_minor}]}]}]}]}]}`, newest day first, with optional
    `branch_id`, `from` and `to`. It carries amounts saved only: no profit, no suggestion, no sales, no voided record. The
    Savings screen shows a seller that view below the form, the newest year open. Off, the route is 403 `permission_denied`.
11. **Banking shows the savings line (issue #814, as the owner's old app did).** The old app's Banked form showed every seller
    the day's sales less the savings. While the setting is on, `GET /retail/bankings/expected` sends a caller without
    `retail.profit.read` who may bank at the branch (`retail.banking.record`, the Till role) `savings_minor` and
    `savings_voids_minor`, and `expected_after_savings_minor`: `cash_expected_minor` less the savings plus their voids. It is
    deliberately not net of cash purchases, so `cash_purchases_minor` and the net `expected_minor` stay with `retail.profit.read`
    (ADR-022 decision 13), as do the stored `expected_minor`, `difference_minor`, `flag` and `warnings` of a save and every
    figure of the banking report. A caller with `retail.profit.read` is not sent `expected_after_savings_minor`; their figure is
    `expected_minor`. `BankingService.expected` decides it on the server, reading the setting once per request; the Banking form
    shows "Savings set aside" and "Expected to bank" when the answer carries them and prefills the amount banked with
    `expected_after_savings_minor` less what is already banked that day. The answer is the read kept on the phone for offline
    use (ADR-116), so the form shows the same lines offline. With the setting off the answer is as before. The savings were
    already visible to these people on Savings while the setting is on, so this shows nothing the owner has not already allowed.

## Consequences

**Better:** the owner chooses whether the person closing the till sees and sets the savings, as the shop did before; no one but
the Owner can make that choice; the cap and the audit still apply; a business that leaves it off is
unaffected.

**Worse:** with it on, a salesperson can work out the day's profit from the savings amount: the suggestion is a share of the
day's profit less its expenses (half at the default rate), and a salesperson knows or records the day's expenses. By trying
amounts, a salesperson can also find the most that may be saved, which is the day's profit less its expenses: an amount above
it is refused and one at or below it is accepted. Leaving the cap out of the answers does not prevent either. The owner
accepted this (owner decision D124). The setting is off by default, only the Owner turns it on, and its help text says plainly that a
salesperson who sees the savings amount can work out the day's profit.

With issue #799 the setting reaches every seller of the branch, not only the people given the till, and turning it on shows
the amounts of the current month's records made before it, so a salesperson can work out the profit of every day of the month
so far; the help text says so. The owner asked for this. With issue #827 that reaches every month, not only the current
one: a salesperson can work out the profit of every saved day the business has. The owner asked for this too, as the old app
showed it; the help text says so.

**Watch for:** an owner who turns it on and later off: the amounts disappear again from the screens, but what a salesperson saw
while it was on cannot be taken back. Turning it on again shows every month's records again (issue #827). A request to show
the savings report, the cash purchases or the net expected-to-bank figure to salespeople is a further relaxation of ADR-022
decision 13 and needs its own decision; the savings line on Banking is decision 11.
