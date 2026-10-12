package com.rincoltech.bms.retail.reports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The figure scan behind every "this caller must not see that figure" check of the retail report
 * tests. If it stopped finding a figure, those checks would pass whatever the API leaked, so it is
 * pinned here without a database: it must find a figure as a number and as a string, and it must not
 * trip on the digits of an id, a date or a timestamp (#237).
 */
class AnalyticsDataFiguresTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    @Test
    void findsAFigureSentAsANumber() {
        assertThat(AnalyticsData.figures(json("{\"totals\":{\"cost_minor\":1800,\"rate\":255.0}}")))
                .contains("1800", "255.0");
    }

    @Test
    void findsAFigureSentAsAStringAtAnyDepth() {
        assertThat(AnalyticsData.figures(json("{\"items\":[{\"days_of_cover\":\"255.0\",\"qty\":\"1450\"}]}")))
                .contains("255.0", "1450");
    }

    @Test
    void findsAFigureInsideASentenceOrAWrittenPercent() {
        assertThat(AnalyticsData.figures(
                        json("{\"detail\":\"Cost was UGX 27,200, margin 33.33%, cost 1800.\",\"title\":\"x\"}")))
                .contains("27200", "33.33", "1800");
    }

    @Test
    void matchesExactTokensOnly() {
        assertThat(AnalyticsData.figures(json("{\"a\":11800,\"b\":\"x1800y\",\"c\":\"18000\",\"d\":\"1800.5\"}")))
                .doesNotContain("1800");
    }

    @Test
    void skipsIdsDatesAndTimestampsButNotOtherFields() {
        JsonNode body = json("""
                {"id":"11111800-aaaa-bbbb-cccc-1800aaaa1800","branch_id":"a-1800","request_id":"1800",
                 "sale_date":"2026-10-18","due_on":"1800","created_at":"2026-10-18T10:00:00Z",
                 "note":"on 2026-10-18 at 7e5d3c2a-1111-4222-8333-444455556666 sold 12",
                 "ids":["1800"],"branch_ids":[1800]}
                """);
        assertThat(AnalyticsData.figures(body)).containsExactlyInAnyOrder("12");
    }

    @Test
    void anEmptyOrNullBodyHasNoFigures() {
        assertThat(AnalyticsData.figures(null)).isEmpty();
        assertThat(AnalyticsData.figures(json("{}"))).isEmpty();
        assertThat(AnalyticsData.figures(json("{\"a\":null}"))).isEmpty();
    }
}
