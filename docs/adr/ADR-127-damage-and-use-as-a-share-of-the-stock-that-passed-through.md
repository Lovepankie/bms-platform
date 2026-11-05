# ADR-127: Damage and use as a share of the stock that passed through, for the owner

## Status

Accepted (issue #822, from the owner's old app compared screen by screen with BMS on 3 November 2026; FR-RET-92). Builds on
FR-RET-07 (usage and damage), the stock health report of issue #149, ADR-055 (who sees cost and profit), ADR-123 (an undone
usage or damage entry counts in no total) and ADR-116 (the figures last loaded kept on the phone). No migration.

## Context

The owner's old app showed each damage entry as a share of the item's stock, so the owner saw losses growing. Stock health
already counts the usage and damage entries per shop in a date range, but only as a number of entries and, for a profit
reader, their cost. A count of entries says nothing about whether two damaged bags are a lot: of 10 bags it is a fifth, of 500
it is nothing. The share needs a base, and the base has to be plain enough to say in one line on the screen.

## Decision

1. **The base is the stock that passed through.** For an item in a shop and a period: the stock at the start of the first day
   (every stock movement before it added up; none when that is below zero) plus what came in during the period: deliveries
   (`purchase`), stock moved in (`transfer_in`) and a starting stock first set in it (`opening`, `legacy_balance` above zero).
   A movement that reverses another, and one a void dated on or before the period's last day has reversed, is left out, so a
   delivery or transfer voided in the period never counts; one voided after it still does (issue #854).
   A stock count, a customer return and the stock an undo puts back are not "came in" and are left out. The screen says it in
   one line: "Out of the stock that passed through: the stock at the start plus what came in."
2. **Damage and use separately, from the entries.** The damaged and the used quantities are the lines of the usage and damage
   entries dated in the period (`occurred_on`), in the item's smallest unit, each kind on its own. An entry undone (ADR-123,
   `retail_usage_undos`) is left out whenever it was undone, exactly as the stock health and daily profit usage readers leave it
   out.
3. **The share.** Each is a whole percent of the base, half up. A base of zero shows no share (absent in the answer, "no stock
   came in" on the screen), never a division by zero or a made up 100 %. A share may go above 100 when more went out than the
   books show coming in (an earlier count added stock); it is shown as it is.
4. **Per item and per shop.** Each shop lists its items with something damaged or used, the larger of the two shares first,
   then by code. The shop total adds every item that passed through the shop, damaged or not, so the total share is out of all
   the shop's stock; each item counts in its own smallest unit, so the total is a rough measure across items of different
   units and the item rows are the exact one. Shops are ordered the same way.
5. **The owner only, on the server.** `GET /retail/reports/stock-health/damage-share` needs `retail.profit.read`; anyone else is
   refused (403) and gets no figures. It answers only the shops where the caller holds it; a `branch_id` outside them, or of
   another business, is 404 `unknown_branch`. The screen asks only when the person holds the permission at the shop chosen (any shop
   when all are chosen), so a manager with it at one shop who looks at another sees no section rather than a refusal (issue
   #854), but the server is the guard. The answer carries quantities and shares only, no cost, price or margin.
6. **The period picker, this month by default.** The section has its own period picker (Today, This week, This month, Custom),
   on This month; the server also defaults to the first of this month to today, at most 366 days.
7. **Offline.** The read is kept on the phone (ADR-116, query key `damage-share`) for a person who may see it; the kept copy goes
   when their permissions change, as every kept read does.

## Consequences

**Better:** the owner sees which items lose the most of what came in, in words and numbers, with the quantities beside each
percent, without a spreadsheet.

**A closed period stays as it was (issue #854).** The void check is bounded by the period's last day
(`r.business_date <= :to`): a delivery or transfer that came in during the period and was voided after it still counts as
come in, because on the period's last day the books held it. Without the bound a void today would rewrite last month's share.
Read the period again after its last day and it gives the same base; a void inside the period still takes the delivery out.

**Worse:** the shop total adds kilos to pieces; the hint and the item rows make that clear. The opening stock reads every
movement before the period, which grows with the shop's history; the query is bounded by the caller's shops and is one read a
screen.

**Watch for:** if the owner wants the share by value instead of quantity, the same base at the average cost gives it; that
would be a cost figure and stays with `retail.profit.read`.
