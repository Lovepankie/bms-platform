package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.ledger.LedgerPosting.PostedEntry;
import com.rincoltech.bms.core.tenancy.Branches.Branch;
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
import com.rincoltech.bms.retail.stock.internal.StockApi.AllBranchesPage;
import com.rincoltech.bms.retail.stock.internal.StockApi.AllBranchesRow;
import com.rincoltech.bms.retail.stock.internal.StockApi.BranchBalance;
import com.rincoltech.bms.retail.stock.internal.StockApi.MovementPage;
import com.rincoltech.bms.retail.stock.internal.StockApi.MovementRow;
import com.rincoltech.bms.retail.stock.internal.StockApi.StockBranch;
import com.rincoltech.bms.retail.stock.internal.StockApi.StockPage;
import com.rincoltech.bms.retail.stock.internal.StockApi.StockRow;
import com.rincoltech.bms.retail.stock.internal.StockApi.Stocktake;
import com.rincoltech.bms.retail.stock.internal.StockApi.StocktakeLine;
import com.rincoltech.bms.retail.stock.internal.StockApi.StocktakeLineRequest;
import com.rincoltech.bms.retail.stock.internal.StockApi.StocktakeRequest;
import com.rincoltech.bms.retail.stock.internal.StockRepository.ProductTotal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock views and stock-takes (FR-RET-03, FR-RET-08, FR-RET-11, FR-RET-14). Balances are
 * branch-owned, so every read and write applies the route permission's branch scope (ADR-017).
 */
@Service
class StockService {

    static final String STOCKTAKE = "retail.stocktake";
    static final String PROFIT_READ = "retail.profit.read";
    /**
     * A product at or below this quantity is low stock (#145). One constant for every tenant until a
     * retail settings group exists; the settings catalogue holds only core and lending keys today.
     */
    static final long LOW_STOCK_MILLI = 5_000;

    static final String LEVEL_OUT = "out";
    static final String LEVEL_LOW = "low";
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private final StockRepository repo;
    private final StockLedger ledger;
    private final RetailCatalogue catalogue;
    private final RetailBooks books;
    private final RetailBranchContext branches;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;

    StockService(
            StockRepository repo,
            StockLedger ledger,
            RetailCatalogue catalogue,
            RetailBooks books,
            RetailBranchContext branches,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit) {
        this.repo = repo;
        this.ledger = ledger;
        this.catalogue = catalogue;
        this.books = books;
        this.branches = branches;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
    }

