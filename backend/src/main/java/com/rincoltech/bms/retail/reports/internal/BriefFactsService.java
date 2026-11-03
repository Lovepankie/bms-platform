package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.reports.RetailBriefFacts;
import com.rincoltech.bms.retail.reports.internal.CreditRepository.BuyerRow;
import com.rincoltech.bms.retail.reports.internal.DashboardRepository.DaySales;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Agg;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Dimension;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Order;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The facts of the retail AI brief (issue #422), read with the queries of the shop reports so that every figure agrees with the
 * page its chip opens: the dashboard's sales, credit control's totals, the sales analysis's items, the stock counts of Home and
 * the Controls cards. The figures stand at the end of the day before the business date, since the brief is made in the
 * morning; changes are against the usual day of the same weekday (the four weeks before) and the seven days before.
 */
@Service
class BriefFactsService implements RetailBriefFacts {

    static final String SALE_READ = "retail.sale.read";
    static final String CONTROLS_READ = ControlsService.PERMISSION;

    /** The best sellers named in the brief, at most. */
    static final int TOP = 3;

    /** The Controls cards the brief counts: what an owner looks at first. */
    static final List<String> CONTROLS = List.of("voided_sales", "returns", "price_overrides");

    private static final Map<String, String> CONTROL_LABELS = Map.of(
            "voided_sales", "Sales cancelled in the last seven days",
            "returns", "Goods taken back in the last seven days",
            "price_overrides", "Prices changed at the till in the last seven days");

    /**
     * What a view may read. A null filter is every branch; {@code money} false leaves out every money figure but the seller's
     * own, which is read only when {@code ownSeller} is set.
     */
    record View(
            boolean money,
            List<UUID> moneyFilter,
            boolean profit,
            boolean stock,
            List<UUID> stockFilter,
            boolean controls,
            List<UUID> controlsFilter,
            boolean orders,
            List<UUID> orderFilter,
            UUID ownSeller,
            List<UUID> ownFilter) {

        static final View EVERYTHING = new View(true, null, true, true, null, true, null, true, null, null, null);
    }

    private final DashboardRepository dashboard;
    private final CreditRepository credit;
    private final SalesAnalysisRepository analysis;
    private final ControlsRepository controls;
    private final HomeRepository home;
    private final RetailCapabilities capabilities;
    private final CurrentTenant tenant;

    BriefFactsService(
            DashboardRepository dashboard,
            CreditRepository credit,
            SalesAnalysisRepository analysis,
            ControlsRepository controls,
            HomeRepository home,
            RetailCapabilities capabilities,
            CurrentTenant tenant) {
        this.dashboard = dashboard;
        this.credit = credit;
        this.analysis = analysis;
        this.controls = controls;
        this.home = home;
        this.capabilities = capabilities;
        this.tenant = tenant;
    }

    @Override
    @Transactional(readOnly = true)
    public FactSet forOwner(LocalDate date) {
        return build(date, View.EVERYTHING);
    }

    @Override
    @Transactional(readOnly = true)
    public FactSet forCaller(LocalDate date) {
        return build(date, viewOf(CurrentPrincipal.require()));
    }

    /** The caller's view (ADR-055): money over every seller only where they see every seller, their own sales otherwise. */
    static View viewOf(Principal p) {
        List<UUID> moneyFilter = p.hasPermission(SALE_READ) ? AnalyticsSupport.everySellerScope(p, null) : List.of();
        boolean money = p.hasPermission(SALE_READ)
                && (moneyFilter == null || !moneyFilter.isEmpty())
                && AnalyticsSupport.covers(p, moneyFilter);
        boolean profit = money && AnalyticsSupport.profitVisible(p, moneyFilter);
        List<UUID> stockFilter = p.branchFilter(DashboardService.STOCK_READ, null);
        List<UUID> controlsFilter = p.branchFilter(CONTROLS_READ, null);
        List<UUID> orderFilter = p.branchFilter(HomeService.ORDER_MANAGE, null);
        List<UUID> ownFilter = p.branchFilter(SALE_READ, null);
        boolean own = !money && p.hasPermission(SALE_READ) && (ownFilter == null || !ownFilter.isEmpty());
        return new View(
                money,
                moneyFilter,
                profit,
                some(p, DashboardService.STOCK_READ, stockFilter),
                stockFilter,
                some(p, CONTROLS_READ, controlsFilter),
                controlsFilter,
                some(p, HomeService.ORDER_MANAGE, orderFilter),
                orderFilter,
                own ? p.userId() : null,
                ownFilter);
    }

    private static boolean some(Principal p, String permission, List<UUID> filter) {
        return p.hasPermission(permission) && (filter == null || !filter.isEmpty());
    }

    FactSet build(LocalDate date, View view) {
        String currency = tenant.profile().currency();
        ZoneId zone = tenant.profile().timezone();
        LocalDate y = date.minusDays(1);
        List<Fact> facts = new ArrayList<>();
        List<ItemName> names = new ArrayList<>();
        Display d = new Display(currency);
        if (view.money()) {
            money(view, y, date, d, facts, names);
        } else if (view.ownSeller() != null) {
            own(view, y, d, facts);
        }
        if (view.stock()) {
            int[] counts = dashboard.counts(view.stockFilter(), DashboardService.LOW_STOCK);
            facts.add(d.count("stock.out", "Items out of stock", Polarity.HIGHER_IS_WORSE, counts[0], null));
            facts.add(d.count("stock.low", "Items low on stock", Polarity.HIGHER_IS_WORSE, counts[1], null));
            List<SalesAnalysisRepository.Stocked> slow = analysis.slowMovers(view.stockFilter(), y.minusDays(29), y, 1);
            long slowCount = slow.isEmpty() ? 0 : slow.get(0).total();
            facts.add(d.count(
                    "stock.slow_items",
                    "Items in stock with no sale in thirty days",
                    Polarity.HIGHER_IS_WORSE,
                    slowCount,
                    null));
        }
        if (view.controls()) {
            Map<String, Long> now = controlCounts(view.controlsFilter(), y.minusDays(6), y, zone);
            Map<String, Long> before = controlCounts(view.controlsFilter(), y.minusDays(13), y.minusDays(7), zone);
            for (String metric : CONTROLS) {
                long a = now.getOrDefault(metric, 0L);
                long b = before.getOrDefault(metric, 0L);
                facts.add(d.count(
                        "controls." + metric,
                        CONTROL_LABELS.get(metric),
                        Polarity.HIGHER_IS_WORSE,
                        a,
                        d.countChange(a - b, "the seven days before")));
            }
        }
        if (view.orders()) {
            facts.add(d.count(
                    "orders.open",
                    "Customer orders not yet delivered",
                    Polarity.NEUTRAL,
                    home.openOrders(view.orderFilter()),
                    null));
        }
        return new FactSet(y, currency, view.money(), facts, names);
    }

    private void money(View view, LocalDate y, LocalDate date, Display d, List<Fact> facts, List<ItemName> names) {
        List<UUID> f = view.moneyFilter();
        Map<LocalDate, long[]> byDay = new HashMap<>(); // sales, cost, count
        for (DaySales s : dashboard.sales(f, y.minusDays(28), y)) {
            long[] day = byDay.computeIfAbsent(s.day(), k -> new long[3]);
            day[0] += s.sales();
            day[1] += s.cost();
            day[2] += s.saleCount();
        }
        LocalDate first = dashboard.firstSaleDay(f);
        long[] yesterday = byDay.getOrDefault(y, new long[3]);

        // The usual day: the same weekday in each of the four weeks before, counting only weeks the business was
        // selling.
        long usualSum = 0;
        int usualDays = 0;
        for (int week = 1; week <= 4; week++) {
            LocalDate day = y.minusWeeks(week);
            if (first != null && !day.isBefore(first)) {
                usualSum += byDay.getOrDefault(day, new long[3])[0];
                usualDays++;
            }
        }
        String weekday = y.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        Change usual = usualDays == 0
                ? null
                : d.moneyChange(yesterday[0] - Math.round((double) usualSum / usualDays), "the usual " + weekday);
        facts.add(d.money("sales.yesterday", "Sales yesterday", Polarity.HIGHER_IS_BETTER, yesterday[0], usual));
        facts.add(d.count("sales.yesterday_count", "Sales made yesterday", Polarity.NEUTRAL, yesterday[2], null));

        long[] week = sum(byDay, y.minusDays(6), y);
        long[] before = sum(byDay, y.minusDays(13), y.minusDays(7));
        boolean comparable = first != null && !first.isAfter(y.minusDays(13));
        facts.add(d.money(
                "sales.last_7_days",
                "Sales in the last seven days",
                Polarity.HIGHER_IS_BETTER,
                week[0],
                comparable ? d.moneyChange(week[0] - before[0], "the seven days before") : null));
        if (view.profit()) {
            long profit = week[0] - week[1];
            long profitBefore = before[0] - before[1];
            facts.add(d.money(
                    "profit.last_7_days",
                    "Gross profit in the last seven days",
                    Polarity.HIGHER_IS_BETTER,
                    profit,
                    comparable ? d.moneyChange(profit - profitBefore, "the seven days before") : null));
        }

        for (Agg a : analysis.grouped(Dimension.ITEM, f, y.minusDays(6), y, Order.SALES, null, TOP)) {
            if (a.sales() <= 0 || a.id() == null) {
                continue;
            }
            String item = a.id().toString();
            names.add(new ItemName(item, a.label()));
            facts.add(new Fact(
                    "item.sales_week",
                    "sales in the last seven days",
                    Kind.MONEY,
                    Polarity.HIGHER_IS_BETTER,
                    a.sales(),
                    d.money(a.sales()),
                    null,
                    item));
        }

        BuyerRow owed = credit.totals(f, date);
        long overdue = owed.buckets().owed() - owed.buckets().notDue();
        facts.add(d.money(
                "credit.owed",
                "Owed by credit buyers",
                Polarity.HIGHER_IS_WORSE,
                owed.buckets().owed(),
                null));
        facts.add(d.money("credit.overdue", "Owed past the due date", Polarity.HIGHER_IS_WORSE, overdue, null));
        facts.add(d.count("credit.buyers", "Buyers who owe", Polarity.NEUTRAL, owed.buyers(), null));

        // The wholesale figures, when the business uses them (ADR-033): sales at a price list's price, buyers over
        // their limit.
        if (capabilities.enabled(RetailCapabilities.PRICE_LISTS)) {
            long listed = analysis.listPricedSales(f, y.minusDays(6), y);
            long listedBefore = analysis.listPricedSales(f, y.minusDays(13), y.minusDays(7));
            facts.add(d.money(
                    "wholesale.list_sales",
                    "Sales at a price list price in the last seven days",
                    Polarity.HIGHER_IS_BETTER,
                    listed,
                    comparable ? d.moneyChange(listed - listedBefore, "the seven days before") : null));
        }
        if (capabilities.enabled(RetailCapabilities.CUSTOMER_ACCOUNTS)) {
            long over = credit.overLimitCount(f, date);
            facts.add(d.count(
                    "credit.over_limit", "Credit customers over their limit", Polarity.HIGHER_IS_WORSE, over, null));
        }
    }

    /** A seller who sees only their own sales (ADR-055): their own figures, and nobody else's. */
    private void own(View view, LocalDate y, Display d, List<Fact> facts) {
        long yesterday = ownSales(view, y, y);
        long week = ownSales(view, y.minusDays(6), y);
        long before = ownSales(view, y.minusDays(13), y.minusDays(7));
        facts.add(d.money("own.sales_yesterday", "Your sales yesterday", Polarity.HIGHER_IS_BETTER, yesterday, null));
        facts.add(d.money(
                "own.sales_last_7_days",
                "Your sales in the last seven days",
                Polarity.HIGHER_IS_BETTER,
                week,
                d.moneyChange(week - before, "the seven days before")));
    }

    private long ownSales(View view, LocalDate from, LocalDate to) {
        return analysis.one(Dimension.SELLER, view.ownFilter(), from, to, view.ownSeller())
                .map(Agg::sales)
                .orElse(0L);
    }

    private Map<String, Long> controlCounts(List<UUID> filter, LocalDate from, LocalDate to, ZoneId zone) {
        Map<String, Long> out = new HashMap<>();
        ControlsRepository.Window w = new ControlsRepository.Window(
                from, to, zone, filter, ControlsService.DEFAULT_WITHIN_MINUTES, null, null, null);
        controls.counts(CONTROLS, w).forEach(c -> out.put(c.metric(), c.count()));
        return out;
    }

    private static long[] sum(Map<LocalDate, long[]> byDay, LocalDate from, LocalDate to) {
        long[] out = new long[3];
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            long[] v = byDay.get(day);
            if (v != null) {
                out[0] += v[0];
                out[1] += v[1];
                out[2] += v[2];
            }
        }
        return out;
    }

    /**
     * The display forms, grouped in threes ({@code UGX 1,250,000}) so that a prompt never holds a run of six digits, which the
     * AI gateway's input screen refuses. Amounts are whole units of the currency's minor unit as the retail tables keep them.
     */
    record Display(String currency) {

        String money(long minor) {
            return (minor < 0 ? "-" : "") + currency + " " + grouped(Math.abs(minor));
        }

        static String count(long n) {
            return (n < 0 ? "-" : "") + grouped(Math.abs(n));
        }

        Fact money(String key, String label, Polarity polarity, long value, Change change) {
            return new Fact(key, label, Kind.MONEY, polarity, value, money(value), change, null);
        }

        Fact count(String key, String label, Polarity polarity, long value, Change change) {
            return new Fact(key, label, Kind.COUNT, polarity, value, count(value), change, null);
        }

        Change moneyChange(long change, String against) {
            return new Change(
                    change, change == 0 ? "no change" : (change > 0 ? "+" : "-") + money(Math.abs(change)), against);
        }

        Change countChange(long change, String against) {
            return new Change(
                    change, change == 0 ? "no change" : (change > 0 ? "+" : "-") + count(Math.abs(change)), against);
        }

        static String grouped(long n) {
            String whole = String.valueOf(n);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < whole.length(); i++) {
                if (i > 0 && (whole.length() - i) % 3 == 0) {
                    out.append(',');
                }
                out.append(whole.charAt(i));
            }
            return out.toString();
        }
    }
}
