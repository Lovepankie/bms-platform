package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.ProductSnapshot;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailBooks;
import com.rincoltech.bms.retail.stock.RetailBooks.Leg;
import com.rincoltech.bms.retail.stock.RetailBooks.Posting;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import com.rincoltech.bms.retail.stock.StockLedger;
import com.rincoltech.bms.retail.stock.StockLedger.Movement;
import com.rincoltech.bms.retail.stock.StockLedger.Recorded;
import com.rincoltech.bms.retail.stock.internal.TransferApi.Transfer;
import com.rincoltech.bms.retail.stock.internal.TransferApi.TransferLine;
import com.rincoltech.bms.retail.stock.internal.TransferApi.TransferLineRequest;
import com.rincoltech.bms.retail.stock.internal.TransferApi.TransferPage;
import com.rincoltech.bms.retail.stock.internal.TransferApi.TransferRequest;
import com.rincoltech.bms.retail.stock.internal.TransferApi.VoidRequest;
import com.rincoltech.bms.retail.stock.internal.TransferRepository.Header;
import com.rincoltech.bms.retail.stock.internal.TransferRepository.NewLine;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock transfers between branches (FR-RET-16, ADR-020 amendment of 2026-10-06). One transaction
 * moves every line out of the source and into the destination at the source's current cost, so
 * nothing is gained or lost: a {@code transfer_out} and a {@code transfer_in} movement per line
 * sharing the transfer's id, and one journal entry per branch through inter-branch clearing
 * (ADR-004). The caller acts on the source branch; the destination is any active branch of the
 * tenant. Overselling at the source is refused like a sale, under the balance locks, which are
 * taken in branch and product order so opposite transfers cannot deadlock.
 */
@Service
class TransferService {

    static final String TRANSFER = "retail.transfer";
    static final String TRANSFER_VOID = "retail.transfer_void";
    static final String PERMISSION = "retail.stock.transfer";
    static final String READ = "retail.stock.read";
    static final String PATH = "/api/v1/retail/transfers";

    private final TransferRepository repo;
    private final RetailCatalogue catalogue;
    private final StockLedger stock;
    private final RetailBooks books;
    private final Idempotency idempotency;
    private final RetailBranchContext branchContext;
    private final Branches branches;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    TransferService(
            TransferRepository repo,
            RetailCatalogue catalogue,
            StockLedger stock,
            RetailBooks books,
            Idempotency idempotency,
            RetailBranchContext branchContext,
            Branches branches,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit) {
        this.repo = repo;
        this.catalogue = catalogue;
        this.stock = stock;
        this.books = books;
        this.idempotency = idempotency;
        this.branchContext = branchContext;
        this.branches = branches;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
    }

    @Transactional
    Outcome<Transfer> create(String idempotencyKey, TransferRequest r) {
        return idempotency.once(idempotencyKey, "POST", PATH, r, Transfer.class, () -> record(r));
    }

    private Transfer record(TransferRequest r) {
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
                problems.add(new FieldProblem("to_branch_id", "unknown_branch", "No such active branch."));
            }
        }
        if (on.isAfter(today)) {
            problems.add(new FieldProblem("transfer_date", "invalid", "A transfer cannot be dated in the future."));
        }
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
            if (!Quantities.exact(l.qty())) {
                problems.add(new FieldProblem("lines[" + i + "].qty", "invalid", "At most three decimal places."));
                continue;
            }
            lines.add(new NewLine(
                    UUID.randomUUID(),
                    i + 1,
                    p.id(),
                    l.qty(),
                    p.costMinor(),
                    Quantities.value(l.qty(), p.costMinor())));
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
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
        stock.record(on, movements);
        long total = lines.stream().mapToLong(NewLine::lineCostMinor).reduce(0, Math::addExact);
        String reference = "Transfer " + id.toString().substring(0, 8);
        String fromCode = branches.findActive(from).map(Branches.Branch::code).orElse("");
        UUID outEntry = books.post(new Posting(
                        from,
                        on,
                        reference,
                        "Stock moved to " + to.code(),
                        TRANSFER,
                        id,
                        "retail.transfer_out:" + id,
                        List.of(Leg.debit("interbranch_clearing", total), Leg.credit("inventory", total))))
                .map(PostedEntry::entryId)
                .orElse(null);
        UUID inEntry = books.post(new Posting(
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
        repo.insert(id, from, to.id(), on, note, tenant.profile().currency(), total, outEntry, inEntry, by, lines);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("from_branch_id", from.toString());
        after.put("to_branch_id", to.id().toString());
        after.put("transfer_date", on.toString());
        after.put("lines", lines.size());
        audit.record(AuditLog.Entry.created("retail.transfer.created", TRANSFER, id, from, after));
        return visible(repo.find(id, false).orElseThrow().transfer());
    }

    @Transactional(readOnly = true)
    TransferPage list(
            List<UUID> branchIds, UUID productId, LocalDate from, LocalDate to, Integer limit, String cursor) {
        List<UUID> filter = CurrentPrincipal.require().branchFilter(READ, branchIds);
        int size = limit == null ? StockService.DEFAULT_LIMIT : Math.clamp(limit, 1, StockService.MAX_LIMIT);
        Cursor.Key before = Cursor.decodeKey(cursor).orElse(null);
        List<Transfer> rows = repo.page(
                filter,
                productId,
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
     * A void moves every line back from the destination to the source at the cost the transfer
     * carried, and reverses both journal entries, under the transfer's row lock and the balance
     * locks. It is refused while the destination no longer holds what the transfer brought there
     * (sold, used or moved on since), because the stock is no longer there to send back.
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
        List<Recorded> moved = new ArrayList<>(stock.bySource(TRANSFER, id));
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
        repo.markVoided(id, principal.userId(), reason);
        audit.record(new AuditLog.Entry(
                "retail.transfer.voided",
                TRANSFER,
                id,
                t.fromBranchId(),
                Map.of("status", "completed"),
                Map.of("status", "voided", "reason", reason)));
        return visible(repo.find(id, false).orElseThrow().transfer());
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
                                l.lineNo(), l.productId(), l.code(), l.description(), l.qty(), null, null))
                        .toList(),
                t.createdAt(),
                t.createdBy(),
                t.voidedAt(),
                t.voidedBy(),
                t.voidReason());
    }
}
