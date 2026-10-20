package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.PackUnit;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.ProductSnapshot;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.ReturnLine;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.ReturnLineRequest;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.ReturnRequest;
import com.rincoltech.bms.retail.sales.internal.ReturnsApi.SaleReturn;
import com.rincoltech.bms.retail.sales.internal.ReturnsRepository.Done;
import com.rincoltech.bms.retail.sales.internal.ReturnsRepository.LineRow;
import com.rincoltech.bms.retail.sales.internal.ReturnsRepository.NewLine;
import com.rincoltech.bms.retail.sales.internal.ReturnsRepository.NewReturn;
import com.rincoltech.bms.retail.sales.internal.ReturnsRepository.Row;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Sale;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleLine;
import com.rincoltech.bms.retail.sales.internal.SalesRepository.Header;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes goods back from a customer (returns capability; ADR-033, issue #201; docs/specs/trade-capabilities.md section
 * 5.3). The work of a return, in the caller's transaction and under the sale's row lock: the plan (what each line is
 * credited and costs, how the credit is paid out), then the record (stock movements at the original cost, the
 * journal entries, the numbered credit note, the sale's settled amount, the audit row). It depends on nothing that
 * depends on it back, so {@link ReturnAction} can call it from the approval transaction without a cycle.
 */
@Service
class ReturnsServicer {

    static final String RETURN = "retail.sale_return";
    static final String RETURN_VOID = "retail.sale_return_void";
    static final String PERMISSION = "retail.sale.return";
    static final int MIN_REASON = 5;

    private final ReturnsRepository repo;
    private final SalesRepository sales;
    private final RetailCatalogue catalogue;
    private final StockLedger stock;
    private final RetailBooks books;
    private final Branches branches;
    private final RetailBranchContext stockBranches;
    private final TenantSequences sequences;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final RetailCapabilities capabilities;

    ReturnsServicer(
            ReturnsRepository repo,
            SalesRepository sales,
            RetailCatalogue catalogue,
            StockLedger stock,
            RetailBooks books,
            Branches branches,
            RetailBranchContext stockBranches,
            TenantSequences sequences,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            RetailCapabilities capabilities) {
        this.repo = repo;
        this.sales = sales;
        this.catalogue = catalogue;
        this.stock = stock;
        this.books = books;
        this.branches = branches;
        this.stockBranches = stockBranches;
        this.sequences = sequences;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.capabilities = capabilities;
    }

    /** One returned line, worked out: its quantity in base units, its credit and its cost. */
    record Planned(
            UUID id,
            int lineNo,
            SaleLine saleLine,
            BigDecimal baseQty,
            UUID unitId,
            String unit,
            BigDecimal unitQty,
            BigDecimal unitFactor,
            long totalMinor,
            long costMinor,
            String reason,
            String disposition,
            UUID restockBranchId) {}

    /** What a request comes to against the sale as it stands under the lock. */
    record Plan(
            Sale sale,
            LocalDate on,
            List<Planned> lines,
            long totalMinor,
            long costMinor,
            long accountCreditMinor,
            long refundMinor,
            String refundMethod,
            String refundChoice,
            UUID refundBranchId,
            long priorReturnedMinor) {}

    /**
     * Locks the sale and works the request out against it without writing anything: every refusal of the spec happens
     * here (more than was sold less what already came back, a voided sale, an account refund that is not allowed, a
     * money refund past what was paid). The caller needs {@code retail.sale.return} at the sale's branch, else 404.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Plan plan(UUID saleId, ReturnRequest r) {
        capabilities.require(RetailCapabilities.RETURNS);
        Principal principal = CurrentPrincipal.require();
        Header h = sales.find(saleId, true)
                .filter(x -> principal.may(PERMISSION, x.sale().branchId()))
                .orElseThrow(ApiException::notFound);
        Sale s = h.sale();
        if (s.status().equals("voided")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "sale_voided",
                    "Sale voided",
                    "This sale has been voided, which already took everything back.");
        }
        if (s.writeOff() != null) {
            throw SalesService.writtenOff(
                    "The unpaid part of this sale was written off, so goods cannot be taken back against it.");
        }
        LocalDate on = clock.today(tenant.profile().timezone());
        Map<UUID, SaleLine> byId = new HashMap<>();
        s.lines().forEach(l -> byId.put(l.id(), l));
        Map<UUID, Done> done = new HashMap<>();
        repo.done(saleId).forEach(d -> done.put(d.saleLineId(), d));
        // What this request has taken of each line so far: two request lines may name one sale line.
        Map<UUID, BigDecimal> takenQty = new HashMap<>();
        Map<UUID, Long> takenTotal = new HashMap<>();
        Map<UUID, Long> takenCost = new HashMap<>();
        List<FieldProblem> problems = new ArrayList<>();
        List<Planned> lines = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            ReturnLineRequest l = r.lines().get(i);
            String field = "lines[" + i + "]";
            SaleLine sl = byId.get(l.saleLineId());
            if (sl == null) {
                problems.add(new FieldProblem(
                        field + ".sale_line_id", "unknown_sale_line", "That line is not on this sale."));
                continue;
            }
            if (!Quantities.exact(l.qty())) {
                problems.add(new FieldProblem(field + ".qty", "invalid", "At most three decimal places."));
                continue;
            }
            UUID restock = null;
            if (l.disposition().equals("restock")) {
                restock = l.restockBranchId() == null ? s.branchId() : l.restockBranchId();
                if (!principal.may(PERMISSION, restock)
                        || branches.findActive(restock).isEmpty()) {
                    problems.add(new FieldProblem(
                            field + ".restock_branch_id", "unknown_branch", "No such active branch in your scope."));
                    continue;
                }
                // 422 branch_not_stocking for a branch that keeps no goods, like every other stock write (ADR-041).
                stockBranches.requireStocks(restock, field + ".restock_branch_id");
            } else if (l.restockBranchId() != null) {
                problems.add(new FieldProblem(
                        field + ".restock_branch_id",
                        "invalid",
                        "Damaged goods are written off; they do not go back to a branch."));
                continue;
            }
            ProductSnapshot product = catalogue.find(sl.productId()).orElseThrow();
            UUID unitId = null;
            String unitName = null;
            BigDecimal unitQty = null;
            BigDecimal factor = BigDecimal.ONE;
            if (l.unitId() != null && !l.unitId().equals(product.unitId())) {
                if (l.unitId().equals(sl.saleUnitId())) {
                    // The unit the line was sold in: its factor is the one the line kept, whatever the pack is now.
                    factor = new BigDecimal(sl.unitFactor());
                    unitName = sl.saleUnit();
                } else {
                    if (!capabilities.enabled(RetailCapabilities.UNITS)) {
                        problems.add(new FieldProblem(
                                field + ".unit_id",
                                "capability_not_enabled",
                                "Pack sizes are not enabled for this business."));
                        continue;
                    }
                    PackUnit pack = catalogue.packUnit(product.id(), l.unitId()).orElse(null);
                    if (pack == null || !pack.active()) {
                        problems.add(new FieldProblem(
                                field + ".unit_id", "unknown_pack_unit", "This item is not sold in that unit."));
                        continue;
                    }
                    factor = pack.factor();
                    unitName = pack.unit();
                }
                unitId = l.unitId();
                unitQty = l.qty();
            }
            BigDecimal baseQty = Quantities.toBase(l.qty(), factor).orElse(null);
            if (baseQty == null) {
                throw ApiException.rule(
                        "quantity_not_representable",
                        l.qty() + (unitName == null ? "" : " " + unitName)
                                + " does not convert exactly to thousandths of the base unit. Change the quantity.");
            }
            BigDecimal sold = new BigDecimal(sl.qty());
            Done d = done.get(sl.id());
            BigDecimal back =
                    (d == null ? BigDecimal.ZERO : d.qty()).add(takenQty.getOrDefault(sl.id(), BigDecimal.ZERO));
            BigDecimal remaining = sold.subtract(back);
            if (baseQty.compareTo(remaining) > 0) {
                throw ApiException.rule(
                        "return_exceeds_sold",
                        "Line " + sl.lineNo() + " of " + s.saleNo() + " sold " + Quantities.format(sold) + " and "
                                + Quantities.format(back) + " has come back already, so at most "
                                + Quantities.format(remaining) + " can be returned.");
            }
            long creditedBefore = (d == null ? 0 : d.totalMinor()) + takenTotal.getOrDefault(sl.id(), 0L);
            long costedBefore = (d == null ? 0 : d.costMinor()) + takenCost.getOrDefault(sl.id(), 0L);
            boolean last = baseQty.compareTo(remaining) == 0;
            long total = last
                    ? sl.lineTotalMinor() - creditedBefore
                    : Math.min(share(sl.lineTotalMinor(), baseQty, sold), sl.lineTotalMinor() - creditedBefore);
            long cost = last
                    ? sl.lineCostMinor() - costedBefore
                    : Math.min(share(sl.lineCostMinor(), baseQty, sold), sl.lineCostMinor() - costedBefore);
            takenQty.merge(sl.id(), baseQty, BigDecimal::add);
            takenTotal.merge(sl.id(), total, Long::sum);
            takenCost.merge(sl.id(), cost, Long::sum);
            lines.add(new Planned(
                    UUID.randomUUID(),
                    i + 1,
                    sl,
                    baseQty,
                    unitId,
                    unitName,
                    unitQty,
                    unitId == null ? null : factor,
                    total,
                    cost,
                    l.reason(),
                    l.disposition(),
                    restock));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        long total = lines.stream().mapToLong(Planned::totalMinor).reduce(0, Math::addExact);
        long cost = lines.stream().mapToLong(Planned::costMinor).reduce(0, Math::addExact);
        long account = 0;
        long money = total;
        String method = null;
        String choice = r.refundMethod();
        if (r.moneyMethod() != null && !choice.equals("account")) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "money_method", "invalid", "Only a refund to the account has a second way of paying out.")));
        }
        if (total > 0 && choice.equals("account")) {
            if (!s.paymentMethod().equals("credit")) {
                throw ApiException.rule(
                        "account_refund_unavailable",
                        "Only a credit sale with an open balance can be credited to the account. Refund in money.");
            }
            if (s.historical()) {
                throw ApiException.rule(
                        "account_refund_historical",
                        "An imported credit sale cannot be credited to the account. Refund in money.");
            }
            long open = s.totalMinor() - s.paidMinor();
            if (open <= 0) {
                throw ApiException.rule(
                        "account_refund_unavailable",
                        "This credit sale has nothing left to pay, so there is no balance to take it off. Refund in money.");
            }
            account = Math.min(total, open);
            money = total - account;
            if (money > 0) {
                if (r.moneyMethod() == null) {
                    throw ApiException.rule(
                            "refund_remainder_method_required",
                            account + " can be taken off what the customer owes; the other " + money
                                    + " is paid out in money. Choose how in money_method.");
                }
                method = r.moneyMethod();
            }
        } else if (total > 0) {
            method = choice;
        } else {
            money = 0;
        }
        if (money > 0 && s.paymentMethod().equals("credit")) {
            // Money goes back only up to the money that came in: a credit sale the buyer has not paid for is credited
            // to the
            // account, never refunded in cash.
            long paidInMoney = s.paidMinor() - repo.accountCredited(saleId);
            long allowed = paidInMoney - repo.moneyRefunded(saleId);
            if (money > allowed) {
                throw ApiException.rule(
                        "refund_exceeds_paid",
                        "Only " + Math.max(allowed, 0) + " of this credit sale was paid in money and not refunded, so "
                                + money + " cannot be paid out. Credit the rest to the account.");
            }
        }
        UUID payBranch = refundBranch(principal, s, money > 0 ? r.refundBranchId() : null, money > 0);
        if (money > 0) {
            stockBranches.requireSells(payBranch, "refund_branch_id");
        }
        return new Plan(
                s, on, lines, total, cost, account, money, method, choice, payBranch, repo.returnedTotal(saleId));
    }

    /**
     * The till money goes out of (spec 5.3, "money out of the receiving branch"): the branch the caller named, inside their
     * {@code retail.sale.return} scope and active; else their own branch when the scope is exactly one branch; else the sale's
     * branch.
     */
    private UUID refundBranch(Principal principal, Sale s, UUID requested, boolean paying) {
        if (requested != null) {
            if (!principal.may(PERMISSION, requested)
                    || branches.findActive(requested).isEmpty()) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "refund_branch_id", "unknown_branch", "No such active branch in your scope.")));
            }
            return requested;
        }
        UUID usual = principal
                .scopeOf(PERMISSION)
                .filter(sc -> !sc.all() && sc.branchIds().size() == 1)
                .map(sc -> sc.branchIds().iterator().next())
                .filter(b -> branches.findActive(b).isPresent())
                .orElse(s.branchId());
        if (!paying || branches.findActive(usual).filter(b -> !b.sellsGoods()).isEmpty()) {
            return usual;
        }
        // The usual till is a store (ADR-048): the one branch the caller may sell at takes the refund; with none or
        // several they choose.
        List<UUID> shops = branches.all().stream()
                .filter(b -> b.active() && b.sellsGoods() && principal.may(PERMISSION, b.id()))
                .map(Branches.Branch::id)
                .toList();
        if (shops.size() == 1) {
            return shops.get(0);
        }
        if (shops.isEmpty()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "refund_branch_required",
                    "No shop can pay this out",
                    "No shop you work at can pay this out; credit the account or ask a shop.",
                    List.of());
        }
        throw new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "refund_branch_required",
                "Choose where the money comes from",
                "The sale was made at a store, and a store has no till. Choose the shop whose till pays the refund.",
                List.of(new FieldProblem(
                        "refund_branch_id", "refund_branch_required", "Choose the shop that pays the refund.")));
    }

    /** {@code amount x part / whole}, rounded half up once. */
    static long share(long amount, BigDecimal part, BigDecimal whole) {
        return BigDecimal.valueOf(amount)
                .multiply(part)
                .divide(whole, 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /**
     * Records a planned return: the stock back at the sale line's cost, the entries (the value returned against the refund
     * or the account, and the cost against the stock, shrinkage or the receiving branch's clearing), the numbered credit
     * note, the sale's settled amount and the audit row. The sale is locked by {@link #plan}.
     *
     * @param approvalId the approval that let it through, or null when no checker was needed
     * @param checkerId the checker, or null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    SaleReturn record(Plan p, String note, UUID makerId, UUID approvalId, UUID checkerId) {
        Sale s = p.sale();
        UUID id = UUID.randomUUID();
        UUID branch = s.branchId();
        String code = branches.all().stream()
                .filter(b -> b.id().equals(branch))
                .findFirst()
                .orElseThrow()
                .code();
        long seq = sequences.next("retail_credit_note_no:" + branch);
        String no = "CN%s-%06d".formatted(code, seq);
        List<Movement> movements = new ArrayList<>();
        for (Planned l : p.lines()) {
            if (l.disposition().equals("restock")) {
                movements.add(new Movement(
                        l.restockBranchId(),
                        l.saleLine().productId(),
                        "return",
                        l.baseQty(),
                        Quantities.perBase(l.costMinor(), l.baseQty()),
                        RETURN,
                        id,
                        l.id(),
                        null,
                        null));
            }
        }
        if (!movements.isEmpty()) {
            stock.record(p.on(), movements);
        }
        // The value: the returned goods against what is paid out or taken off what the customer owes.
        List<Leg> legs = new ArrayList<>();
        legs.add(Leg.debit("sales_returns", p.totalMinor()));
        // Money leaves the till of the refund branch: in the sale's own branch it is one entry; elsewhere the sale's
        // branch
        // owes it through inter-branch clearing and that branch pays it out in an entry of its own, as a transfer does.
        UUID payBranch = p.refundBranchId();
        boolean apart = p.refundMinor() > 0 && !payBranch.equals(branch);
        if (p.refundMinor() > 0) {
            legs.add(
                    apart
                            ? Leg.credit("interbranch_clearing", p.refundMinor())
                            : Leg.credit(SalesService.accountFor(p.refundMethod()), p.refundMinor()));
        }
        if (p.accountCreditMinor() > 0) {
            legs.add(Leg.credit("trade_debtors", p.accountCreditMinor()).withSubledger(SalesService.SALE, s.id()));
        }
        UUID entry = books.post(new Posting(
                        branch,
                        p.on(),
                        no,
                        "Credit note " + no + " for " + s.saleNo(),
                        RETURN,
                        id,
                        RETURN + ":" + id,
                        legs))
                .map(PostedEntry::entryId)
                .orElse(null);
        UUID refundEntry = apart
                ? books.post(new Posting(
                                payBranch,
                                p.on(),
                                no,
                                "Credit note " + no + " paid out for " + code,
                                RETURN,
                                id,
                                RETURN + "_refund:" + id,
                                List.of(
                                        Leg.debit("interbranch_clearing", p.refundMinor()),
                                        Leg.credit(SalesService.accountFor(p.refundMethod()), p.refundMinor()))))
                        .map(PostedEntry::entryId)
                        .orElse(null)
                : null;
        // The cost: at the original cost of the sale line. Restocked in the sale's own branch it goes back to
        // inventory;
        // damaged goods go to stock shrinkage; stock restocked in another branch goes through inter-branch clearing,
        // with
        // one entry in that branch, as a transfer does.
        long here = 0;
        long writtenOff = 0;
        Map<UUID, Long> elsewhere = new TreeMap<>();
        for (Planned l : p.lines()) {
            if (l.disposition().equals("write_off")) {
                writtenOff = Math.addExact(writtenOff, l.costMinor());
            } else if (l.restockBranchId().equals(branch)) {
                here = Math.addExact(here, l.costMinor());
            } else {
                elsewhere.merge(l.restockBranchId(), l.costMinor(), Long::sum);
            }
        }
        long clearing = elsewhere.values().stream().mapToLong(Long::longValue).sum();
        List<Leg> costLegs = new ArrayList<>();
        costLegs.add(Leg.debit("inventory", here));
        costLegs.add(Leg.debit("interbranch_clearing", clearing));
        costLegs.add(Leg.debit("stock_shrinkage", writtenOff));
        costLegs.add(Leg.credit("cost_of_goods_sold", p.costMinor()));
        UUID costEntry = books.post(new Posting(
                        branch,
                        p.on(),
                        no,
                        "Cost of returned goods " + no,
                        RETURN,
                        id,
                        RETURN + "_cost:" + id,
                        costLegs))
                .map(PostedEntry::entryId)
                .orElse(null);
        Map<UUID, UUID> stockEntries = new HashMap<>();
        for (Map.Entry<UUID, Long> e : elsewhere.entrySet()) {
            stockEntries.put(
                    e.getKey(),
                    books.post(new Posting(
                                    e.getKey(),
                                    p.on(),
                                    no,
                                    "Returned goods restocked from " + code,
                                    RETURN,
                                    id,
                                    RETURN + "_stock:" + id + ":" + e.getKey(),
                                    List.of(
                                            Leg.debit("inventory", e.getValue()),
                                            Leg.credit("interbranch_clearing", e.getValue()))))
                            .map(PostedEntry::entryId)
                            .orElse(null));
        }
        repo.insert(new NewReturn(
                id,
                no,
                seq,
                s.id(),
                branch,
                s.customerId(),
                p.on(),
                s.currency(),
                p.totalMinor(),
                p.costMinor(),
                p.accountCreditMinor(),
                p.refundMinor(),
                p.refundMethod(),
                p.refundMinor() > 0 ? payBranch : branch,
                p.refundChoice(),
                SalesService.blankToNull(note),
                entry,
                costEntry,
                refundEntry,
                approvalId,
                makerId,
                checkerId));
        for (Planned l : p.lines()) {
            repo.insertLine(
                    id,
                    new NewLine(
                            l.id(),
                            l.lineNo(),
                            l.saleLine().id(),
                            l.saleLine().productId(),
                            l.baseQty(),
                            l.unitId(),
                            l.unitQty(),
                            l.unitFactor(),
                            l.saleLine().unitCostMinor(),
                            l.totalMinor(),
                            l.costMinor(),
                            l.reason(),
                            l.disposition(),
                            l.restockBranchId(),
                            l.disposition().equals("restock")
                                            && !l.restockBranchId().equals(branch)
                                    ? stockEntries.get(l.restockBranchId())
                                    : null));
        }
        // The credit note against the customer's balance settles that much of the sale: the same column a payment
        // raises,
        // under the sale's lock. A return that credits nothing still moves the sale's version, so a request waiting for
        // a checker on this sale goes stale.
        if (p.accountCreditMinor() > 0) {
            sales.addPaid(s.id(), p.accountCreditMinor());
        } else {
            repo.touchSale(s.id());
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("credit_note_no", no);
        after.put("sale_no", s.saleNo());
        after.put("total_minor", p.totalMinor());
        after.put("account_credit_minor", p.accountCreditMinor());
        after.put("refund_minor", p.refundMinor());
        if (p.refundMethod() != null) {
            after.put("refund_method", p.refundMethod());
        }
        after.put("lines", p.lines().size());
        after.put("made_by", makerId);
        if (checkerId != null) {
            after.put("approved_by", checkerId);
            after.put("approval_id", approvalId);
        }
        audit.record(AuditLog.Entry.created("retail.sale_return.created", RETURN, id, branch, after));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    /**
     * Undoes a return as a whole, once: the stock it put back is taken out again (refused when it has been sold since),
     * every entry is reversed on today's date, and the account credit goes back onto the sale. The sale is locked first,
     * then the return, as {@link #plan} locks the sale before it writes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    SaleReturn voidReturn(UUID returnId, String reasonRaw) {
        capabilities.require(RetailCapabilities.RETURNS);
        Principal principal = CurrentPrincipal.require();
        String reason = reasonRaw.trim();
        if (reason.length() < MIN_REASON) {
            throw ApiException.validation(List.of(
                    new FieldProblem("reason", "invalid", "Give a reason of at least " + MIN_REASON + " characters.")));
        }
        Row seen = repo.find(returnId, false)
                .filter(x -> principal.may(PERMISSION, x.branchId()))
                .orElseThrow(ApiException::notFound);
        Sale lockedSale = sales.find(seen.saleId(), true)
                .orElseThrow(ApiException::notFound)
                .sale();
        if (lockedSale.writeOff() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "sale_written_off",
                    "Sale written off",
                    "The unpaid part of this sale was written off, so its returns cannot be voided.");
        }
        Row r = repo.find(returnId, true).orElseThrow(ApiException::notFound);
        if (r.status().equals("voided")) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "return_voided", "Return voided", "This return has been voided already.");
        }
        List<LineRow> lines = repo.lines(List.of(returnId)).getOrDefault(returnId, List.of());
        // Stock first: what the return put back must still be there to take out. The balances are locked in branch and
        // product order, as the ledger locks them.
        List<Recorded> moved = stock.bySource(RETURN, returnId);
        Map<List<UUID>, BigDecimal> net = new TreeMap<>(
                Comparator.<List<UUID>, UUID>comparing(k -> k.get(0)).thenComparing(k -> k.get(1)));
        for (Recorded m : moved) {
            net.merge(List.of(m.branchId(), m.productId()), m.qty().negate(), BigDecimal::add);
        }
        for (Map.Entry<List<UUID>, BigDecimal> e : net.entrySet()) {
            BigDecimal onHand = stock.lock(e.getKey().get(0), e.getKey().get(1));
            if (onHand.add(e.getValue()).signum() < 0) {
                throw ApiException.rule(
                        "return_stock_moved",
                        "The goods this return put back are no longer all in stock, so it cannot be voided.");
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
                                    RETURN_VOID,
                                    returnId,
                                    m.sourceLineId(),
                                    m.id(),
                                    reason))
                            .toList());
        }
        UUID reversal = null;
        if (r.journalEntryId() != null) {
            reversal = books.reverse(r.journalEntryId(), today, r.creditNoteNo(), RETURN_VOID + ":" + returnId)
                    .entryId();
        }
        if (r.costEntryId() != null) {
            books.reverse(r.costEntryId(), today, r.creditNoteNo(), RETURN + "_cost_void:" + returnId);
        }
        if (r.refundEntryId() != null) {
            books.reverse(r.refundEntryId(), today, r.creditNoteNo(), RETURN + "_refund_void:" + returnId);
        }
        lines.stream()
                .map(LineRow::stockEntryId)
                .filter(x -> x != null)
                .distinct()
                .forEach(e -> books.reverse(e, today, r.creditNoteNo(), RETURN + "_sv:" + e));
        Instant now = clock.now();
        if (repo.markVoided(returnId, principal.userId(), today, now, reason, reversal) == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "return_voided", "Return voided", "This return has been voided already.");
        }
        // After the return reads voided: the sales guard lets paid_minor fall only inside this path, bound to this
        // return and to exactly the account credit it gave.
        if (r.accountCreditMinor() > 0) {
            sales.releasePaidForReturn(r.saleId(), returnId, r.accountCreditMinor());
        } else {
            repo.touchSale(r.saleId());
        }
        audit.record(new AuditLog.Entry(
                "retail.sale_return.voided",
                RETURN,
                returnId,
                r.branchId(),
                Map.of("status", "completed"),
                Map.of("status", "voided", "reason", reason, "credit_note_no", r.creditNoteNo())));
        return view(repo.find(returnId, false).orElseThrow(), true);
    }

    // ---- Views -----------------------------------------------------------------------------

    /** A return for the caller; cost is left out unless the caller reads profit at the return's branch. */
    SaleReturn view(Row r, boolean withLines) {
        return view(r, withLines ? repo.lines(List.of(r.id())).getOrDefault(r.id(), List.of()) : List.of());
    }

    SaleReturn view(Row r, List<LineRow> lines) {
        boolean cost = CurrentPrincipal.require().may("retail.profit.read", r.branchId());
        return new SaleReturn(
                r.id(),
                r.creditNoteNo(),
                r.saleId(),
                r.saleNo(),
                r.branchId(),
                r.customerId(),
                r.customerName(),
                r.returnedOn(),
                r.currency(),
                r.totalMinor(),
                r.accountCreditMinor(),
                r.refundMinor(),
                r.refundMethod(),
                r.refundMethod() == null ? null : r.refundBranchId(),
                r.refundChoice(),
                r.note(),
                r.status(),
                lines.stream().map(l -> line(l, cost)).toList(),
                cost ? r.costMinor() : null,
                r.approvalId(),
                r.createdAt(),
                r.createdBy(),
                r.approvedBy(),
                r.voidedAt(),
                r.voidedOn(),
                r.voidedBy(),
                r.voidReason(),
                r.version());
    }

    private static ReturnLine line(LineRow l, boolean cost) {
        return new ReturnLine(
                l.id(),
                l.lineNo(),
                l.saleLineId(),
                l.productId(),
                l.code(),
                l.description(),
                Quantities.format(l.qty()),
                l.unitId(),
                l.unit(),
                ReturnsRepository.formatted(l.unitQty()),
                l.lineTotalMinor(),
                l.reason(),
                l.disposition(),
                l.restockBranchId(),
                cost ? l.unitCostMinor() : null,
                cost ? l.lineCostMinor() : null);
    }
}
