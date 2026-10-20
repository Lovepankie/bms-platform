package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.PackUnit;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.ProductSnapshot;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.BranchQty;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.Purchase;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.PurchaseLineRequest;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.PurchasePage;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.PurchaseRequest;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.Supplier;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.SupplierList;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.SupplierRequest;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.UpdateSupplierRequest;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.VoidPurchaseRequest;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingRepository.NewLine;
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
    private final Idempotency idempotency;
    private final RetailBranchContext branches;
    private final TenantSequences sequences;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final RetailCapabilities capabilities;

    PurchasingService(
            PurchasingRepository repo,
            RetailCatalogue catalogue,
            StockLedger stock,
            RetailBooks books,
            Idempotency idempotency,
            RetailBranchContext branches,
            TenantSequences sequences,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            RetailCapabilities capabilities) {
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
        this.capabilities = capabilities;
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
        Supplier s = new Supplier(UUID.randomUUID(), r.name().trim(), blankToNull(r.contact()), true, null, 1);
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

    /** #146: edit the name or contact, or switch a supplier off; never deleted. Audited without anything but the changes. */
    @Transactional
    Supplier updateSupplier(UUID id, String ifMatch, UpdateSupplierRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        Supplier before = repo.supplier(id).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        String name = r.name() == null ? before.name() : r.name().trim();
        String contact = r.contact() == null ? before.contact() : blankToNull(r.contact());
        boolean active = r.active() == null ? before.active() : r.active();
        if (name.isEmpty()) {
            throw ApiException.validation(List.of(new FieldProblem("name", "invalid", "The name must not be blank.")));
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        change(was, now, "name", before.name(), name);
        change(was, now, "contact_changed", false, !java.util.Objects.equals(before.contact(), contact));
        change(was, now, "active", before.active(), active);
        if (now.isEmpty()) {
            return before;
        }
        try {
            if (repo.updateSupplier(id, name, contact, active, expected) == 0) {
                throw repo.supplier(id).map(s -> Versions.conflict(s.version())).orElseGet(ApiException::notFound);
            }
        } catch (DuplicateKeyException e) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "duplicate_supplier", "Duplicate", "A supplier with this name exists.");
        }
        audit.record(new AuditLog.Entry("retail.supplier.updated", "retail.supplier", id, null, was, now));
        return repo.supplier(id).orElseThrow();
    }

    private static void change(Map<String, Object> was, Map<String, Object> now, String key, Object x, Object y) {
        if (!java.util.Objects.equals(x, y)) {
            was.put(key, x);
            now.put(key, y);
        }
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
        } else if (r.supplierId() != null
                && !repo.supplier(r.supplierId()).orElseThrow().active()) {
            problems.add(new FieldProblem(
                    "supplier_id", "inactive_supplier", "This supplier is switched off. Choose another one."));
        }
        if (r.paymentMethod().equals("credit") && r.supplierId() == null) {
            problems.add(new FieldProblem("supplier_id", "required", "A credit purchase names its supplier."));
        }
        String currency = tenant.profile().currency();
        Set<UUID> products = new TreeSet<>();
        // ADR-033: the pack of each line entered in a pack unit, by line index; a base unit line has none.
        Map<Integer, PackUnit> packs = new java.util.HashMap<>();
        for (int i = 0; i < r.lines().size(); i++) {
            PurchaseLineRequest l = r.lines().get(i);
            String field = "lines[" + i + "]";
            ProductSnapshot p = catalogue.find(l.productId()).orElse(null);
            if (p == null) {
                problems.add(new FieldProblem(field + ".product_id", "unknown_product", "No such product."));
                continue;
            }
            if (l.unitId() != null && !l.unitId().equals(p.unitId())) {
                if (!capabilities.enabled(RetailCapabilities.UNITS)) {
                    problems.add(new FieldProblem(
                            field + ".unit_id",
                            "capability_not_enabled",
                            "Pack sizes are not enabled for this business."));
                    continue;
                }
                PackUnit pack = catalogue.packUnit(p.id(), l.unitId()).orElse(null);
                if (pack == null || !pack.active()) {
                    problems.add(new FieldProblem(
                            field + ".unit_id", "unknown_pack_unit", "This item is not bought in that unit."));
                    continue;
                }
                packs.put(i, pack);
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
                branches.resolveStocking(PERMISSION, b.branchId());
            }
        }
        products.forEach(catalogue::lock);

        UUID id = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        String purchaseNo = "RP%08d".formatted(sequences.next("retail_purchase_no"));
        List<NewLine> lines = new ArrayList<>();
        Map<UUID, Long> valueByBranch = new TreeMap<>();
        // What is owed or paid per branch is the amount as entered; what goes to inventory is the movements at the
        // cost per base unit. They differ only for a pack cost that does not divide exactly (purchase_rounding).
        Map<UUID, Long> stockByBranch = new TreeMap<>();
        // Per line, the quantity of each branch in base units (a base unit line: as entered).
        List<List<BigDecimal>> baseQtys = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            PurchaseLineRequest l = r.lines().get(i);
            PackUnit pack = packs.get(i);
            long costBase = pack == null ? l.costMinor() : Quantities.baseCost(l.costMinor(), pack.factor());
            if (costBase > RetailCatalogue.MAX_AMOUNT_MINOR) {
                throw ApiException.rule(
                        "amount_out_of_range", "The cost on line " + (i + 1) + " is too large to compute.");
            }
            long lineTotal = 0;
            BigDecimal qtyTotal = BigDecimal.ZERO;
            BigDecimal unitQtyTotal = BigDecimal.ZERO;
            List<BigDecimal> bases = new ArrayList<>();
            for (BranchQty b : l.qtyByBranch()) {
                BigDecimal base = b.qty();
                if (pack != null) {
                    base = Quantities.toBase(b.qty(), pack.factor())
                            .orElseThrow(() -> ApiException.rule(
                                    "quantity_not_representable",
                                    b.qty() + " " + pack.unit() + " does not convert exactly to thousandths of the"
                                            + " item's unit. Change the quantity."));
                    unitQtyTotal = unitQtyTotal.add(b.qty());
                }
                long entered = Quantities.value(b.qty(), l.costMinor());
                long stocked = pack == null ? entered : Quantities.value(base, costBase);
                lineTotal = Math.addExact(lineTotal, entered);
                qtyTotal = qtyTotal.add(base);
                bases.add(base);
                valueByBranch.merge(b.branchId(), entered, Math::addExact);
                stockByBranch.merge(b.branchId(), stocked, Math::addExact);
            }
            baseQtys.add(bases);
            lines.add(new NewLine(
                    UUID.randomUUID(),
                    i + 1,
                    l.productId(),
                    costBase,
                    l.sellMinor(),
                    qtyTotal,
                    lineTotal,
                    pack == null ? null : pack.unitId(),
                    pack == null ? null : pack.factor(),
                    pack == null ? null : unitQtyTotal,
                    pack == null ? null : l.costMinor()));
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
            List<BranchQty> entered = r.lines().get(i).qtyByBranch();
            for (int j = 0; j < entered.size(); j++) {
                BranchQty b = entered.get(j);
                movements.add(new Movement(
                        b.branchId(),
                        line.productId(),
                        "purchase",
                        baseQtys.get(i).get(j),
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
            long stocked = stockByBranch.get(branch);
            // Zero for every base unit line, so a restock without packs posts exactly as before.
            long rounding = value - stocked;
            List<Leg> legs = new ArrayList<>(List.of(Leg.debit("inventory", stocked), pay));
            if (rounding > 0) {
                legs.add(Leg.debit("purchase_rounding", rounding));
            } else if (rounding < 0) {
                legs.add(Leg.credit("purchase_rounding", -rounding));
            }
            books.post(new Posting(
                    branch,
                    r.purchasedOn(),
                    purchaseNo,
                    "Restock " + purchaseNo,
                    PURCHASE,
                    id,
                    "retail.purchase:" + id + ":" + branch,
                    legs));
        });
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("purchase_no", purchaseNo);
        after.put("supplier_id", r.supplierId());
        after.put("payment_method", r.paymentMethod());
        after.put("lines", lines.size());
        audit.record(AuditLog.Entry.created("retail.purchase.created", PURCHASE, id, null, after));
        return repo.find(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    PurchasePage list(
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID supplierId,
            UUID categoryId,
            UUID productId,
            String status,
            boolean newestFirst,
            Integer limit,
            String cursor) {
        if (status != null && !status.equals("posted") && !status.equals("voided")) {
            throw ApiException.validation(
                    List.of(new FieldProblem("status", "invalid", "status is posted or voided.")));
        }
        List<UUID> filter = CurrentPrincipal.require().branchFilter(PERMISSION, null);
        // Which restocks are listed follows the branch asked for; what each card shows (and may be corrected from)
        // follows the caller's own scope.
        List<UUID> listed = CurrentPrincipal.require().branchFilter(PERMISSION, branchIds);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Instant afterCreated = null;
        UUID afterId = null;
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        if (after != null) {
            afterCreated = after.at();
            afterId = after.id();
        }
        List<Purchase> rows = repo.page(
                filter,
                listed,
                from,
                to,
                supplierId,
                categoryId,
                productId,
                status,
                newestFirst,
                afterCreated,
                afterId,
                size + 1);
        boolean more = rows.size() > size;
        List<Purchase> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        items.getLast().createdAt() + "|" + items.getLast().id())
                : null;
        return new PurchasePage(List.copyOf(items), next);
    }

    /** One restock with the quantities of the branches the caller may see; absent when it added stock to none of them. */
    @Transactional(readOnly = true)
    Purchase get(UUID id) {
        List<UUID> filter = CurrentPrincipal.require().branchFilter(PERMISSION, null);
        if (filter != null && filter.isEmpty()) {
            throw ApiException.notFound();
        }
        return repo.find(id, filter).orElseThrow(ApiException::notFound);
    }

    // ---- Void a restock (issue #351, ADR-044) -----------------------------------------------

    static final int MIN_REASON = 5;
    static final String PURCHASE_VOID = "retail.purchase_void";

    @Transactional
    Outcome<Purchase> voidPurchase(String idempotencyKey, UUID id, VoidPurchaseRequest r) {
        return idempotency.once(
                idempotencyKey, "POST", PATH + "/" + id + "/void", r, Purchase.class, () -> voidNow(id, r));
    }

    /**
     * Undoes a restock as a whole, once, in one transaction: the stock it added is taken out again (refused when some of it
     * was sold, used or moved since), its journal entries are reversed today, the product prices it set go back while it is
     * still the latest price event, and a row records who, when and why. Nothing is deleted: the restock shows as Voided.
     * Needs {@code retail.purchase.create} at every branch the restock added stock to. Locks: the restock, the supplier, the
     * products in id order, then the stock balances in branch and product order, as a restock takes them.
     */
    private Purchase voidNow(UUID id, VoidPurchaseRequest request) {
        Principal principal = CurrentPrincipal.require();
        String reason = request.reason().trim();
        if (reason.length() < MIN_REASON) {
            throw ApiException.validation(List.of(
                    new FieldProblem("reason", "invalid", "Give a reason of at least " + MIN_REASON + " characters.")));
        }
        Purchase seen = repo.find(id).orElseThrow(ApiException::notFound);
        List<Recorded> moved = stock.bySource(PURCHASE, id);
        List<UUID> touched =
                moved.stream().map(Recorded::branchId).distinct().sorted().toList();
        if (touched.stream().noneMatch(b -> principal.may(PERMISSION, b))) {
            throw ApiException.notFound();
        }
        if (!touched.stream().allMatch(b -> principal.may(PERMISSION, b))) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "This restock added stock at a branch you do not manage, so only someone who manages every branch it"
                            + " touched can void it.");
        }
        repo.lockPurchase(id);
        // A closed branch, or one that keeps no goods, takes no stock movement and no entry (issue #329, ADR-041).
        touched.forEach(b -> branches.requireStocks(b, "branch_id"));
        if (seen.supplierId() != null) {
            repo.lockSupplier(seen.supplierId());
        }
        if (repo.voidOf(id).isPresent()) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "purchase_voided", "Restock voided", "This restock has been voided already.");
        }
        if (repo.historical(id)) {
            throw ApiException.rule(
                    "purchase_historical",
                    "This restock was imported from the old records, so it cannot be voided here. Use Correct the quantity"
                            + " on the stock list instead.");
        }
        if (repo.paidAgainst(id)) {
            throw ApiException.rule(
                    "purchase_paid",
                    "A payment to the supplier has been recorded against this restock. Reverse that payment first, then"
                            + " void the restock.");
        }
        moved.stream().map(Recorded::productId).distinct().sorted().forEach(catalogue::lock);
        Map<UUID, Map<UUID, BigDecimal>> added = new TreeMap<>();
        for (Recorded m : moved) {
            added.computeIfAbsent(m.branchId(), k -> new TreeMap<>()).merge(m.productId(), m.qty(), BigDecimal::add);
        }
        for (Map.Entry<UUID, Map<UUID, BigDecimal>> branch : added.entrySet()) {
            for (Map.Entry<UUID, BigDecimal> product : branch.getValue().entrySet()) {
                BigDecimal onHand = stock.lock(branch.getKey(), product.getKey());
                if (onHand.compareTo(product.getValue()) < 0) {
                    ProductSnapshot p = catalogue.find(product.getKey()).orElseThrow();
                    throw ApiException.rule(
                            "purchase_stock_moved",
                            p.description() + ": this restock added " + plain(product.getValue()) + " " + p.unit()
                                    + " but only " + plain(onHand) + " are in stock there now, because some were sold,"
                                    + " used or moved. It cannot be voided. Use Correct the quantity on the stock list,"
                                    + " or count the stock.");
                }
            }
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        stock.record(
                today,
                moved.stream()
                        .map(m -> new Movement(
                                m.branchId(),
                                m.productId(),
                                "adjustment",
                                m.qty().negate(),
                                m.unitCostMinor(),
                                PURCHASE_VOID,
                                id,
                                m.sourceLineId(),
                                m.id(),
                                reason))
                        .toList());
        for (UUID entry : repo.entriesOf(id)) {
            books.reverse(entry, today, seen.purchaseNo(), PURCHASE_VOID + ":" + entry);
        }
        for (UUID productId :
                moved.stream().map(Recorded::productId).distinct().sorted().toList()) {
            catalogue.revertPurchasePrices(
                    productId, id, "Restock " + seen.purchaseNo() + " voided", repo.laterPurchase(productId, id));
        }
        repo.insertVoid(id, today, reason, principal.userId());
        audit.record(new AuditLog.Entry(
                "retail.purchase.voided",
                PURCHASE,
                id,
                null,
                Map.of("status", "posted"),
                Map.of("status", "voided", "reason", reason, "purchase_no", seen.purchaseNo())));
        return repo.find(id, principal.branchFilter(PERMISSION, null)).orElseThrow();
    }

    private static String plain(BigDecimal qty) {
        return qty.stripTrailingZeros().toPlainString();
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
