package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailPricing;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Product;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.EndItemRequest;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.Group;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.GroupList;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.GroupRequest;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.Item;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.ItemPage;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.ItemRequest;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.PriceList;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.PriceListList;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.PriceListRequest;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.UpdateGroupRequest;
import com.rincoltech.bms.retail.catalogue.internal.PriceListApi.UpdatePriceListRequest;
import com.rincoltech.bms.retail.catalogue.internal.PriceListRepository.ItemRow;
import com.rincoltech.bms.retail.catalogue.internal.PriceListRepository.Range;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * Price lists, their items and customer groups (ADR-033, issue #195; docs/specs/trade-capabilities.md section 3).
 * Every route is the {@code price_lists} capability: with it off they answer 404 {@code capability_not_enabled}, and the
 * sale ignores any list that exists. Every write is audited in the same transaction. The retail price is never copied
 * into a list: it stays the product's own {@code sell_minor}.
 */
@Service
class PriceListService {

    static final String LIST = "retail.price_list";
    static final String ITEM = "retail.price_list_item";
    static final String GROUP = "retail.customer_group";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final PriceListRepository repo;
    private final CatalogueRepository catalogue;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final ModuleSettings settings;

    PriceListService(
            PriceListRepository repo,
            CatalogueRepository catalogue,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            ModuleSettings settings) {
        this.repo = repo;
        this.catalogue = catalogue;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.settings = settings;
    }

    /** The {@code price_lists} capability; the catalogue reads the group directly because stock depends on it (as {@code units}). */
    private void requireCapability() {
        if (!Boolean.TRUE.equals(settings.group("retail").get(RetailPricing.PRICE_LISTS_CAPABILITY))) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "capability_not_enabled",
                    "Capability not enabled",
                    "This capability is not enabled for the tenant.");
        }
    }

    private LocalDate today() {
        return clock.today(tenant.profile().timezone());
    }

    // ---- Lists ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    PriceListList lists() {
        requireCapability();
        return new PriceListList(repo.lists(today()));
    }

    @Transactional(readOnly = true)
    PriceList list(UUID id) {
        requireCapability();
        return repo.list(id, today()).orElseThrow(ApiException::notFound);
    }

    @Transactional
    PriceList createList(PriceListRequest r) {
        requireCapability();
        UUID id = UUID.randomUUID();
        String name = r.name().trim();
        if (name.isEmpty()) {
            throw ApiException.validation(List.of(new FieldProblem("name", "invalid", "The name must not be blank.")));
        }
        try {
            repo.insertList(
                    id,
                    name,
                    tenant.profile().currency(),
                    Boolean.TRUE.equals(r.strict()),
                    CurrentPrincipal.require().userId());
        } catch (DuplicateKeyException e) {
            throw duplicate("duplicate_price_list", "A price list with this name exists (names ignore case).");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        after.put("strict", Boolean.TRUE.equals(r.strict()));
        audit.record(AuditLog.Entry.created("retail.price_list.created", LIST, id, null, after));
        return repo.list(id, today()).orElseThrow();
    }

    @Transactional
    PriceList updateList(UUID id, String ifMatch, UpdatePriceListRequest r) {
        requireCapability();
        int expected = Versions.fromIfMatch(ifMatch);
        PriceList before = repo.lockList(id, today()).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        String name = r.name() == null ? before.name() : r.name().trim();
        boolean strict = r.strict() == null ? before.strict() : r.strict();
        boolean active = r.active() == null ? before.active() : r.active();
        if (name.isEmpty()) {
            throw ApiException.validation(List.of(new FieldProblem("name", "invalid", "The name must not be blank.")));
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        diff(was, now, "name", before.name(), name);
        diff(was, now, "strict", before.strict(), strict);
        diff(was, now, "active", before.active(), active);
        if (now.isEmpty()) {
            return before;
        }
        try {
            if (repo.updateList(id, name, strict, active, expected) == 0) {
                throw Versions.conflict(
                        repo.list(id, today()).map(PriceList::version).orElse(expected));
            }
        } catch (DuplicateKeyException e) {
            throw duplicate("duplicate_price_list", "A price list with this name exists (names ignore case).");
        }
        audit.record(new AuditLog.Entry("retail.price_list.updated", LIST, id, null, was, now));
        return repo.list(id, today()).orElseThrow();
    }

    // ---- Items ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    ItemPage items(UUID listId, UUID productId, String query, Boolean includeEnded, Integer limit, String cursor) {
        requireCapability();
        LocalDate today = today();
        repo.list(listId, today).orElseThrow(ApiException::notFound);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Search.Query search = Search.parse(query);
        Search.After position = Search.unwrap(search, cursor);
        Cursor.Key after = Cursor.decodeKey(position.cursor()).orElse(null);
        List<Search.Ranked<ItemRow>> rows = repo.items(
                listId,
                productId,
                search,
                Boolean.TRUE.equals(includeEnded),
                today,
                position.rank(),
                after == null ? null : after.sortKey(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<Search.Ranked<ItemRow>> page = more ? rows.subList(0, size) : rows;
        String next = more
                ? Search.wrap(
                        search,
                        page.getLast().rank(),
                        Cursor.encode(page.getLast().row().sortKey() + "|"
                                + page.getLast().row().item().id()))
                : null;
        return new ItemPage(page.stream().map(x -> visible(x.row().item())).toList(), next);
    }

    /**
     * A new item of a list. An open ended item takes over from the current open ended one of the same product, unit
     * and break: the old one is closed the day before. Anything else that overlaps in time is refused with 409
     * {@code price_item_overlap}; the list row is locked first, so two concurrent edits of one list serialise.
     */
    @Transactional
    Item addItem(UUID listId, ItemRequest r) {
        requireCapability();
        LocalDate today = today();
        PriceList list = repo.lockList(listId, today).orElseThrow(ApiException::notFound);
        Product product = catalogue.find(r.productId()).orElse(null);
        if (product == null) {
            throw ApiException.validation(
                    List.of(new FieldProblem("product_id", "unknown_product", "No such product.")));
        }
        if (!product.currency().equals(list.currency())) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "product_id", "currency_mismatch", "The product is priced in " + product.currency())));
        }
        if (!r.unitId().equals(product.unitId())) {
            boolean packOn = Boolean.TRUE.equals(settings.group("retail").get(RetailCatalogue.UNITS_CAPABILITY));
            if (!packOn
                    || !catalogue
                            .pack(product.id(), r.unitId())
                            .map(CatalogueApi.Pack::active)
                            .orElse(false)) {
                throw ApiException.validation(List.of(
                        new FieldProblem("unit_id", "unknown_pack_unit", "This item is not sold in that unit.")));
            }
        }
        BigDecimal minQty = r.minQty() == null ? BigDecimal.ZERO : r.minQty();
        LocalDate from = r.validFrom() == null ? today : r.validFrom();
        if (from.isBefore(today)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "valid_from",
                    "in_the_past",
                    "A price cannot start in the past; a sale already made keeps its price.")));
        }
        if (r.validTo() != null && r.validTo().isBefore(from)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("valid_to", "invalid", "The last day cannot be before the first day.")));
        }
        List<Range> overlapping = repo.overlapping(listId, product.id(), r.unitId(), minQty, from, r.validTo());
        Range takeOver = null;
        if (!overlapping.isEmpty()) {
            Range only = overlapping.getFirst();
            boolean replaces = r.validTo() == null
                    && overlapping.size() == 1
                    && only.validTo() == null
                    && only.validFrom().isBefore(from);
            if (!replaces) {
                throw duplicate(
                        "price_item_overlap",
                        "This list already has a price for the item, unit and quantity in those dates. End it first or choose other dates.");
            }
            takeOver = only;
        }
        UUID id = UUID.randomUUID();
        if (takeOver != null) {
            repo.closeItem(takeOver.id(), from.minusDays(1));
        }
        repo.insertItem(
                id,
                listId,
                product.id(),
                r.unitId(),
                minQty,
                r.priceMinor(),
                from,
                r.validTo(),
                CurrentPrincipal.require().userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("price_list_id", listId);
        after.put("product_id", product.id());
        after.put("unit_id", r.unitId());
        after.put("min_qty", minQty.setScale(3).toPlainString());
        after.put("price_minor", r.priceMinor());
        after.put("valid_from", from.toString());
        if (r.validTo() != null) {
            after.put("valid_to", r.validTo().toString());
        }
        if (takeOver != null) {
            after.put("replaced_item_id", takeOver.id());
        }
        audit.record(AuditLog.Entry.created("retail.price_list_item.created", ITEM, id, null, after));
        return visible(repo.item(id, today).orElseThrow().item());
    }

    /** Ends an open item: it applies up to and including {@code valid_to}, never earlier than today or its first day. */
    @Transactional
    Item endItem(UUID listId, UUID itemId, EndItemRequest r) {
        requireCapability();
        LocalDate today = today();
        repo.lockList(listId, today).orElseThrow(ApiException::notFound);
        Item item = repo.item(itemId, today)
                .map(ItemRow::item)
                .filter(i -> i.priceListId().equals(listId))
                .orElseThrow(ApiException::notFound);
        if (item.validTo() != null) {
            throw ApiException.rule("price_item_closed", "This price has an end date already.");
        }
        if (r.validTo().isBefore(today) || r.validTo().isBefore(item.validFrom())) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "valid_to", "invalid", "The last day cannot be before today or before the first day.")));
        }
        repo.closeItem(itemId, r.validTo());
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("valid_to", r.validTo().toString());
        audit.record(new AuditLog.Entry("retail.price_list_item.ended", ITEM, itemId, null, Map.of(), now));
        return visible(repo.item(itemId, today).orElseThrow().item());
    }

    /** The cost flag leaves this service only for a caller who may read cost (ADR-020 decision 10). */
    private static Item visible(Item i) {
        if (CatalogueService.mayReadCost()) {
            return i;
        }
        return new Item(
                i.id(),
                i.priceListId(),
                i.productId(),
                i.code(),
                i.description(),
                i.unitId(),
                i.unit(),
                i.minQty(),
                i.priceMinor(),
                i.validFrom(),
                i.validTo(),
                i.status(),
                null);
    }

    // ---- Customer groups -----------------------------------------------------------------

    @Transactional(readOnly = true)
    GroupList groups() {
        requireCapability();
        return new GroupList(repo.groups());
    }

    @Transactional
    Group createGroup(GroupRequest r) {
        requireCapability();
        String name = r.name().trim();
        if (name.isEmpty()) {
            throw ApiException.validation(List.of(new FieldProblem("name", "invalid", "The name must not be blank.")));
        }
        requireActiveList(r.priceListId());
        UUID id = UUID.randomUUID();
        try {
            repo.insertGroup(
                    id, name, r.priceListId(), CurrentPrincipal.require().userId());
        } catch (DuplicateKeyException e) {
            throw duplicate("duplicate_customer_group", "A group with this name exists (names ignore case).");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", name);
        if (r.priceListId() != null) {
            after.put("price_list_id", r.priceListId());
        }
        audit.record(AuditLog.Entry.created("retail.customer_group.created", GROUP, id, null, after));
        return repo.group(id).orElseThrow();
    }

    @Transactional
    Group updateGroup(UUID id, String ifMatch, UpdateGroupRequest r) {
        requireCapability();
        int expected = Versions.fromIfMatch(ifMatch);
        Group before = repo.group(id).orElseThrow(ApiException::notFound);
        if (before.version() != expected) {
            throw Versions.conflict(before.version());
        }
        String name = r.name().trim();
        if (name.isEmpty()) {
            throw ApiException.validation(List.of(new FieldProblem("name", "invalid", "The name must not be blank.")));
        }
        // A list that is already the group's may stay even if it was switched off since.
        if (!Objects.equals(r.priceListId(), before.priceListId())) {
            requireActiveList(r.priceListId());
        }
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        diff(was, now, "name", before.name(), name);
        diff(was, now, "price_list_id", before.priceListId(), r.priceListId());
        diff(was, now, "active", before.active(), r.active());
        if (now.isEmpty()) {
            return before;
        }
        try {
            if (repo.updateGroup(id, name, r.priceListId(), r.active(), expected) == 0) {
                throw Versions.conflict(repo.group(id).map(Group::version).orElse(expected));
            }
        } catch (DuplicateKeyException e) {
            throw duplicate("duplicate_customer_group", "A group with this name exists (names ignore case).");
        }
        audit.record(new AuditLog.Entry("retail.customer_group.updated", GROUP, id, null, was, now));
        return repo.group(id).orElseThrow();
    }

    private void requireActiveList(UUID priceListId) {
        if (priceListId == null) {
            return;
        }
        PriceList list = repo.list(priceListId, today()).orElse(null);
        if (list == null || !list.active()) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "price_list_id", "unknown_price_list", "Choose a price list that exists and is switched on.")));
        }
    }

    private static void diff(Map<String, Object> was, Map<String, Object> now, String key, Object x, Object y) {
        if (!Objects.equals(x, y)) {
            was.put(key, x);
            now.put(key, y);
        }
    }

    private static ApiException duplicate(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, "Duplicate", detail);
    }
}
