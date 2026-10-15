package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The audit row of every transition of an intent (FR-AUD-03, ADR-035): the old and the new status, the amounts, the order
 * tracking id and a closed reason, in the same transaction as the change. It never carries the payer's phone number, a
 * provider body or a credential; the audit writer masks the phone key anyway. The actor is the signed-in user for a
 * request, and for the checker and the poller the intent's initiator under the kind {@code system} (spec 3.4: the audit
 * actor is the intent's initiator).
 */
@Component
class IntentAudit {

    private final AuditLog audit;

    IntentAudit(AuditLog audit) {
        this.audit = audit;
    }

    /** A transition made by the checker, the creator or the booker (no principal). */
    void system(Intent before, String verb, String to, Map<String, Object> extra) {
        audit.record(entry(before, verb, to, extra), before.initiatedBy(), "system");
    }

    /** A transition made on behalf of the signed-in user. */
    void user(Intent before, String verb, String to, Map<String, Object> extra) {
        audit.record(entry(before, verb, to, extra));
    }

    private static AuditLog.Entry entry(Intent before, String verb, String to, Map<String, Object> extra) {
        Map<String, Object> was = new LinkedHashMap<>();
        was.put("status", before.status());
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("status", to);
        now.put("merchant_reference", before.merchantReference());
        now.put("amount_minor", before.amountMinor());
        now.put("currency", before.currency());
        if (before.providerReference() != null) {
            now.put("order_tracking_id", before.providerReference());
        }
        now.putAll(extra);
        return new AuditLog.Entry(
                "core.payments.intent." + verb, "payment_intent", before.id(), before.branchId(), was, now);
    }

    static Map<String, Object> reason(String key, Object value) {
        return Map.of(key, value);
    }

    /** The created entry: written with the row, by the signed-in user. */
    void created(Intent created, UUID subjectId) {
        audit.record(AuditLog.Entry.created(
                "core.payments.intent.created",
                "payment_intent",
                created.id(),
                created.branchId(),
                Map.of(
                        "status", "created",
                        "purpose", created.purpose(),
                        "subject_type", created.subjectType(),
                        "subject_id", subjectId.toString(),
                        "amount_minor", created.amountMinor(),
                        "currency", created.currency(),
                        "merchant_reference", created.merchantReference())));
    }
}
