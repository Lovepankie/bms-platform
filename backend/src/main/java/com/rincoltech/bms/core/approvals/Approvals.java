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
     * As {@link #request}, for a request the platform raises (a payment the provider reported reversed): the maker is the tenant's
     * non-login system user, who holds no permission, or a person the caller names who asked the platform to raise it again (and who
     * then, as the maker, can never decide it); the maker's permission is not asked. It never executes
     * at once (an action with a threshold is refused here), the checker is a person, and a request for the same action and
     * subject that is still pending is returned as it is instead of being refused, so raising it again is idempotent.
     */
    Outcome requestAsSystem(java.util.UUID makerUserId, ActionRequest request);

    /**
     * Ends the request of this action still pending on this subject as {@code stale}, with a note the checker reads on the
     * request, because the facts it stood on no longer hold (the provider says a payment it called reversed is completed
     * again). Nothing is executed. Returns whether a pending request was ended.
     */
    boolean markStale(String actionType, java.util.UUID subjectId, String note);

    /**
     * @param subjectVersion the subject's {@code version} now, checked again on approval
     * @param amountMinor the amount the threshold and the queue show, or {@code null}
     * @param payload the snapshot executed on approval (FR-APR-08); JSON-compatible values only
     */
    record ActionRequest(
            String actionType,
            UUID branchId,
            UUID subjectId,
            Integer subjectVersion,
            Long amountMinor,
            String currency,
            Map<String, Object> payload) {}

    /** @param approvalId the pending request, or {@code null} when the action executed at once */
    record Outcome(boolean executed, UUID approvalId) {}
}
