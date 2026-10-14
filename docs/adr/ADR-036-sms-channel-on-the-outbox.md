# ADR-036: SMS as a channel of the notification outbox: a provider adapter (Africa's Talking first), a tenant delivery log, sending windows, opt-out, and a sink everywhere except production

## Status

Accepted. Designed in issue #210 (spec `docs/specs/client-extras.md` section 4); the channel core, decisions 1 to 6
and 8 to 14 except the tenant screens, is built by issue #212 (see the Amendment below). Delivery reports (decision 7)
come with #215, the tenant settings screens, opt-out screens and template overrides with #218, reminders with #227.
Settles the SMS half of what chapter 12 section 12.3.3 carried as pending ADR-013; USSD remains pending ADR-013.
Builds on ADR-008 (jobs), ADR-016 (definer functions), ADR-024 (the outbox), ADR-032 (savings
receipts queued as SMS that expire unsent). Implements FR-NTF-02 to FR-NTF-07 and the SMS events of
chapter 11 section 11.3. Migrations are numbered when each build slice merges.

## Context

The outbox accepts the channel `sms` since migration `V30`, and savings receipts already queue rows
that no sender delivers. The product owner wants a real SMS provider, first Africa's Talking, with
delivery reports and per tenant sender names, and wants the cost of each message recorded. The
existing outbox is a **platform** table: no `tenant_id`, no row-level security policy, reached only
through definer functions, parameters cleared after sending (ADR-024). Per tenant behaviour
(sender name, sending window, opt-out, templates, cost, delivery log) therefore needs a tenant link
and a tenant-scoped log. The provider's bulk API returns per recipient a status, a message id and a
cost, and posts delivery reports to a callback; it offers no idempotency key, and the public
reference documents no signature on the delivery report (to be confirmed in the sandbox).
Staging must never text a real person.

## Decision

1. **SMS stays a channel of the one outbox.** `SmsOutboxChannel` is a third `OutboxChannel` beside
   email and Telegram, behind a new `SmsProvider` port (`send`, `parse_delivery_report`; chapter 12
   section 12.3.1). `AfricasTalkingSmsProvider` is the first adapter; a recording fake serves tests.
   No second queue is created.
2. **The outbox row learns its tenant.** An additive migration adds `tenant_id uuid` (nullable;
   platform rows keep null) and a `category` (`transactional`, `reminder`, `arrears`) to
   `notification_outbox` and a new overload of `notification_outbox_enqueue` that takes them. The
   enqueue is taken from the caller's bound tenant, never from a parameter (standing rule 1). The
   sender uses the stored tenant to bind a short tenant transaction (`TenantJobs`) for the steps
   that need tenant data: reading settings, the opt-out, the template override and the member's
   phone check, and later writing the delivery log. Claiming and recording remain platform steps.
3. **A tenant-scoped delivery log, `sms_deliveries`** (row-level security, append-mostly): outbox id,
   template key, category, subject (member or account id), recipient masked to its last four digits
   plus a keyed hash for opt-out matching, provider, provider message id, segments counted by us and
   by the provider when it says, `cost_minor` and `currency` parsed from the provider's cost string
   (kept raw too), status (`queued`, `sent`, `delivered`, `undelivered`, `failed`, `rejected`,
   `blocked_optout`, `sunk`, `unknown`), failure reason, and timestamps. It feeds `core.sms_usage`
   (FR-NTF-07). Retention 13 months unless the retention decision (NFR-DP-06) says otherwise.
