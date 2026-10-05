# Retail API contract draft

**Status:** Draft, replaced by the generated `openapi.json` once R1 to R4 land. Decision: ADR-020. Scope: `docs/specs/retail-mvp-scope.md`.

Conventions follow the existing API: base path `/api/v1/retail`, JSON, problem details for errors,
`Idempotency-Key` header on every POST that creates a financial record, tenant and branch from the
session. Money is an integer in minor units (`...Minor`), quantities are decimal strings with up
to three places (`"3.500"`), ids are UUIDs, dates are ISO 8601. A user without
`retail.profit.read` never receives cost, cost snapshot or profit fields.

## Permissions

`retail.catalogue.manage`, `retail.price.edit`, `retail.customer.manage`, `retail.sale.create`,
`retail.sale.read`, `retail.sale.void`, `retail.stock.read`, `retail.stocktake.commit`,
`retail.purchase.create`, `retail.usage.report`, `retail.profit.read`, `retail.price.below_cost`.
Sales role: sale create and read, stock read, usage report, customer manage. Admin role: all
except `retail.price.below_cost`, which no default role holds.

## Catalogue

| Method and path | Purpose | Notes |
|---|---|---|
| `GET/POST /categories`, `GET/POST /units` | Lists and creation | `{id, name}` |
| `GET /products?query=&categoryId=&active=&branchId=` | Search; with `branchId` each row carries that branch's balance | row: `{id, code, description, categoryId, unit, sellMinor, costMinor*, active, qty?}` |
| `POST /products` | Create | body: `{code, description, categoryId, unitId, costMinor, sellMinor}` |
| `GET/PATCH /products/{id}` | Read, edit non-price fields | prices change only through the next row |
| `POST /products/{id}/prices` | Manual price edit, audited, writes history | body: `{costMinor?, sellMinor?, reason}`; needs `retail.price.edit` |
| `GET /products/{id}/price-history` | History | row: `{at, by, source, oldCostMinor, newCostMinor, oldSellMinor, newSellMinor}` |

## Stock

| Method and path | Purpose | Notes |
|---|---|---|
| `GET /stock?branchId=&query=&negativeOnly=` | Balances for a branch | row: `{productId, description, unit, qty, negative, sellMinor, costMinor*}` |
| `GET /stock/movements?productId=&branchId=&from=&to=` | Movement list | row: `{at, kind, qty, sourceType, sourceId, by}` |
| `POST /stocktakes` | Start a count | body: `{branchId, lines:[{productId, countedQty}], note}`; returns variance per line |
| `GET /stocktakes/{id}` | Read draft or committed | |
| `POST /stocktakes/{id}/commit` | Write adjustment movements | needs `retail.stocktake.commit`; each adjustment is counted less `expectedQty`, so movements after the count stay; 409 `stock_moved_since_count` asks for a recount when the result would be negative |

## Sales and customers

| Method and path | Purpose | Notes |
|---|---|---|
| `POST /sales` | Record a sale | body: `{branchId?, saleDate?, paymentMethod: "cash"|"mobile_money"|"bank"|"credit", customerId?, buyerName?, buyerContact?, dueDate?, lines:[{productId, qty, unitPriceMinor?}]}`; unit price defaults to the product's sell price; response has lines, `totalMinor`, `balanceMinor`, and `profitMinor*`; 422 `insufficient_stock` when a line exceeds the branch's stock (always, no setting); 422 `price_below_cost` when a unit price is not above the product's cost, unless the caller holds `retail.price.below_cost`; the message never carries the cost |
| `GET /sales?branchId=&from=&to=&customerId=` and `GET /sales/{id}` | Read | |
| `POST /sales/{id}/void` | Void | body `{reason}`; reverses movements and journals; needs `retail.sale.void` |
| `POST /sales/{id}/payments` | Pay a credit sale | body `{amountMinor, method, paidOn}` |
| `GET/POST /customers`, `GET /customers/{id}/balance` | Credit buyers and what they owe | |

## Purchasing and usage

| Method and path | Purpose | Notes |
|---|---|---|
| `GET/POST /suppliers` | Suppliers | |
| `POST /purchases` | Restock | body: `{supplierId?, purchasedOn, paymentMethod: "cash"|"bank"|"credit", lines:[{productId, costMinor, sellMinor?, qtyByBranch:[{branchId, qty}]}]}`; sets the product prices atomically and writes history |
| `GET /purchases?from=&to=&supplierId=` | Read | |
| `POST /usage` | Usage or damage | body: `{branchId, kind: "used"|"damaged", reason, lines:[{productId, qty}]}`; 422 `insufficient_stock` past the branch's stock |

## Reports

| Method and path | Purpose | Notes |
|---|---|---|
| `GET /reports/valuation?branchId=&asOf=` | Stock value at cost and expected sales at price | rows plus totals; cost columns need `retail.profit.read`; a row too large to value has `amountOutOfRange: true` and is left out of the totals |
| `GET /reports/profit/daily?branchId=&from=&to=` | Profit per branch per day | needs `retail.profit.read` |

\* present only with `retail.profit.read`.
