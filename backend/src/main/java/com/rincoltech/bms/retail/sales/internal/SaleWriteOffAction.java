package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.core.approvals.RequestSupersededException;
import com.rincoltech.bms.retail.sales.internal.WriteOffsServicer.Plan;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code retail_sale_write_off} (chapter 8 section 8.4; issue #340, ADR-043): writing off the unpaid part of a credit sale at
 * or above the tenant's approval threshold for this action, or by default (no positive threshold) when the tenant has two or
 * more active administrators. Requested and decided with {@code retail.credit.write_off}, by two different people.
 * {@link WriteOffsService} decides when to call {@link com.rincoltech.bms.core.approvals.Approvals}. The subject is the sale, whose version
 * moves with every payment and return, so a request goes stale when anything else happened to the sale meanwhile (FR-APR-08).
 * Everything is checked again at execution against the sale as it is then, and the write-off is dated the day it executes.
 */
@Component
class SaleWriteOffAction implements ApprovalAction {

    static final String TYPE = "retail_sale_write_off";
    static final String REASON = "reason";
    /** The amount the maker asked to write off, which the checker approved (issue #385: a fee on the account can change it). */
    static final String UNPAID = "unpaid_minor";

    private final WriteOffsServicer servicer;
    private final SalesRepository sales;
    // Lazy: the write-off service asks the approvals module, which holds this action.
    private final WriteOffsService writeOffs;

    SaleWriteOffAction(WriteOffsServicer servicer, SalesRepository sales, @Lazy WriteOffsService writeOffs) {
        this.writeOffs = writeOffs;
        this.servicer = servicer;
        this.sales = sales;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return "retail.sale";
    }

    @Override
    public String makerPermission() {
        return WriteOffsServicer.PERMISSION;
    }

    @Override
    public String checkerPermission() {
        return WriteOffsServicer.PERMISSION;
    }

    @Override
    public boolean thresholdApplies() {
        return true;
    }

    @Override
    public boolean switchable() {
        return true;
    }

    @Override
    public boolean onByDefault(long thresholdMinor) {
        return true;
    }

    @Override
    public String label() {
        return "Credit write-offs";
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return sales.find(subjectId, false).map(h -> h.sale().version());
    }

    @Override
    public void execute(Execution e) {
        Plan plan = servicer.plan(e.subjectId());
        // The checker approved an amount: a debt that changed since (a payment, a fee paid or now owed) is not written
        // off.
        Object approved = e.payload().get(UNPAID);
        if (approved instanceof Number n && n.longValue() != plan.unpaidMinor()) {
            // The request is superseded, not failed: the approvals inbox closes it and frees the sale for a fresh
            // request.
            throw new RequestSupersededException(
                    "write_off_changed",
                    "The debt changed",
                    "The amount owed changed from " + n.longValue() + " to " + plan.unpaidMinor()
                            + " since the write-off was asked for. Ask for it again.",
                    java.util.Map.of("approved_minor", n.longValue(), "owed_minor", plan.unpaidMinor()));
        }
        if (e.approvalId() == null) {
            // Run at once with no checker because the switch is off (ADR-091): flagged and told like any write-off done
            // alone.
            writeOffs.writeAlone(plan, (String) e.payload().get(REASON), e.makerId(), null, "approval_switched_off");
            return;
        }
        servicer.record(plan, (String) e.payload().get(REASON), e.makerId(), e.approvalId(), e.checkerId(), null);
    }
}
