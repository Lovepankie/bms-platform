package com.rincoltech.bms.retail.stock;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The only writer of {@code retail_stock_movements} and {@code retail_stock_balances} (FR-RET-03).
 * Runs in the caller's transaction: each movement is inserted and its balance moved under the
 * balance row's lock, so two sales of the last unit are serialised and the balance always equals
 * the sum of its movements. Balance rows are locked in branch and product order, so concurrent
 * multi-line events cannot deadlock.
 *
 * <p>When the tenant setting {@code retail_allow_negative_stock} is false, a {@code sale},
 * {@code usage} or {@code damage} movement that would take a balance below zero is refused with
 * 422 {@code insufficient_stock} (ADR-020 decision 4).
 */
public interface StockLedger {

    /** @return the new movement ids, in the order given */
    List<UUID> record(List<Movement> movements);

    /** The movements a source document wrote, in the order they were recorded. */
    List<Recorded> bySource(String sourceType, UUID sourceId);

    /** The current balance of a branch and product; zero when nothing has moved. */
    BigDecimal balance(UUID branchId, UUID productId);

    /**
     * Takes the balance row's lock for the rest of the transaction and returns the balance, for a
     * caller that must compute a movement from the locked balance (a stock-take commit). Lock in
     * branch and product order.
     */
    BigDecimal lock(UUID branchId, UUID productId);

    /**
     * @param kind {@code opening}, {@code purchase}, {@code sale}, {@code usage}, {@code damage},
     *     {@code adjustment}, {@code return} or {@code legacy_balance}
     * @param qty signed: negative for stock leaving the branch
     * @param unitCostMinor the product's cost when the movement happens (valuation snapshot)
     */
    record Movement(
            UUID branchId,
            UUID productId,
            String kind,
            BigDecimal qty,
            long unitCostMinor,
            String sourceType,
            UUID sourceId,
            UUID sourceLineId,
            UUID reversesMovementId,
            String note) {}

    record Recorded(
            UUID id,
            UUID branchId,
            UUID productId,
            String kind,
            BigDecimal qty,
            long unitCostMinor,
            UUID sourceLineId) {}
}
