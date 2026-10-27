# AGENTS.md

Read this file first. It is the onboarding brief for every developer and every coding
agent working in this repository.

## What this repository is

**BMS Platform** is a multi-tenant SaaS business management system for small and medium
enterprises in Uganda and East Africa. It is a **platform core plus vertical modules**
that a tenant switches on (ADR-001). The first vertical, being built now, is **lending**
(microfinance and money lending: members, loans, collateral, savings, investments,
collections). Retail is the second vertical and is being built now (ADR-020, story #50).

This repository holds the application code and, under `docs/`, the complete specification:
the System Design Document, the decision log (ADRs), the Structurizr architecture model,
the MVP scope and the pilot data dictionary. Documentation lives with the code; there is
no other documentation repository (ADR-005).

The first customer is referred to only as **the pilot tenant**: a licensed money lender
(Tier 4, Uganda) with loans, savings and investment products.

## Current state (read before writing code)

- The specification (`docs/`) is complete enough to build the lending MVP.
- **Stack (decided):** Java 25, Spring Boot 4.1, Spring Modulith 2.1, Flyway, PostgreSQL 16
  with forced row-level security, Maven (ADR-010); background jobs on PostgreSQL with
  db-scheduler, no Redis (ADR-008); React 18, TypeScript, Vite, vite-plugin-pwa, TanStack
  Router and Query (ADR-009); build once, `main` to staging, tag to production (ADR-006).
- **Built (the foundation):** tenant resolution from the host and the transaction hook that
  binds `app.tenant_id`; the `bms_owner` and `bms_app` roles; migration `V1` with the RLS,
  grant and append-only helpers and the foundation tables (chapter 6 section 6.9.1); the
  audit writer; the ledger's `post_entry` with its deferred balance trigger; the per-tenant
  job runner; locked-down actuator with `/healthz`, `/readyz`, `/version`; the development
  authentication stub; and **`lending.members` (create, list, get) as the reference vertical
  slice: copy its shape for every new module.** The PWA skeleton has the staff and member
  route split and a typed client generated from `docs/api/openapi.json`.
- **Built (increment 1, issue #7):** migration `V2` (role and permission catalogue seeded from
  chapter 8, credentials, recovery codes, invitations, sessions, role assignments, settings,
  subscriptions, approval requests, platform tables and functions); staff and platform sign-in
  with argon2id, TOTP, recovery codes and admin MFA reset, revocable sessions (ADR-014);
  invitations whose one-time link is shown to the inviting admin; per-permission branch scope
  (ADR-017); branches, settings, plan limits and the suspended tenant; the maker-checker
  mechanism with actions registered by their modules (ADR-015); the platform console API
  (ADR-016); audit search and CSV export; the notification port with a recording fake adapter;
  and the PWA's sign-in, MFA, invitation, branch switcher and approvals inbox. No production
  approval action is registered until increment 2.
- **Integrated, not yet on `main` (issue #71):** the loan pull requests #47 and #48 (appraisal,
  credit score, approval decision; migration `V9`) and, on top of them, the retail vertical
  (ADR-020): catalogue, stock, sales, purchasing, usage, valuation and daily profit (`V10` to
  `V12`), the oversell refusal and price floor (`V13`), the review fixes (`V14`), the
  `import-retail` command (`V20`, `docs/runbooks/import-retail.md`) and the phone-first retail
  screens on the real API (`docs/specs/retail-ui-notes.md`). Stock transfers between branches
  (issue #84, ADR-020 amendment) add `V22`. The database optimisation (issue #107, ADR-028) adds
  `V26` (indexes and a fillfactor only; `V24`, `V25` and `V27` stay unused, since a number below
  an applied one can never run). The retail catalogue management (issue #146) takes `V28`. Lending increment 5,
  disbursement and repayments (issue #108, ADR-026), takes `V29`, lending savings (issue #151,
  ADR-032) `V30`, the first-run preferences (issue #19, ADR-025) `V31`, and lending investments
  (issue #152, ADR-031) `V32`, and lending insights (issue #153, ADR-030) `V33`. Flyway runs with
  `outOfOrder` off, so a new migration takes a number above the highest one on any open branch
  (`V34` is the retail cash book, issue #147; `V35` is claimed by lending, issue #176; `V36` is claimed by lending payment methods, issue #160; `V37` is retail branch roles, issue #191; `V38` is the retail two-step transfer, issue #193; `V39` is units of measure, issue #194; `V40` is search, issue #241; `V41` is price lists, issue #195; `V42` is the loan reopen pledge guard, issue #176; `V43` is payment methods, issue #160; `V44` is customer accounts, issue #196; `V45` is returns, issue #201; `V46` is optional documents, issue #197; `V47` is optional purchasing, issue #198; `V48` is landed cost and supplier credit notes, issue #199; `V49` is report runs, issue #214; `V50` is the SMS channel core, issue #212; `V51` is the `core.ai` call log, issue #220; `V52` is the tenant SMS settings, issue #218; `V53` is the member event SMS cancel, issue #221; `V54` is the payment gateway core, issue #213; `V55` is the stored daily AI brief and the advisor permissions, issue #226; `V56` is the lenders' own payment accounts and `V57` their permission, issue #233; `V58` is the step-up failure counter; `V59` is the loan repayment by gateway and the unallocated receipts, issue #216; `V60` is the savings deposit by gateway, issue #219; `V62` is the staff forgot-password migration, issue #320; `V65` is the store branch flag, issue #366; `V67` is the two-step default, issue #348; `V69` is the items with sizes and colours, issue #384, the next number after `V68` (the push subscriptions); `V70` is customer orders with delivery, issue #385; `V71` is the item photos, issue #384 slice 2; `V72` is find your sign-in page, issue #383; `V73` is the retail Storekeeper and Purchasing roles, issue #399; `V74` is sellers who see only their own figures, issue #399 slice 2; `V75` is the operator screens, issue #407, directly after it; `V76` is the low-stock minimums and the daily notices, issue #412, directly after `V75`; `V77` is changing an order's items, issue #393, directly after `V76`; `V79` is the Owner of a business, issue #442, directly after `V78`; `V80` is in-app feedback, issue #447, directly after `V79`; `V81` is the lending arrears run and penalty charges, issue #264, then `V82` the penalty waivers, issue #265, `V83` the collection actions, issue #266, and `V84` the arrears notices' record, issue #227, in that merge order; then `V85` shop logos, issue #461, pull request #465, `V86` offline sales, pull request #463, and `V87` barcodes, pull request #470; then `V88` referrals, pull request #467, `V89` existing loans, pull request #475, and `V90` the self-serve plan, pull request #474 (issue #472, ADR-073), and `V91` Download my data as a workbook, pull request #478 (issue #334); `V80` to `V91` are all merged. `V92` is subscription billing automation, pull request #482 (issue #480, ADR-075), merged; `V93` is the shop's EAN-13 barcodes (#528, issue #524, ADR-070 decision 12), merged; `V94` is the average cost (#529, issue #523, ADR-079), merged; `V95` is the savings cap and the optional savings reason (issue #538, pull request #540, ADR-080), merged; `V96` is item codes made from the category (issue #493, ADR-081), the next number after `V95`, merged; `V97` is buying on credit and paying suppliers without the purchasing capability (issue #557, ADR-084), the next number after `V96`; `V98` is one staff phone per business (issues #323 and #324, ADR-085), the next number after `V97`, merged; `V99` stays unused, since it is the placeholder `placeholder-migration-guard` refuses; `V100` is the index for the shop Home takings, `(tenant_id, paid_on)` on `retail_sale_payments` (issue #488, ADR-076), directly after `V98`, merged; `V101` is the Controls page's two partial indexes on sale lines (issue #357, ADR-087), directly after `V100`, merged; `V102` is the billing contact, closing the business and its removal (issue #571, ADR-090), directly after `V101`, merged; `V103` is the kind of business (issue #424, ADR-092: the retail profile renamed without changing a switch, and `business_type` on the operator's overview), directly after `V102`, merged; `V104` is the entry warnings' three product indexes on sale, purchase and transfer lines (issue #353, ADR-093), directly after `V103`, merged; `V105` is the billing review follow-ups (issue #502, ADR-075): `platform_mark_subscription_overdue` refused under a tenant binding and for a business that asked to close, directly after `V104`; `V106` is the sign-in review follow-ups (issues #369 and #377, ADR-046): `platform_sessions.revoked_reason` gains `two_step_required`, so the refresh of an operator with no second factor ends the session instead of failing, directly after `V105`, merged; `V107` is the fee and interest waivers (issue #265, pull request #597, FR-ARR-04 second half: `lending_item_waivers` and the permission `lending.charges.waive_fee_interest`), directly after `V106`; How do I...? (issue #598) needs no migration; `V108` is Ask BMS (issue #20, ADR-096: the tables `help_ask_questions`, `help_ask_tickets`, `help_ask_spend` and `help_ask_answers`, and the functions `help_ask_platform_spend` and `help_erase_personal_data`), directly after `V107`; `V109` is the default units for retail businesses (issue #618, ADR-098), directly after `V108`; `V110` is selling packs with their own prices (issue #624, ADR-099: `purpose` and `sell_minor` on `retail_product_units`), directly after `V109`, merged (PR #627); `V111` is the usual pack size and purpose of a unit (issue #625, PR #628, ADR-100, Set up your shop: `pack_size`, `pack_of_unit_id` and `pack_purpose` on `retail_units`, the purpose taking the values of `retail_product_units.purpose`), directly after `V110`; payments (#315 and #318) take the numbers after `V111`; the merger renumbers at merge time if the order changes). A migration takes the highest number on main plus one at merge time; a number above one that is still unmerged is allowed only as one of the ordered claims listed here; a placeholder `V99__` is refused by `placeholder-migration-guard` and by the first job of `deploy.yml`. The retail analytics pass (issue #149) is reports only on existing data: no migration; its
  queries are in `scripts/db-bench/queries` (k01 to k15).
- **Built (trade 1 of 10, issue #191, ADR-033):** migration `V37` (`retail_branch_roles`, the permission
  `retail.branch_role.manage`, a backfill of role `both`); the tenant settings group `retail.capabilities`
  (eight flags, the `shop` or `trade` profile as a preset, all off by default; the profile is the kind of business since issue #424, ADR-092) through a vertical-neutral
  core hook (`core.tenancy.ModuleSettingsGroup`, supplied by `retail.manifest`); `/me` now lists `modules`
  and `module_capabilities`; branch roles `store`, `shop`, `both` owned by `retail.stock` (`BranchRoles`,
  `RetailCapabilities`), enforced on sales only (422 `branch_role_forbids_sales`; restock and transfer are
  never role limited; a missing row and capability off both read as `both`); the sales screen follows the
  role and the menu follows the tenant's modules (issue #185). Deferred to the next slices: units, price
  lists, customer accounts, documents, purchasing, transfer receipt, returns, and an administrator screen
  for the flags and roles (the routes exist).
- **Built (trade 2 of 10, issue #192, ADR-033):** PWA only, no migration: the Retail settings screen (shop or trade
  profile, the eight feature switches, a review before saving, `If-Match` on the settings version), the branch roles
  screen, `SCREEN_CAPABILITY` so a screen of a capability is offered only while `/me` lists it, and a tour per
  capability that starts once when first enabled (branch roles built; the others arrive with their screens).
- **Built (trade 3 of 10, issue #193, ADR-033):** migration `V38` (the two-step transfer: it replaces the
  `retail_transfers` status check, entry-pair check, void-column checks and update guard, adds the receipt
  columns and `received_qty`, all expand only); with the `transfer_receipt` capability a transfer is
  dispatched (`in_transit`), then received (a shortage is stock shrinkage at the destination valued at the
  source cost) or cancelled; switching the capability off is refused while one is on its way; the reports
  `stock-by-location` (stores then shops, subtotals, stock in transit, combined total) and
  `transfers-in-flight` (built from the open dispatches, reconciled against retail transfer entries in
  account 1190 only, since lending shares it); the staff screens for stock by store and shop, the in-transit
  list and dispatch and receive (`docs/specs/retail-ui-notes.md`). One-step transfers are the default and
  unchanged. Deferred: the shortage in the daily profit report, and the rest of the ten slices.
- **Built (trade 4 of 10, issue #194, ADR-033):** migration `V39` (units of measure): `retail_product_units` (pack sizes of a
  product: a unit and its factor in base units), nullable unit columns on `retail_sale_lines` and `retail_purchase_lines`, the chart
  account 5120 `purchase_rounding`; routes `/retail/products/{id}/units`; `unit_id` on a sale line and a restock line, converted to base
  units exactly in `retail.sales` and `retail.purchasing` (`Quantities` holds the maths); stock, balances, reports and snapshots stay in base
  units; the CSV item import reads `pack unit` and `pack factor`; the sale and restock screens offer the item's packs and the item screen
  edits them while `module_capabilities` lists `units`. No permission added. The pack sizes capability is marked ready with its own tour
  (`capability-units`).
- **Built (trade 5 of 10, issue #195, ADR-033):** migration `V41` (price lists): `retail_price_lists`, `retail_price_list_items`
  (append-only apart from the one-time `valid_to`), `retail_customer_groups`, a group and a list on `retail_customers`, a list on
  `retail_sales`, and how the price was chosen on `retail_sale_lines`; permissions `retail.pricelist.manage` and
  `retail.pricelist.use` (administrators) and `retail.price.override` (every holder of `retail.sale.create`); routes
  `/retail/price-lists`, `/retail/customer-groups`, `/retail/prices/resolve` and `PUT /retail/customers/{id}/pricing`; the sale resolves each
  line on the server (`SalesPricing`: the sale's list, the customer's, the group's, then the quantity break, then the retail price, strict
  lists refuse) and stores the resolved price, so a later list change never touches a sale; a typed price that differs is an override
  with a reason. The Price lists and Customer groups screens, the Credit buyers assignment and the sale screen's price notes show while
  `module_capabilities` lists `price_lists`, which is marked ready with its own tour (`capability-price-lists`).
- **Built (trade 6 of 10, issue #196, ADR-033):** migration `V44` (customer accounts): `credit_limit_minor` and `terms_days` on
  `retail_customers`, `retail_account_payments` (the receipt of a payment on account, reversed once, as a whole) and its append-only
  allocations to ordinary sale payments; permissions `retail.credit.manage` and `retail.credit.override` (administrators); routes
  `/retail/customers/{id}/account`, `/statement`, `/payments` and `/payments/{id}/reverse`, and `/retail/reports/customer-ageing`;
  a credit sale locks the buyer's row, refuses above the limit (or takes a reason from a holder of the override), needs a customer
  record, and takes its due date from the terms; the credit control report carries the limit; the cash book takes a payment on
  account in the receiving branch; the V14 guard of `retail_sales` lets `paid_minor` fall only inside the reversal of one named receipt and only by exactly what it allocated to the sale; a payment on account posts to the seeded retail accounts by system key (`cash_on_hand`, `mobile_money`, `bank`), like every retail posting (ADR-026, #236). The Customer
  accounts page, the Customer ageing page, the account page (summary, take a payment, payments taken, statement), the Credit buyers and Credit control links
  and the sale screen's limit handling show while `module_capabilities` lists `customer_accounts`, which is marked ready with its own
  tour (`capability-customer-accounts`). The statement as a PDF is the documents slice.
- **Built (trade 7 of 10, issue #201, ADR-033):** migration `V45` (customer returns): `retail_sale_returns` (the numbered credit note, `CN` + branch code + six digits per tenant
  and branch) and `retail_sale_return_lines`, `retail_sale_settled_minor`, the chart account 4110 `sales_returns`, the permission `retail.sale.return` (administrators), and a new body
  for the `retail_sales` guard that also lets `paid_minor` fall inside the void of a named return (`bms.void_sale_return`, by exactly the credit it gave) without loosening the V44
  binding to a reversed receipt; routes `POST` and `GET /retail/sales/{id}/returns`, `GET /retail/returns`, `GET /retail/returns/{id}` and `POST /retail/returns/{id}/void`; a return
  of whole or partial lines in any unit (exact conversions), with a reason, restocked into a chosen branch or store or written off, credited at the price charged and costed at the
  sale line's snapshot, repaid in cash, mobile money or bank, or taken off a credit sale's balance (the rest in money); numbered, idempotent, audited, voidable by reversal, and above the
  tenant's `retail_sale_return` approval threshold a maker-checker action; the daily profit, the sales analysis, the margins, the dashboard, the evaluation, the cash book and the
  statement net it on the day of the return. The Customer returns page, the Take goods back form, the credit note view and the Return goods link on an open sale show while
  `module_capabilities` lists `returns`, which is marked ready with its own tour (`capability-returns`). The credit note PDF is the documents slice.
- **Built (trade 8 of 10, issue #197, ADR-033):** migration `V46` (optional documents): `retail_document_prefixes`, `retail_document_series`
  (a counter per tenant, type and branch, taken under a row lock in the transaction that writes the document, with a guard so it only
  rises by one), `retail_quotations` and its lines, `retail_documents` (append-only, with the JSON snapshot of everything the document shows),
  the four new kinds in the `documents.doc_type` check, and the permission `retail.document.issue` (administrators); routes
  `/retail/sales/{id}/documents`, `/retail/transfers/{id}/documents`, `/retail/documents`, `/retail/document-series`, `/retail/quotations`
  (create, cancel, convert to a sale and its invoice) and `/retail/customers/{id}/statement/pdf`; the invoice, delivery note, quotation and the
  customer statement as PDFs through the ADR-034 renderer (the first three stored once from the snapshot, numbered `INV-HQ-000001`, a failed render
  leaves a numbered document that renders on the next request, the statement never stored); documents are an after-step: a sale, a transfer and a
  restock never read or wait for one, and with the capability off no route, menu entry or response field exists. The Documents screen (with the
  Document numbers), the Quotations screen, the Make invoice and Make delivery note buttons on a sale and on a stock move, the statement PDF on the account page
  and the tour show while `module_capabilities` lists `documents`, which is marked ready with its own tour (`capability-documents`). A void document is served only as a version with a VOID banner. A credit note has one number, the return's (V45), and
  `POST /retail/returns/{id}/document` renders it as the credit note PDF under that number.
- **Built (trade 9 of 10, issue #198, ADR-033):** migration `V47` (optional purchasing): eleven tables (`retail_purchase_orders` and lines,
  `retail_goods_receipts` and lines, `retail_supplier_bills`, lines and entries, `retail_supplier_payments` and allocations,
  `retail_purchasing_settings`), the chart accounts 2110 `goods_received_not_invoiced` and 5130 `purchase_price_variance`, and the
  permissions `retail.payable.manage` and `retail.purchase.approve` (administrators); routes `/retail/purchase-orders`,
  `/retail/goods-receipts`, `/retail/supplier-bills`, `/retail/supplier-payments`, `/retail/suppliers/{id}/unbilled-receipts` and
  `.../open-items`, `/retail/reports/supplier-payables` and `/retail/purchasing/settings`. A purchase order is numbered per tenant and
  branch, a draft until it is sent (under a tenant threshold the send is a maker-checker action, `retail_purchase_order`); goods are
  received into a chosen branch or store, against an order (partial, over-receipt tolerance) or blind, through the restock's
  primitives, posting inventory against goods received not invoiced; a supplier invoice is matched by line to received lines (the
  share of the receipt cleared, the price variance posted, a reason above the tolerance, never more quantity than was received); a
  supplier payment is allocated to open invoices and credit restocks (cash is a cash-out of the till in the cash book) and reversed
  as a whole; the payables report ages what is owed on the business day. The plain restock and everything else are unchanged with the
  capability off. The Purchase orders, Goods received, Supplier invoices, Supplier payments and What you owe suppliers pages, and the purchasing tolerances on Retail settings, show while
  `module_capabilities` lists `purchasing`, which is marked ready with its own tour (`capability-purchasing`). Deferred: the supplier
  credit note and landed cost (issue #199), the purchase order PDF.
- **Built (trade 10 of 10, issue #199, ADR-033):** migration `V48` (landed cost and supplier credit notes): eight tables (`retail_landed_costs`,
  `_charges`, `_lines` and `_entries`; `retail_supplier_credits`, `_lines` and `_entries`; `retail_receipt_holds`), the stock movement kind
  `return_to_supplier` (the two movement checks replaced `NOT VALID`, behind a five second `lock_timeout`) and the setting
  `require_costing_before_sale`; no new permission (`retail.payable.manage`), no new chart account; routes `/retail/landed-costs` (create, preview,
  list, get, void), `/retail/supplier-credits` (create, list, get, void) and `/retail/goods-receipts/{id}/release-hold`. A landed cost spreads the
  charges of one payee over received lines by value, quantity, weight or typed amounts in exact integers (largest remainder, deterministic ties),
  posts the share for goods still on hand (first in, first out) to inventory and the share for goods already sold or moved to cost of goods sold,
  moves the product's cost only while the receipt is its latest cost event, and creates the payee's invoice as a supplier bill of kind
  `landed_cost`, paid and aged like any other. A supplier credit note is against a supplier invoice that stands: goods sent back (stock out,
  inventory and purchase price variance) or a price adjustment, never above what the invoice leaves to pay. Both are voidable with the guards of
  a bill. The Landed costs and Supplier credit notes pages, the hold on a receipt and the costing switch show while `module_capabilities` lists
  `purchasing`. Unapplied credits to spend later and a cash refund from a supplier are deferred.
- **Spec (units and packs, issue #512):** `docs/specs/units-packs-and-costing.md` sets the model for packs, buying and selling in packs, profit per piece, the glossary, item codes from the category and shop barcodes, with the owner's decisions (D14, D16, D22 to D26, D32) and the build order. Read it before changing units, packs, costing, item codes or barcodes.
- **Built (profit from the average buying price per piece, issue #523, owner decision D22, ADR-079):** migration `V94`
  (`retail_stock_balances.avg_cost_minor`, backfilled from each product's cost, a row inserted without one starting at the product's cost by trigger; `retail_cost_spreads`, the part of
  a landed cost or price credit line each branch's average took, so its void takes back the same parts). `StockLedger` keeps each branch's moving weighted
  average per base unit under the balance row's lock: a movement at another cost than the average re-averages it (restock, goods
  received, transfer in at the source's average, customer return, sale void, the void of a restock or receipt taking its units out
  at their own cost), a movement at the average leaves it (sale, usage, damage, count, correction, transfer out), the remainder posts
  per branch to purchase rounding (source `retail.average_cost`), a void that would leave a negative average is refused (422
  `average_cost_negative`); `StockLedger.averageCost` and `revalue` (landed cost and supplier price credits). Sale lines snapshot
  the branch average; every valuation reads it; `retail_products.cost_minor` is the last price paid, shown as Last paid. No route,
  permission or response field added. Tests: `RetailAverageCostIT`, `RetailLandedCostIT`, `QuantitiesAverageTest`, `MigrationOrderIT`, `RlsIsolationIT`.
- **Built (items are added inside a category and the item code is made from it, issue #493, owner decisions D14 and D16, ADR-081, FR-RET-70):**
  migration `V96` (the next number after `V95`, merged): `retail_categories.code_prefix` (2 to 4 capital letters, unique per business, suggested from the name by
  `bms_category_prefix_base` and `bms_suggest_category_prefix`, backfilled oldest first, filled by a trigger when an insert leaves it out) and
  `code_next`; `bms_next_item_code(category_id)` locks the category row and returns `PREFIX-0001`, the next number no item holds. `POST /retail/products`
  takes `code` as optional (blank means the category's next code; a typed code was kept until #562, which refuses it); `POST` and `PATCH /retail/categories`
  take `code_prefix` (409 `duplicate_category_prefix`); `GET /retail/categories/prefix-suggestion`; a category carries `code_prefix` and `next_code`; the
  CSV import fills blank codes (or a missing code column) from each row's category on apply. Existing items and codes are untouched; moving an item to
  another category (its own form, audited) keeps its code. Screens: Categories shows each code start and opens the category page
  (`/staff/retail/catalogue/categories/$categoryId`, tour `page-category`): its items, Add an item with the category fixed (no dropdown) and Change the
  code start; the field is Item code everywhere (D16); on the Items page the category comes first. No permission added. Tests: `RetailItemCodesIT`,
  `MigrationOrderIT`, `catalogue-category.dom.test.tsx`, `catalogue-names.dom.test.tsx`, `tours-retail-cash.test.tsx`.
- **Built (the app always makes a new item's code, issue #562, owner decision of 25 Oct 2026, ADR-081 as amended, FR-RET-70):** no
  migration. Every new item gets its category's next code from `bms_next_item_code`: the item form in a category, Add an item on
  Items, the new item on Buy stock and Count stock, and Add an item with sizes and colours (the base code is the category's next
  code, each size `BLB-0007-03M-BLUE`). The CSV import keeps a file's codes (blank ones made) unless Let the app make the
  codes is ticked (`make_codes=true`, owner decision D45, unticked by default), when every row's code is made. A typed code is refused with 400 `item_code_made_by_app` (field problem `not_allowed`): `code` on
  `POST /retail/products`, `base_code` on the family create and joins, a size line's `code`, and on `PATCH /retail/products/{id}` a
  `code` other than the item's own. Existing codes never change. Screens show the code as text ("Item code: BLB-0007, made by
  the app"; on edit "It never changes."), never an input; the new category form shows the code start the app makes and asks
  for none (it changes on the category's page). No permission added. Tests: `RetailItemCodesIT`, `RetailVariantsIT`,
  `VariantGridTest`, `catalogue-category.dom.test.tsx`, `new-item-here.dom.test.tsx`,
  `catalogue-variants.dom.test.tsx`, `catalogue-import.dom.test.tsx`, `design-guard.test.ts`.
- **Built (one category picker, issue #578, FR-UXN-10):** PWA only, no migration, no API change. `CategoryPicker`
  (`frontend/src/areas/staff/retail/category-picker.tsx`) replaces the category select of Add an item, the item form, Buy stock's
  and Count stock's new item, the sizes and colours forms and the Items and Stock filters: a full height Sheet on a phone and a
  `@radix-ui/react-popover` list on a wider screen, with a search over any part of the name, Add a new category for
  `retail.catalogue.manage` (name only; the code start is the app's, from `prefix-suggestion`), the new one chosen at once with a
  dark toast, and `No category called "X". Add it?`. Tests `category-picker.dom.test.tsx`; other tests choose a category
  with `chooseCategory` (`frontend/src/test-utils/category-picker.ts`).
- **Built (Refresh is a visible button in the top bar again, issue #631, FR-UXN-11):** PWA only, no migration, no API change.
  `RefreshButton` sits in `StaffBar` just before Search on every staff screen, named Refresh, its icon alone below 720px, and
  is gone from the person menu (`TopMenu`). Below 430px the bar's controls touch, each exactly 48px, so the branch name stays
  readable (`layout/header.layout.test.ts`). The three welcome tours gain a Refresh step on the `refresh` anchor. Tests
  `top-bar.dom.test.tsx`, `refresh-button.dom.test.tsx`, `model.test.ts`.
- **Built (a buying pack's size per delivery, issue #554, owner decision D23, ADR-082, FR-RET-72):** no migration. A restock line and a
  goods received line in a pack take `pack_size` (how many base units each pack held in this delivery, at least 1), kept in the line's existing
  `unit_factor`; stock, the cost per base unit and the branch's average (ADR-079) use it, and every later reading of the line (voids, supplier
  invoices, goods sent back) already converts with it. `use_from_now_on` also makes it the pack's usual size, audited, under the product row lock
  before any balance row, and needs `retail.catalogue.manage`. Goods received against an order line in a pack count packs whatever each held
  (`received_unit_qty` and `base_unit` on the order line). Selling and scanning keep the usual size. Screens: How many pieces in each carton? on Buy
  stock and Goods received, required, with Use 30 from now on. Tests: `RetailPackSizePerDeliveryIT`, `pack-size-per-delivery.dom.test.tsx`,
  `scan.dom.test.tsx`.
- **Built (selling packs with their own prices, issue #624, owner decision D23, ADR-099, FR-RET-78):** migration `V110` (directly after
  `V109`): `retail_product_units.purpose` (`buy`, `sell` or `both`, `both` for every existing pack) and `sell_minor` (the selling price of one
  pack, null for the piece price times the size; none on a pack only for buying). A walk-in sale, the Sell picker, a scanned pack barcode, a
  quotation, a customer order and `/retail/prices/resolve` take the pack's own price when no price list prices the line
  (`SalesService.walkInPrice`); price lists still win. The sale's below-cost check is unchanged (the line against its pieces times the
  branch's average, ADR-079); the pack responses add `suggested_sell_minor` and, for `retail.profit.read`, `below_cost` against the item's
  average. A pack only for buying is refused on a sale (`unknown_pack_unit`, a clash offline) and a pack only for selling on Buy stock, a
  purchase order and goods received. Setting a pack's price, or changing the size of a pack that has its own price or making it buy only,
  needs `retail.price.edit`; `V110` also adds the index `retail_stock_balances_product_held` for the item's average. Edit item shows the packs in one table (pack, size,
  selling price, the piece first) with Change opening a sheet (size, Used for, Selling price of one pack with the suggestion). Help: How do I
  sell in a box and in pieces at different prices?. Tests: `RetailSellingPacksIT`, `selling-packs.test.tsx`, `corpus.test.ts`.
- **Built (buy on credit, what I owe suppliers, pay a supplier, issue #557, owner decision D15, ADR-084, FR-RET-73):** migration `V97` (the next
  number after `V96`): the `retail_purchases.payment_method` check takes `mobile_money` (posted to the `mobile_money` account, never the till).
  No second payable system: the V47 supplier payments, open items and payables report are reused and no longer need the `purchasing`
  capability (`retail.payable.manage` still; purchase orders, goods received, invoices, landed costs, credit notes and the purchasing settings
  stay behind it). A payment takes `paid_on` (today when absent; never in the future, 422 `future_date`), posts and leaves the till on that
  day, and pays only what was owed on it (422 `after_payment_date`, `payment_exceeds_open`). `GET /retail/suppliers/{id}/statement` (opening,
  each credit restock, invoice, credit note, payment, void and reversal with the running balance, closing; scoped like the payables report).
  Screens: Paid how on Buy stock (On credit needs a supplier), What I owe suppliers under the Money tab (total, Pay and Statement per supplier),
  Date paid on Pay a supplier, the supplier statement (`/staff/retail/suppliers/$supplierId/statement`, shared as text; tour
  `page-supplier-statement`; the four payment and payables tours are version 2 for owners without the capability). Tests: `RetailBuyOnCreditIT`,
  `RetailTradePurchasingIT`, `buy-on-credit.dom.test.tsx`, `capabilities.test.tsx`, `tours-retail-purchasing.test.tsx`.
- **Built (go-live hardening: one staff phone per business, no recorded links in production, issues #323 and #324, ADR-085,
  FR-IAM-01):** migration `V98` (the next number after `V97`): the partial unique index `users_staff_phone` on `users (tenant_id,
  phone_e164)` for invited and active staff, so two invitations at once cannot make two staff users with one phone; the refusal
  (and the same race on `users_staff_email`) is 409 `duplicate_phone` or `duplicate_email`, as the application check answers.
  V98 first counts existing shared phones and, if any, stops with the business ids and the count (never the phone), changing
  nothing and never deleting a user; the manual fix is `docs/runbooks/staff-duplicate-phones.md`. `RecordingNotifier`, which
  keeps sent messages and their one-time links in memory, is now a bean of the dev and test profiles only; every other profile
  (`server` on staging and production) gets `LogOnlyNotifier`, which keeps nothing and logs only the template and the masked
  recipient. The sign-in flow is unchanged. Tests: `StaffPhoneUniqueIT`, `NotifierProfileIT`.
- **Self-onboarding, build step 1 (issue #89, ADR-024):** migration `V23` with
  `onboarding_applications` and `notification_outbox`, reached only through definer functions;
  the public sign-up and applicant page; the operator portal on the platform host (operator
  sign-in, applications queue, Activate through `TenantProvisioning`, messages not sent); the
  outbox sender job with SMTP and Telegram senders, off until their `BMS_SMTP_*`, `BMS_MAIL_FROM`
  and `BMS_TELEGRAM_*` variables are set.
- **Built (increment 5, issue #108, ADR-026):** migration `V29` (schedule items, loan transactions,
  repayment allocations); disbursement with the `loan_disbursement` approval action and fee
  handling; schedules from the disbursement date; repayments allocated by R-ALLOC with overpayment
  credit; payoff quote; reversal with re-allocation (`repayment_reversal`); automatic closure;
  write-off (`loan_write_off`) and recovery; every money event posting through `post_entry` with
  receipt and voucher numbers; the staff loan screens (`docs/specs/lending-ui-notes.md`); and the
  `seed-lending` command for a fabricated staging loan book (`docs/runbooks/seed-lending.md`).
  Deferred: waivers, collections and the reminder SMS (increment 6, pull requests #271, #272 and #290; the arrears job
  and penalties are #270, migration `V81`), import (7), reports and the
  receipt, voucher and statement PDFs (8 and a documents follow-up).
- **Built (fee and interest waivers, issue #265, the second half of FR-ARR-04; the owner's decision D50 of 26 Oct 2026, https://github.com/RincolTech-Solutions-ltd/bms-platform/issues/265#issuecomment-6410874637):** migration `V107` (directly after `V106`, #596; it adds the unique key `(tenant_id, loan_id, id)` on `lending_schedule_items` for the waiver's composite foreign key):
  `lending_item_waivers` (tenant-owned, forced RLS, append-only: one dated row per waiver of all or part of a schedule item's
  `fee` or `interest`, with the reason, the approval request, the maker and the checker as composite foreign keys) and the
  permission `lending.charges.waive_fee_interest` (the accountant and the tenant admin). `POST
  /lending/loans/{loan_id}/schedule/{item_no}/waive` `{component, amount_minor, reason}` (`lending.charges.waive_request`,
  `Idempotency-Key`; `ItemWaiverService`) is the existing `charge_waiver` approval: its checker also needs the accountant's
  permission (`WaiverAction.extraCheckerPermission`, so a branch manager is refused with 403 `permission_denied`), the
  threshold weighs everything waived on the loan (penalties, fees and interest) with the request, and a maker without the
  accountant's permission always waits (the rule is read once and passed to `Approvals.request(request, rule)`, ADR-091). A
  refusal on approval (the amount is no longer due) makes the request stale with its reason (ADR-091 decision 11). Principal
  is never waived (422 `principal_not_waivable`); a waiver is at most what is still due on its component (422
  `waiver_exceeds_due`, `nothing_to_waive`). The item's `fees_waived_minor` or `interest_waived_minor` carries it, nothing is
  posted (fees and interest are income only when paid), the statement and the receipts credit it on its date ("Fees
  waived", "Interest waived"), and a reversal's replay keeps it (`Servicing.Item.interestWaivers`, apart from the payoff
  rebate it recomputes; ADR-026 Watch for). The schedule rows carry `interest_outstanding_minor` and
  `fees_outstanding_minor`. Screen: Waive fee or interest on a schedule row of an active loan for whoever may ask
  (`waive-item.tsx`, `waive-state.ts`): fees or interest, the amount, why, then a review of the borrower, loan, item,
  amount, who asks and why before it is sent. Approvals names `charge_waiver` "Waive a penalty, fee or interest" and links
  the loan. Tests: `LoanServicingIT.frArr04_aFeeOrInterestWaiverIsMakerCheckerAndPostsNothing`,
  `frArr04_onlyTheAccountantsPermissionApprovesAFeeOrInterestWaiver`, `frArr04_theFeeAndInterestThresholdWeighsTheCumulativeTotal`,
  `frArr04_aWaiverNeverTakesPrincipalOrMoreThanIsDue`, `frArr04_aReversalKeepsTheFeeAndInterestWaivers`, `RlsIsolationIT`,
  `PermissionMatrixIT`, `waive-state.test.ts`, `waive-item.dom.test.tsx`.
- **First run and guided tours (issues #19 and #86, ADR-025):** accepting an invitation signs the
  user in; the PWA walks a new user through password, an optional second factor and recovery codes;
  role aware spotlight tours (`frontend/src/tour/`) start once and replay from Help, with per-user
  progress in `users.preferences` (`V31`). A new screen adds `data-tour` anchors and its steps. **Every staff route has a page tour, breadcrumbs and the cross links of issue #336** (a buyer name opens the buyer, an item its stock, a sale its documents and returns, a branch its stock, a total the rows behind it); a pull request that adds or renames a route changes `frontend/src/tour/` too (bump the version of a tour you change), or its body has a line `TOUR-EXEMPT: <reason>`, and `tour-guard` (`.github/scripts/tour_guard.py`, ADR-049) fails it otherwise.
- **Built (staff Users screen, issue #319):** PWA only, no migration, no API change: `/staff/users` (list with search, roles with branch,
  status invited, active or deactivated) and `/staff/users/new` (phone-first invite, role pick-list with plain descriptions, branch
  pick-list unless the role is the administrator). The one-time invitation link is shown once with Copy and Send on WhatsApp; resend,
  change roles and deactivate follow the existing `/users` routes. Visible to `core.users.read`, actions to `core.users.manage`; the
  `staffAdmin` tour flag is on (`docs/sdd/07-api-design.md` section 7.11.4, UI notes).
- **Built (forgot password, issue #320, FR-IAM-15, ADR-042):** migration `V62`: `user_password_resets` (RLS, the token as SHA-256 only, `self`, `admin` or `operator`, one hour, single use, a newer link revokes the older) and `password_reset` as a session revoke reason. `POST /auth/staff/password/forgot` always answers the same 202 after a 400 ms floor (20 an hour per address, 3 links an hour per account) and sends the link by email through the outbox (`identity.password_reset`); `POST /auth/staff/password/reset` sets the password under the invitation's rules, ends every session of the user, emails a notice, and signs in under the sign-in rules (a user with TOTP still needs the code; the reset does not clear the lock); `POST /users/{user_id}/reset-sign-in` (`core.users.manage`, a fresh authenticator code in the body, or the admin's own password when they have none enrolled) returns the link once to a tenant admin for an active user who is not a tenant admin and not themselves, and `POST /platform/tenants/{tenant_id}/users/{user_id}/reset-sign-in` does the same for a tenant admin, through the platform operator, with the operator's own fresh authenticator code (as the platform's `mfa/reset` now also needs); the tenant admin's route has the Users screen's Reset sign-in button (issue #339), the platform operator's route has the Business page's Make a reset link (issue #407); the user is emailed when a link is issued; no link by email for a user whose role or tenant actually requires a second factor not yet set up (two-step sign-in is optional; policy ticket #348). The sign-in page's Forgot password link, `/forgot-password` and `/reset-password`, and `resetUserSignIn(userId, credential: {totpCode?, password?})` in `frontend/src/api/sign-in-reset.ts`, which the Users screen's Reset sign-in button calls (issue #339: for an active person who is not an administrator and not oneself, with the code from the administrator's own authenticator app or their own password, the link shown once in the link box; `page-users` tour version 2).
- **Built (each shop can have its own logo, issue #461, FR-BR-09, ADR-068):** migration `V85` (`branches.logo_document_id`, a
  `core.tenant` document with the branch id; foreign key on tenant and document). `PUT` and `DELETE /api/v1/branches/{branch_id}/logo`
  (`core.branches.manage`, the rules and processing of the business logo through `BrandingService.LOGO`, audited
  `core.branch.logo_changed`) and the public `GET /api/v1/branches/{branch_id}/logo` (the headers of `/branding/logo`); `logo_url` on
  the branch and on each branch of `/me`. `Letterheads.forBranch` and `forBranchCode` put the shop's logo on that shop's retail
  documents and loan documents, else the business logo (also when the shop logo's file cannot be read). The Branches set-up has an
  upload and remove control per shop; the shop's logo shows beside the branch picker in the staff bar and on the sale screen header, and
  the shop in use is shown with its logo (else the business logo, else no image; an empty space of its size while it loads) beside its name at the top of the side menu and the
  phone drawer (`CurrentShop` in `frontend/src/areas/staff/branch-picker.tsx`). The app icon, the tab icon and the manifest keep the
  business logo (ADR-066). Tests: `BranchLogoIT`, `LoanServicingIT`, `RlsIsolationIT`, `frontend/src/areas/staff/branches/branches.dom.test.tsx`,
  `branch-picker.dom.test.tsx`, `side-menu-shop.dom.test.tsx`, `frontend/src/app/branding.test.ts`.
- **Built (the installed app uses the business's logo, issue #446, FR-TEN-08, ADR-066):** no migration, no permission. Public
  `GET /api/v1/branding/app/manifest.webmanifest` (the business of the host: `id` and `start_url` `/staff`, its display name, a 12
  character `short_name` cut by grapheme clusters at a word boundary when one is 6 or more in, its theme colour) and `GET /api/v1/branding/app/{icon}` (`icon-192.png`, `icon-512.png`, the two maskable ones
  and the 180 px `apple-touch-icon.png`, drawn from the logo on the theme colour with Java2D in `core.tenancy` `AppIcons`, cached by logo,
  colour and size per business, rendered once under concurrent misses, a render failure logged once and served as the BMS icon); the BMS
  icons under `/icons/` where there is no logo or it cannot be drawn, and the BMS manifest on a host that is no business (the manifest
  and the five icon paths are the exact paths `TenantResolutionFilter` lets through without a tenant). `index.html` links both; vite-plugin-pwa runs with
  `manifest: false`; the shell sets `apple-mobile-web-app-title` to the same short name. An iPhone keeps the icon it took at Add to Home
  Screen. Tests: `AppManifestIT`, `AppManifestShortNameTest`, `app-manifest.test.ts`, `branding.test.ts`, `app-title.dom.test.tsx`.
- **Built (referral links and commission tracking, issue #464, FR-REF-01 to FR-REF-08, ADR-069):** migration `V88` (directly after
  shop logos `V85`, offline sales `V86` and barcodes `V87`, all merged): `referral_codes` (a code per business from a trigger on `tenants`, backfilled; letters of
  the slug and two characters without 0, 1, I or O), `tenant_referrals` and `referral_commission_lines` (forced RLS on the
  **referrer**, SELECT only for `bms_app`, never deleted, a line paid once), the closed platform tables `referral_commission_rules`
  (append-only) and `referral_settings` (the default rule; `V88` seeds the product owner's default, 10% of each payment for the first 12 paid months, no cap, ADR-069 decision 14, only when none is set), two columns
  on `onboarding_applications`, the definer functions, and the permission `core.referrals.read` (tenant admin, so the Owner). New
  module `core.referrals` with the port `Referrals`. The sign-up takes `referral_code` (422 `referral_code_unknown`), Activate makes
  the referral; `POST /platform/tenants/{id}/payments` adds the commission line from the rule in force (a percent for the first N
  paid months with an optional cap, a fixed first payment amount, or a fixed amount per paid month for N months; the override of a
  referral, else the default) with a snapshot of the rule. Operator routes: `GET /platform/referrals` (filters referrer, status,
  commission due or paid), `/export` (CSV), `/{id}`, `PUT /platform/tenants/{id}/referrer` (422 `referral_self`), the default rule,
  a referral's rule, manual lines, reversals and paid marks, each with a reason and audited; the app never pays. Tenant route
  `GET /referrals`. The PWA: Referrals under Business set-up (`/staff/setup/referrals`: the link, Copy, Share on WhatsApp, the list
  with status and money; tour `page-referrals`), and Referred by on the sign-up, filled from `?ref=`. The operator portal has no
  referrals screen yet (the API is complete). After the review (ADR-069 decisions 9 to 13): the months used count the payments
  since the business's first referral, one at a time under the subscription lock, leaving out `currency_differs` and refunded ones
  (kept in the closed `referral_payment_outcomes`); a percent is rounded half up; a sign-up whose applicant is an Owner or administrator of the
  referrer drops the code quietly with the usual 202 (checked again at Activate, audited `platform.referral.refused_self`); a typed line needs an
  Idempotency-Key; the referrer and rule PUTs need the operator's `totp_code`; a reversed unpaid line is not paid (409
  `commission_line_reversed`); statuses `suspended` and `cancelled`; the
  referrer's status worked out at every read (`referrer_status`, `referrer_inactive` on every line) and a paid mark while it is
  suspended or cancelled is 409 `referrer_inactive` unless confirmed; a returning applicant's code
  goes on the open, still submitted application without a referrer. Tests: `ReferralsIT`, `ReferralServiceTest`,
  `RlsIsolationIT`, `MigrationOrderIT`, `PermissionMatrixIT`, `referrals.dom.test.tsx`, `referral-code.dom.test.tsx`.
- **Built (retail sales offline, issue #458, FR-RET-67, NFR-OFF-07, NFR-OFF-08, ADR-067):** migration `V86` (main has `V80` to `V84`
  merged; agreed order: `V85` shop logos #465, `V86` this, `V87` barcodes #470, `V88` referrals #467, `V89` existing loans
  #475, `V90` modules #474, `V91` the data download #478, payments #315 and #318 after `V91`): `client_sale_id` (unique per tenant),
  `occurred_at`, `recorded_offline` and `request_hash` on `retail_sales`, kept as written by a trigger; `retail_sale_clashes`;
  `retail_offline_failures`; `retail_offline_waiting` (sales other people left queued on a shared phone, in short);
  the permission `retail.offline.resolve` (administrators and the Supervisor). `POST /retail/sales` takes them optionally: a client sale id
  already recorded by the caller with the same request is answered as a replay after the key expired too (another request 422
  `client_sale_conflict`; the id of a discarded failure 422 `offline_sale_discarded`); the sale is dated the day it happened, five minutes
  of clock skew allowed; a sale recorded offline records short stock (the balance may go negative), an item or a pack switched off, a saved
  price that changed (kept as charged, an override naming the offline sale), a typed price the online rules would not take
  (`price_typed`), a price not above cost and a phone clock ahead or behind as clashes instead of refusing, and pushes
  `retail.sale_clash` to the branch's holders of `retail.offline.resolve`; never a credit sale. Routes `/retail/sale-clashes` (list,
  resolve with a note), `/retail/offline-failures` (report up to 64 KB, mine, list, retry, discard with an audited reason; never by
  the person who made the sale) and `/retail/offline-waiting` (a phone's summary, the managers' list). The PWA
  (`frontend/src/offline/`): the catalogue copy and the outbox per business host and person in IndexedDB, the sale screen offline with a
  provisional receipt, a sync engine (one at a time, oldest first, backoff, stops on an expired session, reports refusals), the dot
  with the count in the staff bar and its sheet, sign-out that asks while sales wait, and the app opened offline with the last profile.
  Lending is unchanged and never cached. Tests: `RetailOfflineSalesIT`, `PushPublishersIT`, `outbox.test.ts`, `sync-engine.test.ts`,
  `catalogue.test.ts`, `offline-sale.dom.test.tsx`, `sync-status.dom.test.tsx`. Notes: `docs/specs/retail-ui-notes.md` (Sell offline).
- **Built (light and dark theme selector, issue #429):** PWA only, no migration, no API change, nothing stored on the server. A System, Light, Dark selector at the top right of the shell's header (every area), kept per device in `localStorage` (`bms.theme`), following the system setting by default, set before first paint by the blocking `frontend/public/theme-init.js` (the CSP is `script-src 'self'`), applied afterwards by `frontend/src/app/theme-mode.ts`; one `:root[data-theme='dark']` token set in `theme.css`, stylesheets draw with colour roles only (`design-guard.test.ts`), the tenant brand pair is untouched and its link and focus colours are derived again for dark, the `theme-color` meta follows the choice (the manifest stays light); the three welcome tours gain a "Light or dark" step (`admin-welcome` v16, `retail-sales` v9, `staff-welcome` v4). Token pairs in `docs/ui/design-system.md`; tests `theme-mode.dom.test.tsx`, `tokens.test.ts`.
- **Built (theme button and Appearance, issue #452):** PWA only, no migration, no API change. The header `<select>` of #429 becomes an icon button (sun, moon, or a phone for the device choice, `aria-label` "Theme: <choice>") opening an accessible three item menu (`ThemeMenu` in `frontend/src/app/shell.tsx`); the Notifications and app page gains an Appearance radio group on the same `useThemeChoice`. The label of `system` is "Same as my phone" everywhere; `theme-mode.ts` is unchanged. Tour `page-notifications` v3. Tests `theme-mode.dom.test.tsx`, `notifications.dom.test.tsx`, `shell.test.tsx`.
- **Built (busy buttons, issue #456):** PWA only, no API change. `BusyButton` (`frontend/src/app/ui/BusyButton.tsx`, `.busy-button` and `.busy-spinner` in `ui/buttons.css`) is the one button for every write (POST, PUT, PATCH, DELETE): while its request runs it keeps its label (no `isPending ?` label swap), shows a small spinner in its end padding without changing width, is `aria-busy` and `aria-disabled` (keeps focus, its press guard drops presses), says Working once through the `BusyAnnouncer` in the app shell (no live region per button; the opened phone menu drawer, being `aria-modal`, mounts a second one inside itself), sends once on a double click and drops an Enter re-submit; under reduced motion the spinner is a pulsing dot. Every write button in the staff, member, onboarding, sign-in and operator screens uses it. **Rule for a new screen:** a button that writes is a `BusyButton` with the mutation's `isPending` (or the screen's submitting state) as `busy`; read-only buttons stay plain; a mutation that needs no busy button of its own carries `// busy-exempt: <reason>` in the `//` comment lines directly above its `const ... = useMutation(` line (no reason, no exemption), and a plain button that only waits (Cancel) carries `/* busy-exempt: <reason> */` in its tag. `src/app/ui/busy-guard.test.ts` fails a mutation (`useMutation<T>(`, destructured, or from a local hook that returns one) that an onClick, onSubmit or component callback sends while its pending flag reaches no `busy={...}` in that file and neither that comment nor an `ALLOWED` entry with a reason exempts it, and a plain `<button>` disabled by a pending state without the tag comment; fixture tests prove the failures and the exemption. It is a text heuristic: it checks that the flag reaches some `busy=` in the same file, not which button. Docs: `docs/ui/design-system.md` (Busy button, rule 6). Tests: `BusyButton.dom.test.tsx`, `busy-guard.test.ts` and the busy cases in the screens' dom tests.
- **Built (slow movers on Stock health, issue #414, FR-RET-64):** reports only, no migration, no permission: `slow_movers` in
  `GET /retail/reports/stock-health` (items that sold in the branch in the last 90 days but would take more than the business's
  `slow_mover_months`, default 4, to sell at that pace, or sold under `slow_mover_min_sold`, default 0 meaning off; apart from dead
  stock; an item newer in the branch is paced over its own days, `days_counted`), with the last sold day, `months_of_stock`, the
  money tied up at cost only with `retail.profit.read` in the branch, the total only with it everywhere, and `better_at`: another
  active, goods-stocking branch in the caller's scope that sells it faster, with a whole quantity to move. No field name holds
  `sell`, `cost` or `price`, so the Storekeeper guard of `RetailRolesIT` holds. The two
  rules live in the `retail` settings group (`/retail/settings/capabilities`). The PWA: the Slow movers section, a Move link that
  opens Move stock filled in (`from`, `to`, `product`, `code`, `qty` in the address), the Slow movers card on Retail settings;
  tours `page-stock-health` v2 and `page-retail-settings` v4 (`docs/specs/retail-ui-notes.md`).
- **Built (share a sale's receipt from the seller's phone, issue #439, FR-RET-66, ADR-061):** no migration and no permission. Two read routes, `GET /retail/sales/{sale_id}/receipt/pdf` and its facts `GET /retail/sales/{sale_id}/receipt` (`retail.sale.read`; without `retail.sale.all_sellers` only the caller's own sales, even of a credit sale they may read; 422 `sale_voided`, `sale_written_off`, `sale_fully_returned`), render the invoice layout titled RECEIPT (RECEIPT AFTER RETURNS with the credit notes and the net) under the sale's number, signed by the seller, with the balance the sale carries, never stored, laid out in its own render place apart from documents and reports (`Documents.renderSharedPdf`), with the documents capability on or off (`DocumentsService.receiptPdf`). The card Send the receipt (`receipt-share.tsx`, `receipt-share-state.ts`) on the saved sale and the opened sale: Share receipt hands the PDF to the phone's share sheet (download where a browser cannot share files; a cancel shows nothing), WhatsApp customer opens wa.me with the receipt's facts and the number normalised by the rules of ADR-061 (an ambiguous number is refused), and an unticked Save this number for the buyer uses the existing customer edit. Nothing is sent from a server and nothing new is audited. Tests: `SaleReceiptPdfIT`, `receipt-share-state.test.ts`, `receipt-share.dom.test.tsx`. Notes: `docs/specs/retail-ui-notes.md` (Share the receipt).
- **Built (the Retail home range, issue #428, FR-RET-65, ADR-060):** reports only, no migration, no permission:
  `GET /retail/reports/dashboard` takes `period` (`today`, `7d`, `30d`, `ytd`, `1y`, `all`) or `period=custom` with `from` and
  `to` (at most 366 days) and then answers `range`: the figures, average sale and number of sales against the period before (as many
  days before; This year against the same days of last year; All, from the first sale in scope, against nothing), the chart in steps
  of a day, seven days or a month with the period before on the same step, the busiest day, the best shop, the best seller and, with
  `retail.profit.read` in every branch covered, the profit and the top salesperson; each shop carries its range sales. Built on the
  dashboard's own dated query and the sales analysis grouping (`SalesAnalysisRepository.one`), read once over the union of the last
  30 days and the range with its period before (`DashboardRange`); without a range the body is unchanged. The PWA: the range row
  (`range-ui.tsx`, data-tour `home-range`), the chart (inline SVG, a step read out on hover, tap or the arrow keys), the detail
  cards, the shops and the cash book cards of the range from the daily cash summary (up to 31 days, for a person who closes the
  till), the choice remembered per person on the device (`range-state.ts`), and the seller's Your sales card on the same range from
  the sales summary (by day, month or year). Tour `page-retail-home` v3. Tests: `RetailDashboardRangeIT`, `RetailSellerFiguresIT`,
  `RetailOwnerDecisionsIT`, `range-state.test.ts`, `own-sales.test.ts`, `home-range.dom.test.tsx`, `analytics.test.tsx`.
- **Built (find your sign-in page, issue #383, FR-IAM-17):** migration `V72` (`V68` is on main from #380; `V69` #388, `V70` #389 and `V71` #392 are claimed) adds the definer function `app_staff_sign_in_pages(email)` (the active tenants where the address is an active staff user: public slug and name only) and the index `users_staff_email_any_tenant` on `lower(email)` that serves it. `POST /api/v1/platform/find-sign-in` (public, platform host) always answers the same 202 after a 400 ms floor (10 an hour per client address; 3 emails an hour per mailbox, counted from the outbox) and emails the sign-in page of each matching business to that address only (`identity.find_sign_in`); every call is audited in `platform_audit_log` with the address masked, and a failure inside (database or outbox) is logged and audited and still answers the same 202. The platform home has the entry "Staff of a business? Find your sign-in page" (`/find-sign-in`, with its breadcrumbs and the public page tour `page-find-sign-in` in `frontend/src/tour/tours-public.ts`), and Operator sign-in a line that points staff there. Looking a business up by name is not offered: a name gives no address to send to without telling the asker which businesses exist.
- **Built (operator screens, issue #407, FR-TEN-10, FR-TEN-11, FR-SUB-01, FR-SUB-02, FR-SPM-01, ADR-056 amending ADR-024 decision 3):** migration `V75` (renamed at merge if `V73` and `V74` land after it): the price book by plan (`plans` gains `description` and `sort_order`; the append-only `plan_prices`: a monthly price in minor units, `annual_months` and the day it starts; nothing seeded, every plan and price typed by the operator), `subscription_payments` (tenant-owned, forced RLS, append-only; a payment received, entered by the operator and confirmed at once), the trial clock (a trigger on `auth_sessions` sets `subscriptions.trial_ends_on` once, one month from the first staff session, in the tenant's time zone, audited as `platform.tenant.trial_started`; existing trials backfilled) and the definer functions `platform_save_plan`, `platform_add_plan_price`, `platform_record_subscription_payment` (the period starts the day after what is covered, a free month or a late subscription becomes `active`, a suspended one stays read only), `platform_set_tenant_plan` (refused when the tenant would not fit), `platform_set_subscription_dates`, `platform_tenant_overview`, `platform_tenant_admins` and `platform_tenant_outbox`. Routes: `GET /platform/tenants` with `q`, `status` and `module` and the owner, last sign-in, dates and address on every tenant; `GET /platform/tenants/{id}/overview`; `PUT .../plan`, `PUT .../subscription-dates`, `POST .../payments` (`Idempotency-Key`); `GET`, `PUT /platform/plans/{code}` and `POST /platform/plans/{code}/prices` (`PriceBookController`); suspend, resume and the status move now take a reason, and every one of these changes nothing when repeated. `core.platform` now depends on `core.operations` (the idempotency protocol). The portal: Businesses (`/platform/businesses`: search, status and module filters, a card per business), the Business page (`/platform/businesses/$tenantId`: details and owner, subscription, modules, a payment, the plan, the dates, suspend and resume, the owner sign-in rescue through the existing reset routes, the emails sent and the operator history, every change confirmed in a plain-words sentence first) and Plans and prices (`/platform/plans`), each with a page tour (`frontend/src/tour/tours-operator.ts`, started only from the page's own button, nothing saved) and breadcrumbs. After the review of #413: a resume brings back the status from before the suspension (`subscriptions.suspended_from`); a payment before anyone signed in starts the free month at the payment and pays after it; a payment and a monthly price are capped at 50,000,000 minor units and an amount more than 25% from the plan's price needs `confirm_unusual_amount`; plan saves, prices, modules and the pilot mark also take a reason; `PUT .../pilot` marks a pilot (complimentary) business with no free month end (`subscriptions.is_pilot`, ADR-024 decision 6), and V75 marks the three businesses agreed as pilots by slug where they exist; dates set by hand are bounded and can be cleared; addresses in an outbox error are masked; commission lines are deferred (ADR-056 decision 8). Tests: `OperatorScreensIT`, `PlatformIT`, `RlsIsolationIT`, `MigrationOrderIT`, `operator-state.test.ts`, `operator.dom.test.tsx`, `tours-operator.test.tsx`. Not built: the tenant billing screen and its "I have paid", the reminders and the guarded scheduled transitions (ADR-024 decision 6), deal prices and agents.
- **Built (subscription billing automation, issue #480, FR-SUB-08 to FR-SUB-12, ADR-075):** migration `V92` (the next number after `V91`, merged): `platform_billing_settings` (one row: `grace_days`, 7 at first, and the operator's `how_to_pay`; `platform_set_billing_settings`), `subscription_reminders` and `subscription_receipts` (tenant-owned, forced RLS, append-only; receipt numbers `RT-<year>-<number>` from the sequence `subscription_receipt_numbers`, the payments recorded before `V92` numbered in the migration), `platform_mark_subscription_overdue` and the permission `core.billing.read` (administrators). The job `core.platform.subscription-billing` (every quarter hour, `bms.platform.billing-job-enabled`, off in `IntegrationTest`) moves a business in its free month or paid, not a pilot, to `past_due` at the first tick after its own midnight following its last day (audited `platform.tenant.subscription_overdue` with no operator), and sends the Owner the reminders 7 days before, the day before and on the day (the free month has its own three) from 08:00 there, once each, email and, only with `BMS_PLATFORM_BILLING_SMS_ENABLED`, SMS. **Nothing is suspended automatically:** `GET /platform/billing/due-for-suspension` lists the businesses past the grace period and the operator suspends by hand. `GET`, `PUT /platform/billing/settings`. Recording a payment numbers its receipt and emails it to the Owner as a PDF in the house letterhead (`templates/documents/subscription_receipt.html`; issuer details from `BMS_PLATFORM_BILLING_ISSUER_*`), attached through the port `EmailAttachments` of `core.notifications`, the same file as #478 adds. `GET /api/v1/billing`, `/billing/payments/{id}/receipt` and the banner `/billing/notice` (any staff); the screens Business set-up > Plan and billing (`/staff/setup/billing`, tour `page-billing`), the overdue banner in the staff shell, and the operator's Due for suspension (`/platform/billing`, tour `page-operator-billing`). `core.platform` now depends on `core.notifications`, `core.documents` and `core.jobs`. With #474 (self-serve plan, merged first, ADR-073): a cancelled business gets no reminder, and one whose Owner scheduled a cancellation (`subscription_scheduled_changes`, kind `cancel`, status `scheduled`) is skipped by the run (`platform_tenants_cancelling`), by `stillDue()` and by `platform_mark_subscription_overdue` under its row lock: no reminder and never `past_due`; an undone cancellation counts for nothing (done, ADR-075 decision 8). Runbook: `docs/runbooks/subscription-billing.md`. Tests: `BillingAutomationIT`, `RlsIsolationIT`, `EmailLayoutTest`, `billing-state.test.ts`, `tours.test.tsx`, `tours-operator.test.tsx`.
- **Built (sign-in review follow-ups, issues #369 and #377, from the reviews of #360 and #361, ADR-042, ADR-046):** migration `V106` (directly after `V105`): `platform_sessions.revoked_reason` accepts `two_step_required`, the reason `AuthFlow.refresh` already wrote when an operator with no second factor refreshed (it failed the check, a 500). The settings' locked recheck (422 `proof_required`) is tested and documented, and the two-step screen then shows the proof field and reads the settings again; the warning email is proven to roll back with the settings write; a grace after people were required at once and widening the roles with a later date need the proof (tested); the two-step screen's day start matches `atStartOfDay` on a day whose midnight is skipped. The Users screen's Reset sign-in: a second action in flight is refused in words and the other actions are disabled while the link is made; `totp_required` or `password_required` reads `/me` again; the code field keeps digits only (a pasted `123 456`); the 404 says the person may be outside the administrator's branches; the form warns that an open link box closes; `PasswordResetIT` proves a reset sent with an `Idempotency-Key` stores no row. No change to who can sign in, to password or code rules, or to session lifetimes. Tests: `TwoStepPolicyIT.anOperatorWithNoSecondFactorHasTheSessionEndedOnRefresh`, `aGraceAfterAtOnceIsPuttingTheDateBack`, `wideningWithALaterDateNeedsTheProof`, `SettingsTwoStepRecheckIT`, `PasswordResetIT.anAdminResetShowsTheLinkOnceAndItWorksOnce`, `users.dom.test.tsx`, `two-step.dom.test.tsx`, `state.test.ts` (two-step), `sign-in-reset.test.ts`.
- **Built (billing review follow-ups, issue #502, from the review of #482, ADR-075):** migration `V105` (directly after `V104`): `platform_mark_subscription_overdue` refuses a caller bound to a business (`app.tenant_id` set, 42501), as `platform_tenants_cancelling` does, so only the unbound platform job passes its `p_today` (the job's day is the application's `BusinessClock`, not the database's), and refuses a business that asked to close (ADR-090). The Owner's cancel (V90) already takes the subscription's row lock (`app_lock_subscription`) before its insert, so a cancel committing at the 00:10 tick is never made `past_due`: confirmed by a test, no change. `BillingService.dueForSuspension()` leaves out the businesses in `platform_tenants_cancelling()` (the Owner's cancellation, ADR-073, and a business that asked to close, ADR-090), as the runbook says. One price date for the reminders, the Owner's page and the list (`BillingService.priceDate`, ADR-075 decision 5): the later of today and the day after the last day paid for, the first day the next payment would cover; no price changes. ADR-075 decision 6 and the runbook say that `RT-<year>` receipt numbers never reset: the digits run on across years. Tests: `BillingAutomationIT.frSub09_onlyThePlatformJobMakesABusinessOverdue`, `frSub09_aCancelCommittingAtTheTickIsNeverMadeOverdue`, `frSub10_aCancellingOrClosingBusinessIsNeverListed`, `frSub12_theRemindersAndThePagePriceTheSameDay`.
- **Built (the rest of the Owner's powers, issue #571, the rest of #325, FR-SUB-13, FR-TEN-09, FR-IAM-18, ADR-090):** migration `V102` (directly after `V101`, merged): `billing_contacts` (the Owner names who gets the reminders and receipts; `PUT`, `DELETE /api/v1/billing/contact`, Owner only, audited `core.billing.contact_changed`; never a sign-in contact; `BillingService.recipient()` reads it before the Owner) and `tenant_closures` (the Owner's request to close the business, `GET`, `POST /api/v1/closure` with the owner step-up and `POST /api/v1/closure/cancel`, Owner only). While a request is open the resolver answers `closing` and every write is 423 `business_closing` except sign-in, the exports, any file's signed address and the cancel; billing leaves the business out (`platform_tenants_cancelling`); `GET /plan/status` gains `closing_remove_after`. The request queues the full Download my data workbook for the Owner through the new public port `DataExports` of `core.reporting` (`core.platform` now depends on `core.reporting`) and emails the Owner (`platform.closure_requested`, `platform.closure_cancelled`) and every operator. The operator lists the requests (`GET /platform/closures`) and removes one after its cooling-off (`bms.platform.closure-cooling-off-days`, 30) with their own code and a reason (`POST /platform/closures/{id}/remove`, 422 `cooling_off`, 409 `closure_not_open`): in one transaction the core's eraser (`app_erase_core_personal_data`) and every kernel `PersonalDataEraser` bean (`DocumentErasure` in `core.documents`, deleting every stored file after the commit; `LendingErasure` in `lending.members` and `RetailErasure` in `retail.sales`, through `lending_erase_personal_data` and `retail_erase_personal_data`) delete or anonymise the personal data, money records stay anonymised and read only until `keep_until` (`BMS_CLOSED_RETENTION_YEARS`, 5 by default, after the end of the business's financial year, the Owner's setting `financial_year_end_month` else June, in which it closed, that is the request; owner decision D46; the buyer's TIN kept in a document's snapshot), when the daily job `core.platform.closed-retention` deletes every remaining row but the tenant, users and closure rows (`platform_purge_closed_tenant`; off in `IntegrationTest`, which calls `ClosureService.purgeDue`), and the tenant becomes `closed` (a new `tenants.status`). The removal begins only outside a business's transaction with the operator authenticated in the database (`platform_begin_tenant_removal`: a live platform session and a code used in the last two minutes), the core's eraser also clears every note, memo and reason column and strips the personal keys of the audit and platform logs, and a closing business is left out of `app_list_active_tenants`, `app_list_active_tenants_with_module` and `app_tenant_is_active` (no jobs, no SMS or push). Every BEFORE UPDATE or DELETE row trigger function of a business's table lets a change through only inside a removal or a purge (`bms_erasing`): **a migration that replaces one of them must keep `bms_erasing(to_jsonb(OLD))`**, and a new tenant table with personal data needs its module's eraser (`BusinessClosureIT.frTen09_theRemovalLeavesNoPersonalDataInAnyTable` scans every tenant table). `GET /platform/tenants/without-owner` lists the businesses with no Owner and their active administrators for Set as the owner (the existing `make-owner`). Screens: Plan and billing gains Who gets the bills (tour `page-billing` v2), Business set-up gains Close the business (`/staff/setup/close`, what closing means, a review step, the Owner's proof, then Cancel the closing; tour `page-close`), every member of staff sees the closing notice, and the operator portal gains Businesses closing (`/platform/closures`, tour `page-operator-closures`) and Businesses with no owner (`/platform/no-owner`, tour `page-operator-no-owner`). Runbook: `docs/runbooks/closing-a-business.md` (removal, the deletion of the kept records after `keep_until`, setting an Owner). Tests: `BusinessClosureIT`, `OwnerlessBusinessesIT`, `BillingAutomationIT.frSub13_*`, `RlsIsolationIT` (factory rows for the two tables; `seedOneRowInEveryTable` and `tenantOwnedTables` are now public), `close.dom.test.tsx`, `contact.dom.test.tsx`, `closures.dom.test.tsx`, `plan.dom.test.tsx`, `tours.test.tsx`, `tours-operator.test.tsx`.
- **Built (the shop's own barcodes are EAN-13, issue #524, owner decision D25, ADR-070 decision 12, FR-RET-68):** migration `V93` (the next number after `V92`; it replaces the V87 check so a made code may be `code128` or an `ean13` starting with 2; no new sequence key). `POST /retail/products/barcodes/generate` now makes `210`, nine digits from `tenant_sequences` key `retail_barcode` and the GS1 check digit (`Barcodes.generated`), locks every item before the sequence, and skips a number another code holds; an item with an `IN` code made before keeps it, and it still scans and prints as Code 128. The label sheet draws an EAN-13 to the GS1 layout with its digits (`ean13Modules` in `barcode-state.ts`, `BarcodeSvg` in `catalogue-barcodes.tsx`). Tests: `BarcodesTest`, `RetailBarcodesIT`, `barcode-state.test.ts`, `scan.dom.test.tsx`.
- **Built (savings capped at profit less expenses, the savings reason optional, issue #538, ADR-080, FR-RET-18, FR-RET-19):** migration `V95` (claimed after `V94`; it replaces `retail_daily_savings_check1` with `retail_daily_savings_overwrite_reason_chk`, a not-blank check on a reason that is present; no other screen's reason changes). `GET /retail/savings/suggestion` adds `day_expenses_minor*`, `available_minor*` and `existing_amount_minor*`; the savings create and the expense create and void share a per-branch, per-day advisory lock, and a later expense leaves the savings and the screen says "Saved UGX X; profit less expenses is now UGX Y"; the suggestion is the rate times the available amount; `POST /retail/savings` refuses an amount above it with 422 `savings_above_available`; `overwrite_reason` is optional. The Savings screen shows profit, expenses and available, says the cap under the amount on blur, offers "Add a reason (optional)" as a pick list, and after a save shows the toast with the amount and the day, the saved state and the record at once. Tests: `RetailCashbookSavingsIT`, `RetailCashbookGuardsIT`, `savings.test.tsx`, `savings.dom.test.tsx`.
- **Built (two-step sign-in policy, issue #348, FR-IAM-06, FR-IAM-16, ADR-046):** migration `V67` clears `roles.mfa_required` for `tenant_admin` (an idempotent
  `UPDATE`; no table), so two-step sign-in is **optional by default on every database**, tenant administrators included, and the staging hand edit becomes
  redundant. A tenant administrator (`core.settings.manage`, no new permission) can require it for everyone (`require_mfa_all_staff`) or for chosen staff roles
  (`require_mfa_roles`) through `PATCH /settings`, with a grace period (`mfa_grace_days`, 0 to 90): turning it on, widening it, or sending a grace period sets
  `mfa_enforce_from` to the start of the day after now plus the days in the business's own time zone (one deadline for the whole policy; it moves only when a grace period is sent, never by narrowing), audited with before and after; turning it off, narrowing it or putting a date back needs the proof of the admin reset of a sign-in and emails the administrators; after the deadline a session with no factor is not renewed (the refresh answers `sign_in_required`, no MFA token). `Accounts.mfaRequired`
  keeps meaning "must have a factor to sign in now" (a flagged role, or a covered user once the deadline has passed), so sign-in, the first run, the sign-in
  after a reset and the refusal of a mailbox reset for a required user with no factor (ADR-042) are unchanged; during the grace period a covered user signs
  in and `GET /me` carries `mfa_due_by` and `two_step_policy` (`off`, `roles`, `everyone`). The PWA: Business set-up > Two-step sign-in
  (`/staff/setup/two-step`: three choices, the grace period, a plain-words review before saving), a reminder with the date for a covered person and a
  dismissible recommendation for administrators while it is off (`frontend/src/areas/staff/two-step/`), and `/staff/two-step` to turn on your own from inside the
  app. The welcome tour is version 14 with a Two-step sign-in step and the page tour is `page-two-step`. The admin reset of a sign-in keeps its step-up (the
  authenticator code, else the admin's own password).
- **Built (lending staff screens 1 and 2, issue #280):** PWA only, no migration: Members (list with live search, add, change, the member's page with the check of the details, next of kin, ID photos, relationships, loans) and the collateral register (register, change, valuations, custody changes, photos, the release request), phone-first, with a tour each (`docs/specs/lending-ui-notes.md`, section "Members and collateral").
- **Built (lending staff screens 3 and 4, issue #280; tenant currency, issue #289):** no migration. The loan application (`/staff/lending/applications/new`, `?memberId=` from the member's page; a draft changes at `/staff/lending/loans/$loanId/edit`): member, product with its limits, amount, term, purpose, date, guarantors found by searching and the member's held, valued items pledged, saved in steps with If-Match and then sent for appraisal; appraisal (`/loans/$loanId/appraise`: income, other payments, visit notes) and the decision (`/loans/$loanId/decide`: approve at or below the request, or decline with a reason; Approve is off for whoever sent or appraised it, FR-APR-03). The loan page shows an application's status, facts, guarantors, collateral, latest appraisal (score, band, warnings, other loans) and provisional schedule, with the next step in the sticky bar, and Send back and Cancel. Steps follow `permission_scopes` in the loan's branch (`lending/permissions.ts`, `applicationSteps`). `GET /me` carries `tenant_currency`, and the member, collateral and valuation forms type money in it (or the record's own currency) instead of UGX. Client `src/api/loan-applications.ts`; tours `page-application-new`, `page-application-edit`, `page-loan-appraise`, `page-loan-decide`, `page-loans` v2, `page-loan` v2 (`src/tour/tours-applications.ts`); tests `application.dom.test.tsx`, `application-state.test.ts`, `tours-applications.test.tsx`, `TwoStepPolicyIT#meNamesTheBusinessCurrency` (`docs/specs/lending-ui-notes.md`, section "Loan applications").
- **Built (increment 9, savings, issue #151, ADR-032):** migration `V30` (savings products,
  accounts, transactions, end-of-day balances, interest postings; the outbox accepts `sms`);
  products with interest rules, minimum and opening balances, withdrawal fee and limits, dormancy;
  any number of accounts per member; deposits; withdrawals with the `savings_withdrawal` checker
  above the threshold (a closure is the same action); reversals through `savings_reversal`; freeze,
  dormancy and reactivation; the nightly end of day (`lending.savings-end-of-day`: end-of-day
  balances, interest per product period rounded once, dormancy); statements and the two savings
  reports; receipt SMS queued in the outbox (not sent until an SMS sender exists, pending ADR-013);
  `SavingsMetrics` for the insights page; the staff savings screens; savings in the fabricated seed.
  Deferred: member self-service (increment 11), USSD and online payments (phase 2), transfers
  between accounts and from loan credit (FR-REP-04a), savings-secured loans (open question 2).
- **Built (increment 10, issue #152, ADR-031):** migration `V32` (investment products, investments,
  schedule items, investment transactions; two checker permissions; account 4060 investment
  penalty income); fixed-term and recurring (auto-renewing) products with flat or monthly
  compounding returns and payout at maturity, monthly or quarterly; funding with the
  `investment_funding` approval action above the threshold; the accrual and payout schedule written
  at funding (R-INV-1 to R-INV-5); the nightly `lending.investment-returns` job that accrues each
  period on its end date, makes payout periods due, matures, rolls over and records reminders,
  idempotently; return and maturity payouts; rollover; early withdrawal with a penalty
  (`investment_early_withdrawal`, R-INV-6); reversals (`investment_reversal`); certificate,
  statement and the maturity ladder with the investment metrics (`InvestmentMetrics`); the staff
  screens (`docs/specs/lending-ui-notes.md`); and fabricated investments with twelve months of
  history in `seed-lending`. Deferred: member self-service and portal applications (increment 11),
  online payments (chapter 12), USSD and SMS reminders (pending ADR-013), crediting returns to
  a savings account (a follow-up on the savings module), the certificate PDF (documents follow-up).
- **In review (issue #153, ADR-030):** `lending.insights`, the staff Insights page
  (`/staff/insights`): morning brief, portfolio with PAR and ageing, revenue from posted
  journal lines, member activity, drill-down tables with CSV export, 60-second polling; migration
  `V33` (permissions, `lending_loan_daily_snapshots` written nightly until increment 6 owns it, the
  digest settings, indexes); the owner's daily digest through the outbox; `seed-lending
  --insights-demo [--scale N]` for a fabricated year. Metrics dictionary:
  `docs/specs/lending-insights-metrics.md`.
- **Built (extras slice D1, issue #214, ADR-038):** migration `V49` (`report_runs` and three read-only definer functions for the
  runner); `core.reporting`: queued report runs with the job `core.reporting.report-runner` (every 15 seconds, one run per
  tenant, `BMS_REPORTS_MAX_CONCURRENT_RUNS` across the platform), one read-only `REPEATABLE READ` snapshot transaction per run
  into a disk spool that commits before the JSON or CSV file is rendered and stored as a core document of type `report`, a
  10 minute timeout, row and size caps, cancel, the nightly `report-expiry` that deletes files after 30 days (download then 410
  `report_expired`), the catalogue `GET /reports`, `POST` and `GET /report-runs`, `POST /report-runs/{id}/cancel`, the audit
  (`core.reports.run`, never a row), and a minimal Reports screen; off until the tenant setting `reports_enabled` is on. The
  first report, `lending.par`, reads the figures through the new public port `InsightsFigures` of `lending.insights`. No
  permission is added. Deferred: the other six reports and the pack, the full report centre (slices D3 to D5, issues #222,
  #225, #229).
- **Built (extras slice D2, issue #217, ADR-038):** no migration and no permission. The report PDF (the shared `report` template of
  `core.documents` under the tenant letterhead: parameters box, repeated heading, tinted rows, total row, page x of y, landscape above
  seven columns, rows cut at `BMS_REPORTS_PDF_ROW_CAP` with a pointer to the Excel file) and the Excel workbook (a minimal streaming
  writer in `core.reporting`: numeric cells, real dates, a sheet per table and a Parameters sheet, text that begins like a formula
  as a string cell with the quote prefix) join JSON and CSV, rendered from the same spool; `lending.par` and the new
  `lending.portfolio_outstanding` (active loans, distinct borrowers, principal and interest outstanding, arrears, average principal;
  by branch, product or officer) are offered in all four formats, from `InsightsFigures.par` and `.portfolio`, with tests that every group
  equals the insights endpoints for the same filter and caller. `ReportMemoryIT` runs both reports as PDF and Excel on the 224 MB
  heap. The Reports screen offers the PDF first. Visual check: `make document-samples` and `docs/ui/report-samples/`.
- **Built (Download my data, issue #334 step 1, ADR-074, FR-RPT-09 to FR-RPT-11):** migration `V91` (`data_export_settings`,
  `data_export_weekly`, the permission `core.data_export.run` for `tenant_admin`, and `app_data_export_weekly_tenants`; the runner's definer functions stay as V90
  left them, so a business the operator suspended gets no run). The report `core.data_export` (`ReportDefinition.everyBusiness`: offered whatever `reports_enabled` says) writes
  the Branches sheet and each vertical's `DataExportSection` (`RetailDataExport`, `LendingDataExport`), a sheet per table split by
  year past `bms.data-export.split-rows`, through the existing streaming writer (not POI SXSSF, ADR-038 decision 6). Routes
  `/api/v1/data-export` (card, `/runs`, `/settings`); a business ended by its Owner's cancellation (ADR-073) may still ask and fetch the file, one the operator suspended may not. The job
  `core.reporting.data-export-weekly` queues the Owner's copy once a week, without the personal-data columns (they are only in
  the copy downloaded after sign-in), and emails it through the outbox (attached up to
  `bms.data-export.attach-max-bytes`, else a link) via the `EmailAttachments` port of `core.notifications`; the integration tests
  switch it off (`bms.data-export.weekly-enabled=false`) and call `DataExportWeekly.tick`. The card is in Business set-up.
- **Built (client extras C1, issue #220, ADR-037):** migration `V51` (`ai_calls`, `ai_platform_usage`); `core.ai`, the
  language model gateway: nothing is sent unless `BMS_AI_PROVIDER=openai_compatible` with a base URL, key and model, the tenant
  has switched the assistant on (settings group `ai`, with the acknowledgement) and is named in `BMS_AI_ENABLED_TENANTS`; per
  call, daily tenant and platform token and cost caps are checked before any network call, an input screen refuses personal-data shapes, and a failed call keeps its reservation; no route, no permission, no screen
  (those are C2 to C5). Runbook: `docs/runbooks/ai-assistant.md`.
- **Built (client extras C2, issue #223, ADR-037):** no migration, no route, no permission, no screen, no model call. The
  public port `PortfolioFacts` of `lending.insights` (with `FactDisplay`): about forty figures of the metrics dictionary with
  display forms, polarity and week-on-week and month-on-month changes, under the caller's scope (the same rules as the insights
  endpoints) or a stored `PortfolioScope`, plus the largest branches and products from `InsightsFigures.par`; any figure over
  one to four loans or members it is computed over, and every figure that would give it back by subtraction (nested windows,
  the PAR levels, group remainders), replaced by `fewer than 5`; no officer dimension and no name; a scope read after midnight
  reads the snapshot of its day. The new module `lending.advisor`: the alias mapper (`Branch 1`, `Product A`), the prompt
  builder, and the **structured output path**: the model writes no text, it returns JSON of findings (up to three fact keys, a
  topic, an action code from the reviewed catalogue `advisor/catalogue.json`, a priority) and a watch list; `StructuredValidator`
  refuses any extra field or any string that is not an enum value or a key of a fact in the prompt (hidden, out of scope or of
  the wrong topic), and our code renders every sentence from the catalogue and the facts, with a deterministic `Fallback` when
  the answer is refused; the offline evaluation harness `AdvisorEvaluation` with its golden set. Also fixes the members
  endpoint answering 500 for a range with no decisions. Spec `docs/specs/client-extras.md` section 5.13; runbook section 9 of
  `docs/runbooks/ai-assistant.md`.
- **Built (client extras C3, issue #226, ADR-037):** migration `V55` (`lending_ai_briefs`, the permissions `lending.advisor.use` and `lending.advisor.configure`); in `lending.advisor` the job `lending.advisor-daily-brief` (every 15 minutes from 06:00 Kampala time; the business date is read once per run from the time it was due, so a job queued before midnight uses its own date; one brief per tenant and date, idempotent), the structured-answer path end to end (gateway, `StructuredValidator`, render from the catalogue, never store the model's text; a refused answer stores the facts-only brief with reason `validation` and still counts in the caps), the stored all-branches brief returned only to users whose branch scope covers every branch and who hold `lending.insights.all_officers` (others get the automatic summary for their scope, reason `scope`), `GET /lending/advisor/brief` (204 and no figure reads while the assistant is off, its switch or the platform list; 200 with the automatic summary on every failure path otherwise), `GET` and `PUT /lending/advisor/settings`, the Insights card (advisory label, as-of time, fact chips) and the screen `/staff/assistant` with its tour. While off no facts are read, no call is made and no card is drawn; a refusal that paid for nothing is not stored. Spec `docs/specs/client-extras.md` section 5.14; ADR-037 amendment of 2026-10-18; runbook section 10 of `docs/runbooks/ai-assistant.md`. Not built: the question box (#230), the call list.
- **Built (client extras A7, issue #233, ADR-035 amendment of 2026-10-18):** migrations `V56` (`payment_gateway_accounts`, definer functions, a CHECK in both directions between the platform scope and a platform purpose) and `V57` (the permission `core.payment_account.manage`, the tenant admin's alone, and the validation of that CHECK). Money owed to a tenant lands only in that tenant's own Pesapal merchant account: the tenant administrator opens Receive payments (`/staff/payments`), enters the consumer key and secret (sealed with the application data key through `core.identity.DataKeys`, write-only, never returned, logged or audited), and Test connection gets a token and registers the account's notification address (only the digest of its path token is stored). Saving and removing ask for a current TOTP code (`core.identity.AdminSecurity`), the other administrators are emailed, the platform's key and a key another tenant uses are refused (blind index). Routes `GET`, `PUT`, `DELETE /api/v1/payments/merchant-account`, `POST .../test`, and `GET /api/v1/platform/tenants/{id}/payment-account` (status only) with the operator's audited `POST .../release`. The poller skips, never closes, an intent whose account has no readable credentials; the data key rotation job also re-seals TOTP seeds (`core.identity.SealedSecrets`). The gateway port names an `Account` (a tenant's carries its tenant, the platform's none); a tenant gets no fallback to the platform account, in code and by test. The environment variables `BMS_PESAPAL_CONSUMER_KEY`, `_CONSUMER_SECRET`, `_IPN_ID`, `_IPN_TOKEN` and `_TENANT_ID` are gone; `BMS_DATA_KEY_PREVIOUS` carries retired data keys during a rotation (job `core.payments.reseal-accounts`). Runbook: `docs/runbooks/connect-lender-pesapal-account.md`. KTA's legal confirmation is still to be asked before the first live repayment.
- **Client extras (issue #210; slices D1, D2, B1, B3, B4, C1, C2 and C3 are built):** `docs/specs/client-extras.md` specifies mobile money through Pesapal
  (ADR-035), SMS through Africa's Talking on the outbox (ADR-036), the advisory AI brief (ADR-037) and the formal
  reports pack as PDF and Excel (ADR-038), with a note on credit bureaus. The build slices are issues
  #211 to #233 in the order of the spec's section 10.
- **Built (extras slice B1, issue #212, ADR-036):** the SMS channel core in `core.notifications`: tenant,
  category and subject on the outbox row; the claim that takes transactional rows first and rotates across tenants;
  `OutboxChannel.send` returning sent, deferred, unknown or refused; the `SmsProvider` port with the Africa's Talking
  adapter, a non-sending sink (the only provider outside production), an allow-list provider and a recording fake;
  `BMS_SMS_MODE` off, sink, allowlist, live with a startup guard that refuses any non-sandbox URL or account outside
  production in every mode; the `sms_deliveries` delivery and cost log, `sms_opt_outs`, the sending window, the platform
  ceiling and the operator-only per tenant switch and cap (`PUT /platform/tenants/{tenant_id}/sms`); strict phone
  forms for SMS; savings receipts through the channel without the balance unless `sms_include_balance`; migration
  `V50`; runbooks `docs/runbooks/sms-setup.md` and `sms-incident.md`. Delivery reports are #215.
- **Built (extras slice B3, issue #218, ADR-036 second amendment):** the tenant SMS settings, migration `V52`: the settings
  group `sms` (`GET` and `PUT /settings/sms`, `If-Match`): the tenant's own switch with its cost acknowledgement (SMS needs both
  this and the operator's; no row is queued or sent while either is off, and switching on drops the backlog), the sender id
  chosen only from the operator's per tenant allow-list (`platform_sms_sender_ids`, routes under
  `/platform/tenants/{tenant_id}/sms/sender-ids`), the window (Africa/Kampala, 60 minutes at least, inside 06:00 to 21:00),
  daily and monthly caps that can be lowered under the operator's and never raised (0 means stop), and the balance switch (the
  old flat `sms_*` keys left `PATCH /settings`); the opt-out list (`/sms/opt-outs`: member rows from the member's page and
  number rows, masked, a pasted list checked first; lifting or narrowing one is the tenant administrator's, with a reason in the
  audit row); the tenant's own wording per event in a placeholder language with no
  expressions (`/notification-templates`, previews with segments and a cost estimate from the tenant's own history) and a test
  message that reaches only the sink or the allow-list and obeys the same switches, opt-outs and caps (`/sms/test-message`); the staff screens under `/staff/sms` and the
  member page's text-message choice; no new permission.
- **Built (extras slice B4, issue #221, ADR-036 third amendment):** migration `V53` (`notification_outbox.leased_until` set by the claim,
  `notification_outbox_cancel_unclaimed`, `lending_investments.notify_member`, and the operator's failed list hides what a
  reversal cancelled); no permission, route or screen. The member events of FR-NTF-02 on the outbox:
  `loan.disbursed` (the amount paid out after deducted fees), `loan.repayment_received` (amount, date, receipt number; the balance only
  when the tenant's `include_balance` is on), `loan.closed`, the two savings receipts (now through the same port), `investment.funded`,
  `investment.maturing` (a reminder, held for the tenant's window) and `investment.paid_out` (a return, the maturity payout or an early
  withdrawal). Modules queue them through the public port `core.notifications.SmsEvents` (`queueWhenActive`): the whole preparation, lookups
  included, runs in a savepoint (nothing that goes wrong reaches the money; a rollback sends nothing), the number is read without
  guessing, the key is `sms:<template>:<tenant>:<transaction>:<member>` (one row per event and member, ever). Only a staff event messages a
  member: not the seed or import ports (the seed's nightly replay passes `runDaily(date, false)`), not a request with `notify_member:
  false` (accepted only from a holder of `core.settings.manage`, else 403; audited; loan disbursement and repayment, savings deposit, withdrawal and closure, investment funding, payouts, early withdrawal), not an
  event dated more than `BMS_SMS_EVENT_MAX_AGE_DAYS` (3) days ago. A reversal queues nothing and withdraws the reversed transaction's
  unclaimed message. Each event has its own placeholder whitelist for the tenant's wording (`/notification-templates` lists eight
  events). The channel cleans every value (a line feed becomes a space, invisible and direction-changing characters go) whichever
  wording is used.
- **Built (extras slice A2, issue #213, ADR-035 second amendment):** migration `V54` (`payment_intents`, `payment_callbacks`, two platform tables for unknown notifications and counters, definer functions); `core.payments`: the gateway port, the Pesapal adapter (token cache following `expiryDate`, the http 200 with body status 500 envelope, whole shillings enforced, RegisterIPN spacing), a fake, intents (30 minute offer, 48 hour polling, one locked creator), the public notification endpoints that never trust their request and always re-query the status, the poller job `core.payments.poll` (`bms.payments.poller-enabled`, off in `IntegrationTest`, issue #487), the purpose handler registry (no real purpose until #216 and #219), audit of every transition, and the settings group `payments` (`GET` and `PUT /payments/settings`, `GET /payments/intents/{id}`, `POST /payments/intents`, `.../cancel`) off by default and bound to `BMS_PESAPAL_TENANT_ID`; outside production only the sandbox base URL starts. No permission added. Runbook: `docs/runbooks/payments.md`.
- **Built (retail sales grouping, issue #328):** no migration. `GET /retail/sales/summary?group_by=year|month|day` with the sales
  list's filters and branch scope: one grouped query per level over `retail_sales` (completed sales only in the figures, voided
  ones counted apart, per payment method counts and amounts, `profit_minor` only with `retail.profit.read` in every branch the
  figures cover), bounded to 20 years, 12 months or 31 days with `has_more` and `next_to`; the list and the summary share
  `SalesRepository.appendFilters`. All sales and Credit sales are collapsible year, month and day groups with a summary on every
  header, a day's sales loaded only when it opens (issue #341 replaced the fixed levels with a chain and nothing open to start with; `sales-grouped-state.ts`,
  `docs/specs/retail-ui-notes.md`), with the Help tours `page-sales` and `page-credit-sales`.
- **Built (retail grouping chain, issue #341):** no migration. `GET /retail/sales/summary` takes `group_by` as an ordered list from
  branch, year, month, seller, day, category and item (a single value still works) and `level` for the level answered; the parent
  selection travels as filters (`branch_id`, `from`/`to`, `seller_id`, `category_id`, `item_id`), one grouped query per level, bounded per
  level (20 years, 12 months, 31 days, 100 shops, 50 of the others) with `has_more` and `next_to` or `next_offset`; a category or an item is summed
  over the sale lines, `profit_minor` only with `retail.profit.read` in every branch covered, voided sales counted apart; the sales list also
  takes `seller_id` and `category_id`. All sales and Credit sales have a Group by control (move, remove, add, kept per user and list in
  localStorage, keyed by the user id) and open from the shop down to the items with a summary on every line and a breadcrumb (`sales-chain.tsx`,
  `sales-chain-state.ts`, `sales-group-by.tsx`, `docs/specs/retail-ui-notes.md`); the salesperson level (and `seller_id`) needs `retail.profit.read`
  in every branch the figures cover (403 otherwise, left out of `available_levels` and of the PWA's Group by; the sales list's `seller_id`
  has the same rule, and `created_by`/`voided_by` on its rows are null for a caller without profit read, unless it is their own id); a group that opens onto groups also offers
  "Show the sales"; the tours `retail-sales` (version 5), `admin-welcome`
  (version 7), `page-sales` and `page-credit-sales` (version 2) have a Group by step.
- **Built (retail sale actions, issues #326, #327 and #330, frontend only):** no migration, permission or route. An opened sale and the Credit sales and All sales lists take **Record payment** and **Mark as fully paid** on a live credit sale that still owes (`POST /retail/sales/{id}/payments` with an `Idempotency-Key` kept by `usePersistedDraft`; amount defaults to the balance, cash, mobile money or bank, a date from the sale to today; shown to a holder of `retail.sale.create` at the sale's branch; an imported sale shows a note instead, which only the screen enforces for now; the payments route does not check `historical`), and an opened completed sale takes **Void this sale** (`POST /retail/sales/{id}/void`, a reason of five characters, a plain warning that stock goes back and reports drop it; shown to a holder of `retail.sale.void` at the branch; the sale then reads Voided with its reason). The forms are in `sale-actions.tsx` and their rules in `sale-actions-state.ts`; the home cards, stock list and stock value say Price and Cost, cost first. Notes: `docs/specs/retail-ui-notes.md` (Sale actions).
- **Built (credit sale recognition, issue #340, ADR-043):** slice 1, no migration. The tenant setting `credit_sale_recognition` in the group `retail.capabilities` (`on_sale`, the default and today's behaviour, or `on_payment`, the owner's rule), changed with `PUT /retail/settings/capabilities` and shown in Retail settings in plain words. The switch is one way and counts from the next business day (`credit_recognition_effective_from`, one stored date): it can be cancelled until that day, then the route refuses to switch it off (422 `credit_rule_cannot_be_switched_off`) and the operator reverts it by SQL (runbook); a credit sale is counted the way the rule stands on the later of its date and the day it was entered, a payment counts on the day it was entered and at the branch that took it (a return credited to the balance is spread over the branches that took the money), and a payment on a sale counted when the buyer pays is dated today (`counted_when_paid` on the sale). Under `on_payment` a credit sale is Sold and On credit, and counts as Received and as profit only as the buyer pays, a part payment giving part of the profit by the sale's margin (`ProfitFigures`, `CreditRecognition`): the daily profit report, the home cards and the cash book's daily savings (half of the received profit per shop per day) read the same figures; stock and the ledger are unchanged; imported credit sales stay outside the rule. `/retail/sales/summary` is not changed here (issue #341). Slice 2 adds the write-off of a buyer who never pays: migration `V64` (it sets `lock_timeout` to five seconds), `retail_sale_writeoffs`, the chart account 5140 `bad_debt_loss`, `POST /retail/sales/{id}/write-off` (its own permission `retail.credit.write_off`, administrators only; the maker-checker action `retail_sale_write_off` above the tenant's threshold), the sale's `write_off`, the loss in the daily profit report on its date, and a Write off form on the opened sale. An invoice issued after a write-off says what was paid and what was written off, never "Paid in full" (issue #354, no migration; `RetailDocumentsIT`, `retail-documents-mock.test.ts`).
- **Built (cash change on the sale form, issue #346):** PWA only, no migration, no API change, no permission. For a cash sale the form shows Amount received (whole shillings, numeric keyboard, quick buttons 1,000 to 50,000 and Exact) and Change to give in large figures; an amount below the total is refused in plain words (no part payment on a cash sale), mobile money, bank and credit hide it, and the change is display only: the sale is recorded at its total, the daily cash summary counts that total, nothing is sent or saved in the draft, and the receipt shows Received and Change given. New files `sale-change-state.ts` and `sale-change.tsx`, so a barcode scanner (#345) can edit `sale.tsx` freely; the tours `retail-sales` (version 6) and `admin-welcome` (version 9) have a Cash and change step (`docs/specs/retail-ui-notes.md`, `docs/sdd/07-api-design.md` section 7.11.20).
- **Built (close a branch, mark a branch as not stocking, issue #329, ADR-041):** migration `V61` (the first free number after `V60`): `branches.stocks_goods`, `closed_at`, `closed_reason`. Closing is the existing status `inactive` with a way back: `POST /branches/{id}/close` (`If-Match`, body `{reason?}`; refused with `branch_close_needs_reason`, in plain words with the stock value, while the branch holds stock or has a transfer on the road, until a reason is sent; nothing is posted or moved) and `POST /branches/{id}/reopen` replace `deactivate`; both are audited and need `core.branches.manage` (no permission added). The retail module supplies the check through the core port `BranchClosingCheck`. A closed branch leaves `/me`, the pickers, the forms and the default `GET /retail/stock/all-branches` (`include_closed=true` and `closed_branches` bring it back with a Closed flag; the stock-by-location report keeps it, flagged). A branch with `stocks_goods` false is refused for sales, restocks, purchase orders, goods receipts, transfers into it and their receipt, stock-takes (made and committed) and the restock side of a return with 422 `branch_not_stocking` (`RetailBranchContext`, which reads the branch row `FOR SHARE` in write transactions so it serialises with a close or a flag change); goods can still leave it (transfer source, usage and damage) and voids stay allowed; marking it needs empty shelves, no transfer on the road and no open stock-take (`branch_still_has_stock`); the importer refuses a balance row for it. The Branches screen (`/staff/setup/branches`, linked from Business set-up) lists, closes, reopens and switches stocking; the sale, restock, move-stock, stock-take and usage screens skip a branch that keeps no goods; the welcome tour is version 8 with a Branches step and the page tour is `page-branches`. Notes: `docs/specs/retail-ui-notes.md` (Branches).
- **Built (a store holds stock and does not sell, issue #366, ADR-048):** migration `V65`: `branches.sells_goods boolean NOT NULL DEFAULT true` and the check `branches_store_stocks` (a store must keep goods). A branch with `sells_goods` false is a Store: it takes restocks, goods receipts, transfers in and out, stock-takes, usage and damage like any stocking branch, and refuses a sale, a quotation, a price resolution, a payment on a credit sale, a payment on account, a refund paid out of its till and the cash book savings, bankings, withdrawals and advance repayments (expenses stay allowed) with 422 `branch_does_not_sell`; a refund for a sale made at a store is paid from the one shop the caller may sell at, else 422 `refund_branch_required` (`RetailBranchContext.requireSells`, read `FOR SHARE` like `requireStocks`). `PATCH /branches/{id}` takes `sells_goods` (also on create): marking is refused with `branch_has_open_credit_sales` while a credit sale made there still owes (core port `BranchSellingCheck`, implemented by `BranchTillCheck` in retail sales) and with `store_must_stock` when the branch would keep no goods. `/me` reports `sells_goods` per branch; the sale form, the till, the refund branch and the default branch skip a Store while the restock, goods receipt, transfer and stock-take forms include it; the Branches screen has Mark as a store (does not sell) and Sells again; the page tour `page-branches` is version 2 with a Store step. Imported credit sales never block marking. Tests: `StoreBranchIT`, `StoreBranchTillIT`. ADR-048 (Proposed). Notes: `docs/specs/retail-ui-notes.md` (Stores).
- **Built (add a branch from the app, issue #581, ADR-089):** no migration. `POST /branches` takes `stocks_goods` beside `sells_goods` (both false is 422 `store_must_stock`) and, with no `code`, makes one from the name (letters and digits in capitals, at most six, then 2, 3 and on when taken; a per-business advisory lock on add and reopen serialises the plan-limit count, so adds at the same moment never pass `max_branches` and two of one name get two codes; `ON CONFLICT` answers only a typed code that is taken, 409 `duplicate_branch_code`). The Branches screen has Add a branch for `core.branches.manage`: Name (its hint shows the code the app makes; no code is typed, the rule of #562), What is it? (Shop, Store, Head office that keeps no goods, a segmented control) and Where is it?; a full plan is said in plain words with a link to Plan and billing; a dark toast names what was added and `/me` and the branch lists are read again. Tests: `AddBranchIT` (each kind and its sale rule from creation, the made code, the plan limit, the permission, a store becoming a shop and back), `branches.dom.test.tsx`. Notes: `docs/specs/retail-ui-notes.md` (Add a branch).
- **Built (entry mistakes corrected inside the app, issues #351 and #352, ADR-044):** migration `V63` (the first free number after `V62`): `bms_name_key`, the
  generated `retail_products.name_key` and `duplicate_reason`, `retail_purchase_voids` and `retail_stock_corrections`; no new permission. A product name is kept once per
  unit (a key that folds case, accents, spaces and punctuation): create and edit refuse a repeat with 422 `duplicate_product_name` naming the existing item, an
  administrator (`core.settings.manage`) may add it anyway with a reason kept on the product, `GET /retail/products/similar` gives the close names the form warns
  about, the CSV import skips a repeated name and the pilot importer reports an anomaly. `POST /retail/purchases/{id}/void` reverses a restock's movements and entries
  in one transaction, gives its prices back while it is still the latest price event, is refused in plain words when its stock moved, it is imported or a supplier
  payment stands against it, and leaves the restock in place, shown as voided (cash returns to the cash book on the day of the void; a voided credit restock is not
  owed). `POST /retail/stock/corrections` (`retail.stocktake.commit` at the branch) posts one `adjustment` of source `retail.stock_correction` with the old and new
  quantity, who, when and why, which the shrinkage, usage and profit reports never count; its 409 `stock_moved` carries `current_qty`, which the form shows at once and keeps when the list is read again (issue #370). The Past restocks screen (Correct this restock, Void and enter it again,
  the unit hint), Correct on a stock row (quantity, category, history) and the Items form's duplicate handling are in the PWA (`docs/specs/retail-ui-notes.md`).
  Deferred: merging two products already created by mistake, a maker-checker threshold for the void and the correction, a dedicated ledger account for corrections.
- **Built (entry warnings and name hints, issue #353, ADR-093, FR-RET-74, FR-RET-75):** rule based only, no AI call (the owner parked AI).
  Migration `V104` (indexes only: `(tenant_id, product_id, created_at DESC, id DESC)` on sale, purchase and transfer lines); no
  permission added. `GET /retail/products/{id}/entry-norms` (`retail.stock.read`): over the item's newest 20 purchases, sales and moves
  (voided ones left out) the median quantity in base units and the usual unit (more than half of the lines), each from 3 lines; the last
  price per piece only for someone who sees prices (ADR-055); the thresholds (10, 2, 3). `POST /retail/entry-warnings/confirmations`
  (`retail.stock.read`, 204) writes the audit row `retail.entry_warning.confirmed`. `GET /retail/products/name-hints`
  (`retail.catalogue.manage`): Did you mean (case, accents, spaces ignored; then a few letters apart) and the category of the items sharing
  the most words. Screens: Buy stock, Sell, Move stock and Count stock read each item's norms once and, on Save, show one plain warning
  next to the field (unit, quantity ten times or a tenth, a count against the records, a typed price twice or half the last) with Confirm
  (checks the screen as it is now, then saves its current values; recorded once the save succeeds) and Change (keeps the form); never disabled, never refused; Add an item and the new item on Buy stock and Count
  stock show Did you mean and Choose the category, tapped only (`entry-checks-state.ts`, `entry-checks.tsx`, `name-hints.tsx`). The
  below cost refusal, the #352 name rules, pack sizes per delivery, the category picker and item codes are unchanged. Tests:
  `EntryChecksIT`, `entry-checks-state.test.ts`, `entry-warnings.dom.test.tsx`, `name-hints.dom.test.tsx`. Notes:
  `docs/specs/retail-ui-notes.md` (Entry warnings and name hints).
- **Built (stock and restocks grouped by a chain, issue #350):** no migration, no permission. `GET /retail/purchases/summary` takes `group_by` as an ordered list from branch, year, month, supplier, day, category and item (`level`, and the parent selection as `branch_id`, `from`/`to`, `supplier_id`, `category_id`, `item_id`), one grouped query per level over the restocks' purchase movements, bounded (20 years, 12 months, 31 days, 100 shops, 50 of the others) with `has_more`, `next_to` or `next_offset`; a voided restock is counted apart, `total_minor` (what was bought at cost) only with `retail.profit.read` in every branch covered, the branch scope as the restocks list; the nil UUID is the key of "No supplier". `GET /retail/stock/summary` takes `branch` and `category` levels with the stock list's filters and answers items, negatives, value at price and, with the cost permission, at cost. `GET /retail/purchases` also takes `branch_id`, `category_id` and `product_id`. The Stock page and Past restocks stay flat until the person chooses Grouped, then offer the Group by control (`list-chain.tsx`, `list-chain-state.ts`, `stock-grouped.tsx`, `restocks-grouped.tsx`) with the screens' own rows under the last level, so Correct on a stock row and Correct this restock work inside the groups; the tours `page-stock` (version 3) and `page-restocks` (version 2) have a Grouped step (`docs/specs/retail-ui-notes.md`, `docs/sdd/07-api-design.md`, FR-RET-49).
- **Built (Controls page, issue #358, part of #357, ADR-047):** migration `V66`: the permission `retail.controls.read` (tenant administrator by default, branch scoped); no index (V34 serves the voids). `GET /retail/controls/summary`, `/groups`, `/records` and `/patterns` in `retail.reports`, read only, no new table: cards for chosen days (sales cancelled, cancelled within N minutes, goods taken back, prices changed, discounts, and sales below cost only for a caller with `retail.profit.read` in every branch covered) each with the trend against the previous period, the share of sales and a rate per 100 sales (sales with the event over sales entered, in the same units); the same by a chain of shop, person (whose sale it was, named, deactivated shown), day, reason and approver (who cancelled it), by name and never ranked, opening onto the individual records and the sale; and repeat patterns (the seller's rate more than 2 times the shop's own, from 3 cases over 10 sales, never the approver). The Controls page (`/staff/retail/controls`, `controls.tsx`, `controls-state.ts`) reuses the Group by control and the breadcrumb of the sales lists (now generic), exports the visible group as CSV, and has the welcome tour step (`admin-welcome` version 10) and the page tour `page-controls`. Restocks cancelled and quantities corrected (#356) have counts and values and no rate; credit write-offs (#349) get their card when that table is on `main`. Notes: `docs/specs/retail-ui-notes.md` (Controls).
- **Built (customer orders with delivery, issue #385, FR-RET-53 to FR-RET-57, ADR-052):** migration `V70` (renamed at merge in merge order): `retail_customer_orders`, `retail_customer_order_lines` and `retail_customer_order_events` (the history, append-only like the lines), `retail_stock_balances.reserved_qty`, the chart account 4120 `delivery_income` and the permission `retail.order.manage` (administrators and every role holding `retail.sale.create`). Routes `/retail/orders` (take, list by step, due day, source and search), `/board`, `/customer-lookup?phone=` (the whole number with its country code finds the earlier orders and the credit buyers, a `07...` or bare nine-digit number read as `+256`, so a `+254` number never finds a `+256` one with the same nine digits, issue #441), `/{id}` (with its history), `PATCH /{id}` (name, phone, place, day, fee and notes under `If-Match`), `/{id}/pack`, `/send` (who takes it), `/deliver` (**M**, also `retail.sale.create` at the shop) and `/cancel` (with a reason), and `/reports/delivery-fees` (by day, source or rider) and `/reports/cancellations`. An order records its source (WhatsApp, Instagram, Facebook, phone call, walk-in, other), is priced by `SalesService.priceLines`, and keeps its items aside through `StockLedger.reserve` under the balance row lock, so two orders for the last unit are serialised and the till cannot sell a unit kept for an order (`insufficient_stock`, every guarded movement). Delivery releases the stock and makes the sale through `SalesService.record` in the same transaction (paid in full, part now with the rest on credit through `recordPayment`, or on credit with the fee paid now, or for a credit buyer on the list with the fee on the account), and posts the fee on its own to `delivery_income`; a cash fee counts in the cash book's takings on the day it is paid, and among the voids on the day its sale is voided. An unpaid fee on the account (shown apart on the account page, paid on its order, written off with its sale) is owed wherever a buyer's debt is read (the buyer list, the balance, the statement, credit control, the customer ageing, the credit counted when paid, the Store check). A price changed when the order was taken stays one override on the sale line (`price_overridden_by`, the person who took the order); any other agreed price that today's list prices otherwise is `order_agreed`, which the Controls page does not count. Delivery takes the sale's trusted path (no price override, price floor or inactive item check again; the credit limit counts what goes on credit); a credit buyer's fee may go on the account (`fee_on_account`, paid later through `/{id}/fee-payment`); voiding the sale reverses the fee; a branch with open orders is not closed or made a Store (`branch_has_open_orders`); the Stock list and the item search show free and kept stock. No capability, no maker-checker. The Orders page (`/staff/retail/orders`: the board, New order with the phone first, the list and the step buttons), one order (`/staff/retail/orders/$orderId`: its history, the details that may change, the sale it became) and Delivery fees and cancellations (`/staff/retail/orders-report`), with the page tours `page-orders`, `page-order` and `page-orders-report`, the retail menu shortcut Orders right after Sale and the tile on the retail home. Tests: `RetailCustomerOrdersIT`, `RlsIsolationIT`. Notes: `docs/specs/retail-ui-notes.md` (Orders).
- **Built (change an order's items without cancelling it, issue #393, ADR-052 amendment 1):** migration `V77` (after `V76` of PR #415, issue #412, which merges first): `retail_customer_orders.items_revision` and `retail_customer_order_lines.revision` (the lines stay append-only; the current lines are those of the order's revision), the guard trigger taught the change, the history step `items_changed`. `POST /retail/orders/{id}/items` (**M**, `If-Match` and `Idempotency-Key`, `retail.order.manage` at the order's shop) takes the whole new set of lines of an order that is ordered or packed (out for delivery, delivered or cancelled: 409 `order_step_not_allowed`) and, in one transaction under the order row lock and then the balance row locks, gives back what removed or smaller lines kept and keeps the new or larger ones through `StockLedger.rehold` (netted per item, in lock order; 422 `insufficient_stock` when not enough is free). A line left as it was (same item, unit and quantity) keeps the price agreed through an `Agreement` entry to `SalesService.priceLines`, as on delivery; any other line, one whose quantity changed included, is priced as on taking an order (price breaks found again, an override with `retail.price.override` and a reason credited to the caller). A packed order goes back to ordered. The order keeps its number; the history says what changed, who and when. The order page has Change the items (the New order item picker and line cards) and the tour `page-order` a step for it (version 2). Tests: `RetailOrderItemChangesIT`, `order-items.dom.test.tsx`, `orders-state.test.ts`.
- **Built (installable app and Web Push, ADR-050):** migration `V68`: `push_subscriptions`
  (one row per user per device, RLS; the endpoint is unique in the tenant so a shared device moves to the next person) and the outbox channel CHECK
  widened to `push`. The PWA manifest is `BMS`, id and start page `/staff`, standalone, PNG icons 192 and 512 and maskable (`frontend/public/icons/`);
  `frontend/src/install/` captures `beforeinstallprompt` and offers the install after sign-in, on the sign-in, invitation, forgot and reset password
  pages (iPhone: the Safari steps, each with a small drawing beside its words since issue #448), waved away for 30 days per device; the page `/staff/notifications` (Notifications and app) has the permanent install
  control, the switch Notifications on this device (permission asked only on the tap) and the person's devices, with a page tour and a step in the
  welcome tours. `public/push-sw.js` (loaded into the generated worker) shows the notification and opens the route on a tap. Backend, in
  `core.notifications`: VAPID keys from `BMS_PUSH_VAPID_PUBLIC_KEY`, `BMS_PUSH_VAPID_PRIVATE_KEY`, `BMS_PUSH_SUBJECT` (off when unset; ephemeral in dev and
  tests), RFC 8291 encryption and RFC 8292 signing on the JDK alone (`WebPushCrypto`), a `PushSender` port (real and a recording fake), an allow-list of
  push service hosts, routes `GET /notifications/push/config`, `GET` and `POST /notifications/push/subscriptions`, `POST .../unsubscribe`, `DELETE
  .../{device_id}`, the `push` outbox channel with its own 10 second sender and the outbox's retry and expiry, and the kernel event `PushNotice` that
  modules publish: a sale voided and a debt written off alone (retail sales), an approval limit raised or two-step sign-in no longer required for all
  (tenancy settings), stock dispatched to your branch (retail stock) and an approval waiting for you (approvals). Runbook `docs/runbooks/push-notifications.md`.
- **Built (everyday push notices, turn-on card and low-stock minimums, issue #412, ADR-057):** migration `V76` (directly after `V75`, the operator screens of #413, on main): `retail_stock_minimums` (an item's level for every branch, branch null, and a branch's own level, which wins; `UNIQUE NULLS NOT DISTINCT`) and `retail_notice_runs` (one row per tenant, kind and day once a daily notice was prepared; append-only). `frontend/src/push/TurnOnCard.tsx` and `turn-on.ts`: the card "Get told when something needs you?" in the staff area, the first time the installed app is opened or right after the install is accepted (`install.ts` `accepted`), only while the push state is `off`; Turn on is the tap (`push.turnOn()` asks for permission before awaiting anything); Not now asks again after 3 days, at most 3 times; on or blocked ends it (`localStorage` `bms.push.ask`). The Notifications and app page is unchanged. New audiences on the kernel `PushNotice`: `BranchStaff` (roles given for that branch itself, not the administrators' branchless roles) and `AnyOf`. New notices: `retail.order_new` (an order with a source other than walk-in, to the shop's `retail.order.manage` staff, not the taker), `retail.order_out_for_delivery` and `retail.order_delivered` (to the taker, unless they did it), `retail.low_stock` (after stock leaves a branch, inside a savepoint, by `LowStockRule` in `retail.stock`: below the level that applies or under 14 days of cover at the 30-day pace, whichever first, with words naming the rule; to that branch's `retail.stock.read` holders and the administrators, once per item, branch and day), `retail.credit_due` (administrators) and `retail.credit_due_branch` (holders of `retail.sale.all_sellers` at that branch, the Supervisor of #404) each morning from 08:00, and `retail.daily_summary` (administrators, counts of sales per shop, never an amount) at the business's time; the job `retail.daily-notices` (a cron on every quarter hour, nothing without push keys) prepares each daily notice once (`retail_notice_runs`); the summary time is a quarter hour up to 23:30, a summary missed while the server was down goes the next morning as `retail.daily_summary_late`, the credit notice only between 08:00 and 12:00, the pace read from completed sales as Stock health reads it, an empty shelf worded "out of stock". The tenant settings group `notifications` (five switches, all on, and `daily_summary_time`, 20:00), `GET` and `PUT /settings/notifications`, applied by `core.notifications` per template (`NotificationSwitches`; the public port `PushPreferences`); the screen Business set-up > Notifications for the business (`/staff/setup/notifications`, page tour `page-business-notifications`). Routes `GET` and `PUT /retail/products/{product_id}/stock-minimum` (`retail.stock.read`; `retail.catalogue.manage`) and `PUT .../branches/{branch_id}` (`retail.catalogue.manage` or `retail.stocktake.commit` at that branch); "Warn me when stock is below" on the item's page and on each Stock health reorder row; the reorder list uses the same rule (`min_qty`, `low_by`, `low_words`). `retail.stock` now depends on `core.notifications`, `retail.sales` on `core.jobs`. `OpenApiSnapshotIT` also writes the generated contract to `target/failsafe-reports/openapi.generated.json`, so a machine without Docker can take it from a CI run's reports. Tests: `PushEverydayNoticesIT`, `RetailStockMinimumsIT`, `RlsIsolationIT`, `turn-on-card.dom.test.tsx`.
- **Built (items with sizes and colours, issue #384, FR-RET-51, FR-RET-52, ADR-051):** migration `V69`: `retail_product_families` (the item: name, base code, category, unit; forced RLS) and four nullable columns on `retail_products` (`family_id`, `variant_size`, `variant_colour`, `variant_sort`) with an all-or-none check and a partial unique index of one variant per size and colour in an item. **A variant is a product**, so every sale, restock, transfer, stock-take, usage, return, price list, pack size and report path is per variant unchanged; every existing product keeps the four columns null and is its own single default variant (nothing copied, nothing reads differently, `MigrationOrderIT` proves it on data at V67). Routes `POST /retail/product-families` (every combination of the sizes and colours chosen once, codes `BASE-SIZE-COLOUR`, the item's prices unless a variant names its own), `GET` and `PATCH /retail/product-families/{id}` (the item's stock summed per branch over the caller's branches; a rename rewrites every variant's description, a new category moves every variant), `POST /retail/product-families/{id}/variants` (more sizes, a combination it has skipped) and `POST /retail/products/{id}/family` (an item without variants given sizes keeps its id, code, prices, stock, history and on-sale state as one of them), `POST /retail/product-families/join` and `/{id}/join` (items kept apart, one per size, become the sizes of one item, keeping ids, stock and history); an item's name is kept once per unit against items with and without sizes (ADR-044 one level up, `duplicate_reason` on the item); a product response carries `variant` only for a variant, and its name, category and unit follow its item (422 `variant_name_follows_item`, `variant_follows_item`). The sales and restock summaries group an `item` as all its variants and split it by a `variant` level offered only to a tenant with an item with sizes; an item's id as `product_id` or `item_id` finds every variant's lines. No permission added. Tests: `RetailVariantsIT`, `VariantGridTest`, `RlsIsolationIT`, `MigrationOrderIT`. Item photos are the second slice of the issue.
- **Built (item photos, issue #384 slice 2, FR-RET-58, ADR-053):** migration `V71`: nullable `photo_document_id` on `retail_products` and `retail_product_families` (composite foreign keys to `documents`). A photo is a `core.documents` upload of subject type `retail.item_photo` through `prepareImage` (PNG, JPEG or WebP by content, never SVG, 5 MB, re-encoded, 1200 px), in the private bucket under the tenant's prefix; `retail.catalogue` now depends on `core.documents`. Routes `POST` and `DELETE /retail/products/{id}/photo` and `/retail/product-families/{id}/photo` (`retail.catalogue.manage`), and `GET /retail/photos/{photo_id}` (`retail.stock.read`) through the new `Documents.readImage`, from the same origin because the content security policy allows no image host, private and cached a day, not audited per read. A product's `photo_id` is its own photo and `item_photo_id` its item's; a 240 px small copy (`retail.item_photo_small`) is stored at upload and served with `?size=small`; `readImage` holds no connection while it fetches. The PWA shows it in the item list, the item's page and every item picker (sale, restock, stock), with Add a photo on an item's edit form and page (`item-photo.tsx`); `page-sizes` tour version 2. Tests: `RetailItemPhotosIT`, `item-photo.test.ts`.
- **Built (retail roles slice 1, issue #399, FR-RET-59 to FR-RET-61, ADR-055):** migration `V73` (renamed to the next free number at merge if another lands first): the roles `retail_storekeeper` (Storekeeper: `retail.stock.read`, `retail.stock.transfer`, `retail.stocktake.commit`, `retail.usage.report`; sends, receives, counts and reports usage at its own branches, never sells, sets or sees prices, handles money or sees cost; no `sell_minor` in any response and no price history for it, the owner's decision of 21 October 2026) and `retail_purchasing` (Purchasing: `retail.stock.read`, `retail.purchase.create`, given on top of another role to chosen people; by default only administrators restock; without `retail.profit.read` a buyer reads only the restocks, orders and receipts they recorded; a new selling price on a restock or a goods receipt also needs `retail.price.edit`, since it applies in every shop), the value of the stock at the selling price left out without `retail.profit.read` like the cost (valuation, stock summary, stock health), and the check `user_role_assignments_admin_every_branch` (an administrator always covers every branch; V73 widens any limited administrator row first). Staff per shop or store is the existing branch scope (one assignment per branch, ADR-017), no new table and no new permission; no check reads a role name, so a role added to an administrator never narrows them. The Users screen asks where a person works with a tick box per shop and store (or every one; taking off the last leaves nothing chosen, which is not saved) and offers Purchasing as a tick box on the invitation; the `page-users` tour has the step. Tests: `RetailRolesIT` (a signed-in user of each role against the retail routes, allowed, 403 or outside the branch; an administrator with no branch assignment sees every branch, report, Controls, profit and cost, also when given a storekeeper role at one store), `PermissionMatrixIT` (two new columns), `MigrationOrderIT`. Slice 2 (sellers see only their own figures) is the next pull request.
- **Built (retail roles slice 2, issue #399, FR-RET-62, ADR-055):** migration `V74` (renamed to the next free number at merge if needed): the permission `retail.sale.all_sellers` (administrators). Without it in every branch the figures cover, the sales list and its grouped summary are the caller's own sales (filtered on `created_by` in the query; another `seller_id` and the salesperson level are 403), one sale and its payments are 404 when someone else made them, and the owner dashboard, the sales analysis and both order reports (delivery fees, cancellations) are 403; the debts still owed (`owing`, `overdue`, a credit sale that still owes) are listed and open whoever made the sale, so the till can take a buyer's payment, while the grouped summary stays the caller's own; imported history (`created_by` is the import actor) is the owner's only. The Retail home shows a seller "Your sales" (today and 7 days, `own-sales.tsx`) instead of the dashboard; the Sales analysis and the orders report tiles are the owner's; the home page tour is version 2 with the step. The cash book summary, credit control, customer ageing and the order list are unchanged and named in ADR-055 for the owner to decide. Tests: `RetailSellerFiguresIT`, `RetailSalesGroupingChainIT`, the report ITs given the permission. Since the owner's decisions of 21 October 2026, also in `V74`: the cash book left the Sales role for the role `retail_till` ("Can close the till and do the banking", permission `retail.till.close`, given per person, administrators everywhere; the daily cash summary, expected banking, savings suggestion and recording savings or banking need it), a buyer's account stays open to sellers without naming the colleague who made a sale or took a payment, an order shows its values to every seller but links a sale only for its seller, and the Supervisor role (`retail_supervisor`) sees every seller's sales, totals, dashboard and analysis at its branches with no cost; the Users screen and the invitation have a "Who can do what" card built from the role words (tours `page-users` v4, `page-invite-user` v3). Tests also `RetailOwnerDecisionsIT`.
- **Built (launch hardening B, issue #425, ADR-058):** host side only, no migration and no application change. `deploy/monitor.sh`
  (`bms-monitor.timer`, every five minutes) posts to the Telegram operator chat through the existing `BMS_TELEGRAM_*` values when the site
  or a tenant sign-in page is down, a db-scheduler task failed, is stuck or overdue, disk passes 70 percent, a certificate expires within
  14 days, a backup failed or is older than 26 hours (on the host and in R2), or the restore drill failed, is older than 9 days or ran over
  the 2 hour RTO; once on start, every six hours while it lasts, once on recovery, and it pings an external heartbeat. The external uptime
  monitor is a design for Hillary to set up. `restore-drill.sh` now runs on `bms-restore-drill.timer`, times itself and records its result
  on the host, in a history file and in R2; `backup.sh` records a failed run and hands retention to the bucket with
  `BACKUP_RETENTION_ON_HOST=false` once the R2 lock and lifecycle rules are on (provider steps left for Hillary). Tests:
  `bash deploy/tests/monitor-test.sh` (CI, with shellcheck). Runbooks `docs/runbooks/monitoring-and-alerts.md`,
  `restore-from-backup.md` sections 5 to 7, `security-incident.md` and `support-and-on-call.md` (owner placeholders to fill).
- **Built (launch hardening A, issue #426, ADR-059, revised after the review of #433):** migration `V78` (V77 is #421's):
  `sign_in_address_failures`, the five-failure count and lock of every sign-in address that matches no account, keyed by
  its SHA-256, so it locks exactly as an account across restarts (`SignInAddressFailures`, nightly purge
  `core.identity.sign-in-failure-purge`). `core.identity`: `AuthThrottle` (was `ResetThrottle`) counts only FAILED
  requests per client IP on every public staff and operator sign-in, two-step, invitation and setup route
  (`BMS_AUTH_SIGN_IN_FAILURES_PER_IP`, 300 in 10 minutes; carrier NAT), keeps the reset buckets (20 an hour per IP, 10 an
  hour per address, 30 link uses in 10 minutes) and the second factor per account (30 in 15 minutes), never evicts a
  bucket that remembers anything, and sends Retry-After with every 429; there is no in-memory per-address sign-in
  budget. A completed reset, `POST /users/{user_id}/unlock` (tenant admin) and
  `POST /platform/tenants/{tenant_id}/users/{user_id}/unlock` (operator, with a reason) lift a lock, audited
  `core.auth.unlocked`; nothing unlocks an operator. `Passwords` runs at most `BMS_AUTH_ARGON2_MAX_CONCURRENT` (4)
  argon2id hashes, answers 503 `sign_in_busy` with Retry-After 2 to 5 seconds after two seconds, and refuses to hash
  inside a transaction: sign-in, the step-up password, invitation acceptance, the reset and the operator's first password
  read, hash with no connection held, then write. `core.notifications`: with `BMS_ENVIRONMENT=production` (read as SMS
  and Pesapal read it; `deploy/compose.yml` defaults it to production) the API refuses to start without SMTP.
  `kernel.LogRedaction` redacts secrets (keys inside longer names too, whole quoted values), Basic and Bearer
  credentials, emails, NINs in any case, Uganda numbers (`%2B`, spaced landlines) but not labelled amounts, times or
  ids, and name keys including `name=`, in linear time; PostgreSQL logs tersely; both compose files cap container logs.
  Tests: `AuthThrottleTest`, `PasswordsBoundTest`, `SignInLimitsIT`, `PasswordResetIT`, `SmtpProductionGuardTest`,
  `LogRedactionTest`, `ApiExceptionRetryAfterTest`.
- **Built (the Owner of a business, issue #442, FR-IAM-18, ADR-062):** migration `V79`: `users.is_owner` (one per business, the
  partial unique index `users_one_owner`; the check `users_owner_is_staff`; the triggers `users_owner_is_admin` and
  `user_role_assignments_owner_keeps_admin`), backfilled to the active administrator with the activated sign-up application's email,
  else the earliest active administrator (a business with none is left without an Owner, a WARNING in the migration log), and the
  operator reads `platform_tenant_overview` and `platform_tenant_admins` now naming the Owner. Not a role: the Owner holds exactly
  the tenant admin's permissions, so the matrix and every permission check are unchanged; `core.identity.internal.Owners` adds the
  rules. Only the Owner invites or promotes an administrator and changes, deactivates, resets the second factor of or re-invites
  another administrator (403 `owner_only`); nobody inside the business does any of it to the Owner (403 `owner_protected`), and the
  Owner cannot drop their own administrator role (422 `owner_must_transfer_first`). `POST /users/{user_id}/transfer-ownership`
  (the Owner's code, else password, an optional reason; refused for 24 hours after the Owner's email, phone, password or two-step sign-in changed,
  `users.contact_changed_at`, `users.second_factor_changed_at`) and `POST /platform/tenants/{tenant_id}/users/{user_id}/make-owner` (the operator's code and a
  reason) hand ownership to an active administrator only, audited on both sides for the operator; the Owner's own email or phone
  change needs the same step-up and tells the old address (`identity.contact_changed`); `POST
  /platform/tenants/{tenant_id}/users/{user_id}/invitation` (code and reason) sends a new link to an invited Owner who never joined.
  One advisory lock per business (exclusive to make or move the Owner, shared for every owner rule) serialises them. The first administrator of a new
  business is its Owner from creation; a business with no Owner keeps the rules of before. `User.is_owner`, `Me.is_owner`,
  `TenantAdmin.is_owner`. The PWA: the Owner badge, the owner-only actions hidden, Transfer ownership (`transfer-owner.tsx`), the
  two-step prompt for the Owner, the Owner line in "Who can do what", `page-users` tour v5; on the Business page the badge and Make
  the owner (`page-operator-business` v2). The OpenAPI snapshot is regenerated outside this Mac. Tests: `TenantOwnerIT`,
  `MigrationOrderIT`, `state.test.ts`, `users.dom.test.tsx`.
- **Built (owners change their own plan, issue #472, FR-SUB-03 to FR-SUB-07, ADR-073):** migration `V90` (claimed in the agreed merge order; `V80` to `V87`
  are merged, `V88` and `V89` merge before it): `subscription_module_requests` and `subscription_scheduled_changes` (forced RLS), `subscriptions.ended_on`,
  `platform_end_subscription`, the operator's definer reads and the job's list, and `app_resolve_tenant_status` answering `ended`.
  `/plan` on the business's host: the Plan and modules card (`core.settings.manage`), and, for the Owner only (ADR-062), asking for a
  module (switched on at once when the plan includes it, otherwise the operator decides), stopping one and cancelling (owner step-up;
  the day after the last day paid for, or at once when nothing is paid ahead; at least one module kept) and undoing before the day;
  `/plan/status` for every member of staff. The operator's `/platform/module-requests` (approve with a reason and the operator's code,
  optionally moving the plan; decline with a reason the Owner sees) and `/platform/scheduled-changes`. The job
  `core.platform.plan-scheduled-changes` (every quarter hour, `bms.platform.plan-changes-job-enabled`, off in `IntegrationTest`)
  applies what is due once per business. After a cancellation's end date writes answer 423 `subscription_ended`; reads, sign-in and
  the exports still work; nothing is deleted. Business set-up has the Plan and modules card with plain-words confirms, and the staff
  area the end-date banner and the read-only notice. Tests: `SelfServePlanIT`, `RlsIsolationIT`, `EmailLayoutTest`.
- **Built (a fuller shop Home, issue #485, FR-UXN-06, ADR-076):** no migration, no permission. `GET /retail/reports/home`
  (`retail.sale.read`, optional `branch_id`) answers every shop figure on Home in one read: today's sales, the takings split by cash,
  mobile money and any other way that took money, credit owed (total, customers, oldest due date), the top five items today and this
  week, a line per shop over two or more, the low stock count and the open orders; the money parts need `retail.sale.all_sellers`
  over the branches covered and are left out otherwise. Built from the dashboard, credit control and sales analysis queries
  (`HomeService`). The Shop card and the cards Takings today, Credit owed, Top items and Each shop today (`home-cards.tsx`), each
  linking to its full page; tour `page-staff-home` v2. Tests: `RetailHomeSummaryIT`, `home-cards.dom.test.tsx`. Notes:
  `docs/specs/retail-ui-notes.md` (Shop Home summary cards).
- **Built (send feedback from inside the app, issue #447, FR-FBK-01 to FR-FBK-05, ADR-065):** migration `V80` (directly after `V79`):
  `feedback_items` (forced RLS, `SELECT` and `INSERT` only for the
  application role) and the definer functions `platform_feedback_list`, `platform_feedback_tenant`, `platform_feedback_set_status` and
  `platform_feedback_set_linked_issue`,
  each refusing a tenant-bound transaction. The new module `core.feedback`: `POST /feedback` (multipart: kind idea, problem or question,
  a message of up to 2000 characters, the page path, an optional PNG or JPEG screenshot of up to 2 MB stored as a core document of
  subject type `core.feedback`; the user agent from the request and the version and git SHA from the server's own build), `GET /feedback`
  (the caller's own, with status and reply) and `GET /feedback/{id}/screenshot`, all `@AuthenticatedEndpoint(kind = "staff")`, no
  permission added; ten an hour per person (429 `feedback_rate_limited`, counted in the database under an advisory lock); each item
  queues the `feedback.new` email to every active platform operator (the `platform_users` addresses, as the onboarding alert) in a
  savepoint, so email never blocks a send. The operator routes are in `core.platform` through the public port `FeedbackInbox`:
  `GET /platform/feedback` (newest first, `status` and `since` filters, with the linked issue), `PUT /platform/feedback/{id}/status`
  (forward only, new, seen, done, with a reply the sender sees, audited `platform.feedback.status_changed`; a reply alone keeps the
  status date), `PUT /platform/feedback/{id}/linked-issue` (the issue opened for the item, set or cleared, audited
  `platform.feedback.issue_linked`) and `GET /platform/feedback/{id}/screenshot` (the business bound
  through `TenantContext.callAs`). The PWA: the Feedback tab of the staff menu and Send feedback on the Help sheet (there is no phone
  More menu; Help is how a phone reaches it), `/staff/feedback` (the form and What you sent), the operator's Feedback tab
  (`/platform/feedback`), the tours `page-feedback` and `page-operator-feedback`, and a Feedback step in `admin-welcome` v18,
  `retail-sales` v12 and `staff-welcome` v6. Tests: `FeedbackIT`, `RlsIsolationIT`, `ModularityTest`, `feedback-state.test.ts`,
  `feedback.dom.test.tsx`, `tours-operator.test.tsx`.
- **Built (approval rules, issue #343, ADR-091, FR-APR-09):** no migration, no permission: two keys of the tenant settings row
  (`approval_switches` and the existing `approval_thresholds_minor`). `GET` and `PUT /settings/approvals` list every registered
  `ApprovalAction` of a module the business uses (returns and purchase orders while their capability is on; a return's void follows
  returns) with its switch and amount; saving needs `core.settings.manage` and the administrator's own code or password
  (`PolicyChangeGuard.requireProof`), is audited as `core.settings.approval_rules_changed`, refuses switching a lending action off
  (422 `approval_always_on`) and pushes `core.approval_policy_weakened` to the other administrators when a rule is loosened.
  `PATCH /settings` needs the same proof to change an amount. `ApprovalAction` gains `switchable`, `onByDefault`, `listed`,
  `offered` and `label`; `Approvals.rule` is the one reading of a rule; the retail modules pass the rule they read to `Approvals.request(request, rule)`
  so it is read once (`RetailApprovalRaceIT`, every retail kind), a caller that read none has a switched-off action run at once
  (a write-off still flagged and told), every caller answers by `executed()` (`ApprovalOutcomeGuardTest`), and a `PATCH /settings`
  amount that loosens a rule, 0 switching one off included, is told (`ApprovalAmountsChanged`, `PushPublishersIT`).
  Defaults keep what every business had: lending always asks, returns and purchase orders ask while their amount is above 0,
  write-offs ask by ADR-043's rule. Three new retail actions, off by default, with an administrator as checker: `retail_sale_void`
  (`POST /retail/sales/{id}/void`), `retail_stock_correction` (`POST /retail/stock/corrections`, valued at the branch's average) and
  `retail_expense` (`POST /retail/expenses`), each answering 202 `ApprovalWaiting` while it waits and running in the maker's name
  on approval. A price below cost is not an approval. Screens: Business set-up, Approval rules (`/staff/setup/approvals`, tour
  `page-approval-rules`), "Sent for approval" on the void, the correction and the expense, the action in words on Approvals, and
  the returns Hint on Retail settings links to the returns row (#575). Tests: `ApprovalRulesIT`, `RetailApprovalRulesIT`,
  `ApprovalsIT`, `RetailReturnsIT`, `RetailSaleWriteOffIT`, `RetailTradePurchasingIT`, `approval-rules.dom.test.tsx`,
  `expenses-waiting.dom.test.tsx`, `stock-correct.dom.test.tsx`, `sale-actions.dom.test.tsx`, `return-new.dom.test.tsx`.
- **Built (the kind of business, issue #424, ADR-092, FR-RET-33):** migration `V103`, no new permission. The Retail settings
  profile becomes "What kind of business is this?": `shops` (Shops only, the default), `stores_and_shops`, `wholesale` and
  `wholesale_and_shops`, each a preset of the six switches it governs (`BusinessTypes` in `retail.manifest`: packs, price lists,
  customer accounts, documents, purchasing, the two-step move; branch roles and returns are left as they are); the page says that
  each branch is set as a store or a shop under Branches. `V103` renames stored profiles without changing a switch, judged from
  the switches (`wholesale_and_shops` with a wholesale tool on; else `trade` to `stores_and_shops`, and `shop` to
  `stores_and_shops` with the two-step move on, else `shops`) and adds `profile_groups` to `platform_tenant_overview`, read through
  the module's group as `business_type`, shown on the operator's list and Business page. The grouped menu gains a Wholesale group between
  Money and Lending while a wholesale tool is on (read from the switches, never the kind), the Retail page lists those tiles under
  a Wholesale heading, `page-retail-settings` v5, and the new `capability-wholesale` tour. Plans, pricing, brochure prices,
  Pesapal and choosing the kind at sign-up are not touched. Screenshots `docs/ui/business-type-424/`. Tests
  `RetailBusinessTypeIT`, `RetailCapabilityGroupTest`, `RetailBranchRolesIT`, `RetailTwoStepTransfersIT`, `capabilities.test.tsx`,
  `side-menu.dom.test.tsx`, `operator.dom.test.tsx`, `tours.test.tsx`.
- **Built (one grouped menu, issue #564, ADR-086, FR-UXN-04):** PWA only, no migration, no API change. `staffMenu` in
  `frontend/src/areas/staff/menu.tsx` builds the groups (Home, Today, Stock, Money, Lending, Business) with the permission checks,
  `useMenuCounts` reads the open orders and "N low" from Home's summary, and `SideNav` and `SideMenu` (`components/side-menu.tsx`)
  draw them with `MenuShop` (`branch-picker.tsx`) at the top and `StaffMenuFoot` (`route.tsx`) at the foot: a fixed 264px sidebar
  from 720px, the drawer from the menu button at the top left of the top bar on a phone. The retail shortcuts, their cap of five,
  `RetailMenuGroup`, the folding sidebar and the foldable Shop and Lending sections are gone; `staffAreas` is the flat list the More
  page, the feature search and the tabs read. Tours `admin-welcome` v27, `retail-sales` v23, `staff-welcome` v13,
  `page-staff-home` v4. Picture `docs/ui/grouped-menu-564/menu.html`, screenshots in `docs/ui/grouped-menu-564/`. Tests:
  `side-menu.dom.test.tsx`, `side-menu-shop.dom.test.tsx`, `menu-icons.test.tsx`, `retail/permissions.test.ts`.
- **Built (How do I...?, issue #598, owner decision D51, ADR-095, FR-HLP-01 to FR-HLP-05):** PWA only, no migration, no API
  change, no permission, no AI call. The help corpus is `frontend/src/help/articles/*.md` (20 articles from the questions pilots
  asked, by job: Sell, Buy stock, Move stock, Count stock, Items and categories, Branches and stores, Users and invitations,
  Money, Loans), bundled through the `bms-help-corpus` Vite plugin as `virtual:help-corpus` with its content-hash `REVISION`
  (`frontend/src/help/corpus.ts`: parsing, `articlesFor` by the tour step rules, `searchArticles`, `articleForScreen`). Each step
  has a 412x900 WebP in `frontend/public/help/` with its caption drawn on it, taken with fabricated data by
  `frontend/scripts/help-screenshots.mjs` (`docs/ui/how-do-i-598/README.md`) and precached by the service worker. Screens: How
  do I...? on the Help sheet, `/staff/help` (search, the jobs) and `/staff/help/$articleId`, tours `page-help` and
  `page-help-article`, a How do I? link beside the breadcrumbs of a screen an article names (`HowDoILink`), and Not answered?
  Ask us opening Feedback as a question with the search typed (`/staff/feedback?ask=`). The Help and feedback step of
  `admin-welcome` v28, `retail-sales` v24 and `staff-welcome` v14 names it. A new or changed screen whose task an article
  explains updates the article and its pictures. Tests: `corpus.test.ts`, `help.dom.test.tsx`, `tours.test.tsx`.
- **Built (Ask BMS, issue #20, owner decision D52, ADR-096, FR-HLP-06 to FR-HLP-10):** migration `V108` (`help_ask_questions`,
  the question log, a hash and never the text; `help_ask_tickets`, the per-person hourly limit and the business's daily cap,
  taken under the business's advisory lock before the work; `help_ask_spend`, every provider call reserved before it and
  settled after; `help_ask_answers`, the answer cache; all forced RLS; `help_ask_platform_spend`, the DeepSeek monthly sum), the module `core.help` and `GET` and `POST /help/ask` (authenticated
  staff, no permission added). The server reads the very articles of `frontend/src/help/articles` (a Maven resource; the API
  image gets the folder as the `help` build context) with the app's revision; PostgreSQL full-text search picks up to three the
  asker's permissions, modules and locale allow, and says "a job for another role" when the best match is one they may not
  read; the question is scrubbed of phones, IDs, emails, long figures and names first; a small model phrases the answer at
  temperature 0 as JSON citing one of the articles it was given, tried Gemini, then Groq, then DeepSeek (paid, last resort,
  monthly cap, skipped until both its prices are set) through `core.ai.LlmClientFactory` (the gateway's adapter). Claude is
  never used: three provider names only, one host each in a constant allow-list (`AskProviders.ALLOWED`), and startup refused
  for anything naming Anthropic or Claude (`NeverClaudeTest`, which also scans what Ask BMS reaches and holds `core.help` to the neutral port of `core.ai`). Removing a closed business deletes its cached answers and tickets (`HelpErasure`). The cache
  key is the prompt version, the provider chain, the corpus revision, the permission matrix version, the locale, the roles, the
  permission set, the modules and the normalised question; only a confident model answer is kept. Off unless the business's id
  is in `BMS_HELP_ASK_TENANTS` (demo businesses first); keys are environment values only (`docs/runbooks/ask-bms.md`); with no
  key, all failing or the daily cap (`BMS_HELP_ASK_DAILY_QUESTIONS`) the matching articles are shown. Screen: the Ask BMS box on
  How do I...? (`areas/staff/help/ask.tsx`), the answer with its article linked, Not answered? Ask us carrying the question.
  Deferred: thumbs up and down, the "show me" tour launch. Tests: `AskBmsIT`, `NeverClaudeTest`, `QuestionScrubberTest`,
  `HelpArticlesTest`, `RlsIsolationIT`, `ask.dom.test.tsx`, `corpus.test.ts`.
- The isolation, boundary, ledger, API, actuator, route permission and contract tests run in
  `mvn verify`; CI runs them on every pull request. Staging runs on a shared ARM64 host behind a
  Cloudflare Tunnel and pulls every green build of `main` from a `staging` pointer tag; hosts are
  `{slug}-bms-staging.rincoltech.com` and `bms-staging.rincoltech.com` (ADR-018). The production
  VM is not provisioned yet; its deploy job skips with a notice until it is
  (`docs/runbooks/provision-host.md`).
- **Built (new retail shops start with default units, issue #618, ADR-098, FR-RET-01):** migration `V109` (directly after `V108`): every
  retail business, not closed, with no unit at all gets Piece, Pair, Set, Box, Pack, Kg, Litre and Metre; a business with any unit is
  left alone. From now on a business gets the same units when retail is switched on, at creation or later (the operator, the Owner's
  request, the scheduled change), in the switching transaction: the new registry `core.tenancy.ModuleSetUp`, called by `core.platform`
  for each module just switched on, bound to the tenant (the operator's module switch now runs under `TenantContext.callAs`), and
  `DefaultUnits` in `retail.catalogue` through the existing unit insert, skipping a name the business has in any case, each unit audited
  `retail.unit.created` by the system. No API, permission or screen change (the inline Add a new unit is #616). Tests:
  `RetailDefaultUnitsIT`, `MigrationOrderIT.defaultUnitsMigrationFillsOnlyRetailBusinessesWithNoUnit`.
- **Built (Set up your shop, issue #625, ADR-100, FR-RET-01, units spec section 9 step 5):** migration `V111` (directly after
  `V110`, #624, PR #627): `retail_units` gains `pack_size`, `pack_of_unit_id` and `pack_purpose`, the usual size of a
  unit that is a pack in a smaller unit and what it is for (`buy`, `sell` or `both`, the values and Used for words of a pack's
  `purpose` on an item, #624), all three or none, never a price; the units routes take and return it as
  `usual_pack`. The screen `/staff/retail/catalogue/set-up` (screen `shopSetup`, the permissions of Categories and Units), offered
  from the first-run card (#615) and the Catalogue home, asks "What kind of shop is it?" from eight bundled starting sets
  (`shop-starters.ts`), lists their categories, units and packs ticked, and adds what stays ticked through the existing create
  routes (`shop-setup-state.ts`); a name the shop has is skipped, a unit with no usual size gets one, and a second run adds
  nothing. The pack form of an item fills in the unit's usual size and its Used for (`purposeForUnit`), while a pack added through the API with no `purpose` is for `both`, as #627 set (issue #637): one pack idea, one `Purpose` type and one `PURPOSES` list (`catalogue-packs.tsx`). Help articles `set-up-your-shop` and `add-a-unit` (How do I add a unit?, which a tester asked for), page tour `page-shop-setup`.
  Tests: `RetailCatalogueManagementIT.aUnitKeepsItsUsualPackSizeAndUse`, `MigrationOrderIT`, `shop-setup-state.test.ts`,
  `shop-setup.dom.test.tsx`, the help corpus test.
- **Built (packs follow-ups of the #628 review, issue #637, ADR-100 note):** no migration. A pack added through the API with no
  `purpose` is for `both` again, as #627 set; the pack form sends the unit's usual Used for itself, and the CSV import adds a pack by
  the same rule. `PATCH /retail/units/{id}` takes `clear_usual_pack`, and the Units page has **Usual pack** on a unit's row: a sheet
  (`unit-usual-pack.tsx`) to change what it holds, the number and Used for, or Remove usual pack; items keep their packs. A usual
  pack is one level only (422 `unit_is_a_pack`, `unit_counted_by_pack`), so Roll of Metre and Metre of Roll can never both be kept.
  Set up your shop reads the units back after a `duplicate_unit`, so a unit another phone added meanwhile is not called missing,
  and leaves out a pack of a unit that is a pack itself. `PURPOSES` is the one list of purpose values, labels and words
  (`asPurpose`). Help `add-a-unit` step 6. Tests: `RetailSellingPacksIT.aPackAddedWithNoPurposeIsForBothWhateverItsUnitsUsualPurpose`,
  `RetailSellingPacksIT.theImportAddsAPackByTheSameRulesAsAddingOne`,
  `RetailCatalogueManagementIT.aUsualPackIsChangedOrClearedAndIsOneLevelOnly`, `usual-pack.dom.test.tsx`,
  `shop-setup-state.test.ts`, the help corpus test.

## Reading order

1. This file, then `PROCESS.md`.
2. `docs/specs/lending-mvp-scope.md`: what is built first, in what order, and the open
   questions for the pilot tenant.
3. `docs/sdd/01-introduction.md`, `02-system-context.md`, `05-architecture.md`.
4. ADR-001 to ADR-004 in `docs/adr/`: the four decisions everything else rests on.
5. For the item you are building: its requirements in `docs/sdd/03-functional-requirements.md`,
   its tables in `06-database-design.md`, its endpoints in `07-api-design.md`, its
   permissions in `08-security-design.md`, its tests in `15-test-strategy.md`.

## Folder structure

```
bms-platform/
├── AGENTS.md            this file
├── CLAUDE.md            Claude Code specific notes (points here)
├── PROCESS.md           work item hierarchy, branches, pull requests, releases
├── README.md            what it is, quickstart, doc map
├── Makefile             dev, seed, test, lint, fmt, migrate, build, openapi
├── docker-compose.yml   local stack: PostgreSQL, migrate, API, web, proxy
├── .env.example         every environment variable, placeholders only
├── backend/             Spring Boot API, modules by package (ADR-010), Flyway migrations
├── frontend/            the React PWA: staff area, member area, platform console (ADR-009)
├── deploy/              host-side files: compose.yml, deploy.sh, backup.sh, restore-drill.sh, monitor.sh, systemd units, Caddy, SQL (SDD ch. 9)
├── scripts/db-bench/    throwaway PostgreSQL benchmark at 25 times the data (ADR-028); never production
├── fixtures/            fabricated test data only (pilot register sample)
├── docs/
│   ├── workspace.dsl    Structurizr C4 model; wires in sdd/ and adr/ as one site
│   ├── sdd/             System Design Document, chapters 01 to 15
│   ├── adr/             Architecture Decision Records
│   ├── specs/           MVP scope, pilot data dictionary
│   ├── api/             OpenAPI snapshot (generated) and contract drafts
│   ├── runbooks/        deploy, rollback, restore, monitoring, incidents, support, onboarding a tenant
│   ├── diagrams/        non-C4 diagram sources
│   └── meetings/        technical meeting notes
├── .github/             CI workflows and guard scripts
└── .claude/             Claude Code settings and hooks
```

## Module map and where each spec lives

Logical modules (ADR-002). Each is a package under `com.rincoltech.bms` declared with
`@ApplicationModule` in its `package-info.java`, whose `allowedDependencies` are checked by
`ModularityTest` (chapter 5 sections 5.4.2 and 5.4.6). A module's public API is its base
package; `internal` is closed. Tables of a vertical are prefixed with its key (`lending_...`).

| Module | Owns | Requirements (ch. 3) | Tables (ch. 6) | Endpoints (ch. 7) |
|---|---|---|---|---|
| `core.kernel` | Money, rates, clock, tenant context, principal and route declarations, errors | 3.4 R-ROUND | none | none |
| `core.tenancy` | Tenants, plans, subscriptions, modules, settings, branches | TEN, BR | 6.4, 6.5 | 7.11.3, 7.11.4 |
| `core.identity` | Users, credentials, sessions, roles, permissions, own tour progress | IAM | 6.4, 6.5 | 7.11.2, 7.11.4 |
| `core.audit` | Audit log | AUD | 6.5 `audit_log` | 7.11.4 |
| `core.approvals` | Maker-checker | APR | 6.5 `approval_requests` | 7.11.5 |
| `core.platform` | Platform console: tenant creation, modules, subscriptions; the price book, payments entered by the operator and the Business page overview (ADR-056, issue #407); the Owner's own plan, the module requests, the scheduled changes and their job (ADR-073, issue #472, migration `V90`); subscription reminders, overdue with grace, the list for suspension, receipts and the Owner's Plan and billing page (ADR-075, issue #480, migration `V92`); the Owner's billing contact, the request to close the business, its removal through every module's `PersonalDataEraser` and the list of businesses with no Owner (ADR-090, issue #571, migration `V102`) | TEN, SUB, SPM | 6.4, 6.5 | 7.11.3, 7.11.27 |
| `core.feedback` (ADR-065, issue #447, migration `V80`) | In-app feedback: the staff send with an optional screenshot, the sender's own list, the hourly limit, the operator email through the outbox, and the public port `FeedbackInbox` the platform console serves the operator's list and status change through | FBK | 6.5 `feedback_items` | 7.11.25, 7.11.3 |
| `core.ledger` | Chart of accounts, periods, journals, reconciliation | GL | 6.6 | 7.11.6 |
| `core.notifications` | Templates, outbox, SMS and email adapters: the platform outbox with SMTP and Telegram senders (ADR-024) and the SMS channel (ADR-036, issue #212): provider port, Africa's Talking adapter, sink, delivery and cost log, opt-out, window, caps, startup guard; the tenant's SMS settings group, sender id allow-list, opt-out list, wording and test message (issue #218); the member events port `SmsEvents` that loans, savings and investments queue through (issue #221); Web Push (ADR-050): the `push` channel, the caller's own devices (`push_subscriptions`), VAPID keys and the handler of the kernel event `PushNotice` | NTF | 6.4 `notification_outbox`, SMS tables, 6.5 | 7.11.3, 7.11.7 |
| `core.onboarding` | Public sign-up, applications, Verify, Needs info, Reject, Activate (ADR-024) | ONB | 6.4 `onboarding_applications` | 7.11.3 |
| `core.documents` | PDFs, uploads, object storage, signed URLs | DOC | 6.5 `documents` | 7.11.7 |
| `core.reporting` | Report catalogue and runs: the queue, the runner job, one snapshot transaction per run into a spool, JSON, CSV, PDF and Excel files, cancel and the 30 day expiry (ADR-038, issues #214 and #217); verticals register their reports; Download my data and its weekly email (ADR-074, issue #334) | RPT; chapter 14 | 6.5 `report_runs` | 7.11.8 |
| `core.imports` | Import batches, review queue, commit | IMP; chapter 13 | 13.4 | 7.11.9 |
| `core.payments` (ADR-035, issue #213, migration `V54`) | The `PaymentGateway` port, the Pesapal v3 adapter and an in-process fake, payment intents with a database-guarded state machine, the public notification endpoints (hints only), the status checker and poller, the `PurposeHandler` registry, each lender's own merchant account (issue #233, migration `V56`, `docs/runbooks/connect-lender-pesapal-account.md`) and the tenant switch; off unless configured (`docs/runbooks/payments.md`) | PAY; chapter 12 | 6.5 | 7.11.10 |
| `core.ai` (ADR-037, issue #220, migration `V51`) | The `LlmClient` port and one OpenAI-compatible adapter, the `AiGateway` every caller uses (tenant switch, platform allow-list, daily token and cost caps checked before any network call, circuit breaker, 30 second budget), the `ai_calls` log of counts and a request hash and never text, the settings group `ai` through `AiSettings`, a recording stub refused outside dev and test, and `LlmClientFactory`, the same adapter for a caller with its own providers (Ask BMS, ADR-096); off by default (`docs/runbooks/ai-assistant.md`); depends on no vertical | FR-AIA | 6.13 `ai_calls` | none |
| `core.help` (ADR-096, issue #20, migration `V108`) | Ask BMS: the help articles read from the app's files with its revision, the question scrub, full-text retrieval filtered to the asker, the provider chain Gemini, Groq, DeepSeek (never Claude) through `core.ai.LlmClientFactory`, the answer cache, the daily question cap and the spend log; off unless `BMS_HELP_ASK_TENANTS` names the business (`docs/runbooks/ask-bms.md`) | HLP | 6.5 `help_ask_questions`, `help_ask_tickets`, `help_ask_spend`, `help_ask_answers` | 7.11.28 |
| `core.jobs` | db-scheduler tasks, per-tenant job runner (ADR-008) | 5.4.4 | `scheduled_tasks` | none |
| `core.operations` | `/version`, readiness, database role guard, the `Idempotency-Key` protocol | NFR-SEC-03 | none | 7.8, 7.11.1 |
| `lending.manifest` | The vertical's registration with the core | 5.4.3 | none | none |
| `lending` members | Members, KYC, next of kin, relationships | MEM | 6.7 | 7.11.11 |
| `lending` products | Loan products and versions | PRD | 6.7 | 7.11.12 |
| `lending` loans | Origination, schedules, disbursement, repayments, arrears, closure | ORG, DIS, REP, ARR, LCL; 3.4 | 6.7 | 7.11.13 |
| `lending` collateral | Collateral register | COL | 6.7 | 7.11.14 |
| `lending.seed` | The `seed-lending` command: fabricated loan book, savings and investments for one empty staging tenant (ADR-026, ADR-031, ADR-032) | none | none (writes 6.7 tables) | none (a command, `docs/runbooks/seed-lending.md`) |
| `lending.savings` | Savings products, accounts, movements, end of day and interest, statements, savings reports (ADR-032) | SAV | 6.7 | 7.11.15 |
| `lending.investments` | Investment products, investments, returns job, maturity, rollover, early withdrawal, maturity ladder (ADR-031) | INV; 3.25.1 R-INV | 6.7 | 7.11.16 |
| `lending.insights` | The insights read model, snapshots, daily digest, panel registry (ADR-030); the public ports `InsightsFigures` (ADR-038) and `PortfolioFacts` (ADR-037) | INS | 6.7 | 7.11.22 |
| `lending.advisor` (ADR-037; facts, aliases, structured output and harness built in #223; the daily brief job, the stored brief `lending_ai_briefs`, the brief and settings routes in #226) | The advisory AI brief and question box over insights facts; aggregates and aliases only; the model returns structured JSON and our code renders every sentence from a reviewed catalogue; the stored all-branches brief goes only to users who may see every branch | FR-AIA | 6.13 `lending_ai_briefs` | 7.11.24 |
| `lending.reports` (ADR-038; `lending.par` built in #214, `lending.portfolio_outstanding` in #217) | The definitions of the seven formal reports and the monthly pack, built on the insights figures through the public `InsightsFigures` port | RPT; chapter 14 section 14.10 | 6.5 `report_runs` | 7.11.8 |
| `lending` collections | Due lists, arrears, actions | CLN | 6.7 | 7.11.17 |
| Member area | Member self-service | MSS; chapter 11 | none | 7.11.18 |
| `retail.manifest` | The retail vertical's registration with the core (ADR-020) | 3.28 | 6.11.1 chart | none |
| `retail.catalogue` | Categories, units, products, append-only price history, items with sizes and colours (ADR-051) | RET-01, RET-02, RET-51 | 6.11 | 7.11.20 |
| `retail.stock` | Stock movements and balances, stock-takes, usage and damage, transfers between branches (one step, or dispatch then receive or cancel), branch roles, the stock-by-location and transfers-in-flight reports, reconciliation; retail posting helpers | RET-03, RET-07, RET-08, RET-11, RET-16 | 6.11 | 7.11.20 |
| `retail.sales` | Sales with snapshots, voids, credit buyers, payments, customer accounts, customer returns and credit notes | RET-04, RET-05, RET-11 | 6.11 | 7.11.20 |
| `retail.purchasing` | Suppliers, restocks that set prices atomically (paid in cash, mobile money, bank or on credit), supplier payments, the payables report and the supplier statement (ADR-084); with the `purchasing` capability purchase orders, goods received, supplier invoices with the three-way match, landed cost allocation and supplier credit notes (ADR-033) | RET-06, RET-11, RET-39 | 6.11 | 7.11.20 |
| `retail.reports` | Valuation, revaluation difference, daily profit; the analytics reports of issue #149 (sales analysis, margins, stock health, credit control, business evaluation, owner dashboard), read only; the Controls page (issue #358, ADR-047) behind `retail.controls.read` | RET-09, RET-10 | 6.11.3 | 7.11.20 |
| `retail.cashbook` (ADR-022, issue #147, migration `V34`) | Daily savings, cash banked, withdrawals, expenses, advances to owner or company, cash reports | FR-RET-17 to FR-RET-32 | 6.11.5 | 7.11.21 |
| `retail.imports` | The one-off `import-retail` command: pilot history, legacy balances, opening journals; `retail_import_refs` | RET-12; chapter 13 section 13.13 | 6.11.4 | none (a command, `docs/runbooks/import-retail.md`) |

## How to run it

Tools: JDK 25 (Temurin), Maven 3.9, Node 20.19 or later, Docker (the backend integration
tests start PostgreSQL 16 through Testcontainers).

| Command | Does |
|---|---|
| `make dev` | Builds and starts PostgreSQL, the one-shot migrate, the API, the web app and a local proxy, then seeds the fabricated `demo` tenant. PWA on http://localhost:8000, API on http://localhost:8080 |
| `make seed` | Creates the `demo` tenant (idempotent), as `docs/runbooks/onboard-tenant.md` does on a server |
| `make test` | `mvn verify` (unit, architecture, formatting, integration on real PostgreSQL) and the frontend tests |
| `make lint`, `make fmt` | Java formatting check or fix; TypeScript check |
| `make openapi` | Regenerate `docs/api/openapi.json` and the frontend's typed client after a contract change |
| `make migrate`, `make psql`, `make down`, `make clean` | Local database chores |

Locally the API runs the `dev` profile. `make seed` prints a one-time link for the fabricated
demo tenant's admin (open it, set a password, sign in at `/sign-in`; turning on two-step sign-in is optional, ADR-046) and a setup
token for a fabricated platform operator. For curl, send `X-Tenant: demo` (or use
http://demo.localhost:8000) and either a bearer token or the development principal headers of
chapter 7 section 7.4.3, for example:

```bash
curl -s localhost:8080/api/v1/lending/members -H 'X-Tenant: demo' \
  -H 'X-Dev-User-Id: 00000000-0000-4000-8000-00000000d001' \
  -H 'X-Dev-Permissions: lending.members.read' -H 'X-Dev-Branch-Ids: *'
```

For the Vite dev server, `cd frontend && npm install && npm run dev` with `VITE_DEV_TENANT=demo`
in `frontend/.env.local`. The PWA signs in for real; the other `VITE_DEV_*` values in
`.env.example` are no longer read.

In a Claude cloud session, `.claude/hooks/cloud-setup.sh` installs JDK 25 (Ubuntu
`openjdk-25-jdk-headless`) and starts Docker at session start (the sandbox ships Java 21).
If `java -version` still shows 21, run `bash .claude/hooks/cloud-setup.sh` and
`export JAVA_HOME=$(find /usr/lib/jvm -maxdepth 1 -name 'java-25-openjdk-*' | head -1)` then
`export PATH=$JAVA_HOME/bin:$PATH` (two statements: in one `export`, `$JAVA_HOME` is
expanded before it is set). Shell state does not persist between tool calls, so set both
in the same command as the build.
See `docs/runbooks/cloud-agent-sessions.md`.

## Standing rules (hard)

These are not style preferences. A pull request that breaks one is not merged.

1. **Tenant isolation.** Every tenant-owned table has `tenant_id`, forced row-level
   security with the standard policy (`bms_apply_tenant_rls` in its migration), and composite
   foreign keys on `(tenant_id, x_id)`. Every transaction binds `app.tenant_id` with
   `set_config(..., true)`, and only the transaction manager does it. The tenant comes only
   from the resolved request (or `TenantJobs` for a job): no service takes a tenant id as a
   parameter, and inserts take `tenant_id` from `current_setting('app.tenant_id')`. Every
   query runs inside `@Transactional`. The application never connects as the table owner
   (ADR-003, chapter 6 section 6.3).
2. **Money is integers.** Amounts are integer minor units in `_minor` columns with a
   `currency`; rates are basis points in `_bp` columns. No floats for money anywhere
   (ADR-004).
3. **Every financial event posts a balanced journal** through the ledger's single posting
   operation, in the same transaction as the event. Journals are never updated or
   deleted; corrections are reversals (ADR-004, chapter 6 section 6.6.3).
4. **Maker-checker** for the actions in chapter 8 section 8.4. The checker is never the
   maker; the database enforces it.
5. **Idempotency keys** on every money-moving endpoint (chapter 7 section 7.8).
6. **Module boundaries** (ADR-002): core never depends on a vertical; modules use each
   other only through public interfaces; no cycles. The boundary test enforces it.
7. **One clock.** No module reads the system clock directly; use the kernel clock so tests
   can fix the business date.
8. **Every route declares a permission** from the chapter 8 matrix, or is explicitly
   public.
9. **No personal data in logs.** NIN, phone numbers, tokens, passwords, PINs and member
   request bodies never appear in logs. On staging and production `kernel.LogRedaction` also masks what it
   recognises (ADR-059), but it is a safety net: names in free text have no pattern, so never log them.
10. **Fabricated data only** in fixtures, tests, seeds, staging and examples.
11. **Import never guesses silently** and never drops a row (chapter 13 section 13.2).

## Content boundary: this repository is technical only

No client names, no names of the pilot tenant's staff or borrowers, no phone numbers, ID
numbers, addresses or emails of real people, no logos, no interest rates or fees actually
charged, no budgets, book sizes or any money figure from a real client, no contracts or
commercial terms. The only real people named in this repository are the team below.

Fixtures and examples use invented values: names like `Test Borrower 01`, phones in the
`+2567000000NN` range, NINs like `CMTEST0000001A`, round amounts. Do not commit real data
and delete it later: a commit is permanent.

## House style

- **No em dashes or en dashes anywhere** (code, comments, docs, commit messages). Use
  commas, colons, full stops or plain hyphens. `dash-guard` fails the pull request.
- ADR format: `# ADR-NNN: Title`, then `## Status`, `## Context`, `## Decision`,
  `## Consequences` with the bolded groups **Better:**, **Worse:**, **Watch for:**.
- An unwritten ADR may be cited only as "pending ADR-NNN". `adr-citation-guard` fails the
  pull request otherwise.
- Never rewrite a merged ADR. Supersede it with a new one and record the supersession in
  both Status sections.
- Requirement IDs (`FR-...`, `NFR-...`) are cited in test names and pull request
  descriptions.

## How to pick up work

1. Take an issue from the board (Epic, Feature, Story, Task hierarchy in `PROCESS.md`).
   Pull requests close a Task or Chore, never a Story or above.
2. Move it to In Progress and put your estimate on it (leaf items only).
3. Branch from `main`: `<type>/<slug>-issue-<n>`, for example
   `feat/loan-schedule-flat-issue-42`.
4. Build it to the chapter 3 acceptance criteria, with the tests chapter 15 asks for.
5. Update the docs the change touches in the same branch.
6. Open a pull request with `Closes #<n>`, the FR IDs, and the definition of done ticked.
7. After merge, `main` deploys to staging automatically; production is a version tag
   (`PROCESS.md` section 7).

## Definition of done (Task level)

- Acceptance criteria of the cited FR and NFR IDs are met and proven by tests.
- CI is green: lint, types, unit, integration against real PostgreSQL, isolation,
  boundary, golden tests, frontend build, documentation guards.
- **Docs updated in the same pull request**: the SDD chapter, the data model, the endpoint
  catalogue, the permission matrix, the Structurizr model and an ADR where the change is a
  decision. No change is too small for this rule.
- Migrations are expand and contract (chapter 6 section 6.9).
- No dashes, no real data, no secrets.
- Reviewed and approved by someone other than the author.

## Architecture decision records

Accepted (this list is the ADR index):

- ADR-001 Platform core plus vertical modules; lending is the first vertical
- ADR-002 Modular monolith with enforced module boundaries
- ADR-003 Tenant isolation: shared schema plus PostgreSQL row-level security
- ADR-004 Money and accounting: integer minor units and a double-entry general ledger
- ADR-005 Documentation lives with the code
- ADR-006 Delivery pipeline: build once, main to staging, tag to production, migrations
  before swap
- ADR-007 bms-platform and ERP_BMS are separate products
- ADR-008 Background jobs and scheduling on PostgreSQL with db-scheduler; no Redis
- ADR-009 Frontend: one React PWA with staff and member areas
- ADR-010 Backend language and framework: Java 25, Spring Boot 4.1, Spring Modulith 2.1, Flyway
- ADR-014 Staff authentication: argon2id, TOTP with recovery codes, signed access tokens and
  server-side sessions
- ADR-015 Maker-checker actions are registered by the modules that own them
- ADR-016 Platform operations run through SECURITY DEFINER functions until the platform role exists
- ADR-017 The principal types live in the shared kernel
- ADR-018 Staging on a shared ARM64 host behind a Cloudflare Tunnel, pull-based deploy, hyphenated
  hosts under the Rincol zone
- ADR-019 Collateral release as an approval action, and the interim duplicate pledge rule (proposed, #24)
- ADR-020 Retail vertical brought forward; stock as append-only movements; retail events post to the
  ledger (proposed, #50)
- ADR-022 Retail cash book: savings reserve, banking, expenses, withdrawals and owner advances
  (proposed, issue #147, design only; spec `docs/specs/retail-cash-book.md`)
- ADR-024 Self-onboarding with operator verification, per-module subscriptions and manual payments
  (spec `docs/specs/self-onboarding-and-subscriptions.md`; build step 1 is #89)
- ADR-025 First run signs the invitee in; guided tours are data with per-user progress on the server
  (proposed, #19 and #86)
- ADR-026 Loan servicing: allocation rows by repayment, replay on reversal, default payment method
  accounts and a servicing port for commands (proposed, #108)
- ADR-027 One onboarding pipeline for customer data: quarantined staging, canonical templates with
  versioned mappings, resumable maker-checker commit (proposed, #125)
- ADR-028 Database performance: measured on 25 times the data, covering indexes, the plain tenant
  policy kept, connection timeouts (proposed, #107)
- ADR-029 One fixed low stock threshold for retail, a per-tenant settings group later (#145)
- ADR-030 Lending insights: a read model over the loan tables and the ledger, live today and
  snapshots for history, polling, and a plain-text digest through the outbox (proposed, #153)
- ADR-031 Investments: month-based returns accrued monthly, recurring as auto-renewal, early
  withdrawal settled in one entry (proposed, #152)
- ADR-032 Savings: end-of-day balances with interest rounded once per posting, movements only after
  the closed day, withdrawals checked again at execution, receipts queued as SMS that expire unsent
  (proposed, #151)
- ADR-033 Wholesale support as optional per-tenant capabilities of the retail module: branch roles,
  units, price lists, customer accounts, documents, purchasing; no second vertical (accepted, #190;
  amended by #193 for the two-step transfer, by #198 for optional purchasing and by #199 for landed cost and supplier credit notes; spec `docs/specs/trade-capabilities.md`)
- ADR-034 Generated PDF documents: XHTML templates rendered with OpenHTMLtoPDF, on demand, stored once
  per version (accepted, #204)
- ADR-035 Mobile money and card collections through Pesapal v3: hosted orders, the notification as a
  hint, the status query as the truth, idempotent booking through purpose handlers (proposed, #210;
  settles the question that was pending ADR-011; amended by #211, #213 and #233: a tenant's money lands only in its own merchant account)
- ADR-036 SMS as a channel of the notification outbox: Africa's Talking first, tenant link and delivery
  log, modes with a sink outside production, windows and opt-out (accepted; channel core built in #212;
  settles the SMS half of pending ADR-013)
- ADR-037 AI portfolio monitoring: advisory, aggregates and aliases only, numbers validated in code,
  off by default (proposed, #210; amended by #220, #223 and #226)
- ADR-038 The reports pack: asynchronous runs over one snapshot, PDF through the shared templates,
  streamed Excel (proposed, #210; run framework built and amended by #214, PDF and Excel renderers by #217)
- ADR-039 One search convention: a shared kernel matcher (folded text, AND of terms, phone digits, ranked, rank in the
  cursor), `bms_fold` and `bms_digits` in V40 and no search index (none is usable under row-level security), one
  live search hook in the PWA (proposed, #241; spec `docs/specs/search.md`)
- ADR-040 Build on a dedicated runner on the staging host, sign on the maintainer's Mac: the deploy jobs run on
  `bms-deploy-laptop` (Jerome's laptop) in an org runner group that admits only `deploy.yml` on main (own user, wall, caps, wiped state,
  no secret), the cosign key lives only in the Keychain and a launchd signer (cosign v2.4.1) signs the digests a
  main run logged; the staging pointer waits for the signatures (hosted minutes are not used and the budget is
  not raised; proposed, #296; `deploy/runner/`, `deploy/signer/`)
- ADR-044 Entry mistakes are corrected inside the app: one product name per unit, a restock voided by reversal and entered
  again, a quantity corrected as an adjustment that no report counts as shrinkage (proposed, #351 and #352)
- ADR-050 BMS installs as an app and tells a signed-in person on their own devices when something needs them, by Web Push (VAPID keys from the
  environment, a subscription per user per device, a `push` outbox channel, notices published by modules; proposed)
- ADR-045 A discontinued product is marked inactive, never deleted: refused on sales, restocks and transfers with 422
  `product_inactive`, left out of the default stock lists (`include_inactive`), its stock shown as a warning (proposed, #342)

- ADR-043 Credit sales count when the buyer pays (a per-tenant setting, `on_sale` by default), and a write-off for buyers who
  never pay (proposed, #340; amends ADR-022 decision 11 for a tenant that chooses `on_payment`)
- ADR-042 A staff user who forgot the password gets back in with a one-time link, by email or from a tenant admin; the reset
  never touches the second factor or the lock, and the form tells nobody whether an account exists (proposed, #320; FR-IAM-15)
- ADR-047 The Controls page: read-only counts of voids, returns, changed prices, restocks cancelled and quantities corrected by
  shop, person (whose sale it was), day, reason and approver; rates over sales, no league tables (proposed, #357 group 1, #358)
- ADR-051 An item with sizes and colours is a group of products: each variant a product with its own code, prices and stock, the item a
  new table, existing items their own single variant, reports rolled up to the item (proposed, #384; FR-RET-51, FR-RET-52)
- ADR-052 Customer orders keep stock aside on the balance row and become an ordinary sale on delivery through the sale path; the delivery
  fee is income of its own, paid at the door or, for a credit buyer, on the account (proposed, #385; FR-RET-53 to FR-RET-57; renumbered at merge if needed; amendment 1, #393: the items of an ordered or packed order change in place)
- ADR-053 Item photos are stored through core.documents and served from the same origin (proposed, #384; FR-RET-58)
- ADR-056 Operator screens: the price book by plan with prices from a date (amends ADR-024 decision 3), payments entered by the operator and
  confirmed at once behind an Idempotency-Key, the free month from the first staff sign-in, a reason on every operator change (proposed, #407)
- ADR-055 Retail roles: staff per shop or store through the existing branch scope, a Storekeeper role, Purchasing as a role the owner gives, an
  administrator never limited by branch (a database check), sellers who see only their own figures (proposed, #399; FR-RET-59 to FR-RET-61; renumbered at merge if needed)
- ADR-057 Everyday push notices (new orders, delivery, low stock, credit due, the daily summary) with per-business switches, a one-tap
  turn-on card, and a low-stock minimum per item and branch beside the pace rule, whichever trips first (proposed, #412; renumbered at merge if needed)
- ADR-058 Host checks post to the Telegram operator chat on a systemd timer, an external uptime monitor and heartbeat watch the host, the
  restore drill is timed and scheduled, and R2 lock and lifecycle rules make backups undeletable from the host (proposed, #425; renumbered at merge if needed)
- ADR-059 Sign-in locks in the database for every address (an unknown one locked like an account), failures-only limits per
  client IP, audited unlock, at most four password hashes at once and never inside a transaction, SMTP required in production, server logs redacted and container logs capped
  (proposed, #426; launch readiness M8 to M10; renumbered at merge if needed)
- ADR-060 The Retail home follows one chosen range against the period before (as many days before, This year against last
  year, All against nothing), from the dashboard's own dated queries; the choice kept per person per device (proposed, #428;
  FR-RET-65; renumbered at merge if needed)
- ADR-061 Receipts are shared from the seller's own phone (the share sheet and wa.me), never sent from a server; the receipt PDF is
  rendered now under `retail.sale.read`, own sales only for a seller, never stored (proposed, #439; FR-RET-66; renumbered at merge if needed)
- ADR-062 The Owner of a business: one administrator per business, a flag and not a role, who alone adds, removes or changes
  administrators and whom nobody inside the business removes; handed on by a transfer with a step-up, or by the operator with a reason
  (proposed, #442; FR-IAM-18; migration `V79`; renumbered at merge if needed)
- ADR-064 Collection lists: a loan officer sees their own portfolio (`lending.collections.all_officers` for the rest),
  only the people who call borrowers see phone numbers (`lending.members.contact`), and a reassignment needs a reason
  (#272, migration `V83`).
- ADR-065 Feedback from inside the app: any member of staff sends an idea, a problem or a question with an optional screenshot, sees
  only their own; the operator reads every business's through definer functions, moves it forward (new, seen, done) with a reply,
  and is emailed through the outbox, never in the way of the send; ten an hour per person (accepted, #447; FR-FBK-01 to FR-FBK-05;
  migration `V80`)
- ADR-068 Each shop can have its own logo: optional, the rules of the business logo, set by the owner and the administrators
  (`core.branches.manage`), served publicly like the business logo; it heads that shop's receipts and documents and its screens,
  and the app icon, tab and manifest stay the business's (accepted, #461; FR-BR-09; migration `V85`; renumbered at merge if needed)
- ADR-066 The installed app uses the business's own logo and name as its icon: a per-host manifest and icons drawn from the logo by the
  API under `/api/v1/branding/app/`, the BMS icons as the fallback; an iPhone takes the icon only when the app is added again (proposed,
  #446; FR-TEN-08; amends ADR-050 decision 1; renumbered at merge if needed)
- ADR-073 Owners change their own plan: a module asked for is switched on at once when the plan includes it, otherwise the operator
  decides; stopping a module and cancelling take effect the day after the last day paid for (at once when nothing is paid ahead), applied
  by a quarter-hour job once; the business read only with `subscription_ended` after a cancellation's end date, data kept (proposed,
  #472; FR-SUB-03 to FR-SUB-07; migration `V90`; renumbered at merge if needed)
- ADR-076 The shop Home reads one summary built from the existing report queries: today's sales, takings by cash, mobile money and
  any other way, credit owed with the oldest due date, the top five items today and this week, a line per shop over two or more, the
  low stock count and the open orders, the money parts left out without `retail.sale.all_sellers` (proposed, #485; FR-UXN-06;
  supersedes ADR-072 decision 2; no migration; renumbered at merge if needed)
- ADR-077 Low and out of stock count only items a branch has held (a balance row or a movement there), low at or below the
  branch's minimum, else the item's, else the fixed 5; over several branches low at any of them, out on the summed balance; one
  SQL rule in `LowStockRule` for Home, the dashboard and the Stock tabs (proposed, #490; amends ADR-029; no migration; renumbered
  at merge if needed)
- ADR-067 Retail sales work offline: the sale catalogue and an outbox per business and person on the device, synced one at a
  time oldest first, sent once by the Idempotency-Key and a client sale id stored on the sale; dated when they happened; stock,
  price and item clashes recorded and flagged, other refusals kept on the device for a manager to resend or discard; a dot in
  the staff bar with the count waiting; offline sales follow the online price rules (accepted, #458; FR-RET-67, NFR-OFF-07, NFR-OFF-08; amends ADR-009 and NFR-OFF-04;
  migration `V86`)
- ADR-075 Subscription reminders to the Owner, overdue the day after with a grace period the operator sets, a list for suspension and
  never an automatic suspension, a numbered receipt emailed as a PDF, and the Owner's Plan and billing page (proposed, #480;
  FR-SUB-08 to FR-SUB-12; migration `V92`; renumbered at merge if needed)
- ADR-078 Deploys keep the previous builds' hashed web files: before each switch, rollbacks included, `deploy.sh` runs the outgoing
  web image once (local image ID, `--pull never`, 60 seconds at most) as `keep-assets` to copy its `/assets` and workbox runtime into the `web_assets` volume, which `web` serves read-only
  from `/srv/previous` after `/srv`; files pruned 14 days after they leave the live build; `index.html` and `sw.js` stay `no-cache`
  (proposed, #525; no migration; renumbered at merge if needed)
- ADR-079 Profit from the moving weighted average buying price per smallest unit, kept per branch on the stock balance row: a
  delivery, goods received, a transfer in (at the source's average) and a customer return re-average it, sales, usage, counts,
  corrections and transfers out take it, voids re-average or are refused (`average_cost_negative`), the rounding remainder goes to
  purchase rounding, sale lines keep it and profit reads those snapshots; `cost_minor` is the last price paid (proposed, #523, owner
  decision D22; FR-RET-69; takes the place of the never written pending ADR-021; supersedes ADR-020 decision 6; migration `V94`; merged)
- ADR-080 Savings are capped at the day's profit less its expenses (not voided), never below 0, on the server (422
  `savings_above_available` in plain words) and under the field; the suggestion is the rate times that amount; the overwrite reason
  is an optional pick list; every other screen's typed reason stays required until decided one by one (proposed, #538; amends
  ADR-022 decision 11; migration `V95`; renumbered at merge if needed)
- ADR-081 Item codes are made from the category: a category has a code start of 2 to 4 letters, unique per business, suggested
  from its name (Bulbs BLB, TVs TV, Phone accessories PHA) and editable; an item added without a typed code gets `PREFIX-0001`, the
  next number no item holds, under the category row's lock; typed codes are kept, existing codes never change, a moved item keeps its
  code, the import fills blank codes; items are added inside a category, whose form has no category dropdown; the field is Item code
  (proposed, #493, owner decisions D14 and D16; FR-RET-70; migration `V96`, the next number after `V95`). Amended 25 Oct 2026
  (#562): the app always makes a new item's code, everywhere; a typed code is refused with 400 `item_code_made_by_app` (the import
  keeps a file's codes unless Let the app make the codes is ticked, D45), existing codes never change, and the new category form shows the code start without asking
- ADR-082 A buying pack's size is kept per delivery: a restock or goods received line in a pack says how many pieces each pack held,
  kept in its `unit_factor` and used for stock and the average; "use from now on" (with `retail.catalogue.manage`) changes the usual size;
  selling packs keep their size; an order counts packs whatever each held (proposed, #554, owner decision D23; FR-RET-72; no migration)
- ADR-083 Deploys switch api and web without a gap: a new container starts beside the old one (`--scale 2 --no-recreate`) and the
  old one stops only once the new one is healthy, else the new one is removed; Caddy finds both through `dynamic a` upstreams with a
  30 second retry of refused connections and no keep-alive; the proxy keeps its image unless its binary, Caddyfiles or settings
  changed; a second API JVM only while its own memory limit plus 64 MB is free below `MemoryHigh` (on the Pi, whose slice is
  1500/1600 MB since D37, staging overlaps on every deploy; with less free it restarts in place and says so); the incoming web
  build's hashed files go into `web_assets` before the web overlap; tested under Compose 2.26.1; Caddy marks only a failed dial
  with `Bms-Not-Delivered`, the only case the PWA calls "Nothing was saved yet", any other proxy error or dropped write reads as
  maybe saved, and a keyed request is retried once (proposed, #496, owner decision D36; no migration)
- ADR-084 Buy stock on credit and pay suppliers without the purchasing capability: the V47 supplier payments, open items and payables report
  are reused, no second payable system; Buy stock is paid in cash, mobile money, bank or on credit; a payment has a date, never in the future,
  and pays only what was owed that day; a supplier statement with a running balance, shared as text (proposed, #557, owner decision D15;
  FR-RET-73; amends ADR-033 as amended by #198; migration `V97`)
- ADR-085 One staff phone per business in the database: a partial unique index on invited and active staff phones refuses
  the second of two invitations at once with 409 `duplicate_phone`; the migration stops, changing nothing, on existing
  duplicates (manual fix in a runbook, never a deleted user); the recording notifier is dev and test only and every other
  profile keeps no message or link (proposed, #323 and #324; amends ADR-014; migration `V98`)
- ADR-086 One grouped staff menu, the same on a phone and a laptop: Home, Today, Stock, Money, Lending (when on, after Money) and
  Business, every page the session may open by the same permission rules and no cap; the shop logo or initials, business, role
  and branch picker at the top; Notifications, Help and feedback, the person and Sign out at the foot; a fixed 264px sidebar from
  720px that no longer folds and a drawer from the top left on a phone, the bottom tabs unchanged (proposed, #564, owner decisions
  D38 and D44; amends the menu decisions of #455 and #471, FR-UXN-04; no migration)
- ADR-087 The Controls page counts credit write-offs (`credit_writeoffs` from `V64`: the unpaid amount at sale prices, never the
  loss; the person whose sale it was, the approver who wrote it off; a rate per 100 sales; not in the patterns) and states its
  query plans for a large tenant, with JIT off for its statements and two partial indexes on sale lines; Controls in the Business group of the grouped menu, ADR-086 (proposed, #357; amends ADR-047; migration `V101`)
- ADR-089 Add a branch from the app as a shop, a store or a head office: `POST /branches` takes `stocks_goods`, the code is
  made from the name when none is typed, the plan limit is said in plain words with a link to Plan and billing, and the shop
  and store rules apply from creation (proposed, #581; amends ADR-041 and ADR-048; no migration)
- ADR-090 The Owner's billing contact gets the reminders and receipts and is never a sign-in contact; the Owner asks to close the
  business with the step-up, it is read only at once with a full data export, cancellable until the operator removes it after 30
  days with their own code: personal data deleted or anonymised in every module through a kernel `PersonalDataEraser`, money
  records kept anonymised and read only for `BMS_CLOSED_RETENTION_YEARS` (5) after the end of the financial year, then deleted by a
  daily job (owner decision D46); the operator lists businesses with no Owner (proposed,
  #571; FR-SUB-13, FR-TEN-09, FR-IAM-18; migration `V102`; renumbered at merge if needed)
- ADR-092 The kind of business names the retail preset by what the owner has: one setting, the `retail.capabilities` profile, as
  Shops only, Stores and shops, Wholesale or Wholesale and shops, each a preset of six switches and never a mode; existing
  profiles renamed without changing a switch; a Wholesale group in the grouped menu while a wholesale tool is on; the operator
  sees the kind (proposed, #424; amends ADR-033 decision 2 and ADR-086; migration `V103`)
- ADR-093 Entry warnings are rule based, read from the item's own history, and never block: medians and the usual unit over the
  newest 20 purchases, sales and moves (from 3 lines), ten times or a tenth for quantities, twice or half the last price for a typed
  selling price, a count against the records; Confirm records an audit row and saves, Change keeps the form; Did you mean and the
  category of similar items while a new item is named; Confirm re-checks the screen and is recorded after the save; no AI
  (proposed, #353; migration `V104`, indexes only)
- ADR-094 A purchase order is sent only while every item on it is active: `POST /retail/purchase-orders/{id}/send` refuses a
  draft holding an inactive item with 422 `product_inactive`, checked before a checker is asked and again when the approval
  sends it (the request then ends stale, ADR-091); goods against an order already sent are still received (proposed, #376; amends ADR-045; no migration)
- ADR-095 The help corpus is Markdown in the app: one short article per task with a captioned 412x900 phone picture for each
  step, tagged with the permissions it applies to (the tour step rules), searched on the phone, precached for offline use; its
  revision is the first 16 hex digits of the SHA-256 of the article files in name order, which #20 keys its answers on
  (proposed, #598, owner decision D51; no migration, no endpoint)
- ADR-096 Ask BMS answers from the help corpus only: PostgreSQL full-text search over the articles the asker may read, the
  question scrubbed of business data, a small model at temperature 0 citing one article, providers Gemini then Groq then
  DeepSeek through the gateway's adapter and never Claude (three names, a constant allow-list of one host each, startup refused), a cache
  keyed on everything that produced the answer, off unless `BMS_HELP_ASK_TENANTS` names the business, a daily question cap and
  a spend log (proposed, #20, owner decision D52; migration `V108`)
- ADR-099 A selling pack has its own price and each pack says what it is for (buying, selling or both): walk-in sales, scans,
  quotations and orders take the pack's own price, else the piece price times the size; price lists still win; below cost per
  pack against the average cost per piece times the size; a pack price, and a new size or buy only on a pack that has one (on Edit
  item, or Use 30 from now on, which is then left out with `size_kept`), needs `retail.price.edit` (proposed, #624, owner decision
  D23; amends the ADR-033 amendments for units and price lists; migration `V110`)
- ADR-046 Two-step sign-in is optional by default (migration `V67`) and a tenant policy can require it for everyone or for chosen roles after a grace
  period; one deadline, enforced-now versus due-by, the forgotten-password rules unchanged (proposed, #348; FR-IAM-06, FR-IAM-16)
- ADR-063 Production is created by Terraform on production day (`infra/production/`, a Hetzner root and an AWS root over shared
  cloud-init and Cloudflare modules, nothing applied before): the VM, its firewall, DNS, the R2 buckets with the ADR-058 lock and
  lifecycle rules and host tokens (the backup one cannot delete a locked backup; the DNS-01 one reaches the whole zone, an accepted risk); later updated by a signed pointer the owner moves with
  a local promote command, no deploy SSH key and no hosted job (proposed, #300, #301; provider, region and the promote command pending)

Pending (cite only as "pending ADR-NNN"):

- ADR-012 Credit scoring model beyond the rules-based default
- ADR-013 USSD aggregator (the SMS half is settled by ADR-036)
- ADR-022 The cash book: expenses, banking and advances, likely partly core (ADR-020)

## Team

| Name | GitHub | Role |
|---|---|---|
| Hillary Arinda | @arindahills | Dev lead; required reviewer for production releases |
| Dennis Kaweesi | @Lovepankie | Developer |
| Solomon Ariho | @arihosolomon | Developer |
