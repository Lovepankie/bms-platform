package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Category;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CategoryList;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CategoryRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CreateProductRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Pack;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PackList;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PackRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceChange;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceEditRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceHistory;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Product;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.ProductPage;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.SimilarProduct;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.SimilarProducts;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Unit;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UnitList;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UnitRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdateCategoryRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdatePackRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdateProductRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdateUnitRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final ModuleSettings settings;
    private final BarcodeRepository barcodes;

    CatalogueService(
            CatalogueRepository repo,
            CurrentTenant currentTenant,
            AuditLog audit,
            ModuleSettings settings,
            BarcodeRepository barcodes) {
        this.repo = repo;
        this.currentTenant = currentTenant;
        this.audit = audit;
        this.settings = settings;
        this.barcodes = barcodes;
    }

    /**
     * The {@code units} capability of the tenant (ADR-033); off is the default and means no packs anywhere. The catalogue
     * cannot use {@code retail.stock}'s {@code RetailCapabilities} (stock depends on the catalogue, and the boundary test
     * forbids the cycle), so it reads the same settings group with the shared key; one read per request that needs it.
     */
    boolean unitsOn() {
        return Boolean.TRUE.equals(settings.group("retail").get(RetailCatalogue.UNITS_CAPABILITY));
    }

    private void requireUnits() {
        if (!unitsOn()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "capability_not_enabled",
                    "Capability not enabled",
                    "This capability is not enabled for the tenant.");
        }
    }

    /**
     * Pack sizes on the products, in one statement, while the capability is on; and their barcodes (issue #345), in one
     * more. A pack's barcode is left out while the capability is off, as the pack is.
     */
    private List<Product> withPacks(List<Product> products) {
        if (products.isEmpty()) {
            return products;
        }
        List<UUID> ids = products.stream().map(Product::id).toList();
        boolean units = unitsOn();
        Map<UUID, List<CatalogueApi.Barcode>> codes = barcodes.ofProducts(ids);
        Map<UUID, List<Pack>> packs = units ? repo.packsOf(ids) : Map.of();
        return products.stream()
                .map(p -> {
                    List<CatalogueApi.Barcode> own = codes.getOrDefault(p.id(), List.of()).stream()
                            .filter(b -> units || b.unitId() == null)
                            .toList();
                    // Absent, not an empty list, for an item with no barcode, as the schema says.
                    Product q = p.withBarcodes(own.isEmpty() ? null : own);
                    return units ? q.withPacks(packs.getOrDefault(p.id(), List.of())) : q;
                })
                .toList();
    }

    private Product withPacks(Product p) {
        return withPacks(List.of(p)).getFirst();
    }

    /** A product as the reads send it: the caller's view of the prices, its packs and its barcodes. */
    Product detailed(Product p) {
        return withPacks(visible(p));
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

    /** Issue #493: the prefix a new category of this name would get now; the form shows it before saving. */
    @Transactional(readOnly = true)
    CatalogueApi.PrefixSuggestion suggestPrefix(String name) {
        // A category name is at most 100 characters, so nothing longer is ever worth reading.
        String n = name == null ? "" : name.trim();
        return new CatalogueApi.PrefixSuggestion(repo.suggestPrefix(n.length() > 100 ? n.substring(0, 100) : n, null));
    }

    @Transactional
    Category createCategory(CategoryRequest r) {
        UUID id = UUID.randomUUID();
        String name = r.name().trim();
        String prefix = r.codePrefix() == null || r.codePrefix().isBlank() ? null : prefix(r.codePrefix());
        try {
            repo.insertCategory(id, name, prefix, CurrentPrincipal.require().userId());
        } catch (DuplicateKeyException e) {
            throw duplicateCategory(e);
        }
        Category c = repo.category(id).orElseThrow();
        audit.record(AuditLog.Entry.created(
                "retail.category.created",
                "retail.category",
                c.id(),
                null,
                Map.of("name", c.name(), "code_prefix", c.codePrefix())));
        return c;
    }

    /** Issue #493, ADR-081: 2 to 4 letters A to Z, kept in capitals. */
    static String prefix(String raw) {
        String p = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!p.matches("[A-Z]{2,4}")) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "code_prefix", "invalid", "The code start must be 2 to 4 letters, A to Z, such as BLB.")));
        }
        return p;
    }

    /** Which unique index refused the category: its name or its code start. */
    static ApiException duplicateCategory(DuplicateKeyException e) {
        return String.valueOf(e.getMessage()).contains("retail_categories_code_prefix")
                ? duplicate(
                        "duplicate_category_prefix",
                        "Another category already uses this code start. Choose other letters.")
                : duplicate("duplicate_category", "A category with this name exists.");
    }

    @Transactional
    Unit createUnit(UnitRequest r) {
        Unit u = new Unit(UUID.randomUUID(), r.name().trim(), true, 0, 1);
        try {
            repo.insertUnit(u.id(), u.name(), CurrentPrincipal.require().userId());
        } catch (DuplicateKeyException e) {
            throw duplicate("duplicate_unit", "A unit with this name exists.");
        }
        audit.record(
                AuditLog.Entry.created("retail.unit.created", "retail.unit", u.id(), null, Map.of("name", u.name())));
        return u;
    }

    /** Rename or (de)activate; the row stays, so products and history keep their meaning (#146). */
    @Transactional
    Category updateCategory(UUID id, String ifMatch, UpdateCategoryRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        Category before = repo.category(id).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        String name = r.name() == null ? before.name() : r.name().trim();
        boolean active = r.active() == null ? before.active() : r.active();
        if (name.isEmpty()) {
            throw ApiException.validation(List.of(new FieldProblem("name", "invalid", "The name must not be blank.")));
        }
        // Issue #493: a new code start is used for items added from now on; items made before keep their codes.
        String codePrefix = r.codePrefix() == null ? before.codePrefix() : prefix(r.codePrefix());
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        diff(was, now, "name", before.name(), name);
        diff(was, now, "active", before.active(), active);
        diff(was, now, "code_prefix", before.codePrefix(), codePrefix);
        if (now.isEmpty()) {
            return before;
        }
        try {
            if (repo.updateCategory(id, name, active, codePrefix, expected) == 0) {
                throw staleCategory(id);
            }
        } catch (DuplicateKeyException e) {
            throw duplicateCategory(e);
        }
        audit.record(new AuditLog.Entry("retail.category.updated", "retail.category", id, null, was, now));
        return repo.category(id).orElseThrow();
    }

    @Transactional
    Unit updateUnit(UUID id, String ifMatch, UpdateUnitRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        Unit before = repo.unit(id).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        String name = r.name() == null ? before.name() : r.name().trim();
        boolean active = r.active() == null ? before.active() : r.active();
        if (name.isEmpty()) {
            throw ApiException.validation(List.of(new FieldProblem("name", "invalid", "The name must not be blank.")));
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        diff(was, now, "name", before.name(), name);
        diff(was, now, "active", before.active(), active);
        if (now.isEmpty()) {
            return before;
        }
        if (before.active() && !active && repo.unitHasPacks(id)) {
            throw ApiException.rule(
                    "unit_in_use_by_pack",
                    "This unit is the unit of an active pack size. Switch those packs off on their items first.");
        }
        try {
            if (repo.updateUnit(id, name, active, expected) == 0) {
                throw staleUnit(id);
            }
        } catch (DuplicateKeyException e) {
            throw duplicate("duplicate_unit", "A unit with this name exists.");
        }
        audit.record(new AuditLog.Entry("retail.unit.updated", "retail.unit", id, null, was, now));
        return repo.unit(id).orElseThrow();
    }

    private ApiException staleCategory(UUID id) {
        return repo.category(id).map(c -> Versions.conflict(c.version())).orElseGet(ApiException::notFound);
    }

    private ApiException staleUnit(UUID id) {
        return repo.unit(id).map(u -> Versions.conflict(u.version())).orElseGet(ApiException::notFound);
    }

    // ---- Products ------------------------------------------------------------------------

    /** FR-RET-01, FR-RET-02: the product and its first history row, source {@code initial}. */
    @Transactional
    Product create(CreateProductRequest r) {
        requireCategoryAndUnit(r.categoryId(), r.unitId(), null, null);
        String duplicateReason = acceptName(r.description().trim(), r.unitId(), null, r.duplicateReason());
        UUID by = CurrentPrincipal.require().userId();
        // Issue #493, ADR-081: a typed code is kept; without one the category gives its next, such as BLB-0001.
        boolean typed = r.code() != null && !r.code().isBlank();
        Product p = new Product(
                UUID.randomUUID(),
                typed ? code(r.code()) : repo.nextItemCode(r.categoryId()),
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
            repo.insert(p, by, duplicateReason);
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
        if (!typed) {
            after.put("code_source", "category");
        }
        if (duplicateReason != null) {
            after.put("duplicate_reason", duplicateReason);
        }
        audit.record(AuditLog.Entry.created("retail.product.created", PRODUCT, p.id(), null, after));
        return withPacks(visible(repo.find(p.id()).orElseThrow()));
    }

    @Transactional(readOnly = true)
    ProductPage list(String query, UUID categoryId, Boolean active, UUID branchId, Integer limit, String cursor) {
        if (branchId != null && !CurrentPrincipal.require().may("retail.stock.read", branchId)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("branch_id", "unknown_branch", "No such branch in your scope.")));
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Search.Query search = Search.parse(query);
        Search.After position = Search.unwrap(search, cursor);
        String afterCode = null;
        UUID afterId = null;
        Cursor.Key after = Cursor.decodeKey(position.cursor()).orElse(null);
        if (after != null) {
            afterCode = after.sortKey();
            afterId = after.id();
        }
        List<Search.Ranked<Product>> rows =
                repo.page(search, categoryId, active, branchId, position.rank(), afterCode, afterId, size + 1);
        boolean more = rows.size() > size;
        List<Search.Ranked<Product>> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Search.wrap(
                        search,
                        items.getLast().rank(),
                        Cursor.encode(items.getLast().row().code() + "|"
                                + items.getLast().row().id()))
                : null;
        return new ProductPage(
                withPacks(items.stream().map(r -> visible(r.row())).toList()), next);
    }

    @Transactional(readOnly = true)
    Product get(UUID id) {
        Product p = withPacks(visible(repo.find(id).orElseThrow(ApiException::notFound)));
        // Issue #342: the stock the caller may read, so the edit screen can warn before an item with stock is switched
        // off.
        // The warning follows any non-zero branch, so a positive and a negative balance never cancel to nothing.
        Principal principal = CurrentPrincipal.require();
        List<CatalogueRepository.BranchBalance> held = repo.stockBalances(id).stream()
                .filter(b -> principal.may("retail.stock.read", b.branchId()))
                .toList();
        BigDecimal total =
                held.stream().map(CatalogueRepository.BranchBalance::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
        return p.withStock(total.setScale(3, java.math.RoundingMode.UNNECESSARY).toPlainString(), held.size());
    }

    /** Non-price fields; omitted fields are unchanged. Prices change only through {@link #editPrices}. */
    @Transactional
    Product update(UUID id, String ifMatch, UpdateProductRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        // Issue #384: a variant's item is locked before the variant (the order a rename of the item takes), so the item
        // name
        // read below is the one committed, never one a concurrent rename is replacing.
        UUID peeked = repo.find(id)
                .map(Product::variant)
                .map(CatalogueApi.VariantOf::familyId)
                .orElse(null);
        if (peeked != null) {
            repo.lockFamily(peeked);
        }
        Product before = repo.lock(id).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        // The item may have changed between the first read and the lock (the product was just given sizes): read again.
        if (!java.util.Objects.equals(
                peeked, before.variant() == null ? null : before.variant().familyId())) {
            throw Versions.conflict(before.version());
        }
        CatalogueApi.VariantOf variant = before.variant();
        String size = null;
        String colour = null;
        if (variant == null) {
            if (r.variantSize() != null || r.variantColour() != null) {
                throw ApiException.rule(
                        "not_a_variant",
                        "This item has no sizes or colours. Give it sizes and colours first, then change one of them.");
            }
        } else {
            // Issue #384: a variant's name, category and unit are its item's; only its size, colour, code and on sale
            // change here.
            if (r.description() != null && !r.description().trim().equals(before.description())) {
                throw ApiException.rule(
                        "variant_name_follows_item",
                        "The name of a size or colour comes from its item. Change the item's name on its sizes and colours page.");
            }
            if ((r.categoryId() != null && !r.categoryId().equals(before.categoryId()))
                    || (r.unitId() != null && !r.unitId().equals(before.unitId()))) {
                throw ApiException.rule(
                        "variant_follows_item",
                        "Every size and colour of an item has the item's category and unit. Change the category on the item's sizes and colours page.");
            }
            size = r.variantSize() == null ? variant.size() : VariantGrid.option(r.variantSize(), "variant_size");
            colour = r.variantColour() == null
                    ? variant.colour()
                    : VariantGrid.option(r.variantColour(), "variant_colour");
            if (size == null && colour == null) {
                throw ApiException.validation(
                        List.of(new FieldProblem("variant_size", "invalid", "Keep a size, a colour or both.")));
            }
            // Before the name check: the other variant's name is the same, and the owner should hear why in item words.
            if ((!Objects.equals(size, variant.size()) || !Objects.equals(colour, variant.colour()))
                    && repo.variantTaken(variant.familyId(), size, colour, id)) {
                throw duplicateVariant(size, colour);
            }
        }
        requireCategoryAndUnit(
                r.categoryId() == null ? before.categoryId() : r.categoryId(),
                r.unitId() == null ? before.unitId() : r.unitId(),
                before.categoryId(),
                before.unitId());
        Product after = new Product(
                id,
                r.code() == null ? before.code() : code(r.code()),
                variant != null
                        ? VariantGrid.description(variant.familyName(), size, colour)
                        : r.description() == null
                                ? before.description()
                                : r.description().trim(),
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
        boolean optionsChanged =
                variant != null && (!Objects.equals(size, variant.size()) || !Objects.equals(colour, variant.colour()));
        if (optionsChanged) {
            diff(was, now, "variant_size", variant.size(), size);
            diff(was, now, "variant_colour", variant.colour(), colour);
        }
        if (now.isEmpty()) {
            return withPacks(visible(before));
        }
        if (!before.categoryId().equals(after.categoryId())) {
            // Issue #351: a category correction is audited with the old and the new category by name as well as by id.
            was.put("category", before.category());
            now.put(
                    "category",
                    repo.category(after.categoryId()).map(Category::name).orElse(null));
        }
        if (!before.unitId().equals(after.unitId()) && repo.productHasPacks(id)) {
            throw ApiException.rule(
                    "unit_in_use_by_pack",
                    "The unit cannot change while the product has active pack sizes: they count base units. Switch them off first.");
        }
        if (!before.unitId().equals(after.unitId()) && repo.productHasPriceItems(id)) {
            throw ApiException.rule(
                    "unit_in_use_by_price_list",
                    "The unit cannot change while a price list has a price for the product: prices are per unit.");
        }
        if (!before.unitId().equals(after.unitId()) && repo.productHasStock(id)) {
            // Issue #351, ADR-044: a unit is never converted. Stock, sales and restocks were counted in the old unit,
            // so
            // changing the label would turn 10 rolls into 10 metres.
            throw ApiException.rule(
                    "unit_in_use_by_stock",
                    "This item has stock or stock history counted in " + before.unit() + ", so its unit cannot change: "
                            + "the numbers would only be relabelled, not converted. Add a new item with the right unit, "
                            + "correct this one to none on the Stock page, and buy stock of the new one.");
        }
        String duplicateReason = null;
        // A new name or unit settles the question again: the reason of an earlier add-anyway goes unless this one needs
        // its own.
        boolean nameChanged = !before.description().equals(after.description())
                || !before.unitId().equals(after.unitId());
        if (nameChanged) {
            duplicateReason = acceptName(after.description(), after.unitId(), id, r.duplicateReason());
            if (duplicateReason != null) {
                now.put("duplicate_reason", duplicateReason);
            }
        }
        try {
            repo.update(after, duplicateReason, nameChanged);
            if (optionsChanged) {
                repo.setVariantName(id, size, colour, after.description(), false);
            }
        } catch (DuplicateKeyException e) {
            throw optionsChanged && String.valueOf(e.getMessage()).contains("retail_products_variant")
                    ? duplicateVariant(size, colour)
                    : duplicateCode();
        }
        audit.record(new AuditLog.Entry("retail.product.updated", PRODUCT, id, null, was, now));
        return withPacks(visible(repo.find(id).orElseThrow()));
    }

    // ---- Names (issue #352, ADR-044) ----------------------------------------------------------

    static final String ADD_ANYWAY = "core.settings.manage";
    static final int MIN_REASON = 5;

    /**
     * A product name is kept once per unit: the key is the description folded to lower case without accents, with spaces
     * and punctuation collapsed ({@code bms_name_key}). Under the name's advisory lock, a second product of the same unit
     * with the same key is refused with 422 {@code duplicate_product_name} carrying the existing product, unless an
     * administrator adds it anyway with a reason (returned, to be stored on the product). A different unit is allowed.
     *
     * @param selfId the product being edited, which never counts as its own duplicate; null on create
     * @return the reason of an accepted duplicate, or null when the name is new
     */
    String acceptName(String description, UUID unitId, UUID selfId, String reason) {
        repo.lockName(description);
        List<CatalogueRepository.NameMatch> same = repo.sameName(description, selfId).stream()
                .filter(m -> m.unitId().equals(unitId))
                .toList();
        if (!same.isEmpty()) {
            CatalogueRepository.NameMatch existing = same.getFirst();
            return duplicateOutcome(existing.id(), existing.code(), existing.description(), reason);
        }
        // Issue #384: an item with sizes and colours of the same unit holds the name too.
        return repo.sameFamilyName(description, null).stream()
                .filter(f -> f.unitId().equals(unitId))
                .findFirst()
                .map(f -> duplicateOutcome(f.id(), f.baseCode(), f.name(), reason))
                .orElse(null);
    }

    /**
     * Issue #384, ADR-044 one level up: the name of an item with sizes and colours is kept once per unit, against the other
     * items with sizes and the items without (a size's own name carries its size, so it never clashes). The same refusal as
     * {@link #acceptName}, and the same way for an administrator to add it anyway with a reason.
     *
     * @param excludeFamilyId the item being renamed; null otherwise
     * @param excludeProducts products that are becoming sizes of this item, whose own names go
     * @return the reason of an accepted duplicate, or null when the name is new
     */
    String acceptItemName(
            String name, UUID unitId, UUID excludeFamilyId, java.util.Set<UUID> excludeProducts, String reason) {
        repo.lockName(name);
        java.util.Optional<CatalogueRepository.NameMatch> plain = repo.sameName(name, null).stream()
                .filter(m -> m.unitId().equals(unitId) && m.familyId() == null && !excludeProducts.contains(m.id()))
                .findFirst();
        if (plain.isPresent()) {
            return duplicateOutcome(
                    plain.get().id(), plain.get().code(), plain.get().description(), reason);
        }
        return repo.sameFamilyName(name, excludeFamilyId).stream()
                .filter(f -> f.unitId().equals(unitId))
                .findFirst()
                .map(f -> duplicateOutcome(f.id(), f.baseCode(), f.name(), reason))
                .orElse(null);
    }

    /** The refusal of a repeated name, or the reason an administrator gave to add it anyway. */
    private String duplicateOutcome(UUID existingId, String existingCode, String existingName, String reason) {
        record Existing(UUID id, String code, String description) {}
        Existing existing = new Existing(existingId, existingCode, existingName);
        boolean admin = CurrentPrincipal.require().hasPermission(ADD_ANYWAY);
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "duplicate_product_name",
                    "Business rule failed",
                    "This product already exists: " + existing.description() + " (" + existing.code() + ").",
                    List.of(),
                    Map.of(
                            "existing_id", existing.id(),
                            "existing_code", existing.code(),
                            "existing_name", existing.description(),
                            "may_add_anyway", admin));
        }
        if (!admin) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Only an administrator can add a product whose name exists already.");
        }
        if (why.length() < MIN_REASON) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "duplicate_reason", "invalid", "Give a reason of at least " + MIN_REASON + " characters.")));
        }
        return why;
    }

    /**
     * The warning before saving: products with this name in the unit asked about ({@code exact}, a save is refused), and
     * products whose name is close ({@code similar}, a few letters apart, or the same name in another unit). Reads one
     * tenant's catalogue and measures the edit distance here; nothing is written.
     */
    @Transactional(readOnly = true)
    SimilarProducts similar(String description, UUID unitId, UUID excludeId) {
        String text = description == null ? "" : description.trim();
        if (text.isEmpty()) {
            return new SimilarProducts(List.of(), List.of());
        }
        String key = repo.nameKey(text);
        List<SimilarProduct> exact = new ArrayList<>();
        List<SimilarProduct> close = new ArrayList<>();
        for (CatalogueRepository.NameMatch m : repo.sameName(text, excludeId)) {
            boolean sameUnit = unitId != null && unitId.equals(m.unitId());
            (sameUnit ? exact : close).add(similarOf(m, 0, sameUnit));
        }
        for (CatalogueRepository.NameMatch m : repo.neighbours(key, excludeId, MAX_DISTANCE, NEIGHBOURS)) {
            if (m.nameKey().equals(key)) {
                continue;
            }
            int distance = editDistance(key, m.nameKey());
            if (distance <= allowedDistance(key, m.nameKey())) {
                close.add(similarOf(m, distance, false));
            }
        }
        close.sort(Comparator.comparingInt(SimilarProduct::distance).thenComparing(SimilarProduct::code));
        return new SimilarProducts(exact, close.size() > SIMILAR_SHOWN ? close.subList(0, SIMILAR_SHOWN) : close);
    }

    static final int MAX_DISTANCE = 3;
    static final int NEIGHBOURS = 5000;
    static final int SIMILAR_SHOWN = 8;

    private static SimilarProduct similarOf(CatalogueRepository.NameMatch m, int distance, boolean sameUnit) {
        return new SimilarProduct(m.id(), m.code(), m.description(), m.unit(), m.active(), distance, sameUnit);
    }

    /** How many letters may differ for two names to count as close: none for a short name, a few for a long one. */
    static int allowedDistance(String a, String b) {
        int longest = Math.max(a.length(), b.length());
        return longest < 5 ? 0 : longest < 10 ? 1 : longest < 20 ? 2 : 3;
    }

    /** The Levenshtein distance between two strings: single letters added, removed or changed. */
    static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int change = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(change, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    // ---- Pack sizes (ADR-033, issue #194; docs/specs/trade-capabilities.md section 4) ---------------

    static final String PACK = "retail.product_unit";

    @Transactional(readOnly = true)
    PackList packs(UUID productId) {
        requireUnits();
        repo.find(productId).orElseThrow(ApiException::notFound);
        return new PackList(repo.packs(productId));
    }

    /**
     * A pack of a unit other than the product's base unit, once per unit. Chains are impossible by
     * construction: a pack counts base units of its own product only.
     */
    @Transactional
    Pack addPack(UUID productId, PackRequest r) {
        requireUnits();
        Product product = repo.lock(productId).orElseThrow(ApiException::notFound);
        Unit unit = repo.unit(r.unitId()).orElse(null);
        if (unit == null) {
            throw ApiException.validation(List.of(new FieldProblem("unit_id", "unknown_unit", "No such unit.")));
        }
        if (!unit.active()) {
            throw ApiException.validation(List.of(
                    new FieldProblem("unit_id", "inactive_unit", "This unit is switched off. Choose another one.")));
        }
        if (unit.id().equals(product.unitId())) {
            throw ApiException.rule(
                    "pack_unit_is_base_unit",
                    "A pack must be a different unit from the one the product is counted in.");
        }
        UUID id = UUID.randomUUID();
        try {
            repo.insertPack(
                    id,
                    productId,
                    r.unitId(),
                    r.factor(),
                    CurrentPrincipal.require().userId());
        } catch (DuplicateKeyException e) {
            throw duplicate("duplicate_pack", "The product has a pack of this unit already.");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("product_id", productId);
        after.put("unit_id", r.unitId());
        after.put("factor", r.factor().setScale(3).toPlainString());
        audit.record(AuditLog.Entry.created("retail.product_unit.created", PACK, id, null, after));
        return repo.pack(productId, r.unitId()).orElseThrow();
    }

    /** Change the factor (future sales and restocks only: lines keep the factor they used) or switch a pack off. */
    @Transactional
    Pack updatePack(UUID productId, UUID unitId, String ifMatch, UpdatePackRequest r) {
        requireUnits();
        int expected = Versions.fromIfMatch(ifMatch);
        Product product = repo.lock(productId).orElseThrow(ApiException::notFound);
        Pack before = repo.pack(productId, unitId).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        java.math.BigDecimal factor = r.factor() == null ? new java.math.BigDecimal(before.factor()) : r.factor();
        boolean active = r.active() == null ? before.active() : r.active();
        if (active && !before.active()) {
            // Switching on again: the item's unit may have changed while the pack was off, so the factor is confirmed.
            Unit unit = repo.unit(unitId).orElseThrow(ApiException::notFound);
            if (r.factor() == null) {
                throw ApiException.rule(
                        "factor_required",
                        "Confirm how many of the item are in one pack when switching it on again: the item's unit may have changed.");
            }
            if (!unit.active()) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "unit_id", "inactive_unit", "This unit is switched off. Switch it on first.")));
            }
            if (unitId.equals(product.unitId())) {
                throw ApiException.rule(
                        "pack_unit_is_base_unit",
                        "This unit is now the one the item is counted in, so it cannot also be a pack.");
            }
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        diff(was, now, "factor", before.factor(), factor.setScale(3).toPlainString());
        diff(was, now, "active", before.active(), active);
        if (now.isEmpty()) {
            return before;
        }
        if (repo.updatePack(before.id(), factor, active, expected) == 0) {
            throw repo.pack(productId, unitId)
                    .map(k -> Versions.conflict(k.version()))
                    .orElseGet(ApiException::notFound);
        }
        audit.record(new AuditLog.Entry("retail.product_unit.updated", PACK, before.id(), null, was, now));
        return repo.pack(productId, unitId).orElseThrow();
    }

    /** D23, issue #554: see {@code RetailCatalogue.useBuyingSize}. The caller holds the product's row lock. */
    void useBuyingSize(UUID productId, UUID unitId, java.math.BigDecimal factor, String sourceType, UUID sourceId) {
        Pack before = repo.pack(productId, unitId).orElseThrow(ApiException::notFound);
        String size = factor.setScale(3).toPlainString();
        if (before.factor().equals(size)) {
            return;
        }
        repo.updatePack(before.id(), factor, before.active(), before.version());
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        was.put("factor", before.factor());
        now.put("factor", size);
        now.put("source_type", sourceType);
        now.put("source_id", sourceId);
        audit.record(new AuditLog.Entry("retail.product_unit.updated", PACK, before.id(), null, was, now));
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

    /**
     * A voided restock (issue #351) gives back the prices it set while it is still the product's latest price event; see
     * {@link RetailCatalogue#revertPurchasePrices}. The product is already locked by the caller.
     */
    boolean revertPurchasePrices(Product locked, UUID purchaseId, String reason, boolean laterPurchase) {
        List<PriceChange> history = repo.history(locked.id());
        List<PriceChange> mine = history.stream()
                .filter(h -> "purchase".equals(h.source())
                        && purchaseId.equals(h.sourceId())
                        && h.oldCostMinor() != null
                        && h.oldSellMinor() != null
                        && (h.oldCostMinor().longValue() != h.newCostMinor().longValue()
                                || h.oldSellMinor().longValue() != h.newSellMinor()))
                .toList();
        if (mine.isEmpty()) {
            return false;
        }
        if (laterPurchase || !history.getLast().id().equals(mine.getLast().id())) {
            noteCostUnchanged(
                    locked,
                    purchaseId,
                    reason
                            + ". The prices were left as they are: later stock came in or they changed after the purchase.");
            return false;
        }
        PriceChange first = mine.getFirst();
        changePrices(locked, first.oldCostMinor(), first.oldSellMinor(), "purchase", purchaseId, reason);
        return true;
    }

    /**
     * A history row that changes nothing (the cost and the sell price are the same on both sides) to say why a landed cost or a
     * supplier credit left the cost alone (ADR-033, issue #199). The product row is not touched, so its version stays and a
     * person editing it meanwhile is not told it changed; there is no price audit row, as no price changed.
     */
    void noteCostUnchanged(Product locked, UUID sourceId, String reason) {
        repo.insertHistory(
                new PriceChange(
                        UUID.randomUUID(),
                        null,
                        CurrentPrincipal.require().userId(),
                        "purchase",
                        sourceId,
                        locked.costMinor(),
                        locked.costMinor(),
                        locked.sellMinor(),
                        locked.sellMinor(),
                        locked.currency(),
                        reason),
                locked.id());
    }

    @Transactional(readOnly = true)
    PriceHistory history(UUID id) {
        repo.find(id).orElseThrow(ApiException::notFound);
        // ADR-055: the history of an item's prices is for whoever may see prices; a storekeeper is refused.
        if (!RetailCatalogue.maySeePrices(CurrentPrincipal.require())) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Prices are for the people who sell or set them.");
        }
        boolean cost = mayReadCost();
        return new PriceHistory(
                repo.history(id).stream().map(h -> cost ? h : h.withoutCost()).toList());
    }

    Product visible(Product p) {
        if (!RetailCatalogue.maySeePrices(CurrentPrincipal.require())) {
            return p.withoutPrice();
        }
        return mayReadCost() ? p : p.withoutCost();
    }

    static String code(String raw) {
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

    /** The category and unit exist, and a newly chosen one (not the product's current one) is active. */
    void requireCategoryAndUnit(UUID categoryId, UUID unitId, UUID currentCategoryId, UUID currentUnitId) {
        Category category = repo.category(categoryId).orElse(null);
        if (category == null) {
            throw ApiException.validation(
                    List.of(new FieldProblem("category_id", "unknown_category", "No such category.")));
        }
        if (!category.active() && !categoryId.equals(currentCategoryId)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "category_id", "inactive_category", "This category is switched off. Choose another one.")));
        }
        Unit unit = repo.unit(unitId).orElse(null);
        if (unit == null) {
            throw ApiException.validation(List.of(new FieldProblem("unit_id", "unknown_unit", "No such unit.")));
        }
        if (!unit.active() && !unitId.equals(currentUnitId)) {
            throw ApiException.validation(List.of(
                    new FieldProblem("unit_id", "inactive_unit", "This unit is switched off. Choose another one.")));
        }
    }

    static void diff(Map<String, Object> was, Map<String, Object> now, String key, Object x, Object y) {
        if (!Objects.equals(x, y)) {
            was.put(key, x);
            now.put(key, y);
        }
    }

    /** Issue #384: the item has a variant of this size and colour already. */
    static ApiException duplicateVariant(String size, String colour) {
        return duplicate(
                "duplicate_variant",
                "The item has " + VariantGrid.label(size, colour) + " already. Each one is listed once.");
    }

    static ApiException duplicateCode() {
        return duplicate("duplicate_product_code", "A product with this code exists (codes ignore case).");
    }

    static ApiException duplicate(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, "Duplicate", detail);
    }
}
