package com.rincoltech.bms.lending.loans;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Loan servicing for callers outside a staff request: the fabricated lending seed, the pilot
 * import (ADR-026) and gateway bookings (ADR-035, issue #216). Each call runs in the caller's transaction (it refuses to run
 * without one) with the same rules, postings and audit as the staff routes, but the caller names
 * the actors, because there is no request principal. Staff never reach this port: their routes go
 * through the approval mechanism and the permission matrix.
 */
public interface LoanServicing {

    /**
     * Disburses an approved loan exactly as a checker's approval of {@code loan_disbursement}
     * would (FR-DIS-02). The maker and checker must differ.
     */
    void disburseApproved(
            UUID loanId, LocalDate disbursementDate, String paymentMethodKey, UUID makerId, UUID checkerId);

    /** Records a repayment (or, on a written-off loan, a recovery) as FR-REP-03 does; returns the transaction id. */
    UUID recordRepayment(UUID loanId, long amountMinor, LocalDate valueDate, String paymentMethodKey, UUID recordedBy);

    /**
     * Records a repayment that arrived through a payment gateway (spec 3.4.1, issue #216): the same allocation order,
     * receipt number, journal and audit as the cash repayment above, because it is the same code, plus three things the
     * seed and the import never needed.
     *
     * <ul>
     *   <li>{@code externalReference} is the provider's reference for the payment, the order tracking id (unique per
     *       payment; a short confirmation code is not). For the {@code gateway} method a second call with the same
     *       reference, loan and amount returns the first transaction and writes nothing, so a replay books one
     *       repayment; the same reference for another loan or amount is refused {@code duplicate_reference}. A unique index over (tenant, method, reference) backs it.
     *   <li>{@code origin} is the transaction's {@code source}: {@code gateway} for a gateway booking.
     *   <li>{@code queueReceiptNotice} queues the receipt SMS in the same transaction (only when the tenant's SMS events
     *       are on, as for a staff repayment); the seed and the import pass false.
     * </ul>
     *
     * Refusals are the {@code ApiException} business codes of the cash route ({@code before_last_repayment},
     * {@code value_date_in_future}, {@code payment_method_unmapped}, {@code invalid_status_transition}).
     */
    UUID recordRepayment(
            UUID loanId,
            long amountMinor,
            LocalDate valueDate,
            String paymentMethodKey,
            UUID recordedBy,
            String externalReference,
            String origin,
            boolean queueReceiptNotice);

    /**
     * Writes off an active loan's principal outstanding on {@code date} (today or earlier) exactly as
     * a checker's approval of {@code loan_write_off} would (FR-LCL-02). The maker and checker must
     * differ. Used by the fabricated insights history (#153) and later by the import.
     */
    void writeOffApproved(UUID loanId, LocalDate date, String reason, UUID makerId, UUID checkerId);
}
