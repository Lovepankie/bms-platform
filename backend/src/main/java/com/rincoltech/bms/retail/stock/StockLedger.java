package com.rincoltech.bms.retail.stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The only writer of {@code retail_stock_movements} and {@code retail_stock_balances} (FR-RET-03).
 * Runs in the caller's transaction: each movement is inserted and its balance moved under the
 * balance row's lock, so two sales of the last unit are serialised and the balance always equals
 * the sum of its movements. Balance rows are locked in branch and product order, so concurrent
 * multi-line events cannot deadlock.
 *
 * <p>A {@code sale}, {@code usage} or {@code damage} movement that would take a balance below zero
 * is always refused with 422 {@code insufficient_stock}; there is no setting to allow it (ADR-020
 * decision 4). Imports ({@code legacy_balance}) and stock-take adjustments are not guarded.
 */
public interface StockLedger {

    /**
     * @param businessDate the event's business date, the date its journal entry carries: the
     *     purchase's {@code purchased_on}, the sale's {@code sale_date}, the usage's {@code
     *     occurred_on}, or the void's or stock-take commit's date. Valuation {@code as_of} reads it.
     * @return the new movement ids, in the order given
     */
    List<UUID> record(LocalDate businessDate, List<Movement> movements);

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

    /**
     * Imported history (ADR-020 decision 9, FR-RET-12): writes each movement flagged
     * {@code historical}, dated when it happened in the source, and moves its balance under the
     * balance row's lock, like {@link #record}. No stock guard applies: history already happened,
     * and a negative running balance is what the source recorded. Posts nothing to the ledger.
     *
     * @param businessDate as for {@link #record}: the source row's sale, purchase or usage date, or
     *     the import date in the tenant's zone for a {@code legacy_balance} movement, the date the
     *     opening journal carries
     * @return the new movement ids, in the order given
     */
    List<UUID> recordHistorical(LocalDate businessDate, List<HistoricalMovement> movements);

    /**
     * Every branch and product that has moved: its balance, the sum of its historical movements
     * other than {@code legacy_balance}, and whether a {@code legacy_balance} movement exists.
     */
    List<Position> positions();

    /** As {@link Movement}, with the source's time; the kind's sign rules are the table's. */
    record HistoricalMovement(
            UUID branchId,
            UUID productId,
            String kind,
            BigDecimal qty,
            long unitCostMinor,
            Instant occurredAt,
            String sourceType,
            UUID sourceId,
            UUID sourceLineId,
            String note) {}

    record Position(UUID branchId, UUID productId, BigDecimal balance, BigDecimal historicalQty, boolean legacy) {}
}
