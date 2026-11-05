# ADR-126: A delivery shows what it should sell for to the owner

## Status

Accepted (issue #821, from the owner's old app compared screen by screen with BMS on 3 November 2026; FR-RET-91). Builds on
FR-RET-06 (restocks), FR-RET-10 and ADR-020 decision 10 (cost and profit only with `retail.profit.read`), ADR-017 (a
permission's branch scope), ADR-055 (two of its rules: a buyer without `retail.profit.read` reads only their own restocks, and
an item's selling price is one price for every shop, set only by whoever may change prices), ADR-044 (a voided restock) and
ADR-116 (the reads kept on the phone). No migration.

## Context

The owner's old delivery form showed, under its lines, the total paid, what the goods should sell for and the expected
profit, so the owner saw at once whether a delivery was worth it. BMS showed only the total cost on Buy stock and on Past
purchases. The figures tell the margin of every line, so they are the owner's, as cost already is.

## Decision

1. **The worth of a delivery.** What the goods should sell for is, over the lines, the quantity in the item's own (base) unit
   times the selling price the line set, else the item's current selling price, rounded half up once per line as every
   amount of a line is. The expected profit is that less the total cost. Nothing is stored: the line already keeps the price
   it set (`retail_purchase_lines.sell_minor`) and the item keeps its current one (`retail_products.sell_minor`). "The item's
   current price for that shop" is that one price: BMS has no selling price per shop (ADR-055), and a price list belongs to
   a customer or a group, not a shop, so a line that stocked two shops is worth its quantity in both at the one price. A pack
   line counts its quantity in the item's own unit, with the size each pack held in that delivery (ADR-082), so 2 boxes that
   held 10 count as 20.
2. **Only a delivery that stands.** A voided restock (ADR-044) had its stock taken back out and will never sell, so it has
   no worth: both fields are absent from it, from the answer of its void on, and Past purchases shows its total alone, as
   before. A restock the caller sees no line of has none either. When an item on a line has no selling price yet (a price
   of 0 on the line, or no price of its own and the item's price 0), the worth is not known: both fields are left out. An
   item that was deleted or that the catalogue does not know counts the same, as unpriced, when its line sets no price of
   its own (issue #852): its current price is read as 0, never guessed. In all of these `has_unpriced_items` is true for whoever may read profit, and the screens say "Some items have no selling price yet."
   under the total cost, on the form as soon as such a line is on it.
3. **The server decides who sees it.** Every restock response (the list, one restock, and the answers of create, void and
   replace) carries `sell_worth_minor` and `expected_profit_minor` only for a caller holding `retail.profit.read` at every
   branch the caller sees the restock stock (ADR-017), and leaves both out for anyone else, never null and never only hidden
   in the screen. They are added after the idempotency store, so the stored answer has neither and a replay under the same
   key shows them only to someone who may read them. For a caller whose scope hides part of a restock, the worth covers the
   part they see, as the total does. The routes read under the caller's own principal; no job or report reuses them, and a caller with no principal
   (an internal one) gets neither field rather than an error (`PurchasingService.showsWorth`).
4. **The form works it out as the lines change.** Buy stock draws the total cost, Should sell for and Expected profit (an
   Expected loss when it should sell for less) in the total's box, from the lines on the form and the item's price the form
   already holds, with the same rounding as the server. It does so only for someone who reads profit at every branch on the
   form; anyone else sees the total cost as before and nothing is worked out. Past purchases draws the three figures the
   server sent under each row's lines, and the total once in them. Amounts go through the one money formatter in tabular
   figures.
5. **Offline.** The form needs no read of its own: the items on the draft carry their prices. Past purchases joins the reads
   kept on the phone (ADR-116), so the rows and, for whoever may see them, their three figures open with no network. This
   widens what ADR-116 keeps: a buyer's own past purchases, with the totals they already read online, are now on the phone
   too. The copy is per person and business, carries what the person was allowed to see when it was made and is thrown away
   when that changes or at sign-out, as every kept read is. A price edit refreshes every retail read (`['retail']`), so a
   row whose worth follows the item's price is read again once there is a network.

## Consequences

**Better:** the owner has the old form's three figures back on the screen they buy stock on and on every past delivery,
without a report. A buyer without the permission sees nothing new, in the API or the screen.

**Worse:** a line that set no price of its own follows the item's price as it is today, so an old delivery's worth moves when
the price changes later; the line's own price, which the form always sends, does not move. Each read of the list looks up the
current price of those lines' items, once per item per page.

**Watch for:** if the owner wants the price at the day of the delivery for old lines without a price, read it from the price
history (`sellMinorAt`) instead of the current price. If a selling price per shop is ever added, the worth takes the price of
each branch the line stocked.
