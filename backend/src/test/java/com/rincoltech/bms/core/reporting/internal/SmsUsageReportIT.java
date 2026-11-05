package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The report {@code core.sms_usage} (issue #215, FR-NTF-07, FR-NTF-15, chapter 14 section 14.6): its tables equal the
 * hand-computed totals of a fabricated September 2026, months are calendar months in the business's time zone, only messages handed to the provider
 * count, the provider's segments win over ours, a cost in another currency is not added, another business's messages
 * never appear, and the report is offered only to a business with SMS on or SMS history. Every figure is fabricated
 * test data, not a price.
 */
@Import(TestReports.class)
@TestPropertySource(
        properties = {
            "bms.reports.max-concurrent-runs=1",
            "bms.reports.timeout=PT10S",
            "bms.reports.xlsx-row-cap=500",
            "bms.reports.pdf-row-cap=200",
            "bms.reports.max-file-bytes=1048576",
            "bms.reports.cancel-poll=PT0.2S",
            "bms.reports.heartbeat=PT0.1S",
            "bms.reports.runner-enabled=false"
        })
class SmsUsageReportIT extends ReportsItSupport {

    static final String KEY = "core.sms_usage";
    static final String FINANCIAL = "core.reports.financial";
    static final Map<String, Object> RANGE = Map.of("from", "2026-09-01", "to", "2026-10-08");

    @Autowired
    ObjectMapper mapper;

    /** One fabricated delivery row; cost in minor units, fabricated test figures. */
    static void message(
            TestDatabase.Fixture t,
            String template,
            String status,
            String createdAtUtc,
            Integer segments,
            Integer providerSegments,
            Long cost,
            String currency) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO sms_deliveries (id, tenant_id, created_at, updated_at, outbox_id, template_key, category,
                                                    recipient_masked, recipient_hash, provider, provider_message_id,
                                                    segments, provider_segments, cost_minor, currency, status)
                        VALUES (?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), ?, ?, 'transactional', '+256*****0101',
                                repeat('b', 64), ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        t.tenantId(),
                        createdAtUtc,
                        createdAtUtc,
                        UUID.randomUUID(),
                        template,
                        status.equals("sunk") ? "sink" : "africastalking",
                        status.equals("sent") || status.equals("delivered") || status.equals("undelivered")
                                ? "ATXid_" + UUID.randomUUID().toString().replace("-", "")
                                : null,
                        segments,
                        providerSegments,
                        cost,
                        currency,
                        status)
                .update();
    }

    /**
     * The fabricated month of business A, and what must not count.
     *
     * <pre>
     * September 2026 (Kampala)       messages delivered not delivered segments cost without a cost
     *   Loan repayment receipt           3        2          0            6      90        2
     *     delivered, ours 2, provider 3, cost 90 UGX            -> 3 segments
     *     unknown outcome, ours 2, no cost                      -> 2 segments, without a cost
     *     delivered, 1 segment, cost 25 KES (not the business's) -> without a cost
     *   Savings deposit receipt          4        2          1            5     150        0
     *     delivered 1 seg 30; delivered ours 1 no provider count 30; undelivered 1 seg 30;
     *     sent 2 seg 60 at 21:30 UTC on 31 August, 00:30 on 1 September in Kampala
     * October 2026
     *   Savings deposit receipt          1        1          0            1      30        0
     *     delivered at 21:30 UTC on 30 September, 00:30 on 1 October in Kampala
     * Not counted in September: sunk, failed, rejected, blocked by an opt out, still queued; business B's message.
     * Totals                             8        5          1           12     270        2
     * </pre>
     */
    TestDatabase.Fixture fabricatedMonth() {
        TestDatabase.Fixture t = tenant(true);
        message(t, "loan.repayment_received", "delivered", "2026-09-20T09:00:00Z", 2, 3, 90L, "UGX");
        message(t, "loan.repayment_received", "unknown", "2026-09-21T09:00:00Z", 2, null, null, null);
        message(t, "loan.repayment_received", "delivered", "2026-09-22T09:00:00Z", 1, 1, 25L, "KES");
        message(t, "savings.deposit", "delivered", "2026-09-03T06:00:00Z", 1, 1, 30L, "UGX");
        message(t, "savings.deposit", "delivered", "2026-09-10T06:00:00Z", 1, null, 30L, "UGX");
        message(t, "savings.deposit", "undelivered", "2026-09-15T06:00:00Z", 1, 1, 30L, "UGX");
        message(t, "savings.deposit", "sent", "2026-08-31T21:30:00Z", 2, 2, 60L, "UGX");
        message(t, "savings.deposit", "delivered", "2026-09-30T21:30:00Z", 1, 1, 30L, "UGX");
        // Not handed to the provider, so not counted.
        message(t, "savings.deposit", "sunk", "2026-09-05T06:00:00Z", 1, null, null, null);
        message(t, "savings.deposit", "failed", "2026-09-06T06:00:00Z", 1, null, null, null);
        message(t, "savings.deposit", "rejected", "2026-09-07T06:00:00Z", 1, null, null, null);
        message(t, "savings.deposit", "blocked_optout", "2026-09-08T06:00:00Z", 1, null, null, null);
        message(t, "savings.deposit", "queued", "2026-09-09T06:00:00Z", 1, null, null, null);
        // Before the range: 31 August in Kampala.
        message(t, "savings.deposit", "delivered", "2026-08-31T20:30:00Z", 1, 1, 30L, "UGX");
        return t;
    }

    JsonNode file(TestDatabase.Fixture t, UUID user, Map<String, Object> params) throws Exception {
        ResponseEntity<JsonNode> posted = post(t, user, FINANCIAL, KEY, params, "json");
        assertThat(posted.getStatusCode().value()).as("%s", posted.getBody()).isEqualTo(202);
        drain();
        JsonNode run = run(t, user, FINANCIAL, posted.getBody().get("id").asString());
        assertReady(run, KEY);
        return mapper.readTree(
                download(t, user, FINANCIAL, run.get("document_id").asString()));
    }

    static List<String> rows(JsonNode file, String table) {
        for (JsonNode t : file.get("tables")) {
            if (t.get("key").asString().equals(table)) {
                List<String> out = new ArrayList<>();
                t.get("rows").forEach(r -> out.add(r.toString()));
                return out;
            }
        }
        throw new AssertionError("no table " + table);
    }

    @Test
    void theReportEqualsTheHandComputedTotalsOfAFabricatedMonth() throws Exception {
        TestDatabase.Fixture a = fabricatedMonth();
        TestDatabase.Fixture b = tenant(true);
        message(b, "savings.deposit", "delivered", "2026-09-10T06:00:00Z", 1, 1, 999L, "UGX");
        UUID user = UUID.randomUUID();

        JsonNode file = file(a, user, RANGE);

        assertThat(rows(file, "usage"))
                .containsExactly(
                        "[\"September 2026\",\"Loan repayment receipt\",3,2,0,6,90,2]",
                        "[\"September 2026\",\"Savings deposit receipt\",4,2,1,5,150,0]",
                        "[\"October 2026\",\"Savings deposit receipt\",1,1,0,1,30,0]",
                        "[\"Total\",null,8,5,1,12,270,2]");
        assertThat(rows(file, "months"))
                .containsExactly("[\"September 2026\",7,11,240]", "[\"October 2026\",1,1,30]", "[\"Total\",8,12,270]");
        assertThat(file.get("parameters").get("from").asString()).isEqualTo("2026-09-01");
        assertThat(file.get("parameters").get("to").asString()).isEqualTo("2026-10-31");
        assertThat(file.get("currency").asString()).isEqualTo("UGX");

        // Business B sees its own message only.
        JsonNode ofB = file(b, user, RANGE);
        assertThat(rows(ofB, "usage"))
                .containsExactly(
                        "[\"September 2026\",\"Savings deposit receipt\",1,1,0,1,999,0]",
                        "[\"Total\",null,1,1,0,1,999,0]");
    }

    /**
     * Issue #808: the months are the business's own time zone (its profile), not Kampala's. A business in Africa/Lagos
     * (UTC+1): 22:30 UTC on 30 September is still September there (October in Kampala), and 22:30 UTC on 31 August is
     * still August there (September in Kampala). No cost is recorded on these rows. JUSTIFICATION-A3: a new case.
     */
    @Test
    void theMonthsAreTheBusinesssOwnTimeZoneNotKampalas() throws Exception {
        TestDatabase.Fixture lagos = tenant(true);
        TestDatabase.owner()
                .sql("UPDATE tenants SET timezone = 'Africa/Lagos' WHERE id = ?")
                .param(lagos.tenantId())
                .update();
        message(lagos, "savings.deposit", "delivered", "2026-09-30T22:30:00Z", 1, 1, null, null);
        message(lagos, "savings.deposit", "delivered", "2026-08-31T22:30:00Z", 1, 1, null, null);

        JsonNode file = file(lagos, UUID.randomUUID(), RANGE);

        assertThat(rows(file, "usage"))
                .anyMatch(r -> r.startsWith("[\"September 2026\",\"Savings deposit receipt\",1,1,0,1,"))
                .noneMatch(r -> r.contains("October 2026"))
                .noneMatch(r -> r.contains("August 2026"));
    }

    @Test
    void theReportIsOfferedOnlyToABusinessWithSmsOnOrSmsHistory() {
        TestDatabase.Fixture none = tenant(true);
        UUID user = UUID.randomUUID();
        assertThat(keys(none, user)).doesNotContain(KEY);
        ResponseEntity<JsonNode> refused = post(none, user, FINANCIAL, KEY, Map.of(), "json");
        assertThat(refused.getStatusCode().value()).isEqualTo(404);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("report_not_found");

        // The business switches SMS on: offered.
        SmsTenants.enable(none.tenantId());
        assertThat(keys(none, user)).contains(KEY);

        // A business with SMS off that used it before still sees what it used.
        TestDatabase.Fixture history = tenant(true);
        message(history, "savings.deposit", "delivered", "2026-09-10T06:00:00Z", 1, 1, 30L, "UGX");
        assertThat(keys(history, user)).contains(KEY);
        // Without the permission it is not listed.
        List<String> withoutPermission = new ArrayList<>();
        call(HttpMethod.GET, "/api/v1/reports", history, user, PORTFOLIO)
                .getBody()
                .forEach(r -> withoutPermission.add(r.get("key").asString()));
        assertThat(withoutPermission).doesNotContain(KEY);
    }

    @Test
    void aRangeBackwardsOrLongerThan24MonthsIsRefused() {
        TestDatabase.Fixture t = tenant(true);
        SmsTenants.enable(t.tenantId());
        UUID user = UUID.randomUUID();
        ResponseEntity<JsonNode> backwards =
                post(t, user, FINANCIAL, KEY, Map.of("from", "2026-10-01", "to", "2026-09-30"), "json");
        assertThat(backwards.getStatusCode().value()).isEqualTo(422);
        ResponseEntity<JsonNode> tooLong =
                post(t, user, FINANCIAL, KEY, Map.of("from", "2024-10-01", "to", "2026-10-08"), "json");
        assertThat(tooLong.getStatusCode().value()).isEqualTo(422);
        ResponseEntity<JsonNode> twentyFour =
                post(t, user, FINANCIAL, KEY, Map.of("from", "2024-11-01", "to", "2026-10-08"), "json");
        assertThat(twentyFour.getStatusCode().value())
                .as("%s", twentyFour.getBody())
                .isEqualTo(202);
    }

    List<String> keys(TestDatabase.Fixture t, UUID user) {
        List<String> keys = new ArrayList<>();
        call(HttpMethod.GET, "/api/v1/reports", t, user, FINANCIAL)
                .getBody()
                .forEach(r -> keys.add(r.get("key").asString()));
        return keys;
    }
}
