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

## Amendment of 28 Oct 2026: paid for now from cash (issue #646, owner decision D69)

Quick add booked every quantity as capital the owner brought in, and cash did not move. An owner who used it for goods just bought
with shop money then saw too much cash in the cash book and the books.

1. Quick add has a tick, off by default: "I just bought these, take the money from cash", with Cash or Mobile money. With it,
   `paid_from` (`cash` or `mobile_money`) is sent, `qty` above 0 and `cost_minor` above 0 are needed, and the stock is booked as
   one purchase paid from that account of the branch, as Buy stock books it: one `retail_purchases` row with no supplier, dated
   today, numbered as every restock, its `purchase` movement at the cost typed, debit inventory and credit `cash_on_hand` or
   `mobile_money` at that branch, the item's buying price set as the latest restock sets it, and the cash book reading it as cash
   out of the till. Nothing posts to opening balance equity then. Left off, Quick add is unchanged.
2. Stock cannot depend on purchasing, so `retail.stock` declares the port `QuickAddPurchase` and purchasing supplies it from Buy
   stock's own steps (`PurchasingService.buy`), in Quick add's transaction. Quick add's `Idempotency-Key` covers the whole request,
   so a retry never posts twice.
3. Buy stock's permission, `retail.purchase.create`, is needed at the branch as well as `retail.stocktake.commit`, both checked on
   the server before anything is made; without it the answer is 403 `permission_denied` (not the branch's 422, since the branch is
   fine for the stock). The server needs only these two: `retail.profit.read` is not checked there, as `POST /retail/products` and
   Buy stock keep a buying price whatever the caller may read. The form is stricter and offers the tick only to someone who holds
   `retail.purchase.create` at Where and may see the buying price; when Where moves to a place where they may not buy, the tick stays
   in view with a line saying so and Add sends nothing until they untick it or move back, so a ticked add never goes in as
   opening stock unnoticed.
4. The form shows the account and the total that will come out ("UGX 30,000 comes out of Cash at Test Shop") before Add, and the
   answer names the purchase. The help's How do I add an item quickly? says when to tick it and points to Buy stock for bigger
   deliveries. No migration.

**Watch for:** a supplier is never named here, so goods bought on credit or from a supplier the owner tracks still go through
Buy stock.

## Amendment of 29 Oct 2026: the category is needed on the screen (issue #688, owner decision D75)

A shop owner was confused to find items filed under General that they never chose. The Quick add screen now asks for the
category: the field sits right under the Name, is marked needed, and Add refuses without it ("Choose a group."), so the
screen always sends `category_id`. It starts with the group this person last chose at the shop (kept on the device), and "+ Add
a group" adds one in a sheet over the form without losing what was typed (FR-UXN-12). The route is unchanged: `category_id`
stays optional on `POST /retail/quick-add`, and General is still made for a caller that leaves it out, so the API and its
tests keep their contract and no migration is needed. Items already in General stay there.

**Watch for:** another caller of the route that leaves the category out still files under General; a screen that adds an item
should ask for the category as Quick add does.
