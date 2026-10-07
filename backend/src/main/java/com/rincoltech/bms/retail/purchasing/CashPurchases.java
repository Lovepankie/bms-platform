package com.rincoltech.bms.retail.purchasing;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * What the cash book reads from purchasing (ADR-022 decisions 9 and 10), in the caller's
 * transaction: restocks paid in cash leave the till, valued at the line costs of the branch's own
 * quantities, rounded per line as the journal entry is.
 */
public interface CashPurchases {

    /** Cash restock cost per purchase date for one branch, {@code from} to {@code to}; empty days are absent. */
    Map<LocalDate, Long> byDay(UUID branchId, LocalDate from, LocalDate to);
}
