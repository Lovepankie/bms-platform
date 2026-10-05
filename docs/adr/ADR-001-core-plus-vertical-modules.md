# ADR-001: Platform core plus vertical modules; lending is the first vertical

## Status

Accepted (2026-09-29). The sequencing of the retail vertical is amended by ADR-020: retail is
built now rather than after the lending MVP.

## Context

BMS Platform is a multi-tenant business management system for small and medium
enterprises in Uganda and East Africa. The product was first scoped as a retail system
(stock, point of sale, invoices, purchases, expenses, debtors). The first paying tenant
is instead a licensed money lender (Tier 4, Uganda) with loans, savings and investment
products, referred to throughout this repository as "the pilot tenant".

Two very different businesses therefore want the same things from the platform:
companies with branches, staff with roles, an audit trail, a subscription, SMS
notifications, generated PDF documents, reports, a spreadsheet import path and, above
all, correct books. They differ completely in their domain objects: a lender has
members, loans, schedules, collateral and savings; a shop has stock, sales and
purchases.

Three options were considered.

1. **Build a lending product.** Fastest to the pilot, but every shared concern (tenancy,
   ledger, reporting) is written in lending vocabulary and the retail product later
   either forks the code or inherits lending assumptions.
2. **Build a generic framework first**, with plug-in points for every conceivable
   vertical, then build lending on it. With one real vertical in hand, the extension
   points are guesses, and the framework delays the pilot.
3. **Build a platform core that owns only the concerns every business shares, and put
   each line of business in vertical modules a tenant switches on.** Lending is the
   first vertical and is built now. Retail is the second and is not built now.

## Decision

Adopt option 3.

The **core** owns: tenancy (tenants, slugs, plans, subscriptions, which modules a tenant
has switched on), branches, users, roles and permissions, staff and member
authentication, maker-checker approvals, the audit log, the double-entry general
ledger, notifications (SMS and email), document generation, the reporting framework,
the import framework, and payment intake from gateways.

A **vertical** is one module that registers with the core through a module manifest (routers, permissions, default chart of accounts
entries, posting rules, report definitions, import templates, notification templates,
scheduled jobs). The core never names a vertical and never imports a module. A tenant's
enabled modules are rows in `tenant_modules`; a request to a module the tenant has not
enabled is refused.

The **lending vertical** is the module `lending`, organised internally by sub-domain: members, products, loans, collateral, savings, investments and collections. Its tables
are prefixed `lending_`. Its scope is set in `docs/sdd/03-functional-requirements.md` and
its first cut in `docs/specs/lending-mvp-scope.md`.

The **retail vertical** (stock, point of sale, invoices, purchases, expenses, debtors)
is named here so the core is not designed as if lending were the only business, and is
explicitly out of scope until the lending MVP is live with the pilot tenant.

The core is kept small by one rule: a concern enters the core only when it is
genuinely shared by lending and retail as described in the original product plan. A
concern with exactly one known consumer stays in the vertical.

## Consequences

**Better:**

- The pilot tenant is served by a product built for lending, not a retail system with
  loans bolted on.
- The general ledger, reporting and import framework are written once, in neutral
  vocabulary, and retail inherits them.
- Which code is core and which is vertical is visible from the directory a file sits in,
  and the boundary is enforced by a test (ADR-002).

**Worse:**

- The module manifest is designed with one real vertical, so some rework is expected
  when retail is built.
- Some shared concepts look alike but are not the same (a lending member and a retail
  customer). Deliberate duplication is permitted rather than forcing a false shared
  model into the core.

**Watch for:**

- Lending vocabulary leaking into core tables or services (for example a `loan_id`
  column on a core table). Core tables reference vertical records only through generic
  `subject_type` and `subject_id` pairs.
- A "shared" feature being promoted into the core with only lending as its consumer.
- Retail work starting before the lending MVP is live. That is a scope decision for the
  dev lead, recorded on the board, not a drift.

## Related ADRs

- ADR-002 sets how modules are structured and how the boundary is enforced.
- ADR-003 covers tenant isolation, which every module inherits from the core.
- ADR-004 covers the general ledger that every module posts to.
- ADR-007 records why the offline single-device product stays separate.