4. **Modes make a real send impossible by accident.** `BMS_SMS_MODE` is `off` (default; rows stay
   `pending`), `sink` (the log row is written with status `sunk`, no network call), `allowlist` (real
   send only to numbers in `BMS_SMS_ALLOWLIST`, the rest sunk) or `live`. Two rules hold in every
   mode, because an allow-list on the live provider URL is still a real send on staging: **when
   `BMS_ENVIRONMENT` is `staging`, startup refuses any `BMS_SMS_AT_BASE_URL` other than the provider's
   sandbox host and any `BMS_SMS_AT_USERNAME` other than the sandbox account**, and refuses `live`;
   `allowlist` against the live host is possible only in production, for a pilot check. The production
   profile refuses `sink` and the fake provider. Staging defaults to `sink`. A startup test covers
   each refusal. The allow-list is an environment value, never in the repository.

5. **Sending window, priority and deferral.** Categories `reminder` and `arrears` are sent only inside
   the tenant's window (default 08:00 to 20:00 Africa/Kampala, FR-NTF-04); `transactional` (receipts,
   payment links) are not held. The enqueue sets `next_attempt_at` to the next window start, and the
   channel checks again at send time and returns `Deferred(until)`. Because 08:00 would otherwise put
   a thousand reminders ahead of a receipt, the claim function orders **transactional rows first**,
   then by due time, and takes rows **round-robin across tenants** (rank within tenant) so one
   tenant's reminders cannot starve another's receipts.

6. **Outcomes, retries and the case a retry would text twice.** The outbox claim counts an attempt
   before the send and leases the row for 2 minutes (`MAX_ATTEMPTS` 3, backoff 1 and 5 minutes,
   ADR-024). SMS keeps those numbers: FR-NTF-05's third retry step of 30 minutes is dropped (the
   requirement is amended), because a longer step needs a new attempt model that has no value for a
   message that goes stale within the hour (decision 14). `OutboxChannel.send` returns a result
   instead of only throwing: `Sent`, `Deferred(until)` and `Unknown`, with a thrown `SendFailure`
   still meaning a clean failure. A new definer function `notification_outbox_record_result`
   records them: `Deferred` gives the attempt back and sets `next_attempt_at`; `Unknown` marks the row
   `failed` with `last_error = 'unknown_outcome'`, never retried automatically. The provider has no
   idempotency key, so a read timeout after the request was written is ambiguous and is `Unknown`.
   The SMS client's total time per message is capped at 15 seconds, far inside the lease (a test
   asserts the cap is under a quarter of the lease), so a slow provider cannot hand a second sender
   the same row.

7. **Delivery reports** arrive on the callback host at
   `/api/v1/channels/sms/africastalking/delivery-reports/<token>`, with a long random token in the
   path, a proxy allow-list of the provider's published source addresses where they exist, and a
   rate limit. The handler finds the log row by provider message id through a definer function,
   moves status forward only (`sent` to `delivered` or `undelivered`, never back), and ignores
   unknown ids. A forged report can at worst change a log status; it never touches money.
8. **Opt-out.** A member can be opted out of `reminder` and `arrears` messages, or of all SMS except
   those the law or a transaction requires, by staff on the member screen (source `staff`) or on the
   member's own request in a later slice. Matching is by member and by the keyed hash of the number,
   so a number reused by another member is not blocked wrongly. An opted-out message is logged
   `blocked_optout` and never sent. Inbound STOP keywords need a short code or inbound number and
   are a later slice. Messages go to the member's primary phone only, never next of kin (scope open
   question 9).
9. **Templates.** Defaults stay in code (the savings texts today). The tenant override table of
   FR-NTF-03 holds text with named placeholders, validated at save, rendered at send in the tenant
   transaction. Every default is tested to fit one GSM 7 segment with fabricated data or to declare
   two. A segment counter (GSM 7 with extension characters, UCS-2 fallback) is a pure, golden tested
   class. Messages carry no balance for reminders, no loan purpose, no next of kin, and no
   collection threats.
10. **Sender id.** A per tenant `sms_sender_name` (3 to 11 alphanumeric characters) with an
    `approved` flag set by the operator after the provider and the networks accept it; until
    approved the shared sender is used. Sender names are operator configuration, not repository
    content.
