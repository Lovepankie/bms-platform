package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.ProductSnapshot;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.BranchQty;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.Purchase;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.PurchaseLineRequest;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.PurchasePage;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.PurchaseRequest;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.Supplier;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.SupplierList;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.SupplierRequest;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingRepository.NewLine;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailIdempotency;
import com.rincoltech.bms.retail.stock.RetailIdempotency.Outcome;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.Movement;
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
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppliers and restocks (FR-RET-06, FR-RET-11, FR-RET-14). A purchase, its prices on the products
 * and their history rows, its movements and its journal entries commit together or not at all.
 */
@Service
class PurchasingService {

    static final String PURCHASE = "retail.purchase";
    static final String PERMISSION = "retail.purchase.create";
    static final String PATH = "/api/v1/retail/purchases";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final PurchasingRepository repo;
    private final RetailCatalogue catalogue;
    private final StockLedger stock;
    private final RetailBooks books;
    private final RetailIdempotency idempotency;
    private final RetailBranchContext branches;
    private final TenantSequences sequences;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    PurchasingService(
            PurchasingRepository repo,
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

    static String accountFor(String method) {
        return switch (method) {
            case "cash" -> "cash_on_hand";
            case "bank" -> "bank";
            case "credit" -> "trade_creditors";
            default -> throw new IllegalArgumentException(method);
        };
    }

    // ---- Suppliers -----------------------------------------------------------------------

    @Transactional
    Supplier createSupplier(SupplierRequest r) {
        Supplier s = new Supplier(UUID.randomUUID(), r.name().trim(), blankToNull(r.contact()), true, null);
        try {
            repo.insertSupplier(s, CurrentPrincipal.require().userId());
        } catch (DuplicateKeyException e) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "duplicate_supplier", "Duplicate", "A supplier with this name exists.");
        }
        audit.record(AuditLog.Entry.created(
                "retail.supplier.created", "retail.supplier", s.id(), null, Map.of("name", s.name())));
        return repo.supplier(s.id()).orElseThrow();
    }

    @Transactional(readOnly = true)
    SupplierList suppliers() {
        return new SupplierList(repo.suppliers());
    }

    // ---- Purchases -----------------------------------------------------------------------

    @Transactional
    Outcome<Purchase> create(String idempotencyKey, PurchaseRequest r) {
        return idempotency.once(idempotencyKey, "POST", PATH, r, Purchase.class, () -> record(r));
    }

    /**
     * FR-RET-06: lines are applied in request order, so when two lines name one product the later
     * line's prices are the product's prices afterwards (latest restock wins), each change with its
     * own history row. Products are locked in id order first.
     */
    private Purchase record(PurchaseRequest r) {
        LocalDate today = clock.today(tenant.profile().timezone());
        List<FieldProblem> problems = new ArrayList<>();
        if (r.purchasedOn().isAfter(today)) {
            problems.add(new FieldProblem("purchased_on", "invalid", "A purchase cannot be dated in the future."));
        }
        if (r.supplierId() != null && repo.supplier(r.supplierId()).isEmpty()) {
            problems.add(new FieldProblem("supplier_id", "unknown_supplier", "No such supplier."));
        }
        if (r.paymentMethod().equals("credit") && r.supplierId() == null) {
            problems.add(new FieldProblem("supplier_id", "required", "A credit purchase names its supplier."));
        }
        String currency = tenant.profile().currency();
        Set<UUID> products = new TreeSet<>();
        for (int i = 0; i < r.lines().size(); i++) {
            PurchaseLineRequest l = r.lines().get(i);
            String field = "lines[" + i + "]";
            ProductSnapshot p = catalogue.find(l.productId()).orElse(null);
            if (p == null) {
                problems.add(new FieldProblem(field + ".product_id", "unknown_product", "No such product."));
                continue;
            }
            if (!p.currency().equals(currency)) {
                problems.add(new FieldProblem(field + ".product_id", "currency_mismatch", "Priced in " + p.currency()));
            }
            products.add(p.id());
            Set<UUID> seen = new HashSet<>();
            for (int j = 0; j < l.qtyByBranch().size(); j++) {
                BranchQty b = l.qtyByBranch().get(j);
                if (!seen.add(b.branchId())) {
                    problems.add(new FieldProblem(
                            field + ".qty_by_branch[" + j + "].branch_id", "duplicate", "Name each branch once."));
                }
                if (!Quantities.exact(b.qty())) {
                    problems.add(new FieldProblem(
                            field + ".qty_by_branch[" + j + "].qty", "invalid", "At most three decimal places."));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        for (PurchaseLineRequest l : r.lines()) {
            for (BranchQty b : l.qtyByBranch()) {
                branches.resolve(PERMISSION, b.branchId());
            }
        }
        products.forEach(catalogue::lock);

        UUID id = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        String purchaseNo = "RP%08d".formatted(sequences.next("retail_purchase_no"));
        List<NewLine> lines = new ArrayList<>();
        Map<UUID, Long> valueByBranch = new TreeMap<>();
        for (int i = 0; i < r.lines().size(); i++) {
            PurchaseLineRequest l = r.lines().get(i);
            long lineTotal = 0;
            BigDecimal qtyTotal = BigDecimal.ZERO;
            for (BranchQty b : l.qtyByBranch()) {
                long value = Quantities.value(b.qty(), l.costMinor());
                lineTotal = Math.addExact(lineTotal, value);
                qtyTotal = qtyTotal.add(b.qty());
                valueByBranch.merge(b.branchId(), value, Math::addExact);
            }
            lines.add(new NewLine(
                    UUID.randomUUID(), i + 1, l.productId(), l.costMinor(), l.sellMinor(), qtyTotal, lineTotal));
        }
        long total = lines.stream().mapToLong(NewLine::lineTotalMinor).reduce(0, Math::addExact);
        repo.insertPurchase(new Purchase(
                id,
                purchaseNo,
                r.supplierId(),
                r.purchasedOn(),
                r.paymentMethod(),
                currency,
                total,
                blankToNull(r.note()),
                List.of(),
                null,
                by));
        List<Movement> movements = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            NewLine line = lines.get(i);
            repo.insertLine(id, line);
            catalogue.applyPurchasePrices(line.productId(), line.costMinor(), line.sellMinor(), id);
            for (BranchQty b : r.lines().get(i).qtyByBranch()) {
                movements.add(new Movement(
                        b.branchId(),
                        line.productId(),
                        "purchase",
                        b.qty(),
                        line.costMinor(),
                        PURCHASE,
                        id,
                        line.id(),
                        null,
                        null));
            }
        }
        stock.record(r.purchasedOn(), movements);
        valueByBranch.forEach((branch, value) -> {
            Leg pay = Leg.credit(accountFor(r.paymentMethod()), value);
            if (r.paymentMethod().equals("credit")) {
                pay = pay.withSubledger("retail.supplier", r.supplierId());
            }
            books.post(new Posting(
                    branch,
                    r.purchasedOn(),
                    purchaseNo,
                    "Restock " + purchaseNo,
                    PURCHASE,
                    id,
                    "retail.purchase:" + id + ":" + branch,
                    List.of(Leg.debit("inventory", value), pay)));
        });
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("purchase_no", purchaseNo);
        after.put("supplier_id", r.supplierId());
        after.put("payment_method", r.paymentMethod());
        after.put("total_minor", total);
        after.put("lines", lines.size());
        audit.record(AuditLog.Entry.created("retail.purchase.created", PURCHASE, id, null, after));
        return repo.find(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    PurchasePage list(LocalDate from, LocalDate to, UUID supplierId, Integer limit, String cursor) {
        List<UUID> filter = CurrentPrincipal.require().branchFilter(PERMISSION, null);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Instant afterCreated = null;
        UUID afterId = null;
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        if (after != null) {
            afterCreated = after.at();
            afterId = after.id();
        }
        List<Purchase> rows = repo.page(filter, from, to, supplierId, afterCreated, afterId, size + 1);
        boolean more = rows.size() > size;
        List<Purchase> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        items.getLast().createdAt() + "|" + items.getLast().id())
                : null;
        return new PurchasePage(List.copyOf(items), next);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
