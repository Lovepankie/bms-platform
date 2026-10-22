package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.core.payments.internal.ReversalTargets.Kind;
import com.rincoltech.bms.core.payments.internal.ReversalTargets.Target;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises the checker request for a payment the provider reports reversed (spec 3.8, ADR-035 decision 16): one
 * {@code gateway_payment_reversal} per intent, made by the tenant's system user, so a person always decides and the platform
 * never reverses by itself. Runs in the checker's own transaction with the intent row locked, which is what makes raising it
 * twice (a notification and a poll, or the next day's review) one request: a request already waiting is returned, a rejected
 * one is respected, and a stale, expired or cancelled one is raised again with the facts as they are now.
 */
@Component
class GatewayReversals {

    private static final Logger log = LoggerFactory.getLogger(GatewayReversals.class);

    /** What the request carries: the facts a checker needs, frozen when it is raised (FR-APR-08). */
    static final String PAYLOAD_PURPOSE = "target_purpose";

    /** Pledged items that would not come back if the reversal reopens the loan, and the flag that says so (spec 3.16). */
    static final String PAYLOAD_UNSECURED = "reopen_would_be_unsecured";

    static final String PAYLOAD_NOT_RESTORED = "collateral_not_restored_on_reopen";

    /** The branch the money was received in (the intent's), where the checker must also decide. */
    static final String PAYLOAD_RECEIVED_BRANCH = "received_branch_id";

    /**
     * A reversal that has been asked for this many times and still stands (stale, expired, refused by the books) is not asked a
     * fourth time by the platform: the order is handed over to a person ({@link ReversalHandOver#UNRESOLVED}), and the weekly look
     * ends. A person may ask again.
     */
    static final int MAX_REQUESTS = 3;

    private final Approvals approvals;
    private final SystemUser systemUser;
    private final ReversalTargets targets;
    private final IntentAudit audit;
    private final PaymentCounters counters;
    private final IntentStore intents;
    private final BusinessClock clock;
    private final ReversalHandOver handOver;
    private final JdbcClient jdbc;

    GatewayReversals(
            Approvals approvals,
            SystemUser systemUser,
            ReversalTargets targets,
            IntentAudit audit,
            PaymentCounters counters,
            IntentStore intents,
            BusinessClock clock,
            ReversalHandOver handOver,
            JdbcClient jdbc) {
        this.approvals = approvals;
        this.systemUser = systemUser;
        this.targets = targets;
        this.audit = audit;
        this.counters = counters;
        this.intents = intents;
        this.clock = clock;
        this.handOver = handOver;
        this.jdbc = jdbc;
    }

    /**
     * What a call to {@link #raise} left: the request that waits, if one does, and whether there is nothing more to look at
     * (a decided or handed-over reversal, or nothing of ours to reverse). A call that could not read what it needs is neither:
     * the look is kept and tried again.
     */
    record Raised(Optional<UUID> waiting, boolean fin) {
        static Raised waiting(UUID id) {
            return new Raised(Optional.ofNullable(id), false);
        }

        static final Raised FINAL = new Raised(Optional.empty(), true);
        static final Raised RETRY = new Raised(Optional.empty(), false);
    }

    /** As {@link #raise(Intent, boolean)} for the platform's own looks. */
    @Transactional(propagation = Propagation.MANDATORY)
    Raised raise(Intent intent) {
        return raise(intent, false);
    }

    /**
     * Raises the request for an intent that is {@code reversed_pending_review}, unless one is waiting, one was decided, the order
     * was handed over, or nothing stands in the books for it. {@code byPerson} is a person asking again: a hand-over, a rejection
     * and the three-request limit are set aside once, and the order is taken back from its hand-over.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Raised raise(Intent intent, boolean byPerson) {
        if (!byPerson && ReversalHandOver.handedOver(intent)) {
            return Raised.FINAL;
        }
        Target target = targets.of(intent);
        if (target.kind() == Kind.NONE) {
            // A refund paid out by hand, or nothing ever booked: no ledger entry of ours can be reversed. The
            // reconciliation
            // lists it as a loss for the accountant (spec 3.8).
            return Raised.FINAL;
        }
        // A reversal reported again after the provider took one back is a new episode: what was asked, rejected or
        // handed over
        // in an earlier one says nothing about this one.
        String episode = String.valueOf(episodeOf(intent));
        EpisodeRequests asked = EpisodeRequests.of(jdbc, intent.id(), episode, clock.now());
        if (!byPerson && asked.lastApproved()) {
            return Raised.FINAL;
        }
        // A request a checker rejected, or one a person asked for, stands in the way of the platform asking by itself
        // again
        // in this episode, whatever became of the request after it (cancelled, expired, stale): otherwise the person
        // who
        // asked, or the rejection, could be got round by letting the next request be the platform's.
        if (!byPerson && !asked.waiting() && (asked.anyRejected() || asked.anyByPerson())) {
            handOver.handOver(intent, asked.anyRejected() ? ReversalHandOver.REJECTED : ReversalHandOver.UNRESOLVED);
            return Raised.FINAL;
        }
        boolean waiting = asked.waiting();
        if (!byPerson && !waiting && asked.total() >= MAX_REQUESTS) {
            handOver.handOver(intent, ReversalHandOver.UNRESOLVED);
            return Raised.FINAL;
        }
        // A request still pending from an earlier episode, or one that has expired, is ended, not reused with its old
        // facts
        // and expiry. One a checker is deciding right now cannot be ended; it is not ours to replace, so nothing is
        // raised.
        if (!waiting) {
            approvals.markStale(
                    GatewayReversalAction.TYPE,
                    intent.id(),
                    "The payment was reported reversed again, so this earlier request was closed and a new one raised.");
            if (pendingRemains(intent.id())) {
                return Raised.RETRY;
            }
        }
        Optional<Integer> version = targets.version(intent);
        if (version.isEmpty()) {
            return Raised.RETRY;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent_id", intent.id().toString());
        payload.put("order_reference", intent.merchantReference());
        payload.put("kind", target.kind().name().toLowerCase(java.util.Locale.ROOT));
        payload.put(PAYLOAD_PURPOSE, target.purpose());
        // The branch the money was received in: the checker must decide there too, whichever branch the loan is in.
        payload.put(PAYLOAD_RECEIVED_BRANCH, intent.branchId().toString());
        if (target.transactionId() != null) {
            payload.put("transaction_id", target.transactionId().toString());
        }
        if (target.receiptId() != null) {
            payload.put("receipt_id", target.receiptId().toString());
        }
        if (intent.confirmationCode() != null) {
            payload.put("confirmation_code", intent.confirmationCode());
        }
        payload.put("episode", episode);
        UUID asker = byPerson ? CurrentPrincipal.require().userId() : null;
        payload.put("reason", byPerson ? "asked_again_by_a_person" : "reported_reversed_by_provider");
        if (asker != null) {
            payload.put("asked_by", asker.toString());
        }
        if (target.kind() == Kind.BOOKED) {
            List<String> cautions = targets.handler(target)
                    .map(h -> h.reversalCautions(target.transactionId()))
                    .orElse(List.of());
            if (!cautions.isEmpty()) {
                payload.put(PAYLOAD_UNSECURED, true);
                payload.put(PAYLOAD_NOT_RESTORED, cautions);
            }
        }
        // The maker is the platform's user, or the person who asked again, who as the maker can never decide it.
        Approvals.Outcome outcome = approvals.requestAsSystem(
                asker != null ? asker : systemUser.id(),
                new ActionRequest(
                        GatewayReversalAction.TYPE,
                        target.branchId() == null ? intent.branchId() : target.branchId(),
                        intent.id(),
                        version.get(),
                        intent.paidAmountMinor(),
                        intent.paidCurrency(),
                        payload));
        if (byPerson && ReversalHandOver.handedOver(intent)) {
            Map<String, Object> cols = new HashMap<>();
            cols.put("refusal_code", null);
            cols.put("next_check_at", clock.now().plus(CheckSchedule.REVIEW_OPEN_EVERY));
            intents.update(intent.id(), "reversed_pending_review", null, clock.now(), cols);
        }
        boolean fresh = !waiting;
        if (fresh) {
            if (asker != null) {
                audit.user(
                        intent,
                        "reversal_requested_again",
                        "reversed_pending_review",
                        IntentAudit.reason("approval_request_id", String.valueOf(outcome.approvalId())));
            } else {
                audit.system(
                        intent,
                        "reversal_requested",
                        "reversed_pending_review",
                        IntentAudit.reason("approval_request_id", String.valueOf(outcome.approvalId())));
            }
            counters.count("reversal_requested");
            log.warn("payment intent {} reversed by the provider: checker request raised", intent.id());
        }
        return Raised.waiting(outcome.approvalId());
    }

    /** Which time the provider has reported this order reversed: the number of reversal reports audited for it. */
    private int episodeOf(Intent intent) {
        return jdbc.sql("""
                        SELECT count(*) FROM audit_log
                         WHERE tenant_id = current_setting('app.tenant_id')::uuid
                           AND entity_type = ? AND entity_id = ? AND action = 'core.payments.intent.reversal_reported'
                        """)
                .params("payment_intent", intent.id())
                .query(Integer.class)
                .single();
    }

    private boolean pendingRemains(UUID intentId) {
        return jdbc.sql("""
                        SELECT count(*) FROM approval_requests WHERE action_type = ? AND subject_id = ? AND status = 'pending'
                        """)
                        .params(GatewayReversalAction.TYPE, intentId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** What has been asked of the checkers in one episode of an order's reversal. */
    private record EpisodeRequests(
            long total, boolean lastApproved, boolean waiting, boolean anyRejected, boolean anyByPerson) {

        static EpisodeRequests of(JdbcClient jdbc, UUID intentId, String episode, java.time.Instant now) {
            return jdbc.sql("""
                            SELECT count(*) AS total,
                                   coalesce(bool_or(status = 'rejected'), false) AS any_rejected,
                                   coalesce(bool_or(payload ->> 'reason' = 'asked_again_by_a_person'), false) AS any_person,
                                   coalesce((array_agg(status ORDER BY requested_at DESC))[1] = 'approved', false) AS last_approved,
                                   coalesce((array_agg(status = 'pending' AND expires_at > CAST(? AS timestamptz) ORDER BY requested_at DESC))[1], false)
                                       AS waiting
                              FROM approval_requests
                             WHERE action_type = ? AND subject_id = ? AND payload ->> 'episode' = ?
                            """)
                    .params(java.sql.Timestamp.from(now), GatewayReversalAction.TYPE, intentId, episode)
                    .query((rs, n) -> new EpisodeRequests(
                            rs.getLong("total"),
                            rs.getBoolean("last_approved"),
                            rs.getBoolean("waiting"),
                            rs.getBoolean("any_rejected"),
                            rs.getBoolean("any_person")))
                    .single();
        }
    }

    /**
     * The provider says a payment it had reported reversed is completed again. Nothing was reversed in the books, so the order
     * goes back to what it was (booked, or held in the queue) with the request waiting for a checker ended {@code stale} and a note
     * the checker reads; if nothing of ours stands to carry on with, it is handed to a person. Counted once, when it happens. Runs
     * in the status check's transaction with the intent locked; the request row is only taken if no checker is deciding it right
     * now, and that checker finds the order no longer in review and is told so.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void completedAgain(Intent intent) {
        Target target = targets.of(intent);
        String back = switch (target.kind()) {
            case BOOKED -> target.receiptId() != null ? "booked_unallocated" : "booked";
            case HELD -> "booked_unallocated";
            case NONE -> null;
        };
        if (back == null) {
            handOver.handOver(intent, ReversalHandOver.COMPLETED_AGAIN);
            return;
        }
        boolean ended = approvals.markStale(
                GatewayReversalAction.TYPE,
                intent.id(),
                "The payment provider now reports this payment completed again, so it was not reversed. Nothing was changed.");
        Map<String, Object> cols = new HashMap<>();
        cols.put("refusal_code", null);
        cols.put(
                "next_check_at",
                intent.paidAt() == null
                        ? null
                        : CheckSchedule.nextReview(clock.now(), intent.paidAt(), intent.reportedMethod()));
        if (intents.update(intent.id(), "reversed_pending_review", back, clock.now(), cols)) {
            // A request a checker was deciding when it was first looked for is no longer locked by us: once more, now
            // that the
            // order is back to booked, so it cannot be left pending against it (the checker's own run also finds the
            // order
            // out of review and closes it).
            approvals.markStale(
                    GatewayReversalAction.TYPE,
                    intent.id(),
                    "The payment provider now reports this payment completed again, so it was not reversed. Nothing was changed.");
            audit.system(
                    intent,
                    "completed_after_reversal",
                    back,
                    Map.of("refusal_code", ReversalHandOver.COMPLETED_AGAIN, "request_ended", ended));
            counters.count(ReversalHandOver.COMPLETED_AGAIN);
            log.warn("payment intent {} reported completed again after a reversal: back to {}", intent.id(), back);
        }
    }
}
