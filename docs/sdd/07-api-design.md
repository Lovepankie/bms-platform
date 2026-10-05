# 7. API Design

**Status:** Draft · **Owner:** Hillary

## 7.1 Scope

The HTTP API that the web PWA (staff and member areas), the platform console, payment
gateways and SMS or USSD aggregators call. It is implemented with Spring MVC (ADR-010);
`springdoc-openapi` generates the contract document from the code. Requirement IDs refer to
chapter 3, permissions to chapter 8, tables to chapter 6.

## 7.2 Hosts, tenant resolution and versioning

Hosts are single DNS labels under the Rincol zone, with hyphens, so the one free Cloudflare edge
certificate for `*.rincoltech.com` covers every one of them, including tenants created later
(ADR-018). They are configuration, not code:

| Host | Staging | Production (later) | Serves | Tenant |
|---|---|---|---|---|
| Tenant host, `BMS_TENANT_HOST_PATTERN` | `{slug}-bms-staging.rincoltech.com` | `{slug}-bms.rincoltech.com` | Staff area, member area, tenant API | Resolved from the slug |
| Platform host, `BMS_PLATFORM_HOST` | `bms-staging.rincoltech.com` | `bms.rincoltech.com` | Platform console and platform API (`/api/v1/platform/...`) | None; platform users only |
| Callback host | `bms-staging-callbacks.rincoltech.com` | `bms-callbacks.rincoltech.com` | Gateway and aggregator callbacks only | Resolved from the callback payload (chapter 12) |

In local development the pattern is `{slug}.localhost` (`http://demo.localhost:8000`) and the
platform host is `localhost`.

- The API validates both values at startup and refuses to start on a missing or invalid one: the
  pattern contains exactly one `{slug}`, inside the leftmost label (so the host is one label under
  the zone); every other label is a valid DNS label; the platform host is a host name that does not
  match the pattern.
- The tenant is resolved from the `Host` header by exact match against the pattern, compared
  case-insensitively: the text between the pattern's prefix and suffix must be a valid slug
  (FR-TEN-02: 3 to 63 lower case letters, digits and hyphens, no edge hyphen, not a reserved label
  `www`, `api`, `app`, `admin`, `static`, `mail`) whose host label stays within 63 characters.
  A trailing dot, a port in the name, extra labels on either side
  (`x.demo-bms-staging.rincoltech.com`, `demo-bms-staging.rincoltech.com.attacker.com`) or another
  zone carry no slug. The slug is resolved with `app_resolve_tenant(slug)` (chapter 6 section
  6.3.2). An unknown host, an unknown slug or an inactive tenant returns 404 `unknown_tenant`.
