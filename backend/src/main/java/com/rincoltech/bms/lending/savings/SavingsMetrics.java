package com.rincoltech.bms.lending.savings;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The savings figures for the insights page (issue #153): balances, inflows, outflows, interest
 * paid and dormant accounts. Shaped like the insights module's {@code InsightsPanel}
 * (pending ADR-030, open as pull request #156) so that, once it is on {@code main}, the panel is a one-line
 * adapter over this bean; until then the insights work can call it directly. Computed from posted
 * savings rows only, in the caller's read-only transaction, with row-level security applied.
 */
public interface SavingsMetrics {

    /** The key the insights page uses for this panel. */
    String KEY = "savings";

    /**
     * The metrics for a date range and branches. Every value comes from savings rows; a metric is
     * present even when zero, because a zero is a fact the rows prove.
     *
     * @param branchIds the branches to include, or null for every branch
     */
    List<Metric> metrics(LocalDate from, LocalDate to, List<UUID> branchIds);

    /**
     * @param key for example {@code savings.balances}
     * @param kind {@code money} (minor units) or {@code count}
     * @param definition the plain-language formula
     */
    record Metric(String key, String label, String kind, long value, String currency, String definition) {}
}
