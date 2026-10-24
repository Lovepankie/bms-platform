package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.approvals.Approvals.ActionRequest;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.purchasing.internal.OrdersApi.CancelRequest;
import com.rincoltech.bms.retail.purchasing.internal.OrdersApi.Order;
import com.rincoltech.bms.retail.purchasing.internal.OrdersApi.OrderLine;
import com.rincoltech.bms.retail.purchasing.internal.OrdersApi.OrderLineRequest;
import com.rincoltech.bms.retail.purchasing.internal.OrdersApi.OrderList;
import com.rincoltech.bms.retail.purchasing.internal.OrdersApi.OrderOutcome;
import com.rincoltech.bms.retail.purchasing.internal.OrdersApi.OrderRequest;
import com.rincoltech.bms.retail.purchasing.internal.OrdersApi.ReceiptRef;
import com.rincoltech.bms.retail.purchasing.internal.OrdersRepository.LineRow;
import com.rincoltech.bms.retail.purchasing.internal.OrdersRepository.NewLine;
import com.rincoltech.bms.retail.purchasing.internal.OrdersRepository.NewOrder;
import com.rincoltech.bms.retail.purchasing.internal.OrdersRepository.OrderRow;
import com.rincoltech.bms.retail.purchasing.internal.PurchaseLines.Numbered;
import com.rincoltech.bms.retail.purchasing.internal.PurchaseLines.Planned;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase orders (purchasing capability; ADR-033, issue #198; docs/specs/trade-capabilities.md section 7.1). An order
 * posts nothing and moves no stock. It is a draft until it is sent, and a draft is edited under {@code If-Match} on its
 * version; sending is the approval point: at or above the tenant's threshold for {@code retail_purchase_order} the send
 * waits for a second person holding {@code retail.purchase.approve} (maker-checker, ADR-015), and with no threshold set it
 * does not (the default is open, as for customer returns). Once sent the order never changes; receipts move it to partly
 * received and received, and what is left is cancelled, never deleted.
 */
@Service
class OrdersService {

    static final String ORDER = "retail.purchase_order";
    static final String PERMISSION = "retail.purchase.create";
    static final String APPROVE = "retail.purchase.approve";
    static final String PAYABLE = "retail.payable.manage";
    static final String ORDERS = "/api/v1/retail/purchase-orders";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    static final int MIN_REASON = 5;
    private static final List<String> STATUSES = List.of("draft", "sent", "partly_received", "received", "cancelled");

    private final OrdersRepository repo;
    private final PurchasingRepository suppliers;
    private final PurchaseLines lines;
    private final RetailBranchContext branches;
    private final Idempotency idempotency;
    private final Approvals approvals;
    private final TenantSettings settings;
    private final RetailCapabilities capabilities;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final OrderSending sending;

    OrdersService(
            OrdersRepository repo,
            PurchasingRepository suppliers,
            PurchaseLines lines,
            RetailBranchContext branches,
            Idempotency idempotency,
            Approvals approvals,
            TenantSettings settings,
            RetailCapabilities capabilities,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            OrderSending sending) {
        this.repo = repo;
        this.suppliers = suppliers;
        this.lines = lines;
        this.branches = branches;
        this.idempotency = idempotency;
        this.approvals = approvals;
        this.settings = settings;
        this.capabilities = capabilities;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.sending = sending;
    }

    // ---- Writes ----------------------------------------------------------------------------

    @Transactional
    Outcome<Order> create(String key, OrderRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(key, "POST", ORDERS, r, Order.class, () -> record(r));
    }

    private Order record(OrderRequest r) {
        UUID branch = branches.resolveStocking(PERMISSION, r.branchId());
        Draft d = plan(r);
        UUID id = UUID.randomUUID();
        Numbered no = lines.next("PO", "retail_purchase_order_no", branch);
        repo.insert(new NewOrder(
                id,
                no.no(),
                no.seq(),
                branch,
                r.supplierId(),
                d.orderedOn(),
                r.expectedOn(),
                tenant.profile().currency(),
                d.total(),
                PurchasingService.blankToNull(r.note()),
                CurrentPrincipal.require().userId()));
        d.lines().forEach(l -> repo.insertLine(id, l));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("order_no", no.no());
        after.put("supplier_id", r.supplierId());
        after.put("total_minor", d.total());
        after.put("lines", d.lines().size());
        audit.record(AuditLog.Entry.created("retail.purchase_order.created", ORDER, id, branch, after));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    /** A draft is replaced as a whole under {@code If-Match}; the branch and the number are fixed. */
    @Transactional
    Order update(UUID id, String ifMatch, OrderRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        int expected = Versions.fromIfMatch(ifMatch);
        OrderRow o = visible(id, true);
        if (o.version() != expected) {
            throw Versions.conflict(o.version());
        }
        if (!o.status().equals("draft")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "order_not_draft",
                    "Order not a draft",
                    "Only a draft order is edited. Cancel this one and raise a new order.");
        }
        if (r.branchId() != null && !r.branchId().equals(o.branchId())) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "branch_id", "invalid", "The branch of an order is fixed; cancel it and raise a new one.")));
        }
        Draft d = plan(r);
        repo.deleteLines(id);
        d.lines().forEach(l -> repo.insertLine(id, l));
        if (repo.updateDraft(
                        id,
                        r.supplierId(),
                        d.orderedOn(),
                        r.expectedOn(),
                        d.total(),
                        PurchasingService.blankToNull(r.note()),
                        CurrentPrincipal.require().userId(),
                        expected)
                == 0) {
            throw Versions.conflict(repo.find(id, false).map(OrderRow::version).orElse(expected));
        }
        audit.record(new AuditLog.Entry(
                "retail.purchase_order.updated",
                ORDER,
                id,
                o.branchId(),
                Map.of("total_minor", o.totalMinor(), "supplier_id", o.supplierId()),
                Map.of(
                        "total_minor",
                        d.total(),
                        "supplier_id",
                        r.supplierId(),
                        "lines",
                        d.lines().size())));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    @Transactional
    Outcome<OrderOutcome> send(String key, UUID id) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(
                key, "POST", ORDERS + "/" + id + "/send", Map.of("id", id), OrderOutcome.class, () -> sendOrAsk(id));
    }

    private OrderOutcome sendOrAsk(UUID id) {
        OrderRow o = visible(id, true);
        if (!o.status().equals("draft")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "order_not_draft",
                    "Order not a draft",
                    "This order has been sent or cancelled already.");
        }
        long threshold = settings.approvalThresholdMinor(PurchaseOrderAction.TYPE);
        if (threshold > 0 && o.totalMinor() >= threshold) {
            Approvals.Outcome a = approvals.request(new ActionRequest(
                    PurchaseOrderAction.TYPE, o.branchId(), id, o.version(), o.totalMinor(), o.currency(), Map.of()));
            return new OrderOutcome(false, a.approvalId(), null);
        }
        sending.markSent(o, CurrentPrincipal.require().userId(), null, null);
        return new OrderOutcome(true, null, view(repo.find(id, false).orElseThrow(), true));
    }

    @Transactional
    Outcome<Order> cancel(String key, UUID id, CancelRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(key, "POST", ORDERS + "/" + id + "/cancel", r, Order.class, () -> cancelNow(id, r));
    }

    private Order cancelNow(UUID id, CancelRequest r) {
        OrderRow o = visible(id, true);
        String reason = r.reason().trim();
        if (reason.length() < MIN_REASON) {
            throw ApiException.validation(List.of(
                    new FieldProblem("reason", "invalid", "Give a reason of at least " + MIN_REASON + " characters.")));
        }
        if (o.status().equals("cancelled") || o.status().equals("received")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "order_closed",
                    "Order closed",
                    "An order that was cancelled or has been received in full cannot be cancelled.");
        }
        if (repo.markCancelled(id, CurrentPrincipal.require().userId(), reason) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "order_closed", "Order closed", "This order is closed.");
        }
        audit.record(new AuditLog.Entry(
                "retail.purchase_order.cancelled",
                ORDER,
                id,
                o.branchId(),
                Map.of("status", o.status()),
                Map.of("status", "cancelled", "reason", reason, "order_no", o.orderNo())));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    /**
     * After a receipt is posted or voided: sent when nothing stands, received when every line has what was ordered,
     * partly received otherwise. Called with the order locked, in the receipt's transaction.
     */
    void refreshStatus(UUID orderId) {
        OrderRow o = repo.find(orderId, false).orElseThrow();
        if (o.status().equals("draft") || o.status().equals("cancelled")) {
            return;
        }
        List<LineRow> all = repo.lines(orderId);
        boolean any = all.stream().anyMatch(l -> l.receivedQty().signum() > 0);
        boolean full =
                !all.isEmpty() && all.stream().allMatch(l -> l.receivedUnitQty().compareTo(orderedInUnit(l)) >= 0);
        repo.setStatus(orderId, full ? "received" : any ? "partly_received" : "sent");
    }

    /** D23, issue #554: what a line ordered, in its own unit (packs for a pack line), as receipts are counted. */
    static BigDecimal orderedInUnit(LineRow l) {
        return l.unitQty() != null ? l.unitQty() : l.qty();
    }

    // ---- Reads -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    Order get(UUID id) {
        capabilities.require(RetailCapabilities.PURCHASING);
        OrderRow o = visible(id, false);
        UUID own = PurchasingService.ownRecordsOnly();
        if (own != null && !own.equals(o.createdBy())) {
            throw ApiException.notFound();
        }
        return view(o, true);
    }

    @Transactional(readOnly = true)
    OrderList list(
            List<UUID> branchIds,
            UUID supplierId,
            String status,
            LocalDate from,
            LocalDate to,
            String q,
            Integer limit,
            String cursor) {
        capabilities.require(RetailCapabilities.PURCHASING);
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.validation(List.of(new FieldProblem("status", "invalid", "Not an order status.")));
        }
        List<UUID> filter = CurrentPrincipal.require().branchFilter(PERMISSION, branchIds);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Search.Query search = Search.parse(q);
        Cursor.Key after =
                Cursor.decodeKey(Search.unwrap(search, cursor).cursor()).orElse(null);
        List<OrderRow> rows = repo.page(
                filter,
                supplierId,
                status,
                from,
                to,
                search,
                after == null ? null : after.at(),
                after == null ? null : after.id(),
                size + 1,
                PurchasingService.ownRecordsOnly());
        boolean more = rows.size() > size;
        List<OrderRow> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Search.wrap(
                        search,
                        0,
                        Cursor.encode(items.getLast().createdAt() + "|"
                                + items.getLast().id()))
                : null;
        return new OrderList(items.stream().map(x -> view(x, false)).toList(), next);
    }

    // ---- Helpers ---------------------------------------------------------------------------

    /** The order if the caller may see it: it is for a branch of their scope, else 404. */
    OrderRow visible(UUID id, boolean lock) {
        Principal principal = CurrentPrincipal.require();
        return repo.find(id, lock)
                .filter(o -> principal.may(PERMISSION, o.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    /** An order the caller may receive against: its branch and the receiving branch are both in their scope, else 404. */
    OrderRow receivable(UUID id, UUID receivingBranch) {
        Principal principal = CurrentPrincipal.require();
        return repo.find(id, true)
                .filter(o -> principal.may(PERMISSION, o.branchId()) && principal.may(PERMISSION, receivingBranch))
                .orElseThrow(ApiException::notFound);
    }

    private record Draft(LocalDate orderedOn, long total, List<NewLine> lines) {}

    private Draft plan(OrderRequest r) {
        List<FieldProblem> problems = new ArrayList<>();
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate ordered = r.orderedOn() == null ? today : r.orderedOn();
        if (ordered.isAfter(today)) {
            problems.add(new FieldProblem("ordered_on", "invalid", "An order cannot be dated in the future."));
        }
        if (r.expectedOn() != null && r.expectedOn().isBefore(ordered)) {
            problems.add(new FieldProblem("expected_on", "invalid", "The expected date is before the order date."));
        }
        var supplier = suppliers.supplier(r.supplierId());
        if (supplier.isEmpty()) {
            problems.add(new FieldProblem("supplier_id", "unknown_supplier", "No such supplier."));
        } else if (!supplier.get().active()) {
            problems.add(new FieldProblem(
                    "supplier_id", "inactive_supplier", "This supplier is switched off. Choose another one."));
        }
        List<NewLine> out = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < r.lines().size(); i++) {
            OrderLineRequest l = r.lines().get(i);
            Planned p = lines.plan("lines[" + i + "]", l.productId(), l.unitId(), l.qty(), l.costMinor(), problems);
            if (p == null) {
                continue;
            }
            total = Math.addExact(total, p.lineTotal());
            out.add(new NewLine(
                    UUID.randomUUID(),
                    i + 1,
                    p.product().id(),
                    p.baseQty(),
                    p.pack() == null ? null : p.pack().unitId(),
                    p.unitQty(),
                    p.pack() == null ? null : p.pack().factor(),
                    p.pack() == null ? null : p.enteredCost(),
                    p.costBase(),
                    p.lineTotal()));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        return new Draft(ordered, total, out);
    }

    /** The order for the caller; received and billed figures are on one order only, billed ones with the payables permission. */
    Order view(OrderRow o, boolean detail) {
        List<OrderLine> ls = List.of();
        List<ReceiptRef> receipts = null;
        if (detail) {
            boolean billed = CurrentPrincipal.require().may(PAYABLE, o.branchId());
            ls = repo.lines(o.id()).stream().map(l -> line(l, billed)).toList();
            receipts = repo.receipts(List.of(o.id())).getOrDefault(o.id(), List.of()).stream()
                    .map(x -> new ReceiptRef(x.id(), x.receiptNo(), x.receivedOn(), x.status(), x.totalMinor()))
                    .toList();
        }
        return new Order(
                o.id(),
                o.orderNo(),
                o.branchId(),
                o.supplierId(),
                o.supplierName(),
                o.orderedOn(),
                o.expectedOn(),
                o.currency(),
                o.totalMinor(),
                o.note(),
                o.status(),
                o.lineCount(),
                detail ? ls : null,
                receipts,
                o.createdAt(),
                o.createdBy(),
                o.updatedBy(),
                o.sentAt(),
                o.sentBy(),
                o.approvalId(),
                o.approvedBy(),
                o.cancelledAt(),
                o.cancelledBy(),
                o.cancelReason(),
                o.version());
    }

    private static OrderLine line(LineRow l, boolean billed) {
        return new OrderLine(
                l.id(),
                l.lineNo(),
                l.productId(),
                l.code(),
                l.description(),
                Quantities.format(l.qty()),
                l.unitId(),
                l.unit(),
                l.unitFactor() == null ? null : Quantities.format(l.unitFactor()),
                l.unitQty() == null ? null : Quantities.format(l.unitQty()),
                l.costMinor(),
                l.unitCostMinor(),
                l.lineTotalMinor(),
                Quantities.format(l.receivedQty()),
                l.receivedMinor(),
                billed ? Quantities.format(l.billedQty()) : null,
                billed ? l.billedMinor() : null,
                l.unitId() == null ? null : Quantities.format(l.receivedUnitQty()),
                l.baseUnit());
    }
}
