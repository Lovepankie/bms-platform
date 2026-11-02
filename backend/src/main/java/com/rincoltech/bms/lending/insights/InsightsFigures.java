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
 * Built by extras slice D1 (issue #214) with the figures the first report needs; slice D2 (issue #217) added
 * the portfolio figures and slice D3 (issue #222) the collections, disbursements, arrears and officer figures.
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

    // JUSTIFICATION-A3: slice D3 (issue #222) adds the collections, disbursements, arrears and officer figures the
    // design names for this port (ADR-038 decision 3), so the reports never compute a figure of the page twice.

    /**
     * Expected against collected over {@code scope.from()} to {@code scope.to()} (chapter 14 section 14.4,
     * {@code lending.expected_vs_collected}), per group and in total. Expected, collected on due and the collection rate
     * are the insights page's own ({@code portfolio.expected}, {@code portfolio.collected_on_due},
     * {@code portfolio.collection_rate}), and the total collected is its {@code portfolio.collected}: repayments and
     * recoveries dated in the range, a reversed one excluded. Runs in the caller's transaction, which should be read only.
     */
    CollectionFigures collections(PortfolioScope scope, ParGroupBy groupBy, Runnable checkpoint);

    /**
     * The loans disbursed in the range (chapter 14 section 14.3, {@code lending.disbursements}), per group and in total;
     * the amount and count are the insights page's {@code portfolio.disbursed} and {@code portfolio.disbursed_count}.
     * Each loan is handed to {@code lines} in date order, one at a time, for the detail table.
     */
    DisbursementFigures disbursements(
            PortfolioScope scope,
            ParGroupBy groupBy,
            Runnable checkpoint,
            java.util.function.Consumer<DisbursementLine> lines);

    /**
     * The active loans more than zero days past due as at {@code scope.to()} (chapter 14 section 14.4,
     * {@code lending.arrears_list}), from the same positions as {@link #par}, most overdue first within each bucket and
     * the buckets in age order; {@code bucket} keeps one bucket ({@code 1_30}, {@code 31_60}, {@code 61_90},
     * {@code over_90}) or all when null. A member's name is given only in the branches of {@code nameBranchIds}, and a
     * phone in full only in the branches of {@code contactBranchIds} (each null for every branch, empty for none); the
     * branch is the position's (the snapshot's for a past date). Elsewhere the name is null and the phone masked.
     */
    void arrears(
            PortfolioScope scope,
            String bucket,
            List<UUID> nameBranchIds,
            List<UUID> contactBranchIds,
            Runnable checkpoint,
            java.util.function.Consumer<ArrearsLine> lines);

    /**
     * Per responsible officer (chapter 14 section 14.4, {@code lending.officer_performance}): the active loans,
     * principal outstanding and PAR 30 as at {@code scope.to()} (the positions of {@link #par}), and over the range the
     * disbursements, expected, collected on due and collection rate (as {@link #collections}), the collection actions
     * logged on their loans and the promises to pay settled kept or broken.
     */
    OfficerFigures officers(PortfolioScope scope, Runnable checkpoint);

    /**
     * One group of {@link #collections}. The parts of the total collected: {@code paidOnDueInRange} (allocations of the
     * range's payments to items due in the range), {@code arrearsRecovered} (to items due before it),
     * {@code prepayments} (to items due after it), {@code penalties}, {@code overpayments}, and {@code recoveries}
     * (payments on written-off loans, which settle no item). {@code unexplained} is the total less those parts, zero when
     * the equation of chapter 14 section 14.4 holds.
     */
    record CollectionGroup(
            String key,
            String label,
            long expectedMinor,
            long collectedOnDueMinor,
            Long collectionRateBp,
            long paidOnDueInRangeMinor,
            long arrearsRecoveredMinor,
            long prepaymentsMinor,
            long penaltiesMinor,
            long overpaymentsMinor,
            long recoveriesMinor,
            long totalCollectedMinor,
            long unexplainedMinor) {}

    record CollectionFigures(
            LocalDate from, LocalDate to, String currency, CollectionGroup total, List<CollectionGroup> groups) {}

    /**
     * One group of {@link #disbursements}: the loans, principal disbursed, upfront fees taken at disbursement, and the
     * average and median loan (half up; null without loans).
     */
    record DisbursementGroup(
            String key,
            String label,
            long loans,
            long principalMinor,
            long feesMinor,
            Long averageMinor,
            Long medianMinor) {}

    record DisbursementFigures(
            LocalDate from, LocalDate to, String currency, DisbursementGroup total, List<DisbursementGroup> groups) {}

    /** One disbursed loan: its date, number, member number, branch, product and officer names, principal and upfront fee. */
    record DisbursementLine(
            LocalDate disbursedOn,
            String loanNo,
            String memberNo,
            String branch,
            String product,
            String officer,
            long principalMinor,
            long feesMinor) {}

    /**
     * One loan in arrears. {@code memberName} is null outside the name branches, {@code phone} masked outside the contact
     * branches. {@code penaltiesOutstandingMinor} is null for a past date (the snapshot does not hold it); the last
     * repayment, the last collection action and the open promise are those on or before the date.
     */
    record ArrearsLine(
            String bucket,
            String loanNo,
            String memberNo,
            String memberName,
            String phone,
            String officer,
            int daysPastDue,
            long arrearsMinor,
            Long penaltiesOutstandingMinor,
            long principalOutstandingMinor,
            LocalDate lastRepaymentOn,
            String lastAction,
            LocalDate lastActionOn,
            LocalDate promisedDate,
            Long promisedAmountMinor) {}

    /** One officer of {@link #officers}; the label is the officer's name. */
    record OfficerGroup(
            String key,
            String label,
            long activeLoans,
            long principalOutstandingMinor,
            Long par30Bp,
            long disbursedLoans,
            long disbursedMinor,
            long expectedMinor,
            long collectedOnDueMinor,
            Long collectionRateBp,
            long actions,
            long promisesKept,
            long promisesBroken) {}

    /**
     * @param source {@code live} or {@code snapshot}, where the positions at the range end were read from
     */
    record OfficerFigures(
            LocalDate from,
            LocalDate to,
            String source,
            String currency,
            OfficerGroup total,
            List<OfficerGroup> groups) {}
}
