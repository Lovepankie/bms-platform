package com.rincoltech.bms.lending.loans.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of {@code /api/v1/lending/loans} (chapter 7 section 7.11.13). */
final class LoanApi {

    static final String PURPOSES = "business|school_fees|medical|agriculture|household|construction|other";

    /** The largest amount any money field accepts, in minor units (the same bound as loan products). */
    static final long MAX_MONEY_MINOR = 1_000_000_000_000_000L;

    /** Guarantors or pledged items per loan; each pledged item is a row lock held for the whole request. */
    static final int MAX_PARTIES = 20;

    private LoanApi() {}

    @Schema(name = "CreateLoanRequest")
    record CreateLoanRequest(
            @NotNull UUID memberId,
            @NotNull UUID productId,
            @NotNull @Positive @Max(MAX_MONEY_MINOR) Long requestedPrincipalMinor,

            @Positive @Schema(description = "Defaults to the product's default term")
            Integer requestedTermCount,

            @NotNull @Pattern(regexp = PURPOSES) String purposeCategory,
            @Size(max = 300) String purposeText,
            LocalDate proposedDisbursementDate) {}

    @Schema(name = "UpdateLoanRequest", description = "Draft only; omitted fields are unchanged")
    record UpdateLoanRequest(
            @Positive @Max(MAX_MONEY_MINOR) Long requestedPrincipalMinor,
            @Positive Integer requestedTermCount,
            @Pattern(regexp = PURPOSES) String purposeCategory,
            @Size(max = 300) String purposeText,
            LocalDate proposedDisbursementDate) {}

    @Schema(name = "LoanGuarantorInput")
    record GuarantorInput(
            @NotNull UUID memberId,
            @NotNull @Positive @Max(MAX_MONEY_MINOR) Long guaranteedAmountMinor,
            @Size(max = 60) String relationship) {}

    @Schema(name = "SetLoanGuarantorsRequest")
    record GuarantorsRequest(
            @NotNull @Valid @Size(max = MAX_PARTIES) List<GuarantorInput> guarantors) {}

    @Schema(name = "LoanPledgeInput")
    record PledgeInput(
            @NotNull UUID collateralId,

            @NotNull @Positive @Max(MAX_MONEY_MINOR) @Schema(description = "At most the item's value")
            Long pledgedValueMinor) {}

    @Schema(name = "SetLoanCollateralRequest")
    record PledgesRequest(
            @NotNull @Valid @Size(max = MAX_PARTIES) List<PledgeInput> collateral) {}

    @Schema(name = "LoanNoteRequest")
    record NoteRequest(@NotBlank @Size(max = 1000) String note) {}

    @Schema(name = "LoanDecisionRequest")
    record DecisionRequest(
            @NotNull @Pattern(regexp = "approve|reject") String decision,

            @Positive
            @Max(MAX_MONEY_MINOR)
            @Schema(description = "Approve only; defaults to the requested principal, never above it")
            Long approvedPrincipalMinor,

            @Positive @Schema(description = "Approve only; defaults to the requested term, never above it")
            Integer approvedTermCount,

            @Size(max = 1000) @Schema(description = "Required on reject")
            String note) {}

    @Schema(name = "LoanGuarantor")
    record Guarantor(UUID memberId, String memberNo, long guaranteedAmountMinor, String relationship, String status) {}

    @Schema(name = "LoanPledge")
    record Pledge(UUID collateralId, String collateralType, long pledgedValueMinor, Long collateralValueMinor) {}

    @Schema(name = "LoanScheduleItem")
    record ScheduleItem(
            int no, LocalDate dueDate, long principalMinor, long interestMinor, long feeMinor, long totalMinor) {}

    @Schema(name = "Loan")
    record LoanResponse(
            UUID id,
            String loanNo,
            UUID branchId,
            UUID memberId,
            String memberNo,
            UUID productId,
            String productCode,
            UUID productVersionId,
            UUID officerUserId,
            String status,
            String channel,
            String purposeCategory,
            String purposeText,
            String currency,
            long requestedPrincipalMinor,
            int requestedTermCount,
            Long approvedPrincipalMinor,
            Integer approvedTermCount,
            String termUnit,
            String interestMethod,
            int interestRateBp,
            String rateUnit,
            String repaymentPattern,
            String instalmentFrequency,
            LocalDate proposedDisbursementDate,
            UUID submittedBy,
            Instant submittedAt,
            UUID appraisedBy,
            UUID approvedBy,
            Instant approvedAt,
            String rejectedReason,
            String cancelledReason,
            List<Guarantor> guarantors,
            List<Pledge> collateral,

            @Schema(
                    description = "FR-ORG-03, FR-ORG-06: from the requested (once approved, the approved) terms and the"
                            + " proposed (else today's) date; display only")
            List<ScheduleItem> provisionalSchedule,

            @Schema(description = "Balances, DPD and dates once disbursed (FR-DIS-04, R-DPD); zeros before")
            ServicingApi.LoanBalances balances,

            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            int version) {}

    @Schema(name = "LoanListItem")
    record LoanListItem(
            UUID id,
            String loanNo,
            UUID branchId,
            UUID memberId,
            String memberNo,
            String memberName,
            String status,
            String purposeCategory,
            long requestedPrincipalMinor,
            int requestedTermCount,
            Long approvedPrincipalMinor,
            String currency,
            UUID officerUserId,
            LocalDate disbursedOn,
            long totalOutstandingMinor,
            int daysPastDue,
            LocalDate nextDueDate,
            Instant createdAt) {}

    @Schema(name = "LoanPage")
    record LoanPage(List<LoanListItem> items, String nextCursor) {}

    @Schema(name = "LoanStatusChange")
    record StatusChange(String fromStatus, String toStatus, UUID changedBy, String reason, Instant at) {}

    @Schema(name = "LoanStatusHistory")
    record StatusHistory(List<StatusChange> items) {}
}
