# ADR-029: One fixed low stock threshold for retail, a settings group later

## Status

Accepted (2026-10-06, review of pull request #154, issue #145). Builds on ADR-020.

## Context

The retail Stock screen has Out of stock and Low stock tabs (`stock_level=out|low` on `GET /retail/stock`
and `GET /retail/stock/all-branches`). Low stock needs a threshold. The legacy spreadsheet data carries
no reorder level per product, the tenant settings catalogue has no retail group yet, and quantities in
retail are decimals of different units (rolls, pieces, litres), so a fixed number is only a rough
signal for some of them.

## Decision

- **One threshold of 5 for every unit, product, branch and tenant.** It is the constant
  `StockService.LOW_STOCK_MILLI` (5,000 thousandths), compared with the branch balance, or in All
  branches with the total across the listed branches. A balance at or below it is low, and so is any
  balance of zero or less. The API reports the value as `low_stock_threshold` on both stock lists so
  the screens never hard-code it.
- **A per-tenant retail settings group later.** When the settings catalogue gains a retail group, the
  threshold becomes a tenant setting with 5 as its default, and a per-product reorder level can follow.
  Changing the source of the number does not change the response shape.

## Consequences

**Better:**
- The Low stock tab works on day one with no setup and no migration.
- The threshold is stated once, in the response, so a later setting needs no screen change.

**Worse:**
- 5 is wrong for fast movers (5 pieces of a bulb) and slow ones (5 rolls of cable); a tenant cannot
  tune it until the settings group exists.

**Watch for:**
- A request to change the number for one tenant is the trigger for the settings group, not for a
  second constant.
- Any new unit of measure with a very different scale (for example grams) makes the single number
  misleading; revisit this ADR then.
