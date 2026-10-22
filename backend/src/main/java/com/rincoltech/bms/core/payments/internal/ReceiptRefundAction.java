package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code unallocated_refund} (FR-PAY-04): send the money of an unallocated receipt back to the payer, recorded with the way it
 * went and the lender's own reference for it. The platform does not move the money itself (the provider has no payout
 * interface, spec 3.1): staff pay it back, then the checker approves the record, so the liability is cleared only for a refund a
 * second person saw. Same permissions, same rules and same exactly-once guarantee as {@link ReceiptAllocationAction}.
 */
@Component
class ReceiptRefundAction implements ApprovalAction {

    static final String TYPE = "unallocated_refund";

    private final ReceiptStore receipts;
    private final ReceiptSettler settler;

    ReceiptRefundAction(ReceiptStore receipts, ReceiptSettler settler) {
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
        settler.refund(execution);
    }
}
