package com.rincoltech.bms.retail.stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Imported history (ADR-020 decision 9, FR-RET-12), shared by the retail modules that write it.
 * History documents are flagged {@code historical}, carry the source's dates, move stock through
 * {@link StockLedger#recordHistorical} and post no journal. They are counted by the reports exactly
 * like live ones (FR-RET-09, FR-RET-10).
 */
public interface RetailHistory {

    /**
     * The {@code created_by} of every imported document: the import, not a staff account. The
     * source system's user is kept with the document's source reference instead.
     */
    UUID IMPORT_ACTOR = new UUID(0L, 0L);

    /**
     * A historical usage or damage report of one line, valued at {@code unitCostMinor} (the
     * source's snapshot), in the caller's transaction.
     *
     * @param kind {@code used} or {@code damaged}
     * @param qty positive; the movement takes it out of the branch
     * @return the report's id
     */
    UUID importUsage(
            UUID branchId,
            UUID productId,
            String kind,
            String reason,
            BigDecimal qty,
            long unitCostMinor,
            Instant reportedAt,
            LocalDate reportedOn);
}
