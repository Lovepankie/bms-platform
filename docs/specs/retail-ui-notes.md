# Retail UI notes

**Status:** Draft · **Tasks:** #56 (R6), #65 (R6b) under story #50 · **Requirement:** FR-RET-15 · **Scope:** `docs/specs/retail-mvp-scope.md`

Phone-first staff screens for the retail vertical, in `frontend/src/areas/staff/retail/`. Since #65 they run on the
real retail API (`docs/sdd/07-api-design.md` section 7.11.20, `docs/api/openapi.json`), which replaced the contract
draft: names are snake_case and the client is typed by the generated `src/api/schema.d.ts`.

## Screens and routes

| Route | Screen | Permissions needed |
|---|---|---|
| `/staff/retail` | Landing: one tile per screen the user may use | any `retail.*` permission |
| `/staff/retail/sale` | Record a sale, then the receipt summary | `retail.sale.create` |
| `/staff/retail/restock` | Restock: supplier, lines, per-branch quantities | `retail.purchase.create` |
| `/staff/retail/usage` | Usage and damage | `retail.usage.report` |
| `/staff/retail/stock` | Stock per branch, search, negative flag | `retail.stock.read` |
| `/staff/retail/stocktake` | Count, review variance, commit | `retail.stocktake.commit` and `retail.stock.read` |
| `/staff/retail/valuation` | Stock at price; at cost too with `retail.profit.read` | `retail.stock.read` (cost columns: `retail.profit.read`) |
| `/staff/retail/profit` | Daily profit per branch | `retail.profit.read` |

Sale, usage, stock, stock-take, valuation and profit work on the branch chosen in the staff header; with
"All branches" they ask for one branch. Restock takes a quantity per branch the user can see.

Behaviour worth knowing: the sale, restock and usage forms generate one `Idempotency-Key` when opened and keep
it for every re-render and retry; "New sale" mounts a fresh form with a fresh key. Money is integer minor units
(`UGX`, no decimals) and quantities are integer thousandths in the forms, so no float touches an amount. The
totals shown while typing are a preview; the server's response is the receipt.

## Permission gating

`retail/permissions.ts` is the one place that maps screens to the permissions the API routes declare, read
from `/me`. The Retail menu entry shows when the user holds a retail permission (`/me` does not list modules
yet; if it carries `modules` later, it is honoured). A user without `retail.profit.read` sees no profit menu
entry, no cost column, no cost hint and no profit line, and the server never sends those fields (they are
absent from the body, not null). Stock value is a stock read, so a sales user sees it at selling price only. The
API stays the authority.

## Errors

`src/api/retail-errors.ts` maps the problem `code` to plain words: `insufficient_stock`, `price_below_cost`,
`idempotency_key_reused`, `idempotency_in_progress`, `idempotency_key_missing`, `permission_denied`,
`module_not_enabled`, `branch_required`, `sale_voided`, `stocktake_committed`, `payment_exceeds_balance`, expired
sessions, and the field messages of `validation_failed`. A code it does not know shows the server's own message,
so a new refusal (for example a price floor code spelled differently) is never hidden.

The sale form also gives early hints that only save a round trip: a quantity above the branch balance, and a
price not above cost when the cost is known (only with `retail.profit.read`; for anyone else only the server can
refuse a price). Note the backend default `retail_allow_negative_stock = true` accepts a sale the hint blocks;
the hint follows the pilot behaviour ("oversell refused") and the server stays the authority.

## The mock switch

`VITE_RETAIL_MOCK=1 npm run dev` (in `frontend/`) serves fabricated products, branches and balances from
`src/api/retail-mock.ts`, signs in a fake user (no backend needed) and runs every screen. Any value other than
empty, `0` or `false` turns it on; leave it unset for the real API. `VITE_RETAIL_MOCK_ROLE=sales` gives the sales
role; the default is admin. The mock returns the real response shapes, omits cost and profit without
`retail.profit.read`, replays a repeated `Idempotency-Key`, refuses a reused key and a sale above the balance
with the server's codes, and updates prices on a restock. The vitest suites use it as the test adapter.

## Verification (#65)

Against the real API: the stack was brought up as in `AGENTS.md` (PostgreSQL, migrate, API, web, proxy), with
the fabricated `demo` tenant from `make seed`, then a fabricated retail tenant created through the platform API
as in `docs/runbooks/onboard-tenant.md` (retail module on, admin with TOTP, a sales user with the `retail_sales`
role at the head office, a second branch, four products with opening stock). A scripted headless Chromium at
360px wide drove, as admin: sale (cash and credit), the stock and price-floor hints, restock with a second
branch, usage, stock view, stock-take with commit, valuation and daily profit; and as the sales user: sale
without a profit line, stock and valuation without cost, no Restock, Stock-take or Profit entry, and "no access"
on those URLs. Every retail response seen by the sales session was checked for the words cost and profit
(none). A sale refused by the server after the form had been filled (stock sold meanwhile) showed the plain
message, and the codes `insufficient_stock`, `idempotency_key_reused` and `permission_denied` were seen from the
real server. No page overflowed sideways. Screenshots and the script are not committed.

Sandbox notes: Docker Hub answered 429, so base images came from `mirror.gcr.io` and were retagged locally, and the
image builds cannot trust the egress proxy's CA, so the API jar was built on the host and wrapped in local images
outside the repository. `price_below_cost` is not on this backend build yet (#64), so a sales user's low price was
accepted by the server; the client path for it is covered by unit tests only.

## Left to do

- Void a sale, pay a credit sale, price edit and price history screens (not in R6).
- The component tests render static markup (no DOM in the test setup); the browser run above covers behaviour.
- Offline use, barcode scanning and receipts to SMS are out of the first release.
