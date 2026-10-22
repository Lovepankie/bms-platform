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
   and by the servicing port, which records the provider's reference as the transaction's
   external reference under a unique index (as built in #216 this is the order tracking id, not the confirmation code; see the amendment of 2026-10-19), so a second record for the same reference, loan and amount is a no-op that
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
- (Superseded by the amendment of 2026-10-18: one merchant per tenant, its own.) One merchant per environment, bound to a named tenant, until per-tenant credentials exist limits the
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
   `payment_intents` (migration V54), which also forbids changing what was asked (amount, currency, subject, reference,
   expiry) and anything the provider reported once it is written. Intents are never deleted.
2. **Status mapping (decision 5) stands as amended.** The adapter decides on the numeric code. Anything it cannot map (an unknown
   order, a credential error, a status code outside 0 to 3, a fraction of a shilling, a tracking id that is not the one
   asked for) is an anomaly: counted, never booked, never ends an intent. A COMPLETED answer whose merchant reference or
   currency is not the intent's, or whose amount is not a positive whole number, ends the intent `needs_attention` and books
   nothing. An order still unpaid 48 hours after creation is cancelled at the provider and the intent ends `cancelled`.
3. **Handler refusals (decision 14).** `before_last_repayment` is retried once dated today (Africa/Kampala). Any other business
   refusal ends the intent `needs_attention` with the refusal code and the amount stored (as built in #213; slice #216 replaced
   this by the posting to unallocated receipts and the state `booked_unallocated`, see the amendment of 2026-10-19 below). Five
   consecutive technical failures end `needs_attention`.
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
7. **Switch and binding.** The settings group `payments` (`collections_enabled` and `daily_ceiling_minor`) is written only
   through `PUT /api/v1/payments/settings`; switching on is refused 409 `merchant_not_bound_to_tenant`,
   `gateway_method_unmapped`, `gateway_not_configured` or `daily_ceiling_required`, and intent creation checks the same things again.
   A daily ceiling (minor units, over the tenant's Kampala day, counted under an advisory lock) is required for collections to be on (the end state of every write is checked: switching on without one, or clearing it of a tenant that
   is already on, is 409 `daily_ceiling_required`): it is the bound on what one day can ask for, and a placeholder for the commercial limits, which stay parked (no
   price and no billing is built).
8. **An expired order stays payable, deliberately.** After the 30 minutes the payer is no longer offered the order, but the hosted
   page cannot be withdrawn without a cancel that can lose a race with a payment, so a payment that completes later is booked and
   flagged `late`. A cancel by staff, or the final cancel after 48 hours, is best effort at the provider; a payment that wins that
   race still books (`cancelled` to `paid` is an allowed transition).
9. **The payer return page** (`GET /api/v1/payments/return/pesapal`) has no token and is open to any browser: its own rate limit bucket,
   no platform queue row and no counter for an unknown reference, tenant-scope orders only until #228. It is on the callback host.
10. **Environments.** `BMS_PESAPAL_BASE_URL` outside production may only be the sandbox host, in every mode and whether or not
   credentials are set, so staging cannot reach the live provider; production refuses the sandbox and the fake; the two
   credential pairs must have different IPN ids and path tokens.
11. **Reversals after booking.** A booked payment is asked about again once a day for 14 days (`CheckSchedule.nextReview`), so a reversal
   whose notification was lost is still seen; a notification asks sooner. A reversal moves the intent to `reversed_pending_review` and raises the
   `reversal` counter. **The ledger is never touched by this slice**: the receipt stays in the books until a person reverses it by hand (the loan or
   savings reversal), and the checker request raised by a system user is slice #224. The nightly `core.payments.reconcile` job, the statement upload and the
   reconciliation report are not built in #213 either (#224); the built job is `core.payments.poll`, every 10 seconds.

## Amendment (2026-10-18, issue #233): money owed to a tenant lands only in that tenant's own merchant account

Hillary's decision of 18 October 2026, built in slice A7 (issue #233). It supersedes decision 10 where it says credentials live in the
server environment for a lender, decision 11 (the merchant bound to one named tenant) and the consequence "one merchant per
environment, bound to a named tenant"; the rest of the ADR stands.

1. **The rule.** Money owed to a tenant (loan repayments, savings deposits, investment funding: slices A3, A4 and later) is paid into
   **that tenant's own Pesapal merchant account** and never into the platform's. The platform's own account receives only money owed to
   the platform (subscriptions and add-ons, slice A6). A tenant that has no connected, tested account of its own cannot start a payment
   (422 `merchant_account_not_verified`); there is no fallback to the platform account, by design and by test.
2. **Why (licensing).** Holding and passing on a third party's money looks like a payment service under the National Payment Systems
   Act 2020, which needs a licence from the Bank of Uganda. A lender collecting its own money through Pesapal is an ordinary merchant,
   the same model Nearly Free Energy uses for its own customers. **Legal confirmation (KTA) is still to be asked, and it is a gate on the
   first live repayment, not on this build.** Nothing here collects real money by default: the slice is off until a tenant connects an
   account and switches collections on, and production needs the live callback host.
3. **Build order.** A7 now comes before A3 and A4; it no longer depends on A3. A3 and A4 refuse to start a payment for a tenant with no
   verified account of its own (`merchant_account_not_verified`) and never fall back.
4. **Self-service connect.** A tenant administrator opens "Receive payments", enters the consumer key and secret of the lender's own
   Pesapal account, and presses Test connection, which asks the provider for a token and registers this account's notification address
   (a new random path token per test; only its SHA-256 is stored). The status is not connected, verified or failed, with the time of the
   last test. Saving makes no call; a refused key or registration marks the account failed; an unreachable provider changes nothing.
   The secret is write-only: sealed on arrival, never returned by any endpoint, never logged, never in an audit row. The platform
   operator sees the status and the time only (`GET /api/v1/platform/tenants/{id}/payment-account`); it cannot read or enter a credential.
5. **Where the credentials live.** Table `payment_gateway_accounts` (migration `V56`): one row per tenant and provider, forced row-level
   security, the consumer key and secret as AES-256-GCM boxes sealed with the **application data key** (`BMS_DATA_KEY`, the one that seals
   TOTP secrets; the box names the key that sealed it), the key's last four characters for display, the IPN id, and the digest of the
   path token. The environment keeps only what is not a lender's: the provider mode, the base URL, the callback origin and the platform
   pair (`BMS_PESAPAL_PLATFORM_*`). `BMS_PESAPAL_CONSUMER_KEY`, `_CONSUMER_SECRET`, `_IPN_ID`, `_IPN_TOKEN` and `_TENANT_ID` are gone.
6. **Key rotation.** After the data key is rotated the host keeps the retired key in `BMS_DATA_KEY_PREVIOUS` (`id:base64` pairs) and gives the
   new key a **new** `BMS_DATA_KEY_ID`; the application refuses to start when a retired key has the id of the current one or two retired keys
   share an id. The job `core.payments.reseal-accounts` (nightly, and run by hand) opens every secret sealed with a retired key and seals it with
   the current one, one tenant at a time in that tenant's own transaction, **suspended tenants included**: the lenders' payment credentials (with
   their blind-index digest) **and the second-factor (TOTP) seeds of every user and of the platform operators**, since they use the same key. Every
   run logs `remaining=<n>`; the retired key is removed only once a run reports 0. A secret whose key was dropped too early is left as it is and the run fails loudly.
7. **The guard in code.** A payment gateway call names an `Account`: a tenant account carries its tenant and the platform account
   carries none, so "the platform's account on behalf of a tenant" cannot be expressed. The credentials of an account come from that
   account alone (`AccountCredentials`): the platform's from the environment, a tenant's from its own decrypted row, only for the
   tenant bound to the thread, and only if the row's tenant is that tenant. `Account.forIntent` gives a stored intent the tenant's own
   account, and refuses the platform account for any purpose that is not a platform purpose. The database adds a CHECK in both directions
   (`payment_intents_scope_purpose`, V56, validated by V57): an intent of the platform scope carries a purpose starting `platform.` and an intent of the
   tenant scope does not. Tests: `AccountGuardTest`, `PaymentGatewayIT`.
8. **Notifications.** The path token selects an account: the platform's by constant-time comparison with the environment, a lender's by
   the digest lookup `app_resolve_payment_account`. A notification that arrives on one lender's address can only match that lender's own
   orders (`app_resolve_payment_intent` gained the tenant of the token; any other tenant is a scope mismatch, counted and kept).
9. **One lender's problem is not everyone's.** A lender's refused credentials do not count against the shared circuit breaker, and the
   token cache is per account and per credential value, so a replaced secret is used at once and one lender's token is never used for
   another.
10. **Replacing credentials.** While an order, a booking or a review of a booked payment still needs the credentials, they cannot be
    replaced by another merchant account or removed (409 `payments_in_flight`); a new secret for the same consumer key is allowed and
    needs a new test.
11. **What is not built here.** The purpose handlers and screens that start a payment (A3, A4, A6), and a screen for the platform
    operator in the console (the status is on the API for it). Switching collections on is now refused with 409
    `merchant_account_not_verified` (it was `merchant_not_bound_to_tenant`) until the tenant's account is verified.

### Review of 2026-10-18 (independent review of PR #308), decisions added to the amendment

12. **No shared merchant, enforced.** A consumer key equal to the platform's is refused, and so is one another tenant is already connected with: the
    column `consumer_key_hmac` holds a keyed digest (HMAC-SHA256 under a key derived from the data key; recomputed when a row is re-sealed), unique across
    tenants and checked through a definer function. The answer is 409 `merchant_account_in_use`, "already connected elsewhere".
13. **Who may redirect the money.** New permission `core.payment_account.manage` (migration V57), held by the **tenant admin only**: save, test and
    remove need it, not the general `core.settings.manage`. Saving and removing ask for a current TOTP code of the signed-in administrator's own
    authenticator (the existing second factor; each code once; wrong codes count toward the lockout; 422 `invalid_code`, 409 `mfa_not_enrolled`). Every
    save, verify, failure and removal is audited and **emailed to the other holders** of the permission (template `payments.account_changed`; no key,
    secret or account detail). The key's last four characters and the host's state are shown only to holders of the permission.
14. **A missing account never loses a payment.** An intent whose account has no readable credentials (not connected yet, removed, the key dropped) is
    skipped by the poller and looked at again in an hour: never closed, never counted as the provider being down (counter `no_credentials`). The check
    window only closes an intent when the provider could actually be asked and was unreachable. Deploy gate in `docs/runbooks/payments.md` section 4b.
15. **Changing an account while payments wait.** "In flight" means an intent still scheduled for a check (not `paid`, not `needs_attention`, which need no
    credentials). The platform operator can release a tenant's account regardless (`POST /api/v1/platform/tenants/{id}/payment-account/release`,
    `platform.tenants.manage`, audited in the platform audit log); orders left waiting are skipped, not closed, until a new account is connected.
