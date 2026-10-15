# Runbook: set up SMS (ADR-036)

Who: the platform operator, with access to the host's `/opt/bms/.env`. When: before the first tenant
gets receipts by SMS, when a provider account or key changes, and when a tenant is switched on.
Spec: `docs/specs/client-extras.md` section 4; decisions: ADR-036. Related: `docs/runbooks/sms-incident.md`.

SMS is a channel of the notification outbox. Nothing is sent unless **three** things are all true: the
environment's mode sends, the provider is configured, and the tenant has been switched on by the
operator. Every one defaults to off.

## What each environment may do

| `BMS_ENVIRONMENT` | `BMS_SMS_MODE` unset means | Allowed modes | Provider URL and account |
|---|---|---|---|
| `staging` (and anything that is not `production`, including unset) | `sink` | `off`, `sink`, `allowlist` | Only the Africa's Talking **sandbox**: `https://api.sandbox.africastalking.com` and the account `sandbox`. Anything else stops the application from starting, in every mode, even `off` |
| `production` | `off` | `off`, `allowlist` (a pilot check), `live` | The live host; the sandbox host or account is refused when the mode can send. `sink` and the fake provider are refused |

The modes: `off` leaves every row `pending`; `sink` writes the delivery row as `sunk` and sends nothing
(the only way staging runs the channel); `allowlist` sends only to the numbers in `BMS_SMS_ALLOWLIST`
and sinks the rest; `live` sends to everyone (production only). The startup guard is in
`SmsSettings.resolve` and has a test for each refusal (`SmsSettingsTest`).

## Staging

1. In `/opt/bms/.env` on the staging host leave `BMS_SMS_MODE` empty (it is the sink). `compose.pi-staging.yml`
   fixes `BMS_ENVIRONMENT` to `staging`, so a wrong value in `.env` cannot lift the guard.
2. Switch SMS on for the staging demo tenant (step "Switch a tenant on" below). A savings deposit now writes a
   `sms_deliveries` row with status `sunk` and sends nothing; check it:
   `SELECT status, provider, segments FROM sms_deliveries ORDER BY created_at DESC LIMIT 5;` as `bms_owner`.
3. To exercise the real provider code against the sandbox, set `BMS_SMS_PROVIDER=africastalking`,
   `BMS_SMS_AT_BASE_URL=https://api.sandbox.africastalking.com`, `BMS_SMS_AT_USERNAME=sandbox`,
   `BMS_SMS_AT_API_KEY=<the sandbox key>`, `BMS_SMS_MODE=allowlist` and `BMS_SMS_ALLOWLIST=<your test numbers>`.
   The sandbox does not deliver to real phones. Restart the API; the log line `SMS channel: environment=staging mode=allowlist ...`
   confirms what was read (it never prints the key).

## Production

1. Create the provider account and API key with Africa's Talking. Put `BMS_SMS_PROVIDER=africastalking`,
   `BMS_SMS_AT_USERNAME`, `BMS_SMS_AT_API_KEY` in `/opt/bms/.env` (mode 600). Leave `BMS_SMS_AT_BASE_URL` empty:
   production defaults to the live host.
2. Pilot check first: `BMS_SMS_MODE=allowlist` with `BMS_SMS_ALLOWLIST=` the numbers of the people running the
   check. Switch one tenant on, make a deposit, confirm exactly those numbers receive it and the delivery row
   shows `sent` with a cost.
3. Go live: `BMS_SMS_MODE=live`, restart. **Moving from `off` or `sink` to a sending mode drops every SMS row
   still queued** (status `failed`, `backlog_dropped`), so switching on never releases old receipts. That is
   by design; tell the tenant.
4. Set `BMS_SMS_PLATFORM_DAILY_CAP` (default 2000 a business day, all tenants). Rows over it are held, not lost.
5. Sender name: an alphanumeric sender id needs registration with the provider and the networks and takes
   weeks; until then the provider's shared sender is used. Once registered, approve it for the tenant (next
   section, "Approve a sender id").

## Switch a tenant on, and set its cap (operator only)

SMS is off for a tenant until the operator turns it on; the tenant admin cannot, and cannot raise the cap
(no endpoint, no table grant, and the database function refuses a tenant transaction).

```
PUT /api/v1/platform/tenants/{tenant_id}/sms        (platform console, permission platform.tenants.manage)
{ "enabled": true, "daily_cap": 500 }
GET /api/v1/platform/tenants/{tenant_id}/sms        (platform.tenants.read)  -> enabled, daily_cap, used_today, platform_daily_cap, mode
```

While a tenant is off, no SMS row is written for it at all. Every change is in the platform audit log
(`platform.sms.controls_changed`, with before and after).

How the cap behaves (a business day is Africa/Kampala): reminders and arrears notices over the cap wait for
the next day's window; receipts and payment links may go to twice the cap; beyond that, or over the platform
ceiling, a row is held and retried every hour until it expires (one hour for a receipt). Nothing over a cap
is sent. The operator gets a Telegram alert at 80 percent and when a cap is reached (once a day per scope).

