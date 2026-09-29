# ADR-016: Platform operations run through SECURITY DEFINER functions until the platform role exists

## Status

Accepted (2026-09-29). Implements the platform API of chapter 7 section 7.11.3 in MVP increment 1
(issue #7). To be superseded when the `bms_platform` database role and its connection pool are
introduced (chapter 6 section 6.3.1).

## Context

FR-TEN-01, FR-TEN-03 and FR-TEN-05 have a platform operator create tenants, switch modules and
move subscriptions. Chapter 6 gives `bms_app` SELECT only on `tenants`, `tenant_modules` and
`subscriptions`, and plans a separate `bms_platform` role for the platform console. A second role
means a second connection pool, a second set of grants, and a rule for which pool each request
uses; chapter 6 also says `bms_platform` has no access to tenant-owned tables, yet creating a
tenant writes its head office, its first admin and its audit row, which are tenant-owned.

Options: build the `bms_platform` role and pool now; grant `bms_app` write access to the tenancy
tables; or keep the grants as they are and let owner-defined functions make exactly the changes
the platform needs.

## Decision

- Tenants, subscriptions and modules are changed only by four `SECURITY DEFINER` functions owned
  by `bms_owner`, with a fixed `search_path`, created by migration V2 and executable by `bms_app`:
  `platform_create_tenant`, `platform_set_tenant_modules`, `platform_set_subscription_status` and
  `platform_list_tenants`. Each validates its input (plan exists, modules allowed by the plan) and
  returns nothing a tenant could not already read about itself. `bms_app` keeps SELECT only on
  the three tables.
- Work inside one tenant (the first admin, the invitation, the tenant's audit row, an admin MFA
  reset) runs as `bms_app` under row-level security, in a transaction opened inside
  `TenantContext.callAs(tenant)`, so the transaction manager binds the tenant exactly as for a
  request. Only the jobs module and the platform console may call `callAs`; an architecture test
  enforces it.
- The platform API is served only on a host that names no tenant (`app.<base domain>`), accepts
  only platform operator tokens, and every route declares a platform permission
  (`platform.tenants.read`, `platform.tenants.manage`) that no tenant role holds.
- `platform_users`, `platform_sessions`, `platform_user_recovery_codes` and `platform_audit_log`
  are platform tables without a tenant policy; until `bms_platform` exists `bms_app` holds the
  grants on them (`platform_audit_log`: SELECT and INSERT, append-only). No tenant endpoint reads
  them.

## Consequences

**Better:**

- Tenant onboarding moves from a psql script to an audited API call without widening what the
  application role can do to tenancy tables.
- One connection pool and one role for the application, as today.

**Worse:**

- A defect in the application role's code path could call the platform functions; the host rule,
  the platform token and the platform permissions are the barriers, not a database role.
- The platform tables are readable by `bms_app`, which chapter 6 reserved for `bms_platform`.

**Watch for:**

- Introduce `bms_platform` (and supersede this record) before the support session of FR-TEN-07,
  which reads tenant data on a platform operator's behalf.
