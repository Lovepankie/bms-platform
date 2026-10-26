package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.ReturnRequest;
import com.rincoltech.bms.retail.sales.internal.ReturnsServicer.Plan;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code retail_sale_return} (chapter 8 section 8.4; issue #201): a customer return at or above the tenant's approval
 * threshold for this action. Requested and decided with {@code retail.sale.return}, by two different people. Unlike the
 * lending actions, it is a switch on the approval rules (ADR-091), off until the business sets an amount or switches it on, so a
 * shop with one administrator can take goods back; {@link ReturnsService} decides that from the rule and calls
 * {@link com.rincoltech.bms.core.approvals.Approvals} only when it asks. The subject is the sale, whose version moves with every payment and every return, so a request goes stale
 * when anything else happened to the sale meanwhile (FR-APR-08). Everything is checked again at execution against the sale
 * as it is then, and the return is dated the day it executes.
 */
@Component
class ReturnAction implements ApprovalAction {

    static final String TYPE = "retail_sale_return";

    private final ReturnsServicer servicer;
    private final SalesRepository sales;
    private final ObjectMapper mapper;
    private final RetailCapabilities capabilities;

    ReturnAction(
            ReturnsServicer servicer, SalesRepository sales, ObjectMapper mapper, RetailCapabilities capabilities) {
        this.capabilities = capabilities;
        this.servicer = servicer;
        this.sales = sales;
        this.mapper = mapper;
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
        return ReturnsServicer.PERMISSION;
    }

    @Override
    public String checkerPermission() {
        return ReturnsServicer.PERMISSION;
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
        return thresholdMinor > 0;
    }

    @Override
    public String label() {
        return "Customer returns";
    }

    @Override
    public boolean offered() {
        return capabilities.enabled(RetailCapabilities.RETURNS);
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return sales.find(subjectId, false).map(h -> h.sale().version());
    }

    @Override
    public void execute(Execution e) {
        ReturnRequest request = mapper.convertValue(e.payload(), ReturnRequest.class);
        Plan plan = servicer.plan(e.subjectId(), request);
        servicer.record(plan, request.note(), e.makerId(), e.approvalId(), e.checkerId());
    }
}
