package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.lending.advisor.internal.Catalogue.Action;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The reviewed catalogue: its shape, and that nothing in it can carry a figure, markup or a placeholder we do not fill. */
class CatalogueTest {

    static final Catalogue C = Catalogue.INSTANCE;

    @Test
    void theCatalogueHasTwelveToTwentyActionsUnderTopicsThatAllHaveOne() {
        assertThat(C.actions().size()).isBetween(12, 20);
        assertThat(C.topics()).hasSizeGreaterThanOrEqualTo(6);
        for (Action a : C.actions().values()) {
            assertThat(C.topics()).as(a.code()).containsKey(a.topic());
            assertThat(a.code()).matches("[A-Z_]{3,50}");
        }
        C.topics()
                .keySet()
                .forEach(t -> assertThat(C.actions().values())
                        .as(t)
                        .anyMatch(a -> a.topic().equals(t)));
        // The topics the spec's advisor covers.
        assertThat(C.topics().keySet())
                .contains(
                        "arrears_follow_up",
                        "collections_focus",
                        "disbursement_pace",
                        "collateral_cover",
                        "concentration",
                        "write_off_review",
                        "liquidity",
                        "data_quality");
    }

    @Test
    void noTextOfTheCatalogueHoldsADigitMarkupOrAnUnfilledPlaceholder() {
        Pattern placeholder = Pattern.compile("\\{\\{([a-z]+)\\}\\}");
        for (Action a : C.actions().values()) {
            Matcher m = placeholder.matcher(a.text());
            while (m.find()) {
                assertThat(m.group(1)).as(a.code()).isEqualTo("where");
            }
            String rest = a.text().replace("{{where}}", "");
            assertThat(rest).as(a.code()).doesNotContainPattern("[\\p{Nd}\\p{No}{}<>\\[\\]@]");
            assertThat(a.text()).as(a.code()).isEqualTo(a.text().strip()).endsWith(".");
        }
        for (String name : Set.of("summary", "finding", "watch", "fallback", "nothing")) {
            assertThat(C.template(name)).as(name).isNotBlank().doesNotContainPattern("[\\p{Nd}<>\\[\\]@]");
        }
        assertThat(C.template("finding")).contains("{{priority}}", "{{facts}}", "{{action}}");
        assertThat(C.template("summary")).contains("{{asof}}", "{{range}}");
    }

    @Test
    void theLoaderRefusesATextThatCouldCarryAFigureOrMarkup() {
        for (String bad : new String[] {
            "Review 5 cases{{where}}.",
            "Review the cases{{somewhere}}.",
            "Review <b>cases</b>.",
            "Review the cases {x}.",
            "Mail me@example.org.",
            "Review the cases ٣.",
            " padded.",
            ""
        }) {
            assertThatThrownBy(() -> Catalogue.checkText("action X", bad, Set.of("where")))
                    .as(bad)
                    .isInstanceOf(IllegalStateException.class);
        }
        Catalogue.checkText("action X", "Review the oldest cases{{where}} this week.", Set.of("where"));
    }

    @Test
    void theCatalogueFileOnTheClasspathIsTheOneThatLoaded() throws Exception {
        try (InputStream in = CatalogueTest.class.getResourceAsStream(Catalogue.RESOURCE)) {
            String file = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (Action a : C.actions().values()) {
                assertThat(file).contains("\"" + a.code() + "\"");
            }
        }
    }

    @Test
    void aFactIsBoundToATopicByItsFamilyWhateverItsAliasOrPeriod() {
        AliasedFacts facts = AliasedFacts.of(com.rincoltech.bms.lending.advisor.Facts.standard());
        java.util.Map<String, String> expected = java.util.Map.of(
                "portfolio.par30", "portfolio.par30",
                "portfolio.par30.week_change", "portfolio.par30",
                "portfolio.par30.month_change", "portfolio.par30",
                "branch_1.par30", "branch.par30",
                "branch_1.loans", "branch.loans",
                "product_a.loans", "product.loans",
                "brief.disbursed_today.week_change", "brief.disbursed_today");
        for (var e : expected.entrySet()) {
            var fact = facts.facts().stream()
                    .filter(f -> f.key().equals(e.getKey()))
                    .findFirst()
                    .orElseThrow();
            assertThat(Catalogue.base(fact)).as(e.getKey()).isEqualTo(e.getValue());
        }
    }

