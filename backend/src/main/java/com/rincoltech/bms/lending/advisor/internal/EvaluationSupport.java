package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The seam between the evaluation harness (public, in {@code lending.advisor}) and the internals it runs: building the
 * request for a fact set and checking it before it would be sent, and validating an answer. Public only because the
 * harness lives one package up; nothing outside the module uses it.
 */
public final class EvaluationSupport {

    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{6,}");
    private static final Pattern ISO_DATE =
            Pattern.compile("(?<!\\d)(?:19|20)\\d{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])(?!\\d)");

    private EvaluationSupport() {}

    /** A request built for a fact set, and whether it is clean to send. */
    public static final class Prepared {
        private final AliasedFacts facts;
        private final LlmClient.Request request;
        private final boolean clean;

        private Prepared(AliasedFacts facts, LlmClient.Request request, boolean clean) {
            this.facts = facts;
            this.request = request;
            this.clean = clean;
        }

        public LlmClient.Request request() {
            return request;
        }

        public boolean clean() {
            return clean;
        }
    }

    /** The verdict on an answer: accepted or not, the distinct violation codes, and the text shown if accepted. */
    public record Verdict(
            boolean accepted, List<String> codes, String shown, List<String> shownPoints, List<String> fallback) {}

    /**
     * Builds the request for the facts and checks it: no real name of a group and none of the planted strings, no
     * phone, email, ID or UUID shape, no run of six digits (the gateway's input screen refuses it), and no figure for a
     * group that has a suppressed ("fewer than 5") fact.
     */
    public static Prepared prepare(FactSet set, List<String> planted) {
        AliasedFacts facts = AliasedFacts.of(set);
        LlmClient.Request request = PromptBuilder.brief(facts);
        return new Prepared(facts, request, clean(facts, request, planted));
    }

    private static boolean clean(AliasedFacts facts, LlmClient.Request request, List<String> planted) {
        StringBuilder all = new StringBuilder();
        request.messages().forEach(m -> all.append(m.content()).append('\n'));
        String normalised = Text.normalise(all.toString());
        String lower = normalised.toLowerCase(Locale.ROOT);
        List<String> secrets = new ArrayList<>(facts.aliases().names());
        secrets.addAll(planted);
        for (String secret : secrets) {
            String s = Text.normalise(secret).toLowerCase(Locale.ROOT);
            if (s.strip().length() >= 3 && lower.contains(s)) {
                return false;
            }
        }
        // The shape screen runs over the facts the model is given; the system prompt is a reviewed resource that the
        // catalogue test proves free of digits, addresses and markup, so it is not scanned for every case.
        StringBuilder user = new StringBuilder();
        request.messages().stream()
                .filter(m -> !"system".equals(m.role()))
                .forEach(m -> user.append(m.content()).append('\n'));
        if (NumericValidator.looksPersonal(Text.normalise(user.toString()))) {
            return false;
        }
        if (DIGIT_RUN.matcher(ISO_DATE.matcher(normalised).replaceAll(" ")).find()) {
            return false;
        }
        Set<String> small = new HashSet<>();
        for (PromptFact f : facts.facts()) {
            if (f.alias() != null && f.suppressed() && f.key().endsWith(".loans")) {
                small.add(f.alias());
            }
        }
        return facts.facts().stream().noneMatch(f -> f.alias() != null && small.contains(f.alias()) && !f.suppressed());
    }

    /** What the person sees when there is no usable answer (a failed call, a refused answer): the deterministic brief. */
    public static List<String> fallback(Prepared prepared) {
        return Fallback.render(prepared.facts).points().stream()
                .map(p -> prepared.facts.aliases().toNames(p.text()))
                .toList();
    }

    public static Verdict validate(Prepared prepared, String answer, List<String> planted) {
        NumericValidator.Result result =
                StructuredValidator.validate(answer, NumericValidator.Context.of(prepared.facts));
        List<String> codes = result.violations().stream()
                .map(NumericValidator.Violation::code)
                .distinct()
                .toList();
        // A refused answer is replaced by the deterministic brief, which the person sees instead.
        AdvisorAnswer brief = result.valid() ? result.answer() : Fallback.render(prepared.facts);
        String shown = prepared.facts.aliases().toNames(brief.summary());
        List<String> points = brief.points().stream()
                .map(p -> prepared.facts.aliases().toNames(p.text()))
                .toList();
        return result.valid()
                ? new Verdict(true, codes, shown, points, List.of())
                : new Verdict(false, codes, null, List.of(), points);
    }
}
