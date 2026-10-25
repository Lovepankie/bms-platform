package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.Window;
import com.rincoltech.bms.retail.reports.internal.ControlsApi.Card;
import com.rincoltech.bms.retail.reports.internal.ControlsApi.Group;
import com.rincoltech.bms.retail.reports.internal.ControlsApi.Groups;
import com.rincoltech.bms.retail.reports.internal.ControlsApi.Pattern;
import com.rincoltech.bms.retail.reports.internal.ControlsApi.Patterns;
import com.rincoltech.bms.retail.reports.internal.ControlsApi.Period;
import com.rincoltech.bms.retail.reports.internal.ControlsApi.Records;
import com.rincoltech.bms.retail.reports.internal.ControlsApi.Summary;
import com.rincoltech.bms.retail.reports.internal.ControlsRepository.Count;
import com.rincoltech.bms.retail.reports.internal.ControlsRepository.Event;
import com.rincoltech.bms.retail.reports.internal.ControlsRepository.ItemCell;
import com.rincoltech.bms.retail.reports.internal.ControlsRepository.PersonCell;
import com.rincoltech.bms.retail.reports.internal.ControlsRepository.Sales;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
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
 * The Controls page (issue #357, group 1; ADR-047): cards with the trend against the period before, grouped tables with
 * a rate per 100 sales, the individual records behind a group, and the repeat patterns (a person or an item whose rate
 * is more than twice the shop's own). Everything is read-only and under the caller's {@code retail.controls.read}
 * branch scope; the one figure judged against cost needs {@code retail.profit.read} in every branch covered.
 */
@Service
class ControlsService {

    static final String PERMISSION = "retail.controls.read";
    static final int DEFAULT_WITHIN_MINUTES = 10;
    static final int MAX_WITHIN_MINUTES = 120;
    static final int GROUP_PAGE = 100;
    static final int RECORD_PAGE = 50;
    /** A rate is called a pattern only from this many events and this many sales (or lines), so small numbers do not shout. */
    static final int MIN_EVENTS = 3;

    static final int MIN_OUT_OF = 10;
    /**
     * The metrics somebody approved: a void, a return, a write-off, a restock voided. A price, a cost and a correction have no
     * approver.
     */
    static final List<String> HAS_APPROVER =
            List.of("voided_sales", "quick_cancels", "returns", "credit_writeoffs", "restock_voids");

    /** The one label for a person the users table does not know, on the server and in the screen. */
    static final String UNKNOWN_PERSON = "Unknown person";

    static final int MAX_PATTERNS = 20;
    static final BigDecimal TWICE = BigDecimal.valueOf(2);

    /** The cards that need no cost, in the order the page draws them. */
    private static final List<String> CARDS = List.of(
            "voided_sales",
            "quick_cancels",
            "returns",
            "credit_writeoffs",
            "price_overrides",
            "discounts",
            "restock_voids",
            "quantity_corrections");

    private final ControlsRepository repo;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final RetailCapabilities capabilities;

    ControlsService(
            ControlsRepository repo, CurrentTenant tenant, BusinessClock clock, RetailCapabilities capabilities) {
        this.repo = repo;
        this.tenant = tenant;
        this.clock = clock;
        this.capabilities = capabilities;
    }

    @Transactional(readOnly = true)
    Summary summary(List<UUID> branchIds, LocalDate from, LocalDate to, Integer withinParam) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter(PERMISSION, branchIds);
        int within = within(withinParam);
        Window w =
                AnalyticsSupport.window(from, to, clock.today(tenant.profile().timezone()));
        long days = ChronoUnit.DAYS.between(w.from(), w.to()) + 1;
        LocalDate prevTo = w.from().minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);
        boolean cost = AnalyticsSupport.profitVisible(principal, filter);
        List<String> metrics = new ArrayList<>(CARDS);
        if (cost) {
            metrics.add("below_cost");
        }
        String currency = tenant.profile().currency();
        if (filter != null && filter.isEmpty()) {
            Period none = new Period(w.from(), w.to(), 0, 0);
            return new Summary(currency, none, new Period(prevFrom, prevTo, 0, 0), within, cost, List.of());
        }
        ControlsRepository.Window now = window(w.from(), w.to(), filter, within, null, null, null);
        ControlsRepository.Window before = window(prevFrom, prevTo, filter, within, null, null, null);
        Sales salesNow = repo.sales(now);
        Sales salesBefore = repo.sales(before);
        Map<String, Count> countsNow = byMetric(repo.counts(metrics, now));
        Map<String, Count> countsBefore = byMetric(repo.counts(metrics, before));
        List<Card> cards = new ArrayList<>();
        for (String metric : metrics) {
            Count a = countsNow.getOrDefault(metric, new Count(metric, 0, 0, 0));
            Count b = countsBefore.getOrDefault(metric, new Count(metric, 0, 0, 0));
            cards.add(new Card(
                    metric,
                    tracked(metric) || a.count() > 0 || b.count() > 0,
                    a.count(),
                    a.salesAffected(),
                    a.valueMinor(),
                    b.count(),
                    b.valueMinor(),
                    stock(metric) ? null : rate(a.salesAffected(), salesNow.count()),
                    stock(metric) ? null : rate(b.salesAffected(), salesBefore.count()),
                    stock(metric) ? null : shareBp(a.valueMinor(), salesNow.totalMinor())));
        }
        return new Summary(
                currency,
                new Period(w.from(), w.to(), salesNow.count(), salesNow.totalMinor()),
                new Period(prevFrom, prevTo, salesBefore.count(), salesBefore.totalMinor()),
                within,
                cost,
                cards);
    }

    @Transactional(readOnly = true)
    Groups groups(
            String metric,
            String groupBy,
            String level,
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID userId,
            UUID approverId,
            String reason,
            Integer withinParam,
            Integer offsetParam) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter(PERMISSION, branchIds);
        requireMetric(metric, principal, filter);
        List<String> chain = chain(groupBy);
        if (chain.contains("approver") && !HAS_APPROVER.contains(metric)) {
            throw ApiException.validation(
                    List.of(
                            new FieldProblem(
                                    "group_by",
                                    "invalid",
                                    "approver is a level of voided_sales, quick_cancels, returns, credit_writeoffs and restock_voids.")));
        }
        String at = level == null ? chain.getFirst() : level;
        if (!chain.contains(at)) {
            throw ApiException.validation(List.of(new FieldProblem("level", "invalid", "level is one of group_by.")));
        }
        int offset = offsetParam == null ? 0 : offsetParam;
        if (offset < 0) {
            throw ApiException.validation(List.of(new FieldProblem("offset", "invalid", "offset is zero or more.")));
        }
        int index = chain.indexOf(at);
        String next = index + 1 < chain.size() ? chain.get(index + 1) : null;
        String currency = tenant.profile().currency();
        if (filter != null && filter.isEmpty()) {
            return new Groups(metric, groupBy, at, chain, next, currency, List.of(), false, null);
        }
        Window w =
                AnalyticsSupport.window(from, to, clock.today(tenant.profile().timezone()));
        ControlsRepository.Window q = window(
                w.from(),
                w.to(),
                filter,
                within(withinParam),
                userId,
                approverId,
                reason == null ? null : reason.toLowerCase(java.util.Locale.ROOT));
        List<Group> rows = repo.groups(metric, at, q, offset, GROUP_PAGE + 1).stream()
                .map(c -> new Group(
                        c.key(),
                        labelOf(at, c.label()),
                        c.userStatus(),
                        c.day(),
                        c.count(),
                        c.salesAffected(),
                        c.valueMinor(),
                        c.salesCount(),
                        rate(c.salesAffected(), c.salesCount())))
                .toList();
        boolean more = rows.size() > GROUP_PAGE;
        return new Groups(
                metric,
                groupBy,
                at,
                chain,
                next,
                currency,
                more ? rows.subList(0, GROUP_PAGE) : rows,
                more,
                more ? offset + GROUP_PAGE : null);
    }

    @Transactional(readOnly = true)
    Records records(
            String metric,
            List<UUID> branchIds,
            LocalDate from,
            LocalDate to,
            UUID userId,
            UUID approverId,
            String reason,
            Integer withinParam,
            Integer offsetParam) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter(PERMISSION, branchIds);
        requireMetric(metric, principal, filter);
        int offset = offsetParam == null ? 0 : offsetParam;
        if (offset < 0) {
            throw ApiException.validation(List.of(new FieldProblem("offset", "invalid", "offset is zero or more.")));
        }
        String currency = tenant.profile().currency();
        if (filter != null && filter.isEmpty()) {
            return new Records(metric, currency, List.of(), false, null);
        }
        Window w =
                AnalyticsSupport.window(from, to, clock.today(tenant.profile().timezone()));
        ControlsRepository.Window q = window(
                w.from(),
                w.to(),
                filter,
                within(withinParam),
                userId,
                approverId,
                reason == null ? null : reason.toLowerCase(java.util.Locale.ROOT));
        List<Event> events = repo.events(metric, q, offset, RECORD_PAGE + 1);
        boolean more = events.size() > RECORD_PAGE;
        List<ControlsApi.Entry> items = (more ? events.subList(0, RECORD_PAGE) : events)
                .stream()
                        .map(e -> new ControlsApi.Entry(
                                e.saleId(),
                                e.saleNo(),
                                e.ref(),
                                e.at(),
                                e.branchId(),
                                e.branchName(),
                                e.userId(),
                                e.userName(),
                                e.userStatus(),
                                e.approverId(),
                                e.approverName(),
                                e.approverStatus(),
                                e.reason(),
                                e.item(),
                                e.valueMinor()))
                        .toList();
        return new Records(metric, currency, items, more, more ? offset + RECORD_PAGE : null);
    }

    /**
     * People and items whose rate is more than twice the rate of their own shop, with at least {@link #MIN_EVENTS} sales
     * with an event and {@link #MIN_OUT_OF} sales (or lines) behind it. A person is the one whose sales were cancelled,
     * returned or repriced (never the one who cancelled them), and their rate is the sales with such an event over the
     * sales they entered in that shop; the strip is ordered by shop and name, not by how far anyone stands out;
     * an item's is over the lines it sold there, by the date of the sale. The comparison is made on whole numbers
     * (events times the shop's sales against twice the shop's events times the person's), so no rounding decides it.
     */
    @Transactional(readOnly = true)
    Patterns patterns(List<UUID> branchIds, LocalDate from, LocalDate to) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter(PERMISSION, branchIds);
        Window w =
                AnalyticsSupport.window(from, to, clock.today(tenant.profile().timezone()));
        if (filter != null && filter.isEmpty()) {
            return new Patterns(w.from(), w.to(), MIN_EVENTS, MIN_OUT_OF, List.of());
        }
        boolean cost = AnalyticsSupport.profitVisible(principal, filter);
        ControlsRepository.Window q = window(w.from(), w.to(), filter, DEFAULT_WITHIN_MINUTES, null, null, null);
        List<Pattern> found = new ArrayList<>();

        List<String> personMetrics = new ArrayList<>(List.of("voided_sales", "returns", "price_overrides"));
        if (cost) {
            personMetrics.add("below_cost");
        }
        List<PersonCell> people = repo.personCells(personMetrics, q);
        Map<String, Long> shopEvents = new LinkedHashMap<>();
        for (PersonCell c : people) {
            shopEvents.merge(c.branchId() + "|" + c.metric(), c.count(), Long::sum);
        }
        Map<UUID, Long> shopSales = repo.salesByBranch(q);
        for (PersonCell c : people) {
            long events = shopEvents.get(c.branchId() + "|" + c.metric());
            long sales = shopSales.getOrDefault(c.branchId(), 0L);
            if (flagged(c.count(), c.sales(), events, sales)) {
                found.add(new Pattern(
                        "user",
                        String.valueOf(c.userId()),
                        c.userName() == null ? UNKNOWN_PERSON : c.userName(),
                        c.userStatus(),
                        c.branchId(),
                        c.branchName(),
                        c.metric(),
                        c.count(),
                        c.sales(),
                        rate(c.count(), c.sales()),
                        rate(events, sales),
                        times(c.count(), c.sales(), events, sales)));
            }
        }

        List<ItemCell> items = repo.itemCells(cost, q);
        Map<String, long[]> shopLines = new LinkedHashMap<>();
        for (ItemCell c : items) {
            long[] t = shopLines.computeIfAbsent(c.branchId() + "|" + c.metric(), k -> new long[2]);
            t[0] += c.count();
            t[1] += c.lines();
        }
        for (ItemCell c : items) {
            long[] t = shopLines.get(c.branchId() + "|" + c.metric());
            if (c.metric().equals("below_cost") && !cost) {
                continue;
            }
            if (flagged(c.count(), c.lines(), t[0], t[1])) {
                found.add(new Pattern(
                        "product",
                        String.valueOf(c.productId()),
                        c.name(),
                        null,
                        c.branchId(),
                        c.branchName(),
                        c.metric(),
                        c.count(),
                        c.lines(),
                        rate(c.count(), c.lines()),
                        rate(t[0], t[1]),
                        times(c.count(), c.lines(), t[0], t[1])));
            }
        }
        // By shop, then by name: a strip of things to look at, never a ranking of people by how far they stand out.
        Comparator<String> byText = Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
        found.sort(Comparator.comparing(Pattern::branchName, byText)
                .thenComparing(Pattern::subject)
                .thenComparing(Pattern::name, byText)
                .thenComparing(Pattern::metric)
                .thenComparing(Pattern::key));
        return new Patterns(
                w.from(),
                w.to(),
                MIN_EVENTS,
                MIN_OUT_OF,
                found.size() > MAX_PATTERNS ? found.subList(0, MAX_PATTERNS) : found);
    }

    /** True when the rate count/outOf is more than twice the shop's own, from enough events over enough sales. */
    static boolean flagged(long count, long outOf, long shopCount, long shopOutOf) {
        if (count < MIN_EVENTS || outOf < MIN_OUT_OF || shopOutOf <= 0 || shopCount <= 0) {
            return false;
        }
        // count/outOf > 2 * shopCount/shopOutOf, without division.
        return BigDecimal.valueOf(count)
                        .multiply(BigDecimal.valueOf(shopOutOf))
                        .compareTo(TWICE.multiply(BigDecimal.valueOf(shopCount)).multiply(BigDecimal.valueOf(outOf)))
                > 0;
    }

    /** How many times the shop's rate, one decimal, half up. */
    static String times(long count, long outOf, long shopCount, long shopOutOf) {
        return BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(shopOutOf))
                .divide(BigDecimal.valueOf(shopCount).multiply(BigDecimal.valueOf(outOf)), 1, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** Per 100, one decimal, half up; null when there is nothing to divide by. */
    static String rate(long count, long outOf) {
        if (outOf <= 0) {
            return null;
        }
        return BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(outOf), 1, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** The value over the total in basis points, half up; capped at the largest int so a huge value never blanks the summary. */
    static Integer shareBp(long value, long total) {
        if (total <= 0) {
            return null;
        }
        BigDecimal bp = BigDecimal.valueOf(value)
                .multiply(BigDecimal.valueOf(10_000))
                .divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP);
        return bp.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 ? Integer.MAX_VALUE : bp.intValue();
    }

    /** A restock voided or a quantity corrected: not about sales, so counted without a rate. */
    private static boolean stock(String metric) {
        return ControlsRepository.STOCK_METRICS.contains(metric);
    }

    /** Whether the capability a card depends on is on; the cards of the sales themselves always are. */
    private boolean tracked(String metric) {
        return switch (metric) {
            case "returns" -> capabilities.enabled(RetailCapabilities.RETURNS);
            case "price_overrides", "discounts" -> capabilities.enabled(RetailCapabilities.PRICE_LISTS);
            default -> true;
        };
    }

    private ControlsRepository.Window window(
            LocalDate from, LocalDate to, List<UUID> filter, int within, UUID userId, UUID approverId, String reason) {
        return new ControlsRepository.Window(
                from, to, tenant.profile().timezone(), filter, within, userId, approverId, reason);
    }

    private static Map<String, Count> byMetric(List<Count> counts) {
        Map<String, Count> out = new LinkedHashMap<>();
        counts.forEach(c -> out.put(c.metric(), c));
        return out;
    }

    private static int within(Integer minutes) {
        int n = minutes == null ? DEFAULT_WITHIN_MINUTES : minutes;
        if (n < 1 || n > MAX_WITHIN_MINUTES) {
            throw ApiException.validation(
                    List.of(new FieldProblem("within_minutes", "invalid", "within_minutes is from 1 to 120.")));
        }
        return n;
    }

    private static void requireMetric(String metric, Principal principal, List<UUID> filter) {
        if (metric == null || !ControlsRepository.METRICS.contains(metric)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "metric",
                    "invalid",
                    "metric is voided_sales, quick_cancels, returns, credit_writeoffs, price_overrides,"
                            + " discounts, below_cost, restock_voids or quantity_corrections.")));
        }
        if (metric.equals("below_cost") && !AnalyticsSupport.profitVisible(principal, filter)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "Sales below cost are for people who may read profit in every branch shown.");
        }
    }

    /** The chain of {@code group_by}: known levels only, each once, in the order given. */
    static List<String> chain(String groupBy) {
        List<String> chain = groupBy == null
                ? List.of()
                : Arrays.stream(groupBy.split(",", -1)).map(String::trim).toList();
        Set<String> seen = new HashSet<>();
        boolean ok = !chain.isEmpty();
        for (String level : chain) {
            ok &= ControlsRepository.LEVELS.contains(level) && seen.add(level);
        }
        if (!ok) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "group_by", "invalid", "group_by is a list of branch, user, day, reason or approver, each once.")));
        }
        return chain;
    }

    private static String labelOf(String level, String label) {
        if (label != null && !label.isBlank()) {
            return label;
        }
        return switch (level) {
            case "branch" -> "Unknown shop";
            case "user", "approver" -> UNKNOWN_PERSON;
            case "reason" -> "No reason recorded";
            default -> "";
        };
    }
}
