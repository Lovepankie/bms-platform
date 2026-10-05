# Retail pilot data dictionary

**Status:** Draft · **Applies to:** the pilot import (FR-RET-12)

Describes the shape of the pilot's source spreadsheet and how each part maps to retail tables.
It holds no real data. The golden test uses a fabricated fixture of the same shape.

## 1. Source tabs and mapping

| Source tab | Content | Target |
|---|---|---|
| Product master | Product id, category, description, unit, cost, sell, a quantity column per branch, derived totals | `retail_products` (id, category, description, unit, prices); the per-branch quantities become `legacy_balance` movements. Derived total columns are ignored. |
| Sales log | User, datetime, branch, product, quantity, unit price, total, buyer, contact, proposed payment date, sale type, captured before and after quantities | `retail_sales` and lines, marked historical. Before and after quantities are ignored. |
| Restock log | User, date, product, unit cost, unit sell, quantity per branch, supplier text, stock before and after | `retail_purchases` and `purchase` movements, marked historical; supplier text becomes a supplier. Before and after columns are ignored. |
| Usage and damage log | User, datetime, branch, product, usage type, reason, quantity, unit cost | `usage` and `damage` movements, marked historical. |
| Supplier list | Name, contact, category, status | `retail_suppliers` |
| Credit buyers | Name, contact | `retail_customers` |
| Categories, units | Dropdown lists | `retail_categories`, `retail_units` |
| App users | Name, email, role, branch | Staff invitations. Roles: admin maps to the admin role, sales to the sales role. |
| Shops | Name | Branches |
| Expenses, banking, withdrawals, advances, daily savings | Cash handling | Not imported in the first release. Mapped when the cash book is built. |

## 2. Rules

1. A product id is matched case-insensitively and trimmed. Ids that differ only by case are one product.
2. Quantities and prices become `numeric(14,3)` and integer minor units. Blank is zero.
3. History rows are movements and documents marked `historical`. They post no journals.
4. For each branch and product, a `legacy_balance` movement equals the source's current quantity
   minus the sum of that product's imported history movements for the branch, so the balance
   equals what the shops see today. The first stock-take replaces it.
5. One opening journal per branch records inventory at current valuation against the opening
   balance equity account.
6. Re-running the import must not duplicate: each source row is keyed by tab, row number and a
   content hash.

## 3. Known data issues the importer must handle

- **Opening stock was never logged.** Summed history does not reproduce the current quantities,
  and many running balances go negative. Rule 4 absorbs this.
- **Date columns.** Date-only cells must be read as the spreadsheet displays them. Reading them
  as timestamps in the script's time zone shifts the date. The importer reads formatted values.
- **Free text.** Supplier names, buyer names and phone numbers are inconsistent, and contact
  fields hold several numbers or partial numbers. They are kept as entered and flagged for review.
- **Unused branches.** Branches with no trading are imported as branches but carry no history.
- **Internal advances.** Tabs named as loans record advances to the owner and the company, not
  customer lending. They are not mapped to the lending module.
- **Product description repeats and near duplicates** go to the import review queue.
- **Stock-takes and returns recorded as restocks.** The restock log carries rows whose supplier text
  marks stock found at a count and customer returns. The importer maps them to `adjustment` and
  `return` movements, not purchases, and they post no supplier payable.
- **Hard-coded totals.** The valuation view shows its totals on one fixed row number of the product
  master. They are not data and are not imported.
- **Restock prices.** The restock form pre-fills cost and sell price from the product and staff may
  change them. That change is the price update (FR-RET-06) and must be reproduced in price history.

## 4. Normalised export format (the `import-retail` command)

The importer does not read the spreadsheet. A person exports it to a directory of the files below,
applying sections 2 and 3 on the way (formatted dates, ids trimmed, stock-takes and returns marked
by `kind`), and runs `import-retail` on the directory (`docs/runbooks/import-retail.md`, SDD
chapter 13 section 13.13). A fabricated example is `fixtures/retail/import-sample/`.

**Encoding.** UTF-8 JSON Lines: one JSON object per line, blank lines ignored, a byte order mark on
the first line tolerated. A line that is not a JSON object is reported and skipped. A missing file
is treated as empty and reported. Fields not listed here are reported once per file and ignored.

**Types.**

| Type | Format | Rules |
|---|---|---|
| text | JSON string | Trimmed; empty is absent; longer than the column is an error |
| money | JSON integer (or a string of one) | Integer minor units of the tenant currency, zero or more; UGX has no decimals, so `12500` is UGX 12,500. A fraction is an error, never rounded |
| qty | decimal string, for example `"12.500"` (a JSON number is accepted) | At most three decimal places; blank or absent is zero (rule 2) |
| date | ISO 8601: `2026-09-10`, `2026-09-10T14:30:00`, `2026-09-10 14:30`, or with an offset `2026-09-10T14:30:00+03:00` | A value without an offset is local time in the tenant's zone (Africa/Kampala for the pilot); a date alone is the start of that day |
| code | text | Product codes are matched trimmed and ignoring case (rule 1). Branch codes are 2 to 10 letters or digits, matched ignoring case and stored in capitals |

