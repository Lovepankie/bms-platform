workspace "BMS Platform" "Multi-tenant business management platform: core plus vertical modules, lending first" {

    !identifiers hierarchical

    # Wire the prose SDD chapters and the ADR log into the rendered workspace, so the
    # model (this file), the decisions (adr/) and the documentation (sdd/) render as one
    # browsable SDD. Paths are relative to this .dsl file.
    !docs sdd
    # ADRs are pulled as docs, not via !adrs: the !adrs directive requires filenames
    # beginning with a number (0001-slug.md), but these are named ADR-001-slug.md, which
    # !adrs cannot parse (NumberFormatException). Same arrangement as rincol-praxis.
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
        member           = person "Member" "A tenant's borrower, saver or investor. Uses the member area, receives SMS; USSD in phase 2."

        # ==================================================================
        # BMS PLATFORM (the system under design)
        # ==================================================================
        bms = softwareSystem "BMS Platform" "Multi-tenant SaaS: platform core plus vertical modules a tenant switches on (ADR-001). Lending is the first vertical." {

            proxy = container "Reverse Proxy" "Terminates TLS with a wildcard certificate; routes <slug>.<base>, app.<base> and api.<base>; request size limits." "Caddy or Nginx (SDD chapter 9)" "edge"

            web = container "Web App" "One installable PWA with a staff area, a member area and the platform console, split by route and lazily loaded (ADR-009)." "React 18, TypeScript, Vite, vite-plugin-pwa" "client"

            api = container "API" "Modular monolith (ADR-002). REST under /api/v1; one database transaction per request, bound to the tenant (ADR-003)." "Backend framework per pending ADR-010" "app" {

                # ---------------- core ----------------
                tenancy       = component "Tenancy" "Tenants, plans, subscriptions, module switching, settings, branches. Resolves the tenant from the host and binds app.tenant_id." "core" "core"
                identity      = component "Identity and Access" "Staff and member authentication, sessions, roles, permission matrix, branch scope." "core" "core"
                audit         = component "Audit" "Append-only audit log written in the same transaction as each change." "core" "core"
                approvals     = component "Approvals" "Maker-checker requests; executes approved actions through executors registered by modules." "core" "core"
                ledger        = component "General Ledger" "Chart of accounts, periods, post_entry, reversals, trial balance, subledger reconciliation (ADR-004)." "core" "core"
                notifications = component "Notifications" "SMS and email templates, notifications outbox, provider adapters, delivery reports." "core" "core"
                documents     = component "Documents" "PDF rendering, uploads, object storage keys, signed download URLs." "core" "core"
                reporting     = component "Reporting" "Report catalogue, parameters, report runs, exports." "core" "core"
                imports       = component "Imports" "Batches, rows, issues, review queue, preview, commit orchestration; templates are registered by modules." "core" "core"
                payments      = component "Payments" "Payment intents, gateway adapter, verified callbacks, unallocated receipts; booking via registered purpose handlers." "core" "core"

                # ---------------- lending vertical ----------------
                members       = component "Lending: Members" "Members, KYC, next of kin, relationship graph and exposure." "lending" "lending"
                products      = component "Lending: Loan Products" "Versioned loan products, fees, schedule preview." "lending" "lending"
                loans         = component "Lending: Loans" "Origination, appraisal, approval, schedules, disbursement, repayments, arrears, penalties, closure, write-off." "lending" "lending"
                collateral    = component "Lending: Collateral" "Collateral register, valuations, custody events, release." "lending" "lending"
                savings       = component "Lending: Savings" "Savings products, accounts, deposits, withdrawals, interest." "lending" "lending"
                investments   = component "Lending: Investments" "Fixed-term investments, returns, maturity, payout and rollover." "lending" "lending"
                collections   = component "Lending: Collections" "Due and arrears lists, officer assignment, collection actions, promises to pay." "lending" "lending"
            }

            worker = container "Worker" "Same codebase and image as the API, different entry point. Drains the outbox (SMS, email, PDFs, reports) and runs scheduled jobs (nightly arrears, interest, reminders, reconciliation, backups)." "Redis-backed queue per pending ADR-008" "app"

            db = container "Database" "System of record for all tenants. Shared schema, forced row-level security on every tenant-owned table, double-entry ledger with a deferred balance trigger." "PostgreSQL 16" "store"

            redis = container "Cache and Queue" "Job queue, rate limits, permission cache, revoked sessions. Rebuildable; the outbox re-enqueues lost jobs." "Redis 7" "store"

            storage = container "Object Storage" "Generated PDFs, uploads and encrypted database backups. Private buckets; tenant-prefixed keys; signed URLs only." "Cloudflare R2 (S3 API)" "store"
        }

        # ==================================================================
        # EXTERNAL SYSTEMS (SDD chapter 12)
        # ==================================================================
        smsAggregator  = softwareSystem "SMS and USSD Aggregator" "Outbound SMS, delivery reports, USSD sessions in phase 2. Provider pending ADR-013." "external"
        paymentGateway = softwareSystem "Payment Gateway" "Mobile money and card collections, callbacks, status queries, settlement reports. Provider pending ADR-011." "external"
        mobileMoney    = softwareSystem "Mobile Money Operators" "MTN MoMo and Airtel Money wallets. Reached only through the payment gateway." "external"
        emailService   = softwareSystem "Transactional Email Service" "Staff invitations, password resets, report-ready notices." "external"

        # ==================================================================
        # PEOPLE TO SYSTEM
        # ==================================================================
        platformOperator -> bms.web "Creates and supervises tenants in the platform console"
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
        bms.api    -> bms.redis "Rate limits, permission cache, revoked sessions" "Redis protocol"
        bms.api    -> bms.storage "Stores uploads; issues signed download URLs" "S3 API"
        bms.worker -> bms.db "Reads the outbox; runs nightly jobs one tenant per transaction" "SQL"
        bms.worker -> bms.redis "Consumes and schedules jobs" "Redis protocol"
        bms.worker -> bms.storage "Stores rendered PDFs, report files and encrypted backups" "S3 API"
        bms.worker -> smsAggregator "Sends SMS" "HTTPS"
        bms.worker -> paymentGateway "Initiates mobile money collections; polls pending intents; fetches settlement reports" "HTTPS"
        bms.worker -> emailService "Sends email" "HTTPS"
        paymentGateway -> mobileMoney "Collects from the payer's wallet"
        smsAggregator  -> bms.proxy "Delivery reports and USSD sessions on api.<base>" "HTTPS"
        paymentGateway -> bms.proxy "Payment callbacks on api.<base>" "HTTPS"

        # ==================================================================
        # COMPONENT LEVEL (inside the API)
        # ==================================================================
        bms.web -> bms.api.identity "Signs in; loads permissions and branch scope"
        bms.web -> bms.api.tenancy "Tenant settings, branches, platform console"
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
        bms.api.tenancy     -> bms.db "Resolves the slug with app_resolve_tenant; binds app.tenant_id"
        bms.api.identity    -> bms.redis "Revoked sessions, rate limits, permission cache"
        bms.api.ledger      -> bms.db "Writes balanced, immutable journal entries"
        bms.api.notifications -> bms.db "Writes notifications and outbox rows"
        bms.api.documents   -> bms.storage "Stores uploads; issues signed URLs"
        bms.api.reporting   -> bms.db "Reads through read-only views"

        # ==================================================================
        # DEPLOYMENT (SDD chapter 9; pending ADR-006)
        # ==================================================================
        deploymentEnvironment "Staging" {
            deploymentNode "Cloudflare" "DNS for the staging base domain and object storage" {
                deploymentNode "R2 staging buckets" "Documents and backups, private" "Cloudflare R2" {
                    containerInstance bms.storage
                }
            }
            deploymentNode "Staging VM" "Single cloud VM; auto-deployed on every merge to main" "Hetzner Cloud, Ubuntu LTS" {
                deploymentNode "Docker Compose" "Pinned image tags built once in CI" "Docker" {
                    containerInstance bms.proxy
                    containerInstance bms.web
                    containerInstance bms.api
                    containerInstance bms.worker
                    containerInstance bms.db
                    containerInstance bms.redis
                }
            }
        }

        deploymentEnvironment "Production" {
            deploymentNode "Cloudflare" "Wildcard DNS for tenant subdomains and object storage" {
                deploymentNode "R2 production buckets" "Documents and nightly encrypted backups, private, 30-day retention" "Cloudflare R2" {
                    containerInstance bms.storage
                }
            }
            deploymentNode "Production VM" "Single cloud VM; deployed from a version tag with the same images already proven on staging" "Hetzner Cloud, Ubuntu LTS" {
                deploymentNode "Docker Compose" "Migrations run as a one-shot container before the app containers switch" "Docker" {
                    containerInstance bms.proxy
                    containerInstance bms.web
                    containerInstance bms.api
                    containerInstance bms.worker
                    containerInstance bms.db
                    containerInstance bms.redis
                }
            }
        }
    }

    views {

        systemContext bms "SystemContext" "Who uses BMS Platform and what it depends on." {
            include *
            autoLayout lr
        }

        container bms "Containers" "Reverse proxy, web PWA, API, worker, database, cache and queue, object storage." {
            include *
            autoLayout
        }

        component bms.api "ApiComponents" "Core modules and the lending vertical inside the modular monolith. Core never depends on lending; the arrows from core to lending are registry calls (ADR-002)." {
            include *
            autoLayout
        }

        deployment bms "Staging" "StagingDeployment" "One VM, Docker Compose; every merge to main deploys here." {
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
            bms.worker -> smsAggregator "Sends the disbursement SMS after commit"
            cashier -> bms.web "Records a partial repayment"
            bms.web -> bms.api.loans "POST /lending/loans/{id}/repayments with Idempotency-Key"
            bms.api.loans -> bms.api.ledger "Posts the repayment journal, split by allocation"
            bms.api.loans -> bms.api.documents "Queues the receipt PDF"
            bms.worker -> bms.storage "Stores the receipt PDF"
            bms.worker -> smsAggregator "Sends the receipt SMS"
            autoLayout lr
        }

        dynamic bms.api "MobileMoneyRepayment" "A member pays an instalment by mobile money through the gateway (phase 2; pending ADR-011)." {
            member -> bms.web "Chooses Pay, confirms amount and wallet phone"
            bms.web -> bms.api.payments "POST /member/payments with Idempotency-Key"
            bms.worker -> paymentGateway "Initiates the collection"
            paymentGateway -> mobileMoney "Prompts the payer's phone"
            member -> mobileMoney "Approves with the wallet PIN"
            paymentGateway -> bms.api.payments "Callback, signature verified"
            bms.api.payments -> paymentGateway "Queries the transaction status before booking"
            bms.api.payments -> bms.api.loans "Books the repayment once, keyed on the provider reference"
            bms.api.loans -> bms.api.ledger "Posts the repayment journal against gateway clearing"
            bms.worker -> smsAggregator "Sends the receipt SMS"
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
        }
        # No `theme default`: it fetches from Structurizr's cloud and breaks offline
        # rendering and the static site generator. The explicit styles block above is
        # self-contained.
    }
}
