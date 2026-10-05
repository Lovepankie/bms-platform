package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.ProductSnapshot;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Customer;
import com.rincoltech.bms.retail.sales.internal.SalesApi.CustomerBalance;
import com.rincoltech.bms.retail.sales.internal.SalesApi.CustomerList;
import com.rincoltech.bms.retail.sales.internal.SalesApi.CustomerRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.OpenSale;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Sale;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleLine;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleLineRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SalePage;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.VoidRequest;
import com.rincoltech.bms.retail.sales.internal.SalesRepository.Header;
import com.rincoltech.bms.retail.sales.internal.SalesRepository.NewLine;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailIdempotency;
import com.rincoltech.bms.retail.stock.RetailIdempotency.Outcome;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.Movement;
import com.rincoltech.bms.retail.stock.StockLedger.Recorded;
import java.time.Instant;
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
 * Sales and credit buyers (FR-RET-04, FR-RET-05, FR-RET-11, FR-RET-14). A sale and everything it
 * causes (lines with their snapshots, stock movements, two journal entries, the audit row, the
 * idempotency key) commit together or not at all.
 */
@Service
class SalesService {

    static final String SALE = "retail.sale";
    static final String SALE_VOID = "retail.sale_void";
    static final String PATH = "/api/v1/retail/sales";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final SalesRepository repo;
    private final RetailCatalogue catalogue;
    private final StockLedger stock;
    private final RetailBooks books;
    private final RetailIdempotency idempotency;
    private final RetailBranchContext branches;
    private final TenantSequences sequences;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    SalesService(
            SalesRepository repo,
            RetailCatalogue catalogue,
            StockLedger stock,
            RetailBooks books,
            RetailIdempotency idempotency,
            RetailBranchContext branches,
            TenantSequences sequences,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit) {
        this.repo = repo;
        this.catalogue = catalogue;
        this.stock = stock;
        this.books = books;
        this.idempotency = idempotency;
        this.branches = branches;
        this.sequences = sequences;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
    }

    /** The general ledger account a payment method lands in (chapter 6 section 6.11.1). */
    static String accountFor(String method) {
        return switch (method) {
            case "cash" -> "cash_on_hand";
            case "mobile_money" -> "mobile_money";
            case "bank" -> "bank";
            case "credit" -> "trade_debtors";
            default -> throw new IllegalArgumentException(method);
        };
    }

    @Transactional
    Outcome<Sale> create(String idempotencyKey, SaleRequest r) {
        Outcome<Sale> outcome = idempotency.once(idempotencyKey, "POST", PATH, r, Sale.class, () -> record(r));
        return outcome;
    }

