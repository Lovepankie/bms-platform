# ADR-003: Tenant isolation: shared schema plus PostgreSQL row-level security

## Status

Accepted (2026-09-29)

## Context

Every tenant is a separate lending or trading business. A tenant seeing another
tenant's members, balances or national ID numbers is the single worst defect this
platform can have: it is a breach under the Uganda Data Protection and Privacy Act 2019
and it ends the product commercially.

Three options were considered.

1. **Database per tenant.** Strong isolation, but migrations run once per tenant,
   backups multiply, connection pools multiply, and a small host cannot carry many
   tenants.
2. **Schema per tenant.** The same operational costs in a milder form.
3. **Shared schema with a `tenant_id` column on every tenant-owned table**, and
   PostgreSQL row-level security (RLS) applying the tenant predicate in the database
   engine, so isolation does not depend on every query remembering a `WHERE` clause.

Option 3 with filtering in application code alone was rejected: one forgotten filter
leaks a tenant.

## Decision

Adopt option 3, with RLS enforced by the engine.

- Every tenant-owned table has `tenant_id uuid NOT NULL`. Tables that belong to a branch
  also have `branch_id uuid NOT NULL`.
- Every tenant-owned table has RLS enabled **and forced**, with one policy:

  ```sql
  ALTER TABLE <t> ENABLE ROW LEVEL SECURITY;
  ALTER TABLE <t> FORCE ROW LEVEL SECURITY;
  CREATE POLICY tenant_isolation ON <t>
    USING (tenant_id = current_setting('app.tenant_id')::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);
  ```

  If `app.tenant_id` has not been set in the session, `current_setting` raises an error,
  so a query that forgot to bind a tenant fails loudly rather than returning rows. One
  shared migration helper applies exactly this policy, so no migration writes it by hand.
- The application connects as `bms_app`, a role that is not a superuser, does not own
  the tables and has `NOBYPASSRLS`. Migrations run as `bms_owner`, which owns the tables
  and is never used by the running application.
- At the start of every transaction the API and the worker execute
  `SELECT set_config('app.tenant_id', :tenant_id, true)`. The third argument makes the
  setting transaction-local, so a pooled connection cannot carry one tenant's setting
  into another tenant's request.
- The tenant is resolved from the request host (`<slug>.<base domain>`), never from the
  request body. A development-only `X-Tenant` header is accepted when
  `ALLOW_TENANT_HEADER=true`, which the configuration refuses in production. The
  authenticated token's tenant must equal the host's tenant, or the request is refused.
- Same-tenant referential integrity is enforced with composite keys: every tenant-owned
  table has `UNIQUE (tenant_id, id)` and references use
  `FOREIGN KEY (tenant_id, x_id) REFERENCES x (tenant_id, id)`, so a row can never point
  at another tenant's row even through a bug in the application.
- **Branch scoping is authorisation, not isolation.** A user's branch scope is applied by
  the application's repository layer from the principal's role assignments. It is not an
  RLS policy, because consolidated reporting across branches is a normal operation for
  permitted roles.
- The `tenants` table is itself under RLS (policy `id = current_setting('app.tenant_id')::uuid`),
  so the application role can see only its own tenant row. Resolving a slug to a tenant
  id before a tenant is bound goes through one `SECURITY DEFINER` function,
  `app_resolve_tenant(slug) RETURNS uuid`, which returns only the id of an active tenant
  and nothing else. Listing tenants for scheduled jobs goes through a second function,
  `app_list_active_tenants() RETURNS SETOF uuid`. These two functions are the only
  sanctioned way around the policy, and both are covered by the isolation tests.
- Reference tables with no tenant data (`plans`, `currencies`, `permissions`) carry no
  `tenant_id` and no RLS; the application role may only read them. Platform-operator
  tables (`platform_users`, `subscriptions`) are written only by the platform console
  path, under a separate role described in `docs/sdd/08-security-design.md`.
- Scheduled jobs that run for every tenant call `app_list_active_tenants()` and open one
  transaction per tenant with `app.tenant_id` set.
- Super admin support access to a tenant's data sets `app.tenant_id` for that tenant in
  a support session that is written to the audit log before any read.

A test suite runs against a real PostgreSQL 16 in CI, connected as `bms_app`, and proves
for every tenant-owned table that tenant A cannot read, insert, update or delete tenant
B's rows, and that with no tenant set every query errors (`docs/sdd/15-test-strategy.md`).

## Consequences

**Better:**

- A forgotten filter in application code returns no rows rather than another tenant's
  rows.
- One migration, one backup and one connection pool serve every tenant.
- The isolation property is testable and tested on every pull request.

**Worse:**

- Every migration that adds a tenant-owned table must add the policy; the RLS test
  enumerates tables from the catalogue so a missed policy fails CI.
- Cross-tenant platform analytics need a separate, audited path.
- RLS adds a predicate to every query, so `tenant_id` leads every composite index.

**Watch for:**

- Anyone connecting the application as the table owner or a superuser, which silently
  bypasses RLS. The API refuses to start if `current_user` owns any application table or
  has `rolbypassrls`.
- `SET` used instead of `set_config(..., true)`, which leaks across pooled connections.
- Background jobs that forget to set the tenant: they fail on their first query, which
  is safe and loud. Jobs also log the tenant they ran for and the row counts they touched.
- The `SECURITY DEFINER` functions growing beyond returning ids. They must stay minimal,
  owned by `bms_owner`, with a fixed `search_path`.

## Related ADRs

- ADR-001 places tenancy in the core.
- ADR-002 defines the repository layer where branch scope is applied.
