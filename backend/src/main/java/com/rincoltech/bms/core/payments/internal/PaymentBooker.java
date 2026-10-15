package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.PurposeHandler;
import com.rincoltech.bms.core.payments.PurposeHandler.Booking;
import com.rincoltech.bms.core.payments.PurposeHandler.PurposeRefusal;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Books a payment that the checker verified and stored as {@code paid} (ADR-035 decision 6 and 14, spec 3.4). One
 * transaction: lock the intent row; if it is no longer {@code paid} stop (the duplicate); run the registered purpose
 * handler inside a savepoint; record the transaction it created and end the intent {@code booked}. So a replayed
 * notification, a poll that finds the same completed order and a second worker all end in the same single booking: the
 * row lock serialises them and the status check makes the later ones a no-op.
 *
 * <p>A handler refusal never strands the money. {@code before_last_repayment} is retried once with the value date moved
 * to today. Any other business refusal rolls the savepoint back and ends the intent {@code needs_attention} with the
 * refusal's code, for a person to look at (the unallocated receipt posting that makes it {@code booked_unallocated}
 * arrives with slice #216, which owns the gateway clearing account's postings). An unexpected exception rolls the whole
 * booking back and counts a technical failure in a transaction of its own; the poller retries with a growing wait, and
 * the fifth failure in a row ends the intent {@code needs_attention}, so nothing completed is retried for ever.
 */
@Component
class PaymentBooker {

    private static final Logger log = LoggerFactory.getLogger(PaymentBooker.class);

    static final int MAX_TECHNICAL_FAILURES = 5;

    /** What a call to {@link #book} did. */
    enum Outcome {
        BOOKED,
        REFUSED,
        RETRY_LATER,
        NEEDS_ATTENTION,
        NOTHING_TO_DO
    }

    private final IntentStore store;
    private final IntentAudit audit;
    private final PurposeRegistry registry;
    private final BusinessClock clock;
    private final CurrentTenant tenant;
    private final PaymentCounters counters;
    private final TransactionTemplate tx;
    private final TransactionTemplate savepoint;

