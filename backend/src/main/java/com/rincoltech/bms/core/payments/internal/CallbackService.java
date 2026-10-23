package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.payments.PaymentGateway.Scope;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What a notification (an IPN, or the payer coming back to the callback url) does, which is little (ADR-035 decision 4,
 * spec 3.6): it names an order, and the platform decides whether to ask the provider about it. Nothing the request said
 * but two identifiers is read, and nothing here calls the provider, so a flood of notifications is a flood of cheap
 * database writes (bounded by the rate limit in front), never of calls out.
 *
 * <ol>
 *   <li>The tenant and intent are found from the merchant reference by a definer function (the request's host plays no
 *       part, ADR-016). A reference that matches no intent, one whose order tracking id belongs to another order, and
 *       one whose intent belongs to the other credential pair are the same thing: unknown. They are counted and kept in a
 *       bounded platform queue, and the caller answers as for any other notification, with no outbound call.
 *   <li>For a known intent, in its own tenant's transaction: a {@code payment_callbacks} row of identifiers, and the
 *       intent's next check moved to now, or to ten seconds after the last check, whichever is later. A notification that
 *       finds a check already due, or one that ran within ten seconds, is stored as {@code throttled} and changes nothing.
 *       The check itself is the job's (the poller takes due intents every few seconds), so the same code path serves a
 *       notification, a lost notification and a retry, and a replay of one notification three times is three rows and one
 *       check.
 * </ol>
 */
@Component
class CallbackService {

    private static final Logger log = LoggerFactory.getLogger(CallbackService.class);

    enum Result {
        /** The intent will be checked soon because of this notification. */
        QUEUED,
        /** The intent is being, or was just, checked; the notification added nothing. */
        THROTTLED,
        /** It named no order of this credential pair. */
        UNKNOWN,
        /** It named an order whose tenant the operator suspended, or one in a state nobody re-checks. */
        IGNORED
    }

    private record Resolved(UUID tenantId, UUID intentId, String match) {}

    private final JdbcClient jdbc;
    private final TenantJobs tenantJobs;
    private final IntentStore store;
    private final PaymentCounters counters;
    private final BusinessClock clock;

    CallbackService(
            JdbcClient jdbc, TenantJobs tenantJobs, IntentStore store, PaymentCounters counters, BusinessClock clock) {
        this.jdbc = jdbc;
        this.tenantJobs = tenantJobs;
        this.store = store;
        this.counters = counters;
        this.clock = clock;
    }

    /**
     * @param tokenTenant the lender whose notification url this arrived on, or null (the platform's url, the payer's
     *     return): an order of any other tenant is a scope mismatch, never matched
     * @param kind {@code ipn} or {@code return}
     * @param notificationType the provider's word, already checked for shape (for example {@code IPNCHANGE})
     * @param trackingId a GUID, already checked for shape
     * @param reference a merchant reference of this environment, already checked for shape
     */
    Result receive(
            Scope scope, UUID tokenTenant, String kind, String notificationType, String trackingId, String reference) {
        Instant now = clock.now();
        Optional<Resolved> resolved = jdbc.sql("SELECT tenant_id, intent_id, match"
                        + " FROM app_resolve_payment_intent('pesapal', ?, ?, ?, ?::uuid)")
                .params(scope.key(), trackingId.toLowerCase(Locale.ROOT), reference, tokenTenant)
                .query((rs, n) -> new Resolved(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("intent_id", UUID.class),
                        rs.getString("match")))
                .optional();
        if (resolved.isEmpty() || !"ok".equals(resolved.get().match())) {
            if (!"ipn".equals(kind)) {
                // The payer return carries no token and anyone can call it: it never writes to the platform queue or
                // the alert counters.
                return Result.UNKNOWN;
            }
            String reason = resolved.map(Resolved::match).orElse("unknown_reference");
            jdbc.sql("SELECT app_record_unmatched_payment_callback('pesapal', ?, ?, ?, ?, ?)")
                    .params(scope.key(), reason, trackingId.toLowerCase(Locale.ROOT), reference, IntentStore.ts(now))
                    .query((rs, n) -> 0)
                    .list();
            counters.count(reason);
            log.info("payment notification matched no order: reason={}", reason);
            return Result.UNKNOWN;
        }
        Resolved r = resolved.get();
        Result result;
        try {
            // A business whose subscription ended still has its payments recorded (ADR-073); one the operator
            // suspended does not.
            result = tenantJobs.callForTenantTakingPayments(
                    r.tenantId(), () -> note(r.intentId(), kind, notificationType, trackingId, reference, now));
        } catch (TenantJobs.InactiveTenantException e) {
            counters.count("tenant_suspended");
            return Result.IGNORED;
        }
        counters.count("received");
        return result;
    }

    private Result note(UUID intentId, String kind, String type, String trackingId, String reference, Instant now) {
        Intent intent = store.lock(intentId).orElse(null);
        if (intent == null) {
            return Result.UNKNOWN;
        }
        String outcome;
        Result result;
        if (!IntentStore.CHECKABLE.contains(intent.status())) {
            // Needs a person, or reversed: a notification changes nothing about that.
            outcome = "unchanged";
            result = Result.IGNORED;
        } else {
            Instant earliest = intent.lastCheckedAt() == null
                    ? now
                    : max(now, intent.lastCheckedAt().plus(CheckSchedule.THROTTLE));
            boolean alreadyDue =
                    intent.nextCheckAt() != null && !intent.nextCheckAt().isAfter(earliest);
            if (alreadyDue) {
                outcome = "throttled";
                result = Result.THROTTLED;
            } else {
                store.requestCheck(intentId, now, earliest);
                outcome = "queued";
                result = Result.QUEUED;
            }
        }
        // A repeat within the throttle window adds nothing the first row does not already say: no second row (so a
        // replay
        // flood cannot grow the table).
        if (!store.hasRecentOpenCallback(intentId, kind, now.minus(CheckSchedule.THROTTLE))) {
            store.insertCallback(
                    UUID.randomUUID(),
                    intentId,
                    now,
                    kind,
                    type,
                    trackingId.toLowerCase(Locale.ROOT),
                    reference,
                    outcome);
        }
        return result;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
