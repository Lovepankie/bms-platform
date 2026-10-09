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
import com.rincoltech.bms.retail.reports.internal.StockHealthApi.Shrinkage;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.Count;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.DeadRow;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.DeadTotal;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.Pace;
import com.rincoltech.bms.retail.reports.internal.StockHealthRepository.Usage;
import com.rincoltech.bms.retail.stock.Quantities;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The stock health report of issue #149: days of cover, reorder suggestions, dead stock and shrinkage. */
@Service
class StockHealthService {

    static final int VELOCITY_DAYS = 30;
    static final int DEAD_DAYS = 90;
    static final int DEFAULT_LEAD_DAYS = 14;
    static final int DEFAULT_COVER_DAYS = 30;
    static final int MAX_DAYS = 365;
    private static final BigDecimal CAP = BigDecimal.valueOf(MAX_DAYS);

    private final StockHealthRepository repo;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    StockHealthService(StockHealthRepository repo, CurrentTenant tenant, BusinessClock clock) {
        this.repo = repo;
        this.tenant = tenant;
        this.clock = clock;
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
            deadBranches.add(new DeadBranch(d.branchId(), d.items(), d.atPrice(), cost ? d.atCost() : null));
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
                        r.atPrice(),
                        principal.may(PROFIT_READ, r.branchId()) ? r.atCost() : null))
                .toList();
        DeadStock dead = new DeadStock(
                DEAD_DAYS,
                deadBranches,
                deadItems,
                totals.stream().mapToInt(DeadTotal::items).sum(),
                costEverywhere ? atCost : null,
                atPrice);

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
                shrinkage(principal, filter, w));
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