    // ---- Order and duplicates (review round 5) ----------------------------------------------------------------

    @Test
    void topicsAndActionsKeepTheOrderOfTheFileWhateverTheJvm() throws Exception {
        try (InputStream in = CatalogueTest.class.getResourceAsStream(Catalogue.RESOURCE)) {
            tools.jackson.databind.JsonNode root = tools.jackson.databind.json.JsonMapper.builder()
                    .build()
                    .readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            java.util.List<String> topics = new java.util.ArrayList<>();
            root.get("topics").forEach(t -> topics.add(t.get("code").asString()));
            java.util.List<String> actions = new java.util.ArrayList<>();
            root.get("actions").forEach(a -> actions.add(a.get("code").asString()));
            assertThat(C.topics().keySet()).containsExactlyElementsOf(topics);
            assertThat(C.actions().keySet()).containsExactlyElementsOf(actions);
            assertThat(C.topicCodes()).containsExactlyElementsOf(topics);
            // The topic's fact families keep the file's order too.
            for (tools.jackson.databind.JsonNode t : root.get("topics")) {
                java.util.List<String> facts = new java.util.ArrayList<>();
                t.get("facts").forEach(f -> facts.add(f.asString()));
                assertThat(C.topics().get(t.get("code").asString()).facts()).containsExactlyElementsOf(facts);
            }
        }
    }

    private static String file() throws Exception {
        try (InputStream in = CatalogueTest.class.getResourceAsStream(Catalogue.RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void theFileOnTheClasspathParsesToTheSameCatalogue() throws Exception {
        Catalogue again = Catalogue.parse(file());
        assertThat(again.actions().keySet())
                .containsExactlyElementsOf(C.actions().keySet());
        assertThat(again.topics().keySet()).containsExactlyElementsOf(C.topics().keySet());
    }

    @Test
    void aDuplicateActionCodeTopicCodeOrJsonKeyFailsTheLoadInsteadOfTheLastOneWinning() throws Exception {
        String ok = file();
        // The first action's line, repeated: the same code twice.
        String firstAction = java.util.Arrays.stream(ok.split("\n"))
                .filter(l -> l.contains("\"code\": \"ARREARS_REVIEW_OLDEST\""))
                .findFirst()
                .orElseThrow();
        String duplicateAction = ok.replace(firstAction, firstAction + "\n" + firstAction);
        assertThatThrownBy(() -> Catalogue.parse(duplicateAction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate action code ARREARS_REVIEW_OLDEST");
        // The same topic twice.
        String duplicateTopic = ok.replace("\"code\": \"liquidity\",", "\"code\": \"concentration\",");
        assertThatThrownBy(() -> Catalogue.parse(duplicateTopic))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate topic code concentration");
        // The same JSON key twice in one object (a last-one-wins in some parsers).
        String duplicateKey = ok.replace("\"nothing\":", "\"nothing\": \"x\", \"nothing\":");
        assertThatThrownBy(() -> Catalogue.parse(duplicateKey)).isInstanceOf(IllegalStateException.class);
        // A calm wording on an action not marked calm, and a calm mark on a text that does not say so.
        String unmarked = ok.replace("\"calm\": true, ", "");
        assertThatThrownBy(() -> Catalogue.parse(unmarked))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marked calm");
        String wronglyMarked = ok.replace(
                "{\"code\": \"ARREARS_REVIEW_OLDEST\", \"topic\": \"arrears_follow_up\",",
                "{\"code\": \"ARREARS_REVIEW_OLDEST\", \"topic\": \"arrears_follow_up\", \"calm\": true,");
        assertThatThrownBy(() -> Catalogue.parse(wronglyMarked))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marked calm");
    }
}
