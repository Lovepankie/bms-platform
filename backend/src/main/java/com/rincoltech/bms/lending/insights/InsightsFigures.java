package com.rincoltech.bms.lending.insights;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The public read port through which the formal reports reach the insights figures (ADR-038 decision 3,
 * {@code docs/specs/client-extras.md} section 6.8). The insights queries and their {@code Scope} stay
 * internal to {@code lending.insights}; a report asks this port for the typed result of the very code the
 * insights page runs, so there is one implementation of PAR and no second one in the reports.
 *
 * <p>It is a two-step contract because a report run executes in a job, where no user is signed in:
 *
 * <ol>
 *   <li>{@link #scope} runs in the request, with the caller's principal, and resolves the page filter
 *       against the caller's permissions exactly as the insights endpoints do (the branches of
 *       {@code lending.insights.read}; without {@code lending.insights.all_officers} only the caller's own
 *       loans). The result is a plain value that the run stores.
 *   <li>{@link #par} runs later, in the run's read-only transaction with the tenant bound, from that stored
 *       scope alone. It never reads the principal, so a job cannot widen what the requester could see.
 * </ol>
 *
 * Built by extras slice D1 (issue #214) with the figures the first report needs; slice D2 (issue #217) adds
 * the rest of the portfolio, collections, disbursements and officer figures to this port.
 */
public interface InsightsFigures {

    /**
     * Resolves a report's filter for the signed-in caller. Refuses with 403 {@code permission_denied} when the
     * caller lacks {@code lending.insights.read}, and with the insights range errors (422
     * {@code range_in_future}, {@code invalid_range}, {@code range_too_long}).
     *
     * @param branchIds the branches asked for, or null or empty for every branch in the caller's scope
     * @param officerId the responsible officer asked for; ignored without {@code lending.insights.all_officers}
     */
    PortfolioScope scope(LocalDate from, LocalDate to, List<UUID> branchIds, UUID officerId, UUID productId);

    /**
     * Portfolio at risk and ageing as at {@code scope.to()} (chapter 14 section 14.3, {@code lending.par}): the live loan
     * tables when {@code to} is the scope's business date and that date is still the business date, the nightly snapshot
     * for an earlier one. A scope whose date has passed since it was resolved (a run that waited past midnight) reads that date's
     * snapshot when it exists, because the loan tables are the new day's by then, and is refused with 422
     * {@code report_stale_as_of} when it does not. Runs in the caller's transaction, which should be read only. Refuses with
     * 422 {@code snapshot_unavailable} when a past date has no snapshot.
     *
     * <p>The loans are read in blocks and totalled as they arrive, so memory grows with the number of groups and never
     * with the number of loans; {@code checkpoint} is called every 1,000 loans, so a caller can stop a long read (a
     * cancelled or timed out run) by throwing from it.
     */
    ParFigures par(PortfolioScope scope, ParGroupBy groupBy, Runnable checkpoint);

    /**
     * The loan portfolio as at {@code scope.to()} (chapter 14 section 14.3, {@code lending.portfolio_outstanding}): per
     * group and in total, the active loans, their distinct borrowers, principal and interest outstanding and arrears, from
     * the same positions as {@link #par} and the insights page (the live loan tables for the scope's business date, the
     * nightly snapshot for an earlier one; 422 {@code snapshot_unavailable} when there is none). Runs in the caller's
     * transaction, which should be read only; memory grows with the number of groups, not of loans, and {@code checkpoint}
     * is called every 1,000 loans as for {@link #par}.
     *
     * <p>The borrowers of a group are the distinct members with an active loan in it, so a member with loans in two
     * branches counts once in each branch and once in the total: the groups' borrowers can add up to more than the
     * total's, as on the insights page.
     */
    PortfolioFigures portfolio(PortfolioScope scope, ParGroupBy groupBy, Runnable checkpoint);

    /**
     * True when the signed-in caller could still ask for everything {@code scope} covers: they still hold
     * {@code lending.insights.read}, every branch the scope names is still in their branches (all branches when the
     * scope names none), and the loans of every officer the scope covers are still theirs to see. A report file made
     * under a wider scope than the caller has now is not theirs to download.
     */
    boolean covers(PortfolioScope scope);

    /**
     * A resolved filter: a date range (inclusive; the figures that are as-at read {@code to}), the business date it was
     * resolved on ({@code today}: a run queued at 23:59 and executed after midnight still reads the loan tables as
     * "today", not a snapshot that tonight's job has not written), the branches (null for every branch, an empty list for
     * none), the responsible officer and the product.
     */
    record PortfolioScope(
            LocalDate from, LocalDate to, LocalDate today, List<UUID> branchIds, UUID officerId, UUID productId) {

        public PortfolioScope {
            branchIds = branchIds == null ? null : List.copyOf(branchIds);
        }
    }

    /** How the PAR figures are split; {@code NONE} gives the total only. */
    enum ParGroupBy {
        NONE,
        BRANCH,
        PRODUCT,
        OFFICER
    }

    /**
     * @param asAt the date the figures stand at
     * @param source {@code live} (the loan tables, for today) or {@code snapshot} (the nightly snapshot)
     * @param currency the tenant's currency; amounts are integer minor units
     * @param total every loan in scope
     * @param groups one per group when grouped (sorted by label), empty for {@link ParGroupBy#NONE}
     */
    record ParFigures(LocalDate asAt, String source, String currency, ParGroup total, List<ParGroup> groups) {}

    /**
     * Active loans of one group on the date. A PAR share is in basis points of the group's principal
     * outstanding, half up, and null when the group owes nothing. {@code buckets} are in age order:
     * {@code current}, {@code 1_30}, {@code 31_60}, {@code 61_90}, {@code over_90}.
     */
    record ParGroup(
            String key,
            String label,
            long loans,
            long principalOutstandingMinor,
            long arrearsMinor,
            Long par1Bp,
            Long par30Bp,
            Long par60Bp,
            Long par90Bp,
            List<ParBucket> buckets) {}

    // JUSTIFICATION-A3: the portfolio report (issue #217) reads figures PAR does not carry (borrowers, interest,
    // average); same port.

    /**
     * @param asAt the date the figures stand at
     * @param source {@code live} or {@code snapshot}, as for {@link ParFigures}
     * @param currency the tenant's currency; amounts are integer minor units
     * @param total every loan in scope
     * @param groups one per group when grouped (sorted by label), empty for {@link ParGroupBy#NONE}
     */
    record PortfolioFigures(
            LocalDate asAt, String source, String currency, PortfolioGroup total, List<PortfolioGroup> groups) {}

    /**
     * Active loans of one group on the date: their count, distinct borrowers, principal and interest outstanding, arrears,
     * and the average principal outstanding per loan (half up, null when the group has no loans).
     */
    record PortfolioGroup(
            String key,
            String label,
            long loans,
            long borrowers,
            long principalOutstandingMinor,
            long interestOutstandingMinor,
            long arrearsMinor,
            Long averagePrincipalMinor) {}

    /** One age bucket of a group: its loans, principal outstanding, share of the group's principal and arrears. */
    record ParBucket(String bucket, long loans, long principalOutstandingMinor, Long shareBp, long arrearsMinor) {}
}
