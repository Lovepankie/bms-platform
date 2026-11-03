package com.rincoltech.bms.retail.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.ai.AiGateway;
import com.rincoltech.bms.core.ai.AiGateway.AiResult;
import com.rincoltech.bms.retail.advisor.internal.BriefWriter.Brief;
import com.rincoltech.bms.retail.reports.RetailBriefFacts.Change;
import com.rincoltech.bms.retail.reports.RetailBriefFacts.Fact;
import com.rincoltech.bms.retail.reports.RetailBriefFacts.FactSet;
import com.rincoltech.bms.retail.reports.RetailBriefFacts.ItemName;
import com.rincoltech.bms.retail.reports.RetailBriefFacts.Kind;
import com.rincoltech.bms.retail.reports.RetailBriefFacts.Polarity;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The retail brief's request, check and rendering (issue #422) with no database and no model: the model's structured answer is
 * accepted only in exactly the shape the catalogue allows, everything the person reads is written by our code, item names never
 * reach the request, and every figure comes from the facts. All figures and names are fabricated.
 */
class BriefWriterTest {

    static final String ITEM = UUID.randomUUID().toString();
    static final String ITEM_NAME = "Zq Canary Soap Bar";

    static PromptFacts facts() {
        List<Fact> facts = List.of(
                new Fact(
                        "sales.yesterday",
                        "Sales yesterday",
                        Kind.MONEY,
                        Polarity.HIGHER_IS_BETTER,
                        120_000,
                        "UGX 120,000",
                        new Change(-30_000, "-UGX 30,000", "the usual Tuesday"),
                        null),
                new Fact(
                        "sales.last_7_days",
                        "Sales in the last seven days",
                        Kind.MONEY,
                        Polarity.HIGHER_IS_BETTER,
                        900_000,
                        "UGX 900,000",
                        null,
                        null),
                new Fact("stock.low", "Items low on stock", Kind.COUNT, Polarity.HIGHER_IS_WORSE, 4, "4", null, null),
                new Fact("stock.out", "Items out of stock", Kind.COUNT, Polarity.HIGHER_IS_WORSE, 1, "1", null, null),
                new Fact(
                        "item.sales_week",
                        "sales in the last seven days",
                        Kind.MONEY,
                        Polarity.HIGHER_IS_BETTER,
                        300_000,
                        "UGX 300,000",
                        null,
                        ITEM),
                new Fact(
                        "credit.overdue",
                        "Owed past the due date",
                        Kind.MONEY,
                        Polarity.HIGHER_IS_WORSE,
                        50_000,
                        "UGX 50,000",
                        null,
                        null));
        return PromptFacts.of(
                new FactSet(LocalDate.of(2026, 10, 20), "UGX", true, facts, List.of(new ItemName(ITEM, ITEM_NAME))));
    }

    static AiResult answer(String text) {
        return new AiResult(text, 100, 20, "stop", null, UUID.randomUUID());
    }

    static final String VALID = """
            {"findings": [{"fact_keys": ["item_a.sales_week", "stock.low"], "topic": "best_sellers",
                           "action": "BEST_KEEP_IN_STOCK", "priority": "medium"},
                          {"fact_keys": ["sales.yesterday.change"], "topic": "sales_pace",
                           "action": "SALES_CHECK_SLOW_DAY", "priority": "high"}],
             "watch": ["credit.overdue"]}
            """;

    @Test
    void theCatalogueLoadsAndThePromptListsEveryTopicAndAction() {
        BriefCatalogue c = BriefCatalogue.INSTANCE;
        c.topics.keySet().forEach(t -> assertThat(BriefWriter.SYSTEM).contains("- " + t + ": "));
        c.actions.keySet().forEach(a -> assertThat(BriefWriter.SYSTEM).contains("- " + a + ", "));
        assertThat(BriefWriter.SYSTEM).contains(BriefWriter.CANARY).doesNotContain("@TOPICS@", "@ACTIONS@");
    }

