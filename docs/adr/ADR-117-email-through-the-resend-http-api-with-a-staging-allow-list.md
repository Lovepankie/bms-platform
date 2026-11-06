# ADR-117: Email through the Resend HTTP API, with each business's name as the from-name and a staging allow-list

## Status

Accepted (issue #333; owner decision of 21 Oct 2026, recorded on the issue on 2 Nov 2026: the sender is
`no-reply@rincoltech.com` on Resend, `rincoltech.com` is verified there and its SPF, DKIM and DMARC records are live in
Cloudflare). Builds on ADR-036 (the outbox and its channels), ADR-054 (branded emails) and ADR-059 (email required in
production). Supersedes the 20 Oct plan on #333 to send through a shared Rincol mail service: the owner chose Resend
directly. No migration. Written as ADR-115 on pull request #759 and renumbered 117 when main took ADR-115 (the
savings setting, #768) and ADR-116 (offline reading, #763) first.

## Context

The outbox delivers email only through `SmtpEmailChannel`, which needs `BMS_SMTP_*` on the host. Staging already sends
through Resend's SMTP endpoint on port 465 with a send-only, domain-scoped key, because port 587 is blocked there. An SMTP
connection per email is slow, its errors say little, and SMTP has no idempotency: a lost answer after the message was
accepted means a second copy on the retry. Resend's HTTP API takes the same message in one request, accepts an
idempotency key, and tags the message so a later event can name the outbox row.

Every business's staff emails (password reset, password changed, contact changed and the rest) went out under the
platform's display name, so a reader could not tell which business wrote. Staging sends to whatever address a tester
types, which can be a real person's.

## Decision

