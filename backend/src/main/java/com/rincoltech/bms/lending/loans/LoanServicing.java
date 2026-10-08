package com.rincoltech.bms.lending.loans;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Loan servicing for callers outside a staff request: the fabricated lending seed now, the pilot
 * import from increment 7 (ADR-026). Each call runs in the caller's transaction (it refuses to run
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
     * Writes off an active loan's principal outstanding on {@code date} (today or earlier) exactly as
     * a checker's approval of {@code loan_write_off} would (FR-LCL-02). The maker and checker must
     * differ. Used by the fabricated insights history (#153) and later by the import.
     */
    void writeOffApproved(UUID loanId, LocalDate date, String reason, UUID makerId, UUID checkerId);
}
