# Runbook: onboard a tenant

**Requirements:** FR-TEN-01 to FR-TEN-06, FR-IAM-01, FR-IAM-12, FR-ONB-01 to FR-ONB-09, FR-NTF-09 to FR-NTF-11 · **Security:** chapter 8 sections 8.2, 8.3.2 and 8.4 · **Decisions:** ADR-014, ADR-016, ADR-018, ADR-024 · **Data protection:** NFR-DP-08

Hosts (chapter 7 section 7.2): the platform host is `bms-staging.rincoltech.com` on staging and
`bms.rincoltech.com` in production; a tenant is `<slug>-bms-staging.rincoltech.com` on staging and
`<slug>-bms.rincoltech.com` in production. Below, `<platform host>` and `<tenant host>` stand for
them.

Most tenants now arrive through self-onboarding (ADR-024): the business applies at
`https://<platform host>/sign-up`, and the operator verifies and activates it in the operator
portal (section "Activate an application" below). A platform operator can still create a tenant
directly through the platform API (FR-TEN-01) for an assisted customer. The psql scripts in
`deploy/sql/` remain for the first operator, for local development and for recovery.

## Before

- Agree the slug with the tenant: lower case letters, digits and hyphens, 3 to 63 characters, not
  `www`, `api`, `app`, `admin`, `static` or `mail` (FR-TEN-02), and at most 51 characters on
  staging (59 in production) so the host stays one DNS label. It becomes the tenant host and
  cannot change in the MVP.
- Confirm the plan (`starter`, `growth` or `institution`), the currency (default `UGX`) and the
  first tenant admin's name and email or phone.
- Remind the tenant of its own registration duty with the Personal Data Protection Office
  (NFR-DP-08).
- Maker-checker needs at least two active staff users with the relevant permissions before the
  lending module is used (chapter 8 section 8.4). The first admin invites them.

## The first platform operator (once per environment)

On the environment's host, as the `deploy` user (production) or the `bms` user (staging):

```bash
cd /opt/bms
docker compose --project-name bms -f compose.yml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U bms_owner -d bms \
  -v email=<operator email> -v name='<Operator name>' -f - < sql/create-platform-user.sql
```

The script prints a one-time setup token, valid 24 hours. The operator opens
`https://<platform host>/platform/setup#token=<token>` (or opens `/platform/setup` and pastes the
token) and sets a password; the API call behind it is
`POST https://<platform host>/api/v1/platform/auth/setup {"token", "password"}`. They then sign in
at `https://<platform host>/platform/sign-in` and enrol TOTP (mandatory), keeping the ten recovery
codes offline.

## Mail and Telegram for the outbox (once per environment)

Activation links, applicant emails and operator alerts go through the outbox (spec section 11).
Set these in `/opt/bms/.env` (never in git; `.env.example` lists them empty), then redeploy or
`docker compose up -d api`:

| Variable | Value |
|---|---|
| `BMS_SMTP_HOST` | The provider's SMTP host |
| `BMS_SMTP_PORT` | `465` (implicit TLS; port 587 is blocked on the staging host) |
| `BMS_SMTP_USER`, `BMS_SMTP_PASSWORD` | The sending account and its app password |
| `BMS_MAIL_FROM` | The sender address, for example `no-reply@<your domain>`, or with a display name: `BMS by Rincol Tech <no-reply@<your domain>>` (quote the whole value in `.env`) |
| `BMS_SUPPORT_CONTACT` | Optional. The support line in the footer of every email, shown as typed, for example `support@<your domain> or <phone>`; empty leaves the line out |
| `BMS_TELEGRAM_BOT_TOKEN` | The bot's token from BotFather |
| `BMS_TELEGRAM_OPERATOR_CHAT_ID` | The chat that receives operator alerts |

