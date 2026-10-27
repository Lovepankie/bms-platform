# ADR-099: A selling pack has its own price, and each pack says what it is for

## Status

Proposed (owner decision D23 of 23 Oct 2026, issue #624; docs/specs/units-packs-and-costing.md section 3 and section 9 step 3),
accepted on merge. Amends the ADR-033 amendment for units of measure (issue #194), decision 2 (a pack price is `sell x factor`)
and decision 5 (a pack is a unit and a factor), and the ADR-033 amendment for price lists (issue #195), decision 1, where a line
with no list price takes the retail price of its unit, `sell x factor` for a pack. Keeps ADR-082 (a buying pack's size per
delivery; a selling pack's size stays fixed) and ADR-079 (profit from the average cost per piece). Migration `V110`; renumbered
at merge if needed.

## Context

Until now a pack sold at the item's piece price times its size, unless a price list priced it, and a walk-in sale has no price
list. A shop sells a carton of 24 for less than 24 pieces, a dozen for less than 12, and a half carton at a price of its own: HATS
sells cable by the box and by the metre at different prices and could not price them. The owner decided (D23) that selling packs
have a fixed size and their own price, that walk-in sales use those prices, and that price lists still win over them. A half
carton or a dozen is just another pack. The same pack table also holds packs that are only bought (a supplier's box of 10 the shop
never sells whole) and packs that are only sold (a half carton nobody delivers).

## Decision

1. **A pack says what it is for.** `retail_product_units.purpose` is `buy`, `sell` or `both`, `both` by default and for every
   pack that exists. A pack only for buying is not offered on Sell, a quotation or an order and a sale line in it is refused (422
   `unknown_pack_unit`, "This item is not sold in that unit"), as for a pack switched off; offline, it is a `pack_inactive` clash
   and the sale stands, as ADR-067 does for a pack switched off. A price list item on a pack only for buying is refused
   with the same code. A pack only for selling is not offered on Buy stock, a purchase
   order or goods received and a line in it is refused with the same code ("not bought in that unit"). Stock counts, moves,
   returns and barcodes take any active pack, as before. An order made before the purpose changed still completes: goods
   received against a purchase order line skip the purpose check, and a customer order's lines are delivered as agreed (the
   sale's agreed lines skip it, as they skip a pack switched off). The purpose may change at any time; only new lines follow it.
   The three values are one constant on the server (`RetailCatalogue.PACK_PURPOSES`).
2. **A selling pack has its own price.** `retail_product_units.sell_minor` is the selling price of one pack, in minor units, or
   null. Null is the price it had until now, the piece price times the size rounded half up once, which the API sends as
   `suggested_sell_minor` and Edit item shows as the suggestion. A pack only for buying has no price (a check in the database, 422
   `pack_not_sold` in the API), and making a priced pack buy only drops its price, audited.
3. **Walk-in sales use it; price lists still win.** The price a line takes when no list prices it (the `retail` source of the
   price resolution, `SalesService.walkInPrice`) is the pack's own price, else the piece price times the size. A sale, the Sell
   picker, a scanned pack barcode, a quotation, a customer order and `/retail/prices/resolve` all go through it. A price list for
   the sale, the customer or the group, and its quantity breaks, are found first exactly as before, and a strict list still refuses.
   A typed price that differs is an override with its reason, as before.
4. **Below cost is judged per pack.** The sale's floor is unchanged: a pack line is below cost when its total is not above its
   pieces times the branch's average cost per piece (ADR-079), judged on the line. Edit item tells the owner before a sale: a selling
   pack is marked `below_cost` when its price is not above its size times the item's average cost per piece, the branches'
   averages weighted by what each holds, or the last paid cost while no branch holds any. The flag goes only to a caller holding
   `retail.profit.read`; saving such a price is allowed, since the owner decides, and selling at it needs `retail.price.below_cost`.
5. **A pack's price is a price.** Setting or dropping a pack's own price needs `retail.price.edit` as well as the route's
   `retail.catalogue.manage` (403 `permission_denied`). So does any change that moves what a pack with its own price sells for: a
   new size (the same price for more or fewer pieces), on Edit item or by ticking Use 30 from now on on Buy stock or goods
   received (ADR-082 decision 2: without the permission the delivery still saves at its own size and only "from now on" is left
   out; the screens say so before sending, the line of the response says so with `size_kept` and a plain sentence, and the skip
   is audited as `retail.product_unit.size_kept`), or making it only for buying (which drops the price). Every pack carries
   `has_own_price` for every caller, the amount only for one who may see prices, so the screens know without the amount. The size of a pack
   without its own price, making a pack only for selling, and switching it off or on need only `retail.catalogue.manage`. Every change is audited as
   `retail.product_unit.created` or `.updated` with the old and new `purpose` and `sell_minor`. No new permission.
6. **Profit stays per piece.** A sale line keeps what was charged and, as before, the average cost per piece at the moment of sale;
   `qty` and `unit_price_minor` stay per piece. Every report adds lines up, so their meaning does not change.
7. **A new size of an item.** Packs copied to a new size (ADR-051) keep their purpose, and their own prices only when the new size
   sells at the same piece price; otherwise the copy sells at its suggested price until the owner sets one.

## Consequences

**Better:** a shop prices a carton, a dozen and a half carton as it sells them, at the till, without a price list; a supplier's
box that is never sold whole no longer appears on Sell; Edit item shows every pack, its size and its price in one small table.

**Worse:** a pack's price does not follow the piece price: after a piece price rise the owner must raise the pack prices too (the
table shows the suggestion and Below cost helps). A pack price has no dated history of its own, only the audit log, so an offline
sale charged at a pack price that changed while the phone was away is read as a typed price, not as the price the phone had saved.

A new index, `retail_stock_balances_product_held` on `(tenant_id, product_id) WHERE qty > 0`, serves the item's average for
the Below cost mark; its build in `V110` blocks stock writes for its short duration.

**Watch for:** anything new that prices a pack line must use `walkInPrice` after the price list, never `sell x factor`; anything
new that offers packs must offer only the ones whose purpose fits.
