package com.rincoltech.bms.lending.savings;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Savings for callers outside a staff request: the fabricated lending seed now, the pilot import
 * later (ADR-032). Each call runs in the caller's transaction (it refuses to run without one) with
 * the same rules, postings and audit as the staff routes, but the caller names the actor, because
 * there is no request principal, and no receipt SMS is queued. Staff never reach this port.
 */
public interface SavingsServicing {

    /** Opens an account as FR-SAV-02 does, at the member's home branch; returns its id. */
    UUID openAccount(UUID memberId, UUID productId, LocalDate openedOn, UUID openedBy);

    /** Records a deposit as FR-SAV-03 does; returns the transaction id. */
    UUID deposit(UUID accountId, long amountMinor, LocalDate valueDate, String paymentMethodKey, UUID recordedBy);

    /**
     * Records a withdrawal as an approved {@code savings_withdrawal} would, with the same limits;
     * returns the transaction id. The maker and checker must differ.
     */
    UUID withdraw(
            UUID accountId,
            long amountMinor,
            LocalDate valueDate,
            String paymentMethodKey,
            UUID makerId,
            UUID checkerId);

    /**
     * Runs the nightly end of day for the bound tenant through {@code through}: end-of-day balances,
     * interest posting at each product period end, dormancy (FR-SAV-05, FR-SAV-06). Idempotent:
     * days already written are skipped. Returns the number of interest postings written.
     */
    int endOfDay(LocalDate through);
}
