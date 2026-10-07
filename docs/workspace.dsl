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

            web = container "Web App" "One installable PWA with a staff area, a member area and the platform console, split by route and lazily loaded (ADR-009). Static files served by Caddy." "React 18, TypeScript, Vite, vite-plugin-pwa" "client"

            api = container "API" "Modular monolith (ADR-002). REST under /api/v1; one database transaction per request, bound to the tenant (ADR-003). Also runs the worker role: db-scheduler jobs on PostgreSQL (ADR-008)." "Java 25, Spring Boot 4.1, Spring Modulith 2.1 (ADR-010)" "app" {

                # ---------------- core ----------------
                tenancy       = component "Tenancy" "Tenants, plans and limits, module switching, settings, branding (logo, theme colour), branches. Resolves the tenant from the host, binds app.tenant_id, and makes a suspended tenant read only." "core" "core"
                identity      = component "Identity and Access" "Staff and platform sign-in (argon2id, TOTP with recovery codes, ADR-014), revocable server-side sessions, invitations, roles, permission matrix, per-permission branch scope." "core" "core"
                audit         = component "Audit" "Append-only audit log written in the same transaction as each change; platform audit log; search and CSV export." "core" "core"
                approvals     = component "Approvals" "Maker-checker requests with payload snapshots; executes approved actions through actions registered by the owning modules (ADR-015)." "core" "core"
                platform      = component "Platform Console" "Tenant creation with head office, modules and first admin; module switching; subscriptions and suspension; tenant admin MFA reset. Served only on the platform host to platform operators (ADR-016, ADR-018)." "core" "core"
                onboarding    = component "Onboarding" "Public sign-up and applicant page endpoints with rate limits and a honeypot; the applications queue with the possible-repeat warning; Verify, Needs info, Reject; Activate, which creates the tenant through the platform console's TenantProvisioning and queues the activation email in the same transaction; nightly expiry and 90 day deletion (ADR-024)." "core" "core"
                ledger        = component "General Ledger" "Chart of accounts, periods, post_entry, reversals, trial balance, subledger reconciliation (ADR-004)." "core" "core"
                notifications = component "Notifications" "Outgoing message port with a recording fake adapter, and the platform outbox (ADR-024): rows written in the cause's transaction, a sender job with SKIP LOCKED claims, SMTP email over implicit TLS and Telegram operator alerts, each off when unconfigured; failed rows for the operator portal. Later: tenant templates, SMS adapters, delivery reports." "core" "core"
                documents     = component "Documents" "PDF rendering, uploads, object storage keys, signed download URLs." "core" "core"
                reporting     = component "Reporting" "Report catalogue, parameters, report runs, exports." "core" "core"
                imports       = component "Imports" "Batches, rows, issues, review queue, preview, commit orchestration; templates are registered by modules." "core" "core"
                payments      = component "Payments" "Payment intents, gateway adapter, verified callbacks, unallocated receipts; booking via registered purpose handlers." "core" "core"
                jobs          = component "Jobs" "The worker role: db-scheduler tasks with state in PostgreSQL, run one tenant per transaction (ADR-008). Drains the outbox; nightly arrears, interest, reminders, reconciliation, key purge." "core" "core"
                operations    = component "Operations" "/healthz, /readyz (database and migrations at head), /version; refuses to start as an over-privileged database role." "core" "core"

                # ---------------- lending vertical ----------------
                members       = component "Lending: Members" "Members, KYC, next of kin, relationship graph and exposure." "lending" "lending"
                products      = component "Lending: Loan Products" "Versioned loan products, fees, schedule preview." "lending" "lending"
                loans         = component "Lending: Loans" "Origination, appraisal, approval, schedules, disbursement, repayments, arrears, penalties, closure, write-off." "lending" "lending"
                collateral    = component "Lending: Collateral" "Collateral register, valuations, custody events, photos and scans; release as a maker-checker action (ADR-019)." "lending" "lending"
                savings       = component "Lending: Savings" "Savings products, accounts, deposits, withdrawals, interest." "lending" "lending"
                investments   = component "Lending: Investments" "Fixed-term investments, returns, maturity, payout and rollover." "lending" "lending"
                collections   = component "Lending: Collections" "Due and arrears lists, officer assignment, collection actions, promises to pay." "lending" "lending"

                # ---------------- retail vertical (ADR-020) ----------------
                retailCatalogue = component "Retail: Catalogue" "Categories, units, products with current cost and sell price; append-only price history written with every price change." "retail" "retail"
                retailStock     = component "Retail: Stock" "Append-only stock movements and per-branch balances kept in the same transaction under a row lock; stock-takes; stock transfers between branches at cost, one entry per branch through inter-branch clearing; nightly reconciliation of balances against movements; retail posting rules and Idempotency-Key handling." "retail" "retail"
                retailSales     = component "Retail: Sales" "Sales with unit cost and price snapshots, credit buyers, payments against credit sales, voids by reversal; revenue and cost of goods sold posted per branch." "retail" "retail"
                retailReports    = component "Retail: Reports" "Stock valuation at cost and expected sales at price per branch, the revaluation difference against the inventory account, daily profit per branch from the sale snapshots; cost and profit only with retail.profit.read." "retail" "retail"
                retailPurchasing = component "Retail: Purchasing" "Suppliers and restocks that set product prices, with history, in the same transaction as the stock movements and the per-branch journals." "retail" "retail"
                retailImports    = component "Retail: Imports" "The one-off import-retail command: reads a normalised JSON Lines export and writes historical documents and movements (no journals), legacy balance movements and one opening journal per branch, keyed by source reference so a re-run adds nothing (ADR-020 decision 9)." "retail" "retail"
                retailCashbook   = component "Retail: Cash book (ADR-022)" "Daily savings with the profit-based suggestion, cash banked with the server-computed expected amount, withdrawals from the bank, company expenses by category and item, advances to the owner or company with repayments; every event voidable and posted through the ledger; daily cash summary, banking and expenses reports." "retail" "retail"
            }

            migrate = container "Migrate" "One-shot container run before the application containers switch: applies the Flyway migrations as bms_owner, then exits (ADR-006)." "API image, migrate command" "app"

            db = container "Database" "System of record for all tenants and the job store. Shared schema, forced row-level security on every tenant-owned table, double-entry ledger with a deferred balance trigger. No Redis (ADR-008)." "PostgreSQL 16" "store"

            storage = container "Object Storage" "Generated PDFs, uploads and encrypted database backups. Private buckets; tenant-prefixed keys; signed URLs only." "Cloudflare R2 (S3 API)" "store"
        }

        # ==================================================================
        # EXTERNAL SYSTEMS (SDD chapter 12)
        # ==================================================================
        smsAggregator  = softwareSystem "SMS and USSD Aggregator" "Outbound SMS, delivery reports, USSD sessions in phase 2. Provider pending ADR-013." "external"
        paymentGateway = softwareSystem "Payment Gateway" "Mobile money and card collections, callbacks, status queries, settlement reports. Provider pending ADR-011." "external"
        mobileMoney    = softwareSystem "Mobile Money Operators" "MTN MoMo and Airtel Money wallets. Reached only through the payment gateway." "external"
        emailService   = softwareSystem "Transactional Email Service" "SMTP over implicit TLS (port 465): activation and applicant links, operator alerts; later staff invitations, password resets, report-ready notices (ADR-024)." "external"
        telegram       = softwareSystem "Telegram Bot API" "sendMessage to the operator chat: alerts for new applications (ADR-024)." "external"

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

        # ==================================================================
        # COMPONENT LEVEL (inside the API)
        # ==================================================================
        bms.web -> bms.api.identity "Signs in; loads permissions and branch scope"
        bms.web -> bms.api.tenancy "Tenant settings and branches; public branding (logo, theme colour) for the shell"
        bms.web -> bms.api.platform "Platform console: tenants, modules, subscriptions"
        bms.web -> bms.api.audit "Searches and exports the audit log"
        bms.web -> bms.api.members "Registers, searches and verifies members"
        bms.web -> bms.api.products "Configures loan products; previews schedules"
        bms.web -> bms.api.loans "Applications, appraisal, decisions, disbursement and repayment requests"
        bms.web -> bms.api.collateral "Registers and releases collateral"
        bms.web -> bms.api.savings "Deposits and withdrawals"
        bms.web -> bms.api.investments "Opens, funds and pays out investments"
        bms.web -> bms.api.collections "Due lists, arrears lists, collection actions"
        bms.web -> bms.api.approvals "Checker queue and decisions"
        bms.web -> bms.api.ledger "Chart of accounts, manual journals, period close"
        bms.web -> bms.api.reporting "Runs and downloads reports"
        bms.web -> bms.api.imports "Uploads, reviews and commits imports"
        bms.web -> bms.api.payments "Starts mobile money payments; polls their status"

        bms.api.loans       -> bms.api.members "Reads KYC, relationships and exposure"
        bms.api.loans       -> bms.api.products "Reads product version terms"
        bms.api.loans       -> bms.api.collateral "Reads pledged collateral and cover"
        bms.api.loans       -> bms.api.approvals "Raises disbursement, reversal, waiver and write-off requests"
        bms.api.loans       -> bms.api.ledger "Posts disbursement, repayment and write-off journals in the same transaction"
        bms.api.loans       -> bms.api.notifications "Queues SMS through the outbox"
        bms.api.loans       -> bms.api.documents "Queues receipts, vouchers and schedules"
        bms.api.loans       -> bms.api.audit "Writes audit rows"
        bms.api.loans       -> bms.db "Reads and writes loans, schedule items, transactions and allocations"
        bms.api.approvals   -> bms.api.loans "Executes approved loan actions through the executor registry"
        bms.api.approvals   -> bms.api.audit "Writes decisions to the audit log"
        bms.api.collections -> bms.api.loans "Reads due and overdue schedule items"
        bms.api.collateral  -> bms.api.members "Links items to the pledging member"
        bms.api.collateral  -> bms.api.approvals "Requests collateral_release, maker-checker with no threshold (ADR-015, ADR-019)"
        bms.api.approvals   -> bms.api.collateral "Executes an approved release through CollateralReleaseAction (ADR-019)"
        bms.api.collateral  -> bms.api.documents "Stores photos and scans; registers who may read them"
        bms.api.collateral  -> bms.api.tenancy "Reads the tenant's disabled collateral types (FR-COL-05)"
        bms.api.collateral  -> bms.db "Items, valuations, append-only custody events; one active pledge per reference (unique index, ADR-019)"
        bms.api.savings     -> bms.api.members "Reads account holders"
        bms.api.savings     -> bms.api.ledger "Posts deposit, withdrawal and interest journals"
        bms.api.investments -> bms.api.members "Reads investors"
        bms.api.investments -> bms.api.savings "Credits monthly returns to savings"
        bms.api.investments -> bms.api.ledger "Posts funding, return and payout journals"
        bms.api.imports     -> bms.api.loans "Invokes the registered lending import template (registry, no code dependency)"
        bms.api.imports     -> bms.api.ledger "Posts opening balances"
        bms.api.payments    -> bms.api.loans "Books succeeded payments through the registered purpose handler (registry, no code dependency)"
        bms.api.payments    -> bms.api.ledger "Posts unallocated receipts and gateway charges"
        bms.api.payments    -> paymentGateway "Queries transaction status before booking a callback" "HTTPS"
        paymentGateway      -> bms.api.payments "Sends payment callbacks" "HTTPS"
        smsAggregator       -> bms.api.notifications "Sends delivery reports" "HTTPS"

        bms.web -> bms.api.retailCatalogue "Manages categories, units and products; edits prices"
        bms.api.retailCatalogue -> bms.api.audit "Writes audit rows"
        bms.api.retailCatalogue -> bms.api.tenancy "Reads the tenant currency"
        bms.api.retailCatalogue -> bms.db "Products and the append-only price history"
        bms.web -> bms.api.retailStock "Stock by branch, movements, stock-takes, usage and damage reports, stock transfers"
        bms.web -> bms.api.retailSales "POST /retail/sales with Idempotency-Key; voids; credit buyers"
        bms.api.retailSales -> bms.api.retailCatalogue "Reads the current cost and sell price to snapshot"
        bms.api.retailSales -> bms.api.retailStock "Moves stock; posts through the retail books; claims the idempotency key"
        bms.api.retailStock -> bms.api.retailCatalogue "Reads the product cost for stock-take valuation"
        bms.api.retailStock -> bms.api.ledger "Posts and reverses entries by system key, one per branch, in the same transaction"
        bms.api.retailStock -> bms.api.tenancy "Resolves the branch"
        bms.api.retailStock -> bms.api.jobs "Runs the nightly stock reconciliation per retail tenant"
        bms.api.retailStock -> bms.db "Append-only movements and balances under a row lock"
        bms.api.retailSales -> bms.api.audit "Writes audit rows"
        bms.api.retailSales -> bms.db "Sales, append-only lines with snapshots, credit buyers, payments"
        bms.web -> bms.api.retailPurchasing "POST /retail/purchases with Idempotency-Key; suppliers"
        bms.api.retailPurchasing -> bms.api.retailCatalogue "Sets cost and sell price with a history row, under the product lock"
        bms.api.retailPurchasing -> bms.api.retailStock "Moves stock per branch; posts per branch; claims the idempotency key"
        bms.api.retailPurchasing -> bms.api.audit "Writes audit rows"
        bms.api.retailPurchasing -> bms.db "Suppliers, append-only purchases and lines"
        bms.web -> bms.api.retailReports "Valuation; daily profit (admins)"
        bms.api.retailReports -> bms.api.ledger "Reads the inventory account balance per branch"
        bms.api.retailReports -> bms.db "Reads balances, movements, sales and usage (read model)"
        bms.web -> bms.api.retailCashbook "Savings, banking, withdrawals, expenses, advances and cash reports"
        bms.api.retailCashbook -> bms.api.retailSales "Reads the day's cash sales and credit payments; profit for the savings suggestion"
        bms.api.retailCashbook -> bms.api.retailReports "Reads the daily profit (only for callers with retail.profit.read)"
        bms.api.retailCashbook -> bms.api.retailPurchasing "Reads the day's cash restocks, which leave the till"
        bms.api.retailCashbook -> bms.api.retailStock "Reuses the retail posting and idempotency helpers"
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
        bms.api.tenancy     -> bms.api.documents "Stores the tenant logo and reads it for the public branding route (FR-TEN-08)"
        bms.api.identity    -> bms.db "Reads sessions and role assignments; revocation takes effect at once"
        bms.api.identity    -> bms.api.audit "Writes sign-in, MFA, invitation and user events"
        bms.api.identity    -> bms.api.tenancy "Reads branches, settings and plan limits"
        bms.api.identity    -> bms.api.notifications "Sends invitation links through the notification port"
        bms.api.approvals   -> bms.api.tenancy "Reads approval thresholds"
        bms.api.approvals   -> bms.db "Stores requests; checker is never maker (database CHECK)"
        bms.api.platform    -> bms.db "Creates and changes tenants through the platform SECURITY DEFINER functions"
        bms.api.platform    -> bms.api.identity "Invites the first tenant admin; resets a tenant admin's MFA"
        bms.api.platform    -> bms.api.audit "Writes the platform audit log and the tenant's first audit row"
        bms.web -> bms.api.onboarding "Sign-up, applicant page, applications queue, decisions and Activate"
        bms.web -> bms.api.notifications "Operator portal: messages not sent, send again"
        bms.api.onboarding  -> bms.db "Applications through their SECURITY DEFINER functions only (V23, ADR-016)"
        bms.api.onboarding  -> bms.api.platform "Creates the tenant through TenantProvisioning, in one transaction with the activation"
        bms.api.onboarding  -> bms.api.notifications "Queues applicant emails, the activation email and operator alerts in the outbox"
        bms.api.onboarding  -> bms.api.audit "Writes the platform audit log"
        bms.api.onboarding  -> bms.api.tenancy "Builds links from BMS_PLATFORM_HOST and BMS_TENANT_HOST_PATTERN"
        bms.api.notifications -> emailService "Sends outbox email" "SMTPS"
        bms.api.notifications -> telegram "Sends operator alerts" "HTTPS"
        bms.api.jobs        -> bms.db "Polls scheduled_tasks; reads the outbox; runs nightly jobs one tenant per transaction" "SQL"
        bms.api.jobs        -> bms.storage "Stores rendered PDFs and report files" "S3 API"
        bms.api.jobs        -> smsAggregator "Sends SMS" "HTTPS"
        bms.api.jobs        -> paymentGateway "Initiates mobile money collections; polls pending intents; fetches settlement reports" "HTTPS"
        bms.api.jobs        -> emailService "Sends email" "HTTPS"
        bms.api.operations  -> bms.db "Checks reachability, the applied migration version and its own role"
        bms.api.ledger      -> bms.db "Writes balanced, immutable journal entries"
        bms.api.notifications -> bms.db "Writes notifications and outbox rows"
        bms.api.documents   -> bms.storage "Stores uploads; issues signed URLs"
        bms.api.reporting   -> bms.db "Reads through read-only views"

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
            host = deploymentNode "Staging host" "Shared ARM64 host at a Rincol home site; no inbound port; BMS capped at 900 MB by bms.slice" "Raspberry Pi 4, Debian 13, linux/arm64" {
                puller = infrastructureNode "Puller" "bms-pull.timer every two minutes: resolves the staging pointer to its sha tag and runs deploy.sh (ADR-018)" "systemd timer, pull-staging.sh"
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
            staging.host.puller -> staging.github.registry "Reads the staging pointer; pulls sha-<short> images anonymously" "HTTPS"
        }

        production = deploymentEnvironment "Production" {
            cloudflare = deploymentNode "Cloudflare" "DNS for the BMS hosts under rincoltech.com and object storage" {
                deploymentNode "R2 production buckets" "Documents and nightly encrypted backups, private, 30-day retention" "Cloudflare R2" {
                    containerInstance bms.storage
                }
                dns = infrastructureNode "DNS" "A records for bms, bms-callbacks and each <slug>-bms under rincoltech.com; TXT records created by Caddy for the DNS-01 wildcard challenge" "Cloudflare DNS"
            }
            github = deploymentNode "GitHub" "Tag vX.Y.Z retags the sha-<short> images; no rebuild (ADR-006)" "GitHub Actions" {
                registry = infrastructureNode "Container registry" "The images proven on staging, tagged vX.Y.Z" "GHCR"
            }
            vm = deploymentNode "Production VM" "Single cloud VM, 4 GB; deployed from a version tag after the dev lead approves" "Hetzner Cloud, Ubuntu LTS" {
                compose = deploymentNode "Docker Compose" "Migrations run as a one-shot container before the app containers switch; failed health check rolls back" "Docker" {
                    proxy = containerInstance bms.proxy
                    containerInstance bms.web
                    containerInstance bms.api
                    containerInstance bms.migrate
                    containerInstance bms.db
                }
            }
            production.vm.compose.proxy -> production.cloudflare.dns "Creates DNS-01 challenge records for *.rincoltech.com" "Cloudflare API"
            production.github.registry -> production.vm.compose.proxy "Images pulled by deploy.sh with a short-lived token" "HTTPS"
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

        deployment bms "Production" "ProductionDeployment" "One VM, Docker Compose; a version tag deploys the images already proven on staging." {
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
            bms.api.loans -> bms.api.notifications "Queues the disbursement SMS"
            bms.api.jobs -> smsAggregator "Sends the disbursement SMS after commit"
            cashier -> bms.web "Records a partial repayment"
            bms.web -> bms.api.loans "POST /lending/loans/{id}/repayments with Idempotency-Key"
            bms.api.loans -> bms.api.ledger "Posts the repayment journal, split by allocation"
            bms.api.loans -> bms.api.documents "Queues the receipt PDF"
            bms.api.jobs -> bms.storage "Stores the receipt PDF"
            bms.api.jobs -> smsAggregator "Sends the receipt SMS"
            autoLayout lr
        }

        dynamic bms.api "MobileMoneyRepayment" "A member pays an instalment by mobile money through the gateway (phase 2; pending ADR-011)." {
            member -> bms.web "Chooses Pay, confirms amount and wallet phone"
            bms.web -> bms.api.payments "POST /member/payments with Idempotency-Key"
            bms.api.jobs -> paymentGateway "Initiates the collection"
            paymentGateway -> mobileMoney "Prompts the payer's phone"
            member -> mobileMoney "Approves with the wallet PIN"
            paymentGateway -> bms.api.payments "Callback, signature verified"
            bms.api.payments -> paymentGateway "Queries the transaction status before booking"
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
