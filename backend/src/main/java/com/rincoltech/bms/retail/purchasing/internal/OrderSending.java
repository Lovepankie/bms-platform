package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.retail.purchasing.internal.OrdersRepository.OrderRow;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The act of sending a draft order, apart from the decision whether it needs a checker: {@link OrdersService} calls it when
 * no second person is needed and {@link PurchaseOrderAction} calls it from the approval transaction. It depends on nothing
 * that depends on approvals, so the action can use it without a cycle (as {@code ReturnsServicer} does for returns).
 */
@Component
class OrderSending {

    private final OrdersRepository repo;
    private final RetailCapabilities capabilities;
    private final AuditLog audit;

    OrderSending(OrdersRepository repo, RetailCapabilities capabilities, AuditLog audit) {
        this.repo = repo;
        this.capabilities = capabilities;
        this.audit = audit;
    }

    /** Executed by the approval: the maker sent it, the checker approved it. The order must still be a draft. */
    @Transactional(propagation = Propagation.MANDATORY)
    void sendApproved(UUID id, UUID makerId, UUID approvalId, UUID checkerId) {
        capabilities.require(RetailCapabilities.PURCHASING);
        OrderRow o = repo.find(id, true).orElseThrow(ApiException::notFound);
        markSent(o, makerId, approvalId, checkerId);
    }

    void markSent(OrderRow o, UUID by, UUID approvalId, UUID approvedBy) {
        if (!o.status().equals("draft") || repo.markSent(o.id(), by, approvalId, approvedBy) == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "order_not_draft",
                    "Order not a draft",
                    "This order has been sent or cancelled already.");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("order_no", o.orderNo());
        after.put("total_minor", o.totalMinor());
        after.put("sent_by", by);
        if (approvedBy != null) {
            after.put("approved_by", approvedBy);
            after.put("approval_id", approvalId);
        }
        audit.record(new AuditLog.Entry(
                "retail.purchase_order.sent",
                OrdersService.ORDER,
                o.id(),
                o.branchId(),
                Map.of("status", "draft"),
                after));
    }
}
