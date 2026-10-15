# Client extras: mobile money, SMS, AI monitoring, the reports pack, and a note on credit bureaus

**Status:** Design; slices #212 (SMS channel core), #214 (report runs) and #217 (report PDF and Excel, portfolio and PAR) built · **Issue:** #210 (umbrella) · **Decisions:** ADR-035 (Pesapal collections),
ADR-036 (SMS channel), ADR-037 (AI monitoring), ADR-038 (reports pack); PDF rendering follows
ADR-034 (accepted, merged in pull request 205) ·
**Expectations:** issue #150 · **SDD:** chapters 5, 7, 8, 9, 11, 12, 14 and 15 carry the consequences

This specification covers five extras a lending client asked for. It is a design, and every build slice
is a separate issue (section 10); where a slice is built, its section says so (section 4.15 for the SMS channel core). It uses invented examples only; no
client name, price, phone number or secret appears in it.

| Area | Product owner decision (2026-10-11) | Where |
|---|---|---|
| A. Mobile money | Pesapal v3 API for collections: MTN MoMo, Airtel Money and cards, in UGX. Loan repayments, savings deposits and subscription payments. Payouts later, only if a provider supports them | section 3, ADR-035 |
| B. SMS | A provider adapter on the existing notification outbox, first provider Africa's Talking | section 4, ADR-036 |
| C. AI monitoring | DeepSeek through an OpenAI-compatible chat API, cheapest model, as a first test; advisory only | section 5, ADR-037 |
| D. Reports pack | Increment 8 (#111): seven formal reports as PDF and Excel, run asynchronously | section 6, ADR-038 |
| E. Credit bureau | Deferred: a note and an extension point | section 7 |

## 1. Principles shared by all five

1. **Off until switched on.** Each extra is off by default per tenant and per environment. A missing
   environment variable switches an adapter off; it never fails the application (as the SMTP and
   Telegram senders do, FR-NTF-10).
2. **Sandbox first, fakes always.** Every provider has a port, a recording fake, and a sandbox
   account used on staging. Staging never sends a real SMS, prompts a real payer, or sends a real
   member's data to a model.
3. **Secrets stay in the server environment** (chapter 8 section 8.7, chapter 9 section 9.8). They are
   listed by name in `.env.example` with placeholders and never appear in the repository, a log, an
   error message or an API response.
4. **Nothing external inside a money transaction.** Provider calls happen in jobs or before a
   transaction starts; outcomes come back as new transactions (chapter 12 section 12.1).
5. **Every inbound call is untrusted.** A payment notification and a delivery report are hints; the
   decision comes from our own authenticated query of the provider, and money is booked once.
6. **Existing code does the posting.** Gateway payments post through `LoanServicing` and
   `SavingsServicing`, SMS goes through the outbox, AI reads the insights metrics, reports reuse the
   insights queries. No second implementation of any money rule.
7. **No personal data in logs, in prompts or in fixtures** (standing rules 9 and 10). Phone numbers are
   masked to the last four digits, keyed hashes serve matching.
8. **Docs, tests and a runbook ship with each slice** (definition of done in `AGENTS.md`).

## 2. How the pieces meet

```
member / staff ---- pay ----> PWA ---- POST /payments/intents ----> core.payments ---- create order ----> Pesapal
                                                                         ^   |                              |
        Pesapal ---- IPN (hint) ---- callback host ---------------------+   +-- GetTransactionStatus ------+
                                                                         |
                                  purpose handler (lending.loans / lending.savings) --> core.ledger (journal)
                                                                         |
                                  outbox (receipt SMS) --> SmsOutboxChannel --> Africa's Talking --> delivery report
                                                                         
insights metrics --> PortfolioFacts --> lending.advisor --> core.ai --> OpenAI-compatible provider (aggregates, aliases)
insights queries --> lending.reports --> core.reporting runner --> PDF (shared templates) + Excel --> object storage
```

## 3. Mobile money through Pesapal (ADR-035)

### 3.1 What the API supports, and what it does not

Taken from the provider's public API 3.0 reference at the time of writing; section 3.12 lists what the
sandbox spike (#211) must confirm before any code is written.

| Capability | Supported | Use here |
|---|---|---|
| Access token (key and secret in, bearer token out, valid at most five minutes) | Yes | Cached in memory per credential pair, refreshed before expiry |
| Register an IPN URL (GET or POST type), list IPNs | Yes | One registration per environment, done by an operator command, id kept in settings |
| Submit an order: our unique reference (at most 50 characters), currency, amount, description (at most 100), callback URL, IPN id, billing address with a phone or email | Yes, returns an order tracking id and a **redirect URL** to a hosted payment page | Every collection |
| Transaction status by order tracking id: status code (0 invalid, 1 completed, 2 failed, 3 reversed), amount, currency, payment method, confirmation code, masked payer account, our reference | Yes | The only source of truth |
| Refund request, order cancellation | Documented | Cancellation of abandoned orders; refunds are a manual back-office step for now |
| Recurring payments | Documented | Out of scope (subscription auto-debit is a later idea) |
| Server-to-wallet push prompt without a hosted page | Not in the public reference | Not used; the payer opens the link |
| Payouts, disbursement, bulk payments | **Not documented** | Out of scope; loan disbursement and savings withdrawals stay manual |
| Settlement report or fee per transaction through the API | **Not documented** | Operator uploads the merchant statement (3.9) |
| IPN signature | **Not documented** | The IPN is never trusted (3.6) |

### 3.2 The collection flow

1. A user starts a payment: staff on a loan or savings account (cashier, loan officer), or later the
   member in the portal. The request names a purpose, the subject (loan or account), the amount and
   the payer's phone, with an `Idempotency-Key` (chapter 7 section 7.8).
2. The API validates the amount against the subject (3.7), writes a `payment_intents` row (`created`)
   and commits. Nothing external has happened yet.
3. The `core.payments.create-order` job (or the request after commit, with a 15 second budget) claims
   the intent as its single creator (3.5), gets a token, calls `SubmitOrderRequest` with the merchant
   reference and the IPN id of the credential pair, stores the order tracking id and redirect URL and
   sets the intent `pending`.
4. The payer opens the redirect URL (a link, a QR code on the cashier's screen, or later an SMS,
   section 4.13), chooses MTN MoMo, Airtel Money or card on the hosted page and approves on the phone.
5. Pesapal calls our IPN URL on the callback host. The handler only records the call and queues a
   status check (3.6). A poller also checks pending intents every 2 minutes for 48 hours, so a lost
   IPN costs a delay, not a payment.
6. The status check calls `GetTransactionStatus`. On `COMPLETED` with matching reference and currency
   the booking transaction runs (3.4); on `FAILED` or `INVALID` the intent ends `failed`; on
   `REVERSED` after booking, 3.8 applies.
7. The staff screen polls `GET /payments/intents/{id}` every 3 seconds for up to 2 minutes, then shows
   "still waiting, we will update this screen" (chapter 11 section 11.5).

### 3.3 The gateway port (amends chapter 12 section 12.4.1)

```
PaymentGateway
  create_order(intent_id, merchant_reference, amount_minor, currency, payer_phone_e164, description)
      -> {provider_reference, redirect_url}
  query_status(provider_reference)
      -> {status: pending | completed | failed | invalid | reversed, paid_amount_minor, currency,
          merchant_reference, payment_method?, confirmation_code?, paid_at?}
  read_notification(http_request) -> {provider_reference?, merchant_reference?}     # identifiers only
  cancel_order(provider_reference)                                                   # best effort
  fetch_settlement(date) -> [...]                                                    # optional; not for Pesapal
```

`PesapalGateway` converts minor units to the decimal the API takes with `BigDecimal` and the currency
exponent (UGX has exponent 0), and converts the paid amount back, refusing a value the currency
cannot hold. A `PayoutGateway` interface is reserved and not implemented.

### 3.4 Purposes, matching and booking

An intent carries a **purpose** and a **subject**. The merchant reference identifies the intent, the
intent identifies the subject: there is no free-text matching of references to loans. Purpose handlers
are registered by the owning module (chapter 5 section 5.4.3).

| Purpose | Handler (module) | Effect | Value date |
|---|---|---|---|
| `lending.loan_repayment` | `LoanServicing` (lending.loans) | Repayment allocated by R-ALLOC, receipt number, journal against `gateway_clearing` | Payment date; today if the payment date is before the loan's last repayment or in a closed period |
| `lending.savings_deposit` | `SavingsServicing` (lending.savings) | Deposit with receipt and running balance | Payment date, but never before the last end of day written |
| `platform.subscription_payment` | Subscription confirmation (core.platform) | The claimed payment is confirmed once; period, state and commission follow (ADR-024) | Payment date |
| `lending.investment_funding` | Later, with member self-service | Funding through the maker-checker route | Later |

**Booking**, in one transaction: lock the intent row; if `booked_subject_txn_id` is set, stop (duplicate);
check status `completed`, equal merchant reference and currency; call the handler inside a **savepoint**
with method `gateway`, the confirmation code as external reference (3.4.1) and an audit entry naming the
order tracking id; store the booked transaction id, the paid amount, the confirmation code and the
reported payment method on the intent. `UNIQUE (provider, provider_reference)` backs it at the database.
The audit actor is the intent's initiator.

**A handler refusal never strands a completed payment** (ADR-035 decision 14):

| Handler outcome | What happens in the same transaction |
|---|---|
| Booked | Intent `booked` |
| `before_last_repayment` | The savepoint rolls back and the handler is retried once dated today; if that books, intent `booked` with the note "dated today" |
| Any other business refusal (`value_date_in_future`, `payment_method_unmapped`, loan closed, account closed or frozen, a refused currency) | The savepoint rolls back; the amount is posted to unallocated receipts (debit `gateway_clearing` by its system key so an unmapped method cannot block it, credit `unallocated_receipts`); the refusal code is stored; intent `booked_unallocated`; alert; a cashier allocates it (FR-PAY-04) |
| Technical failure (database error) | The whole booking rolls back; the poller retries with backoff; after five consecutive failures the intent becomes `needs_attention`, listed to the accountant and the operator with an alert |

Intent states are therefore `created`, `pending`, `expired`, `failed`, `booked`, `booked_unallocated`,
`needs_attention` and `reversed_pending_review`; each completed payment ends in `booked` or
`booked_unallocated` (or `needs_attention` with a person looking at it), never in a silent retry loop.
Collections cannot be switched on until the `gateway` payment method is mapped (FR-GL-08).

New columns on `payment_intents` over chapter 6 section 6.5: `merchant_reference` (unique),
`provider_reference` (order tracking id), `redirect_url`, `scope` (`tenant` or `platform`),
`paid_amount_minor`, `confirmation_code`, `reported_method`, `last_checked_at`, `final_status`,
`refusal_code`, `creation_lease_until`, `technical_failures`. `payment_callbacks` keeps one row per
notification with identifiers only (no body that could carry anything else is trusted),
`status_query_result`, and `outcome` (`booked`, `duplicate`, `rejected`, `status_mismatch`,
`unknown_reference`, `throttled`).

### 3.4.1 The servicing ports (amends ADR-026 and ADR-032, in slices #216 and #219)

Today `LoanServicing.recordRepayment` and `SavingsServicing.deposit` take no idempotency key or external
reference (the loan servicer is called with a null external reference), `SavingsServicing` queues no
receipt SMS, and both javadocs say the ports serve only the seed and the import. Gateway booking needs
more, so each port gains an overload:

```
LoanServicing.recordRepayment(loanId, amountMinor, valueDate, paymentMethodKey, recordedBy,
                              externalReference, origin, queueReceiptNotice) -> txnId
SavingsServicing.deposit(accountId, amountMinor, valueDate, paymentMethodKey, recordedBy,
                         externalReference, origin, queueReceiptNotice) -> txnId
```

- `externalReference` is the provider's confirmation code (the order tracking id if none is given),
  stored in the transaction's existing external reference column. A unique index over (tenant,
  payment method, external reference) for method `gateway` makes a second call with the same reference a
  no-op that returns the first transaction, so idempotency holds at the port as well as on the intent.
- `origin` is `gateway`; the audit entry carries it with the order tracking id.
- `queueReceiptNotice` queues the repayment or savings receipt SMS in the same transaction (templates of
  slice #221 and the existing savings texts of ADR-032). The seed and the import pass false, so they still
  message nobody.
- Both interfaces' javadocs are corrected to "the seed, the import and gateway bookings", and an
  Amendment section (dated, additive) is appended to ADR-026 and ADR-032 in those slices; the records
  are not rewritten.

### 3.5 The merchant reference and order creation

`BMS-<env>-<32 hex characters of the intent id>`, for example `BMS-ST-0f8e...` (39 characters), using
letters, digits and dashes only. The intent id is a random version 4 UUID, so the reference is not
guessable and carries no tenant, amount or person. `<env>` is `ST` on staging and `PR` on production
(from `BMS_ENVIRONMENT`).

**One creator, one use.** A reference is used for one `SubmitOrderRequest` only. The creating job claims
the intent with `FOR UPDATE SKIP LOCKED` and sets `creation_lease_until`, so two workers cannot create the
same order. If the call times out or its answer is lost, the intent ends `failed` with reason
`creation_unknown` and the user starts a **new intent with a new reference**; the old reference is never
retried, because what the provider answers to a duplicate reference is not documented (the spike confirms
it) and a status query needs the tracking id that the lost answer carried. This cannot lose money: the
payer only receives a link from a successful answer, so an order whose answer was lost has no link in
anyone's hands. If the spike shows a lookup by merchant reference, the creator may use it to recover the
tracking id instead.

### 3.6 Securing the IPN endpoint

`POST` or `GET /api/v1/payments/callbacks/pesapal/{token}` on the callback host, public, no session.

1. The token in the path is a long random value (`BMS_PESAPAL_IPN_TOKEN`) compared in constant time.
   It keeps noise out; it is not what makes the endpoint safe, and proxy logs redact it.
2. The handler reads only the order tracking id and the merchant reference from the request. Nothing
   else in the request, including any amount or status, is read.
3. It resolves them with the definer function `app_resolve_payment_intent(provider, tracking id,
   merchant reference)` (ADR-016), which returns the tenant and intent or nothing. **Nothing unknown
   triggers an outbound call**, so the endpoint cannot be used to make us query arbitrary ids.
4. It stores the `payment_callbacks` row, queues one status check for the intent and answers 200 with
   the acknowledgement body Pesapal expects (confirmed in the spike).
5. The status check is throttled to one per intent per ten seconds; extra notifications are stored as
   `throttled`.
6. The check calls the provider with our own token. The decision uses only that answer: status,
   merchant reference equal to the intent's, currency equal to the intent's. The paid amount is what
   gets booked (3.7).
7. A flood is limited at the edge (a Cloudflare rule on the callback path, as for sign-up, chapter 9
   section 9.8) and by the proxy's per-address limit; the response is cheap because it does no
   provider call for unknown or throttled notifications.

### 3.7 Amounts: exact, partial, excess, wrong currency

| Case | Behaviour |
|---|---|
| Paid equals ordered | Book |
| Paid less than ordered (any cause) | Book the paid amount as a partial repayment or deposit; the intent shows `paid_less`; staff see the difference |
| Paid more than ordered | Book the paid amount; the servicing code's overpayment credit (R-ALLOC) holds the excess on a loan, a deposit simply increases the balance; flagged `paid_more` |
| Currency differs from the intent | Never book to the subject; unallocated receipt and an alert |
| Subject cannot take the money (loan closed, account closed or frozen) or the handler refuses for any business reason | The whole amount becomes an unallocated receipt in the same transaction (section 3.4); a cashier allocates or refunds |
| Amount asked at creation | At least one currency unit, at most the loan's payoff quote for a repayment (a larger amount is refused with `exceeds_payoff`) and the tenant's per-transaction limit for a deposit |

### 3.8 Duplicates, failures, late and reversed payments

| Event | Behaviour |
|---|---|
| Same IPN three times, or IPN and poller together | One booking; the others log `duplicate` |
| IPN for an unknown order | Stored in the platform queue, 200, no provider call, counted for the alert |
| Status `FAILED` or `INVALID` | Intent `failed`, shown to staff, nothing booked; the payer may start a new intent |
| Order creation timed out | Intent `failed` (`creation_unknown`); a new intent with a new reference (3.5) |
| Status pending after 30 minutes | Intent `expired` for the screen (no new payment offered); polling continues for 48 hours |
| Completed after the intent expired | Books normally: the money has moved; the audit note says "late" |
| After 48 hours still pending | Best-effort `cancel_order`, intent final `expired` |
| Handler refuses a completed payment | Savepoint, retry dated today for `before_last_repayment`, otherwise unallocated receipt and alert (3.4) |
| Status `REVERSED` after booking | No automatic reversal. One approval request per intent (`repayment_reversal` or `savings_reversal`) made by the tenant's non-login system user, so the maker is never the checker (ADR-035 decision 16); the intent shows `reversed_pending_review`. The request is stale if allocations change before it is decided (FR-APR-08), and the next reconciliation raises a fresh one |
| A savings deposit reversed after it was withdrawn | The servicing code refuses a reversal that would make the balance negative; the intent stays `reversed_pending_review`, the reconciliation report shows the shortfall, and the accountant records the loss by manual journal. The platform never creates a negative balance |
| Card payment reversed weeks later | The reconciliation re-queries card payments for 120 days (mobile money 7 days), daily for 14 days then weekly (3.9) |
| The same mobile money receipt also entered by a cashier by hand | The reconciliation report flags a manual receipt whose reference equals a gateway confirmation code (3.9) |
| The provider is down | Breaker opens after 5 failures for 60 seconds; intents stay `created` or `pending` and the poller catches up |

### 3.9 Daily reconciliation (FR-PAY-05)

`core.payments.reconcile` runs nightly after the end of day. It re-queries every intent not in a final
state and every intent booked within the reversal window (7 days for mobile money, 120 days for card
payments, using the payment method the status query reported; daily for the first 14 days, weekly
after) and lists, in a report and an operator alert:
completed but not booked; booked but now reversed or failed; pending over 24 hours; paid differing from
ordered; manual receipts that duplicate a gateway confirmation code; unmatched notifications. The
**statement upload** (`core.payments.manage`) takes the merchant statement as CSV, matches rows on
confirmation code and merchant reference, records the fee and the settled amount, posts the fee to the
gateway charges account (FR-PAY-06, once per row) and lists statement rows that match no intent as
unallocated receipts (FR-PAY-04). Moving the net settlement from `gateway_clearing` to the bank is the
tenant's bank credit entry, as chapter 12 section 12.4.2 describes. If the provider later documents a
settlement endpoint, `fetch_settlement` replaces the upload.

### 3.10 Screens

Staff, on a loan or savings account, with `core.payments.collect`: "Collect by mobile money or card":
amount (defaulting to the instalment due or the arrears), payer phone (defaulting to the member's
primary phone), a QR code and a copy-link button, live status with the plain reasons of 3.8. The
payments list (`core.payments.read`) shows intents with status and flags. The unallocated receipts
queue (`core.payments.allocate`). The member's own screen waits for the member portal (increment 11).

### 3.11 Credentials, tenancy and environments

| Variable | Purpose |
|---|---|
| `BMS_PESAPAL_BASE_URL` | Sandbox or live API base. The sandbox URL is refused on the production profile and the live URL on staging |
| `BMS_PESAPAL_CONSUMER_KEY`, `BMS_PESAPAL_CONSUMER_SECRET` | The merchant credentials used for tenant collections |
| `BMS_PESAPAL_IPN_ID`, `BMS_PESAPAL_IPN_TOKEN` | The IPN registration of that credential pair and the random path token of 3.6 that selects it |
| `BMS_PESAPAL_TENANT_ID` | The one tenant this merchant account is bound to |
| `BMS_PESAPAL_PLATFORM_CONSUMER_KEY`, `BMS_PESAPAL_PLATFORM_CONSUMER_SECRET` | The platform operator's own merchant for subscription payments; absent means subscription payments stay manual |
| `BMS_PESAPAL_PLATFORM_IPN_ID`, `BMS_PESAPAL_PLATFORM_IPN_TOKEN` | The same two values for the platform pair: **an IPN registration belongs to one credential pair**, so every pair (the platform's, each later per-tenant account) has its own IPN id and its own path token |

**The merchant is bound to a named tenant.** `payments.collections_enabled` (`core.settings.manage`,
default off) can be switched on only for the tenant named in `BMS_PESAPAL_TENANT_ID`. For every other
tenant, and for all tenants while the variable is unset, the route answers 409
`merchant_not_bound_to_tenant`. Without the binding, whichever tenant admin switched collections on
first would receive the environment's merchant account, and the money would reach the wrong lender. The
account that receives the money must be that lender's own; a platform account collecting for several
lenders raises licensing and fund-flow questions that need a decision of their own. Switching on is also
refused (409 `gateway_method_unmapped`) until the `gateway` payment method is mapped to a ledger account.
Slice #233 adds per tenant credentials stored encrypted with the application data key, each with its own
IPN id and token, and lifts the binding. Credentials are issued by Pesapal to the account holder; the
dev lead stores them in the host environment file (mode 600) and never in a chat, an issue or the
repository.

### 3.12 Facts to confirm in the sandbox spike (#211)

| Fact | Current understanding | Confirm |
|---|---|---|
| Token lifetime | At most five minutes, `expiryDate` in the response | Yes |
| IPN verb and parameters | Order tracking id, merchant reference, notification type, by GET or POST as registered | Exact names and verb |
| IPN acknowledgement | A JSON body echoing the identifiers with status 200 | Exact body |
| IPN retries | Not documented | Count and interval |
| Status code and description casing | 0 to 3; description may vary in case | Exact strings |
| Amount representation for UGX | A number; whole shillings | Decimals accepted and returned |
| Fee, settlement, payout endpoints | None documented | Ask the provider in writing |
| Cancel of an unpaid order | Documented | Fields and behaviour |
| Callback host reachable from the sandbox | Needs a public HTTPS name | The staging tunnel must expose the callback host (chapter 9 section 9.13) |
| Rate limits | Not documented | Observed and asked |
| Reply to a second `SubmitOrderRequest` with a used merchant reference | Not documented; assumed to be an error without the tracking id | Exact reply; whether an order can be looked up by merchant reference |
| IPN registration scope | One IPN id per merchant credential pair | Whether one id can serve two pairs (the design assumes not) |
| Card chargeback and reversal window | Assumed up to 120 days | Ask the provider in writing |

### 3.13 Threat model (Pesapal)

| Threat | Control |
|---|---|
| Forged IPN claims a payment | The IPN carries no authority; only a successful authenticated status query books money |
| IPN replay or duplicate | One booking per provider reference: row lock, stored transaction id, unique index, idempotency key |
| Attacker guesses a merchant reference or tracking id | References are random 128 bit values; unknown ids make no outbound call |
| Callback endpoint used as an amplifier against the provider | Unknown ids ignored, per-intent throttle, edge rate limit |
| Attacker pays a small amount and the loan is treated as settled | The booked amount is the confirmed amount; allocation follows R-ALLOC; closure is automatic only at zero balance |
| Payer changes the amount or the loan on the hosted page | The order is created server side with a fixed amount and a subject bound to the intent |
| Cross-tenant booking | The tenant comes from the intent via the definer function; the booking transaction binds it; isolation tests |
| Stolen consumer secret | Held only in the host environment; rotated by the provider; the rotation runbook; alert on provider 401s |
| Staff create intents to hide cash | Money lands in the merchant account, not the till; intents are permission checked and audited; reconciliation flags oddities |
| Cashier also records the same wallet receipt by hand | The reconciliation duplicate check (3.8, 3.9) |
| Token or secret in logs or errors | Redaction filter test; the adapter reports only its own safe messages (as `OutboxChannel.SendFailure`) |
| Provider outage | Breaker, retries, poller; payments are delayed, never lost |
| Chargeback or reversal after booking | Alert plus a checker request made by a system user; never automatic; card payments watched for 120 days |
| A handler refuses a completed payment and it retries for ever | Savepoint, then an unallocated receipt and an alert; five technical failures end in `needs_attention` (3.4) |
| A tenant admin switches collections on and receives another lender's merchant account | The merchant is bound to one named tenant; every other tenant gets 409 (3.11) |
| A lost order-creation answer strands an intent or double-creates an order | One locked creator; the old reference is never reused; a new intent follows (3.5) |

## 4. SMS (ADR-036)

### 4.1 Summary

SMS becomes a real channel of the notification outbox. The provider is behind the `SmsProvider` port;
Africa's Talking is the first adapter. The outbox row learns its tenant, a tenant-scoped delivery log
records status and cost, and modes make a real send on staging impossible. OTP codes and USSD are out
of scope.

### 4.2 Changes to the outbox

`notification_outbox` is a platform table with no tenant (ADR-024). An additive migration adds
`tenant_id uuid` (null for platform rows) and `category` (`transactional`, `reminder`, `arrears`), and a
new overload of `notification_outbox_enqueue` that takes both. The tenant is read from the caller's
bound tenant (`current_setting('app.tenant_id')`), never a parameter. The claim and the record
functions stay platform steps; the SMS channel binds the stored tenant (`TenantJobs`) for the tenant
steps. The purge function and the operator's failed-rows view are updated; isolation tests cover them.

### 4.3 The provider port and Africa's Talking

```
SmsProvider
  send(to_e164, body, sender_id?, client_ref) -> {provider_message_id, segments?, cost_raw?, status}
  parse_delivery_report(http_request) -> [{provider_message_id, status, failure_reason?}]
```

Per the provider's public bulk SMS reference (to confirm in the sandbox in #212): a form POST to the
messaging endpoint with the account username, the recipient, the message and an optional sender id,
an API key header, and per recipient in the answer a status, a message id and a cost string such as
`UGX 32.0000`. The adapter parses the cost with `BigDecimal` into `cost_minor` (UGX exponent 0, round
half up) and keeps the raw string. There is no idempotency key. The sandbox uses a separate username
and base URL.

### 4.4 Modes

| `BMS_SMS_MODE` | Behaviour | Allowed where |
|---|---|---|
| `off` (default) | Rows stay `pending`, nothing is attempted | Anywhere |
| `sink` | The delivery row is written with status `sunk`; no network call | Local, test, staging; refused in production |
| `allowlist` | Real send only to numbers in `BMS_SMS_ALLOWLIST` (an environment value), others sunk | Staging only against the provider's sandbox host and account; in production for a pilot check |
| `live` | Real send | Production only; refused at startup on staging |

**Staging guard, in every mode.** When `BMS_ENVIRONMENT` is `staging`, startup refuses any
`BMS_SMS_AT_BASE_URL` other than the provider's sandbox host and any `BMS_SMS_AT_USERNAME` other than the
sandbox account, whatever the mode, because an allow-list on the live provider URL is still a real send.
The recording fake provider and `sink` are refused in production (chapter 12 section 12.7). Staging
defaults to `sink`. A startup test covers each refusal.

### 4.5 The delivery log

`sms_deliveries` (row-level security): outbox id, template key, category, subject type and id, recipient
as `+256*****1234` and a keyed hash (HMAC with a key derived from the application data key) for
opt-out matching, provider, provider message id, `segments` (ours, and the provider's if given),
`cost_minor`, `currency`, `cost_raw`, status, failure reason and timestamps. Statuses: `queued`, `sent`,
`delivered`, `undelivered`, `failed`, `rejected`, `blocked_optout`, `sunk`, `unknown`. The log feeds
`core.sms_usage` (FR-NTF-07) and the per message history of FR-NTF-05. Retention is 13 months unless
the retention decision (NFR-DP-06) says otherwise.

### 4.6 Sending, windows, retries, priority and limits

- The dispatcher claims and records as today. The SMS channel, outside any transaction, opens a short
  tenant transaction to load settings, the opt-out, the template override and the phone, renders,
  sends, and a second short tenant transaction writes the delivery row.
- **Outcomes.** `OutboxChannel.send` returns a result instead of only throwing: `Sent`,
  `Deferred(until)` and `Unknown`; a thrown `SendFailure` still means a clean failure. A new definer
  function `notification_outbox_record_result` records them. The outbox claim counts the attempt before
  the send and leases the row for 2 minutes, with `MAX_ATTEMPTS` 3 and backoff of 1 and 5 minutes
  (ADR-024); `Deferred` gives the attempt back and sets `next_attempt_at`; `Unknown` marks the row
  `failed` with `last_error = 'unknown_outcome'` and is never retried automatically.
- **Retries.** SMS keeps the outbox numbers: attempts at once, after 1 minute and after 5 minutes, then
  `failed`. The 30 minute step of FR-NTF-05 is dropped (the requirement is amended): a receipt that old is
  stale (below). The SMS client's total time per message is capped at 15 seconds, far inside the 2 minute
  lease, and a test asserts the cap stays under a quarter of the lease, so a slow provider cannot hand a
  second sender the same row.
- **Ambiguity.** A read timeout after the request was written cannot be retried safely (no idempotency
  key): it is `Unknown`, listed for the operator, and the delivery report usually settles it. A staff
  "send again" is a deliberate duplicate.
- **Windows.** `reminder` and `arrears` are sent only inside the tenant's window (default 08:00 to 20:00
  Africa/Kampala, FR-NTF-04). Enqueue sets `next_attempt_at` to the next window start and the channel
  checks again, returning `Deferred`. `transactional` rows (receipts, payment links) are not held.
- **Priority and fairness.** At 08:00 a thousand reminders become due together. The claim function orders
  transactional rows first, then by due time, and takes rows round-robin across tenants (rank within
  tenant), so receipts are not queued behind reminders and one tenant cannot starve another.
- **Staleness and backlog.** A transactional SMS expires one hour after it was queued and a reminder at
  the end of its window day. When the channel moves from a non-sending mode (`off`, `sink`) to a sending
  one, rows queued before that moment are marked `failed` with `backlog_dropped` and not sent, so
  switching SMS on never releases old savings receipts (which expire after two days today, ADR-032).
- **Cost limits.** A platform-wide daily ceiling (`BMS_SMS_PLATFORM_DAILY_CAP`) and a per tenant daily
  cap that **only the platform operator can change** (a platform console setting; the tenant admin sees
  it read only). An alert fires at 80 percent. Over the tenant cap, `reminder` and `arrears` rows are
  deferred to the next day's window and `transactional` rows continue up to twice the cap; beyond that,
  or over the platform ceiling, rows are held `pending` (deferred an hour at a time) with an operator
  alert until they expire. Nothing over a cap is sent, and nothing is dropped before it expires.
- **Throughput.** The sender today sends 50 rows per 30 seconds run in sequence. For a tenant with
  thousands of reminders that is slow; slice #212 raises the batch for the SMS channel and measures, and
  uses the provider's multi-recipient request only if a measurement says it is needed.

### 4.7 Delivery reports

`POST /api/v1/channels/sms/africastalking/delivery-reports/{token}` on the callback host, public. The
token is long and random (`BMS_SMS_CALLBACK_TOKEN`); the proxy allows only the provider's published
source addresses where they exist; a rate limit applies. The handler finds the delivery row by provider
message id with a definer function, moves status **forward only**, and ignores unknown ids. A forged
report can at worst change a log status. Nothing money-related depends on it. Whether the provider
signs reports is confirmed in the sandbox.

### 4.8 Sender id

Setting `sms_sender_name` (chapter 6; 3 to 11 alphanumeric characters) plus an operator-set `approved`
flag. Until approved the provider's shared sender is used. Registration is a provider and network
process; no date is promised.

### 4.9 Opt-out and recipients

Staff can opt a member out of `reminder` and `arrears` messages, or of all non-security SMS. Matching
is by member and by the keyed hash of the number, so a reused number does not block the wrong person.
Security notices (a withdrawal receipt) are sent unless the member is opted out of all SMS. An
opted-out message is logged `blocked_optout`. Messages go to the member's primary phone only, never next
of kin (scope open question 9). Inbound STOP handling needs a short code and is later.

### 4.10 Templates

Defaults live in code (today the two savings texts in `OutboxTemplates`); tenant overrides follow
FR-NTF-03 with placeholder validation at save (`unknown_placeholder`) and a preview with fabricated
data. A pure GSM 7 / UCS-2 segment counter, golden tested, checks every default fits one segment or
declares two. No reminder carries a balance; no message carries a loan purpose, a third party, or a
threat. Savings receipts carry the balance today (ADR-032); on a phone a household shares that tells
everyone what a member holds, so the default savings receipt omits the balance and a tenant setting
`sms_include_balance` (default off, tenant admin) puts it back (FR-NTF-17).

### 4.11 Events (FR-NTF-02)

Loan disbursed, repayment received (receipt), loan closed; savings deposit and withdrawal (already
queued, ADR-032); investment funded, maturing, paid out. Idempotency key per transaction id
(`loan.repayment_received:<txn id>`). A rolled-back repayment sends nothing, because the enqueue
shares the transaction (FR-NTF-01).

### 4.12 Reminders and arrears

Due in 3 days, due today, overdue at 1, 7 and 30 days past due, written by the nightly arrears job of
increment 6 (#109), idempotent on (schedule item, reminder type) (FR-NTF-06). The channel is ready
before the job; the reminders wait for it.

### 4.13 Payment link by SMS

Template `payment.link`: the hosted order URL for an open intent, sent from the collection screen to
the payer's number, category `transactional`, once per intent, only while the intent is open.

### 4.14 Threat model (SMS)

| Threat | Control |
|---|---|
| Staging texts real people | Default sink; `live` refused on staging; allow-list from the environment; provider sandbox account |
| Forged delivery report | Path token, source address allow-list, forward-only status, no money depends on it |
| Duplicate SMS after an ambiguous send | `unknown` status, no automatic retry, operator list |
| Spam or abuse of the SMS-triggering endpoints (payment links, receipts) | Permission checks, one link per intent, per tenant daily cap on SMS (setting) and an alert at 80 percent |
| Message content leaks personal data | Templates reviewed: no name, no balance in reminders; recipient masked everywhere; parameters cleared after sending |
| Number reused by another person | Opt-out matches on member plus hash, not on the number alone |
| API key theft | Environment only, rotation runbook, alert on provider 401 |
| Sender id spoofing or unapproved id | The approved flag; the provider rejects unregistered ids |
| Cross-tenant leakage through the platform outbox | The tenant id is set from the bound tenant, the delivery log has row-level security, isolation tests on both |
| Switching SMS on releases old queued messages | Rows queued before the move to a sending mode are dropped (`backlog_dropped`); receipts expire after an hour (4.6) |
| Staging reaches the live provider with an allow-list | Staging refuses a non-sandbox URL or username in every mode (4.4) |
| A tenant admin raises its own SMS cap | The cap is operator-only; a platform ceiling sits above every tenant (4.6) |
| Reminders at 08:00 delay receipts | Transactional rows are claimed first, round-robin across tenants (4.6) |
| Collection harassment of borrowers | Window, opt-out, no next-of-kin messages, wording rules; the tenant remains the data controller |

### 4.15 As built in slice #212 (differences from the design above)

The channel core is built (pull request for issue #212; ADR-036 carries the Amendment). Where the build differs from, or
sharpens, the text above:

- **Four outcomes.** `Sent`, `Deferred(until)`, `Unknown` and `Refused(reason)`; a refusal is a final no (opt-out, switched off,
  a number we will not text, a provider rejection) recorded `failed` with its reason and never retried.
- **Operator controls in a platform table.** The per tenant switch and the daily cap are rows of `platform_sms_controls`
  (not a tenant setting), written by `PUT /platform/tenants/{tenant_id}/sms`. SMS is off for a tenant until the operator
  switches it on, and while it is off no SMS row is written. The default cap shown for a tenant never set is 200; the
  platform ceiling defaults to 2000.
- **No message is sent twice by the machine.** The delivery row is written `queued` before the network call; an attempt that
  finds it `queued` or `unknown` answers `Unknown` and does not call the provider. The operator's "send again" is the only way
  to resend an unknown message.
- **An over-cap reminder outlives today.** Deferred to tomorrow's window, it also gets an expiry at the end of tomorrow's window
  day (bounded to 2 days after it was queued), so it can be sent then.
- **Ambiguity is wider than a timeout.** An HTTP 500, 502 or 504, a gateway error code in the answer and an SSL error while
  reading are unknown, not retried. The provider alert of 4.14 (key refused, no credit) is one Telegram alert a day.
- **Fairness has a memory.** One row is claimed per call, so a tenant's turn is its rank among its own due rows plus the rows it
  was given in the last minute.
- **The guard covers every non-production environment**, including an unset `BMS_ENVIRONMENT`, and production refuses the
  sandbox when the mode can send.
- **Not in this slice.** Delivery reports (4.7, #215, so no `delivered` or `undelivered` yet), the tenant SMS settings screen,
  template overrides and the staff opt-out screens (4.8 to 4.10, #218), reminders (#227), the payment link (#231). The port
  has `send` only; the opt-out table, the sending window and the caps are built and honoured.
- **Throughput.** SMS has its own sender job (200 rows and a 4 minute budget per run). Measured against the fake provider the
  dispatcher handled 200 messages in about 1.4 seconds; the provider's own latency is measured by the manual sandbox job.

### 4.16 As built in slice #218 (tenant SMS settings, opt-out screens and wording)

The tenant side of 4.8 to 4.10 is built (migration `V52`; ADR-036 second amendment carries the decisions, review answers 11.1
findings 4, 6 and 8 hold). Where the build differs from, or sharpens, the text above:

- **Two switches.** SMS needs the operator's switch (4.6, `PUT /platform/tenants/{tenant_id}/sms`) and the tenant's own, the
  settings group `sms` (`PUT /settings/sms`, the tenant administrator only), which is off for every tenant and is switched on
  after the administrator accepts a notice that every message costs money, that a message goes only to a member's own phone and
  that the platform caps the volume. The notice has a version; the user and time are stamped by the server. No SMS row is
  written while either switch is off, a row queued before the tenant switched off is refused, and switching on drops the
  tenant's pending rows (`backlog_dropped`) in the same transaction, so a switch-on never releases an old receipt. Tenants the
  operator had already switched on are off until their administrator does the same.
- **Sender id is an allow-list (4.8).** The `approved` flag is the table `platform_sms_sender_ids`: the operator approves an id
  for a tenant (`PUT /platform/tenants/{tenant_id}/sms/sender-ids/{sender_id}`), an id belongs to one tenant at a time, and the
  tenant administrator can only choose among its own. The shape is 3 to 11 ASCII letters and digits with at least one letter.
  The channel checks the approval again on every message and uses the shared sender once an id is withdrawn.
- **Window.** On the Africa/Kampala clock, at least 60 minutes, inside 06:00 to 21:00. These limits are hard; a tenant cannot set
  them aside.
- **Caps.** A tenant's daily cap is at most the operator's cap for it (and never above the platform ceiling); its monthly cap is
  at most thirty-one times that. 0 means stop and is enforced for every message including receipts; an absent cap is the
  ceiling. The lower of the stored cap and the ceiling applies on every message, so lowering the operator's cap bites at once.
  The month is the Africa/Kampala calendar month; the monthly count is read only when a monthly cap is set.
- **Opt-out (4.9).** Two kinds of row: about a member (from the member's page, `PUT /lending/members/{id}/sms-opt-out`; it holds
  no number and follows the member to any number) and about a number alone (the administrator adds one or pastes up to 2000;
  matched by the keyed hash, blocking the number whoever the message is about; a pasted list never weakens a block). Numbers are
  shown masked and never stored. Stopping more is any permitted user's; lifting a row or narrowing `all` to `reminders` is the tenant
  administrator's, needs a reason of 3 to 200 characters kept in the audit row, and the screens ask for a confirmation first. The
  list searches as you type (ADR-039) and pages by cursor.
- **Templates (4.10).** Overrides for the two savings receipts (the events the application sends today); each later event brings its
  placeholder list. The language is `{name}` placeholders and plain text only. Limits: 320 characters, at most 4 segments in the
  worst case (counted in UTF-16 units), no invisible or direction-changing characters, required placeholders, and no balance in any
  override. A value longer than its placeholder allows is refused (`value_too_long`), never cut. The preview
  shows fabricated data with the tenant's own business name, the encoding and segments, and a cost estimate that is the average
  cost per segment of the tenant's own sent messages in the last 30 days (none without history). The test message reaches only the
  sink or the environment's allow-list and never a number in a live mode; one that would really go out obeys the operator's switch,
  the tenant's switch, the opt-out list and the caps, and is logged as a delivery.
- **Settings moved.** `sms_sender_name`, `sms_window_start`, `sms_window_end` and `sms_include_balance` left `PATCH /settings` for
  the group; `V52` copies the window and balance values once (a window that fails the limits is not copied). A window stored
  outside 06:00 to 21:00 or under 60 minutes is replaced by the default on read. A row queued before the switch-on is refused by
  the channel as `backlog_dropped`, including one already claimed when the switch was turned on.
- **Not in this slice.** Per-event wording for the loan and investment events and the reminders (they join when their slices queue
  them), delivery reports and the per-message history screen (#215), the operator's screen for sender ids (the routes and the runbook
  exist), inbound STOP.

## 5. AI monitoring (ADR-037)

### 5.1 Summary

Two features over the insights metrics, for a tenant that switches them on: a **daily brief** in plain
language and **ask about my portfolio**. Advisory only: the model never changes a record, sends
nothing, and has no tools. The first provider is DeepSeek through its OpenAI-compatible chat API, using
its cheapest chat model; the model name, base URL and key are configuration. This is a first test, to
learn whether the value justifies the cost and the care.

### 5.2 Switch, acknowledgement, scope and the screen

- Setting `ai.enabled`, default **off**. A tenant admin (`core.settings.manage`) can switch it on only if
  the tenant is named in the platform's `BMS_AI_ENABLED_TENANTS` environment list, which the operator
  fills after the provider's written terms are on file (5.11); otherwise 403 `advisor_not_available`.
  Switching on shows what is sent (aggregates and aliases, never names, phones, IDs or addresses), where
  it goes (a provider outside Uganda), what is stored (the call log), and the limits; the admin ticks an
  acknowledgement stored with user, time and text version, audited.
- Permissions: `lending.advisor.use` (see the brief, ask), `lending.advisor.configure` (caps and call log;
  tenant admin). Both are new rows of the matrix (chapter 8).
- **Scope.** Insights is branch scoped, so a tenant-wide brief would show a branch manager figures
  outside their branches. The automatic daily brief is therefore one per tenant and day over all
  branches and is shown only to users whose branch scope covers every branch and who hold
  `lending.insights.all_officers`. Everyone else sees the deterministic brief for their own scope and can
  use the question box, whose facts are computed under the caller's scope and never cached or shared.
- The Insights page shows an **AI assistant (advisory)** card: the brief text, the data's as-of time,
  chips for the figures used (each opens the drill-down of that metric), and the line "Generated from
  your portfolio figures. Check before acting. It does not change any record." When the fallback is
  used the card says "Automatic summary".

### 5.3 Architecture

- `core.ai`: `LlmClient` port, `OpenAiCompatibleClient`, token accounting, per tenant and platform
  caps, `ai_calls` log, circuit breaker, recording stub (refused in production). Built in slice C1 (#220,
  migration V51); the choices it made are the amendment of ADR-037. In short: callers use `AiGateway`,
  which refuses before any network call (switch, allow-list, unconfigured provider, per call cap, the
  daily token and cost caps of the tenant and of the platform, an open breaker); the tenant's switch,
  caps and acknowledgement are the settings group `ai` read through `AiSettings`; and the first
  provider is DeepSeek through its OpenAI-compatible endpoint, the cheapest chat model, set by
  `BMS_AI_MODEL` alone. The runbook is `docs/runbooks/ai-assistant.md`.
- `lending.advisor`: prompt builder, alias mapper, `NumericValidator`, the daily job, the brief and
  question endpoints. Depends on `core.ai` and on the public `PortfolioFacts` port of
  `lending.insights` (the port takes the caller's scope); core depends on no vertical (ADR-002). `ModularityTest` covers it.
- Endpoints: `GET /lending/advisor/brief`, `POST /lending/advisor/questions`,
  `GET` and `PUT /lending/advisor/settings` (chapter 7 additions).

### 5.4 What leaves the platform

`PortfolioFacts` returns a typed list `{key, label, value, display, kind, polarity, delta}` from the
metrics dictionary (`docs/specs/lending-insights-metrics.md`): `brief.*`, `portfolio.*`, `revenue.*`,
`members.*`, with precomputed week-on-week and month-on-month deltas so the model never needs
arithmetic, computed under the scope of the user it is built for. `polarity` says whether a higher value
is good (collection rate) or bad (PAR).

- **Aliases for branches and products** (`Branch 1`, `Product B`), assigned per request in a stable order
  and mapped back by whole word, longest alias first, only in the text shown to the user.
- **No officer dimension in the first release.** An officer is a named staff member and an officer's
  portfolio is personal data; an `Officer C` alias would still identify a person to anyone who knows the
  branch. Officer figures stay on the insights page for people allowed to see them.
- **Small-count suppression.** Any group or figure computed over fewer than five loans or members is left
  out and replaced by the fact "fewer than 5", for branch and product breakdowns too: a branch with two
  loans describes two people.
- **Never in a prompt:** member names, phone numbers, ID numbers, addresses, loan numbers, staff names or
  emails, free text from notes. Loan-level rows are out of the first release; a later option adds loan
  tokens (a daily-changing keyed hash mapped back locally) behind a second switch and a new ADR.

This is data minimisation, not a claim that nothing identifying could ever remain; the acknowledgement
and the leak tests are the controls (5.11).

### 5.5 The question box

A question is at most 300 characters. Before any check the text is normalised: spaces, dots, dashes and
brackets between digits are removed, so `+256 700 000 001` and `0700-000-001` are seen as a run of
digits. It is refused, before any call, if it then contains:

- a run of six or more digits, an email address or an ID number pattern;
- a loan, receipt or account number of the tenant's own series (the series prefix followed by digits,
  however few);
- a word that is a member's name. Names are checked in the database, not in memory (a set of every
  member name in heap does not fit the staging host): the question's words of at least three letters,
  minus a short list of common English words, are matched against member first and last names with an
  indexed query. A word is refused when it matches a name and is not a common word, or when two
  adjacent words match a first and a last name. A common word that happens to be a surname does not
  refuse a question.

The logged and sent text is the scrubbed question. The screen and the acknowledgement state the
residual risk: a name no check recognises can still be typed. This is why the box ships after the
brief. A per user daily limit applies, and facts are computed under the caller's scope (5.2).

### 5.6 Prompts and the numeric validator

The system prompt fixes the role, the tone, English, a maximum length, the instruction to use only the
supplied figures exactly as displayed, never to compute, estimate or round, and to answer in JSON
`{summary, points: [{text, facts: [keys]}]}` with at most four cited facts per point. The user content is
the fact list (aliases), the as-of time and, for a question, the scrubbed question.

The validator (pure, property tested) accepts a response only if it parses, and every numeric token in
`summary` and in each point's `text` matches the display or plain form of a fact cited by that point.
Number words (zero to twenty, tens, hundred, thousand, million, half, third, quarter, double, triple) are
converted to digits and checked the same way. Excluded before the check: alias labels, the as-of date,
list ordinals.

**Direction words are checked against the sign of the delta.** A point that cites a delta fact may say
up, rose, grew, higher, increase and so on only when the delta is positive, and down, fell, dropped,
lower, decrease only when it is negative; flat, unchanged and steady only when it is zero. Words such as
better, improved, worse and worsened are checked against the sign and the metric's `polarity`: a falling
PAR is better, a falling collection rate is worse. A point with a contradicting direction, or with both
directions for one fact, fails. A model that writes "PAR rose" when the delta is negative is therefore
rejected even though every number is right.

A failure is retried once with a stricter reminder; a second failure uses the fallback.
It checks that numbers exist among the cited facts and that stated directions agree with the data, not
that the sentence is wise; words outside the lexicon are a residual risk the evaluation measures; the advisory label
and the evaluation (5.8) carry that risk.

### 5.7 Fallback, availability and caps

- Provider down, slow (30 second total budget), over a cap, switched off, or two validation failures:
  show the deterministic brief built by ADR-030 (`Sentences`), labelled "Automatic summary". The page
  never waits on the model beyond the budget and never fails because of it.
- Circuit breaker: 5 consecutive failures open it for 60 seconds.
- Caps: tokens per tenant per day (setting, default small), questions per user per day, one automatic
  brief per tenant per day (idempotent by tenant and date), a platform-wide daily ceiling, and a
  `max_tokens` on every request. A call over a cap is refused before any network call and the card says
  why.

### 5.8 Evaluation plan

Run on the fabricated insights demo data (`seed-lending --insights-demo`, several scales) before any
non-fabricated data is used, and again for every candidate model.

| Measure | How | Gate |
|---|---|---|
| Numeric fidelity after validation | Every number shown equals a golden metric (from the metrics tests) | 100 percent |
| Raw fidelity before validation | Share of responses that pass without a retry | Recorded, no gate; a falling rate flags the model |
| Citation correctness | Cited keys exist and cover the claim | At least 95 percent |
| Coverage | Does the brief mention the largest movements (new arrears, PAR30 change, collection rate gap) | At least 90 percent on the golden set |
| Missing data | A tenant with no loans or a missing metric: no invented figure, an honest sentence | 100 percent |
| Leak test | Fabricated names planted in members, branches, notes appear in no outgoing request and no log row | 100 percent |
| Injection test | Question text with instructions to reveal the prompt or ignore rules gives a refusal or a validated answer | 100 percent no leak |
| Direction | Points whose direction word contradicts the delta sign, over a set of fabricated up, down and flat series | 100 percent rejected |
| Small groups | Branches and products under five loans never appear as figures in a request | 100 percent |
| Scope | A branch-scoped user's question uses only that scope's facts; the stored brief is never shown to them | 100 percent |
| Cost and latency | Tokens, estimated cost and time per brief and per question | Recorded against the caps |
| Usefulness | The owner rates briefs 1 to 5 for two weeks on the fabricated staging book, then on an opted-in pilot tenant | Go/no-go note in #232 |

### 5.9 Cost

Provider prices change; they are read from operations configuration, never the repository. Estimated cost
is recorded per call in micro-units of the provider's currency from the token counts the provider
returns. One brief per tenant per day plus a capped number of questions bounds the spend; the platform
ceiling bounds the worst case. The first test measures cost per brief (5.8).

### 5.10 Logging

`ai_calls` (row-level security): tenant, purpose (`brief`, `question`, `evaluation`), asking user (staff;
never sent), model, the SHA-256 of the request, token counts, estimated cost, latency, outcome, the HTTP
status and a closed fallback reason. **It stores no request text, no response text and no provider error text** (settled
in slice C1, #220, migration V51; ADR-037 decision 9 and its amendment say the same), so the log cannot leak what it never held; a test pins the column list, so a new
column is a review decision. The slice that builds the prompts may add aliased text and the validation
outcome by its own expand migration and an amendment of ADR-037, if the evaluation needs them. Retention
90 days (a nightly job deletes older rows and closes a call left pending for an hour). A row is finished
once and its request columns never change (a database trigger). Reading it needs
`lending.advisor.configure`, from slice C3. Provider error bodies are never read into the log or an exception.

### 5.11 Data protection (Uganda Data Protection and Privacy Act, 2019)

This section lists considerations for the dev lead and the tenant; it is not legal advice and a legal
review is a go-live condition.

- **Minimisation and purpose:** only aggregates and aliases are sent, groups under five are suppressed and the officer dimension is excluded; the purpose is the tenant's
  management reporting, which the acknowledgement states.
- **Cross-border processing:** the provider is outside Uganda. The Act restricts processing personal
  data outside Uganda; because no personal data is sent by design (aggregates, aliases, small groups
  suppressed), the exposure is the tenant's confidential business figures, which the acknowledgement
  covers. A small group that identifies a person would be personal data, which is why suppression exists. NFR-DP-07's hosting review also
  covers this provider.
- **Security:** key in the environment, TLS, no personal data in logs, caps and a switch-off.
- **Retention and training:** before any real figure is sent, the dev lead obtains and records the
  provider's written terms on retention and use of API data for training. A provider that cannot
  state them is not used for real data.
- **Automated decisions:** none. The output is advisory; no loan, limit, collection action or
  member communication is decided or triggered by it.
- **Data subject rights:** the model holds no member data; nothing to export or delete there. The
  call log holds none either.
- **Registration and duties:** the platform operator's registration (NFR-DP-08) and each tenant's own
  duty are unchanged; the acknowledgement reminds the tenant.

### 5.12 Threat model (AI)

| Threat | Control |
|---|---|
| Personal data reaches the provider | Typed fact list, aliases for branches and products, no officer dimension, groups under five suppressed, no free rows; question scrub; canary test at the client boundary; log inspection test |
| A branch manager reads figures outside their branches | The stored brief is all-branches and shown only to all-branch users; questions are computed under the caller's scope (5.2) |
| A tenant turns the feature on before the provider terms are on file | The platform allow-list `BMS_AI_ENABLED_TENANTS` (5.2) |
| Model states a wrong or invented number, or the wrong direction | Cited-fact validator with number words and direction words checked against the delta sign; fallback; advisory label |
| Prompt injection through a question | No tools, no write path, plain text output, scrubbed and length-limited input, injection test in the evaluation |
| Injection through data (a branch named with instructions) | Names are aliases; only numbers and fixed labels are in the prompt |
| Cost runaway | Daily caps, `max_tokens`, one brief per day, platform ceiling, breaker |
| Provider outage or slowness blocks users | 30 second budget, fallback, breaker; the page never depends on it |
| Cross-tenant mix-up | Prompts are built under the tenant binding; `ai_calls` has row-level security; no shared cache of answers |
| Users treat advice as decisions | Label on every answer, no action buttons, wording rules in the prompt |
| Key theft | Environment only, rotation runbook, alert on 401, spend cap at the provider if available |
| Provider changes terms or model silently | Model name pinned in configuration; the evaluation suite reruns on any change; terms re-checked each quarter |

## 6. The reports pack (ADR-038)

### 6.1 Summary

Increment 8 (#111): seven reports, as PDF and Excel, as a bundle ("monthly pack") or one at a time,
produced by asynchronous runs. Definitions are chapter 14 and are not repeated here.

| Report | Key | Chapter 14 | Permission |
|---|---|---|---|
| Portfolio | `lending.portfolio_outstanding` | 14.3 | `lending.reports.portfolio` |
| Portfolio at risk and ageing | `lending.par` | 14.3 | `lending.reports.portfolio` |
| Collections (expected against collected, arrears list) | `lending.expected_vs_collected`, `lending.arrears_list` | 14.4 | `lending.reports.collections` |
| Disbursements | `lending.disbursements` | 14.3 | `lending.reports.portfolio` |
| Officer performance | `lending.officer_performance` | 14.4 | `lending.reports.collections` |
| Collateral register | `lending.collateral_register` | 14.5 | `lending.reports.members` |
| Regulator summary | `lending.regulatory_summary` | 14.7 | `lending.reports.compliance` |

### 6.2 Async runs

`POST /report-runs` `{report_key | pack, params, format}` returns a `report_runs` row (`queued`). The job
`core.reporting.report-runner` claims runs with `FOR UPDATE SKIP LOCKED` (one per tenant;
`BMS_REPORTS_MAX_CONCURRENT_RUNS` across the platform, 1 on staging and 2 elsewhere), executes in the two
phases of 6.3, stores the files and sets `ready` or `failed` with a safe error (`report_too_large`,
`snapshot_unavailable`, `timeout`). An identical request for the same user while one is queued or
running returns the same run. Timeout 10 minutes. The requester sees status on the report centre and
gets an in-app notice (and email if configured) when ready (FR-RPT-04). Files expire after 30 days (the
object is deleted, the `documents` row stays, a download answers 410 `report_expired`).

**As built in slice D1 (issue #214).** The run framework, its JSON and CSV renderers and `lending.par` are built; the
amendment at the end of ADR-038 records what building settled: the scope frozen at queuing and the two step
`InsightsFigures` port, the tenant setting `reports_enabled`, the `cancelled` status, the caps and the clock, and who may fetch a
file. The PDF and Excel renderers and the portfolio report arrived with slice D2 (issue #217, below); the other reports, the pack and
the report centre are slices D3 to D5.

### 6.3 Consistency, and memory: query first, then render

A run has two phases. **Query:** all queries execute in one read-only `REPEATABLE READ` transaction under
the tenant's row-level security and write each report's rows to a compact temporary spool file on disk;
the transaction then **commits**, releasing the snapshot. **Render:** the PDF and Excel builders read the
spool. The seven reports of a pack still agree with each other because every row came from one snapshot,
and a long render never pins old row versions. The run records `as_of` (business date) and `snapshot_at`.
Past dates read `lending_loan_daily_snapshots` (chapter 14 section 14.1); none yet means
`snapshot_unavailable`.

Staging is a Raspberry Pi whose API container has a 448 MB limit and about 224 MB of heap, so memory is a
design input, not a tuning afterthought: one run at a time on staging, spool on disk, each report rendered
to its own PDF file and the pack assembled by merging those files with PDFBox on temporary-file storage,
lower caps on staging (6.6, 6.7), and a memory test (`ReportMemoryIT`) that runs the pack on the
fabricated demo book under the staging heap setting and must pass before the first report slice merges.

### 6.4 The pack

One run, one snapshot: a combined PDF (cover with parameters and a sign-off block, contents, the seven
reports in the order above) and one workbook (a sheet per report plus Parameters). The requester needs
every report's permission; otherwise the pack is refused and the reports can still be run singly. Loan
officers see their own loans in the collections and officer reports.

### 6.5 Catalogue and permissions

`GET /reports` lists what the user may run (FR-RPT-01). No new permission is needed: the existing report
permissions of chapter 8 apply; running is audited (`core.reports.run`, with report key, parameters and
row count, never rows). Downloads use the signed URL path (FR-DOC-03).

### 6.6 Excel

A minimal streaming writer in `core.reporting` (Apache POI is not in the build; ADR-038 amendment for issue #217), one row in memory.
A `Parameters` sheet first, then one sheet per table of a report (a report with two tables, as PAR and ageing, has two sheets); money as
numeric cells in whole currency units with the format; rates as percentages to one decimal; real dates; frozen header; widths set.
Totals are values from the same code as the PDF, not spreadsheet formulas. A text value is always a string cell, and one beginning with
`=`, `+`, `-`, `@`, a tab or a carriage return also carries the quote prefix style. Cap 200,000 rows per sheet, 20,000 on staging
(`report_too_large`).

### 6.7 PDF

Reports use the shared templates of ADR-034 and the product owner's quality bar from pull
request 205: one header and footer (tenant logo and details, document number and date, brand-colour
rule, centred title), brand colour and tint, amounts in figures with currency and tabular alignment,
signature blocks where needed (the pack cover has prepared and approved), readable unique document
numbers with "page x of y" in the footer, embedded fonts, A4 margins, header row repeated on each page,
and a PNG visual check with a reviewed sample sheet before merge. Report specifics: landscape for more
than seven columns; a parameters box on every report (FR-RPT-02); "generated at, by, as at" line; tables over 3,000 rows (500 on staging) are cut with the printed note "full detail in the Excel file". Each report is
rendered to its own file and the pack is merged on disk (6.3).

### 6.8 Portfolio and PAR

Defined in chapter 14 section 14.3. **Through a public port.** The insights queries and `Scope` are internal to `lending.insights`; reports reach
them through the public read port `InsightsFigures` with a public `PortfolioScope` (range, branches, officer,
product), which the insights module resolves against the caller's permissions, so `ModularityTest` passes and
there is one implementation of PAR and collected. Cross check: the pack's PAR30, PAR90, principal outstanding, and
collection rate equal the insights metrics for the same range (`portfolio.par30`, `portfolio.par90`,
`portfolio.principal_outstanding`, `portfolio.collection_rate`).

**As built in slice D2 (issue #217).** The PDF and Excel renderers, the report template, the `InsightsFigures.portfolio` method and the
reports `lending.portfolio_outstanding` and `lending.par` as PDF, Excel, CSV and JSON are built (chapter 14 section 14.10 lists what each
file holds, and the ADR-038 amendment what building settled). No migration and no permission. Two differences from the text above: the
portfolio report has no fees and penalties outstanding and no grouping by purpose, because the insights positions and the nightly snapshot
do not hold them; and the workbook has a sheet for each table rather than for each report. `ReportMemoryIT` on the 224 MB heap
passes with the lending reports as PDF and Excel, a sheet of 20,000 rows and a PDF cut at 500 rows; the production cap of 3,000 PDF rows
finishes on that heap but fills the old generation, so it stays a setting for hosts with more memory. The visual check is
`make document-samples` and the reviewed sample sheet `docs/ui/report-samples/report-sample-sheet.png`.

### 6.9 Collections, disbursements, officer performance

Chapter 14 sections 14.3 and 14.4. The collections equation (total collected equals collected on due plus
arrears recovered plus prepayments plus penalties plus overpayments) is printed and asserted.

### 6.10 Collateral register and regulator summary

Chapter 14 sections 14.5 and 14.7. The regulator summary carries the cover note "Data summary. Not a
filed return." until the client's return formats are known (scope question 5).

### 6.11 Operations and threats

| Threat or risk | Control |
|---|---|
| A report exposes another tenant's data | Row-level security in the run transaction; isolation test on runs and files; object keys under `tenants/<id>/` |
| A loan officer sees other officers' loans | Scope enforced where the figures are computed (`InsightsFigures` resolves the caller's permissions); test per report |
| Formula injection through a name in Excel | Explicit string cells for values starting with `=`, `+`, `-`, `@`; test |
| A long run pins old row versions or exhausts the staging heap | Snapshot released after the query phase, spool on disk, one run at a time on staging, per-report PDF files merged on disk, lower staging caps, `ReportMemoryIT`, 10 minute cap |
| Files with member data linger | 30 day object deletion; signed 5 minute URLs; downloads audited |
| Numbers differ between PDF, Excel and screen | One implementation per figure (`InsightsFigures`), one totals code, cross check tests |
| Large book makes the PDF unreadable | 3,000 row cap with the Excel pointer |
| Report request flooding | Per user rate limit (chapter 7 section 7.10), dedupe of identical runs, platform concurrency of two |

## 7. Credit bureaus (deferred, a note only)

No build plan. What would be needed:

1. **A commercial and regulatory basis.** Membership of a licensed credit reference bureau under the
   regulator's rules, a data sharing agreement, and confirmation that the client's licence tier may or
   must submit and query (an open question for the client and the regulator).
2. **A data format and channel.** The bureau's submission file specification (borrower identity,
   facility, repayment status, arrears, write-offs), its submission schedule (usually monthly), and an
   enquiry interface (report or score) with per-enquiry fees and a dispute process.
3. **Consent.** A clause in the loan application and member registration consenting to submit and to
   enquire, recorded per member and per application with the version of the text; refusal handling.
4. **Data quality.** The identity fields the bureau needs (NIN, name, date of birth, address) are held
   in `lending.members`; gaps are listed by a data quality report before the first submission.
5. **Retention and correction.** Corrections after a dispute; deletion rules per the bureau's rules.

**Extension point (no code now):** a `CreditBureau` port in `lending.loans` with `submit(batch)` and
`enquire(member_ref) -> report | score`, an adapter per bureau, a consent flag on members and
applications, a nightly or monthly export job on the job runner, and the enquiry result offered to the
appraisal as one input of the credit score (pending ADR-012). Nothing calls a bureau from a request
thread or inside a money transaction (chapter 12 section 12.1).

## 8. Test strategy

| Layer | What |
|---|---|
| Provider stubs | `FakePaymentGateway`, `RecordingSmsProvider`, `RecordingLlmClient`: in-process, deterministic, record calls, can be told to time out, fail, or return odd shapes. Refused in production |
| Local HTTP stubs | For each real adapter, a JDK `HttpServer` that replays the recorded fabricated fixtures (Pesapal from #211, Africa's Talking, an OpenAI-compatible chat) so serialisation, headers, timeouts and error mapping are tested without a network |
| Contract tests | Per real adapter against the provider's sandbox, in a separate manually triggered CI job, never on every pull request, never against production (chapter 12 section 12.8). They cover token, order, status, IPN registration, one SMS to a sandbox number, one chat completion |
| Callback tests | Recorded fabricated bodies including: bad token, replay x3, unknown reference, throttled, amount mismatch, currency mismatch, late after expiry, reversed after booking; concurrent IPN plus poller proves one booking |
| Money correctness | After every payment test the trial balance balances and the loan or account balance equals the subledger (the `LoanServicingIT` pattern, chapter 15 section 15.6) |
| Golden tests | SMS segment counter and cost parser; merchant reference format (length 50, allowed characters); every SMS template within segment limits; report PDFs by extracted text and Excel by cell values on fabricated datasets with hand-computed output (FR-RPT-05); the AI evaluation set against the metrics dictionary |
| Property tests | `NumericValidator` (random numbers, formats, number words, aliases), the segment counter, the amount converter (no float, exponent handling) |
| Isolation tests | New tenant tables (`payment_intents`, `payment_callbacks`, `sms_deliveries`, `ai_calls`, `report_runs`, opt-outs, settings) and the platform outbox tenant column: tenant A cannot read or write tenant B's rows, including through the definer functions |
| Boundary tests | `core.ai` depends on no vertical; `lending.advisor` uses only public ports; core never imports lending (`ModularityTest`) |
| Canary and leak tests | Fabricated names planted in members, branches and notes never appear in an AI request, an `ai_calls` row, a log line, or an SMS log |
| Startup refusal tests | Staging refuses SMS `live`, any non-sandbox SMS URL or username in every mode, and the Pesapal live URL; collections refused for any tenant but `BMS_PESAPAL_TENANT_ID`; production refuses sink, fakes and the sandbox URL; unset variables turn adapters off without failing |
| Handler refusal tests | A completed payment whose handler refuses (`before_last_repayment`, `value_date_in_future`, `payment_method_unmapped`, closed loan) ends `booked` dated today or `booked_unallocated`, never retried for 48 hours; an injected database error ends `needs_attention` after five tries |
| Port tests | The gateway overloads of `LoanServicing` and `SavingsServicing` return the first transaction on a repeated external reference; the receipt notice is queued only when asked |
| Outbox tests | Priority and round-robin claim; `Deferred` returns the attempt; `Unknown` is never retried; an SMS client time under a quarter of the lease; `backlog_dropped` on enabling; caps |
| Memory test | `ReportMemoryIT`: the pack on the demo book under the staging heap setting |
| Visual checks | Report and document templates rendered to PNG by a documented script or CI step; a sample sheet reviewed before merge |
| Performance | Reports at 20 times the demo seed (NFR-PERF-01 to NFR-PERF-05); SMS dispatch throughput; AI brief latency and cost per brief |

## 9. Cost and operations

### 9.1 Secrets (names only; values live in `/opt/bms/.env`, mode 600, never committed)

| Area | Variables |
|---|---|
| Pesapal | `BMS_PESAPAL_BASE_URL`, `BMS_PESAPAL_CONSUMER_KEY`, `BMS_PESAPAL_CONSUMER_SECRET`, `BMS_PESAPAL_IPN_ID`, `BMS_PESAPAL_IPN_TOKEN`, `BMS_PESAPAL_TENANT_ID`, `BMS_PESAPAL_PLATFORM_CONSUMER_KEY`, `BMS_PESAPAL_PLATFORM_CONSUMER_SECRET`, `BMS_PESAPAL_PLATFORM_IPN_ID`, `BMS_PESAPAL_PLATFORM_IPN_TOKEN` |
| SMS | `BMS_SMS_PROVIDER`, `BMS_SMS_MODE`, `BMS_SMS_ALLOWLIST`, `BMS_SMS_AT_BASE_URL`, `BMS_SMS_AT_USERNAME`, `BMS_SMS_AT_API_KEY`, `BMS_SMS_CALLBACK_TOKEN`, `BMS_SMS_PLATFORM_DAILY_CAP` |
| AI | `BMS_AI_PROVIDER` (`off`, `openai_compatible`, or `recording` in dev and test only), `BMS_AI_BASE_URL`, `BMS_AI_API_KEY`, `BMS_AI_MODEL`, `BMS_AI_ENABLED_TENANTS`, `BMS_AI_PLATFORM_DAILY_TOKENS`, `BMS_AI_PLATFORM_DAILY_COST_MICRO`, `BMS_AI_PRICE_INPUT_MICRO_PER_MILLION`, `BMS_AI_PRICE_OUTPUT_MICRO_PER_MILLION`, `BMS_AI_PRICE_CURRENCY`; optional tuning (not secrets, defaults in `application.yml`): `BMS_AI_BUDGET_SECONDS` (30, never more), `BMS_AI_MAX_TOKENS_PER_CALL`, `BMS_AI_MAX_INPUT_TOKENS_PER_CALL`, `BMS_AI_TENANT_DAILY_TOKENS`, `BMS_AI_TENANT_DAILY_COST_MICRO`; a daily cap of 0 means stop, a negative one or a per call limit of 0 fails startup; `BMS_AI_ENABLED_TENANTS` holds tenant ids only |
| Reports | `BMS_REPORTS_MAX_CONCURRENT_RUNS`, `BMS_REPORTS_PDF_ROW_CAP`, `BMS_REPORTS_XLSX_ROW_CAP` (not secrets; staging values 1, 500, 20,000); slice D1 adds `BMS_REPORTS_MAX_FILE_BYTES` (64 MiB; 8 MiB on staging) and `BMS_REPORTS_SPOOL_DIR`, and `BMS_REPORTS_XLSX_ROW_CAP` caps the rows of every tabular output (sheet, CSV and JSON) |

`.env.example` lists each with a placeholder. Rotation: Pesapal secret and SMS key on staff change and
yearly, after any suspected exposure, and when the provider requires; AI key the same. Rotation runbooks
say how to roll with no downtime (the adapters read the value at start; a restart applies it).

### 9.2 Monitoring and alerts (to wire when each slice ships, chapter 9 section 9.11)

- Payments: notifications received per hour; unknown references; `status_mismatch`; intents `completed`
  but unbooked over 10 minutes; pending over 24 hours; provider 401 or 5xx rate; breaker open;
  reconciliation differences; reversals.
- SMS: failed and `unknown` counts; delivery rate per day; provider balance or credit if the API gives it;
  daily send count against the tenant cap (80 percent alert); window deferrals; sink mode in production
  (an alert, since it means no one is receiving messages).
- AI: calls, fallbacks and validation failures per day; tokens against caps; breaker open; cost per day;
  any `ai_calls` row failing the leak scan.
- Reports: run duration, failures, queue age, the oldest transaction age during a run, storage used.
- Jobs: `scheduled_tasks.consecutive_failures` for every new job (existing alert).

### 9.3 Cost model (qualitative; no prices in the repository)

- Pesapal: a fee per transaction taken by the provider and shown on the merchant statement, recorded when
  the statement is uploaded (3.9). Who bears it (tenant or payer) is a commercial decision outside this
  repository; the ledger can carry either.
- SMS: cost per message recorded from the provider's answer (`cost_minor`); segments counted by us; the
  tenant's monthly use in `core.sms_usage`. Passing cost on is commercial.
- AI: tokens per call from the provider; one brief a day per tenant plus capped questions; platform
  ceiling. The first test measures cost per brief before anything is promised.
- Reports: compute and storage only; bounded by caps and 30 day retention.
- Operations effort: a statement upload per cycle, a sender id registration, a quarterly provider terms
  check, the reconciliation review.

### 9.4 Runbooks to write (each with its slice)

`docs/runbooks/pesapal-setup.md` (credentials, IPN registration, sandbox to live, rotation),
`pesapal-reconciliation.md` (daily differences, statement upload, reversals), `sms-setup.md` (account,
sender id, modes, allow-list), `sms-incident.md` (provider outage, unknown statuses, stopping all sends),
`ai-assistant.md` (switching a tenant on, the acknowledgement, swapping the model, reading the call log,
stopping it), `reports-operations.md` (a stuck or slow run, storage, cap tuning).

## 10. Build plan

The umbrella is #210. Each slice is a task issue (label `type:task`, a native sub-issue of #210), ships
independently and off by default, takes its migration number at merge time, and carries its docs and
runbook. Order is the recommended build order; slices of different areas can run in parallel.

| Order | Issue | Slice | Depends on |
|---|---|---|---|
| 1 | #211 | A1 Pesapal sandbox spike, fixtures, confirmed contract | none |
| 2 | #212 | B1 SMS channel core: tenant on the outbox, provider, modes, delivery log | none |
| 3 | #213 | A2 Gateway core: port, adapter, intents, IPN endpoint, idempotent booking | A1 |
| 4 | #214 | D1 Report runs framework (**built**, migration V49; see ADR-038 amendment) | none (PDF later) |
| 5 | #215 | B2 Delivery reports and `core.sms_usage` | B1 |
| 6 | #216 | A3 Loan repayment by Pesapal, with the port extension (3.4.1), the savepoint and the minimal unallocated receipt posting | A2, increment 5 (#108), payment methods table (#160) |
| 7 | #217 | D2 The public `InsightsFigures` port, Excel writer, report PDF templates, portfolio and PAR, `ReportMemoryIT` (**built**, no migration; see ADR-038 amendment) | D1, the document templates of ADR-034 (#120, pull request 209) |
| 8 | #218 | B3 Tenant SMS settings, opt-out, template overrides | B1 |
| 9 | #219 | A4 Savings deposit by Pesapal | A3 |
| 10 | #220 | C1 `core.ai` port, adapter, caps, call log | none |
| 11 | #221 | B4 Loan event SMS | B1, B3, increment 5 (#108) |
| 12 | #222 | D3 Collections, disbursements, officer performance | D2 |
| 13 | #223 | C2 `PortfolioFacts` (scope aware, polarity and deltas), aliasing, small-count suppression, numeric and direction validator, evaluation harness | C1 |
| 14 | #224 | A5 Unallocated receipts queue and allocation screen, reversals, reconciliation, statement upload | A3 (which already posts unallocated receipts) |
| 15 | #225 | D4 Collateral register and regulator summary | D2 |
| 16 | #226 | C3 Daily AI brief | C2 |
| 17 | #227 | B5 Reminders and arrears notices | B1, B3, B4, arrears job (#109) |
| 18 | #228 | A6 Subscription payments | A2, self-onboarding step 2 (#90) |
| 19 | #229 | D5 Monthly pack and the report centre | D3, D4 |
| 20 | #230 | C4 Ask about my portfolio | C3 |
| 21 | #231 | B6 Payment link by SMS | B4, A3 |
| 22 | #232 | C5 Evaluation run and model comparison | C2, C3 |
| 23 | #233 | A7 Per-tenant merchant credentials | A3, A6 |

Pilot readiness for each area: mobile money after A5 (the money is reconcilable); SMS after B4 (receipts
and events) and B5 once the arrears job exists; AI brief after C3 and C5's go note; the pack after D5.

## 11. Adversarial self review

One pass, written as questions a hostile reviewer would ask, with what the design answers and what it
changed. Items marked (changed) altered the design during this review; the others were already answered by the design.

1. **"The IPN token in the URL will leak into logs and proves nothing."** Correct, and the design does
   not rely on it: it only filters noise. Safety comes from the status query. Proxy logs redact the path
   token (3.6).
2. **"An attacker posts IPNs with chosen tracking ids to make you call the provider."** Unknown ids make
   no outbound call, known ids are throttled per intent, and the edge rate limits the path (3.6).
3. **"The payer is a stranger; what stops him paying loan A's order and claiming loan B?"** The subject
   is bound to the intent server side; the hosted page has only the fixed order.
4. **"Cashier records the same wallet receipt by hand and the payer also paid through the link."**
   Double counting is possible. (changed) The reconciliation now flags a manual receipt whose reference
   equals a gateway confirmation code, and the loan screen marks gateway-paid items (3.8, 3.9).
5. **"No settlement API means you cannot reconcile."** The design re-queries every non-final and
   recently booked intent nightly and uses the uploaded statement for fees and settled amounts; the
   gap is stated, not hidden (3.1, 3.9). Asking the provider in writing is part of the spike.
6. **"One merchant account for several lenders puts the platform in the flow of funds."** (changed)
   Collections are bound to one named tenant (`BMS_PESAPAL_TENANT_ID`) until per-tenant credentials exist (3.11,
   #233); the platform pair is separate and only for subscriptions.
7. **"Reversals after booking are routine in mobile money."** Handled: alert plus a checker request,
   never automatic, so the maker-checker rule holds (3.8).
8. **"SMS rows have no tenant; per-tenant sender, window and cost are impossible."** (changed) The
   outbox gains `tenant_id` and `category`, set from the bound tenant, with a tenant delivery log and
   isolation tests (4.2, 4.5).
9. **"A timeout means you may text twice."** The provider has no idempotency key; the design records
   `unknown`, does not retry, and lists it (4.6).
10. **"Sending 50 rows per half minute will not clear a day of reminders."** (changed) Throughput is now a
    stated requirement of #212 with a measurement and a higher SMS batch (4.6).
11. **"Staging could text real people by mistake."** Default sink, `live` refused on staging, allow-list
    from the environment, provider sandbox, startup refusal tests (4.4).
12. **"A forged delivery report."** Path token, source allow-list, forward-only status; at worst a log
    status changes (4.7).
13. **"The AI will invent a number, or swap two real ones."** Cited-fact validation per point, number
    words converted, at most four cited facts per point so citing everything is impossible, retry, then
    fallback (5.6). Residual: a sentence can be unwise; the label and evaluation carry it.
14. **"Aliases leak: a model writes 'Branch 1' into 'Branch 10'."** Mapping back is by whole word, longest
    alias first (5.4).
15. **"Staff will type a borrower's name into the box."** Scrub plus the member-name token check, the
    residual risk stated, and the box ships second (5.5).
16. **"DeepSeek is hosted outside Uganda; is that lawful?"** No personal data is sent by design, the
    tenant acknowledges what is sent, the provider's written retention and training terms and a legal
    review are go-live conditions (5.11). The adapter is vendor neutral if the answer is no.
17. **"A report file holds member names and the `documents` table is write-once."** (changed) The object
    is deleted after 30 days, the row stays as metadata, downloads answer 410 (6.2, ADR-038).
18. **"A long REPEATABLE READ transaction hurts PostgreSQL."** Capped at 10 minutes, two concurrent runs,
    monitored; acceptable at the current size, revisited at scale (6.11).
19. **"PDF and Excel will drift."** One implementation per figure and one totals code; cross check tests against the
    insights page (6.3, 6.8).
20. **"The Excel can execute a formula from a member's name."** Explicit string cells for dangerous
    leading characters (6.6).
21. **"ADR numbers and pending references."** 033 and 034 are taken by pull requests 200 and 205; these
    are 035 to 038; ADR-034 has merged and is cited as an accepted record; pending ADR-011 and the SMS half of pending
    ADR-013 are settled by ADR-035 and ADR-036 and USSD stays pending ADR-013.
22. **"Provider facts are from documentation, not from the sandbox."** True for IPN format, token and
    acknowledgement; slice #211 must close section 3.12 before booking code is written.
23. **"What did you not do?"** No code, no migration, no OpenAPI change (the endpoints are in chapter 7
    as design), no real provider call, no prices, no legal opinion.

### 11.1 Independent review, round 1 (Opus 5.5, 21 findings) and what changed

The pull request was reviewed independently after the first push and failed with 21 findings. All are
addressed in the spec, ADR-035 to ADR-038, the SDD, the model and the issues; where each lives:

| # | Finding | Answer | Where |
|---|---|---|---|
| 1 | A completed payment the handler refuses is retried for 48 hours with no final state | Savepoint; `before_last_repayment` retried dated today; any other business refusal posts an unallocated receipt and alerts; technical failures end in `needs_attention` after five tries | 3.4, ADR-035 d.14 |
| 2 | The servicing ports take no key or reference, queue no receipt SMS, and say they serve only seed and import | Port overloads with external reference, origin and a receipt-notice flag; unique index; javadocs and an Amendment to ADR-026 and ADR-032 in slices #216 and #219 | 3.4.1, ADR-035 d.6 and d.15 |
| 3 | Any tenant admin switching on first gets the environment's merchant | `BMS_PESAPAL_TENANT_ID` binds the merchant to one tenant; 409 `merchant_not_bound_to_tenant` for all others | 3.11, ADR-035 d.11 |
| 4 | Staging could text real phones in allowlist mode on the live URL | Staging refuses any non-sandbox URL or username in every mode; startup test | 4.4, ADR-036 d.4 |
| 5 | The daily AI brief ignores branch scope | One all-branch brief for all-branch users only; questions and other users use the caller's scope | 5.2, ADR-037 d.7 |
| 6 | The SMS retry model does not match the outbox code | Result type and a new record function; attempts 0, 1 and 5 minutes; the 30 minute step dropped; SMS client time under a quarter of the lease | 4.6, ADR-036 d.6 |
| 7 | Receipts queue behind 08:00 reminders | Claim orders transactional rows first, round-robin across tenants | 4.6, ADR-036 d.5 |
| 8 | SMS cost limits | Platform ceiling; operator-only tenant cap; defined over-cap behaviour | 4.6, ADR-036 d.13 |
| 9 | Reversals: card window, maker actor, staleness, negative savings | 120 day card window; per-tenant system user as maker; stale requests re-raised; no negative balances, a manual journal | 3.8, 3.9, ADR-035 d.9 and d.16 |
| 10 | A timed-out order creation strands the intent | One locked creator; never reuse the reference; a new intent follows; spike items added | 3.5, 3.12, ADR-035 d.2 |
| 11 | One IPN id per credential pair | Every pair has its own IPN id and path token | 3.11, ADR-035 d.10 |
| 12 | Officer aliases are personal data; the "no personal data by design" claim | Officer dimension dropped; groups under five suppressed; the claim restated as minimisation | 5.4, 5.11, ADR-037 d.3 |
| 13 | The validator ignores direction words | Direction and polarity checked against the delta sign | 5.6, ADR-037 d.5 |
| 14 | AI go-live needs a platform control, not a tenant switch alone | `BMS_AI_ENABLED_TENANTS` allow-list | 5.2, ADR-037 d.7 |
| 15 | The report pack on the Pi (224 MB heap) | Query, commit, then render from a spool; one run at a time on staging; per-report PDFs merged on disk; lower caps; `ReportMemoryIT` | 6.3, ADR-038 d.2, d.5, d.7 |
| 16 | A3 depends on A5 | The unallocated posting moves into A3; A5 keeps the queue screen, allocation, reversals, reconciliation | 3.4, section 10 |
| 17 | Rebase: ADR-034 merged | Rebased; every "pending ADR-034" replaced, in the spec, ADR-038 and issues #214 and #217 | header, 6.7, item 21 |
| 18 | Question scrub: spaced numbers, short loan numbers, common-word names, heap | Normalisation before checks; series-prefix pattern; indexed database name match with a common-word list | 5.5, ADR-037 d.4 |
| 19 | Reports cannot reach `InsightsQueries` and `Scope` | Public read port `InsightsFigures` with a public `PortfolioScope` | 6.8, ADR-038 d.3 |
| 20 | #221 lists increment 5, the spec table does not | Table aligned with the issues | section 10 |
| 21 | Savings receipts carry the balance; enabling SMS sends 2 day old rows | Default receipt omits the balance (tenant setting to include it); receipts expire after an hour; backlog dropped on enabling | 4.6, 4.10, ADR-036 d.14 |
