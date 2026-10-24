# ADR-079: Profit from the moving weighted average cost per smallest unit, kept per branch

## Status

Proposed (owner decision D22 of 24 Oct 2026, issue #523; docs/specs/units-packs-and-costing.md section 3 and section 9 step 1),
accepted on merge. Settles the record ADR-020 decision 6 left open as pending ADR-021, which is never written: this record takes
its place. Supersedes ADR-020 decision 6, amends ADR-020 decisions 5 and 8 and the ADR-020 transfer amendment ("at the source's
current cost"), and amends the ADR-033 amendment for landed cost and supplier credits (#199) where it says the valuation is
balance times the product's one cost. Migration `V94`; renumbered at merge if needed.

## Context

Until now a product had one cost, `retail_products.cost_minor`, and the latest restock overwrote it (ADR-020 decision 5). A sale
line snapshotted that cost, and stock was valued at balance times that cost. When two deliveries of different carton sizes or
prices are on the shelf together, the profit of every sale after the second delivery is worked out as if every piece had cost what
the last carton cost, and the stock is revalued by every delivery without a journal (the "revaluation difference" of ADR-020
decision 8). George buys cartons of 24 and of 30 at different prices and sells in cartons, dozens and pieces; the owner wants the
day's profit right per piece (spec section 1 and 3).

The method was always meant to be the moving weighted average (ADR-020 decision 6). Two questions were open: whether the average is
one figure for the business or one per branch, and what happens to remainders, voids, transfers and history.

## Decision

1. **One average per branch and product, on the balance row.** `retail_stock_balances.avg_cost_minor` is the average cost of one
   base unit (the smallest unit) at that branch, in whole minor units. It is changed only by `StockLedger`, under the balance row's
   lock, in the transaction of the movement, so it is serialised with the quantity it averages over. Per branch and not per
   business because:
   - each branch already has its own inventory account and its own journal entries (ADR-020 decision 7). A business-wide average
     would revalue the stock of every other branch on each delivery with no entry there, which is the revaluation difference
     ADR-020 decision 8 hoped this method would remove; per branch, a delivery changes only the books and the valuation of the
     branch it went to;
   - the balance row is the lock every sale, restock and transfer already takes. A business-wide average would need the product
     row or every branch's balance locked by every sale, or a receipt in one branch could average over units a concurrent sale in
     another branch is taking out;
   - costs do differ by branch when shops buy for themselves, and a transfer carries the cost (decision 4), so a store that buys
     and moves to its shops gives each shop the store's cost.
   The cost: there is no one "average cost" of an item for the whole business. Where a screen shows one (the all-branches stock list,
   the location report's item line) it is the average of the branches that hold stock, weighted by what each holds.
2. **The formula and the remainder.** A movement whose cost is the branch's average leaves the average as it is. Any other movement
   re-averages: (on hand x average + quantity x cost) / (on hand + quantity), rounded half up to the minor unit
   (`Quantities.average`). Stock coming into a branch that holds none, or less than none (imported history), takes its own cost per
   unit; when nothing is left the average is kept. The value that came in less what the new average carries is the remainder; it is
   posted per branch, on the movement's date, between inventory and purchase rounding (5120, the V39 account), in one entry of
   source `retail.average_cost` keyed by the first movement it covers. It has its own source so that a void, which reverses a
   document's own entries, never reverses it: the void's movements re-average and post their own remainder. With it the
   inventory account of a branch follows its valuation at the average, apart from the half minor unit each line's value rounds.
3. **What re-averages, and what leaves at the average.**
   - In, at its own cost: a restock and goods received (the cost per base unit of the line, after the pack cost's own remainder,
     which is still posted by the restock as before), a transfer in (decision 4), a customer return put back into stock (at the
     share of the sale line's cost snapshot the return already takes), the void of a sale (at the line's snapshot), the cancelling
     of a dispatch, an import's opening balance (decision 7).
   - Out, at the branch's average: a sale, usage and damage, a stock correction and a count's difference (up or down: a count that
     finds more pieces adds them at the average), a transfer out.
   - Value only: a landed cost and a supplier price credit put their inventory part (ADR-033 #199, spread over the branches that hold
     the goods in proportion to what each holds) into those branches' averages (`StockLedger.revalue`). The part each branch took
     is recorded (`retail_cost_spreads`), and the void takes exactly those parts back from exactly those branches, as its reversed
     entries take them out of their inventory accounts; when a branch has since sold so much of the product that its part would
     leave the rest below zero, the void is refused (`average_cost_negative`, "It cannot be voided now"); a price credit worth more
     than a branch's stock is carried at is refused when it is recorded, in words about the credit ("Record a smaller price
     credit, or send the goods back to the supplier instead"). A void locks every balance row it will revalue, the recorded
     branches included, in one pass in branch and product order after the product rows. A line posted before
     `V94` has no recorded parts and its void spreads its part over what each branch holds then.
   - Undoing an earlier movement (the void of a restock, a goods receipt, a customer return or a transfer, goods sent back to a
     supplier) takes its units out at the cost they came in at, which re-averages what stays. Goods sent back to a supplier leave at
     what the receipt's units are carried at (#199), not at the average, so the supplier's credit, the price variance and the
     inventory account stay as #199 built them; what stays is re-averaged. When sales at a higher average since would leave the
     rest below zero the return is refused with its own words ("Send back fewer, or ask the supplier for a price credit
     instead").
4. **A transfer carries the cost.** A transfer out leaves the source at the source's average (the line's `unit_cost_minor`, as
   before but now the average and not the product's cost); the transfer in, at once or when a two-step dispatch is received, brings
   the units in at that cost and re-averages the destination. The clearing legs still net to zero, the business's valuation does not
   change, and a later delivery at one end does not reach the other.
5. **Voids and corrections re-average or are refused in plain words.** Undoing a restock after some of the goods were sold at a
   higher average than they cost can leave the rest at a cost below zero; the ledger refuses that with 422 `average_cost_negative`
   ("this would leave the rest of the stock at this branch at a cost below zero ... Use Correct the quantity on the stock list, or
   count the stock"). Every other void re-averages: when it empties the branch, what the average cannot carry is the remainder of
   decision 2, the cost error of the goods already sold, put to purchase rounding on the void's day rather than restating those
   sales. The existing guards stay (a restock whose stock was sold, used or moved since cannot be voided).
6. **`retail_products.cost_minor` is the last price paid.** It is still set by the latest restock or receipt, taken back by a void
   while it is the latest price event, raised by a landed cost under the #199 guard, and editable by hand with its history row
   (ADR-020 decision 5). It no longer values stock or costs a sale: it is shown as **Last paid** and used to set prices. A branch's
   first average, before it has held the product, is the last paid cost; a manual change of the last paid cost does not move an
   average.
7. **Imports.** History posts nothing (ADR-020 decision 9), so historical movements do not re-average; the opening balance
   (`legacy_balance`) of an import is valued, in the opening journal, at the cost it carries: into a branch that holds nothing it
   becomes the branch's average, added to stock already held it re-averages with it, and a legacy balance that takes stock away
   leaves the average alone. History posts nothing, so the rounding of that re-average (at most half a minor unit per unit held)
   is not posted either and shows as part of the revaluation difference against the opening journal.
8. **Profit is the sale lines' snapshots; history never changes.** A sale line keeps the branch's average at the moment of sale in
   `unit_cost_minor`, and `line_cost_minor` is the base quantity times it, whether the line was sold in cartons, dozens or pieces.
   The daily profit, the margins, the sales analysis, the dashboard and the evaluation already add up `line_cost_minor` (and returns
   their own snapshot), so they are unchanged and a later delivery never restates a past day. The price floor of a sale (not above
   cost, ADR-020 decision 5 and ADR-033 decision 3) is judged against the branch's average. The below-cost flags of price lists
   still read the last paid cost; selling packs with their own prices is spec step 3.
9. **Valuation reads the averages.** Every read of stock at cost (stock lists, stock by location, the valuation, stock health, the
   dashboard, the evaluation, a branch's holdings before it closes) multiplies a balance by its own branch's average.
10. **Migration `V94`.** One column with a constant default and a range check (`NOT VALID`, validated after the backfill), and a
    backfill that sets every balance row's average to its product's cost: until now every unit was valued at that cost, so it is
    today's average and nothing moves on the day of the deploy. The constant default is then replaced by a trigger that starts
    a row inserted without an average at the product's last paid cost (as `StockLedger` does), so an application instance from
    before the migration keeps working during a deploy and no row ever starts at zero. It adds the append-only
    `retail_cost_spreads` (decision 3) and follows V92's handling of an owner without `BYPASSRLS`. The backfill updates every
    balance row in the transaction that took ACCESS EXCLUSIVE for the new column, so stock writers wait for it: well under a
    second at staging's size; a business with millions of balance rows would want it batched in a migration of its own.

## Consequences

**Better:** profit per piece is right when cartons of different sizes and prices are on the shelf together, whichever way the goods
were sold; a branch's inventory account follows its valuation; a delivery to one branch no longer revalues another; transfers carry
cost; profit history never changes.

**Worse:** an extra small journal entry for a delivery or void whose average does not divide; the average is rounded to the minor
unit, so a remainder of up to half a minor unit per unit on hand goes to purchase rounding each time it is re-averaged; the
business has no single average of an item (decision 1); goods sent back to a supplier leave at their receipt's carrying cost, not the
average, so they move the average of what stays; a void that empties a branch after mixed deliveries and sales puts the cost error of
those sales into purchase rounding on the void's day. The valuation as of an earlier date multiplies the quantity on that date by
each branch's average today: the average's history is not kept, so a past valuation is today's costs applied to that day's stock.

**Watch for:** a sale (after the buyer's lock, spec 5.2), a usage report and a transfer (both branches, in branch and product order)
take the balance rows' locks before they read the average, so a delivery committing meanwhile cannot leave them costed at the
average it replaced; a caller that reads the average without the lock still keeps the books in agreement (the ledger re-averages
what stays), but its cost may be a moment old. Anything that writes a stock movement must give its cost as above: at the average for stock leaving,
at what it cost for stock coming in; a movement at another cost is a re-average, not an error. A future business-wide view of cost
(a "what this item costs us" figure for price setting) must weight the branches' averages by what each holds, as the stock lists do.
