# Runbook: payment collections through Pesapal

Slice A2 (issue #213, ADR-035 and its 2026-10-15 amendment, spec `docs/specs/client-extras.md` section 3). Mobile money and
card collections are a paid add-on: off for everyone until an operator binds the merchant account to one business and that
business switches collections on. This runbook covers the secrets, sandbox against live, registering the notification
url, switching a business on, and what to do when something goes wrong. No key, secret or token is ever written in the
repository, a chat or an issue.

## 1. What is where

| Variable | Meaning |
|---|---|
| `BMS_PESAPAL_PROVIDER` | Empty (adapter when credentials are complete, else off), `pesapal`, `fake` (never in production) or `off` |
| `BMS_PESAPAL_BASE_URL` | Empty or the provider's API base. Outside production only the sandbox is accepted |
| `BMS_PESAPAL_CONSUMER_KEY`, `BMS_PESAPAL_CONSUMER_SECRET` | The merchant account's credentials |
| `BMS_PESAPAL_IPN_ID`, `BMS_PESAPAL_IPN_TOKEN` | The notification registration of that pair, and the random path token of its url (24 to 128 letters, digits, `-`, `_`) |
| `BMS_PESAPAL_TENANT_ID` | The one tenant id the merchant account is bound to |
| `BMS_PESAPAL_CALLBACK_BASE_URL` | The public https origin of the callback host, no path |
| `BMS_PESAPAL_PLATFORM_*` | The operator's own pair for subscription payments (slice #228); leave empty until then |
| `BMS_PAYMENTS_*` | Tuning, not secrets: `POLL_PER_TENANT` (5), `POLL_TOTAL` (40), `CALLBACK_IP_PER_MINUTE` (60), `CALLBACK_GLOBAL_PER_MINUTE` (3000), `MAX_BODY_BYTES` (4096) |

Values live in `/opt/bms/.env` (mode 600) and nowhere else. The API container gets them; no other container does. A change
is a restart. Rotate the secret on staff change, yearly and after any suspected exposure: ask the provider for a new
pair, update the file, restart the API, confirm `Payment gateway:` in the start log says `tenant-credentials=set`
(the log never prints a value).

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

## 3. Registering the notification url (once per credential pair)

The url is `<BMS_PESAPAL_CALLBACK_BASE_URL>/api/v1/payments/callbacks/pesapal/<BMS_PESAPAL_IPN_TOKEN>`, registered as type
`GET` (the provider appends the three names as query parameters). The callback host must be reachable from the internet: the
staging tunnel must expose it (chapter 9 section 9.13). The proxy forwards only `/api/v1/payments/callbacks/*`,
`/api/v1/payments/return/*` and `/api/v1/channels/*` on that host. **Required before activation, not yet present:** an edge rate
limit on those paths and redaction of the path token from the proxy's access log (the application's own limit per address
and overall holds without the edge rule, and the IPN and the payer return have separate buckets).

1. Generate a token: 32 random characters of `[A-Za-z0-9]`. Put it in the environment file as `BMS_PESAPAL_IPN_TOKEN`.
2. Register the url with the provider (dashboard, or `PaymentGateway.registerIpn`, which refuses a non-public or `http` url
   itself because the provider accepts them, spaces its calls 10 seconds apart and waits out the provider's throttle of about
   one call in five seconds). Registering the same url and type again returns the same id, so repeating is safe.
3. Put the returned id in the environment file as `BMS_PESAPAL_IPN_ID`. One id belongs to one credential pair: never reuse
   it for the platform pair or another merchant.
4. Restart the API.

Until the first staging run proves it, the acknowledgement body (spec 3.6) and the arrival of a GET notification are
documented, not observed. On that run pay a sandbox order with a documented test card, watch the status become completed, and
check the notification arrived and the order booked.

## 4. Switching a business on

1. Set `BMS_PESAPAL_TENANT_ID` to the business's tenant id (from the platform console) and restart. Any other business
   switching on gets 409 `merchant_not_bound_to_tenant`; this is deliberate, so one lender's merchant account is never
   handed to another.
2. In the business, map the `gateway` payment method to a ledger account (Settings, payment methods). Until then 409
   `gateway_method_unmapped`.
3. A tenant administrator opens Settings, payments, sets the daily ceiling in minor units and switches collections on
   (`PUT /payments/settings`, `If-Match`). With no credentials on the host the answer is 409 `gateway_not_configured`; with no
   ceiling 409 `daily_ceiling_required` (it cannot be removed while on).
4. Collecting from a loan or a savings account needs the purpose handlers of slices #216 and #219; until they ship, only
   the test purpose of the dev and test profiles exists, so no real intent can be created.

Switching off is never refused and stops new intents at once; intents already created are still checked and booked.

## 5. How a payment moves, for the person on call

`created` (order not yet made) then `pending` (the payer has a link) then `paid` (the provider's status query said
completed, and its reference and currency are ours) then `booked`. Other ends: `failed` (the provider said so, or the order
could not be made), `cancelled`, `expired` (30 minutes unpaid; polling continues for 48 hours, and a completed payment still books
as late; an expired order stays payable on purpose), `needs_attention`, `reversed_pending_review`, `reversed`. The database guards every move.

* A notification only asks for a check; the poller (`core.payments.poll`, every 10 seconds, 5 intents a tenant and 40 a tick
  at most, fair across tenants) asks the provider. A lost notification costs a delay, not a payment.
* Nothing in a notification is read but the order id and our reference. Unknown references are answered like any other,
  counted in `payment_callback_counters` and kept (bounded) in `payment_unmatched_callbacks`.

## 6. Incidents

| Symptom | Check and action |
|---|---|
| Payers paid, intents stay `pending` | Provider down or breaker open (log `circuit_open`): nothing is lost, the poller catches up within its schedule. If the provider returned `invalid_order_tracking_id` or a credential error the intent shows no change and the counters `status_mismatch` or `anomaly` rise: check the credentials and which merchant account the order was made under |
| 401 or `provider_auth` failures | The secret was rotated or revoked. Fix the environment file, restart. The adapter backs off 30 seconds after a refused token |
| Intent `needs_attention` | Read `refusal_code`: `technical_failures` (five failed bookings: look at the API log for the handler's error, fix, then a person decides how to book it), `reference_mismatch`, `currency_mismatch`, `amount_invalid` (money moved and the evidence is odd: compare with the merchant dashboard before touching anything), or a handler refusal code such as `loan_closed` (allocate by hand; the unallocated receipt screen is slice #224). Never edit the row to push it forward |
| Intent `created` for long | The provider was unreachable when it was asked. The poller retries; after five rounds the intent ends `failed` with `creation_unknown`, and the cashier starts a new intent with a new reference |
| `reversed_pending_review` | The provider reports a reversal after booking. Nothing is reversed automatically; the checker request is slice #224. Reverse through the loan or savings reversal by hand after the accountant agrees |
| Flood on the callback path | Answered 429 by the application (separate buckets for the provider's notifications and the payer return page); unknown references make no outbound call. The edge rule is required before activation, see section 3 |
| Stop everything at once | Empty `BMS_PESAPAL_CONSUMER_KEY` (the adapter is off, intents stay or fail, nothing is sent), restart the API. Collections can also be switched off per business |

Alerts to wire (spec 9.2): notifications per hour, unknown references, `status_mismatch`, intents `paid` but not booked over 10
minutes, pending over 24 hours, provider 401 or 5xx rate, breaker open, reversals.
