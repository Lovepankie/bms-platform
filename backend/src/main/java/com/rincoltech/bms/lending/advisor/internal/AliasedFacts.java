package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.lending.insights.PortfolioFacts.Delta;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Fact;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Kind;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Polarity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The facts as a model sees them: a flat list in which every group figure speaks of {@code Branch 1} or
 * {@code Product A} and every week-on-week and month-on-month change is a fact of its own that a point can cite, so the
 * model quotes a movement and never computes one. No name of a branch or product is in here; the names stay in
 * {@link Aliases}, which maps an answer back for the person who asked.
 */
record AliasedFacts(
        LocalDate asOf, LocalDate from, LocalDate to, String currency, List<PromptFact> facts, Aliases aliases) {

    /**
     * One line of the prompt.
     *
     * @param key {@code portfolio.par30}, {@code branch_1.par30}, or a change: {@code portfolio.par30.week_change}
     * @param metric the figure's name without the alias, for matching a sentence to the fact it speaks of
     * @param alias {@code Branch 1}, or null for a figure of the whole scope
     * @param deltaOf the key of the figure this is the change of, or null for a figure
     * @param period {@code week} or {@code month} for a change, null for a figure
     * @param value the figure, or for a change the signed difference in the figure's units; null when none
     */
    record PromptFact(
            String key,
            String label,
            String metric,
            String alias,
            Kind kind,
            Polarity polarity,
            Long value,
            String display,
            boolean suppressed,
            String deltaOf,
            String period) {

        boolean isChange() {
            return deltaOf != null;
        }
    }

    static AliasedFacts of(FactSet set) {
        Aliases aliases = Aliases.of(set);
        List<PromptFact> out = new ArrayList<>();
        for (Fact f : set.facts()) {
            String alias = f.group() == null ? null : aliases.aliasOf(f.group());
            if (f.group() != null && alias == null) {
                // A group the fact set does not name cannot be aliased: it is left out, never sent under its id.
                continue;
            }
            String key = alias == null
                    ? f.key()
                    : alias.toLowerCase().replace(' ', '_')
                            + f.key().substring(f.key().indexOf('.'));
            String label = alias == null ? f.label() : alias + ": " + f.label();
            out.add(new PromptFact(
                    key,
                    label,
                    f.label(),
                    alias,
                    f.kind(),
                    f.polarity(),
                    f.value(),
                    f.display(),
                    f.suppressed(),
                    null,
                    null));
            change(out, f, key, label, alias, f.weekOnWeek(), "week", "change on last week");
            change(out, f, key, label, alias, f.monthOnMonth(), "month", "change on last month");
        }
        return new AliasedFacts(set.asOf(), set.from(), set.to(), set.currency(), out, aliases);
    }

    private static void change(
            List<PromptFact> out,
            Fact f,
            String key,
            String label,
            String alias,
            Delta d,
            String period,
            String words) {
        if (d == null) {
            return;
        }
        out.add(new PromptFact(
                key + "." + period + "_change",
                label + ", " + words,
                f.label(),
                alias,
                f.kind(),
                f.polarity(),
                d.change(),
                d.display(),
                false,
                key,
                period));
    }

    /**
     * The dates an answer may name without inventing one: the as-of date, the range, and the same range a week and a
     * month earlier, which the changes compare with.
     */
    Set<LocalDate> dates() {
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (LocalDate d : new LocalDate[] {asOf, from, to}) {
            if (d != null) {
                dates.add(d);
                dates.add(d.minusDays(7));
                dates.add(d.minusMonths(1));
            }
        }
        return dates;
    }
}
