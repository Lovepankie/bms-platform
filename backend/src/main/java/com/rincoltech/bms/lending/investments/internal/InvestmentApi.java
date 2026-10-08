package com.rincoltech.bms.lending.investments.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies of investments (chapter 7 section 7.11.16; FR-INV-01 to FR-INV-12):
 * products, investments, funding, schedule, statement, certificate, payouts, rollover, early
 * withdrawal, reversal and the maturity ladder.
 */
final class InvestmentApi {

    static final long MAX_MONEY_MINOR = 1_000_000_000_000_000L;

    /** Payment methods money moves by; each maps to a ledger account by system key (ADR-026). */
    static final String METHODS = "cash|bank|mtn_momo|airtel_money";

    private InvestmentApi() {}

    // ---- Products (FR-INV-01, FR-INV-08) -------------------------------------------------

    @Schema(name = "InvestmentProductTerms")
    record ProductTerms(
            @NotNull
            @Pattern(regexp = "fixed_term|recurring")
            @Schema(
                    description =
                            "recurring: renews itself at maturity unless the member asks for a payout (FR-INV-08)")
            String productType,

            @NotEmpty @Size(max = 24) List<@NotNull @Min(1) @Max(120) Integer> allowedTermsMonths,

            @NotNull @Min(0) @Max(100_000) @Schema(description = "Per year")
            Integer returnRateBp,

            @NotNull
            @Pattern(regexp = "flat|compound")
            @Schema(description = "compound: monthly, only with payout at_maturity (R-INV-3)")
            String returnMethod,

            @NotNull @Pattern(regexp = "at_maturity|monthly|quarterly")
            String payoutFrequency,

            @NotNull @Positive @Max(MAX_MONEY_MINOR) Long minAmountMinor,
            @Positive @Max(MAX_MONEY_MINOR) Long maxAmountMinor,
            @NotNull Boolean earlyWithdrawalAllowed,
            @Pattern(regexp = "forfeit_return|reduced_rate") String earlyWithdrawalRule,
            @Min(0) @Max(100_000) Integer earlyWithdrawalRateBp,

            @Min(0) @Max(5_000) @Schema(description = "Penalty on principal withdrawn early, in basis points")
            Integer earlyWithdrawalPenaltyBp) {}

    @Schema(name = "CreateInvestmentProductRequest")
    record CreateProductRequest(
            @NotBlank @Size(max = 20) @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]*")
            String code,