    @Test
    void aCatalogueTextWithADigitOrAWrongCalmMarkIsADefect() throws java.io.IOException {
        String good;
        try (java.io.InputStream in = BriefCatalogue.class.getResourceAsStream(BriefCatalogue.RESOURCE)) {
            good = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThatThrownBy(() -> BriefCatalogue.parse(good.replace("Restock the items", "Restock the 3 items")))
                .hasMessageContaining("digit");
        assertThatThrownBy(() -> BriefCatalogue.parse(good.replace("\"calm\": true", "\"calm\": false")))
                .hasMessageContaining("calm");
    }

    @Test
    void theRequestCarriesAliasesAndNeverAnItemName() {
        String content = BriefWriter.content(facts());
        assertThat(content).contains("item_a.sales_week | Item A: sales in the last seven days | UGX 300,000");
        assertThat(content).contains("sales.yesterday.change");
        assertThat(content).doesNotContain(ITEM_NAME).doesNotContain(ITEM);
        assertThat(content).doesNotContainPattern("\\d{6,}");
    }

    @Test
    void aValidAnswerIsWrittenByOurCodeWithTheItemNamedBack() {
        Brief brief = BriefWriter.build(facts(), answer(VALID));
        assertThat(brief.source()).isEqualTo("model");
        assertThat(brief.reason()).isNull();
        assertThat(brief.points()).hasSize(3);
        assertThat(brief.points().get(0).text())
                .startsWith("Medium priority. " + ITEM_NAME + ": sales in the last seven days (UGX 300,000)")
                .contains("Suggested action: Keep enough stock for " + ITEM_NAME
                        + " so the best sellers do not run out.");
        assertThat(brief.points().get(1).text())
                .contains("Sales yesterday fell by UGX 30,000 compared with the usual Tuesday (worse)");
        assertThat(brief.points().get(2).text()).isEqualTo("To watch: Owed past the due date (UGX 50,000).");
        assertThat(brief.chips())
                .extracting(BriefWriter.Chip::metric)
                .containsExactly("item.sales_week", "stock.low", "credit.overdue");
        assertThat(brief.summary()).contains("20 October 2026");
    }

    @Test
    void anythingOutsideTheSchemaIsRefusedAsAWhole() {
        List<String> refused = List.of(
                "Sales are down, call your buyers.",
                VALID.replace("\"watch\"", "\"note\": \"hello\", \"watch\""),
                VALID.replace("item_a.sales_week", "item_b.sales_week"),
                VALID.replace("BEST_KEEP_IN_STOCK", "CREDIT_CALL_OVERDUE"),
                VALID.replace("\"priority\": \"medium\"", "\"priority\": \"urgent\""),
                VALID.replace("\"stock.low\"]", "\"credit.overdue\"]"),
                VALID.replace("[\"credit.overdue\"]", "[\"credit.overdue\", \"credit.overdue\"]"),
                VALID.replace("SALES_CHECK_SLOW_DAY", "SALES_STEADY"),
                VALID.replace("SALES_CHECK_SLOW_DAY\", \"priority\": \"high", "SALES_STEADY\", \"priority\": \"low"),
                "{\"findings\": [], \"watch\": [], \"findings\": []}");
        for (String raw : refused) {
            Brief brief = BriefWriter.build(facts(), answer(raw));
            assertThat(brief.source()).as(raw).isEqualTo("fallback");
            assertThat(brief.reason()).as(raw).isEqualTo(BriefWriter.VALIDATION);
        }
    }

    @Test
    void anUnansweredCallIsTheFactsOnlyBriefWithTheGatewaysReason() {
        Brief brief = BriefWriter.build(facts(), AiResult.fallback(AiGateway.Reason.TIMEOUT, UUID.randomUUID()));
        assertThat(brief.source()).isEqualTo("fallback");
        assertThat(brief.reason()).isEqualTo("timeout");
        assertThat(brief.points()).singleElement().satisfies(p -> {
            assertThat(p.text()).startsWith("Key figures: Sales yesterday (UGX 120,000); Sales yesterday fell by");
            assertThat(p.facts()).contains("sales.yesterday", "stock.low", "credit.overdue");
        });
    }

    @Test
    void anEmptyAnswerIsTheAutomaticSummaryNeverLabelledAsAi() {
        Brief brief = BriefWriter.build(facts(), answer("{\"findings\": [], \"watch\": []}"));
        assertThat(brief.source()).isEqualTo("fallback");
        assertThat(brief.reason()).isEqualTo(BriefWriter.EMPTY);
        assertThat(brief.points().get(0).text()).startsWith("Key figures:");
        assertThat(RetailBriefReader.reasonOf(brief)).isEqualTo("empty");
    }

    /**
     * Rendering itself never writes a stray number; this checks the second line through a fact whose label carries one: a count
     * of 4 cited once may appear once (review of #735). JUSTIFICATION-A3: a new test case for the counted number check.
     */
    @Test
    void aNumberIsCountedSoAStrayCopyOfACitedCountIsRefused() {
        FactSet set = new FactSet(
                LocalDate.of(2026, 10, 20),
                "UGX",
                true,
                List.of(new Fact(
                        "stock.low",
                        "Items low on stock, 4 of them",
                        Kind.COUNT,
                        Polarity.HIGHER_IS_WORSE,
                        4,
                        "4",
                        null,
                        null)),
                List.of());
        String raw = """
                {"findings": [{"fact_keys": ["stock.low"], "topic": "stock_levels", "action": "STOCK_RESTOCK_LOW",
                               "priority": "medium"}], "watch": []}
                """;
        assertThat(BriefWriter.build(PromptFacts.of(set), answer(raw)).reason()).isEqualTo(BriefWriter.VALIDATION);
    }

    @Test
    void aViewWithNoFiguresSaysNothingWasFlagged() {
        PromptFacts none = PromptFacts.of(new FactSet(LocalDate.of(2026, 10, 20), "UGX", false, List.of(), List.of()));
        Brief brief = BriefWriter.fallback(none, "scope");
        assertThat(brief.points())
                .singleElement()
                .satisfies(p -> assertThat(p.text()).isEqualTo("Nothing was flagged in the figures you can see."));
    }

    @Test
    void aNameThatReadsLikeAnAliasIsNotReplacedAgain() {
        FactSet set = new FactSet(
                LocalDate.of(2026, 10, 20),
                "UGX",
                true,
                List.of(
                        new Fact(
                                "item.sales_week",
                                "sales",
                                Kind.MONEY,
                                Polarity.HIGHER_IS_BETTER,
                                1,
                                "UGX 1",
                                null,
                                "a"),
                        new Fact(
                                "item.sales_week",
                                "sales",
                                Kind.MONEY,
                                Polarity.HIGHER_IS_BETTER,
                                2,
                                "UGX 2",
                                null,
                                "b")),
                List.of(new ItemName("a", "Item B box"), new ItemName("b", "Plain tea")));
        assertThat(PromptFacts.of(set).toNames("Item A and Item B")).isEqualTo("Item B box and Plain tea");
    }

    /**
     * The stored brief needs every permission a figure in it needs, over every branch (review of #735, point 8).
     * JUSTIFICATION-A3: a new test case; no existing test covers who may read the stored brief.
     */
    @Test
    void theStoredBriefNeedsEveryPermissionOfItsFigures() {
        java.util.Set<String> all = java.util.Set.copyOf(RetailBriefReader.STORED_BRIEF_NEEDS);
        UUID user = UUID.randomUUID();
        assertThat(RetailBriefReader.seesEverything(
                        com.rincoltech.bms.kernel.Principal.uniform(user, "staff", all, true, java.util.Set.of())))
                .isTrue();
        for (String missing : all) {
            java.util.Set<String> less = new java.util.HashSet<>(all);
            less.remove(missing);
            assertThat(RetailBriefReader.seesEverything(
                            com.rincoltech.bms.kernel.Principal.uniform(user, "staff", less, true, java.util.Set.of())))
                    .as(missing)
                    .isFalse();
        }
        assertThat(RetailBriefReader.seesEverything(com.rincoltech.bms.kernel.Principal.uniform(
                        user, "staff", all, false, java.util.Set.of(UUID.randomUUID()))))
                .as("one branch is not every branch")
                .isFalse();
    }
}
