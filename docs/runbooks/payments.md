# Runbook: payment collections through Pesapal

Slice A2 (issue #213, ADR-035 and its 2026-10-15 amendment, spec `docs/specs/client-extras.md` section 3), changed by slice A7
(issue #233, ADR-035 amendment of 2026-10-18): **money owed to a business lands only in that business's own Pesapal account, never
in the platform's**. Mobile money and card collections are a paid add-on: off for everyone until the business connects and tests its
own Pesapal account (`docs/runbooks/connect-lender-pesapal-account.md`) and switches collections on. This runbook covers the host
secrets, sandbox against live, switching a business on, rotating the data key, and what to do when something goes wrong. No key,
secret or token is ever written in the repository, a chat or an issue.

## 1. What is where

| Variable | Meaning |
|---|---|
| `BMS_PESAPAL_PROVIDER` | Empty (adapter when the callback origin or the platform pair is set, else off), `pesapal`, `fake` (never in production) or `off` |
| `BMS_PESAPAL_BASE_URL` | Empty or the provider's API base. Outside production only the sandbox is accepted |
| `BMS_PESAPAL_CALLBACK_BASE_URL` | The public https origin of the callback host, no path. **Needed before any lender can test its account**: the notification address is built from it |
| `BMS_PESAPAL_PLATFORM_*` | The operator's own pair for subscription payments (slice #228); leave empty until then. Never used for a lender's money |
| `BMS_PESAPAL_REFUSED_KEY_DIGESTS` | **Required in production whenever the gateway is on** (startup refuses otherwise): comma separated SHA-256 digests (lower case hex) of **every** consumer key the platform operator owns, current and retired, so no lender can enter one as its own account, whether or not `BMS_PESAPAL_PLATFORM_*` is in use. Compute one with `printf %s "$CONSUMER_KEY" \| shasum -a 256`, **and a second one over the key in lower case** (`printf %s "$CONSUMER_KEY" \| tr 'A-Z' 'a-z' \| shasum -a 256`; the same digest when the key has no capital letters): a key is compared without regard to letter case (see section 4c), so a lender entering your key with a letter's case changed is caught by the lower case digest. Add a key's digest before it is ever used anywhere, and keep the digests of retired keys. Development and tests may leave it empty |
| `BMS_DATA_KEY`, `BMS_DATA_KEY_ID` | The application data key (32 bytes, base64) that seals the lenders' stored credentials (and TOTP secrets), and its id |
| `BMS_DATA_KEY_PREVIOUS` | Only during a data key rotation: the retired keys as `id:base64` pairs, comma separated (section 4a) |

A lender's own consumer key and secret are **not** in the environment: the lender enters them on the Receive payments screen and they
are stored sealed in `payment_gateway_accounts`. The variables `BMS_PESAPAL_CONSUMER_KEY`, `BMS_PESAPAL_CONSUMER_SECRET`,
`BMS_PESAPAL_IPN_ID`, `BMS_PESAPAL_IPN_TOKEN` and `BMS_PESAPAL_TENANT_ID` of slice #213 are removed; delete them from a host's
`/opt/bms/.env` (they are ignored).
| `BMS_PAYMENTS_*` | Tuning, not secrets: `POLL_PER_TENANT` (5), `POLL_TOTAL` (40), `CALLBACK_IP_PER_MINUTE` (60), `CALLBACK_GLOBAL_PER_MINUTE` (3000), `MAX_BODY_BYTES` (4096) |

Values live in `/opt/bms/.env` (mode 600) and nowhere else. The API container gets them; no other container does. A change
is a restart. Rotate the platform pair on staff change, yearly and after any suspected exposure: ask the provider for a new
pair, update the file, restart the API, confirm `Payment gateway:` in the start log says `platform-credentials=set` and
`tenant-accounts=enabled` (the log never prints a value).

## 2. Sandbox and live

* **Staging can never move real money.** At start the API refuses any `BMS_PESAPAL_BASE_URL` that is not the sandbox
  when `BMS_ENVIRONMENT` is not `production`, in every mode, with or without credentials, and refuses it with a message that
  names the variable only. Leave the variable empty to take the sandbox. The startup test is `PesapalSettingsTest`.
* **Production refuses the sandbox and the fake.** An empty base URL means the live API there.
* The sandbox merchant is shared by other developers: register only fabricated or staging urls, never read the list of
  registrations back as ours, and use only the sandbox's published test cards (spec 3.12). Mobile money has no published
  sandbox number, so it cannot be tested end to end before the provider answers question 9 of spec 3.12.2.
* The sandbox host did not validate with one client's bundled certificate list. If the first call from the staging JVM fails
  with `tls`, import the provider's intermediate certificate into the truststore (spec 3.12).

## 3. The notification url

A **lender's** notification url is registered by the lender's own Test connection (no operator step): the API builds
`<BMS_PESAPAL_CALLBACK_BASE_URL>/api/v1/payments/callbacks/pesapal/<a new random token>`, registers it for the lender's account and stores
only the SHA-256 of the token. The steps below are for the **platform pair** only (subscription payments, slice #228). The url is
`<BMS_PESAPAL_CALLBACK_BASE_URL>/api/v1/payments/callbacks/pesapal/<BMS_PESAPAL_PLATFORM_IPN_TOKEN>`, registered as type
`GET` (the provider appends the three names as query parameters). The callback host must be reachable from the internet: the
staging tunnel must expose it (chapter 9 section 9.13). The proxy forwards only `/api/v1/payments/callbacks/*`,
`/api/v1/payments/return/*` and `/api/v1/channels/*` on that host. **Required before activation, not yet present:** an edge rate
limit on those paths and redaction of the path token from the proxy's access log (the application's own limit per address
and overall holds without the edge rule, and the IPN and the payer return have separate buckets).

1. Generate a token: 32 random characters of `[A-Za-z0-9]`. Put it in the environment file as `BMS_PESAPAL_PLATFORM_IPN_TOKEN`.
2. Register the url with the provider (dashboard, or `PaymentGateway.registerIpn`, which refuses a non-public or `http` url
   itself because the provider accepts them, spaces its calls 10 seconds apart and waits out the provider's throttle of about
   one call in five seconds). Registering the same url and type again returns the same id, so repeating is safe.
3. Put the returned id in the environment file as `BMS_PESAPAL_PLATFORM_IPN_ID`. One id belongs to one credential pair: never reuse
   it for a lender's account or another merchant.
4. Restart the API.

Until the first staging run proves it, the acknowledgement body (spec 3.6) and the arrival of a GET notification are
documented, not observed. On that run pay a sandbox order with a documented test card, watch the status become completed, and
check the notification arrived and the order booked.

## 4. Switching a business on

1. The business's administrator connects and tests its own Pesapal account: `docs/runbooks/connect-lender-pesapal-account.md`. The
   operator does not enter, see or hold the lender's key or secret; the platform console shows the status only
   (`GET /api/v1/platform/tenants/{id}/payment-account`). Until the account is verified, switching on gets 409
   `merchant_account_not_verified`, and a payment cannot be started (422 `merchant_account_not_verified`): the platform's account is
   never a substitute.
2. In the business, map the `gateway` payment method to a ledger account (Settings, payment methods). Until then 409
   `gateway_method_unmapped`.
3. A tenant administrator opens Settings, payments, sets the daily ceiling in minor units and switches collections on
   (`PUT /payments/settings`, `If-Match`). With no provider or callback address on the host the answer is 409
   `gateway_not_configured`; with no ceiling 409 `daily_ceiling_required` (it cannot be removed while on).
4. Collecting a **loan repayment** (slice #216) is offered on the loan page as "Collect by mobile money or card" to staff with
   `core.payments.collect`; see section 4d for what to watch. Collecting a **savings deposit** (slice #219) is offered the same way on an active or
   dormant savings account; see section 4e.
5. **Before the first live repayment:** ask KTA to confirm the own-account model (ADR-035 amendment of 2026-10-18). It is a gate on
   live money, not on staging with the sandbox.

Switching off is never refused and stops new intents at once; intents already created are still checked and booked. A lender cannot
remove or replace its account (other than a new secret for the same consumer key) while an intent still needs it (409 `payments_in_flight`).

## 4d. Loan repayments by mobile money: operating them

What happens: staff start a payment on a loan, the payer pays on their phone into **the lender's own** Pesapal account, the notification or the poller
confirms it with an authenticated status query, and the booker records it as a repayment (method `gateway`, account the mapping of `gateway`, normally
`gateway_clearing`) exactly like a cashier's, with a receipt number and the receipt text. Nothing is recorded on a notification alone.

1. **Switch on per business, in this order:** the administrator connects and tests the business's own account (`connect-lender-pesapal-account.md`), the `gateway`
   payment method is mapped, then collections are switched on with a daily ceiling (section 4, steps 1 to 3). The loan action appears for staff holding
   `core.payments.collect`; a business without a verified account gets a plain refusal on the form and nothing reaches Pesapal.
2. **Where the money is.** Each repayment settles into the lender's Pesapal merchant account, not ours. The `gateway_clearing` balance is what Pesapal owes the
   business until the lender records the bank credit (chapter 12 section 12.4.2). The intent's `merchant_id` (and the audit row `core.payments.intent.booked`)
   say which merchant account took the money.
3. **A completed payment the loan could not take.** A closed loan, a date the books cannot take or an unmapped method does not leave the money unbooked: it is posted out of the account `gateway` is mapped to at the branch into `unallocated_receipts`, and the intent ends
   `booked_unallocated` and a row is written to `unallocated_receipts` with the refusal code.
   The counter `booking_refused` (spec 9.2) is the alert. Receipts debit the account the `gateway` method is mapped to at the branch, the account a repayment would have debited (`gateway_clearing` only when the method is unmapped or switched off). To list what is waiting (owner role, never the application role):
   `SELECT created_at, tenant_id, amount_minor, refusal_code, confirmation_code, intent_id FROM unallocated_receipts WHERE status = 'open' ORDER BY created_at;`.
   **Staff clear it through the queue (FR-PAY-04, slice #224), never by a manual journal.** The screen "Unallocated receipts" (a holder of `core.payments.read`) lists what is waiting; a cashier or accountant holding
   `core.payments.allocate` asks to put the money on a loan (or a savings account) or to record that it went back, and a second person holding `core.payments.allocate_approve` (tenant admin, branch manager,
   accountant; never the same person) decides it in the approvals inbox. Approving books the repayment or deposit through the same code as any payment, or records the refund, and clears the liability in the
   same step; the receipt ends once, whatever is clicked twice. Money above a loan's payoff is allowed (spec 3.7): the loan is paid off and the rest becomes the borrower's credit balance; the inbox shows the checker "This creates a credit balance of ... on loan ..." before they decide, and the excess is in the audit log (ADR-035 decision 51). A refund by `bank`, `cash` or a wallet needs that method mapped to an account (the form refuses an unmapped one); the platform does not send the
   money, staff do, and type the bank or wallet reference. A manual journal that clears the liability by hand leaves the receipt `open` and it can then be allocated a second time: do not. The money is safe in the lender's own
   Pesapal account and in the liability `unallocated_receipts` while it waits.
4. **Five technical failures in a row** (the database, or a configuration error such as `account_not_postable`, a ledger `currency_mismatch` or a missing chart account) end the intent `needs_attention` (`refusal_code technical_failures`) with an alert; the money is
   safe at the provider and in the intent's stored paid amount. Look at the log lines `payment intent ... booking failed technically`, fix the cause and ask the
   developer to re-queue; nothing is retried for ever.
5. **A repeated order** (the port is keyed on the order tracking id, never on the provider's short confirmation code, which two genuine payments can share) for the same loan and amount returns the first repayment; the same order for another loan or amount is
   refused `duplicate_reference` and the second payment is held as an unallocated receipt. A written-off loan takes no gateway payment (held the same way). Staff cannot
   reverse a gateway repayment by hand (`gateway_reversal_pending`): it is reversed only when the provider reports the payment reversed (step 7). If a cashier also entered
   the same mobile money receipt by hand it is a second repayment: the reconciliation report flags it (the next slice of #224).
6. **Receipt text.** Queued in the same transaction for a business with SMS events on (`sms-setup.md`); a business with SMS off sends none.
7. **The provider reports a payment reversed** (a chargeback or a refund the payer won). Nothing is reversed by itself. The intent shows `reversed_pending_review`, the counters `reversal` and `reversal_requested` rise
   (alert on both), and one approval request `gateway_payment_reversal` appears in the checker queue, made by "Payments system" (a user that cannot sign in). The checker needs `core.payments.allocate_approve` and also the
   permission that reverses a repayment (`lending.repayments.reverse_approve`); approving reverses the repayment through the normal reversal (the clearing balance falls by the amount, the loan is re-allocated), or clears
   the receipt if the money was still in the queue, and ends the intent `reversed`. Rejecting keeps the payment and the intent stays in review (it is not raised again and the weekly look stops). A repayment that closed the loan and would reopen it without some of its pledged items lists those items on the request, and the checker then also needs `lending.collateral.release_approve` (the same two permissions as a staff reversal of such a repayment); the same holds for rejecting. A request is raised in the branch of the loan or account that is reversed, and the checker must also decide payments in the branch the money was received in. If the same order has been asked for three times and still stands (`reversal_unresolved`), or a checker rejected it (`reversal_rejected`), the order stays in review but is **handed over**: it carries that word on its `refusal_code`, no weekly look is scheduled, and the counters `reversal_unresolved` and `reversal_rejected` are the alert. To put it to a checker again, a holder of `core.payments.allocate_approve` in its branch asks `POST /payments/intents/{id}/reversal-request`: the system first asks the provider (a payment it now reports completed goes back to booked and nothing is raised; if the provider cannot be asked, try again), then raises a fresh request **in the asker's name**, audited. The asker cannot decide it, and neither can anyone who rejected an earlier request, or asked for one, for the same payment in the same episode (the provider reporting it reversed again after it was completed is a new episode): a different checker decides. In a small team where everyone who holds both checker permissions has already rejected or asked, no one can decide until another user is given them (an administrator is one) or the provider reports a new episode; the order stays handed over meanwhile. The answers of the ask are 202 (the request), 409 `try_again` (the provider could not be asked, or a checker is deciding the earlier request: wait a moment), 409 `completed_again` (the provider says it is paid), 409 `provider_disagrees` (it says pending or failed: look at the order with the provider), 409 `not_in_review` and 422 `nothing_to_reverse`; the provider is asked at most once in ten seconds, whoever asks (the slot is claimed under the order's row lock). A request already waiting is returned. If the provider reports a reversed payment **completed again**, nothing in the books was reversed, so the order goes back to `booked` (or `booked_unallocated` while its money is in the queue), the waiting request ends `stale` with a note the checker reads, and the counter `completed_after_reversal` rises; if the approved reversal had already been made, the order stays `reversed`, the same counter rises and a person must look (the borrower paid and was given it back). Reversals of money that stands in a loan are never taken out of `needs_attention` by hand: the database refuses that move for an order that carries a booked transaction. If the loan or the receipt moved after the request
   was made it goes stale and the next notification or the weekly look raises a fresh one. A booked payment is looked at again daily for 7 days when the provider names a mobile money wallet and for 120 days (daily for 14
   days, then weekly) for a card or an unknown method. A reversal of money the lender already refunded by hand raises no request (nothing in the books can be reversed): the loss is recorded by the accountant by manual journal.

**Rolling back V84 (the unallocated receipts queue).** Flyway has no down step; the migration is expand only, so the previous application
version runs on the new schema untouched. To go back to the previous release: deploy it (it ignores the new columns, the `system` user and
the new permission), and leave the data. If the schema itself must be put back, in this order and only by the owner role, after the
queue has been emptied (no `unallocated_allocation`, `unallocated_refund` or `gateway_payment_reversal` request pending; reject them):
(1) drop the trigger `unallocated_receipts_guard` and its function, and restore `payment_intents_guard()` from V54 (V84 added the moves from review back to booked and narrowed needs_attention to reversed);
(2) revoke `UPDATE` on `unallocated_receipts` from `bms_app` (the V59 grant was `SELECT, INSERT`) and drop the V84 columns and checks; a receipt
already `allocated`, `refunded` or `reversed` has lost its `status` explanation then, so keep a copy of
`SELECT id, status, allocated_txn_id, cleared_entry_id, refund_method_key, refund_reference FROM unallocated_receipts WHERE status <> 'open'`
first, and set those receipts back to `open` only if their journal entries are also reversed by the accountant (the books, not the status, are the record);
(3) keep the `users` rows of kind `system` and leave `users_kind_check` as V84 made it: requests, allocations and audit rows name that user as their maker with no foreign key, and deleting it would leave them pointing at nothing (it has no credentials, role or session, and every staff query filters on kind `staff`); if the check must be put back, export the rows that reference those ids first;
(4) delete the permission `core.payments.allocate_approve` and its three role rows. Nothing of this is needed to roll the application back.

## 4e. Savings deposits by mobile money: operating them

The same as 4d with a savings account as the subject; read 4d first. The payer pays into **the lender's own** Pesapal account, the notification or the poller
confirms it, and the booker records a deposit (method `gateway`, the account the mapping of `gateway`, normally `gateway_clearing`) exactly like a cashier's, with an `RC-` receipt
number, the running balance and the receipt text.

1. **Switch on** exactly as 4d step 1; the savings action appears for staff holding `core.payments.collect` and `lending.savings.read`, on an active or dormant account.
2. **A completed payment the account could not take.** An account that was frozen or closed after the link was made, a first deposit below the product's minimum opening balance, a
   closed period with nowhere to retry or an unmapped method does not leave the money unbooked: it is posted out of the account `gateway` is mapped to at the branch into
   `unallocated_receipts`, the intent ends `booked_unallocated` and a row is written with the refusal code (`account_frozen`, `account_closed`, `below_minimum_opening`, ...). The list query,
   the alert counter, the rule about not clearing a receipt by a manual journal and the mapped-account rule are those of 4d step 3. Money for a frozen account is a decision for the
   branch manager: the receipt waits in the queue until the manager decides whether the account is reopened and the money allocated, or refunded (4d step 3).
3. **Dated today.** A payment whose day is on or before the last end of day written is booked dated today (the audit note says "dated_today"); staff cannot back-date a savings
   movement past the nightly run (ADR-032), and neither can a payment.
4. **Repeats and reversal.** As 4d step 5: the order tracking id, never the confirmation code, is the reference; a repeat for the same account and amount returns the first deposit, another account
   or amount is `duplicate_reference` and is held as an unallocated receipt. Staff cannot reverse a gateway deposit by hand (`gateway_reversal_pending`); a reversal the provider reports is raised and decided as in 4d step 7, with `lending.savings.withdraw_approve` as the second permission. A deposit
   the member already withdrew is not reversed into a negative balance: the approval fails with `insufficient_balance`, stays pending, and the accountant records the loss by manual journal and rejects the request. If a cashier also keyed the same mobile
   money receipt by hand it is a second deposit; the reconciliation report flags it (the next slice of #224).
5. **Five technical failures in a row** end the intent `needs_attention`: as 4d step 4.

## 4f. Reconciliation and the statement upload: operating them

Each night at 03:10 (Kampala) every active business that has orders or statements gets a reconciliation run (job `core.payments.reconcile`); an accountant can run one now. The job writes at most one run per business and day (run again the same day, it writes nothing); a run now while another run of the business is going waits up to 30 seconds and then answers 409 `try_again`. It reports and never repairs: it posts nothing to a ledger and changes no order's status. Its one write to an order is to
schedule an immediate look (`next_check_at`, `updated_at`) at one that is paid and unbooked, or unpaid after a day, and has nothing scheduled, which the poller then does; it also removes runs older than 90 days. Read the report on the page "Payment reconciliation"
(`GET /payments/reconciliation`, `core.payments.read`, limited to the branches the reader reads). The counter `reconciliation_differences` is the alert, and it rises only for a difference the previous run did not already list. Uploading a statement, running the reconciliation and marking a line as
outside this system need `core.payments.manage` (tenant admin, accountant) **in every branch**: a branch-only holder is refused.

1. **Upload the statement.** Export the merchant statement from the provider's dashboard as CSV and upload it under "Payment reconciliation" > "Upload the provider's statement". It needs a column for the order tracking id and/or the confirmation code, the amount and the date; the fee, the net and the payment method are
   read when present, and every other column (a payer's number included) is ignored and never stored. A file with anything the system cannot read (a bad amount or date, a date after today, a date such as 10/11/2026 that could be read two ways, a line with another number of cells than the header, two columns that mean the
   same thing, a quote left open, the same order twice, two lines that name the same order, a code starting with `=`, `+`, `-` or `@`) is refused whole and the screen names the **line of the file** each problem is on; fix the export and upload again. The same file twice changes nothing. Upload each export once; overlapping exports are
   safe (a fee posts once per order).
   *Which days the export covers.* A booked payment missing from every export is reported only for a day the export is **complete** for. The system cannot know whether an export began or ended part way through a day, so unless you state the days (the two optional date fields next to the file) it counts only the days strictly between the
   first and the last line; a one-day export counts none. State the first and last day when you know the export is complete for them.
2. **What an upload does.** Every line is matched to the business's own orders. A cleanly matched line posts the provider's fee to "Payment gateway charges" (debit it, credit the account the gateway method is mapped to, normally `gateway_clearing`), once per order. A line whose amount differs, whose order this system does not
   hold as paid, whose code names several orders, or that matches no order posts nothing and is listed. A matched line with **no fee** posts nothing either, and so does a **re-upload of the same file** (it is the same statement): if the fee was missing or wrong, a corrected export (a different file) is needed. Statement lines that match no
   order are **not** booked: they may be money for something outside this system. If one is, open the statement and press "Not ours" on the line with a short note: that asks a second person (another holder of `core.payments.manage` in every branch) to confirm it in the approvals inbox, and only once they do is the line no longer reported (ADR-035 decision 66). The request is raised in the head office branch; a business with no head office is told so (422 `head_office_missing`) and should mark one branch as the head office first. A held fee whose order's fee was posted meanwhile from another line is closed as stale when approved (409 `fee_already_posted`), posting nothing. A provider fee is posted at once only up to 5% of the line's amount (`BMS_PAYMENTS_STATEMENT_FEE_AUTO_MAX_BPS`, in basis points, default 500); a higher fee shows "waiting for a second person to approve" and a holder of `core.journals.approve` other than the uploader approves it in the inbox, which posts it once (decision 65). If one is money to put on a loan, the lender records it as an ordinary receipt.
3. **Reading the differences.**
   `completed_not_booked` / `pending_over_24h`: the poller is asked to look again at once; if it stays, see 4d step 4 (`needs_attention`) and the logs. `paid_differs`: the member paid another amount than ordered (nothing wrong in the books). `reversed_awaiting_review`: the provider reversed a booked payment and a checker has not decided (4d step 7).
   `reversal_handed_over`: the same, but nobody is waiting on it (rejected, or asked for three times without an end): put it to a checker again or leave it (4d step 7). `reversal_blocked`: the checker approved and the books refused (a deposit already withdrawn, a closed account): the shortfall; record it by manual journal and reject the request.
   `reversed_after_refund`: money already sent back by hand was then reversed by the provider: a loss, record it by manual journal.
   `manual_receipt_duplicate`: a receipt keyed by hand has the reference of a gateway payment's confirmation code **and** is for the same loan or account, or for the same amount within three days. It is probably the same payment keyed twice, so the member may be credited twice: **verify the subject and the amount, then reverse the one
   entered by hand** (its transaction id is on the line). A short code is not unique, so two genuine payments can carry one; the system lists only the likely ones, and you decide.
   `in_provider_not_in_bms`: the statement has a payment this system does not hold as paid (or has never seen); look at the order, or mark the line "Not ours" if it is outside this system. `in_bms_not_in_provider`: booked here on a day an export is complete for but on none of its lines: check the export is complete, then the payment.
   `amount_mismatch` / `currency_mismatch` / `ambiguous_statement_row`: the statement and this system disagree; check both against the member.
   If the report says the run **could not read everything** ("truncated": more orders or statement lines than one run holds in memory: 200,000 and 100,000), what it lists is partial and it lists nothing that needs the whole (no `in_bms_not_in_provider`); tell the developer.
4. **Closing a difference.** Put its cause right; the next run no longer lists it (statement lines are matched again every run). Runs are kept 90 days.
5. **Rolling back V85.** The previous release ignores the new tables and the job does not exist there; nothing needs undoing for a rollback of the application. To remove the schema (owner role only): drop the five tables in this order, findings, runs, reviews, statement rows, statements; drop the two indexes on `payment_intents`
   (`payment_intents_lower_provider_reference`, `payment_intents_upper_confirmation_code`); and delete the permission `core.payments.manage` and its two role rows. The fees already posted are ordinary ledger entries (source type `statement_fee`) and stay; they are reversed only by the accountant, one entry at a time, if a statement was wrong.

## 4a. Rotating the data key

The lenders' stored credentials and every user's second-factor seed (and the platform operators') are sealed with `BMS_DATA_KEY`; each box carries
the id of its key. To rotate:

1. Generate a new key (`java -jar bms-api.jar keys` prints a fresh data key line; take only `BMS_DATA_KEY`).
2. In `/opt/bms/.env` set `BMS_DATA_KEY` to the new key and `BMS_DATA_KEY_ID` to a **new** id (for example `k2`; the same id as the old key is refused at
   start, because it would make every old box unreadable), and put the **old** key in `BMS_DATA_KEY_PREVIOUS=k1:<old base64>` (two entries with one id are
   refused too). The compose files pass `BMS_DATA_KEY_PREVIOUS` through. Restart the API. New boxes use `k2`; old ones still open.
3. The job `core.payments.reseal-accounts` runs nightly at 03:50 Africa/Kampala and re-seals, tenant by tenant (suspended tenants too), every payment account
   and every second-factor seed sealed with a retired key, and the platform operators' seeds. Every run logs one line `data key re-seal: key=k2
   resealed=<n> remaining=<n> tenants=<n>`. A tenant whose secret cannot be opened (a key dropped too early) is logged by id and error type, left
   untouched, and the run fails so the scheduler retries.
4. Remove `BMS_DATA_KEY_PREVIOUS` (and restart) **only after a run logs `remaining=0`**. Never drop the old key earlier: a sealed credential or a seed cannot be
   recovered without it (the lender would have to enter the key and secret again, and every user would have to enrol the authenticator again).
   To see the count by hand: `SELECT app_count_sealed_secrets_to_reseal('k2')` as the application role.

## 4b. Deploy gate: moving from environment credentials (V54 era) to a lender's own account

Before V56 a lender's merchant account was `BMS_PESAPAL_CONSUMER_KEY` and `_SECRET` in the host environment, bound to one tenant. Those variables are gone
and nothing moves their values into the table (the lender enters its own key and secret). So, before deploying this change to a host that ever ran V54
with credentials:

1. Check what is in flight: `SELECT status, count(*) FROM payment_intents WHERE next_check_at IS NOT NULL GROUP BY 1` as the owner role. **Today no live
   tenant uses environment credentials and Pesapal is sandbox only**; staging may hold V54 test intents, which are harmless.
2. If any intent could be a real payment: switch collections off for that tenant (new intents stop, old ones are still checked), wait until the query above
   shows nothing (an order is final after 48 hours), then deploy. Or deploy and have the lender connect **the same Pesapal account** (same consumer key) at once.
3. What happens if neither is done: the poller finds no readable credentials, **skips the intent and looks again in an hour** (counter `no_credentials`,
   never closed, never counted as the provider being down), so a payment made meanwhile is found once the account is connected. The old notification address (the
   old `BMS_PESAPAL_IPN_TOKEN`) answers 404 from now on: a lost notification costs a delay, not a payment, because the poller asks anyway.
4. If the lender connects a **different** merchant account, its old orders cannot be checked there (the provider answers that it knows no such order): they
   are counted as anomalies, never booked and never closed; reconcile them by hand against the old account's statement before releasing anything.
5. If an account must be removed while orders wait (a compromised merchant account), the operator releases it
   (`POST /api/v1/platform/tenants/{id}/payment-account/release` with a `reason` of 10 to 300 characters; audited in the platform audit log in one transaction with the removal, and the
   tenant's administrators are emailed) and the lender connects the new one. Orders are bound to the merchant account they were made under (`payment_intents.merchant_id`): a new
   connection with a **different consumer key** is a different merchant, and the old orders are skipped, not checked with it (they wait, never closed, until reconciled by hand
   against the old account's statement). Reconnecting the **same consumer key** after a release is a new merchant id too: reconcile the same way.

## 5. How a payment moves, for the person on call

`created` (order not yet made) then `pending` (the payer has a link) then `paid` (the provider's status query said
completed, and its reference and currency are ours) then `booked`. Other ends: `failed` (the provider said so, or the order
could not be made), `cancelled`, `expired` (30 minutes unpaid; polling continues for 48 hours, and a completed payment still books
as late; an expired order stays payable on purpose), `needs_attention`, `reversed_pending_review`, `reversed`. The database guards every move.

* A notification only asks for a check; the poller (`core.payments.poll`, every 10 seconds, 5 intents a tenant and 40 a tick
  at most, fair across tenants) asks the provider. A lost notification costs a delay, not a payment.
* Nothing in a notification is read but the order id and our reference. Unknown references are answered like any other,
  counted in `payment_callback_counters` and kept (bounded) in `payment_unmatched_callbacks`.

## 5a. What is and is not watching a booked payment

* **Built (#213):** every payment is verified by the provider's status query before it is booked, and a booked payment is asked about again
  once a day for 14 days (and sooner when a notification arrives). If the provider then reports a reversal the intent becomes
  `reversed_pending_review` and the `reversal` counter in `payment_callback_counters` rises: that counter is the only alert today.
* **Not built (slice #224):** the nightly reconcile job and report, the statement upload, the checker request for a reversal, and any posting
  to the ledger for one. **The books are never changed by a reversal in #213**: the receipt stays posted until a person reverses it by hand
  (the loan or savings reversal) after the accountant agrees. After 14 days a booked payment is no longer re-asked about: a later card
  chargeback is seen only on the provider's statement. Do not activate collections for a real lender until #224 ships or the person on call reads
  that counter daily.

## 6. Incidents

| Symptom | Check and action |
|---|---|
| Payers paid, intents stay `pending` | Provider down or breaker open (log `circuit_open`): nothing is lost, the poller catches up within its schedule. If the provider returned `invalid_order_tracking_id` or a credential error the intent shows no change and the counters `status_mismatch` or `anomaly` rise: check the credentials and which merchant account the order was made under |
| 401 or `provider_auth` failures | The secret was rotated or revoked. Fix the environment file, restart. The adapter backs off 30 seconds after a refused token |
| Intent `reversed_pending_review` with `refusal_code` `reversal_unresolved` or `reversal_rejected` | A reversal the provider reported that nobody finished or a checker rejected: look at the requests of the order, then `POST /payments/intents/{id}/reversal-request` (4d step 7) if it should be reversed after all. `completed_after_reversal` on such an order means the provider took the reversal back and there was nothing of ours to carry on with: a person decides |
| Intent `needs_attention` | Read `refusal_code`: `technical_failures` (five failed bookings: look at the API log for the handler's error, fix, then a person decides how to book it), `reference_mismatch`, `currency_mismatch`, `amount_invalid` (money moved and the evidence is odd: compare with the merchant dashboard before touching anything), or a handler refusal code such as `loan_closed` (allocate by hand; the unallocated receipt screen is slice #224). Never edit the row to push it forward |
| Intent `created` for long | The provider was unreachable when it was asked. The poller retries; after five rounds the intent ends `failed` with `creation_unknown`, and the cashier starts a new intent with a new reference |
| `reversed_pending_review` | The provider reports a reversal after booking (found by the daily re-ask or by a notification). Nothing is reversed automatically and the ledger is untouched; the checker request is slice #224. Reverse through the loan or savings reversal by hand after the accountant agrees |
| Flood on the callback path | Answered 429 by the application (separate buckets for the provider's notifications and the payer return page); unknown references make no outbound call. The edge rule is required before activation, see section 3 |
| Stop everything at once | Set `BMS_PESAPAL_PROVIDER=off` (the gateway is off for every tenant and the platform, intents stay or fail, nothing is sent), restart the API. Collections can also be switched off per business, and a lender's account can be removed by its administrator when nothing is in flight |
| Counter `provider_auth` rises | Pesapal refused a lender's credentials while checking an order (the lender rotated or revoked the secret). The intent is not closed or cancelled and is asked again hourly. The lender pastes the new secret (same consumer key: allowed) and tests |
| Counter `no_credentials` rises | An intent's account has no readable credentials (not connected, released, or its data key was dropped). Nothing is lost: the intent is checked again hourly and never closed. Connect the account, or restore the retired key in `BMS_DATA_KEY_PREVIOUS` (section 4a) |
| A lender's account shows `failed` | Pesapal refused its consumer key and secret, or the notification address (`failure_reason`: `invalid_credentials` or `registration_refused`). Collecting for that lender stops (422 `merchant_account_not_verified`); other lenders are unaffected. Ask the lender to check the key and secret in its own Pesapal account and test again; never ask for the secret |
| `provider_auth` on a lender's intents | The lender rotated or revoked its secret at Pesapal. Orders already made keep failing their checks until the lender pastes the new secret (same consumer key: allowed with payments in flight) and tests again |

Alerts to wire (spec 9.2): notifications per hour, unknown references, `status_mismatch`, intents `paid` but not booked over 10
minutes, pending over 24 hours, provider 401 or 5xx rate, breaker open, reversals.

## 4c. How a consumer key is compared (issue #309)

- **Letter case.** Nothing recorded in this repository (the sandbox fixtures, ADR-035, the Pesapal reference) says whether Pesapal treats the
  letter case of a consumer key as significant, so the two checks that decide whether two keys are the same account assume it may not: the
  refused-key check (the configured platform key, and the digests listed in `BMS_PESAPAL_REFUSED_KEY_DIGESTS`) and the cross-tenant uniqueness
  check (`consumer_key_hmac`) both use the lower cased key. "AbC..." and "abc..." are one key to them. The key sent to Pesapal, and the one
  stored sealed, is always exactly what the lender pasted. If a sandbox call ever shows Pesapal refuses a case-changed key, the choice stays
  harmless (it only refuses more), so nothing needs undoing. Accounts saved before this change hold the digest of the key as entered: they
  are matched when the same case is entered again, and are re-digested in the folded form by the next data key rotation re-seal or the next
  save of that account.
- **A key that cannot be read is refused.** If a saved account's key box cannot be opened (a data key dropped from `BMS_DATA_KEY_PREVIOUS` before
  the re-seal finished, or a damaged row) the account counts as refused: it is not a verified account, no intent is created for it, a test marks
  it `failed` (`account_not_allowed`), and the host logs one line `a payment account's consumer key could not be opened`. Restore the key in
  `BMS_DATA_KEY_PREVIOUS` (section 4a) and the lender tests again.
- **Intent creation and a key change.** An intent reads the lender's account row `FOR SHARE` in the transaction that records it; saving a new
  key holds the same row `FOR UPDATE`. So an intent is created under the merchant that is current when it commits, never under the one a key
  change is replacing.
