package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.GatewayException;
import com.rincoltech.bms.core.payments.PaymentGateway;
import com.rincoltech.bms.core.payments.PaymentGateway.Account;
import com.rincoltech.bms.core.payments.PaymentGateway.OrderRequest;
import com.rincoltech.bms.core.payments.PaymentGateway.OrderResult;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the hosted order for an intent that is {@code created} (spec 3.5, ADR-035 decision 2 and its amendment). One
 * creator at a time per intent: the claim takes a lease with {@code FOR UPDATE SKIP LOCKED}, so two workers cannot make
 * the same order. The provider call is made with no transaction open (spec principle 4).
 *
 * <p>The provider answers a repeat of a used reference with the original order, so a lost answer is recovered by
 * repeating the same reference and body, up to three times with a growing pause. An answer is accepted only if it
 * echoes the intent's own reference, and the first status query must later report the intent's amount and currency
 * (the checker). If every try ends without an answer the intent ends {@code failed} with {@code creation_unknown}: the
 * payer was never given a link, so no payment can be missed, and a new intent with a new reference follows. A refusal by
 * the provider ends it {@code failed} at once with the provider's code in lower case. If the breaker is open or the
 * budget is spent, nothing is decided: the intent stays {@code created} and the next worker repeats the same call.
 */
@Component
class OrderCreator {

    private static final Logger log = LoggerFactory.getLogger(OrderCreator.class);

    enum Outcome {
        /** The order exists and the intent is pending. */
        CREATED,
        /** The intent ended failed. */
        FAILED,
        /** Nothing decided; the intent is still created and will be tried again. */
        DEFERRED,
        /** Another worker holds it, or it is no longer created. */
        NOT_CLAIMED
    }

    static final int TRIES = 3;
    /** Claims made on one intent before it is given up as {@code creation_unknown}. */
    static final int MAX_ROUNDS = 5;

    static final Duration FIRST_PAUSE = Duration.ofMillis(500);
    static final Duration RETRY_AFTER_DEFER = Duration.ofSeconds(30);

    private final IntentStore store;
    private final IntentAudit audit;
    private final PaymentGateway gateway;
    private final BusinessClock clock;
    private final TransactionTemplate tx;
    private final Pauser pauser;
    private final CurrentTenant tenant;

    OrderCreator(
            IntentStore store,
            IntentAudit audit,
            PaymentGateway gateway,
            BusinessClock clock,
            PlatformTransactionManager transactions,
            Pauser pauser,
            CurrentTenant tenant) {
        this.store = store;
        this.audit = audit;
        this.gateway = gateway;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.pauser = pauser;
        this.tenant = tenant;
    }

