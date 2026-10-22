package com.rincoltech.bms.core.payments.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The shapes of {@code /api/v1/payments/reconciliation} (chapter 7 section 7.11.10, FR-PAY-05). */
final class ReconciliationApi {

    private ReconciliationApi() {}

    @Schema(name = "PaymentReconciliationRun")
    record RunView(
            UUID id,
            Instant createdAt,
            LocalDate ranOn,
            @Schema(allowableValues = {"job", "manual"}) String trigger,
            LocalDate windowFrom,

            @Schema(description = "Orders looked at; absent for a reader of some branches only")
            Integer intentsExamined,

            @Schema(description = "Statements looked at; absent for a reader of some branches only")
            Integer statementsUsed,

            @Schema(description = "Differences; for a reader of some branches, those in their branches")
            int findingCount,

            @Schema(
                    description =
                            "The run could not read everything: what it lists is partial, and it lists nothing that needs the whole")
            boolean truncated) {}

    @Schema(name = "PaymentReconciliationFinding")
    record FindingView(
            UUID id,

            @Schema(
                    description =
                            "What differs. completed_not_booked, pending_over_24h, paid_differs, reversed_awaiting_review,"
                                    + " reversal_blocked, reversed_after_refund, manual_receipt_duplicate, in_provider_not_in_bms,"
                                    + " in_bms_not_in_provider, amount_mismatch, currency_mismatch, ambiguous_statement_row")
            String kind,

            UUID intentId,
            UUID branchId,
            String orderTrackingId,
            String confirmationCode,
            Long bmsAmountMinor,
            Long providerAmountMinor,
            String currency,

            @Schema(description = "A receipt keyed by hand (manual_receipt_duplicate): its transaction")
            UUID otherTransactionId,

            UUID statementRowId) {}

    @Schema(name = "PaymentReconciliation")
    record Report(
            @Schema(description = "The latest run, or absent before the first")
            RunView run,

            Map<String, Integer> countsByKind,
            List<FindingView> findings,
            boolean findingsTruncated) {}
}
