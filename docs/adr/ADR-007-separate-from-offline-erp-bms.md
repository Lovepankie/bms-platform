# ADR-007: bms-platform and ERP_BMS are separate products

## Status

Accepted (2026-09-29)

## Context

The organisation already has `ERP_BMS` (repository `RincolTech-Solutions-ltd/ERP_BMS`),
an offline, single-device Flutter application for very small shops. It keeps its data in
SQLite on the device and backs up to the owner's cloud drive. It has no server, no
tenancy, no branches, no multi-user access and no general ledger.

`bms-platform` is a multi-tenant server platform: many companies, many branches per
company, many staff per branch, members and customers with their own portal access, a
double-entry ledger and regulator-grade reporting.

The names are similar and both are "business management systems", so there is a
recurring temptation to treat one as a version of the other: to port ERP_BMS screens
into the platform, or to grow ERP_BMS into a server product.

## Decision

- The two are separate products with separate repositories, release cycles and data
  stores. Neither depends on the other at build time or at run time.
- `bms-platform` does not reuse ERP_BMS code or its data model. Where a retail concept
  exists in both, the platform's design is taken from the platform's own requirements.
- ERP_BMS continues to serve very small, single-device, offline shops.
- A future one-way synchronisation from ERP_BMS devices into a `bms-platform` tenant (for
  a shop that outgrows one device) is possible and **out of scope**. If it is ever
  built, it will be designed as an import path through the platform's import framework,
  with its own ADR, not as shared code.

## Consequences

**Better:**

- Each product can be designed for its real users: offline-first on one device, or
  online multi-user with a ledger.
- No accidental coupling: a change in one cannot break the other.

**Worse:**

- Some retail concepts (items, sales, receipts) will be designed twice.
- A shop moving from ERP_BMS to the platform needs a migration path that does not exist
  yet.

**Watch for:**

- Requests to "just add a server" to ERP_BMS, or to "just reuse the ERP_BMS screens" in
  the platform. Both reopen this decision and need a superseding ADR.
- Marketing material that presents the two as one product.

## Related ADRs

- ADR-001 names retail as the platform's second vertical, which is where any ERP_BMS
  migration path would land.
