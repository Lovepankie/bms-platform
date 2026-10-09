# Retail UI parity with the pilot's AppSheet app

**Status:** Draft · **Issues:** #145 (the parity pass), #144 (All branches), #146 (catalogue management), #121 (stock-take difference) · **Reads with:** `retail-pilot-app-behaviour.md`, `retail-pilot-data-dictionary.md`, `retail-ui-notes.md`

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
| Sales by cash and by profit margin, grouped by shop, year and month | Sales analysis (`sales-analysis`): by day, week or month, by shop, category, item and seller; Margins (`margins`): profit and margin percent by item and category; Daily profit (`profit`) per branch and day | done | Nothing for the grouping and the margin. The year is a range of up to 366 days. The cash against credit split of a day is on the retail home dashboard; there is no "by cash" cut of the profit report |
| Void or return a sale | None | missing | The API voids a sale (`POST /retail/sales/{id}/void`); no screen. Customer returns were recorded as restocks in the AppSheet app and are `return` movements here |

## Stock

| AppSheet view | Equivalent screen | Status | What remains |
|---|---|---|---|
| Product catalogue (quantity and selling price, no cost for sales staff) | Stock (`stock`): item, category, quantity, price, cost only with `retail.profit.read`; search by name, code or category; category filter | done | Nothing |
| Product categories (with related products) | Stock category filter and the by-category table on Stock value | done | Managing categories is on the Categories screen (see Catalogue management) |
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

## Catalogue management (#146)

The AppSheet app has list and form views for each of these; the screens are under `/staff/retail/catalogue`
(`docs/specs/retail-ui-notes.md`, "Catalogue management").

| AppSheet view | Equivalent screen | Status | What remains |
|---|---|---|---|
| Products (add, edit, activate) and prices | Items (`products`): search, category and on sale filters; add, edit, switch off or on; Change price with the reason and the price history; cost only with `retail.profit.read` | done | Nothing. Prices change only through Change price (`retail.price.edit`); a price at or below cost is saved with a warning (only for a user who can see cost), and a sale at it is refused by the server unless the seller may sell below cost |
| Product categories | Categories (`categories`): add, rename, switch off or on, "Used by N items" | done | Nothing. A category is never deleted (items and history point at it) |
| Units | Units (`units`): the same | done | Nothing |
| Suppliers | Suppliers (`suppliers`): add, edit name and contact, switch off or on | done | Nothing. Needs `retail.catalogue.manage` and, for the list, `retail.purchase.create` |
| Credit buyers (customers) | Credit buyers (`buyers`): add, edit name and contact, what each owes | done | Nothing. What is owed counts the branches the user may read |
| Start a new client from a product list (no AppSheet view) | Import items (`import`): paste or choose a CSV, check, then add; administrators only | done | The file is read as whole amounts in the business's currency; no photos or barcodes |
| Stock-take losses and gains in the profit report | Daily profit, column "Stock-take difference" (#121) | done | Nothing |

## Analytics (#149)

The pilot owner was promised better analytics than the AppSheet app. These screens are reports on the existing data
(`docs/specs/retail-ui-notes.md`, "Analytics screens"); they have no AppSheet view to match.

| Owner question | Screen | Status | What remains |
|---|---|---|---|
| How are we doing today, this week, this month, by shop? | Retail home dashboard: today's sales, cash against credit, profit, 7 and 30 day sparklines, stock value, out of stock and low stock, a table by shop | done | "Banked against expected" and "Savings" are marked placeholders until the cash book |
| Sales by day, week, month, shop, category, item, seller; top sellers; slow movers; items with no sale | Sales analysis (`sales-analysis`) | done | Nothing |
| Profit and margin by item and category; items sold below a target; what a price change did | Margins (`margins`), profit reader only | done | Nothing |
| Days of cover, what to reorder, dead stock, shrinkage and damage cost by shop | Stock health (`stock-health`) | done | A reorder suggestion is a number of units at the pace of 30 days; no supplier or lead time per item |
| Who owes, how late, what was paid | Credit control (`credit-control`) | done | The "Record a payment" form is still missing (see Sales) |
| Expected profit of the stock and a run rate | Business evaluation (`evaluation`), profit reader only; an estimate, not a forecast | done | The exact formula the AppSheet app used is still to be agreed with the owner (issue #149 item 7); these are the stated formulas |
| Cash view per shop per day (takings, expenses, banked, withdrawn, savings, difference) | None | out of scope | The cash book |
| Export to PDF and Excel; a daily summary on WhatsApp | None | out of scope | Report runs (SDD 7.11.8) and the notification outbox |

## Cash handling and administration

| AppSheet view | Equivalent screen | Status | What remains |
|---|---|---|---|
| Daily savings | Savings (with its records) | done | The suggestion and every savings amount show only with `retail.profit.read` (ADR-022 decision 13). NOT built: the savings report screen (`GET /retail/reports/cash/savings` has no screen; the records list covers it) and CSV export of the reports |
| Cash banked (Banked) | Banking and Banking report | done | Expected amount computed by the server; imported days listed apart |
| Cash withdrawn from the bank | Cash withdrawals | done | Needs a shop (the ledger needs a branch) |
| Expenses and expense records | Expenses, Expenses report and Expense lists | done | NOT built: the optional receipt photo (the frontend has no documents upload client yet; the API accepts a `receipt_document_id` of a document uploaded for an expense) |
| Loans and loan payments (advances to the owner or company) | Advances (list, repayments, outstanding report) | done | Advances, not lending (ADR-022); imported through `import-retail` |
| The day's cash position | Cash summary | done | New: closing agrees with the books |
| Location captured with a distance from the shop | None | out of scope | Later, as an audit signal (behaviour spec section 8) |
| Users, roles and shops | Business set-up and the staff area (core) | out of scope | Handled by the platform core, not the retail screens (ADR-020: permissions replace admin lists) |

## Summary

Done: record a sale, credit sales, all sales, stock (single branch and All branches, category filter, low and out
of stock), stock-take, usage, restock, move stock, stock value with expected profit, daily profit (with the stock-take
difference), the catalogue screens for items, prices, categories, units, suppliers and credit buyers, with the
item import, the analytics screens (sales analysis, margins, stock health, credit control, business evaluation and the owner dashboard), and the cash book screens (savings, banking and its report, withdrawals, expenses and its report and
lists, advances, cash summary).

Partly: paying a credit sale from the screens, low stock with a per-product level.

Missing: void and returns, damaged and used records, purchase records.

Out of scope: location capture, user and shop
administration.