    /** FR-RET-04, FR-RET-05, FR-RET-11. */
    private Sale record(SaleRequest r) {
        UUID branch = branches.resolve("retail.sale.create", r.branchId());
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate saleDate = r.saleDate() == null ? today : r.saleDate();
        List<FieldProblem> problems = new ArrayList<>();
        if (saleDate.isAfter(today)) {
            problems.add(new FieldProblem("sale_date", "invalid", "A sale cannot be dated in the future."));
        }
        boolean credit = r.paymentMethod().equals("credit");
        String buyerName = blankToNull(r.buyerName());
        if (r.customerId() != null && repo.customer(r.customerId()).isEmpty()) {
            problems.add(new FieldProblem("customer_id", "unknown_customer", "No such customer."));
        }
        if (credit && r.customerId() == null && buyerName == null) {
            problems.add(new FieldProblem("customer_id", "required", "A credit sale names its buyer."));
        }
        if (!credit && r.dueDate() != null) {
            problems.add(new FieldProblem("due_date", "invalid", "Only a credit sale has a payment date."));
        }
        if (r.dueDate() != null && r.dueDate().isBefore(saleDate)) {
            problems.add(new FieldProblem("due_date", "invalid", "The payment date cannot be before the sale."));
        }
        String currency = tenant.profile().currency();
        List<NewLine> lines = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            SaleLineRequest l = r.lines().get(i);
            String field = "lines[" + i + "]";
            ProductSnapshot p = catalogue.find(l.productId()).orElse(null);
            if (p == null) {
                problems.add(new FieldProblem(field + ".product_id", "unknown_product", "No such product."));
                continue;
            }
            if (!p.active()) {
                problems.add(new FieldProblem(field + ".product_id", "product_inactive", "The product is inactive."));
                continue;
            }
            if (!p.currency().equals(currency)) {
                problems.add(new FieldProblem(field + ".product_id", "currency_mismatch", "Priced in " + p.currency()));
                continue;
            }
            if (!Quantities.exact(l.qty())) {
                problems.add(new FieldProblem(field + ".qty", "invalid", "At most three decimal places."));
                continue;
            }
            long price = l.unitPriceMinor() == null ? p.sellMinor() : l.unitPriceMinor();
            lines.add(new NewLine(
                    UUID.randomUUID(),
                    i + 1,
                    p.id(),
                    l.qty(),
                    price,
                    p.costMinor(),
                    Quantities.value(l.qty(), price),
                    Quantities.value(l.qty(), p.costMinor())));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        long total = lines.stream().mapToLong(NewLine::lineTotalMinor).reduce(0, Math::addExact);
        long cost = lines.stream().mapToLong(NewLine::lineCostMinor).reduce(0, Math::addExact);
        UUID id = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        String saleNo = "RS%08d".formatted(sequences.next("retail_sale_no"));
        Sale header = new Sale(
                id,
                saleNo,
                branch,
                saleDate,
                r.paymentMethod(),
                r.customerId(),
                buyerName,
                blankToNull(r.buyerContact()),
                r.dueDate(),
                "completed",
                currency,
                total,
                credit ? 0 : total,
                credit ? total : 0,
                cost,
                total - cost,
                List.of(),
                false,
                null,
                by,
                null,
                null,
                null,
                1);
        repo.insert(header);
        lines.forEach(l -> repo.insertLine(id, l));
        stock.record(lines.stream()
                .map(l -> new Movement(
                        branch,
                        l.productId(),
                        "sale",
                        l.qty().negate(),
                        l.unitCostMinor(),
                        SALE,
                        id,
                        l.id(),
                        null,
                        null))
                .toList());
        Leg receivable = Leg.debit(accountFor(r.paymentMethod()), total);
        if (credit) {
            receivable = receivable.withSubledger(SALE, id);
        }
        UUID saleEntry = books.post(new Posting(
                        branch,
                        saleDate,
                        saleNo,
                        "Sale " + saleNo,
                        SALE,
                        id,
                        "retail.sale:" + id,
                        List.of(receivable, Leg.credit("sales_revenue", total))))
                .map(PostedEntry::entryId)
                .orElse(null);
        UUID costEntry = books.post(new Posting(
                        branch,
                        saleDate,
                        saleNo,
                        "Cost of sale " + saleNo,
                        SALE,
                        id,
                        "retail.sale_cost:" + id,
                        List.of(Leg.debit("cost_of_goods_sold", cost), Leg.credit("inventory", cost))))
                .map(PostedEntry::entryId)
                .orElse(null);
        repo.setEntries(id, saleEntry, costEntry);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("sale_no", saleNo);
        after.put("payment_method", r.paymentMethod());
        after.put("total_minor", total);
        after.put("cost_total_minor", cost);
        after.put("lines", lines.size());
        audit.record(AuditLog.Entry.created("retail.sale.created", SALE, id, branch, after));
        return visible(repo.find(id, false).orElseThrow().sale());
    }

