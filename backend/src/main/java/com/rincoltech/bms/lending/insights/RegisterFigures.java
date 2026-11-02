package com.rincoltech.bms.lending.insights;

import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The public read port of the collateral register and the regulator data summary (extras slice D4, issue #225; chapter 14
 * sections 14.5 and 14.7; ADR-038 decision 3). Like {@link InsightsFigures}, a report resolves what the caller may see at
 * request time and hands the frozen value to these methods in the job, which never read the principal. Both run in the
 * caller's read-only transaction under row-level security.
 */
public interface RegisterFigures {

    /** The collateral types, in the order the register's filter offers them. */
    List<String> COLLATERAL_TYPES =
            List.of("land_title", "vehicle_logbook", "vehicle", "national_id", "household_item", "other");

    /** The custody statuses, in the order the register's filter offers them. */
    List<String> CUSTODY_STATUSES = List.of("pledged", "in_custody", "released", "seized", "disposed");

    /** The business date now, in the tenant's time zone: the date a register of things as they stand is printed under. */
    LocalDate today();

    /**
     * Every collateral item in {@code filter}'s branches as it stands now (FR-COL-06), by branch name, member number and
     * the order the items were registered, one at a time.
     */
    void collateral(CollateralFilter filter, Runnable checkpoint, Consumer<CollateralLine> lines);

    /**
     * The regulator data summary over {@code scope.from()} to {@code scope.to()} (chapter 14 section 14.7). The loan book at
     * the end of the period is the positions of {@link InsightsFigures#par} (live for today, the nightly snapshot for an
     * earlier date; the same errors), so its PAR 30 and PAR 90 are those of section 14.3; the flows of the period are
     * the insights page's.
     */
    RegulatorySummary regulatory(PortfolioScope scope, Runnable checkpoint);

    /**
     * @param branchIds the branches, null for every branch, empty for none
     * @param type one of {@link #COLLATERAL_TYPES}, or null for all
     * @param custodyStatus one of {@link #CUSTODY_STATUSES}, or null for all
     * @param nameBranchIds the branches whose members' items print who owns them (the member's number and name and the
     *     item's reference, which can be an ID number, a plate or a title number), by the member's branch; null for
     *     every branch, empty for none
     * @param officerId when not null, only items that secure a loan this officer is responsible for
     */
    record CollateralFilter(
            List<UUID> branchIds, String type, String custodyStatus, List<UUID> nameBranchIds, UUID officerId) {

        public CollateralFilter {
            branchIds = branchIds == null ? null : List.copyOf(branchIds);
            nameBranchIds = nameBranchIds == null ? null : List.copyOf(nameBranchIds);
        }
    }

    /**
     * One item. {@code memberNo}, {@code memberName} and {@code reference} are null outside the name branches.
     * {@code latestValuationMinor} and {@code valuedOn} are the latest valuation's market value and date, null
     * without one; {@code loans} the linked loans with their status in words. The flags (chapter 14 section 14.5):
     * {@code releaseOverdue} for an item still held (pledged or in custody) whose linked loans are all closed, cancelled
     * or rejected; {@code unverified} for an item registered by an import with exactly the note "imported; verify custody
     * and value" (chapter 13 section 13.6.8) that nobody has valued, moved or noted since; {@code noValuation} for an item
     * never valued.
     */
    record CollateralLine(
            String branch,
            String memberNo,
            String memberName,
            String type,
            String description,
            String reference,
            Long estimatedValueMinor,
            Long latestValuationMinor,
            LocalDate valuedOn,
            String custodyStatus,
            String location,
            String loans,
            boolean releaseOverdue,
            boolean unverified,
            boolean noValuation) {}

    /**
     * @param source {@code live} or {@code snapshot}, where the loan book at the end of the period was read from
     * @param borrowersByGender distinct members with an active loan, by gender in words, in a fixed order
     * @param borrowers distinct members with an active loan
     * @param activeLoans the active loans and {@code principalOutstandingMinor} their principal outstanding
     * @param par30Bp PAR 30 of the book, as {@code lending.par}
     * @param par90Bp PAR 90 of the book, as {@code lending.par}
     * @param byProduct the book by product and rate unit, with the rate range and the weighted average rate
     * @param byPurpose the book by loan purpose
     * @param savingsMinor savings held from members at the end of the period, null for a caller limited to their own loans
     * @param savingsAccounts the savings accounts open at the end of the period, null as {@code savingsMinor}
     * @param investmentsMinor investments held from members at the end of the period, null as {@code savingsMinor}
     * @param investments the open investments now, null as {@code savingsMinor}
     * @param branches the active branches of the scope, now
     * @param staff active staff with a role in the branches of the scope (or every branch), now
     */
    record RegulatorySummary(
            LocalDate from,
            LocalDate to,
            String source,
            String currency,
            List<Count> borrowersByGender,
            long borrowers,
            long activeLoans,
            long principalOutstandingMinor,
            Long par30Bp,
            Long par90Bp,
            List<ProductRates> byProduct,
            List<Book> byPurpose,
            long disbursedLoans,
            long disbursedMinor,
            long writtenOffLoans,
            long writtenOffMinor,
            Long savingsMinor,
            Long savingsAccounts,
            Long investmentsMinor,
            Long investments,
            long branches,
            long staff) {}

    record Count(String label, long count) {}

    /** Active loans of one group and their principal outstanding. */
    record Book(String label, long loans, long principalOutstandingMinor) {}

    /**
     * The active loans of one product at one rate unit ({@code per month} and so on): their principal outstanding, the
     * lowest and highest rate, and the average rate weighted by principal outstanding (half up; null when they owe no
     * principal). Rates are basis points per the unit.
     */
    record ProductRates(
            String product,
            String rateUnit,
            long loans,
            long principalOutstandingMinor,
            long minRateBp,
            long maxRateBp,
            Long weightedRateBp) {}
}
