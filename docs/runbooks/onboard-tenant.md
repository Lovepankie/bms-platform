# Runbook: onboard a tenant

**Requirements:** FR-TEN-01, FR-TEN-02, FR-TEN-03 · **Security:** chapter 8 section 8.4 · **Data protection:** NFR-DP-08

Until the platform console implements FR-TEN-01, a tenant is created with
`deploy/sql/create-tenant.sql`, run as `bms_owner`. The script is idempotent (an existing slug is
reported and left alone) and does everything in one transaction: the tenant, its head office
branch, the lending module switch, the default lending chart of accounts (chapter 6 section
6.6.2) and an audit row.

## Before

- Agree the slug with the tenant: lower case letters, digits and hyphens, 3 to 63 characters, not
  `www`, `api`, `app`, `admin`, `static` or `mail` (FR-TEN-02). It becomes `<slug>.<base>` and
  cannot change in the MVP.
- Confirm the plan (`starter`, `growth` or `institution`) and the currency (default `UGX`).
- Remind the tenant of its own registration duty with the Personal Data Protection Office
  (NFR-DP-08).
- Maker-checker needs at least two active staff users with the relevant permissions before the
  lending module is used (chapter 8 section 8.4). Staff user creation arrives with the identity
  module.

## Create

On the environment's host, as the `deploy` user:

```bash
cd /opt/bms
export IMAGE_TAG="$(cat state/current_tag)"
docker compose --project-name bms -f compose.yml exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U bms_owner -d bms \
  -v slug=<slug> -v name='<Tenant display name>' -v plan=starter -v currency=UGX \
  -v branch_code=HQ -v branch_name='Head Office' -v lending=true \
  -f - < sql/create-tenant.sql
```

Staging uses fabricated tenants only (chapter 15 section 15.8). Locally, `make seed` runs the same
script for the `demo` tenant.

## Check

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://<slug>.<base>/api/v1/lending/members
```

`401` means the tenant resolved and authentication is required (correct). `404` with
`unknown_tenant` means the slug is wrong or the tenant is not active.

## Suspend or remove

Suspension and module switching are platform console features (FR-TEN-03, FR-TEN-06). Until they
exist, change `tenants.status` or `tenant_modules` as `bms_owner` only with the dev lead, and write
the change to the audit log the same way the script does. Tenant data is never deleted without a
written instruction from the tenant (NFR-DP-06).
