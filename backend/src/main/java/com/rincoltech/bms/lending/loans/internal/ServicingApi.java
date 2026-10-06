package com.rincoltech.bms.lending.loans.internal;

import io.swagger.v3.oas.annotations.media.Schema;
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

/**
 * Request and response bodies of loan servicing (chapter 7 section 7.11.13; FR-DIS, FR-REP,
 * FR-LCL): disbursement, schedule, repayments, reversal, payoff quote, write-off.
 */
final class ServicingApi {

    /** Payment methods money moves by; each maps to a ledger account by system key (ADR-026). */
    static final String METHODS = "cash|bank|mtn_momo|airtel_money";

    private ServicingApi() {}

    @Schema(name = "DisbursementRequest")
    record DisbursementRequest(
            @NotNull @Schema(description = "Today or earlier, in an open period")
            LocalDate disbursementDate,

            @NotNull @Pattern(regexp = METHODS) String paymentMethodKey,
            @Size(max = 100) String externalReference) {}

    @Schema(name = "RepaymentRequest")
    record RepaymentRequest(
            @NotNull @Positive @Max(LoanApi.MAX_MONEY_MINOR) Long amountMinor,

            @NotNull
            @Schema(
                    description = "Today or earlier, not before the disbursement or the loan's latest repayment, in an"
                            + " open period")
            LocalDate valueDate,

            @NotNull @Pattern(regexp = METHODS) String paymentMethodKey,
            @Size(max = 100) String externalReference) {}

    @Schema(name = "LoanReasonRequest")
    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    @Schema(
            name = "LoanActionOutcome",
            description = "executed: the action took effect now (below the tenant's threshold); otherwise it waits for"
                    + " a checker as approval_request_id")
    record ActionOutcome(UUID loanId, boolean executed, UUID approvalRequestId, String loanStatus) {}

    @Schema(name = "LoanBalances", description = "FR-DIS-04, R-DPD: the loan's position as of its last money event")
    record LoanBalances(
            LocalDate disbursedOn,
            LocalDate maturityDate,
            LocalDate closedOn,
            LocalDate writtenOffOn,
            long principalDisbursedMinor,
            long principalOutstandingMinor,
            long interestOutstandingMinor,
            long feesOutstandingMinor,
            long penaltiesOutstandingMinor,
            long totalOutstandingMinor,
            long arrearsMinor,
            int daysPastDue,
            LocalDate nextDueDate,
            LocalDate lastRepaymentOn,
            long totalPaidMinor,

            @Schema(description = "Overpayment held for the member (R-ALLOC step 4)")
            long creditBalanceMinor) {}

    @Schema(name = "LoanScheduleRow")
    record ScheduleRow(
            Integer no,
            LocalDate dueDate,
            long principalDueMinor,
            long interestDueMinor,
            long feesDueMinor,
            long penaltiesDueMinor,
            long totalDueMinor,
            long principalPaidMinor,
            long interestPaidMinor,
            long feesPaidMinor,
            long penaltiesPaidMinor,
            long totalPaidMinor,
            long waivedMinor,
            long writtenOffMinor,
            long outstandingMinor,

            @Schema(description = "pending, due, overdue, partially_paid, paid, waived or written_off; null on totals")
            String status,

            LocalDate paidOn) {}

    @Schema(name = "LoanSchedule", description = "FR-DIS-04: the items and a totals row that equals the loan totals")
    record Schedule(UUID loanId, String currency, List<ScheduleRow> items, ScheduleRow totals) {}

    @Schema(name = "LoanAllocation")
    record AllocationRow(
            @Schema(description = "The repayment whose money the row moves")
            UUID appliesToTxnId,

            @Schema(description = "Null for an overpayment") Integer itemNo,
            String component,

            @Schema(description = "Negative on a reversal's rows")
            long amountMinor) {}

    @Schema(name = "LoanTransaction")
    record LoanTransaction(
            UUID id,
            String txnType,
            long amountMinor,
            String currency,
            LocalDate valueDate,
            String paymentMethodKey,
            String externalReference,

            @Schema(description = "RC- receipt of a repayment or recovery, VC- voucher of a disbursement")
            String receiptNo,

            String reason,
            UUID reversesTxnId,
            UUID reversedByTxnId,
            UUID journalEntryId,
            UUID approvalRequestId,
            UUID recordedBy,
            Instant createdAt,
            List<AllocationRow> allocations) {}

    @Schema(name = "LoanTransactionList")
    record TransactionList(List<LoanTransaction> items) {}

    @Schema(name = "RepaymentResult")
    record RepaymentResult(LoanTransaction transaction, String loanStatus, LoanBalances balances) {}

    @Schema(name = "PayoffQuote", description = "R-PAYOFF as at value_date; rebate is interest not charged")
    record PayoffQuote(
            UUID loanId,
            String currency,
            LocalDate valueDate,
            long principalMinor,
            long interestMinor,
            long feesMinor,
            long penaltiesMinor,
            long rebateMinor,
            long totalMinor) {}
}
