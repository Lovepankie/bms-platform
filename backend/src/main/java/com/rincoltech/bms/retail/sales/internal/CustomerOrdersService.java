package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
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
import com.rincoltech.bms.kernel.PushNotice;
import com.rincoltech.bms.kernel.Search;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.Board;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.CancelRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.CancellationsReport;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.ChangeRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.DeliverRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.Delivered;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.FeePaymentRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.FeesReport;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.ItemsRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.Lookup;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.Order;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.OrderEvent;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.OrderPage;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.OrderRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.ReportRow;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.SendRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.StepRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersRepository.Header;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersRepository.StoredLine;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Customer;
import com.rincoltech.bms.retail.sales.internal.SalesApi.PaymentRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Sale;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleLineRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleRequest;
import com.rincoltech.bms.retail.sales.internal.SalesRepository.LinePricing;
import com.rincoltech.bms.retail.sales.internal.SalesRepository.NewLine;
import com.rincoltech.bms.retail.sales.internal.SalesService.Priced;
import com.rincoltech.bms.retail.stock.BranchRoles;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.Hold;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer orders with delivery (issue #385, ADR-052): an order taken by phone, WhatsApp, Instagram, Facebook or at the counter
 * is recorded without payment and keeps its stock aside ({@link StockLedger#reserve}); it moves through packed and out for
 * delivery; on delivery the stock it kept is released and the ordinary sale path ({@link SalesService#record}) makes the sale in
 * the same transaction, so receipts, credit, reports, returns and the Controls page see a normal sale; the delivery fee is posted
 * on its own to 4120 delivery fees. A cancel releases the stock and keeps the reason. Every step is in the order's history and the
 * audit log.
 */
@Service
class CustomerOrdersService {

    static final String MANAGE = "retail.order.manage";
    /** A delivery fee left on the account is collected like a payment on a credit sale (issue #385). */
    static final String FEE_PAYMENT = "retail.sale.create";

    static final String ORDER = "retail.customer_order";
    static final String PATH = "/api/v1/retail/orders";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    static final int MAX_REPORT_DAYS = 366;
    static final Set<String> STATUSES =
            Set.of("open", "ordered", "packed", "out_for_delivery", "delivered", "cancelled");
    static final Set<String> SOURCES = Set.of("whatsapp", "instagram", "facebook", "phone", "walk_in", "other");

    private final CustomerOrdersRepository repo;
    private final SalesRepository salesRepo;
    private final SalesService sales;
    private final AccountsService accounts;
    private final StockLedger stock;
    private final RetailBooks books;
    private final RetailBranchContext branches;
    private final BranchRoles roles;
    private final RetailCapabilities capabilities;
    private final Idempotency idempotency;
    private final TenantSequences sequences;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final ApplicationEventPublisher events;

    CustomerOrdersService(
            CustomerOrdersRepository repo,
            SalesRepository salesRepo,
            SalesService sales,
            AccountsService accounts,
            StockLedger stock,
            RetailBooks books,
            RetailBranchContext branches,
            BranchRoles roles,
            RetailCapabilities capabilities,
            Idempotency idempotency,
            TenantSequences sequences,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            ApplicationEventPublisher events) {
        this.repo = repo;
        this.salesRepo = salesRepo;
        this.sales = sales;
        this.accounts = accounts;
        this.stock = stock;
        this.books = books;
        this.branches = branches;
        this.roles = roles;
        this.capabilities = capabilities;
        this.idempotency = idempotency;
        this.sequences = sequences;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    private LocalDate today() {
        return clock.today(tenant.profile().timezone());
    }

    // ---- Taking an order ---------------------------------------------------------------------------

    @Transactional
    Outcome<Order> create(String idempotencyKey, OrderRequest r) {
        return idempotency.once(idempotencyKey, "POST", PATH, r, Order.class, () -> record(r));
    }

    private Order record(OrderRequest r) {
        Principal principal = CurrentPrincipal.require();
        UUID branch = branches.resolveStocking(MANAGE, r.branchId());
        // An order is filled from a shop's shelf and becomes a sale there; a store never sells (ADR-048).
        roles.requireMaySell(branch);
        branches.requireSells(branch, "branch_id");
        LocalDate today = today();
        List<FieldProblem> problems = new ArrayList<>();
        Customer buyer = r.customerId() == null
                ? null
                : salesRepo.customer(r.customerId()).orElse(null);
        if (r.customerId() != null && buyer == null) {
            problems.add(new FieldProblem("customer_id", "unknown_customer", "No such customer."));
        }
        String name = blankToNull(r.customerName());
        if (name == null && buyer != null) {
            name = buyer.name();
        }
        if (name == null) {
            problems.add(new FieldProblem("customer_name", "required", "Name the customer."));
        }
        String phone = blankToNull(r.customerPhone());
        if (phone == null && buyer != null) {
            phone = blankToNull(buyer.contact());
        }
        if (r.deliveryDate() != null && r.deliveryDate().isBefore(today)) {
            problems.add(new FieldProblem(
                    "delivery_date", "invalid", "A delivery cannot be planned for a day that has passed."));
        }
        long fee = r.deliveryFeeMinor() == null ? 0 : r.deliveryFeeMinor();
        List<SaleLineRequest> requested = r.lines().stream()
                .map(l -> new SaleLineRequest(
                        l.productId(), l.qty(), l.unitPriceMinor(), l.unitId(), l.priceOverrideReason()))
                .toList();
        // The prices are agreed now, by the rules a sale would take today (units, price lists, the price floor).
        Priced priced = sales.priceLines(branch, today, buyer, null, requested, problems);
        long items = priced.lines().stream().mapToLong(NewLine::lineTotalMinor).reduce(0, Math::addExact);
        if (items > RetailCatalogue.MAX_AMOUNT_MINOR || items + fee > RetailCatalogue.MAX_AMOUNT_MINOR) {
            throw ApiException.rule("amount_out_of_range", "The order is too large to compute.");
        }
        UUID id = UUID.randomUUID();
        String orderNo = "OR%06d".formatted(sequences.next("retail_order_no"));
        repo.insert(
                id,
                orderNo,
                branch,
                today,
                r.source(),
                r.customerId(),
                name,
                phone,
                blankToNull(r.deliveryPlace()),
                r.deliveryDate(),
                fee,
                tenant.profile().currency(),
                items,
                blankToNull(r.notes()),
                principal.userId());
        List<Hold> holds = new ArrayList<>();
        for (NewLine l : priced.lines()) {
            boolean pack = l.saleUnitId() != null;
            repo.insertLine(
                    id,
                    1,
                    l.lineNo(),
                    l.productId(),
                    l.saleUnitId(),
                    pack ? l.saleQty() : l.qty(),
                    l.qty(),
                    pack ? l.saleUnitPriceMinor() : l.unitPriceMinor(),
                    l.lineTotalMinor(),
                    l.pricing(),
                    principal.userId());
            holds.add(new Hold(branch, l.productId(), l.qty()));
        }
        // Under the balance rows' locks: two orders for the last unit queue here and the second is refused.
        stock.reserve(holds);
        repo.event(id, "ordered", null, principal.userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("order_no", orderNo);
        after.put("source", r.source());
        after.put("items_total_minor", items);
        after.put("delivery_fee_minor", fee);
        after.put("lines", priced.lines().size());
        audit.record(AuditLog.Entry.created("retail.order.created", ORDER, id, branch, after));
        // Issue #412: an order that came in from outside the shop (a call, WhatsApp, social media) is news for the
        // people who
        // work at that shop, who pack it; a walk-in order is taken at the counter by someone already there.
        if (!"walk_in".equals(r.source())) {
            events.publishEvent(new PushNotice(
                    "retail.order_new",
                    id.toString(),
                    new PushNotice.BranchStaff(MANAGE, branch, principal.userId()),
                    Map.of(
                            "branch_id", branch.toString(),
                            "source_label", sourceWords(r.source()),
                            "path", "/staff/retail/orders/" + id)));
        }
        return view(repo.find(id, false).orElseThrow(), true);
    }

    // ---- Reading -----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    Order get(UUID id) {
        return view(visible(id, false), true);
    }

    private Header visible(UUID id, boolean lock) {
        Principal principal = CurrentPrincipal.require();
        return repo.find(id, lock)
                .filter(h -> principal.may(MANAGE, h.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    OrderPage page(
            List<UUID> branchIds,
            String status,
            String due,
            String source,
            String query,
            Integer limit,
            String cursor) {
        List<FieldProblem> problems = new ArrayList<>();
        if (status != null && !STATUSES.contains(status)) {
            problems.add(new FieldProblem(
                    "status", "invalid", "status is open, ordered, packed, out_for_delivery, delivered or cancelled."));
        }
        if (due != null && !due.equals("today") && !due.equals("overdue")) {
            problems.add(new FieldProblem("due", "invalid", "due is today or overdue."));
        }
        if (source != null && !SOURCES.contains(source)) {
            problems.add(new FieldProblem("source", "invalid", "Not a known source."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        List<UUID> filter = CurrentPrincipal.require().branchFilter(MANAGE, branchIds);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Search.Query search = Search.parse(query);
        Cursor.Key after =
                Cursor.decodeKey(Search.unwrap(search, cursor).cursor()).orElse(null);
        List<Header> rows = repo.page(
                filter,
                status,
                due,
                today(),
                source,
                search,
                after == null ? null : after.at(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<Header> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Search.wrap(
                        search,
                        0,
                        Cursor.encode(items.getLast().createdAt() + "|"
                                + items.getLast().id()))
                : null;
        Set<UUID> mine = salesMadeByCaller(items);
        return new OrderPage(items.stream().map(h -> view(h, false, mine)).toList(), next);
    }

    @Transactional(readOnly = true)
    Board board(List<UUID> branchIds) {
        LocalDate today = today();
        long[] c = repo.board(CurrentPrincipal.require().branchFilter(MANAGE, branchIds), today);
        return new Board(today, c[0], c[1], c[2], c[3], c[4], c[5]);
    }

    /**
     * The quick entry of an order copied from a chat: the newest order of this phone number in the caller's branches (its
     * name, place and fee to fill in again), and a credit buyer whose contact is this number.
     */
    @Transactional(readOnly = true)
    Lookup lookup(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() < Search.MIN_PHONE_DIGITS) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "phone", "invalid", "Type at least " + Search.MIN_PHONE_DIGITS + " digits of the phone number.")));
        }
        List<UUID> filter = CurrentPrincipal.require().branchFilter(MANAGE, null);
        Map<String, Object> last = repo.lastByPhone(digits, filter).orElse(null);
        UUID buyer = repo.customerByPhone(digits).orElse(null);
        if (last == null && buyer == null) {
            return new Lookup(false, null, null, null, null, null, 0);
        }
        Customer c = buyer == null ? null : salesRepo.customer(buyer).orElse(null);
        UUID customerId = last != null && last.get("customer_id") != null ? (UUID) last.get("customer_id") : buyer;
        return new Lookup(
                true,
                customerId,
                last != null ? (String) last.get("customer_name") : c == null ? null : c.name(),
                last != null ? (String) last.get("customer_phone") : c == null ? null : c.contact(),
                last == null ? null : (String) last.get("delivery_place"),
                last == null ? null : (Long) last.get("delivery_fee_minor"),
                last == null ? 0 : (Long) last.get("orders"));
    }

    // ---- Changing the delivery details -------------------------------------------------------------

    @Transactional
    Order change(UUID id, String ifMatch, ChangeRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        Header h = visible(id, true);
        if (h.version() != expected) {
            throw Versions.conflict(h.version());
        }
        requireOpen(h, "changed");
        String name = r.customerName() == null ? h.customerName() : blankToNull(r.customerName());
        if (name == null) {
            throw ApiException.validation(
                    List.of(new FieldProblem("customer_name", "invalid", "The name must not be blank.")));
        }
        String phone = r.customerPhone() == null ? h.customerPhone() : blankToNull(r.customerPhone());
        String place = r.deliveryPlace() == null ? h.deliveryPlace() : blankToNull(r.deliveryPlace());
        LocalDate date = Boolean.TRUE.equals(r.clearDeliveryDate())
                ? null
                : r.deliveryDate() == null ? h.deliveryDate() : r.deliveryDate();
        if (r.deliveryDate() != null && r.deliveryDate().isBefore(today())) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "delivery_date", "invalid", "A delivery cannot be planned for a day that has passed.")));
        }
        long fee = r.deliveryFeeMinor() == null ? h.deliveryFeeMinor() : r.deliveryFeeMinor();
        if (h.itemsTotalMinor() + fee > RetailCatalogue.MAX_AMOUNT_MINOR) {
            throw ApiException.rule("amount_out_of_range", "The order is too large to compute.");
        }
        String notes = r.notes() == null ? h.notes() : blankToNull(r.notes());
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        List<String> words = new ArrayList<>();
        if (!name.equals(h.customerName())) {
            was.put("customer_name", h.customerName());
            now.put("customer_name", name);
            words.add("name");
        }
        if (!Objects.equals(phone, h.customerPhone())) {
            // A phone number is personal data: the audit says it changed, not what it is.
            now.put("customer_phone_changed", true);
            words.add("phone");
        }
        if (!Objects.equals(place, h.deliveryPlace())) {
            now.put("delivery_place_changed", true);
            words.add("delivery place");
        }
        if (!Objects.equals(date, h.deliveryDate())) {
            was.put("delivery_date", h.deliveryDate());
            now.put("delivery_date", date);
            words.add("delivery day");
        }
        if (fee != h.deliveryFeeMinor()) {
            was.put("delivery_fee_minor", h.deliveryFeeMinor());
            now.put("delivery_fee_minor", fee);
            words.add("delivery fee");
        }
        if (!Objects.equals(notes, h.notes())) {
            now.put("notes_changed", true);
            words.add("notes");
        }
        if (now.isEmpty()) {
            return view(h, true);
        }
        if (repo.markChanged(id, h.version(), name, phone, place, date, fee, notes) == 0) {
            throw Versions.conflict(repo.find(id, false).orElseThrow().version());
        }
        UUID by = CurrentPrincipal.require().userId();
        repo.event(id, "changed", "Changed: " + String.join(", ", words) + ".", by);
        audit.record(new AuditLog.Entry("retail.order.changed", ORDER, id, h.branchId(), was, now));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    // ---- Changing the items ----------------------------------------------------------------------------

    /**
     * The items of an open order change without cancelling it (issue #393, ADR-052 amendment 1), while it is ordered or packed:
     * in one transaction under the order row's lock, then the balance rows' locks, the stock kept for the removed or reduced
     * lines is given back and the stock for the new or larger ones is kept (refused with {@code insufficient_stock} when not
     * enough is free), the new lines are written as the order's next items revision and the history says what changed. The
     * order keeps its number; a packed order goes back to ordered, to be packed again.
     */
    @Transactional
    Outcome<Order> changeItems(String idempotencyKey, UUID id, String ifMatch, ItemsRequest r) {
        return idempotency.once(
                idempotencyKey,
                "POST",
                PATH + "/" + id + "/items",
                r,
                Order.class,
                () -> doChangeItems(id, ifMatch, r));
    }

    /** An item in a unit: how a line of the new items finds the line it keeps. */
    private record LineKey(UUID productId, UUID unitId) {}

    /** A line of the new items as it is written: in the unit ordered, at its price, with how that price was chosen. */
    private record NewItem(
            int lineNo,
            UUID productId,
            UUID unitId,
            BigDecimal qty,
            BigDecimal baseQty,
            long unitPriceMinor,
            long totalMinor,
            LinePricing pricing,
            UUID by) {

        String key() {
            return productId + "|" + unitId + "|" + qty.stripTrailingZeros().toPlainString() + "|" + unitPriceMinor;
        }
    }

    private Order doChangeItems(UUID id, String ifMatch, ItemsRequest r) {
        int expected = Versions.fromIfMatch(ifMatch);
        Principal principal = CurrentPrincipal.require();
        // The order row first, then the balance rows (in StockLedger.rehold), as delivery and cancel take them.
        Header h = visible(id, true);
        if (!h.status().equals("ordered") && !h.status().equals("packed")) {
            throw itemsFixed(h);
        }
        if (h.version() != expected) {
            throw Versions.conflict(h.version());
        }
        roles.requireMaySell(h.branchId());
        branches.requireSells(h.branchId(), "branch_id");
        List<StoredLine> was = repo.lines(id);
        Map<LineKey, List<StoredLine>> onOrder = new HashMap<>();
        for (StoredLine l : was) {
            onOrder.computeIfAbsent(new LineKey(l.productId(), l.unitId()), k -> new ArrayList<>())
                    .add(l);
        }
        // A line left as it was (the same item, unit and quantity, with no price or the price agreed)
        // keeps the price agreed and how it was chosen: it goes to priceLines with an Agreement, as on
        // delivery, so an item switched off or a cost risen since does not block the change. Every other
        // line, one whose quantity changed included, is priced as a new order's line would be: price
        // breaks found again, a typed price an override with the permission and a reason, credited to
        // the person changing it. An agreed break or resolved price is never carried to a new quantity.
        List<StoredLine> kept = new ArrayList<>();
        List<SaleLineRequest> requested = new ArrayList<>();
        List<SalesService.Agreement> agreements = new ArrayList<>();
        for (CustomerOrdersApi.OrderLineRequest l : r.lines()) {
            List<StoredLine> same = onOrder.getOrDefault(new LineKey(l.productId(), l.unitId()), List.of());
            StoredLine keep = same.stream()
                    .filter(s -> s.qty().compareTo(l.qty()) == 0
                            && (l.unitPriceMinor() == null || l.unitPriceMinor() == s.unitPriceMinor()))
                    .findFirst()
                    .orElse(null);
            kept.add(keep);
            if (keep != null) {
                same.remove(keep);
                String why = "override".equals(keep.priceSource())
                        ? keep.overrideReason()
                        : "Price agreed on order " + h.orderNo();
                requested.add(new SaleLineRequest(l.productId(), l.qty(), keep.unitPriceMinor(), l.unitId(), why));
                agreements.add(new SalesService.Agreement(keep.priceSource(), keep.overriddenBy()));
            } else {
                requested.add(new SaleLineRequest(
                        l.productId(), l.qty(), l.unitPriceMinor(), l.unitId(), l.priceOverrideReason()));
                agreements.add(null);
            }
        }
        Customer buyer = h.customerId() == null
                ? null
                : salesRepo.customer(h.customerId()).orElse(null);
        List<FieldProblem> problems = new ArrayList<>();
        Priced priced = sales.priceLines(h.branchId(), today(), buyer, null, requested, problems, agreements);
        int revision = h.itemsRevision() + 1;
        List<Hold> holds = new ArrayList<>();
        List<NewItem> rows = new ArrayList<>();
        long items = 0;
        for (int i = 0; i < priced.lines().size(); i++) {
            NewLine l = priced.lines().get(i);
            StoredLine keep = kept.get(i);
            NewItem row;
            if (keep != null) {
                // Written as it was: the unit, the quantities, the price and how it was chosen, the override's author.
                row = new NewItem(
                        l.lineNo(),
                        keep.productId(),
                        keep.unitId(),
                        keep.qty(),
                        keep.baseQty(),
                        keep.unitPriceMinor(),
                        keep.lineTotalMinor(),
                        keep.pricing(),
                        keep.overriddenBy());
            } else {
                boolean pack = l.saleUnitId() != null;
                row = new NewItem(
                        l.lineNo(),
                        l.productId(),
                        l.saleUnitId(),
                        pack ? l.saleQty() : l.qty(),
                        l.qty(),
                        pack ? l.saleUnitPriceMinor() : l.unitPriceMinor(),
                        l.lineTotalMinor(),
                        l.pricing(),
                        principal.userId());
            }
            items = Math.addExact(items, row.totalMinor());
            rows.add(row);
            holds.add(new Hold(h.branchId(), row.productId(), row.baseQty()));
        }
        if (items > RetailCatalogue.MAX_AMOUNT_MINOR
                || items + h.deliveryFeeMinor() > RetailCatalogue.MAX_AMOUNT_MINOR) {
            throw ApiException.rule("amount_out_of_range", "The order is too large to compute.");
        }
        if (sameItems(was, rows)) {
            return view(h, true);
        }
        for (NewItem row : rows) {
            repo.insertLine(
                    id,
                    revision,
                    row.lineNo(),
                    row.productId(),
                    row.unitId(),
                    row.qty(),
                    row.baseQty(),
                    row.unitPriceMinor(),
                    row.totalMinor(),
                    row.pricing(),
                    row.by());
        }
        // Under the balance rows' locks, netted per item: what was kept for the old lines goes back and the new lines
        // are
        // kept, refused when not enough is free.
        stock.rehold(
                was.stream()
                        .map(l -> new Hold(h.branchId(), l.productId(), l.baseQty()))
                        .toList(),
                holds);
        // A packed order whose items changed is ordered again: what was packed is not what it now holds.
        boolean wasPacked = h.status().equals("packed");
        if (repo.markItemsChanged(id, h.version(), revision, items) == 0) {
            throw Versions.conflict(repo.find(id, false).orElseThrow().version());
        }
        List<StoredLine> now = repo.lines(id);
        String changes = itemChanges(was, now);
        String note = blankToNull(r.note());
        String text = "Items changed: " + changes + "."
                + (wasPacked ? " It was packed, so it is back to ordered: pack it again." : "")
                + (note == null ? "" : " " + note);
        repo.event(
                id, "items_changed", text.length() > 500 ? text.substring(0, 497) + "..." : text, principal.userId());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("items_total_minor", h.itemsTotalMinor());
        before.put("lines", auditLines(was));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("items_total_minor", items);
        after.put("lines", auditLines(now));
        after.put("items_revision", revision);
        audit.record(new AuditLog.Entry("retail.order.items_changed", ORDER, id, h.branchId(), before, after));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    /** True when the new lines are the old ones: the same items, units, quantities and prices in any order. */
    private static boolean sameItems(List<StoredLine> was, List<NewItem> rows) {
        if (was.size() != rows.size()) {
            return false;
        }
        List<String> a = new ArrayList<>();
        for (StoredLine l : was) {
            a.add(l.productId() + "|" + l.unitId() + "|"
                    + l.qty().stripTrailingZeros().toPlainString() + "|" + l.unitPriceMinor());
        }
        List<String> b = new ArrayList<>(rows.stream().map(NewItem::key).toList());
        a.sort(null);
        b.sort(null);
        return a.equals(b);
    }

    /** What changed, in plain words: "added 2 Baby vest, removed Socks, Bib 1 to 3, price of Cap 5000 to 4500". */
    static String itemChanges(List<StoredLine> was, List<StoredLine> now) {
        Map<LineKey, StoredLine[]> byKey = new LinkedHashMap<>();
        Map<LineKey, BigDecimal[]> qty = new LinkedHashMap<>();
        for (StoredLine l : was) {
            LineKey k = new LineKey(l.productId(), l.unitId());
            byKey.computeIfAbsent(k, x -> new StoredLine[2])[0] = l;
            qty.computeIfAbsent(k, x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO})[0] =
                    qty.get(k)[0].add(l.qty());
        }
        for (StoredLine l : now) {
            LineKey k = new LineKey(l.productId(), l.unitId());
            byKey.computeIfAbsent(k, x -> new StoredLine[2])[1] = l;
            qty.computeIfAbsent(k, x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO})[1] =
                    qty.get(k)[1].add(l.qty());
        }
        List<String> words = new ArrayList<>();
        for (Map.Entry<LineKey, StoredLine[]> e : byKey.entrySet()) {
            StoredLine before = e.getValue()[0];
            StoredLine after = e.getValue()[1];
            BigDecimal[] q = qty.get(e.getKey());
            String name = (after != null ? after : before).description();
            String unit = (after != null ? after : before).unit();
            if (before == null) {
                words.add("added " + plain(q[1]) + " " + unit + " " + name);
            } else if (after == null) {
                words.add("removed " + name);
            } else {
                if (q[0].compareTo(q[1]) != 0) {
                    words.add(name + " " + plain(q[0]) + " to " + plain(q[1]) + " " + unit);
                }
                if (before.unitPriceMinor() != after.unitPriceMinor()) {
                    words.add("price of " + name + " " + before.unitPriceMinor() + " to " + after.unitPriceMinor());
                }
            }
        }
        return words.isEmpty() ? "the lines were put in another order" : String.join(", ", words);
    }

    private static String plain(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }

    private static List<Map<String, Object>> auditLines(List<StoredLine> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StoredLine l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("product_id", l.productId());
            m.put("unit_id", l.unitId());
            m.put("qty", plain(l.qty()));
            m.put("unit_price_minor", l.unitPriceMinor());
            m.put("price_source", l.priceSource());
            out.add(m);
        }
        return out;
    }

    /** 409 {@code order_step_not_allowed}: the items of an order out for delivery, delivered or cancelled are fixed. */
    private static ApiException itemsFixed(Header h) {
        String where = switch (h.status()) {
            case "delivered" -> "was delivered already";
            case "cancelled" -> "was cancelled";
            default -> "is out for delivery";
        };
        return new ApiException(
                HttpStatus.CONFLICT,
                "order_step_not_allowed",
                "Order step not allowed",
                "Order " + h.orderNo() + " " + where + ", so its items cannot be changed."
                        + (h.status().equals("out_for_delivery")
                                ? " Change the items before it goes out, or cancel it and take a new order."
                                : ""));
    }

    // ---- Packed and out for delivery ---------------------------------------------------------------

    /** Packed. A retry on an order already packed answers the order as it is, so a double tap is harmless. */
    @Transactional
    Order pack(UUID id, StepRequest r) {
        Header h = visible(id, true);
        if (h.status().equals("packed")) {
            return view(h, true);
        }
        if (!h.status().equals("ordered")) {
            throw stepRefused(h, "marked packed");
        }
        UUID by = CurrentPrincipal.require().userId();
        if (repo.markPacked(id, h.version(), by) == 0) {
            throw stepRefused(h, "marked packed");
        }
        repo.event(id, "packed", blankToNull(r == null ? null : r.note()), by);
        audit.record(new AuditLog.Entry(
                "retail.order.packed",
                ORDER,
                id,
                h.branchId(),
                Map.of("status", h.status()),
                Map.of("status", "packed")));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    /** Out for delivery. A retry with the same rider on an order already out answers the order as it is. */
    @Transactional
    Order send(UUID id, SendRequest r) {
        Header h = visible(id, true);
        String rider = r.deliveredBy().trim();
        if (h.status().equals("out_for_delivery") && rider.equals(h.deliveredBy())) {
            return view(h, true);
        }
        if (!h.status().equals("ordered") && !h.status().equals("packed")) {
            throw stepRefused(h, "marked out for delivery");
        }
        UUID by = CurrentPrincipal.require().userId();
        if (repo.markSent(id, h.version(), by, rider) == 0) {
            throw stepRefused(h, "marked out for delivery");
        }
        String note = blankToNull(r.note());
        repo.event(id, "out_for_delivery", "Taken out by " + rider + "." + (note == null ? "" : " " + note), by);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "out_for_delivery");
        after.put("delivered_by", rider);
        audit.record(
                new AuditLog.Entry("retail.order.sent", ORDER, id, h.branchId(), Map.of("status", h.status()), after));
        toldTaker(h, "retail.order_out_for_delivery", by);
        return view(repo.find(id, false).orElseThrow(), true);
    }

    // ---- Delivered: the order becomes a sale --------------------------------------------------------

    @Transactional
    Outcome<Delivered> deliver(String idempotencyKey, UUID id, DeliverRequest r) {
        return idempotency.once(
                idempotencyKey, "POST", PATH + "/" + id + "/deliver", r, Delivered.class, () -> doDeliver(id, r));
    }

    private Delivered doDeliver(UUID id, DeliverRequest r) {
        Principal principal = CurrentPrincipal.require();
        Header h = visible(id, true);
        requireOpen(h, "marked delivered");
        if (!principal.may("retail.sale.create", h.branchId())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Delivering an order records a sale, and you may not record sales at this branch.");
        }
        boolean credit = r.paymentMethod().equals("credit");
        boolean feeOnAccount = Boolean.TRUE.equals(r.feeOnAccount());
        long items = h.itemsTotalMinor();
        long fee = h.deliveryFeeMinor();
        long paidNow = r.paidNowMinor() == null ? 0 : r.paidNowMinor();
        List<FieldProblem> problems = new ArrayList<>();
        if (!credit && r.paidNowMinor() != null) {
            problems.add(new FieldProblem(
                    "paid_now_minor",
                    "invalid",
                    "Only a part payment names the amount paid now. Paid in full needs no amount."));
        }
        if (!credit && r.paidNowMethod() != null) {
            problems.add(new FieldProblem(
                    "paid_now_method", "invalid", "Paid in full is paid by the payment method; leave this out."));
        }
        if (!credit && r.dueDate() != null) {
            problems.add(new FieldProblem("due_date", "invalid", "Only an order on credit has a payment date."));
        }
        if (!credit && feeOnAccount) {
            problems.add(new FieldProblem(
                    "fee_on_account", "invalid", "Only an order on credit can leave the delivery fee on the account."));
        }
        if (credit && paidNow > 0 && r.paidNowMethod() == null) {
            problems.add(new FieldProblem("paid_now_method", "required", "Say how the money taken now was paid."));
        }
        if (credit && paidNow == 0 && r.paidNowMethod() != null) {
            problems.add(new FieldProblem(
                    "paid_now_method", "invalid", "No money is taken now, so there is no way it was paid."));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        if (feeOnAccount && (h.customerId() == null || fee == 0)) {
            throw ApiException.rule(
                    "fee_on_account_not_allowed",
                    fee == 0
                            ? "This order has no delivery fee."
                            : "Only a credit buyer on the list can have the delivery fee put on the account.");
        }
        if (credit && paidNow >= items + fee) {
            throw ApiException.rule(
                    "paid_in_full",
                    "The customer paid everything, so nothing goes on credit. Choose cash, mobile money or bank.");
        }
        if (credit && !feeOnAccount && paidNow < fee) {
            throw ApiException.rule(
                    "delivery_fee_unpaid",
                    "The delivery fee of " + fee + " is paid when the goods arrive. Take at least that now; the rest"
                            + " of the order can go on credit. A credit buyer on the list can have the fee put on the account.");
        }
        if (feeOnAccount && paidNow > items) {
            throw ApiException.rule(
                    "paid_now_exceeds_items",
                    "With the delivery fee on the account, the money taken now pays the items, so it cannot be more than "
                            + items + ".");
        }
        LocalDate today = today();
        // Locks in the order of the sale path: the order row (taken above), the buyer's row, then the balance rows.
        if (credit && h.customerId() != null && accounts.on()) {
            accounts.lockForSale(h.customerId());
        }
        List<StoredLine> lines = repo.lines(id);
        stock.release(lines.stream()
                .map(l -> new Hold(h.branchId(), l.productId(), l.baseQty()))
                .toList());
        boolean unitsOn = capabilities.enabled(RetailCapabilities.UNITS);
        List<SaleLineRequest> saleLines = new ArrayList<>();
        List<SalesService.Agreement> agreements = new ArrayList<>();
        for (StoredLine l : lines) {
            // A price changed when the order was taken keeps its own reason; any other agreed price says where it came
            // from.
            String agreed =
                    "override".equals(l.priceSource()) ? l.overrideReason() : "Price agreed on order " + h.orderNo();
            agreements.add(new SalesService.Agreement(l.priceSource(), l.overriddenBy()));
            if (l.unitId() != null && !unitsOn) {
                // Pack sizes were switched off since the order: the line is sold in base units at the same line total.
                saleLines.add(new SaleLineRequest(
                        l.productId(), l.baseQty(), Quantities.perBase(l.lineTotalMinor(), l.baseQty()), null, agreed));
            } else {
                saleLines.add(new SaleLineRequest(l.productId(), l.qty(), l.unitPriceMinor(), l.unitId(), agreed));
            }
        }
        // What goes on the buyer's credit: the money taken now pays the fee first (unless the fee is on the account),
        // the rest
        // pays the items.
        long onSale = !credit ? 0 : feeOnAccount ? paidNow : paidNow - fee;
        // Added to the sale's own total (which the sale computes from the lines, rounding included) to give what goes
        // on credit.
        long creditDelta = (feeOnAccount ? fee : 0) - onSale;
        // The ordinary sale path at the prices agreed on the order: stock, the two journal entries, credit and its
        // limit
        // (checked against what actually goes on credit), the receipt number and the audit row.
        Sale sale = sales.record(
                new SaleRequest(
                        h.branchId(),
                        null,
                        r.paymentMethod(),
                        h.customerId(),
                        h.customerId() == null ? h.customerName() : null,
                        h.customerId() == null ? h.customerPhone() : null,
                        credit ? r.dueDate() : null,
                        null,
                        r.creditOverrideReason(),
                        saleLines),
                credit ? creditDelta : null,
                agreements);
        // The sale's own total decides, not the order's stored sum: a line converted to base units may round
        // differently.
        if (credit && paidNow >= Math.addExact(sale.totalMinor(), fee)) {
            throw ApiException.rule(
                    "paid_in_full",
                    "The customer paid everything, so nothing goes on credit. Choose cash, mobile money or bank.");
        }
        if (feeOnAccount && paidNow > sale.totalMinor()) {
            throw ApiException.rule(
                    "paid_now_exceeds_items",
                    "With the delivery fee on the account, the money taken now pays the items, so it cannot be more than "
                            + sale.totalMinor() + ".");
        }
        if (onSale > 0) {
            sales.recordPayment(sale.id(), new PaymentRequest(onSale, r.paidNowMethod(), null));
        }
        String feeMethod = fee == 0 ? null : feeOnAccount ? "account" : credit ? r.paidNowMethod() : r.paymentMethod();
        Leg feeDebit = feeOnAccount
                ? Leg.debit("trade_debtors", fee).withSubledger(SalesService.SALE, sale.id())
                : fee == 0 ? null : Leg.debit(SalesService.accountFor(feeMethod), fee);
        UUID feeEntry = fee == 0
                ? null
                : books.post(new Posting(
                                h.branchId(),
                                today,
                                h.orderNo(),
                                "Delivery fee " + h.orderNo(),
                                ORDER,
                                id,
                                "retail.order_fee:" + id,
                                List.of(feeDebit, Leg.credit("delivery_income", fee))))
                        .map(PostedEntry::entryId)
                        .orElse(null);
        if (repo.markDelivered(id, h.version(), principal.userId(), today, sale.id(), feeMethod, feeEntry) == 0) {
            throw stepRefused(h, "marked delivered");
        }
        long paidAtDoor = credit ? paidNow : Math.addExact(sale.totalMinor(), fee);
        long owed = credit ? Math.addExact(sale.totalMinor(), fee) - paidNow : 0;
        String how = credit
                ? (paidNow == 0 ? "All on credit." : "Part payment of " + paidNow + ", " + owed + " on credit.")
                        + (feeOnAccount ? " The delivery fee is on the account." : "")
                : "Paid in full by " + r.paymentMethod().replace('_', ' ') + ".";
        String note = blankToNull(r.note());
        repo.event(
                id,
                "delivered",
                "Sale " + sale.saleNo() + ". " + how + (note == null ? "" : " " + note),
                principal.userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "delivered");
        after.put("sale_id", sale.id());
        after.put("sale_no", sale.saleNo());
        after.put("payment_method", r.paymentMethod());
        after.put("paid_now_minor", paidAtDoor);
        after.put("delivery_fee_minor", fee);
        after.put("fee_on_account", feeOnAccount);
        // A price changed when the order was taken is carried to the sale as an override of the person who changed it.
        List<Map<String, Object>> carried = new ArrayList<>();
        for (StoredLine l : lines) {
            if ("override".equals(l.priceSource())) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("line_no", l.lineNo());
                o.put("product_id", l.productId());
                o.put("overridden_by", l.overriddenBy());
                o.put("reason", l.overrideReason());
                carried.add(o);
            }
        }
        if (!carried.isEmpty()) {
            after.put("price_overrides_carried", carried);
        }
        audit.record(new AuditLog.Entry(
                "retail.order.delivered", ORDER, id, h.branchId(), Map.of("status", h.status()), after));
        toldTaker(h, "retail.order_delivered", principal.userId());
        return new Delivered(
                view(repo.find(id, false).orElseThrow(), true), sale.id(), sale.saleNo(), paidAtDoor, owed);
    }

    // ---- A delivery fee left on the account, paid later ----------------------------------------------

    @Transactional
    Outcome<Order> payFee(String idempotencyKey, UUID id, FeePaymentRequest r) {
        return idempotency.once(
                idempotencyKey, "POST", PATH + "/" + id + "/fee-payment", r, Order.class, () -> doPayFee(id, r));
    }

    private Order doPayFee(UUID id, FeePaymentRequest r) {
        Principal principal = CurrentPrincipal.require();
        // Taking the money is the credit payment permission's (retail.sale.create at the branch), as on a credit sale.
        Header h = repo.find(id, true)
                .filter(x -> principal.may(FEE_PAYMENT, x.branchId()))
                .orElseThrow(ApiException::notFound);
        if (!h.status().equals("delivered") || !"account".equals(h.feeMethod())) {
            throw ApiException.rule("fee_not_on_account", "This order has no delivery fee left on the account.");
        }
        if (h.feePaidOn() != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "fee_already_paid",
                    "Fee already paid",
                    "The delivery fee of order " + h.orderNo() + " was paid on " + h.feePaidOn() + ".");
        }
        branches.requireSells(h.branchId(), "branch_id");
        if (salesRepo
                .find(h.saleId(), false)
                .map(x -> x.sale().status())
                .orElse("")
                .equals("voided")) {
            throw ApiException.rule(
                    "sale_voided", "The sale of this order was voided, so its delivery fee was reversed.");
        }
        LocalDate today = today();
        UUID entry = books.post(new Posting(
                        h.branchId(),
                        today,
                        h.orderNo(),
                        "Delivery fee paid " + h.orderNo(),
                        ORDER,
                        id,
                        "retail.order_fee_payment:" + id,
                        List.of(
                                Leg.debit(SalesService.accountFor(r.method()), h.deliveryFeeMinor()),
                                Leg.credit("trade_debtors", h.deliveryFeeMinor())
                                        .withSubledger(SalesService.SALE, h.saleId()))))
                .map(PostedEntry::entryId)
                .orElseThrow();
        if (repo.markFeePaid(id, h.version(), principal.userId(), today, r.method(), entry) == 0) {
            throw Versions.conflict(repo.find(id, false).orElseThrow().version());
        }
        repo.event(
                id,
                "fee_paid",
                "Delivery fee of " + h.deliveryFeeMinor() + " paid by "
                        + r.method().replace('_', ' ') + ".",
                principal.userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fee_paid_method", r.method());
        after.put("delivery_fee_minor", h.deliveryFeeMinor());
        audit.record(new AuditLog.Entry("retail.order.fee_paid", ORDER, id, h.branchId(), Map.of(), after));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    // ---- Cancelled -----------------------------------------------------------------------------------

    /** Cancelled, with a reason. A retry on an order already cancelled answers the order as it is. */
    @Transactional
    Order cancel(UUID id, CancelRequest r) {
        Header h = visible(id, true);
        if (h.status().equals("cancelled")) {
            return view(h, true);
        }
        requireOpen(h, "cancelled");
        String reason = r.reason().trim();
        UUID by = CurrentPrincipal.require().userId();
        stock.release(repo.lines(id).stream()
                .map(l -> new Hold(h.branchId(), l.productId(), l.baseQty()))
                .toList());
        if (repo.markCancelled(id, h.version(), by, today(), reason) == 0) {
            throw stepRefused(h, "cancelled");
        }
        repo.event(id, "cancelled", reason, by);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "cancelled");
        after.put("reason", reason);
        audit.record(new AuditLog.Entry(
                "retail.order.cancelled", ORDER, id, h.branchId(), Map.of("status", h.status()), after));
        return view(repo.find(id, false).orElseThrow(), true);
    }

    // ---- Reports -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    FeesReport fees(List<UUID> branchIds, LocalDate from, LocalDate to, String groupBy) {
        String group = groupBy == null ? "day" : groupBy;
        if (!Set.of("day", "source", "rider").contains(group)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("group_by", "invalid", "group_by is day, source or rider.")));
        }
        LocalDate[] range = range(from, to);
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter(MANAGE, branchIds);
        requireAllSellers(principal, filter);
        List<ReportRow> rows = repo.fees(filter, range[0], range[1], group).stream()
                .map(row -> new ReportRow(
                        row.key(), label(group, row.key()), row.orders(), row.itemsMinor(), row.feesMinor()))
                .toList();
        return new FeesReport(
                range[0],
                range[1],
                group,
                tenant.profile().currency(),
                rows.stream().mapToLong(ReportRow::orders).sum(),
                rows.stream().mapToLong(ReportRow::itemsMinor).reduce(0, Math::addExact),
                rows.stream().mapToLong(ReportRow::feesMinor).reduce(0, Math::addExact),
                rows);
    }

    /**
     * ADR-055: the fees, the value of everybody's orders and who cancelled them are the owner's figures; a seller who sees only
     * their own sales is refused (403), as for the dashboard.
     */
    private static void requireAllSellers(Principal principal, List<UUID> filter) {
        if (SalesSummaryService.ownSellerOnly(principal, filter, null) != null) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "This report adds up everybody's orders; it is for the owner.");
        }
    }

    @Transactional(readOnly = true)
    CancellationsReport cancellations(List<UUID> branchIds, LocalDate from, LocalDate to) {
        LocalDate[] range = range(from, to);
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter(MANAGE, branchIds);
        requireAllSellers(principal, filter);
        List<ReportRow> bySource = repo.cancelledBySource(filter, range[0], range[1]).stream()
                .map(row -> new ReportRow(row.key(), label("source", row.key()), row.orders(), row.itemsMinor(), 0))
                .toList();
        return new CancellationsReport(
                range[0],
                range[1],
                tenant.profile().currency(),
                bySource.stream().mapToLong(ReportRow::orders).sum(),
                bySource.stream().mapToLong(ReportRow::itemsMinor).reduce(0, Math::addExact),
                bySource,
                repo.cancelled(filter, range[0], range[1], 500));
    }

    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate today = today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("from", "invalid", "The first day is after the last day.")));
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_REPORT_DAYS) {
            throw ApiException.validation(
                    List.of(new FieldProblem("to", "invalid", "Choose at most " + MAX_REPORT_DAYS + " days.")));
        }
        return new LocalDate[] {start, end};
    }

    /** The words a report shows for a group key. */
    static String label(String groupBy, String key) {
        if (groupBy.equals("source")) {
            return switch (key) {
                case "whatsapp" -> "WhatsApp";
                case "instagram" -> "Instagram";
                case "facebook" -> "Facebook";
                case "phone" -> "Phone call";
                case "walk_in" -> "Walk-in";
                default -> "Other";
            };
        }
        if (groupBy.equals("rider")) {
            return key == null || key.isEmpty() ? "Collected at the shop" : key;
        }
        return key;
    }

    // ---- Helpers -------------------------------------------------------------------------------------

    /**
     * Issue #412: the person who took the order hears that it went out and that it arrived, unless they did the step
     * themselves. The notice names the shop and never the customer.
     */
    private void toldTaker(Header h, String template, UUID by) {
        if (h.createdBy() == null || h.createdBy().equals(by)) {
            return;
        }
        events.publishEvent(new PushNotice(
                template,
                h.id().toString(),
                new PushNotice.Users(Set.of(h.createdBy())),
                Map.of("branch_id", h.branchId().toString(), "path", "/staff/retail/orders/" + h.id())));
    }

    /** How a remote order came in, in the words a notification uses. */
    static String sourceWords(String source) {
        return switch (source == null ? "" : source) {
            case "whatsapp" -> "WhatsApp";
            case "instagram" -> "Instagram";
            case "facebook" -> "Facebook";
            case "phone" -> "phone";
            default -> "another channel";
        };
    }

    /**
     * ADR-055 (the owner's decision of 21 October 2026): every seller of the branch sees the orders and their values, the shop's
     * shared work, but not a colleague's figures: the sale a colleague made on delivery is not linked (no id or number, and the
     * history's note does not name it), and who took, moved or delivered the order is shown only for the caller's own steps,
     * unless the caller may see every seller's sales there. The sales the caller made are found for a whole page at once.
     */
    private Set<UUID> salesMadeByCaller(List<Header> headers) {
        List<UUID> ids =
                headers.stream().map(Header::saleId).filter(Objects::nonNull).toList();
        return repo.salesMadeBy(ids, CurrentPrincipal.require().userId());
    }

    private static final java.util.regex.Pattern SALE_NOTE = java.util.regex.Pattern.compile("^Sale \\S+\\. ?");

    private Order view(Header h, boolean withHistory) {
        return view(h, withHistory, salesMadeByCaller(List.of(h)));
    }

    private Order view(Header h, boolean withHistory, Set<UUID> mine) {
        Principal principal = CurrentPrincipal.require();
        boolean everyone = principal.may(SalesSummaryService.ALL_SELLERS, h.branchId());
        boolean sale = h.saleId() != null && (everyone || mine.contains(h.saleId()));
        UUID me = principal.userId();
        List<OrderEvent> events = withHistory ? repo.events(h.id()) : null;
        if (events != null && !everyone) {
            events = events.stream()
                    .map(e -> new OrderEvent(
                            e.step(),
                            sale || e.note() == null
                                    ? e.note()
                                    : SALE_NOTE.matcher(e.note()).replaceFirst(""),
                            me.equals(e.byUser()) ? e.byUser() : null,
                            me.equals(e.byUser()) ? e.byName() : null,
                            e.at()))
                    .toList();
        }
        return new Order(
                h.id(),
                h.orderNo(),
                h.branchId(),
                h.orderedOn(),
                h.source(),
                h.customerId(),
                h.customerName(),
                h.customerPhone(),
                h.deliveryPlace(),
                h.deliveryDate(),
                h.deliveryFeeMinor(),
                h.currency(),
                h.itemsTotalMinor(),
                h.itemsTotalMinor() + h.deliveryFeeMinor(),
                h.notes(),
                h.status(),
                everyone || sale || h.saleId() == null ? h.deliveredBy() : null,
                h.packedAt(),
                h.sentAt(),
                h.deliveredAt(),
                h.deliveredOn(),
                sale ? h.saleId() : null,
                sale ? h.saleNo() : null,
                h.feeMethod(),
                h.cancelledAt(),
                h.cancelReason(),
                h.feePaidOn(),
                h.feePaidMethod(),
                "voided".equals(h.saleStatus()) ? Boolean.TRUE : null,
                h.createdAt(),
                everyone || me.equals(h.createdBy()) ? h.createdBy() : null,
                h.version(),
                repo.lines(h.id()).stream().map(StoredLine::view).toList(),
                events);
    }

    private static void requireOpen(Header h, String step) {
        if (!h.open()) {
            throw stepRefused(h, step);
        }
    }

    /** 409 {@code order_step_not_allowed}, in the words of where the order stands; {@code step} says what was asked ("marked packed"). */
    static ApiException stepRefused(Header h, String step) {
        String where = switch (h.status()) {
            case "delivered" -> "was delivered already";
            case "cancelled" -> "was cancelled";
            case "packed" -> "is packed already";
            case "out_for_delivery" -> "is out for delivery already";
            default -> "is not packed yet";
        };
        return new ApiException(
                HttpStatus.CONFLICT,
                "order_step_not_allowed",
                "Order step not allowed",
                "Order " + h.orderNo() + " " + where + ", so it cannot be " + step + ".");
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
