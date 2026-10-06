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

Behaviour worth knowing: the sale, restock and usage forms generate one `Idempotency-Key` per draft and keep it,
with the draft, in the tab's `sessionStorage` (per user and, for sale and usage, per branch) for every re-render,
retry and reload, until the server answers with success or the user taps "Clear this sale" (or form, or restock),
which starts a fresh draft with a fresh key (#77). A reload in the middle of a save therefore posts the same entry
once. "New sale" mounts a fresh form with a fresh key. A supplier added inside a restock is kept in the draft once
created, so a retry does not add it twice. Money is integer minor units
(`UGX`, no decimals) and quantities are integer thousandths in the forms, so no float touches an amount. The
totals shown while typing are a preview; the server's response is the receipt.

## Look and navigation (#95)

The screens draw with the shared design layer (`docs/specs/design-system.md`); their own classes
(`.rt-card`, `.rt-row`, `.rt-total`, `.rt-flag`, `.rt-primary`) are styled in `src/app/ui/retail.css`.
The inline `<style>` element the screens used to render (`RetailStyles`) is gone: the production CSP
(`style-src 'self'`) refused it, so on a server the screens had no styles at all. `RetailStyles` stays as
a no-op export.

- The retail home shows one large tile per screen, with an icon, two per row on a phone.
- On the retail pages the staff layout adds a bottom bar (`retail/nav.tsx`): Retail home plus the first
  three of Sale, Stock, Restock, Stock-take and Usage that the user may use. It is fixed to the bottom
  on a phone and a row of pills above the title from 720px wide.
- Tables sit in a `.table-wrap` that scrolls sideways inside its card on a phone, with a shadow on the
  side that has more columns; quantities and money are right-aligned in tabular figures.
- Forms end with the main action full width, and any secondary action ("Clear this sale") under it.
- Choice rows (payment method, usage kind) are full-width 44px tap targets.

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
refuse a price). The server refuses both itself, always: overselling is refused with `insufficient_stock` (there is
no setting, ADR-020 decision 4, #64) and a price at or below cost with `price_below_cost` unless the caller holds
`retail.price.below_cost` (#64). The hints only save a round trip; the server stays the authority.

## The mock switch

`VITE_RETAIL_MOCK=1 npm run dev` (in `frontend/`) serves fabricated products, branches and balances from
`src/api/retail-mock.ts`, signs in a fake user (no backend needed) and runs every screen. Any value other than
empty, `0` or `false` turns it on; leave it unset for the real API. `VITE_RETAIL_MOCK_ROLE=sales` gives the sales
role; the default is admin. `retail-mock.ts` is imported dynamically only when `VITE_RETAIL_MOCK` is set at build
time, so a production build contains none of it (#77: `npm run build` without the flag emits no mock chunk and no
mock data). The mock returns the real response shapes, omits cost and profit without
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
outside the repository. That run predates the price floor (#64).

## Verification on the integrated branch (#71)

The walk was repeated against the combined backend (loan PRs, retail R1 to R4, #64, #68 and the importer) with
the same shape of stack: `docker compose up --no-build` with the API image wrapping the jar built by `mvn verify`
and the web image wrapping `npm run build`, both built on the host (outside the repository), and a fresh fabricated
retail tenant created with `deploy/sql/create-tenant.sql`, `platform_set_tenant_modules` and
`deploy/sql/invite-tenant-admin.sql` (admin and a `retail_sales` user at the head office, both with TOTP, a
second branch, four products counted in by stock-take). A Playwright script drove Chromium at 360px; 38 of 38
steps passed, among them:

- the admin flows above (cash and credit sale with the profit line, stock and price-floor hints, restock to two
  branches, usage, stock with cost, stock-take commit, valuation, daily profit);
- the sales user's tiles, "no access" on Restock, Stock-take and Daily profit by URL, a sale without profit, and
  stock and valuation without cost; none of the 20 retail responses that session received carried cost or profit;
- **price floor:** the sales user, who does not see cost, entered 3,500 and 3,499 for an item costing 3,500; the
  form allowed it, the server answered 422 `price_below_cost` without the cost in the body, and the form showed
  the plain message; 3,501 was accepted;
- **oversell, default tenant settings:** the form was filled with the whole branch balance, another till sold one
  unit, and Save was refused with 422 `insufficient_stock` and the plain message; the balance stayed at zero or
  above;
- no screen overflowed sideways at 360px.

A TOTP code is accepted once, so a script that enrols and then signs in must wait for the next 30 second window.
The script and its screenshots are not committed.

## Left to do

- Void a sale, pay a credit sale, price edit and price history screens (not in R6).
- The component tests render static markup (no DOM in the test setup); the browser run above covers behaviour.
- Offline use, barcode scanning and receipts to SMS are out of the first release.