            @NotBlank @Size(max = 100) String name,
            @NotNull @Valid ProductTerms terms) {}

    @Schema(name = "UpdateInvestmentProductRequest")
    record UpdateProductRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Valid ProductTerms terms) {}

    @Schema(name = "InvestmentProduct")
    record Product(
            UUID id,
            String code,
            String name,
            String currency,
            String status,
            int version,
            String productType,
            List<Integer> allowedTermsMonths,
            int returnRateBp,
            String returnMethod,
            String payoutFrequency,
            long minAmountMinor,
            Long maxAmountMinor,
            boolean earlyWithdrawalAllowed,
            String earlyWithdrawalRule,
            Integer earlyWithdrawalRateBp,
            int earlyWithdrawalPenaltyBp,
            Instant createdAt) {}

    @Schema(name = "InvestmentProductList")
    record ProductList(List<Product> items) {}

    // ---- Investments (FR-INV-02, FR-INV-03) ----------------------------------------------

    @Schema(name = "OpenInvestmentRequest")
    record OpenRequest(
            @NotNull UUID memberId,
            @NotNull UUID productId,
            @NotNull @Positive @Max(MAX_MONEY_MINOR) Long amountMinor,
            @NotNull @Min(1) @Max(120) Integer termMonths,

            @Pattern(regexp = "payout|rollover_principal|rollover_all")
            String maturityInstruction) {}

    @Schema(name = "InvestmentReturnPreviewRequest")
    record PreviewRequest(
            @NotNull UUID productId,
            @NotNull @Positive @Max(MAX_MONEY_MINOR) Long amountMinor,
            @NotNull @Min(1) @Max(120) Integer termMonths,
            @Schema(description = "Today when omitted") LocalDate startDate) {}

    @Schema(name = "InvestmentReturnPreview")
    record Preview(
            String currency,
            long amountMinor,
            int termMonths,
            int returnRateBp,
            String returnMethod,
            String payoutFrequency,
            LocalDate startDate,
            LocalDate maturityDate,
            long agreedReturnMinor,
            long maturityValueMinor,
            List<SchedulePeriod> periods) {}

    @Schema(name = "Investment")
    record Investment(
            UUID id,
            String accountNo,
            UUID branchId,
            UUID memberId,
            String memberNo,
            String memberName,
            UUID productId,
            String productCode,
            String productName,
            String productType,
            String currency,
            String status,
            int version,
            long principalMinor,
            int returnRateBp,
            String returnMethod,
            int termMonths,
            String payoutFrequency,
            boolean earlyWithdrawalAllowed,
            String earlyWithdrawalRule,
            Integer earlyWithdrawalRateBp,
            int earlyWithdrawalPenaltyBp,
            LocalDate startDate,
            LocalDate maturityDate,
            Long agreedReturnMinor,
            long principalHeldMinor,
            long returnAccruedMinor,
            long returnDueMinor,
            long returnPaidMinor,

            @Schema(description = "Return due and not yet paid: what a return payout pays now")
            long returnAvailableMinor,

            String maturityInstruction,
            UUID rolledOverFromId,
            UUID rolledOverToId,
            String certificateNo,
            String channel,
            LocalDate preMaturityRemindedOn,
            LocalDate postMaturityRemindedOn,
            LocalDate closedOn,
            UUID pendingApprovalId,
            Instant createdAt) {}

    @Schema(name = "InvestmentPage")
    record InvestmentPage(List<Investment> items, String nextCursor) {}

    @Schema(name = "InvestmentMaturityInstructionRequest")
    record InstructionRequest(
            @NotNull @Pattern(regexp = "payout|rollover_principal|rollover_all")
            String instruction) {}

    @Schema(name = "InvestmentFundingRequest")
    record FundingRequest(
            @NotNull @Schema(description = "Today or earlier, in an open period")
            LocalDate valueDate,

            @NotNull @Pattern(regexp = METHODS) String paymentMethodKey,
            @Size(max = 100) String externalReference) {}

    @Schema(name = "InvestmentPaymentRequest")
    record PaymentRequest(
            @NotNull @Pattern(regexp = METHODS) String paymentMethodKey,
            @Size(max = 100) String externalReference) {}

    @Schema(name = "InvestmentRolloverRequest")
    record RolloverRequest(
            @NotNull @Pattern(regexp = "rollover_principal|rollover_all")
            String mode) {}

    @Schema(name = "InvestmentEarlyWithdrawalRequest")
    record EarlyWithdrawalRequest(
            @NotNull @Pattern(regexp = METHODS) String paymentMethodKey,
            @Size(max = 100) String externalReference,
            @NotBlank @Size(max = 500) String reason) {}

    @Schema(name = "InvestmentReasonRequest")
    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    @Schema(
            name = "InvestmentActionOutcome",
            description = "executed: the action took effect now (below the tenant's threshold); otherwise it waits for"
                    + " a checker as approval_request_id")
    record ActionOutcome(UUID investmentId, boolean executed, UUID approvalRequestId, String investmentStatus) {}

    @Schema(name = "InvestmentTransactionResult")
    record TransactionResult(InvestmentTransaction transaction, Investment investment) {}

    @Schema(name = "InvestmentRolloverResult")
    record RolloverResult(Investment previous, Investment next) {}

    @Schema(name = "InvestmentEarlyWithdrawalQuote", description = "R-INV-6 as at the value date")
    record EarlyQuote(
            UUID investmentId,
            String currency,
            LocalDate valueDate,
            String rule,
            long principalMinor,
            long earnedReturnMinor,
            long returnAccruedMinor,
            long returnPaidMinor,
            long penaltyMinor,
            long cashMinor) {}

    // ---- Schedule, statement, certificate (FR-INV-09, FR-INV-10) --------------------------

    @Schema(name = "InvestmentSchedulePeriod")
    record SchedulePeriod(
            int periodNo,
            LocalDate periodStart,
            LocalDate periodEnd,
            long openingBalanceMinor,
            long returnMinor,
            long cumulativeReturnMinor,
            boolean payout,
            String status) {}

    @Schema(name = "InvestmentSchedule")
    record Schedule(UUID investmentId, String currency, List<SchedulePeriod> periods, long totalReturnMinor) {}

    @Schema(name = "InvestmentTransaction")
    record InvestmentTransaction(
            UUID id,
            UUID investmentId,
            String txnType,
            long amountMinor,
            long principalMinor,
            long returnMinor,
            long penaltyMinor,
            String currency,
            LocalDate valueDate,
            Integer periodNo,
            String paymentMethodKey,
            String externalReference,
            String receiptNo,
            String reason,
            UUID reversesTxnId,
            UUID reversedByTxnId,
            UUID journalEntryId,
            UUID approvalRequestId,
            String source,
            UUID recordedBy,
            Instant createdAt) {}

    @Schema(name = "InvestmentStatementLine")
    record StatementLine(
            InvestmentTransaction transaction,

            @Schema(description = "Principal held after this line")
            long principalBalanceMinor,

            @Schema(description = "Return accrued and not paid after this line")
            long returnPayableMinor) {}

    @Schema(name = "InvestmentStatement")
    record Statement(
            Investment investment, List<StatementLine> lines, long principalBalanceMinor, long returnPayableMinor) {}

    @Schema(name = "InvestmentCertificate", description = "FR-INV-10: the data of the printed certificate")
    record Certificate(
            String certificateNo,
            String tenantName,
            String branchName,
            String accountNo,
            String memberNo,
            String memberName,
            String productName,
            String currency,
            long principalMinor,
            int returnRateBp,
            String returnMethod,
            String payoutFrequency,
            int termMonths,
            LocalDate startDate,
            LocalDate maturityDate,
            long agreedReturnMinor,
            long maturityValueMinor,
            String maturityInstruction,
            boolean earlyWithdrawalAllowed,
            String earlyWithdrawalRule,
            Integer earlyWithdrawalRateBp,
            int earlyWithdrawalPenaltyBp,
            LocalDate issuedOn) {}

    // ---- Maturities and metrics (FR-INV-11, FR-INV-12) ----------------------------------

    @Schema(name = "InvestmentLadderBucket")
    record LadderBucket(String bucket, int count, long principalMinor, long returnMinor, long totalMinor) {}

    @Schema(name = "InvestmentMaturity")
    record Maturity(
            UUID investmentId,
            String accountNo,
            String memberName,
            String productName,
            String status,
            LocalDate maturityDate,
            int daysToMaturity,
            long principalMinor,
            long returnMinor,
            long totalMinor,
            String maturityInstruction) {}

    @Schema(name = "InvestmentMaturities")
    record Maturities(String currency, LocalDate asOf, List<LadderBucket> ladder, List<Maturity> items) {}

    @Schema(name = "InvestmentMetric")
    record Metric(String key, String label, String kind, long value, String currency, String definition) {}

    @Schema(name = "InvestmentShare")
    record Share(UUID id, String label, long principalMinor, int shareBp) {}

    @Schema(name = "InvestmentMetrics")
    record Metrics(
            LocalDate from,
            LocalDate to,
            List<Metric> metrics,
            List<LadderBucket> ladder,
            long totalPrincipalMinor,
            List<Share> topInvestors,
            List<Share> products) {}
}
