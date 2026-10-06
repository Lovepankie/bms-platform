package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Category;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CategoryList;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CategoryRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CreateProductRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceChange;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceEditRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceHistory;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Product;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.ProductPage;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Unit;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UnitList;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UnitRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdateProductRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The retail catalogue (FR-RET-01, FR-RET-02, FR-RET-14). One {@code @Transactional} method per
 * use case; every write is audited in the same transaction. Products are tenant-wide, not branch
 * bound, so the route's permission is the whole check. Cost fields leave this service only for a
 * principal holding {@code retail.profit.read}.
 */
@Service
class CatalogueService {

    static final String PRODUCT = "retail.product";
    static final String PROFIT_READ = "retail.profit.read";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final CatalogueRepository repo;
    private final CurrentTenant currentTenant;
    private final AuditLog audit;

    CatalogueService(CatalogueRepository repo, CurrentTenant currentTenant, AuditLog audit) {
        this.repo = repo;
        this.currentTenant = currentTenant;
        this.audit = audit;
    }

    // ---- Categories and units ------------------------------------------------------------

    @Transactional(readOnly = true)
    CategoryList categories() {
        return new CategoryList(repo.categories());
    }

    @Transactional(readOnly = true)
    UnitList units() {
        return new UnitList(repo.units());
    }

    @Transactional
    Category createCategory(CategoryRequest r) {
        Category c = new Category(UUID.randomUUID(), r.name().trim());
        try {
            repo.insertCategory(c.id(), c.name(), CurrentPrincipal.require().userId());
        } catch (DuplicateKeyException e) {
            throw duplicate("duplicate_category", "A category with this name exists.");
        }
        audit.record(AuditLog.Entry.created(
                "retail.category.created", "retail.category", c.id(), null, Map.of("name", c.name())));
        return c;
    }

    @Transactional
    Unit createUnit(UnitRequest r) {
        Unit u = new Unit(UUID.randomUUID(), r.name().trim());
        try {
            repo.insertUnit(u.id(), u.name(), CurrentPrincipal.require().userId());
        } catch (DuplicateKeyException e) {
            throw duplicate("duplicate_unit", "A unit with this name exists.");
        }
        audit.record(
                AuditLog.Entry.created("retail.unit.created", "retail.unit", u.id(), null, Map.of("name", u.name())));
        return u;
    }

    // ---- Products ------------------------------------------------------------------------

    /** FR-RET-01, FR-RET-02: the product and its first history row, source {@code initial}. */
    @Transactional
    Product create(CreateProductRequest r) {
        requireCategoryAndUnit(r.categoryId(), r.unitId());
        UUID by = CurrentPrincipal.require().userId();
        Product p = new Product(
                UUID.randomUUID(),
                code(r.code()),
                r.description().trim(),
                r.categoryId(),
                null,
                r.unitId(),
                null,
                r.sellMinor(),
                r.costMinor(),
                currentTenant.profile().currency(),
                true,
                null,
                null,
                1,
                null,
                null);
        try {
            repo.insert(p, by);
        } catch (DuplicateKeyException e) {
            throw duplicateCode();
        }
        repo.insertHistory(
                new PriceChange(
                        UUID.randomUUID(),
                        null,
                        by,
                        "initial",
                        null,
                        null,
                        r.costMinor(),
                        null,
                        r.sellMinor(),
                        p.currency(),
                        null),
                p.id());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("code", p.code());
        after.put("description", p.description());
        after.put("category_id", p.categoryId());
        after.put("unit_id", p.unitId());
        after.put("sell_minor", p.sellMinor());
        audit.record(AuditLog.Entry.created("retail.product.created", PRODUCT, p.id(), null, after));
        return visible(repo.find(p.id()).orElseThrow());
    }

