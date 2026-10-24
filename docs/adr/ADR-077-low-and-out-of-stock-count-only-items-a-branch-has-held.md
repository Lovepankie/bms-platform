# ADR-077: Low and out of stock count only items a branch has held, by its minimum

## Status

Proposed (issue #490). Amends ADR-029 (the fixed threshold now applies only to an item with no minimum) and carries out the
"separate change" ADR-057 left open (the Stock list's Low stock tab moves to the minimums). Builds on ADR-076 (the Home count is the
dashboard's). No migration, no permission. Renumbered at merge if needed.

## Context

The Home "Low on stock" count, the dashboard's out and low counts and shop lines, and the Out and Low tabs of the Stock screen
judged every active catalogue item at every branch, an item with no balance row counting as zero. A business whose branches each
carry a part of a large shared catalogue saw almost the whole catalogue as low at every branch: a shop that has stock rows for about
half the catalogue showed about nine items in ten as low, because the items it never stocks were counted at zero against the fixed
threshold of 5 (ADR-029). The per-branch minimums of ADR-057 were used by the push notice and Stock health, but not by these counts.

## Decision

1. **An item is judged only where it has been held.** At a branch an item counts as out or low only when the branch has a balance
   row for it or a stock movement of it. A catalogue item a branch never carried is neither out nor low there, and still shows in
   the unfiltered list at zero.
2. **Low at one branch is at or below its level:** the branch's own minimum, else the item's minimum for every branch, else the
   fixed threshold of ADR-029. Out is zero or less. Low includes out.
3. **Over several branches together** (All branches, the overall dashboard counts, Home without a chosen branch) an item is low
   when it is low at one of them by decision 2, so a branch's own minimum counts on Home with no branch chosen as on that branch's
   line; it is out when its balance summed over them is zero or less, as before, and one of them has held it. Low still includes
   out: a sum at zero or below leaves a held branch at zero or below. This replaces the earlier rule that judged low on the summed
   balance against one threshold (an item at 4 in each of two shops was not low over both). This is issue #490's own rule (at or below the branch minimum, else the threshold) applied to each held branch in
   scope, so the Home total agrees with the per-branch lines; it adds no requirement.
4. **One rule in one place.** The SQL of these predicates lives in `retail.stock.LowStockRule` and is used by the stock lists, the
   grouped stock summary, the dashboard (counts and the line per shop) and so Home, so the figures equal the filtered lists. Every
   probe is correlated on the outer row's `tenant_id` as well as the branch and the item, so it seeks the
   `(tenant_id, branch_id, product_id ...)` indexes of the balances, the movements and the minimums whatever the row-level security
   policy adds.
5. **A branch that has held nothing shows no stock figures** on the dashboard's table by shop: its line stays, with its sales, and
   carries no `out_of_stock` or `low_stock`.

## Consequences

**Better:** the counts describe what each shop actually stocks; the owner's minimums now drive the Low tab and Home, as they
already drove the push notice; Home, the dashboard and the Stock tabs agree.

**Worse:** an item that should be stocked at a branch but never was does not show as out there; the reorder list of Stock health
and a minimum set for that branch are the way to see it once it is stocked. Over several branches an item plentiful in one shop and
low in another counts as low, where the earlier summed rule did not. The push notice keeps its strict "below the minimum" while the
counts use "at or below", as the threshold always did.

**Watch for:** the held test reads `retail_stock_balances` and `retail_stock_movements` per item and branch through their
`(tenant_id, branch_id, product_id ...)` indexes; with a catalogue of thousands of items and many branches the dashboard's per-shop
count is items times branches probes. Bench queries k15 (positions), k18 (the counts) and c10 (the Low tab) measure it. At 25
times the data (3000 items in the large tenant, 4 branches, 1.6 million movements, 8103 minimums in `seed.sql`, the VM profile)
every held and minimum probe is an index scan with `tenant_id` in its condition: the balances through
`retail_stock_balances_pkey`, the movements through `retail_stock_movements_position` and both minimums through
`retail_stock_minimums_tenant_id_product_id_branch_id_key`; medians were k15 179 ms, k18 219 ms and c10 20 ms (pull request
#517 has the plans). No index is added. The positions query keeps its per-row select in a `MATERIALIZED` CTE: flattened, the
held and minimum probes ran once per aggregate and JIT optimisation took it to a median of 1456 ms (1193 ms of it JIT). With no
branch chosen the counts cover the branches the positions report (active, or closed and still holding stock), so a closed and
emptied shop adds nothing. Measured again on the SQL of pull request #517's final head with the default profile: k15 144 ms, k18
172 ms, c10 15 ms, the same indexes. If it grows, a held flag kept on the balance row is the fix and needs a migration. A movement
makes an item held at its branch: a movement never has a quantity of zero (the check on `retail_stock_movements`), but an import
that wrote an opening movement of every catalogue item at every branch would make them all held again, so an importer writes
movements only for what a branch carries. With a closed branch that still holds stock, the overall counts include it while All
branches shows its column only with `include_closed=true`.