    PaymentBooker(
            IntentStore store,
            IntentAudit audit,
            PurposeRegistry registry,
            BusinessClock clock,
            CurrentTenant tenant,
            PaymentCounters counters,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.audit = audit;
        this.registry = registry;
        this.clock = clock;
        this.tenant = tenant;
        this.counters = counters;
        this.tx = new TransactionTemplate(transactions);
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    Outcome book(UUID intentId) {
        OrderCreator.requireNoTransaction();
        Outcome outcome;
        try {
            outcome = tx.execute(status -> bookLocked(intentId));
        } catch (RuntimeException e) {
            // The booking rolled back as a whole (the handler broke, or the database did). Counted on its own.
            return technicalFailure(intentId, e);
        }
        if (outcome == Outcome.REFUSED) {
            counters.count("booking_refused");
        }
        return outcome == null ? Outcome.NOTHING_TO_DO : outcome;
    }

    private Outcome bookLocked(UUID intentId) {
        Intent intent = store.lock(intentId).orElse(null);
        if (intent == null || !"paid".equals(intent.status())) {
            return Outcome.NOTHING_TO_DO;
        }
        Optional<PurposeHandler> handler = registry.find(intent.purpose());
        if (handler.isEmpty()) {
            throw new IllegalStateException("no payment purpose handler is registered for " + intent.purpose());
        }
        Instant now = clock.now();
        ZoneId zone = tenant.profile().timezone();
        boolean late = intent.expiresAt() != null && intent.paidAt().isAfter(intent.expiresAt());
        LocalDate paidOn = LocalDate.ofInstant(intent.paidAt(), zone);
        UUID transactionId;
        String note = null;
        try {
            transactionId = attempt(handler.get(), intent, paidOn, late);
        } catch (PurposeRefusal first) {
            if (!PurposeHandler.BEFORE_LAST_REPAYMENT.equals(first.code())) {
                return refused(intent, now, first);
            }
            // A repayment cannot be dated before the loan's last one: the money came today as far as the books go.
            try {
                transactionId = attempt(handler.get(), intent, LocalDate.ofInstant(now, zone), late);
                note = "dated_today";
            } catch (PurposeRefusal second) {
                return refused(intent, now, second);
            }
        }
        Map<String, Object> cols = new HashMap<>();
        cols.put("booked_subject_txn_id", transactionId);
        cols.put("booked_at", now);
        cols.put("next_check_at", null);
        cols.put("technical_failures", 0);
        if (!store.update(intent.id(), "paid", "booked", now, cols)) {
            throw new IllegalStateException("the intent changed under its own row lock");
        }
        Map<String, Object> extra = new HashMap<>();
        extra.put("booked_subject_txn_id", transactionId.toString());
        extra.put("paid_amount_minor", intent.paidAmountMinor());
        if (note != null) {
            extra.put("note", note);
        }
        if (late) {
            extra.put("late", true);
        }
        audit.system(intent, "booked", "booked", extra);
        log.info("payment intent {} paid -> booked", intent.id());
        return Outcome.BOOKED;
    }

    private UUID attempt(PurposeHandler handler, Intent intent, LocalDate valueDate, boolean late) {
        Booking booking = new Booking(
                intent.id(),
                intent.purpose(),
                intent.subjectType(),
                intent.subjectId(),
                intent.branchId(),
                intent.paidAmountMinor(),
                intent.paidCurrency(),
                intent.confirmationCode(),
                intent.providerReference(),
                intent.reportedMethod(),
                valueDate,
                intent.initiatedBy(),
                late);
        // A savepoint: a refusal undoes what the handler wrote and leaves this transaction, and the intent row, intact.
        return savepoint.execute(status -> {
            UUID id = handler.book(booking);
            if (id == null) {
                throw new IllegalStateException("a payment purpose handler returned no transaction id");
            }
            return id;
        });
    }

    private Outcome refused(Intent intent, Instant now, PurposeRefusal refusal) {
        Map<String, Object> cols = new HashMap<>();
        cols.put("refusal_code", refusal.code());
        cols.put("next_check_at", null);
        if (!store.update(intent.id(), "paid", "needs_attention", now, cols)) {
            throw new IllegalStateException("the intent changed under its own row lock");
        }
        audit.system(intent, "booking_refused", "needs_attention", Map.of("refusal_code", refusal.code()));
        log.warn("payment intent {} not booked, the handler refused: {}", intent.id(), refusal.code());
        return Outcome.REFUSED;
    }

    private Outcome technicalFailure(UUID intentId, RuntimeException cause) {
        // Only the type is logged: a database or handler message can quote a value.
        log.warn(
                "payment intent {} booking failed technically: {}",
                intentId,
                cause.getClass().getSimpleName());
        counters.count("booking_failed");
        return tx.execute(status -> {
            Intent intent = store.lock(intentId).orElse(null);
            if (intent == null || !"paid".equals(intent.status())) {
                return Outcome.NOTHING_TO_DO;
            }
            Instant now = clock.now();
            int failures = intent.technicalFailures() + 1;
            Map<String, Object> cols = new HashMap<>();
            cols.put("technical_failures", failures);
            if (failures >= MAX_TECHNICAL_FAILURES) {
                cols.put("refusal_code", "technical_failures");
                cols.put("next_check_at", null);
                store.update(intentId, "paid", "needs_attention", now, cols);
                audit.system(
                        intent, "needs_attention", "needs_attention", Map.of("refusal_code", "technical_failures"));
                return Outcome.NEEDS_ATTENTION;
            }
            cols.put("next_check_at", CheckSchedule.nextBooking(now, failures));
            store.update(intentId, "paid", null, now, cols);
            return Outcome.RETRY_LATER;
        });
    }
}
