package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * The phrases our code writes about a fact: its name, its value and, for a change, the movement (from the sign), what it is
 * compared with (from the kind of figure) and whether it is better or worse (from the metric's polarity). Nothing here is
 * ever taken from the model: the model only names which fact.
 */
final class Phrases {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    /** The stocks: a figure of a moment, compared with the same figure a week or a month ago. */
    static final Set<String> STOCKS = Set.of(
            "portfolio.principal_outstanding",
            "portfolio.active_loans",
            "portfolio.borrowers",
            "portfolio.arrears",
            "portfolio.par1",
            "portfolio.par30",
            "portfolio.par60",
            "portfolio.par90");

    /** Test switch: the phrase writes a wrong figure, to prove the second line catches a defect of this class. */
    static volatile boolean corrupt = false;

    private Phrases() {}

    static String date(LocalDate d) {
        return DAY.format(d);
    }

    /**
     * What a change is compared with, by the kind of figure it is: a brief figure (a day) with the same day a week or a month
     * earlier, a stock with its value a week or a month ago, a flow over the range with the same range a week or a month
     * earlier.
     */
    static String comparedWith(String baseKey, String period) {
        boolean week = "week".equals(period);
        if (baseKey.startsWith("brief.")) {
            return week ? "the same day last week" : "the same day last month";
        }
        if (STOCKS.contains(baseKey)) {
            return week ? "a week ago" : "a month ago";
        }
        return week ? "the same period a week earlier" : "the same period a month earlier";
    }

    /** The phrase for one fact: {@code PAR 30 (12.5 percent)} or {@code PAR 30 fell by 0.40 points compared with a week ago (better)}. */
    static String fact(PromptFact f) {
        String name = f.alias() == null ? f.metric() : f.alias() + ": " + f.metric();
        String display = corrupt ? "9" + f.display() : f.display();
        if (!f.isChange()) {
            return name + " (" + display + ")";
        }
        long change = f.value() == null ? 0 : f.value();
        String compared = "compared with " + comparedWith(f.deltaOf(), f.period());
        if (change == 0) {
            return name + " did not change " + compared;
        }
        String magnitude = display.replaceFirst("^[+-]", "");
        String how = switch (f.polarity()) {
            case HIGHER_IS_BETTER -> change > 0 ? " (better)" : " (worse)";
            case HIGHER_IS_WORSE -> change > 0 ? " (worse)" : " (better)";
            case NEUTRAL -> "";
        };
        return name + (change > 0 ? " rose by " : " fell by ") + magnitude + " " + compared + how;
    }
}
