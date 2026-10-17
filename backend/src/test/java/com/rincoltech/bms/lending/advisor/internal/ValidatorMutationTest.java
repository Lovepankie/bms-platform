package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.advisor.AdvisorEvaluation;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Report;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Result;
import com.rincoltech.bms.lending.advisor.GoldenCases;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Mutation checks of the golden evaluation: each rule of the structured validator is switched off in turn through its test
 * switch ({@link StructuredValidator.Rules}) and the harness must then FAIL, with the very cases that rule exists for being
 * accepted. A rule whose removal changes nothing is a rule the golden set does not test.
 */
class ValidatorMutationTest {

    @AfterEach
    void restoreTheRules() {
        StructuredValidator.Rules.reset();
    }

    private static Report run() {
        return AdvisorEvaluation.run(GoldenCases.all(), AdvisorEvaluation.recorded());
    }

    private static Set<String> wronglyAccepted(Report report) {
        return report.results().stream()
                .filter(r -> !r.passed() && r.accepted())
                .map(Result::id)
                .collect(Collectors.toSet());
    }

    /** One rule, how to switch it off, and the golden cases that only it refuses. */
    private record Mutation(Consumer<Boolean> setting, Set<String> onlyThisRuleRefuses) {}

    private static Map<String, Mutation> mutations() {
        Map<String, Mutation> m = new LinkedHashMap<>();
        m.put(
                "strictFields",
                new Mutation(
                        v -> StructuredValidator.Rules.strictFields = v,
                        Set.of(
                                "shape-extra-root-field",
                                "shape-extra-finding-field",
                                "attack-001-extra-field",
                                "attack-001-finding-extra-field")));
        m.put(
                "enumTopic",
                new Mutation(
                        v -> StructuredValidator.Rules.enumTopic = v,
                        Set.of("topic-unknown", "topic-upper-case", "topic-padded", "attack-001-topic")));
        m.put(
                "enumAction",
                new Mutation(
                        v -> StructuredValidator.Rules.enumAction = v,
                        Set.of("action-unknown", "action-lower-case", "attack-001-action")));
        m.put(
                "enumPriority",
                new Mutation(
                        v -> StructuredValidator.Rules.enumPriority = v,
                        Set.of("priority-unknown", "priority-upper-case", "attack-001-priority")));
        m.put(
                "actionBelongsToTopic",
                new Mutation(
                        v -> StructuredValidator.Rules.actionBelongsToTopic = v, Set.of("action-from-another-topic")));
        m.put(
                "factExists",
                new Mutation(
                        v -> StructuredValidator.Rules.factExists = v,
                        Set.of(
                                "fact-unknown",
                                "fact-real-name",
                                "fact-out-of-scope-alias",
                                "fact-out-of-scope-product",
                                "watch-unknown",
                                "attack-001-fact-key",
                                "attack-001-watch")));
        m.put(
                "factNotHidden",
                new Mutation(
                        v -> StructuredValidator.Rules.factNotHidden = v,
                        Set.of("fact-hidden-group", "fact-hidden-book", "watch-hidden")));
        m.put(
                "factFitsTopic",
                new Mutation(
                        v -> StructuredValidator.Rules.factFitsTopic = v,
                        Set.of("fact-of-another-topic", "fact-of-another-topic-change")));
        m.put(
                "calmAction",
                new Mutation(
                        v -> StructuredValidator.Rules.calmAction = v,
                        Set.of("calm-high-priority", "calm-medium-priority", "calm-worse-change")));
        m.put(
                "noDuplicates",
                new Mutation(
                        v -> StructuredValidator.Rules.noDuplicates = v, Set.of("keys-duplicate", "watch-duplicate")));
        m.put(
                "limits",
                new Mutation(
                        v -> StructuredValidator.Rules.limits = v,
                        Set.of("keys-four", "keys-none", "findings-seven", "watch-seven")));
        return m;
    }

    @Test
    void withEveryRuleOnTheGoldenSetPasses() {
        Report report = run();
        assertThat(report.allPassed()).as(report.text()).isTrue();
    }

    @Test
    void eachRuleIsTheOneThatRefusesItsCases() {
        // The second line re-derives the numbers of the rendering; with it off as well, nothing but the rule under test
        // remains.
        for (Map.Entry<String, Mutation> e : mutations().entrySet()) {
            StructuredValidator.Rules.reset();
            StructuredValidator.Rules.secondLine = false;
            e.getValue().setting().accept(false);
            Report report = run();
            assertThat(report.allPassed())
                    .as("the harness must fail with %s off", e.getKey())
                    .isFalse();
            assertThat(wronglyAccepted(report))
                    .as("cases accepted with %s off", e.getKey())
                    .containsAll(e.getValue().onlyThisRuleRefuses());
        }
    }

    @Test
    void theSecondLineCatchesADefectOfTheRendering() {
        // The phrases write a wrong figure (a test switch): the second line re-derives the numbers and refuses.
        Phrases.corrupt = true;
        Report with = run();
        List<Result> clean = with.results().stream()
                .filter(r -> r.id().equals("clean-arrears-risk"))
                .toList();
        assertThat(clean).singleElement().satisfies(r -> {
            assertThat(r.accepted()).isFalse();
            assertThat(r.codes()).contains("render_check_failed");
            // And the person is shown the deterministic brief instead, which is written by the same phrases and so
            // refused
            // by nothing: it is what the corrupted phrases make, which is why the second line is a safety net and the
            // golden
            // set is the check on the templates.
            assertThat(r.fallback()).isNotEmpty();
        });

        // With the second line off, the same corrupted rendering is accepted: the second line is what stopped it.
        StructuredValidator.Rules.secondLine = false;
        Report without = run();
        assertThat(without.results().stream().filter(r -> r.id().equals("clean-arrears-risk")))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.accepted()).isTrue();
                    assertThat(r.shownPoints().get(0)).contains("(912.5 percent)");
                });
    }

    @Test
    void theSecondLineIsRedundantOnTheGoldenSetByDesign() {
        StructuredValidator.Rules.secondLine = false;
        assertThat(run().allPassed()).isTrue();
    }

    @Test
    void everyRefusedCaseIsRefusedAndNoCleanCaseIsRefusedByAnything() {
        List<Result> results = run().results();
        assertThat(results).allMatch(Result::passed);
        assertThat(results.stream().filter(r -> r.category().equals("clean")))
                .allSatisfy(r -> assertThat(r.codes()).as(r.id()).isEmpty());
    }
}
