# Retail UI notes

**Status:** Draft · **Tasks:** #56 (R6), #65 (R6b) under story #50, #84 (stock transfers), #103, #105, #112 (walk-through fixes), #145 and #144 (parity), #121 and #146 (catalogue management) · **Requirements:** FR-RET-15, FR-RET-16 · **Scope:** `docs/specs/retail-mvp-scope.md`

Phone-first staff screens for the retail vertical, in `frontend/src/areas/staff/retail/`. Since #65 they run on the
real retail API (`docs/sdd/07-api-design.md` section 7.11.20, `docs/api/openapi.json`), which replaced the contract
draft: names are snake_case and the client is typed by the generated `src/api/schema.d.ts`.

## Screens and routes

| Route | Screen | Permissions needed |
|---|---|---|
| `/staff/retail` | Landing: one tile per screen the user may use | any `retail.*` permission |
| `/staff/retail/sale` | Record a sale, then the receipt summary; a shop with no items starts with the card Get your shop ready (Quick add an item, the one primary action, then Set up your shop, Add your first items and Import a list, or Ask the owner to add the items first), and a search that finds nothing offers Not in the list? Add it here with what was typed and How many you have here (more than 0), the new item counted and going on the sale; an existing item chosen with Use this item is never counted (issue #615) | `retail.sale.create` (Add it here: `retail.catalogue.manage`, others read Ask the owner to add it; the count: `retail.stocktake.commit` at the branch) |
| `/staff/retail/restock` | Buy stock: supplier, lines, per-branch quantities; under the item search, Not in the list? Add it here (issue #505): Item code shown as text, made by the app from the category (never typed, issue #562), Category, Description, Counted in, Buying price, Selling price and the quantity at this branch, one Save that makes the item and puts it on the purchase; a line bought in a pack asks How many pieces in each carton? (issue #554, ADR-082: required, filled with the usual size or by a scanned carton barcode, and Use 30 from now on for a different size, offered with `retail.catalogue.manage`) | `retail.purchase.create` (Add it here: `retail.catalogue.manage`, others read Ask your manager to add this item) |
| `/staff/retail/usage` | Usage and damage | `retail.usage.report` |
| `/staff/retail/credit-sales` | Credit sales, grouped by a chain of levels the user chooses (issues #328, #341): buyer, date, amount, due date, state; open a sale | `retail.sale.read` |
| `/staff/retail/sales` | All sales, grouped by a chain of levels the user chooses (issues #328, #341): filters by dates, buyer, paid by and item; open a sale | `retail.sale.read` |
| `/staff/retail/stock` | Stock per branch or All branches, search, category filter, Out of stock and Low stock tabs, negative flag | `retail.stock.read` |
| `/staff/retail/stocktake` | Count stock: count, review the difference, commit; the same Add it here as Buy stock, for a first count, with How many you counted (the buying price only with `retail.profit.read`) (issue #505) | `retail.stocktake.commit` and `retail.stock.read` (Add it here: `retail.catalogue.manage`) |
| `/staff/retail/quick-add` | Quick add (issue #632, ADR-101): Name, Selling price, Buying price (optional, with `retail.profit.read`), How many do you have here now?, Where (the branch in use first, stores too; the kit's EmptyChoice when there is no branch to add stock at), Category (optional, left out it is General; needed while General is switched off), the unit (the shop's piece unit; needed while Piece is switched off) and Photo, one Add; it opens with no field focused, so a tour over it never shows an error before the person types (issue #642); then the line "Test bulb added: 600 pc at Test Store" with Add another (category and where kept) and Sell it (the sale opens at the branch the stock was added at; when another branch is in use it says so, with Switch to that branch and Sell here without it; an item the branch's list does not return says "Search for it to add it", never dropped in silence, issue #641); while an Add is not confirmed every field is locked and Add sends exactly the same again (the line names the button that sends again: Add, or the name panel's button while the panel shows, issue #641), and Start again shows only after a certain refusal; a repeated name offers Use the existing one and just add stock, which adds the number typed ("8 pc added at Test Store, 58 pc now"), or Keep the existing one for someone who may not add stock; offline it says it needs the internet. I just bought these, take the money from cash (off by default, for whoever holds `retail.purchase.create` at Where and may see the buying price; issue #646): the Buying price is then needed, Cash or Mobile money is the kit's Segmented (rule 11), a one line note shows the total that comes out and of which account ("UGX 30,000 comes out of Cash at Test Shop") before Add (rule 8, `money()`), and the answer names the purchase; moved to a place where they may not buy, the tick stays with a line saying so and Add sends nothing. Reached from the Stock menu and More, the first-run card (Quick add an item, its first action), the catalogue home, the Items page and Sell's not-found line (with what was typed) | `retail.catalogue.manage` and `retail.stock.read` (the stock: `retail.stocktake.commit` at the branch, without which the item is added with no stock; paid from cash: also `retail.purchase.create` there) |
| `/staff/retail/transfer` | Move stock: to which branch, items with the source's stock, date and note | `retail.stock.transfer` and `retail.stock.read` |
| `/staff/retail/transfers` | Stock moves from or to the branch; open one; cancel it | `retail.stock.read` (cancel: `retail.stock.transfer`) |
| `/staff/retail/stock-by-location` | Stores and shops: every product in each branch, stores first, a subtotal per role, stock on the way, the combined total (#193); only while `branch_roles` is on | `retail.stock.transfer` and `retail.stock.read` (the API needs only the read; value: `retail.profit.read`) |
| `/staff/retail/in-transit` | On the way: transfers sent and not yet received, the longest wait first; check what arrived and mark received, or take back (#193); only while `transfer_receipt` is on | `retail.stock.transfer` and `retail.stock.read` (receive at the destination, take back at the source) |
| `/staff/retail/valuation` | Stock at price; at cost too with `retail.profit.read` | `retail.stock.read` (cost columns: `retail.profit.read`) |
| `/staff/retail/profit` | Daily profit per branch, with the stock-take difference as its own column (#121) | `retail.profit.read` |
| `/staff/retail/sales-analysis` | Sales analysis: totals by day, week or month, top items as bars, by branch, category, item and seller, slow movers, items with no sale (#149) | `retail.sale.read` (profit columns: `retail.profit.read`) |
| `/staff/retail/margins` | Margins: profit and margin by item and category, items under a margin target, price change impact (#149) | `retail.profit.read` |
| `/staff/retail/stock-health` | Stock health: days of cover, reorder suggestions, slow movers (#414), dead stock, used, damaged and found missing (#149) | `retail.stock.read` (cost: `retail.profit.read`) |
| `/staff/retail/credit-control` | Credit control: what credit buyers owe by age, the overdue list, payments received (#149) | `retail.sale.read` |
| `/staff/retail/evaluation` | Business evaluation: profit to date, expected profit of the stock at today's prices, a 30 day pace (#149) | `retail.profit.read` |
| `/staff/retail/catalogue` | Catalogue home: links to the screens below the session may use | any one of the catalogue screens' permissions |
| `/staff/retail/catalogue/products` | Items: list with search, category and on sale filters; add, edit, change price, price history (#146); the Item code is shown as text on add (the category's next code, made by the app) and on edit (it never changes), never an input (issue #562); Add an item ends with an optional Photo (Take a picture or Choose a photo, sent right after the item is made, issue #617), and Edit shows the photo first, under the name | `retail.catalogue.manage` and `retail.stock.read` (Change price: `retail.price.edit`; cost: `retail.profit.read`) |
| `/staff/retail/catalogue/categories`, `/units` | Add, rename, switch off or on, with the number of items using each; a category shows its code start and next code and opens the category page (issue #493); the add form does not ask for a code start, it shows the one the app makes from the name (issue #562) | `retail.catalogue.manage` and `retail.stock.read` |
| `/staff/retail/catalogue/categories/$categoryId` | One category (issue #493, ADR-081): how its item codes are made, its items, Add an item with the category fixed (no dropdown; the Item code is shown as text, the next code such as BLB-0001, made by the app and never typed, issue #562), Change the code start (items made before keep their codes) | `retail.catalogue.manage` and `retail.stock.read` |
| `/staff/retail/catalogue/suppliers` | Add, edit, switch off or on | `retail.catalogue.manage` and `retail.purchase.create` |
| `/staff/retail/catalogue/buyers` | Credit buyers with what each owes; add, edit name and contact | `retail.customer.manage` and `retail.sale.read` |
| `/staff/retail/catalogue/import` | Import items from a CSV file or pasted rows: check first, then add (#146); the tick Let the app make the codes, unticked by default, makes every item's code and leaves out the file's, and a file already checked is checked again when the tick changes (owner decision D45, issue #562) | `retail.catalogue.manage`, `retail.stock.read` and `core.settings.manage` (administrators) |

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

- **Starting branch (#103).** The branch in use (`useActiveBranch` in `areas/staff/branch-picker.tsx`, switched from the title of
  the top bar since #526) starts on the last
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

- The retail home shows one large tile per screen, with an icon, two per row on a phone. Each tile has its own icon
  (`icons[screen]`, issue #483), the same one its row shows in the staff menu; so do the catalogue tiles (`TILE_ICONS`).
- The staff menu lists the shop's pages in groups (issue #564, ADR-086): Today (Sell, Orders with the open count),
  Stock (My stock with "N low", Buy stock, Count stock, Move stock, Items and categories) and Money (Cash book, Expenses,
  Customers who owe, What I owe suppliers, Savings and advances), then Reports under Business, which opens this retail
  home with the figures and every shop page. Each row shows only when the session may use its page, with no cap (the five
  retail shortcuts of issue #455 and the Retail item they hung under are gone). On a phone the bottom tabs hold the daily jobs
  (issue #539): Sell, My stock (with the count of items low on stock), Buy, Money and More, each shown only when the session
  may use it, and the sale's sticky total sits on top of them; the menu button at the top left opens the same menu as the
  laptop's sidebar.
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

A sale line's quantity is the kit's Stepper (issue #520): minus and plus either side of the number, from 1 up, and the
number can still be typed (1.5 for a part of a kilo). Minus and ArrowDown never go below 1, so on 1.5 kg minus gives 1, and
below 1 (a typed 0.5 kg) minus is greyed out and ArrowDown does nothing: a part below 1 is typed, never stepped to. This is on
purpose, not a fault. The Stock screen's All items, Out of stock and Low stock, and every other
row of pick-one buttons in the retail screens, is the kit's Segmented control.

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
- **Out of stock and Low stock.** Tabs on Stock (`stock_level=out|low`): out is zero or less, low is at or below
  the item's minimum (the branch's own, else the one for every branch) or 5 or fewer without one, and so includes
  out of stock. Both keep only items the branch has held (issue #490, ADR-077). The threshold is a constant in the
  API (`StockService.LOW_STOCK_MILLI`, ADR-029); in All branches an item is low when it is low at any listed branch and out
  when its total is zero or less. The hints say
  "Items you have stocked with none left, or less than none." and "Items you have stocked that are at or below their
  minimum, or 5 or fewer without one, including those out of stock."; the dashboard's Low stock figure says "at their
  minimum, or 5 or fewer without one".
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
  for a new item (the server refuses it with 422 `inactive_category`, `inactive_unit`). A switched-on unit also has
  Usual pack (issue #637), offered unless another unit's usual pack counts it (one level only): a Sheet
  (`unit-usual-pack.tsx`) with It holds (units that are not packs themselves; with none, the kit's EmptyChoice and Add a
  new unit), How many in one, Used for (Segmented, `PURPOSES`), Save and Remove usual pack; items keep their packs.
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

## Analytics screens (#149)

The analytics pass is reports only, on existing data (`docs/sdd/07-api-design.md` section 7.11.20, `GET /retail/reports/...`).
Each screen is a date range (the last 30 days, at most 366, checked on the screen as the server checks it), a
"Rows to show" choice (the `top` limit of every list) and its own few choices, then sections. They share
`analytics-ui.tsx` (date range, choice, section, `Bars`, `Sparkline`) and read through `api/retail-analytics.ts`.

- **Phone first.** A table with a name and one or two figures; on a phone a second figure goes under the first or into
  the grey line under the name (a profit under the sales, the quantity and margin under an item), because two money columns
  of seven digits do not fit in 360px. A price change, a shrinkage line and a number of stat tiles are cards on a phone.
  Dates are written year first (2026-10-07) so they sort as they read.
- **Drawn, not charted.** The top ten items are `Bars` (a track and a fill as inline SVG, scaled to the largest), the
  sales over time and the 7 and 30 day trend are `Sparkline` (a polyline). Both are decoration beside the figures, which are
  always in text, and have no inline style: the colours are classes on the tokens (`.bar-fill`, `.spark-line` in
  `retail.css`). No chart library.
- **Profit is only what the server sent.** Every profit, margin and cost column is drawn when its field is in the body
  and not otherwise. The component tests strip those keys the way the server does and check that no word of cost, profit or
  margin is left on the page. Margins and Business evaluation need `retail.profit.read` as a whole and the other screens show
  their profit parts only for it.
- **The retail home is the owner dashboard (#149 step 6).** Today (sales, cash and mobile money, credit, profit), the 7
  and 30 day sparklines, stock at price and at cost with the out of stock and low stock counts, and a table by shop; the
  tiles of the screens follow, each only with its permission. For an owner (a profit reader) the dashboard comes first. For a
  seller the tiles come first (Record a sale is one tap away) and the sales and stock parts follow. Two dashed slots,
  "Banked against expected" and "Savings", were placeholders for the cash book until the range of #428 filled them.
- **The home follows one range (#428, ADR-060).** A row of buttons under the heading: Today, 7 days, 30 days, This year,
  1 year, All and Choose days (two dates and Show these days; at most 366 days, checked before asking). The choice is kept per
  person in this browser (`bms.homeRange.<host>.<user>`), 7 days at first. Under it: the total of the range in large type with
  Up or Down, the amount and the percent on the period before (green up, red down, with an arrow and the words); the chart,
  320 by 150 in SVG, the range as a line and the period before dashed, a step picked by hover, tap or the arrow keys and read out
  under it ("Wed 21 Oct UGX 3,600 (before: UGX 1,500)"); then cards for the number of sales, average sale, profit (profit
  readers), money collected and on credit now (`on_payment`), busiest day, best shop, best seller and top salesperson (profit
  readers), each with its change; links to Sales analysis and All sales; the shops in the range with the period before; the stock
  now (it does not follow the range, and says so); and, for a person who closes the till, the cash book of the range (banked,
  expected to bank, savings set aside, expenses) up to 31 days, with links to the Cash summary and the Banking report. A seller's
  Your sales card has the same row and chart from their own sales only. The home tour (version 3) has the step on the row.
- **Business evaluation says what it is.** The page opens with "an estimate from today's prices and the last 30 days
  of sales; not a forecast or a promise" (the server sends the same sentence as `note`), and repeats it at the end.
- **Credit control** counts days from the due date; a sale with no due date counts as not yet due, as the line under the
  title says.

### Verification of the analytics screens (#149)

The real stack as in `docs/ui/design-system/analytics/README.md` (API jar from `mvn package` at V28, the built PWA served
by a small static server that proxies `/api`, headless Chromium at 360px and 1280px, a real invitation, password and TOTP
sign-in as the tenant administrator): a fabricated tenant with three shops, fifteen items in four categories, 44 days of
sales by three sellers (cash, mobile money and credit), restocks, usage and damage, a stock-take that found two items
missing, three price changes with sales after them, and credit sales part paid. All six screens (and the home) loaded
with no console error and no failed request; none overflowed sideways at either width. The first pass found and fixed:
tables with a sales and a profit column cut off on a phone, the price change table (now a card each), the dead stock
and shrinkage tables, a "Biggest amount first" choice cut short, the By shop table beyond the column on a wide screen
(the home is the wide column when it carries the dashboard), and "1 reports". Not walked: a seller session (its
screens are covered by the component tests and the API tests for the permission and the profit gating).

## The cash book screens (#147)

Savings, Banking, Banking report, Expenses, Expenses report, Expense lists, Cash withdrawals, Advances and Cash
summary, each shown only with its permission (`docs/specs/retail-cash-book.md`). A real-stack run: the API on
the dev profile over PostgreSQL migrated to V34, the PWA from the Vite dev server, headless Chromium at 360px and
1280px, a real sign-in with TOTP, on a fabricated tenant ("Sample Shop (fabricated)", one head office, three
products, five days of cash and mobile money sales, a cash restock, expenses, savings, two bankings, a
withdrawal and an advance with a repayment). The cash book screens were opened at 360px and 1280px and none
overflowed sideways; only ten captures are committed in `docs/ui/design-system/cashbook/` (not every screen at both
widths: see its README), and the script is not committed. Notes:

- The savings, banking and expenses screens follow the sale screen: one branch from the Branch box
  (`BranchRequired` otherwise), the date in the business time zone, a success message and a toast. The toast is
  its own small helper in `retail/toast.tsx`; the catalogue screens (#146) added `useToast` to `retail/ui.tsx`
  meanwhile, and the two should be reconciled.
- A caller without `retail.profit.read` sees the day's total sold and "Cash expected" only: no savings amount,
  suggestion, profit, cash purchases, net expected amount, difference or running unbanked total, and the savings
  form sends no amount.
- The bottom bar now carries Retail plus up to five shortcuts in the pilot's order (Sale, Banked, Savings,
  Expenses, Advances), so an administrator's bar no longer shows Stock and Restock; both stay on the Retail home.
  Six items still fit at 360px.
- The receipt photo is skipped: the frontend has no documents upload client yet.
- The savings report endpoint (`GET /retail/reports/cash/savings`) is built but has no screen; the savings
  records list covers it. CSV export of the reports waits for the report runs.

## Retail settings and branch roles (#192, ADR-033)

The administrator's two screens for the trade capabilities, and the rule every capability screen follows.

- **Retail settings** (`/staff/retail/settings`, tile "Retail settings", `core.settings.manage`): the profile as a
  choice of Shop or Trade, and a switch for each of the eight features. Choosing a profile writes its preset over
  the switches (Trade: branch roles, pack sizes, price lists, customer accounts, returns, received transfers;
  documents and purchasing stay as they are, spec 1.2) and every switch stays editable. "Review changes" reads the
  change back in words (the features a preset switches on, the features switched off and that their data is kept);
  "Confirm" sends `PUT /retail/settings/capabilities` with the settings version as `If-Match`, and the server audits
  the old and new values. Afterwards `/me` is read again, so the menu follows at once. A feature whose pages are not
  released yet says so; it can be switched on and changes nothing visible.
- **Branch roles** (`/staff/retail/branch-roles`, tile "Branch roles", `retail.branch_role.manage`): one choice per
  branch (store, shop, both). It exists only while `branch_roles` is on.
- **The rule** (`SCREEN_CAPABILITY` in `retail/permissions.ts`): a screen of a capability is offered, in the tiles,
  the menu rows and the route gate, only while `/me` lists the capability, whatever permissions the user
  holds. A screen not listed there behaves as before capabilities existed, so a shop (every capability off) sees the
  same menu as today plus, for the administrator, the Retail settings tile. A later slice adds its screens to that
  map, sets `ready` for its capability in `retail/capabilities-state.ts` and adds its tour; `capabilities.test.tsx`
  fails if a ready capability has no tour.
- **Tours**: a tour with a `capability` starts by itself once, the first time a user it is for signs in with that
  capability on, after the first-run tour; a dismissal is for good and a new version shows once more. The Retail
  settings page has a page tour in Help. Built: the branch roles tour.

## Credit sales: when they count (#340, ADR-043)

- **Retail settings** gains one question under the features: "When does a credit sale count as income and profit?", two plain choices (When the sale is made, today's way and the default; When the buyer pays). A different choice is read back in words and confirmed before it is saved; the server audits it. The rule is one way: it starts the next day, a pending change can be cancelled by choosing the first answer, and once it has started the first choice is off and the page says "This rule cannot be switched off once it has started. Ask Rincol support." The page tour has a step for it (page tour version 3).
- **Payment date.** On a credit sale the server marks `counted_when_paid` (the later of its date and the day it was entered is on or after the first day of the rule); only then is the payment form's date fixed to today, and the date sent is always today, whatever a stored draft holds. A payment on account is dated today in the same way when any open sale it would pay is marked. A sale entered and dated before the start keeps a free date.
- **Profit and home figures** read the when-paid way once the rule has started and a report holds a day from the start on or a credit sale counted when the buyer pays; a report of earlier days keeps the plain layout, exactly as before. Received is the money recorded as collected on the day it was entered, by any method: sales paid at the sale and every payment recorded on a credit sale, old ones and imported ones included (not a claim that it equals the drawer). The credit profit tile leaves imported credit sales out. The report table has a Received column beside "Counted for profit", and a tile "Net credit profit counted at the sale" (from credit made before the rule started, after returns; in the profit, not taken off Received) when there is one. They say when the rule starts in one line (`credit_recognition_starts_on`).
- **Home cards** under "When the buyer pays": Sales today (everything sold, credit included, with the count), Money collected (money recorded as collected today by any method, less money paid back, payments on earlier and imported credit sales included), On credit (what buyers still owe and how many sales, with a hint that it is not profit) and Profit today (from the money received). The 7 and 30 day tiles keep Sold and add Received. With the default the cards are exactly as before.
- **Daily profit** under "When the buyer pays": Received (the cash) and "Counted for profit" are two columns, Sold is a column and a headline figure beside them, On credit now is a headline figure, and the profit line says it is from money received when nothing was counted at the sale. The savings screens read the same profit.

## Trade screens (#193, ADR-033)

Two screens and a changed one, all behind a tenant capability that `/me` lists as `module_capabilities.retail`;
with every capability off the menu, the tiles and every screen are today's (`trade-stock.test.tsx` proves it).

- **Stores and shops** (`/staff/retail/stock-by-location`, capability `branch_roles`). A wide screen draws a column
  per branch under its role (stores, then shops, then both), a Subtotal column where a role has several branches,
  then On the way and Combined; a phone draws a card per product with the combined total, a line per role and what is
  on the way, and a button that opens the branches. The stock on its way is in neither branch's count, so Combined is
  the balances plus that column. Value at cost, by role and combined, shows only with `retail.profit.read`.
- **On the way** (`/staff/retail/in-transit`, capability `transfer_receipt`). One card per open dispatch: the route, the
  date and how long it has waited, the items, and the value at cost with profit read. Where the user may act at the
  destination the card has "Check what arrived" (each item prefilled with what was sent; less than sent is allowed
  and says it is written off as a loss; more than sent is refused before the server is asked) and "Mark as received";
  where they may act at the source it has "Take this back" with a reason. A user holding the permission at neither
  sees the list only. Someone who reads every branch with cost also sees whether the books agree with the list.
- **Move stock** says "Send stock" while `transfer_receipt` is on and tells the user that the other branch counts it
  once they receive it. **Stock moves** shows "on its way", "received" or "received, some short" (less arrived than was
  sent), "taken back" (the source cancelled it) and keeps "cancelled" for a void; a received or completed move can be
  cancelled as before, a move on its way is taken back from On the way.
- Tours: page tours for both new screens start only from Help, for a user who holds the permission on a tenant with the
  capability on (`tours.ts`, `tours.test.tsx` renders each anchor).
- The mock (`retail-mock.ts`) answers the same shapes; `setMockTransferReceipt(true)` and `setMockBranchRoles` switch the
  two capabilities for tests and `VITE_RETAIL_MOCK` sessions (`retail-two-step.test.ts`).

## Breadcrumbs and category groups (issue #240, FR-UXN-01 and FR-UXN-02)

Frontend only: no API, schema or permission change.

**Breadcrumbs.** One trail under the staff bar on every staff screen, drawn once by the staff layout
(`StaffBreadcrumbs` in `components/breadcrumbs.tsx`). It is not a second map of the app: each route in
`app/router.tsx` has a `crumb` label in its static data (`retailScreen(path, label)`,
`lendingScreen(path, label)`), and the trail is the labelled routes whose path is a prefix of the open one, for
example Home / Retail / Catalogue / Items. A new route gets its place by declaring a label;
`breadcrumbs.test.tsx` fails when a staff route has none.

- A routed detail page names its crumb after the entity the screen already loads (`useCrumbLabel`: loan,
  investment and savings account numbers) and shows the generic label while it loads. Nothing is requested for
  the name.
- A detail view inside a screen adds a last crumb (`useCrumbTail`): the open sale ("Sale <number>"), the open
  stock move, the item being edited or priced, and the whole-business stock view ("By location", the stock
  screen with All branches chosen). Where the screen can go back, its own crumb becomes the button that does.
- Markup: `<nav aria-label="Breadcrumb">` with an `<ol>`; the last item is a `<span aria-current="page">`, not a
  link. Labels longer than 16rem are cut with an ellipsis (the full text is the title). Below 720px the trail
  collapses to "Back to <parent>" and the current page.

**Category groups.** Stock per branch, stock across branches (table, and cards on a phone) and the catalogue
items group by category (`retail/category-groups.tsx`). The item pickers no longer do: since issue #534 they
list matches only while something is typed (see "Item pickers" below). The low and out of stock lists are the same Stock screen with its level filter,
so they group too.

- A header per category (a button with `aria-expanded` and `aria-controls`) shows the count and, on the stock
  lists, a subtotal: the quantity when every item of the group has the same unit, and what the group holds at
  the selling price. The catalogue shows the count only. Expand all and Collapse all sit above
  the list.
- Categories are A to Z; items without one are in "Uncategorised", last; items keep the order the server sent.
- Sections start open for 30 items or fewer and closed above that. A search (the stock and items search boxes)
  opens them, so a match inside a closed section shows. The default is decided when results first
  arrive for a search and filters and held while more load, so "Show more items" never opens or closes a section.
- The "Group by category" switch is remembered per screen (`stock`, `items`) in `localStorage`, inside
  try and catch: with storage blocked it defaults to on and is forgotten at reload.
- Grouping is within the items the screen holds. Stock is not paged. The catalogue items list is
  (cursor paging): its hint says "Grouped within the items loaded so far. Show more items loads the rest."
- Search and filters stay the screen's own (plain component state, sent to the server as before); the grouping
  takes the already filtered items. Grouped lists drop the Category column and the per-row category label,
  since the header says it.

## Units of measure (#194, ADR-033)

While `/me` lists the `units` capability for retail, an item that has pack sizes shows a "Sell by" choice on the sale screen and "Bought by" on the
restock screen (an item without packs, and every screen while the capability is off, is exactly as before). Choosing a pack changes the labels to the
pack's unit (an item with packs may be added to the sale a second time, for cartons and loose pieces in one sale), sets the price or cost to the pack equivalent (`sell x factor`, rounded half up), and checks the stock and the price floor in base units, as
the server does (`units-state.ts`). The receipt and All sales show a pack line as "2 carton" at the pack price; an older line shows as it always did. The
item screen (Edit) has a Pack sizes group for a user who may manage the catalogue: add a pack (a unit other than the item's own and a number of items in
one pack), change the number, switch a pack off. The unit choice on the sale screen carries `data-tour="sale-unit"`. The pack sizes capability has a tour that starts once for
whoever manages the catalogue (`capability-units`: an introduction and the Catalogue tile); `units` is marked ready in `capabilities-state.ts`.
It adds no routes, so there is no new breadcrumb: the item screen already adds the item's name as its last crumb.

## Price lists (#195, ADR-033)

Two screens exist while `/me` lists `price_lists` and the user holds `retail.pricelist.manage` (and, for the first, `retail.stock.read`):
**Price lists** (`/staff/retail/price-lists`, crumb "Price lists") and **Customer groups** (`/staff/retail/customer-groups`, crumb "Customer groups"); both are
in `SCREEN_CAPABILITY`, so they are offered nowhere while the feature is off and a seller never sees them. Price lists: a card per list (rename, make strict, switch
off, never delete) and Prices, which opens the list's items with the usual search (`type="search"`, the debounced query of the other lists), "Show ended prices" and
Show more. Add a price finds an item with the product search, then the unit (its base unit or an active pack), the quantity break ("leave empty for any quantity"), the
price, the first day (today or later) and the optional last day; a price not above cost is flagged before saving, but only for a session that holds `retail.profit.read`.
A price is never edited: an open price is ended ("End" sets today as its last day), and a new open ended price takes over from the current one on its first day. The Credit
buyers screen shows each buyer's group and own list and, for `retail.pricelist.manage`, a Change prices form; the buyer's own list wins over the group's.

On the sale screen, while the feature is on, a customer can be named on any sale ("Customer (optional): sets the price list"); the form asks `POST /retail/prices/resolve`
for the lines (debounced) and shows the resolved price with its note ("Price list Wholesale, from 10", "Retail price") under each line (`data-tour="sale-price-source"`), the list
in force for the sale (`sale-price-list`) and, for a user holding `retail.pricelist.use`, a choice of another list. A user without `retail.price.override` sees the price
read only and a sentence saying so; with it, typing another price shows the old one, asks for a reason of at least five characters and offers "Use the list price". Save waits
for the resolution and is refused for a line a strict list has no price for. The receipt and All sales show each line's note and, for a changed price, the old price and the
reason (`units-state.ts` and `price-state.ts` hold the pure rules). With the feature off none of this is drawn and the form is exactly what it was. `price_lists` is marked
ready in `capabilities-state.ts` with its tour (`capability-price-lists`: an introduction, the Price lists tile, the lists) and a Help page tour (`page-price-lists`).

## Customer accounts (#196, ADR-033)

Three places show it while `/me` lists `customer_accounts` (`SCREEN_CAPABILITY`, so none of them is drawn while it is off). **Customer accounts**
(`/staff/retail/accounts`, crumb "Customer accounts", tile `retail-customerAccounts`) is the administrator's page (it needs `retail.credit.manage`,
which the sales role lacks, so a seller sees the same tiles with the feature on and off): the credit buyers with the shared live search, each with what
they owe, the limit and the terms, and Open account. A seller reaches the same account from **Credit buyers** (each buyer shows the limit and terms and an
Open account link) and from **Credit control** (the buyer's name links to the account, a Limit column appears, and a buyer is marked above the limit only when the
server sends `over_limit`, which it does for a report over every branch, because the limit is measured across all of them; a branch's share is never compared with it). **The account** (`/staff/retail/accounts/$customerId`, crumb "Account", named after the buyer) is open to whoever reads sales: the summary (what
is owed over the branches the caller may read, the limit with what is left when the caller sees every branch, the terms, and for `retail.credit.manage` a
form to change the limit and terms under `If-Match`), **Take a payment** (branch that received the money, amount, method, date; the oldest sales are paid
first and the form lists where the money would go before anything is saved; "Choose which sales it pays" lists the sales with an amount box each; imported
sales are never offered), **Payments taken** (each receipt with its allocations; Reverse, with a reason of at least five characters once trimmed, the rule the server applies too, for
`retail.sale.void`; each receipt has its own Idempotency-Key, repeated only on a retry of that receipt with the same reason; to allocate differently, reverse and take it again) and the **Statement** (From and To, 90 days by default ending today in the business
time zone; opening balance, each line with its running balance, closing balance, and imported sales apart under "Before system start").

On the sale screen with the feature on, a credit sale needs a buyer from the list ("Add this buyer" creates one first); the buyer's limit and terms show under the
choice; the date is optional when the buyer has terms (the server dates the sale); a sale refused for the limit says by how much, and a user holding
`retail.credit.override` at the branch gets a reason box and saves again to sell above the limit. `customer_accounts` is marked ready in
`capabilities-state.ts` with its tour (`capability-customer-accounts`: an introduction, the tile, the list) and a Help page tour (`page-accounts`). **Customer ageing**
(`/staff/retail/customer-ageing`, crumb "Customer ageing", tile `retail-customerAgeing`, linked from Customer accounts; the administrator's tile like Customer
accounts, so a seller's menu does not change) reads `GET /retail/reports/customer-ageing`: the total by age bucket and one row a buyer with the limit, the terms,
the buckets and the days late, the Top number, the shared live search box narrowing the rows already loaded by name, and the account link. The limit and terms
form sends each field as a value or an explicit clear (`clear_credit_limit`, `clear_terms_days`). The
pure rules (the preview of an allocation, the checks of a payment and of a limit, the words of a statement) are in `account-state.ts`.

## Customer returns (#201, ADR-033)

Three places show it while `/me` lists `returns` (`SCREEN_CAPABILITY`, so none of them is drawn while it is off), and only to whoever holds `retail.sale.return`
(administrators), so a seller's menu is the same with the feature on and off. **Customer returns** (`/staff/retail/returns`, crumb "Customer returns", tile
`retail-returns`) lists the credit notes newest first with the shared live search (credit note number, sale number or buyer; the term is never put in the address) and a
Standing or Voided filter; each row shows the number, the date, the total, the sale and how it was settled, and Open. **Take goods back**
(`/staff/retail/returns/new/$saleId`, crumb "Take goods back", named after the sale) is reached from **Return goods** on an open sale in All sales or Credit sales (shown
for a completed sale at a branch where the caller holds the permission, together with a list of the returns already made against it). For each line it shows what was
sold, what has come back and what can still come back, and a quantity box; once a quantity is typed it asks why (the reason codes), what happens to the goods (put back
on the shelf at a branch or store, or damaged and written off, which names no branch) and, for a line sold in a pack, whether the quantity is counted in the pack or in
single units. The credit of each line and the credit note total are shown before saving with the server's maths (`returns-state.ts`: a share of what the line was charged,
the last piece taking the remainder). **How the customer is repaid** offers cash, mobile money, bank, or "Take it off what they owe" (only for a credit sale that owes; for a
cash sale, an imported credit sale or one that is paid up the option is shown and cannot be chosen, with the reason beneath); when the credit is more than is owed, the form says how much comes off the balance and asks how the rest is paid out. A "Pay the money out of the till of" box appears when money is paid out and the person may use more than one branch. The
button waits for a form the server would accept, and the draft and its Idempotency-Key survive a reload (`usePersistedDraft`). A return that takes the sale's returns to the tenant's
threshold answers "needs a second administrator" instead of a credit note, and so does voiding a return that was approved. **Retail settings** shows one neutral Hint line
while returns are on and no approval limit is set ("Returns need no second administrator yet.", the full explanation behind the Hint's More (its info button, "Show more"); issue #575: no amber on a normal screen, held by `design-guard.test.ts`). No screen sets the limit yet (the approval limits screen is issue #343), so the Hint has no link; it gains the link with #343. **The credit note** (`/staff/retail/returns/$returnId`, crumb "Credit note", named after
its number) shows the facts, each line with its reason and where the goods went, the total and how it was settled, and for `retail.sale.return` a Void form that needs a
reason of at least five characters and says what voiding does (the goods come off the shelf again, the money or balance goes back); the printable credit note is the
documents slice. The customer statement names a return "Goods returned, credit note" and its void "Credit note voided". `returns` is marked ready in
`capabilities-state.ts` with its tour (`capability-returns`: an introduction, the tile, the list) and a Help page tour (`page-returns`). The mock
(`retail-returns-mock.ts`) holds the server's rules in small for development and tests.
## Optional documents (#197, ADR-033)

Four places show it while `/me` lists `documents` (`SCREEN_CAPABILITY`, `canIssueDocuments`), and only to a user holding `retail.document.issue` (the
administrator's), so a seller's menu is the same with the feature on and off and a sale never needs any of it. **Documents** (`/staff/retail/documents`, crumb
"Documents", tile `retail-documents`) lists every invoice, delivery note and quotation in the branches the session may read, newest first, with the shared live
search (the number or the buyer), a type filter, Show more, and an Open PDF button on each card (a document whose PDF was never stored is rendered from its snapshot
first, in a tab opened before the waiting so it is not taken for a pop-up); a document whose sale or quotation was cancelled is marked Void and its PDF is said to stay
as issued. Under the list **Document numbers** shows each series (the prefix, and per branch the next number and how many were issued); whoever holds
`core.settings.manage` changes a prefix (capitals and digits, starting with a letter, as the server checks) and later numbers take it; a credit note has no
series (the return numbers it). **Quotations** (`/staff/retail/quotations`, crumb "Quotations", tile `retail-quotations`) lists them with a status filter and the same search;
New quotation (one branch chosen at the top; a store is refused by the server) takes the buyer (a credit buyer from the list or a typed name and phone), the last day (thirty
days ahead by default, never before today), notes and items added with the same picker and line cards as the sale (pack units, a typed price, no stock check because a quotation
reserves nothing), with one idempotency key per draft kept in the tab like a sale. An open quotation can be cancelled with a reason, or made into a sale (how it is paid, a
date for credit unless the buyer has terms, keep the quoted prices, also make the invoice) by a user who holds `retail.sale.create` at its branch; an expired one says it cannot be
converted. **A sale** shows a Documents panel under the saved receipt and under an opened sale (All sales, Credit sales): the documents it has, and Make invoice and Make
delivery note for those it lacks (a checkbox shows the prices on the delivery note, off by default); a voided sale offers none. **A stock move** shows Make delivery note on its
detail (quantities only). **A credit note** (Customer returns, #201) shows "Open the credit note as a PDF" while `documents` is also on, for a user who may issue documents at its branch; the PDF carries the return's own number. **A buyer's account** shows "Open the statement as a PDF" under the on-screen statement while `customer_accounts` is also on. `documents` is marked
ready in `capabilities-state.ts` with its tour (`capability-documents`: an introduction, the tile, where documents are made) and Help page tours (`page-documents`,
`page-quotations`). The pure rules (the words and state of a document, the documents a sale may still be given, the prefix rule, the quotation form and its request, the
conversion choices) are in `documents-state.ts`; `retail-documents-mock.ts` is the fabricated server for `VITE_RETAIL_MOCK` and the tests.

## Optional purchasing (#198, ADR-033)

Everything here is offered only while `module_capabilities` lists `purchasing` (`SCREEN_CAPABILITY`), and the Restock screen is untouched. Orders and
receipts need `retail.purchase.create`; invoices, payments and the payables report need `retail.payable.manage` (administrators); the tolerances
need `core.settings.manage`. Each page has a breadcrumb and a live search where it lists (the shared search; the term is never in the address).

- **Purchase orders** (`/staff/retail/purchase-orders`): status chips (draft, sent, partly received, received, cancelled), search by order number or
  supplier, New purchase order. **New purchase order** and **Change order** (`/purchase-orders/new`, `/purchase-orders/$orderId/edit`, a draft only,
  saved under `If-Match`) take the supplier, the expected date, a note and lines (item, unit when pack sizes are on, quantity, cost per unit as entered)
  with the totals worked out as the server rounds them. **The order** (`/purchase-orders/$orderId`, crumb "Purchase order") shows each line as ordered,
  received and, for `retail.payable.manage`, billed, the receipts against it, and the actions Send (a 202 says it waits for a second administrator
  holding `retail.purchase.approve`, decided in the approvals inbox), Change, Cancel (a reason of at least five characters) and Receive goods.
- **Receive goods** (`/purchase-orders/$orderId/receive`) and **Receive without an order** (`/goods-received/new`) are one form: the branch or store
  the goods go into (only branches in scope), the quantity of each line (pre-filled with what is still to come), its cost (pre-filled from the order)
  and an optional new selling price, the delivery note number and a note. The server's refusals are shown in plain words (more than ordered, not a
  whole number of base units). A line in a pack asks How many pieces in each carton? (issue #554, ADR-082), as Buy stock does. **Goods received** (`/goods-received`) lists the deliveries; a delivery (`/goods-received/$receiptId`, crumb "Delivery")
  shows its lines and what is billed, and voids with a reason, with the two refusals (an invoice bills it; some of it is sold) explained.
- **Supplier invoices** (`/supplier-bills`, open-only toggle) and **New supplier invoice** (`/supplier-bills/new`): choose the supplier, tick the goods
  received and not yet invoiced that the invoice is for, type the invoice number, dates, the supplier's price and the total as printed. The page asks
  the server to match it (`POST /retail/supplier-bills/match`) as the form changes and shows each line as invoice says, received at and difference,
  the tolerance, a warning when the total is not the sum of the lines and, when the difference is above the tolerance, a required reason. **A supplier
  invoice** (`/supplier-bills/$billId`) shows the lines, paid and open, and voids with a reason (refused while a payment stands).
- **Supplier payments** (`/supplier-payments`) and **Pay a supplier** (`/supplier-payments/new`): supplier, paying branch, cash, mobile money or
  bank, amount, reference and note, paid oldest first or by choosing what to pay (an amount against each open invoice or credit restock, the sum
  checked against the payment before saving). **A payment** (`/supplier-payments/$paymentId`) lists what it paid and reverses with a reason.
  **What you owe suppliers** (`/supplier-payables`) is the report: per supplier what is owed by days past due, the total, and the goods received
  and not yet invoiced beside it; a row starts a payment for that supplier.
- **Buy on credit and pay suppliers without purchasing** (issue #557, owner decision D15, ADR-084). Buy stock asks **Paid how** (required): Cash,
  Mobile money, Bank or On credit (owed to the supplier); On credit marks the supplier required and says "On credit needs a supplier" until one
  is chosen or typed. **What I owe suppliers** (`/supplier-payables`, under the Money tab, no capability) starts with "You owe suppliers UGX X in
  all." and lists each supplier owed something with the balance and two 48 px buttons, Pay and Statement; with the purchasing capability the
  ageing table stays, with a Statement link per row. **Pay a supplier** (`/supplier-payments/new`, no capability) marks the supplier, paying
  branch, Paid how, amount and **Date paid** required (today at first, nothing later; an earlier day lists only what was owed then) and links to
  What I owe suppliers and the supplier's statement. **Supplier statement** (`/suppliers/$supplierId/statement`): From and To (the last 90 days
  at first), owed at the start, a card per purchase on credit, payment, void or reversal with what is owed after it, owed at the end, Pay this
  supplier, and **Share the statement** (the phone's share options with the statement as text, or WhatsApp).
- **Retail settings** gains the two tolerances in percent (stored as basis points, shown to two decimals) under `If-Match`, and says when no
  order approval limit is set that any administrator can send any order.
- `purchasing` is marked ready in `capabilities-state.ts` with its tour (`capability-purchasing`) and a Help page tour (`page-purchase-orders`). The
  mock (`retail-purchasing-mock.ts`) holds the server's rules in small for development and tests.
- Follow-up, marked: the purchase order as a PDF to send to the supplier. It reuses the ADR-034 renderer once the documents slice (#197) is on main.

## Landed costs and supplier credit notes (#199, ADR-033)

Both are part of the `purchasing` capability (`SCREEN_CAPABILITY`) and need `retail.payable.manage` (administrators), in the branch of the goods;
there is no new permission. The tiles Landed costs and Supplier credit notes sit beside the other purchasing tiles, and each route has a crumb.

- **Landed costs** (`/staff/retail/landed-costs`): live search by our number, the payee's invoice number or the payee, a status filter, and for each
  row the total and what is paid and still owed on the payee's invoice. **A landed cost** (`/landed-costs/$landedCostId`, crumb "Landed cost") shows
  the facts, the charges, a link to the payee's invoice and the allocation table: per line the received line, its share of the charges and, for
  `retail.profit.read`, the part added to the stock still on hand, the part counted against what is already sold or moved, and what happened to the
  cost of one (or why it did not change). The share and the basis are sent only with `retail.profit.read`; without it the page says amounts are shown
  to people who can read costs and still shows the total. It voids with a reason; the server's refusals (paid invoice, stock moved, credit note
  standing) are shown in plain words.
- **New landed cost** (`/landed-costs/new`, optionally `?receipt=` from a delivery): choose who is owed (a supplier row, the forwarder), their invoice
  number and dates, add charges (kind, optional description, amount) with a running total beside the invoice total as printed, choose how to spread
  them (by value, quantity, weight, or amounts typed, each in one sentence), find posted deliveries and tick their lines (all ticked at first). A
  weight box shows only for the weight method, a share box only for typed amounts, with a live check that the shares add up. **Review** asks the
  server to work out the shares (`POST /retail/landed-costs/preview`) and shows them before **Save**. The draft survives a reload and one
  `Idempotency-Key` belongs to one attempt, like the other forms.
- **Supplier credit notes** (`/supplier-credits`, `/supplier-credits/$creditId`) list and show credit notes and void them with a reason. **New credit
  note** (`/supplier-credits/new`, optionally `?bill=`) finds a standing goods invoice, asks whether goods are sent back or a price is adjusted, and per
  invoice line takes a quantity (shown against what the invoice billed and what earlier credit notes took, with the credit for one starting at the
  billed price) or an amount, then the supplier's credit note number, date, reason and the total as printed. The checks on the page only repeat the
  server's; the server decides.
- **Other screens.** The supplier invoice shows what credit notes took off and what is still owed, a Credit note button for a standing goods invoice,
  and for the invoice of a landed cost a label, a link to the landed cost and no void (the landed cost is voided instead). Goods received shows a
  "Held, not costed" badge, the landed amount of each line, an Add landed cost button and a reason panel to release a hold (administrators). The
  receive form has a "Hold until the landed cost is in" box. Retail settings has the switch that stops goods on a held delivery being moved out.
- The tour `capability-purchasing` (now version 2) gains the two tiles, and the Help page tour `page-landed-costs` explains the landed costs page.
  The mock (`retail-purchasing-mock.ts`) holds the same rules in small, with a second fabricated supplier row for the forwarder.

## Sales lists grouped by year, month and day (issue #328)

Issue #341 (the next section) replaced the fixed year, month and day levels with a chain the user chooses, and nothing is open to
start with; the header, the filters, the payment buttons and the plain words below still hold for the groups it shows.

All sales and Credit sales are no longer one flat newest-first list. Each is a set of collapsible groups, year then month then
day, and every header is a figure the server summed (`GET /retail/sales/summary`, chapter 7 section 7.11.20), never a sum of
pages in the browser.

- **A header** shows the period in words (2026, October 2026, Monday 19 October 2026), the total, the number of sales, the
  profit when the server sent it (`retail.profit.read` in every branch shown; absent otherwise, so nothing is drawn), a note
  of voided sales that are not counted (All sales only), and on All sales the payment methods used with their counts and
  amounts (cash, mobile money, bank, credit). The header is the whole button, at least a tap high, with `aria-expanded`.
- **Opening a group** lists the level below it, asked for with the group's own days inside the range the screen is showing, so
  the figures above never change when a month or a day is opened. (Superseded by issue #341 below: nothing is open to start with.) A day loads its sales only when it is opened, from the existing list endpoint with a
  single-day range and the screen's filters, 50 at a time with Show more, in the same sale cards as before; tapping a sale
  opens it and the way back finds the same groups open (what is open is kept by the screen; in issue #341 that is `useChainView`).
- **Breadcrumbs.** An open day shows where it sits, All sales (or Credit sales), the year, the month, the day. Tapping a place
  shuts what is inside it and shows that place again. The staff bar crumbs for the sale itself are unchanged.
- **The choices still work.** All sales keeps From and To (From is empty to start with, so every year shows, and To is today; the old 30 day start would have cut the year and month figures short), the buyer search, Paid by and the
  item search; Credit sales keeps the buyer search and Show (all, still owing, overdue, paid) and covers every date. Each
  level is asked with the same filters, so a group and the sales under it agree. A period of more than the server's bound (20
  years, 12 months, 31 days) ends with Show earlier years, months or days, which asks again from the server's `next_to`.
- **Payment buttons stay.** The sales of an opened day, and the sales of the Still owing list, are the same cards with the same
  `PaySaleAction` under each (`action` on `DaySales`, as on `SaleList`; issue #326): Record payment and Mark as fully paid on an
  owing live credit sale, a note and no button on an imported one, nothing on a paid sale. `sales-lists-actions.dom.test.tsx`
  renders both grouped lists and fails if a day stops passing the action. The tours `retail-sales` (version 4) and `admin-welcome`
  (version 6) say "open the day of the sale, then tap Record payment".
- **Debts are not hidden in a collapsed month.** On Credit sales, when Show is Still owing or Overdue the grouping steps aside
  and every such sale is listed together, 50 at a time (a debt from an old month would otherwise sit inside a closed group);
  All credit sales and Paid stay grouped. The search box shows the sales counted by the top level (the years' sales counts).
- **Plain words for the odd cases.** While a level loads it says Loading; a failed level shows the message and Try again; a
  level with nothing says "No sales found." (or "No credit sales found."), "No sales in this month." or "No sales on this day.";
  a search with no match keeps the screen's no-results box with its Clear button.
- **Mobile first.** Headers are laid out for a 360px phone (period and total on one line, the figures under it) and need no sideways
  scroll; not yet checked on a device or in a browser, and the styles are the `rt-group` classes in `retail.css` on the shared tokens.
- **Files.** `sales-grouped-state.ts` (pure rules: what is open, titles, the days a group asks for, the methods of a group),
  `sales-grouped.tsx` (the groups; the day's sales are supplied by `sales.tsx` as `renderDay`), the mock in
  `api/retail-summary-mock.ts`, and tests `sales-grouped-state.test.ts`, `sales-grouped.dom.test.tsx` and
  `retail-summary-mock.test.ts`.
- **Tours.** Help pages `page-sales` (the day range, the groups) and `page-credit-sales` (Show, the groups), shown from the
  Help menu on each page for anyone who may read sales.
## Sale actions: record a payment, void a sale, Price and Cost (#326, #327, #330)

Frontend only: the routes `POST /retail/sales/{id}/payments` and `POST /retail/sales/{id}/void` already existed (FR-RET-05, FR-RET-04). The forms are
`sale-actions.tsx`; their rules (what a form refuses before it asks the server, the words of a result) are `sale-actions-state.ts`. The server decides every rule
again under the sale's lock; the screens only save a round trip and say each refusal in plain words (`api/retail-errors.ts`).

- **Record payment** (an opened sale, and each row of Credit sales and All sales; every list passes it to `SaleList` through the `action` prop, and
  `sales-wiring.dom.test.tsx` fails if a list stops doing so). Shown only for a live credit sale that is completed and still owes, to a holder of
  `retail.sale.create` at the sale's branch. The form asks for the amount (the whole balance to start with, any part allowed, never more than is owed), how it was paid
  (cash, mobile money or bank) and the date received (from the sale's date to today). **Mark as fully paid** is the shortcut next to it: the amount is fixed at the
  balance, the way of paying and the date are still chosen. One `Idempotency-Key` is made when the form opens and kept in the tab with the draft
  (`usePersistedDraft`), so a retry after a lost answer posts once; a successful payment starts the next form with a new key. The button waits while the request is on its
  way and a second press sends nothing. After a payment the sale and the lists are read again, so the state moves between Unpaid, Part paid and Paid, and a line says
  "Recorded UGX 4,000. Still owes UGX 6,000." or "This sale is now paid in full." The refusals say `payment_exceeds_balance` as "more than the buyer still owes" and
  `sale_not_payable` as "only a credit sale that is not voided takes payments".
- **Imported sales.** An imported (historical) credit sale that still owes shows a note instead of the buttons: one short line in a list row ("Imported from the old
  records; record this payment with your accountant.") and a fuller one on the opened sale. A payment on it would post cash against a debtor that was never recorded
  here (`docs/runbooks/import-retail.md`), so it is left to the accountant, as the issue asks. **The server enforces it too (issue #338):** `recordPayment` in
  `SalesService` refuses a historical sale with 422 `sale_historical`, as the account payment route does, so an old cached build or a hand-made request cannot post cash
  against it either. The message is the same plain line ("An imported sale cannot be paid from here. Record this payment with your accountant."); the screen maps the code
  to that wording in `retail-errors.ts`. How imported credit is finally settled stays with the accountant and the operator.
- **When the sale moves on.** If the balance changed since the screen read it (someone else took a payment), `payment_exceeds_balance`, `sale_not_payable` and
  `sale_voided` make the lists and the sale load again. In Mark as fully paid the message is "The balance has changed; check the new amount."; a typed amount above
  the new balance goes back to the balance. Record payment and Mark as fully paid keep separate drafts and keys, so a lost answer on one never blocks the other; Cancel
  forgets the form unless a request went out and its answer may have been lost; `idempotency_key_reused` resets the form with a new key instead of asking for a reload.
- **Void this sale** (an opened sale only). Shown on a completed sale to a holder of `retail.sale.void` at the sale's branch. A credit sale that has had a payment or a return shows
  "A credit sale that has had a payment or a return cannot be voided." instead of the button (the server refuses it as `sale_has_payments`), and an imported sale
  shows a short note instead (its stock and entries predate this system). A void that already went through (`sale_voided`) counts as done, and the button stays away
  until the reloaded sale reads Voided. The form needs a reason of at least five
  characters and says plainly that the items go back into stock at the branch, the sale stops counting in reports and in what the buyer owes, and it cannot be undone.
  The schema gives this route no `Idempotency-Key`; a second void is refused by the server as `sale_voided`, and the button also waits while the request is on its way.
  A sale that has returns against it is refused by the server (`sale_has_returns`: "Void the returns first."). The sale then reads **Voided** with "Voided because" and
  its reason, and offers neither Return goods nor Record payment.
- **Wording (#330).** "Selling price" is now **Price** and cost comes first: the Stock page and its All branches table list Cost then Price (Cost only with
  `retail.profit.read`), the phone cards read "Cost UGX 3,500, price UGX 6,000 each", the home cards read Stock at cost, then Stock at price, and Stock value and the
  business evaluation say "At price" and "Stock at price". The catalogue's edit forms still say "Selling price" where a person types it; that is a field name, not a
  column, and is left for the catalogue pass.
- **Tours.** The administrator's welcome tour (version 5) and the selling tour (version 3) each gain a step on the Credit sales tile ("When a buyer pays what they owe"),
  and the administrator's tour a step on the All sales tile for voiding ("Fix a sale made by mistake"). A step is shown only to someone who holds the permissions it
  needs.
- **Mock.** `retail-mock.ts` answers both calls with the server's rules in small (balance, dates, the key replayed once, no void after a payment or twice) for
  `VITE_RETAIL_MOCK` and the tests. Tests: `sale-actions-state.test.ts` (the rules), `sale-actions.dom.test.tsx` (who sees what, validation, double submit,
  retry with the same key, error words, a moved-on balance, key reuse), `sales-wiring.dom.test.tsx` (the lists and the opened sale on the mock show the buttons and a payment refreshes the list), `screens.test.tsx` and `analytics.test.tsx` (Price and Cost order and words).

## Write off a debt (#340, ADR-043)

- **Write off** on an opened credit sale that still owes (`retail.credit.write_off` at the sale's branch; not on an imported, voided, paid or already written off sale). The form asks why the buyer will not pay (at least five characters), says in plain words that the amount is treated as lost, that the sale stays on record and reads Written off, that it leaves the owing and overdue lists and that it cannot be undone, and has one button, Write off, and one way back, Keep the debt. The answer is "Sent to a second administrator to approve" when the business has a limit and the debt reaches it, or, with no limit set, whenever two or more administrators count; a business with one administrator writes off at once, and the screen says it was done without a second person. The Idempotency-Key is kept with the draft like the payment form's.
- The opened sale reads Written off with the day, the amount and the reason; a written off sale offers no payment, void or return. The customer statement lists the write-off as a credit. Daily profit shows a Debts written off column once there is one.
- Tests: `write-off.dom.test.tsx`, `sale-actions-state` helpers; the mock answers with the server's rules.

## Branches: close, reopen and keeps-no-goods (#329, ADR-041)

- **Screen.** `/staff/setup/branches` (crumbs Home, Business set-up, Branches; a link in Business set-up for whoever holds `core.branches.read`). One card per
  branch with its name and code, the badges Head office, Closed and Keeps no goods, a line in plain words, and for an administrator (`core.branches.manage`)
  the actions: Close this branch (head office excluded), Mark as keeping no goods or Keeps goods again, and on a closed branch Reopen this branch. Closed branches
  are hidden behind a Show closed branches box that states how many there are, and a closed branch that still holds stock says so in its row (items and value). Mark as keeping no goods asks for a confirmation in plain words first (goods left there can still be moved out); Keeps goods again is one tap. Progressive disclosure: Close opens a confirmation panel; if the server finds stock
  or a transfer on the road it answers `branch_close_needs_reason` and the panel shows the server's words (with the stock value), asks "Why are you closing it?"
  and enables Close it anyway only with a reason. Nothing is posted or written off. After any change `/me`, the branch list and the retail queries are read again,
  so the pickers and the stock list follow. A reader sees the list and the line "Only an administrator can close or reopen a branch."
- **Everywhere else.** The staff bar picker lists the open branches from `/me` and tags a branch that keeps no goods "(no stock)"; the staff area does not start on
  it unless it is all the user has or the user chose it last (cash book). The sale, move-stock, stock-take and usage screens use `useStockingBranch` and
  `BranchRequired stocking`: on a branch that keeps no goods they say so and offer the branches that do. The restock, purchase order and goods receipt forms, the move-stock
  destination and the restock branch of a return list only branches that keep goods. The Stock page in All branches leaves closed branches out, says "N closed shops
  with stock hidden. Show them" when some are, and marks a closed column "(closed)"; the stock-by-location report keeps them with a Closed badge.
- **Tours.** The welcome tour is version 8 (a "Your branches" step for whoever may read branches); `page-branches` is the page tour.
- **Tests.** `branches/state.test.ts` (the rules), `branches/branches.dom.test.tsx` (hidden then shown closed branches, the badge and reason, confirm once, the warning
  and reason, refusals in plain words, reopen, the stocking switch, a reader sees no action), `retail/not-stocking.test.tsx`, `auth/branch.test.ts`, `tour/*`.

## Stores: a branch that holds stock and does not sell (#366, ADR-048)

- **Screen.** On the Branches screen an administrator sees, on each open branch that keeps goods, Mark as a store (does not sell). One tap only asks, in plain
  words: the branch keeps its stock and still takes restocks and transfers, but nothing can be sold, paid for or refunded there; Yes, make it a store sends it.
  A store carries the badge "Store (does not sell)" (and the ADR-033 role beside it as "Branch role: store" when roles are on) and the line "Store: holds stock and
  takes restocks and transfers in and out. Nothing is sold here. No takings, savings, banking or repayments here; expenses can still be paid." and offers Sells again (make it a shop), one tap; the Mark as keeping
  no goods button is not offered on a store. The confirmation says there are no takings, savings, banking or repayments there and that expenses can still be paid. A refusal over a credit sale still owing, or a branch that keeps no goods, shows the server's words.
- **Everywhere else.** The staff bar picker tags a store "(store)"; the staff area does not start on it while a shop exists. The sale screen on a store says "is a
  store, and a store does not sell" with a button for each shop (the same note as a store by branch role, ADR-033). The sale and quotation forms, the account
  payment's receiving branch and the return's "Pay the money out of the till of" list leave stores out (`sellingBranches`). The restock, purchase order, goods
  receipt, move-stock and stock-take forms, the restock branch of a return and the stock lists include a store, since it keeps goods. The Quotations page hides New
  quotation at a store and says why, and Make it a sale is not offered on a quotation made at one. A return for a sale made at a store drops "The usual branch" from the
  refund till and, when exactly one shop can pay, chooses and sends it.
- **Tours.** `page-branches` is version 2 with a Store step.
- **Tests.** `branches/state.test.ts`, `branches/branches.dom.test.tsx` (confirm once, the refusal in plain words, the badge and the way back),
  `retail/not-stocking.test.tsx`, `auth/branch.test.ts`.

## Add a branch (#581, ADR-089)

- **Screen.** On the Branches screen whoever holds `core.branches.manage` sees Add a branch at the top. The form (Fields marked * are needed) asks for the
  Name *, What is it? as a segmented control (Shop (sells), Store (keeps stock, does not sell), Head office (keeps no goods)) with a one-line hint for the
  chosen kind, and Where is it? (the branch `location`). No code is asked for (the owner's rule of #562): the Name's hint shows the code the app makes from
  it ("Code KASUBI, made by the app from the name", with a number when a listed branch has it). Add the branch sends `sells_goods` and `stocks_goods` for the kind. A full plan shows "Your plan has no room for another branch" with
  Open Plan and billing for whoever holds `core.billing.read`, else "Ask the owner to change the plan"; a taken code shows its refusal in plain words.
  On success the form closes, a dark toast says what was added ("Gayaza Store added. It is a store: it keeps stock and does not sell.") and `/me`, the branch
  list and the retail queries are read again, so the picker and Move stock carry it at once. A reader sees "Only an administrator can add, close or reopen a branch."
- **Tours.** `page-branches` is version 4 with an Add a branch step.
- **Business set-up.** The Branches tile reads "Add a shop or a store, close or reopen one, or say a branch keeps no goods".
- **Tests.** `branches/branches.dom.test.tsx` (each kind with its flags and toast, no code box and the code made from the name, the needed name, the plan limit with and without the billing link, the button only for `core.branches.manage`), `branches/state.test.ts`; backend `AddBranchIT`.

## Entry warnings and name hints (#353, ADR-093)

Rule based only (no AI call): each check is a sentence from the item's own records that the person can check.

- **Before saving, on Buy stock, Sell, Move stock and Count stock.** Each screen reads an item's norms once
  (`GET /retail/products/{id}/entry-norms`, kept five minutes). On Save, a line that looks wrong shows one plain warning next to its field,
  a tinted note with the warning icon, Confirm and Change (48px buttons; no stripe, no gradient, no emoji): "You usually buy this in rolls;
  you entered 200 metres. Is that right?" (a unit other than the usual one, Buy stock and Sell only, where a unit is chosen), "You usually
  buy about 3.5 rolls of this at a time; you entered 40 rolls. Is that right?" (ten times the usual or a tenth of it), "The records show 10
  pieces here; you counted 1,000 pieces. Is that right?" (Count stock; where the records hold nothing, against the usual purchase), and on
  Sell, for a price the seller typed, "You last sold this at UGX 6,000 a piece; you entered UGX 60,000. Is that right?". Confirm checks
  the screen as it is now (a line edited since loses its warning, a new one shows) and, once none is open, saves what the screen shows;
  the confirmations are recorded (`POST /retail/entry-warnings/confirmations`) once that save succeeds (Count stock: once the
  count is committed), and the next entry is asked about afresh; Change takes the warning away and puts the
  cursor in the field; nothing is saved. The save button is never disabled by a warning. With no history (under 3 lines), offline, or
  while the norms are still loading, nothing is asked. The price floor stays a refusal, as before.
- **While naming a new item** (Add an item, the item form in a category, and Not in the list? Add it here on Buy stock and Count stock):
  from 3 letters, a neutral note "Did you mean this item?" lists up to 5 existing items (case and spacing ignored), each with Open (Items) or
  Use this item (Buy stock and Count stock, which puts the existing item on the line instead), and a hint "Items like this are in Lighting,
  such as LED bulb 9W screw." with Choose Lighting, which chooses the category only when tapped (not offered when the form is inside a
  category). Not shown while an item is changed. The save-time name check of #352 below is unchanged.
- **Tours.** The page tours of Buy stock (version 5), Move stock (version 4) and Count stock (version 5) say in their save step that an
  unusual line is asked about first. The selling tour, which starts by itself, is left at its version so sellers are not shown it again.

## Correcting entry mistakes (#351, #352, ADR-044)

The owner fixes a mistake where he sees it, with a trail the spreadsheet never had. Everything below is phone first, in plain words, and
shows an action only to someone who may take it; the server decides again.

- **One name per item (#352).** On the Items form (add and change), pressing Save first asks the server for similar names
  (`GET /retail/products/similar`). The same name in the same unit shows "This product already exists: <name> (code). Open it?" with Open it,
  Change the name and, for an administrator (`core.settings.manage`) only, Add anyway: it asks "Why is this a different item?" (5 characters at
  least), keeps the reason with the item and saves. Anyone else sees no Add anyway. A close name shows "An item with a similar name exists. Check you
  are not adding the same item twice." with each neighbour, an Open button on each, "This is a different item, save it" and Change the name; the
  same name in another unit is listed there as "the same name in another unit". Editing the description or unit clears the panels and asks
  again; an edit that leaves both alone saves at once. If the server refuses at the moment of saving (someone else added the name), the same
  panel appears from its answer. One request however often Save is pressed (`sending` ref). Open it and Open go to the Items edit screen of that item.
  The CSV import reports a repeated name as skipped ("An item with this name exists already (code ...)").
- **Past restocks (`/staff/retail/restocks`, crumbs Home, Retail, Past restocks; a link on the Restock screen).** Every restock, newest first, a
  voided one marked Voided with its date and reason and never hidden; filters From, To and Show (all, not voided, voided). Each card shows the
  number, date, supplier, payment, total and each line with its quantity; a line entered in a unit that is not the item's usual one adds "Entered in
  metre but this item is kept in roll? If that is wrong, correct this restock and enter it again in the right unit." **Correct this restock**
  (for someone who holds `retail.purchase.create` at every branch it touched; otherwise a line says why not) opens a form with what a void does
  in plain words, a reason (5 characters at least) and three buttons: **Void and enter it again**, **Void only** and Keep the restock. The reason
  and the `Idempotency-Key` are kept per restock in the tab (`usePersistedDraft`), so a lost answer and a retry send the same key; one request
  however often a button is pressed. A refusal shows the server's words (stock sold or moved, paid, imported). Void and enter it again goes to
  `/staff/retail/restock?again=<id>`: the restock form opens with the same supplier, date, payment and lines (quantities in the unit they were
  entered in) dated today (the old date may be banked in the cash book; a plain warning shows if it is moved back), in a draft of its own, so a restock in progress is never overwritten, under "You are entering restock RP... again, which you
  voided. Check each quantity, unit and price, then save." On the restock form a line whose chosen pack unit is not the item's usual unit shows the same
  hint.
- **Correct on a stock row.** On the stock list of one branch (not All branches) each row has Correct, for someone who holds
  `retail.stocktake.commit` at that branch or `retail.catalogue.manage`. It opens four small choices, each on its own: *The quantity is wrong*
  (In stock now, the right quantity, "That takes away 4 roll.", a reason: Entry mistake or Miscount, an optional note, a hint that the old figure
  stays in the history and is not counted as shrinkage, Save the right quantity; one request, one key kept in the tab), *The category is wrong*
  (the right category; "<item> is now in <new>. It was in <old>."; audited with both), *The unit, name or price is wrong* (where to go:
  Past restocks for a restock entered in the wrong pack unit or at the wrong price, Items for a name or price; a unit in use is never converted, so the way out is a new item in the right unit, this one corrected to none, and a restock of the new one) and *See its history* (the item at this branch, newest first: date, what it was, the quantity with its sign,
  "Corrected from 10 to 6. Entry mistake.", the note, "By you" or "By a colleague"). Nothing is edited underneath.
- **Tours.** `retail-sales` is version 7 and `admin-welcome` version 10 (a Correct step on the stock list for whoever may count stock, and for the
  welcome tour a Past restocks step); `page-stock` is version 2; `page-restocks` is new (version 1). Anchors: `stock-list`, `restock-past`,
  `restocks-filters`, `restocks-list`.
- **Mock.** `api/retail-mock.ts` implements `similarProducts`, the duplicate refusal (`sameNameCheck`), `listPurchases`, `getPurchase`,
  `voidPurchase`, `correctStock` and `stockHistory` in memory, with the server's codes and words.
- **Tests.** `catalogue-names.dom.test.tsx`, `restocks.dom.test.tsx`, `stock-correct.dom.test.tsx`, `tour/*`.

## Inactive items (#342, ADR-045)

An item that is no longer sold is switched off, never deleted.

- **Items screen.** The edit form of an item shows an Inactive tick to a holder of `retail.catalogue.manage` (the server decides again), with a hint that it
  is never deleted and that its history and reports stay. While it is ticked and any branch you can see still holds a quantity (`stock_branches`, `stock_on_hand` of `GET /retail/products/{id}`), a plain warning
  says "This item still has stock in the branches you can see ..." (`inactiveWarning`). The stock-take count sheet keeps an inactive item that holds stock (`countableStock`). The list badge and the
  filter say Inactive.
- **Pickers and lists.** The sale, restock, usage and transfer pickers already ask for active items only. The Stock page has a Show inactive items tick
  (`include_inactive`, off by default) on the one branch and the All branches view; with it off an inactive item is not listed even while it holds stock.
- **Refusals.** Through the API a sale, restock, purchase order line or transfer of an inactive item is 422 `product_inactive` in plain words.
- **Tour.** `page-catalogue` is version 2 with an Items you stopped selling step.
- **Tests.** `catalogue.test.tsx` (the warning, the lists), `RetailProductInactiveIT` (backend, CI only).

## Left to do

- Analytics: export to PDF and Excel and the daily WhatsApp summary (#149 items 8), and the cash view per shop per day (item 5), which need the cash book.
- The component tests render static markup (no DOM in the test setup); the browser run above covers behaviour.
- Offline use and receipts to SMS are out of the first release; barcode scanning is built (issue #345, below).
- The tenant's timezone and a transfer number are not in the API; when they are, use them (#112).

## Sales grouped by a chain of levels (issue #341)

The grouped lists of issue #328 open from the shop down to the items, the way the old spreadsheet app did: shop, year, month,
salesperson, day, product category, item, with a summary on every line. The user chooses the order and the levels.

- **Group by.** A control above the groups on All sales and Credit sales (a `details` that shows the chosen order in one line:
  "Group by: Shop, Year, Month, Salesperson, Day, Product category, Item"). Opened, it lists the levels with Up, Down and Remove
  buttons (buttons, not dragging, so it works with a thumb and a keyboard), the levels not used as buttons to add, and Use the usual
  order. The last level cannot be removed. The choice is kept per user and per list (All sales and Credit sales each have their own)
  in `localStorage` (`bms.salesChain.<list>.<user>`, read and written in try/catch; blocked or garbled storage gives the usual
  order). The key holds the user's id from `/me` (never an email or a phone number). Changing it closes every open group. When one shop is chosen
  at the top of the page the shop level is left out (it would be one line). The salesperson level is shown only to someone who may read profit
  (a cashier sees no ranking of colleagues): it is left out of Group by, of the usual order and of any stored chain, and it is shown only where profit read covers every branch in view (one shop chosen, or all of them).
- **A line** is the same header as in issue #328 (name, total, number of sales, profit when the server sent it, voided note,
  payment methods on All sales), now for any level: a shop or a person or a category by name, a period in words. At the item level it
  adds the quantity sold ("4 sold, in 3 sales"). Under a category or an item the figures are its own lines, not whole sales, and the count reads
  "in N sales" (the sales that hold it). An item row shows product, quantity, amount and profit (only when sent); at the end of the chain it opens
  onto the sales that include the item.
- **Opening** a line asks the next level with what was opened above it as filters (shop, dates, person, category, item), so a
  figure never changes when a child opens. Nothing is open to start with (little at first, expand at will). What is open is kept by
  the screen (`useChainView`, keyed by the path, so the same day under two people is two groups), so coming back from an opened sale finds
  the same groups open.
- **Where the sales are.** The last level of the chain opens onto the sales themselves, in the same cards as before, 50 at a time,
  with the filters of the whole path (including `seller_id`, `category_id` and, for an item, `product_id` on the list). A group that opens onto
  more groups also offers "Show the sales of this day" (a day) or "Show the sales here" when it is a day, when the chain has no day at all,
  or when it is the level above a last Item, so every chain a person can make reaches a sale and the Record payment and Mark as fully paid
  buttons (the `action` of `DaySales`, issue #326) still sit under each sale. Under a category or an item the sales are whole sales, so a
  line says "Whole sales that include this category (or item)." and their totals can be more than the line above; this is the one place
  where a group and the sales under it do not add up the same way.
- **Breadcrumb.** The way to the group opened last shows above the list (All sales, the shop, the year, ...). A place in it shuts
  what is inside it and shows that place again.
- **Bounds.** A level shows at most 20 years, 12 months, 31 days, 100 shops, or 50 people, categories or items; the button under it
  reads Show earlier years (or months or days) for a period, and Show more shops (or salespeople, categories, items) otherwise. The levels
  other than periods are ordered by total, which can move between two requests, so the pages are merged by `key` and a group that comes
  twice is drawn once.
- **Plain words.** Loading; a failed level shows the message and Try again; an empty list says "No sales found." (or "No credit sales
  found."), and an empty group "No sales here."
- **Mobile first.** One column on a 360px phone; the header, the figures and the Group by buttons wrap and need no sideways scroll; each
  button is at least a tap high. Not yet checked on a device or in a browser.
- **Files.** `sales-chain-state.ts` (pure rules: the levels and their words, move, drop, add, the remembered chain, what a group selects,
  the open paths and the breadcrumb), `sales-chain.tsx` (the levels and `useChainView`), `sales-group-by.tsx` (the control), the
  mock `api/retail-chain-mock.ts`, and tests `sales-chain-state.test.ts`, `sales-chain.dom.test.tsx` and `retail-chain-mock.test.ts`.
  `sales-grouped.tsx` keeps only `useRootFigures` (the search box count); the year, month and day components of issue #328 and their
  tests were removed.
- **Tours.** `retail-sales` (version 5) and `admin-welcome` (version 7) get a Group by step on the retail home; the Help pages
  `page-sales` and `page-credit-sales` (version 2) get a Group by step and a line about the breadcrumb.

## Stock and past restocks grouped by a chain (issue #350)

The Group by chain of the sales lists (issue #341) is offered on the Stock page and on Past restocks. Both stay as they were until
the person chooses **Grouped** (a switch of two buttons, Flat list and Grouped, remembered per person and list in `localStorage`
under `bms.listView.<list>.<user id>`). Then the Group by control and the groups appear; what is open is kept in the screen, so a
restock opened for correction and closed finds the same groups open. Nothing is open to start with.

- **Stock:** levels shop and product category (`bms.listChain.stock.<user id>`, default shop then category). Every line is the
  server's summary of its group (`GET /retail/stock/summary`): the value at the selling price, how many items, the value at cost
  when the server sent it, and how many are negative. The items of the last opened group are the screen's own stock table (flat, no
  category sections) for that shop and category, so **Correct** on a stock row works inside the groups; with no shop in the
  chain the all-branches table or cards for the category. With one shop chosen at the top of the page the shop level is left out.
  The search, category, negative and stock-level filters apply to the groups and to the items. A "Include closed shops that still
  hold stock" box replaces the closed-shops hint while grouped.
- **Past restocks:** levels shop, year, month, supplier, day, product category and item (`bms.listChain.restocks.<user id>`, default
  in that order). Every line shows how many restocks, how many were voided and are not counted, what was bought at cost only when the
  server sent it, and at the item level the quantity bought with its unit. The restocks of the last opened group, and "Show the
  restocks here" on a day, on any level of a chain with no day and above a last item level, are the screen's own cards, so **Correct
  this restock** and its void work inside the groups. Under a shop, a category or an item the cards are whole restocks that include
  it (their totals can be more than the line above); under a shop the note also says each card shows the quantities and the total for
  every shop the person can see (#372). The From, To and Show filters of the page apply to the groups and the cards.
  Restocks with no supplier are one group, "No supplier".
- Components: `list-chain.tsx` (the view switch, Group by, the tree), `list-chain-state.ts` (levels, remembered choice, selection),
  `stock-grouped.tsx`, `restocks-grouped.tsx`. The tours `page-stock` (version 3) and `page-restocks` (version 2) have a Grouped step
  that explains Group by.

## Amount received and change to give (issue #346)

For a cash sale the seller types what the customer handed over and the form shows the change to give back, as at a supermarket
till. Barcode scanning is a separate ticket (#345) and is not part of this. PWA only: no migration, no API change.

- **Where it shows.** Under the Total, in a box called Cash from the customer, only while How is it paid is Cash. Mobile money, bank and
  credit hide it. The form has no reference field for mobile money or bank today, so none was added; credit is unchanged.
- **The field.** Amount received (UGX), numeric keyboard, whole shillings. Commas and spaces are allowed ("20,000"). The quick buttons
  are 1,000, 2,000, 5,000, 10,000, 20,000 and 50,000 (each sets the amount to that note) and Exact (sets it to the total, disabled while
  the total is 0). Change to give appears in large plain figures (the same style as the Total) once the amount covers the total; Exact
  shows UGX 0.
- **What is refused (Save stays off, the reason is in words).** An amount below the total: "Enter at least the total, UGX 15,500." (the
  backend has no part payment on a cash sale, so there is no "part paid" choice; a part payment is a credit sale or a payment recorded
  later). A negative: "The amount cannot be negative." A decimal: "Type whole shillings only, for example 20000." Letters or signs:
  "Type numbers only, for example 20000." More than UGX 100 billion: "That amount is too large. Check the figure." An empty field is allowed:
  the customer paid the exact amount and no change was asked for, so the quick sale is as fast as before.
- **Display only.** The sale is saved at its total. The amount received is not sent to the server, not stored in the saved draft
  (`usePersistedDraft`), and not in any storage; it lives in the form's own state, so a new sale starts empty, Clear this sale empties it and
  a reload loses it. The daily cash summary keeps counting the sale total, not the cash handed over.
- **Receipt.** After saving a cash sale where an amount was typed, the receipt adds "Received UGX 20,000. Change given: UGX 4,500". No
  amount typed, no line. Mobile money, bank and credit receipts are unchanged.
- **Several items.** The total is the sum of all lines, so the change follows every item added or removed.
- **Permission and double tap.** Whoever may record a sale (`retail.sale.create`) sees it; nothing new. One request however often
  Save is pressed, as before.
- **Mobile first.** One column at 360px; the note buttons wrap and are at least a tap high; the change is the largest figure on the form.
  Not yet checked on a device.
- **Files.** `sale-change-state.ts` (reading what was typed, the change, the refusals, the notes), `sale-change.tsx` (`ChangePanel`,
  `ReceiptChange`), tests `sale-change-state.test.ts` and `sale-change.dom.test.tsx`. `sale.tsx` only keeps the typed text in one
  `useState`, draws the panel under the Total, adds its refusal to the form's problem and passes the amount to the receipt, so a barcode
  scanner added to the same form does not collide with it.
- **Tours.** `retail-sales` (version 6) and `admin-welcome` (version 9) get a Cash and change step on the sale screen (skipped when the
  customer pays another way); the Help page `page-sale` shows it too.

## Navigation and tour coverage (issues #336 and #337, ADR-049)

Audit of 2026-10-20 over every staff route in `frontend/src/app/router.tsx` (the retail and lending routes and the staff routes beside them; the sign-in,
sign-up, platform console, member area and style routes are not staff screens and are outside this rule). One row per route: the breadcrumb label it
declares, its page tour in `frontend/src/tour/`, and the cross links this change added. Frontend only: no API, schema or permission change, no new screen.

**Result.** 105 staff routes. Breadcrumbs: 105 of 105 (they are structural: each route declares a `crumb`, a test fails without one, and the detail pages
name their last crumb after the entity they load). Page tours: 29 before this change, 105 after (76 added, each version 1; no existing tour's copy
changed, so no existing version was bumped). `tour/route-coverage.test.ts` now fails when a staff route has no page tour, and the `tour-guard` CI guard fails a
pull request that adds or renames a route without a change under `frontend/src/tour/` or a `TOUR-EXEMPT: <reason>` line in its body (ADR-049).

**Added after the first audit (merge of main, 2026-10-20).** The routes that arrived with the Controls page (`/staff/retail/controls`, tour `page-controls`), the two-step policy page (`/staff/setup/two-step`, `page-two-step`) and the user's own two-step page (`/staff/two-step`, `page-own-two-step`, added here) each have a page tour, so the count stays complete; `route-coverage.test.ts` is what keeps it so, and the table below is the state at the first audit.

**Conventions the tours follow.** A tour is data in one of the slice files (`tours.ts`, `tours-customers.ts`, `tours-retail-reports.ts`, `tours-retail-cash.ts`,
`tours-retail-purchasing.ts`, `tours-lending-more.ts`), `page` is the route path (`$name` matches any one segment), plain words, one step per main action,
`optional: true` for a step a permission or setting can hide. Its anchors are `data-tour` attributes on markup the screen draws before any data
arrives, because `tour/test-kit.tsx` renders each step's screen statically and fails when the anchor is missing; a detail page exports the body it draws for
a given id so the test can render it. Cross links show only when the user may open the destination (`retail/screen-link.tsx`, `ToStock` and `ToSales` in
`analytics-ui.tsx`).

**Cross links that could not be added without a new screen or a search parameter** (left as they were, listed so a later slice can take them):
a sale number opens nothing by itself, because a sale opens in place inside All sales and Credit sales, which take no sale parameter, so a sale number on a
credit note, a return, an account statement or a credit control row stays plain; a product name opens the Stock list, not that item, because Stock takes no
item parameter; a branch name opens Stock for the session's branch, not the named one, so branch names in the cash records stay plain (the branch roles page opens
Stores and shops, which lists every branch); category and unit rows open the unfiltered items list, because the items page keeps its category in local
state; Insights drill-down rows carry no ids; the used, damaged and found-missing counts on Stock health have no list screen. A product opening "its stock and history" is
therefore as far as the existing routes go: Stock, and Past restocks and Stock moves for the history.

| Route | Breadcrumb | Page tour | Cross links added (#336) |
|---|---|---|---|
| `/staff` | Home | `page-staff-home` v1 (added); v2 adds the shop summary step (#485) | member name opens the member; each shop summary card opens its full page (#485) |
| `/staff/approvals` | Approvals | `page-approvals` v1 (added) | "Open the item" on collateral releases |
| `/staff/assistant` | AI assistant | `page-assistant` v1 (existing) | none added |
| `/staff/insights` | Insights | `page-insights` v1 (added) | links to the loan list and to maturities |
| `/staff/lending` | Lending | `page-loans` v1 (existing) | none added |
| `/staff/lending/collateral` | Collateral | `page-collateral` v1 (existing) | none added |
| `/staff/lending/collateral/$collateralId` | Item | `page-collateral-item` v1 (existing) | none added |
| `/staff/lending/collateral/$collateralId/edit` | Change details | `page-collateral-edit` v1 (added) | Cancel returns to the item |
| `/staff/lending/collateral/new` | Register collateral | `page-collateral-new` v1 (existing) | none added |
| `/staff/lending/investments` | Investments | `page-investments` v1 (added) | none added |
| `/staff/lending/investments/$investmentId` | Investment | `page-investment` v1 (added) | member name opens the member; certificate number opens the certificate |
| `/staff/lending/investments/$investmentId/certificate` | Certificate | `page-investment-certificate` v1 (added) | none added |
| `/staff/lending/investments/maturities` | Maturities | `page-investment-maturities` v1 (added) | largest investors open their investments |
| `/staff/lending/investments/member/$memberId` | Member investments | `page-member-investments` v1 (added) | member number opens the member; link to their savings |
| `/staff/lending/investments/member/$memberId/new` | New investment | `page-investment-new-member` v1 (added) | member name opens the member |
| `/staff/lending/investments/new` | New investment | `page-investment-new` v1 (added) | member name opens the member |
| `/staff/lending/investments/products` | Investment products | `page-investment-products` v1 (added) | none added |
| `/staff/lending/loans/$loanId` | Loan | `page-loan` v1 (existing) | none added |
| `/staff/lending/members` | Members | `page-members` v1 (existing) | none added |
| `/staff/lending/members/$memberId` | Member | `page-member` v1 (existing) | none added |
| `/staff/lending/members/$memberId/edit` | Change details | `page-member-edit` v1 (added) | none added |
| `/staff/lending/members/$memberId/savings` | Member savings | `page-member-savings` v1 (added) | member name opens the member; link to the member's investments |
| `/staff/lending/members/new` | New member | `page-member-new` v1 (existing) | none added |
| `/staff/lending/savings` | Savings | `page-lending-savings` v1 (added) | none added |
| `/staff/lending/savings/accounts/$accountId` | Account | `page-savings-account` v1 (added) | none added |
| `/staff/lending/savings/accounts/$accountId/statement` | Statement | `page-savings-statement` v1 (added) | account number opens the account |
| `/staff/lending/savings/products` | Savings products | `page-savings-products` v1 (added) | the account count opens the savings list |
| `/staff/payments` | Receive payments | `page-payments` v1 (added) | none added |
| `/staff/reports` | Reports | `page-reports` v1 (added) | none added |
| `/staff/retail` | Retail | `page-retail-home` v2 (the seller's own sales, ADR-055) | none added |
| `/staff/retail/accounts` | Customer accounts | `page-accounts` v1 (existing) | none added |
| `/staff/retail/accounts/$customerId` | Account | `page-account` v1 (added) | none added |
| `/staff/retail/advances` | Advances | `page-advances` v1 (added) | link to Cash summary |
| `/staff/retail/banking` | Banking | `page-banking` v1 (added) | link to the banking report |
| `/staff/retail/banking-report` | Banking report | `page-banking-report` v1 (added) | link back to Banking |
| `/staff/retail/branch-roles` | Branch roles | `page-branch-roles` v1 (added) | branch row opens Stores and shops |
| `/staff/retail/cash-summary` | Cash summary | `page-cash-summary` v1 (added) | each figure opens the rows behind it (sales, expenses report, banking report, withdrawals, advances, savings) |
| `/staff/retail/catalogue` | Catalogue | `page-catalogue` v1 (existing) | none added |
| `/staff/retail/catalogue/buyers` | Credit buyers | `page-buyers` v1 (added) | Open account (already there) |
| `/staff/retail/catalogue/categories` | Categories | `page-categories` v1 (added) | Items link on each row |
| `/staff/retail/catalogue/import` | Import items | `page-import` v1 (added) | none added |
| `/staff/retail/catalogue/products` | Items | `page-products` v1 (added) | each item has a Stock link |
| `/staff/retail/catalogue/suppliers` | Suppliers | `page-suppliers` v1 (added) | Open items link (supplier payment form) |
| `/staff/retail/catalogue/units` | Units | `page-units` v1 (added) | Items link on each row |
| `/staff/retail/credit-control` | Credit control | `page-credit-control` v1 (added) | overdue buyer opens the account; "See the credit sales" |
| `/staff/retail/credit-sales` | Credit sales | `page-credit-sales` v2 (existing) | none added |
| `/staff/retail/customer-ageing` | Customer ageing | `page-customer-ageing` v1 (added) | buyer opens the account (already there); Credit sales link |
| `/staff/retail/customer-groups` | Customer groups | `page-customer-groups` v1 (added) | Credit buyers and Price lists links |
| `/staff/retail/documents` | Documents | `page-documents` v1 (existing) | none added |
| `/staff/retail/evaluation` | Business evaluation | `page-evaluation` v1 (added) | item names open Stock |
| `/staff/retail/expense-lists` | Expense lists | `page-expense-lists` v1 (added) | link to Expenses |
| `/staff/retail/expenses` | Expenses | `page-expenses` v1 (added) | links to the expenses report and the expense lists |
| `/staff/retail/expenses-report` | Expenses report | `page-expenses-report` v1 (added) | link back to Expenses |
| `/staff/retail/goods-received` | Goods received | `page-goods-received` v1 (added) | none added |
| `/staff/retail/goods-received/$receiptId` | Delivery | `page-goods-receipt` v1 (added) | supplier opens its open items |
| `/staff/retail/goods-received/new` | Receive without an order | `page-goods-receipt` v1 (added) | none added |
| `/staff/retail/in-transit` | On the way | `page-in-transit` v1 (existing) | none added |
| `/staff/retail/landed-costs` | Landed costs | `page-landed-costs` v1 (existing) | none added |
| `/staff/retail/landed-costs/$landedCostId` | Landed cost | `page-landed-cost` v1 (added) | supplier opens its open items; delivery number opens the delivery |
| `/staff/retail/landed-costs/new` | New landed cost | `page-landed-cost-new` v1 (added) | none added |
| `/staff/retail/margins` | Margins | `page-margins` v1 (added) | sales total opens All sales; item names open Stock |
| `/staff/retail/price-lists` | Price lists | `page-price-lists` v1 (existing) | none added |
| `/staff/retail/profit` | Daily profit | `page-profit` v1 (added) | branch names open Stock; "See the sales behind it" opens All sales |
| `/staff/retail/purchase-orders` | Purchase orders | `page-purchase-orders` v1 (existing) | none added |
| `/staff/retail/purchase-orders/$orderId` | Purchase order | `page-purchase-order` v1 (added) | supplier opens its open items |
| `/staff/retail/purchase-orders/$orderId/edit` | Change order | `page-purchase-order-edit` v1 (added) | none added |
| `/staff/retail/purchase-orders/$orderId/receive` | Receive goods | `page-order-receive` v1 (added) | none added |
| `/staff/retail/purchase-orders/new` | New purchase order | `page-purchase-order-new` v1 (added) | none added |
| `/staff/retail/quotations` | Quotations | `page-quotations` v1 (existing) | none added |
| `/staff/retail/restock` | Restock | `page-restock` v1 (added) | none added |
| `/staff/retail/restocks` | Past restocks | `page-restocks` v2 (existing) | none added |
| `/staff/retail/returns` | Customer returns | `page-returns` v1 (existing) | none added |
| `/staff/retail/returns/$returnId` | Credit note | `page-credit-note` v1 (added) | buyer opens the account |
| `/staff/retail/returns/new/$saleId` | Take goods back | `page-return-new` v1 (added) | buyer opens the account |
| `/staff/retail/sale` | Record a sale | `page-sale` v1 (existing) | none added |
| `/staff/retail/sales` | All sales | `page-sales` v2 (existing) | none added |
| `/staff/retail/sales-analysis` | Sales analysis | `page-sales-analysis` v1 (added) | sales total and count open All sales; item and branch names open Stock |
| `/staff/retail/savings` | Savings | `page-savings` v1 (added) | link to Cash summary |
| `/staff/retail/settings` | Retail settings | `page-retail-settings` v4 (slow movers step, #414) | none added |
| `/staff/retail/stock` | Stock | `page-stock` v3 (existing) | none added |
| `/staff/retail/stock-by-location` | Stores and shops | `page-stock-by-location` v1 (existing) | none added |
| `/staff/retail/stock-health` | Stock health | `page-stock-health` v2 (slow movers step, #414) | item and branch names open Stock; Move on a slow mover opens Move stock filled in |
| `/staff/retail/stocktake` | Stock-take | `page-stocktake` v1 (existing) | none added |
| `/staff/retail/supplier-bills` | Supplier invoices | `page-supplier-bills` v1 (added) | none added |
| `/staff/retail/supplier-bills/$billId` | Supplier invoice | `page-supplier-bill` v1 (added) | supplier opens its open items; delivery number opens the delivery |
| `/staff/retail/supplier-bills/new` | New supplier invoice | `page-supplier-bill-new` v1 (added) | none added |
| `/staff/retail/supplier-credits` | Supplier credit notes | `page-supplier-credits` v1 (added) | none added |
| `/staff/retail/supplier-credits/$creditId` | Supplier credit note | `page-supplier-credit` v1 (added) | supplier opens its open items |
| `/staff/retail/supplier-credits/new` | New credit note | `page-supplier-credit-new` v1 (added) | none added |
| `/staff/retail/supplier-payables` | What I owe suppliers | `page-supplier-payables` v2 (issue #557) | Pay a supplier, Supplier payments; per supplier Pay and Statement |
| `/staff/retail/supplier-payments` | Supplier payments | `page-supplier-payments` v2 (issue #557) | none added |
| `/staff/retail/supplier-payments/$paymentId` | Supplier payment | `page-supplier-payment` v2 (issue #557) | supplier opens its open items |
| `/staff/retail/supplier-payments/new` | Pay a supplier | `page-supplier-payment-new` v2 (issue #557) | What I owe suppliers, Supplier payments, the supplier's Statement |
| `/staff/retail/suppliers/$supplierId/statement` | Supplier statement | `page-supplier-statement` v1 (added, issue #557) | Pay this supplier, What I owe suppliers |
| `/staff/retail/transfer` | Move stock | `page-transfer` v1 (added) | branch names on the saved summary open Stock |
| `/staff/retail/transfers` | Stock moves | `page-transfers` v1 (added) | branch names in a move open Stock; link to Move stock |
| `/staff/retail/usage` | Usage and damage | `page-usage` v1 (added) | "See the stock" link on the saved summary |
| `/staff/retail/valuation` | Stock value | `page-valuation` v1 (added) | item and branch names open Stock |
| `/staff/retail/withdrawals` | Cash withdrawals | `page-withdrawals` v1 (added) | link to Cash summary |
| `/staff/setup` | Business set-up | `page-setup` v1 (existing) | none added |
| `/staff/setup/branches` | Branches | `page-branches` v1 (existing) | none added |
| `/staff/sms` | SMS | `page-sms-settings` v1 (added) | none added |
| `/staff/sms/opt-outs` | Opt-outs | `page-sms-opt-outs` v1 (added) | none added |
| `/staff/sms/wording` | Wording | `page-sms-wording` v1 (added) | none added |
| `/staff/users` | Staff users | `page-users` v2 (existing) | none added |
| `/staff/users/new` | Invite someone | `page-invite-user` v1 (added) | none added |
## Controls (issue #358, part of #357, ADR-047)

A read-only page for the tenant administrator and anyone given `retail.controls.read`: what deserves a second look, metrics first,
details on drill-down. `/staff/retail/controls`; breadcrumb Retail > Controls; a tile on the retail home for a holder of the permission.
No migration beyond the permission; no write anywhere on the page.

- **Days.** Today, This week (Monday to today), This month (the 1st to today) or Choose days (two dates, at most 366 days). Every count is
  set against the days just before ("the day before", "the 7 days before"). "Cancelled at once means within" is a choice of 2, 5, 10, 15, 30
  or 60 minutes.
- **Cards.** One tappable card per kind of thing, in a fixed order: Sales cancelled, Cancelled at once, Goods taken back, Credit written off,
  Prices changed, Discounts given, Purchases cancelled, Quantities corrected, and Sold below cost (only when the server sent it, that is for someone who may read profit in every shop shown; a line
  of words says so otherwise). Each shows the count (for a price, a discount or a cost it says "3 lines in 2 sales, lines worth ..."), what it is worth at sale prices, the rate ("2.5 per 100 sales", always sales with such an event per 100 sales entered, so the units match), the trend ("Up 3, from 5
  in the 7 days before"; "None, and none in ...") and its share of the value of sales. A card whose capability is off and never counted says
  "Not switched on for this business, so nothing is recorded" instead of a calm zero. A card is a button: the chosen one sets the table below.
  No red or green: the page lists, it does not judge.
- **Tables.** Under the cards, the chosen kind grouped by a chain of Shop, Person, Day, Reason and Approver, in the usual order, with the same Group
  by control as All sales (move, remove, add; kept per user in the browser; the shop level is left out when one shop is chosen at the top).
  Each line is a button with the count, the value and the rate; opening it shows the next level and, on the last, the individual records
  (sale number, time, person with "(deactivated)" when they are, shop, reason or "No reason recorded", item, amount) with Open sale, which
  shows the sale as the sales lists do and comes back to the same groups. A group that opens onto groups also offers Show the records here.
  The path shows as a breadcrumb above the groups. Imported history is not counted. **Person is whose sale it was**, also for a cancelled
  sale or a return, so the cashier whose sales were cancelled carries the count and the administrator who cancelled them does not; the
  administrator is the **Approver** level and the "Cancelled by ..." line of a record (the lines of a sale have no approver, so the level is
  left out for them). Shops, people and approvers are listed by name, never by count or rate. A person the server cannot name reads Unknown
  person, is counted and cannot be opened. Try again shows when the figures or the patterns could not be read.
- **Rates.** "per 100 sales" is the count over the sales that person, shop or day entered, so a busy person and a quiet one can be compared;
  a person who entered none has none ("no sales of their own to compare with").
- **Repeat patterns.** A strip under the tables in plain sentences, by shop and name: "5 of the 13 sales Test Seller One entered at Test Branch
  Gayaza were cancelled (38.5 per 100). The shop's own is 14.0, so 2.8 times." Only from 3 cases over 10 sales, and the page says it is a reason to look, not
  a finding; the approver is never in it. An item reads "5 of the 20 lines of Test Cable 2m sold at ... were on cancelled sales".
- **Export.** Export CSV under every list saves what is on screen (the groups, or the records with the time in words and the approver, or the patterns) from the browser, so it can
  never hold more than the person may see; a text that begins with `=`, `+`, `-` or `@` is written as plain text.
- **Permission and branch.** `retail.controls.read` only; the sales list is a separate permission, so Open sale is offered only to someone who
  may also read sales. The server limits every figure to the shops of the permission's scope.
- **Mobile first.** One column at 360px; the day buttons wrap, the cards are two across (four on a wide screen) and at least a tap high, each
  record is a card of its own; nothing needs sideways scroll.
- **Files.** `controls.tsx` (the page), `controls-state.ts` (the days, the words, the chain, the CSV; tests in `controls-state.test.ts`),
  `controls.dom.test.tsx`, `api/retail-controls.ts`. The Group by control (`sales-group-by.tsx`), the breadcrumb (`GroupTrail` in
  `sales-chain.tsx`) and the chain helpers (`sales-chain-state.ts`) are generic over the levels and shared with the sales lists.
- **Tours.** `admin-welcome` (version 10) has a Controls step on the retail home; the Help page `page-controls` (version 1) walks the days, the
  cards, Group by, the tables and the patterns.
- **Stock cards.** Restocks cancelled and Quantities corrected (#356) are cards too: a count and a value, no rate (not about sales), no Open sale
  on their records, and no Approver level for a correction.
- **Credit written off (issue #357, ADR-087).** A card after Goods taken back: the credit sales written off in the days, worth what the
  buyers still owed (never the loss, which can be a cost), with the rate per 100 sales and the share of sales like the other sale cards.
  Person is whose sale it was; the record reads "Written off by ..." and opens the sale. Not in the repeat patterns.
- **Menu.** Controls is a row of the Business group of the grouped menu (#564, ADR-086), after Reports, for a session that may
  open the page; on the page, Controls is the current row and Reports is not.
- **Not yet.** Groups 2 to 6 of #357 are separate pages.

## Sizes and colours (issue #384, ADR-051, FR-RET-51, FR-RET-52)

For a shop that sells one design in several sizes, colours or prints (a romper in 0-3m, 3-6m and 6-12m, in Blue and Pink; a cable in
1.5mm and 2.5mm). Each size and colour is an item of its own everywhere stock is touched, named with its size and colour: "Baby romper
(0-3m, Blue)". So a sale, a restock, Move stock, a stock-take, Usage and damage and Take goods back pick it like any item, and the
stock list shows it on its own row.

- **Items** has a second button, **Add an item with sizes and colours** (`/staff/retail/catalogue/products/sizes/new`). The owner types
  the name, the category, the unit and the prices once, then the sizes and the colours or prints, each list separated by
  commas. The list of what will be made shows at once, each with its item code made by the app from the category's next code
  (`BLB-0007-03M-BLUE`, shown as text, never typed: issue #562) and its price, which the owner may change (a bigger size that costs
  more); two that would share a code are flagged in plain words. **Make these 6** saves exactly that list.
- **The item's page** (`/staff/retail/catalogue/products/sizes/$familyId`, crumbs Catalogue, Items, then the item's name): the stock of
  every size and colour together, per shop or store the person may see and in all; each size and colour with its code, price, cost
  (with cost read) and stock, with **Change price** and **Change** (its size, colour, on sale; the code never changes); **Add more sizes or colours** (a
  new print is made in every size the item has, at the price of its first size); **Change the name or category** (every size and
  colour follows). Links to Items, Record a sale, Restock, Move stock, Stock and Sales.
- **An existing item** gets **Give this item sizes and colours** on its edit form: the owner says which size or colour its stock is
  now; it keeps its code, prices, stock and history, and the others are made at its prices.
- **Join items as sizes of one item** (`/staff/retail/catalogue/products/sizes/join`, a third button on Items): for a shop that
  typed one item per size. Name the item, pick the items with Add as a size, type each one's size or colour, Join them as one item.
  Each keeps its code, price, stock and history. Tour `page-sizes-join`; `page-products` is version 3 with a Join step.
- **A name another item has** (on Add an item with sizes and colours and on Join) is refused in plain words naming the one that
  exists; an administrator may use it anyway with a reason.
- **A size's own edit form** shows its name, category and unit as the item's, with a link to change them on the item's page, and lets
  the size, colour, code and on sale change. A card in the Items list shows the size and colour and **All sizes and colours**.
- **All sales, Credit sales and Past restocks (grouped)**: the Item level shows an item with sizes once, every size and colour
  together; the new level **Size and colour** splits it. The level is offered in Group by only once the business has an item with
  sizes, so a shop without them sees the lists as before.
- **Files.** `catalogue-variants.tsx` (the new item screen, the panel for an existing item, the preview, the level hooks),
  `catalogue-family.tsx` (the item's page), `api/retail-variants.ts` (the same combination and code rules as the server; tests in
  `retail-variants.test.ts`), `catalogue-variants.dom.test.tsx`, `api/retail-variants-mock.test.ts`.
- **Tours.** `page-products` version 2 (a Sizes and colours step), `page-sizes-new` and `page-sizes`.
- **Photos (slice 2, ADR-053).** Add a photo on an item's edit form, on the item's page (for every size) and on a size's Change form
  (for that size alone). A PNG, JPEG or WebP up to 5 MB; a larger file or another type is refused in words before it is sent. The
  photo shows on the item cards, the item's page and in the item picker of the sale, restock and stock screens, so the right print
  is easy to pick. Take the photo off removes it from the screens; the file is kept. `item-photo.tsx`; tour `page-sizes` version 2.

## Orders (issue #385, ADR-052)

For shops that sell through WhatsApp, Instagram, Facebook and phone calls and deliver. BMS keeps no chat, storefront or checkout of its
own: the order is copied from the chat, so the form asks for the phone first and fills in the rest.

- **Orders** (`/staff/retail/orders`, `orders.tsx`, rules in `orders-state.ts`): the board (New, Packed, Out for delivery, To deliver today,
  Late, and Delivered today) as buttons that filter the list; New order for the chosen shop (a Store says it does not sell); the list by
  step, source and a search on the number, name or phone, newest first; each card shows who, the phone as a call link, where, when, the
  items at the prices agreed, the fee and the total, with Packed, Out for delivery (who takes it), Delivered and Cancel (why). Links to
  Delivery fees and cancellations, Stock and Credit sales.
- **New order**: the phone first; with nine digits or more an earlier customer is found (`/retail/orders/customer-lookup`) and Use these
  details fills the name, the credit buyer, the place and the fee; where it came from as six choices; the items through the usual item
  search (with the shop's stock) and the sale's line cards (packs, a changed price); the place (empty: collects at the shop), the day, the fee
  and notes; the totals. Saving keeps the items aside; the draft and its Idempotency-Key survive a reload.
- **Delivered**: five plain choices: Cash, all of it; Mobile money, all of it; Bank, all of it; Part now, the rest later (the amount, at least
  the fee, and how it was paid); All later (credit), fee paid now; and, for a credit buyer on the list, On the customer account, delivery
  fee too (an optional amount paid now for the items). The figures show what is paid now and what goes on credit. Offered only to whoever
  may sell at the order's shop. The success line names the sale. A fee left on the account shows on the order with a Save: the fee is paid
  form.
- **One order** (`/staff/retail/orders/$orderId`, `order.tsx`): the summary and the step buttons, Change the items while
  the order is ordered or packed (issue #393: the same item picker and line cards as New order, the items at the price agreed, a new
  item or a new quantity at today's price (the price shown changes with the quantity), why a price changed, why the items changed for the history; a packed order
  goes back to ordered and says to pack it again; the picker adds an item once per item and unit, so a second unit of an item on the order is its own line; a line
  left as agreed sends the price agreed and keeps it; at the quantity agreed but another price it sends the price shown; any other line sends a
  price only when the one shown differs from the sell price, as New order does, so with price lists on the server may charge the customer's list
  price or a quantity price break instead of the sell price shown), Change the
  delivery details (name, phone, place, day, fee, notes), Open sale (the ordinary sale view, with its payments, returns and void) once delivered,
  and the history: each step with the time, the person and the note. The breadcrumb names the order.
- **Delivery fees and cancellations** (`/staff/retail/orders-report`, `orders-report.tsx`): the days (the last 30 by default), the fees taken
  grouped by day, where the orders came from or who delivered, and the orders cancelled with the reason and the source, each opening the
  order.
- Stock kept aside: the Stock list and the item search of the sale, restock and order forms show "N free, M kept for orders" beside a
  balance that open orders hold part of (`reserved_qty`).
- Tours: `page-orders`, `page-order`, `page-orders-report` (`frontend/src/tour/tours-retail-orders.ts`). The retail menu has an Orders
  shortcut right after Sale, and the retail home an Orders tile, for whoever holds `retail.order.manage`.

## Slow movers on Stock health (issue #414, FR-RET-64)

- Stock health has a Slow movers section between Days of cover and Dead stock: items that sold in the branch in the last 90
  days, but whose stock lasts more than the business's months at that pace (or that sold fewer than its minimum). The title
  says the months; the hint says the minimum when one is set, and that items with no sale are under Dead stock.
- Each row: the item (opens Stock), its code and branch, the stock, the last day it sold, the months of stock ("over 120.0"
  when capped; "new, pace of N days" under it for an item in the branch less than 90 days) and, only when the body carries it, the money tied up at cost. On a phone the stock, the last sale and the money
  tied up move under the item. Below the table, for a caller with `retail.profit.read` in every branch, "Money tied up in slow
  stock" with the total at cost (only for such a caller, unlike the dead stock totals, which follow the branches shown). Units sold
  are counted before customer returns, as in Days of cover.
- "Sells better at Test Branch B (30 sold in 90 days). Move 8 piece there." under the item when another branch of the caller's
  sells it faster; only an open branch that stocks goods is named. A Move button follows for a person who may move stock from the row's branch; it opens Move stock at
  `/staff/retail/transfer?from=...&to=...&product=...&code=...&qty=...`, which uses that source branch when the person may move
  stock there and fills an empty form once (the destination and the item with the quantity, the item read with the source's
  stock so the usual "Only N in stock" hint shows). The person still reviews and saves; the server checks everything as for
  any stock move.
- Retail settings has a Slow movers card: the months (1 to 24) and the minimum sold in 90 days (0 for none), saved with the
  settings version like the other retail settings and audited as `retail.capabilities.changed`.
- Tours: `page-stock-health` v2 adds the Slow movers step (`health-slow`), `page-retail-settings` v4 the settings step
  (`retail-slow-movers`).

## Category picker (issue #578, FR-UXN-10)

The pilot tenant found the category select on Add an item a long list with no search and no way to add a category,
and showed the sheet of their old AppSheet app: Search, New, the list and Done. Every place a category is chosen now uses one
`CategoryPicker` (`frontend/src/areas/staff/retail/category-picker.tsx`).

- **Where.** Add an item and an item's own form (Items), the new item on Buy stock and Count stock, Add an item with sizes and
  colours, Change the name or category of an item with sizes, and the Category filters of Items and Stock (with All
  categories first and no Add). Expense categories are a different list and keep their select.
- **The field.** Looks like a select and names the chosen category, or "Choose a category". It is required where the
  category is, with the red asterisk; a Save with no category goes to it.
- **Phone (below 720px).** The kit's Sheet at full height, "Choose a category": the search box with the cursor in it, Add a
  new category, the list in rows of at least 48px with the chosen one tinted and ticked (no stripe), and Done. A tap on a
  row chooses it and closes the sheet; Done or a swipe down closes it as it was.
- **Laptop.** The same search, Add row and list open under the field in a card (Radix Popover), as wide as the field or at
  least 280px.
- **Search.** Any part of the name, whatever the case, on the categories already loaded (no request per key). Names show as
  the owner typed them. Nothing matching says `No category called "Rice". Add it?` with an Add Rice button, or without the
  question and the button for someone who cannot add.
- **Add a new category.** Only for `retail.catalogue.manage`. A short form in place: Name of the new category (the search
  text filled in), and the line "Item codes here will start with RCE, like RCE-0001, made by the app." from the code start
  suggestion as the name is typed. No code is typed (#562). Add category creates it (`POST /retail/categories`), chooses it,
  closes the picker and shows the dark toast "Added the category Rice. Its item codes start with RCE." It is the screen's own
  toast (`ScreenToast` in `retail/toast.tsx`), so a screen never shows two; the picker says the same in a live region by the
  field. The new category goes
  into every category list the screens keep (the catalogue's and the Stock filter's) before they are fetched again, so the
  field and the item code line name it at once.
- **A name already used.** Checked against every category, switched-off ones too, whatever the case, before anything is
  sent: "There is already a category called Bulbs." with Choose Bulbs, or "There is already a category called Torches
  (switched off)." with Switch Torches on and choose it. A name another phone added a moment ago is refused by the server and
  reads "A category with this name already exists." in place.
- **Keyboard and screen reader.** The field is a combobox that opens with a press, Enter, Space or the down arrow. The search
  is a combobox over the listbox "Categories": Up, Down, Home and End move the highlight (aria-activedescendant), Enter
  chooses it (or the only match), Escape closes, and the focus goes back to the field. The number found is announced.

## Item pickers (issue #534, FR-UXN-07)

Hatibu (HATS Gayaza) found the always-open list of items under the search box confusing, and asked for the AppSheet dropdown:
the items show only while you search, and go away once one is picked. Every item picker is now the one shared component
`ItemCombobox` (`frontend/src/app/ui/ItemCombobox.tsx`); `ProductPicker` (`retail/ui.tsx`) wraps it with the catalogue search.

- **Where.** Record a sale (and the offline picker, which searches the copy on the phone), restock, usage and damage, Move
  stock, count stock, price lists, orders and their items, quotations, labels, purchase orders, goods received without an
  order, joining sizes, and the item chosen for an unknown barcode. Correct stock opens from a line of the Stock page, which
  keeps its full list with Group by category, Expand all and Collapse all. No picker has the grouped browse list any more.
- **Empty box.** Only the label "Find an item by name, code or category" and the box: no list, and no request to the server.
- **Typing.** The term follows 250 ms after the last key (`useLiveSearch`). The matches open under the box, over the page, in
  a card with the larger shadow (no stripe), with a 150 ms fade and 4px rise that eases out and is off under reduced motion.
  Each option is a row at least 48px tall: the photo, the name and code with the typed words marked, then the category, the
  price where the screen shows one and the stock here (a negative balance flagged), and the verb of the screen (Add, Choose,
  Join this item, Add as a size). At most 10 show; with more the status line says "First 10 items shown. Type more to narrow."
  No match shows `No item matches "<text>".` in the list and the status line. The offline picker says `No item on this phone
  matches "<text>".` instead, or that nothing is saved on the phone yet. While more is typed, or the answer is on its way, the
  list says Searching and holds no option, so a quick tap or Enter can never pick a match of the earlier text; the status line
  stays quiet until the answer, so a screen reader hears it once per pause.
- **Picking.** A tap, or Up and Down then Enter, adds the item. The box empties, the list closes and the cursor stays in
  the box for the next item. Enter with nothing highlighted only settles the search; it never picks. Escape closes the list,
  and a second Escape empties the box; an Escape that closes the list or empties the box never reaches a panel around the picker, and one with nothing to close goes on to it. The list closes when the cursor leaves the picker and opens again on return.
- **Accessibility.** The input is `role="combobox"` with `aria-expanded`, `aria-controls` and `aria-autocomplete="list"`; the
  list is a `listbox` named by its box's label and its rows `option`s, the highlighted one `aria-selected` and named on the
  input by `aria-activedescendant`; the status line under the box is a polite live region. A second picker on a screen has its
  own label: the barcode assign panel's is "Find the item this barcode belongs to".
- **Count stock (owner decision on #535).** The search and Scan boxes sit on top. Below them, **Counted items (N)** holds the
  lines whose count is in, in the order done, and **Not counted yet (N)** holds every other item of the sheet (active items and
  inactive ones that still hold stock) with its count box and a **0** button for an item that is gone. A line moves to Counted
  items when its count is entered (on leaving the box, on Enter, or on 0) and back when the count is emptied. A found item that
  is not counted yet heads Not counted yet, marked, with the cursor in its count box (the one picker where a pick does not leave it in the search, since the count is the next step); a scan counts one more and moves the
  line to Counted items. With lines left, Review variance first says "12 items not counted. Review the difference anyway?"
  with **Keep counting** and **Review the difference anyway** (issue #629, which retired Finish here), as an inline warning, not a dialog. Only counted lines are sent, as before.
- **Count stock: a count that wipes stock (issue #629).** The count page's note ends "Leave an item empty if you did not count
  it.": an empty or blank box is never sent, so it never sets an item to 0. On **Review the difference**, a line that takes an
  item holding stock to 0, or to less than half of what it held, has a note under it in the entry warning's look, with its icon
  and words ("This removes 8 pieces. Is that right?"), and a 48px tick named for the item ("Yes, Test Plug is right"), so it
  never rests on colour alone. **Commit the count** passes `notReady` until every such line is ticked: a press sends nothing
  and goes to the first tick left, and a line under the list says how many are left. A count typed above zero that keeps half
  or more, and a count that adds, ask nothing. A new review (after **Back to the count**) asks again. The help article and the
  `page-stocktake` tour (version 6) say both, and name the button **Commit the count**, never Finish; the question before the
  review names the button it leads to, Review the difference anyway; the server's refusals that
  told a person to finish a count now say to commit it.
- **Count stock: the server checks big drops too (issue #636).** **Commit the count** sends the product ids of the ticked lines,
  and the server refuses a commit that leaves out a big drop with 409 `big_drop_not_acknowledged`, naming the lines, so a draft
  committed from another tab or the API never writes one unasked. The screen shows the server's words under the button.
  The count Sell makes of an item added there is of a new item, which holds nothing, so it is never a big drop; should the
  records ever hold more, Sell leaves the count for Count stock and says so, rather than sending it to be refused.
- **Scan box.** Unchanged (ADR-070 decision 11, owner decision D7): a scanner types only into the Scan box, which adds the item or one more.
- **Tours.** `retail-sales` version 16, `page-stocktake` 3 (the search on top and the list Not counted yet), `page-restock` 3, `page-usage` 2, `page-transfer` 3,
  `page-sizes-join` 2 and `page-labels` 2 word their search steps for the list that shows while you type.

## Share the receipt (issue #439, ADR-061, FR-RET-66)

A card **Send the receipt** under the receipt, right after a sale is saved (`sale.tsx`) and on an opened sale (`sales.tsx`, beside
Documents). It shows for a completed sale to someone who may read it: a seller for their own sales, a supervisor or an administrator
for every seller's (`receipt-share.tsx`, `canShareReceipt`). A voided or written-off sale has none. The card reads the receipt's
facts (`GET /retail/sales/{sale_id}/receipt`) and shows the server's refusal in plain words instead of the buttons, for a sale
whose goods were all taken back. The facts and the PDF are fetched again when the sale's version, paid amount or balance
changes, so after a payment on the same screen the next share sends the new receipt.

- **View receipt** (issue #500) shows the receipt on the screen as the PDF prints it, in the PDF's order: the logo (the one the PDF
  prints: the shop's when its stored file can be read, else the business's, by its public cacheable URL), the business, branch and address, the number and date, the title, Bill to, the
  sale, payment, due date and price list, the items as the PDF's table (No, Item with the code and price list line under it, Quantity with the pack under it, Unit
  price and Amount), the total in figures and
  words, the payment status with what was paid and the balance, the credit notes and the net after returns, the notes, Issued
  by, the received line and the receipt footer. Every one of these strings, the received line and the code and price list line
  included, is the server's own from the PDF's model; the screen joins and words none of them. It is fetched only when the button is tapped, from
  `GET /retail/sales/{sale_id}/receipt/printed`, which the server reads off the PDF's own model, so the receipt facts stay a
  cheap read for Share and WhatsApp and no PDF is fetched to show it: a phone's browser often cannot show a PDF inline (Android
  Chrome shows a blank box, iOS Safari only the first page). When the receipt cannot be laid out the preview says so and offers
  **Download the PDF**. The button keeps its label and says open or closed through `aria-expanded` and `aria-controls`; a
  payment or a return on the same screen closes the preview. Under it, **Download the PDF** fetches the real PDF; it and Share
  receipt share one fetch, so tapping both fetches the PDF once.
- **Share receipt** fetches `GET /retail/sales/{sale_id}/receipt/pdf` (never stored, documents capability on or off) and hands the
  PDF to the phone's share sheet as `Receipt <sale no>.pdf` with the caption as its text. On an iPhone (Safari or the installed
  app) and on an Android phone (Chrome or the installed app) the share sheet lists WhatsApp, Gmail, Messages, Telegram and the rest.
  A browser that cannot share files (some desktop browsers) downloads the PDF and says: "This browser cannot share files, so the
  receipt PDF was downloaded. Send it from your downloads." Cancelling the share sheet shows nothing. If the phone refuses the share
  because fetching took too long, the screen says "The receipt is ready. Tap Share receipt again to send it." and the second tap
  shares the fetched file at once; if the phone refuses again, the PDF downloads and the screen says so.
- **Customer's WhatsApp number**, prefilled from the buyer's record (read by id with the facts) or the number on the sale (an order delivered as a sale carries
  its phone), else empty. **WhatsApp customer** normalises it (`receipt-share-state.ts`, rules in ADR-061) and opens
  `wa.me/<number>?text=<caption>` in a new window; a number that could mean two things is refused under the box in plain words and
  nothing opens. Under the button: "WhatsApp opens with the receipt details. To send the PDF itself, use Share receipt and pick
  WhatsApp."
- **Save this number for <buyer>**, unticked, appears only when the buyer has a record with no number, the person may edit buyers
  (`retail.customer.manage` at the sale's branch) and a number was typed; ticked, the number is saved on the buyer through the existing edit when
  WhatsApp opens.
- The caption: `*<business name>*: Receipt <sale no>`, `Customer: <buyer>` (left out without one), `Amount paid: UGX <amount>
  (<way of paying>)`, after returns `Goods returned: UGX <amount> (credit note <numbers>)` and `Net after returns: UGX
  <amount>`, then `Balance outstanding: UGX <amount>` (the balance the sale carries) or `Fully settled, thank you!`, then `Thank you for your business.` The
  business name is the shop's own letterhead name, never the platform's; without one the first line is `Receipt <sale no>`.
  `*`, `_`, `~` and the backtick are taken out of the names so they cannot break WhatsApp's bold, and half an emoji is dropped.
- Nothing is written to the server except the optional number; the typed number is never logged. The tour `retail-sales` is
  version 10: its Save step names Share receipt and WhatsApp customer.
- **On the sale-complete screen (issue #534)** Share receipt is the first of the receipt actions and the screen's one main
  action, right under the receipt; View receipt, the WhatsApp number and WhatsApp customer follow, then **New sale** as a full
  width secondary button 48px tall, and the sale's documents last, so the next sale needs no scroll past them. Until Share receipt is drawn (its facts loading or
  failed to load), or when the sale has no receipt to share, New sale is the main action, so the screen always has one.

## Sell offline (issue #458, ADR-067, FR-RET-67)

Record a sale works with no network for a cash, mobile money or bank sale. The phone keeps a copy of the active items of each
branch the person may sell at (with their pack sizes, prices and the stock when saved), the buyers list and the person's
profile, refreshed when the app comes online, at sign-in and every 15 minutes (`frontend/src/offline/`).

- **Offline**, the form shows "You are offline. This sale is kept on this phone and sent when the phone is back online." with
  "Prices as of 22 Oct, 09:14". The item search reads the copy ("in stock when saved"); Credit (pay later) is greyed with "A credit
  sale needs the internet."; price lists are not applied, so a line is priced at the item's price or its pack price saved on the phone; only a holder of
  `retail.price.override` may type another, with a reason (at least 5 characters), as online; for anyone else the price field is
  read only with "Offline, the price saved on this phone is used. You do not have permission to change it." and a **Use the price
  saved on this phone** button on a line typed before the phone went offline;
  too little stock on the copy does not stop the sale. **Buyer (optional)** offers the customers saved on the phone or a typed
  name. The button reads **Save on this phone**.
- After saving, **Sale saved on this phone** with receipt **OFF-0A1B2C3D (provisional)**, the lines and the total, and "Not yet sent."
  Once sent, the same receipt reads **Sale sent**, **Receipt RS00000042**. A refused sale says why and that a manager decides.
- **Online**, a save whose answer never came ("The server did not answer. The sale may or may not be saved.") offers **Keep it on
  this phone** and **Try again**: the form is locked ("The items and the payment cannot be changed now, so the same sale is
  sent."), and either sends the same request with the same client sale id, so it is recorded once. **Clear this sale** starts a
  new one.
- A sale a manager **discarded** shows in the sheet as "Discarded by a manager. It is not recorded and is not sent again." for a
  day, and on its receipt.
- **The dot** in the top bar, drawn only while offline, sending, or with sales waiting or failed, and always for a manager (#526): green online, grey
  offline, amber sending, always with the word **Online**, **Offline** or **Syncing** beside it (below 430 px the dot and the number waiting alone, "3", in every state, issue #605), and "3 waiting to sync" ("3 waiting" at 480 px and below); the button's name says it all. Its sheet lists the waiting and
  failed sales, **Sync now** and, for a failed one, **Send again**. A manager (`retail.offline.resolve`: administrators and
  Supervisors) also sees the sales other phones could not record, with **Send it again** and **Discard** (a reason of at least 5
  characters, never recorded, audited), and the open clashes with **Mark resolved** and a note.
- **A shared phone**: when someone else signs in on a phone that holds another person's queued sales, a notice says "2 sales
  from another person are waiting on this phone. They are sent when that person signs in here again." While online the phone
  tells the server in short, and a manager's sheet lists them under **Waiting on a phone**.
- **Sign out** with sales waiting asks first: "3 sales are waiting to sync." with Sync now, Keep them and sign out, and Cancel.
  Kept sales go when the same person signs in on that phone again.
- **Opened with no network**, the app shows the last person signed in on this phone, with Record a sale and the outbox working;
  other screens say they cannot load. Back online, the session is restored or the sign-in page shows.
- The tour `retail-sales` (version 13) and `page-sale` (version 2) gain the step **Online or offline** on the dot.

Tests: `outbox.test.ts`, `sync-engine.test.ts`, `catalogue.test.ts`, `offline-sale.dom.test.tsx`, `sync-status.dom.test.tsx`; backend
`RetailOfflineSalesIT` and `PushPublishersIT`.

## Barcodes, scanning and labels (issue #345)

ADR-070, FR-RET-68, migration `V87`. Generic for every business; no capability switch.

- **Scan box** (`scan.tsx`, data-tour `scan-bar`) under the item search of the sale, restock, goods received without an order (purchasing), move stock and stock count forms
  (ADR-070 decision 11, owner decision D7): a box labelled **Scan here** ("Scan or type a barcode, then Enter"; "A barcode scanner
  types into this box. You can also type the barcode and press Enter.") and, beside it, a camera icon button **Scan with the
  camera** (data-tour `scan-camera`) shown only where the browser can open a camera (`navigator.mediaDevices.getUserMedia`). A USB
  or Bluetooth scanner types the code into the box and presses Enter, at any speed; a code typed by hand and Enter works the same.
  Enter in the box looks the code up and never submits the form. On the sale, restock, move stock and stock count forms the box
  takes the cursor when the form opens on a device with a mouse or trackpad (not on a phone, where it would open the keyboard);
  after each scan the box is empty and keeps the cursor for the next one (after the camera too, on such a device). Nothing reads keys
  anywhere else: with the cursor in a quantity, price or count field, whatever is typed stays in that field and Enter submits the
  form as usual (a scan made there types the code into the field, as any typing would). There is no scanner setting in
  Notifications and app. A found item is added at 1 or its line gains one, in the pack the barcode is on (the sale and the restock,
  with pack sizes on); on the stock count it adds one to the count, or the pack factor, and clears the search so the counted line shows, marked with an outline and scrolled into view; an item not on the branch's count sheet is refused with "<item> is not on the count sheet of this branch." On goods received the Scan box itself says "Added <item>." politely; on the
  sale, restock, move stock and stock count forms the form's dark toast says it instead (issue #498, below), so it is heard once. A
  scan gives a short beep and buzz where the device allows. On the restock the quantity goes to the branch chosen at the top; with none chosen a scan says "Choose the branch at the top of the page first".
- **Item added (issue #498, Hillary, 23 Oct 2026).** On Sell, Buy stock, Move stock and Count stock every add, picked from the
  search, scanned, or a new item made on the form, raises the dark toast (`useItemAdded` in `toast.tsx`, 2.5 seconds) with the
  quantity the line now has: "Added <item>, now 3" (the count on Count stock, the quantity at the chosen branch on Buy stock). A line
  with no quantity yet (a pick on Buy stock, an uncounted item picked on Count stock) says "Added <item>. Type how many." The line
  added or topped up flashes once (`.line-added`, a tint fading over 1.2s on `--ease-out`; under reduced motion a still tint that goes
  in one step), and a phone that can buzzes for 10ms. The toast is the live region a screen reader hears. An item already on the
  purchase or the move, picked again, gets an info toast ("<item> is already on the purchase. Change its quantity below.") with no
  flash, as on the sale.
- **Unknown code:** "No item has the barcode <code>." An administrator gets **Add this barcode to a product**: the item search
  opens, Choose puts the barcode on that item and adds it to the form. For a code that looks like spreadsheet damage (all digits of
  6, 7, 9, 10, 11 or 14, such as a GTIN-14 or a UPC-E) the search shows "This code is exactly as printed (Code 128)", as the item
  form does, and with it ticked the code is added as Code 128. Anyone else reads "Ask an administrator to add it to the
  item, or find the item by name above." A scanned code holding % ; ? # / or \ (a foreign Code 128) is
  never sent to the server: no item can hold it, so it gets the unknown code message at once, without the add button.
- **Camera** (`camera-scan.tsx`, loaded on Scan): the back camera full screen, "Hold the barcode inside the frame, about a hand
  away.", Close the camera. The browser's BarcodeDetector reads the frames; on an iPhone Quagga2 (loaded only then) reads a still
  four times a second. A blocked camera says how to allow it (on an iPhone: Settings, Safari, Camera); a missing or busy camera and
  an insecure address each have their own sentence.
- **Offline:** when the lookup cannot reach the server, only the items the screen has already read and cached (with their
  `barcodes`) are searched; the whole catalogue only once the offline catalogue cache of issue #463 carries `barcodes`.
- **Item form** (edit an item, `catalogue-barcodes.tsx`, data-tour `item-barcodes`): the barcodes with their format and pack, Remove,
  Add a barcode (the format and check digit are judged as you type, in the server's words), "This barcode is on" one unit or one
  pack, and **Make a barcode for labels** for an item with no made code. Administrators only.
- **Barcode labels** (`/staff/retail/catalogue/labels`, `labels.tsx`, a Catalogue tile, tour `page-labels`): find items and Add, how
  many labels of each, **Make the labels** (every label carries the item's own `IN` label code in Code 128, made for an item that has none, so one scanner setting reads every label), **Print**: an A4 sheet of 3 by 8
  labels of 70 by 37 mm with the name, the price and the barcode, black on white whatever the theme. The page states the label stock (A4, 3 by 8 labels of 70 by 37 mm, zero margin, actual size) and offers "My printer cannot print to the edge: keep 4 mm clear inside each label". Print takes everything else out of the layout (display none on the shell, not visibility hidden), so the sheet starts at the corner of the page.
- **Import:** the item file takes `barcode`, `pack barcode` and `barcode format` columns (several codes separated by spaces or `|`).
  A code a spreadsheet may have damaged (all digits of 6, 7, 9, 10, 11 or 14, or `4.00638E+12`) is an error row unless the
  row's `barcode format` is `code128`; on the item form the same code offers "This code is exactly as printed (Code 128)".
- Tours: a **Scan here** step on the Scan box in `retail-sales` (version 15, from version 14's Scan step), `page-sale` (version 3),
  `page-restock` (version 2), `page-transfer` (version 2) and `page-stocktake` (version 2); `page-labels` version 1.
- Tests: `barcode-state.test.ts`, `scan.dom.test.tsx`, `item-added.dom.test.tsx` (#498) (the Scan box; 1256 typed fast into a quantity, price or count field stays
  there, submits normally and is never looked up), `camera-scan.test.ts`; backend `BarcodesTest`, `RetailBarcodesIT`.

## Shop Home summary cards (issue #485, ADR-076, FR-UXN-06)

- **Where.** The staff Home (`/staff`), after the Shop card and before the Lending card. Every shop figure on Home, the Shop card's
  included, comes from one read, `GET /retail/reports/home`, under the branch chosen in the header (All branches sends none).
- **Takings today.** A line each for Cash and Mobile money, then Bank or any other way only when it moved money today (a way the
  PWA does not name yet reads Other, never its key; #489), and the Total. Read as the cash book reads a day, so it agrees with the cash summary: sales paid at the sale, payments on credit sales and
  delivery fees in; cancelled sales, reversed payments on account and refunds on goods taken back (yesterday's sale refunded today
  included) out. Nothing taken: "No money taken yet today." Link: Open the cash summary. The card is drawn
  only for a session that may read the cash summary (`retail.cashbook.read` in the branches covered and `retail.till.close`,
  ADR-076 decision 3), so it never waits on a figure the server will leave out.
- **Credit owed.** What customers owe, with "3 customers" as a hint, and Oldest due with the earliest due date of a sale still owing
  ("2 Oct 2026"). Nobody owes: "No customer owes you money." Link: Open credit control.
- **Top items.** Today and This week (from Monday) as two buttons; the five items that sold the most by value, each with its
  quantity and unit and the amount. Both lists come in the one read, so the switch asks nothing. Nothing sold: "Nothing sold yet
  today." or "Nothing sold yet this week." Link: Open sales analysis.
- **Each shop today.** One line per shop with its sales and number of sales, most first, and "No sales yet" for a shop that has
  sold nothing. Only with All branches chosen, for a session that sees every seller's sales in more than one branch; the server
  leaves the lines out over fewer than two shops. Link: Open the shop dashboard.
- **Who sees what.** The four cards and Sales today are money: they need `retail.sale.all_sellers` in the branches covered
  (ADR-055); Takings today needs besides what the cash summary needs (`retail.cashbook.read` in every branch the money covers, as the server checks, and `retail.till.close`; #489), so a
  Supervisor without the till sees no Takings card; a seller sees no money card, and now sees Low on stock (with `retail.stock.read`) and the open orders (with
  `retail.order.manage`) on the Shop card. A session with the orders permission and no sales read still reads the orders board.
- **States.** "Loading" while the read is on its way, "Could not load" in each card when it fails, the other cards unchanged. A
  part the server leaves out (no money permission over the branches covered, a branch outside the session's scope) takes its card
  or figure away instead of showing zeros. Recording a sale or moving an order refreshes the Home summary.
- **Tour.** `page-staff-home` v2: the step Your shop today on the Takings card (`data-tour="home-shop-today"`), for whoever sees
  everybody's money.
- **Screenshot description.** On a phone the cards stack: Shop (Sales today UGX 125,000, 7 sales; Low on stock 3 items; Open
  customer orders 4), Takings today (Cash 60,000, Mobile money 35,000, Bank 3,000, Total 98,000), Credit owed (450,000, 3
  customers; Oldest due 2 Oct 2026), Top items (Today pressed, Test Sugar 1kg 4 bag 24,000), Each shop today (Test Shop One
  100,000, 5 sales; Test Shop Two, No sales yet), each with its link underneath. On a wide screen they sit side by side in columns
  of at least 280px.

## Last paid and the average buying price (issue #523, ADR-079, FR-RET-69)

- The item's own buying price is the last one paid: the item card says "last paid UGX 750" and the Change price form calls the field
  **Last paid**, with a hint that profit uses the average buying price of the stock in each shop, which restocks keep up to date. The
  new item form still asks for the **Buying price**; it becomes the last paid price and the first average of a shop that has none.
- The stock lists call their cost column **Average cost**: on one shop, that shop's average per smallest unit; on all shops, the
  average of the shops that hold stock, weighted by what each holds. The phone cards say "Average cost UGX 769, price UGX 1,000 each".
- A restock line that changes the item's buying price says "Last paid will change from UGX 750 to UGX 800", and the restock's summary
  "last paid changed from UGX 750 to UGX 800". Nothing else on the screens changes: sale lines, profit and the reports show the cost
  each sale line kept, which is now the shop's average at the moment of sale.

## One glossary of shop words (issue #547, D24)

- The words a person sees come from `frontend/src/app/glossary.ts` (spec `units-packs-and-costing.md` section 5, PR #513).
  Restock is now **Buy stock** (the screen) and a record of it is a **purchase** (Past purchases, Save the purchase,
  Correct this purchase, Stock bought on credit); Stock-take is **Count stock** (a stock count); Variance is
  **Difference**; Shrinkage is **Lost or damaged stock**; Credit buyers is **Customers who owe**; the Unit of an item is
  **Counted in**; Pack sizes is **Cartons, boxes and other packs**.
- Routes (`/staff/retail/restock`, `/restocks`, `/stocktake`, `/catalogue/buyers`), API fields, permission names and error
  codes are unchanged. The backend's plain-words refusals that a person reads use the same words.
- `glossary.test.ts` fails when SKU, variance, shrinkage, UOM or restock (any case), or a retired label, shows in the
  source strings or JSX text of a screen, in a tour or in the menu help. It reads source, not the rendered page: words
  built at run time and the backend's refusals are worded by hand. The tours whose words changed took a new version.

## Approval rules and actions that wait (issue #343, ADR-091, FR-APR-09)

- **Approval rules** (Business set-up, `/staff/setup/approvals`, administrators): the business's actions grouped as Shop and
  Lending, one card each. A retail action has a checkbox "Ask an administrator first" and, while on, "<action> from this amount"
  (the one money field; 0 means every one, or for credit write-offs "a second administrator when the business has two or more", ADR-043's rule). A lending action reads "Always on" and shows only its amount. One Hint line per card says
  what happens while it waits. Below the shop list: "Selling below cost is not an approval", with a link to Staff users. Review and
  save reads each change back as "Sale voids: a second person from UGX 50,000 (was no second person).", says when the other
  administrators will be told, and asks for the administrator's own password, or the authenticator code when they have one.
- **While an action waits** (void a sale, correct a quantity, record an expense): a neutral note, not a success: "Sent for
  approval. <what> Nothing changes until an administrator approves it." and the row "See it, or cancel it, on Approvals". The void
  button stays away; the expense form offers Record another expense.
- **Approvals** names every action in words ("Void a sale", "Correct a stock quantity", "Record an expense"); an unknown type keeps
  its identifier.
- **Retail settings** returns Hint (#575) now links to the returns row of the approval rules ("Set a returns limit in Approval
  rules").
