package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.advisor.AttackCorpus;
import com.rincoltech.bms.lending.advisor.Facts;
import com.rincoltech.bms.lending.advisor.internal.Catalogue.Action;
import com.rincoltech.bms.lending.advisor.internal.Catalogue.Topic;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Context;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Result;
import com.rincoltech.bms.lending.advisor.internal.NumericValidator.Violation;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Dimension;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Kind;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Polarity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The structured validator and renderer (ADR-037 decision 5 as amended in issue #223): the schema refuses extras, every string
 * must be an enum value or a key of a fact in the prompt, and every sentence of an accepted answer is template text from the
 * catalogue or a value our code wrote. The last is checked over the whole catalogue, every action with every fact its topic
 * allows.
 */
class StructuredValidatorTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AliasedFacts FACTS = AliasedFacts.of(Facts.standard());
    static final Context CTX = Context.of(FACTS);

    static ObjectNode finding(String topic, String action, String priority, String... keys) {
        ObjectNode f = JSON.createObjectNode();
        ArrayNode k = f.putArray("fact_keys");
        for (String key : keys) {
            k.add(key);
        }
        f.put("topic", topic);
        f.put("action", action);
        f.put("priority", priority);
        return f;
    }

    static String answer(List<ObjectNode> findings, String... watch) {
        ObjectNode r = JSON.createObjectNode();
        ArrayNode f = r.putArray("findings");
        findings.forEach(f::add);
        ArrayNode w = r.putArray("watch");
        for (String key : watch) {
            w.add(key);
        }
        return r.toString();
    }

    static List<String> codes(Result r) {
        return r.violations().stream().map(Violation::code).toList();
    }

    // ---- The limits are exact -----------------------------------------------------------------------------

    @Test
    void theLimitsAreExactlyThoseOfTheSchema() {
        List<ObjectNode> six = new ArrayList<>();
        for (int i = 0; i < StructuredValidator.MAX_FINDINGS; i++) {
            six.add(finding("arrears_follow_up", "ARREARS_REVIEW_OLDEST", "low", "portfolio.par30"));
        }
        assertThat(StructuredValidator.validate(answer(six), CTX).valid()).isTrue();
        six.add(finding("arrears_follow_up", "ARREARS_REVIEW_OLDEST", "low", "portfolio.par30"));
        assertThat(codes(StructuredValidator.validate(answer(six), CTX))).contains("too_many");

        Result three = StructuredValidator.validate(
                answer(List.of(finding(
                        "arrears_follow_up",
                        "ARREARS_REVIEW_OLDEST",
                        "high",
                        "portfolio.par30",
                        "portfolio.par30.week_change",
                        "portfolio.par30.month_change"))),
                CTX);
        assertThat(three.valid()).isTrue();

        String sixWatch = answer(
                List.of(),
                "portfolio.par30",
                "portfolio.par30.week_change",
                "portfolio.par30.month_change",
                "portfolio.collection_rate",
                "portfolio.collection_rate.week_change",
                "portfolio.principal_outstanding");
        assertThat(StructuredValidator.validate(sixWatch, CTX).valid()).isTrue();
        assertThat(StructuredValidator.MAX_RAW_CHARS).isEqualTo(4000);
    }

    // ---- The schema refuses extras and anything that is not an enum value or a key ---------------------------

    @Test
    void anExtraFieldAnywhereIsRefusedAsAWholeWhateverItHolds() {
        for (String attack : AttackCorpus.all()) {
            ObjectNode root = JSON.createObjectNode();
            root.putArray("findings");
            root.putArray("watch");
            root.put("explanation", attack);
            Result r = StructuredValidator.validate(root.toString(), CTX);
            assertThat(r.valid()).as(attack).isFalse();
            assertThat(codes(r)).as(attack).contains("unknown_field");
            assertThat(r.answer()).isNull();
        }
    }

    @Test
    void everyStringIsAnEnumValueOrAKeyOfAFactInThePrompt() {
        Set<String> accepted = new LinkedHashSet<>(
                FACTS.facts().stream().map(AliasedFacts.PromptFact::key).toList());
        accepted.addAll(Catalogue.INSTANCE.topics().keySet());
        accepted.addAll(Catalogue.INSTANCE.actions().keySet());
        accepted.addAll(Catalogue.PRIORITIES);
        for (String attack : AttackCorpus.all()) {
            assertThat(accepted)
                    .as("the corpus holds a real enum value or key: %s", attack)
                    .doesNotContain(attack);
        }
        // Near misses of a real value are refused too.
        for (String near : List.of(
                "arrears_follow_up ",
                " arrears_follow_up",
                "Arrears_Follow_Up",
                "arrears-follow-up",
                "arrears_follow_up\u0000",
                "arrears_follow_up\n",
                "arrears_follоw_up")) {
            Result r = StructuredValidator.validate(
                    answer(List.of(finding(near, "ARREARS_REVIEW_OLDEST", "high", "portfolio.par30"))), CTX);
            assertThat(r.valid()).as(near).isFalse();
            assertThat(codes(r)).as(near).contains("unknown_topic");
        }
        for (String near :
                List.of("portfolio.par30 ", "Portfolio.par30", "portfolio.par30.", "portfolio.par30.week_change ")) {
            Result r = StructuredValidator.validate(
                    answer(List.of(finding("arrears_follow_up", "ARREARS_REVIEW_OLDEST", "high", near))), CTX);
            assertThat(r.valid()).as(near).isFalse();
            assertThat(codes(r)).as(near).contains("unknown_fact");
        }
    }

    @Test
    void aRefusalCarriesAClosedCodeAndNeverAStringTheModelWrote() {
        String marker = "ZQXJ-MARKER-9317";
        ObjectNode f = finding(marker, marker, marker, marker);
        f.put("extra", marker);
        ObjectNode root = JSON.createObjectNode();
        root.putArray("findings").add(f);
        root.putArray("watch").add(marker);
        root.put(marker, marker);
        Result r = StructuredValidator.validate(root.toString(), CTX);
        assertThat(r.valid()).isFalse();
        assertThat(r.violations()).isNotEmpty();
        assertThat(r.violations().toString()).doesNotContain(marker);
        for (Violation v : r.violations()) {
            assertThat(v.code()).matches("[a-z_]+");
            assertThat(v.token()).isNull();
        }
    }

    @Test
    void aMalformedInputFailsClosed() {
        for (String raw :
                new String[] {null, "", "  ", "not json", "{", "{\"findings\":[", "[]", "\"x\"", "7", "true"}) {
            Result r = StructuredValidator.validate(raw, CTX);
            assertThat(r.valid()).as(String.valueOf(raw)).isFalse();
            assertThat(r.answer()).isNull();
        }
    }

    // ---- The rendering ------------------------------------------------------------------------------------

    @Test
    void anAcceptedAnswerIsRenderedFromTheTemplatesAndTheFactsAndNothingElse() {
        Result r = StructuredValidator.validate(
                answer(
                        List.of(finding("arrears_follow_up", "ARREARS_REVIEW_OLDEST", "medium", "branch_1.par30")),
                        "portfolio.par30"),
                CTX);
        assertThat(r.valid()).isTrue();
        assertThat(r.answer().summary())
                .isEqualTo("Advisory summary as of 14 October 2026, covering 1 October 2026 to 14 October 2026."
                        + " These are suggestions for the owner to check before acting; nothing has been changed.");
        assertThat(r.answer().points().stream().map(AdvisorAnswer.Point::text))
                .containsExactly(
                        "Medium priority. Branch 1: PAR 30 (9.1 percent). Suggested action: Review the oldest arrears cases for Branch 1 this week.",
                        "To watch: PAR 30 (12.5 percent).");
    }

    @Test
    void whereNamesTheGroupsTheCitedFactsAreAbout() {
        assertThat(StructuredValidator.where(List.of())).isEmpty();
        List<AliasedFacts.PromptFact> facts = FACTS.facts();
        AliasedFacts.PromptFact branch = facts.stream()
                .filter(f -> f.key().equals("branch_1.par30"))
                .findFirst()
                .orElseThrow();
        AliasedFacts.PromptFact product = facts.stream()
                .filter(f -> f.key().equals("product_a.loans"))
                .findFirst()
                .orElseThrow();
        AliasedFacts.PromptFact total = facts.stream()
                .filter(f -> f.key().equals("portfolio.par30"))
                .findFirst()
                .orElseThrow();
        assertThat(StructuredValidator.where(List.of(total))).isEmpty();
        assertThat(StructuredValidator.where(List.of(branch, total))).isEqualTo(" for Branch 1");
        assertThat(StructuredValidator.where(List.of(branch, product))).isEqualTo(" for Branch 1 and Product A");
        assertThat(StructuredValidator.where(List.of(branch, branch))).isEqualTo(" for Branch 1");
    }

    @Test
    void aTemplateFillsInOnePassSoAValueThatLooksLikeAPlaceholderIsJustText() {
        assertThat(Templates.fill("{{a}} and {{b}}", java.util.Map.of("a", "{{b}}", "b", "x")))
                .isEqualTo("{{b}} and x");
        assertThat(Templates.fill("no placeholder", java.util.Map.of())).isEqualTo("no placeholder");
        assertThat(Templates.fill("{{missing}}.", java.util.Map.of())).isEqualTo(".");
        assertThat(Templates.fill("{{a}}", java.util.Map.of("a", "$1 \\ (x)"))).isEqualTo("$1 \\ (x)");
    }

    // ---- The whole catalogue: every action with every fact its topic allows -----------------------------------

    /** A fact set holding every family any topic may reference, with a figure, a week change and a month change each. */
    static FactSet everyFamily() {
        Facts f = Facts.asOf("2026-10-14");
        Set<String> bases = new LinkedHashSet<>();
        Catalogue.INSTANCE.topics().values().forEach(t -> bases.addAll(t.facts()));
        int i = 0;
        for (String base : bases) {
            i++;
            String label = "Figure " + Aliases.letters(i);
            if (base.startsWith("branch.")) {
                f.group(Dimension.BRANCH, "b-1", "Fabricated Branch Alpha");
                f.groupFact(Dimension.BRANCH, "b-1", base, label, Kind.COUNT, Polarity.NEUTRAL, 100 + i);
            } else if (base.startsWith("product.")) {
                f.group(Dimension.PRODUCT, "p-1", "Fabricated Product Gamma");
                f.groupFact(Dimension.PRODUCT, "p-1", base, label, Kind.COUNT, Polarity.NEUTRAL, 100 + i);
            } else {
                Polarity polarity = i % 3 == 0
                        ? Polarity.HIGHER_IS_WORSE
                        : i % 3 == 1 ? Polarity.HIGHER_IS_BETTER : Polarity.NEUTRAL;
                f.count(base, label, polarity, 100 + i, i % 2 == 0 ? 7L : -4L, i % 2 == 0 ? -9L : 12L);
            }
        }
        return f.build();
    }

    @Test
    void everyActionWithEveryFactItsTopicAllowsRendersOnlyTemplateTextAndValuesWeWrote() {
        AliasedFacts facts = AliasedFacts.of(everyFamily());
        Context ctx = Context.of(facts);
        Catalogue catalogue = Catalogue.INSTANCE;
        int rendered = 0;
        for (Action action : catalogue.actions().values()) {
            Topic topic = catalogue.topics().get(action.topic());
            List<AliasedFacts.PromptFact> allowed = facts.facts().stream()
                    .filter(f -> !f.suppressed() && topic.facts().contains(Catalogue.base(f)))
                    .toList();
            assertThat(allowed).as("facts for %s", action.code()).isNotEmpty();
            List<List<AliasedFacts.PromptFact>> combinations = new ArrayList<>();
            for (AliasedFacts.PromptFact a : allowed) {
                combinations.add(List.of(a));
            }
            List<AliasedFacts.PromptFact> head = allowed.subList(0, Math.min(10, allowed.size()));
            for (int x = 0; x < head.size(); x++) {
                for (int y = x + 1; y < head.size(); y++) {
                    combinations.add(List.of(head.get(x), head.get(y)));
                }
            }
            combinations.add(allowed.subList(0, Math.min(3, allowed.size())));
            for (List<AliasedFacts.PromptFact> used : combinations) {
                String[] keys = used.stream().map(AliasedFacts.PromptFact::key).toArray(String[]::new);
                String priority = action.calm() ? "low" : "medium";
                Result r = StructuredValidator.validate(
                        answer(List.of(finding(action.topic(), action.code(), priority, keys))), ctx);
                if (action.calm() && used.stream().anyMatch(StructuredValidator::isWorse)) {
                    // A calm action beside a worsening is refused whole, never rendered.
                    assertThat(r.valid())
                            .as("%s with %s", action.code(), List.of(keys))
                            .isFalse();
                    assertThat(r.violations()).extracting(Violation::code).containsExactly("calm_action_mismatch");
                    continue;
                }
                assertThat(r.violations())
                        .as("%s with %s", action.code(), List.of(keys))
                        .isEmpty();
                String text = r.answer().points().get(0).text();
                String expected = (action.calm() ? "Low" : "Medium") + " priority. "
                        + String.join("; ", used.stream().map(Phrases::fact).toList())
                        + ". Suggested action: "
                        + action.text().replace("{{where}}", StructuredValidator.where(used));
                assertThat(text).as("%s with %s", action.code(), List.of(keys)).isEqualTo(expected);
                // Nothing of the model's: no key, no code, no braces, no markup, no digit but those our phrases wrote.
                assertThat(text)
                        .doesNotContain("{{")
                        .doesNotContain("}}")
                        .doesNotContain(action.code())
                        .doesNotContain("_");
                for (String key : keys) {
                    assertThat(text).as("key %s in %s", key, text).doesNotContain(key);
                }
                // Hand-typed numerals cannot be in the template part: strip our values and no digit is left.
                String templatePart = text.substring(text.indexOf("Suggested action: "));
                assertThat(Pattern.compile("[\\p{Nd}]")
                                .matcher(templatePart.replaceAll("Branch \\d+|Product [A-Z]+", ""))
                                .find())
                        .as(templatePart)
                        .isFalse();
                rendered++;
            }
        }
        assertThat(rendered).isGreaterThan(1000);
    }

    // ---- A calm action: "no action is needed" ------------------------------------------------------------------

    @Test
    void anActionThatSaysNoActionIsNeededIsAllowedOnlyAtLowPriorityAndWithNoWorseChange() {
        AliasedFacts facts = AliasedFacts.of(everyFamily());
        Context ctx = Context.of(facts);
        Catalogue catalogue = Catalogue.INSTANCE;
        int calmActions = 0;
        for (Action action : catalogue.actions().values()) {
            // The wording decides: any text that says so must be marked calm, and nothing else is.
            assertThat(action.calm())
                    .as(action.code())
                    .isEqualTo(Catalogue.CALM_WORDING.matcher(action.text()).find());
            if (!action.calm()) {
                continue;
            }
            calmActions++;
            Topic topic = catalogue.topics().get(action.topic());
            List<AliasedFacts.PromptFact> inTopic = facts.facts().stream()
                    .filter(f -> !f.suppressed() && topic.facts().contains(Catalogue.base(f)))
                    .toList();
            AliasedFacts.PromptFact plain =
                    inTopic.stream().filter(f -> !f.isChange()).findFirst().orElseThrow();
            AliasedFacts.PromptFact worse = inTopic.stream()
                    .filter(StructuredValidator::isWorse)
                    .findFirst()
                    .orElseThrow();
            AliasedFacts.PromptFact better = inTopic.stream()
                    .filter(f -> f.isChange() && f.value() != null && f.value() != 0 && !StructuredValidator.isWorse(f))
                    .findFirst()
                    .orElseThrow();
            for (String priority : List.of("high", "medium")) {
                Result r = StructuredValidator.validate(
                        answer(List.of(finding(topic.code(), action.code(), priority, plain.key()))), ctx);
                assertThat(r.violations())
                        .as("%s at %s priority", action.code(), priority)
                        .extracting(Violation::code)
                        .containsExactly("calm_action_mismatch");
            }
            Result withWorse = StructuredValidator.validate(
                    answer(List.of(finding(topic.code(), action.code(), "low", plain.key(), worse.key()))), ctx);
            assertThat(withWorse.violations()).extracting(Violation::code).containsExactly("calm_action_mismatch");
            // The same beside a change that is better, or no change at all, is allowed at low priority.
            assertThat(StructuredValidator.validate(
                                    answer(List.of(
                                            finding(topic.code(), action.code(), "low", plain.key(), better.key()))),
                                    ctx)
                            .valid())
                    .isTrue();
            assertThat(StructuredValidator.validate(
                                    answer(List.of(finding(topic.code(), action.code(), "low", plain.key()))), ctx)
                            .valid())
                    .isTrue();
        }
        assertThat(calmActions).isGreaterThanOrEqualTo(1);
        // And the wording check itself: the phrases that mean "nothing to do" are all recognised.
        for (String calmText : List.of(
                "No action is needed now.",
                "There is no action needed.",
                "Keep watching the book.",
                "Keep this under watch.")) {
            assertThat(Catalogue.CALM_WORDING.matcher(calmText).find())
                    .as(calmText)
                    .isTrue();
        }
    }

    @Test
    void aWorseChangeIsDecidedBySignAndPolarity() {
        AliasedFacts facts = AliasedFacts.of(Facts.standard());
        java.util.Map<String, Boolean> expected = java.util.Map.of(
                "portfolio.par30.week_change", false, // PAR fell: better
                "portfolio.par30.month_change", true, // PAR rose: worse
                "portfolio.collection_rate.week_change", false, // collection rate rose: better
                "portfolio.collection_rate.month_change", true, // collection rate fell: worse
                "portfolio.principal_outstanding.week_change", false, // neutral
                "portfolio.par30", false); // not a change
        expected.forEach((key, worse) -> assertThat(StructuredValidator.isWorse(facts.facts().stream()
                        .filter(f -> f.key().equals(key))
                        .findFirst()
                        .orElseThrow()))
                .as(key)
                .isEqualTo(worse));
    }

    // ---- No finding: the key figures ----------------------------------------------------------------------------

    @Test
    void anAnswerWithNoFindingAndNothingToWatchShowsTheKeyFiguresNotAnEmptyStatement() {
        Result r = StructuredValidator.validate(answer(List.of()), CTX);
        assertThat(r.valid()).isTrue();
        assertThat(r.answer().points()).singleElement().satisfies(p -> {
            assertThat(p.text()).startsWith("Key figures: Principal outstanding (UGX 245,000,000)");
            assertThat(p.text()).doesNotContain("Nothing was flagged");
        });
        // With no figure to show at all, and only then, the plain statement remains.
        Result few = StructuredValidator.validate(
                answer(List.of()),
                Context.of(AliasedFacts.of(com.rincoltech.bms.lending.advisor.GoldenCases.fewLoans())));
        assertThat(few.valid()).isTrue();
        assertThat(few.answer().points().get(0).text()).isEqualTo("Nothing was flagged for this scope.");
    }

    // ---- Grammar: the same words for a singular and a plural name -----------------------------------------------

    @Test
    void aFactPhraseUsesNoVerbThatAgreesWithTheNameSoSingularAndPluralNamesReadAlike() {
        FactSet set = Facts.asOf("2026-10-14")
                .count("portfolio.written_off_count", "Loans written off", Polarity.HIGHER_IS_WORSE, 8, 0L, 3L)
                .count("portfolio.active_loans", "Active loans", Polarity.NEUTRAL, 312, 0L, 0L)
                .rate("portfolio.par30", "PAR 30", Polarity.HIGHER_IS_WORSE, 1254, 0L, 25L)
                .rate("portfolio.collection_rate", "Collection rate", Polarity.HIGHER_IS_BETTER, 8750, 0L, -300L)
                .build();
        for (AliasedFacts.PromptFact f : AliasedFacts.of(set).facts()) {
            String phrase = Phrases.fact(f);
            assertThat(phrase).as(phrase).doesNotContainPattern("\\b(?:is|are|was|were|has|have)\\b");
            if (f.isChange() && f.value() != null && f.value() == 0) {
                assertThat(phrase)
                        .endsWith("did not change compared with " + Phrases.comparedWith(f.deltaOf(), f.period()));
            }
        }
    }

    @Test
    void noActionOrTemplateTextRefersToTheCitedFactsAsAFigureOrFiguresSoItReadsRightForOneFactOrThree() {
        Pattern reference = Pattern.compile("(?i)\\b(?:figures?|numbers?|values?|these|those|each of)\\b");
        Catalogue.INSTANCE
                .actions()
                .values()
                .forEach(a -> assertThat(reference.matcher(a.text()).find())
                        .as(a.code() + ": " + a.text())
                        .isFalse());
        // The templates too, but the facts line and the "Key figures" lead-in are headings, not references.
        assertThat(Catalogue.INSTANCE.template("finding")).doesNotContainPattern("(?i)\\b(?:this|these)\\b");
        assertThat(Catalogue.INSTANCE.template("watch")).doesNotContainPattern("(?i)\\b(?:this|these)\\b");
    }

    @Test
    void noActionTextMentionsAnOfficerOrAnythingTheFactsDoNotCover() {
        // There is no officer dimension (ADR-037 decision 3): no text may suggest one, nor savings or investments,
        // which
        // no fact covers.
        Pattern uncovered = Pattern.compile(
                "(?i)\\b(?:officers?|staff|agents?|savings|investments?|withdrawals?|payouts?|deposits?)\\b");
        Catalogue.INSTANCE
                .actions()
                .values()
                .forEach(a -> assertThat(uncovered.matcher(a.text()).find())
                        .as(a.code() + ": " + a.text())
                        .isFalse());
        Catalogue.INSTANCE
                .topics()
                .values()
                .forEach(t -> assertThat(uncovered.matcher(t.label()).find())
                        .as(t.code() + ": " + t.label())
                        .isFalse());
    }

    @Test
    void theDeterministicBriefHoldsOnlyKeyFiguresWeWroteAndNeverAHiddenFact() {
        AliasedFacts facts = AliasedFacts.of(Facts.standard());
        AdvisorAnswer brief = Fallback.render(facts);
        assertThat(brief.summary()).startsWith("Advisory summary as of 14 October 2026");
        assertThat(brief.points()).singleElement().satisfies(p -> {
            assertThat(p.text())
                    .startsWith("Key figures: Principal outstanding (UGX 245,000,000); Active loans (312);");
            assertThat(p.facts())
                    .containsExactlyElementsOf(Fallback.KEY_FIGURES.stream()
                            .filter(k ->
                                    facts.facts().stream().anyMatch(f -> f.key().equals(k)))
                            .toList());
        });
        AdvisorAnswer none =
                Fallback.render(AliasedFacts.of(com.rincoltech.bms.lending.advisor.GoldenCases.fewLoans()));
        assertThat(none.points())
                .singleElement()
                .satisfies(p -> assertThat(p.text()).isEqualTo("Nothing was flagged for this scope."));
    }
}
