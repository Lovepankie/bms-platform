package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.GatewayException;
import com.rincoltech.bms.core.payments.PaymentGateway;
import com.rincoltech.bms.core.payments.PaymentGateway.Scope;
import com.rincoltech.bms.core.payments.PaymentGateway.StatusResult;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only thing that moves an intent on the strength of the provider (ADR-035 decision 4 to 8, spec 3.2, 3.6 to 3.8).
 * A notification, the poller and the creation job all end here: ask the provider for the status of the order with our
 * own credentials, with no transaction open, then apply the answer under the intent's row lock, deciding on the intent's
 * state as it is <em>then</em>, with conditional writes, so that of two checks racing for one transition exactly one
 * makes it and the other finds it done. Nothing in a request, in a notification or in a stored callback is evidence of
 * payment; {@code paid} is reachable only from a COMPLETED answer whose reference and currency are the intent's.
 *
 * <p>The answer, by the intent's state:
 *
 * <ul>
 *   <li>PENDING (an unpaid order reads status code 0 with {@code payment_details_not_found}): stay; the first time, the
 *       amount and currency must be the intent's own (a repeated order creation may have handed back an order made with
 *       another body), else the intent fails with {@code order_mismatch} and the order is cancelled; 48 hours after
 *       creation an unpaid order is cancelled and the intent ends {@code cancelled};
 *   <li>COMPLETED: unpaid states become {@code paid} (late is normal: the money has moved), then the booker books;
 *       a reference or currency that is not the intent's, or an amount that is not a positive whole number, ends
 *       {@code needs_attention} and books nothing; an intent already paid or booked is a duplicate;
 *   <li>FAILED: pending or expired becomes {@code failed}; REVERSED: a paid intent becomes {@code reversed}, a booked
 *       one {@code reversed_pending_review} (never reversed automatically, spec 3.8);
 *   <li>ANOMALY (an unknown order, a credential error, a fraction of a shilling, an unknown code): counted, nothing moves.
 * </ul>
 */
@Component
class StatusChecker {

    private static final Logger log = LoggerFactory.getLogger(StatusChecker.class);

    /** What the tick budgets for creating one order (the job path; a request uses 15 seconds). */
    static final Duration JOB_CREATE_BUDGET = Duration.ofSeconds(45);

    private static final Set<String> PERIODIC = Set.of("created", "pending", "expired", "paid");

    /** What applying an answer decided, to be done after the transaction has committed. */
    private record Applied(boolean bookNext, String cancelReference, Scope scope, List<String> counts) {
        static Applied none() {
            return new Applied(false, null, null, List.of());
        }
    }

    private final IntentStore store;
    private final IntentAudit audit;
    private final PaymentGateway gateway;
    private final OrderCreator creator;
    private final PaymentBooker booker;
    private final PaymentCounters counters;
    private final BusinessClock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate read;

    StatusChecker(
            IntentStore store,
            IntentAudit audit,
            PaymentGateway gateway,
            OrderCreator creator,
            PaymentBooker booker,
            PaymentCounters counters,
            BusinessClock clock,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.audit = audit;
        this.gateway = gateway;
        this.creator = creator;
        this.booker = booker;
        this.counters = counters;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
    }

    /**
     * Does the work an intent is due for: makes its order, asks the provider about it, or books what is paid. Outside any
     * transaction, with the tenant bound (by the request or by the poller).
     */
    void process(UUID intentId) {
        OrderCreator.requireNoTransaction();
        Intent intent = read.execute(status -> store.find(intentId).orElse(null));
        if (intent == null) {
            return;
        }
        switch (intent.status()) {
            case "created" -> creator.create(intentId, JOB_CREATE_BUDGET);
            case "paid" -> booker.book(intentId);
            case "pending", "expired", "failed", "cancelled", "booked", "booked_unallocated" -> verify(intent);
            default ->
                tx.executeWithoutResult(status -> store.update(
                        intentId,
                        intent.status(),
                        null,
                        clock.now(),
                        nullColumns("next_check_at", "check_requested_at")));
        }
    }

