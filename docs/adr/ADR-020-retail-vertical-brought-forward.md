# ADR-020: Retail vertical brought forward; stock as append-only movements; retail events post to the ledger

## Status

Proposed (2026-10-05), accepted on merge. Amends one sentence of ADR-001: retail is no longer
"out of scope until the lending MVP is live". Everything else in ADR-001 stands.

## Context

ADR-001 named retail as the second vertical and deferred it. A multi-shop electrical retailer
now needs to move onto the platform, and a second retail business is expected to follow. The
retailer runs today on a spreadsheet with a form app on top. That system shows the failure
modes a platform must prevent:

- Per-shop stock is held as editable cells that scripts add to and subtract from. A script that
  misfires, runs twice or writes the wrong column leaves the number wrong with no trace.
- Prices are copied between sheets by scripts. When that copy was lost in a rewrite, restock
  prices stopped reaching the product record and nobody saw an error.
- Derived totals are formulas that break when rows are appended by the app.

Lending is being built in parallel by another developer. Retail uses core modules only and
does not touch lending packages.

## Decision

1. **Retail is built now** as the module `retail` (key `retail`, tables prefixed `retail_`),
   registered with the core through a module manifest like lending (ADR-001). A tenant switches
   it on. The core rule of ADR-001 still holds: a concern enters the core only when two
   verticals share it.
2. **Sub-domains:** catalogue, stock, sales, purchasing, adjustments, customers (credit buyers),
   valuation. The package layout copies `lending.members`.
3. **Stock is a ledger of movements.** `retail_stock_movements` is append-only: no UPDATE or
   DELETE privilege for the application role and a trigger that rejects both, the same pattern
   as journals (ADR-004). Kinds: opening, purchase, sale, usage, damage, adjustment, return,
   legacy_balance. Quantity is a signed `numeric(14,3)` per branch and product, so metres and
   rolls are exact. `retail_stock_balances` is maintained by the module inside the same
   transaction, and a reconciliation job checks it against the sum of movements. Nothing edits
   a balance directly; a stock-take writes an adjustment movement. Transfers between branches
   are reserved and not in the first release.
4. **Negative stock is allowed by default.** The tenant setting `retail.allow_negative_stock`
   defaults to true because shops sell before the purchase is recorded; refusing the sale loses
   the sale. A negative balance is flagged on every stock view until a purchase or stock-take
   corrects it.
5. **Prices live on the product and are written by the event that changes them.** The product
   holds the current `cost_minor` and `sell_minor`. `retail_price_history` is append-only. A
   restock line that carries a price sets it in the same transaction as the movement (latest
   restock wins) and writes a history row. A manual price edit needs `retail.price.edit` and
   writes a history row. A sale line snapshots the unit cost and unit price at the time of sale,
   and profit is computed from the snapshot, never from the current price.
6. **Cost basis in the first release is the product's cost at the time of sale.** Weighted
   average cost is a later per-tenant option and will need its own record (pending ADR-021).
7. **Every financial retail event posts through `post_entry`** in the same transaction, one
   entry per branch: a sale (debit cash, mobile money, bank or trade debtors; credit sales), its
   cost (debit cost of goods sold; credit inventory at the snapshot cost), a restock (debit
   inventory; credit cash, bank or trade creditors), usage and damage (debit shrinkage; credit
   inventory), and a void by a reversing entry. A default retail chart of accounts with
   `system_key` accounts for these is seeded when the module is switched on.
8. **Known simplification:** inventory is posted at purchase cost and relieved at the product's
   current cost, so the inventory control account and the stock valuation at current cost can
   differ after a price change. The reconciliation reports that as a revaluation difference and
   does not fail. Weighted average cost removes it.
9. **History import posts no journals.** Imported sales, purchases and usage are movements and
   documents marked `historical`. One opening journal per branch records inventory at current
   valuation against the opening balance equity account. A `legacy_balance` movement per branch
   and product makes the balance equal the source figure, and the first stock-take replaces it.
10. **Permissions:** a `retail.*` catalogue (sale create and read, stock read, purchase create,
    usage report, price edit, stock-take commit, catalogue manage, profit read). Profit read is
    its own permission, held by admins only. Branch scope applies (ADR-017).
11. **Retail is generic.** No field exists for one tenant. A second retail tenant is the module
    switch plus a catalogue import; differences are settings, categories and units.

## Consequences

Better: the drift and frozen price classes of failure cannot occur by construction, because
stock is derived from movements and a price changes only inside the event that carries it.
Retailers get real books for the first time. Valuation is consistent the moment a price or a
quantity changes. One module serves several retail tenants.

Worse: more tables and posting rules. The balance table has to be kept in lockstep with the
movements, hence the reconciliation job. Two journal entries per sale double the ledger row
count; end-of-day batching is a later option. Valuation at current cost and the inventory
account can differ (decision 8).

Watch: Flyway versions are one sequence shared with the parallel lending work, so the retail
migration takes the next free number at merge time. `ModularityTest` must keep retail from
depending on lending. The cash book (expenses, banking, advances, a daily savings target) is
out of the first release and is likely partly core, because lending needs cash handling too
(pending ADR-022).
