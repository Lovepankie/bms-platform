# CONTEXT.md

Continuity notes for picking this repo up on any machine. Technical status only; the design
lives in `docs/sdd/` and the decisions in `docs/adr/`. Start with [AGENTS.md](AGENTS.md) and
[PROCESS.md](PROCESS.md).

## What this is

A multi-tenant business management platform: a core (tenancy, branches, users and roles, audit,
maker-checker approvals, a double-entry ledger, documents, jobs) plus vertical modules a tenant
switches on. Lending is the first vertical; retail was brought forward (ADR-020).

## Stack

- Backend: Java 25, Spring Boot 4.1, Spring Modulith 2.1, plain SQL through `JdbcClient`.
- Database: PostgreSQL 16 with forced row-level security (ADR-003), Flyway migrations.
- Jobs: db-scheduler on PostgreSQL, one tenant per transaction (ADR-008).
- Frontend: React PWA with a typed client generated from `docs/api/openapi.json` (ADR-009).
- Staging: a single ARM64 host behind a tunnel (ADR-018); every merge to `main` deploys it.

## Layout

- `backend/src/main/java/com/rincoltech/bms/`: `kernel`, `core/*`, `lending/{members,collateral,products,loans}`, `retail/{catalogue,stock,sales,purchasing,reports,imports}`.
- `backend/src/main/resources/db/migration/`: V1 to V14, V20 to V23, V26, V28, V29 and V31 (the first-run preferences, #19). The numbers in between stay unused; Flyway only needs them to increase.
- `docs/sdd/` chapters 1 to 15, `docs/adr/`, `docs/runbooks/`, `docs/workspace.dsl`.

## Current status (2026-10-19)

- Lending increments 1 to 5 are on `main`, with savings, investments, insights and the loan
  documents (ADR-034).
- Lending increment 6 (#109, arrears and collections) is in three stacked PRs, merged in order:
  T1 #270 (nightly arrears job, R-PEN penalties, PAR bucket, migration V63 with every increment 6
  table), T2 #271 (penalty waivers, `charge_waiver`), T3 #272 (collections: officer reassignment, due,
  arrears and promise lists, collection actions, promises settled nightly). Open question on #265:
  a route for fee and interest waivers.
- Retail R1 to R5, transfers, catalogue management, the parity pass, the optional trade
  capabilities and landed cost (V48) are on `main`, as are report runs (V49), the SMS channel
  core (V50), the AI gateway (V51), SMS settings (V52), event SMS (V53), and mobile money: the Pesapal
  gateway core (V54), payments into the lender's own account (V56, V57), loan repayment (V59) and savings
  deposit (V60) by mobile money.
- Next lending work: the pilot import (#110) and reports (#111).

## Working rules that are easy to miss

- Never commit to `main`; branch, PR, squash or rebase merge (PROCESS.md section 6).
- Every PR closes an issue (`linked-issue-guard`), contains no em or en dashes (`dash-guard`)
  and cites only existing ADRs (`.github/scripts/adr_citation_guard.py`).
- Migrations: claim the next number on issue #50 before using it, one migration PR at a time,
  and merge one only after the last Deploy on `main` is green and `/version` on staging shows
  `main`'s sha.
- Fabricated data only in the repo: no real names, client data or money figures.
- After changing an API, regenerate the snapshot and the client:
  `UPDATE_OPENAPI_SNAPSHOT=true mvn -B verify -Dit.test=OpenApiSnapshotIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`,
  then in `frontend/`: `npx openapi-typescript ../docs/api/openapi.json -o src/api/schema.d.ts`
  and `npx tsc --noEmit`.
- Move `backend/target` aside before a full `mvn verify` after switching branches: stale
  classes and migrations from another branch leak into the tests.
