# 4. Non-Functional Requirements

**Status:** Draft · **Owner:** Hillary

## 4.1 How to read this chapter

Each requirement has an ID `NFR-<AREA>-NN`, a measurable target and how it is verified.
Areas: SEC security, ISO tenant isolation, PERF performance, CAP capacity, AVL
availability, BAK backup and recovery, OFF offline tolerance, DP data protection, OBS
observability, MNT maintainability, ACC accessibility and localisation.

Sizing assumption for targets: one production virtual machine of the class described in
chapter 9 (2 to 4 vCPU, 4 to 8 GB RAM), 1 to 20 tenants, the largest with up to 20
branches, 100 staff users, 50,000 members and 100,000 loans over its lifetime, and up to
50 concurrent staff users across all tenants.

## 4.2 Security (SEC)

| ID | Requirement | Verification |
|---|---|---|
| NFR-SEC-01 | All traffic is TLS 1.2 or later; HTTP redirects to HTTPS; HSTS one year. | Configuration test against staging on each deploy. |
| NFR-SEC-02 | Passwords and PINs are hashed with argon2id; one-time codes and refresh tokens are stored hashed only. | Unit test inspects stored values. |
| NFR-SEC-03 | The API and worker refuse to start if their database role owns application tables, is a superuser or has `BYPASSRLS`. | Startup test with a misconfigured role. |
| NFR-SEC-04 | Every route declares a permission or is explicitly public. | Route enumeration test (FR-IAM-03). |
| NFR-SEC-05 | No secret is committed to the repository. | Secret scanning in CI; `.env.example` holds placeholders only. |
| NFR-SEC-06 | Dependencies are scanned for known vulnerabilities on every pull request and weekly; a critical finding fails the build. | CI job. |
| NFR-SEC-07 | Money-moving endpoints require an idempotency key and are maker-checker where chapter 8 section 8.4 says so. | Endpoint tests. |
| NFR-SEC-08 | Application logs contain no NIN, phone number, password, PIN, token or member request body. | Redaction filter unit test; log sample review before each production release. |

## 4.3 Tenant isolation (ISO)

| ID | Requirement | Verification |
|---|---|---|
| NFR-ISO-01 | Every tenant-owned table has RLS enabled and forced with the standard policy. | Catalogue test: every table with a `tenant_id` column has both flags and the policy (chapter 15). |
| NFR-ISO-02 | For every tenant-owned table, a session bound to tenant A can neither read, insert, update nor delete tenant B's rows. | Generated isolation test per table against real PostgreSQL 16 in CI. |
| NFR-ISO-03 | A session with no tenant bound gets an error on any tenant-owned table. | Isolation test. |
| NFR-ISO-04 | Every list endpoint on a branch-owned resource returns no row outside the principal's branch scope. | Per-endpoint test with two branches. |
| NFR-ISO-05 | Object storage access for one tenant's documents is only possible through a signed URL issued after a permission check. | Test: a guessed key without a signature is refused by the bucket. |
| NFR-ISO-06 | Redis keys for tenant-scoped data carry the tenant prefix. | Unit test on the key builder. |

## 4.4 Performance (PERF)

Measured at the API, on staging sized like production, with the capacity dataset in
NFR-CAP-01 loaded, at 50 concurrent users.

| ID | Requirement | Verification |
|---|---|---|
| NFR-PERF-01 | Read endpoints (detail, list page of 50): p95 under 300 ms, p99 under 800 ms. | Load test before each production release that touches queries. |
| NFR-PERF-02 | Money-moving endpoints (repayment, deposit, disbursement execution): p95 under 800 ms. | Load test. |
| NFR-PERF-03 | Member search (FR-MEM-11) over 50,000 members: p95 under 1 second. | Load test. |
| NFR-PERF-04 | Synchronous reports return in under 5 seconds or are run in the worker (FR-RPT-04). Portfolio and PAR reports over 100,000 loans complete in the worker in under 60 seconds. | Report timing test on the capacity dataset. |
| NFR-PERF-05 | The nightly job (FR-ARR-01) for 100,000 active loans across all tenants completes in under 30 minutes. | Timed run on staging. |
| NFR-PERF-06 | The member area's initial JavaScript is under 250 KB compressed; first contentful paint under 3 seconds on a simulated slow 3G connection with a mid-range Android device profile. | CI bundle size check; Lighthouse run on staging. |
| NFR-PERF-07 | The pilot spreadsheet import of 5,000 rows parses and classifies in under 60 seconds. | Import timing test on a generated fixture. |

