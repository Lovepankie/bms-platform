package com.rincoltech.bms.lending.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Case;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Expect;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Report;
import com.rincoltech.bms.lending.advisor.AdvisorEvaluation.Result;
import com.rincoltech.bms.lending.advisor.internal.EvaluationSupport;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Dimension;
import com.rincoltech.bms.lending.insights.PortfolioFacts.FactSet;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Kind;
import com.rincoltech.bms.lending.insights.PortfolioFacts.Polarity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The golden evaluation (spec 5.8, ADR-037 decision 10), offline: the recording client answers each case with the
 * answer recorded for it, so no model is called, nothing is spent and the run takes a moment. The gates are the spec's:
 * every adversarial answer refused with the expected code, every clean one accepted, and no request that carries a
 * fabricated name, a planted instruction, a phone number or a figure for a small group.
 */
class AdvisorEvaluationTest {

    static final List<Case> CASES = GoldenCases.all();

    @Test
    void everyGoldenCaseGetsItsExpectedVerdict() throws IOException {
        Report report = AdvisorEvaluation.run(CASES, AdvisorEvaluation.recorded());
        // The report is written for a person to read (target/ is not tracked).
        Path out = Path.of("target", "advisor-evaluation-report.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report.text(), StandardCharsets.UTF_8);

        List<Result> failed = report.results().stream().filter(r -> !r.passed()).toList();
        assertThat(failed).as("failed cases:%n%s", report.text()).isEmpty();
        assertThat(report.allPassed()).isTrue();
        assertThat(report.results()).hasSameSizeAs(CASES);
    }

    @Test
    void theGoldenSetIsBroadAndBalanced() {
        Map<String, Long> byCategory =
                CASES.stream().collect(Collectors.groupingBy(Case::category, Collectors.counting()));
        assertThat(CASES).hasSizeGreaterThanOrEqualTo(60);
        assertThat(byCategory.keySet())
                .contains(
                        "clean",
                        "invented_number",
                        "injection",
                        "pii",
                        "small_group",
                        "missing_data",
                        "scope",
                        "shape",
                        "provider");
        assertThat(CASES.stream().filter(c -> c.expect() == Expect.ACCEPT).count())
                .isGreaterThanOrEqualTo(15);
        assertThat(CASES.stream().filter(c -> c.expect() == Expect.REJECT).count())
                .isGreaterThanOrEqualTo(40);
        assertThat(CASES).extracting(Case::id).doesNotHaveDuplicates();
        // Every refusal names the code it must produce, except the provider failing.
        assertThat(CASES.stream().filter(c -> c.expect() == Expect.REJECT && c.recorded() != null))
                .allSatisfy(c -> assertThat(c.code()).as(c.id()).isNotNull());
    }

    @Test
    void theGatesOfTheSpecEachHoldAtOneHundredPercent() {
        Report report = AdvisorEvaluation.run(CASES, AdvisorEvaluation.recorded());
        for (String category : List.of(
                "clean",
                "invented_number",
                "injection",
                "pii",
                "small_group",
                "missing_data",
                "scope",
                "shape",
                "provider")) {
            assertThat(report.percent(category)).as(category).isEqualTo(100);
        }
    }

    @Test
    void theHarnessDoesNotJustSayYes() {
        // Flip every expectation: a harness that passed everything would still pass. This one must fail all but the
        // provider case, so each verdict above is a real one.
        List<Case> flipped = CASES.stream()
                .filter(c -> c.recorded() != null)
                .map(c -> new Case(
                        c.id(),
                        c.category(),
                        c.facts(),
                        c.planted(),
                        c.recorded(),
                        c.expect() == Expect.ACCEPT ? Expect.REJECT : Expect.ACCEPT,
                        null))
                .toList();
        Report report = AdvisorEvaluation.run(flipped, AdvisorEvaluation.recorded());
        assertThat(report.passed()).isZero();
        assertThat(report.results())
                .allSatisfy(r -> assertThat(r.requestClean()).isTrue());
    }

    @Test
    void everyRuleBitesBecauseItsRepairedAnswerIsAccepted() {
        Map<String, String> repairs = GoldenCases.repairs();
        assertThat(repairs).hasSizeGreaterThanOrEqualTo(25);
        List<Case> originals = new java.util.ArrayList<>();
        List<Case> repaired = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : repairs.entrySet()) {
            Case c = CASES.stream()
                    .filter(x -> x.id().equals(e.getKey()))
                    .findFirst()
                    .orElseThrow();
            assertThat(c.expect()).as(e.getKey()).isEqualTo(Expect.REJECT);
            originals.add(c);
            repaired.add(new Case(c.id(), c.category(), c.facts(), c.planted(), e.getValue(), Expect.ACCEPT, null));
        }
        // The original is refused with its code ...
        Report refused = AdvisorEvaluation.run(originals, AdvisorEvaluation.recorded());
        assertThat(refused.allPassed()).as(refused.text()).isTrue();
        assertThat(refused.results())
                .allSatisfy(r -> assertThat(r.accepted()).as(r.id()).isFalse());
        // ... and mended in that one place it is accepted: the rule, and nothing else, was refusing it.
        Report mended = AdvisorEvaluation.run(repaired, AdvisorEvaluation.recorded());
        assertThat(mended.allPassed()).as(mended.text()).isTrue();
        assertThat(mended.results())
                .allSatisfy(r -> assertThat(r.accepted()).as(r.id()).isTrue());
    }

