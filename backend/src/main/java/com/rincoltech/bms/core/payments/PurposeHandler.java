package com.rincoltech.bms.core.payments;

import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What a payment is for, registered by the module that owns the subject (chapter 5 section 5.4.3, spec 3.4): one bean
 * per purpose, found by {@code core.payments} through the Spring context, so the core never imports a vertical. Slices
 * #216 and #219 add the loan repayment and savings deposit handlers; this slice ships the registry and a handler for
 * the dev and test profiles only.
 *
 * <p>Both methods run inside a transaction of the payments module with the tenant bound, so a handler reads and writes
 * its own tables under row-level security and posts through the ledger's single operation in the same transaction.
 * Neither may call a provider.
 */
public interface PurposeHandler {

    /** {@code <module>.<what>}, for example {@code lending.loan_repayment}; the value stored on the intent. */
    String purpose();

    /**
     * The branch the subject belongs to, if the caller can see it: asked first, so the permission check is made before
     * {@link #describe} says anything about the subject (a limit on the amount would otherwise tell an unauthorised
     * caller).
     */
    default java.util.Optional<UUID> branchOf(UUID subjectId) {
        return java.util.Optional.empty();
    }

    /**
     * Called when an intent is created. Checks that the subject exists, is visible to the caller and can take this
     * amount, and says which branch the money belongs to. Refuses with a {@link PurposeRefusal} (the codes of chapter 7
     * section 7.11.10: {@code subject_not_payable}, {@code exceeds_payoff}); the caller turns it into a 422.
     */
    Subject describe(UUID subjectId, long amountMinor, String currency) throws PurposeRefusal;

    /**
     * Books a verified payment: the registered module posts the repayment, the deposit or the confirmation, and returns
     * the id of the transaction it created. Called at most once per intent with success; an implementation must still
     * be idempotent on {@link Booking#providerReference} (the order tracking id, unique per intent, spec 3.4.1; the
     * confirmation code is not unique), because the first call may have committed with its
     * answer lost.
     *
     * @throws PurposeRefusal a business refusal (a closed loan, a value date in the future): the transaction part of
     *     the handler is rolled back to a savepoint and the payment is not stranded (ADR-035 decision 14)
     * @throws RuntimeException any other exception is a technical failure: the whole booking rolls back and the poller
     *     retries it
     */
    UUID book(Booking booking) throws PurposeRefusal;

    /** @param subjectType a word such as {@code loan} or {@code savings_account} */
    record Subject(String subjectType, UUID branchId) {}

    /**
     * @param amountMinor the amount the provider confirmed was paid, in the intent's currency
     * @param valueDate the payment date in the tenant's time zone; today when a first try was refused as
     *     {@code before_last_repayment}
     * @param late the payment completed after the intent expired
     */
    record Booking(
            UUID intentId,
            String purpose,
            String subjectType,
            UUID subjectId,
            UUID branchId,
            long amountMinor,
            String currency,
            String confirmationCode,
            String providerReference,
            String reportedMethod,
            LocalDate valueDate,
            UUID initiatedBy,
            boolean late) {}

    /** The code a handler uses when a repayment cannot be dated before the loan's last one; retried once dated today. */
    String BEFORE_LAST_REPAYMENT = "before_last_repayment";

    /** A business refusal. The code is a closed lower case word; it is stored on the intent and shown to staff. */
    final class PurposeRefusal extends RuntimeException {

        private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{1,59}$");

        private final String code;

        public PurposeRefusal(String code) {
            super(
                    "purpose refused: " + (code != null && CODE.matcher(code).matches() ? code : "refused"),
                    null,
                    false,
                    false);
            this.code = code != null && CODE.matcher(code).matches() ? code : "refused";
        }

        public String code() {
            return code;
        }
    }
}
