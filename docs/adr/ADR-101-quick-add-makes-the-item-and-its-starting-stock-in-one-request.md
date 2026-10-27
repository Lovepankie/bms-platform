# ADR-101: Quick add makes the item and its starting stock in one request

## Status

Accepted (issue #632, owner decision D67 of 28 Oct 2026).

## Context

A tester needed about nine steps on four screens to add one item and its stock: a category, then the item, its unit and
prices, then Count stock, find the item, type the count, review and commit. They gave up confused, and the owner said
AppSheet was still easier. The owner asked for one form and one tap: the name, the selling price, the buying price
(optional), how many there are here now, where, an optional category and unit, and an optional photo.

Two existing calls already do the work: `POST /retail/products` makes the item (the code from the category, ADR-081; the name
rule, ADR-044) and a one-line stock count (`POST /retail/stocktakes` then its commit, FR-RET-08) records the stock. Sell's
Add it here (issue #615) chains them from the phone, so a dropped connection between the calls leaves an item with no stock
and says so. The item needs a category and a unit; a new shop may have neither.

## Decision

1. `POST /api/v1/retail/quick-add` makes the item and adds the quantity typed to its stock at the chosen branch, in one
   transaction. The item is made through the catalogue's own create (now also reached through `RetailCatalogue.createItem`).
   It is a money-moving route: it takes an `Idempotency-Key`.
2. The stock goes in as one `opening` movement of its own source type, `retail.quick_add`, at the branch's average (for a new
   item, the buying price typed), under the balance row's lock, and posts the same journal as the import's opening stock
   (`retail-import`, ADR-020): debit inventory, credit opening balance equity (3030). The import's own movements are of kind
   `legacy_balance` and historical; the kind `opening`, allowed since V11, had no writer before Quick add. It is not a stock
   count: a count's difference posts to lost or damaged stock, so a starting quantity would show as a gain and as profit. The
   daily profit and stock health read lost or damaged stock from stock-take adjustments by source, so they never see it, and
   the stock history's label for the kind `opening` (Opening stock, already in the app) names it (`RetailQuickAddIT`,
   `stock-correct.dom.test.tsx`). A later supplier credit or landed cost counts it as a later layer of stock, as any stock
   that came in. No other item moves.
3. The quantity is always added, never set: for a new item it is how many there are, and for an item already in the list
   (`product_id`, chosen when the name is refused as a repeat) it is added to what the branch holds, exactly; 0 is refused there.
   The item's prices stay as they are.
4. The route lives in `retail.stock` (stock already depends on the catalogue, never the reverse) and needs
   `retail.catalogue.manage`. The stock also needs `retail.stocktake.commit` at the chosen branch, checked before anything is
   made. Without a quantity the item is made with no stock, so a catalogue manager who may not add stock can still add it. A
   buying price is kept for a caller without `retail.profit.read`, as `POST /retail/products` keeps it.
5. A left out category files the item under General and a left out unit counts it in the shop's piece unit (Piece, else pc or
   pcs). Either is made when the business has none of that name (an insert that does nothing when the name is taken, so two
   adds at once make it once), audited as `retail.category.created` or `retail.unit.created` with source `quick_add`. Only a
   switched-on piece unit is used: a switched-off pc or pcs is passed over. A General or a Piece the owner switched off is never
   switched back on: the request is refused naming `category_id` or `unit_id`, and the form asks the person to choose one.
6. Nothing is kept for later offline: the offline outbox holds sales only (ADR-067), so the form says it needs the internet.
   No migration: the movement kind `opening` and the account `opening_balance_equity` exist since V11 and V10.

## Consequences

**Better:** one tap makes the item and its stock, or neither; starting stock is never profit and never lost or damaged stock;
adding to an item already in the list can never lower its stock; a new shop needs no category or unit first.

**Worse:** opening balance equity grows with every Quick add, so an accountant reads it as the owner's stock brought in, not
as a purchase with a supplier and a cost of cash; a shop that bought the goods and wants them as a purchase still uses Buy
stock. Stock added without a buying price (always so for someone who may not read cost, whose form does not ask it) goes in
at 0, so its opening value is 0 and a later sale of it shows the whole price as profit until a purchase sets the cost. The form
says so under an empty Buying price ("Add a buying price so profit is right"). General
and Piece are English names.

**Watch for:** a second way to make items that skips a rule of `POST /retail/products`; Quick add must keep calling the same
create. Goods that were really bought, entered through Quick add instead of Buy stock, leave what is owed to suppliers and the
cash book short. If the offline outbox later holds more than sales, Quick add is a candidate.