11. **Credentials** are environment values: `BMS_SMS_PROVIDER` (`africastalking` or `fake`),
    `BMS_SMS_AT_USERNAME`, `BMS_SMS_AT_API_KEY`, `BMS_SMS_AT_BASE_URL`, `BMS_SMS_CALLBACK_TOKEN`. A
    sender with unset values is off, as email is (FR-NTF-10). Per tenant provider accounts are a
    later option.
12. **Out of scope:** one time codes (FR-NTF-02 portal codes), USSD, inbound SMS, WhatsApp.

13. **Cost limits are set by the operator, not the tenant.** A platform-wide daily ceiling
    (`BMS_SMS_PLATFORM_DAILY_CAP`) and a per tenant daily cap that only the platform operator can
    change (a platform console setting; the tenant admin sees it read only). At 80 percent an alert is
    raised. Over the tenant cap, `reminder` and `arrears` rows are deferred to the next day's window,
    and `transactional` rows continue up to twice the cap; beyond that, or over the platform ceiling,
    rows are held `pending` (deferred an hour at a time) with an operator alert until they expire.
    Nothing over a cap is silently dropped before it expires, and nothing over a cap is sent.
14. **Content and staleness.** A transactional SMS expires one hour after it was queued and a reminder
    at the end of its window day (a receipt arriving the next day is worse than none). When the
    channel moves from a non-sending mode (`off`, `sink`) to a sending one, rows queued before that
    moment are marked `failed` with `backlog_dropped` and not sent, so switching SMS on never
    releases up to two days of old savings receipts. Savings receipts today carry the balance; on a
    shared phone that tells a household what a member holds, so the default savings receipt omits the
    balance and a tenant setting `sms_include_balance` (default off, tenant admin) puts it back.
    Reminders never carry a balance.

## Consequences

**Better:**

- One queue, one dispatcher, one retry model; email, Telegram and SMS share tests and monitoring.
- Per tenant cost, sender name, window and opt-out are possible without making the whole outbox
  tenant scoped.
- Staging cannot text a real person: the default is a sink, `live` is refused, and a network send
  needs a sandbox account or an allow-list.
- Cost per message is recorded from the provider's answer, giving the tenant bill input and the
  `core.sms_usage` report.

**Worse:**

- The outbox gains a nullable tenant column in a table designed as tenant-free; the definer
  functions and the purge must be changed with care and covered by the isolation tests.
- Rendering at send time inside a tenant transaction adds one short transaction per message.
- An `unknown` outcome can leave a message that was or was not sent; the operator sees it, and a
  duplicate is possible only when a staff member resends by hand.

**Watch for:**

- The delivery report fields and any signature scheme are confirmed against the sandbox in the first
  slice; if the provider offers signing, the path token becomes a second factor, not the only one.
- Phone numbers sit in the platform outbox until sent and for the purge period (30 days after
  sending); the purge job and the 7 day cap on unsent rows already bound it (ADR-024), and SMS
  parameters are cleared once sent.
