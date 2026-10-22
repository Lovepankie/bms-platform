package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import java.util.Optional;
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

    @Override
    public void execute(Execution execution) {
        settler.allocate(execution);
    }
}
