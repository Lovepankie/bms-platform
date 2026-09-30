# Runbook: onboard a tenant

**Requirements:** FR-TEN-01 to FR-TEN-06, FR-IAM-01, FR-IAM-12 · **Security:** chapter 8 sections 8.2 and 8.4 · **Decisions:** ADR-014, ADR-016, ADR-018 · **Data protection:** NFR-DP-08

Hosts (chapter 7 section 7.2): the platform host is `bms-staging.rincoltech.com` on staging and
`bms.rincoltech.com` in production; a tenant is `<slug>-bms-staging.rincoltech.com` on staging and
`<slug>-bms.rincoltech.com` in production. Below, `<platform host>` and `<tenant host>` stand for
them.

A platform operator creates tenants through the platform API on the platform host (FR-TEN-01). The psql
scripts in `deploy/sql/` remain for the first operator, for local development and for recovery.

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

The script prints a one-time setup token, valid 24 hours. The operator sets a password with
`POST https://<platform host>/api/v1/platform/auth/setup {"token", "password"}`, then signs in and
enrols TOTP (mandatory), keeping the ten recovery codes offline.

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
