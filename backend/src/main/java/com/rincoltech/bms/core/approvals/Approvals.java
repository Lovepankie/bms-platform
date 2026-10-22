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
