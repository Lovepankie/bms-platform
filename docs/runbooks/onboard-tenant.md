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
| `BMS_MAIL_FROM` | The sender address, for example `no-reply@<your domain>` |
| `BMS_TELEGRAM_BOT_TOKEN` | The bot's token from BotFather |
| `BMS_TELEGRAM_OPERATOR_CHAT_ID` | The chat that receives operator alerts |

With a sender's values unset it is off: its messages wait as `pending` and nothing fails; the
portal's "Messages not sent" page says which sender is off. A message that fails three times
stays `failed` there with its error class; fix the cause and press "Send again". After 7 days an
unsent message loses its link and cannot be sent again (`outbox_too_old`): issue a new one (for an
applicant, Needs info; for a tenant admin, a new invitation). A bot token that is not in the
`<digits>:<characters>` shape (for example with a trailing space or a CRLF line ending in
`.env`) switches Telegram off with a warning in the log; fix the line.

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
   change later.
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

cloudflared picks up the new configuration within seconds; nothing changes on the host. To remove
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

## Check

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://<tenant host>/api/v1/me
```

`401` means the tenant resolved and authentication is required (correct). `404` with
`unknown_tenant` means the slug is wrong or the tenant does not exist.

## Modules, subscription, suspension

- `PUT /api/v1/platform/tenants/{id}/modules {"modules": [...]}` switches modules; data is kept
  (FR-TEN-03).
- `POST /api/v1/platform/tenants/{id}/subscription {"status", "next_status_change_on"}` moves the
  subscription (FR-TEN-05). `suspended` or `cancelled` makes the tenant read only;
  `POST .../suspend` and `.../resume` are shortcuts (FR-TEN-06).

Every change is written to `platform_audit_log`.

## A tenant whose only admin lost their phone

The admin first tries a recovery code. If none is left, a platform operator verifies the request
with the tenant out of band, then calls
`POST /api/v1/platform/tenants/{id}/users/{user_id}/mfa/reset`; the admin enrols again at the next
sign-in (FR-IAM-12). With two admins, the other admin resets it from the tenant's own user
administration instead.

If a tenant has lost every admin account, invite a new one from the host with
`deploy/sql/invite-tenant-admin.sql`, with the dev lead, and record why in the tenant's ticket.

## Remove

Tenant data is never deleted without a written instruction from the tenant (NFR-DP-06).
