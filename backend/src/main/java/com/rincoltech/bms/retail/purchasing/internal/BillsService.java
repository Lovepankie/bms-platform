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
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.Bill;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.BillLine;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.BillLineRequest;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.BillList;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.BillRequest;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.Match;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.Unbilled;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.UnbilledList;
import com.rincoltech.bms.retail.purchasing.internal.BillsApi.VoidRequest;
import com.rincoltech.bms.retail.purchasing.internal.BillsRepository.LineRow;
import com.rincoltech.bms.retail.purchasing.internal.BillsRepository.NewBill;
import com.rincoltech.bms.retail.purchasing.internal.BillsRepository.NewLine;
import com.rincoltech.bms.retail.purchasing.internal.BillsRepository.Receivable;
import com.rincoltech.bms.retail.purchasing.internal.BillsRepository.Row;
import com.rincoltech.bms.retail.purchasing.internal.PurchaseLines.Numbered;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier invoices and their three-way match (purchasing capability; ADR-033, issue #198;
 * docs/specs/trade-capabilities.md sections 7.1 and 12.3). A bill bills quantities of received lines at the supplier's price.
 * Each line clears its share of the receipt line's value from goods received not invoiced, the last piece taking the
 * remainder so the pieces add up exactly; the difference to the billed amount is the price variance, posted to purchase
 * price variance. A difference above the tenant's tolerance (zero by default) needs a reason. A bill never bills more than
 * was received and not yet billed: what the supplier invoices before it is received waits for the receipt. The supplier's
 * row is locked first, so two invoices cannot spend the same receipt line. One entry is posted per branch of the receipts:
 * debit goods received not invoiced and the variance, credit trade creditors with the supplier as the subledger.
 */
@Service
class BillsService {

    static final String BILL = "retail.supplier_bill";
    static final String PERMISSION = OrdersService.PAYABLE;
    static final String BILLS = "/api/v1/retail/supplier-bills";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    private static final long BP = 10000;

    private final BillsRepository repo;
    private final PurchasingRepository suppliers;
    private final PurchaseLines lines;
    private final PurchasingSettingsService settings;
    private final RetailBooks books;
    private final Idempotency idempotency;
    private final RetailCapabilities capabilities;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    BillsService(
            BillsRepository repo,
            PurchasingRepository suppliers,
            PurchaseLines lines,
            PurchasingSettingsService settings,
            RetailBooks books,
            Idempotency idempotency,
            RetailCapabilities capabilities,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit) {
        this.repo = repo;
        this.suppliers = suppliers;
        this.lines = lines;
        this.settings = settings;
        this.books = books;
        this.idempotency = idempotency;
        this.capabilities = capabilities;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
    }

    /** One bill line worked out against the receipt line it bills. */
    private record Planned(
            Receivable receipt, BigDecimal base, BigDecimal unitQty, long unitPrice, long lineTotal, long cleared) {

        long variance() {
            return lineTotal - cleared;
        }
    }

    private record Plan(
            List<Planned> lines, long total, long cleared, long variance, long tolerance, boolean reasonRequired) {}

    // ---- Writes ----------------------------------------------------------------------------

    @Transactional
    Outcome<Bill> create(String key, BillRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(key, "POST", BILLS, r, Bill.class, () -> post(r));
    }

