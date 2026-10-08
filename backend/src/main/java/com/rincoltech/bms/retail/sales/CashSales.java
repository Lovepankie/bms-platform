package com.rincoltech.bms.retail.sales;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * What the cash book reads from sales (ADR-022 decisions 9 and 10), in the caller's transaction. A
 * sale counts on its own {@code sale_date} whether or not it is voided later; its void shows on the
 * day the void is made, in the tenant's zone, as a separate line, so a past day is never rewritten.
 */
public interface CashSales {

    /**
     * Per day from {@code from} to {@code to} for one branch. Days with nothing are absent.
     *
     * @param zone the tenant's time zone, used for the day of a void
     */
    Map<LocalDate, Day> days(UUID branchId, LocalDate from, LocalDate to, ZoneId zone);

    /**
     * @param cashTakingsMinor cash sales by sale date plus cash payments on credit sales by paid-on date
     * @param cashSaleVoidsMinor total of the cash sales voided on this day
     * @param totalSoldMinor total of the completed (not voided) sales dated this day, any payment method
     * @param hasHistorical true when an imported row contributes to the day
     */
    record Day(long cashTakingsMinor, long cashSaleVoidsMinor, long totalSoldMinor, boolean hasHistorical) {}
}