    private void verify(Intent first) {
        Instant started = clock.now();
        Intent intent = first;
        if (intent.providerReference() == null) {
            tx.executeWithoutResult(status -> {
                store.update(
                        first.id(), first.status(), null, started, nullColumns("next_check_at", "check_requested_at"));
                store.finishCallbacks(first.id(), started, started, "unchanged", "{\"status\":\"no_order\"}");
            });
            return;
        }
        if ("pending".equals(intent.status()) && !started.isBefore(intent.expiresAt())) {
            // The payer is no longer offered the order, whether or not the provider can be reached now.
            intent = expire(intent, started);
        }
        Scope scope = Scope.ofKey(intent.scope());
        StatusResult result;
        try {
            result = gateway.queryStatus(scope, intent.providerReference());
        } catch (GatewayException e) {
            unreachable(intent, e, started);
            return;
        }
        UUID id = intent.id();
        Applied applied = tx.execute(status -> apply(id, result, started));
        if (applied.cancelReference() != null) {
            try {
                gateway.cancelOrder(applied.scope(), applied.cancelReference());
            } catch (GatewayException e) {
                log.warn("payment order not cancelled at the provider: kind={}", e.kind());
            }
        }
        applied.counts().forEach(counters::count);
        if (applied.bookNext()) {
            booker.book(id);
        }
    }

    private Intent expire(Intent intent, Instant now) {
        return tx.execute(status -> {
            Intent locked = store.lock(intent.id()).orElse(intent);
            if (store.update(locked.id(), "pending", "expired", now, new HashMap<>())) {
                audit.system(locked, "expired", "expired", Map.of());
                log.info("payment intent {} pending -> expired", locked.id());
            }
            return store.find(locked.id()).orElse(locked);
        });
    }

    /** The provider could not be asked. The wait grows; at the end of the 48 hours an unpaid intent still ends. */
    private void unreachable(Intent intent, GatewayException e, Instant started) {
        log.warn("payment status check could not reach the provider: intent={} kind={}", intent.id(), e.kind());
        String json =
                "{\"status\":\"unreachable\",\"kind\":\"" + e.kind().name().toLowerCase(java.util.Locale.ROOT) + "\"}";
        Boolean cancel = tx.execute(status -> {
            Instant now = clock.now();
            Intent i = store.lock(intent.id()).orElse(intent);
            int failures = i.checkFailures() + 1;
            boolean unpaid = Set.of("pending", "expired").contains(i.status());
            if (unpaid && CheckSchedule.isFinal(now, i.createdAt())) {
                closeUnpaid(i, now);
                store.finishCallbacks(i.id(), started, now, "rejected", json);
                return true;
            }
            Map<String, Object> cols = new HashMap<>();
            cols.put("last_checked_at", now);
            cols.put("check_failures", failures);
            cols.put(
                    "next_check_at",
                    PERIODIC.contains(i.status()) ? CheckSchedule.next(now, i.createdAt(), failures) : null);
            store.update(i.id(), i.status(), null, now, cols);
            store.finishCallbacks(i.id(), started, now, "rejected", json);
            return false;
        });
        if (Boolean.TRUE.equals(cancel)) {
            try {
                gateway.cancelOrder(Scope.ofKey(intent.scope()), intent.providerReference());
            } catch (GatewayException ignored) {
                // Best effort, and the provider was just unreachable.
            }
        }
    }

    private void closeUnpaid(Intent i, Instant now) {
        Map<String, Object> cols = new HashMap<>();
        cols.put("failure_reason", "unpaid_after_48h");
        cols.put("next_check_at", null);
        cols.put("check_requested_at", null);
        if (store.update(i.id(), i.status(), "cancelled", now, cols)) {
            audit.system(i, "cancelled", "cancelled", Map.of("reason", "unpaid_after_48h"));
            log.info("payment intent {} {} -> cancelled, unpaid after 48 hours", i.id(), i.status());
        }
    }

    // ---- applying the answer ---------------------------------------------------------------

    private Applied apply(UUID id, StatusResult r, Instant started) {
        Instant now = clock.now();
        Intent i = store.lock(id).orElse(null);
        if (i == null) {
            return Applied.none();
        }
        String s = i.status();
        String json = resultJson(r);
        switch (r.status()) {
            case PENDING:
                return applyPending(i, r, now, started, json);
            case COMPLETED:
                return applyCompleted(i, r, now, started, json);
            case FAILED:
                if (Set.of("pending", "expired").contains(s)) {
                    Map<String, Object> cols = settled(i, now, 0, null);
                    cols.put("failure_reason", "provider_failed");
                    store.update(i.id(), s, "failed", now, cols);
                    audit.system(i, "failed", "failed", Map.of("reason", "provider_failed"));
                    store.finishCallbacks(i.id(), started, now, "failed", json);
                    log.info("payment intent {} {} -> failed", i.id(), s);
                    return Applied.none();
                }
                return Set.of("failed", "cancelled").contains(s)
                        ? unchanged(i, now, started, json, "unchanged")
                        : mismatch(i, now, started, json, "failed_after_completed", false);
            case REVERSED:
                return applyReversed(i, now, started, json);
            default:
                return mismatch(i, now, started, json, r.anomaly() == null ? "anomaly" : r.anomaly(), true);
        }
    }

