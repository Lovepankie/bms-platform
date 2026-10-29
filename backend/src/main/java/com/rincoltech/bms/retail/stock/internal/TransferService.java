package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ModuleSettingsChangeGuard;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.PushNotice;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.ProductSnapshot;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.Movement;
import com.rincoltech.bms.retail.stock.StockLedger.Recorded;
import com.rincoltech.bms.retail.stock.TransferOutGuard;
import com.rincoltech.bms.retail.stock.internal.TransferApi.CancelRequest;
import com.rincoltech.bms.retail.stock.internal.TransferApi.ReceiveLine;
import com.rincoltech.bms.retail.stock.internal.TransferApi.ReceiveRequest;
import com.rincoltech.bms.retail.stock.internal.TransferApi.Transfer;
import com.rincoltech.bms.retail.stock.internal.TransferApi.TransferLine;
import com.rincoltech.bms.retail.stock.internal.TransferApi.TransferLineRequest;
import com.rincoltech.bms.retail.stock.internal.TransferApi.TransferPage;
import com.rincoltech.bms.retail.stock.internal.TransferApi.TransferRequest;
import com.rincoltech.bms.retail.stock.internal.TransferApi.VoidRequest;
import com.rincoltech.bms.retail.stock.internal.TransferRepository.Header;
import com.rincoltech.bms.retail.stock.internal.TransferRepository.NewLine;
import com.rincoltech.bms.retail.stock.internal.TransferRepository.StoredLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock transfers between branches (FR-RET-16, ADR-020 amendment of 2026-10-06, ADR-033). By
 * default one transaction moves every line out of the source and into the destination at the
 * source's current cost, so nothing is gained or lost: a {@code transfer_out} and a {@code
 * transfer_in} movement per line sharing the transfer's id, and one journal entry per branch
 * through inter-branch clearing (ADR-004). The caller acts on the source branch; the destination
 * is any active branch of the tenant. Overselling at the source is refused like a sale, under the
 * balance locks, which are taken in branch and product order so opposite transfers cannot
 * deadlock.
 *
 * <p>With the {@code transfer_receipt} capability on (docs/specs/trade-capabilities.md section
 * 9.3) the same call dispatches: the source's {@code transfer_out} movement and entry post at
 * once and the status is {@code in_transit}, so the stock is in neither branch's balance and its
 * value sits in inter-branch clearing. The destination later receives it (its {@code transfer_in}
 * movements and entry, for what arrived; a shortage is posted as stock shrinkage at the
 * destination, valued at the SOURCE cost so the clearing legs of the transfer net to zero), or the
 * source cancels it (the stock goes back at the original cost and the source entry is reversed).
 */
@Service
class TransferService implements ModuleSettingsChangeGuard {

    static final String TRANSFER = "retail.transfer";
    static final String TRANSFER_VOID = "retail.transfer_void";
    static final String TRANSFER_RECEIPT = "retail.transfer_receipt";
    static final String TRANSFER_CANCEL = "retail.transfer_cancel";
    static final String PERMISSION = "retail.stock.transfer";
    static final String READ = "retail.stock.read";
    static final String PATH = "/api/v1/retail/transfers";
    static final Set<String> STATUSES = Set.of("completed", "in_transit", "received", "cancelled", "voided");

    private final TransferRepository repo;
    private final RetailCatalogue catalogue;
    private final StockLedger stock;
    private final RetailBooks books;
    private final Idempotency idempotency;
    private final RetailBranchContext branchContext;
    private final RetailCapabilities capabilities;
    private final Branches branches;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final ObjectProvider<TransferOutGuard> guards;
    private final ApplicationEventPublisher events;

    TransferService(
            TransferRepository repo,
            RetailCatalogue catalogue,
            StockLedger stock,
            RetailBooks books,
            Idempotency idempotency,
            RetailBranchContext branchContext,
            RetailCapabilities capabilities,
            Branches branches,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            ObjectProvider<TransferOutGuard> guards,
            ApplicationEventPublisher events) {
        this.events = events;
        this.repo = repo;
        this.catalogue = catalogue;
        this.stock = stock;
        this.books = books;
        this.idempotency = idempotency;
        this.branchContext = branchContext;
        this.capabilities = capabilities;
        this.branches = branches;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.guards = guards;
    }

    @Transactional
    Outcome<Transfer> create(String idempotencyKey, TransferRequest r) {
        return idempotency.once(idempotencyKey, "POST", PATH, r, Transfer.class, () -> record(r));
    }

