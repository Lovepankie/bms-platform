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
