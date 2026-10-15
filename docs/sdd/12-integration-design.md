# 12. Integration Design

**Status:** Draft · **Owner:** Hillary

## 12.1 Principles

- **Adapters behind interfaces.** Each external service sits behind one interface in the
  core (`notifications`, `payments`, `documents`). Business code never calls a provider
  directly. Swapping a provider is a new adapter plus configuration.
- **Undecided providers stay undecided in code.** Where the provider is pending an ADR,
  the MVP ships the interface, a fake adapter for development and tests, and the first
  real adapter once the ADR is accepted.
- **Nothing external inside the money transaction.** Calls to providers happen in the
  worker or before the transaction starts, never while a database transaction holds row
  locks. Outcomes come back as new transactions.
- **Every inbound call is authenticated and idempotent.** Callbacks are verified, then
  confirmed by querying the provider, then booked once.
- **Timeouts everywhere.** Connect timeout 5 seconds, read timeout 15 seconds, then retry
  with backoff in the worker. A circuit breaker opens after 5 consecutive failures for 60
  seconds, so a provider outage does not pile up workers.

## 12.2 Integration summary

| Integration | Direction | Decision | Adapter interface |
|---|---|---|---|
| SMS | Outbound (built, #212), plus delivery report callbacks (#215) | ADR-036: Africa's Talking first, as a channel of the notification outbox | `SmsProvider` |
| USSD | Inbound session callbacks | Pending ADR-013, later phase | `UssdProvider` |
| Payment gateway (mobile money, cards) | Outbound collection requests, inbound callbacks, status queries, settlement reports | ADR-035 (proposed): Pesapal v3 | `PaymentGateway` |
| Object storage | Outbound | Cloudflare R2 over the S3 API (product plan) | `ObjectStorage` |
| Transactional email | Outbound | Provider chosen with the infrastructure work; no ADR yet | `EmailProvider` |

## 12.3 SMS aggregator

### 12.3.1 Interface

```
SmsProvider
  send(to_e164, body, sender_id?, client_ref) -> {provider, status, message_id?, segments?, cost_raw?, failure_reason?}
        status: ACCEPTED | SUNK | REJECTED; throws Transient (certainly not sent) or Ambiguous (may have been sent)
  parse_delivery_report(http_request) -> [{provider_message_id, status, error_code?}]      (#215)
  verify_delivery_report(http_request) -> bool                                             (#215)
```

Built in issue #212: `send` only. Adapters are `AfricasTalkingSmsProvider` (a form POST with the key in the `apiKey` header, the
JSON answer read per recipient, a 15 second total time per message, a clean failure for a connection that could not be made
or a 401, 403, 429 or 503 answer, or no balance, and an ambiguous one for anything after the request was written: a timeout,
a broken connection, an SSL error while reading, an HTTP 500, 502 or 504 or an internal or gateway error code in the answer;
a 409 Do Not Disturb is a final rejection), `SinkSmsProvider` (no client, no URL, no
credential; the only provider allowed outside production), `AllowlistSmsProvider` (the real provider for listed numbers, the
sink for the rest) and `RecordingSmsProvider` (the fake). The configuration picks the provider from `BMS_SMS_MODE`
(`SmsConfiguration`) and the startup guard (`SmsSettings.resolve`) refuses every combination that could text a real phone from
outside production.

`client_ref` is the `notifications.id`, so a retried send that the provider already
accepted can be recognised.

### 12.3.2 Flow

1. A business transaction writes a `notifications` row (`queued`) and an outbox row.
2. The worker renders nothing new (the body was rendered at queue time), checks the
   sending window, calls `send`, stores `provider_message_id` and `segments`, sets
   `sent`.
3. Failure: retry at 1, 5 and 30 minutes, then `failed` (FR-NTF-05).
4. Delivery report callback on `<callback host>/api/v1/channels/sms/{provider}/delivery-reports`:
   verify, look up by `provider_message_id`, update status to `delivered` or
   `undelivered`. Unknown ids are logged and ignored.

### 12.3.3 What ADR-036 settles and what stays pending ADR-013

