package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.ai.AiGateway.AiResult;
import com.rincoltech.bms.core.ai.AiGateway.Reason;
import com.rincoltech.bms.lending.advisor.Facts;
import com.rincoltech.bms.lending.advisor.internal.BriefBuilder.Brief;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the daily brief stores (ADR-037 as amended in #223, issue #226): the text our renderer made, never the model's; the
 * facts-only brief with the reason for every way a model brief is not available; and an advisory source on every path.
 */
class BriefBuilderTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AliasedFacts FACTS = AliasedFacts.of(Facts.standard());

    static String valid() {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode f = root.putArray("findings").addObject();
        ArrayNode keys = f.putArray("fact_keys");
        keys.add("portfolio.par30");
        f.put("topic", "arrears_follow_up");
        f.put("action", "ARREARS_REVIEW_OLDEST");
        f.put("priority", "high");
        root.putArray("watch");
        return root.toString();
    }

    static AiResult answered(String text) {
        return new AiResult(text, 400, 80, "stop", null, UUID.randomUUID());
    }

    @Test
    void anAcceptedAnswerIsStoredAsTheRenderedTextAndNeverTheModelsOwn() {
        String raw = valid();
        Brief brief = BriefBuilder.build(FACTS, answered(raw));
        assertThat(brief.source()).isEqualTo("model");
        assertThat(brief.reason()).isNull();
        String stored = brief.summary() + brief.points();
        // The raw answer (its keys, its action code, its JSON) is not in what is kept.
        assertThat(stored).doesNotContain(raw).doesNotContain("fact_keys").doesNotContain("ARREARS_REVIEW_OLDEST");
        assertThat(stored).contains("PAR 30");
        assertThat(brief.chips()).extracting(BriefBuilder.Chip::key).contains("portfolio.par30");
    }

    @Test
    void aRefusedAnswerStoresTheFactsOnlyBriefWithReasonValidationAndNoModelText() {
        String prose = "Portfolio at risk is the thing to worry about this week, call everyone.";
        for (String raw : Arrays.asList(
                prose,
                "{\"findings\":[],\"watch\":[],\"summary\":\"" + prose + "\"}",
                valid().replace("high", "urgent"),
                valid().replace("portfolio.par30", "Made Up Branch"))) {
            Brief brief = BriefBuilder.build(FACTS, answered(raw));
            assertThat(brief.source()).isEqualTo("fallback");
            assertThat(brief.reason()).isEqualTo("validation");
            assertThat(brief.summary() + brief.points()).doesNotContain("worry").doesNotContain("Made Up");
            assertThat(brief.points().get(0).text()).startsWith("Key figures");
        }
    }

    @Test
    void everyWayTheGatewayDoesNotAnswerStoresTheFactsOnlyBriefWithItsReason() {
        for (Reason reason : Reason.values()) {
            Brief brief = BriefBuilder.build(FACTS, AiResult.fallback(reason, null));
            assertThat(brief.source()).as(reason.code()).isEqualTo("fallback");
            assertThat(brief.reason()).isEqualTo(reason.code());
            assertThat(brief.points()).isNotEmpty();
        }
    }

    @Test
    void theApiReasonIsInTheClosedVocabularyForEveryStoredReason() {
        for (Reason reason : Reason.values()) {
            String api = BriefReadService.reasonOf(BriefBuilder.fallback(FACTS, reason.code()));
            assertThat(api).as(reason.code()).isIn("off", "validation", "cap", "provider_down", "unavailable");
        }
        assertThat(BriefReadService.reasonOf(BriefBuilder.fallback(FACTS, "validation")))
                .isEqualTo("validation");
        assertThat(BriefReadService.reasonOf(BriefBuilder.fallback(FACTS, "timeout")))
                .isEqualTo("provider_down");
        assertThat(BriefReadService.reasonOf(BriefBuilder.fallback(FACTS, "tenant_token_cap")))
                .isEqualTo("cap");
        assertThat(BriefReadService.reasonOf(BriefBuilder.fallback(FACTS, "not_enabled")))
                .isEqualTo("off");
        assertThat(BriefReadService.reasonOf(BriefBuilder.build(FACTS, answered(valid()))))
                .isNull();
    }
}
