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

Taken from the provider's public API 3.0 reference and checked against the sandbox in the spike (#211, run on
2026-10-13); section 3.12 records, fact by fact, what was observed and what is only documented. Where the sandbox
disagreed with the reference, the sandbox is written here.

| Capability | Supported | Use here |
|---|---|---|
| Access token (key and secret in, bearer token out; the reference says at most five minutes, the sandbox issued 3600 seconds, `expiryDate` is the authority) | Yes | Cached in memory per credential pair, refreshed a minute before `expiryDate` |
| Register an IPN URL (GET or POST type), list IPNs | Yes; registering the same url and type again returns the same id, but a second call within about five seconds is refused with http 409 | One registration per credential pair, done by an operator command with the command idempotent, id kept in settings |
| Submit an order: our unique reference (at most 50 characters), currency, amount, description (at most 100), callback URL, IPN id, billing address with a phone or email | Yes, returns an order tracking id and a **redirect URL** to a hosted payment page | Every collection |
| Transaction status by order tracking id: status code (0 invalid, 1 completed, 2 failed, 3 reversed), amount, currency, payment method, confirmation code, masked payer account, our reference. **An order that has not been paid answers code 0 `INVALID` with the error `payment_details_not_found` ("Pending Payment")**, so code 0 with that error means pending (3.12) | Yes | The only source of truth |
| Refund request, order cancellation | Cancellation observed on a pending order; refund observed only as a rejection, its success path needs a completed payment (documented) | Cancellation of abandoned orders; refunds are a manual back-office step for now |
| Recurring payments | Documented | Out of scope (subscription auto-debit is a later idea) |
| Server-to-wallet push prompt without a hosted page | Not in the public reference | Not used; the payer opens the link |
| Payouts, disbursement, bulk payments | **Not documented in the API reference; five guessed paths answered 404.** Pesapal sells a dashboard payout product (Openfloat); whether it has an API is a written question (3.12.2) | Out of scope; loan disbursement and savings withdrawals stay manual |
| Settlement report or fee per transaction through the API | **Not documented; guessed paths answered 404.** A restricted "additional endpoints" page exists (a query by phone number or email); its contents are behind a login | Operator uploads the merchant statement (3.9) |
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
6. The status check calls `GetTransactionStatus`. On status code 1 (`COMPLETED`) with matching reference and
   currency the booking transaction runs (3.4); on code 2 (`FAILED`) the intent ends `failed`; on code 0
   with the error `payment_details_not_found` ("Pending Payment") the order is simply unpaid and the intent
   stays `pending` (the sandbox answers this for every unpaid order, so code 0 is **not** a failure); code
   0 with any other error (`invalid_order_tracking_id`) is an anomaly that is counted and alerted and never
   books; on code 3 (`REVERSED`) after booking, 3.8 applies. The decision reads the status code, never the
   description, whose casing differs between the reference and the sandbox (3.12).
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

**One creator, one order per reference.** The creating job claims the intent with `FOR UPDATE SKIP LOCKED`
and sets `creation_lease_until`, so two workers cannot create the same order. The sandbox spike showed that
a second `SubmitOrderRequest` with a used reference is **not an error**: it answers http 200 with the
original order (same tracking id and redirect url) and ignores the new body, even a different amount. There
is no lookup by merchant reference (3.12), so this repeat is the recovery path. The rule is therefore:

1. If the call times out or its answer is lost, the creator repeats the call with the **same reference and
   the same body** (up to 3 attempts with backoff inside the lease).
2. It accepts an answer only if `merchant_reference` equals the intent's reference, and it never trusts that
   the body was applied: the first status query must report the intent's amount and currency, otherwise the
   intent ends `failed` with `order_mismatch`, the order is cancelled best effort and a new intent follows.
3. If every attempt fails, the intent ends `failed` with reason `creation_unknown` and the user starts a
   **new intent with a new reference**. This cannot lose money: the payer only receives a link from a
   successful answer.
4. A reference is never reused after `cancel_order` or after the intent was failed for any reason: the
   cancelled order stays attached to the reference and a repeat returns it (observed), so a fresh payment
   always takes a fresh intent and reference.

The duplicate-reference behaviour is observed in the sandbox only; the written question to Pesapal (3.12.2)
asks for the live statement. Steps 2 and 4 make the code correct whichever way the live system behaves.

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
   `Content-Type: application/json` and the documented acknowledgement body, a JSON object echoing the
   three received names in camelCase with a numeric status: `{"orderNotificationType":"IPNCHANGE",
   "orderTrackingId":"...","orderMerchantReference":"...","status":200}` (documented, not observed: the
   sandbox cannot call a public url from here; the first staging run confirms it). Status 500 is answered
   only when our own store failed, so the provider has a reason to try again; an unknown identifier is
   answered 200.
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
| Status code 2 (`FAILED`) | Intent `failed`, shown to staff, nothing booked; the payer may start a new intent |
| Status code 0 (`INVALID`) with `payment_details_not_found` | Not a failure: the order is unpaid, the intent stays `pending` under the 30 minute and 48 hour rules below. A cancelled order answers the same, so nothing is read after our own cancel |
| Status code 0 with `invalid_order_tracking_id` or a credential error | An anomaly: counted, alerted, never booked, never ends the intent by itself |
| Order creation timed out | The same reference and body are repeated up to 3 times; if all fail the intent is `failed` (`creation_unknown`) and a new intent with a new reference follows (3.5) |
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

### 3.12 The confirmed contract (sandbox spike, #211)

**How it was run.** On 2026-10-13 a throwaway script outside the repository called the sandbox
(`https://cybqa.pesapal.com/pesapalv3`) with the shared demo merchant credentials that Pesapal publishes in
its developer documentation for the sandbox (the Ugandan demo merchant; the Kenyan one only to prove that two
credential pairs are isolated). No live credential was used and no key, secret or token is in the repository,
a log or a fixture. About 280 calls were made: Auth, RegisterIPN, GetIpnList, SubmitOrderRequest (UGX),
GetTransactionStatus, RefundRequest and CancelOrder, plus error cases and burst tests. Recorded shapes are in
`backend/src/test/resources/pesapal/` (its README and `manifest.json` mark each file observed or documented).

