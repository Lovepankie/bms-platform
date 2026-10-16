package com.rincoltech.bms.lending.insights;

import com.rincoltech.bms.lending.insights.InsightsFigures.PortfolioScope;
import java.time.LocalDate;
import java.util.List;

/**
 * The public read port through which the AI advisor reaches the portfolio figures (ADR-037 decision 3,
 * {@code docs/specs/client-extras.md} sections 5.3 and 5.4). It answers with a typed list of facts taken from the
 * metrics dictionary ({@code docs/specs/lending-insights-metrics.md}: the {@code brief.*}, {@code portfolio.*},
 * {@code revenue.*} and {@code members.*} keys), each with its display form, its polarity (is a higher value good or
 * bad) and precomputed week-on-week and month-on-month changes, so a model never needs arithmetic.
 *
 * <p>It is built from the same code the insights endpoints run, under the same scope rules: {@link #forCaller} resolves
 * the signed-in caller's scope through {@link InsightsFigures#scope} (the permitted branches of
 * {@code lending.insights.read}; without {@code lending.insights.all_officers} only the caller's own loans), and
 * {@link #build} reads from a stored scope alone, like {@link InsightsFigures#par}, so a job cannot widen what its
 * requester could see. The branch and product breakdowns come from {@link InsightsFigures#par}.
 *
 * <p>What it never returns: member names, phones, ID numbers, addresses, loan numbers, staff names (there is no officer
 * dimension in the first release), or free text. Any group or figure computed over fewer than {@link #MIN_GROUP} loans
 * or members is left out and replaced by a fact whose display is {@code fewer than 5}. A branch or product appears as a
 * {@link Group} reference; its real name is in {@link FactSet#names()} only so that the advisor can map an alias back
 * for the person who asked, and must never be put in a prompt or a log.
 */
public interface PortfolioFacts {

    /** A group or figure over fewer loans or members than this is suppressed. */
    int MIN_GROUP = 5;

    /** The facts for the signed-in caller, over the default range (the month so far, ending today). */
    FactSet forCaller();

    /**
     * The facts for a resolved scope: flows over {@code scope.from()} to {@code scope.to()}, stocks as at
     * {@code scope.to()}, the brief figures only when {@code scope.to()} is the business date. Reads no principal.
     * Changes are against the same range a week earlier and a month earlier; a change is absent when the earlier figure
     * cannot be known (no snapshot for that date, or the earlier population was under {@link #MIN_GROUP}).
     */
    FactSet build(PortfolioScope scope);

    /** The unit of a value: integer minor units, a count, basis points, days or hours. */
    enum Kind {
        MONEY,
        COUNT,
        BASIS_POINTS,
        DAYS,
        HOURS
    }

    /** Whether a higher value is good news ({@code HIGHER_IS_BETTER}) or bad news ({@code HIGHER_IS_WORSE}). */
    enum Polarity {
        HIGHER_IS_BETTER,
        HIGHER_IS_WORSE,
        NEUTRAL
    }

    /** The dimensions a breakdown can have. There is deliberately no officer. */
    enum Dimension {
        BRANCH,
        PRODUCT
    }

    /** A branch or product, by its id. The id is not a name; the name is in {@link FactSet#names()}. */
    record Group(Dimension dimension, String id) {}

    /**
     * A change against an earlier period, in the fact's own units ({@code change} is basis points for a rate).
     * {@code display} carries its sign, for example {@code +0.40 points} or {@code -UGX 120,000}.
     */
    record Delta(long change, String display) {}

    /**
     * One fact.
     *
     * @param key the dictionary key, for a group figure {@code branch.par30} or {@code product.loans}
     * @param value null when suppressed or not available
     * @param display the form to put in a prompt, for example {@code UGX 1,250,000} or {@code 12.5 percent};
     *     {@code fewer than 5} when suppressed and {@code not available} when there is none
     * @param group the branch or product the figure is about, or null for the whole scope
     * @param weekOnWeek null when not defined or not known
     */
    record Fact(
            String key,
            String label,
            Kind kind,
            Polarity polarity,
            Long value,
            String display,
            boolean suppressed,
            Delta weekOnWeek,
            Delta monthOnMonth,
            Group group) {}

    /** The real name of a group, for mapping an alias back. Never for a prompt or a log. */
    record GroupName(Group group, String name) {

        @Override
        public String toString() {
            return "GroupName[" + group + "]";
        }
    }

    /** The facts of one scope. {@code names} are kept out of {@link #toString} so that a log line cannot carry them. */
    record FactSet(
            LocalDate asOf, LocalDate from, LocalDate to, String currency, List<Fact> facts, List<GroupName> names) {

        public FactSet {
            facts = List.copyOf(facts);
            names = List.copyOf(names);
        }

        @Override
        public String toString() {
            return "FactSet[asOf=" + asOf + ", facts=" + facts.size() + ", groups=" + names.size() + "]";
        }
    }
}
