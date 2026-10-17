package com.rincoltech.bms.lending.advisor;

import com.rincoltech.bms.lending.insights.FactDisplay;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Delta;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Dimension;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Fact;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Group;
import com.rincoltech.bms.lending.insights.PortfolioFacts.GroupName;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Kind;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Polarity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds fabricated fact sets for the advisor's tests and its golden evaluation set, with the display forms of
 * production's own {@link FactDisplay}, so a test cannot drift from what a prompt really carries. Nothing here is a
 * real figure or a real name.
 */
public final class Facts {

    public static final String CURRENCY = "UGX";

    private final LocalDate asOf;
    private final List<Fact> facts = new ArrayList<>();
    private final List<GroupName> names = new ArrayList<>();

    private Facts(LocalDate asOf) {
        this.asOf = asOf;
    }

    /** A fact set as of this date (ISO), over the month so far. */
    public static Facts asOf(String date) {
        return new Facts(LocalDate.parse(date));
    }

    /** A string from code points, so that a test writes an unusual character as a number and never pastes it in. */
    public static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static Delta delta(Kind kind, Long change) {
        return change == null ? null : new Delta(change, FactDisplay.change(kind, change, CURRENCY));
    }

    /** A figure with an optional week-on-week and month-on-month change in the figure's own units. */
    public Facts fact(String key, String label, Kind kind, Polarity polarity, long value, Long week, Long month) {
        facts.add(new Fact(
                key,
                label,
                kind,
                polarity,
                value,
                FactDisplay.of(kind, value, CURRENCY),
                false,
                delta(kind, week),
                delta(kind, month),
                null));
        return this;
    }

    public Facts money(String key, String label, Polarity polarity, long value, Long week, Long month) {
        return fact(key, label, Kind.MONEY, polarity, value, week, month);
    }

    public Facts rate(String key, String label, Polarity polarity, long bp, Long week, Long month) {
        return fact(key, label, Kind.BASIS_POINTS, polarity, bp, week, month);
    }

    public Facts count(String key, String label, Polarity polarity, long value, Long week, Long month) {
        return fact(key, label, Kind.COUNT, polarity, value, week, month);
    }

    /** A figure that is not available (a rate over nothing). */
    public Facts missing(String key, String label, Kind kind, Polarity polarity) {
        facts.add(new Fact(key, label, kind, polarity, null, "not available", false, null, null, null));
        return this;
    }

    /** A figure over fewer than five loans or members. */
    public Facts small(String key, String label, Kind kind, Polarity polarity) {
        facts.add(new Fact(key, label, kind, polarity, null, "fewer than 5", true, null, null, null));
        return this;
    }

    /** A branch or product, by id and (fabricated) name. */
    public Facts group(Dimension dimension, String id, String name) {
        names.add(new GroupName(new Group(dimension, id), name));
        return this;
    }

    /** A figure of a group already added. */
    public Facts groupFact(
            Dimension dimension, String id, String key, String label, Kind kind, Polarity polarity, long value) {
        facts.add(new Fact(
                key,
                label,
                kind,
                polarity,
                value,
                FactDisplay.of(kind, value, CURRENCY),
                false,
                null,
                null,
                new Group(dimension, id)));
        return this;
    }

    /** A group with fewer than five loans: its loans fact is suppressed and it has no other. */
    public Facts smallGroup(Dimension dimension, String id) {
        String prefix = dimension == Dimension.BRANCH ? "branch" : "product";
        facts.add(new Fact(
                prefix + ".loans",
                "Active loans",
                Kind.COUNT,
                Polarity.NEUTRAL,
                null,
                "fewer than 5",
                true,
                null,
                null,
                new Group(dimension, id)));
        return this;
    }

    public FactSet build() {
        return new FactSet(asOf, asOf.withDayOfMonth(1), asOf, CURRENCY, facts, names);
    }

    /**
     * A standard set: PAR 30 down 0.40 points on last week and up 0.25 on last month, a collection rate up 1.20 points on
     * the week and down 3.00 on the month, a disbursed amount, active loans, a count with no change, a negative
     * contribution, two branches (one with figures, one small) and one product.
     */
    public static FactSet standard() {
        return asOf("2026-10-14")
                .rate("portfolio.par30", "PAR 30", Polarity.HIGHER_IS_WORSE, 1254, -40L, 25L)
                .rate("portfolio.collection_rate", "Collection rate", Polarity.HIGHER_IS_BETTER, 8750, 120L, -300L)
                .money(
                        "portfolio.principal_outstanding",
                        "Principal outstanding",
                        Polarity.NEUTRAL,
                        245_000_000L,
                        5_000_000L,
                        -2_000_000L)
                .money(
                        "brief.disbursed_today",
                        "Money out today",
                        Polarity.HIGHER_IS_BETTER,
                        1_250_000L,
                        250_000L,
                        null)
                .count("portfolio.active_loans", "Active loans", Polarity.NEUTRAL, 312, 4L, 15L)
                .count("portfolio.written_off_count", "Loans written off", Polarity.HIGHER_IS_WORSE, 3, 0L, 1L)
                .count("brief.new_arrears", "New arrears", Polarity.HIGHER_IS_WORSE, 7, null, null)
                .count("members.new_members", "New members", Polarity.NEUTRAL, 18, null, null)
                .money("revenue.contribution", "Profit contribution", Polarity.HIGHER_IS_BETTER, -500_000L, null, null)
                .rate("portfolio.yield", "Portfolio yield (annualised)", Polarity.HIGHER_IS_BETTER, 4210, null, null)
                .missing(
                        "brief.collection_rate_today",
                        "Collection rate today",
                        Kind.BASIS_POINTS,
                        Polarity.HIGHER_IS_BETTER)
                .group(Dimension.BRANCH, "b-1", "Fabricated Branch Alpha")
                .group(Dimension.BRANCH, "b-2", "Fabricated Branch Beta")
                .group(Dimension.PRODUCT, "p-1", "Fabricated Product Gamma")
                .groupFact(Dimension.BRANCH, "b-1", "branch.loans", "Active loans", Kind.COUNT, Polarity.NEUTRAL, 120)
                .groupFact(
                        Dimension.BRANCH,
                        "b-1",
                        "branch.par30",
                        "PAR 30",
                        Kind.BASIS_POINTS,
                        Polarity.HIGHER_IS_WORSE,
                        910)
                .smallGroup(Dimension.BRANCH, "b-2")
                .groupFact(Dimension.PRODUCT, "p-1", "product.loans", "Active loans", Kind.COUNT, Polarity.NEUTRAL, 88)
                .build();
    }
}
