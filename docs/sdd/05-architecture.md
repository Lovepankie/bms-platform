# 5. Architecture

**Status:** Draft · **Owner:** Hillary

## 5.1 Purpose and scope

Container and module structure, the dependency rules between modules, and the mapping
from each structural decision to the ADR that made it. The Containers and Components
views in `docs/workspace.dsl` are the diagram form of this chapter. The backend is Java 25,
Spring Boot 4.1 and Spring Modulith 2.1 (ADR-010); section 5.4.6 maps the logical modules to
packages.

## 5.2 Architectural decisions in force

| Decision | ADR |
|---|---|
| Platform core plus vertical modules; lending first, retail later | ADR-001 |
| Modular monolith with enforced module boundaries | ADR-002 |
| Shared schema, PostgreSQL row-level security | ADR-003 |
| Integer minor units, double-entry general ledger | ADR-004 |
| Documentation lives with the code | ADR-005 |
| Delivery pipeline: build once, main to staging, tag to production | ADR-006 |
| Separate from the offline single-device product | ADR-007 |
| Background jobs and scheduling on PostgreSQL (db-scheduler); no Redis | ADR-008 |
| One React PWA with staff and member areas | ADR-009 |
| Backend: Java 25, Spring Boot 4.1, Spring Modulith 2.1, Flyway | ADR-010 |
| Payment gateway | pending ADR-011 |
| Credit scoring model beyond the rules-based default | pending ADR-012 |
| SMS and USSD aggregator | pending ADR-013 |

## 5.3 Containers

| Container | Technology | Responsibility |
|---|---|---|
| Reverse proxy | Caddy with the Cloudflare DNS module (chapter 9) | TLS termination with a wildcard certificate (DNS-01), host and path routing (`<slug>.`, `app.`, `api.`), security headers, request size limits |
| Web | React 18, TypeScript, Vite, PWA; static files served by Caddy | Staff area, member area, platform console (ADR-009) |
| API | Java 25, Spring Boot 4.1, Spring Modulith 2.1 (ADR-010) | All HTTP endpoints in chapter 7; runs every module in one process; one transaction per request, bound to the tenant; also runs the worker role |
| Worker (a role) | db-scheduler inside the API process, state in PostgreSQL (ADR-008); can move to a second container of the same image with `BMS_SCHEDULER_ENABLED` | Drains the outbox (SMS, email, PDF rendering, report builds), runs scheduled jobs (nightly arrears, interest, reminders, reconciliation, key purge) |
| Migrate (one-shot) | The API image's `migrate` command | Applies Flyway migrations as `bms_owner` before the application containers switch (ADR-006) |
| PostgreSQL 16 | Database | System of record for all tenants (chapter 6), and the job store (`scheduled_tasks`, ADR-008) |
| Object storage | Cloudflare R2 (S3 API) | Generated PDFs, uploads, encrypted database backups |

External systems: SMS and USSD aggregator (pending ADR-013), payment gateway
(pending ADR-011) connecting to mobile money operators, transactional email provider.

Deployment: one virtual machine per environment (staging, production), all containers
under Docker Compose, object storage external. There is no Redis: jobs, the outbox and
session revocation live in PostgreSQL, and rate limits and the permission cache are in process
(ADR-008). Chapter 9 and ADR-006 hold the detail.

## 5.4 Module structure

### 5.4.1 Module map

```
core
  kernel          money, rates, dates and clock, tenant context, error types  (no dependencies)
  tenancy         tenants, plans, subscriptions, module switching, settings, branches
  identity        users, roles, permissions, credentials, sessions, principal
  audit           audit log writer and query
  approvals       maker-checker requests; dispatches execution to the owning module
  ledger          chart of accounts, periods, post_entry, reversal, trial balance, reconciliation hooks
  notifications   templates, outbox, SMS and email adapters, delivery reports
  documents       PDF rendering, object storage, signed URLs
  reporting       report catalogue, report runs, export formats
  imports         batches, rows, issues, review queue, commit orchestration
  payments        payment intents, gateway adapters, callbacks, unallocated receipts
  jobs            db-scheduler tasks, per-tenant job runner (ADR-008)
  operations      version endpoint, readiness checks, database role guard
lending           vertical module (ADR-001)
  manifest        the vertical's registration with the core (section 5.4.3)
  members         members, KYC, next of kin, relationship graph
  products        loan products, versions, fees
  loans           origination, appraisal, schedule, disbursement, repayment, arrears, closure
  collateral      register, valuations, custody
  savings         products, accounts, transactions, interest
  investments     products, investments, returns, maturity
  collections     due list, arrears list, officer assignment, collection actions
  lending reports, lending import templates, lending posting rules, lending jobs
retail            vertical module (future)
```

### 5.4.2 Allowed dependencies

Arrows read "may call the public interface of".

```
every module          -> core.kernel
core.* (non-kernel)   -> core.tenancy, core.identity, core.audit
core.approvals        -> (executes through a registry the verticals register into; never imports them)
core.imports          -> core.ledger, core.documents   (templates register into it)
core.payments         -> core.ledger, core.notifications (booking is delegated through a registry)
core.reporting        -> core.documents
lending               -> any core module
lending.loans         -> lending.members, lending.products, lending.collateral, lending.savings (transfer)
lending.collections   -> lending.loans, lending.members
lending.savings       -> lending.members
lending.investments   -> lending.members, lending.savings (monthly return credit)
lending.collateral    -> lending.members
```

