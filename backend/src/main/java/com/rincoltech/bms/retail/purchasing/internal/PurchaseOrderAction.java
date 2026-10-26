package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.approvals.ApprovalAction;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code retail_purchase_order} (chapter 8 section 8.4; issue #198): sending a purchase order whose value is at or above
 * the tenant's approval threshold for this action. Requested with {@code retail.purchase.create} and decided with
 * {@code retail.purchase.approve}, by two different people. Unlike the lending actions, it is a switch on the approval rules
 * (ADR-091), off until the business sets an amount or switches it on, so a shop with one administrator can order;
 * {@link OrdersService} decides that from the rule and calls {@link com.rincoltech.bms.core.approvals.Approvals} only when
 * it asks. The subject is the order, whose version
 * moves with every edit, so a request goes stale when the draft was changed meanwhile (FR-APR-08).
 */
@Component
class PurchaseOrderAction implements ApprovalAction {

    static final String TYPE = "retail_purchase_order";

    private final OrderSending sending;
    private final OrdersRepository repo;
    private final RetailCapabilities capabilities;

    PurchaseOrderAction(OrderSending sending, OrdersRepository repo, RetailCapabilities capabilities) {
        this.capabilities = capabilities;
        this.sending = sending;
        this.repo = repo;
    }

    @Override
    public String actionType() {
        return TYPE;
    }

    @Override
    public String subjectType() {
        return OrdersService.ORDER;
    }

    @Override
    public String makerPermission() {
        return OrdersService.PERMISSION;
    }

    @Override
    public String checkerPermission() {
        return OrdersService.APPROVE;
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
        return "Purchase orders";
    }

    @Override
    public boolean offered() {
        return capabilities.enabled(RetailCapabilities.PURCHASING);
    }

    @Override
    public Optional<Integer> subjectVersion(UUID subjectId) {
        return repo.find(subjectId, false).map(OrdersRepository.OrderRow::version);
    }

    /**
     * The checker is none of the people who made the order: the one who wrote the draft and the one who last edited it, as
     * well as the one who sent it (the maker, which the approvals module already excludes). Without this, whoever wrote an
     * order could approve it once a colleague sent it.
     */
    @Override
    public Set<UUID> conflictedDeciders(UUID subjectId, Map<String, Object> payload) {
        Set<UUID> out = new HashSet<>();
        repo.find(subjectId, false).ifPresent(o -> {
            out.add(o.createdBy());
            if (o.updatedBy() != null) {
                out.add(o.updatedBy());
            }
        });
        return out;
    }

    @Override
    public void execute(Execution e) {
        sending.sendApproved(e.subjectId(), e.makerId(), e.approvalId(), e.checkerId());
    }
}
