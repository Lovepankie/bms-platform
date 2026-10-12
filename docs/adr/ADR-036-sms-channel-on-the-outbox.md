# ADR-036: SMS as a channel of the notification outbox: a provider adapter (Africa's Talking first), a tenant delivery log, sending windows, opt-out, and a sink everywhere except production

## Status

Proposed (issue #210, design only; spec `docs/specs/client-extras.md` section 4). Settles the SMS
half of what chapter 12 section 12.3.3 carried as pending ADR-013; USSD remains pending ADR-013.
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
