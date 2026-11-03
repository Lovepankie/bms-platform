package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.kernel.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code unallocated_allocation} (FR-PAY-04, chapter 8 section 8.4): put the money of an unallocated receipt on a loan or a
 * savings account. Requested with {@code core.payments.allocate} in the receipt's branch, decided with
 * {@code core.payments.allocate_approve}, never below a threshold (money that arrived without an owner is always looked at by
 * a second person). The subject is the receipt; its version rises with every change, so a settled receipt makes every other
 * request on it stale (FR-APR-08).
 */
@Component
class ReceiptAllocationAction implements ApprovalAction {

    static final String TYPE = "unallocated_allocation";

    private final ReceiptStore receipts;
    private final ReceiptSettler settler;

    ReceiptAllocationAction(ReceiptStore receipts, ReceiptSettler settler) {
        this.receipts = receipts;
        this.settler = settler;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return "core.unallocated_receipt";
    }

    @Override
    public String makerPermission() {
        return "core.payments.allocate";
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
        return receipts.version(subjectId);
    }

    /** The credit balance sentence with the payoff of now, not of the request (ADR-035 decision 51). */
    @Override
    public Optional<String> checkerNotice(UUID subjectId, java.util.Map<String, Object> payload) {
        return payload == null ? Optional.empty() : settler.currentCreditNotice(payload);
    }

    /**
     * The checker also decides in the branch of the loan or account the money goes to, so one who could not approve can neither
     * reject nor be offered the decision (the parity {@link GatewayReversalAction} keeps for reversals).
     * JUSTIFICATION-A3: the one hook the approval service asks; the branch rule existed only at execute time.
     */
    @Override
    public boolean mayDecide(Principal checker, UUID branchId, Map<String, Object> payload) {
        Object target = payload == null ? null : payload.get(ReceiptSettler.SUBJECT_BRANCH_ID);
        if (!ApprovalAction.super.mayDecide(checker, branchId, payload)) {
            return false;
        }
        try {
            return target == null
                    || checker.may(ReceiptSettler.CHECKER_PERMISSION, UUID.fromString(String.valueOf(target)));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** The loan or account the money goes to, its branch and its payoff (through the excess) are for deciders only. */
    @Override
    public Set<String> deciderOnlyPayloadKeys() {
        return Set.of(
                CHECKER_NOTICE,
                ReceiptService.CREDIT_BALANCE,
                ReceiptService.CREDIT_SUBJECT_LABEL,
                ReceiptSettler.SUBJECT_ID,
                ReceiptSettler.SUBJECT_BRANCH_ID);
    }

    @Override
    public void execute(Execution execution) {
        settler.allocate(execution);
    }
}
