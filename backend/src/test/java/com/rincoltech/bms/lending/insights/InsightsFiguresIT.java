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
import com.rincoltech.bms.lending.insights.InsightsFigures.ParBucket;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroup;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroupBy;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioFigures;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioGroup;
import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import com.rincoltech.bms.lending.seed.internal.LendingSeeder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * The public {@code InsightsFigures} port (extras slice D1, issue #214; ADR-038 decision 3) against the independent
 * recomputation of the insights golden model: raw rows of a fabricated year summed with plain loops, no SQL
 * aggregation and none of the code under test. Proves that the PAR figures a report prints equal the figures of the
 * insights page for the live date and for a past date read from the snapshots, per group, per branch scope and per
 * officer scope, and that the scope is resolved from the caller's permissions in the request and nowhere else. The
 * clock is pinned at 2026-10-08 (Kampala), so the fabricated year is the same on every day the suite runs.
 */
@Import(InsightsFiguresIT.FixedClock.class)
class InsightsFiguresIT extends IntegrationTest {

    static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    /** A clock the tests can move: pinned at 2026-10-08 (Kampala) unless a test moves it. */
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

    @Autowired
    MovableClock clock;

    static TestDatabase.Fixture a;
    static GoldenModel model;

    @Autowired
    LendingSeeder seeder;

    @Autowired
    InsightsFigures figures;

    @Autowired
    InsightsSnapshots snapshotWriter;

    @Autowired
    TenantJobs tenantJobs;

    @BeforeEach
    void seededOnce() {
        clock.now = NOW;
        if (a != null) {
            return;
        }
        a = TestDatabase.tenant("figures", true);
        seeder.seed(a.slug(), new LendingSeeder.Options(true, 1));
        model = GoldenModel.load(a.tenantId());
    }

    @AfterEach
    void signOut() {
        CurrentPrincipal.clear();
    }

    private static Principal user(UUID id, Set<String> permissions, Set<UUID> branches) {
        return Principal.uniform(id, "staff", permissions, branches == null, branches == null ? Set.of() : branches);
    }

    private static final Set<String> ALL = Set.of("lending.insights.read", "lending.insights.all_officers");

    /** The figures for a date, resolved for the principal in the request and read later without one. */
    private ParFigures par(Principal who, LocalDate asAt, List<UUID> branches, ParGroupBy by) {
        CurrentPrincipal.set(who);
        PortfolioScope scope =
                tenantJobs.callAs(a.tenantId(), () -> inTx(() -> figures.scope(asAt, asAt, branches, null, null)));
        CurrentPrincipal.clear();
        return tenantJobs.callAs(a.tenantId(), () -> figures.par(scope, by, () -> {}));
    }

    private <T> T inTx(java.util.function.Supplier<T> work) {
        return new org.springframework.transaction.support.TransactionTemplate(txManager).execute(s -> work.get());
    }

    @Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;

    /** Compares one group with the model's positions: loans, principal, arrears, PAR in basis points, and the buckets. */
    private static void matches(ParGroup g, List<Pos> positions) {
        long po = GoldenModel.po(positions);
        assertThat(g.loans()).isEqualTo(positions.size());
        assertThat(g.principalOutstandingMinor()).isEqualTo(po);
        assertThat(g.arrearsMinor())
                .isEqualTo(positions.stream().mapToLong(Pos::arrears).sum());
        Function<Integer, Long> par =
                over -> po == 0 ? null : GoldenModel.bp(GoldenModel.parAmount(positions, over), po);
        assertThat(g.par1Bp()).isEqualTo(par.apply(0));
        assertThat(g.par30Bp()).isEqualTo(par.apply(30));
        assertThat(g.par60Bp()).isEqualTo(par.apply(60));
        assertThat(g.par90Bp()).isEqualTo(par.apply(90));
        // The buckets are the five age bands by days past due, and add up to the group.
        int[][] bands = {{0, 0}, {1, 30}, {31, 60}, {61, 90}, {91, Integer.MAX_VALUE}};
        assertThat(g.buckets())
                .extracting(ParBucket::bucket)
                .containsExactly("current", "1_30", "31_60", "61_90", "over_90");
        long loans = 0;
        long principal = 0;
        for (int i = 0; i < 5; i++) {
            int lo = bands[i][0];
            int hi = bands[i][1];
            List<Pos> in = positions.stream()
                    .filter(p -> p.dpd() >= lo && p.dpd() <= hi)
                    .toList();
            ParBucket b = g.buckets().get(i);
            assertThat(b.loans()).as("loans in band %d", i).isEqualTo(in.size());
            assertThat(b.principalOutstandingMinor()).isEqualTo(GoldenModel.po(in));
            assertThat(b.arrearsMinor())
                    .isEqualTo(in.stream().mapToLong(Pos::arrears).sum());
            assertThat(b.shareBp()).isEqualTo(po == 0 ? null : GoldenModel.bp(GoldenModel.po(in), po));
            loans += b.loans();
            principal += b.principalOutstandingMinor();
        }
        assertThat(loans).isEqualTo(g.loans());
        assertThat(principal).isEqualTo(po);
    }

    private static List<Pos> live(Predicate<Loan> f) {
        return model.live(TODAY, f);
    }

    @Test
    void todaysFiguresEqualTheModelsLivePositions() {
        ParFigures f = par(user(UUID.randomUUID(), ALL, null), TODAY, List.of(), ParGroupBy.NONE);
        assertThat(f.source()).isEqualTo("live");
        assertThat(f.asAt()).isEqualTo(TODAY);
        assertThat(f.currency()).isEqualTo("UGX");
        assertThat(f.groups()).isEmpty();
        List<Pos> expected = live(l -> true);
        assertThat(expected)
                .as("the fabricated year has active loans, some late")
                .isNotEmpty();
        assertThat(GoldenModel.parAmount(expected, 0)).isPositive();
        matches(f.total(), expected);
    }

    @Test
    void everyGroupEqualsTheModelFilteredToIt() {
        Principal who = user(UUID.randomUUID(), ALL, null);
        for (ParGroupBy by : List.of(ParGroupBy.BRANCH, ParGroupBy.PRODUCT, ParGroupBy.OFFICER)) {
            ParFigures f = par(who, TODAY, List.of(), by);
            assertThat(f.groups()).as("%s groups", by).isNotEmpty();
            long loans = 0;
            for (ParGroup g : f.groups()) {
                UUID key = UUID.fromString(g.key());
                Predicate<Loan> in = switch (by) {
                    case BRANCH -> l -> l.branch().equals(key);
                    case PRODUCT -> l -> l.product().equals(key);
                    default -> l -> key.equals(l.officer());
                };
                matches(g, live(in));
                loans += g.loans();
            }
            assertThat(loans)
                    .as("the %s groups add up to the book", by)
                    .isEqualTo(f.total().loans());
            assertThat(f.groups()).extracting(ParGroup::label).isSorted();
        }
    }

    @Test
    void aBranchScopedCallerGetsOnlyTheirBranchWhateverTheyAskFor() {
        UUID north = model.loans.values().stream()
                .map(Loan::branch)
                .distinct()
                .filter(b -> !b.equals(a.headOffice()))
                .findFirst()
                .orElseThrow();
        // Held at the north branch only: asking for every branch, or for the other one, gives the north branch's loans
        // or none.
        Principal northOnly = user(UUID.randomUUID(), ALL, Set.of(north));
        matches(
                par(northOnly, TODAY, List.of(), ParGroupBy.NONE).total(),
                live(l -> l.branch().equals(north)));
        ParFigures other = par(northOnly, TODAY, List.of(a.headOffice()), ParGroupBy.NONE);
        assertThat(other.total().loans()).isZero();
        assertThat(other.total().par30Bp()).as("no loans, no ratio").isNull();
        // A caller who may see every branch can narrow to one.
        matches(
                par(user(UUID.randomUUID(), ALL, null), TODAY, List.of(north), ParGroupBy.NONE)
                        .total(),
                live(l -> l.branch().equals(north)));
    }

    @Test
    void withoutAllOfficersACallerSeesOnlyTheirOwnLoans() {
        UUID officer = model.loans.values().stream()
                .filter(l -> l.status().equals("active"))
                .map(Loan::officer)
                .findFirst()
                .orElseThrow();
        Principal own = user(officer, Set.of("lending.insights.read"), null);
        matches(par(own, TODAY, List.of(), ParGroupBy.NONE).total(), live(l -> officer.equals(l.officer())));
    }

    @Test
    void aPastDateReadsTheSnapshotAndEqualsTheReplayedModel() {
        Principal who = user(UUID.randomUUID(), ALL, null);
        for (LocalDate d : List.of(TODAY.minusDays(1), TODAY.minusDays(45), TODAY.minusDays(200))) {
            ParFigures f = par(who, d, List.of(), ParGroupBy.NONE);
            assertThat(f.source()).as("source on %s", d).isEqualTo("snapshot");
            matches(f.total(), List.copyOf(model.asOf(d).values()));
        }
    }

    // JUSTIFICATION-A3: the portfolio figures of slice D2 (issue #217) are new on the port; PAR had the tests above.

    /** As {@link #par}, for the portfolio figures: resolved in the request, read later without a principal. */
    private PortfolioFigures portfolio(Principal who, LocalDate asAt, List<UUID> branches, ParGroupBy by) {
        CurrentPrincipal.set(who);
        PortfolioScope scope =
                tenantJobs.callAs(a.tenantId(), () -> inTx(() -> figures.scope(asAt, asAt, branches, null, null)));
        CurrentPrincipal.clear();
        return tenantJobs.callAs(a.tenantId(), () -> figures.portfolio(scope, by, () -> {}));
    }

    /**
     * One group against the model's positions, summed with plain loops: loans, the distinct members that hold them,
     * principal and interest outstanding, arrears, and the average principal per loan (half up, none without loans).
     */
    private static void matchesPortfolio(PortfolioGroup g, List<Pos> positions) {
        Set<UUID> borrowers = new java.util.HashSet<>();
        long principal = 0;
        long interest = 0;
        long arrears = 0;
        for (Pos p : positions) {
            borrowers.add(model.loans.get(p.loan()).member());
            principal += p.po();
            interest += p.io();
            arrears += p.arrears();
        }
        assertThat(g.loans()).as("loans").isEqualTo(positions.size());
        assertThat(g.borrowers()).as("distinct borrowers").isEqualTo(borrowers.size());
        assertThat(g.principalOutstandingMinor()).as("principal").isEqualTo(principal);
        assertThat(g.interestOutstandingMinor()).as("interest").isEqualTo(interest);
        assertThat(g.arrearsMinor()).as("arrears").isEqualTo(arrears);
        long n = positions.size();
        assertThat(g.averagePrincipalMinor())
                .as("average principal, half up")
                .isEqualTo(n == 0 ? null : Math.floorDiv(principal * 2 + n, n * 2));
    }

    @Test
    void thePortfolioFiguresEqualTheModelTodayTotalAndPerGroupAndBorrowersAreDistinctPerGroup() {
        Principal who = user(UUID.randomUUID(), ALL, null);
        PortfolioFigures all = portfolio(who, TODAY, List.of(), ParGroupBy.NONE);
        assertThat(all.source()).isEqualTo("live");
        assertThat(all.groups()).isEmpty();
        List<Pos> expected = live(l -> true);
        assertThat(expected).isNotEmpty();
        matchesPortfolio(all.total(), expected);
        for (ParGroupBy by : List.of(ParGroupBy.BRANCH, ParGroupBy.PRODUCT, ParGroupBy.OFFICER)) {
            PortfolioFigures f = portfolio(who, TODAY, List.of(), by);
            assertThat(f.total().borrowers()).isEqualTo(all.total().borrowers());
            long loans = 0;
            long borrowers = 0;
            for (PortfolioGroup g : f.groups()) {
                UUID key = UUID.fromString(g.key());
                Predicate<Loan> in = switch (by) {
                    case BRANCH -> l -> l.branch().equals(key);
                    case PRODUCT -> l -> l.product().equals(key);
                    default -> l -> key.equals(l.officer());
                };
                matchesPortfolio(g, live(in));
                loans += g.loans();
                borrowers += g.borrowers();
            }
            assertThat(loans)
                    .as("the %s groups add up to the book", by)
                    .isEqualTo(f.total().loans());
            assertThat(borrowers)
                    .as("a borrower counts once in each group, so the groups hold at least the total's borrowers")
                    .isGreaterThanOrEqualTo(f.total().borrowers());
            assertThat(f.groups()).extracting(PortfolioGroup::label).isSorted();
        }
    }

    @Test
    void thePortfolioFiguresOfAPastDateEqualTheReplayedModelTotalAndPerBranch() {
        Principal who = user(UUID.randomUUID(), ALL, null);
        for (LocalDate d : List.of(TODAY.minusDays(1), TODAY.minusDays(45), TODAY.minusDays(200))) {
            PortfolioFigures f = portfolio(who, d, List.of(), ParGroupBy.BRANCH);
            assertThat(f.source()).as("source on %s", d).isEqualTo("snapshot");
            List<Pos> positions = List.copyOf(model.asOf(d).values());
            matchesPortfolio(f.total(), positions);
            for (PortfolioGroup g : f.groups()) {
                UUID branch = UUID.fromString(g.key());
                matchesPortfolio(
                        g,
                        positions.stream()
                                .filter(p -> model.loans.get(p.loan()).branch().equals(branch))
                                .toList());
            }
        }
    }

    @Test
    void thePortfolioOfABranchScopedAndOfAnOfficerScopedCallerIsTheirOwn() {
        UUID north = model.loans.values().stream()
                .map(Loan::branch)
                .distinct()
                .filter(b -> !b.equals(a.headOffice()))
                .findFirst()
                .orElseThrow();
        matchesPortfolio(
                portfolio(user(UUID.randomUUID(), ALL, Set.of(north)), TODAY, List.of(), ParGroupBy.NONE)
                        .total(),
                live(l -> l.branch().equals(north)));
        PortfolioFigures other = portfolio(
                user(UUID.randomUUID(), ALL, Set.of(north)), TODAY, List.of(a.headOffice()), ParGroupBy.BRANCH);
        assertThat(other.total().loans()).isZero();
        assertThat(other.total().borrowers()).isZero();
        assertThat(other.total().averagePrincipalMinor()).isNull();
        assertThat(other.groups()).isEmpty();
        UUID officer = model.loans.values().stream()
                .filter(l -> l.status().equals("active"))
                .map(Loan::officer)
                .findFirst()
                .orElseThrow();
        matchesPortfolio(
                portfolio(user(officer, Set.of("lending.insights.read"), null), TODAY, List.of(), ParGroupBy.NONE)
                        .total(),
                live(l -> officer.equals(l.officer())));
    }

    @Test
    void thePortfolioOfAPastDateWithNoSnapshotIsRefusedAndTheCheckpointCanStopIt() {
        TestDatabase.Fixture empty = TestDatabase.tenant("figures-empty-portfolio", true);
        PortfolioScope past = new PortfolioScope(TODAY.minusDays(3), TODAY.minusDays(3), TODAY, null, null, null);
        assertThatThrownBy(() ->
                        tenantJobs.callAs(empty.tenantId(), () -> figures.portfolio(past, ParGroupBy.NONE, () -> {})))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("snapshot_unavailable"));
        PortfolioScope todayScope = new PortfolioScope(TODAY, TODAY, TODAY, null, null, null);
        PortfolioFigures none =
                tenantJobs.callAs(empty.tenantId(), () -> figures.portfolio(todayScope, ParGroupBy.OFFICER, () -> {}));
        assertThat(none.total().loans()).isZero();
        assertThat(none.groups()).isEmpty();
        // A checkpoint that throws stops the read before any figure is made, as a cancelled run does.
        int[] calls = {0};
        assertThatThrownBy(() -> tenantJobs.callAs(
                        a.tenantId(),
                        () -> figures.portfolio(todayScope, ParGroupBy.BRANCH, () -> {
                            if (++calls[0] == 1) {
                                throw new IllegalStateException("cancelled");
                            }
                        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cancelled");
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void aPastDateWithNoSnapshotIsRefusedNotGuessed() {
        TestDatabase.Fixture empty = TestDatabase.tenant("figures-empty", true);
        CurrentPrincipal.set(user(UUID.randomUUID(), ALL, null));
        PortfolioScope scope = tenantJobs.callAs(
                empty.tenantId(),
                () -> inTx(() -> figures.scope(TODAY.minusDays(3), TODAY.minusDays(3), List.of(), null, null)));
        CurrentPrincipal.clear();
        assertThatThrownBy(
                        () -> tenantJobs.callAs(empty.tenantId(), () -> figures.par(scope, ParGroupBy.NONE, () -> {})))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("snapshot_unavailable"));
        // Today is always live, even with no loans: zeros and no ratios.
        ParFigures today = tenantJobs.callAs(
                empty.tenantId(),
                () -> figures.par(
                        new PortfolioScope(TODAY, TODAY, TODAY, null, null, null), ParGroupBy.NONE, () -> {}));
        assertThat(today.source()).isEqualTo("live");
        assertThat(today.total().loans()).isZero();
        assertThat(today.total().par1Bp()).isNull();
    }

    @Test
    void theScopeIsResolvedForTheSignedInCallerOnly() {
        // No insights permission: refused, whatever other permissions the caller holds.
        CurrentPrincipal.set(user(UUID.randomUUID(), Set.of("lending.reports.portfolio"), null));
        assertThatThrownBy(() -> tenantJobs.callAs(
                        a.tenantId(), () -> inTx(() -> figures.scope(TODAY, TODAY, List.of(), null, null))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("permission_denied"));
        // A date in the future is refused, as on the insights page.
        CurrentPrincipal.set(user(UUID.randomUUID(), ALL, null));
        assertThatThrownBy(() -> tenantJobs.callAs(
                        a.tenantId(),
                        () -> inTx(() -> figures.scope(TODAY.plusDays(1), TODAY.plusDays(1), List.of(), null, null))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("range_in_future"));
        // Without all_officers the officer is forced to the caller, whoever the filter names.
        UUID me = UUID.randomUUID();
        CurrentPrincipal.set(user(me, Set.of("lending.insights.read"), null));
        PortfolioScope own = tenantJobs.callAs(
                a.tenantId(), () -> inTx(() -> figures.scope(TODAY, TODAY, List.of(), UUID.randomUUID(), null)));
        assertThat(own.officerId()).isEqualTo(me);
        assertThat(own.branchIds()).as("every branch of an all-branch caller").isNull();
        // The result is a plain value: reading with it needs no principal.
        CurrentPrincipal.clear();
        assertThat(tenantJobs
                        .callAs(a.tenantId(), () -> figures.par(own, ParGroupBy.NONE, () -> {}))
                        .total())
                .isNotNull();
    }

    // JUSTIFICATION-A3: new test cases for the checkpoint, the frozen business date and covers(), no existing case
    // does.
    @Test
    void theCheckpointIsCalledWhileTheLoansAreReadAndCanStopTheRead() {
        // The seeded book has far fewer than 1,000 loans, so the checkpoint runs at the start and the end of the read.
        int[] calls = {0};
        CurrentPrincipal.set(user(UUID.randomUUID(), ALL, null));
        PortfolioScope scope =
                tenantJobs.callAs(a.tenantId(), () -> inTx(() -> figures.scope(TODAY, TODAY, List.of(), null, null)));
        CurrentPrincipal.clear();
        tenantJobs.callAs(a.tenantId(), () -> figures.par(scope, ParGroupBy.NONE, () -> calls[0]++));
        assertThat(calls[0]).isGreaterThanOrEqualTo(2);
        // A checkpoint that throws ends the read at once: that is how a cancelled run stops.
        assertThatThrownBy(() -> tenantJobs.callAs(
                        a.tenantId(),
                        () -> figures.par(scope, ParGroupBy.NONE, () -> {
                            throw new IllegalStateException("stop");
                        })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stop");
    }

    @Test
    void aScopeIsReadOnTheBusinessDateItWasResolvedOn() {
        CurrentPrincipal.set(user(UUID.randomUUID(), ALL, null));
        PortfolioScope scope =
                tenantJobs.callAs(a.tenantId(), () -> inTx(() -> figures.scope(null, null, List.of(), null, null)));
        CurrentPrincipal.clear();
        assertThat(scope.today()).isEqualTo(TODAY);
        assertThat(scope.to()).isEqualTo(TODAY);

        // The run is executed after midnight: the clock is now on 2026-10-09 in Kampala, and tonight's snapshot of the
        // 8th has
        // not been written. The loan tables now hold the 9th's activity, so they are no longer the 8th's book: the
        // figures
        // are refused with a retry hint, never read live under the 8th.
        clock.now = Instant.parse("2026-10-08T21:00:30Z");
        assertThatThrownBy(() -> tenantJobs.callAs(a.tenantId(), () -> figures.par(scope, ParGroupBy.NONE, () -> {})))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("report_stale_as_of"));
        assertThatThrownBy(() ->
                        tenantJobs.callAs(a.tenantId(), () -> figures.portfolio(scope, ParGroupBy.NONE, () -> {})))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("report_stale_as_of"));
        // Resolved after midnight instead, the same date is a past date and has no snapshot: that is what the frozen
        // date avoids.
        PortfolioScope late = new PortfolioScope(TODAY, TODAY, TODAY.plusDays(1), null, null, null);
        assertThatThrownBy(() -> tenantJobs.callAs(a.tenantId(), () -> figures.par(late, ParGroupBy.NONE, () -> {})))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("snapshot_unavailable"));
        // Once the nightly job has written the 8th, the same scope reads that snapshot, which is the 8th's book.
        tenantJobs.callAs(a.tenantId(), () -> inTx(() -> snapshotWriter.backfill(TODAY, TODAY)));
        ParFigures later = tenantJobs.callAs(a.tenantId(), () -> figures.par(scope, ParGroupBy.NONE, () -> {}));
        assertThat(later.source()).isEqualTo("snapshot");
        assertThat(later.asAt()).isEqualTo(TODAY);
        matches(later.total(), live(l -> true));
        // On the day itself the same scope is still live.
        clock.now = NOW;
        assertThat(tenantJobs
                        .callAs(a.tenantId(), () -> figures.par(scope, ParGroupBy.NONE, () -> {}))
                        .source())
                .isEqualTo("live");
    }

    @Test
    void aCallerCoversTheScopeOnlyWhileTheirAccessIsAsWideAsTheScope() {
        UUID north = model.loans.values().stream()
                .map(Loan::branch)
                .distinct()
                .filter(b -> !b.equals(a.headOffice()))
                .findFirst()
                .orElseThrow();
        PortfolioScope everyBranch = new PortfolioScope(TODAY, TODAY, TODAY, null, null, null);
        PortfolioScope northOnly = new PortfolioScope(TODAY, TODAY, TODAY, List.of(north), null, null);
        PortfolioScope nobody = new PortfolioScope(TODAY, TODAY, TODAY, List.of(), null, null);
        UUID me = UUID.randomUUID();

        CurrentPrincipal.set(user(me, ALL, null));
        assertThat(figures.covers(everyBranch)).isTrue();
        assertThat(figures.covers(northOnly)).isTrue();
        CurrentPrincipal.set(user(me, ALL, Set.of(north)));
        assertThat(figures.covers(everyBranch)).as("narrower now than the file").isFalse();
        assertThat(figures.covers(northOnly)).isTrue();
        assertThat(figures.covers(nobody)).isTrue();
        CurrentPrincipal.set(user(me, ALL, Set.of(a.headOffice())));
        assertThat(figures.covers(northOnly)).as("a different branch now").isFalse();
        // The officer: a file made for everyone's loans is not for a caller who now sees only their own.
        CurrentPrincipal.set(user(me, Set.of("lending.insights.read"), null));
        assertThat(figures.covers(everyBranch)).isFalse();
        assertThat(figures.covers(new PortfolioScope(TODAY, TODAY, TODAY, null, me, null)))
                .isTrue();
        assertThat(figures.covers(new PortfolioScope(TODAY, TODAY, TODAY, null, UUID.randomUUID(), null)))
                .isFalse();
        // No insights access at all.
        CurrentPrincipal.set(user(me, Set.of("lending.reports.portfolio"), null));
        assertThat(figures.covers(everyBranch)).isFalse();
    }
}
