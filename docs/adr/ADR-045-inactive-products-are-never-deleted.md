# ADR-045: A discontinued product is marked inactive, never deleted

## Status

Proposed (2026-10-20, issue #342). Builds on ADR-020 (retail stock and cost, the `active` flag of a product) and ADR-044 (entry mistakes are corrected without deleting). No migration: `retail_products.active` exists since `V10`.

## Context

The owner asked to delete products the business no longer sells. History and analysis depend on the record: sale lines, restocks, stock movements and price history all name the product, and the reports read them. The agreed answer is that nothing is deleted. The `active` flag existed and `PATCH /retail/products/{id}` already edited it, but a sale refused an inactive product only on the sale route; a restock, a transfer and the sale price resolver did not, and the stock lists still showed an inactive product that held stock.

## Decision

1. **Inactive, never deleted.** There is no delete route. A holder of `retail.catalogue.manage` sets `active` false or true on the product (the existing PATCH, audited with the old and new value).
2. **An inactive product cannot be put into new documents.** Goods received against a sent purchase order line are the exception: they are already on the way, so they are received (a blind receipt is refused); an in-transit transfer is received whatever the item's state. A sale, the price resolver, a restock and a purchase order line and a stock transfer refuse it with 422 `product_inactive` and a plain sentence naming the way back ("Switch it on again on the Items screen first"). Returns, voids, stock corrections, stock-takes, usage and damage of an inactive product are allowed, because they correct or close what already happened.
3. **It leaves the default lists.** The pickers of the sale, restock, usage and transfer screens already ask for active products only. `GET /retail/stock` and `GET /retail/stock/all-branches` now list active products only, and take `include_inactive=true` (the screen's "Show inactive items") to bring the others back. The earlier rule that an inactive product still holding stock stayed in the list is dropped: the stock is reported on the product instead (point 4). What each report does with an inactive product's stock: the **valuation** and the **stock-by-location** report (which lists an inactive product only while it holds a quantity) include it, since the stock is still on the shelf and in the books; the **dashboard** stock value, its out of stock and low stock counts, and **stock health** count active items only, as the stock list does. The **count sheet** of a stock-take lists active items and every inactive item that still holds stock, so a shortage is never hidden.
4. **A balance is never hidden silently.** `GET /retail/products/{id}` carries `stock_on_hand`, the stock summed over the branches the caller may read. `stock_branches` counts the branches the caller may read where the balance is not zero, so a positive and a negative balance never cancel to nothing. The Items screen shows a plain warning, "in the branches you can see", while Inactive is ticked and `stock_branches` is above zero. The server does not refuse switching off an item with stock: the owner may want it parked, and stock can be corrected on the Stock page.
5. **History and reports keep the product.** Lists of sales, restocks, movements and price history, and every report, read inactive products as before.

## Consequences

**Better:** the owner removes discontinued items from every working list without losing a figure; the refusal is the same on every route, in words a shop assistant understands.

**Worse:** stock held by an inactive product is out of the default stock list, so a forgotten balance is seen only through the warning, the Show inactive items switch or the reports.

**Watch for:** a new route that adds a product to a document must call the same check; a purchase order sent before an item went inactive can still be received, since goods already on the way are not refused here; a draft holding an item switched off since is not sent (ADR-094, issue #376).
