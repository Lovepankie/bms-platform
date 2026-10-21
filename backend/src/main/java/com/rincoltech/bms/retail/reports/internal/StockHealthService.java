package com.rincoltech.bms.retail.reports.internal;

import static com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.PROFIT_READ;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.Window;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.CoverList;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.CoverRow;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.DeadBranch;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.DeadItem;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.DeadStock;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.Report;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.SellsBetterAt;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.Shrinkage;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.SlowItem;
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.SlowMovers;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.BranchPace;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.Count;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.DeadRow;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.DeadTotal;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.Pace;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.SlowRow;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.Usage;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stock health report of issue #149: days of cover, reorder suggestions, dead stock and shrinkage; and the
 * slow movers of issue #414, with the branch where each sells better.
 */
@Service
class StockHealthService {

    static final int VELOCITY_DAYS = 30;
    static final int DEAD_DAYS = 90;
    static final int DEFAULT_LEAD_DAYS = 14;
    static final int DEFAULT_COVER_DAYS = 30;
    static final int MAX_DAYS = 365;
    static final int SLOW_DAYS = 90;
    static final int MAX_MONTHS = 120;
    private static final BigDecimal CAP = BigDecimal.valueOf(MAX_DAYS);
    private static final BigDecimal MONTHS_CAP = BigDecimal.valueOf(MAX_MONTHS);

    private final StockHealthRepository repo;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final RetailCapabilities settings;

