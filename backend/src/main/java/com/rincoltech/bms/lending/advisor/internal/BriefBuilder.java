package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.core.ai.AiGateway.AiResult;
import com.rincoltech.bms.lending.advisor.internal.AdvisorAnswer.Point;
import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns what came back from the gateway into the brief that is stored and shown (ADR-037 as amended in #223, spec 5.13).
 * The model's text is never kept: an answered call is run through {@link StructuredValidator}, and only the text our
 * renderer made from the catalogue and the facts is stored; an unanswered call, or an answer the validator refused, stores
 * the deterministic facts-only brief ({@link Fallback}) with the reason. Aliases are mapped back to the names of the
 * branches and products, because the stored brief is shown only to people who may see every branch.
 */
final class BriefBuilder {

    /** The reason stored when the structured answer was refused. */
    static final String VALIDATION = "validation";

    /** At most this many figures are shown as chips. */
    static final int MAX_CHIPS = 8;

    /** One figure the text rests on, for the chip under the brief. {@code metric} is the dictionary key a drill-down opens, or null. */
    record Chip(String key, String label, String display, String metric) {}

    /** What is stored and shown. {@code source} is {@code model} or {@code fallback}; {@code reason} is null for a model brief. */
    record Brief(
            String source, String reason, String summary, List<Point> points, List<Chip> chips, LocalDate factsAsOf) {

        boolean model() {
            return "model".equals(source);
        }
    }

    private BriefBuilder() {}

    /** @param result the gateway's answer; null when no call was made (the deterministic brief for a caller) */
    static Brief build(AliasedFacts facts, AiResult result) {
        if (result == null) {
            return fallback(facts, null);
        }
        if (!result.answered()) {
            return fallback(facts, result.reason().code());
        }
        NumericValidator.Result verdict =
                StructuredValidator.validate(result.text(), NumericValidator.Context.of(facts));
        if (!verdict.valid() || verdict.answer() == null) {
            return fallback(facts, VALIDATION);
        }
        return of("model", null, verdict.answer(), facts);
    }

    /** The facts-only brief, with the reason it stands in for a model brief (null when none was asked for). */
    static Brief fallback(AliasedFacts facts, String reason) {
        return of("fallback", reason, Fallback.render(facts), facts);
    }

    private static Brief of(String source, String reason, AdvisorAnswer answer, AliasedFacts facts) {
        List<Point> points = new ArrayList<>();
        for (Point p : answer.points()) {
            points.add(new Point(facts.aliases().toNames(p.text()), List.copyOf(p.facts())));
        }
        return new Brief(
                source,
                reason,
                facts.aliases().toNames(answer.summary()),
                List.copyOf(points),
                chips(points, facts),
                facts.asOf());
    }

    private static List<Chip> chips(List<Point> points, AliasedFacts facts) {
        Map<String, PromptFact> byKey =
                facts.facts().stream().collect(Collectors.toMap(PromptFact::key, Function.identity(), (a, b) -> a));
        Set<String> keys = new LinkedHashSet<>();
        points.forEach(p -> keys.addAll(p.facts()));
        List<Chip> chips = new ArrayList<>();
        for (String key : keys) {
            PromptFact f = byKey.get(key);
            if (f == null || f.suppressed() || f.display() == null || chips.size() >= MAX_CHIPS) {
                continue;
            }
            // A group figure or a change has no drill-down of its own: the chip shows the figure only.
            String metric = f.alias() == null && !f.isChange() ? f.key() : null;
            chips.add(new Chip(key, facts.aliases().toNames(f.label()), f.display(), metric));
        }
        return List.copyOf(chips);
    }
}
