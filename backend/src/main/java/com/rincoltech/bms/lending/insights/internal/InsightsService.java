package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.insights.InsightsPanel;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.ArrearsRow;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Breakdown;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.BriefResponse;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Bucket;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.CountPoint;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.DayAmount;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Drill;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Group;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.MembersResponse;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Metric;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Panel;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.PanelsResponse;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.PeriodPoint;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.PortfolioResponse;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.RevenueMonth;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.RevenueResponse;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.RevenueRow;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Slice;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.StaffRow;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Stage;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.TableResponse;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.TrendPoint;
import com.rincoltech.bms.lending.insights.internal.InsightsQueries.DayTotal;
import com.rincoltech.bms.lending.insights.internal.InsightsQueries.DuePaid;
import com.rincoltech.bms.lending.insights.internal.InsightsQueries.LedgerAmount;
import com.rincoltech.bms.lending.insights.internal.Periods.Period;
import com.rincoltech.bms.lending.insights.internal.Positions.Position;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The insights use cases (chapter 14 section 14.9): each a read-only transaction, the scope
 * resolved from the request principal: the branches of {@code lending.insights.read} and, without
 * {@code lending.insights.all_officers}, only the caller's own loans. Today's numbers are live;
 * a past date reads the nightly snapshot.
 */
@Service
class InsightsService {

    static final String READ = "lending.insights.read";
    static final String ALL_OFFICERS = "lending.insights.all_officers";
    static final String MEMBERS_READ = "lending.members.read";

    /** Rows a drill-down returns on the page; an export returns up to {@link #EXPORT_LIMIT}. */
    static final int TABLE_LIMIT = 200;

    static final int EXPORT_LIMIT = 10_000;

    /** The longest range a request may ask for: five years. */
    static final long MAX_RANGE_DAYS = 5 * 366;

    private final Positions positions;
    private final InsightsQueries queries;
    private final InsightsTables tables;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final ObjectProvider<InsightsPanel> panels;

    InsightsService(
            Positions positions,
            InsightsQueries queries,
            InsightsTables tables,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            ObjectProvider<InsightsPanel> panels) {
        this.positions = positions;
        this.queries = queries;
        this.tables = tables;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.panels = panels;
    }

    /** A staff user the users table does not name (the fabricated seed's actors). */
    static String staffLabel(UUID id) {
        String s = String.valueOf(id);
        return id == null ? "Unassigned" : "Staff " + s.substring(s.length() - 4);
    }

    // ---- Scope ------------------------------------------------------------------------------

    LocalDate today() {
        return clock.today(tenant.profile().timezone());
    }

    private ZoneId zone() {
        return tenant.profile().timezone();
    }

    Scope scope(LocalDate from, LocalDate to, List<UUID> branchIds, UUID officerId, UUID productId) {
        Principal principal = CurrentPrincipal.require();
        LocalDate today = today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.withDayOfMonth(1) : from;
        if (end.isAfter(today)) {
            throw ApiException.rule("range_in_future", "The range ends today or earlier.");
        }
        if (start.isAfter(end)) {
            throw ApiException.rule("invalid_range", "The range starts on or before its end.");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw ApiException.rule("range_too_long", "The range is at most five years.");
        }
        UUID officer = principal.hasPermission(ALL_OFFICERS) ? officerId : principal.userId();
        return new Scope(
                start,
                end,
                today,
                principal.branchFilter(READ, branchIds),
                officer,
                productId,
                tenant.profile().currency());
    }