    private Applied applyPending(Intent i, StatusResult r, Instant now, Instant started, String json) {
        String s = i.status();
        if (!Set.of("pending", "expired").contains(s)) {
            return Set.of("failed", "cancelled").contains(s)
                    ? unchanged(i, now, started, json, "unchanged")
                    : mismatch(i, now, started, json, "pending_after_completed", false);
        }
        Map<String, Object> extra = new HashMap<>();
        if (i.orderVerifiedAt() == null) {
            boolean same = r.amountMinor() != null
                    && r.amountMinor() == i.amountMinor()
                    && i.currency().equals(r.currency())
                    && i.merchantReference().equals(r.merchantReference());
            if (!same) {
                // The repeated creation may have handed back an order made with another body: it is not ours to collect
                // on.
                Map<String, Object> cols = settled(i, now, 0, null);
                cols.put("failure_reason", "order_mismatch");
                store.update(i.id(), s, "failed", now, cols);
                audit.system(i, "failed", "failed", Map.of("reason", "order_mismatch"));
                store.finishCallbacks(i.id(), started, now, "status_mismatch", json);
                log.warn("payment intent {} failed: the order is not the one asked for", i.id());
                return new Applied(false, i.providerReference(), Scope.ofKey(i.scope()), List.of("order_mismatch"));
            }
            extra.put("order_verified_at", now);
        }
        if (CheckSchedule.isFinal(now, i.createdAt())) {
            closeUnpaid(i, now);
            store.finishCallbacks(i.id(), started, now, "unchanged", json);
            return new Applied(false, i.providerReference(), Scope.ofKey(i.scope()), List.of());
        }
        Map<String, Object> cols = settled(i, now, 0, started);
        cols.putAll(extra);
        store.update(i.id(), s, null, now, cols);
        store.finishCallbacks(i.id(), started, now, "unchanged", json);
        return Applied.none();
    }

    private Applied applyCompleted(Intent i, StatusResult r, Instant now, Instant started, String json) {
        String s = i.status();
        if (Set.of("paid", "booked", "booked_unallocated", "reversed_pending_review", "reversed", "needs_attention")
                .contains(s)) {
            // Already counted: a replay or a second check that found the same completed order.
            store.finishCallbacks(i.id(), started, now, "duplicate", json);
            if ("paid".equals(s)) {
                return new Applied(true, null, null, List.of());
            }
            store.update(i.id(), s, null, now, settled(i, now, 0, started));
            return Applied.none();
        }
        String bad = !i.merchantReference().equals(r.merchantReference())
                ? "reference_mismatch"
                : !i.currency().equals(r.currency())
                        ? "currency_mismatch"
                        : r.amountMinor() == null || r.amountMinor() <= 0 ? "amount_invalid" : null;
        if (bad != null) {
            // Money moved and the books cannot take it on this evidence: nothing is booked, a person looks.
            Map<String, Object> cols = settled(i, now, 0, null);
            cols.put("refusal_code", bad);
            store.update(i.id(), s, "needs_attention", now, cols);
            audit.system(i, "mismatch", "needs_attention", Map.of("refusal_code", bad));
            store.finishCallbacks(i.id(), started, now, "status_mismatch", json);
            log.warn("payment intent {} needs attention: {}", i.id(), bad);
            return new Applied(false, null, null, List.of(bad));
        }
        boolean late = "expired".equals(s) || !now.isBefore(i.expiresAt());
        Map<String, Object> cols = new HashMap<>();
        cols.put("paid_amount_minor", r.amountMinor());
        cols.put("paid_currency", r.currency());
        cols.put(
                "confirmation_code",
                r.confirmationCode() == null || r.confirmationCode().isBlank()
                        ? i.providerReference()
                        : r.confirmationCode());
        cols.put(
                "reported_method",
                r.paymentMethod() == null || r.paymentMethod().isBlank() ? null : r.paymentMethod());
        cols.put("paid_at", now);
        cols.put("verified_at", now);
        cols.put("failure_reason", null);
        cols.put("last_checked_at", now);
        cols.put("check_failures", 0);
        cols.put("check_requested_at", null);
        // Due at once, so a worker that dies before it books leaves the booking to the poller.
        cols.put("next_check_at", now);
        store.update(i.id(), s, "paid", now, cols);
        Map<String, Object> extra = new HashMap<>();
        extra.put("paid_amount_minor", r.amountMinor());
        extra.put("confirmation_code", cols.get("confirmation_code"));
        if (r.amountMinor() < i.amountMinor()) {
            extra.put("flag", "paid_less");
        } else if (r.amountMinor() > i.amountMinor()) {
            extra.put("flag", "paid_more");
        }
        if (late) {
            extra.put("late", true);
        }
        audit.system(i, "paid", "paid", extra);
        store.finishCallbacks(i.id(), started, now, "paid", json);
        log.info("payment intent {} {} -> paid", i.id(), s);
        return new Applied(true, null, null, List.of());
    }

