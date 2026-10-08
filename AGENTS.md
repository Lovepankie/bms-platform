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
  (the retail cash book, issue #147, takes `V34`; `V35` next).
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
| `core.notifications` | Templates, outbox, SMS and email adapters; today the platform outbox with SMTP and Telegram senders (ADR-024) | NTF | 6.4 `notification_outbox`, 6.5 | 7.11.3, 7.11.7 |
| `core.onboarding` | Public sign-up, applications, Verify, Needs info, Reject, Activate (ADR-024) | ONB | 6.4 `onboarding_applications` | 7.11.3 |
| `core.documents` | PDFs, uploads, object storage, signed URLs | DOC | 6.5 `documents` | 7.11.7 |
| `core.reporting` | Report catalogue and runs | RPT; chapter 14 | 6.5 `report_runs` | 7.11.8 |
| `core.imports` | Import batches, review queue, commit | IMP; chapter 13 | 13.4 | 7.11.9 |
| `core.payments` | Payment intents, gateway, callbacks | PAY; chapter 12 | 6.5 | 7.11.10 |
| `core.jobs` | db-scheduler tasks, per-tenant job runner (ADR-008) | 5.4.4 | `scheduled_tasks` | none |
| `core.operations` | `/version`, readiness, database role guard | NFR-SEC-03 | none | 7.11.1 |
| `lending.manifest` | The vertical's registration with the core | 5.4.3 | none | none |
| `lending` members | Members, KYC, next of kin, relationships | MEM | 6.7 | 7.11.11 |
| `lending` products | Loan products and versions | PRD | 6.7 | 7.11.12 |
| `lending` loans | Origination, schedules, disbursement, repayments, arrears, closure | ORG, DIS, REP, ARR, LCL; 3.4 | 6.7 | 7.11.13 |
| `lending` collateral | Collateral register | COL | 6.7 | 7.11.14 |
| `lending.seed` | The `seed-lending` command: fabricated loan book, savings and investments for one empty staging tenant (ADR-026, ADR-031, ADR-032) | none | none (writes 6.7 tables) | none (a command, `docs/runbooks/seed-lending.md`) |
| `lending.savings` | Savings products, accounts, movements, end of day and interest, statements, savings reports (ADR-032) | SAV | 6.7 | 7.11.15 |
| `lending.investments` | Investment products, investments, returns job, maturity, rollover, early withdrawal, maturity ladder (ADR-031) | INV; 3.25.1 R-INV | 6.7 | 7.11.16 |
| `lending.insights` | The insights read model, snapshots, daily digest, panel registry (ADR-030) | INS | 6.7 | 7.11.22 |
| `lending` collections | Due lists, arrears, actions | CLN | 6.7 | 7.11.17 |
| Member area | Member self-service | MSS; chapter 11 | none | 7.11.18 |
| `retail.manifest` | The retail vertical's registration with the core (ADR-020) | 3.28 | 6.11.1 chart | none |
| `retail.catalogue` | Categories, units, products, append-only price history | RET-01, RET-02 | 6.11 | 7.11.20 |
| `retail.stock` | Stock movements and balances, stock-takes, usage and damage, transfers between branches, reconciliation; retail posting and idempotency helpers | RET-03, RET-07, RET-08, RET-11, RET-16 | 6.11 | 7.11.20 |
| `retail.sales` | Sales with snapshots, voids, credit buyers, payments | RET-04, RET-05, RET-11 | 6.11 | 7.11.20 |
| `retail.purchasing` | Suppliers, restocks that set prices atomically | RET-06, RET-11 | 6.11 | 7.11.20 |
| `retail.reports` | Valuation, revaluation difference, daily profit | RET-09, RET-10 | 6.11.3 | 7.11.20 |
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

Pending (cite only as "pending ADR-NNN"):

- ADR-011 Payment gateway (Pesapal or Interswitch)
- ADR-012 Credit scoring model beyond the rules-based default
- ADR-013 SMS and USSD aggregator
- ADR-021 Weighted average cost for retail, per tenant (ADR-020 decision 6)
- ADR-022 The cash book: expenses, banking and advances, likely partly core (ADR-020)

## Team

| Name | GitHub | Role |
|---|---|---|
| Hillary Arinda | @arindahills | Dev lead; required reviewer for production releases |
| Dennis Kaweesi | @Lovepankie | Developer |
| Solomon Ariho | @arihosolomon | Developer |
