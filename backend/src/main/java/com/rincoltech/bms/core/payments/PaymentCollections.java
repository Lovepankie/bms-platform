package com.rincoltech.bms.core.payments;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Starting and reading a collection for the signed-in staff user (FR-PAY-01). The module that owns the subject (a loan
 * page, a savings account page) calls this or the route; the permission check ({@code core.payments.collect} in the
 * branch of the subject) is made here, not by the caller.
 */
public interface PaymentCollections {

    /**
     * Writes the intent ({@code created}), commits it, and asks the provider for the order within a short budget. The
     * answer is the intent as it stands: normally {@code pending} with its {@code redirect_url}, otherwise
     * {@code created} (the creation job finishes it) or {@code failed} with a reason, and in every case a row that can
     * be read back by id.
     *
     * @throws com.rincoltech.bms.kernel.ApiException 422 {@code collections_disabled}, {@code unknown_purpose},
     *     {@code validation_failed} (with the field problems {@code amount_minor}, {@code payer_phone}, {@code subject_id}),
     *     {@code currency_not_supported}, {@code daily_ceiling_exceeded} or the purpose's own refusal
     *     ({@code subject_not_payable}, {@code exceeds_payoff}); 403 when the caller may not collect in the subject's
     *     branch
     */
    IntentView create(CreateIntent request, String idempotencyKey);

    /** The intent, for its initiator or a holder of {@code core.payments.read}; 404 for anyone else. */
    IntentView get(UUID intentId);

    /** The provider confirmation code of the intent whose booking created this subject transaction, if any (for a receipt). */
    java.util.Optional<String> confirmationCodeOf(UUID bookedTransactionId);

    /** Cancels an order that is not paid (best effort at the provider) and ends the intent {@code cancelled}. */
    IntentView cancel(UUID intentId);

    @Schema(name = "CreatePaymentIntentRequest")
    record CreateIntent(
            @Schema(description = "What the money is for, registered by a module, for example lending.loan_repayment")
            String purpose,

            UUID subjectId,

            @Schema(
                    description =
                            "Whole currency units in minor units; UGX has no fraction, so 1500 is 1,500 shillings")
            long amountMinor,

            @Schema(description = "The payer's mobile number: 07XXXXXXXX, 2567XXXXXXXX or +2567XXXXXXXX")
            String payerPhone) {}

    /**
     * @param status created, pending, paid, booked, booked_unallocated, needs_attention, failed, cancelled, expired,
     *     reversed_pending_review or reversed
     * @param flags {@code paid_less}, {@code paid_more} or {@code late}
     * @param payerPhone masked to the last four digits
     * @param redirectUrl where the payer pays; present from {@code pending}
     */
    @Schema(name = "PaymentIntent")
    record IntentView(
            UUID id,
            String purpose,
            String subjectType,
            UUID subjectId,
            long amountMinor,
            String currency,
            String status,
            String merchantReference,
            String redirectUrl,
            String payerPhone,
            Instant createdAt,
            Instant expiresAt,
            Long paidAmountMinor,
            Instant paidAt,
            List<String> flags,
            String failureReason,
            String refusalCode,
            UUID bookedTransactionId,

            @Schema(
                    description =
                            "The provider's confirmation code once the payment is confirmed; not unique across payments")
            String confirmationCode) {}
}