    private Result run(String id) {
        return AdvisorEvaluation.run(
                        CASES.stream().filter(c -> c.id().equals(id)).toList(), AdvisorEvaluation.recorded())
                .results()
                .get(0);
    }

    static final String SUMMARY = "Advisory summary as of 14 October 2026, covering 1 October 2026 to 14 October 2026."
            + " These are suggestions for the owner to check before acting; nothing has been changed.";

    static final String KEY_FIGURES = "Key figures: Principal outstanding (UGX 245,000,000); Active loans (312);"
            + " PAR 30 (12.5 percent); PAR 30 fell by 0.40 points compared with a week ago (better);"
            + " Collection rate (87.5 percent);"
            + " Collection rate rose by 1.20 points compared with the same period a week earlier (better);"
            + " Money out today (UGX 1,250,000).";

    @Test
    void ourCodeWritesEverySentenceFromTheCatalogueAndTheFacts() {
        Result one = run("clean-arrears-risk");
        assertThat(one.accepted()).isTrue();
        assertThat(one.shown()).isEqualTo(SUMMARY);
        assertThat(one.shownPoints())
                .containsExactly(
                        "High priority. PAR 30 (12.5 percent); PAR 30 fell by 0.40 points compared with a week ago (better)."
                                + " Suggested action: Review the oldest arrears cases this week.");
        assertThat(run("clean-three-findings").shownPoints())
                .containsExactly(
                        "Medium priority. Collection rate (87.5 percent);"
                                + " Collection rate rose by 1.20 points compared with the same period a week earlier (better)."
                                + " Suggested action: Call the borrowers whose payment is due in the next days.",
                        "Low priority. Money out today (UGX 1,250,000);"
                                + " Money out today rose by UGX 250,000 compared with the same day last week (better)."
                                + " Suggested action: Check the pipeline of applications and decide the ones that are ready.",
                        "Medium priority. Principal outstanding (UGX 245,000,000); Active loans (312)."
                                + " Suggested action: Check the collateral cover of the largest loans.");
        assertThat(run("clean-both-changes").shownPoints())
                .containsExactly(
                        "High priority. PAR 30 (12.5 percent); PAR 30 fell by 0.40 points compared with a week ago (better);"
                                + " PAR 30 rose by 0.25 points compared with a month ago (worse)."
                                + " Suggested action: Review the portfolio at risk and compare it with the other branches and products.");
        assertThat(run("clean-watch-only").shownPoints())
                .containsExactly("To watch: PAR 30 (12.5 percent); Collection rate (87.5 percent);"
                        + " Principal outstanding (UGX 245,000,000).");
        // No finding and nothing to watch: the person sees the key figures, not an empty answer.
        assertThat(run("clean-nothing-to-flag").shownPoints()).containsExactly(KEY_FIGURES);
        assertThat(run("clean-missing-data").shownPoints())
                .containsExactly("Low priority. Collection rate today (not available)."
                        + " Suggested action: Send payment reminders ahead of the due dates.");
        assertThat(run("clean-negative").shownPoints())
                .containsExactly("Low priority. Profit contribution (-UGX 500,000)."
                        + " Suggested action: No action is needed now; keep this under watch.");
    }

    @Test
    void aGroupIsNamedByItsAliasAndMappedBackOnlyForThePersonWhoAsked() {
        assertThat(run("clean-branch-where").shownPoints())
                .containsExactly("High priority. Fabricated Branch Alpha: PAR 30 (9.1 percent)."
                        + " Suggested action: Contact the borrowers who most recently fell into arrears"
                        + " for Fabricated Branch Alpha before their arrears grow older.");
        assertThat(run("clean-two-groups-where").shownPoints())
                .containsExactly("Low priority. Fabricated Branch Alpha: PAR 30 (9.1 percent);"
                        + " Fabricated Product Gamma: Active loans (88)."
                        + " Suggested action: Look more closely at how the loans are distributed"
                        + " for Fabricated Branch Alpha and Fabricated Product Gamma.");
        // The hostile names of a tenant never get near the rendering: the aliases are what is written and mapped back.
        assertThat(run("clean-hostile-names-aliased").shownPoints())
                .singleElement()
                .asString()
                .contains(GoldenCases.INJECTION_BRANCH)
                .contains(GoldenCases.INJECTION_PRODUCT);
    }

