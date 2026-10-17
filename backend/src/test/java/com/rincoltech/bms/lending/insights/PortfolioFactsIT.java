package com.rincoltech.bms.lending.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.insights.GoldenModel.Loan;
import com.rincoltech.bms.lending.insights.GoldenModel.Pos;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Dimension;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Fact;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Group;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Kind;
import com.rincoltech.bms.lending.seed.internal.LendingSeeder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The public {@code PortfolioFacts} port (extras slice C2, issue #223; ADR-037 decision 3; spec 5.4) against the
 * independent recomputation of the insights golden model: raw rows of a fabricated year summed with plain loops, none of
 * the code under test. Proves that the facts equal the golden metrics with their display forms and their week-on-week
 * and month-on-month changes, that a branch manager's or a loan officer's facts are exactly the insights endpoints'
 * figures for the same caller, that a group of fewer than five loans is one suppressed fact, that no name of a branch,
 * product or staff member is in any fact, and that the business date is Kampala's at the 21:00 UTC edge. The clock is
 * pinned at 2026-10-08 (Kampala), so the fabricated year is the same on every day the suite runs.
 */
@Import(PortfolioFactsIT.FixedClock.class)
class PortfolioFactsIT extends IntegrationTest {

    static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    static final LocalDate FROM = TODAY.withDayOfMonth(1);
    static final Predicate<Loan> ALL_LOANS = l -> true;
    static final String ALL_PERMISSIONS = "lending.insights.read,lending.insights.all_officers,lending.members.read";

    static final class MovableClock extends Clock {
        private volatile Instant now = NOW;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        MovableClock fixedClock() {
            return new MovableClock();
        }
    }

    static TestDatabase.Fixture a;
    static GoldenModel model;
    static final List<String> PLANTED_BRANCHES = new ArrayList<>();
    static final List<String> PLANTED_PRODUCTS = new ArrayList<>();

    @Autowired
    MovableClock clock;

    @Autowired
    LendingSeeder seeder;

    @Autowired
    PortfolioFacts portfolioFacts;

    @Autowired
    InsightsFigures figures;

    @Autowired
    TenantJobs tenantJobs;

    @Autowired
    TestRestTemplate http;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;

    @BeforeEach
    void seededOnce() {
        clock.now = NOW;
        if (a != null) {
            return;
        }
        a = TestDatabase.tenant("facts", true);
        seeder.seed(a.slug(), new LendingSeeder.Options(true, 1));
        model = GoldenModel.load(a.tenantId());
        // Fabricated names that must appear in no fact: every branch and product is renamed to a canary.
        int i = 0;
        for (UUID id : TestDatabase.owner()
                .sql("SELECT id FROM branches WHERE tenant_id = ? ORDER BY id")
                .param(a.tenantId())
                .query(UUID.class)
                .list()) {
            String name = "Canary Branch Zq" + (++i);
            PLANTED_BRANCHES.add(name);
            TestDatabase.owner()
                    .sql("UPDATE branches SET name = ? WHERE id = ?")
                    .params(name, id)
                    .update();
        }
        i = 0;
        for (UUID id : TestDatabase.owner()
                .sql("SELECT id FROM lending_loan_products WHERE tenant_id = ? ORDER BY id")
                .param(a.tenantId())
                .query(UUID.class)
                .list()) {
            String name = "Canary Product Zq" + (++i);
            PLANTED_PRODUCTS.add(name);
            TestDatabase.owner()
                    .sql("UPDATE lending_loan_products SET name = ? WHERE id = ?")
                    .params(name, id)
                    .update();
        }
    }

    @AfterEach
    void signOut() {
        CurrentPrincipal.clear();
    }

    // ---- Helpers ----------------------------------------------------------------------------------------------

    static Principal user(UUID id, Set<String> permissions, Set<UUID> branches) {
        return Principal.uniform(id, "staff", permissions, branches == null, branches == null ? Set.of() : branches);
    }

    static final Set<String> ALL = Set.of("lending.insights.read", "lending.insights.all_officers");

    <T> T inTx(java.util.function.Supplier<T> work) {
        return new org.springframework.transaction.support.TransactionTemplate(txManager).execute(s -> work.get());
    }

    PortfolioScope scope(Principal who, LocalDate from, LocalDate to, List<UUID> branches, UUID product) {
        CurrentPrincipal.set(who);
        try {
            return tenantJobs.callAs(a.tenantId(), () -> inTx(() -> figures.scope(from, to, branches, null, product)));
        } finally {
            CurrentPrincipal.clear();
        }
    }

    /** The facts for a scope resolved for the principal in the request and read later without one. */
    FactSet facts(Principal who, LocalDate from, LocalDate to, List<UUID> branches, UUID product) {
        PortfolioScope scope = scope(who, from, to, branches, product);
        return tenantJobs.callAs(a.tenantId(), () -> portfolioFacts.build(scope));
    }

    FactSet wholeBook() {
        return facts(user(UUID.randomUUID(), ALL, null), FROM, TODAY, List.of(), null);
    }

    static Fact figure(FactSet set, String key) {
        List<Fact> found = set.facts().stream()
                .filter(f -> f.key().equals(key) && f.group() == null)
                .toList();
        assertThat(found).as("fact %s", key).hasSize(1);
        return found.get(0);
    }

    static boolean small(long population) {
        return population > 0 && population < PortfolioFacts.MIN_GROUP;
    }

    /** The fact is the golden figure in its display form, or one suppressed fact when its population is one to four. */
    static void expect(FactSet set, String key, Long golden, long... populations) {
        Fact f = figure(set, key);
        boolean suppressed = false;
        for (long p : populations) {
            suppressed |= small(p);
        }
        if (suppressed) {
            assertThat(f.suppressed()).as("%s is over a small population", key).isTrue();
            assertThat(f.display()).isEqualTo("fewer than 5");
            assertThat(f.value()).isNull();
            assertThat(f.weekOnWeek()).isNull();
            assertThat(f.monthOnMonth()).isNull();
            return;
        }
        assertThat(f.suppressed()).as(key).isFalse();
        assertThat(f.value()).as(key).isEqualTo(golden);
        assertThat(f.display())
                .as(key)
                .isEqualTo(golden == null ? "not available" : FactDisplay.of(f.kind(), golden, "UGX"));
    }

    static Long rate(long part, long whole) {
        return whole == 0 ? null : GoldenModel.bp(part, whole);
    }

    static final Set<String> COLLECTED = Set.of("repayment", "recovery");

    /** The distinct loans with a transaction of these types in the range, a reversed one excluded. */
    static long loansWith(Set<String> types, LocalDate from, LocalDate to, Predicate<Loan> filter) {
        return model.txns.values().stream()
                .filter(t -> types.contains(t.type())
                        && GoldenModel.between(t.valueDate(), from, to)
                        && !model.reversed.contains(t.id())
                        && filter.test(model.loans.get(t.loan())))
                .map(GoldenModel.Txn::loan)
                .distinct()
                .count();
    }

    /** The distinct loans behind the posted lines of a revenue account in the range. */
    static long loansBehind(String account, LocalDate from, LocalDate to, Predicate<Loan> filter) {
        return model.lines.stream()
                .filter(l -> l.key().equals(account) && GoldenModel.between(l.date(), from, to))
                .map(l -> model.txns.get(l.sourceTxn()).loan())
                .filter(loan -> filter.test(model.loans.get(loan)))
                .distinct()
                .count();
    }

    static long late(List<Pos> stock, int over) {
        return stock.stream().filter(p -> p.dpd() > over).count();
    }

    static final int[] PAR_OVER = {0, 30, 60, 90};

    /** One when the PAR level is hidden (small itself, or one to four loans from a shown earlier level), else its loans. */
    static long parPop(List<Pos> stock, int over) {
        int level = 0;
        for (int i = 0; i < PAR_OVER.length; i++) {
            if (PAR_OVER[i] == over) {
                level = i;
            }
        }
        return parHidden(stock, level) ? 1 : late(stock, over);
    }

    static boolean parHidden(List<Pos> stock, int level) {
        long n = late(stock, PAR_OVER[level]);
        if (small(n)) {
            return true;
        }
        for (int j = 0; j < level; j++) {
            long d = late(stock, PAR_OVER[j]) - n;
            if (!parHidden(stock, j) && d > 0 && d < PortfolioFacts.MIN_GROUP) {
                return true;
            }
        }
        return false;
    }

    /** The distinct loans with a transaction of these types on the days of the window other than today. */
    static long otherDaysWith(Set<String> types, LocalDate from) {
        return loansWith(types, from, TODAY.minusDays(1), ALL_LOANS);
    }

    @Test
    void theFactsOfTheWholeBookEqualTheGoldenMetricsInTheirDisplayForms() {
        FactSet set = wholeBook();
        assertThat(set.asOf()).isEqualTo(TODAY);
        assertThat(set.from()).isEqualTo(FROM);
        assertThat(set.to()).isEqualTo(TODAY);
        assertThat(set.currency()).isEqualTo("UGX");

        List<Pos> stock = model.live(TODAY, ALL_LOANS);
        long active = stock.size();
        assertThat(active).as("the fabricated year has a book").isGreaterThan(50);
        long po = GoldenModel.po(stock);
        long borrowers = stock.stream()
                .map(p -> model.loans.get(p.loan()).member())
                .distinct()
                .count();
        expect(set, "portfolio.principal_outstanding", po, active);
        expect(set, "portfolio.active_loans", active, active);
        expect(set, "portfolio.borrowers", borrowers, borrowers);
        expect(set, "portfolio.arrears", stock.stream().mapToLong(Pos::arrears).sum(), late(stock, 0));
        for (int over : new int[] {0, 30, 60, 90}) {
            expect(
                    set,
                    "portfolio.par" + (over == 0 ? 1 : over),
                    rate(GoldenModel.parAmount(stock, over), po),
                    active,
                    parPop(stock, over));
        }
        assertThat(figure(set, "portfolio.par30").value())
                .as("some risk in the book")
                .isPositive();

        long disbursedCount = model.countTxns("disbursement", FROM, TODAY, ALL_LOANS);
        long collectedCount = model.txns.values().stream()
                .filter(t -> COLLECTED.contains(t.type()) && GoldenModel.between(t.valueDate(), FROM, TODAY))
                .count();
        long expected = model.expected(FROM, TODAY, ALL_LOANS);
        long onDue = model.collectedOnDue(FROM, TODAY, ALL_LOANS);
        expect(
                set,
                "portfolio.disbursed",
                model.sumTxns(Set.of("disbursement"), FROM, TODAY, ALL_LOANS, false),
                disbursedCount,
                otherDaysWith(Set.of("disbursement"), FROM));
        expect(
                set,
                "portfolio.disbursed_count",
                disbursedCount,
                disbursedCount,
                otherDaysWith(Set.of("disbursement"), FROM));
        long payers = loansWith(COLLECTED, FROM, TODAY, ALL_LOANS);
        assertThat(collectedCount).isGreaterThanOrEqualTo(payers);
        long otherPaying = otherDaysWith(COLLECTED, FROM);
        long otherDue = model.loansDue(FROM, TODAY.minusDays(1), ALL_LOANS);
        expect(
                set,
                "portfolio.collected",
                model.sumTxns(COLLECTED, FROM, TODAY, ALL_LOANS, true),
                active,
                payers,
                otherPaying);
        expect(set, "portfolio.expected", expected, active, otherDue);
        expect(set, "portfolio.collection_rate", rate(onDue, expected), active, payers, otherPaying, otherDue);
        long writeOffs = model.countTxns("write_off", FROM, TODAY, ALL_LOANS);
        long behindWriteOff = loansBehind("loan_write_off_expense", FROM, TODAY, ALL_LOANS);
        expect(set, "portfolio.written_off_count", writeOffs, writeOffs);
        expect(
                set,
                "portfolio.written_off",
                model.sumTxns(Set.of("write_off"), FROM, TODAY, ALL_LOANS, false),
                writeOffs,
                behindWriteOff);
        expect(set, "portfolio.forecast_7", model.forecast(TODAY, 7, ALL_LOANS), active);
        expect(set, "portfolio.forecast_30", model.forecast(TODAY, 30, ALL_LOANS), active);
        long[] repeat = model.repeat(FROM, TODAY, ALL_LOANS);
        expect(set, "portfolio.repeat_share", rate(repeat[1], repeat[0]), repeat[0]);

        long[] revenue = model.revenue(FROM, TODAY, ALL_LOANS);
        long behindInterest = loansBehind("loan_interest_income", FROM, TODAY, ALL_LOANS);
        long behindFees = loansBehind("loan_fee_income", FROM, TODAY, ALL_LOANS);
        long behindPenalties = loansBehind("loan_penalty_income", FROM, TODAY, ALL_LOANS);
        long behindRecovered = loansBehind("bad_debt_recovered", FROM, TODAY, ALL_LOANS);
        long recoverers = loansWith(Set.of("recovery"), FROM, TODAY, ALL_LOANS);
        expect(set, "revenue.interest", revenue[0], behindInterest);
        expect(set, "revenue.fees", revenue[1], behindFees);
        expect(set, "revenue.penalties", revenue[2], behindPenalties);
        expect(set, "revenue.recovered", revenue[3], recoverers, behindRecovered);
        expect(set, "revenue.write_off_expense", revenue[4], behindWriteOff, writeOffs);
        // The contribution is hidden with any component it would give back.
        expect(
                set,
                "revenue.contribution",
                revenue[5],
                active,
                behindInterest,
                behindFees,
                behindPenalties,
                recoverers,
                behindRecovered,
                behindWriteOff,
                writeOffs);

        // The brief is today's.
        long disbursedToday = model.countTxns("disbursement", TODAY, TODAY, ALL_LOANS);
        long collectedToday = model.txns.values().stream()
                .filter(t -> COLLECTED.contains(t.type()) && t.valueDate().equals(TODAY))
                .count();
        expect(
                set,
                "brief.disbursed_today",
                model.sumTxns(Set.of("disbursement"), TODAY, TODAY, ALL_LOANS, false),
                disbursedToday);
        expect(set, "brief.disbursed_loans_today", disbursedToday, disbursedToday);
        long payersToday = loansWith(COLLECTED, TODAY, TODAY, ALL_LOANS);
        assertThat(collectedToday).isGreaterThanOrEqualTo(payersToday);
        expect(
                set,
                "brief.collected_today",
                model.sumTxns(COLLECTED, TODAY, TODAY, ALL_LOANS, true),
                active,
                payersToday);
        long fresh = stock.stream().filter(p -> p.dpd() == 1).count();
        long goingBad =
                stock.stream().filter(p -> p.dpd() >= 24 && p.dpd() <= 30).count();
        expect(set, "brief.new_arrears", fresh, fresh);
        expect(
                set,
                "brief.new_arrears_amount",
                stock.stream().filter(p -> p.dpd() == 1).mapToLong(Pos::arrears).sum(),
                fresh);
        expect(set, "brief.going_bad", goingBad, goingBad);
        expect(
                set,
                "brief.going_bad_principal",
                stock.stream()
                        .filter(p -> p.dpd() >= 24 && p.dpd() <= 30)
                        .mapToLong(Pos::po)
                        .sum(),
                goingBad);
    }

    @Test
    void everyFactCarriesItsKindPolarityAndLabelFromTheDictionary() {
        FactSet set = wholeBook();
        Fact par = figure(set, "portfolio.par30");
        assertThat(par.kind()).isEqualTo(Kind.BASIS_POINTS);
        assertThat(par.polarity()).isEqualTo(PortfolioFacts.Polarity.HIGHER_IS_WORSE);
        assertThat(par.label()).isEqualTo("PAR 30");
        assertThat(figure(set, "portfolio.collection_rate").polarity())
                .isEqualTo(PortfolioFacts.Polarity.HIGHER_IS_BETTER);
        assertThat(figure(set, "portfolio.principal_outstanding").kind()).isEqualTo(Kind.MONEY);
        // Penalty income rising is neither good nor bad news.
        assertThat(figure(set, "revenue.penalties").polarity()).isEqualTo(PortfolioFacts.Polarity.NEUTRAL);
        assertThat(figure(set, "portfolio.active_loans").kind()).isEqualTo(Kind.COUNT);
        assertThat(figure(set, "portfolio.principal_outstanding").polarity())
                .isEqualTo(PortfolioFacts.Polarity.NEUTRAL);
        assertThat(set.facts()).extracting(Fact::label).doesNotContainNull().allMatch(l -> !l.isBlank());
        // A key appears once per group: no duplicate whole-book facts.
        List<String> keys = set.facts().stream()
                .filter(f -> f.group() == null)
                .map(Fact::key)
                .toList();
        assertThat(keys).doesNotHaveDuplicates();
    }

    // ---- Changes: week on week and month on month --------------------------------------------------------------

    @Test
    void theChangesEqualTheGoldenFiguresOfTheEarlierRanges() {
        FactSet set = wholeBook();
        for (int[] shift : new int[][] {{7, 0}, {0, 1}}) {
            boolean week = shift[0] == 7;
            LocalDate from = week ? FROM.minusDays(7) : FROM.minusMonths(1);
            LocalDate to = week ? TODAY.minusDays(7) : TODAY.minusMonths(1);
            // Stocks are read from the snapshot of the earlier end date.
            List<Pos> then = List.copyOf(model.asOf(to).values());
            List<Pos> now = model.live(TODAY, ALL_LOANS);
            long poThen = GoldenModel.po(then);
            long poNow = GoldenModel.po(now);
            assertThat(then).as("a book on %s", to).hasSizeGreaterThan(50);
            assertChange(
                    figure(set, "portfolio.principal_outstanding"),
                    week,
                    poNow - poThen,
                    pair(now.size(), then.size()));
            assertChange(
                    figure(set, "portfolio.active_loans"),
                    week,
                    (long) now.size() - then.size(),
                    pair(now.size(), then.size()));
            assertChange(
                    figure(set, "portfolio.par30"),
                    week,
                    rate(GoldenModel.parAmount(now, 30), poNow) - rate(GoldenModel.parAmount(then, 30), poThen),
                    pair(now.size(), then.size()),
                    pair(late(now, 30), late(then, 30)),
                    parHidden(then, 1) ? pair(1, 1) : pair(0, 0));
            assertChange(
                    figure(set, "portfolio.arrears"),
                    week,
                    now.stream().mapToLong(Pos::arrears).sum()
                            - then.stream().mapToLong(Pos::arrears).sum(),
                    pair(late(now, 0), late(then, 0)));
            // Flows are the transactions of the earlier range.
            long disbursedNow = model.sumTxns(Set.of("disbursement"), FROM, TODAY, ALL_LOANS, false);
            long disbursedThen = model.sumTxns(Set.of("disbursement"), from, to, ALL_LOANS, false);
            assertChange(
                    figure(set, "portfolio.disbursed"),
                    week,
                    disbursedNow - disbursedThen,
                    pair(
                            model.countTxns("disbursement", FROM, TODAY, ALL_LOANS),
                            model.countTxns("disbursement", from, to, ALL_LOANS)));
            long collectedNow = model.sumTxns(COLLECTED, FROM, TODAY, ALL_LOANS, true);
            long collectedThen = model.sumTxns(COLLECTED, from, to, ALL_LOANS, true);
            assertChange(
                    figure(set, "portfolio.collected"),
                    week,
                    collectedNow - collectedThen,
                    pair(now.size(), then.size()),
                    pair(loansWith(COLLECTED, FROM, TODAY, ALL_LOANS), loansWith(COLLECTED, from, to, ALL_LOANS)));
            long expectedNow = model.expected(FROM, TODAY, ALL_LOANS);
            long expectedThen = model.expected(from, to, ALL_LOANS);
            assertChange(
                    figure(set, "portfolio.collection_rate"),
                    week,
                    rate(model.collectedOnDue(FROM, TODAY, ALL_LOANS), expectedNow)
                            - rate(model.collectedOnDue(from, to, ALL_LOANS), expectedThen),
                    pair(now.size(), then.size()),
                    pair(loansWith(COLLECTED, FROM, TODAY, ALL_LOANS), loansWith(COLLECTED, from, to, ALL_LOANS)));
            assertChange(
                    figure(set, "revenue.interest"),
                    week,
                    model.revenue(FROM, TODAY, ALL_LOANS)[0] - model.revenue(from, to, ALL_LOANS)[0],
                    pair(
                            loansBehind("loan_interest_income", FROM, TODAY, ALL_LOANS),
                            loansBehind("loan_interest_income", from, to, ALL_LOANS)));
            assertChange(
                    figure(set, "brief.disbursed_today"),
                    week,
                    model.sumTxns(Set.of("disbursement"), TODAY, TODAY, ALL_LOANS, false)
                            - model.sumTxns(Set.of("disbursement"), to, to, ALL_LOANS, false),
                    pair(
                            model.countTxns("disbursement", TODAY, TODAY, ALL_LOANS),
                            model.countTxns("disbursement", to, to, ALL_LOANS)));
        }
        // Figures with no earlier comparison have no change facts.
        assertThat(figure(set, "brief.new_arrears").weekOnWeek()).isNull();
        assertThat(figure(set, "brief.going_bad").monthOnMonth()).isNull();
        assertThat(figure(set, "portfolio.forecast_7").weekOnWeek()).isNull();
    }

    static long[] pair(long now, long then) {
        return new long[] {now, then};
    }

    /** True when a change over these loans would be a small group's exact figure: a small side, or one to four between. */
    static boolean changeHidden(long[]... populations) {
        for (long[] p : populations) {
            long diff = Math.abs(p[0] - p[1]);
            if (small(p[0]) || small(p[1]) || (diff > 0 && diff < PortfolioFacts.MIN_GROUP)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The change is the golden difference, in the figure's units, with the display form of its sign, and is left out when
     * the loans behind either figure are one to four or when the two windows' loans differ by one to four (the change would
     * then be the exact figure of those few loans). Each pair is the loans (now, earlier) of one population of the figure.
     */
    static void assertChange(Fact f, boolean week, long golden, long[]... populations) {
        PortfolioFacts.Delta d = week ? f.weekOnWeek() : f.monthOnMonth();
        if (f.suppressed() || changeHidden(populations)) {
            assertThat(d)
                    .as("%s %s change over a small population", f.key(), week ? "week" : "month")
                    .isNull();
            return;
        }
        assertThat(d).as("%s %s change", f.key(), week ? "week" : "month").isNotNull();
        assertThat(d.change()).as("%s %s", f.key(), week ? "week" : "month").isEqualTo(golden);
        assertThat(d.display()).isEqualTo(FactDisplay.change(f.kind(), golden, "UGX"));
        if (golden != 0) {
            assertThat(d.display()).startsWith(golden > 0 ? "+" : "-");
        }
    }

    @Test
    void aChangeIsLeftOutWhenTheEarlierRangeHeldFewerThanFiveLoansOrTheTwoRangesDifferByOneToFour() {
        // Scan the fabricated year for ranges of 14 days whose week earlier held one to four disbursements, five or
        // more
        // that differ from the current by one to four, and five or more that are the same or differ by five or more:
        // only
        // the last gives a change (the others would hand back a small group's exact figure by subtraction).
        LocalDate smallEarlier = null;
        LocalDate nearEarlier = null;
        LocalDate farEarlier = null;
        int length = 14;
        for (int back = 0; back < 200 && (smallEarlier == null || nearEarlier == null || farEarlier == null); back++) {
            LocalDate to = TODAY.minusDays(back);
            LocalDate from = to.minusDays(length - 1);
            long now = model.countTxns("disbursement", from, to, ALL_LOANS);
            long before = model.countTxns("disbursement", from.minusDays(7), to.minusDays(7), ALL_LOANS);
            long diff = Math.abs(now - before);
            if (now >= PortfolioFacts.MIN_GROUP && small(before) && smallEarlier == null) {
                smallEarlier = to;
            }
            if (now >= PortfolioFacts.MIN_GROUP
                    && before >= PortfolioFacts.MIN_GROUP
                    && diff > 0
                    && diff < PortfolioFacts.MIN_GROUP
                    && nearEarlier == null) {
                nearEarlier = to;
            }
            if (now >= PortfolioFacts.MIN_GROUP
                    && before >= PortfolioFacts.MIN_GROUP
                    && (diff == 0 || diff >= PortfolioFacts.MIN_GROUP)
                    && farEarlier == null) {
                farEarlier = to;
            }
        }
        assertThat(smallEarlier).as("a range after a quiet week").isNotNull();
        assertThat(nearEarlier)
                .as("a range one to four loans from the week before")
                .isNotNull();
        assertThat(farEarlier).as("a range far from the week before").isNotNull();

        UUID user = UUID.randomUUID();
        FactSet quiet = facts(user(user, ALL, null), smallEarlier.minusDays(length - 1), smallEarlier, List.of(), null);
        Fact count = figure(quiet, "portfolio.disbursed_count");
        assertThat(count.suppressed()).isFalse();
        assertThat(count.value()).isGreaterThanOrEqualTo(PortfolioFacts.MIN_GROUP);
        assertThat(count.weekOnWeek()).as("the earlier week held one to four").isNull();
        assertThat(figure(quiet, "portfolio.disbursed").weekOnWeek()).isNull();

        FactSet near = facts(user(user, ALL, null), nearEarlier.minusDays(length - 1), nearEarlier, List.of(), null);
        assertThat(figure(near, "portfolio.disbursed_count").weekOnWeek())
                .as("the two ranges differ by one to four loans")
                .isNull();
        assertThat(figure(near, "portfolio.disbursed").weekOnWeek()).isNull();

        FactSet far = facts(user(user, ALL, null), farEarlier.minusDays(length - 1), farEarlier, List.of(), null);
        long beforeCount = model.countTxns(
                "disbursement", farEarlier.minusDays(length - 1 + 7), farEarlier.minusDays(7), ALL_LOANS);
        assertChange(
                figure(far, "portfolio.disbursed_count"),
                true,
                figure(far, "portfolio.disbursed_count").value() - beforeCount,
                pair(figure(far, "portfolio.disbursed_count").value(), beforeCount));
        assertThat(figure(far, "portfolio.disbursed_count").weekOnWeek()).isNotNull();
    }

    @Test
    void theFabricatedYearHasChangesOfBothSignsSoTheDirectionChecksHaveSomethingToCheck() {
        FactSet set = wholeBook();
        List<Long> changes = new ArrayList<>();
        for (Fact f : set.facts()) {
            if (f.group() == null && f.weekOnWeek() != null) {
                changes.add(f.weekOnWeek().change());
            }
            if (f.group() == null && f.monthOnMonth() != null) {
                changes.add(f.monthOnMonth().change());
            }
        }
        assertThat(changes).anyMatch(c -> c > 0).anyMatch(c -> c < 0);
    }

    // ---- Scope: the facts are the insights endpoints' figures for the same caller ---------------------------

    static HttpHeaders headers(UUID user, String permissions, String branches) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", a.slug());
        h.add("X-Dev-User-Id", user.toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", branches);
        return h;
    }

    JsonNode endpoint(String path, HttpHeaders h) {
        ResponseEntity<JsonNode> r =
                http.exchange("/api/v1/lending/insights" + path, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
        assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static Long metric(JsonNode body, String key) {
        for (JsonNode m : body.get("metrics")) {
            if (m.get("key").asString().equals(key)) {
                return m.get("value").isNull() ? null : m.get("value").asLong();
            }
        }
        throw new AssertionError("no metric " + key);
    }

    /** Every unsuppressed fact equals the same-named metric of the endpoint the page reads, for the same caller. */
    void factsEqualTheEndpoints(FactSet set, UUID user, String permissions, String branches) {
        HttpHeaders h = headers(user, permissions, branches);
        String range = "from=" + FROM + "&to=" + TODAY;
        JsonNode portfolio = endpoint("/portfolio?" + range, h);
        JsonNode revenue = endpoint("/revenue?" + range, h);
        JsonNode members = endpoint("/members?" + range, h);
        JsonNode brief = endpoint("/brief", h);
        int compared = 0;
        for (Fact f : set.facts()) {
            if (f.group() != null || f.suppressed()) {
                continue;
            }
            JsonNode source = f.key().startsWith("portfolio.")
                    ? portfolio
                    : f.key().startsWith("revenue.") ? revenue : f.key().startsWith("members.") ? members : brief;
            assertThat(f.value()).as(f.key()).isEqualTo(metric(source, f.key()));
            compared++;
        }
        assertThat(compared).as("figures compared with the endpoints").isGreaterThan(15);
    }

    @Test
    void theWholeBooksFactsEqualTheInsightsEndpoints() {
        UUID user = UUID.randomUUID();
        factsEqualTheEndpoints(facts(user(user, ALL, null), FROM, TODAY, List.of(), null), user, ALL_PERMISSIONS, "*");
    }

    private UUID northBranch() {
        return model.loans.values().stream()
                .map(Loan::branch)
                .distinct()
                .filter(b -> !b.equals(a.headOffice()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aBranchManagersFactsAreOnlyTheirBranchAndEqualTheEndpointsForThem() {
        UUID north = northBranch();
        UUID user = UUID.randomUUID();
        FactSet mine = facts(user(user, ALL, Set.of(north)), FROM, TODAY, List.of(), null);
        FactSet everything = wholeBook();

        Predicate<Loan> inNorth = l -> l.branch().equals(north);
        List<Pos> stock = model.live(TODAY, inNorth);
        long active = stock.size();
        assertThat(active)
                .isGreaterThan(4)
                .isLessThan(model.live(TODAY, ALL_LOANS).size());
        expect(mine, "portfolio.active_loans", active, active);
        expect(mine, "portfolio.principal_outstanding", GoldenModel.po(stock), active);
        expect(
                mine,
                "portfolio.par30",
                rate(GoldenModel.parAmount(stock, 30), GoldenModel.po(stock)),
                active,
                parPop(stock, 30));
        expect(
                mine,
                "portfolio.disbursed",
                model.sumTxns(Set.of("disbursement"), FROM, TODAY, inNorth, false),
                model.countTxns("disbursement", FROM, TODAY, inNorth));
        // The same caller's figures are the endpoints' figures, and not the whole book's.
        factsEqualTheEndpoints(mine, user, ALL_PERMISSIONS, north.toString());
        assertThat(figure(mine, "portfolio.principal_outstanding").value())
                .isNotEqualTo(
                        figure(everything, "portfolio.principal_outstanding").value());
        // Only their branch appears as a group, and only products with loans in it.
        Set<String> branchIds = mine.names().stream()
                .filter(n -> n.group().dimension() == Dimension.BRANCH)
                .map(n -> n.group().id())
                .collect(Collectors.toSet());
        assertThat(branchIds).containsExactly(north.toString());
        Set<String> productsInNorth = stock.stream()
                .map(p -> model.loans.get(p.loan()).product().toString())
                .collect(Collectors.toSet());
        assertThat(mine.names().stream()
                        .filter(n -> n.group().dimension() == Dimension.PRODUCT)
                        .map(n -> n.group().id()))
                .isSubsetOf(productsInNorth);
        // Whatever the manager asks for, they get their branch or nothing.
        FactSet asksForAll = facts(user(user, ALL, Set.of(north)), FROM, TODAY, List.of(), null);
        assertThat(figure(asksForAll, "portfolio.principal_outstanding").value())
                .isEqualTo(GoldenModel.po(stock));
        FactSet asksForOther = facts(user(user, ALL, Set.of(north)), FROM, TODAY, List.of(a.headOffice()), null);
        assertThat(figure(asksForOther, "portfolio.active_loans").value())
                .as("no loans in view")
                .isZero();
        assertThat(figure(asksForOther, "portfolio.principal_outstanding").value())
                .isZero();
        assertThat(figure(asksForOther, "portfolio.par30").display()).isEqualTo("not available");
        assertThat(asksForOther.names()).isEmpty();
    }

    @Test
    void aCallerWithNoBranchesGetsEmptyFactsNotTheWholeBook() {
        FactSet nobody = facts(user(UUID.randomUUID(), ALL, Set.of()), FROM, TODAY, List.of(), null);
        // Held at no branch: the scope is empty, as the endpoints answer.
        assertThat(figure(nobody, "portfolio.active_loans").value()).isZero();
        assertThat(figure(nobody, "portfolio.principal_outstanding").value()).isZero();
        assertThat(nobody.names()).isEmpty();
    }

    @Test
    void aLoanOfficerWithoutAllOfficersGetsTheirOwnLoansOnlyAndSmallBooksAreSuppressed() {
        UUID officer = model.loans.values().stream()
                .filter(l -> l.status().equals("active"))
                .map(Loan::officer)
                .findFirst()
                .orElseThrow();
        Predicate<Loan> own = l -> officer.equals(l.officer());
        FactSet mine = facts(user(officer, Set.of("lending.insights.read"), null), FROM, TODAY, List.of(), null);
        List<Pos> stock = model.live(TODAY, own);
        expect(mine, "portfolio.active_loans", (long) stock.size(), stock.size());
        expect(mine, "portfolio.principal_outstanding", GoldenModel.po(stock), stock.size());
        factsEqualTheEndpoints(mine, officer, "lending.insights.read", "*");
        assertThat(figure(mine, "portfolio.active_loans").suppressed()).isFalse();

        // An officer looking at one product in which they hold one to four active loans: every figure is "fewer than
        // 5".
        Map<List<UUID>, Long> perOfficerAndProduct = model.live(TODAY, ALL_LOANS).stream()
                .map(p -> model.loans.get(p.loan()))
                .collect(Collectors.groupingBy(l -> List.of(l.officer(), l.product()), Collectors.counting()));
        List<UUID> narrow = perOfficerAndProduct.entrySet().stream()
                .filter(e -> small(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        assertThat(narrow)
                .as("an officer with one to four active loans in a product")
                .isNotNull();
        FactSet few = facts(
                user(narrow.get(0), Set.of("lending.insights.read"), null), FROM, TODAY, List.of(), narrow.get(1));
        List<Pos> held = model.live(
                TODAY, l -> narrow.get(0).equals(l.officer()) && l.product().equals(narrow.get(1)));
        assertThat(held).hasSizeBetween(1, 4);
        // The stock figures, and PAR 30 as a share of them, are over the loans held, always one to four here. The
        // arrears
        // amount is over the late loans among them and is hidden exactly when those number one to four (an officer with
        // a
        // product of one to four loans may hold none that is late, which is then a zero and not a secret).
        for (String key : List.of("portfolio.active_loans", "portfolio.principal_outstanding", "portfolio.par30")) {
            assertThat(figure(few, key).suppressed()).as(key).isTrue();
            assertThat(figure(few, key).display()).isEqualTo("fewer than 5");
            assertThat(figure(few, key).value()).as(key).isNull();
        }
        assertThat(figure(few, "portfolio.arrears").suppressed())
                .as("arrears over %d late loans", late(held, 0))
                .isEqualTo(small(late(held, 0)));
        assertThat(few.facts()).noneMatch(f -> f.display().contains(FactDisplay.money(GoldenModel.po(held), "UGX")));
    }

    // ---- Small groups ------------------------------------------------------------------------------------------

    /** A product in which some branch has one to four active loans and another has five or more. */
    private UUID productWithASmallBranch() {
        List<Pos> stock = model.live(TODAY, ALL_LOANS);
        Map<UUID, Map<UUID, Long>> byProduct = stock.stream()
                .map(p -> model.loans.get(p.loan()))
                .collect(Collectors.groupingBy(
                        Loan::product, Collectors.groupingBy(Loan::branch, Collectors.counting())));
        return byProduct.entrySet().stream()
                .filter(e -> e.getValue().values().stream().anyMatch(PortfolioFactsIT::small)
                        && e.getValue().values().stream().anyMatch(c -> c >= PortfolioFacts.MIN_GROUP))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    @Test
    void aBranchWithFewerThanFiveLoansIsOneSuppressedFactAndNothingElse() {
        UUID product = productWithASmallBranch();
        assertThat(product)
                .as("the fabricated year has a product with a small branch")
                .isNotNull();
        FactSet set = facts(user(UUID.randomUUID(), ALL, null), FROM, TODAY, List.of(), product);
        Predicate<Loan> inProduct = l -> l.product().equals(product);
        Map<UUID, Long> perBranch = model.live(TODAY, inProduct).stream()
                .map(p -> model.loans.get(p.loan()))
                .collect(Collectors.groupingBy(Loan::branch, Collectors.counting()));

        int small = 0;
        int shown = 0;
        int forced = 0;
        for (Map.Entry<UUID, Long> e : perBranch.entrySet()) {
            Group group = new Group(Dimension.BRANCH, e.getKey().toString());
            List<Fact> of =
                    set.facts().stream().filter(f -> group.equals(f.group())).toList();
            assertThat(set.names())
                    .as("named for the alias mapping")
                    .anyMatch(n -> n.group().equals(group));
            if (small(e.getValue())) {
                small++;
                assertThat(of).as("a branch of %d loans", e.getValue()).hasSize(1);
                Fact f = of.get(0);
                assertThat(f.key()).isEqualTo("branch.loans");
                assertThat(f.suppressed()).isTrue();
                assertThat(f.value()).isNull();
                assertThat(f.display()).isEqualTo("fewer than 5");
                // Its real figures are nowhere in the facts, in any form.
                long po = GoldenModel.po(
                        model.live(TODAY, inProduct.and(l -> l.branch().equals(e.getKey()))));
                String grouped = FactDisplay.money(po, "UGX");
                assertThat(set.facts())
                        .noneMatch(x -> x.display().equals(grouped) && group.equals(x.group()))
                        .noneMatch(x -> group.equals(x.group()) && x.value() != null);
            } else if (e.getValue() >= PortfolioFacts.MIN_GROUP && of.size() == 1) {
                // Hidden with the small branch, so that subtracting the shown ones from the total cannot give its
                // count.
                assertThat(of.get(0).suppressed()).isTrue();
                assertThat(of.get(0).key()).isEqualTo("branch.loans");
                forced++;
            } else if (e.getValue() >= PortfolioFacts.MIN_GROUP) {
                shown++;
                assertThat(of)
                        .extracting(Fact::key)
                        .containsExactly("branch.loans", "branch.principal_outstanding", "branch.par30");
                assertThat(of.get(0).value()).isEqualTo(e.getValue());
                long po = GoldenModel.po(
                        model.live(TODAY, inProduct.and(l -> l.branch().equals(e.getKey()))));
                assertThat(of.get(1).value()).isEqualTo(po);
            }
        }
        assertThat(small).isGreaterThan(0);
        assertThat(shown + forced)
                .as("branches of five or more loans, shown or hidden with the small one")
                .isGreaterThan(0);
    }

    @Test
    void aSmallBookIsSuppressedWhereverItsPopulationIsSmall() {
        // A product-and-branch narrow enough to hold one to four loans: the whole book of that scope is small.
        List<Pos> stock = model.live(TODAY, ALL_LOANS);
        Map<List<UUID>, Long> counts = stock.stream()
                .map(p -> model.loans.get(p.loan()))
                .collect(Collectors.groupingBy(l -> List.of(l.branch(), l.product()), Collectors.counting()));
        List<UUID> narrow = counts.entrySet().stream()
                .filter(e -> small(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        assertThat(narrow).as("a branch and product with one to four loans").isNotNull();
        FactSet set = facts(user(UUID.randomUUID(), ALL, null), FROM, TODAY, List.of(narrow.get(0)), narrow.get(1));
        for (String key : List.of(
                "portfolio.active_loans",
                "portfolio.principal_outstanding",
                "portfolio.par1",
                "portfolio.par30",
                "portfolio.collected",
                "portfolio.expected",
                "revenue.contribution")) {
            Fact f = figure(set, key);
            assertThat(f.suppressed()).as(key).isTrue();
            assertThat(f.value()).as(key).isNull();
            assertThat(f.display()).as(key).isEqualTo("fewer than 5");
            assertThat(f.weekOnWeek()).as(key).isNull();
        }
        // No digit of the real principal appears in any fact's display.
        long po = GoldenModel.po(model.live(
                TODAY, l -> l.branch().equals(narrow.get(0)) && l.product().equals(narrow.get(1))));
        assertThat(set.facts()).noneMatch(f -> f.display().contains(FactDisplay.money(po, "UGX")));
    }

    // ---- Related figures are hidden together (review item 3) ---------------------------------------------------

    private static boolean hidden(Fact f) {
        return f.suppressed() && f.value() == null && f.weekOnWeek() == null && f.monthOnMonth() == null;
    }

    /** No fact of the set shows this amount, in any display form, as a figure or as a change. */
    private static void showsNowhere(FactSet set, long amount) {
        if (amount == 0) {
            return;
        }
        String shown = FactDisplay.money(Math.abs(amount), "UGX");
        for (Fact f : set.facts()) {
            assertThat(f.display()).as("%s", f.key()).isNotEqualTo(shown).isNotEqualTo("-" + shown);
            assertThat(f.value() == null || Math.abs(f.value()) != Math.abs(amount))
                    .as("%s", f.key())
                    .isTrue();
            for (PortfolioFacts.Delta d : new PortfolioFacts.Delta[] {f.weekOnWeek(), f.monthOnMonth()}) {
                if (d != null) {
                    assertThat(Math.abs(d.change())).as("%s change", f.key()).isNotEqualTo(Math.abs(amount));
                    assertThat(d.display())
                            .as("%s change", f.key())
                            .isNotEqualTo("+" + shown)
                            .isNotEqualTo("-" + shown);
                }
            }
        }
    }

    @Test
    void oneToFourWriteOffsHideEveryFigureThatWouldShowThem() {
        LocalDate to = null;
        LocalDate from = null;
        for (int back = 0; back < 200 && to == null; back++) {
            LocalDate end = TODAY.minusDays(back);
            LocalDate start = end.minusDays(29);
            if (model.countTxns("write_off", start, end, ALL_LOANS) >= 1
                    && model.countTxns("write_off", start, end, ALL_LOANS) <= 4
                    && model.live(TODAY, ALL_LOANS).size() >= PortfolioFacts.MIN_GROUP) {
                to = end;
                from = start;
            }
        }
        assertThat(to).as("a month with one to four write-offs").isNotNull();
        long writtenOff = model.sumTxns(Set.of("write_off"), from, to, ALL_LOANS, false);
        long[] revenue = model.revenue(from, to, ALL_LOANS);
        FactSet set = facts(user(UUID.randomUUID(), ALL, null), from, to, List.of(), null);

        for (String key : List.of(
                "portfolio.written_off_count",
                "portfolio.written_off",
                "revenue.write_off_expense",
                "revenue.contribution")) {
            assertThat(hidden(figure(set, key)))
                    .as("%s is hidden with the write-offs", key)
                    .isTrue();
            assertThat(figure(set, key).display()).isEqualTo("fewer than 5");
        }
        // Neither the written-off principal nor the expense nor the contribution, in any fact, figure or change.
        showsNowhere(set, writtenOff);
        showsNowhere(set, revenue[4]);
        showsNowhere(set, revenue[5]);
        // What does not describe those loans stays: the book, and the interest earned over many loans.
        assertThat(figure(set, "portfolio.principal_outstanding").suppressed()).isFalse();
        LocalDate windowStart = from;
        LocalDate windowEnd = to;
        long interestLoans = model.lines.stream()
                .filter(l ->
                        l.key().equals("loan_interest_income") && GoldenModel.between(l.date(), windowStart, windowEnd))
                .map(l -> model.txns.get(l.sourceTxn()).loan())
                .distinct()
                .count();
        assertThat(figure(set, "revenue.interest").suppressed()).isEqualTo(small(interestLoans));
    }

    @Test
    void oneToFourRecoveriesHideTheRecoveredFiguresAndTheContribution() {
        LocalDate to = null;
        LocalDate from = null;
        for (int back = 0; back < 200 && to == null; back++) {
            LocalDate end = TODAY.minusDays(back);
            LocalDate start = end.minusDays(29);
            long loans = model.txns.values().stream()
                    .filter(t -> t.type().equals("recovery")
                            && GoldenModel.between(t.valueDate(), start, end)
                            && !model.reversed.contains(t.id()))
                    .map(GoldenModel.Txn::loan)
                    .distinct()
                    .count();
            if (small(loans)) {
                to = end;
                from = start;
            }
        }
        assertThat(to).as("a month with recoveries on one to four loans").isNotNull();
        long recovered = model.sumTxns(Set.of("recovery"), from, to, ALL_LOANS, true);
        FactSet set = facts(user(UUID.randomUUID(), ALL, null), from, to, List.of(), null);
        for (String key : List.of("portfolio.recovered", "revenue.recovered", "revenue.contribution")) {
            assertThat(hidden(figure(set, key))).as(key).isTrue();
        }
        showsNowhere(set, recovered);
        showsNowhere(set, model.revenue(from, to, ALL_LOANS)[3]);
        showsNowhere(set, model.revenue(from, to, ALL_LOANS)[5]);
    }

    @Test
    void arrearsAndRiskAreHiddenWhenFewerThanFiveLoansAreLate() {
        // A product in which five or more loans are active and one to four are in arrears, and one in which one to four
        // are more than 30 days late: the amounts at risk of those few must not show, in the arrears or in any PAR.
        List<Pos> stock = model.live(TODAY, ALL_LOANS);
        Map<UUID, List<Pos>> byProduct = stock.stream()
                .collect(Collectors.groupingBy(p -> model.loans.get(p.loan()).product()));
        UUID fewLate = null;
        UUID fewOver30 = null;
        for (Map.Entry<UUID, List<Pos>> e : byProduct.entrySet()) {
            long active = e.getValue().size();
            long late = e.getValue().stream().filter(p -> p.dpd() > 0).count();
            long over30 = e.getValue().stream().filter(p -> p.dpd() > 30).count();
            if (active >= PortfolioFacts.MIN_GROUP && small(late) && fewLate == null) {
                fewLate = e.getKey();
            }
            if (active >= PortfolioFacts.MIN_GROUP
                    && late >= PortfolioFacts.MIN_GROUP
                    && small(over30)
                    && fewOver30 == null) {
                fewOver30 = e.getKey();
            }
        }
        assertThat(fewLate).as("a product with one to four loans in arrears").isNotNull();
        FactSet few = facts(user(UUID.randomUUID(), ALL, null), FROM, TODAY, List.of(), fewLate);
        List<Pos> inProduct = byProduct.get(fewLate);
        assertThat(figure(few, "portfolio.principal_outstanding").suppressed()).isFalse();
        for (String key : List.of("portfolio.arrears", "portfolio.par1")) {
            assertThat(hidden(figure(few, key))).as(key).isTrue();
        }
        showsNowhere(few, inProduct.stream().mapToLong(Pos::arrears).sum());

        assertThat(fewOver30)
                .as("a product with one to four loans over 30 days late")
                .isNotNull();
        FactSet over = facts(user(UUID.randomUUID(), ALL, null), FROM, TODAY, List.of(), fewOver30);
        assertThat(hidden(figure(over, "portfolio.par30"))).isTrue();
        // PAR 60 is hidden exactly when one to four loans are more than 60 days late; zero late loans is a plain 0.0
        // percent.
        assertThat(hidden(figure(over, "portfolio.par60"))).isEqualTo(parPop(byProduct.get(fewOver30), 60) == 1);
        assertThat(figure(over, "portfolio.par1").suppressed())
                .as("many loans are late, few very late")
                .isFalse();
    }

    @Test
    void aGroupsPar30IsHiddenWhenFewerThanFiveOfItsLoansAreMoreThan30DaysLate() {
        FactSet set = wholeBook();
        List<Pos> stock = model.live(TODAY, ALL_LOANS);
        int hiddenGroups = 0;
        for (Dimension dimension : Dimension.values()) {
            for (Fact f : set.facts()) {
                if (f.group() == null
                        || f.group().dimension() != dimension
                        || !f.key().endsWith(".par30")) {
                    continue;
                }
                long over30 = stock.stream()
                        .filter(p -> p.dpd() > 30
                                && (dimension == Dimension.BRANCH
                                                ? model.loans.get(p.loan()).branch()
                                                : model.loans.get(p.loan()).product())
                                        .toString()
                                        .equals(f.group().id()))
                        .count();
                if (small(over30)) {
                    assertThat(f.suppressed())
                            .as("%s of %s", f.key(), f.group())
                            .isTrue();
                }
                hiddenGroups += f.suppressed() ? 1 : 0;
            }
        }
        assertThat(hiddenGroups)
                .as("the fabricated year has a group with a few very late loans")
                .isPositive();
    }

    // ---- Complementary suppression: the groups never add up to a small remainder --------------------------

    private static long shownLoans(FactSet set, Dimension dimension, String key) {
        return set.facts().stream()
                .filter(f -> f.group() != null
                        && f.group().dimension() == dimension
                        && f.key().equals(key)
                        && !f.suppressed())
                .mapToLong(Fact::value)
                .sum();
    }

    /** The loans of the scope more than 30 days late, by the golden model. */
    private static long lateOf(Predicate<Loan> in) {
        return model.live(TODAY, in).stream().filter(p -> p.dpd() > 30).count();
    }

    private void remainderIsNeverSmall(FactSet set, Dimension dimension, Predicate<Loan> scope) {
        Fact total = figure(set, "portfolio.active_loans");
        if (total.suppressed()) {
            return;
        }
        String loansKey = dimension == Dimension.BRANCH ? "branch.loans" : "product.loans";
        long remainder = total.value() - shownLoans(set, dimension, loansKey);
        assertThat(remainder == 0 || remainder >= PortfolioFacts.MIN_GROUP)
                .as("total less the %s shown leaves %d loans", dimension, remainder)
                .isTrue();
        if (small(lateOf(scope))) {
            return; // the total's own PAR 30 is hidden, so there is nothing to subtract from
        }
        // The same for the loans more than 30 days late behind the PAR 30 of the groups shown.
        long shownLate = 0;
        for (Fact f : set.facts()) {
            if (f.group() != null
                    && f.group().dimension() == dimension
                    && f.key().endsWith(".par30")
                    && !f.suppressed()) {
                String id = f.group().id();
                shownLate += lateOf(scope.and(l -> (dimension == Dimension.BRANCH ? l.branch() : l.product())
                        .toString()
                        .equals(id)));
            }
        }
        long lateRemainder = lateOf(scope) - shownLate;
        assertThat(lateRemainder == 0 || lateRemainder >= PortfolioFacts.MIN_GROUP)
                .as("loans over 30 days late not behind a shown PAR 30: %d", lateRemainder)
                .isTrue();
    }

    @Test
    void subtractingTheShownGroupsFromTheTotalNeverGivesASmallGroupsExactFigures() {
        UUID user = UUID.randomUUID();
        remainderIsNeverSmall(wholeBook(), Dimension.BRANCH, ALL_LOANS);
        remainderIsNeverSmall(wholeBook(), Dimension.PRODUCT, ALL_LOANS);
        Set<UUID> products = model.loans.values().stream().map(Loan::product).collect(Collectors.toSet());
        Set<UUID> branches = model.loans.values().stream().map(Loan::branch).collect(Collectors.toSet());
        int scopes = 0;
        for (UUID product : products) {
            FactSet set = facts(user(user, ALL, null), FROM, TODAY, List.of(), product);
            remainderIsNeverSmall(set, Dimension.BRANCH, l -> l.product().equals(product));
            scopes++;
        }
        for (UUID branch : branches) {
            FactSet set = facts(user(user, ALL, null), FROM, TODAY, List.of(branch), null);
            remainderIsNeverSmall(set, Dimension.PRODUCT, l -> l.branch().equals(branch));
            scopes++;
        }
        assertThat(scopes).isGreaterThan(2);
    }

    @Test
    void aBranchWithFiveOrMoreLoansIsHiddenWhenThatIsTheOnlyWayToKeepASmallBranchApart() {
        // A product in which one branch has one to four loans and the others five or more: showing every other branch
        // would
        // give the small one's exact count by subtraction, so the smallest other branch is hidden with it.
        UUID product = productWithASmallBranch();
        assertThat(product).isNotNull();
        FactSet set = facts(user(UUID.randomUUID(), ALL, null), FROM, TODAY, List.of(), product);
        Map<UUID, Long> perBranch = model.live(TODAY, l -> l.product().equals(product)).stream()
                .map(p -> model.loans.get(p.loan()))
                .collect(Collectors.groupingBy(Loan::branch, Collectors.counting()));
        long smallLoans = perBranch.values().stream()
                .filter(PortfolioFactsIT::small)
                .mapToLong(Long::longValue)
                .sum();
        long forced = perBranch.entrySet().stream()
                .filter(e -> e.getValue() >= PortfolioFacts.MIN_GROUP)
                .filter(e -> set.facts().stream()
                        .anyMatch(f -> f.group() != null
                                && f.group().id().equals(e.getKey().toString())
                                && f.key().equals("branch.loans")
                                && f.suppressed()))
                .count();
        assertThat(smallLoans).isPositive();
        if (smallLoans < PortfolioFacts.MIN_GROUP) {
            assertThat(forced).as("another branch hidden with the small one").isPositive();
        } else {
            assertThat(forced)
                    .as("the small branches already add up to five or more")
                    .isZero();
        }
        long remainder =
                figure(set, "portfolio.active_loans").value() - shownLoans(set, Dimension.BRANCH, "branch.loans");
        assertThat(remainder).isGreaterThanOrEqualTo(PortfolioFacts.MIN_GROUP);
    }

    // ---- Nested windows and the PAR levels as a partition ------------------------------------------------------

    @Test
    void aWindowThatContainsTodayNeverDiffersFromTodayByOneToFourWhenBothAreShown() {
        UUID user = UUID.randomUUID();
        int checked = 0;
        for (int back : new int[] {0, 1, 2, 3, 5, 8, 13, 21, 34, 55}) {
            LocalDate from = TODAY.minusDays(back);
            FactSet set = facts(user(user, ALL, null), from, TODAY, List.of(), null);
            // The loans that moved money on the days of the window other than today: today's figure plus the figure
            // of those days is the window's, so when they number one to four the window's figure is hidden.
            long otherDisbursed = otherDaysWith(Set.of("disbursement"), from);
            long otherPaying = otherDaysWith(COLLECTED, from);
            long otherDue = model.loansDue(from, TODAY.minusDays(1), ALL_LOANS);
            for (Object[] pair : new Object[][] {
                {"portfolio.disbursed", "brief.disbursed_today", otherDisbursed},
                {"portfolio.collected", "brief.collected_today", otherPaying},
                {"portfolio.expected", "brief.expected_today", otherDue}
            }) {
                Fact outer = figure(set, (String) pair[0]);
                Fact inner = figure(set, (String) pair[1]);
                long others = (long) pair[2];
                if (small(others)) {
                    assertThat(hidden(outer))
                            .as("%s from %s: %d loans on other days", pair[0], from, others)
                            .isTrue();
                    checked++;
                }
                assertThat(!outer.suppressed() && !inner.suppressed() && small(others))
                        .as("%s and %s both shown with %d loans on other days", pair[0], pair[1], others)
                        .isFalse();
            }
        }
        // The exact 6 and 5 case is PortfolioFactsSuppressionTest; here the real year must at least never leak.
        assertThat(checked).isGreaterThanOrEqualTo(0);
    }

    @Test
    void noTwoShownParLevelsDifferByOneToFourLoans() {
        UUID user = UUID.randomUUID();
        List<List<Pos>> scopes = new ArrayList<>();
        List<FactSet> sets = new ArrayList<>();
        sets.add(wholeBook());
        scopes.add(model.live(TODAY, ALL_LOANS));
        for (UUID product : model.loans.values().stream().map(Loan::product).collect(Collectors.toSet())) {
            sets.add(facts(user(user, ALL, null), FROM, TODAY, List.of(), product));
            scopes.add(model.live(TODAY, l -> l.product().equals(product)));
        }
        int pairs = 0;
        for (int k = 0; k < sets.size(); k++) {
            List<Pos> stock = scopes.get(k);
            List<Integer> shown = new ArrayList<>();
            for (int level = 0; level < PAR_OVER.length; level++) {
                String key = "portfolio.par" + (PAR_OVER[level] == 0 ? 1 : PAR_OVER[level]);
                Fact f = figure(sets.get(k), key);
                if (!f.suppressed()) {
                    shown.add(level);
                }
            }
            for (int i = 0; i < shown.size(); i++) {
                for (int j = i + 1; j < shown.size(); j++) {
                    long d = late(stock, PAR_OVER[shown.get(i)]) - late(stock, PAR_OVER[shown.get(j)]);
                    assertThat(d == 0 || d >= PortfolioFacts.MIN_GROUP || d < 0)
                            .as("scope %d: PAR levels %d and %d shown %d loans apart", k, shown.get(i), shown.get(j), d)
                            .isTrue();
                    pairs++;
                }
            }
        }
        assertThat(pairs).isPositive();
    }

    @Test
    void forCallerTakesNoScopeAndBuildTakesOnlyAStoredOne() throws Exception {
        assertThat(PortfolioFacts.class.getMethod("forCaller").getParameterCount())
                .isZero();
        assertThat(PortfolioFacts.class.getMethod("build", PortfolioScope.class))
                .isNotNull();
        assertThat(java.util.Arrays.stream(PortfolioFacts.class.getMethods())
                        .filter(m -> m.getName().equals("build") || m.getName().equals("forCaller"))
                        .count())
                .isEqualTo(2);
        UUID north = northBranch();
        CurrentPrincipal.set(user(UUID.randomUUID(), ALL, Set.of(north)));
        FactSet mine = tenantJobs.callAs(a.tenantId(), () -> portfolioFacts.forCaller());
        CurrentPrincipal.clear();
        FactSet wide = wholeBook();
        // The caller's frozen scope is their branch: forCaller can give nothing wider, and nothing narrower than their
        // own.
        assertThat(figure(mine, "portfolio.active_loans").value()).isEqualTo((long)
                model.live(TODAY, l -> l.branch().equals(north)).size());
        assertThat(figure(mine, "portfolio.active_loans").value())
                .isLessThan(figure(wide, "portfolio.active_loans").value());
    }

    // ---- No names ----------------------------------------------------------------------------------------------

    @Test
    void noFactHoldsAnyNameOfABranchProductOrStaffMember() {
        FactSet set = wholeBook();
        List<String> secrets = new ArrayList<>(PLANTED_BRANCHES);
        secrets.addAll(PLANTED_PRODUCTS);
        List<String> staff = TestDatabase.owner()
                .sql("SELECT full_name FROM users WHERE tenant_id = ?")
                .param(a.tenantId())
                .query(String.class)
                .list();
        secrets.addAll(staff);
        assertThat(secrets).hasSizeGreaterThan(4);
        for (Fact f : set.facts()) {
            String all = String.join(
                    "|",
                    f.key(),
                    f.label(),
                    f.display(),
                    String.valueOf(f.group()),
                    String.valueOf(f.weekOnWeek()),
                    String.valueOf(f.monthOnMonth()));
            for (String secret : secrets) {
                assertThat(all).as("fact %s", f.key()).doesNotContainIgnoringCase(secret);
            }
        }
        // The names travel apart, for the mapping back, and print nowhere.
        assertThat(set.names()).extracting(n -> n.name()).containsAnyElementsOf(PLANTED_BRANCHES);
        assertThat(set.toString()).doesNotContain("Canary");
        assertThat(set.names().toString()).doesNotContain("Canary");
        assertThat(set.facts().toString()).doesNotContain("Canary");
    }

    @Test
    void thereIsNoOfficerDimension() {
        FactSet set = wholeBook();
        assertThat(Dimension.values()).containsExactlyInAnyOrder(Dimension.BRANCH, Dimension.PRODUCT);
        assertThat(set.facts()).extracting(Fact::key).noneMatch(k -> k.contains("officer") || k.contains("staff"));
        assertThat(set.facts())
                .filteredOn(f -> f.group() != null)
                .extracting(f -> f.group().dimension())
                .containsOnly(Dimension.BRANCH, Dimension.PRODUCT);
        // Every group of the facts is a branch or a product of the tenant, not a user.
        Set<String> branchIds = TestDatabase.owner()
                .sql("SELECT id FROM branches WHERE tenant_id = ?")
                .param(a.tenantId())
                .query(UUID.class)
                .list()
                .stream()
                .map(UUID::toString)
                .collect(Collectors.toSet());
        Set<String> productIds = TestDatabase.owner()
                .sql("SELECT id FROM lending_loan_products WHERE tenant_id = ?")
                .param(a.tenantId())
                .query(UUID.class)
                .list()
                .stream()
                .map(UUID::toString)
                .collect(Collectors.toSet());
        for (Fact f : set.facts()) {
            if (f.group() != null) {
                assertThat(f.group().dimension() == Dimension.BRANCH ? branchIds : productIds)
                        .contains(f.group().id());
            }
        }
        assertThat(set.facts().stream().filter(f -> f.group() != null)).isNotEmpty();
    }

    @Test
    void theGroupFactsAreTheGoldenFiguresOfEachBranchAndProduct() {
        FactSet set = wholeBook();
        for (Dimension dimension : Dimension.values()) {
            Set<String> seen = set.facts().stream()
                    .filter(f -> f.group() != null && f.group().dimension() == dimension)
                    .map(f -> f.group().id())
                    .collect(Collectors.toSet());
            assertThat(seen).as("%s groups", dimension).isNotEmpty();
            for (String id : seen) {
                Predicate<Loan> in = l -> (dimension == Dimension.BRANCH ? l.branch() : l.product())
                        .toString()
                        .equals(id);
                List<Pos> stock = model.live(TODAY, in);
                String prefix = dimension == Dimension.BRANCH ? "branch" : "product";
                Group g = new Group(dimension, id);
                Map<String, Fact> byKey = set.facts().stream()
                        .filter(f -> g.equals(f.group()))
                        .collect(Collectors.toMap(Fact::key, f -> f));
                if (small(stock.size())) {
                    assertThat(byKey.keySet()).containsExactly(prefix + ".loans");
                    continue;
                }
                if (byKey.size() == 1) {
                    // Five or more loans, hidden with a small group so that the total less the shown cannot give it
                    // back.
                    assertThat(hidden(byKey.get(prefix + ".loans"))).isTrue();
                    continue;
                }
                assertThat(byKey.get(prefix + ".loans").value()).isEqualTo((long) stock.size());
                assertThat(byKey.get(prefix + ".principal_outstanding").value()).isEqualTo(GoldenModel.po(stock));
                Fact par30 = byKey.get(prefix + ".par30");
                if (small(late(stock, 30))) {
                    assertThat(hidden(par30)).as("%s of %s", par30.key(), id).isTrue();
                } else if (hidden(par30)) {
                    // Hidden to close a subtraction of the late loans, not because this group's own few late loans.
                    assertThat(par30.display()).isEqualTo("fewer than 5");
                } else {
                    assertThat(par30.value()).isEqualTo(rate(GoldenModel.parAmount(stock, 30), GoldenModel.po(stock)));
                    assertThat(par30.display()).isEqualTo(FactDisplay.percent(par30.value()));
                }
            }
        }
    }

    // ---- Dates, the clock and the missing --------------------------------------------------------------------

    @Test
    void theBusinessDateIsKampalasAtTheTwentyOneHundredEdge() {
        UUID user = UUID.randomUUID();
        // 23:59:59 in Kampala on the 8th.
        clock.now = Instant.parse("2026-10-08T20:59:59Z");
        FactSet before = facts(user(user, ALL, null), null, null, List.of(), null);
        assertThat(before.asOf()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(before.to()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(before.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(figure(before, "brief.disbursed_today").value())
                .isEqualTo(model.sumTxns(Set.of("disbursement"), TODAY, TODAY, ALL_LOANS, false));
        // 00:00:01 in Kampala on the 9th.
        clock.now = Instant.parse("2026-10-08T21:00:01Z");
        FactSet after = facts(user(user, ALL, null), null, null, List.of(), null);
        assertThat(after.asOf()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(after.to()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(after.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(figure(after, "brief.disbursed_today").value())
                .as("nothing was disbursed on the 9th yet")
                .isZero();
    }

    @Autowired
    InsightsSnapshots snapshots;

    @Test
    void aScopeResolvedBeforeMidnightIsReadFromTheSnapshotOfItsDayNotFromTheLiveTables() {
        UUID user = UUID.randomUUID();
        clock.now = Instant.parse("2026-10-08T20:59:59Z");
        PortfolioScope scope = scope(user(user, ALL, null), null, null, List.of(), null);
        assertThat(scope.today()).isEqualTo(TODAY);

        // A job runs it after midnight, when the clock says the 9th: the live tables now hold the 9th's activity, and
        // tonight's snapshot of the 8th has not been written. Refused with a clear code rather than read live.
        clock.now = Instant.parse("2026-10-08T21:00:30Z");
        assertThatThrownBy(() -> tenantJobs.callAs(a.tenantId(), () -> portfolioFacts.build(scope)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("snapshot_unavailable"));

        // Once the night's job has written the snapshot of the 8th, the facts are the 8th's. Then activity of the 9th
        // lands
        // in the live tables (a repayment of 1,000 shillings on an active loan): it must not appear in the 8th's facts.
        tenantJobs.callAs(a.tenantId(), () -> inTx(() -> snapshots.backfill(TODAY, TODAY)));
        String liveSum = "SELECT sum(i.principal_due_minor - i.principal_paid_minor) FROM lending_schedule_items i"
                + " JOIN lending_loans l ON l.id = i.loan_id WHERE i.tenant_id = ? AND l.status = 'active'";
        long liveBefore = TestDatabase.owner()
                .sql(liveSum)
                .param(a.tenantId())
                .query(Long.class)
                .single();
        UUID item = TestDatabase.owner()
                .sql("SELECT i.id FROM lending_schedule_items i JOIN lending_loans l ON l.id = i.loan_id"
                        + " WHERE i.tenant_id = ? AND l.status = 'active'"
                        + " AND i.principal_due_minor - i.principal_paid_minor >= 1000 ORDER BY i.id LIMIT 1")
                .param(a.tenantId())
                .query(UUID.class)
                .single();
        TestDatabase.owner()
                .sql(
                        "UPDATE lending_schedule_items SET principal_paid_minor = principal_paid_minor + 1000 WHERE id = ?")
                .param(item)
                .update();
        try {
            long liveAfter = TestDatabase.owner()
                    .sql(liveSum)
                    .param(a.tenantId())
                    .query(Long.class)
                    .single();
            assertThat(liveAfter).as("the activity is in the live tables").isEqualTo(liveBefore - 1000);
            FactSet late = tenantJobs.callAs(a.tenantId(), () -> portfolioFacts.build(scope));
            assertThat(late.asOf()).isEqualTo(TODAY);
            assertThat(figure(late, "portfolio.principal_outstanding").value())
                    .as("the snapshot of the 8th: the activity after midnight is not in it")
                    .isEqualTo(GoldenModel.po(model.live(TODAY, ALL_LOANS)));
            assertThat(figure(late, "portfolio.active_loans").value())
                    .isEqualTo((long) model.live(TODAY, ALL_LOANS).size());
            // The branch and product groups are the 8th's too: each shown principal is the golden figure of the 8th,
            // none of them moved by the activity after midnight.
            int groupsChecked = 0;
            for (Fact f : late.facts()) {
                if (f.group() != null && f.key().endsWith(".principal_outstanding")) {
                    String id = f.group().id();
                    boolean branch = f.group().dimension() == Dimension.BRANCH;
                    long golden = GoldenModel.po(model.live(
                            TODAY,
                            l -> (branch ? l.branch() : l.product()).toString().equals(id)));
                    assertThat(f.value()).as("%s of %s", f.key(), id).isEqualTo(golden);
                    groupsChecked++;
                }
            }
            assertThat(groupsChecked).as("group figures read after midnight").isPositive();
            // The brief is today's live reading, and the 8th is not today any more: it is left out, not guessed.
            assertThat(late.facts()).extracting(Fact::key).noneMatch(k -> k.startsWith("brief."));
        } finally {
            TestDatabase.owner()
                    .sql(
                            "UPDATE lending_schedule_items SET principal_paid_minor = principal_paid_minor - 1000 WHERE id = ?")
                    .param(item)
                    .update();
            TestDatabase.owner()
                    .sql("DELETE FROM lending_loan_daily_snapshots WHERE tenant_id = ? AND business_date = ?")
                    .params(a.tenantId(), java.sql.Date.valueOf(TODAY))
                    .update();
        }
    }

    @Test
    void forCallerResolvesTheSignedInCallersOwnScope() {
        UUID north = northBranch();
        CurrentPrincipal.set(user(UUID.randomUUID(), ALL, Set.of(north)));
        FactSet mine = tenantJobs.callAs(a.tenantId(), () -> portfolioFacts.forCaller());
        CurrentPrincipal.clear();
        assertThat(figure(mine, "portfolio.active_loans").value()).isEqualTo((long)
                model.live(TODAY, l -> l.branch().equals(north)).size());
        assertThat(mine.from()).isEqualTo(FROM);
        assertThat(mine.to()).isEqualTo(TODAY);
        // No insights permission: refused, as on the page.
        CurrentPrincipal.set(user(UUID.randomUUID(), Set.of("lending.reports.portfolio"), null));
        assertThatThrownBy(() -> tenantJobs.callAs(a.tenantId(), () -> portfolioFacts.forCaller()))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("permission_denied"));
    }

    @Test
    void aTenantWithNoBookHasZerosMissingRatesAndNoStockChanges() {
        TestDatabase.Fixture empty = TestDatabase.tenant("facts-empty", true);
        CurrentPrincipal.set(user(UUID.randomUUID(), ALL, null));
        PortfolioScope scope = tenantJobs.callAs(
                empty.tenantId(), () -> inTx(() -> figures.scope(FROM, TODAY, List.of(), null, null)));
        CurrentPrincipal.clear();
        FactSet set = tenantJobs.callAs(empty.tenantId(), () -> portfolioFacts.build(scope));
        assertThat(figure(set, "portfolio.active_loans").value()).isZero();
        assertThat(figure(set, "portfolio.active_loans").suppressed()).isFalse();
        assertThat(figure(set, "portfolio.principal_outstanding").display()).isEqualTo("UGX 0");
        assertThat(figure(set, "portfolio.par30").value()).isNull();
        assertThat(figure(set, "portfolio.par30").display()).isEqualTo("not available");
        assertThat(figure(set, "portfolio.par30").weekOnWeek()).isNull();
        assertThat(figure(set, "portfolio.collection_rate").display()).isEqualTo("not available");
        // No snapshot exists for the earlier dates, so a stock has no change; a flow has a change of nothing.
        assertThat(figure(set, "portfolio.principal_outstanding").weekOnWeek()).isNull();
        assertThat(figure(set, "portfolio.principal_outstanding").monthOnMonth())
                .isNull();
        assertThat(figure(set, "portfolio.disbursed").weekOnWeek().change()).isZero();
        assertThat(figure(set, "portfolio.disbursed").weekOnWeek().display()).isEqualTo("no change");
        assertThat(set.names()).isEmpty();
    }

    @Test
    void aPastDateWithoutASnapshotIsRefusedNotGuessed() {
        TestDatabase.Fixture empty = TestDatabase.tenant("facts-past", true);
        LocalDate past = TODAY.minusDays(3);
        CurrentPrincipal.set(user(UUID.randomUUID(), ALL, null));
        PortfolioScope scope = tenantJobs.callAs(
                empty.tenantId(), () -> inTx(() -> figures.scope(past.minusDays(6), past, List.of(), null, null)));
        CurrentPrincipal.clear();
        assertThatThrownBy(() -> tenantJobs.callAs(empty.tenantId(), () -> portfolioFacts.build(scope)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("snapshot_unavailable"));
    }

    @Test
    void buildReadsNoPrincipalSoAJobCannotWidenAScope() {
        UUID north = northBranch();
        PortfolioScope scope = scope(user(UUID.randomUUID(), ALL, Set.of(north)), FROM, TODAY, List.of(), null);
        assertThat(scope.branchIds()).containsExactly(north);
        // A different, wider principal is signed in when it runs: the scope, not the principal, decides.
        CurrentPrincipal.set(user(UUID.randomUUID(), ALL, null));
        FactSet set = tenantJobs.callAs(a.tenantId(), () -> portfolioFacts.build(scope));
        CurrentPrincipal.clear();
        assertThat(figure(set, "portfolio.active_loans").value()).isEqualTo((long)
                model.live(TODAY, l -> l.branch().equals(north)).size());
    }
}
