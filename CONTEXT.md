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
- `backend/src/main/resources/db/migration/`: V1 to V14, V20, V21. V15 to V19 stay unused.
- `docs/sdd/` chapters 1 to 15, `docs/adr/`, `docs/runbooks/`, `docs/workspace.dsl`.

## Current status (2026-10-07)

- Lending increments 1 to 4 are on `main`: members, KYC documents, collateral, loan products,
  applications with guarantors and pledges, appraisal with the rules-based credit score,
  approval with its checks and the approval expiry job. The review follow-ups landed with #76
  (V21).
- Retail R1 to R5 and its review fixes are on `main`, with the import command.
- Next lending work: increment 5, disbursement and repayments (#108), then arrears (#109),
  the pilot import (#110) and reports (#111).

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
