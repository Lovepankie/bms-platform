package com.rincoltech.bms.core.payments.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The shapes of {@code /api/v1/payments/unallocated} (chapter 7 section 7.11.10, FR-PAY-04). */
final class ReceiptApi {

    private ReceiptApi() {}

    @Schema(name = "AllocateReceiptRequest")
    record AllocateRequest(
            @Schema(
                    description = "What the money is put on: lending.loan_repayment or lending.savings_deposit",
                    example = "lending.loan_repayment")
            @NotBlank
            @Size(max = 60)
            String purpose,

            @Schema(description = "The loan or the savings account") @NotNull
            UUID subjectId,

            @Schema(description = "Why this loan or account, for the checker") @NotBlank @Size(max = 300)
            String reason) {}

    @Schema(name = "RefundReceiptRequest")
    record RefundRequest(
            @Schema(
                    description =
                            "How the money went or goes back: gateway (through the provider), cash, bank, mtn_momo or"
                                    + " airtel_money",
                    example = "bank")
            @NotBlank
            @Size(max = 20)
            String methodKey,

            @Schema(
                    description =
                            "The lender's own reference for the refund (a bank or wallet reference), printable characters")
            @NotBlank
            @Size(max = 100)
            String reference,

            @Schema(description = "Why it is returned, for the checker") @NotBlank @Size(max = 300)
            String reason) {}

    @Schema(name = "ReceiptActionOutcome", description = "The request waits for a checker as approval_request_id")
    record ActionOutcome(UUID receiptId, UUID approvalRequestId) {}

    @Schema(name = "ReceiptPendingRequest")
    record PendingRequest(UUID approvalRequestId, String actionType) {}

    @Schema(name = "ReceiptIntendedSubject", description = "What the payer meant to pay, as the order recorded it")
    record IntendedSubject(String purpose, String subjectType, UUID subjectId) {}

    @Schema(name = "ReceiptAllocation")
    record Allocation(String purpose, String subjectType, UUID subjectId, UUID transactionId) {}

    @Schema(name = "ReceiptRefund")
    record Refund(String methodKey, String reference) {}

    @Schema(name = "UnallocatedReceipt")
    record ReceiptView(
            UUID id,
            UUID branchId,

            @Schema(allowableValues = {"open", "allocated", "refunded", "reversed"})
            String status,

            long amountMinor,
            String currency,
            LocalDate valueDate,
            Instant createdAt,

            @Schema(
                    description =
                            "Why the loan or account did not take it, a closed word such as invalid_status_transition")
            String refusalCode,

            @Schema(description = "The provider's payment code, as shown on the provider's statement")
            String confirmationCode,

            @Schema(description = "Our reference for the order (BMS-...)")
            String orderReference,

            @Schema(description = "The provider's order tracking id")
            String orderTrackingId,

            String reportedMethod,
            Instant paidAt,
            IntendedSubject intendedSubject,
            Allocation allocation,
            Refund refund,

            @Schema(description = "Why it was allocated or refunded, as the maker wrote it")
            String note,

            UUID requestedBy,
            UUID decidedBy,
            Instant decidedAt,
            UUID approvalRequestId,

            @Schema(description = "Requests waiting for a checker (an open receipt)")
            List<PendingRequest> pendingRequests,

            int version) {}

    @Schema(name = "UnallocatedReceiptPage")
    record ReceiptPage(List<ReceiptView> items, String nextCursor) {}
}
