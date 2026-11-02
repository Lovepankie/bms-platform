package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.core.approvals.RequestSupersededException;
import com.rincoltech.bms.core.payments.PurposeHandler;
import com.rincoltech.bms.core.payments.internal.IntentStore.Intent;
import com.rincoltech.bms.core.payments.internal.ReversalTargets.Target;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.Principal;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code gateway_payment_reversal} (spec 3.8, ADR-035 decision 16): the checker decision on a payment the provider reports
 * reversed. Raised only by the platform ({@link GatewayReversals}, as the tenant's system user); no staff member can request it
 * (the maker permission is one nobody holds). Decided with {@code core.payments.allocate_approve}, and besides that with the
 * permission that decides a reversal of what the money became (a repayment reversal on a loan, a withdrawal approval on a
 * savings account), so no one reverses a loan they could not reverse by hand. Never below a threshold.
 *
 * <p>Approving reverses what stands for the payment, once: a repayment or deposit through the same code as a staff reversal (the
 * only way a gateway transaction can be reversed), or the receipt still held in the queue; the intent then ends
 * {@code reversed}. The subject's version is the loan's, the account's or the receipt's, so any movement since the request
 * makes it stale (FR-APR-08) and the daily review raises a fresh one. A deposit already withdrawn is refused by the savings
 * code (no negative balance): the approval fails, the request stays pending with the reason, and the loss is the accountant's
 * to record by manual journal.
 */
@Component
class GatewayReversalAction implements ApprovalAction {

    static final String TYPE = "gateway_payment_reversal";

    private final IntentStore intents;
    private final IntentAudit intentAudit;
    private final ReversalTargets targets;
    private final ReceiptSettler settler;
    private final BusinessClock clock;
    private final ReversalHandOver handOver;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    GatewayReversalAction(
            IntentStore intents,
            IntentAudit intentAudit,
            ReversalTargets targets,
            ReceiptSettler settler,
            BusinessClock clock,
            ReversalHandOver handOver,
            org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.intents = intents;
        this.intentAudit = intentAudit;
        this.targets = targets;
        this.settler = settler;
        this.clock = clock;
        this.handOver = handOver;
        this.jdbc = jdbc;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return "core.payment_intent";
    }

    /** Nobody holds it: the platform raises this request itself. */
    @Override
    public String makerPermission() {
        return "core.payments.system";
    }

    @Override
    public String checkerPermission() {
        return ReceiptSettler.CHECKER_PERMISSION;
    }

    @Override
    public boolean thresholdApplies() {
        return false;
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return intents.find(subjectId).flatMap(targets::version);
    }

    /**
     * The permission that reverses what the money became, and, for a loan that would reopen without some of its pledges, the
     * authority that lets collateral go (the same two a staff reversal of such a repayment asks, #176). Asked of a rejection as
     * well as an approval.
     */
    @Override
    public Set<String> extraCheckerPermissions(Map<String, Object> payload) {
        Object purpose = payload.get(GatewayReversals.PAYLOAD_PURPOSE);
        Optional<PurposeHandler> handler =
                purpose == null ? Optional.empty() : targets.registry().find(String.valueOf(purpose));
        Set<String> needed = new HashSet<>();
        handler.flatMap(PurposeHandler::reversalCheckerPermission).ifPresent(needed::add);
        if (Boolean.TRUE.equals(payload.get(GatewayReversals.PAYLOAD_UNSECURED))) {
            handler.flatMap(PurposeHandler::cautionCheckerPermission).ifPresent(needed::add);
        }
        return needed;
    }

    /**
     * Whoever rejected an earlier request for this payment, and whoever asked for one, cannot decide a later one in the same
     * episode of the reversal: a person cannot override a checker's rejection by asking again and approving, nor ask, cancel
     * and let the platform's next request be theirs to approve. A new episode (the provider took the reversal back and
     * reported it again) starts clean, so a small team is not locked out for good; within one, the exit is a checker who took
     * no part (spec 3.16, runbook 4d).
     */
    @Override
    public Set<UUID> conflictedDeciders(UUID subjectId, Map<String, Object> payload) {
        Object episode = payload.get("episode");
        return new HashSet<>(jdbc.sql("""
                        SELECT decided_by FROM approval_requests
                         WHERE action_type = :type AND subject_id = :subject AND status = 'rejected'
                           AND decided_by IS NOT NULL AND (CAST(:episode AS text) IS NULL OR payload ->> 'episode' = CAST(:episode AS text))
                        UNION
                        SELECT requested_by FROM approval_requests
                         WHERE action_type = :type AND subject_id = :subject
                           AND payload ->> 'reason' = 'asked_again_by_a_person'
                           AND (CAST(:episode AS text) IS NULL OR payload ->> 'episode' = CAST(:episode AS text))
                        """)
                .param("type", TYPE)
                .param("subject", subjectId)
                .param("episode", episode == null ? null : String.valueOf(episode))
                .query(UUID.class)
                .list());
    }

    /**
     * Approve, reject and the queue's "you may decide" all ask this: the checker permissions in the branch of what is reversed,
     * and {@code core.payments.allocate_approve} also in the branch the money was received in, which differs when a receipt of
     * one branch was allocated to a loan of another.
     */
    @Override
    public boolean mayDecide(Principal checker, UUID branchId, Map<String, Object> payload) {
        if (!ApprovalAction.super.mayDecide(checker, branchId, payload)) {
            return false;
        }
        Object received = payload.get(GatewayReversals.PAYLOAD_RECEIVED_BRANCH);
        if (received == null) {
            return true;
        }
        try {
            return checker.may(ReceiptSettler.CHECKER_PERMISSION, UUID.fromString(String.valueOf(received)));
        } catch (IllegalArgumentException bad) {
            return false;
        }
    }

    /** A rejection hands the order to a person: it is not asked again, and is no longer counted as waiting for a checker. */
    @Override
    public void rejected(Execution request, String note) {
        intents.lock(request.subjectId())
                .filter(i -> "reversed_pending_review".equals(i.status()))
                .ifPresent(i -> handOver.handOver(i, ReversalHandOver.REJECTED));
    }

    @Override
    public void execute(Execution e) {
        Instant now = clock.now();
        Intent intent = intents.lock(e.subjectId()).orElseThrow(ApiException::notFound);
        if (!"reversed_pending_review".equals(intent.status())) {
            throw new RequestSupersededException(
                    "payment_not_in_review",
                    "The payment has changed",
                    "The payment is now " + intent.status() + ". This request is closed.",
                    Map.of());
        }
        Target target = targets.of(intent);
        String reason = String.valueOf(e.payload().getOrDefault("reason", "reported_reversed_by_provider"));
        Map<String, Object> extra = new LinkedHashMap<>();
        switch (target.kind()) {
            case HELD -> {
                settler.reverseHeld(target.receiptId(), e, reason);
                extra.put("unallocated_receipt_id", target.receiptId().toString());
            }
            case BOOKED -> {
                PurposeHandler handler = targets.handler(target)
                        .orElseThrow(() -> ApiException.rule("unknown_purpose", "No handler for that payment."));
                handler.reverseBooked(
                        target.transactionId(),
                        reason,
                        e.approvalId(),
                        e.makerId(),
                        e.checkerId(),
                        listed(e.payload()));
                extra.put("reversed_subject_txn_id", target.transactionId().toString());
            }
            case NONE ->
                throw ApiException.rule("nothing_to_reverse", "Nothing stands in the books for this payment any more.");
        }
        if (!intents.update(intent.id(), "reversed_pending_review", "reversed", now, new java.util.HashMap<>())) {
            throw new IllegalStateException("the intent changed under its own row lock");
        }
        extra.put(
                "approval_request_id",
                e.approvalId() == null ? null : e.approvalId().toString());
        extra.put("paid_amount_minor", intent.paidAmountMinor());
        intentAudit.user(intent, "reversed", "reversed", extra);
    }

    private static Set<String> listed(Map<String, Object> payload) {
        Set<String> items = new HashSet<>();
        if (payload.get(GatewayReversals.PAYLOAD_NOT_RESTORED) instanceof List<?> l) {
            l.forEach(i -> items.add(String.valueOf(i)));
        }
        return items;
    }
}
