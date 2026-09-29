# 5. Architecture

**Status:** Draft · **Owner:** Hillary

## 5.1 Purpose and scope

Container and module structure, the dependency rules between modules, and the mapping
from each structural decision to the ADR that made it. The Containers and Components
views in `docs/workspace.dsl` are the diagram form of this chapter. The backend framework
is pending ADR-010; this chapter is written so that it holds for either candidate.

## 5.2 Architectural decisions in force

| Decision | ADR |
|---|---|
| Platform core plus vertical modules; lending first, retail later | ADR-001 |
| Modular monolith with enforced module boundaries | ADR-002 |
| Shared schema, PostgreSQL row-level security | ADR-003 |
| Integer minor units, double-entry general ledger | ADR-004 |
| Documentation lives with the code | ADR-005 |
| Delivery pipeline: build once, main to staging, tag to production | pending ADR-006 |
| Separate from the offline single-device product | ADR-007 |
| Background jobs and scheduling on Redis | pending ADR-008 |
| One React PWA with staff and member areas | ADR-009 |
| Backend language and framework | pending ADR-010 |
| Payment gateway | pending ADR-011 |
| Credit scoring model beyond the rules-based default | pending ADR-012 |
| SMS and USSD aggregator | pending ADR-013 |

## 5.3 Containers

| Container | Technology | Responsibility |
|---|---|---|
| Reverse proxy | Caddy or Nginx (chapter 9) | TLS termination with a wildcard certificate, host routing (`<slug>.`, `app.`, `api.`), static caching headers, request size limits |
| Web | React 18, TypeScript, Vite, PWA; static files served by a small web server container | Staff area, member area, platform console (ADR-009) |
| API | Backend framework per pending ADR-010 | All HTTP endpoints in chapter 7; runs every module in one process; one transaction per request |
| Worker | Same codebase and image as the API, different entry point; Redis-backed queue per pending ADR-008 | Drains the outbox (SMS, email, PDF rendering, report builds), runs scheduled jobs (nightly arrears, interest, reminders, reconciliation, backups trigger) |
| PostgreSQL 16 | Database | System of record for all tenants (chapter 6) |
| Redis 7 | Cache and queue | Job queue, rate limits, permission cache, revoked session set. Holds nothing that cannot be rebuilt: losing Redis loses queued jobs, which the outbox re-enqueues |
| Object storage | Cloudflare R2 (S3 API) | Generated PDFs, uploads, encrypted database backups |

External systems: SMS and USSD aggregator (pending ADR-013), payment gateway
(pending ADR-011) connecting to mobile money operators, transactional email provider.

Deployment: one virtual machine per environment (staging, production), all containers
under Docker Compose, object storage external. Chapter 9 and pending ADR-006 hold the
detail.

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
lending           vertical module (ADR-001)
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
T". Tests fix it. No module reads the system clock directly; a lint rule or architecture
test enforces it. Business date is computed in the tenant's timezone.

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
