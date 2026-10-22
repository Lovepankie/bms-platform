package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction.Execution;
import com.rincoltech.bms.core.approvals.RequestSupersededException;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.ledger.LedgerPosting;
import com.rincoltech.bms.core.ledger.LedgerPosting.EntryRequest;
import com.rincoltech.bms.core.ledger.LedgerPosting.Line;
import com.rincoltech.bms.core.ledger.LedgerPosting.ReversalRequest;
import com.rincoltech.bms.core.ledger.PaymentMethods;
import com.rincoltech.bms.core.payments.PurposeHandler;
import com.rincoltech.bms.core.payments.PurposeHandler.Booking;
import com.rincoltech.bms.core.payments.PurposeHandler.PurposeRefusal;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.core.payments.internal.ReceiptStore.Receipt;
import com.rincoltech.bms.core.payments.internal.ReceiptStore.Settlement;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a checker's approval of an allocation or a refund does (FR-PAY-04, spec 3.16, ADR-035 amendment of slice A5). It runs
 * inside the approval's own transaction (it refuses to run without one), so the booking, the journal entry that clears the
 * liability, the receipt's new status and the audit rows commit together or not at all.
 *
 * <p>Exactly once, three times over. The receipt row is locked and must still be {@code open} (else the request ends stale,
 * FR-APR-08); its update is conditional on the status and version just read; and the database refuses a second settlement of a
 * settled receipt (guard trigger, V83), a second allocation transaction for one receipt (unique index) and a second reversal
 * of the receipt's journal entry (ledger, FR-GL-04). An allocation books through the purpose handler registered by the owning
 * module, the same {@link PurposeHandler#book} the booker uses, so the repayment or deposit has the allocation, receipt number,
 * journal and notice of a cashier's, is idempotent on the order tracking id and is refused for a closed loan or a frozen account
 * like any payment. A refusal is the approval's error: the request stays pending for the checker to reject or for the maker to
 * ask again with another subject.
 *
 * <p>The ledger: the receipt posted debit (the account the {@code gateway} method is mapped to) credit
 * {@code unallocated_receipts}. An allocation reverses that entry (debit the liability, credit the same account) and the
 * repayment or deposit brings its own debit of the account: the net is one debit and the loan or savings credit, as for a
 * payment that booked at once. A refund through the provider reverses the entry the same way (the provider hands the money back
 * out of the same balance); a refund paid out of cash, the bank or a wallet debits the liability and credits that method's
 * account.
 */
@Component
class ReceiptSettler {

    private static final Logger log = LoggerFactory.getLogger(ReceiptSettler.class);

    static final String PURPOSE = "purpose";
    static final String SUBJECT_ID = "subject_id";
    static final String SUBJECT_BRANCH_ID = "subject_branch_id";
    static final String REASON = "reason";
    static final String AMOUNT = "amount_minor";
    static final String CURRENCY = "currency";
    static final String METHOD = "method_key";
    static final String REFERENCE = "reference";

    /** The purposes a receipt may be allocated to: money owed to a lender's member. Never the platform's own. */
    static final Set<String> ALLOCATION_PURPOSES = Set.of("lending.loan_repayment", "lending.savings_deposit");

    /** How a refund can leave: back through the provider, or paid out by the lender in cash, from the bank or a wallet. */
    static final Set<String> REFUND_METHODS = Set.of("gateway", "cash", "bank", "mtn_momo", "airtel_money");

    static final String LIABILITY = UnallocatedReceipts.LIABILITY;
    static final String CHECKER_PERMISSION = "core.payments.allocate_approve";

    private final ReceiptStore receipts;
    private final IntentStore intents;
    private final IntentAudit intentAudit;
    private final PurposeRegistry registry;
    private final LedgerPosting ledger;
    private final LedgerAccounts accounts;
    private final PaymentMethods methods;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final TransactionTemplate savepoint;

    ReceiptSettler(
            ReceiptStore receipts,
            IntentStore intents,
            IntentAudit intentAudit,
            PurposeRegistry registry,
            LedgerPosting ledger,
            LedgerAccounts accounts,
            PaymentMethods methods,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            PlatformTransactionManager transactions) {
        this.receipts = receipts;
        this.intents = intents;
        this.intentAudit = intentAudit;
        this.registry = registry;
        this.ledger = ledger;
        this.accounts = accounts;
        this.methods = methods;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    // ---- allocation ---------------------------------------------------------------------------

    @Transactional(propagation = Propagation.MANDATORY)
    void allocate(Execution e) {
        Instant now = clock.now();
        LocalDate today = clock.today(tenant.profile().timezone());
        Receipt receipt = lockOpen(e);
        Intent intent = lockHeldIntent(receipt);
        Map<String, Object> p = e.payload();
        requireSnapshot(receipt, p);
        String purpose = String.valueOf(p.get(PURPOSE));
        if (!ALLOCATION_PURPOSES.contains(purpose)) {
            throw ApiException.rule("unknown_purpose", "A receipt cannot be allocated to that.");
        }
        PurposeHandler handler = registry.find(purpose)
                .orElseThrow(() -> ApiException.rule("unknown_purpose", "A receipt cannot be allocated to that."));
        UUID subjectId = UUID.fromString(String.valueOf(p.get(SUBJECT_ID)));
        UUID targetBranch = handler.branchOf(subjectId)
                .orElseThrow(() -> ApiException.rule("subject_not_found", "The loan or account was not found."));
        // The checker's own reach: a receipt of one branch may be put on a subject of another only by someone who may
        // decide in both.
        Principal checker = CurrentPrincipal.require();
        if (!checker.may(CHECKER_PERMISSION, targetBranch)) {
            throw ApiException.rule("permission_denied", "The checker may not decide for the subject's branch.");
        }
        if (!ledger.periodOpen(today)) {
            throw ApiException.rule("period_closed", "Today's accounting period is closed.");
        }
        // The credit balance this allocation creates is worked out again now, at approval (ADR-035 decision 51): the
        // payoff
        // may have moved since the request, and the audit records what it is when the money moves.
        long creditNow = currentExcess(handler, subjectId, receipt.amountMinor());
        // The money moves to the subject through the very code a payment that booked at once goes through. The first
        // try is dated the payment's day; a date the subject cannot take is retried dated today, as the booker does.
        Booked booked = book(
                handler, purpose, subjectId, targetBranch, receipt, intent, e.makerId(), receipt.valueDate(), today);
        UUID txnId = booked.transactionId();
        // The receipt's money leaves the liability on the day the repayment or deposit takes it, so a month that closes
        // between
        // the two never shows the money in both places (or in neither).
        PostedClearing cleared =
                clearThroughReversal(receipt, booked.date(), "Allocated to " + purpose + " " + subjectId);
        Settlement s = new Settlement(
                "allocated",
                e.makerId(),
                e.checkerId(),
                e.approvalId(),
                now,
                note(p),
                purpose,
                subjectTypeOf(purpose),
                subjectId,
                txnId,
                cleared.entryId(),
                null,
                null);
        settle(receipt, now, s);
        // Backstop and link: the order's money now has a booked subject transaction, the one the reconciliation joins
        // through.
        if (!intents.update(
                intent.id(),
                "booked_unallocated",
                null,
                now,
                Map.of("booked_subject_txn_id", txnId, "booked_at", now))) {
            throw new IllegalStateException("the intent changed under its own row lock");
        }
        Map<String, Object> after = base(receipt, e, "allocated");
        after.put("allocated_purpose", purpose);
        after.put("allocated_subject_type", s.allocatedSubjectType());
        after.put("allocated_subject_id", subjectId.toString());
        after.put("transaction_id", txnId.toString());
        after.put("cleared_entry_id", cleared.entryId().toString());
        if (creditNow > 0 || p.get(ReceiptService.CREDIT_BALANCE) != null) {
            after.put(ReceiptService.CREDIT_BALANCE, creditNow);
            after.put("credit_balance_at_request_minor", p.get(ReceiptService.CREDIT_BALANCE));
        }
        audit.record(new AuditLog.Entry(
                "core.payments.receipt.allocated",
                "unallocated_receipt",
                receipt.id(),
                receipt.branchId(),
                Map.of("status", "open"),
                after));
        Map<String, Object> onIntent = new LinkedHashMap<>();
        onIntent.put("unallocated_receipt_id", receipt.id().toString());
        onIntent.put("booked_subject_txn_id", txnId.toString());
        onIntent.put("paid_amount_minor", receipt.amountMinor());
        if (receipt.confirmationCode() != null) {
            onIntent.put("confirmation_code", receipt.confirmationCode());
        }
        intentAudit.user(intent, "allocated", "booked_unallocated", onIntent);
        log.info("unallocated receipt {} allocated", receipt.id());
    }

    /** The transaction the handler created and the day it dated it. */
    private record Booked(UUID transactionId, LocalDate date) {}

    private Booked book(
            PurposeHandler handler,
            String purpose,
            UUID subjectId,
            UUID targetBranch,
            Receipt receipt,
            Intent intent,
            UUID recordedBy,
            LocalDate first,
            LocalDate today) {
        try {
            return new Booked(
                    attempt(handler, purpose, subjectId, targetBranch, receipt, intent, recordedBy, first), first);
        } catch (PurposeRefusal refused) {
            boolean redate = PurposeHandler.BEFORE_LAST_REPAYMENT.equals(refused.code())
                    || "period_closed".equals(refused.code());
            if (!redate || first.equals(today)) {
                throw refusal(refused);
            }
            try {
                return new Booked(
                        attempt(handler, purpose, subjectId, targetBranch, receipt, intent, recordedBy, today), today);
            } catch (PurposeRefusal second) {
                throw refusal(second);
            }
        }
    }

    private UUID attempt(
            PurposeHandler handler,
            String purpose,
            UUID subjectId,
            UUID targetBranch,
            Receipt receipt,
            Intent intent,
            UUID recordedBy,
            LocalDate valueDate) {
        Booking booking = new Booking(
                intent.id(),
                purpose,
                subjectTypeOf(purpose),
                subjectId,
                targetBranch,
                receipt.amountMinor(),
                receipt.currency(),
                intent.confirmationCode(),
                intent.providerReference(),
                intent.reportedMethod(),
                valueDate,
                recordedBy,
                false);
        // A savepoint: a refusal undoes what the handler wrote and leaves the approval's transaction intact for the
        // retry.
        return savepoint.execute(status -> {
            UUID id = handler.book(booking);
            if (id == null) {
                throw new IllegalStateException("a payment purpose handler returned no transaction id");
            }
            return id;
        });
    }

    private static ApiException refusal(PurposeRefusal refused) {
        return ApiException.rule(refused.code(), "The loan or account refused the money: " + refused.code() + ".");
    }

    static String subjectTypeOf(String purpose) {
        return "lending.loan_repayment".equals(purpose) ? "loan" : "savings_account";
    }

    // ---- refund -------------------------------------------------------------------------------

    @Transactional(propagation = Propagation.MANDATORY)
    void refund(Execution e) {
        Instant now = clock.now();
        LocalDate today = clock.today(tenant.profile().timezone());
        Receipt receipt = lockOpen(e);
        Intent intent = lockHeldIntent(receipt);
        Map<String, Object> p = e.payload();
        requireSnapshot(receipt, p);
        String method = String.valueOf(p.get(METHOD));
        String reference = String.valueOf(p.get(REFERENCE));
        if (!REFUND_METHODS.contains(method)) {
            throw ApiException.rule("invalid_refund_method", "A refund goes back by one of the listed methods.");
        }
        if (!ledger.periodOpen(today)) {
            throw ApiException.rule("period_closed", "Today's accounting period is closed.");
        }
        PostedClearing cleared;
        if ("gateway".equals(method)) {
            cleared = clearThroughReversal(receipt, today, "Refunded through the provider, ref " + reference);
        } else {
            LedgerAccounts.Account liability = accounts.bySystemKey(LIABILITY)
                    .orElseThrow(() -> ApiException.rule(
                            "lending_chart_missing",
                            "The tenant has no active account with system key " + LIABILITY + "."));
            LedgerAccounts.Account paidFrom = accounts.byRef(methods.accountRef(method, receipt.branchId()))
                    .orElseThrow(() -> ApiException.rule(
                            "payment_method_unmapped", "That payment method has no account to pay the refund from."));
            if (!liability.currency().equals(receipt.currency())
                    || !paidFrom.currency().equals(receipt.currency())) {
                throw ApiException.rule(
                        "currency_mismatch", "The refund's accounts are not in the payment's currency.");
            }
            LedgerPosting.PostedEntry entry = ledger.post(new EntryRequest(
                    receipt.branchId(),
                    today,
                    "UF-" + receipt.merchantReference(),
                    "Payment refunded by " + method + ", ref " + reference,
                    UnallocatedReceipts.SOURCE_MODULE,
                    "unallocated_refund",
                    receipt.id(),
                    "payments.unallocated.refund:" + receipt.id(),
                    List.of(
                            new Line(liability.id(), receipt.amountMinor(), 0, receipt.currency(), null, null),
                            new Line(paidFrom.id(), 0, receipt.amountMinor(), receipt.currency(), null, null))));
            cleared = new PostedClearing(entry.entryId());
        }
        Settlement s = new Settlement(
                "refunded",
                e.makerId(),
                e.checkerId(),
                e.approvalId(),
                now,
                note(p),
                null,
                null,
                null,
                null,
                cleared.entryId(),
                method,
                reference);
        settle(receipt, now, s);
        Map<String, Object> after = base(receipt, e, "refunded");
        after.put("refund_method_key", method);
        after.put("refund_reference", reference);
        after.put("cleared_entry_id", cleared.entryId().toString());
        audit.record(new AuditLog.Entry(
                "core.payments.receipt.refunded",
                "unallocated_receipt",
                receipt.id(),
                receipt.branchId(),
                Map.of("status", "open"),
                after));
        Map<String, Object> onIntent = new LinkedHashMap<>();
        onIntent.put("unallocated_receipt_id", receipt.id().toString());
        onIntent.put("refund_method_key", method);
        onIntent.put("paid_amount_minor", receipt.amountMinor());
        intentAudit.user(intent, "refunded", "booked_unallocated", onIntent);
        log.info("unallocated receipt {} refunded", receipt.id());
    }

    // ---- reversal of money still held -------------------------------------------------------------

    /**
     * The provider reported the payment reversed while its money still sat in the queue, and a checker approved (spec 3.8,
     * ADR-035 decision 16): the receipt's own entry is reversed (the liability and the clearing balance both go) and the
     * receipt ends {@code reversed}, once. The intent is the caller's to end.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void reverseHeld(UUID receiptId, Execution e, String reason) {
        Instant now = clock.now();
        LocalDate today = clock.today(tenant.profile().timezone());
        Receipt receipt = lockOpen(receiptId);
        if (!ledger.periodOpen(today)) {
            throw ApiException.rule("period_closed", "Today's accounting period is closed.");
        }
        PostedClearing cleared = clearThroughReversal(receipt, today, "Reversed by the provider");
        settle(
                receipt,
                now,
                new Settlement(
                        "reversed",
                        e.makerId(),
                        e.checkerId(),
                        e.approvalId(),
                        now,
                        reason,
                        null,
                        null,
                        null,
                        null,
                        cleared.entryId(),
                        null,
                        null));
        Map<String, Object> after = base(receipt, e, "reversed");
        after.put("cleared_entry_id", cleared.entryId().toString());
        audit.record(new AuditLog.Entry(
                "core.payments.receipt.reversed",
                "unallocated_receipt",
                receipt.id(),
                receipt.branchId(),
                Map.of("status", "open"),
                after));
        log.info("unallocated receipt {} reversed", receipt.id());
    }

    // ---- shared -------------------------------------------------------------------------------

    private record PostedClearing(UUID entryId) {}

    /** Debit the liability, credit the account the receipt debited: the receipt's own entry, reversed (FR-GL-04, once). */
    private PostedClearing clearThroughReversal(Receipt receipt, LocalDate today, String memo) {
        LedgerPosting.PostedEntry reversed = ledger.reverse(new ReversalRequest(
                receipt.journalEntryId(),
                today,
                "UC-" + receipt.merchantReference(),
                memo,
                "payments.unallocated.cleared:" + receipt.id()));
        return new PostedClearing(reversed.entryId());
    }

    private Receipt lockOpen(Execution e) {
        return lockOpen(e.subjectId());
    }

    private Receipt lockOpen(UUID receiptId) {
        Receipt receipt = receipts.lock(receiptId).orElseThrow(ApiException::notFound);
        if (!receipt.open()) {
            throw new RequestSupersededException(
                    "receipt_not_open",
                    "The receipt is settled",
                    "This receipt was " + receipt.status() + " by another request. This request is closed.",
                    Map.of());
        }
        return receipt;
    }

    /** The order must still be the one whose money sits in unallocated receipts (the provider may have reversed it). */
    private Intent lockHeldIntent(Receipt receipt) {
        Intent intent = intents.lock(receipt.intentId()).orElseThrow(ApiException::notFound);
        if (!"booked_unallocated".equals(intent.status())) {
            throw new RequestSupersededException(
                    "payment_not_held",
                    "The payment has changed",
                    "The payment is now " + intent.status()
                            + ", so its money can no longer be allocated or refunded here."
                            + " This request is closed.",
                    Map.of());
        }
        return intent;
    }

    private static void requireSnapshot(Receipt receipt, Map<String, Object> payload) {
        Object amount = payload.get(AMOUNT);
        if (!(amount instanceof Number n)
                || n.longValue() != receipt.amountMinor()
                || !receipt.currency().equals(payload.get(CURRENCY))) {
            throw new RequestSupersededException(
                    "receipt_changed",
                    "The receipt has changed",
                    "The amount or currency is not the one requested. This request is closed.",
                    Map.of());
        }
    }

    private void settle(Receipt receipt, Instant now, Settlement s) {
        if (!receipts.settle(receipt.id(), receipt.version(), now, s)) {
            throw new IllegalStateException("the receipt changed under its own row lock");
        }
    }

    private static String note(Map<String, Object> payload) {
        Object reason = payload.get(REASON);
        return reason == null ? null : String.valueOf(reason);
    }

    private static Map<String, Object> base(Receipt receipt, Execution e, String status) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", status);
        after.put("amount_minor", receipt.amountMinor());
        after.put("currency", receipt.currency());
        after.put("order_reference", receipt.merchantReference());
        if (receipt.orderTrackingId() != null) {
            after.put("order_tracking_id", receipt.orderTrackingId());
        }
        if (receipt.confirmationCode() != null) {
            after.put("confirmation_code", receipt.confirmationCode());
        }
        after.put("approval_id", e.approvalId() == null ? null : e.approvalId().toString());
        after.put("requested_by", e.makerId().toString());
        after.put("decided_by", e.checkerId().toString());
        return after;
    }

    /** What of the amount the subject cannot take today and so becomes its credit balance (0 for a purpose without a payoff). */
    long currentExcess(PurposeHandler handler, UUID subjectId, long amountMinor) {
        java.util.OptionalLong payoff = handler.payoffMinor(subjectId);
        return payoff.isPresent() ? Math.max(0, amountMinor - payoff.getAsLong()) : 0;
    }

    /**
     * The checker's sentence for a pending allocation, worked out now from the current payoff (ADR-035 decision 51); empty when
     * the allocation no longer creates a credit balance.
     */
    java.util.Optional<String> currentCreditNotice(Map<String, Object> p) {
        Object purpose = p.get(PURPOSE);
        Object subject = p.get(SUBJECT_ID);
        Object amount = p.get(AMOUNT);
        Object currency = p.get(CURRENCY);
        if (purpose == null || subject == null || !(amount instanceof Number n) || currency == null) {
            return java.util.Optional.empty();
        }
        return registry.find(String.valueOf(purpose)).flatMap(handler -> {
            UUID subjectId = UUID.fromString(String.valueOf(subject));
            java.util.OptionalLong payoff = handler.payoffMinor(subjectId);
            if (payoff.isEmpty() || n.longValue() <= payoff.getAsLong()) {
                return java.util.Optional.empty();
            }
            String label = p.get(ReceiptService.CREDIT_SUBJECT_LABEL) instanceof String l
                    ? l
                    : handler.label(subjectId).orElse("the loan");
            return java.util.Optional.of(ReceiptService.creditNotice(
                    String.valueOf(currency), n.longValue() - payoff.getAsLong(), payoff.getAsLong(), label));
        });
    }
}