    @Transactional(readOnly = true)
    ProductPage list(String query, UUID categoryId, Boolean active, UUID branchId, Integer limit, String cursor) {
        if (branchId != null && !CurrentPrincipal.require().may("retail.stock.read", branchId)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("branch_id", "unknown_branch", "No such branch in your scope.")));
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        String afterCode = null;
        UUID afterId = null;
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        if (after != null) {
            afterCode = after.sortKey();
            afterId = after.id();
        }
        String q = query == null || query.isBlank() ? null : query.trim();
        List<Product> rows = repo.page(q, categoryId, active, branchId, afterCode, afterId, size + 1);
        boolean more = rows.size() > size;
        List<Product> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(items.getLast().code() + "|" + items.getLast().id())
                : null;
        return new ProductPage(items.stream().map(this::visible).toList(), next);
    }

    @Transactional(readOnly = true)
    Product get(UUID id) {
        return visible(repo.find(id).orElseThrow(ApiException::notFound));
    }

    /** Non-price fields; omitted fields are unchanged. Prices change only through {@link #editPrices}. */
    @Transactional
    Product update(UUID id, String ifMatch, UpdateProductRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        Product before = repo.lock(id).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        requireCategoryAndUnit(
                r.categoryId() == null ? before.categoryId() : r.categoryId(),
                r.unitId() == null ? before.unitId() : r.unitId());
        Product after = new Product(
                id,
                r.code() == null ? before.code() : code(r.code()),
                r.description() == null ? before.description() : r.description().trim(),
                r.categoryId() == null ? before.categoryId() : r.categoryId(),
                null,
                r.unitId() == null ? before.unitId() : r.unitId(),
                null,
                before.sellMinor(),
                before.costMinor(),
                before.currency(),
                r.active() == null ? before.active() : r.active(),
                before.createdAt(),
                before.updatedAt(),
                before.version(),
                null,
                null);
        if (after.code().isEmpty() || after.description().isEmpty()) {
            throw ApiException.validation(
                    List.of(new FieldProblem("code", "invalid", "Code and description must not be blank.")));
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        diff(was, now, "code", before.code(), after.code());
        diff(was, now, "description", before.description(), after.description());
        diff(was, now, "category_id", before.categoryId(), after.categoryId());
        diff(was, now, "unit_id", before.unitId(), after.unitId());
        diff(was, now, "active", before.active(), after.active());
        if (now.isEmpty()) {
            return visible(before);
        }
        try {
            repo.update(after);
        } catch (DuplicateKeyException e) {
            throw duplicateCode();
        }
        audit.record(new AuditLog.Entry("retail.product.updated", PRODUCT, id, null, was, now));
        return visible(repo.find(id).orElseThrow());
    }

    /**
     * FR-RET-02: a manual price edit under If-Match, with the product's row lock, its history row
     * and audit. {@code price_unchanged} never depends on a cost the caller may not read (#77): for
     * a caller without {@code retail.profit.read} a request that names a cost always goes ahead.
     */
    @Transactional
    Product editPrices(UUID id, String ifMatch, PriceEditRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        if (r.costMinor() == null && r.sellMinor() == null) {
            throw ApiException.validation(
                    List.of(new FieldProblem("sell_minor", "required", "Give a new cost, a new sell price or both.")));
        }
        Product before = repo.lock(id).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        long cost = r.costMinor() == null ? before.costMinor() : r.costMinor();
        long sell = r.sellMinor() == null ? before.sellMinor() : r.sellMinor();
        boolean costUnchanged = mayReadCost() ? cost == before.costMinor() : r.costMinor() == null;
        if (costUnchanged && sell == before.sellMinor()) {
            throw ApiException.rule("price_unchanged", "The new prices equal the current ones.");
        }
        changePrices(before, cost, sell, "manual", null, r.reason().trim());
        return visible(repo.find(id).orElseThrow());
    }

    /**
     * Sets both prices on a product already locked by the caller, writes the history row and the
     * audit row, all in the caller's transaction (ADR-020 decision 5).
     */
    void changePrices(Product locked, long cost, long sell, String source, UUID sourceId, String reason) {
        UUID by = CurrentPrincipal.require().userId();
        repo.setPrices(locked.id(), cost, sell);
        repo.insertHistory(
                new PriceChange(
                        UUID.randomUUID(),
                        null,
                        by,
                        source,
                        sourceId,
                        locked.costMinor(),
                        cost,
                        locked.sellMinor(),
                        sell,
                        locked.currency(),
                        reason),
                locked.id());
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        diff(was, now, "sell_minor", locked.sellMinor(), sell);
        now.put("source", source);
        if (sourceId != null) {
            now.put("source_id", sourceId);
        }
        audit.record(new AuditLog.Entry("retail.product.price_changed", PRODUCT, locked.id(), null, was, now));
    }

    @Transactional(readOnly = true)
    PriceHistory history(UUID id) {
        repo.find(id).orElseThrow(ApiException::notFound);
        boolean cost = mayReadCost();
        return new PriceHistory(
                repo.history(id).stream().map(h -> cost ? h : h.withoutCost()).toList());
    }

    Product visible(Product p) {
        return mayReadCost() ? p : p.withoutCost();
    }

    private static String code(String raw) {
        return RetailCatalogue.normaliseCode(raw)
                .orElseThrow(() -> ApiException.validation(List.of(new FieldProblem(
                        "code",
                        "invalid",
                        "A code must not be blank or contain control characters or special spaces."))));
    }

    static boolean mayReadCost() {
        Principal principal = CurrentPrincipal.require();
        return principal.hasPermission(PROFIT_READ);
    }

    private void requireCategoryAndUnit(UUID categoryId, UUID unitId) {
        if (!repo.categoryExists(categoryId)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("category_id", "unknown_category", "No such category.")));
        }
        if (!repo.unitExists(unitId)) {
            throw ApiException.validation(List.of(new FieldProblem("unit_id", "unknown_unit", "No such unit.")));
        }
    }

    private static void diff(Map<String, Object> was, Map<String, Object> now, String key, Object x, Object y) {
        if (!Objects.equals(x, y)) {
            was.put(key, x);
            now.put(key, y);
        }
    }

    private static ApiException duplicateCode() {
        return duplicate("duplicate_product_code", "A product with this code exists (codes ignore case).");
    }

    private static ApiException duplicate(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, "Duplicate", detail);
    }
}
