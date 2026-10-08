package com.rincoltech.bms.lending.insights;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A panel another lending module adds to the insights page (savings and investments, issues #151
 * and #152). A module registers a panel by exposing a bean of this type; the insights page asks
 * every registered panel for its metrics and renders nothing for a module that registers none, or
 * for a tenant whose panel says it has nothing to show ({@link #shown()}).
 * Implementations compute from their own posted rows, in the caller's read-only transaction, with
 * the tenant bound and the filter already narrowed to the caller's branch scope.
 */
public interface InsightsPanel {

    /** Stable key, for example {@code savings} or {@code investments}. */
    String key();

    /** The heading shown on the page. */
    String title();

    /**
     * False when the bound tenant has none of this module's data (no savings account, no
     * investment), so the page shows no tab for it rather than a panel of zeros.
     */
    default boolean shown() {
        return true;
    }

    /** The metrics for the filter; never invented: a value with no source rows is absent. */
    List<Metric> metrics(Filter filter);

    /**
     * @param branchIds the branches to include, or null for every branch in the caller's scope
     * @param officerId the responsible officer, or null for all
     */
    record Filter(LocalDate from, LocalDate to, List<UUID> branchIds, UUID officerId) {}

    /**
     * One number with its definition.
     *
     * @param key for example {@code savings.balances}
     * @param kind {@code money} (minor units), {@code count} or {@code basis_points}
     * @param definition the plain-language formula shown in the info tooltip
     */
    record Metric(String key, String label, String kind, long value, String currency, String definition) {}
}