    static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("a provider call is never made inside a database transaction");
        }
    }

    /** @param budget the longest this call may keep trying (15 seconds inside a request, longer in the job) */
    Outcome create(UUID intentId, Duration budget) {
        requireNoTransaction();
        long deadline = System.nanoTime() + budget.toNanos();
        Intent intent = tx.execute(status -> {
            Instant now = clock.now();
            if (!store.claimCreation(intentId, now, now.plus(CheckSchedule.LEASE))) {
                return null;
            }
            return store.find(intentId).orElse(null);
        });
        if (intent == null) {
            return Outcome.NOT_CLAIMED;
        }
        Account scope = Account.forIntent(intent.scope(), intent.tenantId(), intent.purpose(), intent.merchantId());
        if (intent.creationAttempts() >= MAX_ROUNDS) {
            return fail(intent, "creation_unknown");
        }
        if (!clock.now().isBefore(intent.expiresAt())) {
            return fail(intent, "order_not_created");
        }
        if (!gateway.available(scope)) {
            return defer(intent);
        }
        // Counted BEFORE the call, so a process that dies in the middle of it still used up a round and creation cannot
        // be
        // retried for ever; given back below when no call was made (the breaker was open).
        tx.executeWithoutResult(status -> store.countCreationAttempt(intent.id(), 1));
        OrderRequest request = new OrderRequest(
                intent.id(),
                intent.merchantReference(),
                intent.amountMinor(),
                intent.currency(),
                intent.payerPhone(),
                "Payment to " + tenant.profile().name());

        for (int attempt = 1; attempt <= TRIES; attempt++) {
            try {
                return accept(intent, gateway.createOrder(scope, request), scope);
            } catch (GatewayException e) {
                switch (e.kind()) {
                    case UNAVAILABLE:
                        if (attempt == 1) {
                            // No call was made at all: the round is given back.
                            tx.executeWithoutResult(status -> store.countCreationAttempt(intent.id(), -1));
                        }
                        return defer(intent);
                    case REFUSED:
                        return fail(intent, "order_refused:" + word(e.code()));
                    case AUTH:
                        return fail(intent, "provider_auth");
                    default:
                        // A lost answer: the order may exist. The same reference and body are repeated.
                        if (attempt == TRIES) {
                            return fail(intent, "creation_unknown");
                        }
                        if (System.nanoTime() >= deadline) {
                            return defer(intent);
                        }
                        try {
                            pauser.pause(FIRST_PAUSE.multipliedBy(1L << (attempt - 1)));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return defer(intent);
                        }
                }
            }
        }
        return fail(intent, "creation_unknown");
    }

    private Outcome accept(Intent intent, OrderResult result, Account scope) {
        if (!intent.merchantReference().equals(result.merchantReference())) {
            cancelQuietly(scope, result.providerReference());
            return fail(intent, "order_mismatch");
        }
        try {
            Boolean moved = tx.execute(status -> {
                Instant now = clock.now();
                Map<String, Object> cols = new HashMap<>();
                cols.put("provider_reference", result.providerReference());
                cols.put("redirect_url", result.redirectUrl());
                cols.put("creation_lease_until", null);
                cols.put("next_check_at", CheckSchedule.next(now, intent.createdAt(), 0));
                boolean ok = store.update(intent.id(), "created", "pending", now, cols);
                if (ok) {
                    audit.system(
                            intent, "order_created", "pending", Map.of("creation_attempts", intent.creationAttempts()));
                }
                return ok;
            });
            if (Boolean.TRUE.equals(moved)) {
                log.info("payment intent {} created -> pending", intent.id());
                return Outcome.CREATED;
            }
        } catch (DataIntegrityViolationException e) {
            // The provider handed back an order another intent already holds: never two intents on one order.
            cancelQuietly(scope, result.providerReference());
            return fail(intent, "order_mismatch");
        }
        // Cancelled or failed while the order was being made: the order has no intent to belong to.
        cancelQuietly(scope, result.providerReference());
        return Outcome.NOT_CLAIMED;
    }

    private Outcome defer(Intent intent) {
        tx.executeWithoutResult(status -> {
            Instant now = clock.now();
            Map<String, Object> cols = new HashMap<>();
            cols.put("creation_lease_until", null);
            cols.put("next_check_at", now.plus(RETRY_AFTER_DEFER));
            store.update(intent.id(), "created", null, now, cols);
        });
        return Outcome.DEFERRED;
    }

    private Outcome fail(Intent intent, String reason) {
        tx.executeWithoutResult(status -> {
            Instant now = clock.now();
            Map<String, Object> cols = new HashMap<>();
            cols.put("failure_reason", reason);
            cols.put("creation_lease_until", null);
            cols.put("next_check_at", null);
            if (store.update(intent.id(), "created", "failed", now, cols)) {
                audit.system(intent, "failed", "failed", Map.of("reason", reason));
            }
        });
        log.warn("payment intent {} failed: {}", intent.id(), reason);
        return Outcome.FAILED;
    }

    private void cancelQuietly(Account scope, String providerReference) {
        try {
            gateway.cancelOrder(scope, providerReference);
        } catch (GatewayException e) {
            log.warn("could not cancel a stray order: kind={}", e.kind());
        }
    }

    /** A provider code as a lower case word of ours: letters, digits and underscores, short. */
    static String word(String code) {
        String w = code == null ? "unspecified" : code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        w = w.isEmpty() || !Character.isLetter(w.charAt(0)) ? "e" + w : w;
        return w.length() > 40 ? w.substring(0, 40) : w;
    }
}
