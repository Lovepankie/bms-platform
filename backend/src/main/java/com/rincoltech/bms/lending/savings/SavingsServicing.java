package com.rincoltech.bms.lending.savings;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Savings for callers outside a staff request: the fabricated lending seed, the pilot import (ADR-032)
 * and gateway bookings (ADR-035, issue #219). Each call runs in the caller's transaction (it refuses to
 * run without one) with the same rules, postings and audit as the staff routes, but the caller names the
 * actor, because there is no request principal, and no receipt SMS is queued unless the gateway overload
 * asks for it. Staff never reach this port.
 */
public interface SavingsServicing {

    /** Opens an account as FR-SAV-02 does, at the member's home branch; returns its id. */
    UUID openAccount(UUID memberId, UUID productId, LocalDate openedOn, UUID openedBy);

    /** Records a deposit as FR-SAV-03 does; returns the transaction id. */
    UUID deposit(UUID accountId, long amountMinor, LocalDate valueDate, String paymentMethodKey, UUID recordedBy);

    /**
     * Records a deposit that arrived through a payment gateway (spec 3.4.1, issue #219): the same receipt number,
     * journal, running balance and audit as the cash deposit above, because it is the same code, plus three things the
     * seed and the import never needed.
     *
     * <ul>
     *   <li>{@code externalReference} is the provider's reference for the payment, the order tracking id (unique per
     *       payment; a short confirmation code is not). For the {@code gateway} method a second call with the same
     *       reference, account and amount returns the first transaction and writes nothing, so a replay books one
     *       deposit; the same reference for another account or amount is refused {@code duplicate_reference}. A unique
     *       index over (tenant, method, reference) backs it.
     *   <li>{@code origin} is the transaction's {@code source}: {@code gateway} for a gateway booking.
     *   <li>{@code queueReceiptNotice} queues the savings receipt SMS in the same transaction (only when the tenant's SMS
     *       events are on, as for a staff deposit); the seed and the import pass false.
     * </ul>
     *
     * Refusals are the {@code ApiException} business codes of the cash route ({@code account_closed},
     * {@code value_date_in_future}, {@code before_opening}, {@code value_date_closed}, {@code below_minimum_opening},
     * {@code payment_method_unmapped}). A gateway deposit to a frozen account is refused {@code account_frozen}: staff
     * decide what happens to money for a frozen account, so it is held as an unallocated receipt.
     */
    UUID deposit(
            UUID accountId,
            long amountMinor,
            LocalDate valueDate,
            String paymentMethodKey,
            UUID recordedBy,
            String externalReference,
            String origin,
            boolean queueReceiptNotice);

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