**Every host needs `BMS_DATA_KEY`, even one that sends by SMTP only** (issue #777, ADR-117 decision 9). The suppressed
email addresses are matched by a hash under it whatever the provider is, so outside the dev and test profiles a blank
`BMS_DATA_KEY` stops the start with `BMS_DATA_KEY must be set`. Set it before the release reaches the host (`java -jar
bms-api.jar keys` prints one); it is the same key that seals second-factor seeds (`docs/runbooks/payments.md`), so a host
that already has one needs nothing new.

On production (`BMS_ENVIRONMENT=production`) the four email values are required: without any of them, or with a
`BMS_MAIL_FROM` that is not an address, the API refuses to start and its log names the unset variables (ADR-059).
Elsewhere, with a sender's values unset it is off: its messages wait as `pending` and nothing fails; the
portal's "Messages not sent" page says which sender is off. A message that fails three times
stays `failed` there with its error class; fix the cause and press "Send again". After 7 days an
unsent message loses its link and cannot be sent again (`outbox_too_old`): issue a new one (for an
applicant, Needs info; for a tenant admin, a new invitation). A bot token that is not in the
`<digits>:<characters>` shape (for example with a trailing space or a CRLF line ending in
`.env`) switches Telegram off with a warning in the log; fix the line.

Every email is sent as an HTML part and a plain-text part (issue #396, ADR-054). The HTML header
loads the Rincol Tech logo from `https://<BMS_PLATFORM_HOST>/brand/rincoltech-logo.png`, and a
business's own staff emails load that business's logo from its public logo route
(`https://<tenant host>/api/v1/branding/logo`) when one is set. Both must be reachable from the
internet for the images to show: a host behind an access gate shows the alt text instead, and the
email still reads correctly. To check a change, send yourself a password reset and open it in a
web mail and a phone mail app, in light and dark mode.

### Email through Resend (issue #333, ADR-117)

The sender is `no-reply@rincoltech.com` on the Rincol Resend account. `rincoltech.com` is verified in Resend and its
records are in Cloudflare (DNS of the `rincoltech.com` zone); do not change them without the owner:

| Record | Where | What it is for |
|---|---|---|
| SPF | `TXT` on the return path subdomain Resend shows under Domains | lets Resend's servers send for the domain |
| DKIM | `TXT` at the `_domainkey` name Resend shows under Domains | signs every message |
| DMARC | `TXT` at `_dmarc`, `v=DMARC1` with the policy the owner set | tells receivers what to do with a message that fails |
| MX | on the return path subdomain, as Resend shows it | receives bounces for Resend |

Copy the exact values from Resend (Domains, `rincoltech.com`); they are not repeated here so they cannot go stale. The
domain shows **Verified** there when all of them are live.

To send by the HTTP API instead of SMTP, set in `/opt/bms/.env` (never in git), then `docker compose up -d api`:

| Variable | Value |
|---|---|
| `BMS_MAIL_PROVIDER` | `resend` (unset or `smtp` keeps SMTP; any other value stops the start) |
| `BMS_RESEND_API_KEY` | A **send-only** key restricted to `rincoltech.com`, made in Resend under API Keys, one per environment. Never logged |
| `BMS_MAIL_FROM` | `BMS by Rincol Tech <no-reply@rincoltech.com>` (quote the whole value) |
| `BMS_MAIL_ALLOWLIST` | **Staging only.** The addresses that may get mail, comma separated; `@<domain>` allows a whole domain, for example `@rincoltech.com`. Empty on staging sends nothing |

The `BMS_SMTP_*` values can stay on the host; they are not used while the provider is `resend`, and setting it back to
`smtp` returns to them. On production the API refuses to start with `resend` and no key or no `BMS_MAIL_FROM`.

- **From-name.** A business's emails show that business's name as the sender, from `no-reply@rincoltech.com`; the
  platform's own emails show the name in `BMS_MAIL_FROM`.
- **Staging allow-list.** With `BMS_ENVIRONMENT=staging`, or on any host where `BMS_MAIL_ALLOWLIST` is set, a message to
  anyone not on the list is not sent and shows on "Messages not sent" as `not_on_staging_allowlist`. Set the list
  **before** this release reaches staging, or every email there stops; the API log then says `BMS_MAIL_ALLOWLIST is empty
  on staging` at every start. To let a tester in, add the address, `docker compose up -d api`, then "Send again". Never
  set the list on production.
- **Resend down.** Keep the `BMS_SMTP_*` values on the host when switching to Resend: an email that cannot reach Resend
  at all (`resend_unreachable`: refused, no route, DNS, connect timeout or TLS handshake, before any of the request
  left) then goes by SMTP at once. The log says `resend unreachable; sent by smtp instead` and then
  `outbox row sent: ... via=smtp`, and the platform audit log has one `platform.outbox.sent_via` row with the outbox row
  id and `via` `smtp`, which is how to tell afterwards which sender carried an email. SMTP has no idempotency key: if
  the SMTP server accepted the message and the connection then failed (on close), the row is retried, and the next try
  may reach Resend, so the reader can get the email twice, as with SMTP alone. A Resend that answers with
  errors, times out after the request was sent, or drops the connection once made is not bypassed (it may already hold
  the email, and SMTP would send a second copy): those rows are tried three times over about six minutes, then show as
  failed on "Messages not sent". When Resend is back, press "Send again" on them; an activation or reset whose link has
  expired needs a new one.
- **What a failure means.** `resend_rejected`: Resend refused the message as invalid; it is not retried (a refused
  business from-name is already retried once with `BMS_MAIL_FROM`). `resend_too_large`: the message, usually the weekly
  workbook, is over Resend's attachment limit (about 40 MB once encoded); it is not retried, so ask the business to use
  Download my data instead. `resend_unauthorized`: the key is wrong, revoked or not allowed for the domain; make a new one.
  `resend_timeout`: the request reached Resend and no answer came in time (20 seconds, more for a large attachment).
  `resend_unreachable`: no connection was made at all (DNS, a firewall or proxy blocking `api.resend.com`, a connect
  timeout, a failed TLS handshake); check the host's outbound HTTPS before suspecting Resend.
  `resend_connection_lost`: the connection was made and then dropped before an answer (a proxy or a network fault
  mid-request); the request may have reached Resend. `resend_unavailable` (Resend answered with a
  server error), `resend_idempotency_conflict` (two tries at once) and `resend_interrupted` (the API was stopping during
  the send): all of these are retried twice on their own, and those tries never send a second copy, because the row's key
  (with the environment and the row id in front) is Resend's idempotency key for 24 hours. "Send again" later than that is
  a new email: if an earlier try was delivered without an answer, the reader gets it twice. A rate limit from Resend is not
  a failure: the row waits and is sent later. `resend_no_row_id`: a send without an outbox row, which the dispatcher never
  makes; report it as a bug.
- **`resend_payload_changed` on "Messages not sent".** The row's email is built again on every try, from the template, the
  business's name and logo. If one of them changed between a try that reached Resend without an answer and the next try
  (the business renamed itself, or was suspended so its name could not be read), Resend refuses the second as a different
  email under the same key. The first may have been delivered: look it up in Resend by its `outbox_id` tag before pressing
  "Send again", which within 24 hours ends the same way. It is rare: it needs a lost answer and a change within minutes.
- **`BMS_RESEND_API_URL`** stays unset on every host. It must be an `https` address; the only exception is a test's
  `http://127.0.0.1` or `http://localhost` stand-in, and production refuses even that at start.
- **Bounces and complaints (ADR-117 decision 8).** Set it up once per host: in Resend, Webhooks, add the endpoint
  `https://<BMS_PLATFORM_HOST>/api/v1/platform/webhooks/resend` with the events `email.bounced`, `email.complained` and
  `email.suppressed`,
  copy its Signing secret (`whsec_...`) into `/opt/bms/.env` as `BMS_RESEND_WEBHOOK_SECRET`, then
  `docker compose up -d api`. The platform host must be reachable from the internet on that path (no access gate in
  front of it). Resend's "Send test event" must show 200 (a test event carries no `env` tag, so it is acknowledged and
  not recorded); a 401 means the secret differs, a 404 that it is unset. Staging and production share the Resend
  account, so each endpoint receives the other's events too: each records only the events whose `env` tag is its own,
  and answers the others 200 with a debug line in the log and nothing recorded. Each bounce,
  complaint and suppressed send then shows on "Messages not sent" under **Bounced and complained** (a suppressed send
  reads "Not sent, address blocked after an earlier bounce": Resend refused to send to an address that bounced or
  complained before), with the message, the
  recipient's domain only and the time; the full address is never stored. Match it to its outbox row by the row id
  (in the platform audit log, action `platform.email.bounced`, `platform.email.complained` or
  `platform.email.suppressed`), or in Resend under Emails by the tag `outbox_id`.
- **Email that goes by SMTP is not tracked or suppressed (ADR-117 decision 8).** Only email sent through the Resend API
  carries the tags (`env`, `outbox_id`); Resend's SMTP documentation names no header for tags. So with
  `BMS_MAIL_PROVIDER=smtp` (even when the SMTP host is Resend's), and for every email sent by the fallback (`via=smtp`),
  a bounce or complaint is not recorded, does not show under **Bounced and complained**, and does not stop later sends:
  such a row can read `sent` while Resend drops it. If a user says an email never came and the row reads `sent`, look
  the address up in Resend (Emails, or Suppressions) before anything else. BMS still refuses an address it suppressed
  earlier, over SMTP too. Choose `resend` on a host where bounces must be tracked.
- **`recipient_suppressed` on "Messages not sent" (ADR-117 decision 9).** An address that bounced hard, complained, or
  that Resend reported as suppressed is not sent to again by BMS, over Resend or SMTP: each later email to it is refused
  and shows here as failed with `recipient_suppressed`, instead of reading `sent` while Resend drops it. BMS keeps only a
  keyed hash of the address, never the address. A soft bounce (a full mailbox) suppresses nothing. To send to the
  address again, first make sure it works (or fix the user's email in BMS, which needs no clear), remove it from
  Resend's suppression list (Resend, Suppressions) if it is there, then clear it in BMS as a platform operator with
  `platform.tenants.manage`, signed in to the operator portal:

  ```
  curl -sS -X POST "https://<BMS_PLATFORM_HOST>/api/v1/platform/outbox/unsuppress" \
    -H "Authorization: Bearer <your operator access token>" -H "Content-Type: application/json" \
    -d '{"email":"<the address>"}'
  ```

  204 means cleared (audited as `platform.email.unsuppressed` under your name); 409 `email_not_suppressed` means BMS was
  not holding it back (check the spelling). Then issue a new link or "Send again" on a newer row; a refused row has had
  its values cleared and cannot be sent again. Run the command from your own machine, not a shared shell history, since
  the address is in it.
- **Rotating the key.** Make the new key, put it in `.env`, `docker compose up -d api`, send yourself a password reset,
  then delete the old key in Resend.

SMS is a channel of the same outbox but is off for every tenant until the platform operator switches it on
(`PUT /api/v1/platform/tenants/{tenant_id}/sms`) and off for the environment until a provider and a mode are set;
see `docs/runbooks/sms-setup.md`. A new tenant therefore sends no SMS until you decide it should.

## Protect the public sign-up (once per environment, the dev lead)

The sign-up form is public. In front of the API's own bounds, add a Cloudflare rate limiting rule
for the platform host (dashboard, Security, WAF, Rate limiting rules):

- When: `URI Path starts with /api/v1/platform/sign-up/` and hostname is the platform host.
- Counting: by IP; 10 requests per 1 minute.
- Action: block for 10 minutes.

The API also caps new applications and confirmation emails per hour
(`BMS_SIGNUP_MAX_APPLICATIONS_PER_HOUR`, default 30; `BMS_SIGNUP_MAX_VERIFICATION_EMAILS_PER_HOUR`,
default 60). When a cap trips, the operator chat gets one Telegram alert for that hour and
`platform_audit_log` a `platform.sign_up.cap_reached` row; sign-ups are still answered but nothing
is created or sent until the hour passes. The caps are small on purpose, so a handful of addresses
(about 6) can fill the default one in an hour, and real applicants in that hour see the normal
"check your email" page but get no email. On the alert:

1. Open the portal's applications (status "Waiting for review" shows only confirmed ones) and the
   `platform_audit_log` rows of the last hour to see whether the burst is real.
2. A burst of throwaway names or addresses: tighten the Cloudflare rule (fewer requests per
   minute, or a managed challenge on the sign-up path) and let the hour pass.
3. A real launch or campaign: raise `BMS_SIGNUP_MAX_APPLICATIONS_PER_HOUR` and
   `BMS_SIGNUP_MAX_VERIFICATION_EMAILS_PER_HOUR` in `/opt/bms/.env` and restart the API.
4. Applicants who signed up while the cap held received nothing: they can apply again with the same
   email after the hour (a repeat sends a fresh link to the address they type). The senders log
the row id, channel and template only, never an address, a text or a link.

## Create a tenant

Signed in as a platform operator:

```bash
curl -s https://<platform host>/api/v1/platform/tenants -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{
    "name": "<Tenant display name>", "slug": "<slug>", "plan_code": "starter",
    "modules": ["lending"], "head_office": {"code": "HQ", "name": "Head Office"},
    "admin": {"full_name": "<Admin name>", "email": "<admin email>"}}'
```

One transaction creates the tenant, its trial subscription, settings, head office, the enabled
modules with their chart of accounts, and the first tenant admin as an invited user. The response
carries `admin_invitation.url`, a one-time link valid 72 hours on the tenant host
(`https://<tenant host>/accept-invitation#token=...`); it is also sent through the
notification port. Pass it to the admin over a channel you trust. The admin sets a password,
enrols TOTP at first sign-in, and invites the other staff (FR-IAM-01).

Staging uses fabricated tenants only (chapter 15 section 15.8). Locally, `make seed` creates the
`demo` tenant with `deploy/sql/create-tenant.sql`, invites its admin with
`deploy/sql/invite-tenant-admin.sql` and creates a platform operator, printing the link and the
setup token.

## Activate an application (self-onboarding, ADR-024)

1. An applicant confirms their email with the "Confirm my email" button on the page the emailed
   link opens. The application then raises an alert on Telegram and by email to every active
   operator, and appears in the portal (`https://<platform host>/platform`, "Applications").
2. Open it. Check the **Possible repeat** box: another application or tenant with the same email,
   phone or business name may be a repeat free month (spec section 4.2 item 4).
3. Call or message the applicant. Then **Verify**, or **Needs info** with a question (the applicant
   gets an email and answers on their application page), or **Reject** with a reason (they see it).
4. Before activating on staging, make the tenant host reachable (next section) with the slug you
   will use. The portal suggests a free slug from the business name; edit it if needed. It cannot
   change later. Then press **Check the address** on the Activate form: it asks
   `https://<slug host>/version` and says "answers" when the host reaches this app. "No DNS record
   yet" means step 1 of the next section is missing; "did not answer" or "something answered, but
   not this app" usually means the tunnel entry (step 2) is missing or the record is minutes old.
   "Refused the check (HTTP 403 or 503)" usually means a Cloudflare security rule, bot check or
   Access policy answered the server's request: open the address in a browser and check the
   zone's security rules before you touch the tunnel.
   The check changes nothing, and Activate works either way.
5. **Activate**: choose the modules, term and way in. For the paid way, note the payment received
   (method, the sender's reference, date); no amounts are modelled in this release. Activate
   creates the tenant with the applicant as invited tenant admin, starts the subscription as
   `trial` or `active`, and queues the activation email with the 72 hour link. The link is also
   shown once in the portal in case the email does not arrive. Activating twice does nothing.
6. Rejected and expired applications are deleted 90 days after they closed; an application whose
   email is not confirmed in 14 days expires.

## Make the tenant host reachable

**Staging (Cloudflare Tunnel, ADR-018).** Each staging tenant needs two things in Cloudflare,
because a tunnel's public hostnames cannot express `*-bms-staging`: a proxied DNS record and a
public hostname entry on the tunnel. Do both with the Cloudflare API from your own machine, with a
short-lived API token that has `Zone.DNS:Edit` on `rincoltech.com` and
`Account.Cloudflare Tunnel:Edit` (never stored on the host). `ZONE_ID`, `ACCOUNT_ID` and
`TUNNEL_ID` are in the dashboard (`docs/runbooks/provision-host.md` section 8.2).

```bash
SLUG=demo
HOST="${SLUG}-bms-staging.rincoltech.com"
CF=https://api.cloudflare.com/client/v4
AUTH="Authorization: Bearer $CF_API_TOKEN"

# 1. The proxied CNAME to the tunnel.
curl -s -X POST "$CF/zones/$ZONE_ID/dns_records" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"type\":\"CNAME\",\"name\":\"${SLUG}-bms-staging\",\"content\":\"${TUNNEL_ID}.cfargotunnel.com\",\"proxied\":true}" \
  | jq '.success, .errors'

# 2. The tunnel's public hostname: read the configuration, add the host before the catch-all
#    rule, write it back (the PUT replaces the whole configuration, so always start from a GET).
curl -s "$CF/accounts/$ACCOUNT_ID/cfd_tunnel/$TUNNEL_ID/configurations" -H "$AUTH" \
  | jq --arg host "$HOST" '{config: (.result.config
      | .ingress |= (map(select(.hostname != $host)) | (.[:-1] + [{hostname: $host, service: "http://proxy:8080"}] + .[-1:])))}' \
  > tunnel-config.json
jq '.config.ingress[] | .hostname // "catch-all"' tunnel-config.json     # check before writing
curl -s -X PUT "$CF/accounts/$ACCOUNT_ID/cfd_tunnel/$TUNNEL_ID/configurations" -H "$AUTH" \
  -H 'Content-Type: application/json' --data @tunnel-config.json | jq '.success, .errors'
```

cloudflared picks up the new configuration within seconds; nothing changes on the host. Confirm
with **Check the address** on the application's Activate form (issue #155). To remove
a tenant host, delete the DNS record and run step 2 with a `map(select(.hostname != $host))` only.

**Check the whole ingress before writing it (ADR-018 finding M6).** The `PUT` above replaces the
entire configuration, so one bad edit can misroute every host, not just the new one. Before the
final `curl -X PUT`, confirm:

```bash
# The catch-all (last) rule has no hostname and returns 404; every other rule points at the proxy.
jq -e '.config.ingress[-1] | has("hostname") | not and (.service == "http_status:404")' tunnel-config.json
jq -e '[.config.ingress[:-1][] | .service == "http://proxy:8080"] | all' tunnel-config.json
```

Both must print `true`. A rule pointing anywhere else (`postgres:5432`, `api:8081`, a raw IP) would
expose the database or the actuator to the internet the moment the tunnel started routing it; the
two networks in `compose.pi-staging.yml` (ADR-018) mean cloudflared could not reach either even if
the ingress rule existed, but a correct ingress configuration is still the first control, not the
second.

**Production VM.** Add `A <slug>-bms` pointing at the VM (DNS only); the wildcard certificate
already covers it (`docs/runbooks/provision-host.md` section 3).

## The short link rule (once per environment, and when the reserved list changes)

Every shop's short link `rincoltech.com/<short name>` is served by one Cloudflare single redirect, never one per shop
(ADR-108). On the rincoltech.com zone, Rules, Redirect Rules, Single Redirects:

1. Have the website's owner confirm `reserved` from the endpoint below lists every page of rincoltech.com; add any missing
   path to `ShortNames.RESERVED` and release first.
2. **Gate: each existing shop's slug is its link name.** The names are the paths of the hand-made redirects listed in the
   zone's Single Redirects (each points at a staging shop host). For each,
   `curl -sI https://<BMS_PLATFORM_HOST>/api/v1/platform/short-links/<name>` must answer 302 to that shop's own host
   (`https://<name>-bms-staging.rincoltech.com/`). Then the slug is the name, a business has it, and no sign-up or
   Activate can claim it. When one lands on `/link-not-found`, that shop's slug is another name: stop here, keep the
   hand-made redirects, and raise an issue to rename that shop (a rename is done by Rincol Tech on request, not in the app;
   ADR-108 decision 1). Until then a stranger could claim the printed name at sign-up. Do not go on while any of them
   fails.
3. Fetch what to type: `curl -s https://<BMS_PLATFORM_HOST>/api/v1/platform/short-link-rule`.
4. Create (or edit) one rule named "Shop short links". When incoming requests match: Custom filter expression, paste
   `expression`. Then: Dynamic, paste `target`, status code 302, Preserve query string off.
5. Delete the old hand-made redirects once the rule is saved and step 2 passed for every one of them.
6. Check: `curl -sI https://rincoltech.com/<a shop's short name>` answers 302 to the platform host, which answers 302 to the
   shop; `curl -sI https://rincoltech.com/about` is the website's own page.

**A shop that wants another short name.** The short name is the shop's host, chosen at sign-up and kept for good; the app has
no rename. A request is recorded as its own issue and done by Rincol Tech later (ADR-108 decision 1).

At go-live, edit the rule's target to the live platform host (fetch it again from the live platform host). When a page is
added to the website, add its path to `ShortNames.RESERVED`, release, and repeat steps 1 and 2.

## Check

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://<tenant host>/api/v1/me
```

`401` means the tenant resolved and authentication is required (correct). `404` with
`unknown_tenant` means the slug is wrong or the tenant does not exist.

## Modules, subscription, suspension

The operator portal does all of this on the Business page (`/platform/businesses`, issue #407); the API is below. **Every
change needs a `reason`** (5 to 300 characters), kept in `platform_audit_log` with the operator; the same request again changes
nothing.

- `PUT /api/v1/platform/tenants/{id}/modules {"modules": [...], "reason"}` switches modules; data is kept
  (FR-TEN-03).
- `POST /api/v1/platform/tenants/{id}/subscription {"status", "next_status_change_on", "reason"}` moves the
  subscription (FR-TEN-05). `suspended` or `cancelled` makes the tenant read only;
  `POST .../suspend {"reason"}` and `.../resume {"reason"}` are shortcuts (FR-TEN-06). A resume brings back the
  status from before the suspension (a free month stays a free month).
- `PUT .../plan {"plan_code", "reason"}`, `PUT .../subscription-dates {"trial_ends_on"?, "paid_until"?,
  "clear_trial_ends_on"?, "clear_paid_until"?, "reason"}`, `PUT .../pilot {"pilot", "reason"}` (a pilot is free with
  no free month end), and `POST .../payments` with an `Idempotency-Key` header (a payment received; an amount far from
  the plan's price needs `"confirm_unusual_amount": true`).
- Plans and prices: `PUT /api/v1/platform/plans/{code}` and `POST .../prices`, each with a `reason`.
- Referral commissions (ADR-069): the platform default rule is 10% of each subscription payment a referred business
  makes, for its first 12 paid months, no cap (product owner decision, 22 Oct 2026; `V88` sets it). For example a
  Growing business paying 60,000 a month earns its referrer 6,000 on each of its first 12 payments and nothing after;
  a year paid upfront earns 10% of that payment once. Recording a payment (above) adds the line by itself. To change
  the default for every later payment: `GET` and `PUT /api/v1/platform/referrals/default-rule {"rule", "reason",
  "totp_code"}`; to give one referral its own deal: `PUT /api/v1/platform/referrals/{id}/rule` the same way (`"rule":
  null` puts it back on the default). Lines already worked out never change.

Every change is written to `platform_audit_log`.

## A tenant whose only admin lost their phone

The admin first tries a recovery code. If none is left, a platform operator verifies the request
with the tenant out of band, then calls
`POST /api/v1/platform/tenants/{id}/users/{user_id}/mfa/reset`; the admin enrols again at the next
sign-in (FR-IAM-12). With two admins, the other admin resets it from the tenant's own user
administration instead.

## A staff user who forgot the password

The user types the email or phone on the sign-in page's "Forgot password?" and a link (valid one hour, once) goes to the email on
file; nothing tells the page whether an account matched (FR-IAM-15, ADR-042). It needs the SMTP variables above on the host: until
they are set the link is queued and not sent. A user whom the tenant's policy requires, now, to use two-step sign-in and who has not set it up yet
gets no link by email.

For a user with no email on file, an unreachable inbox, or a host without SMTP, a tenant admin calls
`POST /api/v1/users/{user_id}/reset-sign-in` and hands the returned link to the user once; it is not emailed, and the user is emailed
that a reset was issued. The body proves the admin is still themselves: `{"totp_code": "<the current code from their own
authenticator>"}` when they have two-step sign-in, otherwise `{"password": "<their own current password>"}` (two-step sign-in is
optional by default; a wrong password counts toward the same 15 minute lock as at sign-in, and the fifth wrong password ends the session that sent it). **On the Users screen** (`/staff/users`) this is the **Reset sign-in** button on the person's card: the administrator types the
6 digit code from their own authenticator app (or their own password when they have none), and the link is shown once with Copy and
Send on WhatsApp (issue #339). A tenant admin cannot be reset this way, nor can an admin reset themselves; the platform operator's
route for a tenant admin has no screen and is called by API.

**Two-step sign-in is optional by default** (migration `V67`, issue #348, ADR-046): no role is flagged, so a new tenant admin is signed in after
the first run and is offered the second factor, and every database behaves the same (staging's hand edit of `roles.mfa_required` is no longer needed). A
tenant administrator chooses who must use it under Business set-up > Two-step sign-in (everyone, or chosen roles, with a grace period); the platform
operator can read it in the tenant's settings. A no-factor admin resets other users with their own password, and, where SMTP is set up, can use Forgot
password themselves, and whoever reads that admin's mailbox can take the account over until the tenant requires two-step sign-in, so recommend it when
you hand a tenant over. A user for whom a factor IS required now (a role flag, or the tenant's policy once its grace period has ended) and has none gets
no link by email and is helped by an administrator or the operator.

A tenant admin who forgot the password and cannot use the sign-in page (no email, or SMTP not set up) is helped by the platform
operator after verifying the request with the tenant out of band: `POST /api/v1/platform/tenants/{id}/users/{user_id}/reset-sign-in` with
`{"totp_code": "<the operator's current code>"}` (operators always enrol; the platform's `mfa/reset` needs the operator's code as
well). The link is returned once and `no-store`, and the admin is emailed that a reset was issued. No operator screen calls it yet. A user
still invited gets the invitation's link (`POST /api/v1/users/{user_id}/invitation`).

The lost-phone case above: the platform reset needs the operator's own code too, so the call is
`POST /api/v1/platform/tenants/{id}/users/{user_id}/mfa/reset` with `{"totp_code": "<the operator's current code>"}`.

If a tenant has lost every admin account, invite a new one from the host with
`deploy/sql/invite-tenant-admin.sql`, with the dev lead, and record why in the tenant's ticket.

## Remove

Tenant data is never deleted without a written instruction from the tenant (NFR-DP-06).
