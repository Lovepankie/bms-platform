# Retail UI notes

**Status:** Draft · **Task:** #56 (R6) under story #50 · **Requirement:** FR-RET-15 · **Scope:** `docs/specs/retail-mvp-scope.md`

Phone-first staff screens for the retail vertical, in `frontend/src/areas/staff/retail/`. They are built
against `docs/api/retail-contract-draft.md` while the backend (R1 to R4) is in progress.

## Screens and routes

| Route | Screen | Permissions needed |
|---|---|---|
| `/staff/retail` | Landing: one tile per screen the user may use | any `retail.*` permission |
| `/staff/retail/sale` | Record a sale, then the receipt summary | `retail.sale.create` |
| `/staff/retail/restock` | Restock: supplier, lines, per-branch quantities | `retail.purchase.create` |
| `/staff/retail/usage` | Usage and damage | `retail.usage.report` |
| `/staff/retail/stock` | Stock per branch, search, negative flag | `retail.stock.read` |
| `/staff/retail/stocktake` | Count, review variance, commit | `retail.stocktake.commit` and `retail.stock.read` |
| `/staff/retail/valuation` | Stock at cost and at price | `retail.profit.read` and `retail.stock.read` |
| `/staff/retail/profit` | Daily profit per branch | `retail.profit.read` |

Sale, usage, stock, stock-take, valuation and profit work on the branch chosen in the staff header; with
"All branches" they ask for one branch. Restock takes a quantity per branch the user can see.

Behaviour worth knowing: the sale, restock and usage forms generate one `Idempotency-Key` when opened and keep
it for every re-render and retry; "New sale" mounts a fresh form with a fresh key. Money is integer minor units
(`UGX`, no decimals) and quantities are integer thousandths in the forms, so no float touches an amount. The
totals shown while typing are a preview; the server's response is the receipt.

## Permission gating

`retail/permissions.ts` is the one place that maps screens to permissions, read from `/me`. The Retail menu
entry shows when the tenant has the retail module and the user holds a retail permission. `/me` does not list
modules yet, so until it does the module is implied by the retail permissions (they exist only for tenants
with the module on); if `/me` later carries `modules`, it is honoured. A user without `retail.profit.read`
sees no valuation or profit menu entry, route content, cost column, cost hint or profit line, and the server
never sends those fields (contract draft). The API stays the authority.

## The mock switch

`VITE_RETAIL_MOCK=1 npm run dev` (in `frontend/`) serves fabricated products, branches and balances from
`src/api/retail-mock.ts`, signs in a fake user (no backend needed) and runs every screen end to end.
`VITE_RETAIL_MOCK_ROLE=sales` gives the sales role; the default is admin. The mock hides cost and profit
without `retail.profit.read`, replays a repeated `Idempotency-Key` and updates prices on a restock, like the
contract. Leave the variable unset for the real API.

## Moving to the generated client

When `docs/api/openapi.json` carries the retail paths: run `make openapi`, replace the hand-written types in
`src/api/retail.ts` with aliases of `components['schemas'][...]` (as `src/api/client.ts` does), and replace the
`http(...)` calls in `realRetail` with `api.GET` and `api.POST` from `./client`. The screens depend only on the
`RetailApi` interface, so they do not change. Keep the mock as the test adapter.

## Assumptions to confirm with R1 to R4

The contract draft does not give these shapes, so the client assumes them: `POST /customers` takes
`{name, contact?}` and `POST /suppliers` takes `{name}`; a stock-take response is
`{id, branchId, status, lines:[{productId, description?, expectedQty, countedQty, varianceQty}]}`; the
valuation response is `{branchId, asOf, rows, totals}`; daily profit returns an array of
`{branchId, date, salesMinor, costMinor, usageMinor, profitMinor}`; a purchase response has
`{id, purchasedOn, paymentMethod, lineCount, totalMinor, pricesUpdated}`. Tenant currency is fixed to UGX in
the client because `/me` does not carry it.

## Left to do

- Void a sale, pay a credit sale, price edit and price history screens (not in R6).
- Browser-level tests: the test setup has no DOM, so component tests render static markup, and the
  idempotency hook's re-render stability is covered by design and a manual browser check, not a test.
- Offline use, barcode scanning and receipts to SMS are out of the first release.
