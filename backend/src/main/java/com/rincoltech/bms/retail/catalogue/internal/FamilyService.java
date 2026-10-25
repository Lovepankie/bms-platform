package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.catalogue.RetailPricing;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.AddVariantsRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.BranchStock;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CreateFamilyRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Family;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.JoinItem;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.JoinRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.MakeFamilyRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceChange;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceNotCopied;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Product;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.TermsCopied;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdateFamilyRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueRepository.FamilyRow;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueRepository.PriceItemTerms;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueRepository.VariantBalance;
import com.rincoltech.bms.retail.catalogue.internal.VariantGrid.Planned;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Items with sizes and colours (issue #384, ADR-051). A variant is a product of {@code retail_products} with its own code,
 * prices, stock and history; the item ({@code retail_product_families}) holds the name, category and unit its variants
 * share. Every write is audited in the same transaction, as in {@link CatalogueService}, whose name and code rules every
 * variant goes through. The route's permission is the whole check: the catalogue is tenant-wide.
 */
@Service
class FamilyService {

    static final String FAMILY = "retail.product_family";

    private final CatalogueRepository repo;
    private final CatalogueService catalogue;
    private final CurrentTenant currentTenant;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final PriceListRepository priceLists;
    private final ModuleSettings settings;

    FamilyService(
            CatalogueRepository repo,
            CatalogueService catalogue,
            CurrentTenant currentTenant,
            AuditLog audit,
            BusinessClock clock,
            PriceListRepository priceLists,
            ModuleSettings settings) {
        this.repo = repo;
        this.catalogue = catalogue;
        this.currentTenant = currentTenant;
        this.audit = audit;
        this.clock = clock;
        this.priceLists = priceLists;
        this.settings = settings;
    }

    /** FR-RET-51: the item and every variant of the sizes and colours chosen once, each with its first price history row. */
    @Transactional
    Family create(CreateFamilyRequest r) {
        catalogue.requireCategoryAndUnit(r.categoryId(), r.unitId(), null, null);
        String name = r.name().trim();
        // Issue #562: the item's base code is the category's next code, such as BLB-0007, and never typed.
        if (r.baseCode() != null && !r.baseCode().isBlank()) {
            throw CatalogueService.codeMadeByApp("base_code", CatalogueService.MADE_BY_APP);
        }
        String base = repo.nextItemCode(r.categoryId());
        List<Planned> plan =
                VariantGrid.plan(base, r.sizes(), r.colours(), r.variants(), r.costMinor(), r.sellMinor(), Set.of(), 0);
        requireSome(plan);
        String duplicateReason = catalogue.acceptItemName(name, r.unitId(), null, Set.of(), r.duplicateReason());
        UUID id = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        repo.insertFamily(id, name, base, r.categoryId(), r.unitId(), by, duplicateReason);
        insertVariants(id, name, r.categoryId(), r.unitId(), plan, by, true);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        after.put("base_code", base);
        after.put("category_id", r.categoryId());
        after.put("unit_id", r.unitId());
        after.put("variants", plan.size());
        if (duplicateReason != null) {
            after.put("duplicate_reason", duplicateReason);
        }
        audit.record(AuditLog.Entry.created("retail.product_family.created", FAMILY, id, null, after));
        return get(id);
    }

    /**
     * FR-RET-51: more sizes or colours for an item. A generated combination the item has already is skipped; prices default
     * to those of the item's first variant, and so do its pack sizes and its price list prices that are in force (on the
     * rules of {@link #copyCommercialTerms}). An item never has more than {@link VariantGrid#MAX_VARIANTS} sizes and colours.
     */
    @Transactional
    Family addVariants(UUID familyId, AddVariantsRequest r) {
        FamilyRow family = repo.lockFamily(familyId).orElseThrow(ApiException::notFound);
        List<Product> existing = repo.variants(familyId);
        Set<String> keys = new HashSet<>();
        int next = 0;
        for (Product v : existing) {
            keys.add(Planned.key(v.variant().size(), v.variant().colour()));
            next = Math.max(next, v.variant().position() + 1);
        }
        Product first = existing.isEmpty() ? null : existing.getFirst();
        long cost = r.costMinor() != null ? r.costMinor() : first == null ? 0 : first.costMinor();
        long sell = r.sellMinor() != null ? r.sellMinor() : first == null ? 0 : first.sellMinor();
        List<Planned> plan =
                VariantGrid.plan(family.baseCode(), r.sizes(), r.colours(), r.variants(), cost, sell, keys, next);
        if (plan.isEmpty()) {
            throw ApiException.rule(
                    "nothing_to_add",
                    "The item has every one of these sizes and colours already. Choose one it does not have.");
        }
        requireRoom(existing.size(), plan.size());
        UUID by = CurrentPrincipal.require().userId();
        // An item whose every size is switched off is no longer sold: a size added to it starts switched off too.
        boolean onSale = existing.isEmpty() || existing.stream().anyMatch(Product::active);
        List<UUID> added =
                insertVariants(familyId, family.name(), family.categoryId(), family.unitId(), plan, by, onSale);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("variants", existing.size() + plan.size());
        TermsCopied copied = first == null ? null : copyCommercialTerms(first, added, plan, by, after);
        repo.touchFamily(familyId);
        audit.record(new AuditLog.Entry(
                "retail.product_family.variants_added",
                FAMILY,
                familyId,
                null,
                Map.of("variants", existing.size()),
                after));
        return get(familyId).withTermsCopied(copied);
    }

    /**
     * FR-RET-51: an item without variants gets sizes and colours. It becomes the variant of {@code size} and {@code colour}
     * (the stock it holds now), keeping its id, code, prices, stock and every line of its history; the item takes its name,
     * category and unit, and the other combinations are made at its prices.
     */
    @Transactional
    Family makeFamily(UUID productId, MakeFamilyRequest r) {
        Product product = repo.lock(productId).orElseThrow(ApiException::notFound);
        if (product.variant() != null) {
            throw ApiException.rule(
                    "already_has_variants",
                    "This item has sizes and colours already. Add more on its sizes and colours page.");
        }
        String size = VariantGrid.option(r.size(), "size");
        String colour = VariantGrid.option(r.colour(), "colour");
        if (size == null && colour == null) {
            throw ApiException.validation(List.of(
                    new FieldProblem("size", "required", "Say which size or colour the stock of this item is now.")));
        }
        String name = product.description();
        if (name.length() > 200) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "description", "invalid", "Shorten the item's name to 200 characters before giving it sizes.")));
        }
        String base =
                product.code().length() > 24 ? product.code().substring(0, 24).strip() : product.code();
        String duplicateReason =
                catalogue.acceptItemName(name, product.unitId(), null, Set.of(productId), r.duplicateReason());
        UUID familyId = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        repo.insertFamily(familyId, name, base, product.categoryId(), product.unitId(), by, duplicateReason);
        String description = VariantGrid.description(name, size, colour);
        catalogue.acceptName(description, product.unitId(), productId, null);
        String oldReason = repo.duplicateReason(productId);
        repo.joinFamily(productId, familyId, size, colour, 0, description);
        Set<String> keys = Set.of(Planned.key(size, colour));
        List<Planned> plan = VariantGrid.plan(
                base, r.sizes(), r.colours(), r.variants(), product.costMinor(), product.sellMinor(), keys, 1);
        requireRoom(1, plan.size());
        // The new sizes are on sale exactly when the item is: an item that was switched off stays off in every size.
        List<UUID> added =
                insertVariants(familyId, name, product.categoryId(), product.unitId(), plan, by, product.active());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        after.put("base_code", base);
        after.put("from_product_id", productId);
        after.put("variants", plan.size() + 1);
        TermsCopied copied = copyCommercialTerms(product, added, plan, by, after);
        audit.record(AuditLog.Entry.created("retail.product_family.created", FAMILY, familyId, null, after));
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        was.put("description", product.description());
        now.put("description", description);
        now.put("family_id", familyId);
        clearedReason(was, now, oldReason);
        if (size != null) {
            now.put("variant_size", size);
        }
        if (colour != null) {
            now.put("variant_colour", colour);
        }
        audit.record(new AuditLog.Entry("retail.product.updated", CatalogueService.PRODUCT, productId, null, was, now));
        return get(familyId).withTermsCopied(copied);
    }

    /**
     * FR-RET-51: items kept apart until now, one per size or colour (a shop that typed "Cable 1.5mm" and "Cable 2.5mm" as two
     * items), become the sizes and colours of one item: a new one ({@code familyId} null) or one that exists. Each keeps its
     * id, code, prices, stock and every line of its history; its name becomes the item's with its size and colour and its
     * category the item's. The item is locked first, then the products in id order (the order a restock takes).
     */
    @Transactional
    Family join(UUID familyId, JoinRequest r) {
        FamilyRow family = familyId == null ? null : repo.lockFamily(familyId).orElseThrow(ApiException::notFound);
        List<UUID> ids = r.items().stream().map(JoinItem::productId).toList();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw ApiException.validation(List.of(new FieldProblem("items", "invalid", "Each item is listed once.")));
        }
        Map<UUID, Product> locked = new LinkedHashMap<>();
        for (UUID pid : ids.stream().sorted().toList()) {
            locked.put(pid, repo.lock(pid).orElseThrow(ApiException::notFound));
        }
        if (family != null) {
            requireNoNewItemFields(r);
        }
        Product first = locked.get(ids.getFirst());
        UUID unitId = family != null ? family.unitId() : first.unitId();
        UUID categoryId =
                family != null ? family.categoryId() : r.categoryId() != null ? r.categoryId() : first.categoryId();
        Set<String> keys = new HashSet<>();
        int next = 0;
        if (family != null) {
            for (Product v : repo.variants(family.id())) {
                keys.add(Planned.key(v.variant().size(), v.variant().colour()));
                next = Math.max(next, v.variant().position() + 1);
            }
            requireRoom(keys.size(), ids.size());
        }
        List<String[]> options = new ArrayList<>();
        for (int i = 0; i < r.items().size(); i++) {
            JoinItem item = r.items().get(i);
            Product p = locked.get(item.productId());
            if (p.variant() != null) {
                throw ApiException.rule(
                        "already_has_variants",
                        p.description() + " is a size or colour of an item already. Take it from the list.");
            }
            if (!p.unitId().equals(unitId)) {
                throw ApiException.rule(
                        "unit_differs",
                        p.description() + " is counted in " + p.unit()
                                + ", the others are not. The sizes of one item are counted in one unit.");
            }
            String size = VariantGrid.option(item.size(), "items[" + i + "].size");
            String colour = VariantGrid.option(item.colour(), "items[" + i + "].colour");
            if (size == null && colour == null) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "items[" + i + "]", "required", "Give " + p.description() + " a size, a colour or both.")));
            }
            if (!keys.add(Planned.key(size, colour))) {
                throw CatalogueService.duplicateVariant(size, colour);
            }
            options.add(new String[] {size, colour});
        }
        String name;
        String duplicateReason = null;
        UUID id;
        UUID by = CurrentPrincipal.require().userId();
        if (family == null) {
            if (r.name() == null || r.name().isBlank()) {
                throw ApiException.validation(
                        List.of(new FieldProblem("name", "required", "Give the item a name, such as Twin cable.")));
            }
            name = r.name().trim();
            catalogue.requireCategoryAndUnit(categoryId, unitId, null, null);
            // Issue #562: later sizes take the first item's code, the one the app gave it; a typed base code is
            // refused.
            if (r.baseCode() != null && !r.baseCode().isBlank()) {
                throw CatalogueService.codeMadeByApp("base_code", CatalogueService.MADE_BY_APP);
            }
            String base =
                    first.code().length() > 24 ? first.code().substring(0, 24).strip() : first.code();
            duplicateReason = catalogue.acceptItemName(name, unitId, null, Set.copyOf(ids), r.duplicateReason());
            id = UUID.randomUUID();
            repo.insertFamily(id, name, base, categoryId, unitId, by, duplicateReason);
        } else {
            name = family.name();
            id = family.id();
            repo.touchFamily(id);
        }
        String category =
                repo.category(categoryId).map(CatalogueApi.Category::name).orElse(null);
        for (int i = 0; i < r.items().size(); i++) {
            Product p = locked.get(r.items().get(i).productId());
            String size = options.get(i)[0];
            String colour = options.get(i)[1];
            String description = VariantGrid.description(name, size, colour);
            catalogue.acceptName(description, unitId, p.id(), null);
            String oldReason = repo.duplicateReason(p.id());
            repo.joinFamily(p.id(), id, size, colour, next + i, description);
            Map<String, Object> was = new LinkedHashMap<>();
            Map<String, Object> now = new LinkedHashMap<>();
            was.put("description", p.description());
            now.put("description", description);
            now.put("family_id", id);
            clearedReason(was, now, oldReason);
            if (size != null) {
                now.put("variant_size", size);
            }
            if (colour != null) {
                now.put("variant_colour", colour);
            }
            if (!p.categoryId().equals(categoryId)) {
                repo.setCategory(p.id(), categoryId);
                was.put("category_id", p.categoryId());
                was.put("category", p.category());
                now.put("category_id", categoryId);
                now.put("category", category);
            }
            audit.record(
                    new AuditLog.Entry("retail.product.updated", CatalogueService.PRODUCT, p.id(), null, was, now));
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        after.put("joined_product_ids", ids);
        if (duplicateReason != null) {
            after.put("duplicate_reason", duplicateReason);
        }
        audit.record(
                family == null
                        ? AuditLog.Entry.created("retail.product_family.created", FAMILY, id, null, after)
                        : new AuditLog.Entry("retail.product_family.joined", FAMILY, id, null, Map.of(), after));
        return get(id);
    }

    @Transactional(readOnly = true)
    Family get(UUID id) {
        FamilyRow f = repo.family(id).orElseThrow(ApiException::notFound);
        Principal principal = CurrentPrincipal.require();
        Map<UUID, BigDecimal> byVariant = new LinkedHashMap<>();
        Map<UUID, Integer> branchesOf = new LinkedHashMap<>();
        Map<UUID, BigDecimal> byBranch = new TreeMap<>();
        for (VariantBalance b : repo.familyBalances(id)) {
            if (!principal.may("retail.stock.read", b.branchId())) {
                continue;
            }
            byVariant.merge(b.productId(), b.qty(), BigDecimal::add);
            branchesOf.merge(b.productId(), 1, Integer::sum);
            byBranch.merge(b.branchId(), b.qty(), BigDecimal::add);
        }
        List<Product> variants = new ArrayList<>();
        List<String> sizes = new ArrayList<>();
        List<String> colours = new ArrayList<>();
        Set<String> seenSizes = new HashSet<>();
        Set<String> seenColours = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Product v : repo.variants(id)) {
            BigDecimal qty = byVariant.getOrDefault(v.id(), BigDecimal.ZERO);
            total = total.add(qty);
            variants.add(catalogue.visible(v).withStock(qty(qty), branchesOf.getOrDefault(v.id(), 0)));
            String size = v.variant().size();
            String colour = v.variant().colour();
            if (size != null && seenSizes.add(size.toLowerCase(Locale.ROOT))) {
                sizes.add(size);
            }
            if (colour != null && seenColours.add(colour.toLowerCase(Locale.ROOT))) {
                colours.add(colour);
            }
        }
        List<BranchStock> stock = new ArrayList<>();
        byBranch.forEach((branch, qty) -> {
            if (qty.signum() != 0) {
                stock.add(new BranchStock(branch, qty(qty)));
            }
        });
        return new Family(
                f.id(),
                f.name(),
                f.baseCode(),
                f.categoryId(),
                f.category(),
                f.unitId(),
                f.unit(),
                sizes,
                colours,
                variants,
                qty(total),
                stock,
                f.createdAt(),
                f.version(),
                f.photoId(),
                null);
    }

    /** A new name renames every variant (each keeps its size and colour); a new category moves every variant. */
    @Transactional
    Family update(UUID id, String ifMatch, UpdateFamilyRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        FamilyRow before = repo.lockFamily(id).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        String name = r.name() == null ? before.name() : r.name().trim();
        UUID categoryId = r.categoryId() == null ? before.categoryId() : r.categoryId();
        if (name.isEmpty()) {
            throw ApiException.validation(List.of(new FieldProblem("name", "invalid", "The name must not be blank.")));
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        CatalogueService.diff(was, now, "name", before.name(), name);
        CatalogueService.diff(was, now, "category_id", before.categoryId(), categoryId);
        if (now.isEmpty()) {
            return get(id);
        }
        catalogue.requireCategoryAndUnit(categoryId, before.unitId(), before.categoryId(), before.unitId());
        boolean renamed = !name.equals(before.name());
        String duplicateReason =
                renamed ? catalogue.acceptItemName(name, before.unitId(), id, Set.of(), r.duplicateReason()) : null;
        if (duplicateReason != null) {
            now.put("duplicate_reason", duplicateReason);
        }
        if (repo.updateFamily(id, name, categoryId, expected, renamed, duplicateReason) == 0) {
            throw Versions.conflict(before.version() + 1);
        }
        // Every variant is locked before any is changed, in id order (the order a restock locks
        // products in), and each is rewritten from its locked row, so a concurrent change of one
        // variant's size or colour is never lost.
        List<Product> locked = new ArrayList<>();
        for (UUID vid : repo.variants(id).stream().map(Product::id).sorted().toList()) {
            repo.lock(vid).ifPresent(locked::add);
        }
        String category =
                repo.category(categoryId).map(CatalogueApi.Category::name).orElse(null);
        for (Product v : locked) {
            Map<String, Object> vWas = new LinkedHashMap<>();
            Map<String, Object> vNow = new LinkedHashMap<>();
            if (!name.equals(before.name())) {
                String description = VariantGrid.description(
                        name, v.variant().size(), v.variant().colour());
                catalogue.acceptName(description, before.unitId(), v.id(), null);
                repo.setVariantName(v.id(), v.variant().size(), v.variant().colour(), description);
                CatalogueService.diff(vWas, vNow, "description", v.description(), description);
            }
            if (!categoryId.equals(v.categoryId())) {
                CatalogueService.diff(vWas, vNow, "category_id", v.categoryId(), categoryId);
                vWas.put("category", v.category());
                vNow.put("category", category);
            }
            if (!vNow.isEmpty()) {
                audit.record(new AuditLog.Entry(
                        "retail.product.updated", CatalogueService.PRODUCT, v.id(), null, vWas, vNow));
            }
        }
        if (!categoryId.equals(before.categoryId())) {
            repo.setFamilyCategory(id, categoryId);
        }
        audit.record(new AuditLog.Entry("retail.product_family.updated", FAMILY, id, null, was, now));
        return get(id);
    }

    /**
     * Joining an item that exists takes its name, base code and category as they are: a field that would name them anew is
     * refused, not ignored, so a caller never believes it renamed or moved the item.
     */
    private static void requireNoNewItemFields(JoinRequest r) {
        List<FieldProblem> problems = new ArrayList<>();
        String why = "The item exists already and keeps its own; leave this out, or change the item on its own page.";
        if (r.name() != null && !r.name().isBlank()) {
            problems.add(new FieldProblem("name", "not_allowed", why));
        }
        if (r.baseCode() != null && !r.baseCode().isBlank()) {
            problems.add(new FieldProblem("base_code", "not_allowed", why));
        }
        if (r.categoryId() != null) {
            problems.add(new FieldProblem("category_id", "not_allowed", why));
        }
        if (r.duplicateReason() != null && !r.duplicateReason().isBlank()) {
            problems.add(new FieldProblem(
                    "duplicate_reason", "not_allowed", "The item's name is not being chosen here; leave this out."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
    }

    /** Joining an item clears the reason a product kept a name that was in use (the item's name replaces it): say what it was. */
    private static void clearedReason(Map<String, Object> was, Map<String, Object> now, String oldReason) {
        if (oldReason != null) {
            was.put("duplicate_reason", oldReason);
            now.put("duplicate_reason", null);
        }
    }

    /**
     * New sizes are sold the way the size they were made from is: its active pack sizes (while the units capability is on)
     * are copied to every one of them, and so are its price list prices in force or coming, on the rules a price list price
     * is set on by hand (issue #395). A price is copied only while the price lists capability is on and the caller may set
     * list prices ({@code retail.pricelist.manage}), only in the size's own unit or a pack copied with it, only to a size
     * that keeps the cost and selling price it was made from (a dearer size at the old wholesale price would sell below
     * what it is worth), and never below the new size's cost. Each copy is audited as a list price set by hand is; what was
     * not copied is answered, so the owner knows which prices to set.
     */
    private TermsCopied copyCommercialTerms(
            Product from, List<UUID> to, List<Planned> plan, UUID by, Map<String, Object> audited) {
        if (to.isEmpty()) {
            return null;
        }
        boolean packsOn = catalogue.unitsOn();
        int packs = 0;
        if (packsOn) {
            for (UUID id : to) {
                packs += repo.copyPacks(from.id(), id, by);
            }
        }
        List<PriceItemTerms> items = repo.priceItemsInForce(
                from.id(), clock.today(currentTenant.profile().timezone()));
        String skipped = null;
        int prices = 0;
        List<PriceNotCopied> notCopied = new ArrayList<>();
        // The permission is asked first and answered the same whatever the lists hold, so a caller who may not set list
        // prices learns nothing of them: not whether the size has any, nor whether the capability is on.
        boolean mayCost = CatalogueService.mayReadCost();
        if (!CurrentPrincipal.require().hasPermission("retail.pricelist.manage")) {
            skipped = "price_list_permission";
        } else if (!Boolean.TRUE.equals(settings.group("retail").get(RetailPricing.PRICE_LISTS_CAPABILITY))) {
            skipped = "price_lists_off";
        } else {
            for (int i = 0; i < to.size(); i++) {
                UUID id = to.get(i);
                Planned v = plan.get(i);
                boolean samePrices = v.costMinor() == from.costMinor() && v.sellMinor() == from.sellMinor();
                for (PriceItemTerms item : items) {
                    boolean ownUnit = item.unitId().equals(from.unitId());
                    String reason;
                    if (item.factor() == null || (!ownUnit && !packsOn)) {
                        reason = "unit_not_sold";
                    } else if (!samePrices) {
                        reason = "price_differs";
                    } else if (BigDecimal.valueOf(item.priceMinor())
                                    .compareTo(BigDecimal.valueOf(v.costMinor()).multiply(item.factor()))
                            < 0) {
                        // Without retail.profit.read the reason would tell that the cost is above the price.
                        reason = mayCost ? "below_cost" : "not_copied";
                    } else {
                        reason = null;
                    }
                    String minQty =
                            item.minQty().setScale(3, RoundingMode.UNNECESSARY).toPlainString();
                    if (reason != null) {
                        notCopied.add(new PriceNotCopied(
                                id, item.priceListId(), item.unitId(), minQty, item.priceMinor(), reason));
                        continue;
                    }
                    UUID itemId = UUID.randomUUID();
                    priceLists.insertItem(
                            itemId,
                            item.priceListId(),
                            id,
                            item.unitId(),
                            item.minQty(),
                            item.priceMinor(),
                            item.validFrom(),
                            item.validTo(),
                            by);
                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put("price_list_id", item.priceListId());
                    after.put("product_id", id);
                    after.put("unit_id", item.unitId());
                    after.put("min_qty", minQty);
                    after.put("price_minor", item.priceMinor());
                    after.put("valid_from", item.validFrom().toString());
                    if (item.validTo() != null) {
                        after.put("valid_to", item.validTo().toString());
                    }
                    after.put("copied_from_product_id", from.id());
                    audit.record(AuditLog.Entry.created(
                            "retail.price_list_item.created", PriceListService.ITEM, itemId, null, after));
                    prices++;
                }
            }
        }
        if (packs > 0) {
            audited.put("packs_copied", packs);
        }
        if (prices > 0) {
            audited.put("price_list_items_copied", prices);
        }
        if (skipped != null) {
            audited.put("price_list_items_skipped", skipped);
        }
        if (!notCopied.isEmpty()) {
            audited.put("price_list_items_not_copied", notCopied.size());
        }
        return new TermsCopied(packs, prices, skipped, List.copyOf(notCopied));
    }

    /** An item never has more than {@link VariantGrid#MAX_VARIANTS} sizes and colours, however they are added. */
    private static void requireRoom(int has, int adds) {
        if (has + adds > VariantGrid.MAX_VARIANTS) {
            throw ApiException.rule(
                    "too_many_variants",
                    "The item has " + has + " sizes and colours and this adds " + adds + ". An item has at most "
                            + VariantGrid.MAX_VARIANTS + "; add fewer.");
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * Each planned variant becomes a product of the item: its name and code pass the catalogue's rules (one name per unit,
     * one code ignoring case), its prices get the {@code initial} history row, and its creation is audited.
     */
    private List<UUID> insertVariants(
            UUID familyId, String name, UUID categoryId, UUID unitId, List<Planned> plan, UUID by, boolean active) {
        List<UUID> made = new ArrayList<>();
        Set<String> codes = repo.productCodes();
        String currency = currentTenant.profile().currency();
        for (Planned v : plan) {
            String code = CatalogueService.code(v.code());
            if (code.length() > 40) {
                throw ApiException.validation(List.of(new FieldProblem(
                        "variants",
                        "invalid",
                        "The code " + code + " is longer than 40 characters. Use a shorter size or colour.")));
            }
            if (codes.contains(code.toLowerCase(Locale.ROOT))) {
                throw CatalogueService.duplicate(
                        "duplicate_product_code",
                        "A product with the code " + code + " exists. Spell the size or colour of "
                                + VariantGrid.label(v.size(), v.colour()) + " differently.");
            }
            String description = VariantGrid.description(name, v.size(), v.colour());
            catalogue.acceptName(description, unitId, null, null);
            Product p = new Product(
                    UUID.randomUUID(),
                    code,
                    description,
                    categoryId,
                    null,
                    unitId,
                    null,
                    v.sellMinor(),
                    v.costMinor(),
                    currency,
                    active,
                    null,
                    null,
                    1,
                    null,
                    null);
            try {
                repo.insert(p, by, null, familyId, v.size(), v.colour(), v.position());
            } catch (DuplicateKeyException e) {
                throw String.valueOf(e.getMessage()).contains("retail_products_variant")
                        ? CatalogueService.duplicateVariant(v.size(), v.colour())
                        : CatalogueService.duplicateCode();
            }
            codes.add(code.toLowerCase(Locale.ROOT));
            made.add(p.id());
            repo.insertHistory(
                    new PriceChange(
                            UUID.randomUUID(),
                            null,
                            by,
                            "initial",
                            null,
                            null,
                            v.costMinor(),
                            null,
                            v.sellMinor(),
                            currency,
                            null),
                    p.id());
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("code", code);
            after.put("description", description);
            after.put("category_id", categoryId);
            after.put("unit_id", unitId);
            after.put("sell_minor", v.sellMinor());
            after.put("family_id", familyId);
            if (!active) {
                after.put("active", false);
            }
            if (v.size() != null) {
                after.put("variant_size", v.size());
            }
            if (v.colour() != null) {
                after.put("variant_colour", v.colour());
            }
            audit.record(
                    AuditLog.Entry.created("retail.product.created", CatalogueService.PRODUCT, p.id(), null, after));
        }
        return made;
    }

    private static void requireSome(List<Planned> plan) {
        if (plan.isEmpty()) {
            throw ApiException.validation(
                    List.of(new FieldProblem("sizes", "required", "Give at least one size or one colour.")));
        }
    }

    private static String qty(BigDecimal q) {
        return q.setScale(3, RoundingMode.UNNECESSARY).toPlainString();
    }
}
