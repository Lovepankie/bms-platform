package com.rincoltech.bms.core.approvals;

import java.util.Map;
import java.util.UUID;

/**
 * Requests an action that needs a checker (FR-APR-01). Called by the module that owns the action,
 * inside its own transaction, after its own validation. Below the tenant's threshold (when the
 * action allows one) the action executes at once; otherwise a pending request is stored and the
 * action has no effect until a checker approves it.
 */
public interface Approvals {

    Outcome request(ActionRequest request);

    /**
     * As {@link #request(ActionRequest)}, weighed against the rule the caller already read to decide to ask (ADR-091), so the rule
     * is read once: a switch saved between the caller's read and this call cannot run at once an action the caller will answer as
     * waiting. The request then waits for a checker as the caller decided.
     */
    Outcome request(ActionRequest request, Rule decided);

    /**
     * ADR-091: the bound business's rule for an action type: whether it asks now and from what amount. A module that decides for
     * itself whether to ask (a return, a write-off, a void) reads this, so the switch on the approval rules is kept everywhere.
     */
    Rule rule(String actionType);

    /**
     * @param on whether the action asks for a second person at all; always true for an action that is not switchable
     * @param thresholdMinor the amount from which it asks; 0 means every one asks
     */
    record Rule(boolean on, long thresholdMinor) {

        public boolean asks(long amountMinor) {
            return on && amountMinor >= thresholdMinor;
        }
    }

    /**
     * @param subjectVersion the subject's {@code version} now, checked again on approval
     * @param amountMinor the amount the queue and the checker see, or {@code null}
     * @param thresholdMinor the amount weighed against the tenant's threshold: the same as {@code amountMinor} unless
     *     the action weighs a running total (a waiver weighs everything waived on the loan with it)
     * @param payload the snapshot executed on approval (FR-APR-08); JSON-compatible values only
     */
    record ActionRequest(
            String actionType,
            UUID branchId,
            UUID subjectId,
            Integer subjectVersion,
            Long amountMinor,
            Long thresholdMinor,
            String currency,
            Map<String, Object> payload) {

        public ActionRequest(
                String actionType,
                UUID branchId,
                UUID subjectId,
                Integer subjectVersion,
                Long amountMinor,
                String currency,
                Map<String, Object> payload) {
            this(actionType, branchId, subjectId, subjectVersion, amountMinor, amountMinor, currency, payload);
        }
    }

    /** @param approvalId the pending request, or {@code null} when the action executed at once */
    record Outcome(boolean executed, UUID approvalId) {}
}
