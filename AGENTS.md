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
  (`V34` is the retail cash book, issue #147; `V35` is claimed by lending, issue #176; `V36` is claimed by lending payment methods, issue #160; `V37` is retail branch roles, issue #191; `V38` is the retail two-step transfer, issue #193; `V39` is units of measure, issue #194; `V40` is search, issue #241; `V41` is price lists, issue #195; `V42` is the loan reopen pledge guard, issue #176; `V43` is payment methods, issue #160; `V44` is customer accounts, issue #196; `V45` is returns, issue #201; `V46` is optional documents, issue #197; `V47` is optional purchasing, issue #198; `V48` is landed cost and supplier credit notes, issue #199; `V49` is report runs, issue #214; `V50` is the SMS channel core, issue #212; `V51` is the `core.ai` call log, issue #220; `V52` is the tenant SMS settings, issue #218; `V53` is the member event SMS cancel, issue #221; `V54` is the payment gateway core, issue #213; `V55` is the stored daily AI brief and the advisor permissions, issue #226; `V56` is the lenders' own payment accounts and `V57` their permission, issue #233; `V58` is the step-up failure counter; `V59` is the loan repayment by gateway and the unallocated receipts, issue #216; `V60` is the savings deposit by gateway, issue #219; `V62` is the staff forgot-password migration, issue #320; `V65` is the store branch flag, issue #366). A migration takes the highest number on main plus one at merge time and never a number above one that is still unmerged; a placeholder `V99__` is refused by `placeholder-migration-guard` and by the first job of `deploy.yml`. The retail analytics pass (issue #149) is reports only on existing data: no migration; its
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
  Deferred: arrears job, penalties, waivers and SMS (increment 6), import (7), reports and the
  receipt, voucher and statement PDFs (8 and a documents follow-up).
- **First run and guided tours (issues #19 and #86, ADR-025):** accepting an invitation signs the
  user in; the PWA walks a new user through password, an optional second factor and recovery codes;
  role aware spotlight tours (`frontend/src/tour/`) start once and replay from Help, with per-user
  progress in `users.preferences` (`V31`). A new screen adds `data-tour` anchors and its steps.
- **Built (staff Users screen, issue #319):** PWA only, no migration, no API change: `/staff/users` (list with search, roles with branch,
  status invited, active or deactivated) and `/staff/users/new` (phone-first invite, role pick-list with plain descriptions, branch
  pick-list unless the role is the administrator). The one-time invitation link is shown once with Copy and Send on WhatsApp; resend,
  change roles and deactivate follow the existing `/users` routes. Visible to `core.users.read`, actions to `core.users.manage`; the
  `staffAdmin` tour flag is on (`docs/sdd/07-api-design.md` section 7.11.4, UI notes).
- **Built (forgot password, issue #320, FR-IAM-15, ADR-042):** migration `V62`: `user_password_resets` (RLS, the token as SHA-256 only, `self`, `admin` or `operator`, one hour, single use, a newer link revokes the older) and `password_reset` as a session revoke reason. `POST /auth/staff/password/forgot` always answers the same 202 after a 400 ms floor (20 an hour per address, 3 links an hour per account) and sends the link by email through the outbox (`identity.password_reset`); `POST /auth/staff/password/reset` sets the password under the invitation's rules, ends every session of the user, emails a notice, and signs in under the sign-in rules (a user with TOTP still needs the code; the reset does not clear the lock); `POST /users/{user_id}/reset-sign-in` (`core.users.manage`, a fresh authenticator code in the body, or the admin's own password when they have none enrolled) returns the link once to a tenant admin for an active user who is not a tenant admin and not themselves, and `POST /platform/tenants/{tenant_id}/users/{user_id}/reset-sign-in` does the same for a tenant admin, through the platform operator, with the operator's own fresh authenticator code (as the platform's `mfa/reset` now also needs); the tenant admin's route has the Users screen's Reset sign-in button (issue #339), the platform operator's route is API only; the user is emailed when a link is issued; no link by email for a user whose role or tenant actually requires a second factor not yet set up (two-step sign-in is optional; policy ticket #348). The sign-in page's Forgot password link, `/forgot-password` and `/reset-password`, and `resetUserSignIn(userId, credential: {totpCode?, password?})` in `frontend/src/api/sign-in-reset.ts`, which the Users screen's Reset sign-in button calls (issue #339: for an active person who is not an administrator and not oneself, with the code from the administrator's own authenticator app or their own password, the link shown once in the link box; `page-users` tour version 2).
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
├── deploy/              host-side files: compose.yml, deploy.sh, backup.sh, Caddy, SQL (SDD ch. 9)
├── scripts/db-bench/    throwaway PostgreSQL benchmark at 25 times the data (ADR-028); never production
├── fixtures/            fabricated test data only (pilot register sample)
├── docs/
│   ├── workspace.dsl    Structurizr C4 model; wires in sdd/ and adr/ as one site
│   ├── sdd/             System Design Document, chapters 01 to 15
│   ├── adr/             Architecture Decision Records
│   ├── specs/           MVP scope, pilot data dictionary
│   ├── api/             OpenAPI snapshot (generated) and contract drafts
│   ├── runbooks/        deploy, rollback, restore, onboarding a tenant
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
| `core.platform` | Platform console: tenant creation, modules, subscriptions | TEN | 6.4, 6.5 | 7.11.3 |
| `core.ledger` | Chart of accounts, periods, journals, reconciliation | GL | 6.6 | 7.11.6 |
| `core.notifications` | Templates, outbox, SMS and email adapters: the platform outbox with SMTP and Telegram senders (ADR-024) and the SMS channel (ADR-036, issue #212): provider port, Africa's Talking adapter, sink, delivery and cost log, opt-out, window, caps, startup guard; the tenant's SMS settings group, sender id allow-list, opt-out list, wording and test message (issue #218); the member events port `SmsEvents` that loans, savings and investments queue through (issue #221) | NTF | 6.4 `notification_outbox`, SMS tables, 6.5 | 7.11.3, 7.11.7 |
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
| `retail.catalogue` | Categories, units, products, append-only price history | RET-01, RET-02 | 6.11 | 7.11.20 |
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
demo tenant's admin (open it, set a password, sign in at `/sign-in` and enrol TOTP) and a setup
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
   request bodies never appear in logs.
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
- ADR-045 A discontinued product is marked inactive, never deleted: refused on sales, restocks and transfers with 422
  `product_inactive`, left out of the default stock lists (`include_inactive`), its stock shown as a warning (proposed, #342)

- ADR-043 Credit sales count when the buyer pays (a per-tenant setting, `on_sale` by default), and a write-off for buyers who
  never pay (proposed, #340; amends ADR-022 decision 11 for a tenant that chooses `on_payment`)
- ADR-042 A staff user who forgot the password gets back in with a one-time link, by email or from a tenant admin; the reset
  never touches the second factor or the lock, and the form tells nobody whether an account exists (proposed, #320; FR-IAM-15)
- ADR-047 The Controls page: read-only counts of voids, returns, changed prices, restocks cancelled and quantities corrected by
  shop, person (whose sale it was), day, reason and approver; rates over sales, no league tables (proposed, #357 group 1, #358)

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
