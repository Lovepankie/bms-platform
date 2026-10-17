package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.lending.advisor.internal.AdvisorAnswer.Point;
import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The deterministic brief shown when the model's answer is refused (spec 5.7): the key figures of the facts, written by the
 * same phrases as everything else, with no model involved. Hidden facts are never in it.
 */
final class Fallback {

    /** The figures an owner looks at first, in order. */
    static final List<String> KEY_FIGURES = List.of(
            "portfolio.principal_outstanding",
            "portfolio.active_loans",
            "portfolio.par30",
            "portfolio.par30.week_change",
            "portfolio.collection_rate",
            "portfolio.collection_rate.week_change",
            "portfolio.arrears",
            "brief.collected_today",
            "brief.disbursed_today");

    private Fallback() {}

    static AdvisorAnswer render(AliasedFacts facts) {
        Catalogue catalogue = Catalogue.INSTANCE;
        String summary = Templates.fill(
                catalogue.template("summary"),
                Map.of(
                        "asof",
                        Phrases.date(facts.asOf()),
                        "range",
                        Phrases.date(facts.from()) + " to " + Phrases.date(facts.to())));
        List<String> keys = new ArrayList<>();
        List<String> phrases = new ArrayList<>();
        for (String key : KEY_FIGURES) {
            for (PromptFact f : facts.facts()) {
                if (f.key().equals(key) && !f.suppressed() && f.alias() == null) {
                    keys.add(key);
                    phrases.add(Phrases.fact(f));
                }
            }
        }
        if (phrases.isEmpty()) {
            return new AdvisorAnswer(summary, List.of(new Point(catalogue.template("nothing"), List.of())));
        }
        String text = Templates.fill(catalogue.template("fallback"), Map.of("facts", String.join("; ", phrases)));
        return new AdvisorAnswer(summary, List.of(new Point(text, keys)));
    }
}
