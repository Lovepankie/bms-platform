package com.rincoltech.bms.lending.investments;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Investment servicing for callers outside a staff request: the fabricated lending seed now, the
 * pilot import later. Each call runs in the caller's transaction (it refuses to run without one)
 * with the same rules, postings and audit as the staff routes and the nightly job; the caller
 * names the actors, because there is no request principal.
 */
public interface InvestmentServicing {

    /**
     * Opens a {@code pending_funding} investment on the product's current terms (FR-INV-02), at the
     * member's branch; returns its id.
     */
    UUID open(
            UUID memberId, UUID productId, long amountMinor, int termMonths, String maturityInstruction, UUID openedBy);

    /** Funds a {@code pending_funding} investment as a checker's approval would (FR-INV-03); maker and checker differ. */
    void fundApproved(UUID investmentId, LocalDate valueDate, String paymentMethodKey, UUID makerId, UUID checkerId);

    /**
     * The nightly job's work as of {@code businessDate} (FR-INV-04, FR-INV-05, FR-INV-07):
     * accrues every period ended on or before it, processes maturities and records reminders.
     * Idempotent: a second run on the same date changes nothing.
     */
    DailyRun runDaily(LocalDate businessDate);

    /** Pays the return that is due and unpaid (FR-INV-04); returns the transaction id. */
    UUID payDueReturn(UUID investmentId, LocalDate valueDate, String paymentMethodKey, UUID recordedBy);

    /** Pays a matured investment out on {@code valueDate}: principal and unpaid return (FR-INV-05). */
    UUID payOutMatured(UUID investmentId, LocalDate valueDate, String paymentMethodKey, UUID recordedBy);

    /** What one daily run did. */
    record DailyRun(int accruals, int matured, int rolledOver, int reminders) {}
}
