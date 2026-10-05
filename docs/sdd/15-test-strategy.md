# 15. Test Strategy

**Status:** Draft · **Owner:** Hillary

## 15.1 Purpose

What is tested, at which level, with what data, and which tests gate a merge. Tools follow
ADR-010 (section 15.3.1).

## 15.2 Principles

- **Money and isolation are tested hardest.** A wrong balance or a cross-tenant read is
  worse than any other defect, so they get dedicated suites that run on every pull
  request.
- **Real PostgreSQL, not a substitute.** RLS, constraint triggers, composite foreign keys
  and `SELECT ... FOR UPDATE` only behave correctly on the real engine. Integration tests
  run against PostgreSQL 16 in Testcontainers, in CI and on a developer's machine alike,
  initialised with the servers' own role script and migrator, and connected as `bms_app`
  (a test connected as a superuser passes with every policy dropped).
- **Fixed clocks.** Every test that depends on time sets the business date through the
  clock abstraction (chapter 5 section 5.4.5).
- **Fabricated data only.** No production data in any environment other than production
  (15.8).
- **Every requirement has a test.** A test name or docstring cites the FR or NFR ID it
  proves; a script lists FR IDs from chapter 3 that no test cites, and the list must
  shrink, never grow, sprint over sprint.

## 15.3 Test pyramid

| Level | Scope | Speed | Runs |
|---|---|---|---|
| Unit | Pure functions: calculations, normalisation, validators, permission matrix, template rendering | milliseconds | Every push |
| Integration | A module's services against a real database, inside a transaction bound to a tenant | about a second each | Every push |
| API | HTTP endpoints through the application, real database, auth stub or real auth | about a second each | Every push |
| Architecture | Module boundaries, route permissions, RLS catalogue, clock usage | seconds | Every push |
| Golden | Import pipeline over the fixture; reports over their datasets | seconds | Every push |
| End to end | Browser flows on staging | minutes | After deploy to staging (later: nightly) |
| Performance | Load tests against staging with the capacity dataset | tens of minutes | Before a production release that touches queries or jobs |

### 15.3.1 Tools

| Level | Backend | Frontend |
|---|---|---|
| Unit and architecture | JUnit 6 in Maven Surefire, classes named `*Test`; Spring Modulith `ApplicationModules.verify()`; ArchUnit | Vitest (`*.test.ts`) |
| Integration and API | JUnit 6 in Maven Failsafe, classes named `*IT`; Spring Boot test on a random port with `TestRestTemplate`; Testcontainers 2, PostgreSQL 16 (`TestDatabase`, one container per test JVM) | |
| Contract | `OpenApiSnapshotIT` against `docs/api/openapi.json` | The generated `schema.d.ts` must match (CI) |
| End to end | | Playwright, later |

`mvn verify` runs all backend levels; `npm test` the frontend unit tests (`make test` runs both).

## 15.4 Isolation tests (RLS)

Run connected as `bms_app`, never as the owner. Built so far: items 1, 2 (reads for every
table, writes for representative tables), 3, 5 and 9 in `RlsIsolationIT`, which enumerates
tenant-owned tables from the catalogue and requires a factory row for each; item 4 in
`TenantBindingIT`; item 6 in `DatabaseRoleGuardIT`; item 7 for the members list and detail in
`MembersApiIT`, for branches in `TenancyAdminIT`, for the approval queue in `ApprovalsIT` and for
the audit log in `AuditSearchIT`. The platform tables of chapter 6 section 6.4 carry no tenant
policy by design and are left out of the catalogue query. The catalogue lists tables only, so each view over tenant
data gets its own read test (`lending_member_links_v` in `RlsIsolationIT`).

### 15.4.1 Identity, tenancy and approvals (increment 1)