    @Test
    void aNameWithMarkupOrALineBreakIsNeutralisedWhereItIsWrittenIntoASentence() {
        String hostile = "<img src=x onerror=alert(1)>\n**Bold** [click](http://x.example) `code` & _more_ | \\ <b>";
        FactSet set = Facts.asOf("2026-10-14")
                .group(Dimension.BRANCH, "b-1", hostile)
                .groupFact(Dimension.BRANCH, "b-1", "branch.loans", "Active loans", Kind.COUNT, Polarity.NEUTRAL, 120)
                .build();
        EvaluationSupport.Prepared prepared = EvaluationSupport.prepare(set, List.of());
        String answer = GoldenCases.answer(
                GoldenCases.finding("concentration", "CONCENTRATION_REVIEW_SPREAD", "low", "branch_1.loans"));
        EvaluationSupport.Verdict verdict = EvaluationSupport.validate(prepared, answer, List.of());
        assertThat(verdict.accepted()).isTrue();
        String shown = String.join("\n", verdict.shownPoints());
        assertThat(shown)
                .contains("img src=x onerror=alert(1)")
                .contains("Bold")
                .contains("click");
        assertThat(shown)
                .doesNotContain("<")
                .doesNotContain(">")
                .doesNotContain("*")
                .doesNotContain("`")
                .doesNotContain("[")
                .doesNotContain("]")
                .doesNotContain("|")
                .doesNotContain("\\")
                .doesNotContain("&")
                .doesNotContain("_");
        // Exactly one point and no line break inside it: the name's own line break became a space.
        assertThat(verdict.shownPoints()).singleElement().asString().doesNotContain("\n");
    }

    @Test
    void aRefusedAnswerIsReplacedByTheDeterministicBriefAndNothingTheModelWrote() {
        for (String id : List.of(
                "shape-empty",
                "shape-prose",
                "topic-unknown",
                "fact-hidden-group",
                "attack-004-topic",
                "attack-040-legacy-prose")) {
            Result r = run(id);
            assertThat(r.accepted()).as(id).isFalse();
            assertThat(r.shown()).as(id).isNull();
            assertThat(r.fallback()).as(id).containsExactly(KEY_FIGURES);
        }
        // With every fact hidden there is nothing to show but that.
        FactSet few = GoldenCases.fewLoans();
        Case refused = new Case("x", "shape", few, GoldenCases.planted(), "", Expect.REJECT, "not_json");
        assertThat(AdvisorEvaluation.run(List.of(refused), AdvisorEvaluation.recorded())
                        .results()
                        .get(0)
                        .fallback())
                .containsExactly("Nothing was flagged for this scope.");
    }

    @Test
    void aWrongCodeFailsTheCaseEvenWhenTheAnswerIsRefused() {
        Case right = CASES.stream()
                .filter(c -> c.id().equals("topic-unknown"))
                .findFirst()
                .orElseThrow();
        Case wrong = new Case(
                right.id(),
                right.category(),
                right.facts(),
                right.planted(),
                right.recorded(),
                Expect.REJECT,
                "unknown_action");
        assertThat(AdvisorEvaluation.run(List.of(right), AdvisorEvaluation.recorded())
                        .allPassed())
                .isTrue();
        Result r = AdvisorEvaluation.run(List.of(wrong), AdvisorEvaluation.recorded())
                .results()
                .get(0);
        assertThat(r.accepted()).isFalse();
        assertThat(r.passed()).isFalse();
        assertThat(r.codes()).contains("unknown_topic").doesNotContain("unknown_action");
    }

    @Test
    void anAcceptedAnswerIsShownWithTheNamesBackInPlaceOfTheAliases() {
        Result r = run("clean-alias-mapped-back");
        assertThat(r.accepted()).isTrue();
        assertThat(r.shownPoints())
                .singleElement()
                .asString()
                .contains("Fabricated Branch Alpha: Active loans (120)")
                .contains("Fabricated Product Gamma: Active loans (88)")
                .doesNotContain("Branch 1")
                .doesNotContain("Product A");
        // And the refused ones show nothing of the model's.
        assertThat(run("topic-unknown").shown()).isNull();
    }

