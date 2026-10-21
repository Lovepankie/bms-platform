# ADR-051: An item with sizes and colours is a group of products

## Status

Proposed (2026-10-21, issue #384). Builds on ADR-020 (stock as append-only movements per product), ADR-033 (pack sizes and price lists per
product) and ADR-044 (one product name per unit). The migration is `V69` (the next free number after `V68` of PR #380; renamed at merge if
another migration lands first). Item photos are the second slice of the same issue and are not decided here.

## Context

A baby clothes shop sells one romper design in age sizes 0-3m, 3-6m and 6-12m and in several prints. Each size and print is counted, bought
and sold on its own: the owner needs to know that 0-3m Blue is sold out while 6-12m Pink is not, and a bigger size may cost more. An
electrical shop has the same shape (a cable in 1.5mm and 2.5mm, a socket in white and black) and so does a wholesaler. Today the owner has
two bad choices: one product per size and colour, typed by hand, with nothing tying them together in the lists and reports, or one product
for the design, which hides which size is on the shelf.

Every stock path in retail is keyed on a product: the balance, the movements, sale lines, restock lines, transfer lines, stock-take
lines, return lines, price list items, pack sizes, purchase orders, goods receipts, landed costs and the reports read
`retail_products.id`. A variant id added next to the product id on each of those tables would touch more than twenty tables, every
movement and every report, and the many rows already on staging would need a default variant copied in and every foreign key moved to it.

## Decision

- **A variant is a product.** The romper in 0-3m Blue is one row of `retail_products` with its own code (the SKU), its own cost and
  selling price, its own balances, movements and history. Every path that already works per product (sale, restock, transfer, stock-take,
  usage and damage, return, price lists, pack sizes, purchase orders, goods receipts, valuation, daily profit) therefore works per variant
  with no change and no new risk.
- **The item is a new table, `retail_product_families`** (name, base code, category, unit), with forced row-level security. A variant has
  four new nullable columns on `retail_products`: `family_id`, `variant_size`, `variant_colour` (one of them may be empty) and
  `variant_sort` (its place in the item's list, in the order the sizes and colours were given). A check keeps the four all empty (an item
  without variants) or a complete variant, and a unique index allows one variant per size and colour in an item, ignoring case.
- **An item without variants is its own single, default variant.** Existing products keep the four columns empty: same id, same code,
  same balance, same history. Nothing is copied, nothing is rewritten and nothing reads differently, so the migration is expand only and
  the previous release runs on the new schema. A product response carries a `variant` object only for a variant.
- **Two option kinds: size and colour (or print).** They cover clothes, cables and sockets in plain words. Packs of a wholesaler stay
  pack sizes (ADR-033). A third kind can be added later as another nullable column without moving any data.
- **The variant's name is the item's name with its size and colour**, "Baby romper (0-3m, Blue)", so every list, receipt, document and
  report that already prints a product's description prints the variant. It passes the one-name-per-unit rule (ADR-044). A variant's
  name, category and unit follow its item: they change on the item (a rename regenerates every variant's description, a new category
  moves every variant), and a change on a single variant is refused with 422 `variant_name_follows_item` or `variant_follows_item`. A
  variant's own size, colour, code and on-sale flag change on the product as before.
- **Quick creation.** `POST /retail/product-families` takes the sizes and colours once and makes every combination, sizes first in the
  order given, each coded from the base code (`ROMP-03M-BLUE`), at the item's prices unless the request names a variant's own code or
  prices. More sizes later skip a combination the item has (`POST /retail/product-families/{id}/variants`). An item without variants is
  given sizes in place (`POST /retail/products/{id}/family`): it becomes the variant of the size and colour its stock is, keeping its id,
  code, prices, stock and history, and the other combinations are made at its prices (switched off when it is). Items kept apart
  until now, one per size, are joined as the sizes of one item (`POST /retail/product-families/join`, or `.../{id}/join` for one that
  exists): each keeps its id, code, prices, stock and history; its name becomes the item's with its size and colour, its category
  the item's; they must share one unit. A size added to an item whose every size is switched off starts switched off. A new size made from an item (more sizes added to it, or an item given sizes) takes
  the template variant's active pack sizes (while the units capability is on) and its price list prices that are in force or coming,
  at the same price, on the rules of a list price set by hand (issue #395): only while the price lists capability is on and the caller
  also holds `retail.pricelist.manage` (a catalogue-only role adds the size without list prices and always gets the same
  `price_list_permission` answer, whether the size has list prices or not and whatever the capability, so it learns nothing of the
  lists); only in the
  size's own unit or a pack copied with it; only to a size that keeps the template's cost and selling price exactly (a size at its
  own prices takes none, so a dearer size never sells at the old wholesale price); never below the new size's cost (`below_cost`, told only to a caller with `retail.profit.read`; `not_copied` otherwise); each copy
  audited as `retail.price_list_item.created`. The answer's `terms_copied` lists every list price not copied and why. Joining into an
  item that exists takes only the items (a name, base code, category or duplicate reason is refused with 422) and clears each joined
  product's `duplicate_reason`, which the audit keeps in `before` (issue #395). **An item never has more than 100 sizes and colours:**
  creating, adding sizes, giving an item sizes and joining all count what the item has already (422 `too_many_variants`); a size
  or colour is one line of at most 30 characters after trimming.
- **Reports roll up to the item.** The sales and restock summaries group an `item` as the item with sizes (all its variants together)
  or the item without; a new `variant` level splits an item by size and colour, offered in `available_levels` only to a tenant that has
  an item with sizes. Wherever an item id is a filter (`product_id` of the sales and restock lists, `item_id` of the summaries), the id
  of an item with sizes stands for all its variants; ids are unique across both tables. The item's own page sums the stock of its
  variants per branch and in all, over the branches the caller may read. The margin report (items by profit, items under the target)
  and the sales analysis (top items by sales and by quantity) count an item with sizes once, all its sizes together, under its base
  code (issue #395); price change impact, the slow movers and no-sales lists and stock health stay per product, which is per
  variant, each named with its size and colour (stock health is being changed by other work).
- **One name per item (ADR-044 one level up).** An item's name is kept once per unit against the other items with sizes and the
  items without (the same key, the same 422 `duplicate_product_name` naming the one that exists, the same add-anyway with a reason
  for an administrator, kept as `retail_product_families.duplicate_reason`), and an item without sizes cannot take the name of an
  item with sizes of its unit either.
- **No new permission.** Making and changing items is `retail.catalogue.manage`, reading them `retail.stock.read`; cost is shown only
  with `retail.profit.read`, as for any product.

## Consequences

**Better:**
- Every stock and money path is correct per variant by construction, because it is the path already proven per product; no movement,
  balance or ledger table changes.
- Existing data needs no backfill and no default rows; the migration is a new table, four nullable columns, a check and an index.
- The owner chooses the sizes and colours once, and the codes and prices are filled in.

**Worse:**
- A variant's description duplicates its item's name; a rename of the item rewrites the descriptions of its variants (inside one
  transaction, under the item's row lock).
- Price change impact and stock health list variants, not items; an owner who wants the item total reads the sales and restock summaries or the item's
  page.

**Watch for:**
- A query that groups by `product_id` and is meant to read "per item" must group by `coalesce(family_id, product_id)`.
- The 100 sizes and colours an item may have and the 7 characters of each size and colour in a generated code; an owner can type a code of their own.