ADR-036 settles the SMS half: the provider (Africa's Talking first), the tenant link on the outbox row, the
delivery log, modes that make a real send on staging impossible, sending windows, opt-out, the delivery report
endpoint with a path token, and cost recording (spec `docs/specs/client-extras.md` section 4). Still pending ADR-013:
the USSD code model (per tenant or shared) and USSD sessions. Commercial questions (whether SMS cost is passed on)
stay outside this repository. The flow of section 12.3.2 changes in one way: the body is rendered at send time inside
a short tenant transaction, because tenant template overrides and the opt-out need the tenant. The channel returns `Sent`,
`Deferred(until)` or `Unknown` (a read timeout after the request was written) instead of only throwing; retries follow the outbox
(attempts at once, after 1 and after 5 minutes) and the 30 minute step of FR-NTF-05 is dropped for SMS. As built (issue #212) the
channel also answers `Refused(reason)` for a message it will never send (see the amendment in ADR-036), writes the delivery log
row `queued` before the network call so a crash is never resent, and has its own sender job. As built (issue #218) the
same transaction also reads the tenant's own settings (its switch, sender id, window, caps and balance switch), the tenant's
wording for the event, and the opt-out rows of the member and of the number, and passes the sender id to
`SmsProvider.send` only while the operator still approves it for the tenant; the test message of the wording screen is a
separate path that can reach only the sink or the allow-list (ADR-036 second amendment).

## 12.4 Payment gateway and mobile money

### 12.4.1 Interface (amended by ADR-035)

Pesapal v3 collections are hosted orders, so `initiate_collection` becomes `create_order` and returns a redirect URL;
`verify_callback` and `parse_callback` become `read_notification`, which extracts identifiers only, because the
notification carries no signature. The listing below is the original shape; `docs/specs/client-extras.md` section 3.3 is
the current one.

```
PaymentGateway
  initiate_collection(intent_id, amount_minor, currency, payer_phone_e164, description)
      -> {provider_reference, status: pending | failed, failure_reason?}
  query_status(provider_reference) -> {status: pending | succeeded | failed, amount_minor, currency, paid_at?, fee_minor?}
  verify_callback(http_request) -> bool
  parse_callback(http_request) -> {provider_reference, reported_status, raw}
  fetch_settlement(date) -> [{provider_reference, amount_minor, fee_minor, settled_at}]
```

### 12.4.2 Collection flow

```
member/staff -> API: POST /payments/intents (Idempotency-Key)
API: create intent (created) -> commit
worker: initiate_collection -> intent pending (provider_reference stored)
payer approves on phone
gateway -> <callback host>/api/v1/payments/callbacks/{provider}
API (no tenant yet): verify_callback -> parse -> app_resolve_payment_intent(provider, ref)
    unknown ref -> unmatched_gateway_callbacks (platform queue), 200 OK
    known ref   -> bind tenant -> store payment_callbacks row
                -> query_status(ref)  (never trust the callback body alone)
                -> succeeded and amounts match -> book via the purpose's handler
                   (repayment, deposit, investment funding) in one transaction,
                   idempotent on (provider, provider_reference)
                -> mismatch -> outcome status_mismatch, alert, no booking
worker: every 2 minutes, query_status for intents pending over 2 minutes; expire after 30
```

Booking a gateway receipt posts to the `gateway_clearing` account (payment method
`gateway`). The daily settlement job (FR-PAY-05) matches settled amounts and fees: fees
post as gateway charges (FR-PAY-06), and the net settlement moves from
`gateway_clearing` to `bank` when the tenant records the bank credit.

Money that arrives without a usable intent (for example a member paying the tenant's
merchant number directly, reported in the settlement file) becomes an unallocated
receipt (FR-PAY-04).

### 12.4.3 Direct mobile money without a gateway

Cash-desk receipts by mobile money (a member sends money to the tenant's own wallet and
the cashier records it) need no integration: the cashier records a repayment with
payment method `mtn_momo` or `airtel_money` and the wallet's transaction reference. This
is the MVP path and works before any gateway is chosen.

### 12.4.4 What ADR-035 settles

The gateway is Pesapal v3. The merchant relationship is the lender's own account (bound to the one tenant named by `BMS_PESAPAL_TENANT_ID` while
credentials are environment-level; per-tenant credentials, each with its own IPN id and token, in slice #233); the platform's own merchant account is used only for
subscription payments. Notifications are untrusted hints, confirmed by an authenticated status query. The public
reference documents no signature, no settlement report endpoint and no payout endpoint, so reconciliation re-queries
intents and uses an uploaded merchant statement for fees, and payouts are out of scope. The fee model stays a
commercial decision outside this repository; refunds are a manual back-office step for now. Detail: spec
`docs/specs/client-extras.md` section 3. The sandbox spike (issue #211, 2026-10-13) added to the adapter's contract: business errors
arrive as http 200 with a body status of 500; an unpaid order reads status code 0 with `payment_details_not_found` (pending, not failed);
a repeated merchant reference returns the original order; there is no lookup by merchant reference; RegisterIPN is throttled to about
one call in five seconds; the token lives 3600 seconds in the sandbox. Observed and documented facts are separated in spec section 3.12.

### 12.4.5 Language model provider (ADR-037)

```
LlmClient
  complete(request{model, messages, max_tokens, response_format}) -> {text, input_tokens, output_tokens, finish_reason}
```

One OpenAI-compatible chat adapter reads `BMS_AI_BASE_URL`, `BMS_AI_MODEL` and `BMS_AI_API_KEY`, so another compatible
provider is configuration (the first is DeepSeek, `https://api.deepseek.com`, its cheapest chat model). Built in #220 as
`core.ai`: the adapter posts `<base>/chat/completions` with a bearer key, `temperature` 0, a `max_tokens` on every request and
`response_format` `json_object` on request; the total budget is 30 seconds at most, a 429 or a 5xx is retried up to three
attempts (pauses 0.5 s and 1 s, or a `Retry-After` of up to 10 s), a read timeout, another 4xx and a redirect are not;
five failures in a row open the process-wide breaker for 60 seconds and one probe closes it; a provider error body is
never read into an exception, a log line or the call log. Callers use `AiGateway`, never the port: it checks the tenant
switch, the platform allow-list, an input screen (six or more digits, an email address, a phone number in any spacing, a
national ID shape: refused with no call) and the daily token and cost caps of the tenant and of the platform before any
network call, and answers with the text or a fallback and a closed reason; it refuses to run inside a transaction. A call that
fails after its request was sent keeps its reservation against the day, and the breaker settles every call with a permit of
its generation, so a late result cannot close it or free the probe slot. `BMS_AI_PROVIDER` is `off` by default; a provider with a
missing URL, key or model, a key with whitespace, or a non-https URL is off with a warning; the recording stub is refused
outside the dev and test profiles. Only the advisor module calls it, and only with the typed fact list and aliases (spec
section 5.4).

## 12.5 Object storage (Cloudflare R2)

```
ObjectStorage
  put(key, bytes, content_type) -> {etag}
  signed_get_url(key, ttl_seconds=300, download_filename?) -> url
  delete(key)
```

- Buckets: one for documents, one for backups, per environment (names in chapter 9).
  Both private; no public access.
- Keys: `tenants/<tenant_id>/<doc_type>/<yyyy>/<mm>/<document_id>.<ext>` for documents;
  backups under `backups/<environment>/<yyyy>/<mm>/<dd>/`.
- Access: scoped API token per bucket and environment. The API issues signed URLs only
  after a permission check (FR-DOC-03).
- Upload of user files goes through the API (size and type checks, chapter 8 section
  8.8), not direct browser to bucket, in the MVP.

## 12.6 Transactional email

```
EmailProvider
  send(to, subject, text_body, html_body?, client_ref) -> {provider_message_id}
```

Used for staff invitations, password resets and report-ready notices. SPF, DKIM and
DMARC for the sending domain are set up with the mail provider (chapter 9).

## 12.7 Configuration

Each adapter reads its settings from environment variables documented in `.env.example`
(chapter 9 owns the file). Naming pattern: `SMS_PROVIDER`, `SMS_*`; `PAYMENT_GATEWAY`,
`PAYMENT_*`; `R2_*`; `EMAIL_PROVIDER`, `EMAIL_*`. Setting a provider key to `fake` selects
the recording fake adapter, which is the default in local and test environments and is
refused in production. The SMS adapter adds `BMS_SMS_MODE` (`off`, `sink`, `allowlist`, `live`; section 4.4 of the
client extras spec) and the AI adapter `BMS_AI_*`; the complete list is in chapter 9 section 9.8.

Object storage (#12): `OBJECT_STORAGE` is `r2` (default) or `fake`, and `fake` refuses to start
outside the dev and test profiles. `r2` needs `R2_ENDPOINT`, `R2_DOCUMENTS_ACCESS_KEY_ID`,
`R2_DOCUMENTS_SECRET_ACCESS_KEY` and `R2_DOCUMENTS_BUCKET`, a token scoped to the documents bucket
only (never the backup token), and startup fails if any is empty. The fake keeps objects in
memory and serves its own signed URLs at `/api/v1/storage/fake` (HMAC over key and expiry), so
expiry and tampering behave as with R2 without a local bucket.

## 12.8 Testing integrations

- Fakes implement each interface and record calls; unit and API tests use them.
- Contract tests per real adapter run against the provider's sandbox in a separate,
  manually triggered CI job (never on every pull request, never against production).
- Callback handling is tested with recorded, fabricated callback bodies, including bad
  signatures, replays, unknown references and amount mismatches (FR-PAY-02, FR-PAY-03).
