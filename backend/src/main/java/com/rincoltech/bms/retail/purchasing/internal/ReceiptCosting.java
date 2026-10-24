package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.purchasing.internal.LotRepository.Lot;
import com.rincoltech.bms.retail.purchasing.internal.LotRepository.MovementKey;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.StockLedger;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What a charge added to or taken from a received line does to the books and the product's cost (ADR-033, issue #199
 * amendment, trade-capabilities.md section 7.2 and 7.5).
 *
 * <p>Stock is not tracked by lot, so which units of a product are the ones this receipt brought is read first in, first out
 * over the units of the product on hand in every branch: the balances, less the stock that came in after the receipt, up
 * to the quantity it received. The product's cost is one figure for the whole business and the valuation is balance times
 * cost, so a change of the cost per unit revalues those units wherever they are. The ledger follows the valuation: the
 * cost change per unit (the charge over the quantity received, rounded down so that no more is capitalised than was
 * charged) times those units is debited to the inventory of the branches that hold the product, in proportion to what each
 * holds, and the rest of the charge, which belongs to units already sold, used or still in transit, to cost of goods sold in
 * the receiving branch. When the cost cannot move (a newer cost event set it since) the share for the units on hand goes to
 * inventory all the same and the difference to the valuation is left to the revaluation difference; when the charge is
 * under one minor unit per unit it is all expensed. A newer cost is never overwritten, and a history row says the cost was
 * not applied.
 */
@Component
class ReceiptCosting {

    /**
     * @param onHand the units of the line still on hand in every branch together
     * @param inventory the part debited (or, for a credit, credited) to inventory, the sum of {@code byBranch}
     * @param gone the part for units already sold, used or in transit, to cost of goods sold in the receiving branch
     * @param byBranch the inventory part by branch
     * @param costBefore the product's cost per base unit before
     * @param costAfter the product's cost per base unit after (equal to before when not applied)
     * @param applied whether the cost moved
     * @param note why the cost did not move, null when it did
     */
    record Effect(
            BigDecimal onHand,
            long inventory,
            long gone,
            Map<UUID, Long> byBranch,
            long costBefore,
            long costAfter,
            boolean applied,
            String note) {}

    static final String NEWER =
            "A newer receipt, restock or price change set the cost after this receipt, so the cost was left as it is.";
    static final String TINY =
            "The charge is under one minor unit per unit, so the cost did not move and it was expensed.";

    private final LotRepository lots;
    private final StockLedger stock;
    private final RetailCatalogue catalogue;

    ReceiptCosting(LotRepository lots, StockLedger stock, RetailCatalogue catalogue) {
        this.lots = lots;
        this.stock = stock;
        this.catalogue = catalogue;
    }

    /**
     * Takes the stock balance rows of every branch that holds one of the lots' products, in branch and product order, so
     * what is on hand cannot change while a charge is worked out. Call it after the products are locked.
     */
    Map<UUID, Set<UUID>> lockStock(Collection<Lot> forLots) {
        return lockStock(forLots, Map.of());
    }

    /**
     * As {@link #lockStock(Collection)}, for a void: also the balance rows of the branches the voided lines' recorded parts
     * went to ({@code lineProducts}: line id to product), which may hold none of the product now, all in one pass in branch and
     * product order, so the void takes every balance lock it will need before any revaluation (ADR-079).
     *
     * @return the rows locked, by branch: a charge is spread only over these (see {@link #apply}), so a branch whose first row
     *     of the product a transfer made after this read is never revalued with a lock taken out of order
     */
    Map<UUID, Set<UUID>> lockStock(Collection<Lot> forLots, Map<UUID, UUID> lineProducts) {
        TreeMap<UUID, TreeSet<UUID>> byBranch = new TreeMap<>();
        for (Lot lot : forLots) {
            byBranch.computeIfAbsent(lot.branchId(), k -> new TreeSet<>()).add(lot.productId());
            for (UUID branch : lots.balances(lot.productId()).keySet()) {
                byBranch.computeIfAbsent(branch, k -> new TreeSet<>()).add(lot.productId());
            }
        }
        lineProducts.forEach((line, product) -> lots.spread(line)
                .keySet()
                .forEach(branch ->
                        byBranch.computeIfAbsent(branch, k -> new TreeSet<>()).add(product)));
        byBranch.forEach((branch, products) -> products.forEach(product -> stock.lock(branch, product)));
        return Collections.unmodifiableMap(new TreeMap<UUID, Set<UUID>>(byBranch));
    }

