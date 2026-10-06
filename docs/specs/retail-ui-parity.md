# Retail UI parity with the pilot's AppSheet app

**Status:** Draft · **Issues:** #145 (the parity pass), #144 (All branches) · **Reads with:** `retail-pilot-app-behaviour.md`, `retail-pilot-data-dictionary.md`, `retail-ui-notes.md`

A shop owner who knows the pilot's AppSheet app should find the same information in the retail screens. This table
walks every view and form the app has (named in `retail-pilot-app-behaviour.md` sections 2 to 6 and the tabs of
`retail-pilot-data-dictionary.md` section 1) against the screen that answers it.

Status words: **done** (the screen gives the same information), **partly** (some of it), **missing** (no screen
yet), **out of scope** (with the reason). Screens are under `frontend/src/areas/staff/retail/`; routes are under
`/staff/retail/`.

## Sales

| AppSheet view | Equivalent screen | Status | What remains |
|---|---|---|---|
| Record a sale (cash or credit, buyer, proposed payment date) | Record a sale (`sale`) | done | Nothing. The category shows beside each item in the picker (#145) |
| Edit a credit sale | Replaced on purpose by payments against the sale (FR-RET-05, behaviour spec section 8) | partly | The API takes payments (`POST /retail/sales/{id}/payments`); the PWA has no "Record a payment" form yet, so a credit sale can be read (Credit sales) but not paid from the screens |
| Credit sales (sales where the type is Credit) | Credit sales (`credit-sales`): buyer, date, amount, due date, state (Unpaid, Part paid, Overdue, Paid); filter by buyer and by what is owed; open a sale for its lines | done | Nothing for the list. Payment history on a sale is not shown yet |
| Sales list and history | All sales (`sales`): filters by date range, branch (the Branch box), buyer, payment method and item; open a sale for its lines | done | Nothing for the list |
| Sales by cash and by profit margin, grouped by shop, year and month | Daily profit (`profit`): per branch and per day, with All branches | partly | No grouping by month or year, no margin percentage, no "by cash" cut. The report API is per day |
| Void or return a sale | None | missing | The API voids a sale (`POST /retail/sales/{id}/void`); no screen. Customer returns were recorded as restocks in the AppSheet app and are `return` movements here |

## Stock

| AppSheet view | Equivalent screen | Status | What remains |
|---|---|---|---|
| Product catalogue (quantity and selling price, no cost for sales staff) | Stock (`stock`): item, category, quantity, price, cost only with `retail.profit.read`; search by name, code or category; category filter | done | Nothing |
| Product categories (with related products) | Stock category filter and the by-category table on Stock value | done | Managing categories is missing (see Catalogue management) |
| Low stock items | Stock tab "Low stock" (at or below 5, out of stock included; a constant in `StockService`, documented in SDD 7.11.20) | partly | The AppSheet app has no per-product reorder level in the data, so one threshold serves all (ADR-029). A per-product or per-tenant level needs a retail settings group |
| Out of stock items | Stock tab "Out of stock" (zero or less) | done | Nothing |
| Stock per shop and across shops | Stock with the Branch box on one branch, or All branches (a column per branch, a total, the negative flag per cell; on a phone the total with a per-branch breakdown) | done | Nothing |
| Stock-take | Stock-take (`stocktake`) | done | Nothing |
| Shop-to-shop moves (recorded as negative and positive restocks) | Move stock (`transfer`) and Stock moves (`transfers`) | done | Better than the app: real transfers (ADR-020 amendment, #84) |
| Usage and damage form | Usage and damage (`usage`) | done | Nothing |
| Damaged and used records | None | missing | The movements API lists them (`GET /retail/stock/movements`); no screen. The loss as a share of stock for admins is not shown |

## Purchasing and valuation

| AppSheet view | Equivalent screen | Status | What remains |
|---|---|---|---|
| Restock form (admin): per-shop quantities, unit cost and sell price, supplier | Restock (`restock`) | done | Nothing. The category shows beside each item in the picker |
| Purchase records (admin) | None | missing | `GET /retail/purchases` exists; no list screen |
| Stock valuation per shop and combined (admin) | Stock value (`valuation`): at cost and at selling price, per branch with All branches, per item | done | Nothing |
| Expected sales and expected profit, and percent profit | Stock value: Expected profit and Profit % (over cost) per item, branch, category and total, only with `retail.profit.read` | done | Nothing |
| Valuation by category | Stock value, "By category" table | done | Nothing |

## Catalogue management (not in this pull request)

The AppSheet app has list and form views for each of these. The API exists for most; the screens do not, and they
are not part of this pull request.

| AppSheet view | Equivalent screen | Status | What remains |
|---|---|---|---|
| Products (add, edit, activate) and prices | None | missing | List, search, add, edit and activate through `GET/POST/PATCH /retail/products`, prices through `POST /retail/products/{id}/prices` (needs `If-Match`), price history |
| Product categories | None | missing | List and add through `/retail/categories` (no rename or delete in the API) |
| Units | None | missing | List and add through `/retail/units` |
| Suppliers | None (a supplier can be added inside a Restock) | missing | List, search, add and edit; the API has list and create only |
| Credit buyers (customers) | None (a buyer name is typed on a credit sale) | missing | List, add and edit through `/retail/customers`, with each buyer's balance (`/retail/customers/{id}/balance`) |

## Cash handling and administration

| AppSheet view | Equivalent screen | Status | What remains |
|---|---|---|---|
| Daily savings, cash banked, withdrawals | None | out of scope | The cash book, pending ADR-022 (behaviour spec section 8) |
| Expenses and expense records | None | out of scope | The cash book, pending ADR-022 |
| Advances and their payments | None | out of scope | Internal advances to the owner are not customer lending and are not imported (data dictionary section 3) |
| Location captured with a distance from the shop | None | out of scope | Later, as an audit signal (behaviour spec section 8) |
| Users, roles and shops | Business set-up and the staff area (core) | out of scope | Handled by the platform core, not the retail screens (ADR-020: permissions replace admin lists) |

## Summary

Done: record a sale, credit sales, all sales, stock (single branch and All branches, category filter, low and out
of stock), stock-take, usage, restock, move stock, stock value with expected profit, daily profit.

Partly: paying a credit sale from the screens, low stock with a per-product level, profit grouped by month or year.

Missing: void and returns, damaged and used records, purchase records, and the catalogue management screens for
products, categories, units, suppliers and credit buyers.

Out of scope: cash handling (savings, banking, withdrawals, expenses, advances), location capture, user and shop
administration.
