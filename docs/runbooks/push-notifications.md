# Push notifications and the installable app (ADR-050)

Off until both VAPID keys are set on the host. Names only here: values live in `/opt/bms/.env` on the host, never in git.

## Environment variables

| Name | Meaning |
|---|---|
| `BMS_PUSH_VAPID_PUBLIC_KEY` | The application server key: 65 byte P-256 point, unpadded base64url |
| `BMS_PUSH_VAPID_PRIVATE_KEY` | The matching 32 byte private scalar, unpadded base64url. Secret |
| `BMS_PUSH_SUBJECT` | A `mailto:` address or an `https:` URL the push services can reach |
| `BMS_PUSH_EXTRA_ENDPOINT_HOSTS` | Optional. More push service hosts, comma separated, beyond Chrome, Firefox, Safari and Windows |

Both compose files pass them to the API. A half-set or mismatched pair, or a subject that is neither form, stops the API at start with
a message that names the variable and never prints a value. `bms.push.ephemeral-keys` (dev profile and tests only) makes a pair at every
start and is refused when `BMS_ENVIRONMENT` is staging or production.

## Turn it on

1. On any machine with Node: `npx web-push generate-vapid-keys`. Copy the two values into `/opt/bms/.env` as the two key variables, and set
   `BMS_PUSH_SUBJECT`. Do not paste them anywhere else.
2. Restart the API. The log line `Web Push: on` confirms it; `GET /api/v1/notifications/push/config` answers `enabled: true`.
3. A person opens Notifications and app in the staff area and turns the switch on; the browser asks once.

## Rotate the keys

Changing the pair strands every subscription (the browser bound it to the old key). Each device resubscribes, without a new question,
the next time its owner opens the staff area. Do it outside working hours, tell the owners, and expect silence from a device nobody opens.

## Turn it off

Remove the key variables and restart. No row is queued, the channel stops, the switch says it is not switched on. Existing device rows
stay and work again when keys are set (with the old pair; a new pair needs the resubscribe above).

## When something is wrong

- No notification arrives: check the outbox failed list in the operator portal for `push` rows. `no_device` means the person has no
  device; `no_deliverable_device` means every device was rejected (a key mismatch after a rotation); a row that stays pending is being retried.
- A device is removed by itself when its push service says it is gone (404 or 410). A rejected device is kept.
- Logs carry the outbox id, counts and the push service's status only: never an endpoint, a key or the text.
- The service worker file `/push-sw.js` is served `no-cache`; if handlers look stale after a deploy, hard refresh the installed app once.

## The everyday notices (issue #412, ADR-057)

- A business chooses what it sends on Business set-up > Notifications for the business (`/staff/setup/notifications`): new orders,
  orders on their way and delivered, stock running low, credit payments to collect and the daily summary, all on until switched off,
  and the summary time (20:00 on the business's clock by default). The safeguard notices have no switch.
- The job `retail.daily-notices` runs on every quarter hour and does nothing while the keys are unset. Per retail tenant it prepares
  the credit payments notice between 08:00 and 12:00 and the summary at the chosen time (a quarter hour up to 23:30), once each a
  day; a summary missed while the server was down goes the next morning, before 12:00, as yesterday's: a row in `retail_notice_runs` (tenant, kind,
  day) records that the day's notice was prepared.
- Low stock is judged after every movement that takes stock out, by the item's level (`retail_stock_minimums`) or by the pace, and is
  told once per item, branch and day (the outbox key carries the day). Nobody hears it unless they hold `retail.stock.read` at that
  branch or are an administrator, and have a device.
- The turn-on card asks on a device at most three times; what was answered is in that browser's `localStorage` (`bms.push.ask`).
  Clearing the site's data shows the card again.
