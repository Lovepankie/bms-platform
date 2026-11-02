package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.kernel.BusinessClock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hands a reversal over to a person (spec 3.16): the order stays {@code reversed_pending_review}, which is what it is, and carries
 * a closed word that says why nobody is looking at it any more, with no weekly look scheduled. It is how a rejection, three
 * requests that still stand and a payment completed again with nothing of ours to reverse are recorded, so "waiting for a
 * checker" stays true of the orders that are. A person starts it again with {@code POST /payments/intents/{id}/reversal-request}.
 */
@Component
class ReversalHandOver {

    private static final Logger log = LoggerFactory.getLogger(ReversalHandOver.class);

    static final String UNRESOLVED = "reversal_unresolved";
    static final String REJECTED = "reversal_rejected";
    static final String COMPLETED_AGAIN = "completed_after_reversal";
    static final Set<String> CODES = Set.of(UNRESOLVED, REJECTED, COMPLETED_AGAIN);

    private final IntentStore intents;
    private final IntentAudit audit;
    private final PaymentCounters counters;
    private final BusinessClock clock;

    ReversalHandOver(IntentStore intents, IntentAudit audit, PaymentCounters counters, BusinessClock clock) {
        this.intents = intents;
        this.audit = audit;
        this.counters = counters;
        this.clock = clock;
    }

    /** True when the order is {@code reversed_pending_review} and already handed over. */
    static boolean handedOver(Intent intent) {
        return "reversed_pending_review".equals(intent.status())
                && intent.refusalCode() != null
                && CODES.contains(intent.refusalCode());
    }

    /** Records the hand-over once; returns whether the order moved. */
    boolean handOver(Intent intent, String code) {
        Map<String, Object> cols = new HashMap<>();
        cols.put("refusal_code", code);
        cols.put("next_check_at", null);
        boolean moved = intents.update(intent.id(), "reversed_pending_review", null, clock.now(), cols);
        if (moved) {
            audit.system(intent, "handed_over", "reversed_pending_review", Map.of("refusal_code", code));
            counters.count(code);
            log.warn("payment intent {} reversal handed to a person: {}", intent.id(), code);
        }
        return moved;
    }
}
