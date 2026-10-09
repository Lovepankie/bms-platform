package com.rincoltech.bms.retail.reports.internal;

import static com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.PROFIT_READ;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.Window;
import com.rincoltech.bms.retail.reports.internal.EvaluationApi.Branch;
import com.rincoltech.bms.retail.reports.internal.EvaluationApi.Category;
import com.rincoltech.bms.retail.reports.internal.EvaluationApi.Period;
import com.rincoltech.bms.retail.reports.internal.EvaluationApi.Product;
import com.rincoltech.bms.retail.reports.internal.EvaluationApi.Report;
import com.rincoltech.bms.retail.reports.internal.EvaluationApi.RunRate;
import com.rincoltech.bms.retail.reports.internal.EvaluationApi.Stock;
import com.rincoltech.bms.retail.reports.internal.EvaluationApi.Total;
import com.rincoltech.bms.retail.reports.internal.EvaluationRepository.Row;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Agg;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Dimension;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Order;
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

/**
 * The business future evaluation of issue #149: profit to date for a period, the expected profit of the
 * stock on hand at today's prices, and a run rate from the last 30 days. Formulas only, no forecast.
 */
@Service
class EvaluationService {

    static final int RUN_RATE_DAYS = 30;
    static final String NOTE =
            "An estimate from today's prices and the last 30 days of sales. It is not a forecast or a promise.";
    private static final BigDecimal CAP = BigDecimal.valueOf(365);

    private final EvaluationRepository repo;
    private final SalesAnalysisRepository sales;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    EvaluationService(
            EvaluationRepository repo, SalesAnalysisRepository sales, CurrentTenant tenant, BusinessClock clock) {
        this.repo = repo;
        this.sales = sales;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    Report evaluate(List<UUID> branchIds, LocalDate from, LocalDate to, Integer topParam) {
        // Every figure is profit or cost: the scope is that of retail.profit.read (ADR-017).
        List<UUID> filter = CurrentPrincipal.require().branchFilter(PROFIT_READ, branchIds);
        LocalDate today = clock.today(tenant.profile().timezone());
        Window w = AnalyticsSupport.window(from, to, today);
        int top = AnalyticsSupport.top(topParam);

        // Branch, then category, then items, as the statement returns them.
        Map<UUID, Map<UUID, List<Row>>> tree = new LinkedHashMap<>();
        for (Row r : repo.rows(filter, w.from(), w.to(), top)) {
            tree.computeIfAbsent(r.branchId(), b -> new LinkedHashMap<>())
                    .computeIfAbsent(r.categoryId(), c -> new ArrayList<>())
                    .add(r);
        }
        LocalDate since = today.minusDays(RUN_RATE_DAYS - 1L);
        Map<UUID, Agg> pace = new LinkedHashMap<>();
        sales.grouped(Dimension.BRANCH, filter, since, today, Order.SALES, null, 500)
                .forEach(a -> pace.put(a.id(), a));

        List<Branch> branches = new ArrayList<>();
        long[] all = new long[4]; // period sales, period cost, stock at price, stock at cost
        for (Map.Entry<UUID, Map<UUID, List<Row>>> b : tree.entrySet()) {
            long[] sum = new long[4];
            List<Category> categories = new ArrayList<>();
            for (List<Row> rows : b.getValue().values()) {
                Row c = rows.getFirst();
                sum[0] = Math.addExact(sum[0], c.categorySales());
                sum[1] = Math.addExact(sum[1], c.categoryCost());
                sum[2] = Math.addExact(sum[2], c.categoryAtPrice());
                sum[3] = Math.addExact(sum[3], c.categoryAtCost());
                categories.add(new Category(
                        c.categoryId(),
                        c.category(),
                        period(c.categorySales(), c.categoryCost()),
                        stock(c.categoryAtPrice(), c.categoryAtCost()),
                        c.categoryItems(),
                        rows.stream().map(EvaluationService::product).toList()));
            }
            for (int i = 0; i < 4; i++) {
                all[i] = Math.addExact(all[i], sum[i]);
            }
            Agg a = pace.get(b.getKey());
            branches.add(new Branch(
                    b.getKey(),
                    period(sum[0], sum[1]),
                    stock(sum[2], sum[3]),
                    runRate(a == null ? 0 : a.sales(), a == null ? 0 : a.cost(), sum[2]),
                    categories));
        }
        // A branch with recent sales but no stock and nothing in the chosen period still has a run rate.
        for (Map.Entry<UUID, Agg> e : pace.entrySet()) {
            if (!tree.containsKey(e.getKey())) {
                branches.add(new Branch(
                        e.getKey(),
                        period(0, 0),
                        stock(0, 0),
                        runRate(e.getValue().sales(), e.getValue().cost(), 0),
                        List.of()));
            }
        }
        long paceSales = pace.values().stream().mapToLong(Agg::sales).sum();
        long paceCost = pace.values().stream().mapToLong(Agg::cost).sum();
        Total total = new Total(period(all[0], all[1]), stock(all[2], all[3]), runRate(paceSales, paceCost, all[2]));
        return new Report(w.from(), w.to(), tenant.profile().currency(), top, RUN_RATE_DAYS, NOTE, total, branches);
    }

    private static Period period(long sales, long cost) {
        return new Period(sales, sales - cost, AnalyticsSupport.marginBp(sales - cost, sales));
    }

    private static Stock stock(long atPrice, long atCost) {
        return new Stock(atCost, atPrice, atPrice - atCost, AnalyticsSupport.overCostBp(atPrice - atCost, atCost));
    }

    private static Product product(Row r) {
        return new Product(
                r.productId(),
                r.code(),
                r.description(),
                r.unit(),
                Quantities.format(r.qty()),
                period(r.sales(), r.cost()),
                stock(r.atPrice(), r.atCost()));
    }

    /** The last 30 days as a daily pace, and how many days the stock at price would last at that pace. */
    static RunRate runRate(long sales30, long cost30, long stockAtPrice) {
        BigDecimal days = BigDecimal.valueOf(RUN_RATE_DAYS);
        long profit = sales30 - cost30;
        String cover = null;
        boolean capped = false;
        if (sales30 > 0) {
            BigDecimal d = BigDecimal.valueOf(stockAtPrice)
                    .multiply(days)
                    .divide(BigDecimal.valueOf(sales30), 1, RoundingMode.HALF_UP);
            capped = d.compareTo(CAP) > 0;
            cover = AnalyticsSupport.oneDecimal(capped ? CAP : d);
        }
        return new RunRate(sales30, profit, daily(sales30, days), daily(profit, days), cover, capped);
    }

    private static long daily(long amount, BigDecimal days) {
        return BigDecimal.valueOf(amount).divide(days, 0, RoundingMode.HALF_UP).longValueExact();
    }
}
