# Retail UI notes

**Status:** Draft · **Tasks:** #56 (R6), #65 (R6b) under story #50, #84 (stock transfers), #103, #105, #112 (walk-through fixes), #145 and #144 (parity), #121 and #146 (catalogue management) · **Requirements:** FR-RET-15, FR-RET-16 · **Scope:** `docs/specs/retail-mvp-scope.md`

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
| `/staff/retail/credit-sales` | Credit sales: buyer, date, amount, due date, state; open a sale | `retail.sale.read` |
| `/staff/retail/sales` | All sales: filters by dates, buyer, paid by and item; open a sale | `retail.sale.read` |
| `/staff/retail/stock` | Stock per branch or All branches, search, category filter, Out of stock and Low stock tabs, negative flag | `retail.stock.read` |
| `/staff/retail/stocktake` | Count, review variance, commit | `retail.stocktake.commit` and `retail.stock.read` |
| `/staff/retail/transfer` | Move stock: to which branch, items with the source's stock, date and note | `retail.stock.transfer` and `retail.stock.read` |
| `/staff/retail/transfers` | Stock moves from or to the branch; open one; cancel it | `retail.stock.read` (cancel: `retail.stock.transfer`) |
| `/staff/retail/valuation` | Stock at price; at cost too with `retail.profit.read` | `retail.stock.read` (cost columns: `retail.profit.read`) |
| `/staff/retail/profit` | Daily profit per branch, with the stock-take difference as its own column (#121) | `retail.profit.read` |
| `/staff/retail/catalogue` | Catalogue home: links to the screens below the session may use | any one of the catalogue screens' permissions |
| `/staff/retail/catalogue/products` | Items: list with search, category and on sale filters; add, edit, change price, price history (#146) | `retail.catalogue.manage` and `retail.stock.read` (Change price: `retail.price.edit`; cost: `retail.profit.read`) |
| `/staff/retail/catalogue/categories`, `/units` | Add, rename, switch off or on, with the number of items using each | `retail.catalogue.manage` and `retail.stock.read` |
| `/staff/retail/catalogue/suppliers` | Add, edit, switch off or on | `retail.catalogue.manage` and `retail.purchase.create` |
| `/staff/retail/catalogue/buyers` | Credit buyers with what each owes; add, edit name and contact | `retail.customer.manage` and `retail.sale.read` |
| `/staff/retail/catalogue/import` | Import items from a CSV file or pasted rows: check first, then add (#146) | `retail.catalogue.manage`, `retail.stock.read` and `core.settings.manage` (administrators) |

Sale, usage, stock, stock-take, Move stock, valuation and profit work on the branch chosen in the staff header;
with "All branches" they ask for one branch. Restock takes a quantity per branch the user can see. Stock moves
lists the transfers from or to the chosen branch, or every branch the user may read with "All branches".

**Move stock (#84).** The branch in the header is the one the stock leaves. The destination is chosen from the
user's other branches in `/me` (the API accepts any active branch of the tenant, but the PWA has no tenant-wide
branch list for a branch-scoped user yet). Items are added with the same product picker as the sale screen
(`ProductPicker` in `ui.tsx`, now shared), which shows what the source branch holds, and each line hints when its
quantity is above that. The date starts on today in the business's timezone (#112) and the note is optional. The form keeps its draft and
one `Idempotency-Key` in `sessionStorage` like the sale form, and a refusal for stock shows the plain
`insufficient_stock` message. No cost is shown, except the value at cost on the saved summary for a user with
`retail.profit.read`. Stock moves opens a transfer and offers "Cancel this move" with a reason to a user who may
move stock; the server refuses with `transfer_stock_moved` once the destination has sold, used or moved part of
it, and that message (naming the branch and the item codes) is shown as the server wrote it.

Behaviour worth knowing: the sale, restock and usage forms generate one `Idempotency-Key` per draft and keep it,
with the draft, in the tab's `sessionStorage` (per user and, for sale and usage, per branch) for every re-render,
retry and reload, until the server answers with success or the user taps "Clear this sale" (or form, or restock),
which starts a fresh draft with a fresh key (#77). A reload in the middle of a save therefore posts the same entry
once. "New sale" mounts a fresh form with a fresh key. A supplier added inside a restock is kept in the draft once
created, so a retry does not add it twice. Money is integer minor units
(`UGX`, no decimals) and quantities are integer thousandths in the forms, so no float touches an amount. The
totals shown while typing are a preview; the server's response is the receipt.

## Branches, dates and the walk-through fixes (#103, #105, #112)

- **Starting branch (#103).** The staff bar's picker (`areas/staff/branch-picker.tsx`) starts on the last
  branch this user chose in this browser, kept in `localStorage` per tenant (the host) and user, read and
  written inside try/catch. Without one it starts on the user's only branch, else the default branch when it
  holds stock, else the first branch that holds stock, and never on a head office without stock while other
  branches exist. "Holds stock" is any quantity above zero in the stock value report
  (`GET /retail/reports/valuation` without a branch), read once for a session with `retail.stock.read`; a
  session without it starts on the default branch as before. The staff area waits for that read (one retry)
  before showing a screen, so a screen never opens on the wrong branch.
- **No stock here (#103).** Stock, Record a sale and Move stock say "This branch holds no stock. Switch
  branch?" when the chosen branch holds nothing, with a button for each of the user's branches that does.
- **Branch names (#105).** Every branch name the retail screens and the picker print goes through
  `branchLabel` (`auth/branch.ts`): the name alone when the code equals the name ignoring case (an imported
  "GAYAZA Gayaza" reads "Gayaza"), otherwise "Name (CODE)".
- **Dates (#112 items 3 and 9).** The tenant's timezone is not on `/me` or the settings yet, so the forms use
  `businessToday()` in `api/retail.ts`: today in Africa/Kampala, the server's default business zone
  (`BusinessClock`). Move stock's date and Restock's "Bought on" start on it, and Daily profit's range is the
  last seven days ending on it. Before #112 Restock and Daily profit used the UTC date, which is yesterday
  between midnight and 03:00 in Kampala. Usage, sale and stock-take send no date and take the server's.
- **Stock moves (#112 item 1).** A row reads the date ("6 Oct 2026", on one line), "From X to Y", the item
  count with the first two item names, and "by you" for the user's own moves; tapping it opens the move. The
  API gives a transfer no number of its own and `created_by` is an id, so no number or other name is shown.
- **Move stock hint (#112 item 2).** A line above the source branch's stock says "Only N piece in stock at
  this branch.", the words of the sale screen, and Move stock stays disabled; a refusal from the server still
  shows as the plain `insufficient_stock` message.
- **Restock saved (#112 items 10 and 11).** The confirmation names the supplier, the restock number
  (`purchase_no`) and date, and lists each line whose sell price changed ("sell price changed from X to Y")
  and, for a user with `retail.profit.read`, whose cost changed.
- **Daily profit and stock-takes (#112 item 8, decided in #121).** The report counts sales, their cost, usage and
  damage reports, and the net stock-take difference as its own column "Stock-take difference" (negative for a
  loss, positive for a gain, at cost, on the day the stock-take was committed), included in the profit. It is the
  same figure the ledger holds in `stock_shrinkage`.

## Look and navigation (#95)

The screens draw with the shared design layer (`docs/ui/design-system.md`); their own classes
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
`module_not_enabled`, `branch_required`, `sale_voided`, `stocktake_committed`, `transfer_voided`, `payment_exceeds_balance`, expired
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
with the server's codes, and updates prices on a restock. It also moves stock between its two fabricated branches,
refuses a transfer above the source's balance (`insufficient_stock`) and a cancel once the destination no longer
holds the stock (`transfer_stock_moved`), and lists and opens transfers; the mock admin holds
`retail.stock.transfer`, the mock sales role does not. The vitest suites use it as the test adapter.

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

## Verification of stock transfers (#84)

The same shape of stack (`docker compose up --no-build`, the API image wrapping the jar built by `mvn verify` and
the web image wrapping `npm run build`, both built outside the repository), migrated to V22, with a fresh fabricated
retail tenant created with `deploy/sql/create-tenant.sql`, `platform_set_tenant_modules` and
`deploy/sql/invite-tenant-admin.sql`: an admin with TOTP, a `retail_sales` user at the head office, a second branch
`BR2`, two products counted in at the head office. A Playwright script drove Chromium at 360px; 35 of 35 steps
passed, among them:

- the admin sees Move stock and Stock moves; the form names the source branch and the picker its stock; three
  units move from head office to `BR2` and the summary shows the branches and the value at cost; both balances
  moved;
- a quantity above the source's balance shows the hint and disables Move stock;
- **refusal:** the form was filled with the whole balance, another till moved one unit meanwhile, and Move stock
  was refused with 422 `insufficient_stock` and the plain message; nothing moved;
- Stock moves lists both transfers; cancelling the first moves the stock back; after `BR2` sold what the second
  brought, its cancel is refused with `transfer_stock_moved` and the server's message naming `BR2` and the code;
- valuation at cost equals the stock left at cost, unchanged by the moves;
- the sales user has no Move stock tile, gets "no access" on its URL, sees transfers without a cancel form or any
  cost (no retail response to that session carried cost), and the sales column gets 403 on `POST /retail/transfers`;
- no screen overflowed sideways at 360px.

The script and its screenshots are not committed.

## Verification of the walk-through fixes (#103, #105, #112)

The API jar from `mvn package` ran on the host against the compose PostgreSQL (migrated to V22) with the
`dev` profile, and both bundles (`main` and this branch, `npm run build`) were served with a small static
server that proxies `/api` with the Host header kept, so `shop.localhost` resolves the tenant. A fabricated
tenant "Sample Shop (fabricated)" was made with `deploy/sql/create-tenant.sql`, `platform_set_tenant_modules`
and `deploy/sql/invite-tenant-admin.sql`, with branches HQ Head Office, SECOND Second Shop, TOWN Town and MKT
Market Kiosk, three products restocked to the three shops only (the head office holds nothing) and one move
from Town to Second Shop. A Playwright script drove Chromium at 360px and 390px; 43 of 43 steps passed
(`docs/ui/design-system/walkthrough-fixes/walk-log.txt`, screenshots beside it):

- after signing in with TOTP, a reload of `/staff/retail/stock` stayed signed in, and a new tab holding only
  the refresh cookie opened signed in, on `main` and on this branch;
- on `main` the picker started on "HQ Head Office" (no stock) and listed "SECOND Second Shop"; on this branch it
  started on "Market Kiosk (MKT)", listed "Town" for the branch whose code is its name, said "This branch holds
  no stock. Switch branch?" on the head office, switched with the button and kept the choice over a reload;
- the stock moves row read "2026-10-06" over two lines then ": TOWN Town to SECOND Second Shop, 2 items" on
  `main`, and "6 Oct 2026 / From Town to Second Shop (SECOND) / 2 items: LED bulb 9W screw, Socket double, by
  you" on this branch, and opened the move when tapped;
- Move stock's date was empty on `main` and today's date on this branch; with 50 of an item the branch held
  12 of, both disabled Move stock, `main` saying "Only 12 piece at the branch you are moving from." and this
  branch "Only 12 piece in stock at this branch.";
- no page overflowed sideways.

## The parity pass (#145, #144)

`docs/specs/retail-ui-parity.md` lists every AppSheet view against its screen. What this pass added:

- **Categories.** Stock has a Category column and a category select ("All categories"); search matches the
  category text; the sale, restock, usage, Move stock and stock-take pickers show the category as a small grey
  label (`CategoryLabel` in `ui.tsx`) and search it. The API: `category_id` and the category on stock rows,
  `query` matches the category name on stock and products.
- **Expected profit.** Stock value shows Expected profit and Profit % (over cost, from `expected_profit_bp`) in
  the total, per item and per branch, and a By category table; all of it only with `retail.profit.read`, since the
  server sends none of it otherwise (the same rule as cost, PR #78). Without that permission the screen shows the
  prices and the By category table at selling price only.
- **All branches (#144).** With "All branches" in the Branch box, Stock calls `GET /retail/stock/all-branches`:
  a column per branch, a Total, the Negative flag on each cell; below 720px (`useIsPhone`) each item is a card
  with the total and a Show branches button. Stock value adds a By branch table (the server's branch totals) and
  names the branch on each item row; Daily profit adds a By branch table and sums the days across branches. The
  write screens (sale, usage, stock-take, Move stock; Restock already chose its branches per line) still need one
  branch, and now say "Choose a branch to continue:" with a button for each of the user's branches.
- **Out of stock and Low stock.** Tabs on Stock (`stock_level=out|low`): out is zero or less, low is 5 or fewer
  and so includes out of stock. The threshold is a constant in the API (`StockService.LOW_STOCK_MILLI`) because
  the settings catalogue has no retail group (ADR-029); in All branches the level is judged on the total.
- **Credit sales and All sales.** Two screens on the sales list API (new filters `payment_method`, `product_id`,
  `buyer`, `status`, `newest_first`), linked from the retail home. A card shows the buyer, date and amount; a
  credit sale adds the due date, what is still owed and a state badge (Paid, Part paid, Unpaid, Overdue; the
  words carry the meaning). Tapping a sale shows its lines. Profit shows only when the server sent it.

### Verification of the parity pass

The same shape of stack as the walkthrough fixes (real API on the dev profile, the built PWA, headless Chromium, a
real sign-in with TOTP) on a fabricated tenant with three branches, five categories and twelve products
(`docs/ui/design-system/parity/`, with a README). Stock, Stock value, Daily profit, Credit sales, All sales and the
write-screen branch message were opened at 360px and 1280px with "All branches" and with one branch: no screen
overflowed sideways, and the first pass led to these changes: on a phone the category sits under the item name and the
cost and usage of a day under its name (so quantity, price, sales and profit stay in view), Stock value's headline
is a two column table instead of large wrapping text, branch and category totals are one card each on a phone, and a
sale in a list is a card with an Open button instead of a blue link-like row. The backend suite (`mvn verify`: 119
unit and 330 integration tests, including `RetailParityIT`) is green.

## Catalogue management (#146)

A shop keeps its own lists without an operator. The retail home has a **Catalogue** tile when the session may use any
of the screens under it; the Catalogue home lists only those it may use (a sales user, who holds
`retail.customer.manage`, sees Credit buyers alone). `retail/permissions.ts` holds the permissions of each
(`CATALOGUE_LINKS`); the API stays the authority.

- **Items** (`catalogue-products.tsx`): the list takes search (code, description, category), a category and a
  "Items on sale, switched off, all" choice, 50 at a time with "Show more items". A card shows the code, category,
  unit and selling price, and the cost only with `retail.profit.read`. Add and Edit change code, description,
  category, unit and whether the item is on sale (`PATCH` under `If-Match` with the version just read, so a change
  by someone else is refused with a plain message). Prices are never edited there: **Change price** (with
  `retail.price.edit`) takes a new selling price, a cost price only with `retail.profit.read`, and a required
  reason; it warns, without blocking, when the selling price is not above the cost the user can see, and the server
  accepts a price at or below cost (the price floor is enforced when a sale is made, `price_below_cost`, unless the
  seller holds `retail.price.below_cost`), so the form only warns, and only when the user can see cost. Under the form, the **price
  history** of the item lists each change with the date and time in the business's time zone (`showWhen`,
  Africa/Kampala like `businessToday`), what changed, why, and the cost only when the server sent it. A new item
  asks for a selling price; its cost is asked only with `retail.profit.read` (else 0, and a restock sets it).
- **Categories and Units** (`catalogue-lists.tsx`, one component twice): Add, Rename, Switch off or Switch on,
  with "Used by N items". Nothing is deleted. A switched-off row stays on the items that use it and is not offered
  for a new item (the server refuses it with 422 `inactive_category`, `inactive_unit`).
- **Suppliers and Credit buyers** (`catalogue-people.tsx`): Add and Edit name and contact; a supplier can also be
  switched off (it is then refused on a new restock). Credit buyers show what each owes on credit sales in the
  branches the user may read (`balance_minor`). The contact is shown as typed and is not written to the audit log.
- **Import items** (`catalogue-import.tsx`): choose a CSV file or paste rows (comma, semicolon or tab separated; a
  spreadsheet paste works). **Check the file** runs a dry run and lists each row as Add, Skip (the code exists, or
  repeats in the file) or Problem with a plain message; **Add N items** appears only after a clean check of the text
  as it stands now. A file with a problem row adds nothing. Adding again skips everything already there. The cost
  price column is accepted only with `retail.profit.read`.
- **Messages.** Every save shows a green on-screen message (`Success`, `role="status"`) and a toast above the
  bottom bar (`useToast` in `ui.tsx`, four seconds, hidden from assistive technology since the on-screen message
  carries the same words). There was no toast helper in the app before this (only the `.toast` style), so
  `useToast` is the first and the other retail screens do not use it yet. Refusals use the existing `Problem` alert
  with the server's plain message.

### Verification of the catalogue screens (#146, #121)

The real stack as in `docs/ui/design-system/catalogue/README.md` (API jar from `mvn verify`, V28, the built PWA, headless
Chromium at 360px and 1280px, a real sign-in with TOTP, a fabricated tenant with three branches): the walk added, edited,
switched off and re-priced items, renamed and switched off categories, added and edited a supplier and a credit buyer, checked
and applied an item import and opened Daily profit. No screen overflowed sideways. It found one defect, fixed with a test: the
price history crashed on the `null` the server sends for a first price. The price edit accepts a price below cost (the floor is
enforced on a sale), so the form warns instead of refusing. A shop that counts in its opening stock with a stock-take will see
that stock as a stock-take difference, and so as profit, at cost, on that day: bring opening stock in with a restock instead.

## Left to do

- Void a sale and pay a credit sale screens; see `docs/specs/retail-ui-parity.md`.
- The component tests render static markup (no DOM in the test setup); the browser run above covers behaviour.
- Offline use, barcode scanning and receipts to SMS are out of the first release.
- The tenant's timezone and a transfer number are not in the API; when they are, use them (#112).