    /** The units of the lot still on hand in every branch together, first in first out. */
    BigDecimal onHand(Lot lot) {
        MovementKey key = lots.receiptMovement(lot.receiptLineId()).orElse(null);
        if (key == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal held = lots.balances(lot.productId()).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return Allocation.remaining(
                held, lots.laterInflow(null, lot.productId(), key), lot.qty().subtract(lot.returnedQty()));
    }

    /** The units of the lot still on hand in its own branch, first in first out: what can go back to a supplier or leave by a transfer. */
    BigDecimal onHandInBranch(Lot lot) {
        MovementKey key = lots.receiptMovement(lot.receiptLineId()).orElse(null);
        if (key == null) {
            return BigDecimal.ZERO;
        }
        return Allocation.remaining(
                stock.balance(lot.branchId(), lot.productId()),
                lots.laterInflow(lot.branchId(), lot.productId(), key),
                lot.qty().subtract(lot.returnedQty()));
    }

    /** The effect of a share on a lot, worked out without changing anything: for the preview ({@code documentId} may be null). */
    Effect estimate(Lot lot, long share, boolean raise, UUID documentId) {
        return estimate(lot, share, raise, documentId, null);
    }

    /** As above, spread only over the balance rows in {@code locked} (null: every branch that holds the product). */
    private Effect estimate(Lot lot, long share, boolean raise, UUID documentId, Map<UUID, Set<UUID>> locked) {
        BigDecimal onHand = onHand(lot);
        Map<UUID, BigDecimal> balances = held(lot.productId(), locked);
        long cost = catalogue.find(lot.productId()).map(p -> p.costMinor()).orElse(0L);
        MovementKey key = lots.receiptMovement(lot.receiptLineId()).orElse(null);
        long perUnit = perUnit(share, lot.qty());
        if (key != null && lots.newerCostEvent(lot.productId(), key, documentId)) {
            long inventory = Allocation.onHandPart(share, onHand, lot.qty()).inventory();
            return new Effect(
                    onHand, inventory, share - inventory, spread(lot, inventory, balances), cost, cost, false, NEWER);
        }
        if (perUnit == 0) {
            return new Effect(onHand, 0, share, Map.of(), cost, cost, false, TINY);
        }
        long moved = raise ? perUnit : Math.min(perUnit, cost);
        long inventory = Math.min(
                share,
                BigDecimal.valueOf(moved)
                        .multiply(onHand)
                        .setScale(0, RoundingMode.FLOOR)
                        .longValueExact());
        return new Effect(
                onHand,
                inventory,
                share - inventory,
                spread(lot, inventory, balances),
                cost,
                moved(cost, perUnit, raise),
                true,
                null);
    }

    /** What each branch holds of the product, limited to the rows in {@code locked} when it is not null. */
    private Map<UUID, BigDecimal> held(UUID productId, Map<UUID, Set<UUID>> locked) {
        Map<UUID, BigDecimal> balances = new TreeMap<>(lots.balances(productId));
        if (locked != null) {
            balances.keySet().removeIf(b -> !locked.getOrDefault(b, Set.of()).contains(productId));
        }
        return balances;
    }

    /**
     * The part by branch, in proportion to what each holds; all in the receiving branch when nothing is held. Every part is
     * above zero: a branch whose share rounds to nothing is left out, and only branches holding more than nothing count.
     */
    static Map<UUID, Long> spread(Lot lot, long inventory, Map<UUID, BigDecimal> balances) {
        Map<UUID, Long> out = new LinkedHashMap<>();
        if (inventory == 0) {
            return out;
        }
        if (balances.isEmpty()) {
            out.put(lot.branchId(), inventory);
            return out;
        }
        List<UUID> branches = List.copyOf(balances.keySet());
        long[] basis = branches.stream()
                .mapToLong(b -> balances.get(b).movePointRight(Quantities.SCALE).longValueExact())
                .toArray();
        long[] parts = Allocation.spread(inventory, basis);
        for (int i = 0; i < branches.size(); i++) {
            if (parts[i] > 0) {
                out.put(branches.get(i), parts[i]);
            }
        }
        return out;
    }

    /**
     * Works the share out and moves the product's cost, in the caller's transaction, with the product and the stock
     * balances locked by the caller.
     *
     * @param raise true for a landed cost (the cost goes up), false for a supplier price credit (it goes down)
     */
    Effect apply(
            Lot lot,
            long share,
            boolean raise,
            UUID documentId,
            String documentNo,
            LocalDate on,
            String sourceType,
            Map<UUID, Set<UUID>> locked) {
        Effect e = estimate(lot, share, raise, documentId, locked);
        if (e.applied()) {
            catalogue.applyCostChange(
                    lot.productId(),
                    e.costAfter(),
                    documentId,
                    (raise ? "Landed cost " : "Supplier credit ") + documentNo + " on " + lot.receiptNo()
                            + (raise ? ": cost raised" : ": cost lowered"));
        } else {
            catalogue.noteCostNotApplied(
                    lot.productId(),
                    documentId,
                    (raise ? "Landed cost " : "Supplier credit ") + documentNo + " on " + lot.receiptNo()
                            + ": cost not applied. " + e.note());
        }
        // ADR-079: the part each branch's inventory takes re-averages that branch's cost, whatever happened to the last
        // paid cost above. The caller locked the product rows, then lockStock the balances in branch and product order,
        // and the spread names only rows it locked, so this takes no lock out of that order.
        e.byBranch().forEach((branch, part) -> requireLocked(locked, branch, lot.productId()));
        e.byBranch()
                .forEach((branch, part) -> stock.revalue(
                        on, branch, lot.productId(), raise ? part : -part, sourceType, documentId, false));
        return e;
    }

    /** ADR-079: keeps the parts a line put into each branch's average, for its void (see {@link #revalueBack}). */
    void recordSpread(UUID lineId, Lot lot, Effect e) {
        lots.insertSpread(lineId, lot.productId(), e.byBranch());
    }

    /**
     * Undoes a cost move when the document is voided, by the document's own change per unit: the cost now less what the
     * document added (or plus what it took off). Documents of this kind may be voided in any order and each takes back
     * exactly its own part. It is refused, with a history row saying so, only when a different kind of cost event (a later
     * receipt or restock, or a manual price change) has set the cost since, because then the arithmetic cannot be separated.
     */
    void restore(Lot lot, long costBefore, long costAfter, UUID documentId, String documentNo) {
        MovementKey key = lots.receiptMovement(lot.receiptLineId()).orElse(null);
        boolean newer = key != null && lots.newerCostEvent(lot.productId(), key, documentId);
        if (!newer) {
            long now = catalogue.find(lot.productId()).map(p -> p.costMinor()).orElse(costAfter);
            long target = Math.max(0, now - (costAfter - costBefore));
            catalogue.applyCostChange(
                    lot.productId(),
                    target,
                    documentId,
                    "Void of " + documentNo + " on " + lot.receiptNo() + ": its change of the cost taken back");
        } else {
            catalogue.noteCostNotApplied(
                    lot.productId(),
                    documentId,
                    "Void of " + documentNo + " on " + lot.receiptNo()
                            + ": cost not put back, a different cost event has set it since.");
        }
    }

    /**
     * ADR-079: the void of a landed cost or a price credit takes its inventory part back out of the averages of exactly the
     * branches that took it, by the parts recorded when it was posted, as its reversed entries take it out of their inventory
     * accounts. A line posted before V94 has no recorded parts; its part is spread over what each branch holds now.
     *
     * @param raise as for {@link #apply}: true for the void of a landed cost, whose part leaves the averages
     */
    void revalueBack(
            UUID lineId,
            Lot lot,
            long inventory,
            boolean raise,
            UUID documentId,
            LocalDate on,
            String sourceType,
            Map<UUID, Set<UUID>> locked) {
        Map<UUID, Long> parts = lots.spread(lineId);
        if (parts.isEmpty()) {
            parts = spread(lot, inventory, held(lot.productId(), locked));
        }
        parts.keySet().forEach(branch -> requireLocked(locked, branch, lot.productId()));
        parts.forEach((branch, part) ->
                stock.revalue(on, branch, lot.productId(), raise ? -part : part, sourceType, documentId, true));
    }

    /**
     * ADR-079: a balance row is revalued only when lockStock locked it in its ordered pass; revaluing any other would take its
     * lock late and out of order, a deadlock against a transfer. Fails fast instead.
     */
    static void requireLocked(Map<UUID, Set<UUID>> locked, UUID branch, UUID product) {
        if (locked == null || !locked.getOrDefault(branch, Set.of()).contains(product)) {
            throw new IllegalStateException(
                    "revaluing a stock balance that lockStock did not lock: branch " + branch + ", product " + product);
        }
    }

    /** The charge per base unit, rounded down: no more is capitalised than was charged. */
    static long perUnit(long share, BigDecimal qty) {
        return BigDecimal.valueOf(share).divide(qty, 0, RoundingMode.FLOOR).longValueExact();
    }

    private static long moved(long cost, long perUnit, boolean raise) {
        return raise ? Math.addExact(cost, perUnit) : Math.max(0, cost - perUnit);
    }
}
