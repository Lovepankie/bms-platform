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
     * ADR-091: whether a tenant administrator may switch this action's approval off on the approval rules. False by default:
     * lending actions always ask.
     */
    default boolean switchable() {
        return false;
    }

    /**
     * ADR-091: whether the approval asks while the business never saved a switch for it, given its stored amount (0 when none),
     * so a business that never saved one keeps the behaviour it had. True by default.
     */
    default boolean onByDefault(long thresholdMinor) {
        return true;
    }

    /**
     * ADR-091: whether the approval rules govern the action, so a refusal on approval makes the request stale with its reason
     * (decision 11). Every switchable action, and one that follows another's switch (the void of a return).
     */
    default boolean governedByRules() {
        return switchable();
    }

    /** ADR-091: whether the action is its own row of the approval rules; false for one that follows another's rule. */
    default boolean listed() {
        return true;
    }

    /** ADR-091: whether the bound business uses the action now (a capability switched on), so the approval rules list it. */
    default boolean offered() {
        return true;
    }

    /** The action as a person names it, in the approval rules and the push to the checkers. */
    default String label() {
        String words = actionType().replace('_', ' ');
        return words.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + words.substring(1);
    }

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
     * A permission the checker needs besides {@link #checkerPermission} for a request with this
     * payload, in the request's branch: for a payload that carries a riskier outcome. A checker
     * without it is refused up front and is not offered the decision. Empty by default.
     */
    default Optional<String> extraCheckerPermission(Map<String, Object> payload) {
        return Optional.empty();
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