16. **Test connection** is allowed once every 30 seconds for a business (429 `test_too_soon`); a refusal that is not about the credentials (a backoff after an
    earlier refusal, a bare 401) changes nothing; a stale result for credentials saved since is dropped (409 `account_changed`). A notification address with
    an exhausted wrong-token quota is not looked up in the database.
17. **Migrations.** `V56` creates the table, the functions and adds the CHECK `NOT VALID` (an instant ACCESS EXCLUSIVE, no scan) and drops the old
    four-argument resolver; `V57` seeds the permission and validates the CHECK in a transaction of its own (SHARE UPDATE EXCLUSIVE, writes continue);
    if an old row breaks it the constraint stays NOT VALID, still binding new rows, with a warning.

### Review round 2 (PR #308), decisions added

18. **The operator's own keys, independent of the platform pair.** `BMS_PESAPAL_REFUSED_KEY_DIGESTS` lists the SHA-256 of every consumer key the platform
    operator owns, current and retired; none can be entered by a lender, whether or not the platform pair is in use on the host (`BMS_PESAPAL_PLATFORM_*` is empty
    until subscriptions exist). Production refuses to start with the gateway on and the list empty; development and tests allow it. The second-factor code is
    checked first, so nobody learns whether a key is refused without passing it.
19. **Duplicate detection across key rotations.** A lookup passes the blind index under the current key and under every retired key (`DataKeys.blindIndexes`),
    because a row sealed before a rotation keeps its old digest until re-sealed. The re-seal runs a tenant's payment accounts and its second-factor seeds in
    separate transactions, so a problem with one can never block the other (or the `remaining` count from reaching 0).