## Approve a sender id (operator only)

A tenant administrator cannot type a sender name: it can only choose one you approved for that tenant, so register
the name with the provider and the networks first (3 to 11 ASCII letters and digits, at least one letter, no
spaces). An id belongs to one tenant at a time, compared without case; a second tenant asking for it gets
409 `sender_id_taken`.

```
PUT    /api/v1/platform/tenants/{tenant_id}/sms/sender-ids/{sender_id}   (platform.tenants.manage)  approve
DELETE /api/v1/platform/tenants/{tenant_id}/sms/sender-ids/{sender_id}   (platform.tenants.manage)  withdraw
GET    /api/v1/platform/tenants/{tenant_id}/sms/sender-ids               (platform.tenants.read)    list
```

Both changes are in the platform audit log (`platform.sms.sender_id_approved`, `platform.sms.sender_id_withdrawn`).
Withdrawing takes effect on the next message: it goes from the shared sender, the tenant's settings keep the name and
show it as withdrawn, and nothing needs editing. Nothing about the name reaches the provider except the `from` of the
message, and only while the id is approved.

## What a tenant admin can change

Staff area, SMS (`/staff/sms`; the tenant administrator, who holds `core.settings.manage`):

- **The tenant's own switch.** SMS needs this switch as well as yours, and it is off for every tenant. The
  administrator reads a notice (every message costs money, it goes only to a member's own phone, the platform caps the
  volume) and ticks that they accept it; the version of the notice, who and when are recorded. **A tenant you switched
  on before this release is off until its administrator does this once**, so tell them. Switching on drops that
  tenant's queued SMS rows (`backlog_dropped`).
- **Sender name**, from the approved list above, or the shared sender.
- **Sending window**, Africa/Kampala, at least 60 minutes inside 06:00 to 21:00 (default 08:00 to 20:00). It applies to
  reminders and arrears notices, never to receipts. The limits cannot be set aside.
- **Daily and monthly caps**, which they can lower under your cap (and 31 times it for a month), never raise. **0 stops
  every message**, receipts included; an empty box means no cap of their own. The lower of their cap and yours applies on
  every message.
- **Balance in savings receipts** (`include_balance`, default off): whether the default receipt carries the account
  balance. Off by default because on a phone a household shares, a balance tells everyone what a member holds. Wording a
  tenant writes itself never carries a balance.
- **Wording** (`/staff/sms/wording`): their own words per event from a fixed list of placeholders, previewed with sample
  data (encoding, segments, a cost estimate from their own last 30 days). A **test message** goes only to the sink or to
  a number on `BMS_SMS_ALLOWLIST`: in `live` mode none is sent (the preview is the test), in `off` none, in `sink` it is
  recorded, in `allowlist` only a listed number gets it, and only while SMS is on for the platform and the business, the number
  is not on the opt-out list and the caps are not reached; such a test is written to the delivery log and counts against the caps.
- **Opt-outs** (`/staff/sms/opt-outs`, and on a member's own page): numbers shown masked, add one, paste up to 2000
  (checked first), lift one. Lifting is the business administrator's: it asks for a reason (3 to 200 characters, kept in the
  audit row) and a confirmation, and so does narrowing a member's "every message" to "reminders"; other staff can only stop
  more. A member's opt-out follows the member to any number; a number added here blocks that number
  whoever the message is about.

The old flat settings `sms_sender_name`, `sms_window_start`, `sms_window_end` and `sms_include_balance` of
`PATCH /api/v1/settings` are gone (400); migration `V52` copied a tenant's window and balance values into the group once.

## Numbers

A message goes only to a number we can read without guessing: `+256 7XX XXX XXX`, `2567XXXXXXXX` or
`07XXXXXXXX`. A bare 10 digit foreign number, a number of another country, anything with stray digits is
refused (`invalid_phone`) and never reaches the provider. Fix the member's number rather than the check.

## Reading the log

`sms_deliveries` holds one row per message: masked recipient, a keyed hash (for the opt-out), segments, the
provider's cost parsed to minor units with the raw string, status and reason. Statuses: `queued` (in flight),
`sent`, `delivered`, `undelivered` (the last two come with the delivery report slice, #215), `failed` (a clean
failure, retried at once, after 1 and after 5 minutes), `rejected` (the provider or we said no for good),
`blocked_optout`, `sunk`, `unknown`. The log is kept 13 months. Numbers and message texts are not in it and not
in any log line.

## Key rotation

Yearly, on staff change, and after any suspected exposure: create a new key with the provider, replace
`BMS_SMS_AT_API_KEY` in `/opt/bms/.env`, restart the API (the adapter reads it at start), send one test message
in `allowlist` mode, then revoke the old key. A rotated-out key shows as `provider_auth_failed` in the outbox
(clean failures, retried, then `failed`).
