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
  (`V34` is the retail cash book, issue #147; `V35` is claimed by lending, issue #176; `V36` is claimed by lending payment methods, issue #160; `V37` is retail branch roles, issue #191; `V38` is the retail two-step transfer, issue #193; `V39` is units of measure, issue #194; `V40` is search, issue #241; `V41` is price lists, issue #195; `V42` is the loan reopen pledge guard, issue #176; `V43` is payment methods, issue #160; `V44` is customer accounts, issue #196; `V45` is returns, issue #201; `V46` is optional documents, issue #197; `V47` is optional purchasing, issue #198; `V48` is landed cost and supplier credit notes, issue #199; `V49` is report runs, issue #214; `V50` is the SMS channel core, issue #212; `V51` is the `core.ai` call log, issue #220; `V52` is the tenant SMS settings, issue #218; `V53` is the member event SMS cancel, issue #221; `V54` is the payment gateway core, issue #213; `V55` is the stored daily AI brief and the advisor permissions, issue #226; `V56` is the lenders' own payment accounts and `V57` their permission, issue #233; `V58` is the step-up failure counter; `V59` is the loan repayment by gateway and the unallocated receipts, issue #216; `V60` is the savings deposit by gateway, issue #219; `V62` is the staff forgot-password migration, issue #320; `V65` is the store branch flag, issue #366; `V67` is the two-step default, issue #348; `V69` is the items with sizes and colours, issue #384, the next number after `V68` (the push subscriptions); `V70` is customer orders with delivery, issue #385; `V71` is the item photos, issue #384 slice 2; `V72` is find your sign-in page, issue #383; `V73` is the retail Storekeeper and Purchasing roles, issue #399; `V74` is sellers who see only their own figures, issue #399 slice 2; `V75` is the operator screens, issue #407, directly after it; `V76` is the low-stock minimums and the daily notices, issue #412, directly after `V75`; `V77` is changing an order's items, issue #393, directly after `V76`; `V79` is the Owner of a business, issue #442, directly after `V78`; `V80` is in-app feedback, issue #447, directly after `V79`; `V81` is the lending arrears run and penalty charges, issue #264, then `V82` the penalty waivers, issue #265, `V83` the collection actions, issue #266, and `V84` the arrears notices' record, issue #227, in that merge order; `V85` and `V86` are claimed by payments, `V87` by offline sales, `V88` by shop logos, `V89` by referrals, `V90` by barcodes, and `V91` by existing loans brought in from a paper register, issue #466, merging after #290). A migration takes the highest number on main plus one at merge time and never a number above one that is still unmerged; a placeholder `V99__` is refused by `placeholder-migration-guard` and by the first job of `deploy.yml`. The retail analytics pass (issue #149) is reports only on existing data: no migration; its
  queries are in `scripts/db-bench/queries` (k01 to k15).
- **Built (trade 1 of 10, issue #191, ADR-033):** migration `V37` (`retail_branch_roles`, the permission
  `retail.branch_role.manage`, a backfill of role `both`); the tenant settings group `retail.capabilities`
  (eight flags, the `shop` or `trade` profile as a preset, all off by default) through a vertical-neutral
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
- **Built (the installed app uses the business's logo, issue #446, FR-TEN-08, ADR-066):** no migration, no permission. Public
  `GET /api/v1/branding/app/manifest.webmanifest` (the business of the host: `id` and `start_url` `/staff`, its display name, a 12
  character `short_name` cut by grapheme clusters at a word boundary when one is 6 or more in, its theme colour) and `GET /api/v1/branding/app/{icon}` (`icon-192.png`, `icon-512.png`, the two maskable ones
  and the 180 px `apple-touch-icon.png`, drawn from the logo on the theme colour with Java2D in `core.tenancy` `AppIcons`, cached by logo,
  colour and size per business, rendered once under concurrent misses, a render failure logged once and served as the BMS icon); the BMS
  icons under `/icons/` where there is no logo or it cannot be drawn, and the BMS manifest on a host that is no business (the manifest
  and the five icon paths are the exact paths `TenantResolutionFilter` lets through without a tenant). `index.html` links both; vite-plugin-pwa runs with
  `manifest: false`; the shell sets `apple-mobile-web-app-title` to the same short name. An iPhone keeps the icon it took at Add to Home
  Screen. Tests: `AppManifestIT`, `AppManifestShortNameTest`, `app-manifest.test.ts`, `branding.test.ts`, `app-title.dom.test.tsx`.
- **Built (light and dark theme selector, issue #429):** PWA only, no migration, no API change, nothing stored on the server. A System, Light, Dark selector at the top right of the shell's header (every area), kept per device in `localStorage` (`bms.theme`), following the system setting by default, set before first paint by the blocking `frontend/public/theme-init.js` (the CSP is `script-src 'self'`), applied afterwards by `frontend/src/app/theme-mode.ts`; one `:root[data-theme='dark']` token set in `theme.css`, stylesheets draw with colour roles only (`design-guard.test.ts`), the tenant brand pair is untouched and its link and focus colours are derived again for dark, the `theme-color` meta follows the choice (the manifest stays light); the three welcome tours gain a "Light or dark" step (`admin-welcome` v16, `retail-sales` v9, `staff-welcome` v4). Token pairs in `docs/ui/design-system.md`; tests `theme-mode.dom.test.tsx`, `tokens.test.ts`.
- **Built (theme button and Appearance, issue #452):** PWA only, no migration, no API change. The header `<select>` of #429 becomes an icon button (sun, moon, or a phone for the device choice, `aria-label` "Theme: <choice>") opening an accessible three item menu (`ThemeMenu` in `frontend/src/app/shell.tsx`); the Notifications and app page gains an Appearance radio group on the same `useThemeChoice`. The label of `system` is "Same as my phone" everywhere; `theme-mode.ts` is unchanged. Tour `page-notifications` v3. Tests `theme-mode.dom.test.tsx`, `notifications.dom.test.tsx`, `shell.test.tsx`.
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
- **Built (lending staff screens 1 and 2, issue #280):** PWA only, no migration: Members (list with live search, add, change, the member's page with the check of the details, next of kin, ID photos, relationships, loans) and the collateral register (register, change, valuations, custody changes, photos, the release request), phone-first, with a tour each (`docs/specs/lending-ui-notes.md`, section "Members and collateral"). Loan application, appraisal and decision are slices 3 and 4.
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
- **Built (extras slice A2, issue #213, ADR-035 second amendment):** migration `V54` (`payment_intents`, `payment_callbacks`, two platform tables for unknown notifications and counters, definer functions); `core.payments`: the gateway port, the Pesapal adapter (token cache following `expiryDate`, the http 200 with body status 500 envelope, whole shillings enforced, RegisterIPN spacing), a fake, intents (30 minute offer, 48 hour polling, one locked creator), the public notification endpoints that never trust their request and always re-query the status, the poller job `core.payments.poll`, the purpose handler registry (no real purpose until #216 and #219), audit of every transition, and the settings group `payments` (`GET` and `PUT /payments/settings`, `GET /payments/intents/{id}`, `POST /payments/intents`, `.../cancel`) off by default and bound to `BMS_PESAPAL_TENANT_ID`; outside production only the sandbox base URL starts. No permission added. Runbook: `docs/runbooks/payments.md`.
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
- **Built (credit sale recognition, issue #340, ADR-043):** slice 1, no migration. The tenant setting `credit_sale_recognition` in the group `retail.capabilities` (`on_sale`, the default and today's behaviour, or `on_payment`, the owner's rule), changed with `PUT /retail/settings/capabilities` and shown in Retail settings in plain words. The switch is one way and counts from the next business day (`credit_recognition_effective_from`, one stored date): it can be cancelled until that day, then the route refuses to switch it off (422 `credit_rule_cannot_be_switched_off`) and the operator reverts it by SQL (runbook); a credit sale is counted the way the rule stands on the later of its date and the day it was entered, a payment counts on the day it was entered and at the branch that took it (a return credited to the balance is spread over the branches that took the money), and a payment on a sale counted when the buyer pays is dated today (`counted_when_paid` on the sale). Under `on_payment` a credit sale is Sold and On credit, and counts as Received and as profit only as the buyer pays, a part payment giving part of the profit by the sale's margin (`ProfitFigures`, `CreditRecognition`): the daily profit report, the home cards and the cash book's daily savings (half of the received profit per shop per day) read the same figures; stock and the ledger are unchanged; imported credit sales stay outside the rule. `/retail/sales/summary` is not changed here (issue #341). Slice 2 adds the write-off of a buyer who never pays: migration `V64` (it sets `lock_timeout` to five seconds), `retail_sale_writeoffs`, the chart account 5140 `bad_debt_loss`, `POST /retail/sales/{id}/write-off` (its own permission `retail.credit.write_off`, administrators only; the maker-checker action `retail_sale_write_off` above the tenant's threshold), the sale's `write_off`, the loss in the daily profit report on its date, and a Write off form on the opened sale.
- **Built (cash change on the sale form, issue #346):** PWA only, no migration, no API change, no permission. For a cash sale the form shows Amount received (whole shillings, numeric keyboard, quick buttons 1,000 to 50,000 and Exact) and Change to give in large figures; an amount below the total is refused in plain words (no part payment on a cash sale), mobile money, bank and credit hide it, and the change is display only: the sale is recorded at its total, the daily cash summary counts that total, nothing is sent or saved in the draft, and the receipt shows Received and Change given. New files `sale-change-state.ts` and `sale-change.tsx`, so a barcode scanner (#345) can edit `sale.tsx` freely; the tours `retail-sales` (version 6) and `admin-welcome` (version 9) have a Cash and change step (`docs/specs/retail-ui-notes.md`, `docs/sdd/07-api-design.md` section 7.11.20).
- **Built (close a branch, mark a branch as not stocking, issue #329, ADR-041):** migration `V61` (the first free number after `V60`): `branches.stocks_goods`, `closed_at`, `closed_reason`. Closing is the existing status `inactive` with a way back: `POST /branches/{id}/close` (`If-Match`, body `{reason?}`; refused with `branch_close_needs_reason`, in plain words with the stock value, while the branch holds stock or has a transfer on the road, until a reason is sent; nothing is posted or moved) and `POST /branches/{id}/reopen` replace `deactivate`; both are audited and need `core.branches.manage` (no permission added). The retail module supplies the check through the core port `BranchClosingCheck`. A closed branch leaves `/me`, the pickers, the forms and the default `GET /retail/stock/all-branches` (`include_closed=true` and `closed_branches` bring it back with a Closed flag; the stock-by-location report keeps it, flagged). A branch with `stocks_goods` false is refused for sales, restocks, purchase orders, goods receipts, transfers into it and their receipt, stock-takes (made and committed) and the restock side of a return with 422 `branch_not_stocking` (`RetailBranchContext`, which reads the branch row `FOR SHARE` in write transactions so it serialises with a close or a flag change); goods can still leave it (transfer source, usage and damage) and voids stay allowed; marking it needs empty shelves, no transfer on the road and no open stock-take (`branch_still_has_stock`); the importer refuses a balance row for it. The Branches screen (`/staff/setup/branches`, linked from Business set-up) lists, closes, reopens and switches stocking; the sale, restock, move-stock, stock-take and usage screens skip a branch that keeps no goods; the welcome tour is version 8 with a Branches step and the page tour is `page-branches`. Notes: `docs/specs/retail-ui-notes.md` (Branches).
- **Built (a store holds stock and does not sell, issue #366, ADR-048):** migration `V65`: `branches.sells_goods boolean NOT NULL DEFAULT true` and the check `branches_store_stocks` (a store must keep goods). A branch with `sells_goods` false is a Store: it takes restocks, goods receipts, transfers in and out, stock-takes, usage and damage like any stocking branch, and refuses a sale, a quotation, a price resolution, a payment on a credit sale, a payment on account, a refund paid out of its till and the cash book savings, bankings, withdrawals and advance repayments (expenses stay allowed) with 422 `branch_does_not_sell`; a refund for a sale made at a store is paid from the one shop the caller may sell at, else 422 `refund_branch_required` (`RetailBranchContext.requireSells`, read `FOR SHARE` like `requireStocks`). `PATCH /branches/{id}` takes `sells_goods` (also on create): marking is refused with `branch_has_open_credit_sales` while a credit sale made there still owes (core port `BranchSellingCheck`, implemented by `BranchTillCheck` in retail sales) and with `store_must_stock` when the branch would keep no goods. `/me` reports `sells_goods` per branch; the sale form, the till, the refund branch and the default branch skip a Store while the restock, goods receipt, transfer and stock-take forms include it; the Branches screen has Mark as a store (does not sell) and Sells again; the page tour `page-branches` is version 2 with a Store step. Imported credit sales never block marking. Tests: `StoreBranchIT`, `StoreBranchTillIT`. ADR-048 (Proposed). Notes: `docs/specs/retail-ui-notes.md` (Stores).
- **Built (entry mistakes corrected inside the app, issues #351 and #352, ADR-044):** migration `V63` (the first free number after `V62`): `bms_name_key`, the
  generated `retail_products.name_key` and `duplicate_reason`, `retail_purchase_voids` and `retail_stock_corrections`; no new permission. A product name is kept once per
  unit (a key that folds case, accents, spaces and punctuation): create and edit refuse a repeat with 422 `duplicate_product_name` naming the existing item, an
  administrator (`core.settings.manage`) may add it anyway with a reason kept on the product, `GET /retail/products/similar` gives the close names the form warns
  about, the CSV import skips a repeated name and the pilot importer reports an anomaly. `POST /retail/purchases/{id}/void` reverses a restock's movements and entries
  in one transaction, gives its prices back while it is still the latest price event, is refused in plain words when its stock moved, it is imported or a supplier
  payment stands against it, and leaves the restock in place, shown as voided (cash returns to the cash book on the day of the void; a voided credit restock is not
  owed). `POST /retail/stock/corrections` (`retail.stocktake.commit` at the branch) posts one `adjustment` of source `retail.stock_correction` with the old and new
  quantity, who, when and why, which the shrinkage, usage and profit reports never count. The Past restocks screen (Correct this restock, Void and enter it again,
  the unit hint), Correct on a stock row (quantity, category, history) and the Items form's duplicate handling are in the PWA (`docs/specs/retail-ui-notes.md`).
  Deferred: merging two products already created by mistake, a maker-checker threshold for the void and the correction, a dedicated ledger account for corrections.
- **Built (stock and restocks grouped by a chain, issue #350):** no migration, no permission. `GET /retail/purchases/summary` takes `group_by` as an ordered list from branch, year, month, supplier, day, category and item (`level`, and the parent selection as `branch_id`, `from`/`to`, `supplier_id`, `category_id`, `item_id`), one grouped query per level over the restocks' purchase movements, bounded (20 years, 12 months, 31 days, 100 shops, 50 of the others) with `has_more`, `next_to` or `next_offset`; a voided restock is counted apart, `total_minor` (what was bought at cost) only with `retail.profit.read` in every branch covered, the branch scope as the restocks list; the nil UUID is the key of "No supplier". `GET /retail/stock/summary` takes `branch` and `category` levels with the stock list's filters and answers items, negatives, value at price and, with the cost permission, at cost. `GET /retail/purchases` also takes `branch_id`, `category_id` and `product_id`. The Stock page and Past restocks stay flat until the person chooses Grouped, then offer the Group by control (`list-chain.tsx`, `list-chain-state.ts`, `stock-grouped.tsx`, `restocks-grouped.tsx`) with the screens' own rows under the last level, so Correct on a stock row and Correct this restock work inside the groups; the tours `page-stock` (version 3) and `page-restocks` (version 2) have a Grouped step (`docs/specs/retail-ui-notes.md`, `docs/sdd/07-api-design.md`, FR-RET-49).
- **Built (Controls page, issue #358, part of #357, ADR-047):** migration `V66`: the permission `retail.controls.read` (tenant administrator by default, branch scoped); no index (V34 serves the voids). `GET /retail/controls/summary`, `/groups`, `/records` and `/patterns` in `retail.reports`, read only, no new table: cards for chosen days (sales cancelled, cancelled within N minutes, goods taken back, prices changed, discounts, and sales below cost only for a caller with `retail.profit.read` in every branch covered) each with the trend against the previous period, the share of sales and a rate per 100 sales (sales with the event over sales entered, in the same units); the same by a chain of shop, person (whose sale it was, named, deactivated shown), day, reason and approver (who cancelled it), by name and never ranked, opening onto the individual records and the sale; and repeat patterns (the seller's rate more than 2 times the shop's own, from 3 cases over 10 sales, never the approver). The Controls page (`/staff/retail/controls`, `controls.tsx`, `controls-state.ts`) reuses the Group by control and the breadcrumb of the sales lists (now generic), exports the visible group as CSV, and has the welcome tour step (`admin-welcome` version 10) and the page tour `page-controls`. Restocks cancelled and quantities corrected (#356) have counts and values and no rate; credit write-offs (#349) get their card when that table is on `main`. Notes: `docs/specs/retail-ui-notes.md` (Controls).
- **Built (customer orders with delivery, issue #385, FR-RET-53 to FR-RET-57, ADR-052):** migration `V70` (renamed at merge in merge order): `retail_customer_orders`, `retail_customer_order_lines` and `retail_customer_order_events` (the history, append-only like the lines), `retail_stock_balances.reserved_qty`, the chart account 4120 `delivery_income` and the permission `retail.order.manage` (administrators and every role holding `retail.sale.create`). Routes `/retail/orders` (take, list by step, due day, source and search), `/board`, `/customer-lookup?phone=` (the last nine digits find the earlier orders and the credit buyers), `/{id}` (with its history), `PATCH /{id}` (name, phone, place, day, fee and notes under `If-Match`), `/{id}/pack`, `/send` (who takes it), `/deliver` (**M**, also `retail.sale.create` at the shop) and `/cancel` (with a reason), and `/reports/delivery-fees` (by day, source or rider) and `/reports/cancellations`. An order records its source (WhatsApp, Instagram, Facebook, phone call, walk-in, other), is priced by `SalesService.priceLines`, and keeps its items aside through `StockLedger.reserve` under the balance row lock, so two orders for the last unit are serialised and the till cannot sell a unit kept for an order (`insufficient_stock`, every guarded movement). Delivery releases the stock and makes the sale through `SalesService.record` in the same transaction (paid in full, part now with the rest on credit through `recordPayment`, or on credit with the fee paid now, or for a credit buyer on the list with the fee on the account), and posts the fee on its own to `delivery_income`; a cash fee counts in the cash book's takings on the day it is paid, and among the voids on the day its sale is voided. An unpaid fee on the account (shown apart on the account page, paid on its order, written off with its sale) is owed wherever a buyer's debt is read (the buyer list, the balance, the statement, credit control, the customer ageing, the credit counted when paid, the Store check). A price changed when the order was taken stays one override on the sale line (`price_overridden_by`, the person who took the order); any other agreed price that today's list prices otherwise is `order_agreed`, which the Controls page does not count. Delivery takes the sale's trusted path (no price override, price floor or inactive item check again; the credit limit counts what goes on credit); a credit buyer's fee may go on the account (`fee_on_account`, paid later through `/{id}/fee-payment`); voiding the sale reverses the fee; a branch with open orders is not closed or made a Store (`branch_has_open_orders`); the Stock list and the item search show free and kept stock. No capability, no maker-checker. The Orders page (`/staff/retail/orders`: the board, New order with the phone first, the list and the step buttons), one order (`/staff/retail/orders/$orderId`: its history, the details that may change, the sale it became) and Delivery fees and cancellations (`/staff/retail/orders-report`), with the page tours `page-orders`, `page-order` and `page-orders-report`, the retail menu shortcut Orders right after Sale and the tile on the retail home. Tests: `RetailCustomerOrdersIT`, `RlsIsolationIT`. Notes: `docs/specs/retail-ui-notes.md` (Orders).
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
- **Built (existing loans from a paper register, issue #466, FR-EXL-01 to FR-EXL-07, ADR-071):** migration `V91` (claimed
  after V81 to V90; merges after #290): the loan status `brought_in`, the columns `go_live_on`, `brought_in_on`,
  `register_ref`, `agreed_total_minor` and `opening_entry_id` on `lending_loans`, the append-only
  `lending_brought_in_repayments`, and the permission `lending.loans.bring_in` (tenant admin, branch manager). Routes under
  `/lending/existing-loans`: the go-live date, record with a check (`dry_run`) and an `Idempotency-Key`, list, get, cancel,
  ask again, and the CSV import (check, then commit). The maker-checker action `loan_bring_in` (checker
  `lending.disbursements.authorise`, no threshold) writes the schedule from the real date, historic transactions with no
  journal and one opening entry at go-live (debit loans receivable, credit opening balance equity). The arrears run charges
  no penalty period before `brought_in_on`. The go-live date is the tenant setting `go_live_date` (`PATCH /settings`, Owner
  only; `PUT /platform/tenants/{id}/go-live` for the operator), defaulting to the first administrator sign-in
  (`StaffDirectory.firstAdminSignIn`). The CSV reader of the retail item import moved to `kernel.CsvTable` so both imports
  share it. The PWA: `/staff/lending/existing-loans` (the form, its check and the loans waiting) and
  `/staff/lending/existing-loans/import`, linked from Loans, and an Open the loan link on a `loan_bring_in` approval. Tests:
  `ExistingLoansIT`, `BringInTest`, `RlsIsolationIT`, `existing-loans-state.test.ts`, `existing-loans.dom.test.tsx`.
- The isolation, boundary, ledger, API, actuator, route permission and contract tests run in
  `mvn verify`; CI runs them on every pull request. Staging runs on a shared ARM64 host behind a
  Cloudflare Tunnel and pulls every green build of `main` from a `staging` pointer tag; hosts are
  `{slug}-bms-staging.rincoltech.com` and `bms-staging.rincoltech.com` (ADR-018). The production
  VM is not provisioned yet; its deploy job skips with a notice until it is
  (`docs/runbooks/provision-host.md`).

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
| `core.platform` | Platform console: tenant creation, modules, subscriptions; the price book, payments entered by the operator and the Business page overview (ADR-056, issue #407) | TEN, SUB, SPM | 6.4, 6.5 | 7.11.3 |
| `core.feedback` (ADR-065, issue #447, migration `V80`) | In-app feedback: the staff send with an optional screenshot, the sender's own list, the hourly limit, the operator email through the outbox, and the public port `FeedbackInbox` the platform console serves the operator's list and status change through | FBK | 6.5 `feedback_items` | 7.11.25, 7.11.3 |
| `core.ledger` | Chart of accounts, periods, journals, reconciliation | GL | 6.6 | 7.11.6 |
| `core.notifications` | Templates, outbox, SMS and email adapters: the platform outbox with SMTP and Telegram senders (ADR-024) and the SMS channel (ADR-036, issue #212): provider port, Africa's Talking adapter, sink, delivery and cost log, opt-out, window, caps, startup guard; the tenant's SMS settings group, sender id allow-list, opt-out list, wording and test message (issue #218); the member events port `SmsEvents` that loans, savings and investments queue through (issue #221); Web Push (ADR-050): the `push` channel, the caller's own devices (`push_subscriptions`), VAPID keys and the handler of the kernel event `PushNotice` | NTF | 6.4 `notification_outbox`, SMS tables, 6.5 | 7.11.3, 7.11.7 |
| `core.onboarding` | Public sign-up, applications, Verify, Needs info, Reject, Activate (ADR-024) | ONB | 6.4 `onboarding_applications` | 7.11.3 |
| `core.documents` | PDFs, uploads, object storage, signed URLs | DOC | 6.5 `documents` | 7.11.7 |
| `core.reporting` | Report catalogue and runs: the queue, the runner job, one snapshot transaction per run into a spool, JSON, CSV, PDF and Excel files, cancel and the 30 day expiry (ADR-038, issues #214 and #217); verticals register their reports | RPT; chapter 14 | 6.5 `report_runs` | 7.11.8 |
| `core.imports` | Import batches, review queue, commit | IMP; chapter 13 | 13.4 | 7.11.9 |
| `core.payments` (ADR-035, issue #213, migration `V54`) | The `PaymentGateway` port, the Pesapal v3 adapter and an in-process fake, payment intents with a database-guarded state machine, the public notification endpoints (hints only), the status checker and poller, the `PurposeHandler` registry, each lender's own merchant account (issue #233, migration `V56`, `docs/runbooks/connect-lender-pesapal-account.md`) and the tenant switch; off unless configured (`docs/runbooks/payments.md`) | PAY; chapter 12 | 6.5 | 7.11.10 |
| `core.ai` (ADR-037, issue #220, migration `V51`) | The `LlmClient` port and one OpenAI-compatible adapter, the `AiGateway` every caller uses (tenant switch, platform allow-list, daily token and cost caps checked before any network call, circuit breaker, 30 second budget), the `ai_calls` log of counts and a request hash and never text, the settings group `ai` through `AiSettings`, a recording stub refused outside dev and test; off by default (`docs/runbooks/ai-assistant.md`); depends on no vertical | FR-AIA | 6.13 `ai_calls` | none |
| `core.jobs` | db-scheduler tasks, per-tenant job runner (ADR-008) | 5.4.4 | `scheduled_tasks` | none |
| `core.operations` | `/version`, readiness, database role guard, the `Idempotency-Key` protocol | NFR-SEC-03 | none | 7.8, 7.11.1 |
| `lending.manifest` | The vertical's registration with the core | 5.4.3 | none | none |
| `lending` members | Members, KYC, next of kin, relationships | MEM | 6.7 | 7.11.11 |
| `lending` products | Loan products and versions | PRD | 6.7 | 7.11.12 |
| `lending` loans | Origination, schedules, disbursement, repayments, arrears, closure; existing loans brought in from a paper register (ADR-071) | ORG, DIS, REP, ARR, LCL, EXL; 3.4 | 6.7 | 7.11.13 |
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
| `retail.purchasing` | Suppliers, restocks that set prices atomically; with the `purchasing` capability purchase orders, goods received, supplier invoices with the three-way match, supplier payments, the payables report, landed cost allocation and supplier credit notes (ADR-033) | RET-06, RET-11, RET-39 | 6.11 | 7.11.20 |
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
- ADR-066 The installed app uses the business's own logo and name as its icon: a per-host manifest and icons drawn from the logo by the
  API under `/api/v1/branding/app/`, the BMS icons as the fallback; an iPhone takes the icon only when the app is added again (proposed,
  #446; FR-TEN-08; amends ADR-050 decision 1; renumbered at merge if needed)
- ADR-071 Existing loans are brought in with their real dates, past repayments as history (no journal) and one opening entry at
  go-live, after a second person approves; only before the go-live date (a tenant setting the Owner or the operator sets);
  no penalty for the time before the bring-in (accepted, #466; FR-EXL-01 to FR-EXL-07; migration `V91`)
- ADR-046 Two-step sign-in is optional by default (migration `V67`) and a tenant policy can require it for everyone or for chosen roles after a grace
  period; one deadline, enforced-now versus due-by, the forgotten-password rules unchanged (proposed, #348; FR-IAM-06, FR-IAM-16)
- ADR-063 Production is created by Terraform on production day (`infra/production/`, a Hetzner root and an AWS root over shared
  cloud-init and Cloudflare modules, nothing applied before): the VM, its firewall, DNS, the R2 buckets with the ADR-058 lock and
  lifecycle rules and host tokens (the backup one cannot delete a locked backup; the DNS-01 one reaches the whole zone, an accepted risk); later updated by a signed pointer the owner moves with
  a local promote command, no deploy SSH key and no hosted job (proposed, #300, #301; provider, region and the promote command pending)

Pending (cite only as "pending ADR-NNN"):

- ADR-012 Credit scoring model beyond the rules-based default
- ADR-013 USSD aggregator (the SMS half is settled by ADR-036)
- ADR-021 Weighted average cost for retail, per tenant (ADR-020 decision 6)
- ADR-022 The cash book: expenses, banking and advances, likely partly core (ADR-020)

## Team

| Name | GitHub | Role |
|---|---|---|
| Hillary Arinda | @arindahills | Dev lead; required reviewer for production releases |
| Dennis Kaweesi | @Lovepankie | Developer |
| Solomon Ariho | @arihosolomon | Developer |
