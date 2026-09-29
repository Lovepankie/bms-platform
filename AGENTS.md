# AGENTS.md

Read this file first. It is the onboarding brief for every developer and every coding
agent working in this repository.

## What this repository is

**BMS Platform** is a multi-tenant SaaS business management system for small and medium
enterprises in Uganda and East Africa. It is a **platform core plus vertical modules**
that a tenant switches on (ADR-001). The first vertical, being built now, is **lending**
(microfinance and money lending: members, loans, collateral, savings, investments,
collections). Retail is a later vertical and is not built now.

This repository holds the application code and, under `docs/`, the complete specification:
the System Design Document, the decision log (ADRs), the Structurizr architecture model,
the MVP scope and the pilot data dictionary. Documentation lives with the code; there is
no other documentation repository (ADR-005).

The first customer is referred to only as **the pilot tenant**: a licensed money lender
(Tier 4, Uganda) with loans, savings and investment products.

## Current state (read before writing code)

- The specification (`docs/`) is complete enough to build the lending MVP.
- **The backend language and framework are under review** (pending ADR-010). The
  `backend/` folder holds a partial early scaffold. Do not extend `backend/` until
  pending ADR-010 is accepted; if it changes the stack, the scaffold is replaced. The data model,
  API contract, rules and tests in `docs/` are framework-neutral and stand either way.
- Frontend (ADR-009), data model (chapter 6), API contract (chapter 7) and test design
  (chapter 15) can be worked on now.

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
├── backend/             API and worker source (framework pending ADR-010)
├── frontend/            the React PWA: staff area, member area, platform console (ADR-009)
├── deploy/              host-side deployment files (SDD chapter 9)
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

Logical modules (ADR-002). Physical package paths follow the framework chosen in
pending ADR-010. Tables of a vertical are prefixed with its key (`lending_...`).

| Module | Owns | Requirements (ch. 3) | Tables (ch. 6) | Endpoints (ch. 7) |
|---|---|---|---|---|
| `core.kernel` | Money, rates, clock, tenant context, errors | 3.4 R-ROUND | none | none |
| `core.tenancy` | Tenants, plans, subscriptions, modules, settings, branches | TEN, BR | 6.4, 6.5 | 7.11.3, 7.11.4 |
| `core.identity` | Users, credentials, sessions, roles, permissions | IAM | 6.4, 6.5 | 7.11.2, 7.11.4 |
| `core.audit` | Audit log | AUD | 6.5 `audit_log` | 7.11.4 |
| `core.approvals` | Maker-checker | APR | 6.5 `approval_requests` | 7.11.5 |
| `core.ledger` | Chart of accounts, periods, journals, reconciliation | GL | 6.6 | 7.11.6 |
| `core.notifications` | Templates, outbox, SMS and email adapters | NTF | 6.5 | 7.11.7 |
| `core.documents` | PDFs, uploads, object storage, signed URLs | DOC | 6.5 `documents` | 7.11.7 |
| `core.reporting` | Report catalogue and runs | RPT; chapter 14 | 6.5 `report_runs` | 7.11.8 |
| `core.imports` | Import batches, review queue, commit | IMP; chapter 13 | 13.4 | 7.11.9 |
| `core.payments` | Payment intents, gateway, callbacks | PAY; chapter 12 | 6.5 | 7.11.10 |
| `lending` members | Members, KYC, next of kin, relationships | MEM | 6.7 | 7.11.11 |
| `lending` products | Loan products and versions | PRD | 6.7 | 7.11.12 |
| `lending` loans | Origination, schedules, disbursement, repayments, arrears, closure | ORG, DIS, REP, ARR, LCL; 3.4 | 6.7 | 7.11.13 |
| `lending` collateral | Collateral register | COL | 6.7 | 7.11.14 |
| `lending` savings | Savings | SAV | 6.7 | 7.11.15 |
| `lending` investments | Investments | INV | 6.7 | 7.11.16 |
| `lending` collections | Due lists, arrears, actions | CLN | 6.7 | 7.11.17 |
| Member area | Member self-service | MSS; chapter 11 | none | 7.11.18 |

## Standing rules (hard)

These are not style preferences. A pull request that breaks one is not merged.

1. **Tenant isolation.** Every tenant-owned table has `tenant_id`, forced row-level
   security with the standard policy, and composite foreign keys on `(tenant_id, x_id)`.
   Every transaction binds `app.tenant_id` with `set_config(..., true)`. The application
   never connects as the table owner (ADR-003, chapter 6 section 6.3).
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

Accepted:

- ADR-001 Platform core plus vertical modules; lending is the first vertical
- ADR-002 Modular monolith with enforced module boundaries
- ADR-003 Tenant isolation: shared schema plus PostgreSQL row-level security
- ADR-004 Money and accounting: integer minor units and a double-entry general ledger
- ADR-005 Documentation lives with the code
- ADR-007 bms-platform and ERP_BMS are separate products
- ADR-009 Frontend: one React PWA with staff and member areas

Pending (cite only as "pending ADR-NNN"):

- ADR-006 Delivery pipeline: build once, main to staging, tag to production, migrations
  before swap
- ADR-008 Background jobs and scheduling on Redis
- ADR-010 Backend language and framework (under review)
- ADR-011 Payment gateway (Pesapal or Interswitch)
- ADR-012 Credit scoring model beyond the rules-based default
- ADR-013 SMS and USSD aggregator

## Team

| Name | GitHub | Role |
|---|---|---|
| Hillary Arinda | @arindahills | Dev lead; required reviewer for production releases |
| Dennis Kaweesi | @Lovepankie | Developer |
| Solomon Ariho | @arihosolomon | Developer |