- Reminders depend on the arrears job of increment 6 (#109); the channel can ship without them.
- The outbox claim and record functions change (priority, fairness, result types): the existing email and
  Telegram tests must stay green, and the isolation tests cover the new column.
- Alphanumeric sender registration time in Uganda is outside our control; do not promise a date.

## Amendment (issue #212, the channel core)

Building the slice changed or sharpened these decisions; the numbers refer to the Decision section above.

- **Decision 6, a fourth outcome.** `OutboxChannel.send` returns `Sent`, `Deferred(until)`, `Unknown` or `Refused(reason)`. A
  refusal is a message the channel will never send (an opt-out, SMS switched off for the tenant, a number it will not
  text, a provider's final no): the row is marked `failed` with the reason code, its parameters are cleared and it is not
  retried. Without it those cases would have been retried three times as clean failures. The new definer function
  `notification_outbox_record_result` records all four, and a clean failure; the older `notification_outbox_record`
  stays for the rollout. A deferral that names a time already gone is moved a minute on, so it cannot be claimed again at
  once. The operator's "send again" (`notification_outbox_retry`) is the deliberate duplicate: for an SMS it is the only
  thing that moves a delivery log row from `unknown` back to `failed`, so the channel may send it once more.
- **Decision 6, never a second text after a crash.** The delivery log row is written `queued` in the first tenant
  transaction, before the network call, and moved to its outcome in the second. An attempt that finds its row `queued` or
  `unknown` (an earlier attempt died or timed out after the request may have gone) answers `Unknown` and does not call the
  provider; one that finds it `sent` or `sunk` answers `Sent` without a call. If the second transaction fails, the answer
  of the provider is still returned: the message went, and a resend would be worse than a stale log row.
- **Decision 5, fairness needs a memory.** The claim takes one row per call, so a rank within the tenant alone is 1 again for
  the tenant just served and one tenant's thousand reminders would run before another's one. A tenant's turn is its rank
  among its own due rows plus the rows it was given in the last minute (attempted and updated since then). Transactional
  rows still come first; ties go to the earlier due time. An index on `notification_outbox(updated_at)` serves the count.
- **Decision 13, where the operator's controls live.** The per tenant switch and daily cap are in a platform table,
  `platform_sms_controls`, not a tenant setting: the application role has no privilege on it, the only writer is a definer
  function that refuses a transaction bound to a tenant, and the platform console endpoint
  `PUT /platform/tenants/{tenant_id}/sms` (permission `platform.tenants.manage`) is audited. **SMS is off for a tenant until
  the operator switches it on, and while it is off no SMS row is written for that tenant** (the enqueue returns false), so
  nothing sits in the outbox for a tenant that does not use SMS. The platform ceiling is `BMS_SMS_PLATFORM_DAILY_CAP`
  (default 2000); the default tenant cap shown for a tenant with no row is 200. Both are counted over the Africa/Kampala
  business day from the delivery log (rows queued, sent, delivered, undelivered, unknown or sunk count). Operator alerts
  go to the Telegram operator chat once per scope, state and day, at 80 percent and when a cap is reached.
- **Decision 4, the guard covers every non-production environment.** The sandbox-only rule for the provider URL and
  account applies whenever `BMS_ENVIRONMENT` is not `production`, including when it is unset, not only on staging.
  `BMS_ENVIRONMENT` is passed to the API by both compose files; the staging file fixes it to `staging`. Production also
  refuses the sandbox URL or account when the mode can send. The fake provider is `BMS_SMS_PROVIDER=fake`.
- **Decision 8, the opt-out in this slice.** The table `sms_opt_outs` and its use in the channel are built; the staff screens
  that write it are #218. Scope `all` blocks every category; scope `reminders` blocks reminders and arrears. A row with a
  number hash blocks that member on that number only; a row without one blocks the member on any number. A removal sets
  `removed_at`. `member_id` has no foreign key, as no core table references a vertical's table (ADR-002).
- **Decision 14, startup records the mode.** The application tells the database its mode at the first dispatch after a
  start, in every mode including `off`, through `sms_channel_mode_changed`, which also drops the backlog when the mode
  moves from `off`, `sink` or never seen to `allowlist` or `live`. Until that call has succeeded the channel does not
  send. The default savings receipt carries no balance (the parameter is not even written to the outbox unless the tenant
  setting `sms_include_balance` is on), and a transactional SMS lives one hour at most, enforced in the enqueue function.
- **Decision 2, how the stored tenant is bound.** The channel binds the tenant an outbox row stores through a new method of the
  jobs module, `TenantJobs.callForTenant(tenantId, work)`: one short transaction with the tenant bound, the id read from the
  stored row and never from a request. It is the one place a stored id becomes a bound tenant (an architecture test lets only
  `core.jobs`, `core.platform` and the kernel do that), so `core.notifications` depends on `core.jobs`. It checks the tenant
  is active (`app_tenant_is_active`); a suspended or closed tenant's message is refused (`tenant_inactive`), not sent.
- **Decision 2, the phone.** The member's phone check is the strict number rule (`PhoneNumbers.forSms`): only a Uganda
  mobile number in a form we can read without guessing; a bare 10 digit foreign number, a number of another country and
  anything with stray digits are refused (`invalid_phone`) and never reach the provider. The outbox row also carries a
  `subject` (member or account) for the opt-out.
- **Throughput.** SMS has its own sender job (`core.notifications.sms-sender`, 200 rows and a 4 minute budget per run,
  every 30 seconds); the general sender leaves SMS to it. The dispatcher itself handled 200 messages in about 1.4 seconds
  against the fake provider on a laptop; the real provider's latency is unmeasured until the sandbox contract job runs.
- **Decision 3 and the Watch-for item on phone numbers in the outbox.** The nightly outbox purge also deletes a failed SMS row
  30 days after it failed, as it does a sent one, because the `recipient` column of a failed row would otherwise keep a
  phone number for ever; email and Telegram rows are unchanged.
- **Decision 13, a reminder over its cap must outlive today.** A reminder expires at the end of its window day, and one over the
  tenant cap is deferred to tomorrow's window, so it would always expire first. `Deferred` can therefore carry a later
  expiry (the end of tomorrow's window day); `notification_outbox_record_result` takes it as an optional last argument and
  only ever moves the expiry later (the new expiry is at most 2 days after the row was queued, and never earlier than the one it had). A receipt held past twice the cap still
  expires after its hour: holding it is not deferring it to a day when it would be stale.
- **Decision 13, what the caps count and how exactly.** The caps count messages, not segments (a two segment message is one
  toward the cap, though two toward the bill; `segments` in the delivery log is what the bill uses). The check reads the
  count and then sends without a lock, so concurrent senders could overshoot a cap by a few messages. One SMS sender job runs
  at a time across instances (db-scheduler), which bounds this to the messages in flight; a hard cap would need a per day
  counter row locked per message, which is not worth its cost for a cost limit.
- **Decision 6, what is ambiguous.** An HTTP 500, 502 or 504, a gateway or internal error code (500, 501) inside the
  provider's answer, a read timeout, a broken connection and an SSL error after the connection was made are all `Unknown`
  (no automatic retry), because the provider may have taken the message first. Only a connection that could not be made, a
  failed TLS handshake, a 401 or 403, a 429, a 503 and "no balance" are clean failures. A 409 (Do Not Disturb), as an HTTP status or as the per recipient code inside a 2xx answer, is a final
  rejection (`provider_dnd`). The other per recipient codes: 401, 402, 403, 404, 406, 407 and 502 are final rejections,
  405 (no balance) is a clean failure, 500 and 501 are unknown, and an unlisted code is unknown.
- **Spec 4.14, the provider alert.** On `provider_auth_failed` and `provider_insufficient_balance` the channel queues one
  Telegram operator alert per reason per day (`sms.provider_alert`; the text carries the reason and the day, nothing else).
- **The keyed phone hash is application code, not a database function.** The migration lists no SQL hash helper: the key is
  derived from the application data key (HMAC-SHA256 with a fixed purpose label, `SmsRecipientHash`), so the secret never
  reaches the database and an operator with SQL access cannot test a number against the log. The column
  `recipient_hash` holds the 64 hex characters.
- **The contract test.** `AfricasTalkingSandboxContractTest` and the manual workflow `sms-sandbox-contract` send one message
  to the provider's sandbox (never on a pull request); it is the only check of the answer format and status codes against
  the real provider, which until it has run come from the provider's public reference.
- **Delivery reports (decision 7)** and the `SmsProvider.parse_delivery_report` method are not in this slice; the port has
  `send` only.
