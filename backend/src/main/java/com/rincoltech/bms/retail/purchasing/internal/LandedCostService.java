package com.rincoltech.bms.retail.purchasing.internal;

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
import com.rincoltech.bms.retail.purchasing.internal.BillsRepository.NewBill;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.Charge;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.ChargeRequest;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.LandedCost;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.LandedCostList;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.LandedCostRequest;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.Line;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.LineRequest;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.Preview;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostApi.VoidRequest;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostRepository.LineRow;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostRepository.NewDoc;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostRepository.NewLine;
import com.rincoltech.bms.retail.purchasing.internal.LandedCostRepository.Row;
import com.rincoltech.bms.retail.purchasing.internal.LotRepository.Lot;
import com.rincoltech.bms.retail.purchasing.internal.PurchaseLines.Numbered;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptCosting.Effect;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import com.rincoltech.bms.retail.stock.StockLedger;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Landed cost (purchasing capability; ADR-033, issue #199 amendment; docs/specs/trade-capabilities.md sections 7.2 and
 * 12.3). Freight, duty, clearing and other charges of one payee are spread over received lines, by value, quantity, weight
 * or amounts typed per line, in exact integers ({@link Allocation}). Each line's share goes to inventory for the units still
 * on hand and to cost of goods sold for the units already sold, used or moved; the product's cost per base unit rises by the
 * charge per unit only while the receipt is still the product's latest cost-setting event. The payee's invoice is a supplier
 * bill of kind {@code landed_cost}, so it is paid, reversed and aged by the supplier payment machinery. One entry per
 * receiving branch: debit inventory and cost of goods sold, credit trade creditors in the document's branch and
 * inter-branch clearing in the others (as a supplier invoice does). Voiding reverses every entry and puts the product's cost
 * back where it is still what the document made it.
 */
@Service
class LandedCostService {

    static final String LANDED = "retail.landed_cost";
    static final String LANDED_VOID = "retail.landed_cost_void";
    static final String PERMISSION = OrdersService.PAYABLE;
    static final String PATH = "/api/v1/retail/landed-costs";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    static final String PROFIT_READ = "retail.profit.read";

    private final LandedCostRepository repo;
    private final LotRepository lotRepo;
    private final BillsRepository billRepo;
    private final ReceiptsRepository receiptRepo;
    private final PurchasingRepository suppliers;
    private final PurchaseLines lines;
    private final ReceiptCosting costing;
    private final RetailCatalogue catalogue;
    private final StockLedger stock;
    private final RetailBooks books;
    private final Idempotency idempotency;
    private final RetailCapabilities capabilities;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    LandedCostService(
            LandedCostRepository repo,
            LotRepository lotRepo,
            BillsRepository billRepo,
            ReceiptsRepository receiptRepo,
            PurchasingRepository suppliers,
            PurchaseLines lines,
            ReceiptCosting costing,
            RetailCatalogue catalogue,
            StockLedger stock,
            RetailBooks books,
            Idempotency idempotency,
            RetailCapabilities capabilities,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit) {
        this.repo = repo;
        this.lotRepo = lotRepo;
        this.billRepo = billRepo;
        this.receiptRepo = receiptRepo;
        this.suppliers = suppliers;
        this.lines = lines;
        this.costing = costing;
        this.catalogue = catalogue;
        this.stock = stock;
        this.books = books;
        this.idempotency = idempotency;
        this.capabilities = capabilities;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
    }

    /** One request line worked out: the lot, what the spread used and the share. */
    private record Planned(Lot lot, LineRequest request, BigDecimal basis, BigDecimal weight, long share) {}

    private record Plan(String method, long total, LocalDate costDate, List<Planned> lines) {}

    // ---- Writes ----------------------------------------------------------------------------

    @Transactional
    Outcome<LandedCost> create(String key, LandedCostRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(key, "POST", PATH, r, LandedCost.class, () -> post(r));
    }

