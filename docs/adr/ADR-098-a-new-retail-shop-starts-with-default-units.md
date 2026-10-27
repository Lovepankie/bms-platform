# ADR-098: A new retail shop starts with default units

## Status: Accepted

## Context
On 27 Oct 2026 a tester's new shop could not save its first item (issue #618). Add an item needs a unit ("Counted in"), and a business got the retail module with no unit at all; Units sits behind Catalogue and nothing pointed to it. The team added two units by hand to unblock the tester. Every new shop would meet the same dead end.

The core switches modules on (`core.platform`, FR-TEN-01, FR-TEN-03) and must never name a vertical (chapter 5 section 5.4.3). Until now a vertical's work at switch-on was only the chart of accounts, seeded inside the database functions `platform_create_tenant` and `platform_set_tenant_modules`.

## Decision
1. A business that gets the retail module, when it is created with it or when retail is switched on later (by the operator, by the Owner's own request, or by the job that applies a scheduled change), gets the units Piece, Pair, Set, Box, Pack, Kg, Litre and Metre. A name the business already has, in any case, is skipped, so switching retail off and on again adds only what is missing. Each unit made is audited as `retail.unit.created` by the system, with `created_by` empty.
2. The core calls a new registry, `ModuleSetUp` in `core.tenancy` (one bean per vertical that needs it), for every module just switched on, in the same transaction as the switch and bound to that tenant, so the set-up commits or rolls back with it. `retail.catalogue` supplies `DefaultUnits` through the existing unit insert. The operator's module switch now runs bound to the tenant (`TenantContext.callAs`), as the Owner's and the job's switches already did.
3. Migration V109 gives the same eight names to every retail business, not closed, that has no unit at all. A business with any unit keeps exactly the units its owner chose.

## Consequences
- Better: a new shop can save its first item at once; the frontend (#616) still lets the owner add a unit inline.
- Worse: the eight names are English and the same for every shop; an owner who never sells by the metre sees Metre until they switch it off. They can rename or switch off any of them.
- The list is written twice, in `DefaultUnits` and in V109; a later change to the defaults changes the Java list only (V109 never runs again).
- A vertical's other set-up at switch-on (for example the chart of accounts) can move to `ModuleSetUp` later; it stays in the database functions for now.
