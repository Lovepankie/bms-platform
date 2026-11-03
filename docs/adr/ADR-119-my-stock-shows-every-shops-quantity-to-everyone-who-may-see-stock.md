# ADR-119: My stock shows every shop's quantity to everyone who may see stock

## Status

Accepted (issue #807, asked for by the business owner on 3 Nov 2026; FR-RET-03, FR-RET-84). Builds on issue #728 (the "0 here. 456 at Test Store" note) and the
ADR-017 branch scope, which it relaxes for stock quantities only. No migration. Renumbered at merge if needed.

## Context

A business owner with more than one shop sent a picture of a My stock card: "In stock: 0 pieces. 0 here. 1 at another shop.
[Switch to another shop]". Two things were wrong with it for a shop floor. Seeing another shop's count moved the person to that
shop, which is not what a seller telling a customer "we have one at the other shop" wants. And the note came only when the shop
in use had none, named only the one other shop holding the most, and only to someone whose role covered that shop, so a seller
scoped to one shop never saw it at all. The spreadsheet catalogue the business used before showed every shop's count of every
item to everyone at once.

ADR-017 scopes every permission to branches. `retail.stock.read` at one shop reads that shop's stock and nothing else; the
all-branches list (#144) returns only the columns the caller may read. That is right for cost, value and movements, which show
how a shop is doing. A plain count of an item at another shop of the same business shows very little, and the owner asked for it.

## Decision

1. **Every row of `GET /retail/stock` carries `shops`**: one entry per active branch of the business that keeps goods
   (`stocks_goods`), the branch listed first with `here: true` (always, even when it keeps no goods, so its own quantity is
   never dropped), then the others in the order of the branch list (head office
   first). Each entry is `{branch_id, name, qty, negative, here}` and nothing else: no cost, no average buying price, no value,
   no movement. A shop with no balance row shows `0.000`; a balance below zero shows as it is, with `negative: true`.
2. **Who gets it.** Whoever may read stock at the branch listed (`retail.stock.read` in its scope, as the route already
   checks) gets every shop's quantity, whatever their scope at the other shops. The cost fields of the row keep their own rule
   (`retail.profit.read` at the branch), and the selling price its own (ADR-055). The all-branches list, the grouped view, the
   valuation and every report keep the ADR-017 scope unchanged.
3. **One request, a fixed number of reads per page.** The quantities of the whole page come from one query on
   `retail_stock_balances` for the page's products and the listed shops (`StockRepository.balances`, the read the all-branches
   list already uses), never one per row or per shop; with the branch list and the settings group, also read once per page,
   the cost of a page does not grow with its rows or shops. An empty page reads no balances (`RetailAllShopsStockReadsIT` counts the reads). The page size and the cursor are as before. The screen no longer reads the all-branches list for the "0 here"
   note when the rows carry `shops`.
4. **The owner's switch.** `other_shops_stock` in the retail settings group (`GET` and `PUT /retail/settings/capabilities`,
   `core.settings.manage`, audited as `retail.capabilities.changed`), true unless switched off. It is not a capability and is
   never listed in `/me`. A value that cannot be read as a boolean is 400 (the field is bound as a boolean, so the strings "true" and
   "false" are taken as such). The list is left out whenever fewer than two shops would be listed (the shop listed and the
   other active shops that keep goods), whatever the switch says, so the default is in effect "on for a business with more
   than one shop". A closed shop is never listed and its stock is not read as a shop in use (422 `unknown_branch`). Off, the rows carry no `shops` and My stock is as before issue #807,
   with the "0 here" note still cut to the shops the person may read.
5. **No Switch button.** The "Switch to" button of issue #728 is removed from My stock and the item's page. Changing the shop in
   use stays where it was: the shop label above every page.
6. **The screen.** Each card (and the In stock cell on a wide screen) lists the shops: "In stock here: 0 pieces" first and
   larger, then "Another shop: 1 piece", in the item's packs and pieces when it has a standard pack, a balance below zero with
   its minus sign and the words "Below zero". Quantities in tabular figures; no stripe, no colour alone. The category chips,
   the search, the filters and the counts stay those of the shop in use. The rows are kept for reading offline as every My stock
   read is (ADR-116, query key `['retail', 'stock', ...]`), so the list shows from the last loaded copy with no network.

## Consequences

**Better:** a seller at one shop answers "do we have it anywhere?" without moving shop or asking someone else; the owner sees
the whole business on the page staff use most; one request a page, no extra read for the note.

**Worse:** a person scoped to one shop learns the counts of the other shops, which before only a wider role could see. It is
quantities only, the owner can switch it off, and the move is in the audit of the settings. A business that wants counts kept
per shop must switch it off.

**Not changed:** the branch scope of every other read and of every write; who may change the shop in use; the all-branches
list and its columns.

## Alternatives considered

- **Show the all-branches list to everyone.** It carries cost to a reader of profit and is a second page; the owner asked for
  the counts on the card people already use.
- **A new route for the counts.** A second request per page on a cheap phone, and a second copy to keep offline; the row of the
  existing list is the one place the screen already reads.
- **A new permission instead of a switch.** Roles are not editable by a business yet; the owner's question is one on or off for
  the whole business.
