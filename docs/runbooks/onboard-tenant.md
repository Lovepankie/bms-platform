# Runbook: onboard a tenant

**Requirements:** FR-TEN-01 to FR-TEN-06, FR-IAM-01, FR-IAM-12 · **Security:** chapter 8 sections 8.2 and 8.4 · **Decisions:** ADR-014, ADR-016 · **Data protection:** NFR-DP-08

A platform operator creates tenants through the platform API on `app.<base>` (FR-TEN-01). The psql
scripts in `deploy/sql/` remain for the first operator, for local development and for recovery.

## Before

- Agree the slug with the tenant: lower case letters, digits and hyphens, 3 to 63 characters, not
  `www`, `api`, `app`, `admin`, `static` or `mail` (FR-TEN-02). It becomes `<slug>.<base>` and
  cannot change in the MVP.
- Confirm the plan (`starter`, `growth` or `institution`), the currency (default `UGX`) and the
  first tenant admin's name and email or phone.
- Remind the tenant of its own registration duty with the Personal Data Protection Office
  (NFR-DP-08).
- Maker-checker needs at least two active staff users with the relevant permissions before the
  lending module is used (chapter 8 section 8.4). The first admin invites them.

## The first platform operator (once per environment)

On the environment's host, as the `deploy` user:

```bash
cd /opt/bms
docker compose --project-name bms -f compose.yml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U bms_owner -d bms \
  -v email=<operator email> -v name='<Operator name>' -f - < sql/create-platform-user.sql
```

The script prints a one-time setup token, valid 24 hours. The operator sets a password with
`POST https://app.<base>/api/v1/platform/auth/setup {"token", "password"}`, then signs in and
enrols TOTP (mandatory), keeping the ten recovery codes offline.

## Create a tenant

Signed in as a platform operator:

```bash
curl -s https://app.<base>/api/v1/platform/tenants -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{
    "name": "<Tenant display name>", "slug": "<slug>", "plan_code": "starter",
    "modules": ["lending"], "head_office": {"code": "HQ", "name": "Head Office"},
    "admin": {"full_name": "<Admin name>", "email": "<admin email>"}}'
```

One transaction creates the tenant, its trial subscription, settings, head office, the enabled
modules with their chart of accounts, and the first tenant admin as an invited user. The response
carries `admin_invitation.url`, a one-time link valid 72 hours; it is also sent through the
notification port. Pass it to the admin over a channel you trust. The admin sets a password,
enrols TOTP at first sign-in, and invites the other staff (FR-IAM-01).

Staging uses fabricated tenants only (chapter 15 section 15.8). Locally, `make seed` creates the
`demo` tenant with `deploy/sql/create-tenant.sql`, invites its admin with
`deploy/sql/invite-tenant-admin.sql` and creates a platform operator, printing the link and the
setup token.

## Check

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://<slug>.<base>/api/v1/me
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
