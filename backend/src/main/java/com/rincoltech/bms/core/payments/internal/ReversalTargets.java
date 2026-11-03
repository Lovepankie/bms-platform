package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.PurposeHandler;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.core.payments.internal.ReceiptStore.Receipt;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Where the money of a payment the provider reversed stands in the books, and so what reversing it means (spec 3.8):
 * {@code BOOKED} on a loan or a savings account (a repayment or a deposit to reverse, whichever purpose took it, the order's
 * own or the one a receipt was allocated to), {@code HELD} in the unallocated receipts queue (the receipt's entry to reverse),
 * or {@code NONE} (a receipt already refunded or reversed: nothing stands, and a refund the lender paid out by hand is a loss
 * the accountant records).
 */
@Component
class ReversalTargets {

    enum Kind {
        BOOKED,
        HELD,
        NONE
    }

    /**
     * @param purpose the purpose that booked the transaction (BOOKED)
     * @param receiptId the receipt (HELD, or the one the money was allocated from)
     * @param branchId the branch of what the reversal changes: the loan's or account's own for a booking (a receipt of one
     *     branch can be allocated to a loan of another), the receipt's for money still held
     */
    record Target(Kind kind, String purpose, UUID transactionId, UUID receiptId, UUID branchId) {
        static final Target NONE = new Target(Kind.NONE, null, null, null, null);
    }

    private final ReceiptStore receipts;
    private final PurposeRegistry registry;

    ReversalTargets(ReceiptStore receipts, PurposeRegistry registry) {
        this.receipts = receipts;
        this.registry = registry;
    }

    Target of(Intent intent) {
        Optional<Receipt> receipt = receipts.findByIntent(intent.id());
        if (receipt.isPresent()) {
            Receipt r = receipt.get();
            return switch (r.status()) {
                case "open" -> new Target(Kind.HELD, null, null, r.id(), intent.branchId());
                case "allocated" ->
                    new Target(
                            Kind.BOOKED,
                            r.allocatedPurpose(),
                            r.allocatedTxnId(),
                            r.id(),
                            branchOf(r.allocatedPurpose(), r.allocatedSubjectId(), intent.branchId()));
                default -> Target.NONE;
            };
        }
        return intent.bookedTxnId() == null
                ? Target.NONE
                : new Target(
                        Kind.BOOKED,
                        intent.purpose(),
                        intent.bookedTxnId(),
                        null,
                        branchOf(intent.purpose(), intent.subjectId(), intent.branchId()));
    }

    private UUID branchOf(String purpose, UUID subjectId, UUID fallback) {
        return registry.find(purpose).flatMap(h -> h.branchOf(subjectId)).orElse(fallback);
    }

    PurposeRegistry registry() {
        return registry;
    }

    Optional<PurposeHandler> handler(Target target) {
        return registry.find(target.purpose());
    }

    /** The version the request is checked against on approval (FR-APR-08): the subject's own, or the receipt's. */
    Optional<Integer> version(Intent intent) {
        Target t = of(intent);
        return switch (t.kind()) {
            case HELD -> receipts.version(t.receiptId());
            case BOOKED -> handler(t).flatMap(h -> h.subjectVersionOfTransaction(t.transactionId()));
            case NONE -> Optional.empty();
        };
    }
}