An unknown product or branch code, a missing required field or a malformed value skips the row and
is listed in the report with its file and line. Nothing is guessed.

**Files**, in the order they are imported (each in one transaction):

| File | Fields (required in bold) | Becomes |
|---|---|---|
| `branches.jsonl` | **`code`**, **`name`** | A branch, unless one with that code exists. Plan limits apply |
| `categories.jsonl` | **`name`** (100) | A category, unless one with that name exists ignoring case |
| `units.jsonl` | **`name`** (30) | A unit, the same way |
| `products.jsonl` | **`code`** (40), **`description`** (300), **`category`**, **`unit`**, **`cost_minor`** (money), **`sell_minor`** (money), `active` (boolean, default true) | A product with these current prices and an `initial` price history row. A later row whose code is the same ignoring case and spaces is reported and skipped. A category or unit not in its file is created and reported. An existing product whose prices differ takes these prices with an `import` history row |
| `suppliers.jsonl` | **`name`** (200) | A supplier, unless one with that name exists ignoring case |
| `customers.jsonl` | **`name`** (200), `contact` (100, kept as entered) | A credit buyer, unless one with that name exists ignoring case |
| `purchases.jsonl` | **`source_ref`** (100), **`product_code`**, **`kind`** (`purchase`, `adjustment` or `return`), **`unit_cost_minor`**, `unit_sell_minor`, **`qty_by_branch`** (an object of branch code to qty), **`purchased_on`** (date), `supplier` (text), `source_user` (text) | `purchase`: a historical purchase of one line with a `purchase` movement per branch (zero quantities left out; negative refused); the supplier is matched or created. `adjustment`: an `adjustment` movement per branch, signed as given. `return`: a `return` movement per branch, positive. Neither creates a purchase or a supplier payable. Where two consecutive `purchase` rows of a product (by date, then line) differ in cost or sell price, an `import` price history row is written |
| `sales.jsonl` | **`source_ref`**, **`branch`**, **`product_code`**, **`qty`** (positive), **`unit_price_minor`**, **`unit_cost_minor`**, **`payment_method`** (`cash` or `credit`), `buyer`, `buyer_contact`, `due_date` (date, credit only), **`sold_at`** (date), `source_user` | A historical sale of one line with the unit price and unit cost snapshots as given, and a `sale` movement. A credit sale needs a `buyer`; it is linked to the credit buyer of that name if there is one, kept as entered either way, and imported unpaid |
| `usage.jsonl` | **`source_ref`**, **`branch`**, **`product_code`**, **`kind`** (`used` or `damaged`), **`reason`** (300), **`qty`** (positive), **`unit_cost_minor`**, **`reported_at`** (date), `source_user` | A historical usage or damage report of one line at that unit cost, and a `usage` or `damage` movement |
| `balances.jsonl` | **`branch`**, **`product_code`**, **`qty`** (may be negative) | The source's current quantity. One `legacy_balance` movement makes the balance equal it (rule 4); then one opening journal per branch for the positive quantities at the product's current cost (rule 5). Negative quantities are listed for the first stock-take. A second row for the same branch and product is reported and skipped |

History rows (`purchases`, `sales`, `usage`) are flagged historical and post no journals (rule 3).
`source_user` is the source system's user name, kept with the source reference; it does not need to
match a staff account.

Example lines (fabricated):

```json
{"code": "KLA", "name": "Test Shop Kampala"}
{"code": "TP-001", "description": "Test cable item 01", "category": "Cables", "unit": "metre", "cost_minor": 700, "sell_minor": 1200, "active": true}
{"source_ref": "PUR-9001", "product_code": "TP-003", "kind": "adjustment", "unit_cost_minor": 2500, "unit_sell_minor": 3000, "qty_by_branch": {"KLA": "4", "ENT": "-2.5"}, "purchased_on": "2026-08-15", "source_user": "test.admin01"}
{"source_ref": "SAL-00011", "branch": "JJA", "product_code": "TP-004", "qty": "2.000", "unit_price_minor": 5000, "unit_cost_minor": 4000, "payment_method": "credit", "buyer": "Test Buyer 03", "buyer_contact": "+256700000003", "due_date": "2026-10-12", "sold_at": "2026-08-12T09:11:00", "source_user": "test.sales03"}
{"source_ref": "USE-003", "branch": "KLA", "product_code": "TP-009", "kind": "used", "reason": "Test reason 03", "qty": "1.000", "unit_cost_minor": 9000, "reported_at": "2026-09-10T15:03:00", "source_user": "test.sales01"}
{"branch": "JJA", "product_code": "TP-007", "qty": "-3.000"}
```
