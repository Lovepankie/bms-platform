package com.rincoltech.bms.core.approvals;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One action type that needs a second person (chapter 8 section 8.4): who may request it, who may
 * decide it, whether the tenant's threshold applies, and how to execute it. Implemented by the
 * module that owns the action, as a Spring bean; the action type is unique across beans.
 */
public interface ApprovalAction {

    /** Lower snake case, for example {@code loan_disbursement}; stored in {@code approval_requests}. */
    String actionType();

    /** What the request is about, for example {@code lending.loan}. */
    String subjectType();

    /** Permission the maker needs in the request's branch. */
    String makerPermission();

    /** Permission the checker needs in the request's branch. */
    String checkerPermission();

    /** FR-APR-04: whether an amount below the tenant's threshold executes without a checker. */
    boolean thresholdApplies();

    /**
     * The subject's current version, or empty when it no longer exists. Compared with the version
     * stored at request time: a difference marks the request {@code stale} (FR-APR-08).
     */
    Optional<Integer> subjectVersion(UUID subjectId);

    /**
     * Users who may not decide this request besides the maker, for example the submitter and the
     * appraiser of a loan (FR-APR-03). Empty by default.
     */
    default Set<UUID> conflictedDeciders(UUID subjectId, Map<String, Object> payload) {
        return Set.of();
    }

    /**
     * Performs the action with exactly the stored payload, inside the approval transaction. A
     * failure leaves the request pending with the error recorded (FR-APR-06).
     */
    void execute(Execution execution);

    /**
     * @param approvalId the approval request, or {@code null} when the action ran below the threshold
     * @param checkerId the user who approved, or {@code null} below the threshold
     */
    record Execution(
            UUID approvalId,
            UUID branchId,
            UUID subjectId,
            Map<String, Object> payload,
            UUID makerId,
            UUID checkerId) {}
}
