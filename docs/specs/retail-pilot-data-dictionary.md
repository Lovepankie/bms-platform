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
- **Hard-coded totals.** The valuation view shows its totals on one fixed row number of the product
  master. They are not data and are not imported.
- **Restock prices.** The restock form pre-fills cost and sell price from the product and staff may
  change them. That change is the price update (FR-RET-06) and must be reproduced in price history.
