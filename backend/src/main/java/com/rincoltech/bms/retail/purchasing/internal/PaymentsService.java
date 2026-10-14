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
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.Allocation;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.AllocationRequest;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.OpenItem;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.OpenItems;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.Payment;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.PaymentList;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.PaymentRequest;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsApi.ReverseRequest;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsRepository.AllocationRow;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsRepository.NewPayment;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsRepository.Open;
import com.rincoltech.bms.retail.purchasing.internal.PaymentsRepository.Row;
import com.rincoltech.bms.retail.purchasing.internal.PurchaseLines.Numbered;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier payments (purchasing capability; ADR-033, issue #198; docs/specs/trade-capabilities.md section 7.1). A payment of
 * cash, mobile money or bank is allocated, when it is recorded, to the supplier's open invoices and credit restocks, oldest
 * due date first or as the caller chose, so a part payment leaves the rest open and one payment can settle several invoices.
 * Every unit of the amount is allocated: nothing is held as a deposit. The supplier's row is locked first, so two payments
 * cannot spend the same balance. One entry posts in the paying branch: debit trade creditors (the supplier is the
 * subledger), credit the account of the method; a cash payment is a cash-out of that branch's till in the cash book. A payment
 * is reversed as a whole, once, and its allocations stop counting.
 */
@Service
class PaymentsService {

    static final String PAYMENT = "retail.supplier_payment";
    static final String PERMISSION = OrdersService.PAYABLE;
    static final String PAYMENTS = "/api/v1/retail/supplier-payments";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final PaymentsRepository repo;
    private final PurchasingRepository suppliers;
    private final PurchaseLines lines;
    private final RetailBooks books;
    private final RetailBranchContext branches;
    private final Idempotency idempotency;
    private final RetailCapabilities capabilities;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    PaymentsService(
            PaymentsRepository repo,
            PurchasingRepository suppliers,
            PurchaseLines lines,
            RetailBooks books,
            RetailBranchContext branches,
            Idempotency idempotency,
            RetailCapabilities capabilities,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit) {
        this.repo = repo;
        this.suppliers = suppliers;
        this.lines = lines;
        this.books = books;
        this.branches = branches;
        this.idempotency = idempotency;
        this.capabilities = capabilities;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
    }

    static String accountFor(String method) {
        return switch (method) {
            case "cash" -> "cash_on_hand";
            case "mobile_money" -> "mobile_money";
            case "bank" -> "bank";
            default -> throw new IllegalArgumentException(method);
        };
    }

    /** The key of an open item in a request: an invoice or a restock. */
    private record Target(String kind, UUID id) {}

    @Transactional
    Outcome<Payment> create(String key, PaymentRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(key, "POST", PAYMENTS, r, Payment.class, () -> pay(r));
    }

    private Payment pay(PaymentRequest r) {
        Principal principal = CurrentPrincipal.require();
        UUID branch = branches.resolve(PERMISSION, r.branchId());
        if (!suppliers.lockSupplier(r.supplierId())) {
            throw ApiException.validation(
                    List.of(new FieldProblem("supplier_id", "unknown_supplier", "No such supplier.")));
        }
        Map<Target, Open> open = new LinkedHashMap<>();
        for (Open o : inScope(repo.open(r.supplierId()), principal)) {
            open.put(new Target(o.kind(), o.id()), o);
        }
        boolean manual = "manual".equals(r.allocation());
        boolean given = r.allocations() != null && !r.allocations().isEmpty();
        if (manual != given) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "allocations",
                    manual ? "required" : "invalid",
                    manual
                            ? "Name what the payment pays."
                            : "Allocations are for allocation manual. Leave them out to pay the oldest first.")));
        }
        Map<Target, Long> split = new LinkedHashMap<>();
        if (manual) {
            List<FieldProblem> problems = new ArrayList<>();
            long sum = 0;
            for (int i = 0; i < r.allocations().size(); i++) {
                AllocationRequest a = r.allocations().get(i);
                String field = "allocations[" + i + "]";
                if ((a.billId() == null) == (a.purchaseId() == null)) {
                    problems.add(new FieldProblem(field, "invalid", "Name an invoice or a restock, not both."));
                    continue;
                }
                Target t = a.billId() != null ? new Target("bill", a.billId()) : new Target("restock", a.purchaseId());
                Open o = open.get(t);
                if (o == null) {
                    problems.add(
                            new FieldProblem(field, "unknown_open_item", "That is not an open item of this supplier."));
                    continue;
                }
                if (split.containsKey(t)) {
                    problems.add(new FieldProblem(field, "duplicate", "Name each item once."));
                    continue;
                }
                if (a.amountMinor() > o.openMinor()) {
                    throw ApiException.rule(
                            "allocation_exceeds_open",
                            o.number() + " has " + o.openMinor() + " left to pay, so " + a.amountMinor()
                                    + " cannot be allocated to it.");
                }
                split.put(t, a.amountMinor());
                sum = Math.addExact(sum, a.amountMinor());
            }
            if (!problems.isEmpty()) {
                throw ApiException.validation(problems);
            }
            if (sum != r.amountMinor()) {
                throw ApiException.rule(
                        "allocation_total_mismatch",
                        "The allocations come to " + sum + " but the payment is " + r.amountMinor()
                                + ". Every part of a payment is allocated.");
            }
        } else {
            long left = r.amountMinor();
            List<Open> order = new ArrayList<>(open.values());
            order.sort(Comparator.comparing(Open::dueDate)
                    .thenComparing(Open::date)
                    .thenComparing(Open::createdAt)
                    .thenComparing(Open::id));
            for (Open o : order) {
                if (left == 0) {
                    break;
                }
                long take = Math.min(left, o.openMinor());
                split.put(new Target(o.kind(), o.id()), take);
                left -= take;
            }
            if (left > 0) {
                long owed = open.values().stream().mapToLong(Open::openMinor).sum();
                throw ApiException.rule(
                        "payment_exceeds_open",
                        "This supplier is owed " + owed + " on open invoices and credit restocks, so " + r.amountMinor()
                                + " cannot be paid. A payment is always allocated to something that is owed.");
            }
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        UUID id = UUID.randomUUID();
        Numbered no = lines.next("SP", "retail_supplier_payment_no", branch);
        String reference = PurchasingService.blankToNull(r.reference());
        // The debt sits in the branch that owes it: an invoice's own branch, and for a credit restock each branch whose
        // trade creditors it credited, by its part (BranchSplit). The paying branch's entry credits the method's
        // account
        // for all of it and debits its own creditors; for what is owed elsewhere it debits inter-branch clearing, and
        // each
        // such branch debits its trade creditors against clearing in an entry of its own, as a transfer does, so no
        // branch
        // is left short or over.
        Map<UUID, Long> byHome = new TreeMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<Target, Long> e : split.entrySet()) {
            Open o = open.get(e.getKey());
            Map<UUID, Long> parts = o.kind().equals("bill")
                    ? Map.of(o.branchIds().iterator().next(), e.getValue())
                    : BranchSplit.split(o.owedBy(), e.getValue());
            for (Map.Entry<UUID, Long> part : parts.entrySet()) {
                byHome.merge(part.getKey(), part.getValue(), Math::addExact);
                rows.add(new Object[] {e.getKey(), part.getKey(), part.getValue()});
            }
        }
        long here = byHome.getOrDefault(branch, 0L);
        long apart = r.amountMinor() - here;
        UUID entry = books.post(new Posting(
                        branch,
                        today,
                        no.no(),
                        "Supplier payment " + no.no(),
                        PAYMENT,
                        id,
                        PAYMENT + ":" + id,
                        List.of(
                                Leg.debit("trade_creditors", here).withSubledger("retail.supplier", r.supplierId()),
                                Leg.debit("interbranch_clearing", apart),
                                Leg.credit(accountFor(r.method()), r.amountMinor()))))
                .map(e -> e.entryId())
                .orElseThrow();
        Map<UUID, UUID> others = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> e : byHome.entrySet()) {
            if (!e.getKey().equals(branch)) {
                books.post(new Posting(
                                e.getKey(),
                                today,
                                no.no(),
                                "Supplier payment " + no.no() + " made by another branch",
                                PAYMENT,
                                id,
                                PAYMENT + ":" + id + ":" + e.getKey(),
                                List.of(
                                        Leg.debit("trade_creditors", e.getValue())
                                                .withSubledger("retail.supplier", r.supplierId()),
                                        Leg.credit("interbranch_clearing", e.getValue()))))
                        .ifPresent(posted -> others.put(e.getKey(), posted.entryId()));
            }
        }
        repo.insert(new NewPayment(
                id,
                no.no(),
                no.seq(),
                branch,
                r.supplierId(),
                today,
                tenant.profile().currency(),
                r.method(),
                r.amountMinor(),
                manual ? "manual" : "oldest_first",
                reference,
                PurchasingService.blankToNull(r.note()),
                entry,
                principal.userId()));
        others.forEach((home, other) -> repo.insertEntry(id, home, other));
        int n = 1;
        for (Object[] row : rows) {
            Target t = (Target) row[0];
            repo.insertAllocation(
                    id,
                    n++,
                    t.kind().equals("bill") ? t.id() : null,
                    t.kind().equals("restock") ? t.id() : null,
                    (UUID) row[1],
                    (Long) row[2]);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("payment_no", no.no());
        after.put("supplier_id", r.supplierId());
        after.put("method", r.method());
        after.put("amount_minor", r.amountMinor());
        after.put("allocations", split.size());
        audit.record(AuditLog.Entry.created("retail.supplier_payment.created", PAYMENT, id, branch, after));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    @Transactional
    Outcome<Payment> reverse(String key, UUID id, ReverseRequest r) {
        capabilities.require(RetailCapabilities.PURCHASING);
        return idempotency.once(
                key, "POST", PAYMENTS + "/" + id + "/reverse", r, Payment.class, () -> reverseNow(id, r));
    }

    /** Undoes a payment as a whole, once, by reversing its entry today; its allocations stop counting. */
    private Payment reverseNow(UUID id, ReverseRequest request) {
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
        Row p = repo.find(id, true).orElseThrow(ApiException::notFound);
        if (p.status().equals("reversed")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "payment_reversed",
                    "Payment reversed",
                    "This payment has been reversed already.");
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        UUID reversal = books.reverse(p.journalEntryId(), today, p.paymentNo(), PAYMENT + "_reverse:" + id)
                .entryId();
        for (UUID other : repo.entries(id)) {
            books.reverse(other, today, p.paymentNo(), PAYMENT + "_reverse:" + other);
        }
        Instant now = clock.now();
        if (repo.markReversed(id, principal.userId(), today, now, reason, reversal) == 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "payment_reversed",
                    "Payment reversed",
                    "This payment has been reversed already.");
        }
        audit.record(new AuditLog.Entry(
                "retail.supplier_payment.reversed",
                PAYMENT,
                id,
                p.branchId(),
                Map.of("status", "posted"),
                Map.of("status", "reversed", "reason", reason, "payment_no", p.paymentNo())));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    // ---- Reads -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    Payment get(UUID id) {
        capabilities.require(RetailCapabilities.PURCHASING);
        Principal principal = CurrentPrincipal.require();
        return repo.find(id, false)
                .filter(x -> principal.may(PERMISSION, x.branchId()))
                .map(x -> view(x, true))
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    PaymentList list(
            List<UUID> branchIds,
            UUID supplierId,
            String status,
            LocalDate from,
            LocalDate to,
            String q,
            Integer limit,
            String cursor) {
        capabilities.require(RetailCapabilities.PURCHASING);
        if (status != null && !status.equals("posted") && !status.equals("reversed")) {
            throw ApiException.validation(
                    List.of(new FieldProblem("status", "invalid", "status is posted or reversed.")));
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
        return new PaymentList(items.stream().map(x -> view(x, false)).toList(), next);
    }

    /** What a supplier is owed on invoices and credit restocks in the caller's scope, oldest due first. */
    @Transactional(readOnly = true)
    OpenItems openItems(UUID supplierId) {
        capabilities.require(RetailCapabilities.PURCHASING);
        var supplier = suppliers.supplier(supplierId).orElseThrow(ApiException::notFound);
        LocalDate today = clock.today(tenant.profile().timezone());
        List<Open> open = inScope(repo.open(supplierId), CurrentPrincipal.require());
        open.sort(Comparator.comparing(Open::dueDate).thenComparing(Open::date).thenComparing(Open::id));
        List<OpenItem> items = open.stream()
                .map(o -> new OpenItem(
                        o.kind(),
                        o.id(),
                        o.number(),
                        o.supplierRef(),
                        o.date(),
                        o.dueDate(),
                        o.totalMinor(),
                        o.paidMinor(),
                        o.creditedMinor(),
                        o.openMinor(),
                        daysPastDue(o.dueDate(), today)))
                .toList();
        return new OpenItems(
                supplierId,
                supplier.name(),
                today,
                items,
                items.stream().mapToLong(OpenItem::openMinor).reduce(0, Math::addExact));
    }

    /**
     * The open items the caller may touch: an invoice for a branch of their scope, a restock whose every branch is in it
     * (a restock that put stock into a branch outside the scope is not theirs to pay). A mutable list.
     */
    static List<Open> inScope(List<Open> all, Principal principal) {
        List<Open> out = new ArrayList<>();
        for (Open o : all) {
            Set<UUID> bs = new HashSet<>(o.branchIds());
            if (!bs.isEmpty() && bs.stream().allMatch(b -> principal.may(PERMISSION, b))) {
                out.add(o);
            }
        }
        return out;
    }

    static int daysPastDue(LocalDate due, LocalDate today) {
        return (int) ChronoUnit.DAYS.between(due, today);
    }

    Payment view(Row p, boolean detail) {
        List<Allocation> as = null;
        if (detail) {
            as = grouped(repo.allocations(List.of(p.id())).getOrDefault(p.id(), List.of()));
        }
        return new Payment(
                p.id(),
                p.paymentNo(),
                p.branchId(),
                p.supplierId(),
                p.supplierName(),
                p.paidOn(),
                p.currency(),
                p.method(),
                p.amountMinor(),
                p.allocation(),
                p.reference(),
                p.note(),
                p.status(),
                as,
                p.createdAt(),
                p.createdBy(),
                p.reversedAt(),
                p.reversedOn(),
                p.reversedBy(),
                p.reverseReason(),
                p.version());
    }

    /** One line per item paid: the rows are per branch, and the payment's view is per invoice or restock. */
    private static List<Allocation> grouped(List<AllocationRow> rows) {
        Map<String, Allocation> out = new LinkedHashMap<>();
        for (AllocationRow a : rows) {
            String key = a.billId() != null ? "b" + a.billId() : "p" + a.purchaseId();
            out.merge(
                    key,
                    new Allocation(
                            a.billId(), a.billNo(), a.supplierRef(), a.purchaseId(), a.purchaseNo(), a.amountMinor()),
                    (x, y) -> new Allocation(
                            x.billId(),
                            x.billNo(),
                            x.supplierRef(),
                            x.purchaseId(),
                            x.purchaseNo(),
                            x.amountMinor() + y.amountMinor()));
        }
        return List.copyOf(out.values());
    }
}