**Legend.** *Observed* means the sandbox answered it that way. *Documented* means only the official
reference says so: a browser, a phone or a public callback host is needed to see it, and the spike could
not do those. Where the two disagree, the sandbox is written.

| Fact | Result | Basis |
|---|---|---|
| Token lifetime | The sandbox issued tokens that expire **3600 seconds** after issue (`expiryDate`, UTC, with `Z`); the reference says "a maximum of 5 minutes". Two requests give two different tokens. Refresh a minute before `expiryDate`; never assume a length | Observed; reference differs |
| Error envelope | Business errors (bad credentials, bad parameters, unknown ids, refused cancel or refund) are **http 200** with `"status":"500"` and an `error` object `{error_type, code, message}`. A missing bearer token is http 401, a garbage token http 500, an unknown route or missing query parameter http 404, a wrong verb http 405. The 401, 404 and 405 bodies are a wrapper `{"message": "<escaped JSON string>"}` or a plain message, not the `error` object. The adapter reads the body `status` and `error`, never only the http code, and parses the wrapper | Observed |
| Observed error codes | `invalid_consumer_key_or_secret_provided`, `invalid_api_request_parameters`, `INVALID_REFERENCE_02`, `invalid_amount_provided`, `invalid_payment_currency`, `InvalidIpnId`, `missing_mandatory_billing_address`, `invalid_ipn_notification_type`, `invalid_url`, `invalid_order_tracking_id`, `payment_details_not_found`, `general_system_decline_error` | Observed |
| IPN verb and parameters | The verb is the one chosen at registration (`ipn_notification_type` `GET` or `POST`; the registration reply carries `notification_type` 0 for GET and 1 for POST). Parameters: `OrderNotificationType` (`IPNCHANGE`, or `RECURRING` for recurring schedules), `OrderTrackingId`, `OrderMerchantReference`. A POST carries them as a JSON body, a GET as query parameters. The payer redirect to `callback_url` carries the same names with `OrderNotificationType=CALLBACKURL`. **No status, amount or signature** in either | Documented, not observed |
| IPN acknowledgement | A JSON body echoing the three values in camelCase with a numeric status: `{"orderNotificationType":"IPNCHANGE","orderTrackingId":"...","orderMerchantReference":"...","status":200}`; `status` 200 means received and processed, 500 received but not processed. The reference does not say what happens if the body is different or empty | Documented, not observed |
| IPN retries | Not documented: no count, no interval, no definition of "unacknowledged". The merchant dashboard (Account settings, IPN settings) has a RETRY button that re-sends pending IPNs; a payment made before the IPN was registered never triggers one. Forum guidance (older, unofficial) says the IPN host must share the subdomain of the page where the payment starts and that TLS-only hosts have been blocked | Documented in part; the rest is a written question |
| IPN registration | The same url and type again returns the **same `ipn_id`** (the operator command is therefore safe to repeat); the same url with the other type makes a second id. `http://` and `https://localhost` urls were **accepted**, only a malformed string is refused (`invalid_url`), so the guard against non public urls is ours. The reply spells the status field `ipn_status_decription` (sic). **RegisterIPN is throttled:** a second call within about 5 seconds got http 409 `general_system_decline_error` "Request Declined.Too many requests" (wrapper body); gaps of 7 seconds and more all passed. The operator command waits 10 seconds between registrations and treats 409 as retry | Observed |
| One IPN id for two credential pairs | **No.** Using an IPN id of one pair in the other pair's order gets `InvalidIpnId`, and the other pair's `GetIpnList` is empty. Each pair has its own id (the design assumption, ADR-035 decision 10, holds) | Observed |
| Status codes and casing | Code 0 `INVALID`, 1 `COMPLETED`, 2 `FAILED`, 3 `REVERSED` (reference). Seen: **an unpaid order, a cancelled order and (with a different error) an unknown id all answer code 0 and `payment_status_description` `INVALID`**. An unpaid or cancelled order also carries `error.code = payment_details_not_found`, `error.message = "Pending Payment"`, a populated `merchant_reference`, `amount`, `currency` and `call_back_url`, and body `status` `"500"`; an unknown or malformed id carries `invalid_order_tracking_id`, null fields and a tracking id of all zeros; another merchant's id carries `invalid_consumer_key_or_secret_provided`. The reference's own sample shows `Failed` in title case while its text says `FAILED`. There is **no separate pending code**: pending is code 0 with `payment_details_not_found`. Decide on the code, compare the description case-insensitively | Pending, unknown, cross-pair observed; 1, 2, 3 documented |
| Completed, failed and reversed bodies | Same field set as the pending body (plus `payment_method`, `confirmation_code`, `payment_account` masked, a null `error` object and body `status` `"200"` per the sample). Mobile money `payment_method` values are listed as a family (`MPESA`, `MTN`, `TIGO`); the exact Ugandan strings are unknown until a payment completes | Documented, not observed |
| Amounts in UGX | `amount` is a JSON number. Accepted: 1000, 1000.55, 0.5, 1, the string `"1000"`, 50000000 and 1000000000000; refused: 0 and negative. The status reply returns it as a number with a point (`1000.0`, `1000.55`), so **the provider does not enforce whole shillings**: the adapter sends an integral value, converts the returned number with `BigDecimal` and refuses a fraction (ADR-035 decision 3 holds and is now necessary, not optional). The hosted page shows `UGX 1,000.00` | Observed |
| Order creation rules | Allowed reference characters: letters, digits, `-`, `_`, `.`, `:` (`INVALID_REFERENCE_02` otherwise); 50 characters pass, 51 get the unhelpful `general_system_decline_error` "Unable to process your order at the moment". `BMS-ST-` plus 32 hex (39 characters) passes. A description of 101 characters, a lowercase currency code and a phone as `0700...` or `+256...` were accepted; an unsupported currency, a missing `callback_url`, an unknown `notification_id` and a missing `billing_address` object were refused; an **empty** `billing_address` object and an email only address were accepted. The reply carries `order_tracking_id`, `merchant_reference`, `redirect_url` (`.../pesapaliframe/PesapalIframe3/Index?OrderTrackingId=...`), `error` null, `status` "200" | Observed |
| Reply to a second `SubmitOrderRequest` with a used reference | **Not an error.** http 200 with the **original** order: same `order_tracking_id`, same `redirect_url`, the new body (even another amount) ignored. The same happens after the order was cancelled (the cancelled order comes back). Uniqueness across two merchant accounts was not measured. Consequence: 3.5 | Observed (sandbox) |
| Lookup of an order by merchant reference | **None.** `GetTransactionStatus` takes only `orderTrackingId`; the reference in that parameter answers `invalid_order_tracking_id`; four guessed routes answered 404. A restricted "additional endpoints" page mentions a query by phone number or email, behind a login. The duplicate submit above is the recovery path | Observed |
| Cancel of an unpaid order | `POST /api/Transactions/CancelOrder` with `{"order_tracking_id": "..."}`. A pending order: http 200, `{"status":"200","message":"Order successfully cancelled."}`. A second cancel: `status "500"`, "Invalid order state. Order cannot be cancelled."; unknown id: "Invalid order request."; GET is 405. A cancelled order is afterwards indistinguishable from an unpaid one in the status reply (see above). The reference allows cancel only for failed or pending payments, once | Observed; reference for the rules |
| Refund | `POST /api/Transactions/RefundRequest` with `confirmation_code`, `amount`, `username`, `remarks`, all required. For a code that is not a completed payment: http 200, `status "500"`, "Invalid transaction request."; missing fields: `invalid_api_request_parameters`. The reference rules: only COMPLETED payments, once per payment, never above the amount collected, cards partial or full, **mobile money full only**, same currency, the merchant must approve, then Pesapal's finance team processes; a 200 means received, not refunded; no time window is stated. Success could not be reached | Rejection observed; rules documented |
| Fee, settlement and payout endpoints | None in the reference; five guessed routes (settlement, transaction list, payout) answered 404. Pesapal markets a payout and bulk disbursement product (Openfloat) as a dashboard; whether it has an API is unknown. Written question, 3.12.2 | Observed absence, documented absence |
| Rate limits | None documented. Observed: 100 concurrent status queries, 10 concurrent token requests and 10 concurrent order submissions all answered http 200 with no throttle header; typical latency 0.8 s per call, GetIpnList 2 s. Only RegisterIPN was throttled. This says nothing about live limits | Observed (sandbox); live is a written question |
| Time zones | The status reply's `created_date` is East Africa Time with no zone marker (22:48 for an order made at 19:48Z); the registration and token dates are UTC with `Z`. The adapter parses status dates as `Africa/Kampala` and prefers its own clock for the value date | Observed |
| Callback host reachable from the sandbox | Any syntactically valid https name is accepted at registration, so the sandbox cannot prove reachability. The staging tunnel must expose the callback host (chapter 9 section 9.13) and the first staging run proves the IPN arrives. The spike registered fabricated `staging.example.test` urls and received nothing, as expected | Documented; proved in #213 |
| Card test numbers | The sandbox's own test page lists: frictionless Visa 4761 7390 0101 0010, Mastercard 5200 0000 0000 0114, Amex 3400 000000 03961 (expiry 07/28, CVV 123, Amex 1234); 3D Secure challenge Visa 4000 0000 0000 1091, Mastercard 5200 0000 0000 0007; declined Visa 4000 0000 0000 1018. An older Pesapal article lists Visa 4111 1111 1111 1111 (CVV 962) with a warning that it may fail to authorise. These are fake cards, not secrets | Documented, not observed |
| Mobile money test numbers | **None published.** The hosted page offers MTN and Airtel prompts that need a phone able to confirm; an old forum answer points to a demo mobile money test page. A completed mobile money payment is therefore not reproducible by the spike | Documented gap |
| Card chargeback and reversal window | **Not published by Pesapal.** Card network practice is commonly up to 120 days, which is the figure the design assumes. A bank-hosted Pesapal FAQ says withdrawal to the merchant's bank takes 48 business hours. Written question | Not confirmed |
| TLS chain | The sandbox host did not validate with Python's bundled CA list ("unable to get local issuer certificate") but did with the operating system's store, which suggests a missing intermediate certificate. Test with the staging JVM in #213 before relying on it, and import the intermediate into the truststore if needed | Observed from one client |
| The demo merchant is shared | The sandbox's `GetIpnList` returns other developers' registrations (about 3.7 KB when read). Never read that list back as "ours" and register only fabricated urls. The spike left about twenty fabricated registrations on it | Observed |

