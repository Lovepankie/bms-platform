package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.approvals.Approvals;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.purchasing.internal.OrdersRepository.OrderRow;
import com.rincoltech.bms.retail.purchasing.internal.PurchaseLines.Numbered;
import com.rincoltech.bms.retail.purchasing.internal.PurchaseLines.Planned;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsApi.Receipt;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsApi.ReceiptLine;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsApi.ReceiptLineRequest;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsApi.ReceiptList;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsApi.ReceiptRequest;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsApi.ReleaseRequest;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsApi.VoidRequest;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsRepository.LineRow;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsRepository.NewLine;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsRepository.NewReceipt;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsRepository.Row;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.Movement;
import com.rincoltech.bms.retail.stock.StockLedger.Recorded;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods received (purchasing capability; ADR-033, issue #198; docs/specs/trade-capabilities.md sections 7.1 and 12.3). A
 * receipt is the plain restock's event for stock, cost and price, with the credit going to goods received not invoiced
 * instead of to cash, bank or trade creditors: purchase movements in base units, the product's cost and an optional new
 * selling price with a history row, inventory against the account (the pack cost remainder to purchase rounding), all in
 * one transaction. Against an order it follows the order's lines, partly, and may not exceed them by more than the
 * tenant's over-receipt tolerance (zero by default); with no order it is a blind receipt, which small wholesalers
 * do. The goods go into the branch or store the caller names, whatever branch the order was raised for.
 */
@Service
class ReceiptsService {

    static final String RECEIPT = "retail.goods_receipt";
    static final String RECEIPT_VOID = "retail.goods_receipt_void";
    static final String PERMISSION = OrdersService.PERMISSION;
    static final String RECEIPTS = "/api/v1/retail/goods-receipts";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final ReceiptsRepository repo;
    private final LandedCostRepository landed;
    private final OrdersRepository orderRepo;
    private final OrdersService orders;
    private final PurchasingRepository suppliers;
    private final PurchaseLines lines;
    private final PurchasingSettingsService settings;
    private final RetailCatalogue catalogue;
    private final StockLedger stock;
    private final RetailBooks books;
    private final RetailBranchContext branches;
    private final Idempotency idempotency;
    private final RetailCapabilities capabilities;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final Approvals approvals;

    ReceiptsService(
            ReceiptsRepository repo,
            LandedCostRepository landed,
            OrdersRepository orderRepo,
            OrdersService orders,
            PurchasingRepository suppliers,
            PurchaseLines lines,
            PurchasingSettingsService settings,
            RetailCatalogue catalogue,
            StockLedger stock,
            RetailBooks books,
            RetailBranchContext branches,
            Idempotency idempotency,
            RetailCapabilities capabilities,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            Approvals approvals) {
        this.repo = repo;
        this.landed = landed;
        this.orderRepo = orderRepo;
        this.orders = orders;
        this.suppliers = suppliers;
        this.lines = lines;
        this.settings = settings;
        this.catalogue = catalogue;
        this.stock = stock;
        this.books = books;
        this.branches = branches;
        this.idempotency = idempotency;
        this.capabilities = capabilities;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.approvals = approvals;
    }

    @Transactional
    Outcome<Receipt> create(String key, ReceiptRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(key, "POST", RECEIPTS, r, Receipt.class, () -> post(r));
    }

    /** One request line worked out against the order line it receives, if any. */
    private record Item(int index, OrdersRepository.LineRow orderLine, ReceiptLineRequest request, Planned planned) {}

    private Receipt post(ReceiptRequest r) {
        List<FieldProblem> problems = new ArrayList<>();
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate on = r.receivedOn() == null ? today : r.receivedOn();
        if (on.isAfter(today)) {
            problems.add(new FieldProblem("received_on", "invalid", "A receipt cannot be dated in the future."));
        }
        UUID branch = branches.resolveStocking(PERMISSION, r.branchId());
        // Lock order, as for a void: the supplier, then the order, then the products and balances.
        UUID lockSupplier = r.orderId() == null
                ? r.supplierId()
                : orderRepo.find(r.orderId(), false).map(OrderRow::supplierId).orElse(null);
        if (lockSupplier != null) {
            suppliers.lockSupplier(lockSupplier);
        }
        OrderRow order = null;
        Map<UUID, OrdersRepository.LineRow> orderLines = new HashMap<>();
        if (r.orderId() != null) {
            order = orders.receivable(r.orderId(), branch);
            if (order.status().equals("draft")) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "order_not_sent",
                        "Order not sent",
                        "This order is a draft. Send it before goods are received against it.");
            }
            if (order.status().equals("cancelled") || order.status().equals("received")) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "order_closed",
                        "Order closed",
                        "This order was cancelled or has been received in full.");
            }
            if (on.isBefore(order.orderedOn())) {
                problems.add(new FieldProblem(
                        "received_on",
                        "before_order",
                        "A receipt cannot be dated before its order, which is dated " + order.orderedOn() + "."));
            }
            if (r.supplierId() != null && !r.supplierId().equals(order.supplierId())) {
                problems.add(new FieldProblem("supplier_id", "invalid", "The supplier is the order's supplier."));
            }
            orderRepo.lines(order.id()).forEach(l -> orderLines.put(l.id(), l));
        }
        UUID supplierId = order != null ? order.supplierId() : r.supplierId();
        if (supplierId == null) {
            problems.add(new FieldProblem("supplier_id", "required", "A blind receipt names its supplier."));
        } else if (order == null) {
            var supplier = suppliers.supplier(supplierId);
            if (supplier.isEmpty()) {
                problems.add(new FieldProblem("supplier_id", "unknown_supplier", "No such supplier."));
            } else if (!supplier.get().active()) {
                problems.add(new FieldProblem(
                        "supplier_id", "inactive_supplier", "This supplier is switched off. Choose another one."));
            }
        }
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            ReceiptLineRequest l = r.lines().get(i);
            String field = "lines[" + i + "]";
            OrdersRepository.LineRow ol = null;
            UUID productId = l.productId();
            UUID unitId = l.unitId();
            Long cost = l.costMinor();
            if (order != null) {
                ol = l.orderLineId() == null ? null : orderLines.get(l.orderLineId());
                if (ol == null) {
                    problems.add(new FieldProblem(
                            field + ".order_line_id",
                            "unknown_order_line",
                            "Name a line of this order. Goods that are not on the order are a blind receipt."));
                    continue;
                }
                if (productId != null && !productId.equals(ol.productId())) {
                    problems.add(
                            new FieldProblem(field + ".product_id", "invalid", "Not the product of that order line."));
                    continue;
                }
                productId = ol.productId();
                if (unitId == null) {
                    unitId = ol.unitId();
                }
                if (cost == null) {
                    if (sameUnit(ol, unitId)) {
                        cost = ol.unitCostMinor() != null ? ol.unitCostMinor() : ol.costMinor();
                    } else {
                        problems.add(new FieldProblem(
                                field + ".cost_minor", "required", "Give the cost in the unit you are receiving."));
                        continue;
                    }
                }
            } else {
                if (l.orderLineId() != null) {
                    problems.add(new FieldProblem(
                            field + ".order_line_id", "invalid", "This receipt is not against an order."));
                    continue;
                }
                if (productId == null) {
                    problems.add(new FieldProblem(field + ".product_id", "required", "Name the product."));
                    continue;
                }
                if (cost == null) {
                    problems.add(new FieldProblem(field + ".cost_minor", "required", "Give the cost."));
                    continue;
                }
            }
            Planned p = lines.plan(field, productId, unitId, l.qty(), cost, problems, ol != null, l.packSize());
            if (p != null) {
                items.add(new Item(i, ol, l, p));
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        if (order != null) {
            overReceipt(order, items, orderLines);
        }
        limitUnauthorised(order, items);
        PurchaseLines.requireUseFromNowOn(items.stream().anyMatch(x -> usesFromNowOn(x.request())));
        new TreeSet<>(items.stream().map(x -> x.planned().product().id()).toList()).forEach(catalogue::lock);

        UUID id = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        Numbered no = lines.next("GR", "retail_goods_receipt_no", branch);
        List<NewLine> newLines = new ArrayList<>();
        // D23, issue #554: the lines whose size becomes the pack's usual size, by the line's own id.
        Set<UUID> fromNowOn = new HashSet<>();
        List<Movement> movements = new ArrayList<>();
        long total = 0;
        long stocked = 0;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            Planned p = item.planned();
            UUID lineId = UUID.randomUUID();
            total = Math.addExact(total, p.lineTotal());
            stocked = Math.addExact(stocked, p.stocked());
            newLines.add(new NewLine(
                    lineId,
                    i + 1,
                    item.orderLine() == null ? null : item.orderLine().id(),
                    p.product().id(),
                    p.baseQty(),
                    p.pack() == null ? null : p.pack().unitId(),
                    p.unitQty(),
                    p.pack() == null ? null : p.pack().factor(),
                    p.enteredCost(),
                    p.costBase(),
                    item.request().sellMinor(),
                    p.lineTotal(),
                    p.stocked()));
            movements.add(new Movement(
                    branch, p.product().id(), "purchase", p.baseQty(), p.costBase(), RECEIPT, id, lineId, null, null));
            if (usesFromNowOn(item.request())) {
                fromNowOn.add(lineId);
            }
        }
        // Lines are applied in request order, so when two lines name one product the later line's cost is the product's
        // cost afterwards (latest receipt wins, ADR-020 decision 5), each change with its own history row.
        // ADR-055: the selling price applies in every shop; only someone who may change prices sets it from a receipt.
        if (newLines.stream()
                        .anyMatch(x -> x.sellMinor() != null
                                && catalogue
                                        .find(x.productId())
                                        .map(p -> p.sellMinor() != x.sellMinor())
                                        .orElse(false))
                && !CurrentPrincipal.require().hasPermission("retail.price.edit")) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Setting the selling price needs the permission to change prices. Leave the selling price empty.");
        }
        Map<UUID, String> sizesKept = new HashMap<>();
        for (NewLine l : newLines) {
            catalogue.applyPurchasePrices(l.productId(), l.costMinor(), l.sellMinor(), id);
            // D23, issue #554: under the product lock taken above, before any balance row (ADR-079 lock order).
            if (fromNowOn.contains(l.id())
                    && !catalogue.useBuyingSize(l.productId(), l.unitId(), l.unitFactor(), RECEIPT, id)) {
                sizesKept.put(l.id(), PurchaseLines.sizeKeptNote(l.unitFactor()));
            }
        }
        stock.record(on, movements);
        // Goods received not invoiced is owed as entered; inventory carries the movements' value; the difference, which
        // is zero unless a pack cost does not divide exactly, is purchase rounding, as in a plain restock.
        long rounding = total - stocked;
        List<Leg> legs = new ArrayList<>(
                List.of(Leg.debit("inventory", stocked), Leg.credit("goods_received_not_invoiced", total)));
        if (rounding > 0) {
            legs.add(Leg.debit("purchase_rounding", rounding));
        } else if (rounding < 0) {
            legs.add(Leg.credit("purchase_rounding", -rounding));
        }
        UUID entry = books.post(new Posting(
                        branch, on, no.no(), "Goods received " + no.no(), RECEIPT, id, RECEIPT + ":" + id, legs))
                .map(e -> e.entryId())
                .orElse(null);
        repo.insert(new NewReceipt(
                id,
                no.no(),
                no.seq(),
                branch,
                supplierId,
                order == null ? null : order.id(),
                on,
                tenant.profile().currency(),
                total,
                PurchasingService.blankToNull(r.deliveryRef()),
                PurchasingService.blankToNull(r.note()),
                entry,
                by));
        newLines.forEach(l -> repo.insertLine(id, l));
        if (Boolean.TRUE.equals(r.holdForCosting())) {
            repo.hold(id, by);
        }
        if (order != null) {
            orders.refreshStatus(order.id());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("receipt_no", no.no());
        after.put("supplier_id", supplierId);
        if (order != null) {
            after.put("order_no", order.orderNo());
        }
        after.put("total_minor", total);
        after.put("lines", newLines.size());
        if (Boolean.TRUE.equals(r.holdForCosting())) {
            after.put("held_for_costing", true);
        }
        audit.record(AuditLog.Entry.created("retail.goods_receipt.created", RECEIPT, id, branch, after));
        Receipt made = view(repo.find(id, false).orElseThrow(), true);
        if (sizesKept.isEmpty() || made.lines() == null) {
            return made;
        }
        // Issue #624: the line whose "use from now on" was left out says so, so nobody is told the opposite.
        return made.withLines(made.lines().stream()
                .map(l -> sizesKept.containsKey(l.id()) ? l.withSizeKept(sizesKept.get(l.id())) : l)
                .toList());
    }

    /** D23, issue #554: the line asks for its size to become the pack's usual size. */
    private static boolean usesFromNowOn(ReceiptLineRequest l) {
        return Boolean.TRUE.equals(l.useFromNowOn()) && l.packSize() != null;
    }

    /** True when the unit being received is the unit the line was ordered in (naming the base unit is the base unit). */
    private boolean sameUnit(OrdersRepository.LineRow ol, UUID unitId) {
        if (ol.unitId() != null) {
            return ol.unitId().equals(unitId);
        }
        return unitId == null
                || catalogue
                        .find(ol.productId())
                        .map(p -> p.unitId().equals(unitId))
                        .orElse(false);
    }

    /**
     * While purchase orders ask for approval (retail_purchase_order, ADR-091), goods cannot be received past what a second
     * person authorised, or the threshold would limit nothing: a receipt with no order is refused when it is worth the
     * threshold or more (raise an order, which a second person approves), and a receipt against an order is refused when the
     * value received on it, with this receipt, would pass the order's value plus the over-receipt tolerance (a price above the
     * order's is as much an over-receipt as a quantity above it). With the approval off neither limit applies; on with no amount,
     * every delivery needs an order.
     */
    private void limitUnauthorised(OrderRow order, List<Item> items) {
        Approvals.Rule rule = approvals.rule(PurchaseOrderAction.TYPE);
        if (!rule.on()) {
            return;
        }
        long threshold = rule.thresholdMinor();
        long total = items.stream().mapToLong(x -> x.planned().lineTotal()).reduce(0, Math::addExact);
        if (order == null) {
            if (total >= threshold) {
                throw ApiException.rule(
                        "blind_receipt_over_limit",
                        "A delivery worth " + total
                                + " or more needs an order, which a second person approves. The limit is " + threshold
                                + ".");
            }
            return;
        }
        int bp = settings.current().overReceiptBp();
        long limit = order.totalMinor() + Math.multiplyExact(order.totalMinor(), (long) bp) / 10000;
        long after = Math.addExact(repo.receivedValue(order.id()), total);
        if (after > limit) {
            throw ApiException.rule(
                    "receipt_value_exceeds_order",
                    "With this delivery " + after + " would have been received on " + order.orderNo()
                            + ", which was ordered for "
                            + order.totalMinor()
                            + (limit > order.totalMinor() ? " (at most " + limit + " with the allowed excess)" : "")
                            + ". Change the prices, or raise and approve a new order.");
        }
    }

    /**
     * The quantity an order line may receive in all: what was ordered plus the tenant's over-receipt tolerance. Under the
     * limit is always allowed (a partial receipt); over it is refused. Base units, exact.
     */
    private void overReceipt(OrderRow order, List<Item> items, Map<UUID, OrdersRepository.LineRow> orderLines) {
        int bp = settings.current().overReceiptBp();
        Map<UUID, BigDecimal> already = repo.receivedByOrderLine(order.id());
        Map<UUID, BigDecimal> now = new HashMap<>();
        items.forEach(x -> now.merge(x.orderLine().id(), inOrderUnit(x), BigDecimal::add));
        for (Map.Entry<UUID, BigDecimal> e : now.entrySet()) {
            OrdersRepository.LineRow ol = orderLines.get(e.getKey());
            BigDecimal had = already.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal ordered = OrdersService.orderedInUnit(ol);
            BigDecimal limit = ordered.multiply(BigDecimal.valueOf(10000L + bp)).movePointLeft(4);
            if (had.add(e.getValue()).compareTo(limit) > 0) {
                BigDecimal room = limit.subtract(had).max(BigDecimal.ZERO);
                throw ApiException.rule(
                        "receipt_exceeds_order",
                        "Line " + ol.lineNo() + " of " + order.orderNo() + " ordered " + Quantities.format(ordered)
                                + " and " + Quantities.format(had) + " has been received, so at most "
                                + room.setScale(Quantities.SCALE, java.math.RoundingMode.DOWN)
                                        .toPlainString()
                                + " more can be received.");
            }
        }
    }

    /**
     * D23, issue #554: what a line receives, in its order line's own unit, as {@code OrdersRepository.RECEIVED_IN_ORDER_UNIT}
     * counts it: packs of the order's unit count as packs whatever each held this time.
     */
    private static BigDecimal inOrderUnit(Item x) {
        OrdersRepository.LineRow ol = x.orderLine();
        Planned p = x.planned();
        if (ol.unitId() == null) {
            return p.baseQty();
        }
        if (p.pack() != null && p.pack().unitId().equals(ol.unitId())) {
            return p.unitQty();
        }
        return p.baseQty().divide(ol.unitFactor(), Quantities.SCALE, java.math.RoundingMode.HALF_UP);
    }

    // ---- Void ------------------------------------------------------------------------------

    @Transactional
    Outcome<Receipt> voidReceipt(String key, UUID id, VoidRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(key, "POST", RECEIPTS + "/" + id + "/void", r, Receipt.class, () -> voidNow(id, r));
    }

    /**
     * Undoes a receipt as a whole, once: the stock it put in is taken out again (refused when it has been sold or moved
     * since), its entry is reversed today, and the order it was against goes back to what is left. Refused while a supplier
     * invoice that stands bills it. The supplier is locked first, then the order, then the receipt.
     */
    private Receipt voidNow(UUID id, VoidRequest request) {
        Principal principal = CurrentPrincipal.require();
        String reason = request.reason().trim();
        if (reason.length() < OrdersService.MIN_REASON) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "reason", "invalid", "Give a reason of at least " + OrdersService.MIN_REASON + " characters.")));
        }
        Row seen = repo.find(id, false)
                .filter(x -> principal.may(PERMISSION, x.branchId()))
                .orElseThrow(ApiException::notFound);
        suppliers.lockSupplier(seen.supplierId());
        if (seen.orderId() != null) {
            orderRepo.find(seen.orderId(), true);
        }
        Row r = repo.find(id, true).orElseThrow(ApiException::notFound);
        if (r.status().equals("voided")) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "receipt_voided", "Receipt voided", "This receipt has been voided already.");
        }
        if (repo.billed(id)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "receipt_billed",
                    "Receipt billed",
                    "A supplier invoice bills this receipt. Void the invoice first.");
        }
        if (landed.costed(id)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "receipt_costed",
                    "Receipt costed",
                    "A landed cost has been added to this receipt. Void the landed cost first.");
        }
        List<Recorded> moved = stock.bySource(RECEIPT, id);
        Map<List<UUID>, BigDecimal> net = new TreeMap<>(
                Comparator.<List<UUID>, UUID>comparing(k -> k.get(0)).thenComparing(k -> k.get(1)));
        for (Recorded m : moved) {
            net.merge(List.of(m.branchId(), m.productId()), m.qty().negate(), BigDecimal::add);
        }
        for (Map.Entry<List<UUID>, BigDecimal> e : net.entrySet()) {
            BigDecimal onHand = stock.lock(e.getKey().get(0), e.getKey().get(1));
            if (onHand.add(e.getValue()).signum() < 0) {
                throw ApiException.rule(
                        "receipt_stock_moved",
                        "The goods this receipt put in are no longer all in stock, so it cannot be voided.");
            }
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        if (!moved.isEmpty()) {
            stock.record(
                    today,
                    moved.stream()
                            .map(m -> new Movement(
                                    m.branchId(),
                                    m.productId(),
                                    "adjustment",
                                    m.qty().negate(),
                                    m.unitCostMinor(),
                                    RECEIPT_VOID,
                                    id,
                                    m.sourceLineId(),
                                    m.id(),
                                    reason))
                            .toList());
        }
        UUID reversal = null;
        if (r.journalEntryId() != null) {
            reversal = books.reverse(r.journalEntryId(), today, r.receiptNo(), RECEIPT_VOID + ":" + id)
                    .entryId();
        }
        Instant now = clock.now();
        if (repo.markVoided(id, principal.userId(), today, now, reason, reversal) == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "receipt_voided", "Receipt voided", "This receipt has been voided already.");
        }
        if (r.orderId() != null) {
            orders.refreshStatus(r.orderId());
        }
        audit.record(new AuditLog.Entry(
                "retail.goods_receipt.voided",
                RECEIPT,
                id,
                r.branchId(),
                Map.of("status", "posted"),
                Map.of("status", "voided", "reason", reason, "receipt_no", r.receiptNo())));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    // ---- Hold ------------------------------------------------------------------------------

    @Transactional
    Outcome<Receipt> releaseHold(String key, UUID id, ReleaseRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(
                key, "POST", RECEIPTS + "/" + id + "/release-hold", r, Receipt.class, () -> releaseNow(id, r));
    }

    /** Releases a receipt held as received and not costed without a landed cost, with a reason: no landed cost will come. Needs retail.payable.manage in the receipt's branch. */
    private Receipt releaseNow(UUID id, ReleaseRequest request) {
        Principal principal = CurrentPrincipal.require();
        String reason = request.reason().trim();
        if (reason.length() < OrdersService.MIN_REASON) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "reason", "invalid", "Give a reason of at least " + OrdersService.MIN_REASON + " characters.")));
        }
        // Lifting the hold is the administrator's, not the receiving clerk's, or the hold would stop nothing.
        repo.find(id, false)
                .filter(x -> principal.may(OrdersService.PAYABLE, x.branchId()))
                .orElseThrow(ApiException::notFound);
        // Under the receipt's lock, as a void and a landed cost take it, so the hold and the status cannot change
        // meanwhile.
        Row r = repo.find(id, true).orElseThrow(ApiException::notFound);
        if (r.status().equals("voided")) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "receipt_voided", "Receipt voided", "This receipt has been voided already.");
        }
        if (repo.release(id, principal.userId(), "waived", reason, null) == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "not_held",
                    "Not held",
                    "This receipt is not held as received and not costed.");
        }
        audit.record(new AuditLog.Entry(
                "retail.goods_receipt.hold_released",
                RECEIPT,
                id,
                r.branchId(),
                Map.of("held_for_costing", true),
                Map.of("held_for_costing", false, "reason", reason, "receipt_no", r.receiptNo())));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    // ---- Reads -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    Receipt get(UUID id) {
        capabilities.require(RetailCapabilities.PURCHASING);
        Principal principal = CurrentPrincipal.require();
        UUID own = PurchasingService.ownRecordsOnly();
        return repo.find(id, false)
                .filter(x -> principal.may(PERMISSION, x.branchId()))
                .filter(x -> own == null || own.equals(x.createdBy()))
                .map(x -> view(x, true))
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    ReceiptList list(
            List<UUID> branchIds,
            UUID supplierId,
            UUID orderId,
            String status,
            LocalDate from,
            LocalDate to,
            String q,
            Integer limit,
            String cursor) {
        capabilities.require(RetailCapabilities.PURCHASING);
        if (status != null && !status.equals("posted") && !status.equals("voided")) {
            throw ApiException.validation(
                    List.of(new FieldProblem("status", "invalid", "status is posted or voided.")));
        }
        List<UUID> filter = CurrentPrincipal.require().branchFilter(PERMISSION, branchIds);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Search.Query search = Search.parse(q);
        Cursor.Key after =
                Cursor.decodeKey(Search.unwrap(search, cursor).cursor()).orElse(null);
        List<Row> rows = repo.page(
                filter,
                supplierId,
                orderId,
                status,
                from,
                to,
                search,
                after == null ? null : after.at(),
                after == null ? null : after.id(),
                size + 1,
                PurchasingService.ownRecordsOnly());
        boolean more = rows.size() > size;
        List<Row> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Search.wrap(
                        search,
                        0,
                        Cursor.encode(items.getLast().createdAt() + "|"
                                + items.getLast().id()))
                : null;
        return new ReceiptList(items.stream().map(x -> view(x, false)).toList(), next);
    }

    Receipt view(Row r, boolean detail) {
        List<ReceiptLine> ls = null;
        if (detail) {
            boolean billed = CurrentPrincipal.require().may(OrdersService.PAYABLE, r.branchId());
            boolean costs = CurrentPrincipal.require().may("retail.profit.read", r.branchId());
            ls = repo.lines(List.of(r.id())).getOrDefault(r.id(), List.of()).stream()
                    .map(l -> line(l, billed, costs))
                    .toList();
        }
        return new Receipt(
                r.id(),
                r.receiptNo(),
                r.branchId(),
                r.supplierId(),
                r.supplierName(),
                r.orderId(),
                r.orderNo(),
                r.receivedOn(),
                r.currency(),
                r.totalMinor(),
                r.deliveryRef(),
                r.note(),
                r.status(),
                r.lineCount(),
                ls,
                r.createdAt(),
                r.createdBy(),
                r.voidedAt(),
                r.voidedOn(),
                r.voidedBy(),
                r.voidReason(),
                r.held(),
                r.version());
    }

    private static ReceiptLine line(LineRow l, boolean billed, boolean costs) {
        return new ReceiptLine(
                l.id(),
                l.lineNo(),
                l.orderLineId(),
                l.productId(),
                l.code(),
                l.description(),
                Quantities.format(l.qty()),
                l.unitId(),
                l.unit(),
                l.unitFactor() == null ? null : Quantities.format(l.unitFactor()),
                l.unitQty() == null ? null : Quantities.format(l.unitQty()),
                l.enteredCostMinor(),
                l.costMinor(),
                l.sellMinor(),
                l.lineTotalMinor(),
                billed ? Quantities.format(l.billedQty()) : null,
                costs ? l.landedMinor() : null,
                null,
                null);
    }
}
