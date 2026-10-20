package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.retail.sales.internal.WriteOffsServicer.Plan;
import java.util.Optional;
import java.util.UUID;
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

    private final WriteOffsServicer servicer;
    private final SalesRepository sales;

    SaleWriteOffAction(WriteOffsServicer servicer, SalesRepository sales) {
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
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return sales.find(subjectId, false).map(h -> h.sale().version());
    }

    @Override
    public void execute(Execution e) {
        Plan plan = servicer.plan(e.subjectId());
        servicer.record(plan, (String) e.payload().get(REASON), e.makerId(), e.approvalId(), e.checkerId(), null);
    }
}
