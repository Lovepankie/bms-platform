package com.rincoltech.bms.lending.insights.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response bodies of the insights routes (chapter 7 section 7.11.21). */
final class InsightsApi {

    private InsightsApi() {}

    /** Where a number drills down to: a table key and the extra parameters beyond the page filter. */
    @Schema(name = "InsightsDrill")
    record Drill(String table, Map<String, String> params) {

        Drill {
            params = Map.copyOf(params);
        }

        static Drill of(String table) {
            return new Drill(table, Map.of());
        }

        static Drill of(String table, String key, String value) {
            return new Drill(table, Map.of(key, value));
        }
    }

    /**
     * One number with its definition (the info tooltip) and its drill-down.
     *
     * @param kind {@code money} (integer minor units), {@code count}, {@code basis_points}, {@code days}
     *     or {@code hours}
     * @param value null when it cannot be computed (a rate over nothing due)
     */
    @Schema(name = "InsightsMetric")
    record Metric(String key, String label, String kind, Long value, String currency, String definition, Drill drill) {}

    @Schema(name = "InsightsBrief")
    record BriefResponse(
            LocalDate asOf,
            String currency,
            List<Metric> metrics,

            @Schema(description = "One plain sentence per movement, in the order of the cards")
            List<String> sentences,

            Instant generatedAt) {}

    @Schema(name = "InsightsPeriod")
    record PeriodPoint(
            LocalDate periodStart,
            LocalDate periodEnd,
            String label,
            long disbursedMinor,
            long disbursedCount,
            long collectedMinor,
            long expectedMinor,
            long collectedOnDueMinor,
            Long collectionRateBp) {}

    @Schema(name = "InsightsAgeingBucket")
    record Bucket(String key, String label, long loans, long principalMinor, Long shareBp, long arrearsMinor) {}

    @Schema(name = "InsightsGroup")
    record Group(String key, String label, long loans, long principalOutstandingMinor, long par30Minor, Long par30Bp) {}

    @Schema(name = "InsightsBreakdown")
    record Breakdown(String dimension, String title, List<Group> rows) {}

    @Schema(name = "InsightsArrearsRow")
    record ArrearsRow(
            UUID loanId,
            String loanNo,
            UUID memberId,
            String memberNo,
            String memberName,
            int daysPastDue,
            long arrearsMinor,
            long principalOutstandingMinor,
            String officerName) {}

    @Schema(name = "InsightsDayAmount")
    record DayAmount(LocalDate date, long amountMinor) {}

    @Schema(name = "InsightsTrendPoint")
    record TrendPoint(
            LocalDate date,
            String label,

            @Schema(description = "snapshot for a past month end, live for today, none when no snapshot exists")
            String source,

            Long principalOutstandingMinor,
            Long par30Bp,
            Long activeLoans) {}

    @Schema(name = "InsightsPortfolio")
    record PortfolioResponse(
            LocalDate from,
            LocalDate to,
            String grain,
            String currency,

            @Schema(description = "The date the stock figures (outstanding, PAR, ageing, breakdowns) are for")
            LocalDate stockAsOf,

            @Schema(description = "live (today), snapshot (a past date) or none (no snapshot for that date yet)")
            String stockSource,

            List<Metric> metrics,
            List<PeriodPoint> series,
            List<Bucket> ageing,
            List<Breakdown> breakdowns,
            List<ArrearsRow> topArrears,
            List<DayAmount> forecast,
            List<TrendPoint> trend,
            Instant generatedAt) {}

    @Schema(name = "InsightsRevenueRow")
    record RevenueRow(
            String key,
            String label,
            long interestMinor,
            long feesMinor,
            long penaltiesMinor,
            long recoveredMinor,
            long writeOffExpenseMinor,
            long contributionMinor,
            Long effectiveYieldBp) {}

    @Schema(name = "InsightsRevenueMonth")
    record RevenueMonth(
            LocalDate month,
            String label,
            long interestMinor,
            long feesMinor,
            long penaltiesMinor,
            long recoveredMinor,
            long writeOffExpenseMinor,
            long contributionMinor) {}

    @Schema(name = "InsightsRevenue")
    record RevenueResponse(
            LocalDate from,
            LocalDate to,
            String currency,
            List<Metric> metrics,
            List<RevenueRow> byProduct,
            List<RevenueRow> byBranch,
            List<RevenueMonth> months,
            Instant generatedAt) {}

    @Schema(name = "InsightsCountPoint")
    record CountPoint(LocalDate periodStart, String label, long count) {}

    @Schema(name = "InsightsFunnelStage")
    record Stage(String key, String label, long count, Long conversionBp, Drill drill) {}

    @Schema(name = "InsightsSlice")
    record Slice(String key, String label, long count) {}

    @Schema(name = "InsightsStaffRow")
    record StaffRow(
            UUID userId,
            String name,
            long membersRegistered,
            long applicationsSubmitted,
            long appraisals,
            long disbursements,
            long repaymentsRecorded) {}

    @Schema(name = "InsightsMembers")
    record MembersResponse(
            LocalDate from,
            LocalDate to,
            String grain,
            List<Metric> metrics,
            List<CountPoint> newMembers,
            List<Stage> funnel,
            List<Slice> kyc,
            List<Slice> scoreBands,
            List<StaffRow> staff,
            Instant generatedAt) {}

    @Schema(name = "InsightsPanel")
    record Panel(String key, String title, List<Metric> metrics) {}

    @Schema(name = "InsightsPanels")
    record PanelsResponse(List<Panel> panels) {}

    @Schema(name = "InsightsColumn")
    record Column(
            String key,
            String label,

            @Schema(description = "text, money, count, date, days or basis_points")
            String kind) {}

    @Schema(name = "InsightsTable")
    record TableResponse(
            String key,
            String title,
            String currency,
            List<Column> columns,

            @Schema(description = "Cells as strings in column order; money in integer minor units")
            List<List<String>> rows,

            @Schema(description = "True when the table stopped at the row limit")
            boolean truncated) {}

    @Schema(name = "InsightsDigestSettings")
    record DigestSettings(
            boolean enabled,
            List<String> emailRecipients,
            String telegramChatId,
            int sendHour,
            LocalDate lastSentOn,
            int version) {}

    @Schema(name = "InsightsDigestSettingsRequest")
    record DigestSettingsRequest(
            @NotNull Boolean enabled,

            @NotNull @Size(max = 5)
            List<@NotNull @Size(max = 320) @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") String> emailRecipients,

            @Pattern(regexp = "^-?[0-9]{1,20}$") String telegramChatId,
            @NotNull @Min(0) @Max(23) Integer sendHour) {}

    @Schema(name = "InsightsDigestPreview")
    record DigestPreview(LocalDate asOf, String subject, String text) {}
}