- One-time links (invitations, the first tenant admin's link) are built from the pattern:
  `https://<the tenant's host>/accept-invitation#token=...`.
- The PWA applies the same rules to its own host to choose the console, a tenant or an "unknown
  address" page. It reads the two values at run time from `/app-config.json`, which the web
  container serves from its environment, so one image serves every environment.
- In local development and tests only, the header `X-Tenant: <slug>` is accepted when the
  host carries no slug (`ALLOW_TENANT_HEADER=true`, on by default in the `dev` profile). The
  application refuses to start with it set in any profile other than `dev` or `test`. The host
  always wins over the header.
- Tenant resolution happens once, in a servlet filter, before authentication. The tenant is
  then bound to the request's thread and, through the transaction manager, to every database
  transaction (chapter 6 section 6.3.2). No endpoint takes a tenant id from its body, path or
  query, and no service chooses one.
- Every tenant request runs in one database transaction bound to the tenant
  (`set_config('app.tenant_id', ..., true)`), committed before the response is sent. A
  failed commit is never reported as success.
- Base path `/api/v1`. A breaking change gets `/api/v2` for the affected resources; the
  previous version is kept for at least one release after the frontend moves.
- Lending endpoints live under `/api/v1/lending/...`. If the tenant has not enabled the
  lending module they return 404 `module_not_enabled` (FR-TEN-03).
- Member portal endpoints live under `/api/v1/member/...` and accept only member
  principals, scoped to the member's own records.
- Platform endpoints live under `/api/v1/platform/...`. They resolve no tenant, are served only on
  the platform host (on any other host they answer 404), and accept only platform operator tokens
  (ADR-016, ADR-018).

## 7.3 Contract and generated clients

- The API publishes an OpenAPI 3.1 document at `/api/v1/openapi.json`
  (`BMS_OPENAPI_ENABLED`; off on production until it can be restricted to platform users).
- `OpenApiSnapshotIT` compares the generated document with `docs/api/openapi.json` and fails if
  they differ, so every contract change is visible in review. `make openapi` regenerates it.
- The frontend's API types (`frontend/src/api/schema.d.ts`) are generated from that file with
  `openapi-typescript` and used through `openapi-fetch` (ADR-009); CI fails if they are stale.
  Hand-written request or response types are not allowed.

## 7.4 Authentication

### 7.4.1 Tokens

| Token | Form | Lifetime | Transport |
|---|---|---|---|
| Access token | Signed JWT (asymmetric signature, `kid` header for rotation) | 15 minutes | `Authorization: Bearer <token>`; held in memory by the SPA, never in local storage |
| Refresh token | 256-bit random, stored hashed (`auth_sessions.refresh_token_hash`) | Staff idle 12 hours, absolute 7 days; members idle 30 days, absolute 90 days | Cookie `__Host-bms_rt`, `HttpOnly; Secure; SameSite=Strict; Path=/` on the tenant host |

Access token claims: `sub` (user id), `tid` (tenant id; absent for platform operators), `knd`
(`staff`, `member` or `platform`), `sid` (session id), `iat`, `exp`, `iss` (the request host),
`pur` (`access`). Access tokens are ES256 JWS (ADR-014). Between the password and the second
factor the API hands out a 5 minute MFA token (`pur=mfa`, no `sid`) that is accepted only by the
MFA endpoints. Permissions and branch scope are not in the token; they are loaded per request
from the role assignments (the 60 second cache this section allows is not built yet).

Platform operators use the same design with their own cookie, `__Host-bms_prt`, idle 2 hours and
absolute 12 hours. Both cookie names carry the `__Host-` prefix (ADR-018 finding M2, since staging,
production and the company site now share the `rincoltech.com` zone): the browser accepts a
`__Host-` cookie only with `Secure`, `Path=/` and no `Domain` attribute, so no sibling host on the
zone can set one that reaches this origin's cookie jar. `Path=/` means the browser now attaches the
cookie to every request to the host, not only its own auth path; each refresh and sign-out endpoint
is still reached at its own dedicated route, which is the only scoping that ever mattered server
side.

### 7.4.2 Per-request checks, in order

1. Resolve tenant from host (7.2).
2. Verify the access token signature and expiry.
3. `tid` equals the host's tenant, else 401 `tenant_mismatch`.
4. Session `sid` is not revoked (a primary key lookup in `auth_sessions`; there is no Redis,
   ADR-008), else 401 `session_revoked` (FR-IAM-08).
5. User status is `active`.
6. Tenant not `suspended` for state-changing methods, else 423 `tenant_suspended`. Staff sign-in,
   MFA, refresh and sign-out stay open so staff can still read and export (FR-TEN-06).
7. Route's declared permission is held (chapter 8), else 403 `permission_denied`.
8. Branch scope: the target record's branch is within the principal's scope, else 404
   `not_found` (a record outside scope is indistinguishable from a missing one).

### 7.4.3 Development stub

Signed-in sessions (bearer tokens) work in every environment. In addition, local and test
environments may run with `AUTH_MODE=dev`, where a request without a bearer token takes its
principal from `X-Dev-User-Id` (a UUID), `X-Dev-Kind` (`staff`, the default, or `member`),
`X-Dev-Permissions` (comma separated permission keys) and `X-Dev-Branch-Ids` (comma separated
branch UUIDs, or `*` for all branches) headers, each permission applying in those branches. The
optional `X-Dev-Scopes` header gives single permissions their own scope, as several roles do for a
signed-in user (ADR-017): semicolon separated `permission=*` or `permission=uuid,uuid` entries,
for example `retail.profit.read=<branch A>`, replacing that permission's scope or adding it. Missing
or malformed headers leave the request unauthenticated; the stub never applies to platform paths.
The application refuses to start with `AUTH_MODE=dev` in any profile other than `dev` or `test`;
with the default `AUTH_MODE=none` only signed-in sessions authenticate. The PWA always signs in
for real; the stub is for curl and tests.

## 7.5 Request and response conventions

- JSON bodies, UTF-8, `snake_case` field names.
- Identifiers are UUID strings. Human numbers (`member_no`, `loan_no`, `receipt_no`) are
  separate fields.
- Amounts are JSON integers in minor units, in fields ending `_minor`, with a `currency`
  field on the same object. The API never accepts or returns a decimal amount.
- Rates are integers in basis points, in fields ending `_bp`.
- Dates are `YYYY-MM-DD`; instants are RFC 3339 in UTC with `Z`.
- Enumerations are lower snake case strings, exactly the values in chapter 6.
- Phone numbers are returned in E.164. Inputs accept the local forms in FR-MEM-02.
- National ID numbers and phone numbers are masked (last 4 visible) in list responses and
  shown in full on detail responses only to roles whose permission includes the member
  detail (chapter 8).
- `null` means unknown or not applicable; omitted fields in a `PATCH` mean "unchanged".

## 7.6 Lists: pagination, filtering, sorting

- Cursor pagination: `?limit=50&cursor=<opaque>`. Default `limit` 50, maximum 200.
- Response: `{"items": [...], "next_cursor": "<opaque>" | null}`. With
  `include_total=true` the response adds `"total"` (capped at 10,000; beyond that
  `"total_capped": true`).
- Sorting: `?sort=<field>` or `?sort=-<field>`; each endpoint lists its sortable fields;
  the cursor encodes the sort key plus `id` as a tiebreaker. Every list decodes it with the
  kernel's one parser (`Cursor.decodeKey`): a cursor that is not base64url, has no `|`, has a
  bad `id` or, on a timestamp-ordered list, a bad timestamp is 400 `malformed_request`, never a 500.
- Filters are query parameters named after fields: `status=active&status=closed`
  (repeatable means OR), ranges as `<field>_from` and `<field>_to` (inclusive), free
  search as `q`.
- `branch_id` (repeatable) filters branch-owned lists. When omitted the list covers every
  branch in the principal's scope; the UI sends the active branch (FR-BR-03, FR-BR-04).

## 7.7 Errors

Errors use RFC 9457 problem details, `Content-Type: application/problem+json`:

```json
{
  "type": "https://docs.bms.invalid/errors/validation_failed",
  "title": "Validation failed",
  "status": 422,
  "code": "validation_failed",
  "detail": "One or more fields are invalid.",
  "request_id": "01J9ZQ3K7T2V8XW4",
  "errors": [
    {"field": "requested_principal_minor", "code": "below_product_minimum", "message": "Must be at least the product minimum."}
  ]
}
```

`code` is the stable, machine-readable identifier the frontend switches on; `title` and
`detail` are for people. The `type` URI host is a placeholder until the problem type pages are
published under the platform host.

| Status | When |
|---|---|
| 400 | `malformed_request`: bad JSON, bad UUID, unknown body field, unknown query parameter |
| 401 | `unauthenticated`, `token_expired`, `tenant_mismatch`, `session_revoked`, `session_expired`, `invalid_credentials` (sign-in), `invalid_mfa_code` (sign-in), `mfa_token_invalid` |
| 403 | Authenticated but lacks the permission (`permission_denied`) |
| 404 | Not found or outside branch scope; `unknown_tenant`; `module_not_enabled` |
| 413 | `file_too_large`: an upload over 5 MB (chapter 8 section 8.8) |
| 415 | `unsupported_file_type`: an upload that is not JPEG, PNG or PDF by content |
| 409 | `version_conflict`, `idempotency_in_progress`, `invalid_status_transition`, `approval_already_pending`, `mfa_already_enrolled`, `mfa_not_enrolled`, `mfa_enrolment_not_started`, duplicates (`duplicate_nin`, `duplicate_import`, `collateral_already_pledged`, `duplicate_email`, `duplicate_phone`, `duplicate_branch_code`, `duplicate_slug`, `duplicate_product_code`); `transaction_conflict` (the database aborted the transaction as a deadlock victim or serialisation loser; nothing was saved, `Retry-After` is set and the same request may be retried with the same `Idempotency-Key`); `stock_moved_since_count` (a retail stock-take whose lines moved after the count so far that the adjustment would leave a negative balance; recount them); `conflict` for any other unique or foreign key violation |
| 422 | Validation and business rule failures (codes below) |
| 423 | `tenant_suspended`, `account_locked` |
| 428 | `precondition_required`: a `PATCH` without `If-Match` (section 7.9) |
| 429 | Rate limited; `Retry-After` header set |
| 500 | `internal_error`; the body carries only the generic title and the `request_id` |
| 503 | Dependency down (the database); readiness fails; `uploads_busy` (image re-encoding is at capacity, retry shortly); `storage_unavailable` (document storage not configured) |

Business rule codes used in chapter 3 (each is a 422 unless listed above):
`validation_failed`, `plan_limit_reached`, `invalid_phone`, `invalid_nin`,
`invalid_term_frequency`, `invalid_rate_unit`, `below_product_minimum`,
`above_product_maximum`, `guarantor_required`, `collateral_required`,
`collateral_cover_insufficient`, `kyc_not_verified`, `member_blacklisted`, `member_not_active`,
`product_archived`,
`max_active_loans_reached`, `approval_above_requested`, `self_approval_forbidden`,
`approver_conflict`, `subject_changed`, `approval_expired`, `period_closed`,
`payment_method_unmapped`, `value_date_in_future`, `has_repayments`,
`already_reversed`, `insufficient_balance`, `collateral_secures_open_loan`,
`branch_has_open_accounts`, `account_has_open_items`, `next_of_kin_required`, `image_too_large`, `collateral_type_disabled`, `unknown_placeholder`,
`blocking_issues_unresolved`, `system_account_not_allowed`, `idempotency_key_reused`,
`idempotency_key_missing`, and from the ledger's posting operation (ADR-004):
`unbalanced_entry`, `invalid_journal_line`, `account_not_postable`, `currency_mismatch`; and
from identity, tenancy and approvals (increment 1): `weak_password`, `invitation_invalid`,
`invitation_expired`, `invalid_mfa_code` (enrolment and recovery code replacement),
`cannot_deactivate_self`, `cannot_reset_own_mfa`, `last_tenant_admin`, `not_a_tenant_admin`,
`head_office_required`, `invalid_tenant`, `module_not_allowed`, `unknown_action_type`,
`approval_execution_failed`; and from retail (ADR-020): `insufficient_stock`, `price_below_cost`; and from any money route: `amount_out_of_range` (exact arithmetic on minor units overflowed; every retail `*_minor` input is also bounded at 10^13 by validation and a database CHECK, review F7).

Entries of the `errors` array carry their own `code`: `invalid` (a Bean Validation failure;
the message says which), `required`, `unknown_branch` (a branch that does not exist, is inactive
or is outside the caller's scope, deliberately indistinguishable), `invalid_phone`,
`invalid_nin`, `unknown_role`, `invalid_slug`, `weak_password`. Every response, success or error,
carries the `X-Request-Id` header.

## 7.8 Idempotency (money-moving endpoints)

Endpoints marked **M** in the catalogue move money or create financial records. They
require the header `Idempotency-Key` (8 to 100 characters; clients use a UUID generated
when the user first presses the button, and reuse it on retry). A missing key returns
422 `idempotency_key_missing`.

Server behaviour:

1. Compute `request_hash` = SHA-256 of the method, path and canonical JSON body.
2. In the same transaction as the business write, insert
   `idempotency_keys (tenant_id, principal_id, key, ..., status = 'in_progress')`.
   Transaction `lock_timeout` is 5 seconds.
3. If the insert conflicts (a concurrent or earlier request with the same key):
   - waiting beyond the lock timeout returns 409 `idempotency_in_progress`;
   - a stored row with a different `request_hash` returns 422 `idempotency_key_reused`;
   - a stored `completed` row replays its stored status and body, with header
     `Idempotent-Replayed: true`, and performs no work.
4. Otherwise perform the work, then update the row to `completed` with the response
   status and body, and commit. Key row and business write commit or roll back together,
   so a failed request (any 4xx or 5xx) leaves no key behind and may be retried with the
   same key.
5. Keys are kept 7 days.

Gateway callbacks use the provider's transaction reference as the idempotency key
(FR-PAY-03).

## 7.9 Optimistic concurrency

Mutable resources return `ETag: "<version>"` on GET. `PATCH` and state transitions on
those resources require `If-Match: "<version>"`; a mismatch returns 409
`version_conflict` with the current version in the body. Money-moving endpoints lock the
target row (`SELECT ... FOR UPDATE`) instead, and do not need `If-Match`.

## 7.10 Rate limits

Enforced in process by each API instance, per tenant (ADR-008; a PostgreSQL-backed limiter
replaces this before a second API instance is added):

| Scope | Limit |
|---|---|
| Staff sign-in per login identifier | 10 per 15 minutes |
| Member OTP request per phone | 3 per hour |
| Member PIN sign-in per phone | 10 per 15 minutes |
| Any authenticated user | 600 requests per minute |
| Report runs per user | 10 per minute |
| Callbacks per provider | 50 per second |

## 7.11 Endpoint catalogue

Legend: **M** money-moving, requires `Idempotency-Key`. **A** creates an approval
request unless below the tenant threshold (FR-APR-04). Permission keys are defined in
chapter 8. Paths are relative to `/api/v1`.

### 7.11.1 Public and health (no tenant, no auth)

| Method | Path | Notes |
|---|---|---|
| GET | `/healthz` | Liveness: process up. Outside `/api/v1`. Body `{"status":"UP"}` only. |
| GET | `/readyz` | Readiness: database reachable and schema at least the image's newest migration. Outside `/api/v1`. Status only. |
| GET | `/version` | `{"version", "git_sha"}`. Outside `/api/v1`. |

All other actuator endpoints are disabled. `health` and `info` exist only on the management
port (8081), which is never published outside the container network.

### 7.11.2 Authentication (`/auth`)

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/auth/staff/login` | public | `{login, password}` returns tokens (`status: signed_in`), or `{status: mfa_required \| mfa_enrolment_required, mfa_token}`. FR-IAM-04, FR-IAM-05 |
| POST | `/auth/staff/mfa/verify` | public (mfa_token) | `{mfa_token, code}`; `code` is a TOTP code or a recovery code. Returns tokens. FR-IAM-06, FR-IAM-11 |
| POST | `/auth/staff/mfa/enrol` | public (mfa_token), or signed in | Returns the TOTP secret and `otpauth` URI once |
| POST | `/auth/staff/mfa/confirm` | public (mfa_token), or signed in | `{mfa_token?, code}` activates TOTP and returns ten recovery codes once; with the MFA token it also signs in. FR-IAM-11 |
| POST | `/auth/staff/mfa/recovery-codes` | authenticated staff | `{code}` (a current TOTP code) replaces every recovery code; returns the new ones once. FR-IAM-11 |
| POST | `/auth/staff/invitations/accept` | public (token) | `{token, password}`; 204. FR-IAM-01 |
| POST | `/auth/staff/password/forgot` | public | Always 202. Not built yet |
| POST | `/auth/staff/password/reset` | public (token) | Not built yet |
| POST | `/auth/member/otp/request` | public | `{phone, purpose}`; always 202. FR-IAM-09 (P2) |
| POST | `/auth/member/otp/verify` | public | `{phone, code}` returns a short-lived `pin_setup_token` (P2) |
| POST | `/auth/member/pin` | public (pin_setup_token) | Sets PIN, returns tokens (P2) |
| POST | `/auth/member/login` | public | `{phone, pin}` (P2) |
| POST | `/auth/refresh` | refresh cookie | Rotates refresh token. FR-IAM-07 |
| POST | `/auth/logout` | authenticated staff | Revokes the session family; clears the cookie |
| GET | `/me` | authenticated staff | User, roles, permissions, `all_branches`, the branches to switch between, the default branch, MFA state and unused recovery codes. FR-BR-03 |

The refresh token never appears in a response body: it is the `__Host-bms_rt` cookie of section 7.4.1.

### 7.11.3 Platform (the platform host, `/platform`, platform users only)

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/platform/auth/setup` | public (setup token) | `{token, password}`: first password, with the token from `deploy/sql/create-platform-user.sql` |
| POST | `/platform/auth/login`, `/platform/auth/mfa/verify`, `/platform/auth/mfa/enrol`, `/platform/auth/mfa/confirm` | public (mfa_token) | As the staff endpoints; TOTP is mandatory and enrolled at first sign-in |
| POST | `/platform/auth/refresh` | refresh cookie `__Host-bms_prt` | |
| POST | `/platform/auth/logout` | authenticated platform | |
| GET | `/platform/me` | authenticated platform | |
| GET | `/platform/plans` | `platform.tenants.read` | Limits only, no prices. FR-TEN-04 |
| GET | `/platform/tenants` | `platform.tenants.read` | |
| POST | `/platform/tenants` | `platform.tenants.manage` | Create per FR-TEN-01: tenant, subscription (`trial`), settings, head office, modules with their chart of accounts, first tenant admin; returns the admin's one-time link once |
| GET | `/platform/tenants/{tenant_id}` | `platform.tenants.read` | |
| PATCH | `/platform/tenants/{tenant_id}` | `platform.tenants.manage` | Not built yet |
| PUT | `/platform/tenants/{tenant_id}/modules` | `platform.tenants.manage` | `{modules: ["lending"]}`. FR-TEN-03 |
| POST | `/platform/tenants/{tenant_id}/subscription` | `platform.tenants.manage` | `{status, next_status_change_on}`. FR-TEN-05 |
| POST | `/platform/tenants/{tenant_id}/suspend`, `/resume` | `platform.tenants.manage` | FR-TEN-06 |
| POST | `/platform/tenants/{tenant_id}/users/{user_id}/mfa/reset` | `platform.tenants.manage` | Tenant admins only. FR-IAM-12 |
| POST | `/platform/tenants/{tenant_id}/support-sessions` | | FR-TEN-07 (P2) |
| GET | `/platform/tenants/{tenant_id}/usage` | | Users, members, SMS segments. FR-NTF-07. Not built yet |

### 7.11.4 Tenant administration

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/settings` | `core.settings.read` | |
| PATCH | `/settings` | `core.settings.manage` | FR-TEN-08 |
| GET | `/branches` | `core.branches.read` | |
| POST | `/branches` | `core.branches.manage` | FR-BR-01 |
| GET, PATCH | `/branches/{branch_id}` | read / manage | |
| POST | `/branches/{branch_id}/deactivate` | `core.branches.manage` | |
| GET | `/users` | `core.users.read` | |
| POST | `/users` | `core.users.manage` | Invite; returns the user and the one-time link once (`invitation.url`, `invitation.expires_at`). FR-IAM-01 |
| GET, PATCH | `/users/{user_id}` | read / manage | |
| PUT | `/users/{user_id}/roles` | `core.users.manage` | `[{role_key, branch_id or null}]` |
| POST | `/users/{user_id}/deactivate` | `core.users.manage` | Revokes every session. FR-IAM-08 |
| POST | `/users/{user_id}/invitation` | `core.users.manage` | New one-time link for a user still invited; the previous link stops working. FR-IAM-01 |
| POST | `/users/{user_id}/mfa/reset` | `core.users.manage` | Another user only. FR-IAM-12 |
| GET | `/roles` | `core.users.read` | Catalogue with permissions |
| GET | `/audit-events` | `core.audit.read` | Filters per FR-AUD-04 |
| POST | `/audit-events/export` | `core.audit.export` | Same filters as the search; CSV, at most 10,000 rows, synchronous until the report runs of increment 8 (FR-RPT-04); the export is audited |

### 7.11.5 Approvals

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/approvals` | `core.approvals.read` | `?status=pending&action_type=...`; returns only requests the principal may decide or made. FR-APR-05 |
| GET | `/approvals/{approval_id}` | `core.approvals.read` | Includes payload snapshot and subject summary |
| POST | `/approvals/{approval_id}/approve` | `core.approvals.read`, then the action type's checker permission in the request's branch (chapter 8 section 8.4) | Executes the action in the same transaction. FR-APR-06 |
| POST | `/approvals/{approval_id}/reject` | same | `{note}` required |
| POST | `/approvals/{approval_id}/cancel` | `core.approvals.read`, maker only | FR-APR-07 |

Approval requests are made by each module's own endpoint for the action (for example the
disbursement request), which calls the approvals module (ADR-015); there is no generic create
endpoint.

### 7.11.6 Ledger (`/ledger`)

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/ledger/accounts` | `core.ledger.read` | Tree with balances as at `?as_of` |
| POST | `/ledger/accounts` | `core.ledger_accounts.manage` | FR-GL-02 |
| PATCH | `/ledger/accounts/{account_id}` | `core.ledger_accounts.manage` | |
| GET | `/ledger/accounts/{account_id}/lines` | `core.ledger.read` | Account activity |
| GET | `/ledger/journal-entries` | `core.ledger.read` | |
| GET | `/ledger/journal-entries/{entry_id}` | `core.ledger.read` | |
| POST | `/ledger/journal-entries` | `core.journals.create` | **M A**. Manual journal. FR-GL-05 |
| POST | `/ledger/journal-entries/{entry_id}/reverse` | `core.journals.create` | **M A** |
| GET | `/ledger/periods` | `core.ledger.read` | |
| POST | `/ledger/periods/{period_id}/close` | `core.periods.close` | **A**. FR-GL-06 |
| GET, PUT | `/ledger/payment-methods` | read / `core.payment_methods.manage` | FR-GL-08 |
| GET | `/ledger/reconciliation` | `core.ledger.read` | Latest subledger reconciliation. FR-GL-10 |

### 7.11.7 Notifications and documents

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/notification-templates` | `core.notifications.read` | |
| PUT | `/notification-templates/{event_key}/{channel}` | `core.notification_templates.manage` | FR-NTF-03 |
| POST | `/notification-templates/{event_key}/{channel}/preview` | `core.notification_templates.manage` | Renders with fabricated sample data |
| GET | `/notifications` | `core.notifications.read` | Send log with status |
| GET | `/documents/{document_id}` | permission on the subject | Metadata. Declared "authenticated" (staff); the owning module's `DocumentAccess` decides, 403 `permission_denied` when it says no |
| POST | `/documents/{document_id}/download-url` | permission on the subject | `{url, expires_at}`, 5 minutes; every issue is audited (`core.document.download_url_issued`). Member ID images (`id_front`, `id_back`) need `lending.members.verify_kyc` (chapter 8). FR-DOC-03 |

Uploads go through the subject's own route (`POST /lending/members/{member_id}/documents` now, the
collateral route next), so each carries its one matrix permission and branch check; there is no
generic `POST /documents`. Built (#12): these two routes and the member document routes.

### 7.11.8 Reports

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/reports` | authenticated staff | Catalogue filtered by permission. FR-RPT-01 |
| GET | `/reports/{report_key}` | report's permission | Synchronous JSON for small reports; params as query |
| POST | `/report-runs` | report's permission | `{report_key, params, format}`; async. FR-RPT-04 |
| GET | `/report-runs/{run_id}` | requester or `core.audit.read` | Status and document id |

### 7.11.9 Imports (`/imports`)

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/imports/templates` | `core.imports.manage` | Available templates, for example `pilot_loan_register_v1` |
| POST | `/imports` | `core.imports.manage` | Multipart `{file, template_key, branch_id}`. FR-IMP-01 |
| GET | `/imports` | `core.imports.manage` | |
| GET | `/imports/{batch_id}` | `core.imports.manage` | Summary: counts by class, status, issue code |
| GET | `/imports/{batch_id}/rows` | `core.imports.manage` | `?status=needs_review&issue_code=...` |
| GET | `/imports/{batch_id}/rows/{row_id}` | `core.imports.manage` | Raw, normalised, issues, resolutions |
| PATCH | `/imports/{batch_id}/rows/{row_id}` | `core.imports.manage` | Edit normalised fields; re-runs row validation. FR-IMP-05 |
| POST | `/imports/{batch_id}/rows/{row_id}/resolve` | `core.imports.manage` | `{issue_id, resolution, value?, link_row_id?, link_member_id?, note}` |
| POST | `/imports/{batch_id}/issues/bulk-resolve` | `core.imports.manage` | `{issue_code, resolution: "accept_suggestion"}` |
| GET | `/imports/{batch_id}/preview` | `core.imports.manage` | FR-IMP-06 |
| POST | `/imports/{batch_id}/commit` | `core.imports.manage` | **M A**. FR-IMP-07 |
| POST | `/imports/{batch_id}/cancel` | `core.imports.manage` | |
| GET | `/imports/{batch_id}/reconciliation` | `core.imports.manage` | FR-IMP-08 |

### 7.11.10 Payments

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/payments/intents` | `core.payments.collect` or `member.self.pay` | **M**. FR-PAY-01 |
| GET | `/payments/intents/{intent_id}` | initiator or `core.payments.read` | Polled by the UI |
| GET | `/payments/unallocated` | `core.payments.read` | FR-PAY-04 |
| POST | `/payments/unallocated/{receipt_id}/allocate` | `core.payments.allocate` | **M** |
| POST | `/payments/callbacks/{provider}` | public, signature verified | On the callback host. FR-PAY-02 |

### 7.11.11 Lending: members (`/lending/members`)

Built so far (the reference slice, `lending.members`): `POST /lending/members`,
`GET /lending/members` (cursor pagination ordered by `member_no`, filters `branch_id`,
`status`, `q`; phone and NIN masked) and `GET /lending/members/{member_id}` (with `ETag`); then
(#10) `PATCH /lending/members/{member_id}`, `POST /lending/members/duplicate-check`,
`POST .../kyc/verify` and `POST .../blacklist`. The PATCH and the two state changes require
`If-Match` (section 7.9) and return the new `ETag`. Loan number search waits for loans
(increment 4). Then (#11) the next of kin routes and `GET .../relationships`, and (#12) the member
document routes. The rest of this table is to be built.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/lending/members` | `lending.members.read` | `q` searches member no, name, phone, NIN, loan no. FR-MEM-11 |
| POST | `/lending/members/duplicate-check` | `lending.members.create` | `{full_name, phone, national_id}`, at least one. Returns up to 20 candidates across the tenant with `match_reasons` (`nin`, `phone`, `name`: trigram similarity at least 0.6). A candidate outside the caller's `lending.members.read` scope carries only `member_no`, `match_reasons` and `in_scope: false`. FR-MEM-04 |
| POST | `/lending/members` | `lending.members.create` | FR-MEM-01. A phone already on another member returns 409 `duplicate_phone` unless `confirmed_not_duplicate: true` (FR-MEM-04) |
| GET, PATCH | `/lending/members/{member_id}` | read / `lending.members.update` | PATCH: omitted fields unchanged; branch not editable (FR-BR-06); `status` per FR-MEM-10; same NIN and phone rules as create |
| POST | `/lending/members/{member_id}/kyc/verify` | `lending.members.verify_kyc` | `{decision: verified or rejected, note}`; only from `pending_verification`, else 409 `invalid_status_transition`; `note` required to reject |
| POST | `/lending/members/{member_id}/blacklist` | `lending.members.blacklist` | `{is_blacklisted, reason}`; `reason` required to blacklist; lifting clears the stored reason (the audit row keeps it) |
| GET, POST | `/lending/members/{member_id}/next-of-kin` | read / update | FR-MEM-06. POST resolves the link (FR-MEM-07); `is_primary: true` moves the primary flag to the new row |
| PATCH, DELETE | `/lending/next-of-kin/{kin_id}` | `lending.members.update` | Both require `If-Match`. A changed NIN or phone re-resolves the link. DELETE of the last next of kin of a member in `pending_verification` or `verified` returns 422 `next_of_kin_required` |
| POST | `/lending/next-of-kin/{kin_id}/link` | `lending.members.update` | `{decision: confirm or reject}`, `If-Match`; only for `suggested` links, else 409 `invalid_status_transition`. FR-MEM-07 |
| GET | `/lending/members/{member_id}/relationships` | `lending.members.read` | `{member_id, names_as_kin, named_as_kin_by}`; a namer outside the caller's scope shows only `member_no`. Guarantees and exposure join in increment 4. FR-MEM-08 |
| GET, POST | `/lending/members/{member_id}/documents` | read / update | FR-MEM-09. POST is multipart: `doc_kind` (`id_front`, `id_back`, `photo`, `other`) and `file`; an `id_front` can complete KYC (FR-MEM-05); beyond 10 active documents of a kind the oldest is superseded. GET lists active documents, and ID images only to callers with `lending.members.verify_kyc` |
| POST | `/lending/members/{member_id}/portal-invite` | `lending.members.update` | Sends activation SMS. FR-IAM-09 |
| GET | `/lending/members/{member_id}/credits` | `lending.members.read` | Overpayment credits |
| POST | `/lending/members/{member_id}/credits/refund` | `lending.repayments.create` | **M A**. FR-REP-04a |
| POST | `/lending/members/{member_id}/transfer` | `lending.members.update` | **A**. FR-BR-06 (P2) |

### 7.11.12 Lending: loan products (`/lending/loan-products`)

Built (#40): every route below. Products are tenant-wide, so the permission is the whole check
(no branch scope). `POST .../versions` and `POST .../archive` require `If-Match`; an archived
product refuses both with 409 `invalid_status_transition`. The schedule preview takes the terms
as typed on the product form (saved or not), a principal, a term count and a disbursement date,
and answers `{items, totals, deducted_at_disbursement_minor, paid_upfront_minor,
net_disbursed_minor}` from the same calculator real schedules will use.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/lending/loan-products` | `lending.products.read` | |
| POST | `/lending/loan-products` | `lending.products.manage` | Creates product and version 1. FR-PRD-01 |
| GET | `/lending/loan-products/{product_id}` | `lending.products.read` | Current version plus history |
| POST | `/lending/loan-products/{product_id}/versions` | `lending.products.manage` | New version. FR-PRD-04 |
| POST | `/lending/loan-products/{product_id}/archive` | `lending.products.manage` | FR-PRD-05 |
| POST | `/lending/loan-products/schedule-preview` | `lending.products.read` | `{terms..., principal_minor, disbursement_date}` returns the schedule. FR-PRD-03 |

### 7.11.13 Lending: loans (`/lending/loans`)

Built (#41): list, create, get (with guarantors, collateral and the provisional schedule from
the loan's own copied terms), PATCH (draft only), PUT guarantors and collateral (draft only),
submit, return, cancel, and `GET .../status-history`. Every change requires `If-Match`. A loan
officer cancels only their own draft; a holder of `lending.loans.approve` cancels any application
not yet disbursed. Codes: `member_not_active`, `product_archived`, `below_product_minimum`,
`above_product_maximum`, `guarantor_required`, `collateral_required`, `kyc_not_verified`,
`collateral_already_pledged` (409); field codes `guarantor_is_borrower`, `unknown_collateral`,
`collateral_not_held`, `currency_mismatch`, `collateral_not_valued`, `pledge_exceeds_value`
(on `pledged_value_minor`) and `in_the_past` (a proposed disbursement date before today). Submit
runs the pledge checks again and adds `member_blacklisted`, `guarantor_not_active` and
`guarantor_blacklisted`; it also stores today as the proposed disbursement date when none was
given. The provisional schedule includes the version's `added_to_loan` fees, so it equals the
product preview. Money fields accept at most 10^15 minor units. `PATCH` with `purpose_text: ""`
clears the text.

Built (#42): `POST` and `GET .../appraisals`. The POST takes
`{declared_monthly_income_minor?, monthly_obligations_minor?, visit_notes?}` with `If-Match` on
the loan, on a `submitted` or `appraised` loan (else 409 `invalid_status_transition`), and returns
201 with the stored snapshot: `score`, `band`, `components`, `flags`, `exposure`
(`own_loans`, `guaranteed_loans`, `linked_party_loans`, each with `loan_no`, `status`,
`outstanding_minor`, `days_past_due`), `weights` and `recommendation`. Income defaults to the
member's declared monthly income. Both money inputs accept at most 10^15 minor units, the same
bound as every loan money field; a larger value is 422 `validation_failed` and nothing is stored,
because appraisals are append-only and a bad snapshot cannot be corrected. The first appraisal moves the loan to `appraised`; a later one
records a new snapshot and the latest appraiser. The GET lists newest first.

Built (#43): `POST .../decision` with `If-Match`, on an `appraised` loan. `approve` takes
`approved_principal_minor` and `approved_term_count` (each defaults to the requested value) and
stores them with the approver (`approved_principal_minor` accepts at most 10^15 minor units,
else 422 `validation_failed`); the loan's `provisional_schedule` then previews the approved
terms. `reject` requires `note`. Codes (422 unless stated): `self_approval_forbidden` (the
approver submitted or appraised the loan, FR-APR-03), `above_requested_principal`,
`above_requested_term`, `below_product_minimum`, `member_blacklisted`, `kyc_not_verified`,
`collateral_below_product_minimum` (cover measured against the approved principal),
`max_active_loans_reached`, and 409 `invalid_status_transition`. The nightly task
`lending.loan-approval-expiry` cancels an approval older than `approval_validity_days` with
reason `approval_expired` (FR-ORG-08), audited with actor kind `system`. Both `approved_at` and
the expiry bound come from the business clock (AGENTS.md rule 7), never the database's `now()`.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/lending/loans` | `lending.loans.read` | Filters: `status`, `member_id`, `officer_user_id`, `product_id`, `dpd_from`, `dpd_to`, `disbursed_on_from/_to` |
| POST | `/lending/loans` | `lending.loans.create` | Draft application. FR-ORG-01 |
| GET | `/lending/loans/{loan_id}` | `lending.loans.read` | Includes balances, DPD, PAR bucket, guarantors, collateral |
| PATCH | `/lending/loans/{loan_id}` | `lending.loans.create` | Draft only |
| PUT | `/lending/loans/{loan_id}/guarantors` | `lending.loans.create` | Draft only. FR-ORG-02 |
| PUT | `/lending/loans/{loan_id}/collateral` | `lending.loans.create` | Draft only |
| POST | `/lending/loans/{loan_id}/submit` | `lending.loans.create` | FR-ORG-03 |
| POST | `/lending/loans/{loan_id}/return` | `lending.loans.approve` | Back to draft with note |
| POST | `/lending/loans/{loan_id}/appraisals` | `lending.loans.appraise` | Runs and stores the score. FR-ORG-04 |
| GET | `/lending/loans/{loan_id}/appraisals` | `lending.loans.read` | |
| POST | `/lending/loans/{loan_id}/decision` | `lending.loans.approve` | `{decision: approve or reject, approved_principal_minor?, approved_term_count?, note}`. FR-ORG-06, FR-APR-03 |
| POST | `/lending/loans/{loan_id}/cancel` | `lending.loans.cancel` | |
| POST | `/lending/loans/{loan_id}/disbursements` | `lending.disbursements.request` | **M A**. FR-DIS-01 |
| GET | `/lending/loans/{loan_id}/schedule` | `lending.loans.read` | FR-DIS-04 |
| GET | `/lending/loans/{loan_id}/transactions` | `lending.loans.read` | With allocations |
| POST | `/lending/loans/{loan_id}/repayments` | `lending.repayments.create` | **M**. `{amount_minor, value_date, payment_method_key, external_reference}`. FR-REP-01 |
| POST | `/lending/loans/{loan_id}/transactions/{txn_id}/reverse` | `lending.repayments.reverse_request` | **M A**. FR-REP-05 |
| GET | `/lending/loans/{loan_id}/payoff-quote` | `lending.loans.read` | `?value_date=`. FR-REP-06 |
| POST | `/lending/loans/{loan_id}/charges/{charge_id}/waive` | `lending.charges.waive_request` | **M A**. FR-ARR-04 |
| POST | `/lending/loans/{loan_id}/write-off` | `lending.loans.write_off_request` | **M A**. FR-LCL-02 |
| POST | `/lending/loans/{loan_id}/restructure` | `lending.loans.restructure_request` | **M A**. FR-LCL-04 (P2) |
| PUT | `/lending/loans/{loan_id}/officer` | `lending.collections.assign` | FR-CLN-01 |
| GET, POST | `/lending/loans/{loan_id}/collection-actions` | `lending.collections.read` / `lending.collections.log_action` | FR-CLN-04 |
| POST | `/lending/loans/{loan_id}/documents/{doc_type}` | `lending.loans.read` | Generates statement, schedule, or agreement PDF |

### 7.11.14 Lending: collateral (`/lending/collateral`)

Built (#13): every route below, and `GET, POST .../{collateral_id}/documents` (photos and scans,
multipart like member documents). `GET .../{collateral_id}` returns `{item, valuations, events}`
with the item's `ETag`; PATCH, events and release require `If-Match`. Release answers 202 with
`{executed, approval_id}` (there is no threshold). The `loan_status` filter waits for loans.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/lending/collateral` | `lending.collateral.read` | Filters: `member_id`, `type`, `custody_status`, `loan_status` |
| POST | `/lending/collateral` | `lending.collateral.manage` | FR-COL-01 |
| GET, PATCH | `/lending/collateral/{collateral_id}` | read / manage | |
| POST | `/lending/collateral/{collateral_id}/valuations` | `lending.collateral.manage` | FR-COL-02 |
| POST | `/lending/collateral/{collateral_id}/events` | `lending.collateral.manage` | Custody change (not release). FR-COL-03 |
| POST | `/lending/collateral/{collateral_id}/release` | `lending.collateral.release_request` | **A**. FR-COL-04 |

### 7.11.15 Lending: savings

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET, POST | `/lending/savings-products` | `lending.savings.read` / `lending.savings_products.manage` | FR-SAV-01 |
| GET | `/lending/savings-accounts` | `lending.savings.read` | |
| POST | `/lending/savings-accounts` | `lending.savings.open` | FR-SAV-02 |
| GET | `/lending/savings-accounts/{account_id}` | `lending.savings.read` | |
| GET | `/lending/savings-accounts/{account_id}/transactions` | `lending.savings.read` | |
| POST | `/lending/savings-accounts/{account_id}/deposits` | `lending.savings.deposit` | **M**. FR-SAV-03 |
| POST | `/lending/savings-accounts/{account_id}/withdrawals` | `lending.savings.withdraw` | **M A** |
| POST | `/lending/savings-accounts/{account_id}/transactions/{txn_id}/reverse` | `lending.savings.withdraw` | **M A** |
| POST | `/lending/savings-accounts/{account_id}/reactivate` | `lending.savings.withdraw_approve` | FR-SAV-06 |
| POST | `/lending/savings-accounts/{account_id}/close` | `lending.savings.withdraw` | **M A**. FR-SAV-07 |

### 7.11.16 Lending: investments

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET, POST | `/lending/investment-products` | `lending.investments.read` / `lending.investment_products.manage` | FR-INV-01 |
| GET | `/lending/investments` | `lending.investments.read` | |
| POST | `/lending/investments` | `lending.investments.open` | FR-INV-02 |
| GET | `/lending/investments/{investment_id}` | `lending.investments.read` | |
| POST | `/lending/investments/{investment_id}/funding` | `lending.investments.fund` | **M**. FR-INV-03 |
| PUT | `/lending/investments/{investment_id}/maturity-instruction` | `lending.investments.open` | FR-INV-05 |
| POST | `/lending/investments/{investment_id}/payout` | `lending.investments.payout` | **M** |
| POST | `/lending/investments/{investment_id}/rollover` | `lending.investments.payout` | **M** |
| POST | `/lending/investments/{investment_id}/early-withdrawal` | `lending.investments.payout` | **M A**. FR-INV-06 |

### 7.11.17 Lending: collections

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/lending/collections/due-list` | `lending.collections.read` | `?date_from&date_to&officer_user_id`. FR-CLN-02 |
| GET | `/lending/collections/arrears` | `lending.collections.read` | `?bucket=1_30`. FR-CLN-03 |
| POST | `/lending/collections/reassign` | `lending.collections.assign` | `{loan_ids, officer_user_id}` |
| GET | `/lending/collections/promises` | `lending.collections.read` | `?promise_status=pending` |

### 7.11.18 Member portal (`/member`, member principals only)

| Method | Path | Notes |
|---|---|---|
| GET | `/member/me` | Member profile (own) |
| GET | `/member/loans` | Own loans. FR-MSS-01 |
| GET | `/member/loans/{loan_id}` | 404 if not own |
| GET | `/member/loans/{loan_id}/schedule` | |
| POST | `/member/loan-applications` | Draft with channel `portal`. FR-ORG-09 |
| GET | `/member/savings-accounts` | FR-MSS-02 |
| GET | `/member/savings-accounts/{account_id}/transactions` | |
| GET | `/member/investments` | |
| POST | `/member/investments` | FR-INV-02 |
| POST | `/member/payments` | **M**. Creates a payment intent. FR-MSS-04 |
| GET | `/member/payments/{intent_id}` | |
| GET | `/member/documents` | Own receipts and statements |
| POST | `/member/documents/{document_id}/download-url` | |

### 7.11.19 Channel callbacks (the callback host, public, verified)

| Method | Path | Notes |
|---|---|---|
| POST | `/payments/callbacks/{provider}` | 7.11.10 |
| POST | `/channels/sms/{provider}/delivery-reports` | FR-NTF-05 |
| POST | `/channels/ussd/{provider}` | Session callback. FR-MSS-07 (Later) |

### 7.11.20 Retail (`/retail`, ADR-020)

Refused with 404 `module_not_enabled` unless the tenant has the retail module switched on. The
draft `docs/api/retail-contract-draft.md` is replaced by this section and the generated
`openapi.json`; as everywhere in this API, JSON fields and query parameters are snake_case
(`cost_minor`, `branch_id`), where the draft wrote camelCase. Quantities are decimal strings with up
to three places (`"3.500"`). Fields marked `*` are **absent** from the body, not null, for a caller
without `retail.profit.read` (ADR-020 decision 10). On a branch-bound row (stock, movements,
stock-takes, usage, sales, valuation rows and branch totals) the permission must cover that row's
branch, per ADR-017, not merely be held somewhere; the valuation's overall `value_at_cost_minor`
shows only when cost shows for every branch reported (review F5). Products and price history are
tenant-wide and need the permission in any branch.

Built (#51, catalogue):

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/retail/categories`, `/retail/units` | `retail.stock.read` | `{items: [{id, name}]}` |
| POST | `/retail/categories`, `/retail/units` | `retail.catalogue.manage` | `{name}`; 409 `duplicate_category`, `duplicate_unit` (names ignore case). FR-RET-01 |
| GET | `/retail/products` | `retail.stock.read` | Filters `query` (code or description), `category_id`, `active`; `limit`, `cursor`, ordered by code. Row: `{id, code, description, category_id, category, unit_id, unit, sell_minor, cost_minor*, currency, active, version}` |
| POST | `/retail/products` | `retail.catalogue.manage` | `{code, description, category_id, unit_id, cost_minor, sell_minor}`; writes the `initial` history row; 409 `duplicate_product_code`. FR-RET-01 |
| GET, PATCH | `/retail/products/{product_id}` | read / `retail.catalogue.manage` | PATCH needs `If-Match` and edits `code`, `description`, `category_id`, `unit_id`, `active` only; a price in the body is 400 |
| POST | `/retail/products/{product_id}/prices` | `retail.price.edit` | `{cost_minor?, sell_minor?, reason}`; at least one price; 422 `price_unchanged`; writes a `manual` history row and audit. FR-RET-02 |
| GET | `/retail/products/{product_id}/price-history` | `retail.stock.read` | `{items: [{id, at, by, source, source_id, old_cost_minor*, new_cost_minor*, old_sell_minor, new_sell_minor, currency, reason}]}` |

Built (#52, stock and sales). `M` marks a money-moving route that requires `Idempotency-Key`
(section 7.8). A sale, stock-take or list for one branch takes `branch_id`, or the caller's one
branch when the permission's scope has exactly one; otherwise 422 `branch_required`.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/retail/products?branch_id=` | `retail.stock.read` | Each row adds `qty` and `negative` for that branch |
| GET | `/retail/stock` | `retail.stock.read` | `branch_id`, `query`, `negative_only`, `limit`, `cursor`. Row: `{product_id, code, description, unit, qty, negative, sell_minor, cost_minor*}`. FR-RET-03 |
| GET | `/retail/stock/movements` | `retail.stock.read` | `branch_id` (repeatable, scoped), `product_id`, `from`, `to`. Row: `{id, at, branch_id, product_id, kind, qty, unit_cost_minor*, source_type, source_id, reverses_movement_id, historical, note, by}` |
| POST | `/retail/stocktakes` | `retail.stocktake.commit` | `{branch_id?, lines: [{product_id, counted_qty}], note?}`; returns each line's `expected_qty` and `variance_qty`. FR-RET-08 |
| GET | `/retail/stocktakes/{stocktake_id}` | `retail.stock.read` | Draft or committed |
| POST | `/retail/stocktakes/{stocktake_id}/commit` | `retail.stocktake.commit` | Adjustment movements of counted less `expected_qty` (the variance when counted, so later movements stay in the balance), posted at cost; 409 `stocktake_committed`; 409 `stock_moved_since_count` when later movements would make an adjusted balance negative (recount those lines) |
| POST | `/retail/sales` | `retail.sale.create` | **M**. `{branch_id?, sale_date?, payment_method, customer_id?, buyer_name?, buyer_contact?, due_date?, lines: [{product_id, qty, unit_price_minor?}]}`; 422 `insufficient_stock` when a line exceeds the branch's stock (always; ADR-020 decision 4); 422 `price_below_cost` when a line's unit price is not above the product's cost and the caller lacks `retail.price.below_cost` (the message never carries the cost; ADR-020 decision 5). Response: the sale with `lines`, `total_minor`, `paid_minor`, `balance_minor`, `cost_total_minor*`, `profit_minor*`, and per line `unit_cost_minor*`, `line_cost_minor*`. FR-RET-04 |
| GET | `/retail/sales` | `retail.sale.read` | `branch_id` (repeatable, scoped), `from`, `to`, `customer_id`, `limit`, `cursor` |
| GET | `/retail/sales/{sale_id}` | `retail.sale.read` | 404 outside scope |
| POST | `/retail/sales/{sale_id}/void` | `retail.sale.void` | `{reason}`; 409 `sale_voided`; 422 `sale_has_payments` for a credit sale with payments |
| GET | `/retail/customers` | `retail.sale.read` | `query`, `limit` |
| POST | `/retail/customers` | `retail.customer.manage` | `{name, contact?}` |
| GET | `/retail/customers/{customer_id}/balance` | `retail.sale.read` | `{customer_id, currency, balance_minor, open_sales: [...]}` over credit sales in the caller's scope. FR-RET-05 |

Built (#53, purchasing, usage and payments):

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET, POST | `/retail/suppliers` | `retail.purchase.create` | `{name, contact?}`; 409 `duplicate_supplier` |
| POST | `/retail/purchases` | `retail.purchase.create` | **M**. `{supplier_id?, purchased_on, payment_method: cash, bank or credit, note?, lines: [{product_id, cost_minor, sell_minor?, qty_by_branch: [{branch_id, qty}]}]}`; every branch must be in the caller's scope; sets the product prices and history atomically, latest line wins. FR-RET-06 |
| GET | `/retail/purchases` | `retail.purchase.create` | `from`, `to`, `supplier_id`, `limit`, `cursor`; purchases that moved stock into the caller's branches. A branch-scoped caller sees only their branches' `qty_by_branch`, with `qty_total`, `line_total_minor` and `total_minor` recomputed from them (lines with nothing in scope left out); an all-branch caller sees the whole document (review F11) |
| POST | `/retail/usage` | `retail.usage.report` | **M**. `{branch_id?, kind: used or damaged, reason, occurred_on?, lines: [{product_id, qty}]}`; response `cost_total_minor*`, per line `unit_cost_minor*`, `line_cost_minor*`; 422 `insufficient_stock` past the branch's stock (ADR-020 decision 4). FR-RET-07 |
| POST | `/retail/sales/{sale_id}/payments` | `retail.sale.create` | **M**. `{amount_minor, method: cash, mobile_money or bank, paid_on?}`; `{payment, sale_paid_minor, sale_balance_minor}`; 422 `payment_exceeds_balance`, `sale_not_payable`. FR-RET-05 |
| GET | `/retail/sales/{sale_id}/payments` | `retail.sale.read` | |

Built (#54, reports):

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/retail/reports/valuation` | `retail.stock.read` | `branch_id` (repeatable, scoped), `as_of` (not in the future; quantities are summed by each movement's business date, the date its journal carries). A row whose quantity times a price does not fit is flagged `amount_out_of_range`, its values left out and excluded from the totals, so one product never fails the report. `{as_of, currency, rows: [{branch_id, product_id, code, description, unit, qty, negative, sell_minor, expected_sales_minor, amount_out_of_range, cost_minor*, value_at_cost_minor*}], branches: [{branch_id, expected_sales_minor, value_at_cost_minor*, inventory_account_minor*, revaluation_difference_minor*}], expected_sales_minor, value_at_cost_minor*}`; `branches` lists every branch in the filter that holds stock or has a non-zero `inventory` balance (review F3). FR-RET-09 |
| GET | `/retail/reports/profit/daily` | `retail.profit.read` | `branch_id` (repeatable, scoped), `from`, `to` (default the last 30 days, at most 366). `{from, to, currency, rows: [{branch_id, date, sales_minor, cost_of_sales_minor, gross_profit_minor, usage_cost_minor, profit_minor}], sales_minor, cost_of_sales_minor, usage_cost_minor, profit_minor}`. FR-RET-10 |

## 7.12 Example: record a repayment

```http
POST /api/v1/lending/loans/7a0c.../repayments HTTP/1.1
Host: pilot.bms.invalid
Authorization: Bearer eyJ...
Idempotency-Key: 2f1e7d3c-5b6a-4c1d-9e8f-0a1b2c3d4e5f
Content-Type: application/json

{"amount_minor": 300000, "currency": "UGX", "value_date": "2026-04-16",
 "payment_method_key": "cash", "external_reference": null}
```

```http
HTTP/1.1 201 Created
Content-Type: application/json

{"transaction_id": "c4d1...", "receipt_no": "RC-HQ-000124",
 "allocations": [
   {"schedule_item_no": 1, "component": "interest", "amount_minor": 120000},
   {"schedule_item_no": 1, "component": "principal", "amount_minor": 180000}],
 "loan": {"status": "active", "principal_outstanding_minor": 1020000,
          "days_past_due": 0, "next_due_date": "2026-04-16"},
 "receipt_document_id": null}
```

The receipt PDF is rendered after commit; its `document_id` appears on the transaction
when ready. Repeating the request with the same key returns the same body with
`Idempotent-Replayed: true`.