## 4.5 Capacity (CAP)

| ID | Requirement | Verification |
|---|---|---|
| NFR-CAP-01 | A generated capacity dataset (fabricated) exists: 3 tenants, 20 branches, 50,000 members, 100,000 loans with schedules and repayments, 30,000 savings accounts. | Generator script output checked into CI artifacts, never into the repository. |
| NFR-CAP-02 | Database size growth is monitored; the host alerts at 70 percent disk use. | Monitoring rule (chapter 9). |

## 4.6 Availability (AVL)

| ID | Requirement | Verification |
|---|---|---|
| NFR-AVL-01 | Production availability of at least 99.5 percent per calendar month, excluding announced maintenance. | Uptime monitor on `/healthz` and a tenant sign-in page every minute. |
| NFR-AVL-02 | Planned maintenance is announced to tenant admins 48 hours ahead and scheduled outside 07:00 to 21:00 East Africa Time. | Runbook. |
| NFR-AVL-03 | A deploy causes at most 30 seconds of unavailability, and a failed health check rolls back automatically to the previous version (pending ADR-006; chapter 10). | Deploy log review. |
| NFR-AVL-04 | Loss of the SMS aggregator or payment gateway degrades only the dependent feature: messages queue and retry, payment intents fail cleanly, everything else works. | Fault injection test with the provider adapter returning errors. |

## 4.7 Backup and recovery (BAK)

| ID | Requirement | Verification |
|---|---|---|
| NFR-BAK-01 | **RPO 24 hours**: a full logical backup (`pg_dump`, custom format) of the production database runs nightly. | Backup job log; alert if no successful backup in 26 hours. |
| NFR-BAK-02 | Backups are encrypted before leaving the host and stored in object storage (Cloudflare R2), separate from the production host. | Restore drill decrypts from R2. |
| NFR-BAK-03 | Backups are retained 30 days; the first backup of each month is retained 12 months. | Bucket lifecycle rule. |
| NFR-BAK-04 | **RTO 2 hours**: production can be restored onto a fresh host from the latest backup, and be serving, within 2 hours of the decision to restore. | Restore drill on a fresh VM at least quarterly, timed, recorded in `docs/runbooks/`. |
| NFR-BAK-05 | Generated documents in object storage are not re-creatable in general and are covered by the bucket's own durability; the database holds each document's checksum for integrity checks. | Monthly sample check of checksums. |
| NFR-BAK-06 | Point-in-time recovery (continuous WAL archiving) is `Later`, triggered by a tenant whose licence or contract requires an RPO under 24 hours. | Recorded here as a known limit. |

## 4.8 Offline tolerance of the PWA (OFF)

Connectivity in the field is intermittent. The rule is: **reads degrade gracefully,
drafts survive, money never moves offline.**

| ID | Requirement | Verification |
|---|---|---|
| NFR-OFF-01 | The app shell (HTML, JavaScript, CSS, icons, fonts) is cached by the service worker, so the app opens with no network. | Playwright test with the network disabled. |
| NFR-OFF-02 | Member area: the last successfully fetched loans, schedules, savings and investment views are cached in IndexedDB and shown offline with a visible "last updated" time and an offline banner. | Test with network disabled after one sync (FR-MSS-06). |
| NFR-OFF-03 | Staff area: member registration and loan application forms autosave drafts locally every few seconds; a draft survives closing the app and is submitted when the user presses submit while online. Each draft carries a client-generated idempotency key so a retried submission cannot create duplicates. | Test: fill form, go offline, reload, go online, submit twice, one record. |
| NFR-OFF-04 | Repayments, disbursements, deposits, withdrawals, approvals and any other money-moving or approving action are refused offline, with a clear message. They are never queued for later sending. | Test with the network disabled. |
| NFR-OFF-05 | Cached member data is cleared on sign-out and when the session's refresh token expires. Staff data other than own drafts is not cached for offline use. | Test inspects IndexedDB after sign-out. |
| NFR-OFF-06 | A new frontend version activates on the next navigation after download, and never while a form has unsaved input. | Manual test script per release. |

