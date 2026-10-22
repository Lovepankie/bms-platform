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
| 429 | Rate limited; `Retry-After` header set where the limiter knows it (on the public sign-in family, the time until the bucket lets the caller in again); `rate_limited` on the public sign-up endpoints, the public sign-in family (ADR-059) and report runs (section 7.10) |
| 500 | `internal_error`; the body carries only the generic title and the `request_id` |
| 503 | Dependency down (the database); readiness fails; `uploads_busy` (image re-encoding is at capacity, retry shortly); `storage_unavailable` (document storage not configured); `sign_in_busy` (every password hashing slot is taken; `Retry-After` 2 to 5 seconds; ADR-059) |

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
`branch_has_open_accounts`, `branch_close_needs_reason`, `branch_still_has_stock`, `lock_timeout` (409: a stock write or a branch change waited too long for the branch row; nothing was saved; send it again), `branch_does_not_sell` (retail refuses a sale, a quotation and its conversion, a price resolution, a payment taken on a credit sale or on account, a refund paid out of the till and the cash book savings, bankings, withdrawals and advance repayments at a Store; expenses are allowed, a branch marked with `sells_goods` false; restocks, goods receipts, transfers and stock-takes there are allowed; ADR-048), `refund_branch_required` (a refund for a sale made at a Store: the one shop the caller may sell at pays it; with several the caller names one in `refund_branch_id`, and with none the answer says no shop can pay it out and to credit the account or ask a shop), `store_must_stock` (a Store cannot also be a branch that keeps no goods), `branch_has_open_credit_sales` (marking a branch as a Store while a credit sale made there still has a balance owing; the problem carries `open_credit_sales`), `branch_not_stocking` (retail refuses a sale, price resolution, restock, purchase order, goods receipt, transfer into it or its receipt, stock-take or return restock at a branch marked as not stocking; goods may still leave it as a transfer source or a usage report; ADR-041), `account_has_open_items`, `next_of_kin_required`, `image_too_large`, `svg_not_allowed` (a logo that is an SVG), `image_too_small` (a logo under 128 px on the short side), `insufficient_contrast` (a theme colour with no readable text colour; a field error of `validation_failed`), `collateral_type_disabled`, `unknown_placeholder`,
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
| Staff and operator sign-in per sign-in address, within one tenant, from any client (database; ADR-059, which replaces the "10 per 15 minutes per login identifier" this table promised and nothing built) | The fifth wrong password in a row locks the address for 15 minutes (423 `account_locked`, the right password refused too); an address of no account is counted and locked in the database exactly as an account (`sign_in_address_failures`, V78). No other limit per address: a right password before the fifth failure always gets in. A completed password reset, a tenant admin or the platform operator lifts the lock |
| Failed sign-in, two-step (verify, enrol, confirm), invitation and operator setup requests per client address, an IPv6 client by its /64 (ADR-059, `BMS_AUTH_SIGN_IN_FAILURES_PER_IP`) | 300 failures per 10 minutes by default (429 with `Retry-After`, the refill time); a success, a 423, a 429 or a 503 costs nothing, so many users behind one carrier NAT address are not limited by each other's sign-ins |
| Second factor per account (ADR-059) | 30 per 15 minutes (429) |
| Password hashes running at once (ADR-059, `BMS_AUTH_ARGON2_MAX_CONCURRENT`) | 4 by default; 503 `sign_in_busy` with `Retry-After` 2 to 5 seconds after a 2 second wait; no request holds a database connection while it waits |
| Member OTP request per phone | 3 per hour |
| Member PIN sign-in per phone | 10 per 15 minutes |
| Any authenticated user | 600 requests per minute |
| Report runs per user | 10 per minute |
| Callbacks per provider | 50 per second |
| Sign-up: new applications per client address, an IPv6 client by its /64 (ADR-024) | 5 per hour (429) |
| Sign-up: link reads (verify, status, reply) per client address | 30 per 10 minutes (429) |
| Sign-up: confirmation emails per mailbox (database) | 3 per 24 hours (202, nothing sent) |
| Forgot password: requests per client address, an IPv6 client by its /64 (FR-IAM-15) | 20 per hour (429) |
| Forgot password: using a link per client address | 30 per 10 minutes (429) |
| Forgot password: requests for one sign-in address, from any client (ADR-059) | 10 per hour (429) |
| Forgot password: links for one account (database) | 3 per hour (202, nothing sent) |
| Forgot password: a user whose role needs a second factor not yet set up | no link by email (202, nothing sent); the admin or the operator issues one |
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
| POST | `/auth/staff/login` | public | `{login, password}` returns tokens (`status: signed_in`), or `{status: mfa_required \| mfa_enrolment_required, mfa_token}`. 401 `invalid_credentials`, 423 `account_locked` (the same after five failures whether or not the address is an account, held in the database), 429 `rate_limited` with `Retry-After` (too many failures from the client address), 503 `sign_in_busy` with `Retry-After` (ADR-059). FR-IAM-04, FR-IAM-05 |
| POST | `/auth/staff/mfa/verify` | public (mfa_token) | `{mfa_token, code}`; `code` is a TOTP code or a recovery code. Returns tokens. 429 `rate_limited` with `Retry-After`, after too many failures from the client address or too many attempts for the account (ADR-059). FR-IAM-06, FR-IAM-11 |
| POST | `/auth/staff/mfa/enrol` | public (mfa_token), or signed in | Returns the TOTP secret and `otpauth` URI once |
| POST | `/auth/staff/mfa/confirm` | public (mfa_token), or signed in | `{mfa_token?, code}` activates TOTP and returns ten recovery codes once; with the MFA token it also signs in. FR-IAM-11 |
| POST | `/auth/staff/mfa/recovery-codes` | authenticated staff | `{code}` (a current TOTP code) replaces every recovery code; returns the new ones once. FR-IAM-11 |
| POST | `/auth/staff/invitations/accept` | public (token) | `{token, password}`; sets the password and signs in: the same body as `/auth/staff/login` (`signed_in` with tokens, or `mfa_enrolment_required` with an MFA token when the role requires a second factor). FR-IAM-01, FR-IAM-13, ADR-025 |
| POST | `/auth/staff/password/forgot` | public | **Built (#320).** `{login}` (email or phone). Always 202 `{message}` with the same words and after at least the same time (a 400 ms floor) for a known account, an unknown one, a deactivated one, a user still invited and a user with no email; a link goes to the email on file only for an active user, through the outbox (`identity.password_reset`). 429 `rate_limited` per client address or per sign-in address (ADR-059); further links for one account in an hour are answered and ignored. FR-IAM-15, ADR-042 |
| POST | `/auth/staff/password/reset` | public (token) | **Built (#320).** `{token, password}`; the token is the fragment of the emailed or admin-issued link. Sets the password under the invitation's rules, ends every session of the user and answers like `/auth/staff/login`: `signed_in` with tokens, `mfa_required` or `mfa_enrolment_required` with an MFA token (a reset never skips the second factor); the reset lifts the failed-attempt lock first (audited `core.auth.unlocked`, ADR-059, amending ADR-042), so `sign_in_required` with no token comes back only when the account was locked again in the same moment. 422 `reset_link_invalid` (used, replaced, unknown, other tenant, user not active), 422 `reset_link_expired`, 422 `weak_password` (the link still works), 429 `rate_limited`. FR-IAM-15, ADR-042 |
| POST | `/auth/member/otp/request` | public | `{phone, purpose}`; always 202. FR-IAM-09 (P2) |
| POST | `/auth/member/otp/verify` | public | `{phone, code}` returns a short-lived `pin_setup_token` (P2) |
| POST | `/auth/member/pin` | public (pin_setup_token) | Sets PIN, returns tokens (P2) |
| POST | `/auth/member/login` | public | `{phone, pin}` (P2) |
| POST | `/auth/refresh` | refresh cookie | Rotates refresh token. FR-IAM-07 |
| POST | `/auth/logout` | authenticated staff | Revokes the session family; clears the cookie |
| GET | `/me` | authenticated staff | User, roles, permissions, `permission_scopes` (per permission key `{all_branches, branch_ids}`, so a screen offers only the branches where its permission is held, ADR-017), `all_branches`, the open branches to switch between (each with `stocks_goods` and `sells_goods`; a closed branch is not listed, a branch that keeps no goods or a Store is listed but is not the default, ADR-041, ADR-048), the default branch, MFA state (`mfa_enabled`; `mfa_required`: must have a factor to sign in now; `mfa_due_by`: the deadline of the tenant's two-step policy for a covered user who has no factor yet and is inside the grace period, else null; `two_step_policy`: `off`, `roles` or `everyone`; `tenant_timezone`: the business's zone, in which `mfa_due_by` is a day's end, ADR-046) and unused recovery codes. Also `tours`: the guided tours the user finished or dismissed, by tour id (FR-IAM-14). FR-BR-03 |
| PUT | `/me/tours/{tour_id}` | authenticated staff | `{status: completed \| dismissed, version}`; records how the caller left a guided tour; 204. Only the caller's own row; tour id is lower case words joined by hyphens, at most 64 tours per user (`tour_id_invalid`, `too_many_tours`). FR-IAM-14, ADR-025 |

The refresh token never appears in a response body: it is the `__Host-bms_rt` cookie of section 7.4.1.

### 7.11.3 Platform (the platform host, `/platform`, platform users only)

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/platform/auth/setup` | public (setup token) | `{token, password}`: first password, with the token from `deploy/sql/create-platform-user.sql` |
| POST | `/platform/auth/login`, `/platform/auth/mfa/verify`, `/platform/auth/mfa/enrol`, `/platform/auth/mfa/confirm` | public (mfa_token) | As the staff endpoints, with the same limits (ADR-059); TOTP is mandatory and enrolled at first sign-in |
| POST | `/platform/auth/refresh` | refresh cookie `__Host-bms_prt` | |
| POST | `/platform/auth/logout` | authenticated platform | |
| GET | `/platform/me` | authenticated platform | |
| GET | `/platform/plans` | `platform.tenants.read` | `?include_inactive=true` adds the plans not offered. Each plan: limits, modules, `description`, `active`, `sort_order`, `current_price` and `next_price` (`{monthly_minor, annual_months, annual_minor, effective_from, currency, reason, created_by_name}`, or null), `tenant_count`. FR-TEN-04, FR-SUB-01 (ADR-056, #407) |
| GET | `/platform/plans/{plan_code}` | `platform.tenants.read` | `{plan, prices}`: every price the plan has had, newest first. 404 for an unknown code |
| PUT | `/platform/plans/{plan_code}` | `platform.tenants.manage` | `{name, description?, max_branches?, max_staff_users?, max_active_members?, allowed_modules, active, sort_order?, reason}` (limits 0 or more): creates the plan (201) or changes it (200); null limits are unlimited; the same request again changes nothing and writes no audit row; 422 `plan_module_in_use` when a business on it uses a module taken out; 422 `plan_limit_below_use` when a shop, staff or member limit goes below what businesses on it use, until `confirm_below_use` is true (the audit row keeps `businesses_over_limit`). Audited as `platform.plan.created` or `platform.plan.changed` with before and after. Built in #407 |
| POST | `/platform/plans/{plan_code}/prices` | `platform.tenants.manage` | `{currency?, monthly_minor (0 to 50,000,000), annual_months (1 to 12), effective_from?, reason}`: a new price from today or a later day (422 `validation_failed`, `effective_from` `in_the_past`); the price already in force on that day again adds nothing; a monthly price more than 25% away from the one in force is 422 `price_change_unusual` until `confirm_large_change` is true. Audited as `platform.plan.price_added`. Built in #407 |
| GET | `/platform/tenants` | `platform.tenants.read` | `?q=` (name, slug or owner email, any case, `%` and `_` are characters), `?status=` (repeatable subscription status), `?module=`. Each tenant also carries `plan_name`, `trial_ends_on`, `paid_from`, `paid_until`, `owner_name`, `owner_email`, `last_sign_in_at` and `address` (its own origin). FR-TEN-10 (#407) |
| GET | `/platform/tenants/{tenant_id}/overview` | `platform.tenants.read` | The Business page: `{tenant, plan_price, admins, payments, messages, history}`; `messages` are the outbox rows of the tenant (masked recipient, never the text or a link), `history` the platform audit rows with the operator's name and reason. FR-TEN-10 (#407). Since V79 each of `admins` carries `is_owner`, the Owner listed first, and the tenant's `owner_name` and `owner_email` (also on `GET /platform/tenants`) are the Owner's, null while the business has none (ADR-062) |
| PUT | `/platform/tenants/{tenant_id}/plan` | `platform.tenants.manage` | `{plan_code, reason}`: moves the tenant and its subscription to an active plan; 422 `plan_modules_not_allowed`, `plan_too_small_branches`, `plan_too_small_staff`, `plan_too_small_members` (active lending members), `plan_unknown`; the same plan again changes nothing. Audited as `platform.tenant.plan_changed`. FR-TEN-11 (#407) |
| PUT | `/platform/tenants/{tenant_id}/subscription-dates` | `platform.tenants.manage` | `{trial_ends_on?, paid_until?, clear_trial_ends_on?, clear_paid_until?, reason}`: the last day of the free month or the last day paid for (a correction), or clear either; a date is from 2026-01-01 to five years ahead (422 `out_of_range`); one change is required; unchanged dates change nothing. Audited as `platform.tenant.subscription_dates_changed`. FR-TEN-11, FR-SUB-02 (#407) |
| POST | `/platform/tenants/{tenant_id}/payments` | `platform.tenants.manage` | `Idempotency-Key` required (422 `idempotency_key_missing`). `{amount_minor (1 to 50,000,000), method, reference?, paid_on, months (1 to 24), note?, reason (5 to 300), confirm_unusual_amount?}`: a payment received, confirmed at once; on a suspended or cancelled business it sets the status a resume brings back to active; an amount more than 25% from the plan's price for those months, or on a plan with no price, is 422 `payment_amount_unusual` (both amounts written "UGX 30,000") until `confirm_unusual_amount` is true; before anyone has signed in the free month starts at the payment and the paid months follow it; 201 with the payment and the period it covers, 200 with the first answer on a repeat. A day after today or more than a year ago is refused. Audited as `platform.tenant.payment_recorded`. FR-SPM-01 (ADR-024 decision 4, ADR-056, #407) |
| POST | `/platform/tenants` | `platform.tenants.manage` | Create per FR-TEN-01: tenant, subscription (`trial`), settings, head office, modules with their chart of accounts, first tenant admin; returns the admin's one-time link once |
| GET | `/platform/tenants/{tenant_id}` | `platform.tenants.read` | |
| POST | `/platform/tenants/{tenant_id}/payment-account/release` | `platform.tenants.manage` | Built in #233. `{reason}` (10 to 300 characters, required). Operator override, one transaction with its platform audit row, emails the tenant's administrators: removes the tenant's saved payment account although payments are in flight (a compromised merchant account), audited in the platform audit log; waiting orders are skipped, never closed |
| GET | `/platform/tenants/{tenant_id}/payment-account` | `platform.tenants.read` | Built in #233. Whether the tenant has connected its own payment account: `status` (`not_connected`, `verified`, `failed`), `last_checked_at`, `failure_reason`. Status only: the operator can neither read nor enter a credential. ADR-035 amendment of 2026-10-18 |
| PATCH | `/platform/tenants/{tenant_id}` | `platform.tenants.manage` | Not built yet |
| PUT | `/platform/tenants/{tenant_id}/modules` | `platform.tenants.manage` | `{modules: ["lending"], reason}` (reason required since #407); the same modules again change nothing. FR-TEN-03 |
| PUT | `/platform/tenants/{tenant_id}/pilot` | `platform.tenants.manage` | `{pilot, reason}`: a pilot (complimentary) business is free with no free month end; marking clears the last day of the free month and any scheduled status change, and no clock starts; `subscription-dates` refuses a free month end on a pilot (422, `trial_ends_on` `pilot`). Audited as `platform.tenant.pilot_changed`; the same mark again changes nothing. ADR-024 decision 6, ADR-056 (#407) |
| POST | `/platform/tenants/{tenant_id}/subscription` | `platform.tenants.manage` | `{status, next_status_change_on, reason}` (reason 5 to 300 characters, required since #407); the status it already has changes nothing. FR-TEN-05 |
| POST | `/platform/tenants/{tenant_id}/suspend`, `/resume` | `platform.tenants.manage` | `{reason}` required since #407, kept in the audit row; a resume brings back the status from before the suspension (`suspended_from` on the tenant); already suspended, or a resume of a tenant that is not suspended, changes nothing and writes nothing. Suspended is read only, never deleted. FR-TEN-06 |
| POST | `/platform/tenants/{tenant_id}/users/{user_id}/unlock` | `platform.tenants.manage` | **Built (ADR-059).** Body `{reason}` (5 to 300 characters). Lifts the sign-in lock of a staff user of the tenant, any role; 204. Audited in the tenant's log (`core.auth.unlocked`, `by: platform`) and the platform log (`platform.tenant_user.sign_in_unlocked`, with the reason). No step-up: it gives nobody a credential. 404 for an id that is not a staff user of that tenant, an operator included: nothing in the application unlocks an operator. FR-IAM-05 |
| POST | `/platform/tenants/{tenant_id}/users/{user_id}/mfa/reset` | `platform.tenants.manage` | Tenant admins only. Body `{totp_code, reason}`: a current code from the operator's own authenticator, used once (422 `invalid_mfa_code`), added by #320 (ADR-042), and a reason of 5 to 300 characters kept in the audit row (#407). FR-IAM-12 |
| POST | `/platform/tenants/{tenant_id}/users/{user_id}/make-owner` | `platform.tenants.manage` | **Built (#442, ADR-062).** Body `{totp_code, reason}`: a current code from the operator's own authenticator, used once, checked after the target, and a reason of 5 to 300 characters (422 `validation_failed` otherwise). Makes an **active administrator** of the business its Owner, for an Owner who lost access or a business that has none; the previous Owner stays an administrator; 204. 422 `owner_must_be_active_admin` (never a non-administrator in one step), 422 `already_owner`, 404 for a user of another business. Audited in the tenant's log (`core.user.ownership_transferred`, by the operator, with the reason) and the platform log (`platform.tenant.owner_transferred`, before, after and the reason, shown in the Business page's history). FR-IAM-18 |
| POST | `/platform/tenants/{tenant_id}/users/{user_id}/invitation` | `platform.tenants.manage` | **Built (#442, ADR-062).** Body `{totp_code, reason}` as `make-owner`. A new one-time invitation link (72 hours) for the first administrator of a business who never joined: the invited Owner, or an invited administrator of a business with no Owner and no active administrator; returns `{user_id, url, expires_at}` once (`no-store`); the earlier link stops working and the link is also sent through the notification port. 422 `not_an_invited_owner` for anyone else, checked before the code is spent. Audited `core.invitation.link_revealed` (by the operator) and `platform.tenant_owner.invitation_reissued` (with the reason). FR-IAM-18 |
| POST | `/platform/tenants/{tenant_id}/users/{user_id}/reset-sign-in` | `platform.tenants.manage` | **Built (#320).** Body `{totp_code, reason}`: a current code from the operator's own authenticator, used once, checked after the target and before anything is done (422 `invalid_mfa_code`), and a reason of 5 to 300 characters kept in the audit row (#407). Tenant admins only (422 `not_a_tenant_admin`), active only (409). Returns `{url, expires_at}` once, `no-store`, for the operator to hand to the admin; audited in both audit logs (`platform.tenant_admin.sign_in_reset`); the admin is emailed that a reset was issued, without the link. After the reset an admin with two-step sign-in still types the code, and one without it is sent to enrolment. FR-IAM-15, ADR-042 |
| POST | `/platform/tenants/{tenant_id}/support-sessions` | | FR-TEN-07 (P2) |
| GET | `/platform/tenants/{tenant_id}/usage` | | Users, members, SMS segments. FR-NTF-07. Not built yet |
| POST | `/platform/sign-up/applications` | public | The sign-up form (FR-ONB-01). Always 202 `{status: check_your_email}`, for a new, a known or a honeypot request (FR-ONB-03); 429 `rate_limited`. An optional `referral_code` (the `?ref=` of a referral link, any case) is resolved first: one no business has is 422 `referral_code_unknown` on that field and nothing is created (FR-REF-02, ADR-069, #464) |
| POST | `/platform/find-sign-in` | public | **Built (#383).** `{email}`. Always 202 `{message}` with the same words and after at least a 400 ms floor, whether or not the address is an active staff user of any active business. When it is, the sign-in page of each such business is emailed to that address only (`identity.find_sign_in`, through the outbox). Limited to 10 an hour per client address (429 `rate_limited`) and 3 emails an hour per mailbox (counted from the outbox, answered like any other request); every call is audited in `platform_audit_log` with the address masked |
| POST | `/platform/sign-up/verify` | public (link token) | `{token}`: confirms the email on first use and returns the applicant view (FR-ONB-02) |
| POST | `/platform/sign-up/status` | public (link token) | `{token}`: the applicant view, no contact details (FR-ONB-04) |
| POST | `/platform/sign-up/reply` | public (link token) | `{token, reply}`: answers a `needs_info` note; back to `submitted` (FR-ONB-04) |
| GET | `/platform/applications` | `platform.tenants.read` | `?status=` (repeatable). Confirmed applications, newest first, at most 500, and `counts` per status (FR-ONB-05) |
| GET | `/platform/applications/{application_id}` | `platform.tenants.read` | The application, `possible_duplicates`, a free `suggested_slug` (FR-ONB-05) and `referred_by` (`{code, tenant_id, tenant_slug, tenant_name}` of the business whose code the applicant brought, or null; Activate makes it the referral, FR-REF-02) |
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

Feedback from every business (issue #447, ADR-065, FR-FBK-03), served by `core.platform` through the `FeedbackInbox` port of
`core.feedback`:

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/platform/feedback` | `platform.tenants.read` | `?status=` (new, seen or done), `since` (an ISO 8601 instant: only what was sent after it), `limit` (1 to 200, default 50) and `offset`. `{items: [PlatformFeedbackItem]}` newest first across every business: the business's id, slug and name, the sender's name, kind, message, page path, app version, user agent, `has_screenshot`, status, reply, `linked_issue_number` and dates. Read through `platform_feedback_list` (V80) |
| PUT | `/platform/feedback/{feedback_id}/status` | `platform.tenants.manage` | `{status, reply_note?}`: forward only (new, seen, done); 422 `feedback_status_backwards`; the same status keeps it and its date and may change the reply (null keeps it, empty or white space only clears it); audited `platform.feedback.status_changed` with before and after |
| PUT | `/platform/feedback/{feedback_id}/linked-issue` | `platform.tenants.manage` | `{linked_issue_number}`: the number of the issue opened for the item (a whole number above zero, else 422), or null to clear it; 404 when there is no such item; audited `platform.feedback.issue_linked` with before and after. Through `platform_feedback_set_linked_issue` (V80) |
| GET | `/platform/feedback/{feedback_id}/screenshot` | `platform.tenants.read` | The picture (PNG or JPEG), read with the item's business bound (`TenantContext.callAs`); 404 when there is none |

Referrals and their commissions across every business (issue #464, ADR-069, FR-REF-03 to FR-REF-06 and FR-REF-08), served by
`core.platform` through the `Referrals` port of `core.referrals`. Every change takes a `reason` (5 to 300 characters) kept in the
platform audit log; none takes an authenticator code (they record money moved outside the app, as a subscription payment does).
The app never pays a commission; amounts are minor units.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/platform/referrals` | `platform.tenants.read` | `?referrer_tenant_id=`, `status=` (`applied`, `active`, `paying`, `ended`; else 422), `commission=` (`due`: something left to pay, `paid`: something paid), `limit` (1 to 500, default 100), `offset`. `{items: [PlatformReferral]}` newest first: the referrals and the open, confirmed applications that carry a code, each with the referrer (id, slug, name), the code, the referred business (id, slug, name; only the name for an application), `status`, `source` (`sign_up`, `operator`), `joined_at`, `started_at`, `ended_at`, `rule_override` and `totals` (`[{currency, earned_minor, paid_minor, due_minor}]`) |
| GET | `/platform/referrals/export` | `platform.tenants.read` | The same filters as CSV (`text/csv`, `referrals.csv`): one row per referral and currency, at most 10,000; a text cell starting with `=`, `+`, `-`, `@`, a tab or a carriage return is neutralised, and amounts stay numbers (a negative one too); audited `platform.referral.exported` |
| GET | `/platform/referrals/{referral_id}` | `platform.tenants.read` | `{referral, rule_in_force, rule_source, lines}`: the rule a payment recorded now would use (`override`, `default` or `none`; none for an ended referral) and every line oldest first with its kind (`payment`, `manual`, `reversal`), amount, `months_counted`, `rule_snapshot`, note and paid columns; 404 for an unknown id |
| PUT | `/platform/tenants/{tenant_id}/referrer` | `platform.tenants.manage` | `{referrer_code, reason, totp_code}`: sets or corrects the business that referred this one by its code (any case), or clears it with a null or empty code; 422 `referral_self` for the business's own code, 422 `referral_code_unknown`, 404 for an unknown tenant. A change ends the current referral and starts one with source `operator`; the same referrer again changes nothing and writes nothing. `{tenant_id, referral}` (the current referral or null). Audited `platform.referral.referrer_changed` with before and after. Needs `totp_code`, a current code from the operator's own authenticator (step-up, used once; 422 `invalid_mfa_code`, 409 `mfa_not_enrolled`), checked before the change. |
| GET | `/platform/referrals/default-rule` | `platform.tenants.read` | `{rule}`: the platform default commission rule, or null (nothing is worked out) |
| PUT | `/platform/referrals/default-rule` | `platform.tenants.manage` | `{rule, reason, totp_code}`; `rule` is `{kind, percent_bp?, amount_minor?, currency?, months?, cap_minor?}` (`percent_of_payment`: `percent_bp` 1 to 10000, `months` 1 to 120 or null, `cap_minor` with its `currency`; `fixed_first_payment`: `amount_minor` 1 to 50,000,000 and `currency`, no months; `fixed_per_month`: `amount_minor`, `currency`, `months` or null), or null to clear it; 422 `validation_failed` in plain words for a shape that does not fit the kind. Applies to payments recorded from then on. Audited `platform.referral.default_rule_changed`. Needs `totp_code`, a current code from the operator's own authenticator (step-up, used once; 422 `invalid_mfa_code`, 409 `mfa_not_enrolled`), checked before the change. |
| PUT | `/platform/referrals/{referral_id}/rule` | `platform.tenants.manage` | `{rule, reason, totp_code}` as above: this referral's own rule, or null so the default applies; 422 `referral_ended` for a referral that was moved to another referrer. Needs `totp_code`, a current code from the operator's own authenticator (step-up, used once; 422 `invalid_mfa_code`, 409 `mfa_not_enrolled`), checked before the change. Audited `platform.referral.rule_changed`. Returns the detail |
| POST | `/platform/referrals/{referral_id}/commissions` | `platform.tenants.manage` | `{amount_minor (1 to 1,000,000,000), currency, reason}`: a line the operator worked out (kind `manual`, the reason as its note). **M**: needs an `Idempotency-Key` (its key row is the referrer's); 201 with the detail, and a repeat adds nothing and answers 200 with the detail. Audited `platform.referral.commission_added` |
| POST | `/platform/referrals/commissions/{line_id}/reverse` | `platform.tenants.manage` | `{reason}`: a line of the exact opposite amount (kind `reversal`); 409 `commission_line_already_reversed`, 422 `commission_line_is_reversal`. Audited `platform.referral.commission_reversed`. Returns the detail |
| POST | `/platform/referrals/commissions/{line_id}/paid` | `platform.tenants.manage` | `{paid_on, method (mtn_momo, airtel_money, bank_transfer, cash, other), reference?, note?, reason}`: records that the line was paid outside the app; a day from 1 Jan 2026 to today; 409 `commission_already_paid`, 409 `commission_line_reversed` for an unpaid line that was reversed. Audited `platform.referral.commission_paid`. Returns the detail |

`POST /platform/tenants/{tenant_id}/payments` also adds the referrer's commission line when the business has a current referral and
a rule is in force (FR-REF-05), in the payment's transaction and behind its Idempotency-Key; the payment's answer is unchanged.

The sign-up endpoints sit under `/platform` so they are served only on the platform host and
resolve no tenant; they need no token. Their link token travels in the body: the emailed link
carries it in the URL fragment, which no server receives.

### 7.11.4 Tenant administration

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/settings` | `core.settings.read` | |
| PATCH | `/settings` | `core.settings.manage` | FR-TEN-08. The SMS settings (`sms_sender_name`, `sms_window_start`, `sms_window_end`, `sms_include_balance`) are no longer here: they are in `PUT /settings/sms` since #218, and this route refuses them (400, unknown property). Includes `theme_primary` (`#RRGGBB`, or an empty string to clear it back to the platform look; a colour with no readable text colour is a 422 `validation_failed` with the field error `insufficient_contrast`) and `setup_dismissed`. The two-step policy (FR-IAM-06, FR-IAM-16, ADR-046) is here too: `require_mfa_all_staff` (everyone), `require_mfa_roles` (staff role keys; an empty list clears the choice; `member` and unknown keys are a 422 `validation_failed` with the field `require_mfa_roles`) and `mfa_grace_days` (0 to 90). Sending `mfa_grace_days` sets `mfa_enforce_from` (in the response) to the start of the day after today plus the days in the business's own time zone, returned as `timezone` (a grace of N days lasts to the end of the Nth day), or clears it for 0; turning the policy off clears it; any other change, narrowing included, leaves it. Turning the policy off, narrowing it, or putting a date back (a later date, or a date for people required at once) needs `totp_code` (an administrator with an authenticator) or `password` (without one), as the admin reset of a sign-in does (422 `totp_required`, `password_required`, `invalid_mfa_code`, `invalid_password`), and emails the administrators who may change the settings; the proof is never stored or logged The response also carries `mfa_grace_days` and `mfa_enforce_from`. Audited as `core.settings.updated` with the keys that changed |
| PUT | `/settings/logo` | `core.settings.manage` | `multipart/form-data` field `file`: PNG, JPEG or WebP, 1 MB, 128 px or more; re-encoded and scaled to 512 px; returns the settings. Takes no `If-Match` but bumps the settings `version`, so a form holding the old version must refetch before its next `PATCH`. A logo over 4 megapixels is 422 `image_too_large`. FR-TEN-08 |
| DELETE | `/settings/logo` | `core.settings.manage` | Clears the logo; the file is kept. Idempotent |
| GET | `/branding` | public | `{display_name, theme_primary, theme_text, logo_url, modules}` for the tenant of the request host; `logo_url` is `/api/v1/branding/logo?v=<document id>` or null; `modules` is the sorted list of enabled module keys (`lending`, `retail`), so the landing page promises only what the tenant has (#99). FR-TEN-08 |
| GET | `/branding/logo` | public | The logo image: `Content-Type` from the stored value (`image/png` or `image/jpeg`), `Cache-Control: public, max-age=3600`, `ETag` (304 on `If-None-Match`), `X-Content-Type-Options: nosniff`, `Content-Disposition: inline`, `Content-Security-Policy: default-src 'none'; sandbox`; 404 `not_found` when there is no logo; 503 `logo_unavailable` when storage cannot be read; `Vary: Host`; the optional `v` parameter must be a UUID and only busts caches. FR-TEN-08 |
| GET | `/branding/app/manifest.webmanifest` | public | **Built (#446, ADR-066).** The installed app's web app manifest (`application/manifest+json`) for the business of the request host: `id` and `start_url` `/staff`, `scope` `/`, `display` `standalone`, `name` the display name, `short_name` its first 12 characters as a reader counts them (grapheme clusters, so an emoji is never split), cut at the last word boundary when that keeps at least 6, `theme_color` the readable theme colour or `#0077B6`, and the four icons below with `?v=<render version>-<logo id>-<colour>`; with no logo the BMS icons under `/icons/`; on a host that is no business (the platform host, an unknown name) the BMS manifest, never 404. `Cache-Control: no-cache`, `ETag` (304 on `If-None-Match`), `Vary: Host`. FR-TEN-08 |
| GET | `/branding/app/{icon}` | public | **Built (#446, ADR-066).** `{icon}` is `icon-192.png`, `icon-512.png`, `icon-maskable-192.png`, `icon-maskable-512.png` or `apple-touch-icon.png` (180 px), anything else 404: a PNG square of the theme colour with the logo centred (within 80 percent of the side; for a maskable icon inside the 40 percent radius safe circle), drawn once per drawing version, logo, colour and size (one render however many requests miss at once; kept per business, so a new logo or colour replaces the old icons), the ETag over the same, `Cache-Control: public, max-age=3600`, `ETag` (304), `Vary: Host`, `nosniff`; 302 to `/icons/{icon}` (the BMS icon, `no-cache`) when there is no logo, no business at the host or storage cannot give the logo, or the stored logo cannot be drawn (never a 500; the failure is logged once and kept for that logo and colour). `v` only busts caches. FR-TEN-08 |

The branding routes are public by design: they take no token, the tenant is resolved from the
host like every tenant route (the manifest and icons of `/branding/app/` answer on any host with the BMS
ones where there is no tenant, ADR-066; the rest as follows) (an unknown host is 404 `unknown_tenant`; the platform host has no
tenant, so they answer 404 there and the PWA shows the Rincoltech brand without calling them), and
they work for a suspended tenant (reads). They expose only the display name, a colour and one
re-encoded image of that tenant, and the keys of its enabled modules (no counts, plans or settings).
| GET | `/branches` | `core.branches.read` | |
| POST | `/branches` | `core.branches.manage` | FR-BR-01 |
| GET, PATCH | `/branches/{branch_id}` | read / manage | The branch carries `status` (`inactive` is closed), `stocks_goods`, `sells_goods` (false is a Store, ADR-048), `closed_at`, `closed_reason`. A closed branch that holds stock also carries `stock_items` and `stock_value_minor`. PATCH also takes `sells_goods` (FR-RET-50): false makes the branch a Store and is refused with 422 `branch_has_open_credit_sales` while a credit sale made there still has a balance owing, or 422 `store_must_stock` when the branch would also keep no goods; POST `/branches` takes the same optional field. PATCH also takes `stocks_goods` (FR-BR-08): false is refused with 422 `branch_still_has_stock` while the branch holds stock, a transfer is on the road or a stock-take is open (the problem carries `stock_items`, `stock_value_minor`, `transfers_in_flight`, `open_stocktakes`) |
| POST | `/branches/{branch_id}/close` | `core.branches.manage` | FR-BR-07. `If-Match` required; body `{reason?}`. Closes the branch and keeps its history; 422 `head_office_required`, `branch_has_open_accounts`, and `branch_close_needs_reason` while stock or transfers on the road remain and no reason is sent (the problem carries `currency`, `stock_items`, `stock_value_minor`, `transfers_in_flight`, `transfers_in_flight_value_minor`); 409 `invalid_status_transition` when already closed. Audit `core.branch.closed` (with the reason and figures) |
| POST | `/branches/{branch_id}/reopen` | `core.branches.manage` | FR-BR-07. `If-Match` required. 409 `invalid_status_transition` when already open; 422 `plan_limit_reached`. Audit `core.branch.reopened` |
| GET | `/users` | `core.users.read` | Each user carries `is_owner` (ADR-062) |
| POST | `/users` | `core.users.manage` | Invite; returns the user and the one-time link once (`invitation.url`, `invitation.expires_at`). FR-IAM-01. Inviting someone as `tenant_admin` is the Owner's alone (403 `owner_only`, ADR-062, FR-IAM-18) |
| GET, PATCH | `/users/{user_id}` | read / manage | PATCH of another administrator's name or contact is the Owner's alone (403 `owner_only`; 403 `owner_protected` on the Owner). One's own is open to every administrator. The Owner changing their own email or phone also sends `totp_code` (with an authenticator) or `password` (without one), checked before the change (422 `totp_required`, `password_required`, `invalid_mfa_code`, `invalid_password`; 422 `step_up_required` if the check was skipped); the old email address is then told (`identity.contact_changed`), or the old phone by SMS for an Owner with no email where the business's SMS is on, and the Owner's transfer waits 24 hours. Every change of email or phone sets `users.contact_changed_at` (ADR-062) |
| PUT | `/users/{user_id}/roles` | `core.users.manage` | `[{role_key, branch_id or null}]`. Changing another administrator's roles, or giving `tenant_admin` to someone who is not one, is the Owner's alone (403 `owner_only`, `owner_protected`); the Owner cannot take `tenant_admin` off themselves (422 `owner_must_transfer_first`) |
| POST | `/users/{user_id}/deactivate` | `core.users.manage` | Revokes every session. FR-IAM-08. An administrator only by the Owner (403 `owner_only`); the Owner by nobody (403 `owner_protected`) |
| POST | `/users/{user_id}/invitation` | `core.users.manage` | New one-time link for a user still invited; the previous link stops working. FR-IAM-01. For an invited administrator, the Owner's alone (403 `owner_only`) |
| POST | `/users/{user_id}/mfa/reset` | `core.users.manage` | Another user only. FR-IAM-12. Another administrator's only by the Owner (403 `owner_only`, `owner_protected`) |
| POST | `/users/{user_id}/transfer-ownership` | `core.users.manage` | **Built (#442, ADR-062).** The Owner only. Body `{totp_code}` when the Owner has an authenticator enrolled, otherwise `{password}`, the Owner's own current password (the same proof, codes and lock counting as `reset-sign-in`). An optional `reason` (at most 300 characters) is kept in the audit row. Makes another **active administrator** of the business the one Owner and returns them (`is_owner: true`); the old Owner stays an administrator. Checked before the proof is spent: 403 `owner_only` (the caller is not the Owner), 409 `no_owner` (the business has none; the operator assigns one), 422 `already_owner` (the caller themselves), 422 `owner_recently_changed` (the Owner's email, phone, password or two-step sign-in changed in the last 24 hours), 422 `owner_must_be_active_admin` (not an administrator, invited or deactivated), 404 for a user of another business; checked again under a per-business lock (409 `owner_changed` if the Owner changed at the same moment). Audited `core.user.ownership_transferred` with the old and new Owner and `after.reason`. FR-IAM-18 |
| POST | `/users/{user_id}/unlock` | `core.users.manage` | **Built (ADR-059).** Body `{reason}` (5 to 300 characters, kept in the audit row). Lifts the sign-in lock of a staff user of the tenant (any role) and starts the failure count again; returns the user. Audited `core.auth.unlocked` with whether it was locked, the count and the reason (`by: tenant_admin`). Changes no password, second factor or session, so no step-up. 404 for an id that is not a staff user of the tenant (a platform operator is never one). FR-IAM-05 |
| POST | `/users/{user_id}/reset-sign-in` | `core.users.manage` | **Built (#320).** Body `{totp_code}` when the acting admin has an authenticator enrolled (422 `totp_required` without it, 422 `invalid_mfa_code` for a wrong or used code), otherwise `{password}`, the admin's own current password (422 `password_required` without it, 422 `invalid_password` for a wrong one, counted on the sign-in lock: the fifth locks the account, 423 `account_locked`, and ends the session that sent it, only that one); two-step sign-in is optional where the `tenant_admin` role's `mfa_required` was hand-edited off (staging only; the V2 seed requires it, so elsewhere an admin with no factor never gets a session), and an admin without it is not locked out of this route there; a caller with no staff account is refused (401). Neither credential is ever logged. The target is checked before the code is spent or a wrong password is counted. Issues a one-time link to choose a new password for an **active** user who is **not a tenant admin and not the caller** and returns `{url, expires_at}` once (one hour; the same shape as the invitation link; `Cache-Control: no-store`; never emailed on this path, but the user is emailed that one was issued, without the link; audited as `core.password_reset.requested` and `core.password_reset.link_revealed`, without the token). An earlier open link of the user is revoked. 409 `cannot_reset_own_sign_in` (use Forgot password), 409 `admin_reset_by_operator` (a tenant admin is reset by the platform operator), 409 `invalid_status_transition` for a user still invited (use `/invitation`) or deactivated; 404 for a user outside the admin's branch scope (a user whose every role lies in the admin's branches; a role over every branch, or no role, needs an admin over every branch). Sessions end when the link is used. FR-IAM-15, ADR-042 |
| GET | `/roles` | `core.users.read` | Catalogue with permissions (`mfa_required` is the platform-wide flag of the role, false for every seeded role since V67; the tenant's own requirement is in `GET /settings`) |
| GET | `/audit-events` | `core.audit.read` | Filters per FR-AUD-04 |
| POST | `/audit-events/export` | `core.audit.export` | Same filters as the search; CSV, at most 10,000 rows, synchronous until the report runs of increment 8 (FR-RPT-04); the export is audited |

**UI notes: Two-step sign-in (issue #348, FR-IAM-06, FR-IAM-16, ADR-046).** No new route (`POST /auth/staff/mfa/enrol` takes `password` when called with a session, 422 `password_required`; `POST /auth/refresh` answers `sign_in_required`, with no MFA token, and ends the session family for a person required to have a factor who has none; `GET /me` carries `tenant_timezone`): the policy is read and changed through
`GET` and `PATCH /settings`, a person's own set-up uses `POST /auth/staff/mfa/enrol` and `/mfa/confirm` with their session, and `GET /me` carries
`mfa_due_by` and `two_step_policy`. Business set-up links to `/staff/setup/two-step` (shown to holders of `core.settings.manage`): three choices
(Optional, Required for the roles I choose, Required for everyone), the roles as the names of `GET /roles`, the grace period (none, 7, 14 or 30 days; 14 is
the suggestion), and a review in plain words before anything is sent: who will be asked, that nobody is locked out before the date, that the person saving
is asked too when covered, and what happens when a phone is lost. One tap sends one request; a stale settings version is shown as such. A covered person without a
factor sees a reminder with the date on every staff screen except their own set-up page (`/staff/two-step`, open to every signed-in staff user), and a holder of
`core.settings.manage` sees a recommendation on the home and set-up screens while the policy is off, which "Not now" puts away for 14 days in this browser only.

**UI notes: the staff Users screen (issue #319, FR-IAM-01, FR-IAM-02, FR-IAM-08).** The PWA's Users page
(`/staff/users`, `frontend/src/areas/staff/users/`) uses only the routes above; no API change. Shown to
holders of `core.users.read` (staff menu entry "Users" and a link on Business set-up); Invite someone,
Send a new link, Change roles and Deactivate are shown only to holders of `core.users.manage`. The list
is `GET /users` followed by its cursor, with `GET /roles` and `GET /branches` for the words; it shows each
person's name, phone then email, every role with its branch ("every branch" when `branch_id` is null) and
the status `invited`, `active` or `deactivated`, and searches in the browser as you type. The invite form
(`/staff/users/new`) is phone first (a Uganda mobile number, checked the way the member forms check it),
the email is optional, and one of the two is required; the role pick-list is the staff roles of
`GET /roles`, each shown by a plain name ("Sales person") with one sentence on what it may do (the key, for example
`retail_sales`, stays in the docs and the tours, not on a small phone screen); a branch pick-list
follows for every role except `tenant_admin`, which covers every branch and is sent without a
`branch_id`. After `POST /users` (and after `POST /users/{user_id}/invitation`) the one-time
`invitation.url` is shown once in a box with the expiry, a Copy button and a Send on WhatsApp button (a
`wa.me` link with a short message and the link, opened on the invited phone when there is one). The link
is held only in the screen's own state, never in a query cache, the browser storage or the URL, and is
gone when the box is closed; to get another, an administrator uses Send a new link, which replaces the
old one. Double taps send one request. Refusals are shown in plain words: `plan_limit_reached` (the plan
caps staff users; people still invited count), `last_tenant_admin`, `cannot_deactivate_self` (the screen
offers no Deactivate on one's own card), `duplicate_email`, `duplicate_phone`, `invalid_phone`.
Deactivating asks first and ends every session of the person (FR-IAM-08), and closes a link box that was
made for that person. **Reset sign-in (issue #339, FR-IAM-15, ADR-042)** is a button on the card of an
**active** person who is not a tenant admin and not the signed-in administrator (the server refuses the others with
409 `admin_reset_by_operator`, `cannot_reset_own_sign_in` and `invalid_status_transition`, so the screen does not offer
it); it needs `core.users.manage`. It opens a small form on the card that asks the administrator to prove it is still them, the
way the merchant account screen does: the 6 digits of their own authenticator app when `/me` says `mfa_enabled`,
otherwise their own password (two-step sign-in is optional, so an administrator without it is not locked out). It calls
`POST /users/{user_id}/reset-sign-in` through `resetUserSignIn`, clears what was typed as soon as it is sent, shows a
refusal (`invalid_mfa_code`, `invalid_password`, `account_locked`, the 409s) in plain words on the card, and shows the returned
link once in the same box as the invitation link (Copy, Send on WhatsApp with a message about choosing a new password,
closed with "I have sent it"), held only in the screen's state. The platform operator's route for a tenant admin still has no screen. A call that gets no answer (a dropped connection, a lost response) is worded as
"the answer did not reach your phone, open the staff list: if the person is there, tap Send a new link", and
a repeated phone or email on the invite screen points at the same list, because the first call may have
succeeded. The server's field problems (`unknown_branch`, `unknown_role`, `invalid`, `required`) are
shown in plain words, not as the server's sentence. A role on a branch that has since closed is listed as
"(closed)" in Change roles and cannot be saved until another branch is chosen. Taking the administrator role
off one's own card asks for a second tap with a warning, and the screen reads `/me` again after saving one's
own roles so the buttons it offers follow the new permissions. The search finds a name, an email, or a phone
typed in any usual way (`0700 000 002`, `+256700000002`, the last digits).

**UI notes: the Owner (issue #442, FR-IAM-18, ADR-062).** The Owner's card on the Users page has an **Owner** badge. An
administrator who is not the Owner is offered no action on the Owner's card or on another administrator's card, and the
administrator role is left out of the invitation's and Change roles' pick-lists for them (`canActOn`, `offeredChoices` in
`frontend/src/areas/staff/users/state.ts`; the server refuses the same with 403 `owner_only` and `owner_protected`); while a
business has no Owner the screen offers what it did before. The Owner sees a card (data-tour `staff-transfer-owner`) that says
only they can add, remove or change an administrator; while `/me` says `mfa_enabled` is false it carries a prompt to turn on
two-step sign-in with a link to `/staff/two-step` (nothing is forced). **Transfer ownership** opens a form
(`transfer-owner.tsx`) that offers only the other active administrators, says in one plain sentence what changes (they become the
owner, only they can then add, remove or change an administrator, you stay an administrator and cannot take it back yourself),
asks for the authenticator code or the Owner's own password as Reset sign-in does, clears it once sent, and sends one request for a
double tap; refusals are shown in plain words. The "Who can do what" card opens with an **Owner** line. The `page-users` tour is
version 5 with an Owner step. The operator's Business page marks the Owner among the administrators and offers **Make the owner**
on any other active administrator, with the operator's code and a reason, through `make-owner`, and **Send a new invitation link**
on an invited Owner, through the operator's `invitation` route, showing the 72 hour link once (tour `page-operator-business`
version 2).

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
| GET | `/settings/notifications` | `core.settings.read` | **Built (#412, ADR-057).** Which everyday push notices the business sends and when the daily summary goes, with the `ETag` of the tenant settings row: `{new_orders, order_updates, low_stock, credit_due, daily_summary, daily_summary_time, timezone, push_available, version}`. Every switch is on until switched off; the time is `HH:mm` on the business's clock, 20:00 by default; `push_available` is false while the server has no push keys |
| PUT | `/settings/notifications` | `core.settings.manage` | **Built (#412, ADR-057).** A partial change under `If-Match` (428 without it, 409 `version_conflict` when stale): the five switches (booleans) and `daily_summary_time` (`HH:mm`, a quarter hour up to 23:30, else 422). Audited as `core.notifications.settings.changed` with before and after; the safeguard notices of ADR-050 have no switch |
| GET | `/sms/opt-outs` | `core.notifications.read` | **Built (#218).** `{items, next_cursor}` newest first: `{id, kind [member, number], member_id, scope [reminders, all], source [staff, member, number, import], recipient_masked, created_at, created_by}`; `q` (the shared search over the masked number's digits; the term never in the address of the page), `scope`, `kind`, `limit` (50, at most 200), `cursor`; 400 for an unknown filter or a cursor from another list or search |
| POST | `/sms/opt-outs` | `core.settings.manage` | **Built (#218).** `{phone, scope, reason?}` (`reason`, 3 to 200 characters, is required, 422 `reason_required`, only when this narrows the number from `all` to `reminders`, and is audited): puts a number on the list (read by the strict SMS phone rule, 422 `invalid_phone` otherwise); 201 for a new row, 200 when the number was already there (its scope is set as asked); audited as `core.sms.opt_out.added` or `.changed` with the masked number |
| POST | `/sms/opt-outs/import` | `core.settings.manage` | **Built (#218).** `{numbers, scope, dry_run}` (default `dry_run` true): up to 2000 numbers separated by new lines, commas or semicolons; returns `{dry_run, rows_read, added, changed, unchanged, invalid, rows: [{line, outcome}]}` with outcomes `added`, `changed` (made stricter), `unchanged`, `duplicate_in_list`, `invalid_phone`; the numbers are never echoed; a pasted list never weakens a block; the valid lines are applied even when another line is invalid; 422 `too_many_rows` over 2000; audited per row and as `core.sms.opt_out.imported` |
| DELETE | `/sms/opt-outs/{opt_out_id}` | `core.settings.manage` | **Built (#218).** `{reason}` (3 to 200 characters, 422 `reason_required`): lifts a row of the tenant; 204; 403 for anyone but the tenant administrator; 404 for another tenant's row, a lifted one or an unknown one; audited as `core.sms.opt_out.removed` with the reason |
| POST | `/sms/test-message` | `core.notification_templates.manage` | **Built (#218).** `{event_key, text?, to}`: sends the wording (the saved one, else the default, or `text`) with fabricated data and the prefix `TEST: ` to one number, and only to the test sink or a number on the environment's allow-list: mode `off` or `live` sends nothing (`reason` `channel_off`, `live_mode_preview_only`), `sink` records it, `allowlist` sends only to a listed number and sinks any other; a send that would really go out first passes the operator's switch, the tenant's switch, the opt-out list and the caps (`reason` `operator_sms_off`, `tenant_sms_off`, `blocked_optout`, `cap_reached`, nothing sent). `{sent, outcome [sunk, sent, not_sent], reason, mode, recipient_masked, text, measure}`; 422 `invalid_phone` or the wording's code; 429 `rate_limited` over 10 an hour per business; not an outbox row; a real send is written to the delivery log as `test.<event>` and counts against the caps, a sink recording is not; audited with the masked number |
| GET | `/lending/members/{member_id}/sms-opt-out` | `lending.members.read` | **Built (#218).** `{opted_out, scope, recipient_masked, since}`; a member outside the caller's branch scope is 404 |
| PUT, DELETE | `/lending/members/{member_id}/sms-opt-out` | PUT `lending.members.update`; DELETE `core.settings.manage` | PUT `{scope: reminders \| all, reason?}`; audited; changing `all` to `reminders` is the administrator's (403 `permission_denied`) and needs the reason (3 to 200 characters, 422 `reason_required`), audited with it. DELETE `{reason}` (3 to 200 characters, 422 `reason_required`) lifts it and is audited with the reason. FR-NTF-14. **Built (#218):** the row is about the member and holds no number (it follows the member to any number); the same scope again writes and audits nothing; DELETE of nothing is a 200; a member outside the caller's branch scope or of another tenant is 404 |
| GET | `/notifications/push/config` | authenticated (staff) | **Built (ADR-050).** `{enabled, public_key}`: whether this server sends Web Push (both VAPID keys are set, or the dev profile made an ephemeral pair) and the application server key a browser subscribes against (unpadded base64url of the 65 byte P-256 point), null while off |
| GET | `/notifications/push/subscriptions` | authenticated (staff) | **Built (ADR-050).** The caller's own devices, newest first: `{items: [{id, label, created_at, last_used_at}]}`. Never an endpoint or a key; `label` reads "Chrome on Android" from the browser's user agent |
| POST | `/notifications/push/subscriptions` | authenticated (staff) | **Built (ADR-050).** `{endpoint, keys: {p256dh, auth}}` (a browser's `PushSubscription.toJSON()`); the `User-Agent` header is kept, cut to 300 characters. Registers this device for the caller, or refreshes it: one row per endpoint in the tenant, moved to the caller when another person used the device before. Returns the device (the status is 201 for both). 409 `push_not_available` while push is off; 422 `validation_failed` on `endpoint` when it is not `https` on port 443 at a known push service host (or one in `BMS_PUSH_EXTRA_ENDPOINT_HOSTS`), and on `keys` when the point is not on the P-256 curve or the secret is not 16 bytes. At most 10 devices per person: the least recently used goes. Audited as `core.push.subscribed` without the address |
| POST | `/notifications/push/subscriptions/unsubscribe` | authenticated (staff) | **Built (ADR-050).** `{endpoint}`: removes the caller's device with that endpoint; 204 whether or not there was one. Another person's device is never touched |
| DELETE | `/notifications/push/subscriptions/{device_id}` | authenticated (staff) | **Built (ADR-050).** Removes one of the caller's own devices; 204; 404 for another person's, another tenant's or an unknown id. Audited as `core.push.unsubscribed` |

The notices sent through the `push` channel (ADR-050 decision 10, ADR-057 decision 2), each a `PushNotice` published in the transaction of
its cause and queued once per person with a device (key `push:<template>:<event>:<user>`): the six safeguards of ADR-050, and since #412
`retail.order_new` (an order from a source other than walk-in, to the holders of `retail.order.manage` given for that shop, not the taker),
`retail.order_out_for_delivery` and `retail.order_delivered` (to the taker, unless they did it), `retail.low_stock` (to the holders of
`retail.stock.read` at that branch and the administrators, once per item, branch and day), `retail.credit_due` (the administrators) and
`retail.credit_due_branch` (holders of `retail.sale.all_sellers` given for that shop) from 08:00, and `retail.daily_summary` (the
administrators) at the business's time. None names a customer, a phone or an amount; a tap opens the order, Stock health, Credit control
or the day's sales.
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
| POST | `/payments/intents` | `core.payments.collect` or `member.self.pay` | **M**. FR-PAY-01. `{purpose, subject_id, amount_minor, payer_phone}`; returns the intent with `redirect_url` once the order exists. 422 `collections_disabled`, `merchant_account_not_verified`, `exceeds_payoff`, `subject_not_payable` (ADR-035). Purpose `lending.savings_deposit` (issue #219): `subject_id` is a savings account, which must be active or dormant and in the tenant's currency (a frozen or closed account is `subject_not_payable`); a first deposit below the product's minimum opening balance is refused `below_minimum_opening`; the caller needs `core.payments.collect` in the account's branch; the money goes only into the lender's own verified merchant account (`merchant_account_not_verified` otherwise). The booked deposit is the cash deposit of section 7.11.15 with method `gateway` (`source` `gateway`, the order tracking id as `external_reference`; the provider's confirmation code is on the payment intent). Purpose `lending.loan_repayment` (issue #216): `subject_id` is a loan, which must be active and in the tenant's currency; the amount is at most today's payoff quote; the caller needs `core.payments.collect` in the loan's branch; the money is collected only into the lender's own verified merchant account, and a tenant without one is refused with `merchant_account_not_verified` before any order is made. The booked repayment is the cash repayment of section 7.11.13 with method `gateway` (`source` `gateway`, the order tracking id as `external_reference`; the provider's confirmation code is on the payment intent) |
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
due date, total paid, credit balance, and since increment 6 the PAR bucket `par_bucket` and
`penalties_assessed_minor`, every penalty charged so far), and the list takes `q` (loan or member number by prefix,
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
| GET | `/lending/loans/{loan_id}/charges` | `lending.loans.read` | R-PEN: each charge with `waived_minor` and `waivable_minor` (unwaived and unpaid) |
| POST | `/lending/loans/{loan_id}/charges/{charge_id}/waive` | `lending.charges.waive_request` | **M A**. FR-ARR-04. `{amount_minor?, reason}`; without an amount, all that is waivable. 201 at once below the `charge_waiver` threshold, else 202 |
| POST | `/lending/loans/{loan_id}/write-off` | `lending.loans.write_off_request` | **M A**. FR-LCL-02 |
| POST | `/lending/loans/{loan_id}/restructure` | `lending.loans.restructure_request` | **M A**. FR-LCL-04 (P2) |
| PUT | `/lending/loans/{loan_id}/officer` | `lending.collections.assign` | FR-CLN-01. `{officer_user_id}`; `{officer_user_id, reassigned}`; 422 `officer_not_eligible` |
| GET, POST | `/lending/loans/{loan_id}/collection-actions` | `lending.collections.read` / `lending.collections.log_action` | FR-CLN-04. Newest first. POST `{action_type, note?, promised_date?, promised_amount_minor?}`, 201; 422 `invalid_action_type`, `note_required`, `promise_incomplete`, `promised_date_in_past`, `promise_fields_not_allowed` |
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
| POST | `/lending/savings-accounts/{account_id}/transactions/{txn_id}/reverse` | `lending.savings.withdraw` | **M A** (`savings_reversal`, always checked). `{reason}`. A deposit or a withdrawal with its fee. 422 `not_reversible`, `insufficient_balance`, `gateway_reversal_pending` (a deposit collected by mobile money, until #224); 409 `already_reversed` |
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
| GET | `/lending/collections/due-list` | `lending.collections.read` | `?date_from&date_to&officer_user_id&branch_id&limit`. FR-CLN-02. Today by default, at most 92 days (422 `invalid_range`, `range_too_long`); at most `limit` lines (200, up to 500) and `truncated`; `phone` masked without `lending.members.contact` in the loan's branch; without `lending.collections.all_officers` only the caller's own loans (ADR-064) |
| GET | `/lending/collections/arrears` | `lending.collections.read` | `?bucket=1_30&officer_user_id&branch_id&limit`. FR-CLN-03. `{buckets: [{par_bucket, loans, arrears_minor, items}], truncated}`: the totals count every loan, the items at most `limit` (200, up to 500); `phone` masked as on the due list; 422 `invalid_bucket` |
| POST | `/lending/collections/reassign` | `lending.collections.assign` | `{loan_ids, officer_user_id, reason}` (reason 5 to 300 characters, audited; also on `PUT /lending/loans/{loan_id}/officer`), at most 500, all or none (404 when any is out of scope) |
| GET | `/lending/collections/promises` | `lending.collections.read` | `?promise_status=pending&officer_user_id&branch_id&limit`; at most `limit` (200, up to 500) and `truncated`; 422 `invalid_promise_status` |

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
| POST | `/retail/products` | `retail.catalogue.manage` | `{code, description, category_id, unit_id, cost_minor, sell_minor, duplicate_reason?}`; writes the `initial` history row; 409 `duplicate_product_code`. **A name is kept once per unit (issue #352, ADR-044, FR-RET-44):** a description whose key (lower case without accents, spaces and punctuation collapsed) another product of the same unit has is refused with 422 `duplicate_product_name`, the problem body carrying `existing_id`, `existing_code`, `existing_name` and `may_add_anyway` (true for a holder of `core.settings.manage`); that administrator repeats the request with `duplicate_reason` (5 characters at least, stored on the product and in the audit entry); anyone else gets 403 `permission_denied`. The same name in another unit is allowed. FR-RET-01 |
| POST | `/retail/product-families` | `retail.catalogue.manage` | Issue #384, FR-RET-51: `{name, base_code, category_id, unit_id, cost_minor, sell_minor, sizes?, colours?, variants?}`. Without `variants` every combination of `sizes` and `colours` is made (sizes first, in the order given; either list may be empty), each coded `BASE-SIZE-COLOUR` from the letters and digits of each part in capitals, seven at most (`ROMP-03M-BLUE`), at the item's prices; with `variants` (`[{size?, colour?, code?, cost_minor?, sell_minor?}]`) exactly those. At most 100. 201 with the item (`RetailFamily`) and its `ETag`. 409 `duplicate_product_code` naming the code, 409 `duplicate_variant` (a size and colour named twice), 422 `duplicate_variant_code`, `too_many_variants`, `duplicate_product_name` (FR-RET-44). Audited as `retail.product_family.created` and one `retail.product.created` per variant |
| GET | `/retail/product-families/{family_id}` | `retail.stock.read` | `{id, name, base_code, category_id, category, unit_id, unit, sizes, colours, variants, stock_on_hand, stock_by_branch: [{branch_id, qty}], created_at, version}`: every variant (active or not, in the item's order) is a `RetailProduct` with `stock_on_hand` and `stock_branches`; the stock is summed over the branches the caller may read. Cost only with `retail.profit.read` |
| PATCH | `/retail/product-families/{family_id}` | `retail.catalogue.manage` | `{name?, category_id?}` under `If-Match` (428, 409 `version_conflict`). A new name rewrites every variant's description (each passes FR-RET-44), a new category moves every variant. Audited as `retail.product_family.updated` |
| POST | `/retail/product-families/{family_id}/variants` | `retail.catalogue.manage` | `{sizes?, colours?, variants?, cost_minor?, sell_minor?}`: as the create; a generated combination the item has already is skipped (422 `nothing_to_add` when none is left), a named one is refused (`duplicate_variant`); prices default to the item's first variant. An item never has more than 100 sizes and colours, counting those it has (422 `too_many_variants`). Each new size takes the first variant's active pack sizes (while the units capability is on) and its price list prices in force or coming, on the rules of a list price set by hand (issue #395): only while the price lists capability is on and the caller also holds `retail.pricelist.manage`; only in the size's own unit or a pack copied with it; only to a size that keeps the first variant's cost and selling price exactly; never below the new size's cost. Each copy is audited as `retail.price_list_item.created`. 201 with the item and `terms_copied: {packs_copied, price_list_items_copied, price_list_items_skipped?, price_list_items_not_copied: [{product_id, price_list_id, unit_id, min_qty, price_minor, reason}]}`; `price_list_items_skipped` is `price_list_permission` for every caller without `retail.pricelist.manage`, whether or not the size has list prices and whatever the capability (nothing of the lists is shown), or `price_lists_off`, and `reason` is `unit_not_sold`, `price_differs`, `below_cost` (only with `retail.profit.read`) or `not_copied` (the same without it), so the owner knows which list prices to set |
| POST | `/retail/product-families/join`, `/retail/product-families/{family_id}/join` | `retail.catalogue.manage` | Issue #384: `{name?, base_code?, category_id?, items: [{product_id, size?, colour?}], duplicate_reason?}`: items kept apart (one per size) become the sizes and colours of a new item (201) or of one that exists (200); each keeps its id, code, prices, stock and history, takes the item's name with its size and colour and the item's category; locked in id order after the item. 422 `already_has_variants`, `unit_differs`, `duplicate_product_name`; 409 `duplicate_variant`. Audited per product (`retail.product.updated`) and as `retail.product_family.created` or `.joined`. Into an item that exists (`/{family_id}/join`) only `items` is taken: `name`, `base_code`, `category_id` and `duplicate_reason` are refused with 422 `validation_failed` (field problem `not_allowed`), and the item may not pass 100 sizes and colours (422 `too_many_variants`). A joined product's `duplicate_reason` is cleared and the old value is kept in the `before` of its `retail.product.updated` audit row (as for `POST /retail/products/{id}/family`) |
| POST | `/retail/products/{product_id}/family` | `retail.catalogue.manage` | `{size?, colour?, sizes?, colours?, variants?}`: an item without variants gets sizes and colours. It becomes the variant of `size` and `colour` (at least one; the stock it holds now), keeping its id, code, prices, stock and history; the item takes its name (200 characters at most), category and unit and a base code of its code's first 24 characters; the other combinations are made at its prices. The item may not pass 100 sizes and colours, the one it becomes counted (422 `too_many_variants`). The new sizes take its pack sizes and price list prices on the rules of `POST /retail/product-families/{family_id}/variants`, with the same `terms_copied` in the answer. 201 with the item; 422 `already_has_variants` |
| POST, DELETE | `/retail/products/{product_id}/photo`, `/retail/product-families/{family_id}/photo` | `retail.catalogue.manage` | Issue #384, FR-RET-58, ADR-053: multipart with one `file`, PNG, JPEG or WebP, 5 MB (413 `file_too_large`, 415 `unsupported_file_type`, 422 `svg_not_allowed`, `image_too_small`), re-encoded and scaled to 1200 px; 200 with the product or the item; a product's `photo_id` is its own photo and `item_photo_id` its item's (each absent when none). A 240 px small copy is stored with each photo. DELETE takes the photo off and keeps the file. Audited as `retail.product.photo_changed` or `retail.product_family.photo_changed` |
| GET | `/retail/products/{product_id}/stock-minimum` | `retail.stock.read` | Issue #412, FR-RET-63, ADR-057: "Warn me when stock is below". `{product_id, min_qty?, can_set_every_branch, lead_days, branches: [{branch_id, branch_name, store, min_qty?, applies_qty?, can_set}]}`: the level for every shop and store, and for each stocking branch the caller reads its own level, the level that applies (its own, else the one for every branch) and whether the caller may set it. Quantities are strings with three places in the item's base unit; 404 for an unknown item |
| PUT | `/retail/products/{product_id}/stock-minimum` | `retail.catalogue.manage` in every branch (403 for a branch-scoped holder) | `{min_qty}` (above 0, at most three places, 422 otherwise; null removes the level; nothing changed writes nothing). Answers as the GET. Audited as `retail.stock_minimum.changed` |
| PUT | `/retail/products/{product_id}/stock-minimum/branches/{branch_id}` | `retail.stock.read` and, at that branch, `retail.catalogue.manage` or `retail.stocktake.commit` (403 otherwise) | `{min_qty}`: the branch's own level, which wins over the level for every branch (null removes it, so the level for every branch applies again); 422 `unknown_branch` for a branch outside the caller's scope. Audited as above |
| GET | `/retail/photos/{photo_id}` | `retail.stock.read` | Issue #384, ADR-053: `size=small` for the 240 px copy (the full image when a photo has none; 422 for any other size); the image bytes of an item photo from the same origin, read with no database connection held while the bytes are fetched, `Cache-Control: max-age=300, must-revalidate, private`, `Vary: Cookie, Authorization`, the checksum as `ETag`; 404 unless it is an item photo of the caller's tenant, 403 (audited) without `retail.stock.read`. Not audited per read |
| GET | `/retail/products/similar` | `retail.catalogue.manage` | Issue #352: `description` (required), `unit_id`, `exclude_id` (the item being edited). `{exact: [...], similar: [...]}`, each `{id, code, description, unit, active, distance, same_unit}`: `exact` is the same name in the unit asked about (a save is refused), `similar` the same name in another unit (distance 0) or a name a few letters apart (edit distance of the keys at most 1, 2 or 3 for keys under 10, under 20 and longer; none under 5), at most 8, nearest first. Nothing is written; the items form asks before it saves |
| GET, PATCH | `/retail/products/{product_id}` | read / `retail.catalogue.manage` | PATCH needs `If-Match` and edits `code`, `description`, `category_id`, `unit_id`, `active` only; a price in the body is 400. A change of `description` or `unit_id` is checked for a duplicate name as on create (422 `duplicate_product_name`, `duplicate_reason` for an administrator); an edit that leaves both alone is never blocked by an older duplicate. A change of `unit_id` is refused with 422 `unit_in_use_by_stock` once the item has any stock movement or a balance that is not zero (a unit is never converted, ADR-044). A change of `category_id` is audited (`retail.product.updated`) with the old and the new category by id and by name (issue #351). `active: false` marks the item inactive, never deleted (issue #342, ADR-045, FR-RET-47): a sale, a restock, a purchase order line and a stock transfer that name it are refused with 422 `product_inactive`; there is no delete route. The GET of one product also carries `stock_on_hand` (the stock summed over the branches the caller may read, a three place string), and `stock_branches` (in how many of those branches the balance is not zero), which the list rows do not. A receipt against a sent order line still receives an inactive item Issue #384: a variant's response carries `variant: {family_id, family_name, size?, colour?, position}` (absent for an item without variants); PATCH of a variant takes `variant_size` and `variant_colour` (an empty string removes one; one must remain; 409 `duplicate_variant`) and refuses a new description (422 `variant_name_follows_item`) or category or unit (422 `variant_follows_item`); on an item without variants they are refused with 422 `not_a_variant`. |
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
| GET | `/retail/stock` | `retail.stock.read` | `branch_id`, `query` (code, description or category name), `category_id`, `negative_only`, `include_inactive` (default false: an inactive product, issue #342, ADR-045, is left out of the list even while it holds stock; true brings it back), `stock_level` (`out`: quantity zero or less; `low`: at or below the low stock threshold, which includes out of stock; any other value is 422), `limit`, `cursor`; the page also carries `low_stock_threshold` (a quantity string, `5.000`, one constant for every tenant until a retail settings group exists, `StockService.LOW_STOCK_MILLI`, ADR-029, #145). Row: `{product_id, code, description, category_id, category, unit, qty, negative, sell_minor, cost_minor*}`. FR-RET-03 |
| GET | `/retail/stock/all-branches` | `retail.stock.read` | Every product with its balance in each open branch the caller may read (#144). A closed branch that still holds a non-zero balance is left out unless `include_closed=true` (#329, ADR-041), when it is a column flagged `closed`; `closed_branches` counts the ones left out, so the screen offers Show closed. Parameters: `query` (code, description or category name), `category_id`, `negative_only` (any branch below zero), `stock_level` (as on `/retail/stock`, judged on the total across the listed branches), `include_closed`, `include_inactive` (as on `/retail/stock`), `limit`, `cursor`. `{low_stock_threshold, closed_branches, branches: [{id, code, name, head_office, closed}], items: [{product_id, code, description, category_id, category, unit, total_qty, negative, sell_minor, cost_minor*, balances: [{branch_id, qty, negative}]}], next_cursor}`; `balances` has one entry per listed branch, zero where no balance exists; `cost_minor` shows only when the caller holds `retail.profit.read` in every listed branch. Reports without `branch_id` (`/retail/reports/valuation`, `/retail/reports/profit/daily`) already cover every branch in scope, with a total per branch |
| GET | `/retail/stock/summary` | `retail.stock.read` | The stock list grouped by shop and category, summed on the server (issue #350; no migration). `group_by` (required: 400 when absent) is an ordered list of `branch` and `category`, each at most once; anything else is 422 (the items themselves are the stock list, `GET /retail/stock` and `/retail/stock/all-branches`, filtered by what was opened). `level` names the level whose groups are wanted. The stock list's own filters apply with the same meaning: `branch_id` (repeatable, within the branches the all-branches list shows), `include_closed` (a closed branch that still holds stock, as in the all-branches list), `include_inactive` (as on `/retail/stock`: an inactive item is counted and valued only with it), `query`, `category_id`, `negative_only`, `stock_level` (`out` or `low`, else 422); one chosen shop is read as the single-branch list reads it, otherwise as the all-branches list does. A group is `{key, label, items_count, negative_count, value_minor?, cost_minor?}`: the items the list shows for it (an active item with no balance counts, an inactive one only while it holds stock), those with a balance below zero, and, only when the caller holds `retail.profit.read` in every branch covered, what the balances hold at the selling price and at cost (the value of the stock is the owner's figure, ADR-055). Ordered by name and bounded to 100 with `has_more` and `next_offset` |
| GET | `/retail/stock/movements` | `retail.stock.read` | `branch_id` (repeatable, scoped), `product_id`, `from`, `to`, `newest_first` (default false; reverses the order and the cursor), `limit`, `cursor`. Row: `{id, at, branch_id, product_id, kind, qty, unit_cost_minor*, source_type, source_id, reverses_movement_id, historical, note, by, correction?}`; `correction` is present on a quantity correction (issue #351): `{reason_code, old_qty, new_qty}`. A void of a restock is an `adjustment` of `source_type` `retail.purchase_void` |
| POST | `/retail/stock/corrections` | `retail.stocktake.commit` at the branch | **M** (issue #351, ADR-044, FR-RET-46). `{branch_id, product_id, new_qty, expected_qty, reason_code: entry_mistake or miscount, note?}`: `new_qty` is the right quantity, not the difference, and `expected_qty` the quantity the form showed (409 `stock_moved` when the balance differs under its lock; 409 `stocktake_in_progress` while a draft stock-take of the branch counts the item). Under the balance row's lock posts one `adjustment` movement for the difference at the product's cost (source type `retail.stock_correction`), a `retail_stock_corrections` row, the ledger entry against inventory and stock shrinkage, and the audit entry `retail.stock.corrected`; 201 `{id, branch_id, product_id, code, description, old_qty, new_qty, reason_code, note, movement_id, at, by}`. 422 `quantity_unchanged`, `unknown_product`, `branch_not_stocking`; a missing `Idempotency-Key` is 422 `idempotency_key_missing`. Not shrinkage and not usage in any report |
| POST | `/retail/stocktakes` | `retail.stocktake.commit` | `{branch_id?, lines: [{product_id, counted_qty}], note?}`; returns each line's `expected_qty` and `variance_qty`. FR-RET-08 |
| GET | `/retail/stocktakes/{stocktake_id}` | `retail.stock.read` | Draft or committed |
| POST | `/retail/stocktakes/{stocktake_id}/commit` | `retail.stocktake.commit` | Adjustment movements of counted less `expected_qty` (the variance when counted, so later movements stay in the balance), posted at cost; 409 `stocktake_committed`; 409 `stock_moved_since_count` when later movements would make an adjusted balance negative (recount those lines) |
| POST | `/retail/sales` | `retail.sale.create` | **M**. `{branch_id?, sale_date?, payment_method, customer_id?, buyer_name?, buyer_contact?, due_date?, lines: [{product_id, qty, unit_price_minor?, unit_id?}]}`; `unit_id` (issue #194, the `units` capability) names a pack of the product: `qty` and `unit_price_minor` are then in that unit, the price defaults to the sell price times the factor rounded half up, the stock moves in base units (422 `quantity_not_representable` when the base quantity is not exact to three places or does not fit, 422 `unknown_pack_unit` or `capability_not_enabled` as a field problem on the line), and the price floor is judged on the line total against the line cost; 422 `insufficient_stock` when a line exceeds the branch's stock (always; ADR-020 decision 4); 422 `price_below_cost` when a line's unit price is not above the product's cost and the caller lacks `retail.price.below_cost` (the message never carries the cost; ADR-020 decision 5). Response: the sale with `lines`, `total_minor`, `paid_minor`, `balance_minor`, `cost_total_minor*`, `profit_minor*`, and per line `unit_cost_minor*`, `line_cost_minor*`, and for a pack line `sale_unit_id`, `sale_unit`, `sale_qty`, `sale_unit_price_minor`, `unit_factor` (absent means entered in the base unit; `qty` and `unit_price_minor` are always per base unit). FR-RET-04 |
| GET | `/retail/sales` | `retail.sale.read` | `branch_id` (repeatable, scoped), `from`, `to` (by sale date), `customer_id`, `buyer` (part of the buyer's name or of the linked credit buyer's name; `%` and `_` are literal), `payment_method` (`cash`, `mobile_money`, `bank` or `credit`; else 422), `product_id` (a sale with a line of that product), `status` (`completed` or `voided`; else 422), `seller_id` (sales made by that user), `category_id` (a sale with a line of a product in that category; the grouping chain of issue #341 passes them to list the sales of its last group), `owing` (`owing`: a completed credit sale with a balance above zero; `overdue`: owing and `due_date` before the tenant's business date; `paid`: a completed credit sale with no balance; anything else 422; applied in SQL before paging, so it holds across every page), `newest_first` (default false: oldest first), `limit`, `cursor` (the cursor records the `newest_first` direction it was issued for; one sent with the other direction is 422 on `cursor`) (#145) ADR-055: without `retail.sale.all_sellers` in every branch shown, only the caller's own sales are listed (a `seller_id` other than one's own is 403), except with `owing=owing` or `overdue`, which list every debt still owed at the caller's branches so a payment can be taken. |
| GET | `/retail/sales/summary` | `retail.sale.read` | The sales list grouped by a chain of levels, summed on the server so the browser never adds pages (issues #328 and #341; no migration). `group_by` (required: 400 when absent) is an ordered, comma separated list of levels from `branch` (shop), `year`, `month`, `seller` (salesperson), `day`, `category` (product category) and `item`, each at most once, in any order; a single value such as `year` still works; an unknown or repeated level, or an empty one, is 422. `level` (optional, one of the levels of `group_by`, else 422) names the level whose groups are wanted; the first of `group_by` by default. What the user opened above that level is passed as filters, so each level is one cheap query: `branch_id` (repeatable, scoped), `from` and `to` (by sale date; `from` after `to` is 422) for a year, month or day, `seller_id` (the user who made the sale; with the `seller` level it needs `retail.profit.read` in every branch the figures cover, the rule of `profit_minor`, else 403 `permission_denied`, and `available_levels` leaves `seller` out for that caller, because a ranking of colleagues is management information; `GET /retail/sales` takes the same filter under the same rule, and its rows and `GET /retail/sales/{id}` carry `created_by` and `voided_by` only for a caller with `retail.profit.read` at the sale's branch or when the id is the caller's own, else null), `category_id` and `item_id` (a product); beside the list's own filters with the same meaning and the same branch scope: `customer_id`, `buyer`, `payment_method`, `product_id` (sales holding that product, whole; over sale lines, that is at the category and item levels and below a chosen category or item, it keeps only that product's lines), `owing` (there is no `status`: completed and voided sales are always told apart). Answers `{group_by (the level answered), levels (the chain), next_level? (absent at the end of the chain), available_levels, currency, items, has_more, next_to?, next_offset?}`; an item is `{key, label, period?, from?, to?, sales_count, total_minor, voided_count, by_method: {cash, mobile_money, bank, credit: {count, total_minor}}, profit_minor?, quantity?}`. `key` is the shop, user, category or product id, or the period (2026, 2026-10 or 2026-10-19), and is what the next level takes as its filter; `label` is the shop, person, category or item name (a deactivated user keeps the name, an unknown one reads Unknown) or the period; `period`, `from` and `to` (the period's first and last calendar day, not narrowed by the request) exist for year, month and day only; `quantity` (base units) for the item level only. Time levels come newest first; the others by `total_minor`, largest first. Only completed sales make `sales_count`, `total_minor`, `by_method` and `profit_minor`; a voided sale is counted in `voided_count` and nowhere else (a sale is gross: a return is netted in the daily profit report, not here). At the category and item levels, and at every level below a chosen category or item (`category_id`, `item_id`), the figures are summed over the sale lines, so a category's figures are those of its own lines (`sales_count` is the completed sales with a line in it, `total_minor` the lines' total, `profit_minor` the lines' total less their cost snapshots); every other level sums whole sales, exactly as the list. `profit_minor` is present only when the caller holds `retail.profit.read` in every branch the figures cover, absent (not null) otherwise at every level, so a figure over some branches the caller may not see the profit of is never shown as the whole. One grouped query per request (plus the names), ranged on `sale_date` (indexes `retail_sales_list`, `retail_sales_by_date`), bounded by level: at most 20 years, 12 months, 31 days, 100 shops, 50 salespeople, 50 categories or 50 items (the work is not bounded: with no `from` the query reads every sale in its filter); money is in the tenant currency of `currency`, one per tenant. When more remain, `has_more` is true and, for a year, month or day, `next_to` is the day before the oldest period shown, to send as `to`; for the other levels `next_offset` is the `offset` (optional, zero or more; 422 below zero) to send for the next request. Over whole sales (shop, year, month, salesperson, day) the list and the summary build their filters in one place, so a group and the sales under it agree. At the category and item levels, and below a chosen category or item, the figures are the lines' while the sales listed under them are whole sales that include the category or the item (the list's `category_id` and `product_id`), so a group's total can be less than the total of the sales under it; the screens say so ("in N sales", "Whole sales that include this category"). The levels other than the periods are ordered by total, so between two requests a group can move: a client merges the pages by `key` and keeps the first; a group whose total moves between two page requests can still be skipped (the total order is not a snapshot), which the bounds make rare and the filters (a narrower range) cure Issue #384: the `item` level is an item with sizes and colours as one group of all its variants (key the item's id, label its name) or an item without; the `variant` level splits by size and colour, and is in `available_levels` only for a tenant that has an item with sizes. `item_id` and `product_id`, and `product_id` of `GET /retail/sales`, take an item's id for all its variants. ADR-055: without `retail.sale.all_sellers` in every branch covered, every level sums the caller's own sales only, the `seller` level is 403 and `seller_id` may name only the caller (the profit rule above then does not apply), debts included (a colleague's debt is found in the sales list, never summed here). |
| GET | `/retail/sales/{sale_id}` | `retail.sale.read` | 404 outside scope ADR-055: also 404 for another person's sale to a caller without `retail.sale.all_sellers` at its branch, unless it is a credit sale that still owes; the same for `GET /retail/sales/{sale_id}/payments`. |
| POST | `/retail/sales/{sale_id}/void` | `retail.sale.void` | `{reason}`; 409 `sale_voided`; 422 `sale_has_payments` for a credit sale with payments; 422 `sale_has_returns` once a return stands against the sale. No `Idempotency-Key`: a second void is `sale_voided`. UI (issue #327): **Void this sale** on an opened completed sale that is not imported and, for credit, has no payment or return, for a holder at its branch, with a reason of five characters and a plain warning (`docs/specs/retail-ui-notes.md`, Sale actions) |
| POST | `/retail/sales/{sale_id}/write-off` | `retail.credit.write_off` | **M**. `{reason}` (5 to 300 characters). Writes off the unpaid part of a live credit sale as a loss (ADR-043, FR-RET-42); never deletes. `201 {executed: true, write_off: {id, sale_id, sale_no, branch_id, written_off_on, currency, unpaid_minor, loss_minor*, credit_sale_recognition, reason, created_at, created_by, approval_id?}}`; `202 {executed: false, approval_id}` when the unpaid amount reaches the tenant's approval threshold for `retail_sale_write_off`, or by default (no positive threshold) when the tenant has two or more administrators who count (active, locked, or removed or deactivated within 30 days; maker-checker, ADR-015; one alone may write off alone, flagged in the audit row, and each administrator who counts is emailed). `loss_minor*` needs `retail.profit.read` in the sale's branch. 409 `sale_voided`, 409 `already_written_off`; a payment, a void or a return of a written-off sale: 409 `sale_written_off`; a payment (on a sale or on account) on a credit sale that counts from the first day of the rule `on_payment` on (the later of its date and the day it was entered; imported sales are outside the rule) must be dated today: 422 `backdated_payment` (the sale and each open sale of a buyer's balance say so with `counted_when_paid`); 422 `sale_not_credit`, `sale_historical` (an imported sale), `nothing_owing`; 404 outside the caller's scope. The sale then carries `write_off: {written_off_on, unpaid_minor, reason}`, its balance is 0, it leaves the owing and overdue lists and On credit, takes no payment, no void (`sale_has_payments`) and no return (409 `sale_written_off`), its earlier payments on account cannot be reversed (`payment_not_reversible`) and its returns cannot be voided (409 `sale_written_off`), and the customer statement lists a credit `write_off` line. The entry debits `bad_debt_loss`, debits `sales_revenue` for the rest and credits trade debtors. |
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
| GET | `/retail/purchases/{purchase_id}` | `retail.purchase.create` | Issue #351: one restock with its lines (scoped as the list is; 404 when it added stock to none of the caller's branches). Every restock row carries `status` (`posted` or `voided`) and, when voided, `voided_at`, `voided_by`, `voided_on` and `void_reason`; `partial` is true when the caller's branch scope hid other branches the restock added stock to (such a caller cannot void it); each line carries `base_unit`, the unit the item is usually kept in, so a line entered in another unit can be told |
| POST | `/retail/purchases/{purchase_id}/void` | `retail.purchase.create` at every branch the restock touched | **M** (issue #351, ADR-044, FR-RET-45). `{reason}` (5 to 300 characters). In one transaction: an `adjustment` movement reverses each movement of the restock (source type `retail.purchase_void`, `reverses_movement_id` set), the ledger reverses each journal entry on the day of the void, the prices it set go back while it is still the product's latest price event (else a price-history row says they were left), and `retail_purchase_voids` records who, when and why; the restock stays and reads `voided`. 403 `permission_denied` when it touched a branch outside the caller's scope (404 when none is in scope); 409 `purchase_voided`; 422 `purchase_stock_moved` (some of it was sold, used or moved: the detail names the item and the quantities), `purchase_historical` (imported) and `purchase_paid` (a supplier payment stands against this credit restock). A voided cash restock gives its cash back in the cash book on the day of the void; a voided credit restock is no longer an open item. Returns the restock |
| GET | `/retail/purchases` | `retail.purchase.create` | `branch_id` (repeatable, scoped), `from`, `to`, `supplier_id` (the nil UUID `00000000-0000-0000-0000-000000000000` is the restocks that name no supplier), `category_id` and `product_id` (a restock that added that product, or a product of that category, to one of the branches asked for, tested on the same stock movement as `branch_id` so the rows match the grouped summary; issue #350: the grouped view passes what was opened to list the restocks of its last group). `branch_id` only chooses which restocks are listed: what a card shows (`qty_by_branch`, totals, `partial`) follows the caller's own branch scope, so an all-branch caller opening a shop group still sees, and may correct, the whole restock, `status` (`posted` or `voided`; else 422), `newest_first` (default false), `limit`, `cursor`; purchases that moved stock into the caller's branches, a voided one included and marked. A branch-scoped caller sees only their branches' `qty_by_branch`, with `qty_total`, `line_total_minor` and `total_minor` recomputed from them (lines with nothing in scope left out); an all-branch caller sees the whole document (review F11) |
| GET | `/retail/purchases/summary` | `retail.purchase.create` | The restocks list grouped by a chain of levels, summed on the server so the browser never adds pages (issue #350; no migration). `group_by` (required: 400 when absent) is an ordered, comma separated list of levels from `branch` (shop), `year`, `month`, `supplier`, `day`, `category` and `item`, each at most once, in any order; an unknown, repeated or empty level is 422. `level` (optional, one of `group_by`, else 422) names the level whose groups are wanted, the first by default. What the user opened above it is passed as filters: `branch_id` (repeatable, scoped), `from`, `to` (by purchase date; `from` after `to` is 422), `supplier_id` (the nil UUID for no supplier), `category_id`, `item_id`, `status` (`posted` or `voided`, else 422; the restocks list's own filter, so the groups count what the cards under them list) and `offset` continues a level that was cut off. Answers `{group_by, levels, next_level?, available_levels, currency, items, has_more, next_to?, next_offset?}`; a group is `{key, label, period?, from?, to?, purchases_count, voided_count, total_minor?, quantity?, unit?}`: restocks counted once each (a category or an item counts the restocks that have a line in it), voided restocks counted apart and left out of every other figure, `quantity` and `unit` at the item level only (base units), and `total_minor` (what was bought, from the stock the restock added to the branches in scope, at the line's cost per base unit, or for a line entered in a pack the pack quantity at the pack cost, as the restock card shows it) only when the caller holds `retail.profit.read` in every branch the figures cover. Years, months and days come newest first, bounded to 20, 12 and 31 with `has_more` and `next_to`; shops are bounded to 100 and the others to 50, by number of restocks, with `has_more` and `next_offset`. A caller scoped to some branches sees only the stock that went into them Issue #384: `item` and `variant` as for the sales summary; `item_id`, and `product_id` of `GET /retail/purchases`, take an item's id for all its variants. |
| POST | `/retail/usage` | `retail.usage.report` | **M**. `{branch_id?, kind: used or damaged, reason, occurred_on?, lines: [{product_id, qty}]}`; response `cost_total_minor*`, per line `unit_cost_minor*`, `line_cost_minor*`; 422 `insufficient_stock` past the branch's stock (ADR-020 decision 4). FR-RET-07 |
| POST | `/retail/sales/{sale_id}/payments` | `retail.sale.create` | **M**. `{amount_minor, method: cash, mobile_money or bank, paid_on?}`; `{payment, sale_paid_minor, sale_balance_minor}`; 422 `payment_exceeds_balance`, `sale_not_payable`, `sale_historical` (an imported credit sale has no debtor in the books, so it takes no payment here: "An imported sale cannot be paid from here. Record this payment with your accountant."; issue #338). FR-RET-05. UI (issue #326): **Record payment** and **Mark as fully paid** on a live credit sale that still owes, from the Credit sales list, the All sales list and the opened sale, one `Idempotency-Key` per form. An imported sale offers neither, and the route refuses it too, with the same `sale_historical` as the account payment route (`docs/specs/retail-ui-notes.md`, Sale actions) |
| GET | `/retail/sales/{sale_id}/payments` | `retail.sale.read` | |

**UI notes: amount received and change to give (issue #346, FR-RET-04).** The sale form's cash tender is PWA only: no
migration, no API change, no new permission (whoever may record a sale, `retail.sale.create`, sees it). For a sale paid in cash the
form shows Amount received (whole shillings, numeric keyboard, commas and spaces allowed), the quick buttons 1,000, 2,000, 5,000, 10,000,
20,000 and 50,000 and Exact, and Change to give in large figures once the amount covers the total. Nothing is shown for mobile money,
bank or credit, and the form has no reference field for mobile money or bank today, so none was added. An empty field is allowed (no
change was asked for); an amount below the total is refused in plain words ("Enter at least the total, UGX 15,500.") because the
backend has no part payment on a cash sale (a part payment is a credit sale or `POST /retail/sales/{sale_id}/payments`); a negative, a
decimal, a non-number and an amount above UGX 100 billion are refused with their own words. The change is display only: the amount
received is not in the `POST /retail/sales` body, not in the saved draft and not stored anywhere; the sale is recorded at its total, the
daily cash summary counts that total, and the receipt shows "Received" and "Change given" from the form's own state, which is gone after
the next sale or a reload. Double-submit protection is unchanged (one `Idempotency-Key` per draft). The tours `retail-sales` (version 6)
and `admin-welcome` (version 9) have a Cash and change step. Notes: `docs/specs/retail-ui-notes.md` (Amount received and change).

Built (#54, reports):

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/retail/reports/valuation` | `retail.stock.read` | `branch_id` (repeatable, scoped), `as_of` (not in the future; quantities are summed by each movement's business date, the date its journal carries). A row whose quantity times a price does not fit is flagged `amount_out_of_range`, its values left out and excluded from the totals, so one product never fails the report. `{as_of, currency, rows: [{branch_id, product_id, code, description, category_id, category, unit, qty, negative, sell_minor, expected_sales_minor*, amount_out_of_range, cost_minor*, value_at_cost_minor*, expected_profit_minor*, expected_profit_bp*}], branches: [{branch_id, expected_sales_minor*, value_at_cost_minor*, inventory_account_minor*, revaluation_difference_minor*, expected_profit_minor*, expected_profit_bp*}], categories: [{category_id, category, expected_sales_minor*, value_at_cost_minor*, expected_profit_minor*, expected_profit_bp*}], expected_sales_minor*, value_at_cost_minor*, expected_profit_minor*, expected_profit_bp*}` (since ADR-055 the value at the selling price, `expected_sales_minor`, follows the same `retail.profit.read` rule as the cost fields: the value of the stock is the owner's figure, so a seller or a storekeeper reads the quantities only); expected profit is expected sales less value at cost, and `expected_profit_bp` is that profit over value at cost in basis points (2500 is 25 percent, half up), absent when the value at cost is not above zero (#145); a category line shows cost and profit only when the caller holds `retail.profit.read` in every branch that contributes to it, as the overall total does; `branches` lists every branch in the filter that holds stock or has a non-zero `inventory` balance (review F3). FR-RET-09 |
| GET | `/retail/reports/profit/daily` | `retail.profit.read` | `branch_id` (repeatable, scoped), `from`, `to` (default the last 30 days, at most 366). `{from, to, currency, rows: [{branch_id, date, sales_minor, cost_of_sales_minor, gross_profit_minor, usage_cost_minor, stocktake_difference_minor, profit_minor, sold_minor?, received_minor?, counted_at_sale_minor?}], sales_minor, cost_of_sales_minor, usage_cost_minor, stocktake_difference_minor, profit_minor, credit_sale_recognition, credit_recognition_starts_on?, sold_minor?, received_minor?, on_credit_minor?, counted_at_sale_minor?}` with `bad_debt_minor` on each row and in the total: the loss of credit sales written off that day (ADR-043), taken off `profit_minor`, so it also lowers that day's savings suggestion. With the tenant setting `credit_sale_recognition` `on_payment` (ADR-043) `received_minor` (row and total) is the money recorded as collected on the day it was entered, by any method (not a claim that it equals the drawer): sales paid at the sale plus every payment recorded on a credit sale on the day it was entered, whatever the sale's date and whether or not it counts when the buyer pays, imported credit sales included, less payments reversed (on the day of the reversal) and money paid back on any return (on the day of the return, at the refund branch, and given back on the day a return is voided); a payment dated earlier than it was entered counts on the day it was entered, sales paid at the sale count on the day they were entered, a day already reported is never rewritten except that cancelling (voiding) a cash sale removes it from its own day, and a reversed account payment and a voided return count on the day they happen; `sales_minor` is the sales counted for profit (the cost and profit columns follow it), and `counted_at_sale_minor` (row and total) is the profit counted at the sale for credit made before the rule started, not imported ones, net of returns, informational and never subtracted from Received; `sold_minor` (row and total) is everything sold on the day or in the period, credit included, and `on_credit_minor` is what buyers still owe now on the credit sales counted when the buyer pays; with `on_sale` (the default, and also for a report that holds no day from the start on and no credit sale counted when the buyer pays) the fields are those of before and the optional ones are absent. `stocktake_difference_minor` is the value at cost of committed stock-take adjustments on the day the stock-take was committed, negative for a loss and positive for a gain (the net of the `stock_shrinkage` postings of `retail.stocktake`, #121), and is included in `profit_minor`. The whole report needs `retail.profit.read`, so no caller without it receives the field. FR-RET-10 |
| GET | `/retail/reports/sales-analysis` | `retail.sale.read` | The sales analysis of issue #149 (retail analytics pass). `branch_id` (repeatable, scoped), `from`, `to` (default the last 30 days, at most 366, both ends counted), `group` (`day`, `week` or `month`; weeks start on Monday), `top` (rows of every list, default 10, 1 to 100), `slow_days` (7 to 180, default 30). `{from, to, currency, group, top, profit_visible, totals: {sales_minor, sale_count, qty, gross_profit_minor*, margin_bp*}, series: [{period_start, sales_minor, sale_count, gross_profit_minor*, margin_bp*}], by_branch, by_category, by_product, top_by_quantity, by_seller: [{id, code?, label, unit?, qty, sales_minor, sale_count, gross_profit_minor*, margin_bp*}], slow_days, slow_movers, slow_movers_total, no_sales, no_sales_total: [{product_id, code, description, unit, qty_on_hand, stock_at_price_minor}]}`. Completed sales only, from the sale lines and their cost snapshots, by sale date (a business date). Totals are over the whole range, never over the row limit. The slow movers are items with stock on hand and no sale in the last `slow_days` days as of today in the tenant time zone; `no_sales` are active items with no sale in the range. Both lists carry quantities and the stock value at price, so they are present only with `retail.stock.read` as well, and are computed over the branches both permissions cover (a caller with `retail.sale.read` alone gets neither list, the keys absent). The seller is the user who recorded the sale. Margin is gross profit over sales in basis points, half up, computed on the server. Profit fields (`*`) and `profit_visible: true` need `retail.profit.read` in every branch the report covers (a per branch row shows its own profit where the permission covers that branch). The queries are in `scripts/db-bench/queries` (k01 to k04), each bounded by the date range, the branch scope and a row limit. `by_product` and `top_by_quantity` count an item with sizes once, all its sizes together, with its base code (issue #395); the slow movers and no-sales lists stay per product. ADR-055: a stocked item's `stock_at_price_minor` is present only with `retail.profit.read` in every branch covered. ADR-055: it adds up every seller's sales, so it is 403 unless the caller also holds `retail.sale.all_sellers` in every branch covered. |
| GET | `/retail/reports/margins` | `retail.profit.read` | Margins of issue #149. `branch_id` (repeatable, scoped to `retail.profit.read`), `from`, `to` (default the last 30 days, at most 366), `top` (1 to 100, default 10), `target_bp` (0 to 10000, default 2000 = 20 percent). `{from, to, currency, top, target_bp, totals: {sales_minor, cost_minor, profit_minor, margin_bp}, by_product, by_category: [{id, code?, label, unit?, qty, sales_minor, cost_minor, profit_minor, margin_bp}], below_target: {items, total}, price_changes: {items: [{product_id, code, description, unit, changed_on, old_sell_minor, new_sell_minor, days_before, days_after, units_before, units_after, sales_before_minor, sales_after_minor, margin_before_bp, margin_after_bp}], total}}`. Cost and profit are the sale line snapshots, never the current cost. Margin is profit over sales in basis points, half up; absent when sales are not above zero. `by_product` and `by_category` are ordered by profit, `below_target` by margin lowest first (an item that sold for nothing at a cost is below any target); `total` counts before the `top` limit. A price change row is one item whose sell price changed in the range (from `retail_price_history`, the change dated in the tenant time zone): the old price is before the first change in the range, the new price after the last, and the split of the sales is the day of the last change (sales before it, then from it to the end of the range); an item whose net price did not change is left out. Queries k05 and k06 in `scripts/db-bench/queries`. `by_product` and `below_target` count an item with sizes once, all its sizes together, with its base code; price change impact stays per size, a price being its own (issue #395) |
| GET | `/retail/reports/stock-health` | `retail.stock.read` | Stock health of issue #149. `branch_id` (repeatable, scoped to `retail.stock.read`), `from`, `to` (the range of the shrinkage section; default the last 30 days, at most 366), `top` (1 to 100, default 10), `lead_days` (1 to 365, default 14), `cover_days` (`lead_days` to 365, default 30). `{from, to, currency, top, velocity_days: 30, lead_days, cover_days, profit_visible, cover: {items, total}, reorder: {items, total}, dead_stock: {days: 90, branches: [{branch_id, item_count, value_at_price_minor, value_at_cost_minor*}], items: [{branch_id, product_id, code, description, unit, qty_on_hand, value_at_price_minor, value_at_cost_minor*}], items_total, value_at_cost_minor*, value_at_price_minor}, shrinkage: [{branch_id, used_reports, damaged_reports, short_lines, over_lines, used_cost_minor*, damaged_cost_minor*, stocktake_loss_minor*, stocktake_gain_minor*, net_cost_minor*}]}`. A cover row is `{branch_id, product_id, code, description, unit, qty_on_hand, sold_last30, avg_daily, days_of_cover, capped, suggested_qty?}`: velocity is the units sold in the branch in the last 30 days (completed sales), the average is that over 30, and `days_of_cover` is stock (negative counts as none) over the average to one place, shown as `365.0` with `capped` above a year. `cover` lists the lowest cover first over items sold in the window; `reorder` lists the items low by either rule of FR-RET-63, whichever trips first (issue #412): below the low-stock level that applies at the branch, or cover under `lead_days` (judged exactly, stock times 30 under `lead_days` times units sold); an item with no level by its pace alone and one that did not sell by its level alone, the most urgent first (the smaller share of its level or of the lead days that is left). A reorder row also has `min_qty` (the level that applies, when set), `low_by` (`minimum` or `pace`) and `low_words` ("below your minimum of 10", "about 4 days from running out at this pace", "out of stock"), and `suggested_qty` is the units to reach `cover_days` at that pace and at least the level, rounded up to the thousandth, less the stock. Dead stock is stock above zero of an active item with no completed sale in that branch in the last 90 days, totalled per branch over every such item and listed by value at price (never ordered by cost). Shrinkage per branch in the range: usage reports by kind (`used`, `damaged`) and committed stock-take adjustments (shortages and surpluses valued at the cost snapshot of the movement). Two known differences, kept on purpose: the stock-take loss here is `round(abs(qty) * unit_cost)` per line, while the daily profit report reads the ledger `stock_shrinkage` postings, so the two can differ by one minor unit a line on fractional quantities; and dead stock counts an item restocked recently and not yet sold as dead, since it looks only at the last sale, not at when the stock arrived. Fields marked `*` need `retail.profit.read` in that branch (the overall dead stock cost in every branch reported); the counts stay. Queries k07 to k09 in `scripts/db-bench/queries` ADR-055: `value_at_price_minor` of the dead stock (per branch, item and total) is present only with `retail.profit.read`, like the cost. Slow movers (issue #414, FR-RET-64): `slow_movers: {days: 90, slow_months, min_sold, items: [{branch_id, product_id, code, description, unit, qty_on_hand, sold_last90, last_sold_on, days_counted, months_of_stock, capped, value_at_cost_minor*, better_at?: {branch_id, sold_last90, suggested_qty}}], total, value_at_cost_minor**}`: stock above zero of an active item that sold in the branch in the last 90 days (so never dead stock) whose stock times `days_counted` is above `slow_months` times 30 times the units sold (units sold gross of customer returns, as in the cover list), or whose units sold are under `min_sold` (0 is no such rule); `slow_months` and `min_sold` are the business's settings `slow_mover_months` and `slow_mover_min_sold`. `days_counted` is 90, or for an item that came to the branch less than 90 days ago the days since its first stock movement or sale there, today included, so a new item is judged on the days it has been there; `months_of_stock` is stock over the monthly pace of those days (30 days a month) to one place, shown as `120.0` with `capped` above that; the longest first, never ordered by cost; `total` and the total at cost are over every slow row, not the limit. `better_at` is the branch, among every branch the caller may read stock in (not only those asked for), that is active and stocks goods (a closed branch or one without goods never is), that sells the item faster (units sold over its own `days_counted`), the fastest first, that can take a whole unit: it takes what it sells in `cover_days` less its stock, and this branch gives at most what it holds above `slow_months` of its own sales; `suggested_qty` is that, rounded down to whole units. `**` is present only with `retail.profit.read` in every branch of the business (an administrator), unlike the dead stock totals, which follow the branches asked for. The field names hold no `sell`, `cost` or `price` a Storekeeper may not see (FR-RET-60). Query k16 |
| GET | `/retail/reports/credit-control` | `retail.sale.read` | Credit control of issue #149; no cost or profit appears. `branch_id` (repeatable, scoped), `from`, `to` (the range of the payments section; default the last 30 days, at most 366), `top` (1 to 100, default 10), `sort` (`amount` or `age`, for the overdue list). `{from, to, as_of, currency, top, totals: Ageing, buyer_count, buyers: [{customer_id?, name, sale_count, ageing: Ageing, oldest_overdue_days?}], sort, overdue_count, overdue: [{sale_id, sale_no, branch_id, customer_id?, buyer_name, sale_date, due_date, days_overdue, total_minor, outstanding_minor}], payments: [{date, method, count, amount_minor}], payments_by_method: [{method, count, amount_minor}], payments_minor}`; `Ageing` is `{owed_minor, not_due_minor, days1_to30_minor, days31_to60_minor, days61_to90_minor, over90_minor}`. What is owed is as of today in the tenant time zone and uses the predicate of `GET /retail/sales?owing=`: a completed credit sale whose total is above its paid amount (overdue: due date before today); the days are counted from the due date and a sale with no due date counts as not yet due. A buyer is the credit buyer record, or the name typed in with the sale ignoring case. `totals` and `buyer_count` cover every buyer in scope whatever `top`; `buyers` list the most owed first; `overdue_count` is before the limit. Payments are those of completed sales in scope, dated by `paid_on`; a payment on account (issue #196) counts in the branch that received it; a reversed one stays on the day it was received and its reversal is a negative row (count and amount) on the day it was undone, as in the cash book, as the ledger and the cash book post it, and any other payment in the branch of its sale. While `customer_accounts` is on a buyer with a record and a limit carries `credit_limit_minor` and, only when the report covers every branch, `over_limit`. Queries k10 to k12 in `scripts/db-bench/queries`; the open debts, not a date range, bound k10 and k11 |
| GET | `/retail/reports/business-evaluation` | `retail.profit.read` | Business future evaluation of issue #149: formulas on today's prices and recent sales, **not a forecast** (the response carries `note` saying so). `branch_id` (repeatable, scoped to `retail.profit.read`), `from`, `to` (the period of the profit to date; default the last 30 days, at most 366), `top` (items per category, 1 to 100, default 10). `{from, to, currency, top, run_rate_days: 30, note, total: {period, stock, run_rate}, branches: [{branch_id, period, stock, run_rate, categories: [{category_id, category, period, stock, products_total, products: [{product_id, code, description, unit, qty_on_hand, period, stock}]}]}]}`. `period` is `{sales_minor, profit_minor, margin_bp?}` from the sale line cost snapshots (gross profit: usage, damage and stock-take differences are in the daily profit report). `stock` is the stock on hand above zero at the current prices, each row rounded half up to the minor unit: `{at_cost_minor, at_price_minor, expected_profit_minor (price less cost), over_cost_bp? (expected profit over cost, half up; absent when cost is zero)}`. `run_rate` is the last 30 days whatever the period: `{sales_minor, profit_minor, avg_daily_sales_minor, avg_daily_profit_minor (over 30, half up), days_of_stock? (stock at price over the daily sales, one place; absent when nothing sold), days_of_stock_capped}`. Categories and branch totals cover every item; only the item lists are limited by `top` (largest expected profit first). Query k13 in `scripts/db-bench/queries` |
| GET | `/retail/reports/dashboard` | `retail.sale.read` | The owner dashboard of the retail home (issue #149). `branch_id` (repeatable, scoped to `retail.sale.read`). `{date, currency, profit_visible, today: {sales_minor, sale_count, cash_minor, credit_minor, gross_profit_minor*, received_minor?, on_credit_minor?, on_credit_count?}, days: [{date, sales_minor, gross_profit_minor*, received_minor?}] (the last 30 days ending today, one a day, zero filled, oldest first), last7_sales_minor, last30_sales_minor, last7_gross_profit_minor*, last30_gross_profit_minor*, stock**: {value_at_price_minor, value_at_cost_minor*, out_of_stock, low_stock, low_stock_threshold}, shops: [{branch_id, today_sales_minor?, last7_sales_minor?, last30_sales_minor?, today_gross_profit_minor*, stock_at_price_minor**, stock_at_cost_minor*, out_of_stock**, low_stock**, today_received_minor?}], credit_sale_recognition, credit_recognition_starts_on?, last7_received_minor?, last30_received_minor?}`. `credit_sale_recognition` is `on_payment` for a report once the first day of the rule has come and the report holds a day from it on or a credit sale counted when the buyer pays (so a report that ends before the start day still shows a backdated credit sale entered after it, as Sold and On credit, while a report of earlier days keeps the plain layout), and `credit_recognition_starts_on` is that first day when it was chosen, which can lie in the future; on the dashboard it is `on_payment` once the first day has come, and the when-paid fields are absent before it. With `credit_sale_recognition` `on_payment` (ADR-043) `sales_minor` is Sold (everything sold, credit included), `on_credit_minor` and `on_credit_count` are what buyers still owe on the credit sales counted when the buyer pays (older credit sales were counted at the sale); Received (`received_minor`, `today_received_minor`, `last7_received_minor`, `last30_received_minor`) is the money recorded as collected, the same figure as in the profit report, and the profit figures include credit counted at the sale before the rule started; the optional fields are absent under `on_sale`, the default. Today is the business date in the tenant time zone; `cash_minor` is sales paid at the sale (cash, mobile money, bank) and `credit_minor` sales on credit, from the lines of completed sales. Fields marked `*` need `retail.profit.read` (the totals in every branch reported, a shop's own in that shop); `**` need `retail.stock.read` (in that shop). `out_of_stock` and `low_stock` follow the stock list rule: every active item (and any inactive one still holding a quantity) is judged on its balance, an item with no balance row counting as zero; out is zero or less and low is at or below the threshold of ADR-029 (the value the stock lists report as `low_stock_threshold`), so low includes out of stock, as the Low tab does. A shop's counts are judged on that branch; the overall counts judge each item on its balance summed over the branches reported, as All branches does, so they equal `total` of `GET /retail/stock?stock_level=out|low` for one branch or for All branches. A shop in the caller's stock scope but not their sales scope carries no `today_sales_minor`, `last7_sales_minor` or `last30_sales_minor` (absent, not zero). The banked against expected and savings figures come with the cash book and are not here. Queries k14 and k15 in `scripts/db-bench/queries`; k15 has no date, it reads the current balances of the branches in scope ADR-055: `stock.value_at_price_minor` and a shop's `stock_at_price_minor` are present only with `retail.profit.read` (every branch covered, or that branch), like the cost. ADR-055: it adds up every seller's sales, so it is 403 unless the caller also holds `retail.sale.all_sellers` in every branch covered; a seller's home shows their own sales from the summary instead. With `period` (`today`, `7d`, `30d`, `ytd`, `1y`, `all`) or `period=custom` with `from` and `to` (dates alone are custom; at most 366 days; 422 otherwise) the body also has `range: {period, from, to, previous_from?, previous_to?, group, current: {sales_minor, sale_count, average_sale_minor?, gross_profit_minor*, received_minor?}, previous?, series: [{start, sales_minor, gross_profit_minor*, previous_sales_minor?}], busiest_day?: {date, sales_minor, previous_date?, previous_sales_minor?}, best_shop?, best_seller?, top_salesperson?}` with a leader as `{id, code?, label?, sales_minor, sale_count, previous_sales_minor?}` (issue #428, ADR-060): the period before is as many days just before (This year: the same days of last year; 1 year: the year before; All, from the first completed sale in scope: none); a step of `series` is a day up to 62 days, seven days from `from` up to a year, else a calendar month, and `previous_sales_minor` is the same step of the period before counted from `previous_from`; `best_seller` is the item with the most sales and `top_salesperson` the person, present only with `retail.profit.read` in every branch covered (the rule of the salesperson level of the sales summary); each shop then carries `range_sales_minor?`, `previous_sales_minor?` and `range_gross_profit_minor*`, and `days` and the shops' `last7_sales_minor` and `last30_sales_minor` are left out. Without a range the body is unchanged. |
| POST | `/retail/orders` | `retail.order.manage` | **M**. Take a customer order (issue #385, ADR-052, FR-RET-53, FR-RET-54): `{branch_id?, source (whatsapp, instagram, facebook, phone, walk_in, other), customer_id?, customer_name?, customer_phone?, delivery_place?, delivery_date?, delivery_fee_minor?, notes?, lines: [{product_id, qty, unit_id?, unit_price_minor?, price_override_reason?}]}`; a name is needed unless a credit buyer is named. Priced by the rules a sale would take today; its items are kept aside (`reserved_qty`). `201` the order. 422 `branch_does_not_sell` at a Store, `insufficient_stock` when the shop has too few free (another order keeps the rest), the price rules of a sale (`price_below_cost`, `override_reason_required`, `no_price_in_list`), `validation_failed` (a delivery day in the past). |
| GET | `/retail/orders` | `retail.order.manage` | Orders in the caller's branch scope, newest first: `branch_id` (repeatable), `status` (`open` or a step), `due` (`today`: open and to deliver today; `overdue`: open and the day passed), `source`, `query` (number, name or phone), `limit`, `cursor`. `{items: [order without history], next_cursor}`. FR-RET-56 |
| GET | `/retail/orders/board` | `retail.order.manage` | `{today, ordered, packed, out_for_delivery, due_today, overdue, delivered_today}` in the caller's branches (`branch_id` repeatable). FR-RET-56 |
| GET | `/retail/orders/customer-lookup` | `retail.order.manage` | `phone` (at least four digits, else 422): the newest order of that number in the caller's branches, matched on its last nine digits, and a credit buyer whose contact is that number: `{found, customer_id?, customer_name?, customer_phone?, delivery_place?, delivery_fee_minor?, orders}`. FR-RET-56 |
| GET | `/retail/orders/{order_id}` | `retail.order.manage` | The order with its lines and `history: [{step, note?, by_user, by_name?, at}]`, and its version as `ETag`. An order is `{id, order_no, branch_id, ordered_on, source, customer_id?, customer_name, customer_phone?, delivery_place?, delivery_date?, delivery_fee_minor, currency, items_total_minor, total_minor, notes?, status, delivered_by?, packed_at?, sent_at?, delivered_at?, delivered_on?, sale_id?, sale_no?, fee_method?, cancelled_at?, cancel_reason?, created_at, created_by, version, lines: [{line_no, product_id, code, description, qty, unit_id?, unit, unit_price_minor, line_total_minor, base_qty}]}`; never a cost. 404 outside the scope. |
| PATCH | `/retail/orders/{order_id}` | `retail.order.manage` | `If-Match`. `{customer_name?, customer_phone?, delivery_place?, delivery_date?, clear_delivery_date?, delivery_fee_minor?, notes?}` of an open order (an empty text clears it); the items change through `/items`. 409 `version_conflict`, 409 `order_step_not_allowed`. |
| POST | `/retail/orders/{order_id}/items` | `retail.order.manage` | **M**. `If-Match` and `Idempotency-Key`. `{lines: [{product_id, qty, unit_id?, unit_price_minor?, price_override_reason?}] (1 to 200), note?}`: the whole new set of items of an order that is ordered or packed (issue #393, ADR-052 amendment 1). The order keeps its number; in one transaction under the order row lock and then the balance row locks the stock kept for removed or smaller lines is given back and the new or larger ones kept. A line left as it was (the same item, unit and quantity, with no price or the price agreed) keeps the price agreed, even if the item was switched off or its cost rose since; any other line, one whose quantity changed included, is priced as on `POST /retail/orders` (its price break found again; an override needs `retail.price.override` and a reason and is credited to the caller). A packed order goes back to ordered. 422 `insufficient_stock` names the item. The history gets `items_changed`; the same items again change nothing. 422 `insufficient_stock`, 409 `order_step_not_allowed` (out for delivery, delivered or cancelled), 409 `version_conflict`, 428 without `If-Match`. FR-RET-53, FR-RET-54 |
| POST | `/retail/orders/{order_id}/pack` | `retail.order.manage` | `{note?}`: ordered to packed; a retry on a packed order answers it as it is. 409 `order_step_not_allowed`. FR-RET-55 |
| POST | `/retail/orders/{order_id}/send` | `retail.order.manage` | `{delivered_by, note?}`: ordered or packed to out for delivery, with who takes it; a retry with the same rider answers the order as it is. 409 `order_step_not_allowed`. FR-RET-55 |
| POST | `/retail/orders/{order_id}/deliver` | `retail.order.manage` | **M**. `{payment_method (cash, mobile_money, bank, credit), paid_now_minor?, paid_now_method? (credit with money taken now only), due_date?, credit_override_reason?, fee_on_account? (credit, a credit buyer on the list: the fee goes on the account), note?}`. The sale is made at the agreed prices without checking the price override permission, the price floor or an inactive item again; the credit limit counts what goes on credit. Also needs `retail.sale.create` at the order's shop (403). Releases what the order kept and makes the sale through the ordinary sale path at the prices agreed (an agreed price that differs from a price list is an override with the reason `Price agreed on order ...`); with `credit` the money taken now pays the delivery fee first and the rest is a payment on the credit sale. The fee is posted on its own to `delivery_income`. `200 {order, sale_id, sale_no, paid_now_minor, owed_minor}`. 422 `delivery_fee_unpaid` (credit with less than the fee taken now), `paid_in_full` (credit with everything paid), every refusal of a sale (`insufficient_stock`, `customer_required`, `credit_limit_exceeded`, `price_below_cost`); 409 `order_step_not_allowed`. FR-RET-55 |
| POST | `/retail/orders/{order_id}/cancel` | `retail.order.manage` | `{reason}` (3 to 300 characters): an open order is cancelled and its items go back; a retry on a cancelled order answers it as it is. 409 `order_step_not_allowed`. FR-RET-54 |
| POST | `/retail/orders/{order_id}/fee-payment` | `retail.sale.create` | **M**. `{method}` (cash, mobile_money, bank): the delivery fee left on a credit buyer's account is paid, once (debit the method's account, credit trade debtors against the sale), by whoever may take payments at the shop (`retail.order.manage` is not needed). 422 `fee_not_on_account`, `sale_voided`; 409 `fee_already_paid`. FR-RET-55 |
| GET | `/retail/orders/reports/delivery-fees` | `retail.order.manage` | `branch_id`, `from`, `to` (default the last 30 days, at most 366), `group_by` (`day`, `source`, `rider`): `{from, to, group_by, currency, orders, items_minor, fees_minor, rows: [{key, label, orders, items_minor, fees_minor}]}` over the orders delivered. FR-RET-57 ADR-055: the fees and the value of everybody's orders are the owner's figures, so it is 403 unless the caller also holds `retail.sale.all_sellers` in every branch covered. |
| GET | `/retail/orders/reports/cancellations` | `retail.order.manage` | `branch_id`, `from`, `to`: `{from, to, currency, orders, items_minor, by_source: [row], items: [{id, order_no, branch_id, cancelled_on, source, customer_name, items_total_minor, reason, cancelled_by}]}`, at most 500 newest first. FR-RET-57 ADR-055: who cancelled and the value of everybody's orders are the owner's figures, so it is 403 unless the caller also holds `retail.sale.all_sellers` in every branch covered. |
| GET | `/retail/controls/summary` | `retail.controls.read` | The cards of the Controls page (issue #358, part of #357; ADR-047). `branch_id` (repeatable, scoped to `retail.controls.read`), `from`, `to` (default the last 30 days, at most 366, both ends counted), `within_minutes` (1 to 120, default 10: a void within this many minutes of the sale being entered is a quick cancel). `{currency, period: {from, to, sales_count, sales_minor}, previous: {...} (the period of the same length ending the day before), within_minutes, cost_visible, cards: [{metric, tracked, count, sales_affected, value_minor, previous_count, previous_value_minor, rate_per_100?, previous_rate_per_100?, share_of_sales_bp?}]}`. `count` counts events (a line, a return, a void) and `sales_affected` the sales that have one, so a sale with two changed lines counts once there. Metrics: `restock_voids` (a restock voided, by the day it was voided, one event for each restock and branch it stocked, valued at that branch's quantity at the selling price now, never the cost; the person is who entered the restock, the approver who voided it; `sale_id` and `sale_no` carry the restock's id and number) and `quantity_corrections` (a quantity corrected on the stock list; the value is the change at the item's selling price now), both with no `rate_per_100`, no `share_of_sales_bp` and no approver for a correction (`group_by` with `approver` is 422 for a metric that has none: changed prices, discounts, below cost, corrections); `voided_sales` (voids, by the day they were voided), `quick_cancels`, `returns` (completed returns, by return date), `price_overrides` (sale lines with `price_source` override), `discounts` (override lines sold below the resolved price; the value is the money given up) and, only when `cost_visible`, `below_cost` (sale lines under their cost snapshot). Values are sale prices. `rate_per_100` is `sales_affected` over the sales entered in the period (any status, imported history left out) times 100, one decimal half up, absent without sales; `share_of_sales_bp` is the value over the completed sales, half up, capped at the largest 32 bit integer (a voided value is not part of the completed total). `tracked` is false when the capability behind a card (`price_lists`, `returns`) is off and the card never counted anything. FR-RET-48 |
| GET | `/retail/controls/groups` | `retail.controls.read` | One metric grouped by a chain of levels, as `GET /retail/sales/summary` is (issue #341). `metric` and `group_by` (a list of `branch`, `user`, `day`, `reason`, `approver`, each once; 422 otherwise) are required; `level` (the level answered, default the first), `branch_id`, `from`, `to`, `within_minutes`, and the parent selection as filters `user_id`, `approver_id` and `reason` (the group key); `offset`. `{metric, group_by, level, levels, next_level?, currency, items: [{key, label, user_status?, day?, count, sales_affected, value_minor, sales_count, rate_per_100?}], has_more, next_offset?}`; 100 groups a page; days newest first, reasons by count, shops, people and approvers by name (never by count or rate: no league table). `sales_count` is the sales entered by the same shop, person or day (for a reason, all sales in the filter; an approver has none and so no rate) and the rate is `sales_affected` over it. A shop, person or approver the server cannot name has the key `""` (people read Unknown person) and is not to be opened as a filter. **The person is the user whose sale it was** (`created_by` of the sale), also for a void and a return; the approver (the user who voided the sale, or took the goods back) is the `approver` level and `approver_id`. `below_cost` is 403 without `retail.profit.read` in every branch covered. FR-RET-48 |
| GET | `/retail/controls/records` | `retail.controls.read` | The individual events behind a metric and a group, newest first, 50 a page. `metric` (required), `branch_id`, `from`, `to`, `within_minutes`, `user_id`, `approver_id`, `reason`, `offset`. `{metric, currency, items: [{sale_id, sale_no, reference?, at, branch_id, branch_name, user_id, user_name?, user_status?, approver_id?, approver_name?, approver_status?, reason?, item?, value_minor}], has_more, next_offset?}`: `reference` is a return's credit note number, `item` the product of a sale line. FR-RET-48 |
| GET | `/retail/controls/patterns` | `retail.controls.read` | People and items whose rate is more than twice their shop's own. `branch_id`, `from`, `to`. `{from, to, min_events: 3, min_out_of: 10, items: [{subject (user or product), key, name, user_status?, branch_id, branch_name, metric (voided_sales, returns, price_overrides or below_cost), count, out_of, rate_per_100, shop_rate_per_100, times}]}`, the largest `times` first, at most 20. A person is whose sales they were, never who cancelled them; the rate is the sales with the event over the sales they entered in that shop (`count` is those sales); an item's is over the lines it sold there by the date of the sale (for voids, lines of voided sales). Ordered by shop name, then name, never by how far anyone stands out. Flagged when `count` is at least 3, `out_of` at least 10 and the rate is more than twice the shop's, judged without rounding. `below_cost` rows only with `retail.profit.read` in every branch covered. FR-RET-48 |

The analytics reports of issue #149 (`sales-analysis`, `margins`, `stock-health`, `credit-control`, `business-evaluation`,
`dashboard`; reports only, no migration) share these rules. Every list is limited by `top` (1 to 100, default 10), except `payments` of `credit-control`, which is one row a day and way of paying in the range (at most 366 days) and so bounded by the range; the
totals of a report never depend on that limit; a list says how many rows it had before the limit (`total` or `..._total`).
A range is `from` and `to` (both ends counted, the default the last 30 days, at most 366, 422 otherwise) in business dates
of the tenant time zone; a window counted back from today (30 days of velocity, 90 of dead stock, the slow movers) is
fixed or a parameter as each row says. Every statement is bounded by the range or window, by the caller's branch scope and
by a row limit, and is among the hot paths measured in `scripts/db-bench/queries` (k01 to k16); the exceptions are the open
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
| GET | `/retail/reports/cash/daily` | `retail.cashbook.read` | Per branch and day: `cash_takings_minor`, `cash_sale_voids_minor`, `expense_voids_minor`, `advance_voids_minor`, `repayment_voids_minor`, `banking_voids_minor`, `withdrawal_voids_minor`, `cash_purchases_minor*`, `expenses_minor`, `advances_out_minor`, `repayments_in_minor`, `withdrawals_in_minor`, `banked_minor`, `cash_expected_minor`, `ledger_basis`, `historical` (an imported day: no running total, see below), and the savings-embedding figures `opening_minor*`, `savings_minor*`, `savings_voids_minor*`, `closing_minor*`, `other_movements_minor*`, `expected_to_bank_minor*`, `unbanked_running_minor*`; `daily_profit_minor*` ADR-055 (the owner's decision of 21 October 2026): this route and every other cash book route (savings, bankings, withdrawals, expenses, advances, the cash reports, expense categories and cash parties) also need `retail.till.close` ("Can close the till and do the banking", the role `retail_till`, administrators everywhere), else 403 `permission_denied`; the Sales role holds no cash book permission since V74. |
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

Built (issue #414): the same group and route carry the two slow mover rules of Stock health, `slow_mover_months` (whole, 1 to 24, default 4) and `slow_mover_min_sold` (whole, 0 to 1,000,000, default 0 meaning off), read on `GET` and changed by a partial `PUT` (422 out of range); they are not capabilities and are never listed in `/me`.

Built (issue #191): `GET` and `PUT /retail/settings/capabilities` (`core.settings.manage`; the body is
the profile and the eight flags, `PUT` needs `If-Match` with the settings version as `PATCH /settings`
does (the `ETag` is returned by `GET`), a change is partial, the group also carries the policy `credit_sale_recognition` (`on_sale`, the default, or `on_payment`; ADR-043, 422 on any other value; the shop or trade profile does not change it) and the read-only `credit_recognition_effective_from` (the first business day of `on_payment`, the day after it was asked for: the rule counts from that day, and credit sales dated earlier keep counting the way they did; it is one way, so asking for `on_sale` once that day has come answers 422 `credit_rule_cannot_be_switched_off`, "This rule cannot be switched off once it has started. Ask Rincol support.", and before it cancels the change; a payment counts on the day it was entered and at the branch that took it), choosing a profile writes its preset
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
| GET | `/retail/sales/{sale_id}/receipt` | `retail.sale.read` | The facts of the receipt to share, with the same check and refusals as its PDF (issue #439, ADR-061): `{sale_id, receipt_no, business_name, buyer_name?, buyer_contact? (the buyer record's number, else the sale's), customer_id?, customer_version?, customer_has_contact, payment_method, currency, total_minor, paid_minor (the money actually paid: a return taken off the balance is not counted), balance_minor (as on the sale), returned_minor (completed returns), net_minor, credit_notes, sale_version}`. Nothing is audited: it writes nothing |
| GET | `/retail/sales/{sale_id}/receipt/pdf` | `retail.sale.read` | The receipt of a sale for the seller to share from their own phone (issue #439, ADR-061, FR-RET-66): `application/pdf`, the invoice layout titled RECEIPT (RECEIPT AFTER RETURNS with each credit note and the net when goods were taken back) under the sale's number (`sale_no`), signed by the seller who made the sale, with the balance the sale carries, rendered now from the sale and never stored, numbered in no series and written to no table, so it answers with the `documents` capability on or off and needs no `retail.document.issue`. Stricter than reading the sale: without `retail.sale.all_sellers` at the sale's branch only the caller's own sales (another seller's credit sale they may read to take a payment is 404 here); 404 outside the scope; 422 `sale_voided`, `sale_written_off` or `sale_fully_returned`; 503 `documents_busy` with Retry-After (the checks run before any render place is taken; the PDF is laid out in the receipt's own place, apart from the document and report places, ADR-061). Nothing is audited: it writes nothing |
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

### 7.11.25 Feedback (`/feedback`, ADR-065, issue #447)

Open to every signed-in member of staff (`@AuthenticatedEndpoint(kind = "staff")`, like `/me`): no matrix permission. The operator's
side is in section 7.11.3.

| Method | Path | Permission | Notes |
|---|---|---|---|
| POST | `/feedback` | authenticated staff | `multipart/form-data`: `kind` (`idea`, `problem`, `question`), `message` (1 to 2000 characters), `page_path` (optional; only the path is kept), `screenshot` (optional PNG or JPEG, at most 2 MB). The user agent is read from the request and the version from the server's own build. 201 with the `FeedbackItem`. 422 `validation_failed`, `screenshot_not_an_image`, `screenshot_too_large`; 429 `feedback_rate_limited` with `Retry-After` after 10 in an hour (FR-FBK-01, FR-FBK-05). Queues the operator email in a savepoint (FR-FBK-04) |
| GET | `/feedback` | authenticated staff | `{items: [FeedbackItem]}`: the caller's own, newest first, at most 100, with status, reply and `has_screenshot` (FR-FBK-02) |
| GET | `/feedback/{feedback_id}/screenshot` | authenticated staff | The picture of one of the caller's own items; 404 for anyone else's or none |

### 7.11.26 Referrals (`/referrals`, ADR-069, issue #464)

The business's own referrals, for its owner and administrators. The operator's side is in section 7.11.3.

| Method | Path | Permission | Notes |
|---|---|---|---|
| GET | `/referrals` | `core.referrals.read` | `{code, link, items, totals}`: the business's referral code, the public sign-up link with it (`{platform origin}/sign-up?ref=CODE`), and for each business it referred, newest first, `referred_name`, `joined_at`, `status` (`applied` for a confirmed application not activated yet, `active`, `paying`, or `ended` for a referral moved to another referrer that carries lines) and `totals` per currency (`earned_minor`, `paid_minor`, `due_minor`); `totals` adds them up. Nothing else of the referred business. FR-REF-01, FR-REF-07 |

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
