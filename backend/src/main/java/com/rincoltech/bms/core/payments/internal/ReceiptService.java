package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.ledger.PaymentMethods;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.payments.PurposeHandler;
import com.rincoltech.bms.core.payments.PurposeHandler.PurposeRefusal;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.ActionOutcome;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.AllocateRequest;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.Allocation;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.IntendedSubject;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.PendingRequest;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.ReceiptPage;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.ReceiptView;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.Refund;
import com.rincoltech.bms.core.payments.internal.ReceiptApi.RefundRequest;
import com.rincoltech.bms.core.payments.internal.ReceiptStore.Pending;
import com.rincoltech.bms.core.payments.internal.ReceiptStore.Receipt;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The unallocated receipts queue (FR-PAY-04, spec 3.16): list and read, and the two requests a cashier makes, to put a
 * receipt's money on a loan or a savings account and to send it back. Both are maker-checker requests (the executing code is
 * {@link ReceiptSettler}); asking never moves money. Permission is checked before the receipt, the loan or the account is
 * described: a receipt in a branch the caller cannot read is "not found", and the existence of a subject is said only to someone
 * who may allocate in its branch.
 */
@Service
class ReceiptService {

    static final String BASE = "/api/v1/payments/unallocated/";
    static final String READ = "core.payments.read";
    static final String ALLOCATE = "core.payments.allocate";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final Set<String> STATUSES = Set.of("open", "allocated", "refunded", "reversed");
    private static final Pattern REFERENCE = Pattern.compile("^[\\x20-\\x7E]{1,100}$");

    private final ReceiptStore receipts;
    private final IntentStore intents;
    private final PurposeRegistry registry;
    private final PaymentMethods methods;
    private final Approvals approvals;
    private final Idempotency idempotency;
    private final BusinessClock clock;