    private LandedCost post(LandedCostRequest r) {
        if (!suppliers.lockSupplier(r.supplierId())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("supplier_id", "unknown_supplier", "No such supplier.")));
        }
        // Lock the received lines' receipts in id order, as a void of a receipt does, so a receipt cannot be voided
        // under us.
        Plan plan = plan(r, true);
        if (billRepo.refExists(r.supplierId(), r.supplierRef().trim())) {
            throw BillsService.duplicate();
        }
        Principal principal = CurrentPrincipal.require();
        UUID id = UUID.randomUUID();
        UUID billId = UUID.randomUUID();
        UUID by = principal.userId();
        String ref = r.supplierRef().trim();
        List<Planned> ps = plan.lines();
        UUID home = ps.getFirst().lot().branchId();

        // Lock the products and then the stock balances of every branch that holds them, in id order, before reading
        // what is on hand.
        new TreeSet<>(ps.stream().map(p -> p.lot().productId()).toList()).forEach(catalogue::lock);
        Map<UUID, Set<UUID>> locked =
                costing.lockStock(ps.stream().map(Planned::lot).toList());

        Numbered no = lines.next("LC", "retail_landed_cost_no", home);
        Numbered billNo = lines.next("SB", "retail_supplier_bill_no", home);

        // What each share does: the split between inventory and cost of goods sold, and the product's cost.
        List<NewLine> newLines = new ArrayList<>();
        Map<UUID, long[]> byBranch = new TreeMap<>();
        for (int i = 0; i < ps.size(); i++) {
            Planned p = ps.get(i);
            Effect e = costing.apply(p.lot(), p.share(), true, id, no.no(), plan.costDate(), LANDED, locked);
            UUID lineId = UUID.randomUUID();
            costing.recordSpread(lineId, p.lot(), e);
            newLines.add(new NewLine(
                    lineId,
                    i + 1,
                    p.lot().receiptLineId(),
                    p.lot().branchId(),
                    p.lot().productId(),
                    p.basis(),
                    p.weight(),
                    p.share(),
                    p.lot().qty(),
                    e.onHand(),
                    e.inventory(),
                    e.gone(),
                    e.costBefore(),
                    e.costAfter(),
                    e.applied(),
                    e.note()));
            for (Map.Entry<UUID, Long> part : e.byBranch().entrySet()) {
                long[] sums = byBranch.computeIfAbsent(part.getKey(), k -> new long[2]);
                sums[0] = Math.addExact(sums[0], part.getValue());
            }
            long[] receiving = byBranch.computeIfAbsent(p.lot().branchId(), k -> new long[2]);
            receiving[1] = Math.addExact(receiving[1], e.gone());
        }
        byBranch.computeIfAbsent(home, k -> new long[2]);

        long elsewhere = 0;
        for (Map.Entry<UUID, long[]> e : byBranch.entrySet()) {
            if (!e.getKey().equals(home)) {
                elsewhere = Math.addExact(elsewhere, Math.addExact(e.getValue()[0], e.getValue()[1]));
            }
        }
        Map<UUID, UUID> entries = new LinkedHashMap<>();
        for (Map.Entry<UUID, long[]> e : byBranch.entrySet()) {
            long inventory = e.getValue()[0];
            long cogs = e.getValue()[1];
            List<Leg> legs = new ArrayList<>();
            legs.add(Leg.debit("inventory", inventory));
            legs.add(Leg.debit("cost_of_goods_sold", cogs));
            if (e.getKey().equals(home)) {
                legs.add(Leg.debit("interbranch_clearing", elsewhere));
                legs.add(Leg.credit("trade_creditors", plan.total()).withSubledger("retail.supplier", r.supplierId()));
            } else {
                legs.add(Leg.credit("interbranch_clearing", Math.addExact(inventory, cogs)));
            }
            books.post(new Posting(
                            e.getKey(),
                            plan.costDate(),
                            no.no(),
                            "Landed cost " + no.no() + " " + ref,
                            LANDED,
                            id,
                            LANDED + ":" + id + ":" + e.getKey(),
                            legs))
                    .ifPresent(posted -> entries.put(e.getKey(), posted.entryId()));
        }
        // The payee's invoice is a supplier bill of kind landed_cost: it clears nothing from goods received not
        // invoiced.
        try {
            billRepo.insert(new NewBill(
                    billId,
                    billNo.no(),
                    billNo.seq(),
                    home,
                    r.supplierId(),
                    ref,
                    plan.costDate(),
                    r.dueDate(),
                    tenant.profile().currency(),
                    plan.total(),
                    plan.total(),
                    0,
                    null,
                    PurchasingService.blankToNull(r.note()),
                    by,
                    "landed_cost"));
        } catch (DuplicateKeyException e) {
            throw BillsService.duplicate();
        }
        repo.insert(new NewDoc(
                id,
                no.no(),
                no.seq(),
                home,
                r.supplierId(),
                billId,
                r.goodsBillId(),
                plan.method(),
                plan.costDate(),
                tenant.profile().currency(),
                plan.total(),
                PurchasingService.blankToNull(r.note()),
                by));
        int n = 1;
        for (ChargeRequest c : r.charges()) {
            repo.insertCharge(id, n++, c.kind(), PurchasingService.blankToNull(c.description()), c.amountMinor());
        }
        newLines.forEach(l -> repo.insertLine(id, l));
        entries.forEach((branch, entry) -> repo.insertEntry(id, branch, entry));
        // A receipt held as received and not costed is costed now.
        Set<UUID> receipts =
                new TreeSet<>(ps.stream().map(p -> p.lot().receiptId()).toList());
        List<String> released = new ArrayList<>();
        for (UUID receipt : receipts) {
            if (receiptRepo.release(receipt, by, "landed_cost", null, id) > 0) {
                released.add(receipt.toString());
            }
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("landed_no", no.no());
        after.put("bill_no", billNo.no());
        after.put("supplier_ref", ref);
        after.put("supplier_id", r.supplierId());
        after.put("method", plan.method());
        after.put("total_minor", plan.total());
        after.put("lines", ps.size());
        if (!released.isEmpty()) {
            after.put("holds_released", released.size());
        }
        audit.record(AuditLog.Entry.created("retail.landed_cost.created", LANDED, id, home, after));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    /** How a landed cost would be spread and what it would do, without recording anything: the screen's review step. */
    @Transactional(readOnly = true)
    Preview preview(LandedCostRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        Plan plan = plan(r, false);
        Principal principal = CurrentPrincipal.require();
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < plan.lines().size(); i++) {
            Planned p = plan.lines().get(i);
            Effect e = costing.estimate(p.lot(), p.share(), true, null);
            boolean costs = principal.may(PROFIT_READ, p.lot().branchId());
            out.add(new Line(
                    i + 1,
                    p.lot().receiptLineId(),
                    p.lot().receiptId(),
                    p.lot().receiptNo(),
                    p.lot().branchId(),
                    p.lot().productId(),
                    p.lot().code(),
                    p.lot().description(),
                    Quantities.format(p.lot().qty()),
                    costs ? plain(p.basis()) : null,
                    p.weight() == null ? null : Quantities.format(p.weight()),
                    costs ? p.share() : null,
                    costs ? Quantities.format(e.onHand()) : null,
                    costs ? e.inventory() : null,
                    costs ? e.gone() : null,
                    costs ? e.costBefore() : null,
                    costs ? e.costAfter() : null,
                    e.applied(),
                    e.note()));
        }
        long typed = r.charges().stream().mapToLong(ChargeRequest::amountMinor).sum();
        return new Preview(plan.total(), typed != r.totalMinor(), plan.method(), out);
    }

    /**
     * Works the request out against the received lines as they stand and spreads the total; writes nothing. The lines are
     * put in one canonical order (receipt number, then line number), so the result does not depend on the order they were typed.
     *
     * @param lock lock the receipts of the lines, in id order, so none is voided while the document is posted
     */
    private Plan plan(LandedCostRequest r, boolean lock) {
        List<FieldProblem> problems = new ArrayList<>();
        Principal principal = CurrentPrincipal.require();
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate costDate = r.costDate() == null ? today : r.costDate();
        String method = r.method() == null ? "value" : r.method();
        if (costDate.isAfter(today)) {
            problems.add(new FieldProblem("cost_date", "invalid", "A landed cost cannot be dated in the future."));
        }
        if (r.dueDate().isBefore(costDate)) {
            problems.add(new FieldProblem("due_date", "invalid", "The due date is before the date of the charge."));
        }
        var payee = suppliers.supplier(r.supplierId());
        if (payee.isEmpty()) {
            problems.add(new FieldProblem("supplier_id", "unknown_supplier", "No such supplier."));
        } else if (!payee.get().active()) {
            problems.add(new FieldProblem("supplier_id", "inactive_supplier", "This supplier is switched off."));
        }
        long total = 0;
        for (ChargeRequest c : r.charges()) {
            total = Math.addExact(total, c.amountMinor());
        }
        if (total > RetailCatalogue.MAX_AMOUNT_MINOR) {
            throw ApiException.rule("amount_out_of_range", "The charges come to more than can be recorded.");
        }
        Set<UUID> seen = new HashSet<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            LineRequest l = r.lines().get(i);
            if (!seen.add(l.receiptLineId())) {
                problems.add(new FieldProblem(
                        "lines[" + i + "].receipt_line_id", "duplicate", "Name each received line once."));
            } else {
                ids.add(l.receiptLineId());
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        if (total != r.totalMinor()) {
            throw ApiException.rule(
                    "landed_total_mismatch",
                    "The charges come to " + total + " but the invoice total is " + r.totalMinor()
                            + ". Check the charges against the paper invoice.");
        }
        if (lock && r.goodsBillId() != null) {
            // The goods invoice first, then the receipts, as a credit note takes them, so the two cannot deadlock.
            billRepo.lock(r.goodsBillId());
        }
        Map<UUID, Lot> lots = lotRepo.lots(ids);
        if (lock) {
            new TreeSet<>(lots.values().stream().map(Lot::receiptId).toList())
                    .forEach(receipt -> receiptRepo.find(receipt, true));
            lots = lotRepo.lots(ids);
        }
        List<Planned> out = new ArrayList<>();
        Map<UUID, Boolean> billed = r.goodsBillId() == null ? Map.of() : repo.billedBy(r.goodsBillId(), ids);
        if (r.goodsBillId() != null) {
            var goods = billRepo.find(r.goodsBillId());
            if (goods.isEmpty()
                    || !goods.get().status().equals("posted")
                    || !goods.get().kind().equals("goods")
                    || !principal.may(PERMISSION, goods.get().branchId())) {
                problems.add(new FieldProblem(
                        "goods_bill_id", "unknown_bill", "No such supplier invoice for goods that still stands."));
            }
        }
        long manual = 0;
        for (int i = 0; i < r.lines().size(); i++) {
            LineRequest l = r.lines().get(i);
            String field = "lines[" + i + "]";
            Lot lot = lots.get(l.receiptLineId());
            if (lot == null || !lot.status().equals("posted") || !principal.may(PERMISSION, lot.branchId())) {
                problems.add(new FieldProblem(
                        field + ".receipt_line_id",
                        "unknown_receipt_line",
                        "No such received line that is still standing."));
                continue;
            }
            if (costDate.isBefore(lot.receivedOn())) {
                problems.add(new FieldProblem(
                        "cost_date",
                        "cost_before_receipt",
                        "The charge is dated before " + lot.receiptNo() + " was received on " + lot.receivedOn()
                                + "."));
                continue;
            }
            if (lot.returnedQty().signum() > 0) {
                throw ApiException.rule(
                        "line_returned",
                        "Part of " + lot.receiptNo() + " line " + lot.lineNo()
                                + " has been sent back to the supplier. Void the credit note before adding a landed cost to it.");
            }
            if (r.goodsBillId() != null && !billed.getOrDefault(lot.receiptLineId(), false)) {
                problems.add(new FieldProblem(
                        field + ".receipt_line_id",
                        "not_on_bill",
                        lot.receiptNo() + " line " + lot.lineNo() + " is not billed by that supplier invoice."));
                continue;
            }
            if (method.equals("weight") && l.weight() == null) {
                problems.add(new FieldProblem(field + ".weight", "required", "Give the line's weight."));
                continue;
            }
            if (method.equals("weight") && !Quantities.exact(l.weight())) {
                problems.add(new FieldProblem(field + ".weight", "invalid", "At most three decimal places."));
                continue;
            }
            if (method.equals("manual")) {
                if (l.amountMinor() == null) {
                    problems.add(new FieldProblem(field + ".amount_minor", "required", "Give the line's share."));
                    continue;
                }
                manual = Math.addExact(manual, l.amountMinor());
            }
            out.add(new Planned(lot, l, null, null, 0));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        out.sort(Comparator.comparing((Planned p) -> p.lot().receiptNo())
                .thenComparingInt(p -> p.lot().lineNo()));
        long[] shares;
        List<Planned> done = new ArrayList<>();
        if (method.equals("manual")) {
            if (manual != total) {
                throw ApiException.rule(
                        "allocation_total_mismatch",
                        "The shares come to " + manual + " but the charges come to " + total
                                + ". The shares must add up to the charges.");
            }
            shares = out.stream().mapToLong(p -> p.request().amountMinor()).toArray();
        } else {
            long[] basis = new long[out.size()];
            for (int i = 0; i < basis.length; i++) {
                Planned p = out.get(i);
                basis[i] = switch (method) {
                    case "quantity" -> thousandths(p.lot().qty());
                    case "weight" -> thousandths(p.request().weight());
                    default -> p.lot().stockedMinor();
                };
            }
            try {
                shares = Allocation.spread(total, basis);
            } catch (IllegalArgumentException e) {
                throw ApiException.rule(
                        "allocation_basis_zero",
                        "The lines have nothing to spread the charges over: every line was received at no cost. Spread them"
                                + " by quantity or weight, or type each line's share.");
            }
        }
        for (int i = 0; i < out.size(); i++) {
            Planned p = out.get(i);
            BigDecimal basis = switch (method) {
                case "quantity" -> p.lot().qty();
                case "weight" -> p.request().weight();
                case "manual" -> BigDecimal.valueOf(p.request().amountMinor());
                default -> BigDecimal.valueOf(p.lot().stockedMinor());
            };
            BigDecimal weight = method.equals("weight") ? p.request().weight() : null;
            done.add(new Planned(p.lot(), p.request(), basis.setScale(3), weight, shares[i]));
        }
        return new Plan(method, total, costDate, done);
    }

    private static long thousandths(BigDecimal v) {
        return v.movePointRight(3).longValueExact();
    }

    // ---- Void ------------------------------------------------------------------------------

    @Transactional
    Outcome<LandedCost> voidCost(String key, UUID id, VoidRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(key, "POST", PATH + "/" + id + "/void", r, LandedCost.class, () -> voidNow(id, r));
    }

    /**
     * Undoes a landed cost as a whole, once, by reversing each of its entries today and voiding the payee's invoice. Refused
     * while a payment stands against the invoice, while a supplier credit note touches one of its lines, and when the goods it
     * was added to have since been sold or moved. The product's cost goes back where it is still what the document made it.
     */
    private LandedCost voidNow(UUID id, VoidRequest request) {
        Principal principal = CurrentPrincipal.require();
        String reason = request.reason().trim();
        if (reason.length() < OrdersService.MIN_REASON) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "reason", "invalid", "Give a reason of at least " + OrdersService.MIN_REASON + " characters.")));
        }
        Row seen = repo.find(id, false)
                .filter(x -> principal.may(PERMISSION, x.branchId()))
                .orElseThrow(ApiException::notFound);
        for (UUID entryBranch : repo.entryBranches(id)) {
            if (!principal.may(PERMISSION, entryBranch)) {
                throw ApiException.notFound();
            }
        }
        suppliers.lockSupplier(seen.supplierId());
        List<UUID> receipts = repo.receipts(id);
        new TreeSet<>(receipts).forEach(receipt -> receiptRepo.find(receipt, true));
        Row d = repo.find(id, true).orElseThrow(ApiException::notFound);
        if (d.status().equals("voided")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "landed_cost_voided",
                    "Landed cost voided",
                    "This landed cost has been voided already.");
        }
        billRepo.lock(d.billId());
        if (billRepo.paid(d.billId())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "bill_paid",
                    "Invoice paid",
                    "A payment stands against the payee's invoice. Reverse the payment first.");
        }
        if (repo.credited(id)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "landed_cost_credited",
                    "Landed cost credited",
                    "A supplier credit note touches a line this landed cost was added to. Void the credit note first.");
        }
        List<LineRow> ls = repo.lines(List.of(id)).getOrDefault(id, List.of());
        new TreeSet<>(ls.stream().map(LineRow::productId).toList()).forEach(catalogue::lock);
        Map<UUID, Lot> lots =
                lotRepo.lots(ls.stream().map(LineRow::receiptLineId).toList());
        Map<UUID, UUID> lineProducts = new LinkedHashMap<>();
        ls.forEach(l -> lineProducts.put(l.id(), l.productId()));
        Map<UUID, Set<UUID>> locked = costing.lockStock(lots.values(), lineProducts);
        for (LineRow l : ls) {
            if (l.toInventoryMinor() > 0
                    && costing.onHand(lots.get(l.receiptLineId())).compareTo(l.onHandQty()) < 0) {
                throw ApiException.rule(
                        "landed_cost_stock_moved",
                        "Some of the goods this landed cost was added to have been sold or moved since, so it cannot be"
                                + " voided.");
            }
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        for (UUID entry : repo.entries(id)) {
            books.reverse(entry, today, d.landedNo(), LANDED_VOID + ":" + entry);
        }
        for (int i = ls.size() - 1; i >= 0; i--) {
            LineRow l = ls.get(i);
            if (l.costApplied()) {
                costing.restore(lots.get(l.receiptLineId()), l.costBeforeMinor(), l.costAfterMinor(), id, d.landedNo());
            }
            costing.revalueBack(
                    l.id(), lots.get(l.receiptLineId()), l.toInventoryMinor(), true, id, today, LANDED_VOID, locked);
        }
        Instant now = clock.now();
        if (billRepo.markVoided(d.billId(), principal.userId(), today, now, reason) == 0
                || repo.markVoided(id, principal.userId(), today, now, reason) == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "landed_cost_voided",
                    "Landed cost voided",
                    "This landed cost has been voided already.");
        }
        // A receipt this landed cost released from its hold is held again, unless another landed cost still stands on
        // it.
        for (UUID receipt : new TreeSet<>(receipts)) {
            if (!repo.costed(receipt)) {
                receiptRepo.rehold(receipt);
            }
        }
        audit.record(new AuditLog.Entry(
                "retail.landed_cost.voided",
                LANDED,
                id,
                d.branchId(),
                Map.of("status", "posted"),
                Map.of("status", "voided", "reason", reason, "landed_no", d.landedNo())));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    // ---- Reads -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    LandedCost get(UUID id) {
        capabilities.require(RetailCapabilities.PURCHASING);
        Principal principal = CurrentPrincipal.require();
        return repo.find(id, false)
                .filter(x -> principal.may(PERMISSION, x.branchId()))
                .map(x -> view(x, true))
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    LandedCostList list(
            List<UUID> branchIds,
            UUID supplierId,
            UUID receiptId,
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
                receiptId,
                status,
                from,
                to,
                search,
                after == null ? null : after.at(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<Row> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Search.wrap(
                        search,
                        0,
                        Cursor.encode(items.getLast().createdAt() + "|"
                                + items.getLast().id()))
                : null;
        return new LandedCostList(items.stream().map(x -> view(x, false)).toList(), next);
    }

    LandedCost view(Row d, boolean detail) {
        List<Charge> charges = null;
        List<Line> ls = null;
        if (detail) {
            Principal principal = CurrentPrincipal.require();
            charges = repo.charges(List.of(d.id())).getOrDefault(d.id(), List.of()).stream()
                    .map(c -> new Charge(c.lineNo(), c.kind(), c.description(), c.amountMinor()))
                    .toList();
            // Only the lines of branches the caller may read; a document across branches shows a reader scoped to one
            // of them that branch's lines.
            ls = repo.lines(List.of(d.id())).getOrDefault(d.id(), List.of()).stream()
                    .filter(l -> principal.may(PERMISSION, l.branchId()))
                    .map(l -> line(l, principal.may(PROFIT_READ, l.branchId())))
                    .toList();
        }
        return new LandedCost(
                d.id(),
                d.landedNo(),
                d.branchId(),
                d.supplierId(),
                d.supplierName(),
                d.billId(),
                d.billNo(),
                d.supplierRef(),
                d.goodsBillId(),
                d.method(),
                d.costDate(),
                d.dueDate(),
                d.currency(),
                d.totalMinor(),
                d.note(),
                d.status(),
                d.status().equals("posted") ? d.paidMinor() : 0,
                d.lineCount(),
                charges,
                ls,
                d.createdAt(),
                d.createdBy(),
                d.voidedAt(),
                d.voidedOn(),
                d.voidedBy(),
                d.voidReason(),
                d.version());
    }

    private static Line line(LineRow l, boolean costs) {
        return new Line(
                l.lineNo(),
                l.receiptLineId(),
                l.receiptId(),
                l.receiptNo(),
                l.branchId(),
                l.productId(),
                l.code(),
                l.description(),
                Quantities.format(l.lineQty()),
                costs ? plain(l.basis()) : null,
                l.weight() == null ? null : Quantities.format(l.weight()),
                costs ? l.allocatedMinor() : null,
                costs ? Quantities.format(l.onHandQty()) : null,
                costs ? l.toInventoryMinor() : null,
                costs ? l.toCogsMinor() : null,
                costs ? l.costBeforeMinor() : null,
                costs ? l.costAfterMinor() : null,
                l.costApplied(),
                l.costNote());
    }

    private static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }
}
