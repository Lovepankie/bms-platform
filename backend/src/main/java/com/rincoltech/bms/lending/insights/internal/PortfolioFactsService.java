package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.lending.insights.FactDisplay;
import com.rincoltech.bms.lending.insights.InsightsFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParBucket;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroup;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroupBy;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import com.rincoltech.bms.lending.insights.PortfolioFacts;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Bucket;
import com.rincoltech.bms.lending.insights.internal.InsightsApi.Metric;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PortfolioFacts} over the insights code (ADR-037 decision 3). The figures are the ones the insights endpoints
 * return for the same scope ({@link InsightsService#brief}, {@code portfolio}, {@code revenue} and {@code members}, and
 * {@link InsightsFigures#par} for the branch and product groups), so there is one implementation of every number and
 * the scope rules are the endpoints' own. This class adds three things and no arithmetic of its own beyond a
 * difference:
 *
 * <ol>
 *   <li>the changes: the same figures read again for the range a week earlier and a month earlier (a stock from the
 *       nightly snapshot of that date, a flow from the transactions of that range), and the difference taken;
 *   <li>the suppression of small counts: a figure is hidden when the loans or borrowers it is <b>actually computed over</b>
 *       number one to four (arrears over the loans in arrears, PAR 30 over the loans more than 30 days late, collected over
 *       the loans that paid, a revenue line over the loans behind its ledger lines, write-offs over the loans written off),
 *       and so is every figure that would give it back by subtraction from the ones shown (the contribution when any of its
 *       components is hidden, the collection rate when collected is hidden). A change is hidden with its figure, and when the
 *       earlier figure was hidden;
 *   <li>the groups: branches and products by id, never by name in a fact (the names travel apart in
 *       {@link FactSet#names()}).
 * </ol>
 *
 * <p>The business date is the scope's: a scope resolved for "today" and read after midnight reads the snapshot of the day
 * it was resolved for, and is refused with 422 {@code snapshot_unavailable} when that night's snapshot has not been
 * written, because the live loan tables by then hold the next day's activity.
 *
 * <p>A residual risk that the acknowledgement states: when some groups are suppressed and others shown, the suppressed
 * ones' share is the total minus the shown groups. A model asked not to compute is not an attacker, and the same figures
 * are on the insights page for the people allowed to see them.
 */
@Service
class PortfolioFactsService implements PortfolioFacts {

    /** The most groups per dimension in the facts: the largest by loans, so that a prompt stays small. */
    static final int MAX_GROUPS = 10;

    // Populations that are not themselves a figure of the dictionary.
    private static final String ACTIVE = "portfolio.active_loans";
    private static final String PAYERS = "loans.paying";
    private static final String PAYERS_TODAY = "loans.paying_today";
    // Today sits inside the range: the range figure less today's is the figure of the OTHER days, and the population of
    // that
    // difference is the distinct loans with activity on a day other than today (not the transactions: one loan paying
    // six
    // times on other days is one borrower, whose six payments the difference would give away).
    private static final String OTHER_DISBURSED = "loans.disbursed_other_days";
    private static final String OTHER_PAYING = "loans.paying_other_days";
    private static final String OTHER_DUE = "loans.due_other_days";
    static final List<String> PAR_KEYS =
            List.of("portfolio.par1", "portfolio.par30", "portfolio.par60", "portfolio.par90");
    private static final String RECOVERERS = "loans.recovering";
    private static final String IN_ARREARS = "loans.in_arrears";
    private static final String OVER_30 = "loans.over_30";
    private static final String OVER_60 = "loans.over_60";
    private static final String OVER_90 = "loans.over_90";
    static final List<String> PAR_LOANS = List.of(IN_ARREARS, OVER_30, OVER_60, OVER_90);
    private static final String REV_INTEREST = "loans.revenue.loan_interest_income";
    private static final String REV_FEES = "loans.revenue.loan_fee_income";
    private static final String REV_PENALTIES = "loans.revenue.loan_penalty_income";
    private static final String REV_RECOVERED = "loans.revenue.bad_debt_recovered";
    private static final String REV_WRITE_OFF = "loans.revenue.loan_write_off_expense";

    /**
     * How a figure is picked: its dictionary key, polarity, whether it has changes, the populations it is computed over,
     * and the other figures that would give it away (or that it would give away) if it were shown alone.
     */
    record Spec(
            String key, Polarity polarity, boolean delta, boolean stock, List<String> populations, List<String> deps) {}

    private static Spec spec(String key, Polarity polarity, boolean delta, boolean stock, String... populations) {
        return new Spec(key, polarity, delta, stock, List.of(populations), List.of());
    }

    private static Spec with(Spec s, String... deps) {
        return new Spec(s.key(), s.polarity(), s.delta(), s.stock(), s.populations(), List.of(deps));
    }

    private static final Polarity BETTER = Polarity.HIGHER_IS_BETTER;
    private static final Polarity WORSE = Polarity.HIGHER_IS_WORSE;
    private static final Polarity NEUTRAL = Polarity.NEUTRAL;

    private static final List<Spec> BRIEF = List.of(
            spec("brief.disbursed_today", BETTER, true, false, "brief.disbursed_loans_today"),
            spec("brief.disbursed_loans_today", BETTER, true, false, "brief.disbursed_loans_today"),
            spec("brief.collected_today", BETTER, true, false, ACTIVE, PAYERS_TODAY),
            spec("brief.expected_today", NEUTRAL, true, false, ACTIVE),
            with(
                    spec("brief.collection_rate_today", BETTER, true, false, ACTIVE),
                    "brief.collected_today",
                    "brief.expected_today"),
            spec("brief.new_arrears", WORSE, false, false, "brief.new_arrears"),
            spec("brief.new_arrears_amount", WORSE, false, false, "brief.new_arrears"),
            spec("brief.going_bad", WORSE, false, false, "brief.going_bad"),
            spec("brief.going_bad_principal", WORSE, false, false, "brief.going_bad"));

    private static final List<Spec> PORTFOLIO = List.of(
            spec("portfolio.principal_outstanding", NEUTRAL, true, true, ACTIVE),
            spec("portfolio.active_loans", NEUTRAL, true, true, ACTIVE),
            spec("portfolio.borrowers", NEUTRAL, true, true, "portfolio.borrowers"),
            spec("portfolio.arrears", WORSE, true, true, IN_ARREARS),
            spec("portfolio.par1", WORSE, true, true, ACTIVE, IN_ARREARS),
            spec("portfolio.par30", WORSE, true, true, ACTIVE, OVER_30),
            spec("portfolio.par60", WORSE, true, true, ACTIVE, OVER_60),
            spec("portfolio.par90", WORSE, true, true, ACTIVE, OVER_90),
            spec("portfolio.disbursed", BETTER, true, false, "portfolio.disbursed_count", OTHER_DISBURSED),
            spec("portfolio.disbursed_count", BETTER, true, false, "portfolio.disbursed_count", OTHER_DISBURSED),
            spec("portfolio.collected", BETTER, true, false, ACTIVE, PAYERS, OTHER_PAYING),
            spec("portfolio.expected", NEUTRAL, true, false, ACTIVE, OTHER_DUE),
            with(
                    spec("portfolio.collection_rate", BETTER, true, false, ACTIVE),
                    "portfolio.collected",
                    "portfolio.expected"),
            spec("portfolio.forecast_7", NEUTRAL, false, false, ACTIVE),
            spec("portfolio.forecast_30", NEUTRAL, false, false, ACTIVE),
            with(
                    spec("portfolio.written_off", WORSE, true, false, "portfolio.written_off_count"),
                    "revenue.write_off_expense"),
            spec("portfolio.written_off_count", WORSE, true, false, "portfolio.written_off_count"),
            spec("portfolio.recovered", BETTER, true, false, RECOVERERS, REV_RECOVERED),
            spec("portfolio.repeat_share", BETTER, true, false, "portfolio.disbursed_count"),
            with(spec("portfolio.yield", BETTER, true, false, ACTIVE), "revenue.interest", "revenue.fees"));

    private static final List<Spec> REVENUE = List.of(
            spec("revenue.interest", BETTER, true, false, REV_INTEREST),
            spec("revenue.fees", BETTER, true, false, REV_FEES),
            spec("revenue.penalties", NEUTRAL, true, false, REV_PENALTIES),
            spec("revenue.recovered", BETTER, true, false, RECOVERERS, REV_RECOVERED),
            with(spec("revenue.write_off_expense", WORSE, true, false, REV_WRITE_OFF), "portfolio.written_off_count"),
            with(
                    spec("revenue.contribution", BETTER, true, false, ACTIVE),
                    "revenue.interest",
                    "revenue.fees",
                    "revenue.penalties",
                    "revenue.recovered",
                    "revenue.write_off_expense"));

    private static final List<Spec> MEMBERS = List.of(
            spec("members.new_members", BETTER, true, false, "members.new_members"),
            spec("members.applied", NEUTRAL, true, false, "members.applied"),
            spec("members.approved", NEUTRAL, true, false, "members.approved"),
            spec("members.disbursed", BETTER, true, false, "members.disbursed"),
            spec("members.median_decision_hours", WORSE, true, false, "members.approved"),
            spec("members.dormant", WORSE, false, false, "members.dormant"));

    static final Map<String, Spec> BY_KEY = new LinkedHashMap<>();

    static {
        for (List<Spec> section : List.of(BRIEF, PORTFOLIO, REVENUE, MEMBERS)) {
            section.forEach(s -> BY_KEY.put(s.key(), s));
        }
    }

    private final InsightsService insights;
    private final InsightsQueries queries;
    private final Positions positions;
    private final InsightsFigures figures;
    private final CurrentTenant tenant;

    PortfolioFactsService(
            InsightsService insights,
            InsightsQueries queries,
            Positions positions,
            InsightsFigures figures,
            CurrentTenant tenant) {
        this.insights = insights;
        this.queries = queries;
        this.positions = positions;
        this.figures = figures;
        this.tenant = tenant;
    }

    @Override
    @Transactional(readOnly = true)
    public FactSet forCaller() {
        // The caller's scope, resolved by the insights code itself: permission, branches, own loans.
        return build(figures.scope(null, null, null, null, null));
    }

    /** One window of figures: every value by dictionary key, and whether the stock figures could be read. */
    static final class Window {
        final Map<String, Long> values = new HashMap<>();
        final Set<String> present = new HashSet<>();
        boolean stockKnown = true;

        void put(String key, Long value) {
            values.put(key, value);
            present.add(key);
        }

        boolean has(Spec s) {
            return present.contains(s.key()) && (!s.stock() || stockKnown);
        }

        /** True when the two counts differ by one to four: the loans between them are exactly those few. */
        static boolean diffSmall(long outer, long inner) {
            long d = outer - inner;
            return d > 0 && d < MIN_GROUP;
        }

        private long loans(int level) {
            Long n = values.get(PAR_LOANS.get(level));
            return n == null ? 0 : n;
        }

        /**
         * The PAR levels are a partition of the late loans (1 to 30 days, 31 to 60, 61 to 90, over 90): the difference of two
         * shown levels times the principal is the principal of the loans between them. A level is hidden when it is small
         * itself, or when a shown level before it differs from it by one to four loans.
         */
        boolean parHidden(int level) {
            long n = loans(level);
            if (n > 0 && n < MIN_GROUP) {
                return true;
            }
            for (int j = 0; j < level; j++) {
                if (!parHidden(j) && diffSmall(loans(j), n)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * True when the loans behind this figure in the two windows differ by one to four: the change would then be the
         * figure of those few loans (write-offs of 6 loans now and 5 a week earlier give "+1 loan" and the amount of exactly
         * one loan). Checked for the populations of the figure and of every figure it would give away. The populations of
         * "the other days" are properties of one window and are not compared across windows.
         */
        boolean differsSmall(Window before, Spec s) {
            for (String p : s.populations()) {
                if (p.endsWith("_other_days")) {
                    continue;
                }
                Long n = values.get(p);
                Long m = before.values.get(p);
                if (n != null && m != null && diffSmall(Math.max(n, m), Math.min(n, m))) {
                    return true;
                }
            }
            for (String dep : s.deps()) {
                Spec d = BY_KEY.get(dep);
                if (d != null && differsSmall(before, d)) {
                    return true;
                }
            }
            return false;
        }

        /** True when a population of this figure, or of a figure it would give away, is one to four. */
        boolean small(Spec s) {
            for (String p : s.populations()) {
                Long n = values.get(p);
                if (n != null && n > 0 && n < MIN_GROUP) {
                    return true;
                }
            }
            int level = PAR_KEYS.indexOf(s.key());
            if (level >= 0 && parHidden(level)) {
                return true;
            }
            for (String dep : s.deps()) {
                Spec d = BY_KEY.get(dep);
                if (d != null && small(d)) {
                    return true;
                }
            }
            return false;
        }
    }

    private Window read(Scope scope, boolean withBrief) {
        Window w = new Window();
        if (withBrief) {
            // The brief is the business date's: in an earlier window "today" is that window's last day.
            Scope day = new Scope(
                    scope.from(),
                    scope.to(),
                    scope.to(),
                    scope.branchIds(),
                    scope.officerId(),
                    scope.productId(),
                    scope.currency());
            for (Metric m : insights.brief(day).metrics()) {
                w.put(m.key(), m.value());
            }
            Scope dayOnly = day.withRange(day.to(), day.to());
            w.put(PAYERS_TODAY, queries.loansWithTransactions(dayOnly, InsightsQueries.COLLECTED_TYPES));
            LocalDate today = day.to();
            w.put(OTHER_DISBURSED, queries.loansWithTransactionsOutsideDay(scope, "('disbursement')", today));
            w.put(OTHER_PAYING, queries.loansWithTransactionsOutsideDay(scope, InsightsQueries.COLLECTED_TYPES, today));
            w.put(OTHER_DUE, queries.loansDueOutsideDay(scope, today));
        }
        InsightsApi.PortfolioResponse portfolio = insights.portfolio(scope, null);
        for (Metric m : portfolio.metrics()) {
            w.put(m.key(), m.value());
        }
        w.stockKnown = !"none".equals(portfolio.stockSource());
        // The loans behind the risk figures, from the ageing buckets of the same stock.
        long inArrears = 0;
        long over30 = 0;
        long over60 = 0;
        long over90 = 0;
        for (Bucket b : portfolio.ageing()) {
            switch (b.key()) {
                case "1_30" -> inArrears += b.loans();
                case "31_60" -> {
                    inArrears += b.loans();
                    over30 += b.loans();
                }
                case "61_90" -> {
                    inArrears += b.loans();
                    over30 += b.loans();
                    over60 += b.loans();
                }
                case "over_90" -> {
                    inArrears += b.loans();
                    over30 += b.loans();
                    over60 += b.loans();
                    over90 += b.loans();
                }
                default -> {}
            }
        }
        w.put(IN_ARREARS, inArrears);
        w.put(OVER_30, over30);
        w.put(OVER_60, over60);
        w.put(OVER_90, over90);
        for (Metric m : insights.revenue(scope).metrics()) {
            w.put(m.key(), m.value());
        }
        Map<String, Long> behind = queries.loansBehindRevenue(scope, scope.from(), scope.to());
        w.put(REV_INTEREST, behind.getOrDefault("loan_interest_income", 0L));
        w.put(REV_FEES, behind.getOrDefault("loan_fee_income", 0L));
        w.put(REV_PENALTIES, behind.getOrDefault("loan_penalty_income", 0L));
        w.put(REV_RECOVERED, behind.getOrDefault("bad_debt_recovered", 0L));
        w.put(REV_WRITE_OFF, behind.getOrDefault("loan_write_off_expense", 0L));
        for (Metric m : insights.members(scope, null).metrics()) {
            w.put(m.key(), m.value());
        }
        w.put(PAYERS, queries.loansWithTransactions(scope, InsightsQueries.COLLECTED_TYPES));
        w.put(RECOVERERS, queries.loansWithTransactions(scope, "('recovery')"));
        return w;
    }

    private static Scope shifted(Scope s, UnaryOperator<LocalDate> by) {
        return new Scope(
                by.apply(s.from()),
                by.apply(s.to()),
                s.today(),
                s.branchIds(),
                s.officerId(),
                s.productId(),
                s.currency());
    }

    @Override
    @Transactional(readOnly = true)
    public FactSet build(PortfolioScope requested) {
        // The business date of the scope, against the business date now. A scope resolved for "today" and read after
        // midnight must not read the live loan tables, which by then hold the next day's activity (the report-runs
        // bug):
        // it reads the snapshot of the day it was resolved for, or is refused when tonight's job has not written it.
        LocalDate businessToday = insights.today();
        PortfolioScope p = requested;
        if (!requested.today().equals(businessToday)) {
            p = new PortfolioScope(
                    requested.from(),
                    requested.to(),
                    businessToday,
                    requested.branchIds(),
                    requested.officerId(),
                    requested.productId());
        }
        if (!p.to().equals(p.today()) && !positions.snapshotTaken(p.to())) {
            throw ApiException.rule(
                    "snapshot_unavailable",
                    "No snapshot of the loan book exists for " + p.to()
                            + " yet. Try again after the nightly snapshot.");
        }
        String currency = tenant.profile().currency();
        Scope now = new Scope(p.from(), p.to(), p.today(), p.branchIds(), p.officerId(), p.productId(), currency);
        boolean brief = now.to().equals(now.today());
        Window current = read(now, brief);
        Window week = read(shifted(now, d -> d.minusDays(7)), brief);
        Window month = read(shifted(now, d -> d.minusMonths(1)), brief);

        List<Fact> facts = new ArrayList<>();
        List<List<Spec>> sections =
                brief ? List.of(BRIEF, PORTFOLIO, REVENUE, MEMBERS) : List.of(PORTFOLIO, REVENUE, MEMBERS);
        for (List<Spec> section : sections) {
            for (Spec s : section) {
                if (current.has(s)) {
                    facts.add(fact(s, currency, current, week, month));
                }
            }
        }
        List<GroupName> names = new ArrayList<>();
        groups(p, ParGroupBy.BRANCH, Dimension.BRANCH, "branch", currency, facts, names);
        groups(p, ParGroupBy.PRODUCT, Dimension.PRODUCT, "product", currency, facts, names);
        return new FactSet(now.to(), now.from(), now.to(), currency, facts, names);
    }

    private static Fact fact(Spec s, String currency, Window current, Window week, Window month) {
        Metrics.Definition def = Metrics.ALL.get(s.key());
        Kind kind = kind(def.kind());
        if (current.small(s)) {
            return new Fact(
                    s.key(), def.label(), kind, s.polarity(), null, "fewer than " + MIN_GROUP, true, null, null, null);
        }
        Long value = current.values.get(s.key());
        if (value == null) {
            return new Fact(s.key(), def.label(), kind, s.polarity(), null, "not available", false, null, null, null);
        }
        return new Fact(
                s.key(),
                def.label(),
                kind,
                s.polarity(),
                value,
                FactDisplay.of(kind, value, currency),
                false,
                s.delta() ? delta(s, kind, value, current, week, currency) : null,
                s.delta() ? delta(s, kind, value, current, month, currency) : null,
                null);
    }

    /**
     * The change against an earlier window, or null when that figure is unknown, covered a small population, or the loans
     * behind the two figures differ by one to four (the difference would then be those few loans' exact figure).
     */
    static Delta delta(Spec s, Kind kind, long value, Window current, Window before, String currency) {
        if (!before.has(s) || before.small(s) || current.small(s) || current.differsSmall(before, s)) {
            return null;
        }
        Long then = before.values.get(s.key());
        if (then == null) {
            return null;
        }
        long change = value - then;
        return new Delta(change, FactDisplay.change(kind, change, currency));
    }

    private static Kind kind(String dictionaryKind) {
        return switch (dictionaryKind) {
            case Metrics.MONEY -> Kind.MONEY;
            case Metrics.COUNT -> Kind.COUNT;
            case Metrics.BP -> Kind.BASIS_POINTS;
            case Metrics.DAYS -> Kind.DAYS;
            case Metrics.HOURS -> Kind.HOURS;
            default -> throw new IllegalArgumentException("unknown kind " + dictionaryKind);
        };
    }

    /**
     * The branch or product groups, from the public figures port: the largest first. A group of fewer than five loans is a
     * single suppressed count and nothing else, and a group's PAR 30 is hidden when fewer than five of its loans are more
     * than 30 days late.
     *
     * <p>Complementary suppression (ADR-037, spec 5.13): the groups partition the book, so the total less the groups shown is
     * what is hidden or left out. If that remainder is one to four loans it is one small group's exact count, principal and
     * risk, recoverable by subtraction; so the smallest other groups are hidden too, one after another, until the remainder
     * is five loans or more (or nothing). The same is done for the loans more than 30 days late behind the PAR 30 of the
     * groups shown.
     */
    private void groups(
            PortfolioScope scope,
            ParGroupBy by,
            Dimension dimension,
            String prefix,
            String currency,
            List<Fact> facts,
            List<GroupName> names) {
        ParFigures par = figures.par(scope, by, () -> {});
        List<ParGroup> all = par.groups().stream()
                .sorted(Comparator.comparingLong(ParGroup::loans).reversed().thenComparing(ParGroup::key))
                .toList();
        List<ParGroup> listed = all.stream().limit(MAX_GROUPS).toList();

        // The loans partition: groups of fewer than five loans and groups left out make up the remainder.
        Set<String> hidden = new HashSet<>();
        long remainder =
                all.stream().skip(MAX_GROUPS).mapToLong(ParGroup::loans).sum();
        for (ParGroup g : listed) {
            if (g.loans() < MIN_GROUP) {
                hidden.add(g.key());
                remainder += g.loans();
            }
        }
        List<ParGroup> smallestFirst = listed.stream()
                .filter(g -> !hidden.contains(g.key()))
                .sorted(Comparator.comparingLong(ParGroup::loans).thenComparing(ParGroup::key))
                .toList();
        for (ParGroup g : smallestFirst) {
            if (remainder == 0 || remainder >= MIN_GROUP) {
                break;
            }
            hidden.add(g.key());
            remainder += g.loans();
        }

        // The late partition: the loans more than 30 days late of the groups whose PAR 30 is not shown.
        long totalLate = all.stream().mapToLong(PortfolioFactsService::late).sum();
        Set<String> parHidden = new HashSet<>();
        long shownLate = 0;
        for (ParGroup g : listed) {
            long late = late(g);
            if (hidden.contains(g.key())) {
                continue;
            }
            if (late > 0 && late < MIN_GROUP) {
                parHidden.add(g.key());
            } else {
                shownLate += late;
            }
        }
        long lateRemainder = totalLate - shownLate;
        List<ParGroup> leastLate = listed.stream()
                .filter(g -> !hidden.contains(g.key()) && !parHidden.contains(g.key()) && late(g) > 0)
                .sorted(Comparator.comparingLong(PortfolioFactsService::late).thenComparing(ParGroup::key))
                .toList();
        for (ParGroup g : leastLate) {
            if (lateRemainder == 0 || lateRemainder >= MIN_GROUP) {
                break;
            }
            parHidden.add(g.key());
            lateRemainder += late(g);
        }

        for (ParGroup g : listed) {
            Group group = new Group(dimension, g.key());
            names.add(new GroupName(group, g.label()));
            if (hidden.contains(g.key())) {
                facts.add(hiddenFact(prefix + ".loans", "Active loans", Kind.COUNT, NEUTRAL, group));
                continue;
            }
            facts.add(new Fact(
                    prefix + ".loans",
                    "Active loans",
                    Kind.COUNT,
                    NEUTRAL,
                    g.loans(),
                    FactDisplay.count(g.loans()),
                    false,
                    null,
                    null,
                    group));
            facts.add(new Fact(
                    prefix + ".principal_outstanding",
                    "Principal outstanding",
                    Kind.MONEY,
                    NEUTRAL,
                    g.principalOutstandingMinor(),
                    FactDisplay.money(g.principalOutstandingMinor(), currency),
                    false,
                    null,
                    null,
                    group));
            if (parHidden.contains(g.key())) {
                facts.add(hiddenFact(prefix + ".par30", "PAR 30", Kind.BASIS_POINTS, WORSE, group));
            } else {
                facts.add(new Fact(
                        prefix + ".par30",
                        "PAR 30",
                        Kind.BASIS_POINTS,
                        WORSE,
                        g.par30Bp(),
                        g.par30Bp() == null ? "not available" : FactDisplay.percent(g.par30Bp()),
                        false,
                        null,
                        null,
                        group));
            }
        }
    }

    private static Fact hiddenFact(String key, String label, Kind kind, Polarity polarity, Group group) {
        return new Fact(key, label, kind, polarity, null, "fewer than " + MIN_GROUP, true, null, null, group);
    }

    /** The loans of the group more than 30 days late. */
    private static long late(ParGroup g) {
        return g.buckets().stream()
                .filter(b -> Arrays.asList("31_60", "61_90", "over_90").contains(b.bucket()))
                .mapToLong(ParBucket::loans)
                .sum();
    }
}