| Test | Proves |
|---|---|
| `StaffAuthIT` | Invitation link shown to the admin, audited and sent through the notification port; expiry and replacement; weak passwords; generic sign-in errors; lockout on the fifth failure; forced TOTP enrolment for a tenant admin; a TOTP code works once; a recovery code works once and replacement invalidates the old set; another admin resets a lost factor and the user's sessions end; refresh rotation, reuse detection and idle expiry; deactivation refused on the next request; a token refused on another tenant's host; masked phones in audit rows (FR-IAM-01, FR-IAM-04 to FR-IAM-08, FR-IAM-11, FR-IAM-12, FR-AUD-03, FR-AUD-05) |
| `PermissionMatrixIT` | Seeded `role_permissions` equal the chapter 8 table; each role signed in holds exactly its column; for every route a principal lacking only its permission gets 403 (FR-IAM-02, FR-IAM-03) |
| `ApprovalsIT`, `ApprovalExpiryJobIT` | Pending with no effect; approval executes the stored payload once; the maker cannot approve (service and database CHECK); conflicted checkers; rejection needs a note; expiry and cancellation; stale subject; failed execution stays pending with the error; threshold; one pending request per subject; the queue per role and branch; the nightly expiry (FR-APR-01 to FR-APR-08) |
| `TenancyAdminIT` | Branch create, rename with `If-Match`, deactivate, head office kept; scoped branch lists and `/me` branches; plan limits for branches, staff and members; settings validation, conflicts and audited before and after; tenant-wide MFA; the suspended tenant (FR-BR-01, FR-BR-03, FR-BR-04, FR-TEN-04, FR-TEN-06, FR-TEN-08) |
| `PlatformIT` | Operator setup token and mandatory TOTP; tenant creation with head office, modules, chart, settings, invited admin and audit rows; slug rules; module switching and the job tenant list; subscription moves and suspension; platform MFA reset of a tenant admin; host and token separation (FR-TEN-01 to FR-TEN-06, FR-IAM-12) |
| `AuditSearchIT` | Search filters and branch scope; CSV export, audited; audited denial on a money-moving route (FR-AUD-03, FR-AUD-04) |
| `Increment1AcceptanceIT` | The increment 1 demo end to end: a platform operator creates a tenant, its admin enrols MFA and invites a branch manager and a cashier, the cashier requests a test action and the branch manager approves it |
| `TenantHostPatternTest`, `TenantResolutionFilterTest`, `MembersApiIT`, `PlatformIT`, `hosts.test.ts` | The host rules of chapter 7 section 7.2 (ADR-018): the pattern and platform host are validated at startup (exactly one `{slug}` in the leftmost label, valid labels, platform host not a tenant host); exact, case-insensitive matching; look-alike hosts (another zone appended, a trailing dot, extra labels, a different separator, reserved or too long slugs) resolve no tenant and answer 404; platform routes answer only on the platform host; invitation links use the pattern; the PWA classifies hosts with the same rules (FR-TEN-02) |
| `CredentialsTest`, `PrincipalTest`, `SecurityArchitectureTest` | RFC 6238 vectors, the data key box, argon2id and password rules, recovery codes, token verification; per-permission branch scope; only identity sets the principal and only tenancy binds a request's tenant |

The maker-checker tests use a test-only action type registered in the test sources
(`TestApprovalAction`, requested with a cashier's permission and decided with a branch manager's),
because no production action exists before increment 2 (ADR-015).

### 15.4.2 Retail (ADR-020)

Every retail table gets its factory row in `RlsIsolationIT`, so the catalogue, cross-tenant read,
write and unbound-session tests cover it without a separate suite.