20. **Step-up cannot be brute-forced.** A locked account (five wrong codes, 15 minutes) refuses step-up and recovery-code regeneration, and the lock ends all of the
    user's sessions (`revoked_reason = admin`), audited as `core.auth.account_locked`.
21. **A provider refusal of a lender's credentials never ends an intent.** `AUTH` on a tenant account (a rotated secret, a backoff) takes the same path as "no
    credentials": the intent is neither closed nor cancelled at the provider, counter `provider_auth`, asked again in an hour.
22. **Intents are bound to the merchant account they were made under.** `payment_gateway_accounts.merchant_id` is new when the consumer key changes (not when only the
    secret does); `payment_intents.merchant_id` records it. Credentials resolve for an intent only while the stored account is that merchant account; another one is
    "no credentials" (skipped, never checked with another merchant's credentials). Intents made before V56 (null) resolve to the tenant's present account as before.
23. **Testing keeps the registration.** Test connection on an account whose notification address is registered only checks the credentials with a fresh token
    (`PaymentGateway.checkCredentials`); the address and IPN id stay, so earlier orders keep notifying a live address. A new registration is made only when there is none.
24. **Notices go to every holder of the permission, the actor included, and name who** (a business with one administrator hears of a hijacked session's change). The
    operator's release needs a reason (10 to 300 characters), removes the account and writes its platform audit row in one transaction, and emails the tenant's administrators.
    The wrong-token pre-check looks at the address's own count only, so a flood from elsewhere cannot shut out a lender's genuine notification.

### Review round 3 (PR #308), decisions added

25. **The refused list is checked whenever a key is used, not only when saved.** A key saved while the gateway was off or the list empty is refused the moment the list
    names it: Test connection marks the account `failed` (`account_not_allowed`), the account is not a verified account (so collections cannot be switched on and no intent
    can be made), and its credentials resolve to nothing (`VerifiedAccounts`, `AccountCredentials`).
26. **A wrong password never ends a session.** Locking at five wrong passwords stays, but only a failed second-factor step-up (a signed-in session guessing a code) ends the
    user's sessions; an unauthenticated stranger who knows a login name can lock the account (as before) but cannot log staff out.
27. **One merchant, one id, however often it is connected.** `merchant_id` is derived from the tenant and the SHA-256 of the consumer key, so the same key after a release is the
    same merchant and its orders are still checked and booked; another key retires the old merchant's scheduled intents (`needs_attention`, `merchant_changed`; an order not yet
    made fails, a booked payment's review stops) instead of leaving them waiting for ever, and only the current merchant's intents block a change. A new secret for the same
    key keeps the registered notification address (V56 no longer forbids a saved account carrying a registration). The operator's release notice reaches a suspended
    tenant's administrators and a failure to queue it is logged.

### Review round 4 (PR #308, follow-ups of issue #309), decisions added

26. **Step-up has its own failure counter.** Wrong codes entered by a signed-in session count on `step_up_failed_count` / `step_up_locked_until`
    (migration V58), apart from the password counter. The fifth locks step-up for 15 minutes and ends every session of the user; password
    failures never end a session and never count there, and a step-up slip never counts toward the password lock.
27. **Letter case of a consumer key.** No recorded evidence (fixtures, docs) says Pesapal treats it as significant, so the refused-key check and
    the cross-tenant uniqueness digest use the lower cased key; the key sent to Pesapal is never altered. Operators list the digest of the lower
    cased form of each platform key too (runbook `payments.md` section 4c).
28. **Fail closed, and lock the row.** An account whose key box cannot be opened is treated as refused. Intent creation reads the account row
    `FOR SHARE`, so it cannot be created under a merchant a concurrent key change is replacing.

## Amendment (2026-10-19, slice A3, issue #216): loan repayment by mobile money into the lender's own account

Additive; nothing above is rewritten.

29. **The purpose `lending.loan_repayment`** is registered by `lending.loans` (`LoanRepaymentPurpose`). Creating the intent goes only
    through the existing tenant-account path of `IntentService`: collections on for the tenant, the host able to call the provider, and a
    **verified own merchant account** read `FOR SHARE` (decision 28). A tenant without one gets 422 `merchant_account_not_verified`
    and no provider call; the platform account is never a substitute (decision 3 of the amendment of 2026-10-18). The intent records
    that account's `merchant_id`, and the booked intent's audit row names it with the order tracking id, so a booked repayment is
    attributable to the merchant account that received the money. The describe step refuses a loan that is not active, a currency other
    than the loan's (`subject_not_payable`) and an amount above today's payoff quote (`exceeds_payoff`).
30. **Booking is the cash repayment.** The handler calls the `LoanServicing.recordRepayment` overload (ADR-026 amendment of the same date)
    with the method `gateway`, the **order tracking id** as the external reference (unique per intent; the confirmation code is kept in the intent's audit row) and origin `gateway`.
    Allocation order, receipt number, journal and audit are those of a cashier's repayment; the receipt text is queued in the same
    transaction. Partial and larger payments follow spec 3.7: what was paid is booked, and the excess on a settled loan is the loan's
    credit balance (R-ALLOC step 4).
31. **Refusals never strand money, as built.** The booker retries `before_last_repayment` once dated today. Any other business refusal
    (a closed or written-off loan, `value_date_in_future`, `payment_method_unmapped`, `duplicate_reference`, a loan currency other than the payment's) rolls the handler's
    savepoint back, then in the same transaction posts the confirmed amount debit the account the `gateway` method is mapped to at the branch (`gateway_clearing` by system key only when it is unmapped, so an unmapped
    method cannot block it) credit `unallocated_receipts`, inserts the `unallocated_receipts` row (migration V59), stores the refusal code
    and ends the intent `booked_unallocated`, audited with the receipt id and the merchant. It is dated the payment's day, or today when
    that period is closed; the debit is the account the `gateway` method is mapped to at the branch, `gateway_clearing` only when it is unmapped (a lookup that cannot refuse, so it never marks the booking rollback-only). A `period_closed` refusal is retried once dated today, like
    `before_last_repayment`. Idempotency is by loan and amount: the same reference for another loan or amount is `duplicate_reference`, never a replay. Staff cannot reverse a gateway
    repayment (`gateway_reversal_pending`) until #224. The branch permission is checked before the handler describes the loan. A failure of that posting is a technical failure (retried, five in a row end `needs_attention`), never a
    lost payment. Allocating or refunding a receipt (FR-PAY-04) is a later slice; the table is append-only until then.
32. **Duplicates.** One booking per intent (row lock and status check, as before) and one repayment per order tracking id (the port's
    idempotency and the unique index), so a replayed notification, a poll that finds the same completed order and a retry after a lost
    answer end in one repayment. A payment that completes after the link expired books normally and is flagged `late`.
33. **Not built here.** The per-tenant non-login system user is created by the slice that first needs it, the reversal request of a
    booked payment (#224), since nothing in this slice acts as it; the audit actor of a booking stays the intent's initiator (spec 3.4). A
    payment in another currency than the intent's still ends `needs_attention` (decision 4; decision 31 concerns the loan's currency, which the handler refuses, and the
    posting throws if the payment's currency is not the accounts' own); posting it to unallocated receipts needs
    the status checker to hand over a row that is not `paid` and is left to the slice that builds the allocation screen.

## Amendment (2026-10-19, slice A4, issue #219): savings deposit by mobile money into the lender's own account

Additive; nothing above is rewritten. The twin of the amendment for #216: the same rules, a different subject.

34. **The purpose `lending.savings_deposit`** is registered by `lending.savings` (`SavingsDepositPurpose`). Creating the intent goes
    through the same tenant-account path (decision 28): a tenant without a verified own merchant account gets 422
    `merchant_account_not_verified` and no provider call. The branch permission (`core.payments.collect` in the account's branch) is
    checked before the handler describes the account. The describe step refuses an account that is not active or dormant, or in another
    currency than the tenant's (`subject_not_payable`), and a first deposit below the product's minimum opening balance
    (`below_minimum_opening`).
35. **Booking is the cash deposit.** The handler calls the `SavingsServicing.deposit` overload (ADR-032 amendment of the same date) with
    the method `gateway`, the **order tracking id** as the external reference and origin `gateway` (decision 30: never the confirmation
    code, which two genuine payments can share; the code stays in the intent's audit row and on the unallocated receipt). Receipt number,
    journal, running balance, audit and the receipt text are those of a cashier's deposit. Idempotent on the reference for the same account
    and amount; the same reference for another account or amount is `duplicate_reference`, held as an unallocated receipt (migration V60,
    unique partial index over deposits).
36. **The account must still be depositable when the money arrives.** `book()` refuses a frozen account (`account_frozen`), a closed one
    (`account_closed`) and any other status that cannot take a deposit, so the money goes to unallocated receipts (decision 31) instead of
    onto an account staff have stopped. Only an allow-list of business refusal codes (`account_closed`, `account_frozen`,
    `account_dormant`, `value_date_in_future`, `before_opening`, `value_date_closed`, `below_minimum_opening`, `payment_method_unmapped`,
    `invalid_status_transition`, `duplicate_reference`, `period_closed`) becomes an unallocated receipt; any other error propagates and is
    retried (five in a row end `needs_attention`). A payment dated on or before the last end of day written, or before the account opened,
    is retried once dated today (`before_last_repayment`, the handler's word for a date a later day cures, note "dated today"); a closed
    period is retried dated today as for a repayment. The receipt debits the account the `gateway` method is mapped to at the branch, as in
    decision 31.
37. **Reversal.** Staff cannot reverse a gateway deposit (`gateway_reversal_pending`, and the screen does not offer Reverse) until the
    provider review of #224, which will use the maker-checker `savings_reversal` (its negative-balance guard, spec 3.8, is unchanged).
38. **The payment code** (the provider's confirmation code) is shown on the collect screen. Savings has no receipt document of its own (the
    receipt is the receipt number on the transaction and in the text), so the code is not printed on one.

## Amendment (2026-10-19, slice A5, issue #224, part 1): the unallocated receipts queue and the governed reversal

Additive; nothing above is rewritten. Spec `docs/specs/client-extras.md` section 3.16; migration `V84`.

39. **The queue is maker-checker, and ends once.** Putting an unallocated receipt on a loan or a savings account, and recording that it went back, are requests (`unallocated_allocation`, `unallocated_refund`) made with
    `core.payments.allocate` and decided with the new `core.payments.allocate_approve` (tenant admin, branch manager, accountant), never below a threshold and never by the maker. A receipt has one change in its life
    (`open` to `allocated`, `refunded` or `reversed`), guarded in the database (a trigger, check constraints binding each ending to its fields, a unique index on the allocation's transaction) and in the code (row locks on the
    receipt and the intent, a conditional update on status and version). Decision 31's "the table is append-only until then" ends here. Alternatives refused: an allocation by a single cashier (money with no owner is exactly what a second pair of eyes is for), and
    a status flag with no guard (a bug or a race would allocate twice).
40. **Allocation is a booking.** The approval calls the same `PurposeHandler.book` the booker calls, so the repayment or deposit has the allocation order, receipt number, journal and notice of a cashier's and is idempotent on the order tracking id (decision 30);
    the receipt's entry is then reversed so the liability is released and the account `gateway` is mapped to ends where a payment that booked at once would have left it. The intent stays `booked_unallocated` and records `booked_subject_txn_id`, the transaction that holds its
    money: the reconciliation joins through `payment_intents.confirmation_code` and that column, never through a loan transaction's external reference (#312).
41. **A refund is a record, not a payout.** The provider has no payout interface (spec 3.1), so staff send the money back and the request records the method and the lender's reference; the checker approves the record and the liability is cleared (the receipt's entry reversed
    for `gateway`, a debit of the liability against that method's mapped account otherwise). A method with no active account is refused when asked. A refund is always the whole receipt.
42. **Decision 16 is built.** A payment reported reversed raises one `gateway_payment_reversal` request per order, made by the tenant's payments system user (migration `V84` lets `users.kind` be `system`; one per tenant; no credentials, role or session; every staff query filters on
    `staff`). It is raised in the status checker's transaction under the intent's row lock through `Approvals.requestAsSystem`, which returns a request already waiting instead of failing, so a notification, a poll and a review are one request. The checker needs `core.payments.allocate_approve`
    and the permission that reverses the thing (`lending.repayments.reverse_approve`, `lending.savings.withdraw_approve`). Approval reverses a booked transaction through `PurposeHandler.reverseBooked` or clears a held receipt, and ends the intent `reversed`. A request is stale
    when the subject moved (its version is the loan's, the account's or the receipt's) and is raised again; a rejected one is not; an order whose money was already refunded by hand raises none.
43. **Staff still cannot reverse a gateway transaction by hand.** `gateway_reversal_pending` stays the answer to the staff routes (decisions 31 and 37): the money would leave the clearing account while the provider still holds it for the lender. The provider-reported path is the only one, and it lifts the guard for itself alone. This replaces "until #224" in those decisions.
44. **Review windows follow spec 3.9.** A booked payment is reviewed daily for 7 days when the reported method is a mobile money wallet, and otherwise (card, or any method string not known) daily for 14 days and weekly to 120 days; a payment waiting for a checker is looked at weekly; a notification
    can ask for a look at it. The previous 14 day rule (decision 11 of the amendment of 2026-10-17) is replaced.
45. **Split.** The nightly reconciliation job and report, the statement upload with fee posting, and the duplicate-receipt flag are the second part of #224 (migration `V85`).

### Review of 2026-10-19 (attack review of PR #315), decisions added to the amendment

46. **A request lives in the branch of what it changes, and one hook decides who may decide.** The reversal request is raised in the branch of the loan or account that is reversed (for money allocated from the queue, the loan's, not the receipt's) and carries the branch the money was received in. `ApprovalAction.mayDecide(principal, branch, payload)` is the single question asked when a request is approved, when it is rejected and when the queue says "you may decide": by default the checker permission and every extra permission in the request's branch, and for this action also `core.payments.allocate_approve` in the receipt's branch. A rejection therefore asks exactly what an approval asks, **for every approval action**, not only this one: a staff reversal flagged as an unsecured reopen (#176) can no longer be rejected by an accountant who may not approve it (regression test in `LoanServicingIT`). A checker who may not approve may not reject, and so cannot keep a reversal from ever being raised again.
47. **Cautions travel on the request.** A repayment whose reversal would reopen a loan without some of its pledged items lists them on the request (`PurposeHandler.reversalCautions`), the checker then also needs the authority that releases collateral, and the items are handed
    back to the reversal, which no longer finds them unlisted. Without this the request went stale on every approval and a new one was raised each week.
48. **A reversal is handed over, not parked, and a person can start it again.** A request asked three times that still stands (`reversal_unresolved`) and one a checker rejects (`reversal_rejected`, recorded in the rejection's own transaction through `ApprovalAction.rejected`) leave the order `reversed_pending_review`, which is what it is, carrying that word on `refusal_code` with no weekly look (so "waiting for a checker" stays true of the orders that are). `POST /payments/intents/{id}/reversal-request`, for a holder of `core.payments.allocate_approve` in the payment's branch, first asks the provider (a payment it now reports completed goes back to booked and nothing is raised; if it cannot be asked, nothing is raised, 409 `try_again`), then raises a fresh request **with the person as its maker**, audited under their name: as the maker they can never decide it, and whoever rejected an earlier request, or asked for one, in the same episode is barred from deciding a later one (`conflictedDeciders`, also asked when the queue says whether a request may be decided), so one person cannot override a rejection alone, and a platform request raised after a person's cancelled or expired ask is not theirs to approve either: once any request of an episode was rejected or asked for by a person, the platform hands the order over instead of asking by itself. The bar ends with the episode, and its exit within one is a checker who took no part. Only an order that is `reversed_pending_review` is asked about (a booked one is told `completed_again`, others `not_in_review`, with no provider call and no state change), at most once in ten seconds (`CheckSchedule.THROTTLE`; an answer of a moment ago is used), and the answers are told apart: `try_again`, `completed_again`, `provider_disagrees`. A request past its expiry is not waiting, and one a checker is deciding at that moment is not replaced (the look and the ask are tried again). Requests, rejections and the count of three belong to the **episode** (each report of the reversal after a completed-again is a new one), so an earlier episode never parks a new reversal; a request left pending from an earlier episode is ended stale when the new one is raised; nothing is ever moved out of `needs_attention` by hand, and the database refuses that move for an order that carries a booked transaction. A look that could not read what it needs (the provider down, the target unreadable) keeps the schedule; only an explicit end (decided, handed over, nothing of ours to reverse) ends it. A payment the provider reports **completed again** while a reversal waits needs no person: nothing in the books was reversed, so the order goes back to `booked` or `booked_unallocated` (two moves added to the guard), the waiting request ends `stale` with a note (`Approvals.markStale`, which skips a request a checker is deciding at that moment, so the status check and a decision cannot deadlock; the checker's own run then finds the order no longer in review, ends the request as stale (`payment_not_in_review`) and changes nothing), and `completed_after_reversal` is counted once. After an **approved** reversal a payment reported completed is counted and logged for a person (the borrower paid and was given it back); a deadlock that is lost is a retryable `try_again`, not an internal error.
49. **The clearing is dated with the booking.** An allocation clears the receipt's entry on the day the repayment or deposit was dated (the payment's day, or today when that was refused), not on the day the checker approves.
50. **Review windows use an exact list.** A mobile money wallet is one of `MTN`, `AIRTEL`, `MPESA`, `M-PESA`, `TIGO` exactly; any other method string is reviewed as a card (the longer window) and logged once so the list can be corrected from what the provider really sends.
51. **Money above a loan's payoff becomes its credit balance, and the checker is told so.** Spec 3.7 governs an allocation from the queue as it governs a payment at the counter: overpayments are normal in lending, so an allocation of more than the loan's payoff is not refused; the loan is paid off and the excess becomes the borrower's credit balance. It is never silent: the request carries the excess (`credit_balance_minor`) and a sentence the checker reads on the approvals list and detail before deciding (`checker_notice`: "This creates a credit balance of UGX 450,000 on loan LN000001: the payment is more than the UGX 50,000 it takes to pay it off today."), and an audit row `core.payments.receipt.allocation_creates_credit` records the amount, the excess and the maker. The sentence is worked out again from the current payoff whenever the pending request is shown, and the allocation's own audit row records the excess at approval beside the one asked about, so a payoff that moved in between is never hidden. Decided by the owner's delegation on 22 Oct 2026 after the review of #315.
