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
| SMS | Outbound, plus delivery report callbacks | Pending ADR-013 (Africa's Talking is the working assumption) | `SmsProvider` |
| USSD | Inbound session callbacks | Pending ADR-013, later phase | `UssdProvider` |
| Payment gateway (mobile money, cards) | Outbound collection requests, inbound callbacks, status queries, settlement reports | Pending ADR-011 (Pesapal or Interswitch) | `PaymentGateway` |
| Object storage | Outbound | Cloudflare R2 over the S3 API (product plan) | `ObjectStorage` |
| Transactional email | Outbound | Provider chosen with the infrastructure work; no ADR yet | `EmailProvider` |

## 12.3 SMS aggregator

### 12.3.1 Interface

```
SmsProvider
  send(to_e164, body, sender_id?, client_ref) -> {provider_message_id, segments, status}
  parse_delivery_report(http_request) -> [{provider_message_id, status, error_code?}]
  verify_delivery_report(http_request) -> bool
```

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

### 12.3.3 What pending ADR-013 must settle

Provider; sender ID registration per tenant or shared; delivery report authentication
method; USSD code model (per tenant or shared); pricing model and whether SMS cost is
passed to tenants (commercial, outside this repository); data residency of message
content.

## 12.4 Payment gateway and mobile money

### 12.4.1 Interface

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

### 12.4.4 What pending ADR-011 must settle

Gateway (Pesapal or Interswitch; the pilot tenant has mentioned Interswitch); which
account holds the merchant relationship (each tenant its own merchant account, or a
platform account with sub-accounts; this has licensing implications); callback
authentication (signature scheme, IP allow-list); sandbox availability; settlement report
access; fee model (commercial terms stay outside this repository); refunds.

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
refused in production.

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
