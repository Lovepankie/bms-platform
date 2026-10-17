# ADR-035: Mobile money and card collections through Pesapal v3: hosted orders, IPN as a hint, status re-query as the truth, idempotent booking through the purpose handlers

## Status

Proposed (issue #210, design only; spec `docs/specs/client-extras.md` section 3). Settles the gateway
question that chapter 12 section 12.4.4 and the scope document carried as pending ADR-011; that
number is not used, because a number below a published one is never written later. Builds on ADR-004
(integer money, the ledger), ADR-008 (jobs), ADR-016 (definer functions), ADR-024 (manual
subscription payments with a gateway-ready record), ADR-026 (servicing ports, default payment method
accounts). Implements FR-PAY-01 to FR-PAY-06 and FR-MSS-04. Migrations are numbered when each
build slice merges, as Flyway runs with `outOfOrder` off.

## Context

A lending client wants members to pay loan repayments and savings deposits by mobile money (MTN MoMo,
Airtel Money) and cards, in UGX, and wants the platform's own subscription payments to be collectable
the same way. The product owner chose the Pesapal v3 API. Chapter 12 already designs a gateway port,
payment intents, callbacks and a booking flow, written before a provider was chosen. Pesapal v3 is
different in four ways that change that design:

1. A collection is a **hosted order**: the API returns an order tracking id and a redirect URL where
   the payer picks a method and enters a phone number or card. There is no server-to-wallet prompt
   call in the public v3 reference, so the port cannot promise "prompt the payer's phone".
2. The instant payment notification (IPN) carries identifiers only and, in the public reference,
   **no signature**. The documented source of truth is `GetTransactionStatus`.
3. The API takes `amount` as a number and `id` (our merchant reference) as a string of at most 50
   characters, so money conversion and the reference format need rules.
4. The public reference documents collections, refunds and order cancellation. It documents **no
   payout or disbursement endpoint and no settlement report endpoint**.

Questions to settle: how an order maps to a loan or account; how the IPN is trusted; how duplicates,
partial, failed, reversed and late payments behave; how reconciliation works without a settlement
API; whose credentials are used; what is out of scope.

## Decision

1. **One adapter behind the existing `PaymentGateway` port, which is amended for hosted orders.**
   `PesapalGateway` lives in `core.payments.internal`. The port's `initiate_collection` becomes
   `create_order(intent_id, amount_minor, currency, payer_phone_e164, description, merchant_reference)
   -> {provider_reference, redirect_url}`; `query_status(provider_reference)` returns the status,
   the paid amount, the currency, the provider's own merchant reference, the payment method and the
   confirmation code. `verify_callback` becomes `read_notification(request)`, which only extracts the
   two identifiers. `fetch_settlement` is optional and not implemented for Pesapal. A fake gateway
   implements the same port for tests and development and is refused in production (chapter 12
   section 12.7).
2. **The merchant reference is `BMS-<env>-<intent id as 32 hex characters>`**, 39 characters, using
   only characters Pesapal allows. It is random (the intent id is a version 4 UUID), carries no
   personal data, no tenant name and no amount, and `<env>` (`ST`, `PR`, from `BMS_ENVIRONMENT`) keeps
   one sandbox or one merchant account shared by two environments from colliding. A reference is used
   for one order creation only. Order creation is done by a single creator at a time per intent (the
   job claims the intent with `FOR UPDATE SKIP LOCKED` and a creation lease). If the creation call
   times out or its answer is lost, the intent ends `failed` with reason `creation_unknown` and the
   next attempt is a **new intent with a new reference**; the old reference is never reused, because
   the provider's reply to a duplicate reference is not documented (the spike confirms it). This is
   safe because the payer only ever receives a link from a successful answer, so an order whose
   answer was lost has no link in anyone's hands. If the spike shows a lookup by merchant reference,
   the creator may use it to recover the tracking id instead.

3. **Money crosses the boundary once, in one place.** Amounts are integer minor units everywhere
   (ADR-004). The adapter converts to the decimal the API wants with `BigDecimal` from the currency's
   exponent (UGX is 0, so whole shillings) and converts the paid amount back the same way, refusing a
   value with a fraction the currency cannot hold. No float is used.
4. **The IPN is a hint, never evidence.** The endpoint is public on the callback host, answers 200
   with the acknowledgement body the provider expects, and does only this: check a long random token
   carried in the registered URL (constant-time comparison), find the intent by the order tracking id
   or merchant reference through the definer function `app_resolve_payment_intent` (ADR-016), store
   an append-only `payment_callbacks` row with the raw identifiers, and queue a status check. The
   status check calls `GetTransactionStatus` with our own token and decides everything from that
   answer. An unknown identifier is stored in the platform queue and answered 200. A status query for
   one intent is throttled to one every ten seconds, so a flood of IPNs cannot be turned into a flood
   of calls to the provider.
5. **Status mapping.** `COMPLETED` is the only status that books money. `FAILED` and `INVALID` end
   the intent as `failed` and book nothing. `REVERSED` after a booking does not reverse anything by
   itself: it raises an alert and a `repayment_reversal` or `savings_reversal` approval request for a
   checker, because reversals are maker-checker actions (ADR-015, ADR-026). A pending status keeps
   the intent `pending`; a poller re-checks pending intents.
6. **Booking is idempotent and goes through the servicing code.** Under a row lock on the intent, if
   the status is `COMPLETED`, the provider's merchant reference equals the intent's, the currency
   equals the intent's, and no booking exists, the registered purpose handler books it in the same
   transaction: `LoanServicing` for `lending.loan_repayment`, `SavingsServicing` for
   `lending.savings_deposit`, the subscription confirmation for `platform.subscription_payment`. The
   payment method is `gateway` (account `gateway_clearing`). Idempotency is enforced twice: by the
   intent (`UNIQUE (provider, provider_reference)`, the stored `booked_subject_txn_id`, the row lock)
   and by the servicing port, which records the provider's confirmation code as the transaction's
   external reference under a unique index, so a second record for the same reference is a no-op that
   returns the first transaction. The two existing ports take no key or reference today and are
   extended in the build slices (decision 15).

7. **Amount rules.** The booked amount is the amount the status query reports as paid, in the
   intent's currency. A different currency is never booked: the receipt goes to unallocated receipts
   with an alert. A smaller amount is booked as a partial repayment or deposit and flagged; a larger
   one is booked and the servicing code's overpayment credit (R-ALLOC) takes the excess. If the
   subject cannot take the money (loan closed, account closed or frozen), the whole amount becomes an
   unallocated receipt for a cashier (FR-PAY-04). The value date is the payment date in the business
   time zone, moved to the earliest date the subject accepts (for savings, after the last end of day
   written; for a closed ledger period, today), with the real time kept on the intent.
8. **Late IPNs are normal.** An intent expires 30 minutes after creation for the payer's screen, but
   the poller keeps checking for 48 hours and an IPN or poll that finds `COMPLETED` after expiry still
   books, because the money has moved. Expiry stops the payer being offered the order; it never
   discards a payment.
9. **Daily reconciliation without a settlement API.** A nightly job re-queries every intent not in a
   final state and every intent booked within the reversal window, and lists differences (booked but
   now reversed, pending for over 24 hours, completed but unbooked, paid amount differing from the
   order, a manual receipt carrying a gateway confirmation code). The window is **7 days for mobile
   money and 120 days for card payments** (card chargebacks arrive long after a mobile money
   reversal could), re-queried daily for the first 14 days and weekly after, using the payment method
   the status query reported. Provider fees and the settled amount come from the merchant statement
   the operator uploads (CSV, matched on confirmation code and merchant reference); a matched row
   posts the fee to the gateway charges account (FR-PAY-06) and the net settlement moves from
   `gateway_clearing` to the bank when the tenant records the bank credit. If the provider later
   offers a settlement endpoint, `fetch_settlement` is implemented and the upload becomes the
   fallback.

10. **Credentials live in the server environment, never in the repository.** `BMS_PESAPAL_BASE_URL`,
    `BMS_PESAPAL_CONSUMER_KEY`, `BMS_PESAPAL_CONSUMER_SECRET`, `BMS_PESAPAL_IPN_ID`,
    `BMS_PESAPAL_IPN_TOKEN` and `BMS_PESAPAL_TENANT_ID` for tenant collections; for subscription
    payments a second, separate set `BMS_PESAPAL_PLATFORM_CONSUMER_KEY`, `..._SECRET`,
    `BMS_PESAPAL_PLATFORM_IPN_ID` and `BMS_PESAPAL_PLATFORM_IPN_TOKEN`. **An IPN registration belongs
    to one credential pair**, so each pair has its own IPN id and its own path token, and the token in
    the callback path selects the pair and the scope (a per-tenant account stores its own IPN id and
    token). A host with no values has the gateway off. The sandbox base URL is refused on the
    production profile and the live base URL on staging. The access token is cached in memory per pair
    until shortly before the expiry the provider returns (the reference says five minutes at most) and
    never logged.

11. **Per-tenant switch, default off, and the merchant is bound to one named tenant.** While
    credentials are environment-level, the merchant account is bound to the tenant named in
    `BMS_PESAPAL_TENANT_ID`, set by the operator when the lender's own merchant account is issued.
    The setting `payments.collections_enabled` can be switched on only for that tenant: for every
    other tenant, and for all tenants while the variable is unset, the route answers 409
    `merchant_not_bound_to_tenant` (a tenant admin holds `core.settings.manage`, so first come first
    served would give one lender's merchant account to whoever switched on first). It also refuses to
    switch on until the `gateway` payment method is mapped to a ledger account (FR-GL-08). The reason
    for the binding is that the merchant account receiving the money must be that lender's own; a
    platform account collecting for several lenders has licensing and fund-flow consequences that need
    a decision of their own. A later slice adds a `payment_gateway_accounts` table with the secret
    encrypted by the application data key (chapter 8 section 8.7), after which the binding lifts.

12. **Payouts and disbursements are out of scope.** Loan disbursement and savings withdrawals stay
    manual (a cashier records them, chapter 12 section 12.4.3). If a payout capability is wanted, it
    needs a provider that documents one (a mobile money disbursement API or a bank) and its own ADR;
    the port reserves a separate `PayoutGateway` interface and nothing implements it.
13. **Outbound calls follow chapter 12 section 12.1**: connect timeout 5 seconds, read timeout 15,
    a circuit breaker opening after 5 consecutive failures for 60 seconds, no call inside a database
    transaction that holds row locks, and a request id on every log line. Sandbox first: staging uses
    only the provider's sandbox with fabricated amounts; no real payer is ever prompted from staging.

14. **A handler refusal never strands a completed payment.** The booking runs the purpose handler
    inside a savepoint. For `before_last_repayment` (a repayment cannot be dated before the loan's
    last one) the handler is retried once dated today. On any other business refusal
    (`value_date_in_future`, `payment_method_unmapped`, a closed or frozen subject, a refused
    currency) the savepoint rolls back and the same transaction posts the amount to **unallocated
    receipts** (debit `gateway_clearing` by its system key, so an unmapped payment method cannot block
    it; credit `unallocated_receipts`), stores the refusal code on the intent, ends it
    `booked_unallocated` and raises an alert, and a cashier allocates it (FR-PAY-04). Only an
    unexpected technical failure (a database error) rolls the whole booking back; it is retried with
    backoff by the poller, and after five consecutive technical failures the intent becomes
    `needs_attention`, listed to the accountant and the operator with an alert, so no completed
    payment is retried silently forever.
15. **The servicing ports are extended for gateway bookings, by amendment.** `LoanServicing.recordRepayment`
    and `SavingsServicing.deposit` get overloads that take `externalReference` (the confirmation
    code, or the order tracking id if the provider gives none), `origin` (`gateway`) and
    `queueReceiptNotice`; a unique index over (tenant, payment method, external reference) for method
    `gateway` makes a repeat a no-op returning the first transaction. The receipt SMS is queued only
    when the flag is set (savings queues none through the port today, and loans queue none; the seed
    and import keep passing false). Both port javadocs are corrected, because they say the ports
    serve only the seed and the import, and an Amendment section is appended to ADR-026 and ADR-032 in
    slices #216 and #219 (additive and dated; the merged records are not rewritten).
16. **Reversals raised by the system have a maker, a freshness check and a limit.** A reversal found
    by the reconciliation raises one `repayment_reversal` or `savings_reversal` request per intent
    (idempotent), made by a per-tenant non-login system user created when collections are first
    enabled (no credentials, no roles, never a checker), so the maker-is-not-checker rule holds. The
    request carries the payload snapshot of the transaction; if allocations change before a checker
    decides, it is stale (FR-APR-08) and the next reconciliation raises a fresh one. A savings
    reversal after the deposit was withdrawn would make the balance negative: the servicing code
    refuses it, the intent stays `reversed_pending_review`, the reconciliation report shows the
    shortfall, and the accountant records the loss by manual journal. The platform never creates a
    negative balance.

## Consequences

**Better:**

- A forged or replayed IPN cannot book money: only a successful authenticated status query can, and
  only once per provider reference.
- Loan, savings and subscription payments share one mechanism and the servicing code, so a gateway
  payment posts exactly as a cashier's would and appears in the same reports and insights.
- The design is honest about what the provider does not document (settlement, payouts) and has a
  working fallback for each.
- The port stays provider neutral: another gateway is a new adapter and configuration.

**Worse:**

- The payer leaves the app for a hosted page; there is no push prompt, so the experience is a link or
  a QR code, and a cashier-initiated payment needs the payer to open it (by SMS, ADR-036).
- Provider fees are known only after the statement is uploaded, so charges lag by a statement cycle.
- One merchant per environment, bound to a named tenant, until per-tenant credentials exist limits the
  product to one lender per environment for collections.
- Polling and nightly re-checks cost provider calls; the throttle and the 48 hour window bound them.

**Watch for:**

- The IPN format, the acknowledgement body, the status casing, the token lifetime, the reply to a
  duplicate merchant reference, whether an order can be looked up by merchant reference, and the
  card reversal window are taken from the public reference or assumed and must be confirmed against
  the sandbox in the first slice (a spike with recorded fixtures) before any booking code is written.
- Pesapal reversals and chargebacks arrive after money was booked; the alert and the checker request
  must not be skipped by a later "auto-reverse" shortcut.
- A merchant reference longer than 50 characters or with a character outside the allowed set is
  refused by the provider; the reference format is covered by a test.
- Keep the secrets out of logs and out of error messages, as the outbox senders do (ADR-024).


## Amendment (2026-10-14, issue #211): the sandbox spike

Additive. The decisions above are not rewritten; where the sandbox contradicted an assumption, this section
is the current rule. Evidence, fact by fact and marked observed or documented, is in
`docs/specs/client-extras.md` section 3.12; the recorded shapes are in `backend/src/test/resources/pesapal/`.

1. **Decision 5 (status mapping) is corrected.** An unpaid order, and a cancelled one, answer status code 0
   with description `INVALID` and the error `payment_details_not_found` ("Pending Payment"). `INVALID` therefore
   does not end an intent as `failed`: code 0 with that error keeps the intent `pending`; code 0 with
   `invalid_order_tracking_id` or a credential error is an anomaly that is counted and alerted and never books or
   fails an intent by itself; only code 2 (`FAILED`) fails an intent. The adapter decides on the status code and
   compares the description case-insensitively, because the reference shows `Failed` and the sandbox shows
   `INVALID`.
2. **Decision 2 (merchant reference) is relaxed, not reversed.** The provider answers a second
   `SubmitOrderRequest` with a used reference by returning the original order (same tracking id and redirect
   url, new body ignored), and there is no lookup by merchant reference. The creator may therefore repeat the
   same reference and body after a lost answer, up to 3 times, accepting an answer only if the echoed
   reference equals the intent's and the first status query reports the intent's amount and currency; otherwise
   the intent fails with `order_mismatch`. After every attempt fails the original rule applies: the intent ends
   `failed` with `creation_unknown` and a new intent takes a new reference. A reference is never reused after a
   cancel or a failure, because the cancelled order stays attached to it. This was observed on the sandbox only;
   the live statement is a written question to Pesapal, and the two checks keep the code correct either way.
3. **Decision 3 (money) is confirmed and becomes mandatory.** The provider accepts and returns fractional UGX
   (1000.55, 0.5) and a numeric string; the adapter alone enforces whole shillings.
4. **Decision 4 (the IPN is a hint) is confirmed**: the IPN and the payer redirect carry only
   `OrderNotificationType`, `OrderTrackingId` and `OrderMerchantReference` (documented, not observed). The
   acknowledgement is a JSON object echoing the three values in camelCase with a numeric `status` of 200
   (documented, not observed), answered with status 500 only when our own store failed. The retry count and
   interval are not published and are a written question; the poller of decision 8 already makes a lost IPN
   cost a delay only.
5. **Decision 10 (one IPN registration per credential pair) is confirmed**: an IPN id of one pair is refused
   (`InvalidIpnId`) for another pair. Registering the same url and type again returns the same id, so the
   operator command is idempotent; RegisterIPN is throttled (a second call within about 5 seconds is refused
   with http 409), so the command spaces its calls by 10 seconds; the provider accepts `http` and localhost
   urls, so the command refuses them itself.
6. **Decision 13 (outbound calls) gains three rules**: business errors arrive as http 200 with a body status of
   500 and must be read from the body (401, 404 and 405 come as a wrapper holding escaped JSON); the sandbox
   token lives 3600 seconds although the reference says five minutes, so the cache follows `expiryDate`; the
   status reply's `created_date` is East Africa Time with no zone marker.
7. **Decisions 9 and 12 are unchanged and still open with the provider.** No settlement, fee or payout
   endpoint exists in the reference or on guessed routes; the card chargeback and reversal window is not
   published (the 120 days stay an assumption); the rate limits and IPN retries of the live system are
   unknown. The questions, with an owner and a date, are in spec section 3.12.2.


## Amendment (2026-10-17, issue #213): the gateway core as built

Additive; the decisions above stand. Where the build made a choice the text left open, this section is the current rule.

1. **States.** Intents are `created`, `pending`, `paid`, `booked`, `booked_unallocated`, `needs_attention`, `failed`,
   `cancelled`, `expired`, `reversed_pending_review` and `reversed`. `paid` is new: the checker stores the verified
   answer (amount, currency, confirmation code, time) in one transaction and the booker books it in the next, so a worker
   that dies between them leaves a row the poller finishes. The allowed transitions are enforced by a trigger on
   `payment_intents` (migration V55), which also forbids changing what was asked (amount, currency, subject, reference,
   expiry) and anything the provider reported once it is written. Intents are never deleted.
2. **Status mapping (decision 5) stands as amended.** The adapter decides on the numeric code. Anything it cannot map (an unknown
   order, a credential error, a status code outside 0 to 3, a fraction of a shilling, a tracking id that is not the one
   asked for) is an anomaly: counted, never booked, never ends an intent. A COMPLETED answer whose merchant reference or
   currency is not the intent's, or whose amount is not a positive whole number, ends the intent `needs_attention` and books
   nothing. An order still unpaid 48 hours after creation is cancelled at the provider and the intent ends `cancelled`.
3. **Handler refusals (decision 14).** `before_last_repayment` is retried once dated today (Africa/Kampala). Any other business
   refusal ends the intent `needs_attention` with the refusal code and the amount stored. The posting to unallocated
   receipts that turns it `booked_unallocated` belongs to slice #216, which owns the `gateway_clearing` postings; the
   state exists in the database now and nothing writes it yet. Five consecutive technical failures end `needs_attention`.
4. **Notifications.** `GET` or `POST /api/v1/payments/callbacks/pesapal/{token}` and the payer's
   `GET /api/v1/payments/return/pesapal`. The token selects the credential pair (constant time over digests); the request is read
   for two identifiers only, whatever its content type; the tenant is found from the merchant reference by a definer function
   that also checks the pair; unknown references and tracking ids that do not match are acknowledged, counted and kept in a bounded
   platform table; past an in-application limit per address and overall the answer is 429. The acknowledgement is the
   documented JSON body with status 200, or status 500 only when our own store failed.
5. **Where checks run.** A notification does not call the provider: it moves the intent's next check to now (at least ten
   seconds after the last check) and the poller, every 10 seconds, takes due intents fairly across tenants (5 per tenant, 40
   per tick, `FOR UPDATE SKIP LOCKED` with a lease). One path serves a notification, a lost notification, the creation of an
   order a request did not finish and the retry of a booking.
6. **Order creation** follows the 2026-10-14 amendment: one creator per intent under a lease, the same reference and body repeated
   up to three times, an echoed reference that is not ours ends `order_mismatch` (the stray order is cancelled), a first status
   answer that does not repeat the amount and currency ends `order_mismatch`, and after the attempts `creation_unknown`.
7. **Switch and binding.** The settings group `payments` (`collections_enabled`, optional `daily_ceiling_minor`) is written only
   through `PUT /api/v1/payments/settings`; switching on is refused 409 `merchant_not_bound_to_tenant`,
   `gateway_method_unmapped`, `gateway_not_configured` or `daily_ceiling_required`, and intent creation checks the same things again.
   A daily ceiling (minor units, over the tenant's Kampala day, counted under an advisory lock) is required to switch on and cannot be
   removed while on: it is the bound on what one day can ask for, and a placeholder for the commercial limits, which stay parked (no
   price and no billing is built).
9. **An expired order stays payable, deliberately.** After the 30 minutes the payer is no longer offered the order, but the hosted
   page cannot be withdrawn without a cancel that can lose a race with a payment, so a payment that completes later is booked and
   flagged `late`. A cancel by staff, or the final cancel after 48 hours, is best effort at the provider; a payment that wins that
   race still books (`cancelled` to `paid` is an allowed transition).
10. **The payer return page** (`GET /api/v1/payments/return/pesapal`) has no token and is open to any browser: its own rate limit bucket,
   no platform queue row and no counter for an unknown reference, tenant-scope orders only until #228. It is on the callback host.
8. **Environments.** `BMS_PESAPAL_BASE_URL` outside production may only be the sandbox host, in every mode and whether or not
   credentials are set, so staging cannot reach the live provider; production refuses the sandbox and the fake; the two
   credential pairs must have different IPN ids and path tokens.
