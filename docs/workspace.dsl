workspace "BMS Platform" "Multi-tenant business management platform: core plus vertical modules, lending first" {

    !identifiers hierarchical

    # Wire the prose SDD chapters and the ADR log into the rendered workspace, so the
    # model (this file), the decisions (adr/) and the documentation (sdd/) render as one
    # browsable SDD. Paths are relative to this .dsl file.
    !docs sdd
    # ADRs are pulled as docs, not via !adrs: the !adrs directive requires filenames
    # beginning with a number (0001-slug.md), but these are named ADR-001-slug.md, which
    # !adrs cannot parse (NumberFormatException).
    !docs adr

    properties {
        "structurizr.dslEditor" "false"
    }

    model {

        # ==================================================================
        # PEOPLE (SDD chapter 2 section 2.2; permissions in chapter 8)
        # ==================================================================
        platformOperator = person "Platform Operator" "Runs the platform: creates tenants, manages plans and subscriptions, supports. Super admin role."
        tenantAdmin      = person "Tenant Admin" "Owner or general manager of a tenant. Configures users, branches, products and settings."
        branchManager    = person "Branch Manager" "Approves loans and checker actions for their branches; supervises collections."
        loanOfficer      = person "Loan Officer" "Registers members, captures and appraises applications, follows up arrears."
        cashier          = person "Cashier" "Records disbursements, repayments, deposits, withdrawals and payouts."
        accountant       = person "Accountant" "Keeps the general ledger, closes periods, runs financial reports."
        auditor          = person "Auditor" "Read-only access to records, reports and the audit log."
        applicant        = person "Applicant" "A business that applies on the public sign-up page of the platform host; has no account until the operator activates it (ADR-024)."
        member           = person "Member" "A tenant's borrower, saver or investor. Uses the member area, receives SMS; USSD in phase 2."

        # ==================================================================
        # BMS PLATFORM (the system under design)
        # ==================================================================
        bms = softwareSystem "BMS Platform" "Multi-tenant SaaS: platform core plus vertical modules a tenant switches on (ADR-001). Lending is the first vertical." {

            proxy = container "Reverse Proxy" "Routes /api to the API and everything else to the PWA for the single-label hosts under rincoltech.com: bms, <slug>-bms and bms-callbacks (ADR-018); security headers; request size limits. Production: terminates TLS with a DNS-01 wildcard certificate. Staging: the internal origin behind the Cloudflare Tunnel, plain HTTP." "Caddy with the Cloudflare DNS module (SDD chapter 9)" "edge"

            web = container "Web App" "One installable PWA with a staff area, a member area and the platform console, split by route and lazily loaded (ADR-009). A step by step first run after an invitation, and role aware guided tours kept as data, replayable from Help (ADR-025). Every staff route has a page tour, breadcrumbs and cross links, enforced by a CI guard (ADR-049). Menus, tiles, routes and a tour per capability follow the modules and capabilities `/me` lists, with the administrator's Retail settings screen for the shop or trade profile (ADR-033). Retail sales work offline: the sale catalogue and an outbox of sales per business and person in IndexedDB, synced one at a time, oldest first, with a status dot and the count waiting in the staff bar (ADR-067). Static files served by Caddy." "React 18, TypeScript, Vite, vite-plugin-pwa" "client"

            api = container "API" "Modular monolith (ADR-002). REST under /api/v1; one database transaction per request, bound to the tenant (ADR-003). Also runs the worker role: db-scheduler jobs on PostgreSQL (ADR-008)." "Java 25, Spring Boot 4.1, Spring Modulith 2.1 (ADR-010)" "app" {

                # ---------------- core ----------------
                tenancy       = component "Tenancy" "Tenants, plans and limits, module switching, settings (including the tenant's two-step sign-in policy and its grace deadline, ADR-046, and a vertical's settings group, stored as opaque jsonb and validated by the vertical, ADR-033), branding (logo, theme colour, and the installed app's manifest and icons drawn from the logo per host, ADR-066), branches (each shop's optional own logo, set by the administrators and served publicly like the business logo, the letterhead of that shop's documents, ADR-068; create, rename, close and reopen with history kept, the not-stocking flag, ADR-041, and the Store flag for a branch that holds stock and does not sell, ADR-048; the retail module reports what it still holds through the branch closing check port and what is open at its till through the branch selling check port). Resolves the tenant from the host, binds app.tenant_id, and makes a suspended tenant read only." "core" "core"
                identity      = component "Identity and Access" "Staff and platform sign-in (argon2id, TOTP with recovery codes, ADR-014), revocable server-side sessions, invitations whose acceptance signs the user in (ADR-025), the forgotten password (a one-time hashed link by email or from a tenant admin, every session ended on use, the second factor untouched, ADR-042, and the failed-attempt lock lifted, ADR-059), two-step sign-in that is optional by default and is required, once a grace period ends, of the users a tenant's policy covers (enforced now versus due by, ADR-046), roles, permission matrix, per-permission branch scope (a role per shop or store a person works in; the retail Storekeeper and Purchasing roles; an administrator always covers every branch, a database check since V73, ADR-055), the Owner of each business (users.is_owner, V79, ADR-062: one per business, an administrator who alone adds, removes or changes administrators, whom nobody inside the business removes, handed on by a transfer with a step-up or by the operator with a reason), and each user's own guided tour progress (users.preferences, migration V31). Sign-in limits (ADR-059): the five-failure lock counted in the database for every address typed, an address of no account in sign_in_address_failures (V78) exactly as an account, so the lock survives a restart and answers alike; in memory only failed sign-ins per client IP, reset requests and second-factor attempts, a drained bucket never evicted; an audited unlock by a tenant admin or the platform operator; at most four password hashes at once, never inside a transaction, with 503 sign_in_busy and Retry-After beyond." "core" "core"
                audit         = component "Audit" "Append-only audit log written in the same transaction as each change; platform audit log; search and CSV export." "core" "core"
                approvals     = component "Approvals" "Maker-checker requests with payload snapshots; executes approved actions through actions registered by the owning modules (ADR-015)." "core" "core"
                platform      = component "Platform Console" "Tenant creation with head office, modules and first admin; module switching; subscriptions and suspension with a reason; tenant admin MFA and sign-in reset, and the audited unlock of a tenant user's sign-in lock (ADR-059); the price book by plan with prices from a date, payments received entered by the operator behind an Idempotency-Key, the plan and dates of a tenant, the businesses list and the Business page overview, and the free month started at the first staff sign-in (ADR-056); the feedback of every business through the Feedback component's FeedbackInbox port, its screenshot read with that business bound (ADR-065); on a business's own host, the Owner's Plan and modules (ask for a module, stop one, cancel, undo, effective at the end of the paid period), the operator's approval or decline of module requests and the list of changes to come, and the quarter-hour job that applies them on their day; the business read only after a cancellation's end date (ADR-073). The operator console is served only on the platform host to platform operators (ADR-016, ADR-018)." "core" "core"
                feedback      = component "Feedback" "In-app feedback (issue #447, ADR-065): any signed-in member of staff sends an idea, a problem or a question with an optional PNG or JPEG screenshot (a core document), the page path, the running version and the user agent captured; the sender lists their own with the status and the operator's reply; ten an hour per person, counted in the database; each new item emails every active platform operator through the outbox in a savepoint; the public port FeedbackInbox gives the platform console the list across businesses and the forward-only status change and the linked issue through the definer functions of V80." "core" "core"
                referrals     = component "Referrals" "Referral links and commission tracking (issue #464, ADR-069): a code and a sign-up link for every business (a trigger on tenants), the code an applicant brings resolved at the sign-up and made the referral at Activate, one current referrer per business and never itself; commission rules the operator types (a platform default, seeded by V88 as the product owner's 10% for the first 12 paid months with no cap, and an override per referral), the line worked out from the rule in force when the operator records a referred business's payment, manual lines, reversals and paid marks, never deleted and never paid by the app; the business's own referrals for its owner and administrators; the public port Referrals for onboarding and the platform console, through the definer functions of V88." "core" "core"
                onboarding    = component "Onboarding" "Public sign-up and applicant page endpoints with rate limits and a honeypot; the applications queue with the possible-repeat warning; Verify, Needs info, Reject; Activate, which creates the tenant through the platform console's TenantProvisioning and queues the activation email in the same transaction; nightly expiry and 90 day deletion (ADR-024)." "core" "core"
                ledger        = component "General Ledger" "Chart of accounts, periods, post_entry, reversals, trial balance, subledger reconciliation (ADR-004)." "core" "core"
                notifications = component "Notifications" "Outgoing message port with a recording fake adapter, and the platform outbox (ADR-024): rows written in the cause's transaction, a sender job with SKIP LOCKED claims, SMTP email over implicit TLS, every email multipart with an HTML part built from the same template (the Rincol Tech logo header, or a business logo on its own staff emails, one button for the main link, a footer with the reason and the optional support contact; ADR-054), and Telegram operator alerts, each off when unconfigured (except email in production, where the API refuses to start without SMTP, ADR-059); failed rows for the operator portal. SMS is a channel of the outbox (ADR-036, built in issue #212): the row carries its tenant, category and subject; the claim takes transactional rows first and rotates across tenants; a channel answers sent, deferred, unknown or refused; the SmsProvider port has an Africa's Talking adapter, a non-sending sink (the only provider outside production), an allow-list provider and a recording fake; a startup guard refuses any non-sandbox URL or account outside production in every mode; a tenant delivery and cost log, the opt-out, the sending window, a platform ceiling and operator-only per tenant switch and cap, strict phone forms, and a backlog drop when sending is switched on. The tenant's own SMS settings (issue #218, ADR-036 amendment): the settings group sms (the tenant switch with its acknowledgement, a sender id chosen only from the operator's allow-list, the window, daily and monthly caps that can only be lowered, the balance switch), the opt-out list (member rows and number rows, a pasted list, masked numbers), the tenant's own wording per event in a safe placeholder language with a segment and cost preview, and a test message that reaches only the sink or the allow-list. The member events (issue #221, ADR-036 third amendment): the public port SmsEvents, which the loan and investment modules call in the transaction of the event (a loan disbursed, a repayment received, a loan closed, a savings receipt, an investment funded, maturing or paid out): it runs the whole preparation, lookups included, inside a savepoint (so nothing that goes wrong reaches the money), reads the number without guessing, writes one outbox row per event and member under a deterministic key, drops the balance unless the tenant asked, skips an old value date, and withdraws the unclaimed message of a transaction when it is reversed; it leaves the switches, the switch-on time, the opt-out, the window, the caps and the wording to the channel." "core" "core"
                documents     = component "Documents" "PDF rendering, uploads, object storage keys, signed download URLs." "core" "core"
                reporting     = component "Reporting" "Report catalogue, parameters and asynchronous report runs (issue #214, ADR-038): a queued run, the runner job, one read-only REPEATABLE READ snapshot transaction into a disk spool that commits before the JSON, CSV, PDF or Excel file is rendered and stored, cancel, the 30 day file expiry, off until the tenant switch is on. The PDF goes through the shared report template of Documents under the tenant letterhead, the Excel through a minimal streaming writer (issue #217)." "core" "core"
                imports       = component "Imports" "Batches, rows, issues, review queue, preview, commit orchestration; templates are registered by modules." "core" "core"
                payments      = component "Payments" "Payment intents, the Pesapal v3 gateway adapter (ADR-035): each lender's own merchant account (credentials entered by the lender, sealed with the application data key, one account per tenant, never the platform's; ADR-035 amendment of 2026-10-18), hosted orders with a random merchant reference, notifications as untrusted hints confirmed by an authenticated status query, idempotent booking through the registered purpose handlers (loan repayment, savings deposit, subscription payment), unallocated receipts, nightly reconciliation, merchant statement upload." "core" "core"
                ai            = component "AI" "LlmClient port and one OpenAI-compatible adapter (ADR-037, off by default and off without a complete key, URL and model): the gateway checks the tenant switch, the platform allow-list, per call and daily token and cost caps for the tenant and the platform before any network call, then a circuit breaker and a 30 second budget with retries on 429 and 5xx; a recording stub for dev and test, refused elsewhere; call log of counts and a request hash, never text. Depends on no vertical." "core" "core"
                jobs          = component "Jobs" "The worker role: db-scheduler tasks with state in PostgreSQL, run one tenant per transaction (ADR-008). Drains the outbox; nightly arrears, interest, reminders, reconciliation, key purge." "core" "core"
                operations    = component "Operations" "/healthz, /readyz (database and migrations at head), /version; refuses to start as an over-privileged database role; the one Idempotency-Key protocol (chapter 7 section 7.8) every money-moving retail and lending route claims its key through, inside the caller's transaction (issue #177)." "core" "core"

                # ---------------- lending vertical ----------------
                members       = component "Lending: Members" "Members, KYC, next of kin, relationship graph and exposure." "lending" "lending"
                products      = component "Lending: Loan Products" "Versioned loan products, fees, schedule preview." "lending" "lending"
                loans         = component "Lending: Loans" "Origination, appraisal, approval, schedules, disbursement, repayments, arrears, penalties, closure, write-off; existing loans brought in from a paper register (ADR-071)." "lending" "lending"
                collateral    = component "Lending: Collateral" "Collateral register, valuations, custody events, photos and scans; release as a maker-checker action (ADR-019)." "lending" "lending"
                savings       = component "Lending: Savings" "Savings products, member accounts, deposits, withdrawals with a checker above the threshold, reversals, freeze, dormancy, closure, the nightly end of day with interest per product period, statements and the savings reports (ADR-032)." "lending" "lending"
                investments   = component "Lending: Investments" "Fixed-term and recurring investment products, member investments, funding with a checker above the threshold, the accrual and payout schedule, the nightly returns and maturity job, payout, rollover, early withdrawal with a penalty, reversals, certificates, statements and the maturity ladder (ADR-031)." "lending" "lending"
                lendingSeed   = component "Lending: Fabricated Seed" "The seed-lending command: fills one empty staging tenant with fabricated members, products, applications, serviced loans, savings accounts with a year of movements and investments with twelve months of history; refused in production and on a tenant holding data (ADR-026, ADR-032)." "lending" "lending"
                insights      = component "Lending: Insights" "The insights read model (ADR-030): morning brief, portfolio, revenue from posted journal lines, member activity, drill-down tables with CSV export; the nightly per-loan snapshot until increment 6 owns it; the owner's plain-text daily digest; the panel registry savings and investments plug into." "lending" "lending"
                collections   = component "Lending: Collections" "Due and arrears lists, officer assignment, collection actions, promises to pay." "lending" "lending"
                advisor       = component "Lending: AI Advisor" "Advisory daily brief and question box over insights facts (ADR-037): aggregates and aliases only, structured output only (the model writes no text; findings from a reviewed catalogue are rendered by our code), fallback to the deterministic brief, off by default, advisory label. Built: the alias mapper, the prompt builder, the structured validator and renderer, the catalogue and the offline evaluation harness (issue #223); the daily brief job, the stored all-branches brief (lending_ai_briefs), the brief and settings routes and the Insights card (issue #226); the question box follows (#230)." "lending" "lending"
                lendingReports = component "Lending: Reports" "The definitions of the formal reports, built on the insights figures through the public InsightsFigures port: lending.par (issue #214) and lending.portfolio_outstanding (issue #217), each as PDF, Excel, CSV and JSON; collections, disbursements, officer performance, collateral register, regulator summary and the monthly pack follow (ADR-038)." "lending" "lending"

                # ---------------- retail vertical (ADR-020) ----------------
                retailCatalogue = component "Retail: Catalogue" "Categories, units, products with current cost and sell price; append-only price history written with every price change; pack sizes of a product (a unit and its factor in base units) behind the units capability; price lists, their dated items with quantity breaks and customer groups behind the price_lists capability. A product name is kept once per unit (a generated name key, issue #352, ADR-044): a repeat is refused with the existing item named, an administrator may add it anyway with a reason, a close name is a warning, and a voided restock gives back the prices it set while it is still the latest price event (issue #351). Items with sizes and colours (issue #384, ADR-051): an item groups variants, each a product with its own code, prices and stock; every combination of the sizes and colours chosen once is made, and an item without variants can be given sizes in place. Barcodes (issue #345, ADR-070): several per product or pack size, EAN-13, EAN-8, UPC-A with check digits or Code 128, unique per business among active items, a scan lookup, and Code 128 label codes from the business's sequence." "retail" "retail"
                retailStock     = component "Retail: Stock" "Append-only stock movements and per-branch balances kept in the same transaction under a row lock; stock-takes; stock transfers between branches at cost, one entry per branch through inter-branch clearing, optionally two-step (dispatched, then received or cancelled, a shortage valued at the source cost) behind the transfer_receipt capability; the stock-by-location and transfers-in-flight reports; nightly reconciliation of balances against movements; retail posting rules; branch roles (store, shop, both) and the capability reads (ADR-033); the not-stocking refusal (`branch_not_stocking`) for sales, restocks, transfers and stock adjustments, the closed branch left out of the default all-branches list, and the branch closing check that reports stock and transfers on the road (ADR-041). The quantity of an item at a branch is corrected on the stock list with a reason: one adjustment movement of its own source type, with the old and new quantity, that no shrinkage or usage report counts (issue #351, ADR-044). Stock kept aside for customer orders (`reserved_qty` on the balance row, reserved and released under the row lock, ADR-052): a sale, usage, damage or transfer out may not take a balance below it." "retail" "retail"
                retailManifest  = component "Retail: Manifest" "The retail registration with the core: the module key and the retail.capabilities settings group (eight capability flags, shop or trade profile preset), validated here and stored by the core (ADR-033)." "retail" "retail"
                retailSales     = component "Retail: Sales" "Sales with unit cost and price snapshots, credit buyers, payments against credit sales, voids by reversal; revenue and cost of goods sold posted per branch; a line may be entered in a pack unit and is converted to base units exactly; with price lists on, the server resolves each line's price (the sale's list, the customer's, the group's, the quantity break) and stores it with its source; with customer accounts on, a credit sale locks the buyer and holds to their credit limit, and a payment on account is allocated to the buyer's open sales, reversible, with the account statement; with returns on, goods are taken back against a sale as a numbered credit note (restocked at the sale line's cost into a chosen branch or written off, repaid in money or off the buyer's balance), voidable by reversal, and above a tenant threshold a maker-checker action.; a credit sale whose buyer never pays is written off with retail.credit.write_off, administrators only (ADR-043): the unpaid debt leaves trade debtors against a bad debt loss, the sale stays and reads Written off, and it is a maker-checker action above a tenant threshold or, with none set, whenever two or more administrators count (one alone may write off, audited, and each administrator is emailed through the outbox); under the one-way tenant rule credit_sale_recognition on_payment a payment on a sale counted when the buyer pays is dated today; a sale, a payment taken and a refund paid are refused at a Store (`branch_does_not_sell`, ADR-048), and the branch selling check reports credit sales still owing before a branch is marked as a Store; with documents on, numbered quotations, invoices and delivery notes issued after the fact from a committed sale, transfer or quotation, the credit note PDF of a return, and the customer statement as a PDF; customer orders with delivery (ADR-052): an order taken from WhatsApp, Instagram, Facebook, a phone call or the counter, priced as a sale would be, keeps its items aside on the balance rows, moves through packed and out for delivery, and on delivery becomes an ordinary sale through the sale path in one transaction (paid in full, part now and the rest on credit, or on credit with the fee paid now), its delivery fee posted on its own to delivery fees income; the items of an order that is ordered or packed change in place, keeping its number, the stock kept following in the same transaction (issue #393); cancel releases the stock with a reason; the history of each step, the board of the day, the phone lookup and the delivery fee and cancellation reports; a seller without retail.sale.all_sellers sees only their own sales, totals and sale pages, apart from the debts still owed (ADR-055); the receipt of a sale as a PDF, rendered now and never stored, for the seller to share from their own phone through its share sheet or wa.me, own sales only, nothing sent from a server (ADR-061); a sale made offline is recorded once by its client sale id, dated when it happened, with stock, price, pack, item and clock clashes recorded and flagged, other refusals reported for a manager to resend or discard, and the sales left waiting on a shared phone listed in short (ADR-067)" "retail" "retail"
                retailReports    = component "Retail: Reports" "Stock valuation at cost and expected sales at price per branch, the revaluation difference against the inventory account, daily profit per branch from the sale snapshots; the analytics reports (sales analysis, margins, stock health with its slow movers and where each sells better, credit control, business evaluation, owner dashboard, which follows a chosen range against the period before, ADR-060); with the one-way tenant setting credit_sale_recognition on_payment (ADR-043, from the next business day) the daily profit, the dashboard and the savings suggestion count a credit sale only as the buyer pays (Sold, Received, On credit), reports that hold days from the start on, or a credit sale counted when the buyer pays, are read that way and any other report as it always was; the Controls page (voids, returns, changed prices and sales below cost by shop, person, day and reason, with a rate per 100 sales and repeat patterns, behind retail.controls.read, ADR-047); cost and profit only with retail.profit.read." "retail" "retail"
                retailPurchasing = component "Retail: Purchasing" "Suppliers and restocks that set product prices, with history, in the same transaction as the stock movements and the per-branch journals; a line may be entered in a pack unit, the pack cost remainder posting to purchase rounding. With the purchasing capability on (ADR-033): purchase orders numbered per branch, a draft until sent (a maker-checker action above a tenant threshold), goods received into a chosen branch or store against an order or blind through the restock rules and posted to goods received not invoiced, supplier invoices matched by line with the price variance posted, supplier payments allocated to open invoices and credit restocks and reversible, the supplier payables report, landed cost allocation (charges of one payee spread over received lines in exact integers, the share for goods on hand to inventory and for goods already sold or moved to cost of goods sold, the product's cost moved only for the latest cost event, the payee's invoice a supplier bill of kind landed cost), supplier credit notes against an invoice (goods sent back or a price adjustment) and a hold on a receipt that is not yet costed. A restock entered wrongly is voided by reversal, never deleted: its movements and entries are reversed in one transaction and a void row records who, when and why (issue #351, ADR-044)." "retail" "retail"
                retailImports    = component "Retail: Imports" "The one-off import-retail command: reads a normalised JSON Lines export and writes historical documents and movements (no journals), legacy balance movements, the optional cash book tabs (ADR-022 decision 18; one cash opening journal per branch) and one opening journal per branch, keyed by source reference so a re-run adds nothing (ADR-020 decision 9)." "retail" "retail"
                retailCashbook   = component "Retail: Cash book (ADR-022)" "Daily savings with the profit-based suggestion, cash banked with the server-computed expected amount, withdrawals from the bank, company expenses by category and item, advances to the owner or company with repayments; every event voidable and posted through the ledger; daily cash summary, banking and expenses reports." "retail" "retail"
            }

            migrate = container "Migrate" "One-shot container run before the application containers switch: applies the Flyway migrations as bms_owner, then exits (ADR-006)." "API image, migrate command" "app"

            db = container "Database" "System of record for all tenants and the job store. Shared schema, forced row-level security on every tenant-owned table, double-entry ledger with a deferred balance trigger. No Redis (ADR-008)." "PostgreSQL 16" "store"

            storage = container "Object Storage" "Generated PDFs, uploads and encrypted database backups. Private buckets; tenant-prefixed keys; signed URLs only." "Cloudflare R2 (S3 API)" "store"
        }

        # ==================================================================
        # EXTERNAL SYSTEMS (SDD chapter 12)
        # ==================================================================
        smsAggregator  = softwareSystem "SMS Provider" "Outbound SMS and delivery reports through Africa's Talking (ADR-036); USSD sessions in phase 2 are pending ADR-013." "external"
        paymentGateway = softwareSystem "Payment Gateway (Pesapal)" "Hosted mobile money and card collections in UGX, notifications, transaction status queries (ADR-035). No payouts, no settlement API." "external"
        mobileMoney    = softwareSystem "Mobile Money Operators" "MTN MoMo and Airtel Money wallets. Reached only through the payment gateway." "external"
        pushService    = softwareSystem "Browser Push Service" "The browser vendors' Web Push services (Chrome and Edge, Firefox, Safari and iOS, Windows): accept an encrypted message signed with the server's VAPID key and deliver it to a subscribed browser; a subscription is per user per device (ADR-050)." "external"
        emailService   = softwareSystem "Transactional Email Service" "SMTP over implicit TLS (port 465), every email multipart HTML and plain text (ADR-054): activation and applicant links, operator alerts, staff password reset links (ADR-042); later staff invitations, report-ready notices (ADR-024)." "external"
        telegram       = softwareSystem "Telegram Bot API" "sendMessage to the operator chat: alerts for new applications (ADR-024), and the host checks' site, job, disk, certificate, backup and restore drill alerts (ADR-058)." "external"
        uptimeMonitor  = softwareSystem "Uptime Monitor" "A hosted uptime service outside our hosts (ADR-058): checks /healthz and one tenant sign-in page every minute, alerts after three failures, and expects a heartbeat from the host checks every five minutes. Sees public URLs only." "external"
        llmProvider    = softwareSystem "Language Model Provider" "OpenAI-compatible chat API (DeepSeek as a first test, ADR-037). Receives aggregated, aliased portfolio figures only; off by default." "external"

        # ==================================================================
        # PEOPLE TO SYSTEM
        # ==================================================================
        platformOperator -> bms.web "Creates and supervises tenants in the platform console; verifies and activates applications in the operator portal"
        applicant        -> bms.web "Applies on the sign-up page and follows the application on the applicant page"
        tenantAdmin      -> bms.web "Configures users, branches, products and settings"
        branchManager    -> bms.web "Approves loans and checker actions"
        loanOfficer      -> bms.web "Registers members, captures and appraises applications, logs collections"
        cashier          -> bms.web "Records disbursements, repayments, deposits and withdrawals"
        accountant       -> bms.web "Keeps the ledger, closes periods, runs financial reports"
        auditor          -> bms.web "Reads records, reports and the audit log"
        member           -> bms.web "Views balances and schedules, applies, invests, pays"
        member           -> mobileMoney "Approves payment prompts with the wallet PIN"
        member           -> smsAggregator "Uses USSD menus (phase 2)"
        smsAggregator    -> member "Delivers receipts, reminders and one-time codes by SMS"

        # ==================================================================
        # CONTAINER LEVEL
        # ==================================================================
        bms.proxy  -> bms.web "Serves the PWA bundle" "HTTPS"
        bms.proxy  -> bms.api "Routes /api/v1 and provider callbacks" "HTTP"
        bms.web    -> bms.api "Calls the REST API through the reverse proxy" "JSON over HTTPS"
        bms.api    -> bms.db "Reads and writes, one transaction per request with app.tenant_id bound" "SQL"
        bms.api    -> bms.storage "Stores uploads; issues signed download URLs" "S3 API"
        bms.migrate -> bms.db "Applies migrations as bms_owner" "SQL"
        paymentGateway -> mobileMoney "Collects from the payer's wallet"
        smsAggregator  -> bms.proxy "Delivery reports and USSD sessions on the callback host" "HTTPS"
        paymentGateway -> bms.proxy "Payment callbacks on the callback host" "HTTPS"
        uptimeMonitor  -> bms.proxy "Checks /healthz and a tenant sign-in page every minute" "HTTPS"
        uptimeMonitor  -> telegram "Posts down and recovery alerts to the operator chat" "HTTPS"

        # ==================================================================
        # COMPONENT LEVEL (inside the API)
        # ==================================================================
        bms.web -> bms.api.identity "Signs in; loads permissions and branch scope"
        bms.web -> bms.api.tenancy "Tenant settings and branches; public branding (logo, theme colour, enabled modules) for the shell and the landing page; the installed app's manifest and icons (ADR-066)"
        bms.web -> bms.api.platform "Operator portal: businesses, the Business page, plans and prices, payments, suspend and resume (ADR-056)"
        bms.web -> bms.api.feedback "Staff: sends feedback with an optional screenshot and reads their own with its status and reply (ADR-065)"
        bms.web -> bms.api.platform "Owner: the Plan and modules card in Business set-up, asks for or stops a module and cancels; every member of staff: the end-date banner and the read-only notice (ADR-073)"
        bms.web -> bms.api.platform "Operator portal: the feedback of every business, filter by status, mark seen or done, reply, the screenshot (ADR-065)"
        bms.web -> bms.api.audit "Searches and exports the audit log"
        bms.web -> bms.api.members "Registers, searches and verifies members"
        bms.web -> bms.api.products "Configures loan products; previews schedules"
        bms.web -> bms.api.loans "Applications, appraisal, decisions, disbursement and repayment requests; Home's Lending card reads GET /lending/loans/summary (ADR-072)"
        bms.web -> bms.api.collateral "Registers and releases collateral"
        bms.web -> bms.api.savings "Savings products, accounts, deposits, withdrawals, statements and reports"
        bms.web -> bms.api.investments "Sets up investment products; opens, funds, pays out, rolls over and withdraws investments; reads the maturity ladder"
        bms.web -> bms.api.collections "Due lists, arrears lists, collection actions"
        bms.web -> bms.api.approvals "Checker queue and decisions"
        bms.web -> bms.api.ledger "Chart of accounts, manual journals, period close"
        bms.web -> bms.api.reporting "Reports page: the catalogue, queues a run, polls its status, cancels, then fetches the file through the signed URL" "JSON/HTTPS"
        bms.web -> bms.api.imports "Uploads, reviews and commits imports"
        bms.web -> bms.api.payments "Starts mobile money payments; polls their status; Receive payments saves the lender's own consumer key and secret (write-only) and tests the connection"

        bms.api.loans       -> bms.api.members "Reads KYC, relationships and exposure"
        bms.api.loans       -> bms.api.products "Reads product version terms"
        bms.api.loans       -> bms.api.collateral "Reads pledged collateral and cover"
        bms.api.loans       -> bms.api.approvals "Raises disbursement, reversal, waiver and write-off requests"
        bms.api.loans       -> bms.api.ledger "Posts disbursement, repayment and write-off journals in the same transaction"
        bms.api.loans       -> bms.api.notifications "Queues the disbursement, repayment receipt and loan closed SMS through the outbox (SmsEvents), in the transaction of the money; a reversal queues nothing"
        bms.api.loans       -> bms.api.documents "Renders receipts, vouchers, schedules and statements as PDF, synchronously on request (ADR-034): a receipt or voucher is stored once, a schedule or statement never; no render or upload runs under the loan's row lock"
        bms.api.loans       -> bms.api.tenancy "Reads the tenant's letterhead (name, logo, brand colour, receipt footer) for generated documents"
        bms.api.loans       -> bms.api.audit "Writes audit rows"
        bms.api.loans       -> bms.api.identity "Checks that a new responsible officer is an active staff user who may create loans in the loan's branch (StaffDirectory, FR-CLN-01), once per branch of a reassignment"
        bms.api.loans       -> bms.db "Reads and writes loans, schedule items, transactions and allocations"
        bms.api.approvals   -> bms.api.loans "Executes approved loan_disbursement, repayment_reversal and loan_write_off through the action registry (ADR-015, ADR-026)"
        bms.api.approvals   -> bms.api.retailSales "Executes an approved retail_sale_return through the action registry (ADR-015, ADR-033)"
        bms.api.approvals   -> bms.api.audit "Writes decisions to the audit log"
        bms.api.collections -> bms.api.loans "Reads due and overdue schedule items"
        platformOperator -> bms.api.lendingSeed "Runs seed-lending on the staging host for a pilot tenant"
        bms.api.lendingSeed -> bms.api.jobs "Binds the tenant by slug for the command"
        bms.api.lendingSeed -> bms.api.loans "Disburses and repays through LoanServicing, so postings match staff postings"
        bms.api.lendingSeed -> bms.api.audit "Records the lending.seed.fabricated marker"
        bms.api.lendingSeed -> bms.db "Fabricated members, products and applications"
        bms.api.lendingSeed -> bms.api.insights "Backfills a year of snapshots for the insights demo"
        bms.web -> bms.api.insights "Staff Insights page: polls every 60 seconds, drills down, exports CSV" "JSON/HTTPS"
        bms.api.insights -> bms.db "Reads loans, schedules, transactions, allocations, members and journal lines under RLS; writes snapshots and digest settings"
        bms.api.insights -> bms.api.notifications "Queues the owner's daily digest in the outbox"
        bms.api.insights -> bms.api.jobs "Runs the nightly snapshot and the digest check per tenant"
        bms.api.insights -> bms.api.savings "Reads the savings panel through SavingsMetrics"
        bms.api.insights -> bms.api.investments "Reads the investments panel and maturity ladder through InvestmentMetrics"
        bms.api.collateral  -> bms.api.members "Links items to the pledging member"
        bms.api.collateral  -> bms.api.approvals "Requests collateral_release, maker-checker with no threshold (ADR-015, ADR-019)"
        bms.api.approvals   -> bms.api.collateral "Executes an approved release through CollateralReleaseAction (ADR-019)"
        bms.api.collateral  -> bms.api.documents "Stores photos and scans; registers who may read them"
        bms.api.collateral  -> bms.api.tenancy "Reads the tenant's disabled collateral types (FR-COL-05)"
        bms.api.collateral  -> bms.db "Items, valuations, append-only custody events; one active pledge per reference (unique index, ADR-019)"
        bms.api.savings     -> bms.api.members "Reads account holders"
        bms.api.savings     -> bms.api.ledger "Posts deposit, withdrawal, fee, interest and reversal journals in the same transaction"
        bms.api.savings     -> bms.api.approvals "Raises savings_withdrawal and savings_reversal requests"
        bms.api.savings     -> bms.api.notifications "Queues the savings receipt SMS through the outbox (SmsEvents), withdraws it when its movement is reversed"
        bms.api.savings     -> bms.api.payments "Registers the lending.savings_deposit purpose (PurposeHandler, issue #219): describes a savings account for a collection and books a verified payment through SavingsServicing with method gateway; the handler is a bean the payments registry finds, so the core still imports no vertical"
        bms.web -> bms.api.savings "Savings account page: Collect by mobile money or card (link, QR code, live status, receipt, payment code) through POST /payments/intents with purpose lending.savings_deposit; money goes only into the lender's own merchant account"
        bms.api.savings     -> bms.api.audit "Writes audit rows"
        bms.api.savings     -> bms.api.jobs "Runs the nightly savings end of day per tenant"
        bms.api.savings     -> bms.db "Reads and writes savings products, accounts, transactions, end-of-day balances and interest postings"
        bms.api.approvals   -> bms.api.savings "Executes approved savings_withdrawal and savings_reversal through the action registry (ADR-015, ADR-032)"
        bms.api.lendingSeed -> bms.api.savings "Opens accounts and moves savings through SavingsServicing, so postings match staff postings"
        bms.api.investments -> bms.api.members "Reads investors"
        bms.api.investments -> bms.api.notifications "Queues the funded, maturing (a reminder held for the window) and paid out SMS through the outbox (SmsEvents), in the transaction of the event or the nightly job"
        bms.api.investments -> bms.api.savings "Credits monthly returns to savings"
        bms.api.investments -> bms.api.ledger "Posts funding, accrual, payout, rollover, early withdrawal and reversal journals in the same transaction"
        bms.api.investments -> bms.api.approvals "Raises investment_funding, investment_early_withdrawal and investment_reversal requests"
        bms.api.approvals   -> bms.api.investments "Executes approved investment actions through the action registry (ADR-015, ADR-031)"
        bms.api.investments -> bms.api.jobs "Runs the nightly returns and maturity job per lending tenant"
        bms.api.investments -> bms.api.audit "Writes audit rows, maturity reminders included"
        bms.api.investments -> bms.db "Reads and writes investment products, investments, schedule items and transactions"
        bms.api.lendingSeed -> bms.api.investments "Opens, funds, accrues and pays out fabricated investments through InvestmentServicing"
        bms.api.imports     -> bms.api.loans "Invokes the registered lending import template (registry, no code dependency)"
        bms.api.imports     -> bms.api.ledger "Posts opening balances"
        bms.api.payments    -> bms.api.loans "Books succeeded payments through the registered purpose handler (registry, no code dependency)"
        bms.api.loans       -> bms.api.payments "Registers the lending.loan_repayment purpose (PurposeHandler, issue #216): describes a loan for a collection and books a verified payment through LoanServicing with method gateway; the handler is a bean the payments registry finds, so the core still imports no vertical"
        bms.web -> bms.api.loans "Loan page: Collect by mobile money or card (link, QR code, live status, receipt) through POST /payments/intents with purpose lending.loan_repayment; money goes only into the lender's own merchant account"
        bms.api.payments    -> bms.api.ledger "Posts unallocated receipts (gateway_clearing to unallocated_receipts when a purpose handler refuses a completed payment, issue #216) and gateway charges"
        bms.api.payments    -> paymentGateway "Queries transaction status before booking a callback" "HTTPS"
        paymentGateway      -> bms.api.payments "Sends payment callbacks" "HTTPS"
        smsAggregator       -> bms.api.notifications "Sends delivery reports to a tokenised callback path" "HTTPS"
        bms.api.notifications -> smsAggregator "Sends SMS from the outbox: sink on staging, tenant window and opt-out applied (ADR-036)" "HTTPS"
        bms.api.notifications -> bms.db "Writes the tenant SMS delivery log"
        bms.api.notifications -> bms.api.tenancy "Registers the SMS settings group (switch, sender id, window, caps, balance) and reads it per message; reads the tenant's letterhead (name, logo, brand colour) for the header of its own staff emails (ADR-054)"
        bms.api.members -> bms.api.notifications "Member screen: opts a member out of SMS reminders or of all SMS; the administrator alone lifts or narrows it, with a reason (SmsOptOuts)"
        bms.api.notifications -> bms.api.jobs "Binds the tenant a stored outbox row names for the SMS steps and for the email brand (TenantJobs.callForTenant)"
        bms.api.payments    -> bms.api.savings "Books succeeded deposits through the registered purpose handler (registry, no code dependency)"
        bms.api.payments    -> bms.api.notifications "Queues the payment link and receipts through the outbox; emails the other administrators when the payment account changes"
        bms.api.payments    -> bms.api.jobs "Creates orders, polls pending intents for 48 hours, reconciles nightly"
        bms.api.payments    -> bms.db "Intents, notifications (identifiers only), statement rows, each tenant's own payment account (credentials sealed, notification token as a digest)"
        bms.api.payments    -> bms.api.identity "Seals and opens the lenders' stored provider credentials with the application data key (DataKeys), re-seals second-factor seeds on a key rotation, asks the signed-in administrator for a second-factor code and lists the other administrators (AdminSecurity)"
        bms.web -> bms.api.advisor "Insights card (advisory label, as-of time, fact chips, automatic summary on every fallback) and the assistant settings screen; the question box follows (#230)"
        bms.api.advisor -> bms.api.insights "Reads PortfolioFacts under the caller's scope: typed aggregates with display forms, polarity and week and month changes, figures over fewer than five loans suppressed, no officer dimension, no member rows (built, issue #223)"
        bms.api.advisor -> bms.api.ai "Sends prompts of aliases and aggregates; receives the structured answer, which is validated and rendered by our code (never stored as the model wrote it); reads and writes the assistant settings (switch, acknowledgement, caps)"
        bms.api.advisor -> bms.api.jobs "Runs the daily brief job every 15 minutes from 06:00 Kampala time, once per tenant and business date, for tenants with the assistant on (built, issue #226)"
        bms.api.advisor -> bms.api.audit "Audits switching on and the acknowledgement"
        bms.api.advisor -> bms.db "Inserts and reads the stored all-branches brief, one row per tenant and day, append-only (lending_ai_briefs, V55)"
        bms.api.ai -> llmProvider "Chat completions with a 30 second budget and a circuit breaker" "HTTPS"
        bms.api.ai -> bms.db "Call log (counts, a request hash, a closed reason; no prompt, no response, no provider error text) and the daily cap sums"
        bms.api.ai -> bms.api.tenancy "Keeps the tenant's switch, caps and acknowledgement as the settings group ai; refuses the switch for a tenant outside BMS_AI_ENABLED_TENANTS"
        bms.api.ai -> bms.db "Nightly housekeeping function for every tenant, suspended included: deletes call rows past 90 days and closes calls left pending"
        bms.api.lendingReports -> bms.api.insights "Reads the PAR and portfolio figures through the public InsightsFigures port with a public PortfolioScope (every group cross checked against the insights endpoints by test)"
        bms.api.lendingReports -> bms.api.reporting "Registers its reports (lending.par and lending.portfolio_outstanding; the rest and the pack follow)"
        bms.api.lendingReports -> bms.db "Reads loans, schedules, transactions, collateral and snapshots under RLS"
        bms.api.reporting -> bms.api.documents "Lays a report out as a PDF with the shared report template, and stores run files (PDF, Excel, CSV, JSON); expiry deletes the object"
        bms.api.reporting -> bms.api.jobs "Runs the report runner (every 15 seconds) and the nightly file expiry; the runner acts as a tenant found through three read-only definer functions"
        bms.api.reporting -> bms.api.tenancy "Reads the tenant's reports switch, the modules that are on, the letterhead and the time zone a PDF or workbook prints"
        bms.api.reporting -> bms.api.audit "Audits every run, cancel, failure and expiry with the report key and parameters, never rows"
        bms.api.reporting -> bms.api.operations "Replays a repeated Idempotency-Key"

        bms.web -> bms.api.retailCatalogue "Manages categories, units and products; edits prices; asks for similar names before saving an item and adds a repeated name anyway as an administrator; makes items with sizes and colours and reads an item's stock over its variants; looks up a scanned barcode (a scanner or a typed code in the Scan box, or the phone camera with BarcodeDetector or the lazy Quagga2 fallback), edits an item's barcodes and makes label codes"
        bms.api.retailCatalogue -> bms.api.audit "Writes audit rows"
        bms.api.retailCatalogue -> bms.api.documents "Stores item photos as checked, re-encoded uploads and reads them back for the PWA (ADR-053)"
        bms.api.retailCatalogue -> bms.api.tenancy "Reads the tenant currency and the units capability; takes the next label code number from the tenant sequences"
        bms.api.retailCatalogue -> bms.db "Products, the append-only price history, pack sizes, price lists and their items; the generated name key and the reason a repeated name was added; the items with sizes and colours (retail_product_families) and each variant's size, colour and place; the barcodes of products (retail_product_barcodes)"
        bms.web -> bms.api.retailStock "Stock by branch, stock by store and shop, the stock summary by shop and category, movements, stock-takes, usage and damage reports, stock transfers with receive and cancel, transfers in flight; corrects the quantity of an item with a reason and reads its history"
        bms.web -> bms.api.retailSales "POST /retail/sales with Idempotency-Key and a client sale id, sent later from the offline outbox (ADR-067); sale clashes, offline failures and the sales waiting on a shared phone; voids; credit buyers; the sales list and its summary by shop, year, month, salesperson, day, category and item"
        bms.api.retailSales -> bms.api.retailCatalogue "Reads the current cost and sell price to snapshot, the pack size of a product, and the price lists and customer groups that price a line"
        bms.api.retailSales -> bms.api.retailStock "Moves stock; keeps and releases stock for customer orders; posts through the retail books"
        bms.api.retailStock -> bms.api.retailCatalogue "Reads the product cost for stock-take valuation"
        bms.api.retailStock -> bms.api.ledger "Posts and reverses entries by system key, one per branch, in the same transaction; reads the inventory balance per branch and the inter-branch clearing balance of retail transfer entries only"
        bms.api.retailStock -> bms.api.tenancy "Resolves the branch and refuses one that keeps no goods; reads and writes the retail capability settings group; owns branch roles (store, shop, both) and the capability reads; supplies the branch closing check (stock and transfers on the road)"
        bms.api.retailManifest -> bms.api.tenancy "Registers the module and supplies the retail.capabilities settings group the core validates through"
        bms.api.retailSales -> bms.api.tenancy "Reads the tenant profile and takes receipt and sale numbers from the tenant sequences"
        bms.api.retailStock -> bms.api.jobs "Runs the nightly stock reconciliation per retail tenant"
        bms.api.retailStock -> bms.api.notifications "After stock leaves a branch, publishes the low-stock push (below the item's level or under the lead days of cover, once per item, branch and day) and reads the business's switch (ADR-057)"
        bms.api.retailSales -> bms.api.jobs "Runs the daily notices every 15 minutes per retail tenant: credit payments due from 08:00 and the closing summary at the business's time, each once a day (ADR-057)"
        bms.api.retailStock -> bms.db "Append-only movements and balances under a row lock"
        bms.api.retailSales -> bms.api.audit "Writes audit rows"
        bms.api.retailSales -> bms.api.notifications "Queues the email to every administrator who counts when a debt is written off by the only one (ADR-043), in the write-off's transaction; publishes the order pushes (a new remote order to its shop, out for delivery and delivered to the taker), the credit due and the daily summary pushes, and reads the summary time (ADR-057)"
        bms.api.retailSales -> bms.api.approvals "Requests a customer return above the tenant threshold and executes it when a checker approves"
        bms.api.retailSales -> bms.api.documents "With the documents capability: renders the invoice, delivery note, quotation and customer statement as PDF (ADR-034), the first three stored once from an immutable snapshot, with no render or upload under a lock"
        bms.api.retailSales -> bms.db "Sales, append-only lines with snapshots, credit buyers with their credit limit and terms, payments, payments on account and their allocations, customer returns and their lines, and the quotations, issued documents with their snapshots and the numbered series of the documents capability, and customer orders with their lines and history"
        bms.web -> bms.api.retailPurchasing "POST /retail/purchases with Idempotency-Key; suppliers; with purchasing on, /retail/purchase-orders, /goods-receipts, /supplier-bills, /supplier-payments and /reports/supplier-payables; lists past restocks, groups them by shop, year, month, supplier, day, category and item, and voids one by reversal"
        bms.api.retailPurchasing -> bms.api.retailCatalogue "Sets cost and sell price with a history row, under the product lock; reads the pack size of a product; gives prices back when a restock is voided"
        bms.api.retailPurchasing -> bms.api.retailStock "Moves stock per branch (and takes it out again on a void); posts per branch; supplies the transfer-out port that refuses a transfer of a product on a receipt held as not costed while the tenant requires costing"
        bms.api.retailPurchasing -> bms.api.audit "Writes audit rows"
        bms.api.retailPurchasing -> bms.db "Suppliers, append-only purchases and lines, purchase orders, goods received and their lines, supplier invoices, lines and entries, supplier payments and their allocations, the purchasing tolerances, landed costs with their charges and allocation, supplier credit notes with their lines, and the holds on uncosted receipts"
        bms.api.retailPurchasing -> bms.api.approvals "Requests the send of a purchase order above the tenant threshold and executes it when a checker approves"
        bms.api.retailPurchasing -> bms.api.tenancy "Reads the tenant profile and approval thresholds and takes document numbers from the tenant sequences"
        bms.web -> bms.api.retailReports "Valuation; daily profit (admins); sales, margin, stock health, credit and evaluation reports; the owner dashboard; the Controls page (retail.controls.read)"
        bms.api.retailReports -> bms.api.ledger "Reads the inventory account balance per branch"
        bms.api.retailReports -> bms.db "Reads balances, movements, sales, payments, price history and usage (read model)"
        bms.web -> bms.api.retailCashbook "Savings, banking, withdrawals, expenses, advances and cash reports"
        bms.api.retailCashbook -> bms.api.retailSales "Reads the day's cash sales and credit payments; profit for the savings suggestion"
        bms.api.retailCashbook -> bms.api.retailReports "Reads the daily profit (only for callers with retail.profit.read)"
        bms.api.retailCashbook -> bms.api.retailPurchasing "Reads the day's cash restocks and cash supplier payments, which leave the till"
        bms.api.retailCashbook -> bms.api.retailStock "Reuses the retail posting helpers"
        bms.api.retailCashbook -> bms.api.ledger "Posts and reverses one entry per event in the record's branch; reads the cash on hand balance"
        bms.api.retailCashbook -> bms.api.audit "Writes audit rows; a savings create carries only flags, never the amount or the profit"
        bms.api.retailCashbook -> bms.api.tenancy "Resolves the branch and the tenant time zone; reads the savings rate and tolerance settings"
        bms.api.retailCashbook -> bms.api.documents "Links an optional receipt photo to an expense"
        bms.api.retailCashbook -> bms.db "Savings, bankings, withdrawals, expenses, advances, repayments, expense lists"
        bms.api.retailImports -> bms.api.retailCashbook "Historical cash book rows and the opening journal"
        platformOperator -> bms.api.retailImports "Runs import-retail on the host with the tenant's export mounted read only"
        bms.api.retailImports -> bms.api.jobs "Binds the tenant by slug for the command"
        bms.api.retailImports -> bms.api.tenancy "Creates missing branches through the branch rules"
        bms.api.retailImports -> bms.api.retailCatalogue "Ensures categories, units and products; writes imported price history"
        bms.api.retailImports -> bms.api.retailPurchasing "Historical purchases and suppliers"
        bms.api.retailImports -> bms.api.retailSales "Historical sales and credit buyers"
        bms.api.retailImports -> bms.api.retailStock "Historical, adjustment, return and legacy balance movements; usage; the opening journal per branch"
        bms.api.retailImports -> bms.api.audit "One audit row per imported file"
        bms.api.retailImports -> bms.db "Source references of imported rows"
        bms.api.tenancy     -> bms.db "Resolves the slug with app_resolve_tenant_status; binds app.tenant_id"
        bms.api.tenancy     -> bms.api.audit "Audits branch and settings changes"
        bms.api.tenancy     -> bms.api.documents "Stores the tenant logo and each shop's own logo and reads them for the public logo routes (FR-TEN-08, FR-BR-09)"
        bms.api.identity    -> bms.db "Reads sessions and role assignments; revocation takes effect at once"
        bms.api.identity    -> bms.api.audit "Writes sign-in, MFA, invitation, password reset and user events"
        bms.api.identity    -> bms.api.tenancy "Reads branches, settings and plan limits"
        bms.api.identity    -> bms.api.notifications "Sends invitation links through the notification port; queues password reset links and the password changed notice in the outbox (ADR-042)"
        bms.api.approvals   -> bms.api.tenancy "Reads approval thresholds"
        bms.api.approvals   -> bms.db "Stores requests; checker is never maker (database CHECK)"
        bms.api.platform    -> bms.db "Creates and changes tenants, plans, prices and payments through the platform SECURITY DEFINER functions"
        bms.api.platform    -> bms.api.operations "Records a payment once per Idempotency-Key"
        bms.api.platform    -> bms.api.identity "Invites the first tenant admin (the Owner); resets a tenant admin's MFA; makes an active administrator the Owner (ADR-062)"
        bms.api.platform    -> bms.api.audit "Writes the platform audit log and the tenant's first audit row"
        bms.web -> bms.api.onboarding "Sign-up, applicant page, applications queue, decisions and Activate"
        bms.web -> bms.api.notifications "Operator portal: messages not sent, send again, switch SMS on for a tenant and set its daily cap; staff area: SMS settings, wording, opt-out list; staff: reads and changes what the business sends by push and when the daily summary goes (PUT /settings/notifications); the turn-on card subscribes this device on a tap"
        bms.api.onboarding  -> bms.db "Applications through their SECURITY DEFINER functions only (V23, ADR-016)"
        bms.api.onboarding  -> bms.api.platform "Creates the tenant through TenantProvisioning, in one transaction with the activation"
        bms.api.platform    -> bms.api.notifications "Queues the email of a plan decision to the Owner and of each plan change to every active platform operator (ADR-073)"
        bms.api.platform    -> bms.api.feedback "Lists every business's feedback, moves an item forward with a reply, reads a screenshot with its business bound (FeedbackInbox, ADR-065)"
        bms.api.feedback    -> bms.api.documents "Checks, re-encodes and stores the screenshot as a core.feedback document; reads it for the sender or the operator"
        bms.api.feedback    -> bms.api.notifications "Queues the feedback.new email to every active platform operator in a savepoint of the send"
        bms.api.feedback    -> bms.db "Stores feedback_items (forced RLS, insert and select only); the operator reads and answers through the V80 definer functions"
        bms.web -> bms.api.referrals "Owner and administrators: the business's referral link with Copy and Share on WhatsApp, and the businesses it referred with the commission earned, paid and due (ADR-069)"
        bms.api.onboarding  -> bms.api.referrals "Resolves the referral code at the sign-up and makes it the business's referral at Activate (Referrals, ADR-069)"
        bms.api.platform    -> bms.api.referrals "Adds the commission line of a referred business's payment; the operator's referrals, rules, lines and CSV (Referrals, ADR-069)"
        bms.api.referrals   -> bms.db "Referral codes, referrals and commission lines (forced RLS on the referrer, select only), rules and the default (closed); every write through the V88 definer functions"
        bms.api.referrals   -> bms.api.audit "Writes the platform audit log of every referral and commission change"
        bms.api.referrals   -> bms.api.tenancy "Builds the referral link from BMS_PLATFORM_HOST"
        bms.api.referrals   -> bms.api.operations "Adds a typed commission line once per Idempotency-Key"
        bms.api.onboarding  -> bms.db "Finds the businesses where an email is an active staff user, through the SECURITY DEFINER function app_staff_sign_in_pages (V72, ADR-016)"
        bms.api.onboarding  -> bms.api.notifications "Queues applicant emails, the activation email, operator alerts and the staff sign-in page email in the outbox"
        bms.api.onboarding  -> bms.api.audit "Writes the platform audit log"
        bms.api.onboarding  -> bms.api.tenancy "Builds links from BMS_PLATFORM_HOST and BMS_TENANT_HOST_PATTERN"
        bms.api.notifications -> emailService "Sends outbox email" "SMTPS"
        bms.api.notifications -> pushService "Sends the push channel's encrypted, VAPID signed messages to the endpoint of each of a person's devices; only hosts of known push services (ADR-050)" "HTTPS"
        pushService -> tenantAdmin "Delivers a notification to the person's installed app or browser"
        bms.api.notifications -> telegram "Sends operator alerts and tenant digests" "HTTPS"
        bms.api.jobs        -> bms.db "Polls scheduled_tasks; reads the outbox; runs nightly jobs one tenant per transaction" "SQL"
        bms.api.jobs        -> bms.storage "Stores rendered PDFs and report files" "S3 API"
        bms.api.jobs        -> smsAggregator "Sends SMS" "HTTPS"
        bms.api.jobs        -> paymentGateway "Initiates mobile money collections; polls pending intents; fetches settlement reports" "HTTPS"
        bms.api.jobs        -> emailService "Sends email" "HTTPS"
        bms.api.retailSales -> bms.api.operations "Claims the Idempotency-Key row and replays a completed response, in the same transaction"
        bms.api.retailPurchasing -> bms.api.operations "Claims the Idempotency-Key row and replays a completed response, in the same transaction"
        bms.api.retailStock -> bms.api.operations "Claims the Idempotency-Key row and replays a completed response, in the same transaction"
        bms.api.retailCashbook -> bms.api.operations "Claims the Idempotency-Key row and replays a completed response, in the same transaction"
        bms.api.loans -> bms.api.operations "Claims the Idempotency-Key row and replays a completed response, in the same transaction"
        bms.api.loans -> bms.api.notifications "Nightly arrears run: queues the due reminders and arrears notices (reminder and arrears categories, held for the sending window), one per schedule item and reminder type (SmsEvents, #227)"
        bms.api.savings -> bms.api.operations "Claims the Idempotency-Key row and replays a completed response, in the same transaction"
        bms.api.investments -> bms.api.operations "Claims the Idempotency-Key row and replays a completed response, in the same transaction"
        bms.api.operations  -> bms.db "Checks reachability, the applied migration version and its own role"
        bms.api.ledger      -> bms.db "Writes balanced, immutable journal entries"
        bms.api.notifications -> bms.db "Writes notifications and outbox rows"
        bms.api.documents   -> bms.storage "Stores uploads; issues signed URLs"
        bms.api.reporting   -> bms.db "Reads and writes report_runs under RLS; a run's queries read through its report's module in one read-only snapshot"

        # ==================================================================
        # DEPLOYMENT (SDD chapter 9; ADR-006; staging ADR-018)
        # ==================================================================
        staging = deploymentEnvironment "Staging" {
            cloudflare = deploymentNode "Cloudflare" "Edge TLS, DNS, the tunnel and object storage for staging" {
                deploymentNode "R2 staging buckets" "Documents and backups under staging/, private" "Cloudflare R2" {
                    containerInstance bms.storage
                }
                edge = infrastructureNode "Edge and tunnel" "Proxied CNAMEs bms-staging and <slug>-bms-staging.rincoltech.com to the tunnel; TLS with the free *.rincoltech.com edge certificate; one public hostname per host" "Cloudflare DNS and Tunnel"
            }
            github = deploymentNode "GitHub" "Build once per commit on main for amd64 and arm64; moves the staging pointer (ADR-006, ADR-018)" "GitHub Actions" {
                registry = infrastructureNode "Container registry" "Private bms-platform-api, -web and -proxy images tagged sha-<short>, and the staging pointer tag; each signed by digest with cosign" "GHCR"
            }
            maintainerMac = deploymentNode "Maintainer's Mac" "Always on; holds the cosign private key in its Keychain and nothing else of the pipeline (ADR-040)" "macOS, launchd" {
                signer = infrastructureNode "Image signer" "bms_signer.py every 30 seconds: signs image@digest for a push-to-main deploy run that built on bms-deploy-laptop, checking the commit and its merged pull request, the build job's log line and the image labels" "Python, cosign, crane"
            }
            laptop = deploymentNode "Jerome's laptop" "Windows 11 with WSL2 Ubuntu; shared with the AGA and other CI runner users; Kyma-owned (ADR-040 amendment)" "HP EliteBook, x86-64" {
                deployRunner = infrastructureNode "Deploy runner" "bms-deploy-laptop: user bmsdeploy (uid 1006), rootless Docker wiped before and after every job, nftables wall, 5 GB cap, no signing key or deploy secret; builds and pushes the images and waits for their signatures before moving the staging pointer (ADR-040)" "GitHub Actions runner, deploy/runner/provision-laptop.sh"
            }
            alertsNode = deploymentNode "Telegram" "Hosted by Telegram; the operator chat the team reads on their phones" "Telegram Bot API" {
                alertsChat = infrastructureNode "Operator chat" "Receives PROBLEM, STILL FAILING and RESOLVED messages carrying check names, hosts and numbers only, never personal data (ADR-058)" "sendMessage"
            }
            uptimeNode = deploymentNode "Uptime monitor" "Hosted uptime service outside our hosts; alerts when the heartbeat pings stop (ADR-058)" "External service" {
                heartbeat = infrastructureNode "Heartbeat check" "Expects a ping every five minutes with a 15 minute grace" "HTTPS heartbeat"
            }
            host = deploymentNode "Staging host" "Shared ARM64 host at a Rincol home site; no inbound port; BMS capped at 900 MB by bms.slice" "Raspberry Pi 4, Debian 13, linux/arm64" {
                puller = infrastructureNode "Puller" "bms-pull.timer every two minutes: resolves the staging pointer to its sha tag and runs deploy.sh (ADR-018)" "systemd timer, pull-staging.sh"
                hostChecks = infrastructureNode "Host checks" "bms-monitor.timer every five minutes: site, jobs, disk, certificates, backups and the restore drill, posted to the Telegram operator chat, then a heartbeat to the uptime monitor; bms-restore-drill.timer weekly (ADR-058)" "systemd timers, monitor.sh, restore-drill.sh"
                compose = deploymentNode "Docker Compose" "compose.pi-staging.yml: pinned multi-arch images, no published ports, per-container memory limits; migrate runs before the switch" "Docker" {
                    cloudflared = infrastructureNode "cloudflared" "Holds the outbound tunnel with a connector token; forwards to the internal origin" "cloudflare/cloudflared"
                    proxy = containerInstance bms.proxy
                    containerInstance bms.web
                    containerInstance bms.api
                    containerInstance bms.migrate
                    containerInstance bms.db
                }
            }
            staging.cloudflare.edge -> staging.host.compose.cloudflared "Visitor requests, over the tunnel the host opened" "HTTPS, Cloudflare Tunnel"
            staging.host.compose.cloudflared -> staging.host.compose.proxy "Forwards with the original Host header" "HTTP"
            staging.host.hostChecks -> staging.host.compose.proxy "Checks /healthz and the certificate through the public host" "HTTPS"
            staging.host.hostChecks -> staging.alertsNode.alertsChat "Posts PROBLEM, STILL FAILING and RESOLVED alerts; the bot token goes on stdin, never the command line" "HTTPS"
            staging.host.hostChecks -> staging.uptimeNode.heartbeat "Pings the heartbeat after every run that sent all its alerts" "HTTPS"
            staging.host.puller -> staging.github.registry "Reads the staging pointer; pulls sha-<short> images anonymously" "HTTPS"
            staging.laptop.deployRunner -> staging.github.registry "Pushes sha-<short> images with the job token; waits for the cosign signatures, then moves the staging pointer" "HTTPS"
            staging.maintainerMac.signer -> staging.github.registry "Signs image@digest with the Keychain key; reads the deploy run's job logs through the GitHub API" "HTTPS"
        }

        production = deploymentEnvironment "Production" {
            cloudflare = deploymentNode "Cloudflare" "The existing Cloudflare account: DNS for the BMS hosts under rincoltech.com and object storage; every resource here is created by infra/production/modules/cloudflare (ADR-063), nothing applied yet" {
                r2 = deploymentNode "R2 production buckets" "A backup bucket and a documents bucket, private, in the R2 location the owner chooses; backups kept 30 days (monthly 365) by bucket lock rules and removed a day later by lifecycle rules, both created by Terraform and out of reach of the host token (ADR-058)" "Cloudflare R2" {
                    storage = containerInstance bms.storage
                }
                dns = infrastructureNode "DNS" "A records (and AAAA on Hetzner) for bms, bms-callbacks and each <slug>-bms under rincoltech.com, DNS only, created by Terraform; TXT records created by Caddy for the DNS-01 wildcard challenge" "Cloudflare DNS"
                hostTokens = infrastructureNode "Host tokens" "Three account tokens created by Terraform, each limited to the VM's addresses if Cloudflare enforces the IP condition: object read and write on the backup bucket (cannot change the lock or lifecycle rules), the same on the documents bucket, DNS edit and zone read on the whole rincoltech.com zone (an accepted risk, ADR-063 decision 4)" "Cloudflare account API tokens"
            }
            github = deploymentNode "GitHub" "Today: tag vX.Y.Z retags the sha-<short> images, no rebuild (ADR-006). Proposed: the host pulls a signed production pointer the owner moves (ADR-063)" "GitHub Actions" {
                registry = infrastructureNode "Container registry" "The images proven on staging, tagged vX.Y.Z" "GHCR"
            }
            maintainerMac = deploymentNode "Maintainer's Mac" "Runs Terraform by hand with the provider and Cloudflare tokens from the Keychain; state in a private R2 state bucket, never in git (ADR-063)" "macOS" {
                terraform = infrastructureNode "Terraform" "infra/production/hetzner or infra/production/aws, the one root the owner chose; plan read, then applied once on production day" "Terraform"
            }
            alertsNode = deploymentNode "Telegram" "Hosted by Telegram; the operator chat the team reads on their phones" "Telegram Bot API" {
                alertsChat = infrastructureNode "Operator chat" "Receives PROBLEM, STILL FAILING and RESOLVED messages carrying check names, hosts and numbers only, never personal data (ADR-058)" "sendMessage"
            }
            uptimeNode = deploymentNode "Uptime monitor" "Hosted uptime service outside our hosts; alerts when the heartbeat pings stop (ADR-058)" "External service" {
                heartbeat = infrastructureNode "Heartbeat check" "Expects a ping every five minutes with a 15 minute grace" "HTTPS heartbeat"
            }
            cloud = deploymentNode "Cloud provider" "Hetzner Cloud or AWS, the owner's choice, in the location or region the data protection decision names (NFR-DP-07); one Terraform root per provider (ADR-063)" "Hetzner Cloud or AWS" {
                firewall = infrastructureNode "Firewall" "Inbound 22 from the admin address list only, 80 and 443 TCP and 443 UDP from anywhere, nothing else; a Hetzner firewall or an AWS security group, created by Terraform" "Cloud firewall"
                vm = deploymentNode "Production VM" "Single cloud VM, 2 vCPU, 4 GB, 40 GB, created by Terraform with delete protection; cloud-init makes the ops user (SSH keys, no passwords, no root), the bms service user, Docker and the systemd units" "Hetzner Cloud server or AWS EC2 instance (the owner's choice), Ubuntu 24.04 LTS" {
                    puller = infrastructureNode "Puller" "bms-pull.timer with the production pointer and compose.yml; installed by cloud-init, off until pull-staging.sh handles production and the production pointer exists (proposed, ADR-063 decision 6)" "systemd timer, pull-staging.sh"
                    backupJob = infrastructureNode "Nightly backup" "backup.sh at 01:15 as bms: pg_dump, encrypted on the host, uploaded to the backup bucket; BACKUP_RETENTION_ON_HOST=false, so the host deletes nothing" "cron, rclone"
                    hostChecks = infrastructureNode "Host checks" "bms-monitor.timer every five minutes: site, jobs, disk, certificates, backups and the restore drill, posted to the Telegram operator chat, then a heartbeat to the uptime monitor; bms-restore-drill.timer weekly (ADR-058); installed by cloud-init" "systemd timers, monitor.sh, restore-drill.sh"
                    compose = deploymentNode "Docker Compose" "Migrations run as a one-shot container before the app containers switch; failed health check rolls back" "Docker" {
                        proxy = containerInstance bms.proxy
                        containerInstance bms.web
                        containerInstance bms.api
                        containerInstance bms.migrate
                        containerInstance bms.db
                    }
                }
            }
            production.maintainerMac.terraform -> production.cloud.vm "Creates the VM with its cloud-init" "Provider API"
            production.maintainerMac.terraform -> production.cloud.firewall "Creates" "Provider API"
            production.maintainerMac.terraform -> production.cloudflare.dns "Creates the host records" "Cloudflare API"
            production.maintainerMac.terraform -> production.cloudflare.r2 "Creates the buckets, lock and lifecycle rules" "Cloudflare API"
            production.maintainerMac.terraform -> production.cloudflare.hostTokens "Creates the host tokens" "Cloudflare API"
            production.cloud.firewall -> production.cloud.vm.compose.proxy "Lets in 80 and 443 only" "TCP, UDP"
            production.cloud.vm.compose.proxy -> production.cloudflare.dns "Creates DNS-01 challenge records for *.rincoltech.com with the DNS host token" "Cloudflare API"
            production.cloud.vm.backupJob -> production.cloudflare.r2.storage "Uploads the encrypted dump with the backup host token; deletes refused by the lock" "S3 API"
            production.cloud.vm.hostChecks -> production.cloud.vm.compose.proxy "Checks /healthz and the certificate through the public host" "HTTPS"
            production.cloud.vm.hostChecks -> production.alertsNode.alertsChat "Posts PROBLEM, STILL FAILING and RESOLVED alerts; the bot token goes on stdin, never the command line" "HTTPS"
            production.cloud.vm.hostChecks -> production.uptimeNode.heartbeat "Pings the heartbeat after every run that sent all its alerts" "HTTPS"
            production.cloud.vm.puller -> production.github.registry "Reads the production pointer and verifies the cosign signatures (proposed, ADR-063)" "HTTPS"
            production.github.registry -> production.cloud.vm.compose.proxy "Images pulled by deploy.sh with the bms user's read:packages login, verified against the host's cosign.pub" "HTTPS"
        }
    }

    views {

        systemContext bms "SystemContext" "Who uses BMS Platform and what it depends on." {
            include *
            autoLayout lr
        }

        container bms "Containers" "Reverse proxy, web PWA, API (with the worker role), one-shot migrate, database, object storage. No Redis (ADR-008)." {
            include *
            autoLayout
        }

        component bms.api "ApiComponents" "Core modules, the lending vertical and the retail vertical inside the modular monolith. Core never depends on a vertical and retail never depends on lending; the arrows from core to lending are registry calls (ADR-002, ADR-020)." {
            include *
            autoLayout
        }

        deployment bms "Staging" "StagingDeployment" "A shared ARM64 host behind a Cloudflare Tunnel; it pulls every green build of main (ADR-018)." {
            include *
            autoLayout
        }

        deployment bms "Production" "ProductionDeployment" "One VM created by Terraform on Hetzner or AWS, Docker Compose; the images already proven on staging (ADR-006 today, ADR-063 proposed)." {
            include *
            autoLayout
        }

        dynamic bms.api "LoanLifecycle" "One loan from application to disbursement to a partial repayment, with maker-checker on approval and disbursement." {
            loanOfficer -> bms.web "Captures and submits the application"
            bms.web -> bms.api.loans "POST /lending/loans, submit, appraisal"
            bms.api.loans -> bms.api.members "Reads KYC, relationships and exposure for the score"
            bms.api.loans -> bms.api.collateral "Reads collateral cover"
            branchManager -> bms.web "Approves; must not be the submitter or appraiser"
            bms.web -> bms.api.loans "POST /lending/loans/{id}/decision"
            cashier -> bms.web "Requests disbursement"
            bms.web -> bms.api.loans "POST /lending/loans/{id}/disbursements with Idempotency-Key"
            bms.api.loans -> bms.api.approvals "Creates a loan_disbursement approval request"
            branchManager -> bms.web "Authorises the disbursement as checker"
            bms.web -> bms.api.approvals "POST /approvals/{id}/approve"
            bms.api.approvals -> bms.api.loans "Executes the disbursement: schedule generated, loan active"
            bms.api.loans -> bms.api.ledger "Posts the disbursement journal in the same transaction"
            bms.api.loans -> bms.api.notifications "Queues the disbursement SMS in the disbursement's transaction"
            bms.api.jobs -> smsAggregator "Sends the disbursement SMS after commit"
            cashier -> bms.web "Records a partial repayment"
            bms.web -> bms.api.loans "POST /lending/loans/{id}/repayments with Idempotency-Key"
            bms.api.loans -> bms.api.ledger "Posts the repayment journal, split by allocation"
            bms.web -> bms.api.loans "POST /lending/loans/{id}/transactions/{txn}/document when the receipt is first opened"
            bms.api.loans -> bms.api.documents "Renders the receipt PDF in the request, from the payment's own allocation rows (ADR-034)"
            bms.api.documents -> bms.storage "Stores the receipt PDF once"
            bms.api.jobs -> smsAggregator "Sends the receipt SMS"
            autoLayout lr
        }

        dynamic bms.api "ExistingLoanBringIn" "An existing loan from a paper register brought in with its real dates and past repayments, then approved by a second person (ADR-071)." {
            tenantAdmin -> bms.web "Records the loan, or imports a CSV of loans, after a check"
            bms.web -> bms.api.loans "POST /lending/existing-loans with Idempotency-Key (or /import)"
            bms.api.loans -> bms.api.tenancy "Reads the go-live date: the loan must be disbursed before it"
            bms.api.loans -> bms.api.approvals "Creates a loan_bring_in approval request; the loan waits as brought_in"
            branchManager -> bms.web "Approves as checker; never the maker"
            bms.web -> bms.api.approvals "POST /approvals/{id}/approve"
            bms.api.approvals -> bms.api.loans "Executes: schedule from the real date, historic transactions, loan active"
            bms.api.loans -> bms.api.ledger "Posts one opening entry at go-live; no old cash is posted again; a loan_bring_in_reversal mirrors it"
            autoLayout lr
        }

        dynamic bms.api "MobileMoneyRepayment" "A member or a cashier collects an instalment by mobile money through a Pesapal hosted order (ADR-035)." {
            member -> bms.web "Chooses Pay, confirms amount and wallet phone"
            bms.web -> bms.api.payments "POST /member/payments with Idempotency-Key"
            bms.api.jobs -> paymentGateway "Creates the hosted order and gets the payment link"
            paymentGateway -> mobileMoney "Hosted page: the payer picks MTN MoMo, Airtel Money or card"
            member -> mobileMoney "Approves with the wallet PIN"
            paymentGateway -> bms.api.payments "Notification: identifiers only, a hint"
            bms.api.payments -> paymentGateway "Authenticated status query decides; nothing is booked on the notification alone"
            bms.api.payments -> bms.api.loans "Books the repayment once, keyed on the provider reference"
            bms.api.loans -> bms.api.ledger "Posts the repayment journal against gateway clearing"
            bms.api.jobs -> smsAggregator "Sends the receipt SMS"
            autoLayout lr
        }

        styles {
            element "Person" {
                shape Person
                background #0D5C75
                color #ffffff
            }
            element "Software System" {
                background #1168BD
                color #ffffff
            }
            element "external" {
                background #999999
                color #ffffff
            }
            element "Container" {
                background #438DD5
                color #ffffff
            }
            element "edge" {
                background #2E7D32
                color #ffffff
            }
            element "client" {
                background #1976D2
                color #ffffff
                shape WebBrowser
            }
            element "app" {
                background #5C6BC0
                color #ffffff
                shape Hexagon
            }
            element "store" {
                background #6D4C41
                color #ffffff
                shape Cylinder
            }
            element "Component" {
                background #85BBF0
                color #000000
            }
            element "core" {
                background #85BBF0
                color #000000
            }
            element "lending" {
                background #8E24AA
                color #ffffff
                shape Component
            }
            element "retail" {
                background #00897B
                color #ffffff
                shape Component
            }
        }
        # No `theme default`: it fetches from Structurizr's cloud and breaks offline
        # rendering and the static site generator. The explicit styles block above is
        # self-contained.
    }
}
