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
| Daily savings, company expenses, cash banked, withdrawals, loan disbursement and payments, expense categories, loan customers | Cash handling | Not imported in the first release. Proposed mapping in section 5 (ADR-022); history rows post no journals. |

## 2. Rules

1. A product id is matched case-insensitively and trimmed. Ids that differ only by case are one product. Trimming removes every Unicode space at the ends, including the no-break space a spreadsheet paste leaves, and NFKC folds full-width forms; an id with a control character or a Unicode space other than a plain space inside is refused. The API and the importer share one normaliser (`RetailCatalogue.normaliseCode`, review F9).
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
- **Shop-to-shop moves recorded as restocks.** The restock log also carries moves of stock from one
  shop to another, as a negative quantity at one branch and a positive one at the other. They are
  exported as `adjustment` rows, signed per branch, and stay adjustments after the import: the
  importer creates no transfer and imported history is not rewritten. New moves are recorded as
  stock transfers (`retail_transfers`, movement kinds `transfer_out` and `transfer_in`; FR-RET-16,
  issue #84).
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
| code | text | Product codes are normalised exactly as the catalogue normalises them (`RetailCatalogue.normaliseCode`: any Unicode space removed at the ends, then NFKC, so a no-break space or a full-width letter cannot make a second code), then matched ignoring case (rule 1); a code with a control character or a special space inside is reported and the row skipped. Branch codes are 2 to 10 letters or digits, matched ignoring case and stored in capitals |

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

## 5. Cash book export (proposed, ADR-022, FR-RET-30)

Not built. The same encoding, types and never-guess rules as section 4, in the order
`cash_parties`, `expense_categories`, `savings`, `expenses`, `banking`, `withdrawals`, `advances`,
`advance_payments`, `cash_balances`. The pilot's "loan" tabs are advances to the owner or the company,
not customer lending (section 3, "Internal advances"). All of it is fabricated below.

| File | Fields (required in bold) | Becomes |
|---|---|---|
| `cash_parties.jsonl` | **`name`** (200), `contact` (100), **`kind`** (`owner`, `company`, `staff`, `supplier`, `other`) | A cash party (beneficiary or advance party), unless one with that kind and name exists ignoring case. The pilot's loan customers list and its beneficiary list both land here |
| `expense_categories.jsonl` | **`category`** (100), **`item`** (100), `requires_explanation` (boolean; true for the item "others") | A category and an item under it, matched ignoring case. The real list is the pilot's expense categories tab, never typed in by hand |
| `savings.jsonl` | **`source_ref`**, **`branch`**, **`business_date`** (date), **`amount_minor`** (money), `total_sold_minor`, `source_user` | A historical savings record. A second row for one branch and date is reported and skipped. The pilot's virtual columns (total sold, daily profit) are not imported as facts; `total_sold_minor` is kept only as the screen snapshot |
| `expenses.jsonl` | **`source_ref`**, **`branch`**, **`business_date`**, **`category`**, **`item`**, `beneficiary`, **`amount_minor`**, `explanation` (required when the item requires one), `source_user` | A historical expense. A category or item missing from `expense_categories` is created as written and reported |
| `banking.jsonl` | **`source_ref`**, **`branch`**, **`business_date`**, **`amount_minor`**, `banked_at` (date), `source_user` | A historical banking record. The pilot's expected amount is virtual and not stored, so `expected_minor` is computed from the imported history |
| `withdrawals.jsonl` | **`source_ref`**, **`business_date`**, **`amount_minor`**, `branch` (default the head office branch; the pilot has no shop), `source_user` | A historical withdrawal |
| `advances.jsonl` | **`source_ref`** (the pilot's advance id), **`branch`** (source of money), **`party`**, `taken_by`, **`principal_minor`**, `purpose`, `business_date`, `processing_fee_minor` (not modelled: reported, kept in the note), `source_user` | A historical advance |
| `advance_payments.jsonl` | **`source_ref`**, **`advance_ref`**, **`amount_minor`**, **`method`** (`cash`, `mobile_money`, `bank`; the pilot's payment channel), **`paid_on`**, `source_user` | A historical repayment; unknown `advance_ref` or an amount above the remaining principal is reported and skipped |
| `cash_balances.jsonl` | **`branch`**, `cash_on_hand_minor`, `bank_minor`, `savings_reserve_minor` | One opening journal per branch with `owner_advances` for the imported advances' outstanding balance, against `opening_balance_equity` |

Fabricated example lines:

```json
{"name": "Test Owner 01", "contact": "+256700000001", "kind": "owner"}
{"category": "Test Category A", "item": "Test Item 1", "requires_explanation": false}
{"category": "Test Category A", "item": "others", "requires_explanation": true}
{"source_ref": "SAV-001", "branch": "KLA", "business_date": "2026-09-10", "amount_minor": 50000, "total_sold_minor": 400000, "source_user": "test.sales01"}
{"source_ref": "BNK-001", "branch": "KLA", "business_date": "2026-09-10", "amount_minor": 300000, "banked_at": "2026-09-10T17:40:00", "source_user": "test.sales01"}
{"source_ref": "ADV-001", "branch": "KLA", "party": "Test Owner 01", "principal_minor": 200000, "purpose": "Test purpose", "business_date": "2026-09-01"}
```