| Test | Proves |
|---|---|
| `RetailCatalogueIT` (#51) | Products with an `initial` history row and audit; codes unique ignoring case; a manual price edit and its history row commit together and need `retail.price.edit`; PATCH never changes a price; a user signed in with the `retail_sales` role holds exactly the sales column and receives no cost field at all (absent, not null) on products and price history; a tenant without the module gets `module_not_enabled`; switching the module on seeds the retail chart once and shares cash, bank and opening balance equity with the lending chart; price history rejects UPDATE and DELETE for `bms_app` (no privilege) and the owner (trigger) (FR-RET-01, FR-RET-02, FR-RET-13, FR-RET-14) |
| `ModularityTest` | Adds `retailNeverDependsOnLending`: no `retail.*` module has a direct dependency on a `lending.*` module (ADR-020) |

1. **Catalogue test.** Query `pg_class` and `pg_policy` for every table with a
   `tenant_id` column (plus `tenants`). Each must have `relrowsecurity` and
   `relforcerowsecurity` true and a `tenant_isolation` policy whose expression uses
   `current_setting('app.tenant_id')`. Any table added without it fails the build
   (NFR-ISO-01).
2. **Generated cross-tenant test per table.** Seed tenants A and B with one row each in
   every tenant-owned table (a factory per table). Bound to A: `SELECT` returns only A's
   row; `UPDATE` and `DELETE` targeting B's row affect 0 rows; `INSERT` with
   `tenant_id = B` is rejected by `WITH CHECK` (NFR-ISO-02).
3. **Unbound session test.** With no `app.tenant_id` set, a `SELECT` on each tenant-owned
   table raises (NFR-ISO-03).
4. **Transaction-local test.** Bind tenant A in one transaction on a pooled connection,
   commit, start a new transaction on the same connection without binding: queries raise
   (no leak).
5. **Composite key test.** Inserting a row of tenant A that references a parent of tenant B
   fails on the foreign key even when RLS is bypassed by the owner role.
6. **Role guard test.** Start the application with a role that owns a table or has
   `BYPASSRLS`: startup fails (NFR-SEC-03).
7. **Branch scope tests.** For every list endpoint on a branch-owned resource: a user
   scoped to branch 1 never receives a branch 2 row, and a detail request for a branch 2
   id returns 404 (NFR-ISO-04).
8. **Member ownership tests.** Member portal endpoints return 404 for another member's
   records (FR-MSS-01).
9. **Resolver function tests.** `app_resolve_tenant` returns only an id and only for active
   tenants; `app_list_active_tenants` returns only ids; `app_list_active_tenants_with_module`
   leaves out a tenant with the module switched off (`PlatformIT`).

## 15.5 Module boundary test

Fails the build when any rule of ADR-002 is broken: core depending on a vertical, a
module using another module's internals, one vertical depending on another, the kernel
depending on anything, or a cycle. The allowed dependency list is chapter 5 section
5.4.2, kept as data in each module's `package-info.java` (`allowedDependencies`), so a change
to the architecture shows up in review as a change to that line. `ModularityTest` runs Spring
Modulith's `verify()` over them and adds explicit rules that no `core.*` module depends on a
vertical and that no `retail.*` module depends on a `lending.*` module (ADR-020). `RoutePermissionIT` enumerates routes and fails on any without a declared permission
(FR-IAM-03), `ClockArchitectureTest` fails on any direct system clock read outside the
kernel, and `SecurityArchitectureTest` fails when a module other than identity sets the current
principal or one other than tenancy binds a request's tenant (ADR-017).

## 15.6 Money correctness

Built so far (#40): the Schedules row, in `ScheduleCalculatorTest` (worked examples A, B and C as
exact tables, and seeded random terms for the properties) and `LoanProductsIT` (the same examples
through the preview endpoint).

| Area | Tests |
|---|---|
| Schedules | Worked examples A, B and C (chapter 3 section 3.4) as exact tables. Property-based tests over random principal, rate, term and frequency: principals sum to `P`; interest sums to `I` (flat); balance ends at 0 (declining); every due date strictly increases; month-end clamping never drifts. |
| Allocation | Worked example D. Properties: allocations sum to the payment; no component is over-allocated; order respected; payoff detection. |
| DPD and penalties | Fixed-clock tables for each penalty method, grace days, caps, and rerun idempotency. |
| Payoff | Both methods, with and without the flat rebate flag. |
| Posting rules | For each event in chapter 6 section 6.6.3, the exact journal lines. |
| Ledger invariants | An unbalanced entry is rejected at commit by the trigger even when inserted with raw SQL; journal UPDATE and DELETE fail; posting to a closed period fails. |
| Subledger reconciliation | After a randomised sequence of disbursements, repayments, reversals, write-offs, deposits and withdrawals, every control account equals its subledger (a property test run with a fixed seed in CI and a random seed nightly). |
| Concurrency | 50 concurrent repayments on one loan and 50 concurrent deposits on one savings account: final balances exact, receipt numbers gap-free and unique. |
| Races on shared records | Two concurrent registrations of one collateral reference, and two loans pledging one item at the same moment: exactly one succeeds and the other gets `collateral_already_pledged`. Each rule that is a check before a write has a lock or a unique index behind it and a two-thread test. |
| Idempotency | Same key replays; same key with different body is refused; concurrent same-key requests produce one record (chapter 7 section 7.8). |
| Maker-checker | Self-approval refused by the service and by the database CHECK; stale payload refused; expiry. |

Coverage gate for the lending calculation code: 95 percent lines (NFR-MNT-02).

## 15.7 Golden tests

- **Import golden test.** Runs the `pilot_loan_register_v1` pipeline over
  `fixtures/pilot_loan_register_sample.csv`, and over an XLSX built from it, with the
  business date fixed at 30 June 2026, and asserts exactly the classes, issue codes and
  normalised values in chapter 13 section 13.12. Then applies the standard resolutions,
  commits, and asserts the created records, the opening journal and the DPD values listed
  there.
- **Normalisation tables.** Every table of examples in chapter 13 section 13.6 is a
  parametrised unit test.
- **Report golden tests.** Each report in chapter 14 runs over its fabricated dataset and
  is compared with a committed expected output (JSON), including the PAR worked example.
- **Document golden tests.** Each PDF template renders fabricated data; the test compares
  the extracted text with a committed expected text (FR-DOC-01).
- **Contract snapshot.** The generated OpenAPI document must equal
  `docs/api/openapi.json` (chapter 7 section 7.3).

Updating a golden file is allowed only in a pull request that explains why the expected
output changed.

## 15.8 Test data policy

- All fixtures are fabricated: names like "Test Borrower 01", phones in the range
  `+2567000000NN`, NINs like `CMTEST0000001A`, villages like "Test Village A", plates like
  `UXX 001X`, round amounts.
- Production data never enters development, CI or staging. Debugging a production issue
  uses production logs and a fabricated reproduction.
- Staging holds only fabricated tenants seeded by a script; the capacity dataset
  (NFR-CAP-01) is generated, not copied.
- SMS and payment adapters in development, CI and staging point at provider sandboxes or
  at fakes that record calls. Staging never sends SMS to real numbers outside an
  allow-list of team phones.

## 15.9 Frontend tests

- Unit tests for formatters (money from minor units, dates), permission-driven visibility,
  form validation shared with the API's rules, and the offline draft store. Built so far: money
  formatting, the second factor and invitation token parsing, the password rule echo, recovery
  code display and the active branch choice (`auth/codes.test.ts`).
- Component tests for the schedule table, allocation display and approval queue.
- Bundle budget check for the member area (NFR-PERF-06).
- Accessibility checks on key screens (NFR-ACC-01).

## 15.10 End-to-end tests (later)

Browser tests against staging after each deploy, starting once the staff loan flow
exists: sign in, register member, create and submit application, appraise, approve as a
second user, request and authorise disbursement, record a partial repayment, check the
schedule and the trial balance, and offline behaviour of the member area (NFR-OFF). They
become a deploy gate for production once stable for two sprints.

## 15.11 CI gates

A pull request cannot merge unless all of these pass (the workflow lives in
`.github/workflows/`; chapter 10 describes the pipeline):

- lint and type checks (backend and frontend);
- unit, integration, API, architecture and golden tests against PostgreSQL 16
  (Testcontainers; there is no Redis, ADR-008);
- frontend build and tests;
- OpenAPI snapshot check;
- documentation guards: dash guard, ADR citation guard, architecture model validation;
- linked issue guard.

## 15.12 Open items

- A mutation testing pass over the calculation code is `Later`.