### 3.12.1 What this changes in the design

1. **Status mapping (3.2, 3.8, ADR-035 decision 5).** Code 0 is the normal state of an unpaid order. The earlier
   reading ("`INVALID` ends the intent failed") would have failed every payment at the first poll. Pending is code
   0 with `payment_details_not_found`; only code 2 fails an intent.
2. **Order creation (3.5, ADR-035 decision 2).** A repeat of the same reference returns the original order, so the
   creator may retry the same reference and body and must then verify the amount through the status query. The
   rule that a lost answer needs a new reference is now the last step, not the first.
3. **Acknowledgement (3.6).** The body is fixed (documented), to be proved on staging.
4. **Amounts (3.3, 3.7).** The adapter must reject fractional UGX itself; the provider will carry it.
5. **Errors.** The adapter treats http 200 with body status 500 as a failure and parses the 401, 404 and 405
   wrappers; the breaker counts these, and 409 on RegisterIPN is a retry, not a breaker failure.
6. **Operator command (3.11).** IPN registration is idempotent, spaced 10 seconds apart, refuses a non public
   or `http` url itself, and records the id.
7. **No change** to the reversal window (3.9: 7 days for mobile money, 120 days for cards) until Pesapal answers.

### 3.12.2 What only Pesapal can answer, and who asks