1. **Two senders behind one channel, chosen on the host.** `BMS_MAIL_PROVIDER` is `smtp` (the default when unset) or
   `resend`. Any other value stops the start, so a typo never leaves email quietly off. Both implement the outbox channel
   `email`; only the chosen one is ever on. The dispatcher keeps the senders that are on before it picks one by channel
   name, so the order of the two beans never decides where a row goes (`OutboxIT` sends a row through the dispatcher
   with SMTP listed first and off). SMTP is unchanged and stays a full choice. **Fallback:** with `resend` chosen and the
   `BMS_SMTP_*` values still on the host, an email whose request could not reach Resend at all goes by SMTP at once.
   "Could not reach" is `resend_unreachable` and nothing else: the connection was refused, had no route or no name
   (DNS), timed out while connecting, or failed its TLS handshake, all before a byte of the request left, so Resend cannot
   have sent it. Every other outcome means a request may have reached Resend and never falls back, because SMTP has no
   idempotency key and the reader could get two copies: any answer (a `5xx`, which is how an unavailable Resend
   answers, a `429`, a `4xx`), a read timeout after the request was sent (`resend_timeout`) and a connection lost once
   made (`resend_connection_lost`). Those are retried under the idempotency key at 1 and 5 minutes and then show as failed
   on "Messages not sent" for "Send again". So a Resend outage that answers errors for longer than about six minutes
   leaves its rows failed there; an outage that refuses connections goes by SMTP. The outbox row only says `sent`, so
   which sender carried a fallback is recorded beside it: the sender's result names SMTP, the dispatcher's log line says
   `via=smtp`, and one `platform_audit_log` row `platform.outbox.sent_via` holds the row id, the channel and `smtp`
   (no address, no text), written through the `PlatformAuditLog` port (so its masking, request id and client address
   apply; issue #777) in the transaction that marks the row sent.
2. **Resend over HTTP.** `ResendEmailChannel` sends one `POST /emails` to `BMS_RESEND_API_URL` (default
   `https://api.resend.com`; anything but `https`, apart from the loopback a test uses, stops the start, so the key never
   travels in clear) with `Authorization: Bearer <BMS_RESEND_API_KEY>`. The message is the one the SMTP sender builds:
   the same plain and HTML parts (ADR-054), the same attachment (ADR-074), the same From. The key is read from the host
   env only, put there by the operator, never committed and never logged (`toString` hides it, and `LogRedaction` masks
   a `BMS_RESEND_API_KEY=` value as it masks any `api_key=`, proven in `LogRedactionTest`).
3. **The outbox key, under the environment, is Resend's idempotency key.** `Idempotency-Key` is
   `bms:<BMS_ENVIRONMENT>:<outbox row id>:<row key>`: staging and production share the Resend account, so they never
   share a key, and no two rows do. Resend
   keeps a key for 24 hours, far longer than the outbox's three automatic tries, so those tries are never a second email.
   An operator's "Send again" after 24 hours (it is allowed up to 7 days) is a new request to Resend: if an earlier try
   was in fact delivered without an answer, the reader gets it twice. The answers:
   2xx sent; `400` and `422` refused (`resend_rejected`); `413` refused (`resend_too_large`, the weekly workbook over
   Resend's attachment limit); `429` gives the attempt back and waits as long as `Retry-After` says (half a minute when
   it says nothing); a `409` naming `invalid_idempotent_request` (the same key with another body: an earlier try reached
   Resend, then the business's name changed) is refused (`resend_payload_changed`), never retried, since the first may
   have been delivered; a timeout (`resend_timeout`), a lost connection, `401`, `403`, a `409` of two requests at once and `5xx` are
   retried. The row id and the template key are sent as tags (`outbox_id`, `template`); the id Resend gives the email is
   logged with the row id. No recipient, text or answer body is logged or stored.
4. **Each business's name is the from-name.** A row of a business is sent from the address of `BMS_MAIL_FROM` with that
   business's name as the display name: one line, without quotes, backslashes, control or format characters, at most 70
   characters counted by code point (an emoji is never cut in half). A platform row (the operator's alerts, an
   applicant's confirmation) keeps `BMS_MAIL_FROM` as set. A name that cannot be read (a suspended business) falls back to
   `BMS_MAIL_FROM`, and if Resend still refuses the From of a business (`422` about `from`) the email is sent once more
   with `BMS_MAIL_FROM` under a key of its own, so no business loses its reset emails over its name. The address is always
   Rincol's verified sender, so SPF, DKIM and DMARC pass for every business. Over SMTP the name is RFC 2047 encoded.
5. **Staging sends only to the allow-list.** With `BMS_ENVIRONMENT=staging`, or on any host where `BMS_MAIL_ALLOWLIST` is
   set whatever `BMS_ENVIRONMENT` says (so an unset or misspelled environment never lifts it), both senders send only to
   recipients on the list: comma separated, each an address or `@domain` for every address at that domain, letter case
   ignored. Any other row is refused with `not_on_staging_allowlist` and never sent anywhere. An empty list on staging
   sends nothing, and the API logs a warning saying so at every start; it does not refuse to start, which would take all
   of staging down for an email setting. A host that is not staging and has no list sends to anyone; production leaves the list unset.
6. **Production needs the chosen sender.** The ADR-059 startup guard follows the provider: with `resend` it needs
   `BMS_RESEND_API_KEY` and `BMS_MAIL_FROM` (an address), naming only the unset variables.
7. **The wording of the invitation, activation and reset emails was reviewed and kept.** Staff invitations are not
   emailed (the inviting administrator hands over the one-time link, increment 1). The activation emails
   (`onboarding.activation`, `onboarding.activation_two_step`) and the reset emails (`identity.password_reset`,
   `identity.password_reset_issued`, `identity.password_changed`) are already plain, carry no dashes, say why the reader
   got them and what to do if it was not them; the short link sentence is fixed by ADR-108. With the business's name now
   the from-name, the "Rincol Tech" sign-off stays: the platform sends on the business's behalf.
8. **Bounces and complaints are recorded in the platform audit log, not on the outbox row.** Resend calls
   `POST /api/v1/platform/webhooks/resend` (public, hidden from the OpenAPI document) for each event. The body must carry
   a Svix signature made with `BMS_RESEND_WEBHOOK_SECRET` over the message id, its timestamp and the exact body, and the
   timestamp must be within five minutes of now; anything else gets 401 and writes nothing, and with the secret unset the
   route answers 404. `email.bounced`, `email.complained` and `email.suppressed` (Resend did not send a later email
   because the address is on its suppression list) become one `platform_audit_log` row each (`platform.email.bounced`,
   `platform.email.complained`, `platform.email.suppressed`), holding the outbox row id and the business from the tags the
   sender puts on every email (`outbox_id`, `tenant_id`, `template`), the recipient's domain only (never the address), the
   bounce type and the message id. Every email sent through the Resend API also carries an `env` tag (the environment,
   as in the idempotency key):
   staging and production share the Resend account and Resend sends every event to every endpoint, so an event whose
   `env` is another environment's, or missing, is answered 200, logged at debug and not recorded. A repeated message id writes nothing
   more: the check takes a transaction advisory lock on the id and searches only the last seven days. Other event types
   are acknowledged and ignored. The operator's "Messages not sent" page lists the latest 100 (`GET /platform/outbox`, `bounces`).
   **No migration and no new column:** the application role already inserts and reads that log. Writing the bounce on
   the outbox row itself would need one new `SECURITY DEFINER` function (the application role cannot update
   `notification_outbox`, and `notification_outbox_record_result` only touches rows still `pending`), which is a Flyway
   migration. The rule against a migration in this change came from the lead's brief for issue #333, not from this ADR.
   That function, marking the bounced row itself `failed` with `last_error` `bounced` or `complained`, is issue #767; it
   reuses this route and its signature check. Stopping later sends needs no migration and is decision 9.
   **Email that goes by SMTP is not tracked or suppressed.** Resend's SMTP documentation names the headers it reads
   (`Resend-Idempotency-Key`, and custom headers such as `X-Entity-Ref-ID` and `List-Unsubscribe`) and no header that
   carries tags, so BMS does not invent one. An email sent over SMTP (the `smtp` provider, also when its host is
   Resend's SMTP endpoint, and the fallback of decision 1) therefore has no `env`, `outbox_id` or `tenant_id` tag. Its
   bounce, complaint or suppressed event arrives untagged and is dropped as another environment's is: nothing is
   recorded, no `recipient_hash` is written, so BMS does not suppress the address, and a later email to it can read
   `sent` while Resend's own suppression list drops it. The check of decision 9 still runs before every SMTP send, so an
   address already suppressed after an email sent through the API is refused over SMTP too. `EmailLayoutTest` pins
   that an SMTP message carries no tag header, and `ResendWebhookIT` that an untagged hard bounce records nothing and
   suppresses nothing. A host that needs bounces tracked chooses `resend`.
9. **BMS stops sending to an address that hard-bounced, complained or that Resend suppressed, and an operator can lift
   it.** Without this, Resend's account-level suppression list drops every later email to such an address while the
   outbox row reads `sent`, so a staff member whose address bounced once never gets a reset and nobody sees why. The
   webhook rows of a hard bounce (`bounce.type` `Permanent`), a complaint and an `email.suppressed` event also hold
   `recipient_hash`: HMAC-SHA256 of the address, stripped and in lower case, under a key derived from the existing data
   key (`BMS_DATA_KEY`) with its own purpose label (`bms.email.recipient-hash.v1`), as ADR-036 hashes SMS numbers. The
   address itself is never stored, and only a holder of the data key can test one. The hash key is never random: a
   blank `BMS_DATA_KEY` stops the start outside the dev and test profiles (`EmailSuppressionsTest`), so a restart can
   never silently forget every suppression, and dev and test use one fixed key of their own. The check exists whatever
   the provider is, so every host needs `BMS_DATA_KEY`, even one that sends by SMTP only (issue #777; a test binds the
   real `application.yml` mapping, and the runbook says so). A soft bounce carries no hash and
   suppresses nothing. Before sending, both senders (`SmtpEmailChannel` and `ResendEmailChannel`, through one shared
   check after the staging allow-list) read the newest platform audit row carrying that hash among
   `platform.email.bounced`, `platform.email.complained`, `platform.email.suppressed` and `platform.email.unsuppressed`;
   when it is one of the first three the row is refused with `recipient_suppressed`, so it shows as failed on "Messages
   not sent" and never reaches Resend. An operator clears it with `POST /api/v1/platform/outbox/unsuppress` (body
   `{email}`, `platform.tenants.manage`), which records `platform.email.unsuppressed` with the hash and the operator
   (409 `email_not_suppressed`, nothing written, when the address was not suppressed). A later hard bounce or complaint
   suppresses it again. The newest row is the one written last: a platform audit row carries `clock_timestamp()`, the
   time of its own write, not of its transaction's start, so a bounce whose transaction waited while a clear committed
   is still the newer (issue #777). No migration and no new column: the same audit log, read by the same role. The check is one
   query per email on a log that holds operator events only, small enough without an index; an index on the hash would
   be a migration. A rotation of `BMS_DATA_KEY` changes the hash, so suppressions recorded before it stop matching (as
   SMS opt-outs do under ADR-036); Resend's own list still drops those sends until the next bounce records them again.

## Consequences

**Better:** staging and production can send without SMTP, through the verified `rincoltech.com` sender. An automatic
retry after a lost answer cannot send a second copy. Readers see which business wrote. Staging can no longer email a real
person by accident. The operator sees which emails bounced or were marked as unwanted, by domain, without a migration.
A later email to an address that bounced hard or complained shows as not sent (`recipient_suppressed`) instead of a
silent `sent`.

**Worse:** two senders to keep working instead of one. On staging, a tester whose address is not on the list gets nothing,
and the operator has to add it. The bounced email itself is beside its outbox row, not on it: that row still reads
`sent`, and the operator matches the two by the row id (#767). A fixed mailbox needs the operator's clear before BMS sends
to it again, and Resend's own suppression list needs clearing in Resend too. Email sent by SMTP, including every
fallback, is neither tracked nor suppressed (decision 8). SMTP has no idempotency key: when a fallback's SMTP server
accepted the message and the connection then failed, the row is retried and may go out again through Resend, so the
reader gets it twice, as with SMTP alone (decision 1).

**Watch for:** setting `BMS_MAIL_ALLOWLIST` on staging before this release reaches it (an empty list sends nothing, the
rows show as failed with `not_on_staging_allowlist`); setting `BMS_RESEND_WEBHOOK_SECRET` and adding the webhook in
Resend on each host; a business name a mail client shows oddly; Resend's limits on attachment size for the weekly
workbook (ADR-074); the bounce on the outbox row itself (issue #767, a migration); the cost of the suppression check if
the platform audit log ever grows large, timed per email as `bms.email.suppression.check` (issue #777): a time that
keeps rising is the cue for an index on the hash, which is a migration.

## Amendment of 2026-11-06 (issue #767): the bounce on the outbox row itself, and 503 while the secret is unset

Decision 8 left the bounced row reading `sent`, with the bounce beside it in the platform audit log. Migration `V138`
adds the one function decision 8 named, `notification_outbox_mark_undelivered`, and no column. After the webhook has
checked the signature, the replay window and the `env` tag, and written the audit row as before, a bounce
(`email.bounced`, hard or soft) or a complaint (`email.complained`) moves its outbox row from `sent` to `failed` with
`last_error` `bounced` or `complained` (`failed_at` set, `sent_at` kept), so it shows on "Messages not sent" with the
other failures. The row is found by the `outbox_id` and `tenant_id` tags together (a platform row only by a null
tenant), so an event whose tags name one business can never mark another business's row; a row that is not `sent`
keeps its first outcome. The function is a platform step: it refuses a transaction bound to a business, as
`sms_delivery_report_apply` does. An `email.suppressed` event marks no row: the email it names is a later one Resend
refused, and the suppression of decision 9 already refuses such sends before they leave. Stopping later sends after a
complaint is decision 9, unchanged.

With `BMS_RESEND_WEBHOOK_SECRET` unset, or not a `whsec_` secret, the route now answers 503 `webhook_not_configured`
instead of 404, and logs one warning the first time it is called (not one per retry). It still never accepts an unsigned
event; a 503 makes Resend keep retrying, so events sent before the operator sets the secret are not lost if it is set
within Resend's retry period. The body is still read as a JSON tree rather than a strict request type, because Resend's
events carry fields BMS does not map and the API refuses unknown properties on its own request types.
