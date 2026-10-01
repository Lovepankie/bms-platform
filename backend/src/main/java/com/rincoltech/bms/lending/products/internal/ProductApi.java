package com.rincoltech.bms.lending.products.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of {@code /api/v1/lending/loan-products} (chapter 7 section 7.11.12). */
final class ProductApi {

    static final String METHODS = "flat|declining";
    static final String RATE_UNITS = "per_term|per_day|per_week|per_month|per_year";
    static final String TERM_UNITS = "day|week|month";
    static final String PATTERNS = "bullet|instalments";
    static final String FREQUENCIES = "daily|weekly|fortnightly|monthly";

    private ProductApi() {}

    @Schema(name = "LoanProductFee")
    record Fee(
            @NotBlank @Size(max = 60) String name,

            @NotNull @Pattern(regexp = "application|processing|insurance|other")
            String feeType,

            @NotNull @Pattern(regexp = "flat|percent_of_principal")
            String calcMethod,

            @Positive @Schema(description = "For flat") Long amountMinor,

            @Positive @Schema(description = "For percent_of_principal")
            Integer rateBp,

            @NotNull @Pattern(regexp = "deducted_at_disbursement|added_to_loan|paid_upfront")
            String timing) {}

    /** The terms of one product version (FR-PRD-01, FR-PRD-02). */
    @Schema(name = "LoanProductTerms")
    record Terms(
            @NotNull @Pattern(regexp = METHODS) String interestMethod,
            @NotNull @Min(0) @Max(100000) Integer interestRateBp,
            @NotNull @Pattern(regexp = RATE_UNITS) String rateUnit,
            @NotNull @Pattern(regexp = TERM_UNITS) String termUnit,
            @NotNull @Min(1) Integer minTermCount,
            @NotNull @Min(1) Integer maxTermCount,
            @NotNull @Min(1) Integer defaultTermCount,
            @NotNull @Pattern(regexp = PATTERNS) String repaymentPattern,

            @Pattern(regexp = FREQUENCIES) @Schema(description = "Required for instalments")
            String instalmentFrequency,

            @NotNull @Positive Long minPrincipalMinor,
            @NotNull @Positive Long maxPrincipalMinor,

            @Schema(description = "A permutation of penalty, fee, interest, principal; that order by default")
            List<String> allocationOrder,

            @Pattern(regexp = "none|flat_per_period|percent_of_overdue_per_period")
            String penaltyMethod,

            @PositiveOrZero Integer penaltyGraceDays,
            @Pattern(regexp = TERM_UNITS) String penaltyPeriodUnit,
            @Positive Long penaltyFlatMinor,
            @Positive Integer penaltyRateBp,
            @Positive Integer penaltyCapBp,
            Boolean flatEarlySettlementRebate,
            Boolean requiresCollateral,

            @Positive @Schema(description = "15000 is 1.5 times the principal")
            Integer minCollateralCoverBp,

            Boolean requiresGuarantor,
            @Valid List<Fee> fees) {}

    @Schema(name = "CreateLoanProductRequest")
    record CreateProductRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{1,19}$")
            String code,

            @NotBlank @Size(max = 100) String name,
            @NotNull @Valid Terms terms) {}

    @Schema(name = "SchedulePreviewRequest", description = "Terms as typed on the product form, saved or not")
    record PreviewRequest(
            @NotNull @Pattern(regexp = METHODS) String interestMethod,
            @NotNull @Min(0) @Max(100000) Integer interestRateBp,
            @NotNull @Pattern(regexp = RATE_UNITS) String rateUnit,
            @NotNull @Pattern(regexp = TERM_UNITS) String termUnit,
            @NotNull @Min(1) @Max(3660) Integer termCount,
            @NotNull @Pattern(regexp = PATTERNS) String repaymentPattern,
            @Pattern(regexp = FREQUENCIES) String instalmentFrequency,
            @Valid List<Fee> fees,
            @NotNull @Positive Long principalMinor,
            @NotNull LocalDate disbursementDate) {}

    @Schema(name = "LoanProductVersion")
    record Version(
            UUID id,
            int versionNo,
            String currency,
            String interestMethod,
            int interestRateBp,
            String rateUnit,
            String termUnit,
            int minTermCount,
            int maxTermCount,
            int defaultTermCount,
            String repaymentPattern,
            String instalmentFrequency,
            long minPrincipalMinor,
            long maxPrincipalMinor,
            List<String> allocationOrder,
            String penaltyMethod,
            int penaltyGraceDays,
            String penaltyPeriodUnit,
            Long penaltyFlatMinor,
            Integer penaltyRateBp,
            Integer penaltyCapBp,
            boolean flatEarlySettlementRebate,
            boolean requiresCollateral,
            Integer minCollateralCoverBp,
            boolean requiresGuarantor,
            List<Fee> fees,
            Instant createdAt) {}

    @Schema(name = "LoanProduct")
    record Product(
            UUID id,
            String code,
            String name,
            String status,
            Version currentVersion,

            @Schema(description = "Every version, newest first; only on the detail response")
            List<Version> versions,

            Instant createdAt,
            Instant updatedAt,
            int version) {}

    @Schema(name = "LoanProductList")
    record ProductList(List<Product> items) {}

    @Schema(name = "SchedulePreviewItem")
    record PreviewItem(
            int no, LocalDate dueDate, long principalMinor, long interestMinor, long feeMinor, long totalMinor) {}

    @Schema(name = "SchedulePreview")
    record Preview(
            List<PreviewItem> items,
            long totalPrincipalMinor,
            long totalInterestMinor,
            long totalFeesMinor,
            long totalDueMinor,

            @Schema(description = "Fees taken from the principal at disbursement")
            long deductedAtDisbursementMinor,

            @Schema(description = "Fees collected before disbursement")
            long paidUpfrontMinor,

            @Schema(description = "Principal minus fees deducted at disbursement")
            long netDisbursedMinor) {}
}