    /**
     * FR-RET-03, #144: every product with its balance in each branch the caller may read, a total and
     * the negative flag per branch. Cost shows only when the caller holds {@code retail.profit.read} in
     * every branch of the page, since one cost cannot be shown for some branches and hidden for others.
     */
    @Transactional(readOnly = true)
    AllBranchesPage allBranches(
            String query, UUID categoryId, boolean negativeOnly, String level, Integer limit, String cursor) {
        Long atMost = atMostOf(level);
        Principal principal = CurrentPrincipal.require();
        List<Branch> visible = branches.visible("retail.stock.read");
        List<UUID> ids = visible.stream().map(Branch::id).toList();
        List<StockBranch> columns = visible.stream()
                .map(b -> new StockBranch(b.id(), b.code(), b.name(), b.headOffice()))
                .toList();
        if (ids.isEmpty()) {
            return new AllBranchesPage(columns, List.of(), Quantities.format(lowStock()), null);
        }
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        List<ProductTotal> rows = repo.allBranches(
                ids,
                blankToNull(query),
                categoryId,
                negativeOnly,
                atMost,
                after == null ? null : after.sortKey(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<ProductTotal> page = more ? rows.subList(0, size) : rows;
        Map<UUID, Map<UUID, BigDecimal>> held =
                repo.balances(page.stream().map(ProductTotal::id).toList(), ids);
        boolean cost = ids.stream().allMatch(b -> principal.may(PROFIT_READ, b));
        List<AllBranchesRow> items = page.stream()
                .map(p -> new AllBranchesRow(
                        p.id(),
                        p.code(),
                        p.description(),
                        p.categoryId(),
                        p.category(),
                        p.unit(),
                        Quantities.format(p.total()),
                        p.negative(),
                        p.sellMinor(),
                        cost ? p.costMinor() : null,
                        ids.stream()
                                .map(b -> {
                                    BigDecimal q =
                                            held.getOrDefault(p.id(), Map.of()).getOrDefault(b, BigDecimal.ZERO);
                                    return new BranchBalance(b, Quantities.format(q), q.signum() < 0);
                                })
                                .toList()))
                .toList();
        String next = more
                ? Cursor.encode(page.getLast().code() + "|" + page.getLast().id())
                : null;
        return new AllBranchesPage(columns, items, Quantities.format(lowStock()), next);
    }

    /** FR-RET-03: a branch's balances, negatives flagged. */
    @Transactional(readOnly = true)
    StockPage stock(
            UUID branchId,
            String query,
            UUID categoryId,
            boolean negativeOnly,
            String level,
            Integer limit,
            String cursor) {
        Long atMost = atMostOf(level);
        UUID branch = branches.resolve("retail.stock.read", branchId);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        List<StockRow> rows = repo.stock(
                branch,
                query == null || query.isBlank() ? null : query.trim(),
                categoryId,
                negativeOnly,
                atMost,
                after == null ? null : after.sortKey(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<StockRow> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(items.getLast().code() + "|" + items.getLast().productId())
                : null;
        boolean cost = mayReadCost(branch);
        return new StockPage(
                branch,
                items.stream()
                        .map(r -> cost
                                ? r
                                : new StockRow(
                                        r.productId(),
                                        r.code(),
                                        r.description(),
                                        r.categoryId(),
                                        r.category(),
                                        r.unit(),
                                        r.qty(),
                                        r.negative(),
                                        r.sellMinor(),
                                        null))
                        .toList(),
                Quantities.format(lowStock()),
                next);
    }

    @Transactional(readOnly = true)
    MovementPage movements(
            List<UUID> branchIds, UUID productId, LocalDate from, LocalDate to, Integer limit, String cursor) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter("retail.stock.read", branchIds);
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        Cursor.Key after = Cursor.decodeKey(cursor).orElse(null);
        List<MovementRow> rows = repo.movements(
                filter,
                productId,
                from,
                to,
                tenant.profile().timezone().getId(),
                after == null ? null : after.at(),
                after == null ? null : after.id(),
                size + 1);
        boolean more = rows.size() > size;
        List<MovementRow> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(items.getLast().at() + "|" + items.getLast().id())
                : null;
        return new MovementPage(
                items.stream()
                        .map(m -> mayReadCost(m.branchId())
                                ? m
                                : new MovementRow(
                                        m.id(),
                                        m.at(),
                                        m.branchId(),
                                        m.productId(),
                                        m.kind(),
                                        m.qty(),
                                        null,
                                        m.sourceType(),
                                        m.sourceId(),
                                        m.reversesMovementId(),
                                        m.historical(),
                                        m.note(),
                                        m.by()))
                        .toList(),
                next);
    }

    /** FR-RET-08: records the counts and the balance each is compared with; nothing moves yet. */
    @Transactional
    Stocktake createStocktake(StocktakeRequest r) {
        UUID branch = branches.resolve("retail.stocktake.commit", r.branchId());
        Set<UUID> seen = new HashSet<>();
        List<FieldProblem> problems = new ArrayList<>();
        for (int i = 0; i < r.lines().size(); i++) {
            StocktakeLineRequest line = r.lines().get(i);
            if (!seen.add(line.productId())) {
                problems.add(new FieldProblem("lines[" + i + "].product_id", "duplicate", "Count each product once."));
            } else if (!repo.productExists(line.productId())) {
                problems.add(new FieldProblem("lines[" + i + "].product_id", "unknown_product", "No such product."));
            }
            if (!Quantities.exact(line.countedQty())) {
                problems.add(
                        new FieldProblem("lines[" + i + "].counted_qty", "invalid", "At most three decimal places."));
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        UUID id = UUID.randomUUID();
        UUID by = CurrentPrincipal.require().userId();
        repo.insertStocktake(id, branch, blankToNull(r.note()), by);
        for (StocktakeLineRequest line : r.lines()) {
            repo.insertStocktakeLine(id, line.productId(), line.countedQty(), ledger.balance(branch, line.productId()));
        }
        audit.record(AuditLog.Entry.created(
                "retail.stocktake.created",
                STOCKTAKE,
                id,
                branch,
                Map.of("lines", r.lines().size())));
        return visible(repo.stocktake(id, false).orElseThrow());
    }

    @Transactional(readOnly = true)
    Stocktake stocktake(UUID id) {
        Principal principal = CurrentPrincipal.require();
        return repo.stocktake(id, false)
                .filter(s -> principal.may("retail.stock.read", s.branchId()))
                .map(this::visible)
                .orElseThrow(ApiException::notFound);
    }

    /**
     * FR-RET-08, FR-RET-11: under the stock-take's lock and each balance's lock, the variance measured
     * when the count was taken (counted less {@code expected_qty}) becomes an adjustment movement at
     * the product's cost, so a sale, restock, usage or void recorded between count and commit stays
     * in the balance. The net value posts to stock shrinkage against inventory in one entry for the
     * branch. When later movements mean the adjustment would take a balance below zero, the count no
     * longer describes the shelf: the commit is refused with 409 {@code stock_moved_since_count}.
     */
    @Transactional
    Stocktake commit(UUID id) {
        Principal principal = CurrentPrincipal.require();
        Stocktake s = repo.stocktake(id, true)
                .filter(x -> principal.may("retail.stocktake.commit", x.branchId()))
                .orElseThrow(ApiException::notFound);
        if (!s.status().equals("draft")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "stocktake_committed",
                    "Stock-take committed",
                    "This stock-take has been committed already.");
        }
        List<StocktakeLine> lines = new ArrayList<>(s.lines());
        lines.sort(Comparator.comparing(StocktakeLine::productId));
        List<Movement> movements = new ArrayList<>();
        long losses = 0;
        long gains = 0;
        List<String> recount = new ArrayList<>();
        for (StocktakeLine line : lines) {
            BigDecimal balance = ledger.lock(s.branchId(), line.productId());
            BigDecimal variance = new BigDecimal(line.countedQty()).subtract(new BigDecimal(line.expectedQty()));
            if (balance.add(variance).signum() < 0) {
                recount.add(line.code());
                continue;
            }
            long cost = catalogue
                    .find(line.productId())
                    .map(ProductSnapshot::costMinor)
                    .orElseThrow();
            repo.commitLine(id, line.productId(), variance, cost);
            if (variance.signum() == 0) {
                continue;
            }
            movements.add(new Movement(
                    s.branchId(), line.productId(), "adjustment", variance, cost, STOCKTAKE, id, null, null, null));
            long value = Quantities.value(variance.abs(), cost);
            if (variance.signum() < 0) {
                losses += value;
            } else {
                gains += value;
            }
        }
        if (!recount.isEmpty()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "stock_moved_since_count",
                    "Stock moved since the count",
                    "Stock of " + String.join(", ", recount)
                            + " has moved since it was counted, so the count no longer matches the shelf."
                            + " Please recount these lines in a new stock-take.");
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        ledger.record(today, movements);
        Optional<PostedEntry> entry = books.post(new Posting(
                s.branchId(),
                today,
                "Stock-take " + id.toString().substring(0, 8),
                "Stock-take variance at cost",
                STOCKTAKE,
                id,
                "retail.stocktake:" + id,
                List.of(
                        Leg.debit("stock_shrinkage", losses),
                        Leg.credit("inventory", losses),
                        Leg.debit("inventory", gains),
                        Leg.credit("stock_shrinkage", gains))));
        repo.markCommitted(
                id, principal.userId(), entry.map(PostedEntry::entryId).orElse(null));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("adjusted_lines", movements.size());
        audit.record(new AuditLog.Entry(
                "retail.stocktake.committed", STOCKTAKE, id, s.branchId(), Map.of("status", "draft"), after));
        return visible(repo.stocktake(id, false).orElseThrow());
    }

    private Stocktake visible(Stocktake s) {
        if (mayReadCost(s.branchId())) {
            return s;
        }
        return new Stocktake(
                s.id(),
                s.branchId(),
                s.status(),
                s.note(),
                s.lines().stream()
                        .map(l -> new StocktakeLine(
                                l.productId(),
                                l.code(),
                                l.description(),
                                l.countedQty(),
                                l.expectedQty(),
                                l.varianceQty(),
                                l.committedVarianceQty(),
                                null))
                        .toList(),
                s.createdAt(),
                s.createdBy(),
                s.committedAt(),
                s.committedBy(),
                s.adjustmentEntryId(),
                s.version());
    }

    /**
     * Cost on a branch-bound row needs {@code retail.profit.read} in that row's branch, not in any
     * branch (ADR-017; review F5).
     */
    private static BigDecimal lowStock() {
        return BigDecimal.valueOf(LOW_STOCK_MILLI, 3);
    }

    /** The quantity a level filter keeps at or below, in thousandths; null for no level (#145). */
    private static Long atMostOf(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        return switch (level) {
            case LEVEL_OUT -> 0L;
            case LEVEL_LOW -> LOW_STOCK_MILLI;
            default ->
                throw ApiException.validation(
                        List.of(new FieldProblem("stock_level", "invalid", "stock_level is out or low.")));
        };
    }

    static boolean mayReadCost(UUID branchId) {
        return CurrentPrincipal.require().may(PROFIT_READ, branchId);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
