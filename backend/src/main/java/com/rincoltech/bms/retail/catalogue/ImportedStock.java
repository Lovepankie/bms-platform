package com.rincoltech.bms.retail.catalogue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * How many of each item there are now, from Import items (issue #870, ADR-101 amendment of 6 Nov 2026): the catalogue reads
 * the list and makes the items, and stock adds each row's quantity at the chosen shop as opening stock, through Quick add's own
 * path (one {@code opening} movement against opening balance equity). The catalogue cannot depend on stock, so stock supplies
 * this port. Runs in the import's transaction and throws an {@code ApiException} to refuse.
 */
public interface ImportedStock {

    /**
     * Checks, before anything is made, that the caller may add stock at the branch ({@code retail.stocktake.commit} there; a
     * store or shop that keeps goods), and the day the stock is dated: today, or for a list kept on the phone offline the day
     * it was made, judged as every entry kept offline is (ADR-067 decision 13). Writes nothing.
     *
     * @param branchId the branch asked for, or null for the caller's one branch
     * @param occurredAt with {@code onlineAsOf}, the offline block of a list sent from the phone's outbox; both null online
     */
    Opening check(UUID branchId, Instant occurredAt, Instant onlineAsOf);

    /** Adds exactly {@code qty} (more than 0, in the item's own unit) to the item's stock at the branch, as opening stock. */
    void add(Opening at, UUID productId, BigDecimal qty, UUID importId);

    /** The audit row of a list kept offline and dated by the phone; nothing for a list sent online. */
    void recordedOffline(Opening at, Instant occurredAt, Instant onlineAsOf, UUID importId);

    /** The branch the stock goes to and the day it is dated. */
    record Opening(UUID branchId, LocalDate day) {}
}
