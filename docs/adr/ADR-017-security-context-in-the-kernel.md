# ADR-017: The principal types live in the shared kernel

## Status

Accepted (2026-09-29). Refines the module layout of ADR-002 for MVP increment 1 (issue #7).

## Context

The foundation put `Principal`, `CurrentPrincipal`, `RequiresPermission` and `PublicEndpoint` in
`core.identity`, and the audit writer depended on identity to read the actor. Increment 1 makes
identity write audit rows (sign-in, MFA and user events, FR-AUD-03), so identity would depend on
audit and audit on identity: a cycle, which ADR-002 forbids and the boundary test rejects. The
audit search endpoints (FR-AUD-04) also need the principal's branch scope inside the audit
module.

Options: move the audit writer into identity; send security events to audit as application events
instead of calls; or move the small, dependency-free security context types to the kernel, next to
`TenantContext`.

## Decision

- `Principal`, `CurrentPrincipal`, `RequiresPermission`, `PublicEndpoint` and the new
  `AuthenticatedEndpoint` live in `kernel`. `Principal` carries, for each permission, the branches
  it applies in, so a user who is branch manager at A and loan officer at B holds
  `lending.loans.approve` at A only (chapter 8 section 8.3.1).
- Only `core.identity` sets or clears the current principal, and only `core.tenancy` binds a
  request's tenant; `SecurityArchitectureTest` enforces both.
- `core.audit` depends on the kernel only; `core.identity` depends on `core.audit`,
  `core.tenancy` and `core.notifications`.

## Consequences

**Better:**

- No cycle; every module reads the principal the same way it reads the tenant.
- Per-permission branch scope is one type every module uses for list filters (NFR-ISO-04).

**Worse:**

- The kernel grows by five small types, and setting the principal is a public method guarded by a
  test rather than by visibility.

**Watch for:**

- Keep the kernel free of behaviour that needs a database or another module; these types are data
  and a thread-local only.