    @Transactional(readOnly = true)
    SalePage list(List<UUID> branchIds, LocalDate from, LocalDate to, UUID customerId, Integer limit, String cursor) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter("retail.sale.read", branchIds);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Instant afterCreated = null;
        UUID afterId = null;
        String after = Cursor.decode(cursor).orElse(null);
        if (after != null) {
            String[] parts = after.split("\\|", 2);
            afterCreated = Instant.parse(parts[0]);
            afterId = UUID.fromString(parts[1]);
        }
        List<Sale> rows = repo.page(filter, from, to, customerId, afterCreated, afterId, size + 1);
        boolean more = rows.size() > size;
        List<Sale> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        items.getLast().createdAt() + "|" + items.getLast().id())
                : null;
        return new SalePage(items.stream().map(SalesService::visible).toList(), next);
    }

    @Transactional(readOnly = true)
    Sale get(UUID id) {
        Principal principal = CurrentPrincipal.require();
        return repo.find(id, false)
                .map(Header::sale)
                .filter(s -> principal.may("retail.sale.read", s.branchId()))
                .map(SalesService::visible)
                .orElseThrow(ApiException::notFound);
    }

    /**
     * FR-RET-04, FR-RET-11: a void reverses each stock movement with a {@code return} movement and
     * each journal entry with its reversal, under the sale's row lock. A credit sale that has taken
     * a payment is not voided.
     */
    @Transactional
    Sale voidSale(UUID id, VoidRequest r) {
        Principal principal = CurrentPrincipal.require();
        Header h = repo.find(id, true)
                .filter(x -> principal.may("retail.sale.void", x.sale().branchId()))
                .orElseThrow(ApiException::notFound);
        Sale s = h.sale();
        if (s.status().equals("voided")) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "sale_voided", "Sale voided", "This sale has been voided already.");
        }
        if (s.paymentMethod().equals("credit") && s.paidMinor() > 0) {
            throw ApiException.rule("sale_has_payments", "A credit sale with payments against it cannot be voided.");
        }
        String reason = r.reason().trim();
        List<Recorded> moved = stock.bySource(SALE, id);
        stock.record(moved.stream()
                .map(m -> new Movement(
                        m.branchId(),
                        m.productId(),
                        "return",
                        m.qty().negate(),
                        m.unitCostMinor(),
                        SALE_VOID,
                        id,
                        m.sourceLineId(),
                        m.id(),
                        reason))
                .toList());
        LocalDate today = clock.today(tenant.profile().timezone());
        if (h.saleEntryId() != null) {
            books.reverse(h.saleEntryId(), today, "Void " + s.saleNo(), "retail.sale_void:" + id);
        }
        if (h.costEntryId() != null) {
            books.reverse(h.costEntryId(), today, "Void " + s.saleNo(), "retail.sale_cost_void:" + id);
        }
        repo.markVoided(id, principal.userId(), reason);
        audit.record(new AuditLog.Entry(
                "retail.sale.voided",
                SALE,
                id,
                s.branchId(),
                Map.of("status", "completed"),
                Map.of("status", "voided", "reason", reason)));
        return visible(repo.find(id, false).orElseThrow().sale());
    }

    // ---- Customers -------------------------------------------------------------------------

    @Transactional
    Customer createCustomer(CustomerRequest r) {
        Customer c = new Customer(UUID.randomUUID(), r.name().trim(), blankToNull(r.contact()), null);
        repo.insertCustomer(c, CurrentPrincipal.require().userId());
        audit.record(AuditLog.Entry.created(
                "retail.customer.created", "retail.customer", c.id(), null, Map.of("name", c.name())));
        return repo.customer(c.id()).orElseThrow();
    }

    @Transactional(readOnly = true)
    CustomerList customers(String query, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        return new CustomerList(repo.customers(blankToNull(query), size));
    }

    /** FR-RET-05: what the customer owes on credit sales in the caller's branch scope. */
    @Transactional(readOnly = true)
    CustomerBalance balance(UUID customerId) {
        repo.customer(customerId).orElseThrow(ApiException::notFound);
        List<UUID> filter = CurrentPrincipal.require().branchFilter("retail.sale.read", null);
        List<OpenSale> open = repo.openSales(customerId, filter);
        long owed = open.stream().mapToLong(OpenSale::balanceMinor).reduce(0, Math::addExact);
        return new CustomerBalance(customerId, tenant.profile().currency(), owed, open);
    }

    static Sale visible(Sale s) {
        if (CurrentPrincipal.require().hasPermission("retail.profit.read")) {
            return s;
        }
        return new Sale(
                s.id(),
                s.saleNo(),
                s.branchId(),
                s.saleDate(),
                s.paymentMethod(),
                s.customerId(),
                s.buyerName(),
                s.buyerContact(),
                s.dueDate(),
                s.status(),
                s.currency(),
                s.totalMinor(),
                s.paidMinor(),
                s.balanceMinor(),
                null,
                null,
                s.lines().stream()
                        .map(l -> new SaleLine(
                                l.id(),
                                l.lineNo(),
                                l.productId(),
                                l.code(),
                                l.description(),
                                l.qty(),
                                l.unitPriceMinor(),
                                l.lineTotalMinor(),
                                null,
                                null))
                        .toList(),
                s.historical(),
                s.createdAt(),
                s.createdBy(),
                s.voidedAt(),
                s.voidedBy(),
                s.voidReason(),
                s.version());
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
