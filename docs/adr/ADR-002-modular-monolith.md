# ADR-002: Modular monolith with enforced module boundaries

## Status

Accepted (2026-09-29). The backend language and framework are deliberately not decided
here; they are the subject of pending ADR-010. This record holds whichever framework is
chosen.

## Context

Two developers, each working with a coding agent, will build the core and the lending
vertical in parallel. The system must deploy to one virtual machine per environment and
must keep money-moving operations transactionally consistent: a repayment updates the
loan, its schedule, the general ledger and the audit log, and either all of that happens
or none of it does.

Three shapes were considered.

1. **Microservices** per module. Independent deployment is worth nothing to a two person
   team on one host, and a repayment would become a distributed transaction.
2. **A plain monolith** with no internal structure. Fastest for a month, then every
   feature reaches into every other feature's tables and the retail vertical cannot be
   added without touching lending code.
3. **A modular monolith**: one deployable API, one worker, one database, one transaction
   per request, with modules whose public surface is declared and whose boundaries are
   checked in CI.

## Decision

Adopt option 3.

**Logical module map.** Names below are logical; the physical package paths follow the
framework chosen in pending ADR-010.

| Layer | Modules |
|---|---|
| Core | `tenancy`, `identity` (users, roles, auth), `audit`, `approvals`, `ledger`, `notifications`, `documents`, `reporting`, `imports`, `payments` |
| Shared kernel | `kernel`: money type, tenant context, error types, clock. Types and primitives only, no business logic |
| Vertical: lending | `lending`, with internal sub-domains `members`, `products`, `loans`, `collateral`, `savings`, `investments`, `collections` |
| Vertical: retail | `retail` (future, ADR-001) |

Every module has a declared **public interface** (service operations, data transfer
types, published events). Everything else in the module is internal.

**Boundary rules.** A module boundary test runs in CI and fails the build on any
violation:

1. The core never depends on a vertical module.
2. A module uses another module only through that module's public interface.
3. One vertical never depends on another vertical; verticals interact only through core
   services and core events.
4. The shared kernel depends on nothing else in the system.
5. The module dependency graph is acyclic (the allowed directions are in
   `docs/sdd/05-architecture.md`).
6. A module never writes another module's tables. Database foreign keys from a vertical's
   tables to core tables are allowed; object-relational mappings that navigate across a
   module boundary are not.

Inside the lending module, sub-domains call each other through their service layer,
never through another sub-domain's data access code. That rule is checked in review.

**Consistency.** Money-moving work is synchronous and in one transaction: a module calls
the ledger's posting operation (ADR-004) inside the same database transaction as its own
writes. Side effects that may lag (SMS, PDF generation, report builds) are written to a
transactional outbox in the same transaction and executed by the background worker after
commit (the worker technology is pending ADR-008).

## Consequences

**Better:**

- One transaction covers a repayment end to end, so the loan subledger and the general
  ledger cannot disagree after a crash.
- The two developers can own different modules and meet at declared interfaces.
- The deployable is one API image and one worker image, which suits one host per
  environment.
- The decision survives the framework choice: both candidate stacks can enforce these
  rules with a test.

**Worse:**

- A call through a declared interface is more ceremony than reaching into another
  module's model.
- The boundary test must be kept fast and accurate, or people learn to ignore it.

**Watch for:**

- A public interface growing into a re-export of everything, which defeats the rule. A
  public operation should exist because a named caller needs it.
- Business logic that only lending uses accumulating in the core (ADR-001: a concern
  enters the core only when it is shared).
- A report that joins across the core and a module. Reports read through read-only SQL
  views owned by the module that owns the data; they never write.

## Related ADRs

- ADR-001 defines the core and the vertical modules this layout contains.
- ADR-004 defines the posting operation every money-moving module calls.
- Pending ADR-010 chooses the backend framework and therefore the enforcement mechanism.
