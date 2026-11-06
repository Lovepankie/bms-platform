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
import com.rincoltech.bms.retail.catalogue.CatalogueNames;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.StandardUnits;
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
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UsualPack;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UsualPackRequest;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

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
    /** Issue #632: the category Quick add files an item under when none is chosen, and the unit it counts in. */
    static final String GENERAL = "General";

    static final String PIECE = "Piece";

    private final CatalogueRepository repo;
    private final CurrentTenant currentTenant;
    private final AuditLog audit;
    private final ModuleSettings settings;
    private final BarcodeRepository barcodes;
    private final Validator validator;

    CatalogueService(
            CatalogueRepository repo,
            CurrentTenant currentTenant,
            AuditLog audit,
            ModuleSettings settings,
            BarcodeRepository barcodes,
            Validator validator) {
        this.repo = repo;
        this.currentTenant = currentTenant;
        this.audit = audit;
        this.settings = settings;
        this.barcodes = barcodes;
        this.validator = validator;
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
                    "Not switched on",
                    "This feature is not switched on for your business.");
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
                    return units ? q.withPacks(forCaller(packs.getOrDefault(p.id(), List.of()))) : q;
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
        // Issue #698 (D86 = A): saved in the one display style.
        String name = CatalogueNames.display(r.name());
        String prefix = r.codePrefix() == null || r.codePrefix().isBlank() ? null : prefix(r.codePrefix());
        refuseSameCategory(name, null);
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

    /**
     * Issue #698: a category name another category has, in any case, is refused before anything is written (a refused
     * insert would end the transaction), naming the one that has it so the screen can offer it: "You already have BULBS.".
     */
    private void refuseSameCategory(String name, UUID self) {
        // Spaces inside count once, so a category saved before ("test  cables") is found too (review of #715).
        java.util.function.Function<String, String> key =
                n -> n.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        repo.categories().stream()
                .filter(c -> !c.id().equals(self) && key.apply(c.name()).equals(key.apply(name)))
                .findFirst()
                .ifPresent(c -> {
                    throw new ApiException(
                            HttpStatus.CONFLICT,
                            "duplicate_category",
                            "Duplicate",
                            "You already have " + c.name() + ".",
                            List.of(),
                            Map.of("existing_id", c.id(), "existing_name", c.name()));
                });
    }

    /** Which unique index refused the category: its name (added a moment ago by someone else) or its code start. */
    static ApiException duplicateCategory(DuplicateKeyException e) {
        return String.valueOf(e.getMessage()).contains("retail_categories_code_prefix")
                ? duplicate(
                        "duplicate_category_prefix",
                        "Another category already uses this code start. Choose other letters.")
                : duplicate("duplicate_category", "A category with this name exists.");
    }

    @Transactional
    Unit createUnit(UnitRequest r) {
        UUID id = UUID.randomUUID();
        String name = r.name().trim();
        refuseSameUnit(name, null);
        Unit of = requireUsualPackOf(id, r.usualPack());
        requireFixedUsualSize(name, r.usualPack());
        // Only a write that raced the lock above is refused here: back to the savepoint, the unit made meanwhile is
        // read and named, so the refusal always carries existing_id (review of #715).
        Object savepoint = TransactionAspectSupport.currentTransactionStatus().createSavepoint();
        try {
            repo.insertUnit(id, name, CurrentPrincipal.require().userId());
        } catch (DuplicateKeyException e) {
            throw unitMadeMeanwhile(savepoint, name, id);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        if (r.usualPack() != null) {
            repo.setUsualPack(id, r.usualPack());
            after.put(
                    "usual_pack",
                    usualPackText(r.usualPack().size(), of.name(), r.usualPack().purpose()));
        }
        audit.record(AuditLog.Entry.created("retail.unit.created", "retail.unit", id, null, after));
        return repo.unit(id).orElseThrow();
    }

    /**
     * Issue #625, ADR-100: a pack's usual size counts another unit of the business that is switched on. The size is a starting
     * number for the item's own pack, so it ties nothing down and has no price. Returns that smaller unit, or null with no pack.
     */
    private Unit requireUsualPackOf(UUID unitId, UsualPackRequest pack) {
        if (pack == null) {
            return null;
        }
        // Review of #710: one usual pack change at a time per business (a transaction lock taken before anything is
        // read),
        // so two phones can never each pass the loop check below and store a loop between them.
        repo.lockUsualPacks();
        Unit of = repo.unit(pack.ofUnitId()).orElse(null);
        if (of == null || of.id().equals(unitId)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "usual_pack.of_unit_id", "unknown_unit", "Choose another unit that this pack holds.")));
        }
        if (!of.active()) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "usual_pack.of_unit_id", "inactive_unit", "This unit is switched off. Choose another one.")));
        }
        // Issue #699 (ADR-107, amending the ADR-100 note of #637): a usual pack may count another pack, a Box of rolls
        // of 2
        // Roll, any depth; only a loop is refused, so Roll of Metre and Metre of Roll can never both be kept. The walk
        // keeps
        // what it has seen, so it ends on any loop, one already stored included.
        Set<UUID> seen = new java.util.HashSet<>(Set.of(unitId));
        for (Unit down = of; down != null; ) {
            if (!seen.add(down.id())) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "usual_pack.of_unit_id",
                        "would_loop",
                        of.name() + " is itself a pack of this unit. Choose a smaller unit.")));
            }
            down = down.usualPack() == null
                    ? null
                    : repo.unit(down.usualPack().ofUnitId()).orElse(null);
        }
        return of;
    }

    /** Issue #709 (ADR-110, D98): the usual size of a dozen is 12, the only size it may have. */
    private static void requireFixedUsualSize(String unitName, UsualPackRequest pack) {
        Integer fixed = StandardUnits.fixedCount(unitName);
        if (pack != null && fixed != null && pack.size().compareTo(BigDecimal.valueOf(fixed)) != 0) {
            throw ApiException.validation(List.of(
                    new FieldProblem("usual_pack.size", "fixed_count", StandardUnits.fixedCountRefusal(fixed))));
        }
    }

    /**
     * Review of #722: a unit renamed to a fixed-count name (Box to Dozen) takes the rule on, so the rename is refused while a
     * pack of the unit on any item, or its usual pack, holds another number; those would be dozens of 10 nobody could fix.
     */
    // JUSTIFICATION-A3: a must-fix of the review of #722; no existing check sees a rename change a unit's rule.
    private void requireFixedCountKept(Unit before, String name, UsualPackRequest next, boolean clearUsual) {
        Integer fixed = StandardUnits.fixedCount(name);
        if (fixed == null || name.equals(before.name())) {
            return;
        }
        BigDecimal usual = next != null
                ? next.size()
                : clearUsual || before.usualPack() == null
                        ? null
                        : new BigDecimal(before.usualPack().size());
        if (repo.unitHasPackNotHolding(before.id(), fixed)
                || (usual != null && usual.compareTo(BigDecimal.valueOf(fixed)) != 0)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "name",
                    "fixed_count",
                    StandardUnits.fixedCountRefusal(fixed) + " " + before.name()
                            + " holds another number on some items, so it cannot be called " + name + ".")));
        }
    }

    /** The audit of a usual pack names the smaller unit, as a person reads it (review of #628): "100.000 Metre both". */
    private static String usualPackText(BigDecimal size, String ofUnit, String purpose) {
        return size.setScale(3).toPlainString() + " " + ofUnit + " " + purpose;
    }

    /** Rename or (de)activate; the row stays, so products and history keep their meaning (#146). */
    @Transactional
    Category updateCategory(UUID id, String ifMatch, UpdateCategoryRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        Category before = repo.category(id).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        String name = r.name() == null ? before.name() : CatalogueNames.display(r.name());
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
        // Only a new name is checked, so a duplicate stored before #698 can still be switched off (review of #715).
        if (now.containsKey("name")) {
            refuseSameCategory(name, id);
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
        boolean clearUsual = Boolean.TRUE.equals(r.clearUsualPack());
        if (clearUsual && r.usualPack() != null) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "clear_usual_pack", "invalid", "Set a usual pack or clear it, not both at once.")));
        }
        requireFixedCountKept(before, name, r.usualPack(), clearUsual);
        UsualPack had = before.usualPack();
        String hadText = had == null ? null : usualPackText(new BigDecimal(had.size()), had.ofUnit(), had.purpose());
        if (r.usualPack() != null) {
            Unit of = requireUsualPackOf(id, r.usualPack());
            requireFixedUsualSize(name, r.usualPack());
            diff(
                    was,
                    now,
                    "usual_pack",
                    hadText,
                    usualPackText(r.usualPack().size(), of.name(), r.usualPack().purpose()));
        } else if (clearUsual) {
            // Issue #637: clearing a usual pack the unit does not have changes nothing.
            diff(was, now, "usual_pack", hadText, null);
        }
        if (now.isEmpty()) {
            return before;
        }
        if (before.active() && !active && repo.unitHasPacks(id)) {
            throw ApiException.rule(
                    "unit_in_use_by_pack",
                    "This unit is the unit of an active pack size. Switch those packs off on their items first.");
        }
        if (now.containsKey("name")) {
            refuseSameUnit(name, id);
        }
        Object savepoint = TransactionAspectSupport.currentTransactionStatus().createSavepoint();
        try {
            if (repo.updateUnit(id, name, active, expected) == 0) {
                throw staleUnit(id);
            }
        } catch (DuplicateKeyException e) {
            throw unitMadeMeanwhile(savepoint, name, id);
        }
        if (now.containsKey("usual_pack")) {
            // Null when clear_usual_pack was sent: the three columns are cleared together (issue #637).
            repo.setUsualPack(id, r.usualPack());
        }
        audit.record(new AuditLog.Entry("retail.unit.updated", "retail.unit", id, null, was, now));
        return repo.unit(id).orElseThrow();
    }

    // JUSTIFICATION-A3: the one refusal both unit writes share, with the existing unit in its body (issue #698).
    /**
     * Issue #698 (ADR-109): a unit name another unit already stands for, in any spelling or case ("pcs" when Piece exists), is
     * refused with 409 {@code unit_exists} naming it, under the lock of its key so two at once make one unit.
     */
    private void refuseSameUnit(String name, UUID self) {
        repo.lockUnitKey(name);
        repo.sameUnit(name, self).ifPresent(u -> {
            throw unitExists(u);
        });
    }

    /** The unit another request made under the same name a moment ago, read after going back to the savepoint. */
    private ApiException unitMadeMeanwhile(Object savepoint, String name, UUID self) {
        TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
        return unitExists(repo.sameUnit(name, self).orElseThrow());
    }

    private static ApiException unitExists(Unit u) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "unit_exists",
                "Duplicate",
                "You already have " + u.name() + ".",
                List.of(),
                Map.of("existing_id", u.id(), "existing_name", u.name(), "existing_active", u.active()));
    }

    private ApiException staleCategory(UUID id) {
        return repo.category(id).map(c -> Versions.conflict(c.version())).orElseGet(ApiException::notFound);
    }

    private ApiException staleUnit(UUID id) {
        return repo.unit(id).map(u -> Versions.conflict(u.version())).orElseGet(ApiException::notFound);
    }

    // ---- Products ------------------------------------------------------------------------

    /**
     * FR-RET-01, FR-RET-02: the product and its first history row, source {@code initial}. Issue #689 (ADR-106): it is never
     * counted in a package, and its packs are made in the same transaction, so a refused pack saves nothing.
     */
    @Transactional
    Product create(CreateProductRequest r) {
        requireCategoryAndUnit(r.categoryId(), r.unitId(), null, null);
        refusePackageUnit(r.unitId());
        List<PackRequest> packs = r.packs() == null ? List.of() : r.packs();
        List<String> purposes = checkPacks(packs);
        // Issue #698 (D86 = A): the name is checked, saved, audited and answered in the one display style.
        String description = CatalogueNames.display(r.description());
        String duplicateReason = acceptName(description, r.unitId(), null, r.duplicateReason());
        UUID by = CurrentPrincipal.require().userId();
        // Issue #562, ADR-081 as amended: the category always gives the code, such as BLB-0001; a typed one is refused.
        if (r.code() != null && !r.code().isBlank()) {
            throw codeMadeByApp("code", MADE_BY_APP);
        }
        Product p = new Product(
                UUID.randomUUID(),
                repo.nextItemCode(r.categoryId()),
                description,
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
        after.put("code_source", "category");
        if (duplicateReason != null) {
            after.put("duplicate_reason", duplicateReason);
        }
        audit.record(AuditLog.Entry.created("retail.product.created", PRODUCT, p.id(), null, after));
        makePacks(p, packs, purposes, by);
        return withPacks(visible(repo.find(p.id()).orElseThrow()));
    }

    /**
     * Review of #692: each pack's bounds (the rules of POST /products/{id}/units) and price checks, before anything is
     * written; a refusal names the pack (pack_index, packs[i].factor), never the item's own fields. Gives each pack's
     * purpose. Issue #697 (ADR-111): an item with sizes checks its packages here once too.
     */
    // JUSTIFICATION-A3: the checks of create moved unchanged, so POST /product-families shares them.
    List<String> checkPacks(List<PackRequest> packs) {
        if (!packs.isEmpty()) {
            requireUnits();
        }
        List<String> purposes = new ArrayList<>();
        for (int i = 0; i < packs.size(); i++) {
            if (packs.get(i) == null) {
                throw ofPack(
                        ApiException.validation(
                                List.of(new FieldProblem("unit_id", "required", "Choose the package."))),
                        i);
            }
            List<FieldProblem> bad = validator.validate(packs.get(i)).stream()
                    .map(v -> new FieldProblem(
                            v.getPropertyPath()
                                    .toString()
                                    .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                                    .toLowerCase(Locale.ROOT),
                            "invalid",
                            v.getMessage()))
                    .toList();
            try {
                if (!bad.isEmpty()) {
                    throw ApiException.validation(bad);
                }
                purposes.add(packPurpose(packs.get(i)));
            } catch (ApiException e) {
                throw ofPack(e, i);
            }
        }
        return purposes;
    }

    /**
     * The packs {@link #checkPacks} passed, made on the new product {@code p}; issue #697 makes them on every size. Issue
     * #699 (ADR-107): a package may hold another of the same request, so a held one is made first; one that holds nothing
     * made is refused on its own row.
     */
    void makePacks(Product p, List<PackRequest> packs, List<String> purposes, UUID by) {
        Set<UUID> made = new java.util.HashSet<>(Set.of(p.unitId()));
        List<Integer> left = new ArrayList<>(
                java.util.stream.IntStream.range(0, packs.size()).boxed().toList());
        while (!left.isEmpty()) {
            int i = left.stream()
                    .filter(j -> packs.get(j).holdsUnitId() == null
                            || made.contains(packs.get(j).holdsUnitId()))
                    .findFirst()
                    .orElse(left.getFirst());
            left.remove(Integer.valueOf(i));
            PackRequest k = packs.get(i);
            try {
                createPack(
                        p,
                        k.unitId(),
                        k.factor(),
                        purposes.get(i),
                        k.sellMinor(),
                        new Holds(k.holdsUnitId(), k.holdsQty(), Boolean.TRUE.equals(k.usualBuy())),
                        by,
                        null);
            } catch (ApiException e) {
                throw ofPack(e, i);
            }
            made.add(k.unitId());
        }
    }

    /** Issue #689 (ADR-106): a new item is never counted in a package (422 unit_is_package on unit_id). */
    // JUSTIFICATION-A3: the review of #692 asks the same refusal on POST /product-families, so create shares it.
    void refusePackageUnit(UUID unitId) {
        Unit unit = repo.unit(unitId).orElseThrow();
        if (unit.isPackage()) {
            throw ApiException.validation(List.of(
                    new FieldProblem("unit_id", "unit_is_package", RetailCatalogue.packageAsUnit(unit.name()))));
        }
    }

    /**
     * Review of #692: a refusal of the new item's package {@code i} names it, its fields as {@code packs[i].unit_id} and the
     * problem with {@code pack_index}, so the form shows it on that package and never on the item's own unit.
     */
    static ApiException ofPack(ApiException e, int i) {
        Map<String, Object> extensions = new LinkedHashMap<>(e.extensions());
        extensions.put("pack_index", i);
        List<FieldProblem> errors = e.errors().stream()
                .map(f -> new FieldProblem("packs[" + i + "]." + f.field(), f.code(), f.message()))
                .toList();
        return new ApiException(e.status(), e.code(), e.title(), e.getMessage(), errors, extensions);
    }

    /**
     * Issue #632, Quick add: the category and unit left out are General and the shop's piece unit, made when missing (the
     * name lookups are lower case), then the item is made exactly as {@link #create} makes it.
     */
    @Transactional
    RetailCatalogue.NewItem createItem(
            String description,
            UUID categoryId,
            UUID unitId,
            long costMinor,
            long sellMinor,
            String duplicateReason,
            List<RetailCatalogue.NewPack> packs) {
        UUID by = CurrentPrincipal.require().userId();
        boolean categoryMade = false;
        UUID category = categoryId;
        if (category == null) {
            // A General the owner switched off is not switched back on behind their back: they choose a category.
            if (repo.inactiveCategoryNames().contains(GENERAL.toLowerCase(Locale.ROOT))) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "category_id",
                        "inactive_category",
                        "The General category is switched off. Choose a category.")));
            }
            category = repo.categoryIdsByName().get(GENERAL.toLowerCase(Locale.ROOT));
        }
        if (category == null) {
            category = UUID.randomUUID();
            categoryMade = madeForQuickAdd("retail_categories", "retail.category", category, GENERAL, by);
            category = categoryMade ? category : repo.categoryIdsByName().get(GENERAL.toLowerCase(Locale.ROOT));
        }
        boolean unitMade = false;
        UUID unit = unitId;
        if (unit == null) {
            // Only a switched-on piece unit is used; a switched-off pc or pcs is passed over and Piece is made. A
            // switched-off
            // Piece is not switched back on: the person chooses a unit.
            // Issue #698: Piece, pc and pcs share one key, so the lookup finds whichever spelling the shop has.
            String piece = CatalogueNames.unitKey(PIECE);
            // The key's lock, as POST /units takes it, so "pcs" added at this moment and Piece made here are one unit.
            repo.lockUnitKey(PIECE);
            Set<String> off = repo.inactiveUnitNames();
            unit = off.contains(piece) ? null : repo.unitIdsByName().get(piece);
            if (unit == null && off.contains(piece)) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "unit_id", "inactive_unit", "The Piece unit is switched off. Choose a unit.")));
            }
        }
        if (unit == null) {
            unit = UUID.randomUUID();
            unitMade = madeForQuickAdd("retail_units", "retail.unit", unit, PIECE, by);
            unit = unitMade ? unit : repo.unitIdsByName().get(CatalogueNames.unitKey(PIECE));
        }
        // Issue #695: the packages go through create, so each is checked and refused on its own row as on Items.
        List<PackRequest> asked = packs == null
                ? null
                : packs.stream()
                        .map(k -> k == null
                                ? null
                                : new PackRequest(
                                        k.unitId(),
                                        k.factor(),
                                        k.purpose(),
                                        k.sellMinor(),
                                        k.holdsUnitId(),
                                        k.holdsQty(),
                                        k.usualBuy()))
                        .toList();
        Product p = create(new CreateProductRequest(
                null, description, category, unit, costMinor, sellMinor, duplicateReason, asked));
        // Read again: the answer of create leaves out the cost for someone who may not read it.
        return new RetailCatalogue.NewItem(
                CatalogueLookup.snapshot(repo.find(p.id()).orElseThrow()), categoryMade, unitMade);
    }

    private boolean madeForQuickAdd(String table, String entity, UUID id, String name, UUID by) {
        if (!repo.insertIfMissing(table, id, name, by)) {
            return false;
        }
        audit.record(AuditLog.Entry.created(
                entity + ".created", entity, id, null, Map.of("name", name, "source", "quick_add")));
        return true;
    }

    @Transactional(readOnly = true)
    ProductPage list(
            String query, UUID categoryId, UUID unitId, Boolean active, UUID branchId, Integer limit, String cursor) {
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
                repo.page(search, categoryId, unitId, active, branchId, position.rank(), afterCode, afterId, size + 1);
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
            if (r.description() != null
                    && !CatalogueNames.display(r.description()).equals(CatalogueNames.display(before.description()))) {
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
        // Issue #562: an item keeps the code the app gave it. A blank code is no code, as on create, and the item's own
        // code
        // sent back, in any case as the unique index compares codes, is no change.
        if (r.code() != null
                && !r.code().isBlank()
                && !RetailCatalogue.normaliseCode(r.code())
                        .map(before.code()::equalsIgnoreCase)
                        .orElse(false)) {
            throw codeMadeByApp("code", CODE_KEPT);
        }
        Product after = new Product(
                id,
                before.code(),
                variant != null
                        ? VariantGrid.description(variant.familyName(), size, colour)
                        : r.description() == null ? before.description() : CatalogueNames.display(r.description()),
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
        if (after.description().isEmpty()) {
            throw ApiException.validation(
                    List.of(new FieldProblem("description", "invalid", "The description must not be blank.")));
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
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
        return new PackList(forCaller(repo.packs(productId)));
    }

    /** Issue #624: a pack's prices go only to a caller who may see prices, its cost flag only to one who may read cost. */
    private static List<Pack> forCaller(List<Pack> packs) {
        boolean prices = RetailCatalogue.maySeePrices(CurrentPrincipal.require());
        boolean cost = mayReadCost();
        return packs.stream().map(k -> k.forCaller(prices, cost)).toList();
    }

    private static Pack forCaller(Pack pack) {
        return forCaller(List.of(pack)).getFirst();
    }

    static final String PRICE_EDIT = "retail.price.edit";

    /** Issue #624: setting or dropping a pack's own selling price is a price change, so it needs retail.price.edit. */
    private static void requirePriceEdit(String detail) {
        if (!CurrentPrincipal.require().hasPermission(PRICE_EDIT)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "permission_denied", "Permission denied", detail);
        }
    }

    /** Issue #624: a pack bought only is never sold, so it has no selling price. */
    private static ApiException buyOnlyPriced() {
        return ApiException.validation(List.of(new FieldProblem(
                "sell_minor",
                "pack_not_sold",
                "This pack is only for buying, so it has no selling price. Make it for selling or both first.")));
    }

    /**
     * A pack of a unit other than the product's base unit, once per unit. Chains are impossible by
     * construction: a pack counts base units of its own product only.
     */
    @Transactional
    Pack addPack(UUID productId, PackRequest r) {
        requireUnits();
        Product product = repo.lock(productId).orElseThrow(ApiException::notFound);
        createPack(
                product,
                r.unitId(),
                r.factor(),
                packPurpose(r),
                r.sellMinor(),
                new Holds(r.holdsUnitId(), r.holdsQty(), Boolean.TRUE.equals(r.usualBuy())),
                CurrentPrincipal.require().userId(),
                null);
        return forCaller(repo.pack(productId, r.unitId()).orElseThrow());
    }

    /**
     * The purpose of a pack to add, after its price checks, for POST /products/{id}/units and a new item's packs (#689).
     * ADR-099: with no purpose sent, a pack is for both, whatever its unit's usual purpose (#637); the screens fill in the
     * unit's usual purpose themselves (ADR-100).
     */
    private static String packPurpose(PackRequest r) {
        String purpose = r.purpose() == null ? RetailCatalogue.PACK_BOTH : r.purpose();
        if (r.sellMinor() != null) {
            requirePriceEdit(
                    "You may not change selling prices, so you cannot set a pack's price. Ask someone who may.");
            if (purpose.equals(RetailCatalogue.PACK_BUY)) {
                throw buyOnlyPriced();
            }
        }
        return purpose;
    }

    /**
     * Issue #637: the one way a pack is made, by POST /products/{id}/units and by the CSV import: the unit must exist, be
     * switched on and differ from the product's own unit, the pack is unique per unit, and it is audited as
     * retail.product_unit.created ({@code source} names a caller other than the API). The caller checks the capability,
     * the price permission and the purpose.
     */
    void createPack(
            Product product,
            UUID unitId,
            BigDecimal factor,
            String purpose,
            Long sellMinor,
            Holds holds,
            UUID by,
            String source) {
        Unit unit = repo.unit(unitId).orElse(null);
        if (unit == null) {
            throw ApiException.validation(List.of(new FieldProblem("unit_id", "unknown_unit", "No such unit.")));
        }
        if (!unit.active()) {
            throw ApiException.validation(List.of(
                    new FieldProblem("unit_id", "inactive_unit", "This unit is switched off. Choose another one.")));
        }
        if (unit.id().equals(product.unitId())) {
            throw ApiException.rule(
                    "pack_unit_is_base_unit", "A pack must be a different unit from the item's single unit.");
        }
        // Issue #699 (ADR-107): a pack may say what it holds, one level down, and its size is worked out from that.
        if ((holds.unitId() == null) != (holds.qty() == null)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    holds.unitId() == null ? "holds_unit_id" : "holds_qty",
                    "required",
                    "Say what one " + unitWord(unit.name()) + " holds and how many.")));
        }
        if (holds.qty() != null && holds.qty() < PackLadder.MIN_HOLDS) {
            throw ladderProblem(new PackLadder.Problem("too_small", unitId), unit.name());
        }
        if (holds.unitId() != null) {
            BigDecimal size = heldFactor(product, repo.packs(product.id()), holds.unitId(), unit.name())
                    .multiply(BigDecimal.valueOf(holds.qty()));
            if (size.compareTo(PackLadder.MAX_FACTOR) > 0) {
                throw ladderProblem(new PackLadder.Problem("too_big", unitId), unit.name());
            }
            if (factor != null && factor.compareTo(size) != 0) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "factor",
                        "factor_mismatch",
                        "1 " + unitWord(unit.name()) + " holding " + holds.qty() + " is " + plain(size)
                                + " of the item, not " + plain(factor) + ".")));
            }
            factor = size;
        } else if (factor == null) {
            throw ApiException.validation(
                    List.of(new FieldProblem("factor", "required", "Say how many of the item are in one pack.")));
        }
        ApiException notFixed = fixedCountProblem(unit.name(), holds.qty(), factor);
        if (notFixed != null) {
            throw notFixed;
        }
        if (holds.usualBuy() && purpose.equals(RetailCatalogue.PACK_SELL)) {
            throw usualBuyNotBought();
        }
        UUID id = UUID.randomUUID();
        try {
            repo.insertPack(id, product.id(), unitId, factor, purpose, sellMinor, holds.unitId(), holds.qty(), by);
        } catch (DuplicateKeyException e) {
            throw duplicate("duplicate_pack", "The product has a pack of this unit already.");
        }
        if (holds.usualBuy()) {
            auditUsualBuyCleared(repo.setUsualBuy(product.id(), id, true), unitId);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("product_id", product.id());
        after.put("unit_id", unitId);
        after.put("factor", factor.setScale(3).toPlainString());
        after.put("purpose", purpose);
        if (sellMinor != null) {
            after.put("sell_minor", sellMinor);
        }
        if (holds.unitId() != null) {
            after.put("holds_unit_id", holds.unitId());
            after.put("holds_qty", holds.qty());
        }
        if (holds.usualBuy()) {
            after.put("usual_buy", true);
        }
        if (source != null) {
            after.put("source", source);
        }
        audit.record(AuditLog.Entry.created("retail.product_unit.created", PACK, id, null, after));
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
        boolean active = r.active() == null ? before.active() : r.active();
        if (active && !before.active()) {
            // Switching on again: the item's unit may have changed while the pack was off, so the factor is confirmed.
            Unit unit = repo.unit(unitId).orElseThrow(ApiException::notFound);
            if (r.factor() == null && r.holdsQty() == null) {
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
                        "This unit is now the item's single unit, so it cannot also be a pack.");
            }
        }
        List<Pack> all = repo.packs(productId);
        if (!active && before.active()) {
            // Issue #699: a level another level holds stays, or the bigger one would hold nothing.
            all.stream()
                    .filter(k -> k.active() && unitId.equals(k.holdsUnitId()))
                    .findFirst()
                    .ifPresent(k -> {
                        throw ApiException.rule(
                                "level_in_use",
                                "The " + unitWord(k.unit()) + " holds the " + unitWord(before.unit()) + ". Remove the "
                                        + unitWord(k.unit()) + " or change what it holds first.");
                    });
        }
        Ladder ladder = ladderAfter(product, all, before, r, active);
        java.math.BigDecimal factor = ladder.factor();
        if (r.factor() != null || r.holdsQty() != null) {
            ApiException notFixed = fixedCountProblem(before.unit(), ladder.holdsQty(), factor);
            if (notFixed != null) {
                throw notFixed;
            }
        }
        String purpose = r.purpose() == null ? before.purpose() : r.purpose();
        if (Boolean.TRUE.equals(r.usualBuy()) && (!active || purpose.equals(RetailCatalogue.PACK_SELL))) {
            throw usualBuyNotBought();
        }
        // Dropping a price the pack does not have, or giving it the price it has, changes nothing and needs no
        // permission.
        boolean clear = Boolean.TRUE.equals(r.clearSellMinor()) && before.sellMinor() != null;
        // A pack with its own price sells at it whatever its size: a new size, or making it buy only (which drops the
        // price),
        // changes what its pieces sell for, so it is a price change too (review of #624).
        boolean priced = before.sellMinor() != null;
        boolean newPrice = r.sellMinor() != null && !r.sellMinor().equals(before.sellMinor());
        boolean resized = new java.math.BigDecimal(before.factor()).compareTo(factor) != 0;
        boolean madeBuyOnly =
                purpose.equals(RetailCatalogue.PACK_BUY) && !before.purpose().equals(RetailCatalogue.PACK_BUY);
        if (newPrice || clear) {
            requirePriceEdit(
                    "You may not change selling prices, so you cannot set a pack's price. Ask someone who may.");
        }
        if (priced && (resized || madeBuyOnly)) {
            requirePriceEdit("This pack has its own price. Ask someone who may change prices.");
        }
        // Issue #699: a bigger level with its own price that this change resizes is a price change too.
        for (Resized k : ladder.others()) {
            if (k.pack().sellMinor() != null) {
                requirePriceEdit("This changes the size of the "
                        + unitWord(k.pack().unit()) + ", which has its own price. Ask someone who may change prices.");
            }
        }
        if (r.sellMinor() != null && clear) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "sell_minor", "invalid", "Give a new price or drop the pack's own price, not both.")));
        }
        if (r.sellMinor() != null && purpose.equals(RetailCatalogue.PACK_BUY)) {
            throw buyOnlyPriced();
        }
        // A pack made buy only is no longer sold, so its own price goes with that change (audited below).
        Long sellMinor = clear || purpose.equals(RetailCatalogue.PACK_BUY)
                ? null
                : r.sellMinor() != null ? r.sellMinor() : before.sellMinor();
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        diff(was, now, "factor", before.factor(), factor.setScale(3).toPlainString());
        diff(was, now, "active", before.active(), active);
        diff(was, now, "purpose", before.purpose(), purpose);
        diff(was, now, "sell_minor", before.sellMinor(), sellMinor);
        diff(was, now, "holds_unit_id", before.holdsUnitId(), ladder.holdsUnitId());
        diff(was, now, "holds_qty", before.holdsQty(), ladder.holdsQty());
        boolean usualBuy = r.usualBuy() == null
                ? before.usualBuy() && active && !purpose.equals(RetailCatalogue.PACK_SELL)
                : r.usualBuy();
        diff(was, now, "usual_buy", before.usualBuy(), usualBuy);
        if (now.isEmpty()) {
            return forCaller(before);
        }
        if (repo.updatePack(
                        before.id(),
                        factor,
                        active,
                        purpose,
                        sellMinor,
                        ladder.holdsUnitId(),
                        ladder.holdsQty(),
                        expected)
                == 0) {
            throw repo.pack(productId, unitId)
                    .map(k -> Versions.conflict(k.version()))
                    .orElseGet(ApiException::notFound);
        }
        if (r.usualBuy() != null) {
            auditUsualBuyCleared(repo.setUsualBuy(productId, before.id(), usualBuy), unitId);
        }
        audit.record(new AuditLog.Entry("retail.product_unit.updated", PACK, before.id(), null, was, now));
        resizeOthers(ladder, before, null, null);
        return forCaller(repo.pack(productId, unitId).orElseThrow());
    }

    /** D23, issue #554: see {@code RetailCatalogue.useBuyingSize}. The caller holds the product's row lock. */
    boolean useBuyingSize(UUID productId, UUID unitId, java.math.BigDecimal factor, String sourceType, UUID sourceId) {
        Product product = repo.find(productId).orElseThrow(ApiException::notFound);
        Pack before = repo.pack(productId, unitId).orElseThrow(ApiException::notFound);
        String size = factor.setScale(3).toPlainString();
        if (before.factor().equals(size)) {
            return true;
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        was.put("factor", before.factor());
        // Issue #699 (ADR-107): a pack that holds another level keeps a whole number of it, and the levels above
        // follow.
        Ladder ladder;
        String refused = null;
        try {
            ladder = ladderAfter(
                    product,
                    repo.packs(productId),
                    before,
                    new UpdatePackRequest(factor, null, null, null, null, null, null, null),
                    // Round 4 of the review of #710: a switched-off pack an active level still holds (a row from before
                    // V118 or a direct write) stays a level here, so its parent is worked out again, not refused.
                    true);
        } catch (ApiException e) {
            // Review of #710: the audit names the ladder's own refusal (not_whole, too_small, would_loop, too_big).
            ladder = null;
            refused = e.errors().isEmpty() ? e.code() : e.errors().getFirst().code();
        }
        // Issue #709 (ADR-110): a dozen keeps its 12 whatever a delivery holds.
        if (ladder != null && fixedCountProblem(before.unit(), ladder.holdsQty(), ladder.factor()) != null) {
            ladder = null;
            refused = "fixed_count";
        }
        // Review of #624: a pack with its own price sells at it whatever its size, so a new usual size is a
        // price change, as on Edit item. Without retail.price.edit the delivery still saves at its own size, the
        // usual size stays, the skip is audited, and the response says so on the line. The same for a bigger level
        // with its own price that would follow (issue #699).
        boolean pricesMove = before.sellMinor() != null
                || (ladder != null
                        && ladder.others().stream().anyMatch(k -> k.pack().sellMinor() != null));
        String kept = ladder == null
                ? refused
                : pricesMove && !CurrentPrincipal.require().hasPermission(PRICE_EDIT) ? "own_price" : null;
        if (kept != null) {
            now.put("factor_asked", size);
            now.put("reason", kept);
            now.put("source_type", sourceType);
            now.put("source_id", sourceId);
            audit.record(new AuditLog.Entry("retail.product_unit.size_kept", PACK, before.id(), null, was, now));
            return false;
        }
        repo.resizePack(before.id(), factor, ladder.holdsUnitId(), ladder.holdsQty());
        now.put("factor", size);
        if (!Objects.equals(before.holdsQty(), ladder.holdsQty())) {
            was.put("holds_qty", before.holdsQty());
            now.put("holds_qty", ladder.holdsQty());
        }
        now.put("source_type", sourceType);
        now.put("source_id", sourceId);
        audit.record(new AuditLog.Entry("retail.product_unit.updated", PACK, before.id(), null, was, now));
        resizeOthers(ladder, before, sourceType, sourceId);
        return true;
    }

    // ---- The ladder: packages inside packages (issue #699, ADR-107) ---------------------------------------
    // JUSTIFICATION-A3: the ladder is new behaviour (ADR-107); its helpers sit beside the pack code they serve.

    /** What a new pack holds (both null: it holds its factor of the item's unit) and whether Buy stock opens on it. */
    record Holds(UUID unitId, Integer qty, boolean usualBuy) {
        static final Holds NONE = new Holds(null, null, false);
    }

    /**
     * Issue #709 (ADR-110, D98): a dozen holds 12 of the level it holds (12 of the item's unit when it names none), never
     * another number; null when the pack of {@code unitName} keeps its fixed count or has none.
     */
    private static ApiException fixedCountProblem(String unitName, Integer holdsQty, BigDecimal factor) {
        Integer fixed = StandardUnits.fixedCount(unitName);
        BigDecimal count = holdsQty != null ? BigDecimal.valueOf(holdsQty) : factor;
        if (fixed == null || count == null || count.compareTo(BigDecimal.valueOf(fixed)) == 0) {
            return null;
        }
        return ApiException.validation(List.of(new FieldProblem(
                holdsQty != null ? "holds_qty" : "factor", "fixed_count", StandardUnits.fixedCountRefusal(fixed))));
    }

    /** A bigger level that follows a change, with its new factor. */
    record Resized(Pack pack, BigDecimal factor) {}

    /** A level after a change: its factor, what it holds and every other level whose size follows. */
    record Ladder(BigDecimal factor, UUID holdsUnitId, Integer holdsQty, List<Resized> others) {}

    /**
     * Issue #699: the level {@code before} after the change {@code r}, and the levels above it worked out again. A new number
     * held, or a new level held, is taken as it is; a factor alone, on a level that holds another, must stay a whole number
     * of that level (422 not_whole). A level that held one now switched off, switching on again with a factor, holds the
     * item's unit again. Refusals name the level in plain words.
     */
    private Ladder ladderAfter(Product product, List<Pack> all, Pack before, UpdatePackRequest r, boolean active) {
        UUID holdsUnitId = before.holdsUnitId();
        Integer holdsQty = before.holdsQty();
        BigDecimal factor = r.factor() == null ? new BigDecimal(before.factor()) : r.factor();
        if (r.holdsUnitId() != null || r.holdsQty() != null) {
            if (r.holdsQty() == null) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "holds_qty", "required", "Say how many are in one " + unitWord(before.unit()) + ".")));
            }
            holdsUnitId =
                    r.holdsUnitId() != null ? r.holdsUnitId() : holdsUnitId != null ? holdsUnitId : product.unitId();
            holdsQty = r.holdsQty();
            if (holdsQty < PackLadder.MIN_HOLDS) {
                throw ladderProblem(new PackLadder.Problem("too_small", before.unitId()), before.unit());
            }
        } else if (r.factor() != null && holdsUnitId != null) {
            BigDecimal held = levelFactor(product, all, holdsUnitId);
            if (held == null) {
                holdsUnitId = null;
                holdsQty = null;
            } else {
                holdsQty = PackLadder.wholeOf(factor, held);
                if (holdsQty == null) {
                    throw notWhole(before.unit(), before.holdsUnit(), held, product);
                }
            }
        }
        List<PackLadder.Level> levels = new ArrayList<>();
        for (Pack k : all) {
            if (k.unitId().equals(before.unitId())) {
                if (active) {
                    levels.add(new PackLadder.Level(k.unitId(), holdsUnitId, holdsQty, factor));
                }
            } else if (k.active()) {
                levels.add(new PackLadder.Level(k.unitId(), k.holdsUnitId(), k.holdsQty(), new BigDecimal(k.factor())));
            }
        }
        Map<UUID, BigDecimal> after;
        try {
            after = PackLadder.factors(product.unitId(), levels);
        } catch (PackLadder.Problem e) {
            throw ladderProblem(e, before.unit());
        }
        if (active && holdsUnitId != null) {
            BigDecimal worked = after.get(before.unitId());
            if (r.factor() != null && r.holdsQty() != null && worked.compareTo(r.factor()) != 0) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "factor",
                        "factor_mismatch",
                        "1 " + unitWord(before.unit()) + " holding " + holdsQty + " is " + plain(worked)
                                + " of the item, not " + plain(r.factor()) + ".")));
            }
            factor = worked;
        }
        List<Resized> others = new ArrayList<>();
        for (Pack k : all) {
            BigDecimal next = k.unitId().equals(before.unitId()) || !k.active() ? null : after.get(k.unitId());
            if (next != null && next.compareTo(new BigDecimal(k.factor())) != 0) {
                others.add(new Resized(k, next));
            }
        }
        return new Ladder(factor, holdsUnitId, holdsQty, others);
    }

    /** Issue #699: every bigger level that follows a change takes its new size, audited as worked out from {@code cause}. */
    private void resizeOthers(Ladder ladder, Pack cause, String sourceType, UUID sourceId) {
        for (Resized k : ladder.others()) {
            repo.resizePack(
                    k.pack().id(), k.factor(), k.pack().holdsUnitId(), k.pack().holdsQty());
            Map<String, Object> now = new LinkedHashMap<>();
            now.put("factor", k.factor().setScale(3).toPlainString());
            now.put("because_of_unit_id", cause.unitId());
            if (sourceType != null) {
                now.put("source_type", sourceType);
                now.put("source_id", sourceId);
            }
            audit.record(new AuditLog.Entry(
                    "retail.product_unit.updated",
                    PACK,
                    k.pack().id(),
                    null,
                    Map.of("factor", k.pack().factor()),
                    now));
        }
    }

    /** Review of #710: a pack that stops being the usual buying level because another became it is audited too. */
    private void auditUsualBuyCleared(List<UUID> cleared, UUID because) {
        for (UUID id : cleared) {
            audit.record(new AuditLog.Entry(
                    "retail.product_unit.updated",
                    PACK,
                    id,
                    null,
                    Map.of("usual_buy", true),
                    Map.of("usual_buy", false, "because_of_unit_id", because)));
        }
    }

    /** The size of a level of the item: 1 for its own unit, an active pack's factor, or null for anything else. */
    private static BigDecimal levelFactor(Product product, List<Pack> all, UUID unitId) {
        if (unitId.equals(product.unitId())) {
            return BigDecimal.ONE;
        }
        return all.stream()
                .filter(k -> k.active() && k.unitId().equals(unitId))
                .findFirst()
                .map(k -> new BigDecimal(k.factor()))
                .orElse(null);
    }

    /** The size of the level a new pack holds, or 422 holds_unknown in plain words. */
    private static BigDecimal heldFactor(Product product, List<Pack> all, UUID heldUnitId, String packUnit) {
        BigDecimal held = levelFactor(product, all, heldUnitId);
        if (held == null) {
            throw ladderProblem(new PackLadder.Problem("holds_unknown", heldUnitId), packUnit);
        }
        return held;
    }

    private static ApiException ladderProblem(PackLadder.Problem e, String unit) {
        String pack = unitWord(unit);
        return switch (e.code()) {
            case "would_loop" ->
                ApiException.validation(List.of(new FieldProblem(
                        "holds_unit_id",
                        "would_loop",
                        "A " + pack + " cannot hold itself or a package that holds it. Choose a smaller one.")));
            case "too_big" ->
                ApiException.validation(List.of(
                        new FieldProblem("holds_qty", "too_big", "That is too many in one " + pack + ". Type fewer.")));
            case "too_small" ->
                ApiException.validation(List.of(new FieldProblem(
                        "holds_qty",
                        "too_small",
                        "A " + pack + " must hold 2 or more of the level below it. Type a whole number like 10.")));
            default ->
                ApiException.validation(List.of(new FieldProblem(
                        "holds_unit_id",
                        "holds_unknown",
                        "Choose what one " + pack + " holds: the single unit or another package of this item.")));
        };
    }

    /** 422 not_whole: "A carton must hold 2 or more whole boxes of 10 pieces." (review of #710). */
    private static ApiException notWhole(String unit, String heldUnit, BigDecimal held, Product product) {
        return ApiException.validation(List.of(new FieldProblem(
                "factor",
                "not_whole",
                "A " + unitWord(unit) + " must hold 2 or more whole " + plural(unitWord(heldUnit)) + " of "
                        + plain(held) + " " + plural(unitWord(product.unit()))
                        + ". Type a size that is a whole number of them.")));
    }

    private static ApiException usualBuyNotBought() {
        return ApiException.validation(List.of(new FieldProblem(
                "usual_buy",
                "usual_buy_not_bought",
                "Buy stock opens only on a package that is switched on and used for buying.")));
    }

    /** A unit's name as a word in a sentence: lower case, a short code as its word ("Ctn" is "carton"), from the one list. */
    private static String unitWord(String name) {
        return CatalogueNames.unitWord(name);
    }

    /** "pieces", "boxes", "metres": a unit's word in the plural for a number other than one (review of #710). */
    static String plural(String word) {
        // A name with "of" takes the plural on its head: "box of rolls" is "boxes of rolls" (review of #710).
        int of = word.indexOf(" of ");
        if (of > 0) {
            return plural(word.substring(0, of)) + word.substring(of);
        }
        if (word.isEmpty() || word.endsWith("s")) {
            return word;
        }
        if (word.matches(".*[^aeiou]y$")) {
            return word.substring(0, word.length() - 1) + "ies";
        }
        return word.matches(".*(x|ch|sh)$") ? word + "es" : word + "s";
    }

    /** 120.000 as 120, 12.5 as 12.5. */
    private static String plain(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
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

    static final String MADE_BY_APP = "The app makes the item code from the category. Leave the code out.";
    static final String CODE_KEPT = "An item keeps the code the app gave it. The code cannot be changed.";

    /**
     * Issue #562, ADR-081 as amended: the app makes every new item's code and never changes one. A typed code is refused
     * with 400, not dropped, so a caller (an old build of the app, a script) never believes its code was kept.
     */
    static ApiException codeMadeByApp(String field, String detail) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "item_code_made_by_app",
                "Item code made by the app",
                detail,
                List.of(new FieldProblem(field, "not_allowed", detail)));
    }

    static ApiException duplicateCode() {
        return duplicate("duplicate_product_code", "A product with this code exists (codes ignore case).");
    }

    static ApiException duplicate(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, "Duplicate", detail);
    }
}