    ReceiptService(
            ReceiptStore receipts,
            IntentStore intents,
            PurposeRegistry registry,
            PaymentMethods methods,
            Approvals approvals,
            Idempotency idempotency,
            BusinessClock clock) {
        this.receipts = receipts;
        this.intents = intents;
        this.registry = registry;
        this.methods = methods;
        this.approvals = approvals;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---- reading -------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    ReceiptPage list(String status, List<UUID> branchIds, Integer limit, String cursor) {
        Principal principal = CurrentPrincipal.require();
        if (status != null && !"all".equals(status) && !STATUSES.contains(status)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("status", "invalid", "Use open, allocated, refunded, reversed or all.")));
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        List<UUID> seeable = principal.branchFilter(READ, branchIds);
        if (seeable != null && seeable.isEmpty()) {
            return new ReceiptPage(List.of(), null);
        }
        var after = Cursor.decodeKey(cursor);
        List<Receipt> rows = receipts.page(
                "all".equals(status) ? null : status == null ? "open" : status,
                seeable,
                after.map(k -> k.at()).orElse(null),
                after.map(k -> k.id()).orElse(null),
                size + 1);
        boolean more = rows.size() > size;
        List<Receipt> page = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        page.getLast().createdAt() + "|" + page.getLast().id())
                : null;
        return new ReceiptPage(views(page), next);
    }

    @Transactional(readOnly = true)
    ReceiptView get(UUID id) {
        Principal principal = CurrentPrincipal.require();
        Receipt receipt =
                receipts.find(id).filter(r -> principal.may(READ, r.branchId())).orElseThrow(ApiException::notFound);
        return views(List.of(receipt)).getFirst();
    }

    private List<ReceiptView> views(List<Receipt> page) {
        Map<UUID, List<PendingRequest>> pending = new LinkedHashMap<>();
        for (Pending p : receipts.pending(
                page.stream().filter(Receipt::open).map(Receipt::id).toList(), clock.now())) {
            pending.computeIfAbsent(p.receiptId(), k -> new java.util.ArrayList<>())
                    .add(new PendingRequest(p.approvalId(), p.actionType()));
        }
        return page.stream()
                .map(r -> view(r, pending.getOrDefault(r.id(), List.of())))
                .toList();
    }

    private static ReceiptView view(Receipt r, List<PendingRequest> pending) {
        return new ReceiptView(
                r.id(),
                r.branchId(),
                r.status(),
                r.amountMinor(),
                r.currency(),
                r.valueDate(),
                r.createdAt(),
                r.refusalCode(),
                r.confirmationCode(),
                r.merchantReference(),
                r.orderTrackingId(),
                r.reportedMethod(),
                r.paidAt(),
                new IntendedSubject(r.purpose(), r.subjectType(), r.subjectId()),
                r.allocatedTxnId() == null
                        ? null
                        : new Allocation(
                                r.allocatedPurpose(),
                                r.allocatedSubjectType(),
                                r.allocatedSubjectId(),
                                r.allocatedTxnId()),
                r.refundMethodKey() == null ? null : new Refund(r.refundMethodKey(), r.refundReference()),
                r.note(),
                r.requestedBy(),
                r.decidedBy(),
                r.decidedAt(),
                r.approvalId(),
                pending,
                r.version());
    }

    // ---- asking --------------------------------------------------------------------------------

    @Transactional
    Outcome<ActionOutcome> requestAllocation(UUID id, String key, AllocateRequest r) {
        return idempotency.once(key, "POST", BASE + id + "/allocate", r, ActionOutcome.class, () -> {
            Principal principal = CurrentPrincipal.require();
            Receipt receipt = requestable(principal, id);
            if (!ReceiptSettler.ALLOCATION_PURPOSES.contains(r.purpose())) {
                throw ApiException.rule(
                        "unknown_purpose", "A receipt can be allocated to a loan repayment or a savings deposit.");
            }
            PurposeHandler handler = registry.find(r.purpose())
                    .orElseThrow(() -> ApiException.rule("unknown_purpose", "That kind of payment is not available."));
            // The branch first and the permission on it before anything is said about the loan or the account (#312
            // review).
            UUID targetBranch = handler.branchOf(r.subjectId())
                    .orElseThrow(() -> ApiException.rule("subject_not_found", "That loan or account was not found."));
            if (!principal.may(ALLOCATE, targetBranch)) {
                throw denied();
            }
            try {
                handler.describe(r.subjectId(), receipt.amountMinor(), receipt.currency());
            } catch (PurposeRefusal refusal) {
                // A payment above the payoff is not wrong here: the excess is the loan's credit balance (spec 3.7).
                if (!"exceeds_payoff".equals(refusal.code())) {
                    throw ApiException.rule(refusal.code(), "That loan or account cannot take this money now.");
                }
            }
            Map<String, Object> payload = snapshot(receipt, r.reason());
            payload.put(ReceiptSettler.PURPOSE, r.purpose());
            payload.put(ReceiptSettler.SUBJECT_ID, r.subjectId().toString());
            payload.put(ReceiptSettler.SUBJECT_BRANCH_ID, targetBranch.toString());
            return outcome(receipt, ReceiptAllocationAction.TYPE, payload);
        });
    }

    @Transactional
    Outcome<ActionOutcome> requestRefund(UUID id, String key, RefundRequest r) {
        return idempotency.once(key, "POST", BASE + id + "/refund", r, ActionOutcome.class, () -> {
            Principal principal = CurrentPrincipal.require();
            Receipt receipt = requestable(principal, id);
            if (!ReceiptSettler.REFUND_METHODS.contains(r.methodKey())) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "method_key", "invalid", "Use gateway, cash, bank, mtn_momo or airtel_money.")));
            }
            if (!REFERENCE.matcher(r.reference()).matches()) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "reference",
                        "invalid",
                        "Use the printable characters of the bank or wallet reference, up to 100.")));
            }
            if (!"gateway".equals(r.methodKey())) {
                // Refuses with payment_method_unmapped before anything is asked: the checker is not handed what cannot
                // run.
                methods.accountRef(r.methodKey(), receipt.branchId());
            }
            Map<String, Object> payload = snapshot(receipt, r.reason());
            payload.put(ReceiptSettler.METHOD, r.methodKey());
            payload.put(ReceiptSettler.REFERENCE, r.reference());
            return outcome(receipt, ReceiptRefundAction.TYPE, payload);
        });
    }

    /** The receipt, if the caller may read it, may allocate in its branch, and it is still open and its money still held. */
    private Receipt requestable(Principal principal, UUID id) {
        Receipt receipt =
                receipts.lock(id).filter(r -> principal.may(READ, r.branchId())).orElseThrow(ApiException::notFound);
        if (!principal.may(ALLOCATE, receipt.branchId())) {
            throw denied();
        }
        if (!receipt.open()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "receipt_not_open",
                    "Receipt already settled",
                    "This receipt was already " + receipt.status() + ".");
        }
        Intent intent = intents.find(receipt.intentId()).orElseThrow(ApiException::notFound);
        if (!"booked_unallocated".equals(intent.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "payment_not_held",
                    "The payment has changed",
                    "The provider reported this payment as " + intent.status()
                            + ", so it cannot be allocated or refunded here.");
        }
        return receipt;
    }

    private static Map<String, Object> snapshot(Receipt receipt, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("receipt_id", receipt.id().toString());
        payload.put(ReceiptSettler.AMOUNT, receipt.amountMinor());
        payload.put(ReceiptSettler.CURRENCY, receipt.currency());
        payload.put(ReceiptSettler.REASON, reason.trim());
        return payload;
    }

    private ActionOutcome outcome(Receipt receipt, String type, Map<String, Object> payload) {
        Approvals.Outcome o = approvals.request(new ActionRequest(
                type,
                receipt.branchId(),
                receipt.id(),
                receipt.version(),
                receipt.amountMinor(),
                receipt.currency(),
                payload));
        return new ActionOutcome(receipt.id(), o.approvalId());
    }

    private static ApiException denied() {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                "permission_denied",
                "Permission denied",
                "You do not have permission to allocate payments for this branch.");
    }
}
