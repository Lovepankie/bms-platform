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
import com.rincoltech.bms.retail.purchasing.internal.BillsRepository.LineRow;
import com.rincoltech.bms.retail.purchasing.internal.CreditsApi.Credit;
import com.rincoltech.bms.retail.purchasing.internal.CreditsApi.CreditList;
import com.rincoltech.bms.retail.purchasing.internal.CreditsApi.CreditRequest;
import com.rincoltech.bms.retail.purchasing.internal.CreditsApi.Line;
import com.rincoltech.bms.retail.purchasing.internal.CreditsApi.LineRequest;
import com.rincoltech.bms.retail.purchasing.internal.CreditsApi.VoidRequest;
import com.rincoltech.bms.retail.purchasing.internal.CreditsRepository.Credited;
import com.rincoltech.bms.retail.purchasing.internal.CreditsRepository.NewCredit;
import com.rincoltech.bms.retail.purchasing.internal.CreditsRepository.NewLine;
import com.rincoltech.bms.retail.purchasing.internal.CreditsRepository.Row;
import com.rincoltech.bms.retail.purchasing.internal.LotRepository.Lot;
import com.rincoltech.bms.retail.purchasing.internal.PurchaseLines.Numbered;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptCosting.Effect;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.Movement;
import com.rincoltech.bms.retail.stock.StockLedger.Recorded;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Supplier credit notes (purchasing capability; ADR-033, issue #199 amendment; docs/specs/trade-capabilities.md sections
 * 7.1 and 12.3). A credit note is against a supplier invoice that stands and takes an amount off what the invoice leaves to
 * pay, never more than that. Kind {@code goods}: units go back to the supplier (a {@code return_to_supplier} movement per line,
 * refused when the branch does not have them), the credit leaves inventory at what the units were carried at (the lot's cost
 * with landed cost and price credits, in proportion) and the rest, what the supplier credited above or below that, goes to
 * purchase price variance. Kind {@code price}: an adjustment with no goods moving; the part that corrects the invoice's own
 * price variance goes back to purchase price variance, the rest is spread as a landed cost is, in reverse: to inventory for the
 * units still on hand, to cost of goods sold for those sold, and the product's cost per unit falls under the same
 * latest-cost-event guard. One entry per branch: debit trade creditors in the invoice's branch, credit the stock accounts in
 * the branch of each received line and inter-branch clearing for the other branches' parts.
 */
@Service
class CreditsService {

    static final String CREDIT = "retail.supplier_credit";
    static final String CREDIT_VOID = "retail.supplier_credit_void";
    static final String PERMISSION = OrdersService.PAYABLE;
    static final String PATH = "/api/v1/retail/supplier-credits";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final CreditsRepository repo;
    private final BillsRepository billRepo;
    private final ReceiptsRepository receiptRepo;
    private final LotRepository lotRepo;
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

