package com.rincoltech.bms.retail.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Imported credit buyers and sales (ADR-020 decision 9, FR-RET-12), in the caller's transaction. A
 * historical sale keeps the source's unit price and unit cost snapshots, is flagged
 * {@code historical}, moves stock through the stock ledger's historical path without the oversell
 * guard, and posts no journal. A historical credit sale is imported unpaid: the source keeps no
 * payments.
 */
public interface SaleHistory {

    /** The first credit buyer with this name ignoring case, created with the contact when there is none. */
    Ensured ensureCustomer(String name, String contact);

    /** The credit buyer with this name ignoring case, if any. */
    Optional<UUID> findCustomer(String name);

    /** @return the sale's id */
    UUID importSale(Sale sale);

    /**
     * One sale of one line.
     *
     * @param paymentMethod {@code cash} or {@code credit}
     * @param customerId a credit buyer, or null; buyer name and contact are kept as entered
     */
    record Sale(
            UUID branchId,
            UUID productId,
            BigDecimal qty,
            long unitPriceMinor,
            long unitCostMinor,
            String paymentMethod,
            UUID customerId,
            String buyerName,
            String buyerContact,
            LocalDate dueDate,
            Instant soldAt,
            LocalDate saleDate) {}

    record Ensured(UUID id, boolean created) {}
}