    StockHealthService(
            StockHealthRepository repo, CurrentTenant tenant, BusinessClock clock, RetailCapabilities settings) {
        this.repo = repo;
        this.tenant = tenant;
        this.clock = clock;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    Report report(
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            Integer topParam,
            Integer leadParam,
            Integer coverParam) {
        int lead = leadParam == null ? DEFAULT_LEAD_DAYS : leadParam;
        int cover = coverParam == null ? DEFAULT_COVER_DAYS : coverParam;
        if (lead < 1 || lead > MAX_DAYS) {
            throw ApiException.validation(
                    List.of(new FieldProblem("lead_days", "invalid", "lead_days is from 1 to 365.")));
        }
        if (cover < lead || cover > MAX_DAYS) {
            throw ApiException.validation(
                    List.of(new FieldProblem("cover_days", "invalid", "cover_days is from lead_days to 365.")));
        }
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter("retail.stock.read", branchIds);
        LocalDate today = clock.today(tenant.profile().timezone());
        Window w = AnalyticsSupport.window(from, to, today);
        int top = AnalyticsSupport.top(topParam);
        boolean costEverywhere = AnalyticsSupport.profitVisible(principal, filter);

        LocalDate since = today.minusDays(VELOCITY_DAYS - 1L);
        List<Pace> lowest = repo.pace(filter, since, today, VELOCITY_DAYS, null, top);
        List<Pace> reorder = repo.pace(filter, since, today, VELOCITY_DAYS, lead, top);
        CoverList coverList = new CoverList(
                lowest.stream().map(p -> cover(p, null)).toList(),
                lowest.isEmpty() ? 0 : lowest.getFirst().total());
        CoverList reorderList = new CoverList(
                reorder.stream().map(p -> cover(p, cover)).toList(),
                reorder.isEmpty() ? 0 : reorder.getFirst().total());

        LocalDate deadSince = today.minusDays(DEAD_DAYS - 1L);
        List<DeadTotal> totals = repo.deadTotals(filter, deadSince, today);
        long atPrice = 0;
        long atCost = 0;
        List<DeadBranch> deadBranches = new ArrayList<>();
        for (DeadTotal d : totals) {
            boolean cost = principal.may(PROFIT_READ, d.branchId());
            atPrice = Math.addExact(atPrice, d.atPrice());
            atCost = Math.addExact(atCost, d.atCost());
            // ADR-055: the value of the stock, even at the selling price, goes with the cost permission.
            deadBranches.add(
                    new DeadBranch(d.branchId(), d.items(), cost ? d.atPrice() : null, cost ? d.atCost() : null));
        }
        List<DeadRow> deadRows = repo.deadItems(filter, deadSince, today, top);
        List<DeadItem> deadItems = deadRows.stream()
                .map(r -> new DeadItem(
                        r.branchId(),
                        r.productId(),
                        r.code(),
                        r.description(),
                        r.unit(),
                        Quantities.format(r.qty()),
                        principal.may(PROFIT_READ, r.branchId()) ? r.atPrice() : null,
                        principal.may(PROFIT_READ, r.branchId()) ? r.atCost() : null))
                .toList();
        DeadStock dead = new DeadStock(
                DEAD_DAYS,
                deadBranches,
                deadItems,
                totals.stream().mapToInt(DeadTotal::items).sum(),
                costEverywhere ? atCost : null,
                costEverywhere ? atPrice : null);

        return new Report(
                w.from(),
                w.to(),
                tenant.profile().currency(),
                top,
                VELOCITY_DAYS,
                lead,
                cover,
                costEverywhere,
                coverList,
                reorderList,
                dead,
                slowMovers(principal, filter, today, top, cover),
                shrinkage(principal, filter, w));
    }

    /**
     * Slow movers (issue #414). Money tied up shows per row only with retail.profit.read in the row's branch, and
     * the total only for a caller who holds it in every branch. "Sells better at" looks across every branch the
     * caller may read stock in, not only the ones asked for, and never beyond.
     */
    private SlowMovers slowMovers(Principal principal, List<UUID> filter, LocalDate today, int top, int coverDays) {
        int months = settings.slowMoverMonths();
        int minSold = settings.slowMoverMinSold();
        LocalDate since = today.minusDays(SLOW_DAYS - 1L);
        List<SlowRow> rows = repo.slow(filter, since, today, SLOW_DAYS, months, minSold, top);
        List<UUID> products = rows.stream().map(SlowRow::productId).distinct().toList();
        List<UUID> scope = principal.branchFilter("retail.stock.read", null);
        Map<UUID, List<BranchPace>> elsewhere = new LinkedHashMap<>();
        for (BranchPace p : repo.paceOf(scope, products, since, today, SLOW_DAYS)) {
            elsewhere.computeIfAbsent(p.productId(), k -> new ArrayList<>()).add(p);
        }
        List<SlowItem> items = rows.stream()
                .map(r -> {
                    BigDecimal monthsOfStock = r.qty()
                            .multiply(BigDecimal.valueOf(r.days()))
                            .divide(r.sold().multiply(BigDecimal.valueOf(30)), 1, RoundingMode.HALF_UP);
                    boolean capped = monthsOfStock.compareTo(MONTHS_CAP) > 0;
                    return new SlowItem(
                            r.branchId(),
                            r.productId(),
                            r.code(),
                            r.description(),
                            r.unit(),
                            Quantities.format(r.qty()),
                            Quantities.format(r.sold()),
                            r.lastSold(),
                            r.days(),
                            AnalyticsSupport.oneDecimal(capped ? MONTHS_CAP : monthsOfStock),
                            capped,
                            principal.may(PROFIT_READ, r.branchId()) ? r.atCost() : null,
                            sellsBetterAt(r, elsewhere.getOrDefault(r.productId(), List.of()), months, coverDays));
                })
                .toList();
        boolean admin = principal.scopeOf(PROFIT_READ).map(s -> s.all()).orElse(false);
        return new SlowMovers(
                SLOW_DAYS,
                months,
                minSold,
                items,
                rows.isEmpty() ? 0 : rows.getFirst().total(),
                admin ? (rows.isEmpty() ? 0L : rows.getFirst().totalAtCost()) : null);
    }

    /**
     * The branch that sells the item fastest of those that sell it faster than {@code r}'s branch and can take some,
     * the pace being units sold over each one's own days ({@link SlowRow#days}, {@link BranchPace#days}): it takes
     * what it sells in {@code coverDays} less what it holds, and this branch gives at most what it holds above
     * {@code months} months of its own sales. Whole units only; null when no branch can take one.
     */
    static SellsBetterAt sellsBetterAt(SlowRow r, List<BranchPace> candidates, int months, int coverDays) {
        BigDecimal ownDays = BigDecimal.valueOf(r.days());
        BigDecimal keep = r.sold().multiply(BigDecimal.valueOf(months * 30L)).divide(ownDays, 3, RoundingMode.CEILING);
        BigDecimal spare = r.qty().subtract(keep);
        if (spare.compareTo(BigDecimal.ONE) < 0) {
            return null;
        }
        // Faster when its units over its days beat ours: c.sold * r.days > r.sold * c.days, compared exactly.
        Comparator<BranchPace> fastestFirst = (a, b) -> b.sold()
                .multiply(BigDecimal.valueOf(a.days()))
                .compareTo(a.sold().multiply(BigDecimal.valueOf(b.days())));
        return candidates.stream()
                .filter(c -> !c.branchId().equals(r.branchId())
                        && c.sold().multiply(ownDays).compareTo(r.sold().multiply(BigDecimal.valueOf(c.days()))) > 0)
                .sorted(fastestFirst.thenComparing(BranchPace::branchId))
                .map(c -> {
                    BigDecimal need = c.sold()
                            .multiply(BigDecimal.valueOf(coverDays))
                            .divide(BigDecimal.valueOf(c.days()), 3, RoundingMode.CEILING)
                            .subtract(c.qty().max(BigDecimal.ZERO));
                    BigDecimal move = need.min(spare).setScale(0, RoundingMode.FLOOR);
                    return move.signum() > 0
                            ? new SellsBetterAt(c.branchId(), Quantities.format(c.sold()), Quantities.format(move))
                            : null;
                })
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private List<Shrinkage> shrinkage(Principal principal, List<UUID> filter, Window w) {
        Map<UUID, long[]> usage = new LinkedHashMap<>();
        for (Usage u : repo.usage(filter, w.from(), w.to())) {
            long[] f = usage.computeIfAbsent(u.branchId(), b -> new long[4]);
            int at = u.kind().equals("used") ? 0 : 2;
            f[at] = u.reports();
            f[at + 1] = u.cost();
        }
        Map<UUID, Count> counts = new LinkedHashMap<>();
        repo.stocktakes(filter, w.from(), w.to()).forEach(c -> counts.put(c.branchId(), c));
        List<UUID> branches = new ArrayList<>(usage.keySet());
        counts.keySet().stream().filter(b -> !usage.containsKey(b)).forEach(branches::add);
        branches.sort(null);
        List<Shrinkage> rows = new ArrayList<>();
        for (UUID b : branches) {
            long[] u = usage.getOrDefault(b, new long[4]);
            Count c = counts.get(b);
            long loss = c == null ? 0 : c.loss();
            long gain = c == null ? 0 : c.gain();
            boolean cost = principal.may(PROFIT_READ, b);
            rows.add(new Shrinkage(
                    b,
                    (int) u[0],
                    (int) u[2],
                    c == null ? 0 : c.shortLines(),
                    c == null ? 0 : c.overLines(),
                    cost ? u[1] : null,
                    cost ? u[3] : null,
                    cost ? loss : null,
                    cost ? gain : null,
                    cost ? Math.addExact(Math.addExact(u[1], u[3]), Math.subtractExact(loss, gain)) : null));
        }
        return rows;
    }

    /** One row of cover; {@code coverDays} adds the suggested purchase to reach that many days. */
    static CoverRow cover(Pace p, Integer coverDays) {
        BigDecimal window = BigDecimal.valueOf(VELOCITY_DAYS);
        BigDecimal left = p.qty().max(BigDecimal.ZERO);
        BigDecimal days = left.multiply(window).divide(p.sold(), 1, RoundingMode.HALF_UP);
        boolean capped = days.compareTo(CAP) > 0;
        String suggested = null;
        if (coverDays != null) {
            // Units for coverDays at this pace, rounded up to the thousandth, less what is on the shelf.
            BigDecimal need = p.sold().multiply(BigDecimal.valueOf(coverDays)).divide(window, 3, RoundingMode.CEILING);
            suggested = Quantities.format(need.subtract(left).max(BigDecimal.ZERO));
        }
        return new CoverRow(
                p.branchId(),
                p.productId(),
                p.code(),
                p.description(),
                p.unit(),
                Quantities.format(p.qty()),
                Quantities.format(p.sold()),
                p.sold().divide(window, 3, RoundingMode.HALF_UP).toPlainString(),
                AnalyticsSupport.oneDecimal(capped ? CAP : days),
                capped,
                suggested);
    }
}
