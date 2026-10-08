package com.rincoltech.bms.lending.investments;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The investment figures the owner plans cash with (issue #152; chapter 14 section 14.5), for the
 * insights page of issue #153 and for any other read model. Runs in the caller's read-only
 * transaction with the tenant bound; the caller narrows {@code branchIds} to its own branch scope
 * first. Every figure comes from posted rows: the journal for returns accrued, the investment
 * transactions for flows, the investments for balances and maturities. Shaped like the insights
 * panel's metric so an adapter there is a mapping, not a computation.
 */
public interface InvestmentMetrics {

    /** Panel key, the prefix of every metric key. */
    String KEY = "investments";

    /**
     * Balances as at {@code to}; inflows, outflows, returns accrued and returns paid over
     * {@code from} to {@code to} inclusive.
     *
     * @param branchIds the branches to include, or null for every branch
     */
    List<Metric> metrics(LocalDate from, LocalDate to, List<UUID> branchIds);

    /** What falls due (principal and return) in the next 7, 30 and 90 days from {@code asOf}, and what is overdue. */
    List<LadderBucket> maturityLadder(LocalDate asOf, List<UUID> branchIds);

    /** The largest investors and the products by principal held now, with their share in basis points. */
    Concentration concentration(List<UUID> branchIds, int top);

    /**
     * @param kind {@code money} (minor units), {@code count} or {@code basis_points}
     * @param definition the plain-language formula
     */
    record Metric(String key, String label, String kind, long value, String currency, String definition) {}

    /**
     * @param bucket {@code overdue}, {@code 0_7}, {@code 8_30} or {@code 31_90}
     */
    record LadderBucket(String bucket, int count, long principalMinor, long returnMinor, long totalMinor) {}

    record Share(UUID id, String label, long principalMinor, int shareBp) {}

    record Concentration(long totalPrincipalMinor, List<Share> investors, List<Share> products) {}
}