## 4.9 Data protection (DP)

The platform processes personal data on behalf of each tenant. Under the Uganda Data
Protection and Privacy Act, 2019 and the Data Protection and Privacy Regulations, 2021,
the tenant is the data controller for its members' data and the platform operator is a
data processor. These requirements are the technical side of that relationship; the
contractual side (a processing agreement per tenant) is commercial and lives outside this
repository.

| ID | Requirement | Verification |
|---|---|---|
| NFR-DP-01 | Purpose limitation and minimisation: only the fields in chapter 6 are collected; no field for special personal data (health, religion, political opinion) exists. | Schema review at each migration touching member tables. |
| NFR-DP-02 | Access control and least privilege per chapter 8; NIN and phone masked in lists, logs and audit. | Chapter 8 tests. |
| NFR-DP-03 | A tenant admin can produce, for one member, a complete export of the member's personal data held by the tenant (subject access request) within the statutory response period. | Member data export function tested (P2). |
| NFR-DP-04 | Correction: staff can correct a member's personal data; every correction is audited with before and after values. | FR-AUD-01 test. |
| NFR-DP-05 | Security breach handling: the platform operator can identify affected tenants and members from audit and access logs, and notify the tenant without undue delay so the tenant can notify the Personal Data Protection Office and affected members. | Incident runbook in `docs/runbooks/`. |
| NFR-DP-06 | Retention: personal and financial records are retained for the period required by law and the tenant's licence conditions, then deleted or anonymised on the tenant's instruction. The exact periods are an open question (`docs/specs/lending-mvp-scope.md`); until settled, nothing is deleted automatically. | Recorded open item. |
| NFR-DP-07 | Cross-border processing: the production host and backups may be located outside Uganda. Before the pilot tenant goes live, the dev lead confirms that the hosting location meets the Act's conditions for processing or storing personal data outside Uganda, and records the outcome in an ADR. | Go-live checklist item. |
| NFR-DP-08 | The platform operator is registered with the Personal Data Protection Office as required, and each tenant is reminded of its own registration duty during onboarding. | Onboarding runbook item. |

## 4.10 Observability (OBS)

| ID | Requirement | Verification |
|---|---|---|
| NFR-OBS-01 | Structured JSON logs with `request_id`, `tenant_id`, `user_id` (never PII), route, status and duration for every request. | Log format test. |
| NFR-OBS-02 | The `request_id` is returned in a response header and in every error body, and is stored on audit rows. | Test. |
| NFR-OBS-03 | Unhandled errors are reported to an error tracker with PII scrubbed. | Staging smoke test. |
| NFR-OBS-04 | Alerts: health check failing 3 minutes, backup missing 26 hours, disk over 70 percent, worker queue older than 15 minutes, subledger reconciliation difference (FR-GL-10), nightly job failure. | Alert rules in chapter 9. |

## 4.11 Maintainability (MNT)

| ID | Requirement | Verification |
|---|---|---|
| NFR-MNT-01 | Module boundaries hold (ADR-002). | Module boundary test in CI. |
| NFR-MNT-02 | Line coverage of the lending calculation code (schedules, allocation, DPD, penalties, payoff, interest) is at least 95 percent; overall backend at least 80 percent. | CI coverage gate. |
| NFR-MNT-03 | Every behaviour change updates the relevant chapter in the same pull request (ADR-005). | Pull request template check and review. |
| NFR-MNT-04 | Migrations are expand and contract (chapter 6 section 6.9). | Review; the previous release's test suite runs against the new schema in CI. |

## 4.12 Accessibility and localisation (ACC)

| ID | Requirement | Verification |
|---|---|---|
| NFR-ACC-01 | Staff and member areas meet WCAG 2.1 AA for contrast, keyboard use and labels. | Automated accessibility checks in CI; manual review per release. |
| NFR-ACC-02 | English is the interface language in the MVP; all strings go through a translation layer so Luganda and Swahili can be added. SMS templates are per locale. | Lint rule forbids literal user-facing strings outside the translation files. |
| NFR-ACC-03 | Amounts are shown with the currency's exponent and thousands separators (for example `UGX 1,200,000`); dates as `16 Apr 2026` in the UI and ISO in exports. | Formatting unit tests. |
| NFR-ACC-04 | The member area works on screens 360 px wide and up. | Playwright viewport test. |
