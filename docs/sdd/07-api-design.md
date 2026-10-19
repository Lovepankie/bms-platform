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
  Operational note (#145): sales-list cursors issued before the deploy that added the direction have none
  and are refused with 400 `malformed_request`, so a client part-way through a sales list must restart the list.
- Filters are query parameters named after fields: `status=active&status=closed`
  (repeatable means OR), ranges as `<field>_from` and `<field>_to` (inclusive), free
  search as `q` (the retail lists keep their `query`, and the sales list its `buyer`; section 7.6.1).
- `branch_id` (repeatable) filters branch-owned lists. When omitted the list covers every
  branch in the principal's scope; the UI sends the active branch (FR-BR-03, FR-BR-04).

### 7.6.1 Search text (ADR-039, `docs/specs/search.md`)

Every list that takes a search text (`q` on `/lending/members`, `/lending/loans`, `/lending/savings-accounts`,
`/lending/investments`; `query` on `/retail/products`, `/retail/stock`, `/retail/stock/all-branches`,
`/retail/customers`, `/retail/cash-parties`; `buyer` on `/retail/sales`) matches it the same way, through the
kernel's one matcher:

- **Case and accent insensitive.** "jose" finds a name written with an accent on the e.
- **Several words are an AND.** The text is split on white space (at most 6 words, at most 100 characters; the rest
  is ignored) and a row must match every word somewhere in its name, number, code or reference columns, in any order.
- **Phone numbers match in any written form.** A word of four or more typed digits, and a whole text that looks like a
  phone number, also match the digits of a phone column: the typed digits anywhere in the number and, when the text starts with 256
  or 0, the rest at the start of the number written with 256, with 0 or with neither. "0700 000 111", "+256 700 000 111",
  "256700000111" and "700000111" are one number, and "0772" or "+2567" is already a search; a contact kept as free text
  ("0772123456") is found as well as a stored +256 number. Fewer than four typed digits is not a phone, and the rest is
  never matched in the middle of a number.
- **`%`, `_` and `\` are ordinary characters.**
- **Ranked.** A name or code that starts with the whole text comes first ("buyer b"), then one where the whole text starts
  a word, then one where every word starts a word, then any other match; inside a rank the list's usual order holds.
  The sales history is the exception and keeps its time order.
- **Same scope, same permissions.** The match narrows the list the caller could already read; it adds no row. The
  columns a list does not show are matched only for a caller who may read them (a member's phone and national id on a
  loan, savings or investment list need `lending.members.read` at the member's branch).
- **Cursor.** With a search text the cursor carries the rank of the last row and a fingerprint of the text; it is valid
  only with the same text. A cursor from another text, from a list without a search text, or the reverse, is 400
  `malformed_request` ("restart the list"). Change the text, restart the list.
- A blank text is no search. Matching and paging never change the response shape.

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
| 404 | Not found or outside branch scope; `unknown_tenant`; `module_not_enabled`; `link_invalid` (an applicant link that is malformed, unknown, replaced or expired, one answer for all, ADR-024) |
| 413 | `file_too_large`: an upload over 5 MB, or a logo over 1 MB (chapter 8 section 8.8) |
| 415 | `unsupported_file_type`: an upload that is not JPEG, PNG or PDF by content (a logo: not PNG, JPEG or WebP, or an image that cannot be decoded) |
| 409 | `version_conflict`, `idempotency_in_progress`, `invalid_status_transition`, `approval_already_pending`, `mfa_already_enrolled`, `mfa_not_enrolled`, `mfa_enrolment_not_started`, duplicates (`duplicate_nin`, the cash book's `duplicate_category`, `duplicate_item`, `duplicate_party`, `duplicate_import`, `collateral_already_pledged`, `duplicate_email`, `duplicate_phone`, `duplicate_branch_code`, `duplicate_slug`, `duplicate_product_code`); the cash book's `cash_record_voided` (any cash book record voided twice), `savings_exists` (a second active savings record for a branch and date), `suggestion_changed` (the savings suggestion changed since the form was shown; the body carries the new `suggestion_token`, ADR-022 decision 11) and `advance_has_repayments`; `transaction_conflict` (the database aborted the transaction as a deadlock victim or serialisation loser; nothing was saved, `Retry-After` is set and the same request may be retried with the same `Idempotency-Key`); `stock_moved_since_count` (a retail stock-take whose lines moved after the count so far that the adjustment would leave a negative balance; recount them); `application_state` (an onboarding decision or reply the application's status does not allow), `application_not_verified` (Activate before Verify), `outbox_not_failed` (send again on a row that has not failed), `outbox_too_old` (send again on a row older than its link's 7 day lifetime); `conflict` for any other unique or foreign key violation |
| 422 | Validation and business rule failures (codes below) |
| 423 | `tenant_suspended`, `account_locked` |
| 428 | `precondition_required`: a `PATCH`, or a state transition or retail price edit, without `If-Match` (section 7.9) |
| 429 | Rate limited; `Retry-After` header set where the limiter knows it; `rate_limited` on the public sign-up endpoints and on report runs (section 7.10) |
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
`branch_has_open_accounts`, `account_has_open_items`, `next_of_kin_required`, `image_too_large`, `svg_not_allowed` (a logo that is an SVG), `image_too_small` (a logo under 128 px on the short side), `insufficient_contrast` (a theme colour with no readable text colour; a field error of `validation_failed`), `collateral_type_disabled`, `unknown_placeholder`,
`blocking_issues_unresolved`, `system_account_not_allowed`, `idempotency_key_reused`,
`idempotency_key_missing`, and from the ledger's posting operation (ADR-004):
`unbalanced_entry`, `invalid_journal_line`, `account_not_postable`, `currency_mismatch`; and
from identity, tenancy and approvals (increment 1): `weak_password`, `invitation_invalid`,
`invitation_expired`, `invalid_mfa_code` (enrolment and recovery code replacement),
`cannot_deactivate_self`, `cannot_reset_own_mfa`, `last_tenant_admin`, `not_a_tenant_admin`,
`head_office_required`, `invalid_tenant`, `module_not_allowed`, `unknown_action_type`,
`approval_execution_failed`; and from retail (ADR-020): `insufficient_stock`, `price_below_cost`; and from the retail cash book (ADR-022): `reason_required`, `explanation_required`, `item_not_in_category`, `category_inactive`, `account_not_expense`, `party_kind_not_allowed`, `suggestion_token_required`, `amount_requires_profit_access`, `repayment_exceeds_balance`, `advance_settled` and `branch_required`; and from any money route: `amount_out_of_range` (exact arithmetic on minor units overflowed; every retail `*_minor` input is also bounded at 10^13 by validation and a database CHECK, review F7).

Entries of the `errors` array carry their own `code`: `invalid` (a Bean Validation failure;
the message says which), `required`, `unknown_branch` (a branch that does not exist, is inactive
or is outside the caller's scope, deliberately indistinguishable), `invalid_phone`,
`invalid_nin`, `unknown_role`, `invalid_slug`, `weak_password`, `future_date` (a business date after today in the tenant's time zone). Every response, success or error,
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

The protocol is implemented once, `Idempotency` in `core.operations`, and every vertical's
money-moving routes call it.

Gateway callbacks use the provider's transaction reference as the idempotency key
(FR-PAY-03).

## 7.9 Optimistic concurrency

Mutable resources return `ETag: "<version>"` on GET. Three kinds of write require
`If-Match: "<version>"`: a `PATCH` of such a resource, a state transition on it, and the retail
manual price edit (`POST /retail/products/{product_id}/prices`, #77), which is a `POST` but changes
the product's prices in place. Without the header the answer is 428 `precondition_required`; a
mismatch returns 409 `version_conflict` with the current version in the body. Money-moving
endpoints lock the target row (`SELECT ... FOR UPDATE`) instead, and do not need `If-Match`; a
retail transfer and its void are among them.

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
| Sign-up: new applications per client address, an IPv6 client by its /64 (ADR-024) | 5 per hour (429) |
| Sign-up: link reads (verify, status, reply) per client address | 30 per 10 minutes (429) |
| Sign-up: confirmation emails per mailbox (database) | 3 per 24 hours (202, nothing sent) |
| Sign-up: new applications, all clients (database, `BMS_SIGNUP_MAX_APPLICATIONS_PER_HOUR`) | 30 per hour by default (202, nothing created; audit row and one Telegram alert per hour) |
| Sign-up: confirmation emails, all clients (database, `BMS_SIGNUP_MAX_VERIFICATION_EMAILS_PER_HOUR`) | 60 per hour by default (as above) |

The per-address limits are token buckets in memory, bounded at 10,000 keys (least recently used
dropped first), per API instance and lost on restart; a flood of fresh addresses can evict them,
so they are a first, cheap line only. The bounds that must hold under a flood (per mailbox and
global) are counted in the database inside one serialised sign-up transaction, and over them the
answer stays the same 202, so the endpoint is no oracle. The global caps are deliberately small,
so they can be filled cheaply: a handful of addresses (about 6 at 5 applications each) fill the
default cap of 30 in an hour, after which every applicant, real or not, sees the normal answer and
gets no email until the hour passes. That is the intended trade: the cap bounds what the form can
make the platform send, and the operator is told at once (one Telegram alert and a
`platform.sign_up.cap_reached` audit row per hour) and follows the runbook
(`docs/runbooks/onboard-tenant.md`, "Protect the public sign-up"). The client address is the one Tomcat's
RemoteIpValve takes from the trusted proxy's `X-Forwarded-For` (the right-most untrusted value, so
a value a client prepends changes nothing). In front of all of this is the Cloudflare rate
limiting rule for `/api/v1/platform/sign-up/*` (chapter 9 section 9.8,
`docs/runbooks/onboard-tenant.md`).

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
| POST | `/auth/staff/invitations/accept` | public (token) | `{token, password}`; sets the password and signs in: the same body as `/auth/staff/login` (`signed_in` with tokens, or `mfa_enrolment_required` with an MFA token when the role requires a second factor). FR-IAM-01, FR-IAM-13, ADR-025 |
| POST | `/auth/staff/password/forgot` | public | Always 202. Not built yet |
| POST | `/auth/staff/password/reset` | public (token) | Not built yet |
| POST | `/auth/member/otp/request` | public | `{phone, purpose}`; always 202. FR-IAM-09 (P2) |
| POST | `/auth/member/otp/verify` | public | `{phone, code}` returns a short-lived `pin_setup_token` (P2) |
| POST | `/auth/member/pin` | public (pin_setup_token) | Sets PIN, returns tokens (P2) |
| POST | `/auth/member/login` | public | `{phone, pin}` (P2) |
| POST | `/auth/refresh` | refresh cookie | Rotates refresh token. FR-IAM-07 |
| POST | `/auth/logout` | authenticated staff | Revokes the session family; clears the cookie |
| GET | `/me` | authenticated staff | User, roles, permissions, `permission_scopes` (per permission key `{all_branches, branch_ids}`, so a screen offers only the branches where its permission is held, ADR-017), `all_branches`, the branches to switch between, the default branch, MFA state and unused recovery codes. Also `tours`: the guided tours the user finished or dismissed, by tour id (FR-IAM-14). FR-BR-03 |
| PUT | `/me/tours/{tour_id}` | authenticated staff | `{status: completed \| dismissed, version}`; records how the caller left a guided tour; 204. Only the caller's own row; tour id is lower case words joined by hyphens, at most 64 tours per user (`tour_id_invalid`, `too_many_tours`). FR-IAM-14, ADR-025 |

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
| POST | `/platform/tenants/{tenant_id}/payment-account/release` | `platform.tenants.manage` | Built in #233. `{reason}` (10 to 300 characters, required). Operator override, one transaction with its platform audit row, emails the tenant's administrators: removes the tenant's saved payment account although payments are in flight (a compromised merchant account), audited in the platform audit log; waiting orders are skipped, never closed |
| GET | `/platform/tenants/{tenant_id}/payment-account` | `platform.tenants.read` | Built in #233. Whether the tenant has connected its own payment account: `status` (`not_connected`, `verified`, `failed`), `last_checked_at`, `failure_reason`. Status only: the operator can neither read nor enter a credential. ADR-035 amendment of 2026-10-18 |
| PATCH | `/platform/tenants/{tenant_id}` | `platform.tenants.manage` | Not built yet |
| PUT | `/platform/tenants/{tenant_id}/modules` | `platform.tenants.manage` | `{modules: ["lending"]}`. FR-TEN-03 |
| POST | `/platform/tenants/{tenant_id}/subscription` | `platform.tenants.manage` | `{status, next_status_change_on}`. FR-TEN-05 |
| POST | `/platform/tenants/{tenant_id}/suspend`, `/resume` | `platform.tenants.manage` | FR-TEN-06 |
| POST | `/platform/tenants/{tenant_id}/users/{user_id}/mfa/reset` | `platform.tenants.manage` | Tenant admins only. FR-IAM-12 |
| POST | `/platform/tenants/{tenant_id}/support-sessions` | | FR-TEN-07 (P2) |
| GET | `/platform/tenants/{tenant_id}/usage` | | Users, members, SMS segments. FR-NTF-07. Not built yet |
| POST | `/platform/sign-up/applications` | public | The sign-up form (FR-ONB-01). Always 202 `{status: check_your_email}`, for a new, a known or a honeypot request (FR-ONB-03); 429 `rate_limited` |
| POST | `/platform/sign-up/verify` | public (link token) | `{token}`: confirms the email on first use and returns the applicant view (FR-ONB-02) |
| POST | `/platform/sign-up/status` | public (link token) | `{token}`: the applicant view, no contact details (FR-ONB-04) |
| POST | `/platform/sign-up/reply` | public (link token) | `{token, reply}`: answers a `needs_info` note; back to `submitted` (FR-ONB-04) |
| GET | `/platform/applications` | `platform.tenants.read` | `?status=` (repeatable). Confirmed applications, newest first, at most 500, and `counts` per status (FR-ONB-05) |
| GET | `/platform/applications/{application_id}` | `platform.tenants.read` | The application, `possible_duplicates` and a free `suggested_slug` (FR-ONB-05) |
| POST | `/platform/applications/{application_id}/verify` | `platform.tenants.manage` | FR-ONB-06 |
| POST | `/platform/applications/{application_id}/needs-info` | `platform.tenants.manage` | `{note}`, which the applicant sees (FR-ONB-06) |
| POST | `/platform/applications/{application_id}/reject` | `platform.tenants.manage` | `{note}`, the reason the applicant sees (FR-ONB-06) |
| POST | `/platform/applications/{application_id}/activate` | `platform.tenants.manage` | `{modules, term, way_in, payment_note?, slug, plan_code?, agent_code?}`: creates the tenant through the FR-TEN-01 path and queues the activation email; `activated_now: false` and nothing done on a repeat (FR-ONB-07) |
| GET | `/platform/outbox` | `platform.tenants.read` | `enabled_channels`, `counts` per status and the failed rows with a masked recipient, and from #212 the row's `tenant_id` and `category` (FR-NTF-09) |
| POST | `/platform/outbox/{outbox_id}/retry` | `platform.tenants.manage` | A failed row back to `pending`; 409 `outbox_not_failed` otherwise. Audited. For an SMS whose outcome was `unknown` it is the deliberate resend (ADR-036); 409 `outbox_too_old` once the receipt's hour has passed |
| GET | `/platform/tenants/{tenant_id}/sms` | `platform.tenants.read` | `{enabled, daily_cap, used_today, platform_daily_cap, mode}`; off and a cap of 200 for a tenant never set; 404 for an unknown tenant. Built in #212 (ADR-036, FR-NTF-20) |
| PUT | `/platform/tenants/{tenant_id}/sms` | `platform.tenants.manage` | `{enabled, daily_cap}` (0 to 100000), the only way to switch SMS on or change the cap; audited as `platform.sms.controls_changed` with before and after |
| GET | `/platform/tenants/{tenant_id}/sms/sender-ids` | `platform.tenants.read` | `{items: [{sender_id, approved_at}]}`: the sender ids approved for the tenant; 404 for an unknown tenant. Built in #218 (ADR-036 second amendment) |
| PUT | `/platform/tenants/{tenant_id}/sms/sender-ids/{sender_id}` | `platform.tenants.manage` | Approves a sender id (3 to 11 letters and digits with at least one letter, else 422) for the tenant, which can then choose it; 409 `sender_id_taken` when another tenant holds it (an id belongs to one tenant at a time, compared without case); idempotent for the same tenant; audited as `platform.sms.sender_id_approved`. Registration with the provider and the networks is done first (runbook `sms-setup`) |
| DELETE | `/platform/tenants/{tenant_id}/sms/sender-ids/{sender_id}` | `platform.tenants.manage` | Withdraws the approval (404 when there is none); the tenant's messages go from the shared sender at once, its settings keep the name and show it as withdrawn; audited as `platform.sms.sender_id_withdrawn` |

The sign-up endpoints sit under `/platform` so they are served only on the platform host and
resolve no tenant; they need no token. Their link token travels in the body: the emailed link
carries it in the URL fragment, which no server receives.

### 7.11.4 Tenant administration

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/settings` | `core.settings.read` | |
| PATCH | `/settings` | `core.settings.manage` | FR-TEN-08. The SMS settings (`sms_sender_name`, `sms_window_start`, `sms_window_end`, `sms_include_balance`) are no longer here: they are in `PUT /settings/sms` since #218, and this route refuses them (400, unknown property). Includes `theme_primary` (`#RRGGBB`, or an empty string to clear it back to the platform look; a colour with no readable text colour is a 422 `validation_failed` with the field error `insufficient_contrast`) and `setup_dismissed` |
| PUT | `/settings/logo` | `core.settings.manage` | `multipart/form-data` field `file`: PNG, JPEG or WebP, 1 MB, 128 px or more; re-encoded and scaled to 512 px; returns the settings. Takes no `If-Match` but bumps the settings `version`, so a form holding the old version must refetch before its next `PATCH`. A logo over 4 megapixels is 422 `image_too_large`. FR-TEN-08 |
| DELETE | `/settings/logo` | `core.settings.manage` | Clears the logo; the file is kept. Idempotent |
| GET | `/branding` | public | `{display_name, theme_primary, theme_text, logo_url, modules}` for the tenant of the request host; `logo_url` is `/api/v1/branding/logo?v=<document id>` or null; `modules` is the sorted list of enabled module keys (`lending`, `retail`), so the landing page promises only what the tenant has (#99). FR-TEN-08 |
| GET | `/branding/logo` | public | The logo image: `Content-Type` from the stored value (`image/png` or `image/jpeg`), `Cache-Control: public, max-age=3600`, `ETag` (304 on `If-None-Match`), `X-Content-Type-Options: nosniff`, `Content-Disposition: inline`, `Content-Security-Policy: default-src 'none'; sandbox`; 404 `not_found` when there is no logo; 503 `logo_unavailable` when storage cannot be read; `Vary: Host`; the optional `v` parameter must be a UUID and only busts caches. FR-TEN-08 |

The two branding routes are public by design: they take no token, the tenant is resolved from the
host like every tenant route (an unknown host is 404 `unknown_tenant`; the platform host has no
tenant, so they answer 404 there and the PWA shows the Rincoltech brand without calling them), and
they work for a suspended tenant (reads). They expose only the display name, a colour and one
re-encoded image of that tenant, and the keys of its enabled modules (no counts, plans or settings).
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
| GET, PUT, DELETE | `/ledger/payment-methods` | `core.ledger.read` / `core.payment_methods.manage` | FR-GL-08. Built (#160): GET lists the mapped rows and the defaults still in use (`source` `mapped` or `default`); PUT sets one method's account for the tenant or a `branch_id` (`is_active` false switches it off). The account must be a postable, active asset account no module controls (`invalid_payment_account`); `unknown_branch`; with the retail module on, `cash` and `bank` stay on their seeded accounts (`account_used_by_retail`). The permission is branch scoped: a branch's row needs that branch in the caller's scope and the tenant-wide row needs authority over every branch (403 `permission_denied` otherwise), for PUT and DELETE alike. Two first saves at once: the loser gets 409 `payment_method_changed`. Audited as `core.payment_method.set`. `DELETE /ledger/payment-methods/{method_key}` (optional `branch_id`) removes that scope's row, so the method posts to its seeded account again (404 when there is no row; audited as `core.payment_method.cleared`). The platform console refuses to switch retail on while cash or bank is mapped elsewhere (`payment_methods_remap_blocks_retail`). |
| GET | `/ledger/reconciliation` | `core.ledger.read` | Latest subledger reconciliation. FR-GL-10 |

### 7.11.7 Notifications and documents

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/notification-templates` | `core.notifications.read` | **Built (#218), channel `sms` only.** `{items: [{event_key, channel, title, default_text, placeholders [{name, description, sample, max_length}], override {text, version, updated_at} or null, sample_text, measure {encoding [gsm7, ucs2], units, segments, worst_case_segments, estimated_cost_minor, currency}, max_chars, max_worst_case_segments}]}`. The events are those the application sends (`savings.deposit`, `savings.withdrawal`, and from #221 `loan.disbursed`, `loan.repayment_received`, `loan.closed`, `investment.funded`, `investment.maturing`, `investment.paid_out`, each with its own placeholder list and never a balance); the estimate is the tenant's own average cost per segment over the last 30 days and null without history or with two currencies |
| PUT | `/notification-templates/{event_key}/{channel}` | `core.notification_templates.manage` | FR-NTF-03. **Built (#218).** `{text}` under `If-Match` on the override's version (0 while there is none; 428, 409 `version_conflict`, one winner of a race). 422 with the first problem's code on `text`: `blank`, `too_long`, `invalid_character`, `malformed_placeholder`, `unknown_placeholder`, `missing_required_placeholder`, `too_many_segments`. Line endings are normalised and the edges trimmed; the same text again writes nothing. 404 for an unknown event or a channel other than `sms`. Audited as `core.sms.template.changed` with the old and new text |
| DELETE | `/notification-templates/{event_key}/{channel}` | `core.notification_templates.manage` | **Built (#218).** Goes back to the default wording under `If-Match`; audited as `core.sms.template.reverted` |
| POST | `/notification-templates/{event_key}/{channel}/preview` | `core.notification_templates.manage` | Renders with fabricated sample data and the tenant's own business name. **Built (#218):** `{text?}` (omitted: the saved wording, else the default) answers `{text, measure}`; refuses what a save would refuse; saves nothing, sends nothing |
| GET | `/notifications` | `core.notifications.read` | Send log with status. For SMS it reads `sms_deliveries` (status, segments, recorded cost, masked recipient) (ADR-036) |
| GET | `/settings/sms` | `core.settings.read` | **Built (#218).** The tenant's SMS settings with the `ETag` of the tenant settings row: `{enabled, operator_enabled, mode, test_message_available, sender_id, sender_status [none, approved, withdrawn], approved_sender_ids, window_start, window_end, window_zone, window_earliest, window_latest, window_min_minutes, daily_cap, monthly_cap, daily_ceiling, monthly_ceiling, effective_daily_cap, effective_monthly_cap, used_today, used_this_month, include_balance, acknowledgement {text_version, text}, acknowledged_text_version, acknowledged_at, acknowledged_by, version}`. A cap of null means none of its own; 0 means stop |
| PUT | `/settings/sms` | `core.settings.manage` (the tenant administrator only) | **Built (#218).** A partial change under `If-Match` (428 without it, 409 `version_conflict` when stale, a lost race has one winner): `enabled` (switching on needs `acknowledged_text_version` equal to the text shown, else 422 `acknowledgement_required`; the user and time are stamped by the server; switching on drops the tenant's pending SMS rows), `sender_id` (one of `approved_sender_ids`, else 422 `not_approved`; an empty string clears it), `window_start` and `window_end` (`HH:mm`, Africa/Kampala, start before end, at least 60 minutes, inside 06:00 to 21:00: 422 `invalid`, `window_too_short`, `outside_limits`), `daily_cap` and `monthly_cap` (whole numbers, 0 stops everything, at most their ceilings else 422 `above_ceiling`; a fraction is refused, not rounded), `include_balance`. Audited as `core.sms.settings.changed` with before and after; a change that alters nothing writes nothing and keeps the version |
| GET | `/sms/opt-outs` | `core.notifications.read` | **Built (#218).** `{items, next_cursor}` newest first: `{id, kind [member, number], member_id, scope [reminders, all], source [staff, member, number, import], recipient_masked, created_at, created_by}`; `q` (the shared search over the masked number's digits; the term never in the address of the page), `scope`, `kind`, `limit` (50, at most 200), `cursor`; 400 for an unknown filter or a cursor from another list or search |
| POST | `/sms/opt-outs` | `core.settings.manage` | **Built (#218).** `{phone, scope, reason?}` (`reason`, 3 to 200 characters, is required, 422 `reason_required`, only when this narrows the number from `all` to `reminders`, and is audited): puts a number on the list (read by the strict SMS phone rule, 422 `invalid_phone` otherwise); 201 for a new row, 200 when the number was already there (its scope is set as asked); audited as `core.sms.opt_out.added` or `.changed` with the masked number |
| POST | `/sms/opt-outs/import` | `core.settings.manage` | **Built (#218).** `{numbers, scope, dry_run}` (default `dry_run` true): up to 2000 numbers separated by new lines, commas or semicolons; returns `{dry_run, rows_read, added, changed, unchanged, invalid, rows: [{line, outcome}]}` with outcomes `added`, `changed` (made stricter), `unchanged`, `duplicate_in_list`, `invalid_phone`; the numbers are never echoed; a pasted list never weakens a block; the valid lines are applied even when another line is invalid; 422 `too_many_rows` over 2000; audited per row and as `core.sms.opt_out.imported` |
| DELETE | `/sms/opt-outs/{opt_out_id}` | `core.settings.manage` | **Built (#218).** `{reason}` (3 to 200 characters, 422 `reason_required`): lifts a row of the tenant; 204; 403 for anyone but the tenant administrator; 404 for another tenant's row, a lifted one or an unknown one; audited as `core.sms.opt_out.removed` with the reason |
| POST | `/sms/test-message` | `core.notification_templates.manage` | **Built (#218).** `{event_key, text?, to}`: sends the wording (the saved one, else the default, or `text`) with fabricated data and the prefix `TEST: ` to one number, and only to the test sink or a number on the environment's allow-list: mode `off` or `live` sends nothing (`reason` `channel_off`, `live_mode_preview_only`), `sink` records it, `allowlist` sends only to a listed number and sinks any other; a send that would really go out first passes the operator's switch, the tenant's switch, the opt-out list and the caps (`reason` `operator_sms_off`, `tenant_sms_off`, `blocked_optout`, `cap_reached`, nothing sent). `{sent, outcome [sunk, sent, not_sent], reason, mode, recipient_masked, text, measure}`; 422 `invalid_phone` or the wording's code; 429 `rate_limited` over 10 an hour per business; not an outbox row; a real send is written to the delivery log as `test.<event>` and counts against the caps, a sink recording is not; audited with the masked number |
| GET | `/lending/members/{member_id}/sms-opt-out` | `lending.members.read` | **Built (#218).** `{opted_out, scope, recipient_masked, since}`; a member outside the caller's branch scope is 404 |
| PUT, DELETE | `/lending/members/{member_id}/sms-opt-out` | PUT `lending.members.update`; DELETE `core.settings.manage` | PUT `{scope: reminders \| all, reason?}`; audited; changing `all` to `reminders` is the administrator's (403 `permission_denied`) and needs the reason (3 to 200 characters, 422 `reason_required`), audited with it. DELETE `{reason}` (3 to 200 characters, 422 `reason_required`) lifts it and is audited with the reason. FR-NTF-14. **Built (#218):** the row is about the member and holds no number (it follows the member to any number); the same scope again writes and audits nothing; DELETE of nothing is a 200; a member outside the caller's branch scope or of another tenant is 404 |
| GET | `/documents/{document_id}` | permission on the subject | Metadata. Declared "authenticated" (staff); the owning module's `DocumentAccess` decides, 403 `permission_denied` when it says no |
| POST | `/documents/{document_id}/download-url` | permission on the subject | `{url, expires_at}`, 5 minutes; every issue is audited (`core.document.download_url_issued`). Member ID images (`id_front`, `id_back`) need `lending.members.verify_kyc` (chapter 8). FR-DOC-03 |

Uploads go through the subject's own route (`POST /lending/members/{member_id}/documents` now, the
collateral route next), so each carries its one matrix permission and branch check; there is no
generic `POST /documents`. Built (#12): these two routes and the member document routes.

### 7.11.8 Reports

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/reports` | authenticated staff | Catalogue filtered by permission and by the tenant's modules: a list of `{key, title, description, permission, formats, params}`; 404 `reports_not_enabled` while the tenant has not switched report runs on, like every other report route. FR-RPT-01. **Built (#214)** |
| GET | `/reports/{report_key}` | report's permission | Synchronous JSON for small reports; params as query. Not built |
| POST | `/report-runs` | report's permission (a pack needs all seven) | `{report_key, params, format}`; async, returns the run: 202 when queued, 200 when an identical queued or running request of the same user already exists (it is returned instead). An identical request is answered with the existing run before the rate limit is applied. Optional `Idempotency-Key` replays the first answer. 404 `reports_not_enabled` while the tenant switch is off, 404 `report_not_found`, 403 `permission_denied`, 422 `validation_failed` for an unknown parameter, a bad value or an unsupported format, 429 `rate_limited` at 10 new runs a minute per user (counted under a lock per requester, so it holds under concurrent requests). FR-RPT-04, FR-RPT-06, ADR-038. **Built (#214)**: formats `json` and `csv`; `pdf`, `xlsx` and `pack` arrive with slices D2 to D5 |
| GET | `/report-runs` | requester | Own runs, newest first, with status, as-of date and expiry (`?limit`, at most 100). **Built (#214)** |
| GET | `/report-runs/{run_id}` | requester or `core.audit.read` | Status, parameters, dates, row count, size, `document_id`, `expires_at`, `expired` and the stable `error` code of a `failed` run (`report_too_large`, `snapshot_unavailable`, `timeout`, ...); anyone else gets 404. Download goes through `POST /documents/{id}/download-url`: the requester only, while reports are on, while they still hold the report's permission and while their branches and officers still cover the scope the file was made under; every other case, including a colleague, is 404 (not 403); 410 `report_expired` after 30 days. **Built (#214)** |
| POST | `/report-runs/{run_id}/cancel` | requester | A queued run is cancelled at once (200); a running one is asked to stop and ends `cancelled` at its next checkpoint (202); a finished one is 409 `run_finished`. Audited. **Built (#214)** |

Responses of these routes are `Cache-Control: no-store`. The switch is the tenant setting `reports_enabled` (`PATCH /settings`,
`core.settings.manage`; also `GET /me` `reports_enabled`, which the PWA reads to show the Reports tab), off by default. No new permission exists: a report is run under its own `lending.reports.*`
permission and a loan officer sees only their own loans. The lending report `lending.par` additionally needs
`lending.insights.read`, because its branch scope is the insights page's.

Formats (slice D2, issue #217): `json`, `csv`, `pdf` (`application/pdf`) and `xlsx`
(`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`); the catalogue lists the formats each report offers, and a request for
any other is 422 on `format`. `lending.par` and `lending.portfolio_outstanding` offer all four. A file is served as an attachment named
by its type and id only (`report-<document id>.pdf` or `.xlsx`), never by anything in it; the PDF's printed number
(`RPT-yyyymmdd-xxxxxx`) is the document's `doc_number`. A run that cannot render because its output passes the byte cap ends `failed` with
`report_too_large`, as for the other formats; a PDF table over `BMS_REPORTS_PDF_ROW_CAP` is cut with a printed pointer to the Excel file and does not fail.

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
| POST | `/payments/intents` | `core.payments.collect` or `member.self.pay` | **M**. FR-PAY-01. `{purpose, subject_id, amount_minor, payer_phone}`; returns the intent with `redirect_url` once the order exists. 422 `collections_disabled`, `exceeds_payoff`, `subject_not_payable` (ADR-035) |
| GET | `/payments/intents` | `core.payments.read` | List with status and flags (`paid_less`, `paid_more`, `late`, `reversed_pending_review`) |
| GET | `/payments/intents/{intent_id}` | initiator or `core.payments.read` | Polled by the UI. Built in #213, with `POST /payments/intents/{intent_id}/cancel` (`core.payments.collect`; the order is cancelled at the provider first; 409 `not_cancellable` once paid). The list, statements, reconciliation and unallocated routes are slices #224 and later |
| POST | `/payments/statements` | `core.payments.manage` | Multipart statement CSV; matches rows, posts fees once. FR-PAY-11 |
| GET | `/payments/reconciliation` | `core.payments.read` | The latest nightly differences (ADR-035 decision 9) |
| GET, PUT | `/payments/settings` | read / `core.settings.manage` | `collections_enabled`; 409 `merchant_account_not_verified` until the tenant's own payment account is verified, 409 `gateway_method_unmapped` until the `gateway` payment method is mapped, 409 `gateway_not_configured` when the host has no provider or callback address (ADR-035 amendment of 2026-10-18) |
| GET | `/payments/merchant-account` | `core.settings.read` | Built in #233. The tenant's own payment account: `status` (`not_connected`, `verified`, `failed`), `credentials_saved`, `key_hint` (last four characters of the consumer key), `last_checked_at`, `failure_reason` (`invalid_credentials`, `registration_refused`), `host_ready`. `key_hint` and `host_ready` are given only to a holder of `core.payment_account.manage`. No field carries a secret. `Cache-Control: no-store` |
| PUT | `/payments/merchant-account` | `core.payment_account.manage` | `{consumer_key, consumer_secret, totp_code}`: sealed with the data key, **write-only**, no call to the provider; needs a current code of the signed-in administrator's authenticator (step-up: 422 `invalid_code`, 409 `mfa_not_enrolled`); the status goes back to `not_connected` until tested; the other holders are emailed. 422 `validation_failed`; 409 `merchant_account_in_use` (the platform's key, or one another tenant is connected with); 409 `payments_in_flight` while a payment still scheduled for a check needs the old account and the consumer key differs |
| POST | `/payments/merchant-account/test` | `core.payment_account.manage` | Test connection (one a half minute, 429 `test_too_soon`): asks the provider for a token and registers this account's notification address; `verified` or `failed` is returned and audited. 409 `account_not_saved`, `gateway_not_configured`, `account_changed`; 429 `provider_busy`; 502 `provider_unreachable` (nothing changed) |
| DELETE | `/payments/merchant-account` | `core.payment_account.manage` | `{totp_code}` (step-up). Removes the saved credentials; the other holders are emailed. 409 `payments_in_flight` while a payment still scheduled for a check needs them |
| GET | `/payments/unallocated` | `core.payments.read` | FR-PAY-04 |
| POST | `/payments/unallocated/{receipt_id}/allocate` | `core.payments.allocate` | **M** |
| POST, GET | `/payments/callbacks/pesapal/{token}` and GET `/payments/return/pesapal` (built in #213; the second is the payer's return page) | public, path token (the platform's from the environment, a lender's by the digest of its own token; a lender's address reaches only that lender's orders); the body is never trusted | On the callback host. Identifies the intent, queues a status query, answers 200 with the provider's JSON acknowledgement (spec 3.6; shape documented in 3.12). FR-PAY-02, FR-PAY-08 |

### 7.11.11 Lending: members (`/lending/members`)

Built so far (the reference slice, `lending.members`): `POST /lending/members`,
`GET /lending/members` (cursor pagination ordered by `member_no`, filters `branch_id`,
`status`, `q`; phone and NIN masked) and `GET /lending/members/{member_id}` (with `ETag`); then
(#10) `PATCH /lending/members/{member_id}`, `POST /lending/members/duplicate-check`,
`POST .../kyc/verify` and `POST .../blacklist`. The PATCH and the two state changes require
`If-Match` (section 7.9) and return the new `ETag`. Loan number search waits for loans
(increment 4). Then (#11) the next of kin routes and `GET .../relationships`, and (#12) the member
document routes. The rest of this table is to be built. `POST /lending/members` and `POST /lending/collateral` do not read `Idempotency-Key` (they move no money, section 7.8); the staff screens still send one per form, and look the record up after an answer that may have been lost before allowing another save.

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
`max_active_loans_reached` (approved and active loans both count, section 3.18), and 409 `invalid_status_transition`. The nightly task
`lending.loan-approval-expiry` cancels an approval older than `approval_validity_days` with
reason `approval_expired` (FR-ORG-08), audited with actor kind `system`. Both `approved_at` and
the expiry bound come from the business clock (AGENTS.md rule 7), never the database's `now()`.

Every request that records money a member is told about takes an optional `notify_member` (boolean, default true; issue #221,
ADR-036 third amendment): loan `POST .../disbursements` and `.../repayments`, savings `.../deposits`, `.../withdrawals` and `.../close`,
investment `.../funding`, `.../return-payouts`, `.../payout` and `.../early-withdrawal`. False records the movement without a text
message to the member, whatever the tenant's SMS settings (a register import sends false), and is accepted only from a holder of
`core.settings.manage`, the business administrator: anyone else gets 403 `notify_member_forbidden` before anything is recorded; where a checker decides, the choice is kept in
the approval's payload. An event whose value date is more than `BMS_SMS_EVENT_MAX_AGE_DAYS` (3) days old is never texted either.

Built (#108, increment 5, ADR-026): disbursement, schedule, transactions, repayments, reversal,
payoff quote and write-off. Every money-moving route requires `Idempotency-Key` (section 7.8; a
replay answers `Idempotent-Replayed: true`). `POST .../disbursements` takes
`{disbursement_date, payment_method_key, external_reference?}` on an `approved` loan and answers
`LoanActionOutcome` `{loan_id, executed, approval_request_id, loan_status}`: 201 when it executed
below the tenant's `loan_disbursement` threshold, else 202 with the pending request (checker
`lending.disbursements.authorise`). `POST .../repayments` takes `{amount_minor, value_date,
payment_method_key, external_reference?}` on an `active` loan (a `written_off` loan records a
`recovery`) and answers 201 `RepaymentResult` `{transaction, loan_status, balances}` with the
allocation rows. `POST .../transactions/{txn_id}/reverse` and `POST .../write-off` take `{reason}`
and answer 202 with the pending request. `GET .../schedule` returns the items and a `totals` row;
`GET .../transactions` returns every money event newest first with its allocation rows
(`applies_to_txn_id`, `item_no`, `component`, `amount_minor`); `GET .../payoff-quote?value_date=`
(today by default) returns principal, interest, fees, penalties, rebate and total. `GET` of a loan
now carries `balances` (disbursed and maturity dates, outstanding by component, arrears, DPD, next
due date, total paid, credit balance), and the list takes `q` (loan or member number by prefix,
member name by any part) and returns the member's number and name, the outstanding total, DPD and
the next due date. `payment_method_key` is `cash`, `bank`, `mtn_momo` or `airtel_money`. Codes (422
unless stated): `value_date_in_future`, `before_disbursement`, `before_last_repayment`,
`payment_method_unmapped`, `fees_exceed_principal`, `period_closed`, `not_reversible`,
`nothing_to_write_off`, 409 `already_reversed`, 409 `invalid_status_transition` (a second
disbursement, FR-DIS-03; a repayment on a closed loan; a quote on a loan that is not active), 409
`approval_already_pending`, and the idempotency codes of section 7.8.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/lending/loans` | `lending.loans.read` | Filters: `status`, `member_id`, `officer_user_id`, `product_id`, `q` (built); `dpd_from`, `dpd_to`, `disbursed_on_from/_to` (increment 8) |
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
| POST | `/lending/loans/{loan_id}/documents/{doc_type}` | `lending.loans.read` | Generates statement, schedule, or agreement PDF. Built (#120, ADR-034): `schedule` and `loan_statement` return the PDF itself as at the business date, numbered `SCH-<branch>-<loan no>` and `ST-<branch>-<loan no>`, never stored, numbered with the statement date; 409 `invalid_status_transition` before disbursement; 503 `documents_busy` with `Retry-After` when too many are being produced. Every document response is `Cache-Control: no-store`. The statement is the loan account with a running balance whose closing balance is the loan's outstanding (ADR-034). |
| POST | `/lending/loans/{loan_id}/transactions/{txn_id}/document` | `lending.loans.read` | Built (#120, ADR-034): the receipt (repayment, recovery, upfront fee) or voucher (disbursement), rendered on the first request and stored once (FR-DOC-02); answers `{document_id, doc_type, doc_number, created, reversed, reversed_on}`, then download through `POST /documents/{id}/download-url` (FR-DOC-03). A receipt shows the loan as it was right after that payment, whenever it is first opened. 422 `no_document` for a reversal or a write-off; 422 `transaction_reversed` when the repayment was reversed and no receipt had been issued; a receipt issued before a reversal is unchanged and comes back with `reversed: true`. |
| GET | `/lending/loans/{loan_id}/documents` | `lending.loans.read` | Built (#120, ADR-034): the receipts and vouchers stored for the loan, newest first, `{items: [{document_id, doc_type, doc_number, created, reversed, reversed_on}]}`; `reversed` marks a receipt whose repayment was reversed after it was issued. |

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

Built in increment 9 (issue #151, ADR-032). A withdrawal, a closure and a reversal answer 201 when
executed and 202 when they wait for a checker (`SavingsActionOutcome`: `executed`,
`approval_request_id`, the withdrawal `transaction` when executed, and the `account`). Every money
route needs an `Idempotency-Key`; a replay answers `Idempotent-Replayed: true`. An account outside
the caller's branch scope is a 404.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET, POST | `/lending/savings-products` | `lending.savings.read` / `lending.savings_products.manage` | FR-SAV-01. Body `{code, currency?, terms}`; 409 `duplicate_code` |
| GET | `/lending/savings-products/{product_id}` | `lending.savings.read` | `ETag` is the version |
| PUT | `/lending/savings-products/{product_id}` | `lending.savings_products.manage` | `If-Match`. `{terms, status}` (`active` or `archived`); 422 `product_in_use` for an interest change once an account uses it |
| GET | `/lending/savings-accounts` | `lending.savings.read` | `branch_id` (repeatable), `member_id`, `status` (repeatable), `product_id`, `q` (account or member number by prefix, member name by part), `limit` (default 25, at most 100), `cursor`; by account number |
| POST | `/lending/savings-accounts` | `lending.savings.open` | FR-SAV-02. `{member_id, product_id, branch_id?}`; the member's home branch by default (FR-BR-05). 422 `member_not_active`, `product_not_active` |
| GET | `/lending/savings-accounts/{account_id}` | `lending.savings.read` | Balance, `available_minor` (what a withdrawal may take now), `accrued_interest_minor` (since the last posting, an estimate) |
| GET | `/lending/savings-accounts/{account_id}/transactions` | `lending.savings.read` | Newest first with running balances; `limit` (default 50, at most 200), `cursor` |
| GET | `/lending/savings-accounts/{account_id}/statement` | `lending.savings.read` | `from`, `to` (value dates; three months to today by default): opening, lines with running balance, totals, closing. 422 `invalid_range` |
| POST | `/lending/savings-accounts/{account_id}/deposits` | `lending.savings.deposit` | **M**. FR-SAV-03. `{amount_minor, value_date?, payment_method_key, external_reference?}`; 201 `{transaction, account}`. 422 `below_minimum_opening`, `value_date_in_future`, `value_date_closed`, `before_opening`, `account_closed`, `payment_method_unmapped` |
| POST | `/lending/savings-accounts/{account_id}/withdrawals` | `lending.savings.withdraw` | **M A** (`savings_withdrawal`). `{amount_minor, payment_method_key, external_reference?}`, dated the day it executes. 422 `insufficient_balance`, `withdrawal_limit_exceeded`, `withdrawal_count_exceeded`, `account_dormant`, `account_frozen`, `account_closed`; 409 `approval_already_pending` |
| POST | `/lending/savings-accounts/{account_id}/transactions/{txn_id}/reverse` | `lending.savings.withdraw` | **M A** (`savings_reversal`, always checked). `{reason}`. A deposit or a withdrawal with its fee. 422 `not_reversible`, `insufficient_balance`; 409 `already_reversed` |
| POST | `/lending/savings-accounts/{account_id}/reactivate` | `lending.savings.withdraw_approve` | FR-SAV-06. `{reason}`; dormant to active. 409 `invalid_status_transition` |
| POST | `/lending/savings-accounts/{account_id}/freeze` | `lending.savings.withdraw_approve` | `{reason}`; active or dormant to frozen (ADR-032) |
| POST | `/lending/savings-accounts/{account_id}/unfreeze` | `lending.savings.withdraw_approve` | `{reason}`; frozen to active |
| POST | `/lending/savings-accounts/{account_id}/close` | `lending.savings.withdraw` | **M A** (`savings_withdrawal` with `close`). FR-SAV-07. `{payment_method_key, external_reference?, reason?}`: interest to date, then the whole balance paid out. 422 `account_on_hold` |
| GET | `/lending/savings-reports/balances` | `lending.reports.members` | `lending.savings_balances` (chapter 14 section 14.5): `as_at` (today by default), `branch_id`, `product_id` |
| GET | `/lending/savings-reports/movements` | `lending.reports.members` | `lending.savings_movements`: `from` (the first of the month by default), `to`, `branch_id`, `product_id` |

### 7.11.16 Lending: investments

Built in increment 10 (#152, ADR-031). **M** marks a route that requires an `Idempotency-Key`;
**A** a maker-checker action: 201 when it executed (below the tenant's threshold), 202 when it waits
for a checker, with `approval_request_id`.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET, POST | `/lending/investment-products` | `lending.investments.read` / `lending.investment_products.manage` | FR-INV-01, FR-INV-08 |
| GET, PUT | `/lending/investment-products/{product_id}` | `lending.investments.read` / `lending.investment_products.manage` | PUT with `If-Match`; investments opened keep their terms |
| POST | `/lending/investment-products/{product_id}/archive` | `lending.investment_products.manage` | `If-Match` |
| POST | `/lending/investment-products/return-preview` | `lending.investments.read` | R-INV-1 to R-INV-4 for an amount and term |
| GET | `/lending/investments` | `lending.investments.read` | Filters `branch_id`, `status`, `member_id` (the member tab), `product_id`, `maturing_within_days`, `q`; cursor. The investments register of chapter 14 |
| POST | `/lending/investments` | `lending.investments.open` | FR-INV-02 |
| GET | `/lending/investments/maturities` | `lending.investments.read` | FR-INV-12: the ladder (overdue, 0 to 7, 8 to 30, 31 to 90 days) and the investments maturing |
| GET | `/lending/investments/metrics` | `lending.investments.read` | FR-INV-12: `from`, `to`, `branch_id`; metrics, ladder, concentration |
| GET | `/lending/investments/{investment_id}` | `lending.investments.read` | Balances, `return_available_minor`, `pending_approval_id` |
| PUT | `/lending/investments/{investment_id}/maturity-instruction` | `lending.investments.open` | FR-INV-05 |
| POST | `/lending/investments/{investment_id}/funding` | `lending.investments.fund` | **M A** (`investment_funding`, threshold applies). FR-INV-03 |
| GET | `/lending/investments/{investment_id}/schedule` | `lending.investments.read` | FR-INV-09 |
| GET | `/lending/investments/{investment_id}/statement` | `lending.investments.read` | FR-INV-10 |
| GET | `/lending/investments/{investment_id}/certificate` | `lending.investments.read` | FR-INV-10 |
| POST | `/lending/investments/{investment_id}/return-payouts` | `lending.investments.payout` | **M**. FR-INV-04 |
| POST | `/lending/investments/{investment_id}/payout` | `lending.investments.payout` | **M**. FR-INV-05 |
| POST | `/lending/investments/{investment_id}/rollover` | `lending.investments.payout` | **M**. FR-INV-05 |
| GET | `/lending/investments/{investment_id}/early-withdrawal-quote` | `lending.investments.read` | R-INV-6 as at `value_date` |
| POST | `/lending/investments/{investment_id}/early-withdrawal` | `lending.investments.payout` | **M A** (`investment_early_withdrawal`, no threshold). FR-INV-06 |
| POST | `/lending/investments/{investment_id}/transactions/{txn_id}/reverse` | `lending.investments.payout` | **M A** (`investment_reversal`, no threshold). FR-INV-11 |

Codes (422 unless stated): `term_not_offered`, `amount_out_of_range`, `product_archived`,
`member_not_active`, `member_not_found`, `compounding_needs_maturity_payout`, `value_date_in_future`,
`value_date_out_of_range`, `no_return_due`, `early_withdrawal_not_allowed`, `investment_matured`,
`before_start`, `nothing_to_pay`, `not_reversible`, `not_funded`, `payment_method_unmapped`,
`period_closed`, 409 `duplicate_code`, 409 `already_reversed`, 409 `invalid_status_transition`, 409
`approval_already_pending`, and the idempotency codes of section 7.8.

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
| POST, GET | `/payments/callbacks/{provider}/{token}` | 7.11.10 |
| POST | `/channels/sms/{provider}/delivery-reports/{token}` | FR-NTF-05; forward-only status; unknown ids ignored (ADR-036) |
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
| GET | `/retail/categories`, `/retail/units` | `retail.stock.read` | `{items: [{id, name, active, product_count}]}`; `product_count` is the products, active or not, that use the row (#146) |
| POST | `/retail/categories`, `/retail/units` | `retail.catalogue.manage` | `{name}`; 409 `duplicate_category`, `duplicate_unit` (names ignore case). FR-RET-01 |
| POST | `/retail/products/import` | `retail.catalogue.manage` and, checked in the service, `core.settings.manage` (administrators only; 403 `permission_denied` otherwise) | `dry_run` (default `true`), body `{csv}`: a header row then one item per row, columns `code`, `description`, `category`, `unit`, `sell price` and optionally `cost price`, `pack unit` and `pack factor` (issue #194: one pack per row, both or neither; an error row while the `units` capability is off, when the pack unit equals the unit or is switched off, or the factor is not at least 1 with at most three places (a comma is refused, "12,5" is an error, never 125; a dry run row names the factor it read); a pack unit is created on demand like a unit; a file without these columns is read as before) (comma, semicolon or tab separated, quotes allowed, at most 2,000 rows and 1,000,000 characters). Codes are normalised by `RetailCatalogue.normaliseCode`; categories and units are created on demand by name ignoring case and surrounding spaces, so `Drinks` and `drinks` in one file are one new row, and a switched-off category or unit is an error row naming the line; amounts are in major units read with the tenant currency's exponent (whole numbers for UGX, up to two decimals for a two-decimal currency), as integer minor units with no floating point. An apply that meets a clash raised by a concurrent change returns 409 `import_conflict` and adds nothing. Returns `{dry_run, rows_read, added, skipped, errors, categories_created, units_created, rows: [{line, code, description, outcome, message}]}` where `outcome` is `added`, `skipped` (the code exists, or repeats in the file) or `error`. A dry run changes nothing. An apply with any error row adds nothing (422 `import_has_errors`); applying again skips every row already added, so it is idempotent on the code. A `cost price` column needs `retail.profit.read` (422 `cost_not_allowed` otherwise) and a cost at or above the sell price is an error row. Each item is audited as `retail.product.created` without cost, plus one `retail.product.imported` summary (#146) |
| PATCH | `/retail/categories/{category_id}`, `/retail/units/{unit_id}` | `retail.catalogue.manage` | `{name?, active?}` under `If-Match` with the `version` last read (428 `precondition_required` without it, 409 `version_conflict` when stale; the response carries the new `ETag`); rename or switch off, never deleted; 409 `duplicate_category`, `duplicate_unit`; 404 for another tenant's id. A switched-off row stays on its products but cannot be chosen for a new product or a product's change (422 `inactive_category`, `inactive_unit`). Audited as `retail.category.updated`, `retail.unit.updated` (#146) |
| GET | `/retail/products` | `retail.stock.read` | Filters `query` (code, description or category name), `category_id`, `active`; `limit`, `cursor`, ordered by code. Row: `{id, code, description, category_id, category, unit_id, unit, sell_minor, cost_minor*, currency, active, version}` |
| POST | `/retail/products` | `retail.catalogue.manage` | `{code, description, category_id, unit_id, cost_minor, sell_minor}`; writes the `initial` history row; 409 `duplicate_product_code`. FR-RET-01 |
| GET, PATCH | `/retail/products/{product_id}` | read / `retail.catalogue.manage` | PATCH needs `If-Match` and edits `code`, `description`, `category_id`, `unit_id`, `active` only; a price in the body is 400 |
| POST | `/retail/products/{product_id}/prices` | `retail.price.edit` | `{cost_minor?, sell_minor?, reason}` with `If-Match` (428 without it, 409 `version_conflict` on a stale version, #77); at least one price; 422 `price_unchanged`, which never depends on a cost the caller may not read: without `retail.profit.read` a body naming `cost_minor` always goes ahead, so resending the current cost writes a history and audit row that change nothing, by design (#77); writes a `manual` history row and audit. FR-RET-02 |
| GET | `/retail/products/{product_id}/units` | `retail.stock.read` | Issue #194, the `units` capability (404 `capability_not_enabled` while off): `{items: [{id, product_id, unit_id, unit, factor, active, version}]}`, `factor` a three-place string. The product responses carry the same list as `packs` while the capability is on (absent while off). Reading needs only `retail.stock.read` because the sale and restock forms offer the packs; the spec's table lists `retail.catalogue.manage` for all three |
| POST | `/retail/products/{product_id}/units` | `retail.catalogue.manage` | `{unit_id, factor}`, factor at least 1 with at most three places (a pack holds at least one base unit); 201 with the pack and its `ETag`; 409 `duplicate_pack`; 422 `pack_unit_is_base_unit`, 422 `inactive_unit`. Audited as `retail.product_unit.created` |
| PATCH | `/retail/products/{product_id}/units/{unit_id}` | `retail.catalogue.manage` | `{factor?, active?}` under `If-Match` (428, 409 `version_conflict`); switching a pack on again needs `factor` (422 `factor_required`) and an active unit that is not the item's own; a new factor applies to later lines only, a pack switched off is not offered, never deleted. Audited as `retail.product_unit.updated`. A product's `unit_id` cannot change while it has an active pack, and a unit used by an active pack cannot be switched off: 422 `unit_in_use_by_pack` |
| GET | `/retail/products/{product_id}/price-history` | `retail.stock.read` | `{items: [{id, at, by, source, source_id, old_cost_minor*, new_cost_minor*, old_sell_minor, new_sell_minor, currency, reason}]}` |

Built (#52, stock and sales). `M` marks a money-moving route that requires `Idempotency-Key`
(section 7.8). A sale, stock-take or list for one branch takes `branch_id`, or the caller's one
branch when the permission's scope has exactly one; otherwise 422 `branch_required`.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/retail/products?branch_id=` | `retail.stock.read` | Each row adds `qty` and `negative` for that branch |
| GET | `/retail/stock` | `retail.stock.read` | `branch_id`, `query` (code, description or category name), `category_id`, `negative_only`, `stock_level` (`out`: quantity zero or less; `low`: at or below the low stock threshold, which includes out of stock; any other value is 422), `limit`, `cursor`; the page also carries `low_stock_threshold` (a quantity string, `5.000`, one constant for every tenant until a retail settings group exists, `StockService.LOW_STOCK_MILLI`, ADR-029, #145). Row: `{product_id, code, description, category_id, category, unit, qty, negative, sell_minor, cost_minor*}`. FR-RET-03 |
| GET | `/retail/stock/all-branches` | `retail.stock.read` | Every product with its balance in each branch the caller may read that is active or, when inactive, still holds a non-zero balance (#144; an empty inactive branch is left out, so the totals agree with the valuation): `query` (code, description or category name), `category_id`, `negative_only` (any branch below zero), `stock_level` (as on `/retail/stock`, judged on the total across the listed branches), `limit`, `cursor`. `{low_stock_threshold, branches: [{id, code, name, head_office}], items: [{product_id, code, description, category_id, category, unit, total_qty, negative, sell_minor, cost_minor*, balances: [{branch_id, qty, negative}]}], next_cursor}`; `balances` has one entry per listed branch, zero where no balance exists; `cost_minor` shows only when the caller holds `retail.profit.read` in every listed branch. Reports without `branch_id` (`/retail/reports/valuation`, `/retail/reports/profit/daily`) already cover every branch in scope, with a total per branch |
| GET | `/retail/stock/movements` | `retail.stock.read` | `branch_id` (repeatable, scoped), `product_id`, `from`, `to`. Row: `{id, at, branch_id, product_id, kind, qty, unit_cost_minor*, source_type, source_id, reverses_movement_id, historical, note, by}` |
| POST | `/retail/stocktakes` | `retail.stocktake.commit` | `{branch_id?, lines: [{product_id, counted_qty}], note?}`; returns each line's `expected_qty` and `variance_qty`. FR-RET-08 |
| GET | `/retail/stocktakes/{stocktake_id}` | `retail.stock.read` | Draft or committed |
| POST | `/retail/stocktakes/{stocktake_id}/commit` | `retail.stocktake.commit` | Adjustment movements of counted less `expected_qty` (the variance when counted, so later movements stay in the balance), posted at cost; 409 `stocktake_committed`; 409 `stock_moved_since_count` when later movements would make an adjusted balance negative (recount those lines) |
| POST | `/retail/sales` | `retail.sale.create` | **M**. `{branch_id?, sale_date?, payment_method, customer_id?, buyer_name?, buyer_contact?, due_date?, lines: [{product_id, qty, unit_price_minor?, unit_id?}]}`; `unit_id` (issue #194, the `units` capability) names a pack of the product: `qty` and `unit_price_minor` are then in that unit, the price defaults to the sell price times the factor rounded half up, the stock moves in base units (422 `quantity_not_representable` when the base quantity is not exact to three places or does not fit, 422 `unknown_pack_unit` or `capability_not_enabled` as a field problem on the line), and the price floor is judged on the line total against the line cost; 422 `insufficient_stock` when a line exceeds the branch's stock (always; ADR-020 decision 4); 422 `price_below_cost` when a line's unit price is not above the product's cost and the caller lacks `retail.price.below_cost` (the message never carries the cost; ADR-020 decision 5). Response: the sale with `lines`, `total_minor`, `paid_minor`, `balance_minor`, `cost_total_minor*`, `profit_minor*`, and per line `unit_cost_minor*`, `line_cost_minor*`, and for a pack line `sale_unit_id`, `sale_unit`, `sale_qty`, `sale_unit_price_minor`, `unit_factor` (absent means entered in the base unit; `qty` and `unit_price_minor` are always per base unit). FR-RET-04 |
| GET | `/retail/sales` | `retail.sale.read` | `branch_id` (repeatable, scoped), `from`, `to` (by sale date), `customer_id`, `buyer` (part of the buyer's name or of the linked credit buyer's name; `%` and `_` are literal), `payment_method` (`cash`, `mobile_money`, `bank` or `credit`; else 422), `product_id` (a sale with a line of that product), `status` (`completed` or `voided`; else 422), `owing` (`owing`: a completed credit sale with a balance above zero; `overdue`: owing and `due_date` before the tenant's business date; `paid`: a completed credit sale with no balance; anything else 422; applied in SQL before paging, so it holds across every page), `newest_first` (default false: oldest first), `limit`, `cursor` (the cursor records the `newest_first` direction it was issued for; one sent with the other direction is 422 on `cursor`) (#145) |
| GET | `/retail/sales/{sale_id}` | `retail.sale.read` | 404 outside scope |
| POST | `/retail/sales/{sale_id}/void` | `retail.sale.void` | `{reason}`; 409 `sale_voided`; 422 `sale_has_payments` for a credit sale with payments |
| GET | `/retail/customers` | `retail.sale.read` | `query`, `limit`, `cursor` (ordered by lower-cased name then id; `next_cursor` when more); each item carries `version` and `balance_minor`, what the buyer owes on credit sales in every branch the caller may read, whichever branch is chosen (#146) |
| POST | `/retail/customers` | `retail.customer.manage` | `{name, contact?}` |
| PATCH | `/retail/customers/{customer_id}` | `retail.customer.manage` | `{name?, contact?}` (an empty contact clears it) under `If-Match` (428 without it, 409 `version_conflict` when stale); audited as `retail.customer.updated` without the contact (#146) |
| GET | `/retail/customers/{customer_id}/balance` | `retail.sale.read` | `{customer_id, currency, balance_minor, open_sales: [...]}` over credit sales in the caller's scope. FR-RET-05 |

Built (#53, purchasing, usage and payments):

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET, POST | `/retail/suppliers` | `retail.purchase.create` | `{name, contact?}`; 409 `duplicate_supplier` |
| PATCH | `/retail/suppliers/{supplier_id}` | `retail.catalogue.manage` | `{name?, contact?, active?}` under `If-Match` (428 without it, 409 `version_conflict` when stale); an empty contact clears it, a switched-off supplier is refused on a new restock (422 `inactive_supplier`), never deleted; audited as `retail.supplier.updated` without the contact (#146) |
| POST | `/retail/purchases` | `retail.purchase.create` | **M**. `{supplier_id?, purchased_on, payment_method: cash, bank or credit, note?, lines: [{product_id, cost_minor, sell_minor?, qty_by_branch: [{branch_id, qty}], unit_id?}]}`; every branch must be in the caller's scope; sets the product prices and history atomically, latest line wins. `unit_id` (issue #194) names a pack: the quantities and `cost_minor` are then in that unit (`sell_minor` stays per base unit), the product's cost and the movements take the pack cost divided by the factor rounded half up, `line_total_minor` stays the amount paid or owed as entered, and the difference per branch posts to `purchase_rounding`; the response line adds `unit_id`, `unit`, `unit_factor`, `unit_qty_total`, `unit_cost_minor` and each branch's `unit_qty`. FR-RET-06 |
| GET | `/retail/purchases` | `retail.purchase.create` | `from`, `to`, `supplier_id`, `limit`, `cursor`; purchases that moved stock into the caller's branches. A branch-scoped caller sees only their branches' `qty_by_branch`, with `qty_total`, `line_total_minor` and `total_minor` recomputed from them (lines with nothing in scope left out); an all-branch caller sees the whole document (review F11) |
| POST | `/retail/usage` | `retail.usage.report` | **M**. `{branch_id?, kind: used or damaged, reason, occurred_on?, lines: [{product_id, qty}]}`; response `cost_total_minor*`, per line `unit_cost_minor*`, `line_cost_minor*`; 422 `insufficient_stock` past the branch's stock (ADR-020 decision 4). FR-RET-07 |
| POST | `/retail/sales/{sale_id}/payments` | `retail.sale.create` | **M**. `{amount_minor, method: cash, mobile_money or bank, paid_on?}`; `{payment, sale_paid_minor, sale_balance_minor}`; 422 `payment_exceeds_balance`, `sale_not_payable`. FR-RET-05 |
| GET | `/retail/sales/{sale_id}/payments` | `retail.sale.read` | |

Built (#54, reports):

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/retail/reports/valuation` | `retail.stock.read` | `branch_id` (repeatable, scoped), `as_of` (not in the future; quantities are summed by each movement's business date, the date its journal carries). A row whose quantity times a price does not fit is flagged `amount_out_of_range`, its values left out and excluded from the totals, so one product never fails the report. `{as_of, currency, rows: [{branch_id, product_id, code, description, category_id, category, unit, qty, negative, sell_minor, expected_sales_minor, amount_out_of_range, cost_minor*, value_at_cost_minor*, expected_profit_minor*, expected_profit_bp*}], branches: [{branch_id, expected_sales_minor, value_at_cost_minor*, inventory_account_minor*, revaluation_difference_minor*, expected_profit_minor*, expected_profit_bp*}], categories: [{category_id, category, expected_sales_minor, value_at_cost_minor*, expected_profit_minor*, expected_profit_bp*}], expected_sales_minor, value_at_cost_minor*, expected_profit_minor*, expected_profit_bp*}`; expected profit is expected sales less value at cost, and `expected_profit_bp` is that profit over value at cost in basis points (2500 is 25 percent, half up), absent when the value at cost is not above zero (#145); a category line shows cost and profit only when the caller holds `retail.profit.read` in every branch that contributes to it, as the overall total does; `branches` lists every branch in the filter that holds stock or has a non-zero `inventory` balance (review F3). FR-RET-09 |
| GET | `/retail/reports/profit/daily` | `retail.profit.read` | `branch_id` (repeatable, scoped), `from`, `to` (default the last 30 days, at most 366). `{from, to, currency, rows: [{branch_id, date, sales_minor, cost_of_sales_minor, gross_profit_minor, usage_cost_minor, stocktake_difference_minor, profit_minor}], sales_minor, cost_of_sales_minor, usage_cost_minor, stocktake_difference_minor, profit_minor}`. `stocktake_difference_minor` is the value at cost of committed stock-take adjustments on the day the stock-take was committed, negative for a loss and positive for a gain (the net of the `stock_shrinkage` postings of `retail.stocktake`, #121), and is included in `profit_minor`. The whole report needs `retail.profit.read`, so no caller without it receives the field. FR-RET-10 |
| GET | `/retail/reports/sales-analysis` | `retail.sale.read` | The sales analysis of issue #149 (retail analytics pass). `branch_id` (repeatable, scoped), `from`, `to` (default the last 30 days, at most 366, both ends counted), `group` (`day`, `week` or `month`; weeks start on Monday), `top` (rows of every list, default 10, 1 to 100), `slow_days` (7 to 180, default 30). `{from, to, currency, group, top, profit_visible, totals: {sales_minor, sale_count, qty, gross_profit_minor*, margin_bp*}, series: [{period_start, sales_minor, sale_count, gross_profit_minor*, margin_bp*}], by_branch, by_category, by_product, top_by_quantity, by_seller: [{id, code?, label, unit?, qty, sales_minor, sale_count, gross_profit_minor*, margin_bp*}], slow_days, slow_movers, slow_movers_total, no_sales, no_sales_total: [{product_id, code, description, unit, qty_on_hand, stock_at_price_minor}]}`. Completed sales only, from the sale lines and their cost snapshots, by sale date (a business date). Totals are over the whole range, never over the row limit. The slow movers are items with stock on hand and no sale in the last `slow_days` days as of today in the tenant time zone; `no_sales` are active items with no sale in the range. Both lists carry quantities and the stock value at price, so they are present only with `retail.stock.read` as well, and are computed over the branches both permissions cover (a caller with `retail.sale.read` alone gets neither list, the keys absent). The seller is the user who recorded the sale. Margin is gross profit over sales in basis points, half up, computed on the server. Profit fields (`*`) and `profit_visible: true` need `retail.profit.read` in every branch the report covers (a per branch row shows its own profit where the permission covers that branch). The queries are in `scripts/db-bench/queries` (k01 to k04), each bounded by the date range, the branch scope and a row limit |
| GET | `/retail/reports/margins` | `retail.profit.read` | Margins of issue #149. `branch_id` (repeatable, scoped to `retail.profit.read`), `from`, `to` (default the last 30 days, at most 366), `top` (1 to 100, default 10), `target_bp` (0 to 10000, default 2000 = 20 percent). `{from, to, currency, top, target_bp, totals: {sales_minor, cost_minor, profit_minor, margin_bp}, by_product, by_category: [{id, code?, label, unit?, qty, sales_minor, cost_minor, profit_minor, margin_bp}], below_target: {items, total}, price_changes: {items: [{product_id, code, description, unit, changed_on, old_sell_minor, new_sell_minor, days_before, days_after, units_before, units_after, sales_before_minor, sales_after_minor, margin_before_bp, margin_after_bp}], total}}`. Cost and profit are the sale line snapshots, never the current cost. Margin is profit over sales in basis points, half up; absent when sales are not above zero. `by_product` and `by_category` are ordered by profit, `below_target` by margin lowest first (an item that sold for nothing at a cost is below any target); `total` counts before the `top` limit. A price change row is one item whose sell price changed in the range (from `retail_price_history`, the change dated in the tenant time zone): the old price is before the first change in the range, the new price after the last, and the split of the sales is the day of the last change (sales before it, then from it to the end of the range); an item whose net price did not change is left out. Queries k05 and k06 in `scripts/db-bench/queries` |
| GET | `/retail/reports/stock-health` | `retail.stock.read` | Stock health of issue #149. `branch_id` (repeatable, scoped to `retail.stock.read`), `from`, `to` (the range of the shrinkage section; default the last 30 days, at most 366), `top` (1 to 100, default 10), `lead_days` (1 to 365, default 14), `cover_days` (`lead_days` to 365, default 30). `{from, to, currency, top, velocity_days: 30, lead_days, cover_days, profit_visible, cover: {items, total}, reorder: {items, total}, dead_stock: {days: 90, branches: [{branch_id, item_count, value_at_price_minor, value_at_cost_minor*}], items: [{branch_id, product_id, code, description, unit, qty_on_hand, value_at_price_minor, value_at_cost_minor*}], items_total, value_at_cost_minor*, value_at_price_minor}, shrinkage: [{branch_id, used_reports, damaged_reports, short_lines, over_lines, used_cost_minor*, damaged_cost_minor*, stocktake_loss_minor*, stocktake_gain_minor*, net_cost_minor*}]}`. A cover row is `{branch_id, product_id, code, description, unit, qty_on_hand, sold_last30, avg_daily, days_of_cover, capped, suggested_qty?}`: velocity is the units sold in the branch in the last 30 days (completed sales), the average is that over 30, and `days_of_cover` is stock (negative counts as none) over the average to one place, shown as `365.0` with `capped` above a year. `cover` lists the lowest cover first over items sold in the window; `reorder` lists those whose cover is under `lead_days` (judged exactly, stock times 30 under `lead_days` times units sold), with `suggested_qty` the units to reach `cover_days` at that pace, rounded up to the thousandth, less the stock. Dead stock is stock above zero of an active item with no completed sale in that branch in the last 90 days, totalled per branch over every such item and listed by value at price (never ordered by cost). Shrinkage per branch in the range: usage reports by kind (`used`, `damaged`) and committed stock-take adjustments (shortages and surpluses valued at the cost snapshot of the movement). Two known differences, kept on purpose: the stock-take loss here is `round(abs(qty) * unit_cost)` per line, while the daily profit report reads the ledger `stock_shrinkage` postings, so the two can differ by one minor unit a line on fractional quantities; and dead stock counts an item restocked recently and not yet sold as dead, since it looks only at the last sale, not at when the stock arrived. Fields marked `*` need `retail.profit.read` in that branch (the overall dead stock cost in every branch reported); the counts stay. Queries k07 to k09 in `scripts/db-bench/queries` |
| GET | `/retail/reports/credit-control` | `retail.sale.read` | Credit control of issue #149; no cost or profit appears. `branch_id` (repeatable, scoped), `from`, `to` (the range of the payments section; default the last 30 days, at most 366), `top` (1 to 100, default 10), `sort` (`amount` or `age`, for the overdue list). `{from, to, as_of, currency, top, totals: Ageing, buyer_count, buyers: [{customer_id?, name, sale_count, ageing: Ageing, oldest_overdue_days?}], sort, overdue_count, overdue: [{sale_id, sale_no, branch_id, customer_id?, buyer_name, sale_date, due_date, days_overdue, total_minor, outstanding_minor}], payments: [{date, method, count, amount_minor}], payments_by_method: [{method, count, amount_minor}], payments_minor}`; `Ageing` is `{owed_minor, not_due_minor, days1_to30_minor, days31_to60_minor, days61_to90_minor, over90_minor}`. What is owed is as of today in the tenant time zone and uses the predicate of `GET /retail/sales?owing=`: a completed credit sale whose total is above its paid amount (overdue: due date before today); the days are counted from the due date and a sale with no due date counts as not yet due. A buyer is the credit buyer record, or the name typed in with the sale ignoring case. `totals` and `buyer_count` cover every buyer in scope whatever `top`; `buyers` list the most owed first; `overdue_count` is before the limit. Payments are those of completed sales in scope, dated by `paid_on`; a payment on account (issue #196) counts in the branch that received it; a reversed one stays on the day it was received and its reversal is a negative row (count and amount) on the day it was undone, as in the cash book, as the ledger and the cash book post it, and any other payment in the branch of its sale. While `customer_accounts` is on a buyer with a record and a limit carries `credit_limit_minor` and, only when the report covers every branch, `over_limit`. Queries k10 to k12 in `scripts/db-bench/queries`; the open debts, not a date range, bound k10 and k11 |
| GET | `/retail/reports/business-evaluation` | `retail.profit.read` | Business future evaluation of issue #149: formulas on today's prices and recent sales, **not a forecast** (the response carries `note` saying so). `branch_id` (repeatable, scoped to `retail.profit.read`), `from`, `to` (the period of the profit to date; default the last 30 days, at most 366), `top` (items per category, 1 to 100, default 10). `{from, to, currency, top, run_rate_days: 30, note, total: {period, stock, run_rate}, branches: [{branch_id, period, stock, run_rate, categories: [{category_id, category, period, stock, products_total, products: [{product_id, code, description, unit, qty_on_hand, period, stock}]}]}]}`. `period` is `{sales_minor, profit_minor, margin_bp?}` from the sale line cost snapshots (gross profit: usage, damage and stock-take differences are in the daily profit report). `stock` is the stock on hand above zero at the current prices, each row rounded half up to the minor unit: `{at_cost_minor, at_price_minor, expected_profit_minor (price less cost), over_cost_bp? (expected profit over cost, half up; absent when cost is zero)}`. `run_rate` is the last 30 days whatever the period: `{sales_minor, profit_minor, avg_daily_sales_minor, avg_daily_profit_minor (over 30, half up), days_of_stock? (stock at price over the daily sales, one place; absent when nothing sold), days_of_stock_capped}`. Categories and branch totals cover every item; only the item lists are limited by `top` (largest expected profit first). Query k13 in `scripts/db-bench/queries` |
| GET | `/retail/reports/dashboard` | `retail.sale.read` | The owner dashboard of the retail home (issue #149). `branch_id` (repeatable, scoped to `retail.sale.read`). `{date, currency, profit_visible, today: {sales_minor, sale_count, cash_minor, credit_minor, gross_profit_minor*}, days: [{date, sales_minor, gross_profit_minor*}] (the last 30 days ending today, one a day, zero filled, oldest first), last7_sales_minor, last30_sales_minor, last7_gross_profit_minor*, last30_gross_profit_minor*, stock**: {value_at_price_minor, value_at_cost_minor*, out_of_stock, low_stock, low_stock_threshold}, shops: [{branch_id, today_sales_minor?, last7_sales_minor?, last30_sales_minor?, today_gross_profit_minor*, stock_at_price_minor**, stock_at_cost_minor*, out_of_stock**, low_stock**}]}`. Today is the business date in the tenant time zone; `cash_minor` is sales paid at the sale (cash, mobile money, bank) and `credit_minor` sales on credit, from the lines of completed sales. Fields marked `*` need `retail.profit.read` (the totals in every branch reported, a shop's own in that shop); `**` need `retail.stock.read` (in that shop). `out_of_stock` and `low_stock` follow the stock list rule: every active item (and any inactive one still holding a quantity) is judged on its balance, an item with no balance row counting as zero; out is zero or less and low is at or below the threshold of ADR-029 (the value the stock lists report as `low_stock_threshold`), so low includes out of stock, as the Low tab does. A shop's counts are judged on that branch; the overall counts judge each item on its balance summed over the branches reported, as All branches does, so they equal `total` of `GET /retail/stock?stock_level=out|low` for one branch or for All branches. A shop in the caller's stock scope but not their sales scope carries no `today_sales_minor`, `last7_sales_minor` or `last30_sales_minor` (absent, not zero). The banked against expected and savings figures come with the cash book and are not here. Queries k14 and k15 in `scripts/db-bench/queries`; k15 has no date, it reads the current balances of the branches in scope |

The analytics reports of issue #149 (`sales-analysis`, `margins`, `stock-health`, `credit-control`, `business-evaluation`,
`dashboard`; reports only, no migration) share these rules. Every list is limited by `top` (1 to 100, default 10), except `payments` of `credit-control`, which is one row a day and way of paying in the range (at most 366 days) and so bounded by the range; the
totals of a report never depend on that limit; a list says how many rows it had before the limit (`total` or `..._total`).
A range is `from` and `to` (both ends counted, the default the last 30 days, at most 366, 422 otherwise) in business dates
of the tenant time zone; a window counted back from today (30 days of velocity, 90 of dead stock, the slow movers) is
fixed or a parameter as each row says. Every statement is bounded by the range or window, by the caller's branch scope and
by a row limit, and is among the hot paths measured in `scripts/db-bench/queries` (k01 to k15); the exceptions are the open
debts of `credit-control` (bounded by the predicate that the debt is open) and the current stock position (bounded by the
branches in scope), each said in its row. Percentages are basis points computed on the server with `BigDecimal`, half up,
and absent (the key left out, never `null` or 0) when the base is not above zero; quantities are decimal strings; no float is used. A figure that is cost or profit
(`*`, or the whole route for `margins` and `business-evaluation`) is absent from the body, never null or zero, unless
`retail.profit.read` covers every branch the figure sums (per branch rows: that branch); a report that reads no profit
writes no audit row, and a refused call carries no figure.

Built (#84, stock transfers between branches; FR-RET-16):

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/retail/transfers` | `retail.stock.transfer` | **M**. `{from_branch_id?, to_branch_id, transfer_date?, note?, lines: [{product_id, qty}]}`. The source is `from_branch_id`, or the caller's one branch, and must be in the permission's scope (422 `unknown_branch` or `branch_required` on `from_branch_id`); the destination is any active branch of the tenant other than the source (422 `same_branch` or `unknown_branch` on `to_branch_id`). `transfer_date` defaults to today in the tenant's zone and may not be in the future; one line per product (422 `duplicate`); quantities positive with at most three places. 422 `insufficient_stock` when a line exceeds the source's balance (always; ADR-020 decision 4); nothing is written. 201 with `Location`: `{id, from_branch_id, to_branch_id, transfer_date, note, status, currency, cost_total_minor*, lines: [{line_no, product_id, code, description, qty, unit_cost_minor*, line_cost_minor*}], created_at, created_by, voided_at, voided_by, void_reason, received_at, received_by, shortage_cost_minor*}`; a line also carries `received_qty` once received. With the `transfer_receipt` capability on, the same call is a dispatch: `status` is `in_transit`, only the source's movement and entry are written, and the destination's follow at the receipt (FR-RET-40, issue #193) |
| GET | `/retail/transfers` | `retail.stock.read` | `branch_id` (repeatable, scoped; a transfer is listed when its source or its destination is one of them), `product_id`, `status` (`completed`, `in_transit`, `received`, `cancelled`, `voided`; 422 otherwise), `from`, `to` (on `transfer_date`), `limit`, `cursor`; newest first |
| GET | `/retail/transfers/{transfer_id}` | `retail.stock.read` | 404 unless the caller may read the source or the destination branch |
| POST | `/retail/transfers/{transfer_id}/void` | `retail.stock.transfer` | `{reason}`; scope checked on the source branch (404 otherwise). Moves every line back at the transfer's cost and reverses both entries, dated today. 409 `transfer_voided`; 422 `transfer_stock_moved` when the destination no longer holds a line's quantity (sold, used or moved on since), naming the destination and the product codes; nothing is written. For a received transfer the rule applies to what was received, the dispatched quantity returns to the source and the shortage entry is reversed with the others. A transfer still on its way is 409 `transfer_in_transit` (cancel it instead) and a cancelled one 409 `transfer_cancelled` |
| POST | `/retail/transfers/{transfer_id}/receive` | `retail.stock.transfer` | **M**. Capability `transfer_receipt` (404 `capability_not_enabled` otherwise). `{lines?: [{product_id, qty}], note?}`; omit `lines` to receive everything as dispatched, otherwise name every line of the transfer once (422 `incomplete`, `unknown_product`, `duplicate`), `qty` from 0 to the dispatched quantity (422 `received_exceeds_dispatched` above it). Scope is checked on the **destination** branch (404 otherwise). Writes a `transfer_in` movement for what arrived, one entry at the destination (debit inventory, credit clearing) and, for a shortage, one shrinkage entry at the destination valued at the source cost. 200 with the transfer (`status` `received`); 409 `transfer_not_in_transit` when it is not on its way |
| POST | `/retail/transfers/{transfer_id}/cancel` | `retail.stock.transfer` | **M**. Capability `transfer_receipt`. `{reason}`; scope on the **source** branch (404 otherwise). Returns every line to the source at the original cost (a `transfer_in` that reverses the dispatch movement, `source_type` `retail.transfer_cancel`) and reverses the source entry. 200 with the transfer (`status` `cancelled`); 409 `transfer_not_in_transit` |
| GET | `/retail/reports/stock-by-location` | `retail.stock.read` | `query`, `category_id`, `negative_only`, `limit`, `cursor` (by product code). `{currency, branches: [{id, code, name, head_office, role}] (stores, then shops, then both; every branch is `both` while `branch_roles` is off), items: [{product_id, code, description, category, unit, balances: [{branch_id, qty, negative, value_minor*}] (in the order of `branches`), subtotals: [{role, qty}], in_transit_qty, combined_qty, negative, sell_minor, cost_minor*, value_minor*, in_transit_value_minor*, combined_value_minor*}], totals: {by_branch: [{branch_id, value_minor}], by_role: [{role, value_minor}], value_minor*, in_transit_value_minor*, combined_value_minor*, inventory_ledger_minor*, revaluation_difference_minor*}, next_cursor}`. Quantities in transit are in neither branch's balance, so `combined_qty` is the balances plus `in_transit_qty` and counts nothing twice. `*` shows only with `retail.profit.read` in every branch of the report; a per-branch value shows where that permission covers the branch, and `totals` cover every product, not the page. Always available; the role grouping and the transit column are trivial while their capabilities are off |
| GET | `/retail/reports/transfers-in-flight` | `retail.stock.read` | Capability `transfer_receipt`. `{currency, items: [{id, from_branch_id, to_branch_id, transfer_date, dispatched_at, age_days, note, lines, cost_total_minor*}] (open dispatches from or to a branch in scope, the longest wait first), in_transit_value_minor*, clearing_minor*, reconciled*}`. The value is the sum of the open dispatches, built from the transfers and never read from account 1190 (shared with lending). For a caller who reads every branch with cost, `clearing_minor` is the net of inter-branch clearing over the retail transfer entries only and `reconciled` says it equals the value of every open dispatch |

For transfers, the `*` fields show when the caller holds `retail.profit.read` in the source or the
destination branch: the same cost is posted to both branches' inventory accounts. Both branches'
`/retail/stock/movements` show the lines as `transfer_out` and `transfer_in` with `source_type`
`retail.transfer` and `source_id` the transfer; a void adds the opposite kinds with `source_type`
`retail.transfer_void` and `reverses_movement_id` set.

### 7.11.21 Retail cash book (`/retail`, ADR-022, issue #147)

**Built** (issue #147; `docs/api/openapi.json` is generated from it). Not built: `format=csv` on the reports, because the report runs of section 7.11.8 do not exist yet.
Refused with 404 `module_not_enabled` unless retail is switched on. Conventions are those of section
7.11.20: snake_case, branch scope on every row (ADR-017, 404 outside scope), `*` fields absent (not
null) without `retail.profit.read` (for the cash book that includes every savings amount, the cash restock total `cash_purchases_minor` and every figure net of them, ADR-022 decision 13), **M** requires `Idempotency-Key` (section 7.8). A request for one
branch takes `branch_id`, or the caller's one branch when the permission's scope has exactly one;
otherwise 422 `branch_required`. Dates are business dates in the tenant's zone, default today, never in
the future (a record posts on its own `business_date`, which may be earlier than its `created_at`, and a void posts on the day it is made, shown as a `*_voids_minor` line on the void day and never rewriting the record's day, ADR-022 decision 9): a future date is a field problem, as in a transfer's `transfer_date`: 422 `validation_failed` with an `errors` entry `{field: business_date, code: future_date}` (the field is `paid_on`, `banked_at` or `withdrawn_at` on those routes). Every record in a response carries `by`, `at`, `voided`, `historical`.
Every void takes `{reason}` (at most 300 characters), is **M**, needs `retail.cashbook.void` in the record's branch (404 outside scope), and is 409 `cash_record_voided` the
second time (the `<thing>_voided` style of `sale_voided` and `transfer_voided`). A record imported from the pilot (`historical`) cannot be voided: 409 `historical_record` ("Imported records cannot be voided; correct them with a new record"), because imported rows have no journal to reverse; the same holds for an imported advance and an imported repayment.

Lists and the expense setup (FR-RET-17):

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/retail/expense-categories` | `retail.cashbook.read` | `{items: [{id, name, expense_account_id, active, items: [{id, name, requires_explanation, active}]}]}`; `active` filter |
| POST | `/retail/expense-categories` | `retail.expense.manage` | `{name, expense_account_id?}`; 409 `duplicate_category`; `expense_account_id` must be a postable expense account (422 `account_not_expense`) |
| PATCH | `/retail/expense-categories/{category_id}` | `retail.expense.manage` | `{name?, expense_account_id?, active?, sort_order?}`; requires `If-Match` (428 without, 409 `version_conflict` on a stale version); 409 `duplicate_category` |
| POST | `/retail/expense-categories/{category_id}/items` | `retail.expense.manage` | `{name, requires_explanation?}`; 409 `duplicate_item` |
| PATCH | `/retail/expense-categories/{category_id}/items/{item_id}` | `retail.expense.manage` | `{name?, requires_explanation?, active?}`; requires `If-Match` (428 without, 409 `version_conflict` on a stale version); 409 `duplicate_item` |
| GET | `/retail/cash-parties` | `retail.cashbook.read` | `query`, `kind`, `limit` (default 50, at most 200), `cursor` |
| POST | `/retail/cash-parties` | `retail.expense.record` (the route declares one permission); a party of kind `owner`, `staff` or `related_entity` also needs `retail.expense.manage` (403 `permission_denied` without it), while a beneficiary of kind `supplier` or `other` may be added on the fly with `retail.expense.record` alone | `{name, contact?, kind}`; `kind` one of `owner`, `staff`, `related_entity`, `supplier`, `other`; 409 `duplicate_party`; 422 `party_kind_not_allowed` when an advance names a `supplier` or `other` party |

Daily savings (FR-RET-18 to FR-RET-20):

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/retail/savings/suggestion` | `retail.savings.record` | `branch_id?`, `date?`. `{branch_id, business_date, total_sold_minor, suggestion_token, suggested_minor*, daily_profit_minor*, existing_id}`; `suggestion_token` is an opaque keyed hash of the suggestion (it reveals nothing, so every caller gets it); `suggested_minor` and the profit are `*`, absent without `retail.profit.read`; `existing_id` is the active record for the day, if any |
| POST | `/retail/savings` | `retail.savings.record` | **M**. `{branch_id?, business_date?, suggestion_token, amount_minor?, overwrite_reason?}`; `suggestion_token` is the one the form was shown (422 `suggestion_token_required` when missing); an omitted amount takes the suggestion; a caller without `retail.profit.read` may not send `amount_minor` or `overwrite_reason` at all (422 `amount_requires_profit_access`, "only the default can be recorded without profit access"; the default is applied server-side, so no accepted-or-refused answer reveals the suggestion); a caller with `retail.profit.read` sending an amount differing from the suggestion the token stands for needs `retail.savings.overwrite` (403) and a reason (422 `reason_required`); if the suggestion has changed since the token was issued the answer is 409 `suggestion_changed` with the new `suggestion_token` (and `suggested_minor*`) and nothing is written, never a false overwrite; 409 `savings_exists`. The response echoes `amount_minor` to the writer only when the writer typed it; a default taken by a caller without `retail.profit.read` is not echoed (`amount_minor*`) |
| GET | `/retail/savings` | `retail.cashbook.read` | `branch_id` (repeatable), `from`, `to`, `include_voided`, `limit` (default 50, at most 200), `cursor`; row `{id, branch_id, business_date, amount_minor*, total_sold_minor, overwritten*, suggested_minor*, by, at}` (a savings amount is profit-derived, ADR-022 decision 13, so without `retail.profit.read` the row shows that savings were recorded but not how much); grouped client-side by shop, year and month |
| POST | `/retail/savings/{savings_id}/void` | `retail.cashbook.void` | Reverses the entry and frees the day |

Cash banked (FR-RET-21 to FR-RET-23):

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/retail/bankings/expected` | `retail.banking.record` | `branch_id?`, `date?`. `{branch_id, business_date, cash_takings_minor, cash_sale_voids_minor, expense_voids_minor, advance_voids_minor, repayment_voids_minor, cash_purchases_minor*, savings_minor*, savings_voids_minor*, expenses_minor, advances_out_minor, repayments_in_minor, cash_expected_minor, expected_minor*, banked_so_far_minor}`; `cash_expected_minor` is takings, less cash sale voids, less expenses and advances paid out, plus repayments (with the expense, advance and repayment voids of the day), **before cash purchases and before savings**, and is what a caller without `retail.profit.read` is shown; `cash_purchases_minor` (a total at cost), `savings_minor` and `expected_minor` (net of both) are `*`; the form shows it and prefills with `expected_minor` (or `cash_expected_minor` when that is all the caller may see, which for a cashier is the takings figure less their own till payments) |
| POST | `/retail/bankings` | `retail.banking.record` | **M**. `{branch_id?, business_date?, amount_minor, banked_at?, reference?}`; stores `expected_minor`; response adds `difference_minor*`, `flag*` and `warnings*: [cash_below_banked?]` (gated like the other balance figures: cash on hand is net of savings and cash purchases, so the warning is absent without `retail.profit.read`; `bank_balance_negative` on withdrawals is not affected); stored `expected_minor*` is profit-gated too; never refused for exceeding cash |
| GET | `/retail/bankings` | `retail.cashbook.read` | `branch_id`, `from`, `to`, `include_voided`, `limit` (default 50, at most 200), `cursor` |
| POST | `/retail/bankings/{banking_id}/void` | `retail.cashbook.void` | |
| POST | `/retail/withdrawals` | `retail.withdrawal.record` | **M**. `{branch_id?, business_date?, amount_minor, withdrawn_at?, purpose?}`; response may carry `warnings: [bank_balance_negative]` |
| GET | `/retail/withdrawals` | `retail.cashbook.read` | Same filters, with `limit` (default 50, at most 200) and `cursor` |
| POST | `/retail/withdrawals/{withdrawal_id}/void` | `retail.cashbook.void` | |

Expenses (FR-RET-24):

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/retail/expenses` | `retail.expense.record` | **M**. `{branch_id?, business_date?, category_id, item_id, party_id?, amount_minor, explanation?, receipt_document_id?}` (a receipt document must have been uploaded for an expense: subject type `retail.expense_receipt`; any other tenant document, a member's file included, is 422 `unknown_document`); 422 `item_not_in_category`, `explanation_required`, `category_inactive` |
| GET | `/retail/expenses` | `retail.cashbook.read` | `branch_id`, `from`, `to`, `category_id`, `item_id`, `include_voided`, `limit`, `cursor` |
| POST | `/retail/expenses/{expense_id}/void` | `retail.cashbook.void` | |

Advances and repayments (FR-RET-26):

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/retail/advances` | `retail.advance.create` | **M**. `{branch_id?, business_date?, party_id, taken_by_party_id?, principal_minor, purpose?}` (`taken_by_party_id` must also be an `owner`, `staff` or `related_entity` party, else 422 `party_kind_not_allowed`); response has `advance_no` |
| GET | `/retail/advances` | `retail.cashbook.read` | `branch_id`, `party_id`, `open_only` (balance above zero, what the repayment form lists), `from`, `to`, `limit` (default 50, at most 200), `cursor`; row adds `repaid_minor`, `balance_minor` |
| GET | `/retail/advances/{advance_id}` | `retail.cashbook.read` | With its repayments |
| POST | `/retail/advances/{advance_id}/repayments` | `retail.advance.repay` | **M**. `{branch_id?, amount_minor, method: cash, mobile_money or bank, paid_on?}` (`branch_id` is the branch that receives the money, scoped, default the advance's branch); 422 `repayment_exceeds_balance`, `advance_settled`; returns the new balance |
| POST | `/retail/advances/{advance_id}/void` | `retail.cashbook.void` | 409 `advance_has_repayments` |
| POST | `/retail/advances/{advance_id}/repayments/{repayment_id}/void` | `retail.cashbook.void` | |

Reports (FR-RET-23, FR-RET-27, FR-RET-29), all `branch_id` (repeatable, scoped), `from`, `to` (default
the last 30 days, at most 366), also `format=csv` through the report runs of section 7.11.8:

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/retail/reports/cash/daily` | `retail.cashbook.read` | Per branch and day: `cash_takings_minor`, `cash_sale_voids_minor`, `expense_voids_minor`, `advance_voids_minor`, `repayment_voids_minor`, `banking_voids_minor`, `withdrawal_voids_minor`, `cash_purchases_minor*`, `expenses_minor`, `advances_out_minor`, `repayments_in_minor`, `withdrawals_in_minor`, `banked_minor`, `cash_expected_minor`, `ledger_basis`, `historical` (an imported day: no running total, see below), and the savings-embedding figures `opening_minor*`, `savings_minor*`, `savings_voids_minor*`, `closing_minor*`, `other_movements_minor*`, `expected_to_bank_minor*`, `unbanked_running_minor*`; `daily_profit_minor*` |
| GET | `/retail/reports/cash/banking` | `retail.cashbook.read` | Per branch and day: `cash_expected_minor`, `banked_minor`, and `expected_minor*`, `difference_minor*`, `flag*` (`ok`, `shortfall`, `surplus`, `not_banked`), `unbanked_running_minor*` (all net of savings, so profit-gated), `entries: [{id, amount_minor, banked_at, by}]`; filter `flag`; an imported (historical) day has `historical: true` and no `unbanked_running_minor`, and does not count in the running total of the live days, which starts at the first live day |
| GET | `/retail/reports/cash/expenses` | `retail.cashbook.read` | `group_by` (`category`, `item`, `branch`, `month`), totals, count; no voided rows |
| GET | `/retail/reports/cash/savings` | `retail.cashbook.read` | Per branch and day `total_sold_minor`; `overwritten*`, `amount_minor*`, `suggested_minor*`, `daily_profit_minor*` |
| GET | `/retail/reports/cash/advances` | `retail.cashbook.read` | Outstanding by party: principal, repaid, balance, oldest advance date |

Error codes: the cash book's codes are listed in section 7.7 (409 `cash_record_voided`, `savings_exists`,
`suggestion_changed`, `advance_has_repayments`, `historical_record`, `duplicate_category`, `duplicate_item`,
`duplicate_party`; 422 `reason_required`, `explanation_required`, `item_not_in_category`,
`category_inactive`, `account_not_expense`, `party_kind_not_allowed`, `suggestion_token_required`, `amount_requires_profit_access`,
`repayment_exceeds_balance`, `advance_settled`, `branch_required`; the field code `future_date`). The cash book has no import endpoint; its history comes through the
`import-retail` command (chapter 13 section 13.13).

### 7.11.23 Retail trade capabilities (`/retail`, ADR-033, issue #190)

Built (issue #191): `GET` and `PUT /retail/settings/capabilities` (`core.settings.manage`; the body is
the profile and the eight flags, `PUT` needs `If-Match` with the settings version as `PATCH /settings`
does (the `ETag` is returned by `GET`), a change is partial, choosing a profile writes its preset
first, the change is audited as `retail.capabilities.changed` with old and new values; an unknown key
is 400 and a wrong value 422); `GET /retail/branch-roles` (`retail.stock.read`; the active branches in
the caller's scope with `role` and `can_sell`) and `PUT /retail/branch-roles/{branch_id}` (body
`{role}`, `retail.branch_role.manage`, audited as `retail.branch_role.changed`, 404 outside the
caller's scope, 422 `branch_has_open_work` while a dispatched transfer is unreceived), both 404
`capability_not_enabled` until `branch_roles` is on. `POST /retail/sales` answers 422
`branch_role_forbids_sales` for a store. Built (issue #193): the two-step transfer, whose routes
(`/receive`, `/cancel`, the `status` filter) and the reports `stock-by-location` and
`transfers-in-flight` are in section 7.11.20 above; a capability change that switches
`transfer_receipt` off while a transfer is on its way is 422 `transfers_in_transit`.

Built (issue #194, units of measure): the three pack routes above, the optional `unit_id` on a sale line and a restock line, the pack fields on their responses, and the `pack unit` and `pack factor` import columns. New error codes: 422 `quantity_not_representable`, `unknown_pack_unit` (a field problem), `pack_unit_is_base_unit`, `unit_in_use_by_pack`; 409 `duplicate_pack`. Built (issue #195, price lists, migration V41): the routes below and the optional `price_list_id` on a sale and `price_override_reason` on a sale line; a sale line and a sale carry how they were priced. All of them are 404 `capability_not_enabled` while `price_lists` is off. Reading a list, its items or the groups needs `retail.pricelist.manage` as well as writing: a list is a customer's negotiated price, so a seller never lists them and asks `POST /retail/prices/resolve` (`retail.sale.create`) for the price it needs. Money is integer minor units per the item's unit; the cost of an item never leaves, only `below_cost` and only with `retail.profit.read`. New error codes: 422 `no_price_in_list`, `override_reason_required`, `unknown_price_list` (a field problem), `unit_in_use_by_price_list`; 409 `price_item_overlap`, `duplicate_price_list`, `duplicate_customer_group`; 422 `price_item_closed`; 403 `permission_denied` for a price override or a foreign list without the permission. Design only, not built: the rest of this section.

Built (issue #196, customer accounts, migration V44): the routes below, the optional `credit_override_reason` on a sale, and the optional `credit_limit_minor` and `terms_days` on a buyer in the buyer list, and `historical` on an open sale of the balance, which appear only while `customer_accounts` is on. Every route is 404 `capability_not_enabled` while it is off. Money is integer minor units; nothing here carries cost or profit. New error codes: 422 `customer_required`, `credit_limit_exceeded` (with `exceeded_by_minor`, and nothing of any other branch's sales), `override_reason_required` (with `exceeded_by_minor`), `payment_exceeds_allocatable` (with `allocatable_minor`), `allocations_must_equal_amount`, `payment_not_reversible`, `too_many_sales`, `statement_too_long`; 409 `receipt_reversed`; field problems `sale_historical`, `unknown_sale`, `duplicate` and `payment_exceeds_balance` on an allocation.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/retail/customers/{customer_id}/account` | `retail.sale.read` | `{customer_id, name, currency, credit_limit_minor?, terms_days?, balance_minor, exposure_minor?, available_minor?, over_limit?, version}` with an `ETag`; `balance_minor` is the caller's branch scope as `GET .../balance`; `exposure_minor` (what the limit is measured against, every branch), `available_minor` and `over_limit` only when the caller's scope covers every branch, so no other branch's sales leak |
| PUT | `/retail/customers/{customer_id}/account` | `retail.credit.manage` | `{credit_limit_minor?, clear_credit_limit?, terms_days?, clear_terms_days?}` replaces both under `If-Match`; each of the two is a value or its clear flag set to true, never neither and never both (422 on `credit_limit_minor` or `terms_days`), so `{}` changes nothing (428, 409 `version_conflict`); the sales role's `retail.customer.manage` edits name and contact only. Audited as `retail.customer.account_changed` with old and new values |
| GET | `/retail/customers/{customer_id}/statement` | `retail.sale.read` | `from`, `to` (default the last 90 days ending today in the business time zone; at most 731 days; at most 2000 lines): `{from, to, opening_minor, lines: [{date, kind, reference, sale_id?, receipt_id?, method?, debit_minor, credit_minor, balance_minor}], closing_minor, before_system_start?: {sales, total_minor}, balance_minor}`. `kind` is `sale`, `payment`, `account_payment` (one line for a receipt's allocations in scope), `reversal` or `void`. Built from sales, sale payments and receipts in the caller's branch scope: `closing_minor` is what the ledger holds as trade debtors for those sales, and `balance_minor` adds the imported sales listed apart under `before_system_start`, so it equals `GET .../balance` when `to` is today or later |
| POST (M) | `/retail/customers/{customer_id}/payments` | `retail.sale.create` | `{branch_id?, amount_minor, method (cash, mobile_money, bank), paid_on?, allocations?: [{sale_id, amount_minor}]}`; `branch_id` is the branch that received the money, in the caller's scope. Without `allocations` the amount is allocated oldest first to the buyer's open credit sales in the caller's scope; imported sales and sales dated after the payment are skipped; what cannot be allocated is 422 `payment_exceeds_allocatable`. With `allocations` exactly those, adding up to the amount. 201 `{id, receipt_no, branch_id, amount_minor, method, paid_on, allocation, status, allocations: [{sale_id, sale_no, branch_id, amount_minor, sale_payment_id}], journal_entry_id, ...}`; one ledger entry in the receiving branch (debit cash, mobile money or bank, credit trade debtors, each sale as the subledger of its own leg); audited as `retail.customer.account_payment_recorded` |
| GET | `/retail/customers/{customer_id}/payments` | `retail.sale.read` | Receipts newest first (`limit`, `cursor`). A caller whose scope covers every branch sees every receipt whole. A scoped caller sees a receipt when it paid a sale of a branch in their scope, with only those allocations and `amount_minor` the sum of them, so the list agrees line by line with the statement (which counts a payment in the branch of the sale it paid); the receiving branch is where the money was banked, and the ledger and the cash book use it, not this view |
| POST (M) | `/retail/customers/{customer_id}/payments/{payment_id}/reverse` | `retail.sale.void` at the receiving branch | `{reason}` (at least 5 characters once trimmed, at most 300; 422 on `reason` otherwise): reverses the receipt as a whole, once: the entry is reversed in the receiving branch on today's business date and each sale gets its paid amount back; 409 `receipt_reversed` the second time. To allocate the money differently, reverse it and take it again with the sales chosen. Audited as `retail.customer.account_payment_reversed` |
| GET | `/retail/reports/customer-ageing` | `retail.sale.read` | `branch_id`, `top` (default 10, at most 100): `{as_of, currency, totals, customer_count, customers: [{customer_id, name, credit_limit_minor?, terms_days?, sale_count, ageing, oldest_overdue_days?, over_limit?}]}`; `ageing` is the open balance of completed credit sales (imported ones included) as not yet due, 1 to 30, 31 to 60, 61 to 90 and over 90 days past the due date on the business day (a sale with no due date is not yet due); `over_limit` only for a caller whose scope covers every branch. The staff screen is Customer ageing (`/staff/retail/customer-ageing`) |

`POST /retail/sales` changes while `customer_accounts` is on: a credit sale must name a `customer_id` (422 `customer_required`; a `buyer_name` alone is refused); the buyer's row is locked (`SELECT ... FOR UPDATE`) before any stock balance, then exposure (the tenant-wide balance of completed credit sales, imported ones included, plus this sale) is compared with the limit: equal is allowed, above is 422 `credit_limit_exceeded` with `exceeded_by_minor` unless the user holds `retail.credit.override` at the sale's branch, who must give a `credit_override_reason` of at least five characters (422 `override_reason_required` without one); the override is written to the audit trail as `retail.credit.limit_overridden` and in the sale's own entry. With no `due_date` the sale is dated `sale_date + terms_days` when the buyer has terms. `GET /retail/credit-control` gives each buyer with a record `credit_limit_minor` while the capability is on. While it is off every one of these is ignored and no response carries a new field.

Built (issue #201, customer returns, migration V45): the routes below. Every one is 404 `capability_not_enabled` while `returns` is off, needs `retail.sale.return` (administrators) and is scoped to the sale's branch (404 outside it); `M` takes an `Idempotency-Key`. Cost fields (`cost_minor`, `unit_cost_minor`, `line_cost_minor`) are present only with `retail.profit.read` at the branch. New error codes: 422 `return_exceeds_sold`, `account_refund_unavailable`, `account_refund_historical`, `refund_remainder_method_required`, `refund_exceeds_paid`, `quantity_not_representable`, `sale_has_returns` (on `POST /retail/sales/{id}/void`), `return_stock_moved` (a void when the goods it put back have been sold since) and `payment_not_reversible` (a payment on account whose sale has money refunded on a return); 409 `sale_voided` (a return against a voided sale), `return_voided` and `approval_already_pending`.

| Method | Path | Notes |
|---|---|---|
| POST (M) | `/retail/sales/{sale_id}/returns` | `{lines: [{sale_line_id, qty (three places), unit_id? (the line's unit, the base unit or an active pack), reason, disposition (restock or write_off), restock_branch_id?}], refund_method (cash, mobile_money, bank, account), money_method?, refund_branch_id? (the till money is paid out of, inside the caller's scope), note?}`. 201 `{executed: true, sale_return}`; 202 `{executed: false, approval_id}` when the returns of the sale that were not voided, with this one, reach the tenant's approval threshold for `retail_sale_return` (maker-checker, section 8.4; the amount shown is that total). A line may name the unit of the sale line, the base unit or a pack unit; the quantity must convert exactly. Two lines may name one sale line; together they cannot exceed what was sold less what came back |
| GET | `/retail/sales/{sale_id}/returns` | The returns of the sale, oldest first, voided ones included, each with its lines |
| GET | `/retail/returns` | `branch_id` (repeatable, scoped), `from`, `to` (on the day of the return), `customer_id`, `sale_id`, `q` (credit note number, sale number, buyer name; the shared search, the term never in the address), `status`, `limit`, `cursor`: `{items, next_cursor}` newest first |
| GET | `/retail/returns/{return_id}` | One return, the credit note: `{id, credit_note_no, sale_id, sale_no, branch_id, customer_id?, customer_name?, returned_on, currency, total_minor, account_credit_minor, refund_minor, refund_method?, refund_choice, note?, status, lines: [{id, line_no, sale_line_id, product_id, code, description, qty, unit_id?, unit?, unit_qty?, line_total_minor, reason, disposition, restock_branch_id?}], cost_minor*, approval_id?, created_at, created_by, approved_by?, voided_*, version}` |
| POST (M) | `/retail/returns/{return_id}/void` | `{reason}` (5 to 300 characters): 200 `{executed: true, sale_return}` after reversing every entry on today's business date, taking the goods it restocked out again and putting the account credit back on the sale; once (409 `return_voided`). 202 `{executed: false, approval_id}` (action `retail_sale_return_void`) when the tenant has a threshold and the return was approved or is worth it alone |

Other routes change only through what they read: the customer statement lists a return of a credit sale as `return` (a credit, the account credit only) and its void as `return_void`; the daily profit, the sales analysis, the margins, the dashboard and the business evaluation net returns on the day of the return (a line written off gives back its credit and not its cost); the cash book counts cash refunded on the return's day with the day's voids (`cash_sale_voids_minor`) and the cash of a voided return as takings on the day of the void. They keep counting a return after the capability is switched off, because the books do.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/retail/price-lists` | `retail.pricelist.manage` | `{items: [{id, name, currency, strict, active, current_items, version}]}`; `current_items` counts prices in force or coming |
| POST | `/retail/price-lists` | `retail.pricelist.manage` | `{name, strict?}`; 201 with the list and its `ETag`; 409 `duplicate_price_list`; the currency is the tenant's. Audited as `retail.price_list.created` |
| GET, PATCH | `/retail/price-lists/{price_list_id}` | `retail.pricelist.manage` | PATCH `{name?, strict?, active?}` under `If-Match` (428, 409 `version_conflict`); a list is never deleted. Audited as `retail.price_list.updated` |
| GET | `/retail/price-lists/{price_list_id}/items` | `retail.pricelist.manage` | `product_id`, `query` (item code or description), `include_ended`, `limit`, `cursor`; by item description; `{id, price_list_id, product_id, code, description, unit_id, unit, min_qty, price_minor, valid_from, valid_to?, status (scheduled, current, ended), below_cost*}` where `*` needs `retail.profit.read` |
| POST | `/retail/price-lists/{price_list_id}/items` | `retail.pricelist.manage` | `{product_id, unit_id, min_qty?, price_minor, valid_from?, valid_to?}`; `unit_id` is the item's base unit or an active pack (422 `unknown_pack_unit`); `valid_from` defaults to today and is never in the past (422); an open ended item takes over from the current open ended one of the same product, unit and break by closing it the day before (a new item that starts on or before the old one's first day is 409 `price_item_overlap`); any other overlap is 409 `price_item_overlap`; a price at or below cost can be saved (`below_cost`). Audited as `retail.price_list_item.created` |
| PATCH | `/retail/price-lists/{price_list_id}/items/{item_id}` | `retail.pricelist.manage` | `{valid_to}`: ends an open item on a day not before today or its first day (422); 422 `price_item_closed` when it has an end already. The only change an item takes. Audited as `retail.price_list_item.ended` |
| GET, POST | `/retail/customer-groups` | `retail.pricelist.manage` | `{name, price_list_id?}`; `{id, name, price_list_id?, price_list?, active, customer_count, version}` |
| PUT | `/retail/customer-groups/{group_id}` | `retail.pricelist.manage` | `{name, price_list_id?, active}` replaces the group under `If-Match`; an absent `price_list_id` clears it; never deleted |
| PUT | `/retail/customers/{customer_id}/pricing` | `retail.pricelist.manage` | `{customer_group_id?, price_list_id?}` replaces the buyer's group and own list under `If-Match`; an absent field clears it. A customer response carries `customer_group_id` and `price_list_id` only while the capability is on. Audited as `retail.customer.pricing_changed` |
| POST | `/retail/prices/resolve` | `retail.sale.create` | `{branch_id?, customer_id?, price_list_id?, sale_date?, lines: [{product_id, unit_id?, qty}]}` returns `{price_list_id?, price_list?, origin? (request, customer, customer_group), lines: [{product_id, unit_id?, qty, unit_price_minor, line_total_minor, price_source (retail, list), price_list_id?, break_min_qty?, problem?}]}` as the sale would take them; a strict list with no price for a line reports `problem: no_price_in_list` instead of refusing; it makes no sale and never returns a cost |

`POST /retail/sales` changes while `price_lists` is on: the sale may carry `price_list_id` (a list other than the customer's own or the customer group's needs `retail.pricelist.use`, else 403; an unknown or switched-off list is a field problem), the server resolves every line (the sale's list, else the customer's own, else the customer group's, passing over a list that is switched off; in the list the item for the line's unit with the largest `min_qty` not above the quantity, valid on the sale date; else the retail price of that unit; 422 `no_price_in_list` on a strict list), and a `unit_price_minor` that differs from the resolved price is an override: 403 `permission_denied` without `retail.price.override`, 422 `override_reason_required` without a `price_override_reason` of at least five characters. The same price typed back is not an override. Every override is written to the audit entry of the sale (`price_overrides`: line, product, list, resolved price, price used, reason). The line stores and returns `price: {source, price_list_id?, price_list?, break_min_qty?, resolved_unit_price_minor, override_reason?}` and the sale `price_list_id` and `price_list`; the price floor and cost visibility apply to the charged price as before. While the capability is off `price_list_id` is a field problem `capability_not_enabled`, a typed price needs no permission and no reason, and nothing about price lists is written or returned.

Built (issue #197, optional documents, migration V46, FR-RET-38): the routes below. Every one answers 404 `capability_not_enabled` while `documents` is off, and none adds a field to an existing response. `retail.document.issue` is the administrator's; a document is read in the branch scope of that permission (404 outside it). Documents are immutable: `status` is `issued`, or `void` when the sale was voided, the stock move cancelled or voided, or the quotation cancelled, and the stored PDF is as issued and never changes. A PDF is downloaded with `POST /api/v1/documents/{id}/download-url` and the `pdf_document_id` these routes return (FR-DOC-03); every response is `Cache-Control: no-store`. A document number is `<prefix>-<branch code>-<six digit counter>` (`INV-HQ-000001`), the counter per tenant, type and branch, taken in the transaction that writes the document, so a failure leaves no gap and a number is never reused. Issuing is two steps: the document and its number commit first, then the PDF is rendered with no connection held and stored once (ADR-034); a failed render (503 `documents_busy`) leaves the numbered document, and the same request, or `POST /retail/documents/{id}/pdf`, renders it from the stored snapshot. A void document is never printed clean: its issued copy is no longer served (the signed link is 403), and `POST /retail/documents/{id}/pdf` renders and stores once a second version with a VOID banner and the reason (its sale was voided, its stock move cancelled, the quotation cancelled), whose id is then the `pdf_document_id` the list and the document carry (absent until then). New error codes: 422 `sale_voided`, `transfer_not_active`, `quotation_expired`, `statement_too_long`; 409 `quotation_not_open`.

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST (M) | `/retail/sales/{sale_id}/documents` | `retail.document.issue` | `{type: invoice or delivery_note, show_prices?}` (a delivery note shows prices only when asked): 201 with the document when this call issued it, 200 with `created: false` when it had been issued already (one invoice and one delivery note per sale, whatever the key); 422 `sale_voided` for a voided sale |
| GET | `/retail/sales/{sale_id}/documents` | `retail.document.issue` | The documents of the sale in the caller's scope, oldest first |
| POST (M) | `/retail/transfers/{transfer_id}/documents` | `retail.document.issue` at the source branch | `{type: delivery_note}`: quantities only, from the source branch to the destination, never prices; one per transfer; 422 `transfer_not_active` for a voided or cancelled one |
| GET | `/retail/documents` | `retail.document.issue` | `branch_id` (repeatable), `type`, `from`, `to`, `query` (the number or the buyer's name; the shared live search), `limit`, `cursor`: `{items: [{id, type, number, branch_id, issue_date, sale_id?, transfer_id?, quotation_id?, source_document_id?, buyer_name?, currency, total_minor?, status, valid_until?, quotation_status?, pdf_document_id?, created, created_at}], next_cursor?}` newest first |
| GET | `/retail/documents/{document_id}` | `retail.document.issue` | One document |
| POST | `/retail/documents/{document_id}/pdf` | `retail.document.issue` | The document with its stored PDF, rendered now from the snapshot when none was stored |
| GET | `/retail/document-series` | `retail.document.issue` | For each type: `{type, prefix, default_prefix, branches: [{branch_id, branch_code, issued, next}]}` for the branches of the caller's scope |
| PUT | `/retail/document-series/{type}` | `core.settings.manage` | `{prefix}` (one to eight capitals or digits, starting with a letter; 422 otherwise): later documents take it, numbers issued never change. Audited as `retail.document.prefix_changed` |
| POST (M) | `/retail/quotations` | `retail.document.issue` | `{branch_id?, customer_id?, buyer_name?, buyer_contact?, price_list_id?, valid_until, notes?, lines: [{product_id, qty, unit_id?, unit_price_minor?, price_override_reason?}]}`: priced exactly as a sale would be (units, price lists, the override rules, the price floor); a customer record or a buyer name; `valid_until` not before today in the business time zone; a store does not quote (422 `branch_role_forbids_sales`); reserves no stock and posts nothing; the document is numbered and its PDF stored; 201 |
| GET | `/retail/quotations`, `/retail/quotations/{quotation_id}` | `retail.document.issue` | `{id, number, document_id, branch_id, quote_date, valid_until, customer_id?, buyer_name, buyer_contact?, price_list_id?, price_list?, currency, total_minor, notes?, status (open, converted, cancelled), expired, converted_sale_id?, converted_sale_no?, cancel_reason?, pdf_document_id?, lines, created_at, version}`; the list takes `branch_id`, `status`, `query`, `limit`, `cursor` |
| POST | `/retail/quotations/{quotation_id}/cancel` | `retail.document.issue` | `{reason}`: an open quotation only (409 `quotation_not_open`); its document reads as void. Audited |
| POST (M) | `/retail/quotations/{quotation_id}/convert` | `retail.document.issue`, and `retail.sale.create` at the branch | `{payment_method, due_date?, keep_quoted_prices?, issue_invoice?, credit_override_reason?}`: one transaction through the ordinary sale path (so stock, the credit limit, the price floor and the branch role all hold), priced again today unless `keep_quoted_prices` (a kept price that differs from today's is a typed price on a sale and needs `retail.price.override` and takes the reason `Quoted on <number>` while price lists are on), and with `issue_invoice` the invoice of the new sale, linked to the quotation and naming it; a quotation converts once (409 `quotation_not_open`) and only while valid (422 `quotation_expired`). 200 with `{quotation_id, sale_id, sale_no, invoice?, note?}`: with price lists or pack sizes switched off since the quotation was made the sale is priced at the product's price, in single units, and `note` says so. Audited |
| POST | `/retail/returns/{return_id}/document` | `retail.document.issue` and `retail.sale.return` at the return's branch | The credit note of a return as a PDF under the return's own number (`credit_note_no`, V45; there is no second series), stored once and immutable in the documents store (subject type `retail.credit_note`), idempotent by return, `Cache-Control: no-store`; needs `documents` and `returns` (404 `capability_not_enabled` otherwise) and a return in the caller's scope (404 outside it). `{return_id, credit_note_no, status (issued, or void when the return was voided), pdf_document_id, created}`. A voided return is never printed clean: its issued copy is no longer served (403) and the first request after the void stores a second version with a VOID banner. Totals and lines are the return's own: each line's credit, the pack unit it was returned in with the base units, the reason, the invoice the sale had, and how the buyer was repaid |
| GET | `/retail/customers/{customer_id}/statement/pdf` | `retail.document.issue` | `from`, `to` as on the statement route; also needs the `customer_accounts` capability (404 otherwise) and `retail.sale.read` (403 without it: the lines are the buyer's sales in that scope): `application/pdf`, as at today, never stored, in the caller's branch scope with the ledger's closing balance and imported credit sales apart, the buyer's terms and credit limit, and the running balance of every line, with a return and the void of a return printed as "Goods returned (credit note)" and "Credit note voided"; 422 `statement_too_long` above 800 lines |
Built (issue #198, optional purchasing, migration V47): the routes below. Every one is 404 `capability_not_enabled` while `purchasing` is off; `M` takes an `Idempotency-Key`; a list takes `q` (the shared search; the term never in the address), `status`, `from`, `to`, `branch_id` (repeatable, scoped), `limit` and `cursor`, and carries no lines. Orders and receipts need `retail.purchase.create` (scope: the order's branch, the receipt's branch), invoices, payments and the payables report need `retail.payable.manage`, the tolerances `core.settings.manage`. Cost appears on these documents because `retail.purchase.create` already reads purchase costs; the payables permission is the administrator's.

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST (M), GET | `/retail/purchase-orders` | `retail.purchase.create` | `{supplier_id, branch_id?, ordered_on?, expected_on?, note?, lines: [{product_id, unit_id?, qty, cost_minor}]}` (cost per unit as entered; a pack line converts exactly, 422 `quantity_not_representable`); 201 draft numbered `PO` + branch code + six digits, with its `ETag`. GET `supplier_id`, `status` |
| GET, PUT | `/retail/purchase-orders/{order_id}` | `retail.purchase.create` | The order carries `created_by` and `updated_by` (the last editor of the draft). GET adds the lines with `received_qty`, `received_minor` and, with `retail.payable.manage`, `billed_qty` and `billed_minor`, and the order's receipts. PUT replaces a draft under `If-Match` (428, 409 `version_conflict`, 409 `order_not_draft`); the branch is fixed (422) |
| POST (M) | `/retail/purchase-orders/{order_id}/send` | `retail.purchase.create` | 200 `{executed: true, order}`, or 202 `{executed: false, approval_id}` when the total reaches the tenant's threshold for `retail_purchase_order` (decided with `retail.purchase.approve`); 409 `order_not_draft` |
| POST (M) | `/retail/purchase-orders/{order_id}/cancel` | `retail.purchase.create` | `{reason}` (5 to 300 characters); a draft, sent or partly received order; 409 `order_closed`; receipts stand |
| POST (M), GET | `/retail/goods-receipts` | `retail.purchase.create` | `{order_id?, supplier_id? (blind), branch_id?, received_on?, delivery_ref?, note?, lines: [{order_line_id? (required against an order), product_id? (blind), unit_id?, qty, cost_minor?, sell_minor?}]}`; the cost and the unit default to the order line's; 201 numbered `GR`; 409 `order_not_sent`, `order_closed`; 422 `receipt_exceeds_order`, `before_order` (dated before its order), and with a tenant threshold for `retail_purchase_order` `blind_receipt_over_limit` (no order, worth the threshold or more) and `receipt_value_exceeds_order` (the value received on the order would pass the order's value plus the tolerance). GET `supplier_id`, `order_id`, `status` |
| GET | `/retail/goods-receipts/{receipt_id}` | `retail.purchase.create` | The lines; `billed_qty` with `retail.payable.manage` |
| POST (M) | `/retail/goods-receipts/{receipt_id}/void` | `retail.purchase.create` | `{reason}`; reverses the entry and takes the stock out; 409 `receipt_billed`, `receipt_voided`; 422 `receipt_stock_moved` |
| GET | `/retail/suppliers/{supplier_id}/unbilled-receipts` | `retail.payable.manage` | The received lines no standing invoice bills in full: `{items: [{receipt_line_id, receipt_no, received_on, unbilled_qty (in the unit received), unit_cost_minor, unbilled_minor}], total_minor}` |
| POST | `/retail/supplier-bills/match` | `retail.payable.manage` | The body of a bill; changes nothing: `{total_minor, cleared_minor, variance_minor, tolerance_minor, reason_required, total_mismatch, lines}` |
| POST (M), GET | `/retail/supplier-bills` | `retail.payable.manage` | `{supplier_id, supplier_ref, bill_date, due_date, total_minor (the invoice total as printed, which must equal the lines), variance_reason?, note?, lines: [{receipt_line_id, qty (in the unit received), unit_price_minor}]}`; 201 numbered `SB`; 409 `duplicate_supplier_bill`; 422 `bill_exceeds_received`, `bill_total_mismatch`, `variance_reason_required`, and a field problem `bill_before_receipt` (dated before the goods it bills). Voiding needs the scope of every branch the bill posted in (404 otherwise). GET `supplier_id`, `open` |
| GET | `/retail/supplier-bills/{bill_id}` | `retail.payable.manage` | The lines with what each cleared and its variance; `paid_minor` and `open_minor` |
| POST (M) | `/retail/supplier-bills/{bill_id}/void` | `retail.payable.manage` | `{reason}`; reverses each entry; 409 `bill_paid`, `bill_voided` |
| POST (M), GET | `/retail/supplier-payments` | `retail.payable.manage` | `{supplier_id, branch_id?, method (cash, mobile_money, bank), amount_minor, allocation? (oldest_first, manual), allocations? [{bill_id or purchase_id, amount_minor}], reference?, note?}`; 201 numbered `SP`, dated today in the business time zone; 422 `payment_exceeds_open`, `allocation_total_mismatch`, `allocation_exceeds_open` |
| GET | `/retail/supplier-payments/{payment_id}` | `retail.payable.manage` | The allocations |
| POST (M) | `/retail/supplier-payments/{payment_id}/reverse` | `retail.payable.manage` | `{reason}`; whole, once; 409 `payment_reversed` |
| GET | `/retail/suppliers/{supplier_id}/open-items` | `retail.payable.manage` | `{items: [{kind (bill, restock), id, number, supplier_ref?, date, due_date, total_minor, paid_minor, open_minor, days_past_due}], total_open_minor}` in the caller's scope, oldest due first |
| GET | `/retail/reports/supplier-payables` | `retail.payable.manage` | `branch_id`, `supplier_id`: `{as_of, currency, totals, suppliers: [{supplier_id, supplier_name, not_due_minor, days1_to30_minor, days31_to60_minor, days61_to90_minor, over90_minor, owed_minor, unbilled_receipts_minor}]}`; days past due on today's business date |
| GET, PUT | `/retail/purchasing/settings` | `core.settings.manage` | `{over_receipt_bp, bill_tolerance_bp, order_approval_threshold_minor? (read only, present when set), version}`; PUT `{over_receipt_bp?, bill_tolerance_bp?}` (0 to 10000) under `If-Match`; audited |

A cash supplier payment is a cash-out of the paying branch's till on its day in the cash book, and its reversal puts the cash back on the day it was reversed (`cash_purchases_minor` of the daily cash report).

Built (issue #199, landed cost and supplier credit notes, migration V48): the routes below, all `retail.payable.manage` in the branch of every received line and of the invoice (404 for a document outside the scope), all 404 `capability_not_enabled` while `purchasing` is off, `M` taking an `Idempotency-Key`. The supplier invoice carries `kind` (`goods` or `landed_cost`), `credited_minor` and `open_minor` (total less paid less credited); an open item carries `credited_minor`; a goods receipt carries `held_for_costing` and, with `retail.profit.read`, `landed_minor` on each line; `GET` and `PUT /retail/purchasing/settings` carry `require_costing_before_sale`.

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST (M) | `/retail/landed-costs` | `retail.payable.manage` | `{supplier_id (the payee), supplier_ref, cost_date?, due_date, method? (value, quantity, weight, manual; default value), goods_bill_id?, total_minor (as invoiced; must equal the charges), note?, charges: [{kind (freight, duty, clearing, insurance, other), description?, amount_minor}], lines: [{receipt_line_id, weight? (weight method), amount_minor? (manual: must add to the total)}]}`; 201 numbered `LC`, with the payee's invoice created in the same transaction; 409 `duplicate_supplier_bill`; 422 `landed_total_mismatch`, `allocation_total_mismatch`, `allocation_basis_zero`, `line_returned`, field problems `unknown_receipt_line`, `cost_before_receipt`, `not_on_bill` |
| POST | `/retail/landed-costs/preview` | `retail.payable.manage` | The body above; writes nothing: `{total_minor, total_mismatch, method, lines}` with each line's share and, with `retail.profit.read`, `on_hand_qty`, `to_inventory_minor`, `to_cogs_minor`, `cost_before_minor`, `cost_after_minor`, `cost_applied`, `cost_note` |
| GET | `/retail/landed-costs` | `retail.payable.manage` | `q` (landed number, payee's invoice number, payee), `supplier_id`, `receipt_id`, `status`, `from`, `to`, `branch_id`, `limit`, `cursor`; no charges or lines |
| GET | `/retail/landed-costs/{landed_cost_id}` | `retail.payable.manage` | The charges and the allocation, `bill_id`, `bill_no` and `paid_minor` |
| POST (M) | `/retail/landed-costs/{landed_cost_id}/void` | `retail.payable.manage` | `{reason}`; reverses every entry, voids the payee's invoice and puts the cost back where it is still the document's; 409 `landed_cost_voided`, `bill_paid`, `landed_cost_credited`; a voided landed cost holds a receipt it released again; 422 `landed_cost_stock_moved` |
| POST (M), GET | `/retail/supplier-credits` | `retail.payable.manage` | `{bill_id, kind (goods, price), supplier_ref, credit_date?, total_minor, reason, note?, lines: [{bill_line_id, qty + unit_credit_minor? (goods), amount_minor (price)}]}`; 201 numbered `SC`; 409 `duplicate_supplier_credit`; 422 `credit_total_mismatch`, `credit_exceeds_billed`, `credit_exceeds_on_hand` (more than the receipt still has in its branch), `credit_exceeds_billed_value`, `credit_exceeds_open`, `line_returned`, `insufficient_stock`, `quantity_not_representable`. GET `q`, `supplier_id`, `bill_id`, `status`, `from`, `to`, `branch_id` |
| GET | `/retail/supplier-credits/{credit_id}` | `retail.payable.manage` | The lines |
| POST (M) | `/retail/supplier-credits/{credit_id}/void` | `retail.payable.manage` | `{reason}`; reverses every entry and puts goods back; 409 `credit_voided`; 422 `credit_stock_moved` |
| POST (M) | `/retail/goods-receipts/{receipt_id}/release-hold` | `retail.payable.manage` in the receipt's branch | `{reason}`; releases a receipt held as received and not costed without a landed cost, under the receipt's lock; 409 `not_held`, `receipt_voided` |

Also refused since issue #199: voiding a goods receipt that has a landed cost (409 `receipt_costed`), voiding a supplier invoice of kind `landed_cost` (409 `bill_is_landed_cost`) or one with a credit note standing (409 `bill_credited`), and a transfer out of a branch that would take the quantity of a held receipt while `require_costing_before_sale` is on (422 `receipt_not_costed`). A landed cost or credit note shows a reader only the lines of the branches in the reader's scope.

Every route below answers 404 `capability_not_enabled` unless its capability is on
(after 404 `module_not_enabled`); the full route table, permissions and the money-moving (`M`)
routes are in `docs/specs/trade-capabilities.md` section 11: capability settings, branch roles,
product units, price lists and price resolution, customer account, statement, payment on account and
ageing, documents and quotations, purchase orders, goods receipts, supplier bills, credits and
payments, landed cost, transfer receive and cancel, sale returns, and the reports stock-by-location,
transfers-in-flight and supplier-payables. Existing routes gain optional request fields (a sale
line's `unit_id`, a sale's `price_list_id`) that are ignored when the capability is off. New error
codes: 422 `branch_role_forbids_sales`, `quantity_not_representable`, `no_price_in_list`,
`credit_limit_exceeded`, `customer_required`, `payment_exceeds_allocatable`, `received_exceeds_dispatched`, `branch_has_open_work`, `unit_in_use_by_pack`, `transfers_in_transit`; 409 `price_item_overlap`, `transfer_not_in_transit`, `transfer_in_transit`, `transfer_cancelled`.

### 7.11.22 Lending: insights (`/lending/insights`, #153, ADR-030)

Read-only. Every route takes the page filter: `from` and `to` (inclusive dates, default the first
of this month to today; 422 `range_in_future`, `invalid_range`, `range_too_long` over five years),
`branch_id` (repeatable; narrowed to the caller's scope of `lending.insights.read`), `officer_id`
(ignored without `lending.insights.all_officers`: such a caller always gets their own loans) and
`product_id`. Responses carry `Cache-Control: no-store`; the page polls every 60 seconds. Money is
integer minor units, rates basis points (null over a zero denominator). Every metric is
`{key, label, kind, value, currency, definition, drill: {table, params}}`; the keys and formulas are
in `docs/specs/lending-insights-metrics.md`.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/lending/insights/brief` | `lending.insights.read` | Today: metrics and one sentence per movement (FR-INS-02) |
| GET | `/lending/insights/portfolio` | `lending.insights.read` | `grain` (`day`, `week`, `month`, `year`; chosen from the range when absent; 422 `too_many_periods` over 120 periods). Metrics, the period `series`, `ageing`, `breakdowns` (product, branch, officer, status), `top_arrears`, the 30-day `forecast`, the 12-month `trend`; `stock_as_of` and `stock_source` (`live`, `snapshot`, `none`) (FR-INS-03) |
| GET | `/lending/insights/revenue` | `lending.insights.read` | Metrics, `by_product`, `by_branch`, 12 `months`, from posted journal lines (FR-INS-04) |
| GET | `/lending/insights/members` | `lending.insights.read` | `grain`. Metrics, `new_members`, `funnel`, `kyc`, `score_bands`, `staff` (FR-INS-05) |
| GET | `/lending/insights/panels` | `lending.insights.read` | Panels registered by other modules: `savings` and `investments`, each only for a tenant with savings accounts or investments and never under a one-officer filter (FR-INS-09) |
| GET | `/lending/insights/tables/{table}` | `lending.insights.read` | The rows behind a number, at most 200, `truncated` when more. Tables and their parameters (`date`, `status`, `stage`, `account`, `min_dpd`, `max_dpd`, `bucket`, `days`, `group`, `key`) in the metrics dictionary; 404 for an unknown table (FR-INS-06) |
| GET | `/lending/insights/tables/{table}/export` | `lending.insights.export` | The same as `text/csv`, at most 10,000 rows, money in minor units with the currency in the header, names as initials without `lending.members.read`; audited `lending.insights.exported` |
| GET | `/lending/insights/digest` | `core.settings.manage` | The digest settings with an `ETag` |
| PUT | `/lending/insights/digest` | `core.settings.manage` | `{enabled, email_recipients (max 5), telegram_chat_id, send_hour}` with `If-Match`; 422 `digest_needs_recipient` when on with no recipient; 412 `version_mismatch`; audited (FR-INS-08) |
| GET | `/lending/insights/digest/preview` | `core.settings.manage` | `{as_of, subject, text}`: the text the digest would send now |

### 7.11.24 Lending: AI assistant (`/lending/advisor`, ADR-037, off by default)

Built in #220 (the gateway, the call log and the settings group), #223 (the facts, the aliases, the structured validator and
renderer and the offline harness) and #226 (the daily brief, the stored brief, the two routes below and the settings routes). The
question box and the call list are #230 and a later slice.

Responses carry `Cache-Control: no-store`. A brief body has `advisory: true`, `as_of` (when it was made), `facts_as_of` (the
business date of the figures), `source` (`model` or `fallback`), a `label` (`AI assistant (advisory)` or `Automatic summary`),
`summary`, `points` (the text), `facts` (chips: key, label, display value and the insights `metric` whose rows open from it) and
`note` (the line under the card). The text is written by our code from a structured model answer (findings that choose facts, a
topic, a catalogue action and a priority; spec 5.13), never taken from the model. Provider errors and refused answers never reach
the client; the deterministic facts-only brief is returned instead, labelled `Automatic summary`.

**Decision of #226 (the coordinator's).** The assistant is invisible while it is off. `GET /lending/advisor/brief` answers **204 No
Content** when the assistant is not active for the tenant, that is when its own switch is off OR the tenant is not named in
`BMS_AI_ENABLED_TENANTS` (a tenant removed from the list while on is off at once, and an old stored model brief is not served).
Nothing is read for a 204 (no figures of the caller), and the card is not drawn. For a tenant where it is active the route answers
200 on every path, with the automatic summary when there is no model brief for the caller, so the page loads. The design's 404
`advisor_disabled` is not used for the brief.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/lending/advisor/brief` | `lending.advisor.use` | Today's brief. The stored model brief (one per tenant and day, over every branch) is returned only to a caller whose branch scope covers every branch in `lending.advisor.use`, `lending.insights.read` and `lending.insights.all_officers` and who holds the last; anyone else, and every case where no model brief exists, gets `source: fallback` computed for their own scope with `reason`: `scope`, `not_ready` (the day's job has not run), `validation`, `provider_down` (provider error, timeout, an open breaker), `cap`, or `unavailable` (the figures could not be read); 204 when the assistant is off. Needs `lending.insights.read` for the figures (403 otherwise). A branch manager holds all officers but only for their own branches, so they never receive the all-branches brief |
| GET | `/lending/advisor/settings` | `lending.advisor.configure` | `enabled`, `allowed` (named in `BMS_AI_ENABLED_TENANTS`), `provider_configured`, the two daily caps with their currency, today's use, the acknowledgement record (user, time, text version), the notice text and its version, and `version` as the `ETag` |
| PUT | `/lending/advisor/settings` | `lending.advisor.configure`; `core.settings.manage` as well to switch on or off | `If-Match` the version read. `enabled: true` needs `acknowledged_text_version` equal to the notice's version (422 `acknowledgement_required`), is refused with 403 `advisor_not_available` when the tenant is not in `BMS_AI_ENABLED_TENANTS`, and stores who, when and which text; `daily_token_cap` and `daily_cost_cap_micro` are at most the platform ceiling (422 `above_platform_ceiling`) and 0 stops every call; 409 `version_conflict`, 428 `precondition_required`; audited as `core.ai.settings.changed` with before and after |
| POST | `/lending/advisor/questions` | `lending.advisor.use` | Not built (#230). `{question}` up to 300 characters, answered from the caller's scope; 422 `question_refused` with a reason class (`too_long`, `digits`, `email`, `id_pattern`, `series_number`, `member_name`), 429 `daily_limit`. FR-AIA-07 |
| GET | `/lending/advisor/calls` | `lending.advisor.configure` | Not built. The call log without personal data, 90 days |

The daily job `lending.advisor-daily-brief` is not a route: every 15 minutes it makes, for each active lending tenant with the
assistant on and on the platform list, the brief of the Africa/Kampala business date its run was due on (not before 06:00), once per tenant and date.

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
