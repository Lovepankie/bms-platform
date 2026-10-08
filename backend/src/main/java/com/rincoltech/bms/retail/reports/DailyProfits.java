package com.rincoltech.bms.retail.reports;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * The day's profit per branch (FR-RET-10), for the cash book's savings suggestion (ADR-022
 * decision 11). Read only; it applies no permission, so a caller must hold {@code retail.profit.read}
 * before it shows the figure to anyone.
 */
public interface DailyProfits {

    /** Sales less cost of sales less usage and damage at cost for the branch and day; may be negative. */
    long profitMinor(UUID branchId, LocalDate date);

    /** The same per day over a range; days with no sales or usage are absent. */
    Map<LocalDate, Long> profitByDay(UUID branchId, LocalDate from, LocalDate to);
}
