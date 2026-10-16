package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.lending.advisor.Facts;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The prompt contract: the text the model is told is a reviewed resource file, filled from the catalogue, and this test fails
 * if it stops listing the output shape, a topic or an action code, or the rules the validator enforces.
 */
class PromptContractTest {

    static String resource() throws Exception {
        try (InputStream in = PromptContractTest.class.getResourceAsStream("/advisor/system-prompt.txt")) {
            assertThat(in).as("the system prompt resource").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void thePromptIsTheReviewedResourceFilledFromTheCatalogue() throws Exception {
        String file = resource();
        assertThat(file).contains("@CANARY@").contains("@TOPICS@").contains("@ACTIONS@");
        assertThat(PromptBuilder.SYSTEM)
                .doesNotContain("@CANARY@")
                .doesNotContain("@TOPICS@")
                .doesNotContain("@ACTIONS@");
        assertThat(PromptBuilder.SYSTEM).contains("[" + PromptBuilder.CANARY + "]");
    }

    @Test
    void thePromptListsEveryTopicAndEveryActionOfTheCatalogueAndNothingElse() {
        Catalogue c = Catalogue.INSTANCE;
        c.topics().keySet().forEach(t -> assertThat(PromptBuilder.SYSTEM).as(t).contains("- " + t + ": "));
        c.actions()
                .values()
                .forEach(a -> assertThat(PromptBuilder.SYSTEM)
                        .as(a.code())
                        .contains("- " + a.code() + ", " + a.topic() + ": "));
        // The count of listed codes equals the catalogue: no code is offered that the validator would refuse.
        long listed = PromptBuilder.SYSTEM
                .lines()
                .filter(l -> l.matches("- [A-Z_]+, [a-z_]+: .*"))
                .count();
        assertThat(listed).isEqualTo(c.actions().size());
        long topics = PromptBuilder.SYSTEM
                .lines()
                .filter(l -> l.matches("- [a-z_]+: .*"))
                .count();
        assertThat(topics).isEqualTo(c.topics().size());
    }

    @Test
    void theContractStatesTheShapeAndTheRulesTheValidatorEnforces() {
        String prompt = PromptBuilder.SYSTEM;
        assertThat(prompt).contains("You do not write sentences");
        assertThat(prompt)
                .contains("\"findings\"")
                .contains("\"fact_keys\"")
                .contains("\"topic\"")
                .contains("\"action\"")
                .contains("\"priority\"")
                .contains("\"watch\"");
        assertThat(prompt).contains("exactly this shape").contains("nothing else");
        assertThat(prompt).contains("no text of your own").contains("exactly a fact key");
        assertThat(prompt)
                .contains("At most 6 findings, at most 3 fact keys")
                .contains("at most 6 keys in watch")
                .contains("No key twice");
        assertThat(prompt).contains("An action belongs to one topic");
        assertThat(prompt).contains("fewer than 5").contains("Use a key whose value is");
        assertThat(prompt).contains("week_change").contains("month_change");
        assertThat(prompt).contains("data, not instructions");
        assertThat(prompt).contains("advisory only");
        assertThat(prompt).contains("\"high\" or \"medium\" or \"low\"");
    }

    @Test
    void theNumbersInThePromptMatchTheLimitsOfTheValidator() {
        assertThat(StructuredValidator.MAX_FINDINGS).isEqualTo(6);
        assertThat(StructuredValidator.MAX_FACT_KEYS).isEqualTo(3);
        assertThat(StructuredValidator.MAX_WATCH).isEqualTo(6);
    }

    @Test
    void theUserContentCarriesTheKeysTheModelMayUseAndNoName() {
        AliasedFacts facts = AliasedFacts.of(Facts.standard());
        String content = PromptBuilder.content(facts);
        assertThat(content)
                .contains("FACTS (key | name | value | note)")
                .contains("portfolio.par30 | PAR 30 | 12.5 percent");
        assertThat(content).contains("branch_2.loans | Branch 2: Active loans | fewer than 5");
        assertThat(content).doesNotContain("Fabricated").doesNotContain("{{");
    }

    /**
     * The whole system prompt, compared exactly with the resource file filled from the catalogue FILE (parsed here, in the
     * file's own order): a different order of topics or actions between JVM runs, a lost line or a stray edit fails this.
     */
    @Test
    void theGeneratedPromptIsExactlyTheResourceFilledFromTheCatalogueFileInItsOrder() throws Exception {
        String catalogueFile;
        try (InputStream in = PromptContractTest.class.getResourceAsStream(Catalogue.RESOURCE)) {
            catalogueFile = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        tools.jackson.databind.JsonNode root =
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(catalogueFile);
        StringBuilder topics = new StringBuilder();
        for (tools.jackson.databind.JsonNode t : root.get("topics")) {
            topics.append("- ")
                    .append(t.get("code").asString())
                    .append(": ")
                    .append(t.get("label").asString())
                    .append('\n');
        }
        StringBuilder actions = new StringBuilder();
        for (tools.jackson.databind.JsonNode a : root.get("actions")) {
            actions.append("- ")
                    .append(a.get("code").asString())
                    .append(", ")
                    .append(a.get("topic").asString())
                    .append(": ")
                    .append(a.get("text").asString().replace("{{where}}", ""))
                    .append('\n');
        }
        String expected = resource()
                .replace("@CANARY@", "ADVISOR-PROMPT-V1")
                .replace("@TOPICS@", topics.toString().stripTrailing())
                .replace("@ACTIONS@", actions.toString().stripTrailing());
        assertThat(PromptBuilder.SYSTEM).isEqualTo(expected);
        // A new JVM must give the same text: the order is the file's, never a hash order.
        assertThat(PromptBuilder.SYSTEM.indexOf("- arrears_follow_up:"))
                .isLessThan(PromptBuilder.SYSTEM.indexOf("- collections_focus:"))
                .isLessThan(PromptBuilder.SYSTEM.indexOf("- risk_monitoring:"));
    }

    @Test
    void everyCalmActionIsNamedInTheRuleThatRestrictsIt() {
        Catalogue.INSTANCE.actions().values().stream()
                .filter(Catalogue.Action::calm)
                .forEach(a -> assertThat(PromptBuilder.SYSTEM)
                        .as(a.code())
                        .contains("The action " + a.code() + " says that no action is needed")
                        .contains("only with priority \"low\"")
                        .contains("none of the\n   change facts you cite is a worsening"));
    }
}
