# Runbook: an SMS incident (ADR-036)

Who: the platform operator on call. Use this when messages are not arriving, an operator alert says a cap is
reached, the outbox lists `unknown_outcome` rows, or sending must be stopped. Setup is in
`docs/runbooks/sms-setup.md`.

## Stop all sending now

Fastest: set `BMS_SMS_MODE=off` in `/opt/bms/.env` and restart the API. Rows stay `pending` (nothing is
lost; they expire after an hour for receipts). Do **not** use `sink` on production (the application refuses to
start). To stop one tenant only: `PUT /api/v1/platform/tenants/{tenant_id}/sms` with `"enabled": false`; rows
already queued for it are then refused (`tenant_sms_off`) when they come up.

When you turn sending back on from `off`, every SMS row queued in between is dropped (`backlog_dropped`) and
not sent, so nobody gets an hour-old receipt as a surprise. Tell the tenant.

## See what is happening

Operator portal, outbox status (`GET /api/v1/platform/outbox`): counts per status, `enabled_channels` (is `sms`
listed?), and the failed rows with tenant, category, masked recipient and `last_error`.

| `last_error` | Meaning | What to do |
|---|---|---|
| `unknown_outcome` | The request reached the provider and no usable answer came back (a timeout, a broken connection, an SSL error while reading, an HTTP 500, 502 or 504). The message may or may not have been sent. Never retried by the machine, because a retry could text twice | Look at the provider's dashboard for the number and the time. If it was not sent, a staff member may resend by hand (the operator "send again" is a deliberate duplicate risk, and only works while the row has not expired). The delivery log row says `unknown` |
| `provider_auth_failed` | The key was refused (the operator also gets one Telegram alert a day) | Check `BMS_SMS_AT_API_KEY` and the account; rotate if needed. Rows are retried, then fail |
| `provider_insufficient_balance` | The provider account has no credit (one Telegram alert a day) | Top up; rows are retried, then fail. Receipts expire after an hour, so some are lost |
| `provider_403`, `provider_406`, `provider_dnd` and similar (`provider_<code>`; `provider_dnd` is Do Not Disturb) | The provider refused this message for good (invalid or blocked number, unsupported number type, unregistered sender) | Not retried. Fix the number or the sender; the delivery row is `rejected` |
| `invalid_phone` | The number is not one we read without guessing | Fix the member's number |
| `tenant_inactive` | The tenant was suspended or closed after the row was queued | Nothing; resume the tenant if it was a mistake |
| `blocked_optout` | The member opted out | Nothing; it is logged on purpose |
| `tenant_sms_off` | The operator, or the tenant's own administrator, switched the tenant off after the row was queued | Nothing, or switch it on (a tenant's own switch is in its SMS settings; switching on drops the queued rows) |
| `backlog_dropped` | Sending was switched on and this row was older than that moment | Nothing |
| `expired` | A receipt older than an hour, or a reminder past its window day | Nothing; stale messages are not sent |

## "SMS stopped for a tenant" checklist

A tenant says its members get no text. Read `GET /api/v1/platform/tenants/{tenant_id}/sms` and then, in the order they bite:

1. **The operator's switch** (`enabled`) is off: nothing is queued at all. Switch it on.
2. **The tenant's own switch** is off (its administrator sees "Off" in the SMS settings, and the tenant was probably switched on
   by you before the tenant switch existed): no row is queued and a queued row is refused (`tenant_sms_off`). Ask the administrator
   to switch SMS on after reading the notice.
3. **A cap of 0**, theirs or yours, stops everything including receipts; a lowered cap reached holds reminders until tomorrow
   and receipts at twice the cap. The tenant's settings page shows both caps and the use today and this month.
4. **The window** holds reminders and arrears notices outside it; receipts are not held by it. Reminders and arrears notices
   are queued by the nightly arrears job (`lending.loan-arrears`, 00:15 tenant time, #227), so they appear in the outbox
   after midnight and wait for the window; the job's audit row (`lending.arrears.run`) counts `reminders_queued`.
5. **The sender id was withdrawn**: messages still go, from the shared sender.
6. **The member or the number is on the opt-out list** (`blocked_optout`): the business administrator lifts it from the member's page or the opt-out list, giving a reason; do it only when the person asked.

## A provider outage

Symptoms: many `provider_unreachable`, `provider_http_503` or `provider_timeout`. Clean failures
(`provider_unreachable`, 5xx answers) are retried at once, after 1 and after 5 minutes, then marked `failed`.
Timeouts are `unknown` and are not retried. Each message is cut off after 15 seconds, and one run of the SMS
sender stops after 4 minutes, so an outage cannot hold the sender for long. If the outage is long, set
`BMS_SMS_MODE=off` (above); receipts queued meanwhile will have expired, which is intended.

## A cap alert

A Telegram alert names the scope (`platform` or `tenant <id>`), the day and the cap. At 80 percent: look at the
use (`GET .../sms` shows `used_today`) and decide whether the cap should rise (`PUT`, platform operator only).
At the cap: reminders wait for tomorrow's window; receipts continue to twice a tenant's cap, then are held an
hour at a time until they expire; over the platform ceiling everything is held. Nothing is dropped early and
nothing over a cap is sent. Raising `BMS_SMS_PLATFORM_DAILY_CAP` needs a restart.

## Suspected leak of the key

Revoke the key at the provider first, then follow "Key rotation" in `docs/runbooks/sms-setup.md`. The key is
never in the repository, a log line or an error message; the application only ever prints whether it is set.

## Never do this

- Never point staging at the live provider to "test quickly": the application refuses to start, and the guard
  is there because an allow-list on the live URL is still a real send.
- Never put a real member's number in a test, a fixture or a ticket. Use the fabricated range `+2567000000NN`.