    // ---- Morning brief ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    BriefResponse brief(Scope base) {
        LocalDate today = base.today();
        Scope day = base.withRange(today, today);
        Scope weekAgo = base.withRange(today.minusDays(7), today.minusDays(7));
        String cur = base.currency();
        long[] out = sum(queries.disbursedByDay(day));
        long[] outBefore = sum(queries.disbursedByDay(weekAgo));
        long[] in = sum(queries.collectedByDay(day, InsightsQueries.COLLECTED_TYPES));
        long[] inBefore = sum(queries.collectedByDay(weekAgo, InsightsQueries.COLLECTED_TYPES));
        long expected = sum(queries.expectedByDay(day))[0];
        long onDue = queries.collectedOnDue(day).stream()
                .mapToLong(DuePaid::amountMinor)
                .sum();
        Long rate = Metrics.bp(onDue, expected);
        List<Position> live = positions.live(base);
        List<Position> fresh = live.stream().filter(p -> p.daysPastDue() == 1).toList();
        List<Position> goingBad = live.stream()
                .filter(p -> p.daysPastDue() >= 24 && p.daysPastDue() <= 30)
                .toList();
        long freshAmount = fresh.stream().mapToLong(Position::arrearsMinor).sum();
        long goingBadPrincipal =
                goingBad.stream().mapToLong(Position::principalOutstandingMinor).sum();
        String d = today.toString();
        List<Metric> metrics = List.of(
                Metrics.of("brief.disbursed_today", out[0], cur, Drill.of("disbursements", "date", d)),
                Metrics.of("brief.disbursed_loans_today", out[1], cur, Drill.of("disbursements", "date", d)),
                Metrics.of("brief.collected_today", in[0], cur, Drill.of("collections", "date", d)),
                Metrics.of("brief.expected_today", expected, cur, Drill.of("expected", "date", d)),
                Metrics.of("brief.collected_on_due_today", onDue, cur, Drill.of("expected", "date", d)),
                Metrics.of("brief.collection_rate_today", rate, cur, Drill.of("expected", "date", d)),
                Metrics.of("brief.new_arrears", (long) fresh.size(), cur, arrearsDrill(1, 1)),
                Metrics.of("brief.new_arrears_amount", freshAmount, cur, arrearsDrill(1, 1)),
                Metrics.of("brief.going_bad", (long) goingBad.size(), cur, arrearsDrill(24, 30)),
                Metrics.of("brief.going_bad_principal", goingBadPrincipal, cur, arrearsDrill(24, 30)));
        List<String> sentences = List.of(
                Sentences.disbursed(out[0], out[1], outBefore[0], cur),
                Sentences.collected(in[0], inBefore[0], cur),
                Sentences.dueToday(expected, onDue, rate, cur),
                Sentences.newArrears(fresh.size(), freshAmount, cur),
                Sentences.goingBad(goingBad.size(), goingBadPrincipal, cur));
        return new BriefResponse(today, cur, metrics, sentences, clock.now());
    }

    private static Drill arrearsDrill(int min, int max) {
        return new Drill("arrears", Map.of("min_dpd", String.valueOf(min), "max_dpd", String.valueOf(max)));
    }

    private static long[] sum(List<DayTotal> days) {
        long amount = 0;
        long count = 0;
        for (DayTotal d : days) {
            amount += d.amountMinor();
            count += d.count();
        }
        return new long[] {amount, count};
    }

    // ---- Loan portfolio ---------------------------------------------------------------------

    /** The loans standing at the end of the range: live for today, the snapshot for a past date. */
    record Stock(LocalDate asOf, String source, List<Position> loans) {}

    Stock stock(Scope s) {
        if (s.to().equals(s.today())) {
            return new Stock(s.today(), "live", positions.live(s));
        }
        if (!positions.snapshotTaken(s.to())) {
            return new Stock(s.to(), "none", List.of());
        }
        return new Stock(s.to(), "snapshot", positions.snapshot(s, s.to()));
    }

    @Transactional(readOnly = true)
    PortfolioResponse portfolio(Scope s, String grainAsked) {
        String grain = grainAsked == null ? Periods.defaultGrain(s.from(), s.to()) : grainAsked;
        List<Period> periods = Periods.split(s.from(), s.to(), grain);
        String cur = s.currency();
        Stock stock = stock(s);
        List<Position> loans = stock.loans();

        List<DayTotal> disbursed = queries.disbursedByDay(s);
        List<DayTotal> collected = queries.collectedByDay(s, InsightsQueries.COLLECTED_TYPES);
        List<DayTotal> expected = queries.expectedByDay(s);
        List<DuePaid> onDue = queries.collectedOnDue(s);
        List<PeriodPoint> series = series(periods, disbursed, collected, expected, onDue);

        long po = loans.stream().mapToLong(Position::principalOutstandingMinor).sum();
        long io = loans.stream().mapToLong(Position::interestOutstandingMinor).sum();
        long arrears = loans.stream().mapToLong(Position::arrearsMinor).sum();
        long borrowers = loans.stream().map(Position::memberId).distinct().count();
        long expectedTotal = sum(expected)[0];
        long onDueTotal = onDue.stream().mapToLong(DuePaid::amountMinor).sum();
        long[] disbursedTotal = sum(disbursed);
        long[] writeOffs = sum(queries.writtenOffByDay(s));
        long recovered = sum(queries.collectedByDay(s, "('recovery')"))[0];
        long[] repeat = queries.repeatBorrowers(s);
        long[] tenor = queries.tenor(s);
        List<DayTotal> forecast = queries.forecast(s, s.today().plusDays(30));
        long next7 = forecast.stream()
                .filter(d -> !d.date().isAfter(s.today().plusDays(7)))
                .mapToLong(DayTotal::amountMinor)
                .sum();
        long next30 = sum(forecast)[0];
        long income = queries.ledger(s, s.from(), s.to()).stream()
                .filter(l -> l.systemKey().equals("loan_interest_income")
                        || l.systemKey().equals("loan_fee_income"))
                .mapToLong(LedgerAmount::amountMinor)
                .sum();
        List<Position> live = stock.source().equals("live") ? loans : null;
        AverageBalance avg = averagePrincipal(balances(s, live), null);

        List<Metric> metrics = new ArrayList<>();
        metrics.add(Metrics.of("portfolio.principal_outstanding", po, cur, Drill.of("active_loans")));
        metrics.add(Metrics.of("portfolio.interest_receivable", io, cur, Drill.of("active_loans")));
        metrics.add(Metrics.of("portfolio.active_loans", (long) loans.size(), cur, Drill.of("active_loans")));
        metrics.add(Metrics.of("portfolio.borrowers", borrowers, cur, Drill.of("active_loans")));
        metrics.add(Metrics.of("portfolio.arrears", arrears, cur, Drill.of("arrears")));
        for (int x : new int[] {0, 30, 60, 90}) {
            long atRisk = loans.stream()
                    .filter(p -> p.daysPastDue() > x)
                    .mapToLong(Position::principalOutstandingMinor)
                    .sum();
            metrics.add(Metrics.of(
                    "portfolio.par" + (x == 0 ? 1 : x),
                    Metrics.bp(atRisk, po),
                    cur,
                    new Drill("arrears", Map.of("min_dpd", String.valueOf(x + 1)))));
        }
        metrics.add(Metrics.of("portfolio.disbursed", disbursedTotal[0], cur, Drill.of("disbursements")));
        metrics.add(Metrics.of("portfolio.disbursed_count", disbursedTotal[1], cur, Drill.of("disbursements")));
        metrics.add(Metrics.of("portfolio.collected", sum(collected)[0], cur, Drill.of("collections")));
        metrics.add(Metrics.of("portfolio.expected", expectedTotal, cur, Drill.of("expected")));
        metrics.add(Metrics.of("portfolio.collected_on_due", onDueTotal, cur, Drill.of("expected")));
        metrics.add(Metrics.of(
                "portfolio.collection_rate", Metrics.bp(onDueTotal, expectedTotal), cur, Drill.of("expected")));
        metrics.add(Metrics.of("portfolio.forecast_7", next7, cur, Drill.of("forecast", "days", "7")));
        metrics.add(Metrics.of("portfolio.forecast_30", next30, cur, Drill.of("forecast", "days", "30")));
        metrics.add(Metrics.of("portfolio.written_off", writeOffs[0], cur, Drill.of("write_offs")));
        metrics.add(Metrics.of("portfolio.written_off_count", writeOffs[1], cur, Drill.of("write_offs")));
        metrics.add(Metrics.of("portfolio.recovered", recovered, cur, Drill.of("recoveries")));
        metrics.add(Metrics.of("portfolio.repeat_borrowers", repeat[1], cur, Drill.of("repeat_borrowers")));
        metrics.add(Metrics.of(
                "portfolio.repeat_share", Metrics.bp(repeat[1], repeat[0]), cur, Drill.of("repeat_borrowers")));
        metrics.add(Metrics.of(
                "portfolio.average_loan_size",
                disbursedTotal[1] == 0 ? null : divideHalfUp(disbursedTotal[0], disbursedTotal[1]),
                cur,
                Drill.of("disbursements")));
        metrics.add(Metrics.of(
                "portfolio.average_tenor_days",
                tenor[1] == 0 ? null : divideHalfUp(tenor[0], tenor[1]),
                cur,
                Drill.of("disbursements")));
        metrics.add(
                Metrics.of("portfolio.yield", annualisedBp(income, avg, s), cur, new Drill("revenue_lines", Map.of())));

        return new PortfolioResponse(
                s.from(),
                s.to(),
                grain,
                cur,
                stock.asOf(),
                stock.source(),
                metrics,
                series,
                ageing(loans, po),
                breakdowns(s, loans),
                topArrears(loans),
                forecast.stream()
                        .map(d -> new DayAmount(d.date(), d.amountMinor()))
                        .toList(),
                trend(s, live),
                clock.now());
    }

    static List<PeriodPoint> series(
            List<Period> periods,
            List<DayTotal> disbursed,
            List<DayTotal> collected,
            List<DayTotal> expected,
            List<DuePaid> onDue) {
        int n = periods.size();
        long[] dAmount = new long[n];
        long[] dCount = new long[n];
        long[] cAmount = new long[n];
        long[] eAmount = new long[n];
        long[] oAmount = new long[n];
        for (DayTotal d : disbursed) {
            int i = Periods.indexOf(periods, d.date());
            if (i >= 0) {
                dAmount[i] += d.amountMinor();
                dCount[i] += d.count();
            }
        }
        for (DayTotal d : collected) {
            int i = Periods.indexOf(periods, d.date());
            if (i >= 0) {
                cAmount[i] += d.amountMinor();
            }
        }
        for (DayTotal d : expected) {
            int i = Periods.indexOf(periods, d.date());
            if (i >= 0) {
                eAmount[i] += d.amountMinor();
            }
        }
        // A period's collection rate counts what was paid by the end of that period.
        for (DuePaid d : onDue) {
            int i = Periods.indexOf(periods, d.dueDate());
            if (i >= 0 && !d.paidOn().isAfter(periods.get(i).end())) {
                oAmount[i] += d.amountMinor();
            }
        }
        List<PeriodPoint> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Period p = periods.get(i);
            out.add(new PeriodPoint(
                    p.start(),
                    p.end(),
                    p.label(),
                    dAmount[i],
                    dCount[i],
                    cAmount[i],
                    eAmount[i],
                    oAmount[i],
                    Metrics.bp(oAmount[i], eAmount[i])));
        }
        return out;
    }

    static final Map<String, String> BUCKET_LABELS = linked(
            "current", "Current",
            "1_30", "1 to 30 days",
            "31_60", "31 to 60 days",
            "61_90", "61 to 90 days",
            "over_90", "Over 90 days");

    static List<Bucket> ageing(List<Position> loans, long po) {
        List<Bucket> out = new ArrayList<>();
        for (Map.Entry<String, String> b : BUCKET_LABELS.entrySet()) {
            List<Position> in =
                    loans.stream().filter(p -> p.bucket().equals(b.getKey())).toList();
            long principal =
                    in.stream().mapToLong(Position::principalOutstandingMinor).sum();
            out.add(new Bucket(
                    b.getKey(),
                    b.getValue(),
                    in.size(),
                    principal,
                    Metrics.bp(principal, po),
                    in.stream().mapToLong(Position::arrearsMinor).sum()));
        }
        return out;
    }

    private List<Breakdown> breakdowns(Scope s, List<Position> loans) {
        Map<UUID, String> products = queries.productNames();
        Map<UUID, String> branches = queries.branchNames();
        Map<UUID, String> officers =
                queries.userNames(loans.stream().map(Position::officerUserId).collect(Collectors.toSet()));
        List<Breakdown> out = new ArrayList<>();
        out.add(new Breakdown("product", "By product", group(loans, Position::productId, products)));
        out.add(new Breakdown("branch", "By branch", group(loans, Position::branchId, branches)));
        out.add(new Breakdown("officer", "By officer", group(loans, Position::officerUserId, officers)));
        List<Group> statuses = queries.byStatus(s).stream()
                .map(r -> new Group((String) r[0], words((String) r[0]), (Long) r[1], (Long) r[2], 0, null))
                .toList();
        out.add(new Breakdown("status", "By status (principal approved or requested)", statuses));
        return out;
    }

    private static List<Group> group(List<Position> loans, Function<Position, UUID> key, Map<UUID, String> names) {
        Map<UUID, List<Position>> by =
                loans.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
        List<Group> out = new ArrayList<>();
        by.forEach((id, list) -> {
            long po =
                    list.stream().mapToLong(Position::principalOutstandingMinor).sum();
            long par30 = list.stream()
                    .filter(p -> p.daysPastDue() > 30)
                    .mapToLong(Position::principalOutstandingMinor)
                    .sum();
            out.add(new Group(
                    String.valueOf(id),
                    names.getOrDefault(id, staffLabel(id)),
                    list.size(),
                    po,
                    par30,
                    Metrics.bp(par30, po)));
        });
        out.sort(Comparator.comparingLong(Group::principalOutstandingMinor)
                .reversed()
                .thenComparing(Group::label));
        return out;
    }

    private List<ArrearsRow> topArrears(List<Position> loans) {
        List<Position> top = loans.stream()
                .filter(p -> p.daysPastDue() > 0)
                .sorted(Comparator.comparingLong(Position::arrearsMinor)
                        .reversed()
                        .thenComparing(Position::loanNo))
                .limit(10)
                .toList();
        Map<UUID, String[]> members =
                queries.members(top.stream().map(Position::memberId).collect(Collectors.toSet()));
        Map<UUID, String> officers =
                queries.userNames(top.stream().map(Position::officerUserId).collect(Collectors.toSet()));
        return top.stream()
                .map(p -> {
                    String[] m = members.getOrDefault(p.memberId(), new String[] {"", ""});
                    return new ArrearsRow(
                            p.loanId(),
                            p.loanNo(),
                            p.memberId(),
                            m[0],
                            m[1],
                            p.daysPastDue(),
                            p.arrearsMinor(),
                            p.principalOutstandingMinor(),
                            officers.getOrDefault(p.officerUserId(), staffLabel(p.officerUserId())));
                })
                .toList();
    }

    private List<TrendPoint> trend(Scope s, List<Position> liveIfRead) {
        List<LocalDate> ends = Periods.monthEnds(s.today());
        List<LocalDate> past = ends.subList(0, ends.size() - 1);
        Map<LocalDate, Object[]> snaps = new HashMap<>();
        queries.snapshotTrend(s, past).forEach(r -> snaps.put((LocalDate) r[0], r));
        LocalDate taken =
                past.stream().filter(positions::snapshotTaken).findFirst().orElse(null);
        List<TrendPoint> out = new ArrayList<>();
        for (LocalDate d : past) {
            Object[] r = snaps.get(d);
            boolean available = taken != null && !d.isBefore(taken);
            String label = Periods.label(d.withDayOfMonth(1), d, "month");
            if (r == null) {
                out.add(
                        available
                                ? new TrendPoint(d, label, "snapshot", 0L, null, 0L)
                                : new TrendPoint(d, label, "none", null, null, null));
            } else {
                long po = (Long) r[1];
                out.add(new TrendPoint(d, label, "snapshot", po, Metrics.bp((Long) r[2], po), (Long) r[3]));
            }
        }
        List<Position> live = liveIfRead != null ? liveIfRead : positions.live(s);
        long po = live.stream().mapToLong(Position::principalOutstandingMinor).sum();
        long par30 = live.stream()
                .filter(p -> p.daysPastDue() > 30)
                .mapToLong(Position::principalOutstandingMinor)
                .sum();
        out.add(new TrendPoint(s.today(), "Today", "live", po, Metrics.bp(par30, po), (long) live.size()));
        return out;
    }

    /** The average daily principal outstanding over the range: a sum of daily totals and the number of days with data. */
    record AverageBalance(BigInteger sum, long days) {}

    /** The daily balances under the yields, read once per request. */
    record Balances(List<Object[]> snapshotRows, List<Position> live) {}

    /**
     * Daily principal outstanding by product from the snapshots for the past days of the range,
     * and the live positions for today when the range ends today ({@code live} when the caller has
     * them already).
     */
    Balances balances(Scope s, List<Position> live) {
        boolean endsToday = s.to().equals(s.today());
        LocalDate lastPast = endsToday ? s.today().minusDays(1) : s.to();
        List<Position> today = endsToday ? (live != null ? live : positions.live(s)) : null;
        return new Balances(queries.snapshotPrincipal(s, s.from(), lastPast), today);
    }

    /**
     * The average daily principal outstanding over the range, for one product or all ({@code
     * product} null). Days before the first snapshot have no data and are left out of the average.
     */
    static AverageBalance averagePrincipal(Balances b, UUID product) {
        Map<LocalDate, Long> byDate = new TreeMap<>();
        for (Object[] r : b.snapshotRows()) {
            if (product == null || product.equals(r[1])) {
                byDate.merge((LocalDate) r[0], (Long) r[2], Long::sum);
            }
        }
        long days = byDate.size();
        BigInteger total = byDate.values().stream().map(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
        if (b.live() != null) {
            long live = b.live().stream()
                    .filter(p -> product == null || product.equals(p.productId()))
                    .mapToLong(Position::principalOutstandingMinor)
                    .sum();
            total = total.add(BigInteger.valueOf(live));
            days++;
        }
        return new AverageBalance(total, days);
    }

    /** {@code income / average balance * 365 / days in range}, in basis points, half up; null without a balance. */
    static Long annualisedBp(long income, AverageBalance avg, Scope s) {
        long rangeDays = ChronoUnit.DAYS.between(s.from(), s.to()) + 1;
        if (avg.days() == 0 || avg.sum().signum() == 0) {
            return null;
        }
        BigInteger numerator = BigInteger.valueOf(income)
                .multiply(BigInteger.valueOf(10_000L * 365L))
                .multiply(BigInteger.valueOf(avg.days()))
                .multiply(BigInteger.TWO);
        BigInteger denominator =
                avg.sum().multiply(BigInteger.valueOf(rangeDays)).multiply(BigInteger.TWO);
        // Half up: (2n + d) / 2d.
        return numerator
                .add(denominator.divide(BigInteger.TWO))
                .divide(denominator)
                .longValueExact();
    }

    static long divideHalfUp(long a, long b) {
        return Math.floorDiv(2 * a + b, 2 * b);
    }

    // ---- Revenue ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    RevenueResponse revenue(Scope s) {
        String cur = s.currency();
        // One read covers the range and the 12 months the trend shows.
        LocalDate firstMonth = s.to().withDayOfMonth(1).minusMonths(11);
        List<LedgerAmount> all = queries.ledger(s, s.from().isBefore(firstMonth) ? s.from() : firstMonth, s.to());
        List<LedgerAmount> range =
                all.stream().filter(l -> !l.date().isBefore(s.from())).toList();
        Map<UUID, String> products = queries.productNames();
        Map<UUID, String> branches = queries.branchNames();
        long[] total = components(range);
        Balances balances = balances(s, null);
        AverageBalance avg = averagePrincipal(balances, null);
        List<Metric> metrics = List.of(
                Metrics.of(
                        "revenue.interest",
                        total[0],
                        cur,
                        Drill.of("revenue_lines", "account", "loan_interest_income")),
                Metrics.of("revenue.fees", total[1], cur, Drill.of("revenue_lines", "account", "loan_fee_income")),
                Metrics.of(
                        "revenue.penalties",
                        total[2],
                        cur,
                        Drill.of("revenue_lines", "account", "loan_penalty_income")),
                Metrics.of(
                        "revenue.recovered", total[3], cur, Drill.of("revenue_lines", "account", "bad_debt_recovered")),
                Metrics.of(
                        "revenue.write_off_expense",
                        total[4],
                        cur,
                        Drill.of("revenue_lines", "account", "loan_write_off_expense")),
                Metrics.of("revenue.contribution", total[5], cur, Drill.of("revenue_lines")),
                Metrics.of(
                        "revenue.effective_yield",
                        annualisedBp(total[0] + total[1] + total[2], avg, s),
                        cur,
                        Drill.of("revenue_lines")));
        List<RevenueRow> byProduct = new ArrayList<>();
        range.stream().map(LedgerAmount::productId).distinct().forEach(id -> {
            long[] c = components(
                    range.stream().filter(l -> id.equals(l.productId())).toList());
            byProduct.add(row(
                    String.valueOf(id),
                    products.getOrDefault(id, ""),
                    c,
                    annualisedBp(c[0] + c[1] + c[2], averagePrincipal(balances, id), s)));
        });
        byProduct.sort(Comparator.comparingLong(RevenueRow::contributionMinor)
                .reversed()
                .thenComparing(RevenueRow::label));
        List<RevenueRow> byBranch = new ArrayList<>();
        range.stream().map(LedgerAmount::branchId).distinct().forEach(id -> {
            long[] c = components(
                    range.stream().filter(l -> id.equals(l.branchId())).toList());
            byBranch.add(row(String.valueOf(id), branches.getOrDefault(id, ""), c, null));
        });
        byBranch.sort(Comparator.comparingLong(RevenueRow::contributionMinor)
                .reversed()
                .thenComparing(RevenueRow::label));

        List<RevenueMonth> months = new ArrayList<>();
        for (int k = 0; k < 12; k++) {
            LocalDate m = firstMonth.plusMonths(k);
            long[] c = components(all.stream()
                    .filter(l -> l.date().withDayOfMonth(1).equals(m))
                    .toList());
            months.add(new RevenueMonth(m, Periods.label(m, m, "month"), c[0], c[1], c[2], c[3], c[4], c[5]));
        }
        return new RevenueResponse(s.from(), s.to(), cur, metrics, byProduct, byBranch, months, clock.now());
    }

    /** interest, fees, penalties, recovered, write-off expense (positive), contribution. */
    static long[] components(List<LedgerAmount> rows) {
        long[] c = new long[6];
        for (LedgerAmount l : rows) {
            switch (l.systemKey()) {
                case "loan_interest_income" -> c[0] += l.amountMinor();
                case "loan_fee_income" -> c[1] += l.amountMinor();
                case "loan_penalty_income" -> c[2] += l.amountMinor();
                case "bad_debt_recovered" -> c[3] += l.amountMinor();
                case "loan_write_off_expense" -> c[4] -= l.amountMinor();
                default -> {}
            }
        }
        c[5] = c[0] + c[1] + c[2] + c[3] - c[4];
        return c;
    }

    private static RevenueRow row(String key, String label, long[] c, Long yieldBp) {
        return new RevenueRow(key, label, c[0], c[1], c[2], c[3], c[4], c[5], yieldBp);
    }

    // ---- Member activity --------------------------------------------------------------------

    @Transactional(readOnly = true)
    MembersResponse members(Scope s, String grainAsked) {
        String grain = grainAsked == null ? Periods.defaultGrain(s.from(), s.to()) : grainAsked;
        List<Period> periods = Periods.split(s.from(), s.to(), grain);
        ZoneId zone = zone();
        String cur = s.currency();
        List<DayTotal> fresh = queries.newMembersByDay(s, zone);
        long[] counts = new long[periods.size()];
        for (DayTotal d : fresh) {
            int i = Periods.indexOf(periods, d.date());
            if (i >= 0) {
                counts[i] += d.count();
            }
        }
        List<CountPoint> newMembers = new ArrayList<>();
        for (int i = 0; i < periods.size(); i++) {
            newMembers.add(new CountPoint(periods.get(i).start(), periods.get(i).label(), counts[i]));
        }
        long[] f = queries.funnel(s, zone);
        String[] stageKeys = {"applied", "appraised", "approved", "disbursed"};
        String[] stageLabels = {"Applied", "Appraised", "Approved", "Disbursed"};
        List<Stage> funnel = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            funnel.add(new Stage(
                    stageKeys[i],
                    stageLabels[i],
                    f[i],
                    i == 0 ? null : Metrics.bp(f[i], f[i - 1]),
                    Drill.of("applications", "stage", stageKeys[i])));
        }
        List<Slice> kyc = queries.kyc(s).stream()
                .map(r -> new Slice((String) r[0], words((String) r[0]), (Long) r[1]))
                .toList();
        long activeMembers = kyc.stream().mapToLong(Slice::count).sum();
        long verified = kyc.stream()
                .filter(k -> k.key().equals("verified"))
                .mapToLong(Slice::count)
                .sum();
        Map<String, Long> bands = new LinkedHashMap<>();
        for (String b : List.of("A", "B", "C", "D")) {
            bands.put(b, 0L);
        }
        queries.scoreBands(s, zone).forEach(r -> bands.put((String) r[0], (Long) r[1]));
        List<Slice> scoreBands = bands.entrySet().stream()
                .map(e -> new Slice(e.getKey(), "Band " + e.getKey(), e.getValue()))
                .toList();
        List<StaffRow> staff = queries.staffActivity(s, zone).stream()
                .map(r -> new StaffRow(
                        (UUID) r[0],
                        r[1] == null ? staffLabel((UUID) r[0]) : (String) r[1],
                        (Long) r[2],
                        (Long) r[3],
                        (Long) r[4],
                        (Long) r[5],
                        (Long) r[6]))
                .toList();
        long activeBorrowers =
                positions.live(s).stream().map(Position::memberId).distinct().count();
        List<Metric> metrics = List.of(
                Metrics.of("members.new_members", sum(fresh)[1], cur, Drill.of("new_members")),
                Metrics.of("members.active_borrowers", activeBorrowers, cur, Drill.of("active_loans")),
                Metrics.of("members.applied", f[0], cur, Drill.of("applications", "stage", "applied")),
                Metrics.of("members.appraised", f[1], cur, Drill.of("applications", "stage", "appraised")),
                Metrics.of("members.approved", f[2], cur, Drill.of("applications", "stage", "approved")),
                Metrics.of("members.disbursed", f[3], cur, Drill.of("applications", "stage", "disbursed")),
                Metrics.of(
                        "members.median_decision_hours",
                        queries.medianDecisionHours(s, zone),
                        cur,
                        Drill.of("applications", "stage", "approved")),
                Metrics.of(
                        "members.kyc_verified_share",
                        Metrics.bp(verified, activeMembers),
                        cur,
                        Drill.of("new_members")),
                Metrics.of("members.dormant", queries.dormant(s), cur, Drill.of("dormant_members")));
        return new MembersResponse(
                s.from(), s.to(), grain, metrics, newMembers, funnel, kyc, scoreBands, staff, clock.now());
    }

    // ---- Panels of other modules ------------------------------------------------------------

    @Transactional(readOnly = true)
    PanelsResponse panels(Scope s) {
        InsightsPanel.Filter filter = new InsightsPanel.Filter(s.from(), s.to(), s.branchIds(), s.officerId());
        List<Panel> out = new ArrayList<>();
        panels.orderedStream().forEach(panel -> {
            if (s.empty() || !panel.shown()) {
                return;
            }
            List<Metric> metrics = panel.metrics(filter).stream()
                    .map(m -> new Metric(m.key(), m.label(), m.kind(), m.value(), m.currency(), m.definition(), null))
                    .toList();
            if (!metrics.isEmpty()) {
                out.add(new Panel(panel.key(), panel.title(), metrics));
            }
        });
        return new PanelsResponse(out);
    }

    // ---- Tables and export ------------------------------------------------------------------

    @Transactional(readOnly = true)
    TableResponse table(String key, Scope s, Map<String, String> params) {
        return tables.table(key, s, params, zone(), TABLE_LIMIT);
    }

    /** The table as CSV; names masked for a caller without member read access; the export is audited. */
    @Transactional
    String export(String key, Scope s, Map<String, String> params) {
        TableResponse t = tables.table(key, s, params, zone(), EXPORT_LIMIT);
        boolean names = CurrentPrincipal.require().hasPermission(MEMBERS_READ);
        String csv = Csv.render(t, names);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("table", key);
        after.put("rows", t.rows().size());
        after.put("truncated", t.truncated());
        after.put("from", s.from().toString());
        after.put("to", s.to().toString());
        after.put("branch_ids", s.branchIds() == null ? "all" : s.branchIds().toString());
        after.put("officer_user_id", s.officerId());
        after.put("product_id", s.productId());
        after.put("names_masked", !names);
        Set<String> extra = new HashSet<>(params.keySet());
        extra.retainAll(
                Set.of("date", "status", "stage", "account", "min_dpd", "max_dpd", "bucket", "days", "group", "key"));
        extra.forEach(k -> after.put("param_" + k, params.get(k)));
        audit.record(new AuditLog.Entry("lending.insights.exported", "lending.insights", null, null, Map.of(), after));
        return csv;
    }

    static String words(String key) {
        String s = key.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @SafeVarargs
    private static <T> Map<T, T> linked(T... kv) {
        Map<T, T> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