    @Test
    void noRequestCarriesAPlantedNameAnInstructionOrAPhoneNumber() {
        // The canary at the client boundary: a recording client for the cases, whose requests are inspected after. The
        // stub
        // keeps its last 100 requests, so the cases go in batches.
        RecordingLlmClient client = new RecordingLlmClient("canary");
        String clean = GoldenCases.valid();
        List<LlmClient.Request> sent = new java.util.ArrayList<>();
        for (int from = 0; from < CASES.size(); from += 50) {
            List<Case> batch = CASES.subList(from, Math.min(CASES.size(), from + 50));
            client.reset();
            for (int i = 0; i < batch.size(); i++) {
                client.thenAnswer(new LlmClient.Result(clean, 100, 20, "stop"));
            }
            Report report = AdvisorEvaluation.run(batch, AdvisorEvaluation.with(client));
            assertThat(report.results())
                    .allSatisfy(r -> assertThat(r.requestClean()).as(r.id()).isTrue());
            sent.addAll(client.requests());
        }
        assertThat(sent).hasSize(CASES.size());
        for (LlmClient.Request request : sent) {
            String text =
                    request.messages().stream().map(LlmClient.Message::content).collect(Collectors.joining("\n"));
            for (String planted : GoldenCases.planted()) {
                assertThat(text).as("planted: %s", planted).doesNotContain(planted);
            }
            assertThat(text)
                    .doesNotContain("0700000001")
                    .doesNotContain("Fabricated")
                    .doesNotContain("Ignore all previous");
            assertThat(text).doesNotContain("b-1").doesNotContain("p-1 ");
        }
        // The hostile set's two groups were sent as aliases.
        String hostile = sent.stream()
                .map(r -> r.messages().get(1).content())
                .filter(c -> c.contains("branch_1.loans") && c.contains("product_a.loans") && c.contains("| 312 |"))
                .findFirst()
                .orElseThrow();
        assertThat(hostile).contains("Branch 1: Active loans").contains("Product A: Active loans");
    }

    @Test
    void aCaseWhoseRequestWouldLeakIsNeverSentAndFails() {
        FactSet leaking = Facts.asOf("2026-10-14")
                .group(Dimension.BRANCH, "b-1", "Fabricated Branch Alpha")
                .count(
                        "portfolio.active_loans",
                        "Active loans of Fabricated Branch Alpha",
                        Polarity.NEUTRAL,
                        312,
                        null,
                        null)
                .build();
        RecordingLlmClient client = new RecordingLlmClient("canary");
        Case c = new Case("leak", "pii", leaking, List.of("Fabricated Branch Alpha"), "{}", Expect.REJECT, null);
        Result r = AdvisorEvaluation.run(List.of(c), AdvisorEvaluation.with(client))
                .results()
                .get(0);
        assertThat(r.requestClean()).isFalse();
        assertThat(r.passed()).isFalse();
        assertThat(r.codes()).containsExactly("request_not_clean");
        assertThat(client.requests())
                .as("a request that fails the leak check is not sent")
                .isEmpty();
    }

    @Test
    void theReportCountsTokensAndCostsNothing() {
        Report report = AdvisorEvaluation.run(CASES, AdvisorEvaluation.recorded());
        String text = report.text();
        assertThat(text).startsWith("Advisor evaluation (offline, recorded answers)");
        assertThat(text).contains("Cost: none").contains("Cases: " + CASES.size());
        assertThat(text).contains("PASS clean-arrears-risk").contains("PASS topic-unknown");
        assertThat(text).doesNotContain("FAIL");
        assertThat(report.results().stream().mapToInt(Result::inputTokens).sum())
                .isPositive();
        // The report holds codes and counts, never a sentence of an answer or a name.
        assertThat(text)
                .doesNotContain("Fabricated")
                .doesNotContain("Ignore all")
                .doesNotContain("0700");
    }

    @Test
    void aFailingProviderIsAFallbackNotAnAcceptedAnswer() {
        Result r = AdvisorEvaluation.run(
                        CASES.stream()
                                .filter(c -> c.id().equals("provider-down"))
                                .toList(),
                        AdvisorEvaluation.recorded())
                .results()
                .get(0);
        assertThat(r.accepted()).isFalse();
        assertThat(r.codes()).containsExactly("provider_provider_error");
        assertThat(r.passed()).isTrue();
        // The same failure against a case that expected an answer is a failed case.
        Case expected =
                new Case("x", "clean", CASES.get(0).facts(), CASES.get(0).planted(), null, Expect.ACCEPT, null);
        assertThat(AdvisorEvaluation.run(List.of(expected), AdvisorEvaluation.recorded())
                        .allPassed())
                .isFalse();
    }
}
