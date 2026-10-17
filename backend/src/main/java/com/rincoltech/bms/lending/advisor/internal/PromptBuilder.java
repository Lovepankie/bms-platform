package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.lending.advisor.internal.AliasedFacts.PromptFact;
import java.util.List;

/**
 * The request for a brief (spec 5.6): a fixed system prompt (the placeholder contract, a reviewed resource), and the facts as the user content, one per line in their
 * display form (grouped digits, so that no run of six digits reaches the gateway's input screen). The content is built
 * from {@link AliasedFacts} alone, which holds no name, so a name cannot get into it; the evaluation harness and the
 * canary test check the finished request anyway.
 */
final class PromptBuilder {

    /** The most output tokens a brief may take. The gateway also caps it. */
    static final int MAX_TOKENS = 700;

    /**
     * A phrase that appears only in the system prompt. An answer that holds it has repeated the prompt, which the
     * validator refuses.
     */
    static final String CANARY = "ADVISOR-PROMPT-V1";

    /** The reviewed text of the system prompt: a resource, so a change to the contract is a change to a file in review. */
    static final String RESOURCE = "/advisor/system-prompt.txt";

    // JUSTIFICATION-A3: the prompt must list the topic and action codes of the catalogue (the only strings the model
    // may write); no existing code does.
    static final String SYSTEM =
            load().replace("@CANARY@", CANARY).replace("@TOPICS@", topics()).replace("@ACTIONS@", actions());

    /** The topic codes with their meaning, from the catalogue. */
    private static String topics() {
        StringBuilder out = new StringBuilder();
        Catalogue.INSTANCE
                .topics()
                .values()
                .forEach(t -> out.append("- ")
                        .append(t.code())
                        .append(": ")
                        .append(t.label())
                        .append('\n'));
        return out.toString().stripTrailing();
    }

    /** The action codes with the topic each belongs to and what the system will recommend, from the catalogue. */
    private static String actions() {
        StringBuilder out = new StringBuilder();
        Catalogue.INSTANCE
                .actions()
                .values()
                .forEach(a -> out.append("- ")
                        .append(a.code())
                        .append(", ")
                        .append(a.topic())
                        .append(": ")
                        .append(a.text().replace("{{where}}", ""))
                        .append('\n'));
        return out.toString().stripTrailing();
    }

    private static String load() {
        try (java.io.InputStream in = PromptBuilder.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    private PromptBuilder() {}

    /** The request for a daily brief over these facts. */
    static LlmClient.Request brief(AliasedFacts facts) {
        return new LlmClient.Request(
                List.of(new LlmClient.Message("system", SYSTEM), new LlmClient.Message("user", content(facts))),
                MAX_TOKENS,
                true);
    }

    static String content(AliasedFacts facts) {
        StringBuilder out = new StringBuilder();
        out.append("As of: ").append(facts.asOf()).append('\n');
        out.append("Totals cover ")
                .append(facts.from())
                .append(" to ")
                .append(facts.to())
                .append(". Currency: ")
                .append(facts.currency())
                .append(".\n");
        out.append("FACTS (key | name | value | note)\n");
        for (PromptFact f : facts.facts()) {
            out.append(f.key()).append(" | ").append(f.label()).append(" | ").append(f.display());
            if (!f.suppressed() && f.value() != null && f.display() != null) {
                out.append(" | ").append(note(f));
            }
            out.append('\n');
        }
        out.append("END FACTS\n");
        return out.toString();
    }

    private static String note(PromptFact f) {
        return switch (f.polarity()) {
            case HIGHER_IS_BETTER -> "higher is better";
            case HIGHER_IS_WORSE -> "lower is better";
            case NEUTRAL -> "neither higher nor lower is better";
        };
    }
}