    CreditsService(
            CreditsRepository repo,
            BillsRepository billRepo,
            ReceiptsRepository receiptRepo,
            LotRepository lotRepo,
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
        this.billRepo = billRepo;
        this.receiptRepo = receiptRepo;
        this.lotRepo = lotRepo;
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

    /** One request line worked out against the invoice line and the lot it credits. */
    private record Planned(
            LineRow billLine,
            Lot lot,
            BigDecimal base,
            BigDecimal unitQty,
            Long unitCredit,
            long amount,
            long ppvPart) {}

    // ---- Writes ----------------------------------------------------------------------------

    @Transactional
    Outcome<Credit> create(String key, CreditRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(key, "POST", PATH, r, Credit.class, () -> post(r));
    }

    private Credit post(CreditRequest r) {
        Principal principal = CurrentPrincipal.require();
        var seen = billRepo.find(r.billId())
                .filter(b -> principal.may(PERMISSION, b.branchId()))
                .orElseThrow(() -> ApiException.validation(
                        List.of(new FieldProblem("bill_id", "unknown_bill", "No such supplier invoice."))));
        // The supplier first, as every writer of one supplier does; then the invoice.
        suppliers.lockSupplier(seen.supplierId());
        var bill = billRepo.lock(r.billId()).orElseThrow(ApiException::notFound);
        if (!bill.status().equals("posted") || !bill.kind().equals("goods")) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "bill_id", "unknown_bill", "Credit notes are for supplier invoices for goods that still stand.")));
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate on = r.creditDate() == null ? today : r.creditDate();
        List<FieldProblem> problems = new ArrayList<>();
        if (on.isAfter(today)) {
            problems.add(new FieldProblem("credit_date", "invalid", "A credit note cannot be dated in the future."));
        }
        if (on.isBefore(bill.billDate())) {
            problems.add(new FieldProblem(
                    "credit_date",
                    "credit_before_invoice",
                    "The credit note is dated before the invoice, which is dated " + bill.billDate() + "."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        boolean goods = r.kind().equals("goods");
        // The receipts of the invoice's lines are locked in id order, as a landed cost or a void of a receipt does, so
        // the lot a credit is worked out from cannot change under it.
        new TreeSet<>(lotRepo
                        .lots(billRepo.lines(List.of(bill.id())).getOrDefault(bill.id(), List.of()).stream()
                                .map(LineRow::receiptLineId)
                                .toList())
                        .values()
                        .stream()
                        .map(Lot::receiptId)
                        .toList())
                .forEach(receipt -> receiptRepo.find(receipt, true));
        List<Planned> ps = plan(r, bill, goods, principal);
        long total = ps.stream().mapToLong(Planned::amount).reduce(0, Math::addExact);
        if (total != r.totalMinor()) {
            throw ApiException.rule(
                    "credit_total_mismatch",
                    "The lines come to " + total + " but the credit note total is " + r.totalMinor()
                            + ". Check the quantities and amounts against the paper credit note.");
        }
        long open = bill.totalMinor() - bill.paidMinor() - bill.creditedMinor();
        if (total > open) {
            throw ApiException.rule(
                    "credit_exceeds_open",
                    "The invoice has " + open + " left to pay, so a credit note for " + total
                            + " cannot be applied to it. Reverse a payment on the invoice first, or credit less.");
        }
        String ref = r.supplierRef().trim();
        if (repo.refExists(bill.supplierId(), ref)) {
            throw duplicate();
        }
        new TreeSet<>(ps.stream().map(p -> p.lot().productId()).toList()).forEach(catalogue::lock);
        Map<UUID, Set<UUID>> locked =
                costing.lockStock(ps.stream().map(Planned::lot).toList());
        if (goods) {
            // Only units of the receipt that are still in its branch can go back: the stock may hold the product from a
            // later delivery at another cost, and units of this receipt that were sold are not there to return.
            for (int i = 0; i < ps.size(); i++) {
                Planned p = ps.get(i);
                BigDecimal here = costing.onHandInBranch(p.lot());
                if (p.base().compareTo(here) > 0) {
                    throw ApiException.rule(
                            "credit_exceeds_on_hand",
                            "Line " + (i + 1) + " sends back " + Quantities.format(p.base()) + " of "
                                    + p.lot().receiptNo() + " but only " + Quantities.format(here)
                                    + " of that delivery is still in the branch. The rest were sold, used or moved.");
                }
            }
        }

        UUID id = UUID.randomUUID();
        UUID by = principal.userId();
        UUID home = bill.branchId();
        Numbered no = lines.next("SC", "retail_supplier_credit_no", home);
        String reason = r.reason().trim();
        List<NewLine> newLines = new ArrayList<>();
        List<Movement> movements = new ArrayList<>();
        Map<UUID, long[]> byBranch = new TreeMap<>();
        Map<UUID, BigDecimal> returnedNow = new HashMap<>();
        Map<UUID, Long> inventoryNow = new HashMap<>();
        for (int i = 0; i < ps.size(); i++) {
            Planned p = ps.get(i);
            UUID lineId = UUID.randomUUID();
            long inventory;
            long cogs = 0;
            long variance;
            BigDecimal onHand = null;
            long costBefore =
                    catalogue.find(p.lot().productId()).map(x -> x.costMinor()).orElse(0L);
            long costAfter = costBefore;
            Map<UUID, Long> inventoryByBranch = Map.of();
            boolean applied = false;
            String note = null;
            if (goods) {
                BigDecimal returnedBefore = p.lot()
                        .returnedQty()
                        .add(returnedNow.getOrDefault(p.lot().receiptLineId(), BigDecimal.ZERO));
                long inventoryBefore = p.lot().returnedInventoryMinor()
                        + inventoryNow.getOrDefault(p.lot().receiptLineId(), 0L);
                boolean rest = returnedBefore.add(p.base()).compareTo(p.lot().qty()) >= 0;
                inventory = rest
                        ? Math.max(0, p.lot().lotCostMinor() - inventoryBefore)
                        : BigDecimal.valueOf(p.lot().lotCostMinor())
                                .multiply(p.base())
                                .divide(p.lot().qty(), 0, RoundingMode.HALF_UP)
                                .longValueExact();
                variance = p.amount() - inventory;
                returnedNow.merge(p.lot().receiptLineId(), p.base(), BigDecimal::add);
                inventoryNow.merge(p.lot().receiptLineId(), inventory, Long::sum);
                // The movement carries what the ledger credits to inventory for these units, per unit.
                movements.add(new Movement(
                        p.lot().branchId(),
                        p.lot().productId(),
                        "return_to_supplier",
                        p.base().negate(),
                        BigDecimal.valueOf(inventory)
                                .divide(p.base(), 0, RoundingMode.HALF_UP)
                                .longValueExact(),
                        CREDIT,
                        id,
                        lineId,
                        null,
                        reason));
            } else {
                variance = p.ppvPart();
                long share = p.amount() - p.ppvPart();
                if (share > 0) {
                    Effect e = costing.apply(p.lot(), share, false, id, no.no(), on, CREDIT, locked);
                    costing.recordSpread(lineId, p.lot(), e);
                    inventory = e.inventory();
                    inventoryByBranch = e.byBranch();
                    cogs = e.gone();
                    onHand = e.onHand();
                    costBefore = e.costBefore();
                    costAfter = e.costAfter();
                    applied = e.applied();
                    note = e.note();
                } else {
                    inventory = 0;
                    onHand = costing.onHand(p.lot());
                    note = "The whole credit corrects the invoice's price variance, so the cost did not move.";
                }
            }
            long[] sums = byBranch.computeIfAbsent(p.lot().branchId(), k -> new long[3]);
            sums[1] = Math.addExact(sums[1], cogs);
            sums[2] = Math.addExact(sums[2], variance);
            if (goods) {
                sums[0] = Math.addExact(sums[0], inventory);
            } else {
                for (Map.Entry<UUID, Long> part : inventoryByBranch.entrySet()) {
                    long[] there = byBranch.computeIfAbsent(part.getKey(), k -> new long[3]);
                    there[0] = Math.addExact(there[0], part.getValue());
                }
            }
            newLines.add(new NewLine(
                    lineId,
                    i + 1,
                    p.billLine().id(),
                    p.lot().receiptLineId(),
                    p.lot().branchId(),
                    p.lot().productId(),
                    goods ? p.base() : null,
                    goods ? p.unitQty() : null,
                    goods ? p.unitCredit() : null,
                    p.amount(),
                    inventory,
                    cogs,
                    variance,
                    onHand,
                    costBefore,
                    costAfter,
                    applied,
                    note));
        }
        if (!movements.isEmpty()) {
            stock.record(on, movements);
        }
        // One entry per branch: the stock accounts credited where the goods are, trade creditors debited in the
        // invoice's branch and inter-branch clearing for the other branches' parts.
        byBranch.computeIfAbsent(home, k -> new long[3]);
        long elsewhere = 0;
        for (Map.Entry<UUID, long[]> e : byBranch.entrySet()) {
            if (!e.getKey().equals(home)) {
                elsewhere = Math.addExact(
                        elsewhere, Math.addExact(Math.addExact(e.getValue()[0], e.getValue()[1]), e.getValue()[2]));
            }
        }
        Map<UUID, UUID> entries = new LinkedHashMap<>();
        for (Map.Entry<UUID, long[]> e : byBranch.entrySet()) {
            long inventory = e.getValue()[0];
            long cogs = e.getValue()[1];
            long variance = e.getValue()[2];
            List<Leg> legs = new ArrayList<>();
            legs.add(Leg.credit("inventory", inventory));
            legs.add(Leg.credit("cost_of_goods_sold", cogs));
            legs.add(
                    variance >= 0
                            ? Leg.credit("purchase_price_variance", variance)
                            : Leg.debit("purchase_price_variance", -variance));
            if (e.getKey().equals(home)) {
                legs.add(Leg.credit("interbranch_clearing", elsewhere));
                legs.add(Leg.debit("trade_creditors", total).withSubledger("retail.supplier", bill.supplierId()));
            } else {
                legs.add(Leg.debit("interbranch_clearing", Math.addExact(Math.addExact(inventory, cogs), variance)));
            }
            books.post(new Posting(
                            e.getKey(),
                            on,
                            no.no(),
                            "Supplier credit " + ref + " " + no.no(),
                            CREDIT,
                            id,
                            CREDIT + ":" + id + ":" + e.getKey(),
                            legs))
                    .ifPresent(posted -> entries.put(e.getKey(), posted.entryId()));
        }
        try {
            repo.insert(new NewCredit(
                    id,
                    no.no(),
                    no.seq(),
                    home,
                    bill.supplierId(),
                    bill.id(),
                    ref,
                    on,
                    tenant.profile().currency(),
                    r.kind(),
                    total,
                    reason,
                    PurchasingService.blankToNull(r.note()),
                    by));
        } catch (DuplicateKeyException e) {
            throw duplicate();
        }
        newLines.forEach(l -> repo.insertLine(id, l));
        entries.forEach((branch, entry) -> repo.insertEntry(id, branch, entry));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("credit_no", no.no());
        after.put("kind", r.kind());
        after.put("bill_no", bill.billNo());
        after.put("supplier_ref", ref);
        after.put("supplier_id", bill.supplierId());
        after.put("total_minor", total);
        after.put("lines", ps.size());
        after.put("reason", reason);
        audit.record(AuditLog.Entry.created("retail.supplier_credit.created", CREDIT, id, home, after));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    private static ApiException duplicate() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "duplicate_supplier_credit",
                "Duplicate credit note",
                "This supplier's credit note number has been recorded already.");
    }

    /** Works the request out against the invoice's lines and their lots; writes nothing. */
    private List<Planned> plan(CreditRequest r, BillsRepository.Row bill, boolean goods, Principal principal) {
        List<FieldProblem> problems = new ArrayList<>();
        Map<UUID, LineRow> billLines = new HashMap<>();
        billRepo.lines(List.of(bill.id())).getOrDefault(bill.id(), List.of()).forEach(l -> billLines.put(l.id(), l));
        Map<UUID, Lot> lots = lotRepo.lots(
                billLines.values().stream().map(LineRow::receiptLineId).toList());
        Map<UUID, Credited> credited = repo.creditedByBillLine(bill.id());
        Set<UUID> seen = new HashSet<>();
        List<Planned> out = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            LineRequest l = r.lines().get(i);
            String field = "lines[" + i + "]";
            LineRow bl = billLines.get(l.billLineId());
            Lot lot = bl == null ? null : lots.get(bl.receiptLineId());
            if (bl == null || lot == null || !principal.may(PERMISSION, lot.branchId())) {
                problems.add(new FieldProblem(
                        field + ".bill_line_id", "unknown_bill_line", "No such line on this invoice."));
                continue;
            }
            if (!seen.add(bl.id())) {
                problems.add(new FieldProblem(field + ".bill_line_id", "duplicate", "Credit each invoice line once."));
                continue;
            }
            Credited done = credited.getOrDefault(bl.id(), new Credited(BigDecimal.ZERO, 0, 0, 0));
            long room = bl.lineTotalMinor() - done.totalMinor();
            if (goods) {
                if (l.qty() == null || l.amountMinor() != null) {
                    problems.add(new FieldProblem(
                            field + ".qty", "required", "A goods credit names the quantity sent back, and no amount."));
                    continue;
                }
                if (!Quantities.exact(l.qty())) {
                    problems.add(new FieldProblem(field + ".qty", "invalid", "At most three decimal places."));
                    continue;
                }
                BigDecimal base = l.qty();
                if (lot.unitFactor() != null) {
                    base = Quantities.toBase(l.qty(), lot.unitFactor())
                            .orElseThrow(() -> ApiException.rule(
                                    "quantity_not_representable",
                                    l.qty() + " " + lot.unit()
                                            + " does not convert exactly to thousandths of the item's unit."
                                            + " Change the quantity."));
                }
                BigDecimal left = bl.qty().subtract(done.qty());
                if (base.compareTo(left) > 0) {
                    throw ApiException.rule(
                            "credit_exceeds_billed",
                            "Line " + (i + 1) + " sends back " + Quantities.format(base) + " but the invoice billed "
                                    + Quantities.format(bl.qty()) + " of " + lot.receiptNo() + " and "
                                    + Quantities.format(done.qty()) + " has been credited already.");
                }
                long unitCredit = l.unitCreditMinor() != null ? l.unitCreditMinor() : defaultUnitCredit(bl, done);
                long amount = PurchaseLines.value(l.qty(), unitCredit);
                if (amount <= 0) {
                    problems.add(new FieldProblem(
                            field + ".unit_credit_minor",
                            "invalid",
                            "The credit for the line must be more than zero."));
                    continue;
                }
                if (amount > room) {
                    throw ApiException.rule(
                            "credit_exceeds_billed_value",
                            "Line " + (i + 1) + " credits " + amount + " but only " + room
                                    + " of the invoice line is left to credit.");
                }
                out.add(new Planned(bl, lot, base, lot.unitFactor() == null ? null : l.qty(), unitCredit, amount, 0));
            } else {
                if (l.amountMinor() == null || l.qty() != null || l.unitCreditMinor() != null) {
                    problems.add(new FieldProblem(
                            field + ".amount_minor", "required", "A price credit names the amount, and no quantity."));
                    continue;
                }
                if (lot.returnedQty().signum() > 0) {
                    throw ApiException.rule(
                            "line_returned",
                            "Part of " + lot.receiptNo() + " line " + lot.lineNo()
                                    + " has been sent back to the supplier. Void the goods credit note before a price credit.");
                }
                if (l.amountMinor() > room) {
                    throw ApiException.rule(
                            "credit_exceeds_billed_value",
                            "Line " + (i + 1) + " credits " + l.amountMinor() + " but only " + room
                                    + " of the invoice line is left to credit.");
                }
                long ppvRoom = Math.max(0, bl.varianceMinor() - done.varianceMinor());
                out.add(new Planned(bl, lot, null, null, null, l.amountMinor(), Math.min(l.amountMinor(), ppvRoom)));
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        return out;
    }

    /**
     * The credit for one unit when the person did not type one: the price the invoice billed, less what price credit notes
     * have taken off the line since (spread over the billed units, rounded half up), so a credit never gives back a price
     * the supplier has already corrected.
     */
    private static long defaultUnitCredit(LineRow bl, Credited done) {
        if (done.priceMinor() == 0) {
            return bl.unitPriceMinor();
        }
        BigDecimal units = bl.unitQty() != null ? bl.unitQty() : bl.qty();
        return BigDecimal.valueOf(bl.lineTotalMinor() - done.priceMinor())
                .divide(units, 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    // ---- Void ------------------------------------------------------------------------------

    @Transactional
    Outcome<Credit> voidCredit(String key, UUID id, VoidRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(key, "POST", PATH + "/" + id + "/void", r, Credit.class, () -> voidNow(id, r));
    }

    /**
     * Undoes a credit note as a whole, once, by reversing each of its entries today. Goods go back into stock; a price credit's
     * cost move is put back where the cost is still what the note made it, and the note is refused when the goods it credited
     * have since been sold or moved. The supplier is locked first, then the note.
     */
    private Credit voidNow(UUID id, VoidRequest request) {
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
        Row c = repo.find(id, true).orElseThrow(ApiException::notFound);
        if (c.status().equals("voided")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "credit_voided",
                    "Credit note voided",
                    "This credit note has been voided already.");
        }
        List<CreditsRepository.LineRow> ls = repo.lines(List.of(id)).getOrDefault(id, List.of());
        new TreeSet<>(ls.stream().map(CreditsRepository.LineRow::productId).toList()).forEach(catalogue::lock);
        Map<UUID, Lot> lots = lotRepo.lots(
                ls.stream().map(CreditsRepository.LineRow::receiptLineId).toList());
        Map<UUID, UUID> lineProducts = new LinkedHashMap<>();
        ls.forEach(l -> lineProducts.put(l.id(), l.productId()));
        Map<UUID, Set<UUID>> locked = costing.lockStock(lots.values(), lineProducts);
        boolean goods = c.kind().equals("goods");
        if (!goods) {
            for (CreditsRepository.LineRow l : ls) {
                if (l.toInventoryMinor() > 0
                        && costing.onHand(lots.get(l.receiptLineId())).compareTo(l.onHandQty()) < 0) {
                    throw ApiException.rule(
                            "credit_stock_moved",
                            "Some of the goods this credit note was applied to have been sold or moved since, so it cannot"
                                    + " be voided.");
                }
            }
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        if (goods) {
            List<Recorded> moved = stock.bySource(CREDIT, id);
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
                                        CREDIT_VOID,
                                        id,
                                        m.sourceLineId(),
                                        m.id(),
                                        reason))
                                .toList());
            }
        }
        for (UUID entry : repo.entries(id)) {
            books.reverse(entry, today, c.creditNo(), CREDIT_VOID + ":" + entry);
        }
        for (int i = ls.size() - 1; i >= 0; i--) {
            CreditsRepository.LineRow l = ls.get(i);
            if (l.costApplied()) {
                costing.restore(lots.get(l.receiptLineId()), l.costBeforeMinor(), l.costAfterMinor(), id, c.creditNo());
            }
            if (!goods) {
                costing.revalueBack(
                        l.id(),
                        lots.get(l.receiptLineId()),
                        l.toInventoryMinor(),
                        false,
                        id,
                        today,
                        CREDIT_VOID,
                        locked);
            }
        }
        Instant now = clock.now();
        if (repo.markVoided(id, principal.userId(), today, now, reason) == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "credit_voided",
                    "Credit note voided",
                    "This credit note has been voided already.");
        }
        audit.record(new AuditLog.Entry(
                "retail.supplier_credit.voided",
                CREDIT,
                id,
                c.branchId(),
                Map.of("status", "posted"),
                Map.of("status", "voided", "reason", reason, "credit_no", c.creditNo())));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    // ---- Reads -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    Credit get(UUID id) {
        capabilities.require(RetailCapabilities.PURCHASING);
        Principal principal = CurrentPrincipal.require();
        return repo.find(id, false)
                .filter(x -> principal.may(PERMISSION, x.branchId()))
                .map(x -> view(x, true))
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    CreditList list(
            List<UUID> branchIds,
            UUID supplierId,
            UUID billId,
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
                billId,
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
        return new CreditList(items.stream().map(x -> view(x, false)).toList(), next);
    }

    Credit view(Row c, boolean detail) {
        List<Line> ls = null;
        if (detail) {
            Principal principal = CurrentPrincipal.require();
            // Only the lines of branches the caller may read; a document across branches shows a reader scoped to one
            // of them that branch's lines.
            ls = repo.lines(List.of(c.id())).getOrDefault(c.id(), List.of()).stream()
                    .filter(l -> principal.may(PERMISSION, l.branchId()))
                    .map(l -> line(l, principal.may(LandedCostService.PROFIT_READ, l.branchId())))
                    .toList();
        }
        return new Credit(
                c.id(),
                c.creditNo(),
                c.branchId(),
                c.supplierId(),
                c.supplierName(),
                c.billId(),
                c.billNo(),
                c.billSupplierRef(),
                c.supplierRef(),
                c.creditDate(),
                c.currency(),
                c.kind(),
                c.totalMinor(),
                c.reason(),
                c.note(),
                c.status(),
                c.lineCount(),
                ls,
                c.createdAt(),
                c.createdBy(),
                c.voidedAt(),
                c.voidedOn(),
                c.voidedBy(),
                c.voidReason(),
                c.version());
    }

    private static Line line(CreditsRepository.LineRow l, boolean costs) {
        boolean goods = l.qty() != null;
        return new Line(
                l.id(),
                l.lineNo(),
                l.billLineId(),
                l.receiptLineId(),
                l.receiptNo(),
                l.branchId(),
                l.productId(),
                l.code(),
                l.description(),
                goods ? Quantities.format(l.qty()) : null,
                l.unitId(),
                l.unit(),
                l.unitQty() == null ? null : Quantities.format(l.unitQty()),
                l.unitCreditMinor(),
                l.lineTotalMinor(),
                costs ? l.toInventoryMinor() : null,
                costs ? l.toCogsMinor() : null,
                costs ? l.varianceMinor() : null,
                costs && !goods ? l.costBeforeMinor() : null,
                costs && !goods ? l.costAfterMinor() : null,
                goods ? null : l.costApplied(),
                l.costNote());
    }
}
