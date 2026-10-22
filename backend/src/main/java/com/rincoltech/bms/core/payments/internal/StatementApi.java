package com.rincoltech.bms.core.payments.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The shapes of {@code /api/v1/payments/statements} (chapter 7 section 7.11.10, FR-PAY-11). */
final class StatementApi {

    private StatementApi() {}

    @Schema(name = "PaymentStatement")
    record StatementView(
            UUID id,
            String fileName,
            Instant createdAt,
            UUID uploadedBy,
            int rowCount,
            LocalDate periodFrom,
            LocalDate periodTo,

            @Schema(
                    description =
                            "The days the export is complete for, where a booked payment missing from it is reported; absent when it covers none")
            LocalDate coveredFrom,

            LocalDate coveredTo,
            String currency,

            @Schema(description = "Lines whose order is ours, paid, with the same amount and currency")
            int matchedCount,

            @Schema(
                    description =
                            "Lines whose order is ours but whose amount, currency or paid state differs, or whose code names several orders")
            int differentCount,

            @Schema(description = "Lines no order of ours has")
            int unmatchedCount,

            @Schema(description = "The fees posted to the gateway charges account by this upload")
            long feesPostedMinor) {}

    @Schema(name = "PaymentStatementRow")
    record RowView(
            int rowNo,
            String orderTrackingId,
            String confirmationCode,
            long amountMinor,
            long feeMinor,
            Long settledMinor,
            LocalDate paidOn,
            String paymentMethod,

            @Schema(
                    allowableValues = {
                        "matched",
                        "amount_mismatch",
                        "currency_mismatch",
                        "not_paid_here",
                        "ambiguous",
                        "unmatched"
                    })
            String matchStatus,

            UUID intentId,

            @Schema(
                    description =
                            "Two people agreed the line is money for something outside this system (marked, then confirmed)")
            boolean reviewedOutside,

            @Schema(
                    description =
                            "Marked as outside this system and waiting for a second person to confirm; still a finding")
            boolean outsideAwaitingConfirmation,

            @Schema(
                    description =
                            "posted: the fee was posted from this line; already_posted: an earlier statement did; none: no fee on the"
                                    + " line; held: the line did not match cleanly, so no fee was posted; awaiting_approval: the fee"
                                    + " is above the automatic rate and waits for a second person; not_approved: that request was"
                                    + " rejected, cancelled or expired, so nothing was posted",
                    allowableValues = {"posted", "already_posted", "none", "held", "awaiting_approval", "not_approved"})
            String feeStatus) {}

    @Schema(name = "PaymentStatementDetail")
    record StatementDetail(
            StatementView statement,
            List<RowView> rows,

            @Schema(description = "True when more lines exist than are listed here (the first 1,000 are)")
            boolean rowsTruncated) {}

    @Schema(name = "PaymentStatementList")
    record StatementList(List<StatementView> items) {}
}