    private Bill post(BillRequest r) {
        if (!suppliers.lockSupplier(r.supplierId())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("supplier_id", "unknown_supplier", "No such supplier.")));
        }
        Plan plan = plan(r);
        if (plan.total() != r.totalMinor()) {
            throw ApiException.rule(
                    "bill_total_mismatch",
                    "The lines come to " + plan.total() + " but the invoice total is " + r.totalMinor()
                            + ". Check the quantities and prices against the paper invoice.");
        }
        String reason = PurchasingService.blankToNull(r.varianceReason());
        if (plan.reasonRequired() && (reason == null || reason.length() < OrdersService.MIN_REASON)) {
            throw ApiException.rule(
                    "variance_reason_required",
                    "The invoice differs from the receipts by " + plan.variance() + ", which is more than the allowed "
                            + plan.tolerance() + ". Say why, in at least " + OrdersService.MIN_REASON + " characters.");
        }
        if (repo.refExists(r.supplierId(), r.supplierRef().trim())) {
            throw duplicate();
        }
        UUID id = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        // The bill belongs to the branch of its first line's receipt; the numbering and the list follow it.
        UUID billBranch = plan.lines().getFirst().receipt().branchId();
        Numbered no = lines.next("SB", "retail_supplier_bill_no", billBranch);
        String ref = r.supplierRef().trim();
        Map<UUID, long[]> byBranch = new TreeMap<>();
        for (Planned p : plan.lines()) {
            long[] sums = byBranch.computeIfAbsent(p.receipt().branchId(), k -> new long[3]);
            sums[0] = Math.addExact(sums[0], p.cleared());
            sums[1] = Math.addExact(sums[1], p.variance());
            sums[2] = Math.addExact(sums[2], p.lineTotal());
        }
        // The whole invoice is owed from the bill's branch: its entry credits trade creditors for all of it, and a
        // branch
        // whose receipts it clears credits inter-branch clearing for its part, as a transfer or a return does, so each
        // branch's
        // goods received not invoiced clears in its own books and the creditor balance sits in one.
        long elsewhere = 0;
        for (Map.Entry<UUID, long[]> e : byBranch.entrySet()) {
            if (!e.getKey().equals(billBranch)) {
                elsewhere = Math.addExact(elsewhere, e.getValue()[2]);
            }
        }
        Map<UUID, UUID> entries = new LinkedHashMap<>();
        for (Map.Entry<UUID, long[]> e : byBranch.entrySet()) {
            long cleared = e.getValue()[0];
            long variance = e.getValue()[1];
            long total = e.getValue()[2];
            boolean home = e.getKey().equals(billBranch);
            List<Leg> legs = new ArrayList<>();
            legs.add(Leg.debit("goods_received_not_invoiced", cleared));
            legs.add(
                    variance >= 0
                            ? Leg.debit("purchase_price_variance", variance)
                            : Leg.credit("purchase_price_variance", -variance));
            if (home) {
                legs.add(Leg.debit("interbranch_clearing", elsewhere));
                legs.add(Leg.credit("trade_creditors", Math.addExact(total, elsewhere))
                        .withSubledger("retail.supplier", r.supplierId()));
            } else {
                legs.add(Leg.credit("interbranch_clearing", total));
            }
            books.post(new Posting(
                            e.getKey(),
                            r.billDate(),
                            no.no(),
                            "Supplier invoice " + ref + " " + no.no(),
                            BILL,
                            id,
                            BILL + ":" + id + ":" + e.getKey(),
                            legs))
                    .ifPresent(posted -> entries.put(e.getKey(), posted.entryId()));
        }
        try {
            repo.insert(new NewBill(
                    id,
                    no.no(),
                    no.seq(),
                    billBranch,
                    r.supplierId(),
                    ref,
                    r.billDate(),
                    r.dueDate(),
                    tenant.profile().currency(),
                    plan.total(),
                    plan.cleared(),
                    plan.variance(),
                    reason,
                    PurchasingService.blankToNull(r.note()),
                    by));
        } catch (DuplicateKeyException e) {
            throw duplicate();
        }
        int n = 1;
        for (Planned p : plan.lines()) {
            repo.insertLine(
                    id,
                    new NewLine(
                            UUID.randomUUID(),
                            n++,
                            p.receipt().receiptLineId(),
                            p.base(),
                            p.unitQty(),
                            p.unitPrice(),
                            p.lineTotal(),
                            p.cleared(),
                            p.variance()));
        }
        entries.forEach((branch, entry) -> repo.insertEntry(id, branch, entry));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("bill_no", no.no());
        after.put("supplier_ref", ref);
        after.put("supplier_id", r.supplierId());
        after.put("total_minor", plan.total());
        after.put("variance_minor", plan.variance());
        if (reason != null) {
            after.put("variance_reason", reason);
        }
        after.put("lines", plan.lines().size());
        audit.record(AuditLog.Entry.created("retail.supplier_bill.created", BILL, id, billBranch, after));
        return view(repo.find(id).orElseThrow(), true);
    }

    private static ApiException duplicate() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "duplicate_supplier_bill",
                "Duplicate invoice",
                "This supplier's invoice number has been recorded already.");
    }

    /** Works the request out against the receipt lines as they stand; writes nothing. */
    private Plan plan(BillRequest r) {
        List<FieldProblem> problems = new ArrayList<>();
        Principal principal = CurrentPrincipal.require();
        LocalDate today = clock.today(tenant.profile().timezone());
        if (r.billDate().isAfter(today)) {
            problems.add(new FieldProblem("bill_date", "invalid", "An invoice cannot be dated in the future."));
        }
        if (r.dueDate().isBefore(r.billDate())) {
            problems.add(new FieldProblem("due_date", "invalid", "The due date is before the invoice date."));
        }
        if (suppliers.supplier(r.supplierId()).isEmpty()) {
            problems.add(new FieldProblem("supplier_id", "unknown_supplier", "No such supplier."));
        }
        Map<UUID, Receivable> receivable = repo.receivable(
                r.lines().stream().map(BillLineRequest::receiptLineId).toList());
        Set<UUID> seen = new HashSet<>();
        List<Planned> out = new ArrayList<>();
        long total = 0;
        long cleared = 0;
        for (int i = 0; i < r.lines().size(); i++) {
            BillLineRequest l = r.lines().get(i);
            String field = "lines[" + i + "]";
            Receivable rl = receivable.get(l.receiptLineId());
            if (rl == null
                    || !rl.status().equals("posted")
                    || !rl.supplierId().equals(r.supplierId())
                    || !principal.may(PERMISSION, rl.branchId())) {
                problems.add(new FieldProblem(
                        field + ".receipt_line_id",
                        "unknown_receipt_line",
                        "No such received line of this supplier that is still standing."));
                continue;
            }
            if (r.billDate().isBefore(rl.receivedOn())) {
                problems.add(new FieldProblem(
                        "bill_date",
                        "bill_before_receipt",
                        "The invoice is dated before " + rl.receiptNo() + " was received on " + rl.receivedOn() + "."));
                continue;
            }
            if (!seen.add(rl.receiptLineId())) {
                problems.add(
                        new FieldProblem(field + ".receipt_line_id", "duplicate", "Bill each received line once."));
                continue;
            }
            if (!Quantities.exact(l.qty())) {
                problems.add(new FieldProblem(field + ".qty", "invalid", "At most three decimal places."));
                continue;
            }
            BigDecimal base = l.qty();
            if (rl.unitFactor() != null) {
                base = Quantities.toBase(l.qty(), rl.unitFactor())
                        .orElseThrow(() -> ApiException.rule(
                                "quantity_not_representable",
                                l.qty() + " " + rl.unit()
                                        + " does not convert exactly to thousandths of the item's unit."
                                        + " Change the quantity."));
            }
            BigDecimal unbilled = rl.qty().subtract(rl.billedQty());
            if (base.compareTo(unbilled) > 0) {
                throw ApiException.rule(
                        "bill_exceeds_received",
                        "Line " + (i + 1) + " bills " + Quantities.format(base) + " but only "
                                + Quantities.format(unbilled) + " of " + rl.receiptNo()
                                + " was received and is not billed yet. Receive the goods first.");
            }
            long lineTotal = PurchaseLines.value(l.qty(), l.unitPriceMinor());
            long remaining = rl.lineTotalMinor() - rl.clearedMinor();
            long part = base.compareTo(unbilled) == 0
                    ? remaining
                    : Math.min(PurchaseLines.share(rl.lineTotalMinor(), base, rl.qty()), remaining);
            total = Math.addExact(total, lineTotal);
            cleared = Math.addExact(cleared, part);
            out.add(new Planned(
                    rl, base, rl.unitFactor() == null ? null : l.qty(), l.unitPriceMinor(), lineTotal, part));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        long variance = total - cleared;
        int bp = settings.current().billToleranceBp();
        long tolerance = Math.multiplyExact(cleared, (long) bp) / BP;
        boolean reasonRequired =
                variance != 0 && Math.multiplyExact(Math.abs(variance), BP) > Math.multiplyExact(cleared, (long) bp);
        return new Plan(out, total, cleared, variance, tolerance, reasonRequired);
    }

    /** What an invoice comes to against the receipts it bills, without recording it: the screen's three-way view. */
    @Transactional(readOnly = true)
    Match match(BillRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        Plan plan = plan(r);
        List<BillLine> ls = new ArrayList<>();
        for (int i = 0; i < plan.lines().size(); i++) {
            Planned p = plan.lines().get(i);
            Receivable rl = p.receipt();
            ls.add(new BillLine(
                    null,
                    i + 1,
                    rl.receiptLineId(),
                    rl.receiptId(),
                    rl.receiptNo(),
                    rl.productId(),
                    rl.code(),
                    rl.description(),
                    Quantities.format(p.base()),
                    rl.unitId(),
                    rl.unit(),
                    p.unitQty() == null ? null : Quantities.format(p.unitQty()),
                    p.unitPrice(),
                    p.lineTotal(),
                    p.cleared(),
                    p.variance()));
        }
        return new Match(
                plan.total(),
                plan.cleared(),
                plan.variance(),
                plan.tolerance(),
                plan.reasonRequired(),
                plan.total() != r.totalMinor(),
                ls);
    }

    @Transactional
    Outcome<Bill> voidBill(String key, UUID id, VoidRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(key, "POST", BILLS + "/" + id + "/void", r, Bill.class, () -> voidNow(id, r));
    }

    /**
     * Undoes a bill as a whole, once, by reversing each of its entries today. Refused while a payment that stands
     * allocates to it: reverse the payment first. The supplier is locked first, then the bill.
     */
    private Bill voidNow(UUID id, VoidRequest request) {
        Principal principal = CurrentPrincipal.require();
        String reason = request.reason().trim();
        if (reason.length() < OrdersService.MIN_REASON) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "reason", "invalid", "Give a reason of at least " + OrdersService.MIN_REASON + " characters.")));
        }
        Row seen = repo.find(id)
                .filter(x -> principal.may(PERMISSION, x.branchId()))
                .orElseThrow(ApiException::notFound);
        // As creating it needed the scope of every branch it clears, so does undoing it.
        for (UUID entryBranch : repo.entryBranches(id)) {
            if (!principal.may(PERMISSION, entryBranch)) {
                throw ApiException.notFound();
            }
        }
        suppliers.lockSupplier(seen.supplierId());
        Row b = repo.lock(id).orElseThrow(ApiException::notFound);
        if (b.status().equals("voided")) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "bill_voided", "Invoice voided", "This invoice has been voided already.");
        }
        if (repo.paid(id)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "bill_paid",
                    "Invoice paid",
                    "A payment stands against this invoice. Reverse the payment first.");
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        for (UUID entry : repo.entries(id)) {
            books.reverse(entry, today, b.billNo(), BILL + "_void:" + entry);
        }
        Instant now = clock.now();
        if (repo.markVoided(id, principal.userId(), today, now, reason) == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "bill_voided", "Invoice voided", "This invoice has been voided already.");
        }
        audit.record(new AuditLog.Entry(
                "retail.supplier_bill.voided",
                BILL,
                id,
                b.branchId(),
                Map.of("status", "posted"),
                Map.of("status", "voided", "reason", reason, "bill_no", b.billNo())));
        return view(repo.find(id).orElseThrow(), true);
    }

    // ---- Reads -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    Bill get(UUID id) {
        capabilities.require(RetailCapabilities.PURCHASING);
        Principal principal = CurrentPrincipal.require();
        return repo.find(id)
                .filter(x -> principal.may(PERMISSION, x.branchId()))
                .map(x -> view(x, true))
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    BillList list(
            List<UUID> branchIds,
            UUID supplierId,
            String status,
            boolean openOnly,
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
                status,
                openOnly,
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
        return new BillList(items.stream().map(x -> view(x, false)).toList(), next);
    }

    /** The received lines of a supplier that no invoice bills in full: what a new invoice is built from. */
    @Transactional(readOnly = true)
    UnbilledList unbilled(UUID supplierId) {
        capabilities.require(RetailCapabilities.PURCHASING);
        Principal principal = CurrentPrincipal.require();
        var supplier = suppliers.supplier(supplierId).orElseThrow(ApiException::notFound);
        List<Unbilled> items = repo.unbilled(supplierId).stream()
                .filter(x -> principal.may(PERMISSION, x.branchId()))
                .map(x -> {
                    BigDecimal left = x.qty().subtract(x.billedQty());
                    BigDecimal inUnit = x.unitFactor() == null ? left : Quantities.fromBase(left, x.unitFactor());
                    return new Unbilled(
                            x.receiptLineId(),
                            x.receiptId(),
                            x.receiptNo(),
                            x.branchId(),
                            x.receivedOn(),
                            x.orderNo(),
                            x.productId(),
                            x.code(),
                            x.description(),
                            x.unitId(),
                            x.unit(),
                            Quantities.format(inUnit),
                            x.enteredCostMinor(),
                            x.lineTotalMinor() - x.clearedMinor());
                })
                .toList();
        long total = items.stream().mapToLong(Unbilled::unbilledMinor).reduce(0, Math::addExact);
        return new UnbilledList(supplierId, supplier.name(), items, total);
    }

    Bill view(Row b, boolean detail) {
        List<BillLine> ls = null;
        if (detail) {
            ls = repo.lines(List.of(b.id())).getOrDefault(b.id(), List.of()).stream()
                    .map(BillsService::line)
                    .toList();
        }
        boolean posted = b.status().equals("posted");
        return new Bill(
                b.id(),
                b.billNo(),
                b.branchId(),
                b.supplierId(),
                b.supplierName(),
                b.supplierRef(),
                b.billDate(),
                b.dueDate(),
                b.currency(),
                b.kind(),
                b.totalMinor(),
                b.clearedMinor(),
                b.varianceMinor(),
                b.varianceReason(),
                b.note(),
                b.status(),
                posted ? b.paidMinor() : 0,
                posted ? b.totalMinor() - b.paidMinor() : 0,
                b.lineCount(),
                ls,
                b.createdAt(),
                b.createdBy(),
                b.voidedAt(),
                b.voidedOn(),
                b.voidedBy(),
                b.voidReason(),
                b.version());
    }

    private static BillLine line(LineRow l) {
        return new BillLine(
                l.id(),
                l.lineNo(),
                l.receiptLineId(),
                l.receiptId(),
                l.receiptNo(),
                l.productId(),
                l.code(),
                l.description(),
                Quantities.format(l.qty()),
                l.unitId(),
                l.unit(),
                l.unitQty() == null ? null : Quantities.format(l.unitQty()),
                l.unitPriceMinor(),
                l.lineTotalMinor(),
                l.clearedMinor(),
                l.varianceMinor());
    }
}