These are put in writing to Pesapal (the merchant account manager, or the developer support address given at
onboarding) by the **product owner as merchant account holder, with the dev lead drafting**, by **2026-10-16**.
The answers are needed before the first live payment (go-live of #216), not before the build of #213 starts,
so the build is not blocked. The draft is in the pull request description of #211; nothing was sent.

| # | Question | Why it matters | Used by |
|---|---|---|---|
| 1 | Is there a settlement report, a fee per transaction or a statement endpoint in the API, or only the dashboard export? In what format and with which columns? | Sets whether `fetch_settlement` can replace the statement upload (3.9) | #224 |
| 2 | What fee is charged per transaction for MTN MoMo, Airtel Money and cards in UGX, who bears it, and is it shown per transaction? | Fee posting (FR-PAY-06) | #224 |
| 3 | Is there an API for payouts or bulk disbursement (Openfloat or other) to mobile money and banks, and what is needed to use it? | Whether loan disbursement can ever be automated (ADR-035 decision 12) | later |
| 4 | What is the card chargeback and reversal window, and for mobile money? Is a reversal reported through the same IPN and status reply? | Reconciliation windows (3.9) | #224 |
| 5 | How often and for how long are unacknowledged IPNs retried, and what exactly counts as an acknowledgement? | Poller cadence | #213 |
| 6 | Does a repeated `SubmitOrderRequest` with a used merchant reference return the original order on the live host as it does on the sandbox, and is a reference unique per merchant account? | Order creation recovery (3.5) | #213 |
| 7 | What are the live rate limits per endpoint, and are there IP allow-lists for IPN calls? | Poller and breaker settings | #213 |
| 8 | Do the live token lifetime and the `payment_method` values for MTN and Airtel Uganda match the sandbox? | Token cache, method mapping | #213 |
| 9 | Is a sandbox mobile money test number or test page available for Uganda? | Automated end-to-end checks | #213 |

**Browser-only checks the dev lead does on the first staging run of #213** (documented, not observed here):
pay a sandbox order with a documented test card and watch the status become code 1; pay with the declined
card and watch code 2; confirm that the registered GET IPN arrives at the callback host with the three
parameters and that the JSON acknowledgement of 3.6 is accepted; replace the `documented` fixtures with
recordings. Mobile money cannot be tested without an answer to question 9.

| Item the issue asked to close | State | Owner and date |
|---|---|---|
| Duplicate reference reply | Closed: the original order is returned (sandbox) | Live behaviour: question 6, product owner, 2026-10-16 |
| IPN registration scope | Closed: one IPN id belongs to one credential pair | none |
| Card reversal window | Open: not published | Question 4, product owner, 2026-10-16 |
| Settlement, fee, payout | Open: not in the API | Questions 1 to 3, product owner, 2026-10-16 |
| IPN retries and live rate limits | Open | Questions 5 and 7, product owner, 2026-10-16 |
| Success paths of completed, failed, reversed, refund and IPN receipt | Documented, not observed | Dev lead, first staging run of #213 |

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

Built in slice #221; section 4.17 records how, and where it differs from this text.

Loan disbursed, repayment received (receipt), loan closed; savings deposit and withdrawal (already
queued, ADR-032); investment funded, maturing, paid out. Idempotency key per transaction id
(`loan.repayment_received:<txn id>`). A rolled-back repayment sends nothing, because the enqueue
shares the transaction (FR-NTF-01).

### 4.12 Reminders and arrears

Due in 3 days, due today, overdue at 1, 7 and 30 days past due, written by the nightly arrears job of
increment 6 (#109), idempotent on (schedule item, reminder type) (FR-NTF-06). The channel is ready
before the job; the reminders wait for it.

Built in slice #227: the arrears run queues them through `SmsEvents` after each loan's recompute, the due reminders per
unpaid instalment and the arrears notices at the highest DPD step reached on the oldest unpaid instalment, so a night the job
missed is caught up. The wording is chapter 11's without "to avoid penalties" (no threats); `loan.overdue_1` says "is
overdue" with no count.

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

### 4.17 As built in slice #221 (loan, savings and investment events)

The events of 4.11 are built (migration `V53`, two columns, one function and three function bodies replaced; no permission, no route, no screen; ADR-036 third amendment carries the
decisions; review answers 11.1 findings 4, 6, 7, 8 and 21 hold and are re-tested). Where the build differs from, or sharpens, the
text above:

- **One port, one guard.** `core.notifications.SmsEvents` is the only way a module queues a member event, savings receipts included.
  It reads the number with the strict rule of 4.9, builds the key, drops the balance unless the tenant asked, adds the business name
  and writes the outbox row. **Everything it does for a call, the lookups its suppliers make included (member phone, settings,
  amount formatting, the check that SMS is on), runs inside a savepoint of the caller's transaction.** Any failure (a Java error, a
  database error that aborted the PostgreSQL transaction, an exception that passed through a transactional proxy and marked the
  transaction rollback-only) rolls back to the savepoint, is logged by event name and error class only, and the money event
  commits; a rolled-back money event takes the row with it. `queueWhenActive` first asks whether SMS is on for the tenant (both
  switches, no cap of 0), so a tenant without SMS costs a money event two small reads and no member lookup.
- **The key.** `sms:<template>:<tenant>:<transaction id>:<member id>`: one row per event and member, ever. The transaction id is the
  disbursement's, the repayment's, the funding's, the payout's or the savings movement's. `loan.closed` uses the id of the repayment
  that settled the loan, so a loan reopened by a reversal and settled again is thanked again, once. `investment.maturing` uses the
  investment and its maturity date (a rollover has a new date), and the nightly job also stamps the reminder on the investment.
- **Who is texted.** Only staff events: the `LoanServicing`, `InvestmentServicing` and `SavingsServicing` ports of the fabricated
  seed and the import send nothing, and the seed replays its nightly runs with `runDaily(date, false)`, so even the maturity
  reminder of the replayed days is not queued (a test seeds a tenant with SMS on and asserts its outbox is empty). The register
  import uses the public routes as staff, so two more rules apply to every event: a request may say `notify_member: false`
  (loan disbursement and repayment, savings deposit, withdrawal and closure, investment funding, payouts and early withdrawal; kept
  in the approval payload where a checker decides; honoured on the server; **accepted only from a holder of `core.settings.manage`,
  the business administrator: anyone else gets 403 `notify_member_forbidden` before anything is recorded**, so a cashier cannot take
  money out of an account and leave the member uninformed), and an event whose value date is more than
  `BMS_SMS_EVENT_MAX_AGE_DAYS` (default 3) before the tenant's today is never texted. The runbook says to import before switching
  SMS on. Every audit entry of a money event records `notify_member` and `sms_within_cutoff`. The maturity reminder has no value
  date, so an investment remembers its investor may not be told (`lending_investments.notify_member`, inherited by a rollover) and
  the nightly job skips it; the migration backfills the flag for existing investments (false for any not funded by a staff request
  and for any funded more than 3 days ago), so switching SMS on reminds no investor of a register loaded before it. The Pesapal repayment of #216 will carry its own receipt-notice flag.
- **Reversals.** A reversal queues no correction (the issue asks for none). It does withdraw the message of the transaction it
  reverses while no sender holds it (`V53`, `notification_outbox_cancel_unclaimed`: every pending row whatever its attempt count,
  so a receipt waiting for its retry goes too, except a row under a live lease; the claim records the lease in
  `notification_outbox.leased_until`; marked failed `cancelled_reversed`, parameters cleared; hidden from the operator's "send
  again" list), because a receipt for an entry made on the wrong account would tell the wrong person. A row a sender holds may be at
  the provider and stays; the lease is on the database's clock, the channel looks once more immediately before the provider
  call and does not send a row cancelled meanwhile (the residual is in ADR-036). A reversal that
  settles a loan queues no thanks either.
- **What each message holds.** The business name, the loan or account number and amounts and dates (`dd/MM/yyyy`). The disbursement
  names **the amount paid out** (the principal less the fees deducted at disbursement) and the first instalment and its due date;
  the repayment receipt the amount, the value date and the receipt number, and the balance (principal, interest, fees and penalties
  still owed after it) only for a tenant with `include_balance` on, and then only in the default wording; a recovery on a written-off
  loan is a receipt without a balance; the thanks only the loan number; the maturing notice the account, the principal and the date;
  the payout the amount paid, the date and the voucher. No name of the member, no loan purpose, nobody else's number. Every default
  fits one GSM-7 segment with realistic values; with the balance two segments are allowed, as for savings; every default passes the
  placeholder language's own validation with a worst case of at most 4 segments, which a test checks.
- **The maturing notice is a reminder.** Category `reminder`: held for the tenant's window (default 08:00 to 20:00 Kampala) and
  expiring at the end of that window day, so its expiry is always after the deferral (queued at 20:59 or 21:01 UTC alike it is held
  to 05:00 UTC the next morning and expires at 17:00 UTC); sent once per investment and maturity date; queued only when the job runs
  for the current day. A receipt is transactional and is not held by the window; it expires an hour after it is queued.
- **Wording.** Each event has its own placeholder whitelist and required placeholders in `SmsTemplateEvents`, so a tenant can reword
  all eight events (`GET /notification-templates` lists them); no whitelist offers a balance.
- **Plain text on one line, whichever wording.** The channel now cleans every value before it renders, the default or the tenant's
  own: a line feed or tab becomes a space and control, invisible and direction-changing characters go (the business name is the only
  free text). Markup characters and quotes are left as they are: an SMS is plain text. Another script or an emoji moves the whole
  message to UCS-2 and the segments are counted and logged exactly.
- **Costs stay exact for later billing.** Every message a tenant causes has one `sms_deliveries` row with its segments, the
  provider's segments, the cost as integer minor units and the raw string; nothing here prices a message or bills anyone.
- **Tests that do not read the wall clock.** The loan and investment event tests run on a clock fixed at 20:59 UTC on 15 October
  2026 (23:59 in Kampala) and move it to 21:01 UTC where the Kampala date must change under them.
- **Not in this slice.** Reminders and arrears notices (#227), the payment link (#231), a correction message for a reversal (decided
  against), delivery reports (#215).

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

### 5.6 Prompts and the validator (superseded by the structured output in 5.13)

The first design had the model write the summary and the points and a pure validator check each number and direction word in
its prose. Four independent reviews each found wording that got through, so the decision of issue #223 (ADR-037, amended) is
that **the model writes no text**. The system prompt fixes the role and the output shape; the model answers with one JSON
object that chooses up to three fact keys per finding, a topic and a catalogue action code from closed sets, and a priority
(5.13). The user content is the fact list (aliases), the as-of time and, for a question (#230), the scrubbed question, which
is mapped to a topic and to the facts of that topic; there is no free answer. The validator accepts an answer only if it is
exactly that shape with exactly those values; our code then renders every sentence from reviewed templates, so a number, a
direction word and a polarity are written from the facts and cannot disagree with them. A refused answer is not retried into
prose: the person sees the deterministic brief (5.7). The older number validator (every number re-derived from the cited
facts) is kept as the second line over the rendering.

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
| Model states a wrong or invented number, or the wrong direction | The model writes no number, word or sentence: it chooses fact keys, a topic, a catalogue action and a priority from closed sets, anything else refuses the whole answer, and our code writes every figure, movement and polarity (5.13); second-line number re-derivation; fallback; advisory label |
| Prompt injection through a question | No tools, no write path, a structured answer that can carry no free text (a rendered brief only), scrubbed and length-limited input, injection test in the evaluation |
| Injection through data (a branch named with instructions or markup) | Names are aliases; only numbers and fixed labels are in the prompt; a real name written back into a sentence for the person who asked is neutralised (angle brackets, markdown characters, line breaks, ampersands removed) |
| Cost runaway | Daily caps, `max_tokens`, one brief per day, platform ceiling, breaker |
| Provider outage or slowness blocks users | 30 second budget, fallback, breaker; the page never depends on it |
| Cross-tenant mix-up | Prompts are built under the tenant binding; `ai_calls` has row-level security; no shared cache of answers |
| Users treat advice as decisions | Label on every answer, no action buttons, wording rules in the prompt |
| Key theft | Environment only, rotation runbook, alert on 401, spend cap at the provider if available |
| Provider changes terms or model silently | Model name pinned in configuration; the evaluation suite reruns on any change; terms re-checked each quarter |

### 5.13 As built in slice #223 (differences from, and sharpenings of, the design above)

The facts, the aliases, the validator and the offline evaluation harness are built (pull request for issue #223; ADR-037
carries the Amendment). Nothing here calls a model, adds a route, a permission, a setting or a table: there is **no migration**.
Where the build differs from, or sharpens, the text above:

- **Where the code is.** `lending.insights` publishes `PortfolioFacts` and `FactDisplay` (the one place where an amount, a rate
  or a change is turned into text). `PortfolioFactsService` calls the insights code the endpoints call (`brief`, `portfolio`,
  `revenue`, `members`) and the public `InsightsFigures.par` for the branch and product groups, so there is one implementation
  of every number. `lending.advisor` is a new module (it may use the kernel, `core.ai` and `lending.insights`) that holds
  `Aliases`, `AliasedFacts`, `PromptBuilder`, `Catalogue`, `StructuredValidator`, `Phrases`, `Templates`, `Fallback`,
  `NumberExtractor`, `NumericValidator` (the second line) and the public `AdvisorEvaluation`. The job, the stored brief, the endpoints and the screens are slices #226 and #230.
- **Scope is the endpoints' scope.** `PortfolioFacts.forCaller()` resolves the signed-in caller's scope with
  `InsightsFigures.scope` (permission `lending.insights.read`, the permitted branches, only the caller's own loans without
  `lending.insights.all_officers`), and `build(PortfolioScope)` reads only that stored scope and never the principal, so a job
  cannot widen it. A branch manager's facts are their branch's figures, equal to the endpoints' for the same caller (a test
  compares them metric by metric); the groups listed are only the branches and products in their scope.
- **Which facts.** Forty-one figures: the brief (only when the range ends on the business date), the portfolio, revenue and
  member-activity figures of the dictionary that a brief needs, and, per branch and product (the ten largest by loans), the
  loans, principal outstanding and PAR 30. Each has its key, label, kind, display form, polarity and, where defined, a
  week-on-week and a month-on-month change.
- **Changes.** A change is the same figure read again for the range a week earlier and a month earlier (both ends shifted),
  and the difference taken in the figure's own units (basis points for a rate, shown as points to two decimals, which is
  exact). A stock (outstanding, PAR, arrears, loans, borrowers) is read from the nightly snapshot of the earlier end date, a
  flow from the transactions of the earlier range. There is no change when the earlier figure cannot be known (no snapshot
  for that date), and none for figures that are live only (new arrears, going bad, the forecasts, dormant members). **A change
  is also left out whenever the distinct loans behind the two figures differ by one to four, or either is one to four** (for
  every figure with changes: write-offs, recoveries, collected, disbursed, penalties, arrears, the PAR levels, revenue and the
  rates): write-offs of 6 loans now and 5 a week earlier would give "+1 loan" and one loan's exact amount. The figure itself
  stays when it is over five loans or more. `PortfolioFactsSuppressionTest` sweeps every pair of loan counts from 0 to 30 for
  every figure with changes.
- **Small counts.** A figure is hidden when the loans it is **actually computed over** number one to four, not when the
  book is large: arrears and PAR 1 over the loans in arrears, PAR 30, 60 and 90 over the loans more than 30, 60 and 90 days
  late, collected over the distinct loans that paid, recovered over the loans recovered, each revenue line over the distinct
  loans behind its ledger lines, write-offs over the loans written off, the new-arrears amount over the new arrears, the rest
  over the active loans. Hiding one figure hides **every figure that would give it back by subtraction** (complementary
  suppression): the write-off amount, the write-off expense and the profit contribution go together, the recovered figures and
  the contribution, any revenue line and the contribution, collected and the collection rate, interest and fees and the
  yield. A change is hidden with its figure, and when the earlier figure was hidden, because current minus change would give
  it back. Zero is not small: nothing is described. A branch or product of fewer than five loans is the single fact
  `branch.loans` or `product.loans` with the display `fewer than 5`, and no other fact; a group's PAR 30 is hidden when fewer
  than five of its loans are more than 30 days late. **Nested windows and the PAR levels** (round 4): today sits inside the month, so the month less today is the other days'
  figure; when the month's population exceeds today's by one to four (6 and 5) the larger window's figure is hidden, for
  disbursed and their count (loans), collected (transactions), expected (schedule items) and the collection rate that follows;
  the earlier windows of a change are checked the same way. The PAR levels are a partition of the late loans (1 to 30, 31 to 60,
  61 to 90, over 90 days): the difference of two shown levels times the book is the principal of the loans between them, so a
  level is hidden when it is small itself or when a shown earlier level differs from it by one to four loans, and the ratios
  derived from them go with it. **Complementary suppression** (owner's rule: never expose small counts): the groups partition the book, so the total less
  the groups shown is the hidden and left-out remainder; when that is one to four loans it is a small group's exact count,
  principal and risk by subtraction, so the smallest other groups are hidden too until the remainder is five loans or more (or
  nothing); the same is done for the loans more than 30 days late behind the PAR 30 of the groups shown. Groups beyond the
  ten largest count as remainder. `PortfolioFactsIT` sweeps every product and branch scope of the fabricated year and asserts
  the remainder is never one to four.
- **The business date.** A scope resolved for today and read after midnight does not read the live loan tables, which by then
  hold the next day's activity: it reads the snapshot of the day it was resolved for, and is refused with 422
  `snapshot_unavailable` while tonight's snapshot is not written. A past date with no snapshot is refused the same way, and the
  brief figures (live only) are left out of facts that are not for today. `PortfolioFactsIT` writes the snapshot of the 8th,
  moves one row of it and shows the facts follow the snapshot, not the live tables.
- **Cost.** A set of facts is twelve reads of the insights code (brief, portfolio, revenue and members, for the range and for
  the two earlier ranges) and two of the PAR port; it is built once per brief and once per question, so the daily brief and the
  per user question limit bound it.
- **Names stay out.** A group is an id in the facts and its name travels apart in `FactSet.names()`, which prints nothing.
  `Aliases` assigns `Branch 1`, `Product A` (then `AA`) in the order of the ids, so an alias tells nothing about a name or a size.
  Group keys become `branch_1.par30`. A group the fact set does not name is left out, never sent under its id. The prompt is
  built from `AliasedFacts` alone. The real names are not matched in an answer: they were never in the prompt, so a branch
  called `Main` must not block every answer that uses the word; an answer can only name a group by its alias, which is
  mapped back for the person who asked. (The evaluation plants fabricated names as forbidden strings; production plants none.)
- **The model writes no text: it returns a structured choice (the primary control, decided after the fourth review of #223).**
  Free prose cannot be checked reliably by patterns: four independent reviews each found wording that got through, so free
  prose is removed from the AI path. The model returns one JSON object and nothing else:
  `{"findings":[{"fact_keys":[...],"topic":"...","action":"...","priority":"high|medium|low"}],"watch":["..."]}`. `fact_keys`
  holds one to three keys from the facts it was given (`portfolio.par30`, `portfolio.par30.month_change`, `branch_1.loans`);
  `topic` is one of the nine topics of the catalogue; `action` is a code from the reviewed catalogue (`advisor/catalogue.json`)
  that belongs to that topic; `watch` holds at most six more keys. At most six findings. The validator
  (`StructuredValidator`) refuses the WHOLE answer, with a closed code, when: the raw text is not exactly a JSON object
  (`not_json`, also for a fence, text before or after, a duplicate key); a field is missing or of the wrong type (`schema`) or
  there is any extra field at the root or in a finding (`unknown_field`); a limit is passed (`too_many`, `too_long`); a topic,
  action or priority is not exactly an enum value (`unknown_topic`, `unknown_action`, `bad_priority`; case, padding and
  look-alike characters do not match); the action belongs to another topic (`action_topic_mismatch`); a key is not among the
  facts given (`unknown_fact`: a real name, an invented figure, a group out of scope), is hidden (`fewer than 5`, or a group
  left out by complementary suppression: `hidden_fact`), is of a family the topic may not cite (`fact_topic_mismatch`), or is
  repeated (`duplicate_fact`); or the rendering fails the second line (`render_check_failed`). A defect in the validator itself
  refuses (`validator_error`). A refused answer is never repaired and never partly shown: the person sees the deterministic
  facts-only brief instead (`Fallback`: a fixed list of key figures and their changes, written by the same phrases). An answer
  with no finding and nothing to watch shows the same key figures, not an empty statement. A provider failure shows it too.
- **Our code writes every sentence.** The summary line, each finding, the watch line, the facts-only brief and the "nothing was
  flagged" line are templates in the catalogue resource, filled by `Templates.fill` in one pass, so a value cannot contain a
  placeholder that is filled again. A fact is written by the phrase table (`Phrases`) as `PAR 30 (12.5 percent)`, a change as
  `PAR 30 fell by 0.40 points compared with a week ago (better)` (the verb from the sign, the period from the fact, better or
  worse from the metric's polarity), a missing figure as `(not available)`, a group's fact as `Branch 1: Active loans (120)`; the
  name travels with the value, so figures cannot be swapped between metrics, groups or periods. The finding reads
  `<Priority> priority. <facts>. Suggested action: <action>`, the action being the catalogue text with `{{where}}` filled by
  our code with the groups the finding cites (` for Branch 1 and Product A`, mapped to names only for the person who asked).
- **The catalogue is reviewed text.** It holds nine topics and nineteen actions with stable codes (arrears follow-up, collections
  focus, disbursement pace, collateral cover, concentration, write-off review, cash flow from expected repayments, data quality,
  portfolio risk and profitability). No text mentions an officer (there is no officer dimension) or savings and investments (no
  fact covers them yet; a topic for them arrives with the facts). Topics and actions keep the order of the file, and the loader
  refuses a duplicate topic code, action code or JSON key. Each action belongs to one topic, and each topic lists the fact families it may cite
  (`portfolio.par30`, `branch.loans`, and so on: a group fact is bound by its family, whatever its alias or period). The loader
  refuses a catalogue with fewer than 12 or more than 20 actions, an action of an unknown topic, a topic without an action, any
  digit of any script, markup or `@` in a text, and any placeholder in an action other than `{{where}}` (a figure never
  appears in catalogue text; it comes from the facts). An action that says no action is needed (`calm`, marked in the file
  and required of any text that says so) is allowed only at low priority and when no cited change is worse (`calm_action_mismatch`),
  so a worsening is never described as calm. No action text refers to the cited facts as `this figure` or `these figures`, and a
  fact phrase uses no verb that agrees with the name (`did not change`), so one fact or three read alike. `CatalogueTest` and a property test over every action with every fact of
  its topic assert that the output is only template text plus values written by our code.
- **The prompt is a reviewed resource** (`advisor/system-prompt.txt`) filled with the topics and actions of the catalogue and the
  canary phrase; `PromptContractTest` fails if it stops listing the output shape, a topic or an action code, or the rules the
  validator enforces (limits, "an action belongs to one topic", "use no key whose value is fewer than 5"). The facts reach the
  model as `key | name | value | note`, between `FACTS` and `END FACTS`, with aliases and never a real name; it is told that
  this is data and not instructions.
- **The second line.** The older `NumericValidator` re-derives every number in the rendered text from the facts the finding
  cites (a number must equal the display or plain form of a cited fact, units and signs must agree, a hidden fact accounts
  for no number) and runs the personal data shape screen over it, so a defect in a template or in the substitution cannot put a
  wrong figure on a screen. `Phrases.corrupt` is a test switch that makes the phrases write a wrong figure, and
  `ValidatorMutationTest` shows the second line refusing it.
- **Every rule has a switch.** `StructuredValidator.Rules` holds one flag per rule (strict fields, topic enum, action enum,
  priority enum, action-belongs-to-topic, fact exists, fact not hidden, fact fits topic, no duplicates, limits, second line).
  `ValidatorMutationTest` turns each off in turn with the second line off as well, and requires the golden evaluation to FAIL
  with the very cases that rule exists for wrongly accepted.
- **What a change is compared with.** Our phrase table says it per kind of figure: a brief figure with `the same day last
  week` or `last month`, a stock (outstanding, loans, borrowers, arrears, PAR) with `a week ago` or `a month ago`, a flow over
  the range (disbursed, collected, expected, revenue, the rates) with `the same period a week earlier` or `a month earlier`;
  never `over the week` for a month-to-date figure. Penalty income has no polarity: rising is neither better nor worse.
- **Not figures.** The dates given (the as-of date, the range, and both earlier ranges) in any written form, list markers and
  ordinals, `PAR 1` to `PAR 90`, the names of facts that carry a digit, aliases, and a number glued to letters (`Q3`) are not
  figures for the second line.
- **The harness.** `AdvisorEvaluation.run(cases, source)` takes cases (a fabricated fact set, a recorded answer, the verdict
  expected and the code a refusal must give) and a source: `recorded()` answers each from the recording client, `with(client)`
  sends the same cases to a real client. Before the call it checks the request that would leave the platform (no real name, no
  planted string, no phone, email, ID or UUID shape in the facts, no run of six digits, no figure for a group that has a
  suppressed fact) and never sends one that fails. The result of a case holds the codes, what is shown if accepted (the
  rendered brief, names mapped back) and the facts-only brief shown if refused. The golden set (`GoldenCases`) has clean
  structured answers whose rendering is asserted word for word, the shape and limit refusals, the enum and binding refusals, and
  for every attack of rounds 1 to 4 (`AttackCorpus`, about 110 of them: invented, derived and mis-formatted numbers, wrong
  directions, injection through a fake field and through the model, personal data, hallucinated names, free prose) the same
  attack expressed in each field the model controls (topic, action, priority, fact key, watch, an extra field, a legacy prose
  answer), each refused with its code. It must pass 100 percent in every category, and every refused case has a repaired twin
  (the same answer mended in the one place the rule looks at) that the harness accepts, so a refusal is the rule's and not
  another check's. The report is plain text with codes and counts, never a sentence, and costs nothing. The canary test
  (`AdvisorCanaryIT`) runs the real gateway with the recording client over a seeded book whose branches and products are named
  with canaries and asserts that none is in the request the client got, in the call log or in the facts, that the gateway's own
  input screen let the prompt through, that a valid structured answer comes back rendered with the real names for the person who
  asked, and that prose, an extra field and an unknown fact key are refused.
- **Later slices use this path (#226, #230).** The daily brief (#226) asks for the same findings and watch list for one day, and
  stores the rendered brief. A free-text question (#230) is scrubbed on input, then mapped to a topic of the enum and to the facts
  of that topic; the answer is the same renderer's, never a free answer. A question outside the topics is answered by the
  facts-only brief and a pointer to the screens.
- **Fixed on the way.** `members.median_decision_hours` made the members endpoint answer 500 for a range with no decisions
  (the median of nothing is NULL); it is now an empty figure, as the dictionary says.
- **Not in this slice.** Retry with a stricter reminder (#226; the facts-only fallback exists here), the question scrub and the box (#230), the
  endpoints, the daily job and the stored brief (#226), the screens, and the run against a real provider and the comparison of
  models (#232).

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
| 13 | #223 | C2 `PortfolioFacts` (scope aware, polarity and deltas), aliasing, small-count suppression, numeric and direction validator, evaluation harness (**built**, no migration; see 5.13) | C1 |
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
13. **"The AI will invent a number, or swap two real ones."** The model writes no number and no sentence: it chooses fact
    keys, a topic, a catalogue action and a priority from closed sets, everything else refuses the whole answer, and our code
    writes the figure with its name, period and polarity (5.13). Residual: the model may choose an unwise action from the
    catalogue; the label and the evaluation carry it.
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
22. **"Provider facts are from documentation, not from the sandbox."** (closed by #211) The sandbox spike ran on
    2026-10-13 and overturned two assumptions (an unpaid order reads `INVALID`; a repeated reference returns
    the original order); section 3.12 marks every fact observed or documented, and 3.12.2 names who asks
    Pesapal what the sandbox cannot answer.
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
| 13 | The validator ignores direction words | The model writes no text; direction and polarity are written by our code from the delta sign | 5.6, 5.13, ADR-037 d.5 |
| 14 | AI go-live needs a platform control, not a tenant switch alone | `BMS_AI_ENABLED_TENANTS` allow-list | 5.2, ADR-037 d.7 |
| 15 | The report pack on the Pi (224 MB heap) | Query, commit, then render from a spool; one run at a time on staging; per-report PDFs merged on disk; lower caps; `ReportMemoryIT` | 6.3, ADR-038 d.2, d.5, d.7 |
| 16 | A3 depends on A5 | The unallocated posting moves into A3; A5 keeps the queue screen, allocation, reversals, reconciliation | 3.4, section 10 |
| 17 | Rebase: ADR-034 merged | Rebased; every "pending ADR-034" replaced, in the spec, ADR-038 and issues #214 and #217 | header, 6.7, item 21 |
| 18 | Question scrub: spaced numbers, short loan numbers, common-word names, heap | Normalisation before checks; series-prefix pattern; indexed database name match with a common-word list | 5.5, ADR-037 d.4 |
| 19 | Reports cannot reach `InsightsQueries` and `Scope` | Public read port `InsightsFigures` with a public `PortfolioScope` | 6.8, ADR-038 d.3 |
| 20 | #221 lists increment 5, the spec table does not | Table aligned with the issues | section 10 |
| 21 | Savings receipts carry the balance; enabling SMS sends 2 day old rows | Default receipt omits the balance (tenant setting to include it); receipts expire after an hour; backlog dropped on enabling | 4.6, 4.10, ADR-036 d.14 |