    private Applied applyReversed(Intent i, Instant now, Instant started, String json) {
        String s = i.status();
        if (("paid".equals(s) || "needs_attention".equals(s)) && i.verifiedAt() != null) {
            Map<String, Object> cols = settled(i, now, 0, null);
            store.update(i.id(), s, "reversed", now, cols);
            audit.system(i, "reversed", "reversed", Map.of());
            store.finishCallbacks(i.id(), started, now, "reversed", json);
            return new Applied(false, null, null, List.of("reversal"));
        }
        if ("booked".equals(s) || "booked_unallocated".equals(s)) {
            // Never reversed automatically: a checker request follows in slice #224 (ADR-035 decision 16).
            Map<String, Object> cols = settled(i, now, 0, null);
            store.update(i.id(), s, "reversed_pending_review", now, cols);
            audit.system(i, "reversal_reported", "reversed_pending_review", Map.of());
            store.finishCallbacks(i.id(), started, now, "reversed", json);
            log.warn("payment intent {} reported reversed by the provider after booking", i.id());
            return new Applied(false, null, null, List.of("reversal"));
        }
        if ("reversed_pending_review".equals(s) || "reversed".equals(s)) {
            return unchanged(i, now, started, json, "duplicate");
        }
        return mismatch(i, now, started, json, "reversed_unpaid", false);
    }

    private Applied unchanged(Intent i, Instant now, Instant started, String json, String outcome) {
        store.update(i.id(), i.status(), null, now, settled(i, now, 0, started));
        store.finishCallbacks(i.id(), started, now, outcome, json);
        return Applied.none();
    }

    private Applied mismatch(Intent i, Instant now, Instant started, String json, String word, boolean anomaly) {
        int failures = i.checkFailures() + 1;
        log.warn("payment status answer disagrees with intent {}: {}", i.id(), word);
        if (Set.of("pending", "expired").contains(i.status()) && CheckSchedule.isFinal(now, i.createdAt())) {
            // The 48 hours are over and the answers never made sense: a person looks, the row is not left with no
            // check.
            Map<String, Object> cols = settled(i, now, failures, null);
            cols.put("refusal_code", "anomaly_after_48h");
            cols.put("next_check_at", null);
            store.update(i.id(), i.status(), "needs_attention", now, cols);
            audit.system(i, "needs_attention", "needs_attention", Map.of("refusal_code", "anomaly_after_48h"));
            store.finishCallbacks(i.id(), started, now, "status_mismatch", json);
            return new Applied(false, null, null, List.of(anomaly ? "anomaly" : "status_mismatch"));
        }
        store.update(i.id(), i.status(), null, now, settled(i, now, failures, started));
        store.finishCallbacks(i.id(), started, now, "status_mismatch", json);
        return new Applied(false, null, null, List.of(anomaly ? "anomaly" : "status_mismatch"));
    }

    /**
     * The columns every checked intent gets: when it was checked, the failures in a row, and the next check, which is
     * the schedule for an intent still waiting on the payer, or soon again when a notification arrived while the provider
     * was being asked, or none. {@code started} is null where the intent has left the waiting states.
     */
    private Map<String, Object> settled(Intent i, Instant now, int failures, Instant started) {
        Map<String, Object> cols = new HashMap<>();
        cols.put("last_checked_at", now);
        cols.put("check_failures", failures);
        boolean again = started != null
                && i.checkRequestedAt() != null
                && i.checkRequestedAt().isAfter(started);
        // A paid row's next check is the booking, due now: a stale answer must never clear it.
        Instant next = "paid".equals(i.status())
                ? now
                : again
                        ? now.plus(CheckSchedule.THROTTLE)
                        : started != null && Set.of("pending", "expired").contains(i.status())
                                ? CheckSchedule.next(now, i.createdAt(), failures)
                                : null;
        cols.put("next_check_at", next);
        if (!again) {
            cols.put("check_requested_at", null);
        }
        return cols;
    }

    private static Map<String, Object> nullColumns(String... names) {
        Map<String, Object> cols = new HashMap<>();
        for (String n : names) {
            cols.put(n, null);
        }
        return cols;
    }

    /** The closed words of the verified answer: a status and, for an anomaly, which; never a provider text. */
    static String resultJson(StatusResult r) {
        String status = r.status().name().toLowerCase(java.util.Locale.ROOT);
        return r.anomaly() == null
                ? "{\"status\":\"" + status + "\"}"
                : "{\"status\":\"" + status + "\",\"word\":\"" + r.anomaly() + "\"}";
    }
}