Forbidden, and enforced by the module boundary test (ADR-002):

- any `core` module depending on `lending` or `retail`;
- `lending` depending on `retail` or the reverse;
- any dependency on another module's internals rather than its public interface;
- a cycle.

### 5.4.3 Registries: how the core calls the verticals without depending on them

The core needs to run vertical behaviour in five places. Each is a registry the vertical
registers into through its manifest at startup:

| Registry (core) | What the vertical registers | Example |
|---|---|---|
| Module manifest | Module key, routers, permissions, default chart of accounts entries, notification templates | `lending` |
| Approval executors | One executor per action type it owns | `loan_disbursement` executes the disbursement |
| Import templates | Parser, classifier, normaliser, committer | `pilot_loan_register_v1` |
| Payment purposes | Booking handler per purpose | `lending.loan_repayment` books a repayment |
| Report definitions | Report key, parameters, permission, query | `lending.par` |
| Scheduled jobs | Job key, schedule, handler | `lending.nightly_arrears` |

The core iterates registries; it never names a vertical.

### 5.4.4 Transactions and side effects

- A request runs in exactly one database transaction, bound to the tenant, committed
  before the response is sent.
- A money-moving service operation, inside that transaction: locks the account row
  (`SELECT ... FOR UPDATE`), validates, writes its own tables, calls the ledger's
  `post_entry`, writes the audit row, writes outbox rows for side effects, and returns.
- The worker picks up outbox rows after commit. A side effect that fails is retried; it
  never rolls back the financial record that caused it.
- Scheduled jobs run one tenant per transaction (`app_list_active_tenants()`), and inside
  a tenant, in batches of at most 500 accounts per transaction so a single bad record
  cannot stall a tenant's whole run. Every job is idempotent for its business date.

### 5.4.5 Time

A single clock abstraction in `core.kernel` supplies "now" and "business date for tenant
T". Tests fix it. No module reads the system clock directly; `ClockArchitectureTest`
enforces it. Business date is computed in the tenant's timezone.

### 5.4.6 Physical layout (ADR-010)

One Maven project, `backend/`, one deployable. Each module is a Java package under
`com.rincoltech.bms` whose `package-info.java` declares it with
`@ApplicationModule(id = "...", allowedDependencies = {...})`; the allowed dependencies of section
5.4.2 are written there, as data, and `ModularityTest` verifies them with Spring Modulith on every
build. A module's public API is the types in its base package; its `internal` subpackage is closed
to other modules.

| Module id | Package | Built so far |
|---|---|---|
| `kernel` | `com.rincoltech.bms.kernel` (open) | Tenant context, money, business clock, request id, problem details, phone and NIN normalisation, masking, cursors |
| `core.tenancy` | `...core.tenancy` | Tenant resolution from the host, the transaction manager that binds `app.tenant_id`, branches, enabled modules, tenant sequences |
| `core.identity` | `...core.identity` | Principal, `@RequiresPermission`, `@PublicEndpoint`, the permission interceptor, the development authentication stub |
| `core.audit` | `...core.audit` | `AuditLog.record`, masking identifiers |
| `core.ledger` | `...core.ledger` | `LedgerPosting.post` (`post_entry`) |
| `core.jobs` | `...core.jobs` | `TenantJobs`, db-scheduler tasks (ADR-008) |
| `core.operations` | `...core.operations` | `/version`, the migrations readiness check, the database role guard |
| `lending.manifest` | `...lending.manifest` | The lending module's registration (`ModuleManifest`) |
| `lending.members` | `...lending.members` | Members: the reference vertical slice |

Each further logical module of section 5.4.1 becomes a package of the same shape
(`core.approvals`, `lending.loans`, and so on), and its `package-info.java` states its allowed
dependencies from section 5.4.2.

## 5.5 Request flow

```
browser -> reverse proxy (TLS, host routing)
        -> API: resolve tenant from host -> authenticate -> check tenant status
        -> open transaction, set app.tenant_id -> check permission and branch scope
        -> module service -> (ledger, audit, outbox) -> commit -> response
worker  -> outbox row -> bind tenant -> send SMS / render PDF / build report -> mark done
```

The dynamic view `LoanLifecycle` in `docs/workspace.dsl` traces one loan from
application to repayment through these components.

## 5.6 Frontend structure

One Vite project (ADR-009):

```
frontend/src/
  app/          router, providers, layouts, service worker registration
  api/          generated client and types from docs/api/openapi.json
  areas/
    staff/      routes per module: members, loans, collateral, savings, investments,
                collections, ledger, reports, imports, approvals, admin
    member/     home, loans, savings, investments, pay, documents
    platform/   tenant management (served on app.<base domain>)
  components/   shared UI (shadcn/ui based), money and date formatters
  offline/      IndexedDB caches and draft storage (chapter 4 section 4.8)
  i18n/         translation files
```

Each area is a lazily loaded route tree. The staff area reads the principal's permissions
from `/me` and hides actions the user cannot take. Money is formatted only in
`components/`, from integer minor units and the currency exponent.

## 5.7 Where the retail vertical will fit

Retail becomes a sibling module `retail` with its own tables (`retail_` prefix), its own
posting rules into the same general ledger, and its own reports in the same reporting
framework. Customers of a retail tenant are retail's own entity (ADR-001: deliberate
duplication over a false shared model). Nothing in the core changes shape to add it;
if something must, that is a signal to write an ADR before building.