    private Transfer record(TransferRequest r) {
        // Read under a shared lock on the settings row (issue #239): the capability cannot be switched off until this
        // transfer, in transit or not, has committed, and the switch-off check then sees it.
        boolean dispatchOnly = capabilities.enabledHeld(RetailCapabilities.TRANSFER_RECEIPT);
        // A branch that keeps no goods may still be the source, so stock stranded there can leave (ADR-041).
        UUID from = branchContext.resolve(PERMISSION, r.fromBranchId(), "from_branch_id");
        LocalDate today = clock.today(tenant.profile().timezone());
        LocalDate on = r.transferDate() == null ? today : r.transferDate();
        List<FieldProblem> problems = new ArrayList<>();
        Branches.Branch to = null;
        if (r.toBranchId().equals(from)) {
            problems.add(
                    new FieldProblem("to_branch_id", "same_branch", "Pick a different branch to move the stock to."));
        } else {
            to = branches.findActive(r.toBranchId()).orElse(null);
            if (to == null) {
                // Any open branch of the business may receive, in or out of the caller's scope (issue #257).
                problems.add(new FieldProblem(
                        "to_branch_id",
                        "unknown_branch",
                        "Choose an open branch of this business to move the stock to."));
            } else {
                branchContext.requireStocks(to.id(), "to_branch_id");
            }
        }
        if (on.isAfter(today)) {
            problems.add(new FieldProblem("transfer_date", "invalid", "A transfer cannot be dated in the future."));
        }
        // ADR-079: the balance rows of both branches are locked in branch and product order, the order the movements
        // take them, before the source's average is read, so a delivery committing meanwhile cannot leave the transfer
        // at the average it replaced, and two opposite transfers still cannot deadlock.
        List<UUID> known = r.lines().stream()
                .map(TransferLineRequest::productId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .filter(id -> catalogue.find(id).isPresent())
                .toList();
        UUID toId = to == null ? null : to.id();
        java.util.stream.Stream.of(from, toId)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .forEach(branch -> known.forEach(product -> stock.lock(branch, product)));
        Set<UUID> seen = new HashSet<>();
        List<NewLine> lines = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            TransferLineRequest l = r.lines().get(i);
            if (!seen.add(l.productId())) {
                problems.add(new FieldProblem(
                        "lines[" + i + "].product_id", "duplicate", "Put each product on one line only."));
                continue;
            }
            ProductSnapshot p = catalogue.find(l.productId()).orElse(null);
            if (p == null) {
                problems.add(new FieldProblem("lines[" + i + "].product_id", "unknown_product", "No such product."));
                continue;
            }
            if (!p.active()) {
                problems.add(
                        new FieldProblem(
                                "lines[" + i + "].product_id",
                                "product_inactive",
                                "This item is inactive, so it cannot be moved to another branch. Switch it on again on the Items screen first."));
                continue;
            }
            if (!Quantities.exact(l.qty())) {
                problems.add(new FieldProblem("lines[" + i + "].qty", "invalid", "At most three decimal places."));
                continue;
            }
            // ADR-079: stock leaves at the source's average and that cost re-averages the destination when it arrives.
            long cost = stock.averageCost(from, p.id());
            lines.add(new NewLine(UUID.randomUUID(), i + 1, p.id(), l.qty(), cost, Quantities.value(l.qty(), cost)));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        Map<UUID, java.math.BigDecimal> quantities = new java.util.LinkedHashMap<>();
        lines.forEach(l -> quantities.put(l.productId(), l.qty()));
        guards.orderedStream().forEach(g -> g.check(from, quantities));
        UUID id = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        List<Movement> movements = new ArrayList<>();
        for (NewLine l : lines) {
            movements.add(new Movement(
                    from,
                    l.productId(),
                    "transfer_out",
                    l.qty().negate(),
                    l.unitCostMinor(),
                    TRANSFER,
                    id,
                    l.id(),
                    null,
                    note));
            if (!dispatchOnly) {
                movements.add(new Movement(
                        to.id(),
                        l.productId(),
                        "transfer_in",
                        l.qty(),
                        l.unitCostMinor(),
                        TRANSFER,
                        id,
                        l.id(),
                        null,
                        note));
            }
        }
        stock.record(on, movements);
        long total = lines.stream().mapToLong(NewLine::lineCostMinor).reduce(0, Math::addExact);
        String reference = "Transfer " + id.toString().substring(0, 8);
        String fromCode = branches.findActive(from).map(Branches.Branch::code).orElse("");
        UUID outEntry = books.post(new Posting(
                        from,
                        on,
                        reference,
                        (dispatchOnly ? "Stock sent to " : "Stock moved to ") + to.code(),
                        TRANSFER,
                        id,
                        "retail.transfer_out:" + id,
                        List.of(Leg.debit("interbranch_clearing", total), Leg.credit("inventory", total))))
                .map(PostedEntry::entryId)
                .orElse(null);
        UUID inEntry = dispatchOnly
                ? null
                : books.post(new Posting(
                                to.id(),
                                on,
                                reference,
                                "Stock moved from " + fromCode,
                                TRANSFER,
                                id,
                                "retail.transfer_in:" + id,
                                List.of(Leg.debit("inventory", total), Leg.credit("interbranch_clearing", total))))
                        .map(PostedEntry::entryId)
                        .orElse(null);
        repo.insert(
                id,
                from,
                to.id(),
                on,
                note,
                tenant.profile().currency(),
                total,
                outEntry,
                inEntry,
                dispatchOnly ? "in_transit" : "completed",
                by,
                lines);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("from_branch_id", from.toString());
        after.put("to_branch_id", to.id().toString());
        after.put("transfer_date", on.toString());
        after.put("lines", lines.size());
        if (dispatchOnly) {
            after.put("status", "in_transit");
        }
        audit.record(AuditLog.Entry.created("retail.transfer.created", TRANSFER, id, from, after));
        if (dispatchOnly) {
            // The people who can receive at the destination hear on their devices (ADR-050): stock is on its way to
            // them.
            events.publishEvent(new PushNotice(
                    "retail.transfer_dispatched",
                    id.toString(),
                    new PushNotice.Holders(PERMISSION, to.id(), by),
                    Map.of(
                            "from_branch_id", from.toString(),
                            "to_branch_id", to.id().toString(),
                            "path", "/staff/retail/in-transit")));
        }
        return visible(repo.find(id, false).orElseThrow().transfer());
    }

    @Transactional(readOnly = true)
    TransferPage list(
            List<UUID> branchIds,
            UUID productId,
            String status,
            LocalDate from,
            LocalDate to,
            Integer limit,
            String cursor) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "status", "invalid", "status is completed, in_transit, received, cancelled or voided.")));
        }
        List<UUID> filter = CurrentPrincipal.require().branchFilter(READ, branchIds);
        int size = limit == null ? StockService.DEFAULT_LIMIT : Math.clamp(limit, 1, StockService.MAX_LIMIT);
        Cursor.Key before = Cursor.decodeKey(cursor).orElse(null);
        List<Transfer> rows = repo.page(
                filter,
                productId,
                status,
                from,
                to,
                before == null ? null : before.at(),
                before == null ? null : before.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<Transfer> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        items.getLast().createdAt() + "|" + items.getLast().id())
                : null;
        return new TransferPage(items.stream().map(TransferService::visible).toList(), next);
    }

    @Transactional(readOnly = true)
    Transfer get(UUID id) {
        Principal principal = CurrentPrincipal.require();
        return repo.find(id, false)
                .map(Header::transfer)
                .filter(t -> principal.may(READ, t.fromBranchId()) || principal.may(READ, t.toBranchId()))
                .map(TransferService::visible)
                .orElseThrow(ApiException::notFound);
    }

    /**
     * The destination receives a dispatched transfer (capability {@code transfer_receipt}). What
     * arrived goes into the destination as {@code transfer_in} movements at the dispatch cost and
     * one entry (debit inventory, credit clearing); what did not arrive is the shortage, posted at
     * the same source cost as stock shrinkage at the destination against clearing, so the
     * transfer's clearing legs net to zero. More than was dispatched is refused. Under the
     * transfer's row lock, so a receipt, a cancel and a void cannot interleave.
     */
    @Transactional
    Outcome<Transfer> receive(String idempotencyKey, UUID id, ReceiveRequest r) {
        return idempotency.once(idempotencyKey, "POST", PATH + "/" + id + "/receive", r, Transfer.class, () -> {
            capabilities.require(RetailCapabilities.TRANSFER_RECEIPT);
            return receiveTransfer(id, r);
        });
    }

    private Transfer receiveTransfer(UUID id, ReceiveRequest r) {
        Principal principal = CurrentPrincipal.require();
        Header h = repo.find(id, true)
                .filter(x -> principal.may(PERMISSION, x.transfer().toBranchId()))
                .orElseThrow(ApiException::notFound);
        Transfer t = h.transfer();
        requireInTransit(t, "received");
        // A closed branch still receives what is on the road to it; a branch that keeps no goods does not (ADR-041).
        branchContext.requireStocksReceiving(t.toBranchId(), "branch_id");
        List<StoredLine> lines = repo.storedLines(id);
        Map<UUID, BigDecimal> arrived = arrived(lines, r.lines());
        String note = r.note() == null || r.note().isBlank() ? null : r.note().trim();
        LocalDate today = clock.today(tenant.profile().timezone());
        List<Movement> movements = new ArrayList<>();
        long receivedValue = 0;
        int shortLines = 0;
        for (StoredLine l : lines) {
            BigDecimal got = arrived.get(l.productId());
            if (got.compareTo(l.qty()) < 0) {
                shortLines++;
            }
            receivedValue = Math.addExact(receivedValue, Quantities.value(got, l.unitCostMinor()));
            if (got.signum() > 0) {
                movements.add(new Movement(
                        t.toBranchId(),
                        l.productId(),
                        "transfer_in",
                        got,
                        l.unitCostMinor(),
                        TRANSFER_RECEIPT,
                        id,
                        l.id(),
                        null,
                        note));
            }
        }
        stock.record(today, movements);
        long total = h.transfer().costTotalMinor();
        long shortage = Math.subtractExact(total, receivedValue);
        String reference = "Receive transfer " + id.toString().substring(0, 8);
        String fromCode = branches.all().stream()
                .filter(b -> b.id().equals(t.fromBranchId()))
                .map(Branches.Branch::code)
                .findFirst()
                .orElse("");
        UUID inEntry = books.post(new Posting(
                        t.toBranchId(),
                        today,
                        reference,
                        "Stock received from " + fromCode,
                        TRANSFER,
                        id,
                        "retail.transfer_in:" + id,
                        List.of(
                                Leg.debit("inventory", receivedValue),
                                Leg.credit("interbranch_clearing", receivedValue)),
                        true))
                .map(PostedEntry::entryId)
                .orElse(null);
        UUID shortageEntry = books.post(new Posting(
                        t.toBranchId(),
                        today,
                        reference,
                        "Shortage on receipt from " + fromCode,
                        TRANSFER,
                        id,
                        "retail.transfer_shortage:" + id,
                        List.of(Leg.debit("stock_shrinkage", shortage), Leg.credit("interbranch_clearing", shortage)),
                        true))
                .map(PostedEntry::entryId)
                .orElse(null);
        for (StoredLine l : lines) {
            repo.setReceivedQty(l.id(), arrived.get(l.productId()));
        }
        repo.markReceived(id, principal.userId(), inEntry, shortageEntry);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "received");
        after.put("lines", lines.size());
        after.put("short_lines", shortLines);
        audit.record(new AuditLog.Entry(
                "retail.transfer.received", TRANSFER, id, t.toBranchId(), Map.of("status", "in_transit"), after));
        return visible(repo.find(id, false).orElseThrow().transfer());
    }

    /**
     * What arrived per product. No lines in the request means everything as dispatched; otherwise
     * every line of the transfer is named exactly once, and no quantity is above what was
     * dispatched ({@code received_exceeds_dispatched}).
     */
    private static Map<UUID, BigDecimal> arrived(List<StoredLine> lines, List<ReceiveLine> given) {
        Map<UUID, BigDecimal> out = new LinkedHashMap<>();
        if (given == null || given.isEmpty()) {
            lines.forEach(l -> out.put(l.productId(), l.qty()));
            return out;
        }
        Map<UUID, StoredLine> byProduct = new LinkedHashMap<>();
        lines.forEach(l -> byProduct.put(l.productId(), l));
        List<FieldProblem> problems = new ArrayList<>();
        for (int i = 0; i < given.size(); i++) {
            ReceiveLine g = given.get(i);
            if (!byProduct.containsKey(g.productId())) {
                problems.add(new FieldProblem(
                        "lines[" + i + "].product_id", "unknown_product", "This product is not on the transfer."));
            } else if (out.containsKey(g.productId())) {
                problems.add(new FieldProblem(
                        "lines[" + i + "].product_id", "duplicate", "Put each product on one line only."));
            } else if (!Quantities.exact(g.qty())) {
                problems.add(new FieldProblem("lines[" + i + "].qty", "invalid", "At most three decimal places."));
            } else {
                out.put(g.productId(), g.qty());
            }
        }
        for (StoredLine l : lines) {
            if (!out.containsKey(l.productId()) && problems.isEmpty()) {
                problems.add(new FieldProblem(
                        "lines", "incomplete", "Say how much of every item arrived, even if it is none."));
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        for (StoredLine l : lines) {
            if (out.get(l.productId()).compareTo(l.qty()) > 0) {
                throw ApiException.rule(
                        "received_exceeds_dispatched",
                        "More arrived than was dispatched for one or more items. Record only what was sent.");
            }
        }
        return out;
    }

    /**
     * The source takes a dispatched transfer back before it is received: every line returns to the
     * source at the original cost (a {@code transfer_in} that reverses the dispatch movement) and
     * the source entry is reversed. The destination never booked anything, so nothing changes there.
     */
    @Transactional
    Outcome<Transfer> cancel(String idempotencyKey, UUID id, CancelRequest r) {
        return idempotency.once(idempotencyKey, "POST", PATH + "/" + id + "/cancel", r, Transfer.class, () -> {
            capabilities.require(RetailCapabilities.TRANSFER_RECEIPT);
            return cancelTransfer(id, r);
        });
    }

    private Transfer cancelTransfer(UUID id, CancelRequest r) {
        Principal principal = CurrentPrincipal.require();
        Header h = repo.find(id, true)
                .filter(x -> principal.may(PERMISSION, x.transfer().fromBranchId()))
                .orElseThrow(ApiException::notFound);
        Transfer t = h.transfer();
        requireInTransit(t, "cancelled");
        String reason = r.reason().trim();
        LocalDate today = clock.today(tenant.profile().timezone());
        List<Recorded> sent = stock.bySource(TRANSFER, id);
        stock.record(
                today,
                sent.stream()
                        .map(m -> new Movement(
                                m.branchId(),
                                m.productId(),
                                "transfer_in",
                                m.qty().negate(),
                                m.unitCostMinor(),
                                TRANSFER_CANCEL,
                                id,
                                m.sourceLineId(),
                                m.id(),
                                reason))
                        .toList());
        if (h.outEntryId() != null) {
            books.reverse(
                    h.outEntryId(),
                    today,
                    "Cancel transfer " + id.toString().substring(0, 8),
                    "retail.transfer_out_cancel:" + id);
        }
        repo.markCancelled(id, principal.userId(), reason);
        audit.record(new AuditLog.Entry(
                "retail.transfer.cancelled",
                TRANSFER,
                id,
                t.fromBranchId(),
                Map.of("status", "in_transit"),
                Map.of("status", "cancelled", "reason", reason)));
        return visible(repo.find(id, false).orElseThrow().transfer());
    }

    private static void requireInTransit(Transfer t, String verb) {
        if (!t.status().equals("in_transit")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "transfer_not_in_transit",
                    "Transfer not in transit",
                    "Only a transfer that is on its way can be " + verb + "; this one is " + t.status() + ".");
        }
    }

    /**
     * A void undoes a one-step or a received transfer: it moves every line back from the destination
     * to the source at the cost the transfer carried (what arrived goes back, and the dispatched
     * quantity returns to the source, so a recorded shortage is undone with it), and reverses every
     * journal entry the transfer made, under the transfer's row lock and the balance locks. It is
     * refused while the destination no longer holds what the transfer brought there (sold, used or
     * moved on since), because the stock is no longer there to send back. A transfer still on its
     * way is cancelled instead; a cancelled or voided one cannot be voided.
     */
    @Transactional
    Transfer voidTransfer(UUID id, VoidRequest r) {
        Principal principal = CurrentPrincipal.require();
        Header h = repo.find(id, true)
                .filter(x -> principal.may(PERMISSION, x.transfer().fromBranchId()))
                .orElseThrow(ApiException::notFound);
        Transfer t = h.transfer();
        if (t.status().equals("voided")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "transfer_voided",
                    "Transfer voided",
                    "This transfer has been voided already.");
        }
        if (t.status().equals("cancelled")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "transfer_cancelled",
                    "Transfer cancelled",
                    "This transfer was cancelled while it was on its way, so there is nothing to void.");
        }
        if (t.status().equals("in_transit")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "transfer_in_transit",
                    "Transfer in transit",
                    "This transfer is still on its way. Cancel it, or wait for it to be received.");
        }
        List<Recorded> moved = new ArrayList<>(stock.bySource(TRANSFER, id));
        moved.addAll(stock.bySource(TRANSFER_RECEIPT, id));
        moved.sort(Comparator.comparing(Recorded::branchId).thenComparing(Recorded::productId));
        Map<UUID, String> codes = new LinkedHashMap<>();
        t.lines().forEach(l -> codes.put(l.productId(), l.code()));
        List<String> gone = new ArrayList<>();
        for (Recorded m : moved) {
            BigDecimal balance = stock.lock(m.branchId(), m.productId());
            if (m.kind().equals("transfer_in") && balance.compareTo(m.qty()) < 0) {
                gone.add(codes.get(m.productId()));
            }
        }
        if (!gone.isEmpty()) {
            String toCode = branches.all().stream()
                    .filter(b -> b.id().equals(t.toBranchId()))
                    .map(Branches.Branch::code)
                    .findFirst()
                    .orElse("The destination branch");
            throw ApiException.rule(
                    "transfer_stock_moved",
                    toCode + " no longer holds all of " + String.join(", ", gone)
                            + " that this transfer brought, so it cannot be voided."
                            + " Move the stock back with a new transfer instead.");
        }
        String reason = r.reason().trim();
        LocalDate today = clock.today(tenant.profile().timezone());
        stock.record(
                today,
                moved.stream()
                        .map(m -> new Movement(
                                m.branchId(),
                                m.productId(),
                                m.kind().equals("transfer_out") ? "transfer_in" : "transfer_out",
                                m.qty().negate(),
                                m.unitCostMinor(),
                                TRANSFER_VOID,
                                id,
                                m.sourceLineId(),
                                m.id(),
                                reason))
                        .toList());
        String reference = "Void transfer " + id.toString().substring(0, 8);
        if (h.outEntryId() != null) {
            books.reverse(h.outEntryId(), today, reference, "retail.transfer_out_void:" + id);
        }
        if (h.inEntryId() != null) {
            books.reverse(h.inEntryId(), today, reference, "retail.transfer_in_void:" + id);
        }
        if (h.shortageEntryId() != null) {
            books.reverse(h.shortageEntryId(), today, reference, "retail.transfer_shortage_void:" + id);
        }
        repo.markVoided(id, principal.userId(), reason);
        audit.record(new AuditLog.Entry(
                "retail.transfer.voided",
                TRANSFER,
                id,
                t.fromBranchId(),
                Map.of("status", t.status()),
                Map.of("status", "voided", "reason", reason)));
        return visible(repo.find(id, false).orElseThrow().transfer());
    }

    /**
     * Refuses switching the {@code transfer_receipt} capability off, by the flag or by a kind of
     * business without it, while any transfer is on its way, so no dispatched stock is left with a
     * destination that can no longer receive it. Runs inside the capability change, under the
     * settings row lock that {@link #record} holds shared, so a dispatch either committed before
     * this reads the transfers or starts after the change and sees the capability off (issue #239).
     */
    @Override
    public void check(String moduleKey, Map<String, Object> before, Map<String, Object> after) {
        boolean off = RetailCapabilities.MODULE.equals(moduleKey)
                && Boolean.TRUE.equals(before.get(RetailCapabilities.TRANSFER_RECEIPT))
                && !Boolean.TRUE.equals(after.get(RetailCapabilities.TRANSFER_RECEIPT));
        if (off && repo.anyInTransit()) {
            throw ApiException.rule(
                    "transfers_in_transit",
                    "Some transfers are still on their way. Receive or cancel them before switching this off.");
        }
    }

    /**
     * The transfer's cost is the same amount in both branches' inventory entries, so {@code
     * retail.profit.read} in either branch shows it (ADR-017; #78).
     */
    static Transfer visible(Transfer t) {
        if (StockService.mayReadCost(t.fromBranchId()) || StockService.mayReadCost(t.toBranchId())) {
            return t;
        }
        return new Transfer(
                t.id(),
                t.fromBranchId(),
                t.toBranchId(),
                t.transferDate(),
                t.note(),
                t.status(),
                t.currency(),
                null,
                t.lines().stream()
                        .map(l -> new TransferLine(
                                l.lineNo(),
                                l.productId(),
                                l.code(),
                                l.description(),
                                l.qty(),
                                null,
                                null,
                                l.receivedQty()))
                        .toList(),
                t.createdAt(),
                t.createdBy(),
                t.voidedAt(),
                t.voidedBy(),
                t.voidReason(),
                t.receivedAt(),
                t.receivedBy(),
                null);
    }
}
