package com.rincoltech.bms.lending.insights.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.lending.seed.internal.LendingSeeder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * FR-INS-08: the owner's daily digest is off by default, is switched on through the settings route
 * with a recipient, queues one plain-text message per recipient once a day through the outbox, and
 * a second run the same day queues nothing.
 */
class InsightsDigestIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    LendingSeeder seeder;

    @Autowired
    TenantJobs tenants;

    @Autowired
    DigestService digest;

    @Autowired
    PlatformTransactionManager transactionManager;

    HttpHeaders admin(TestDatabase.Fixture t, String ifMatch) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", "core.settings.manage");
        h.add("X-Dev-Branch-Ids", "*");
        if (ifMatch != null) {
            h.add(HttpHeaders.IF_MATCH, ifMatch);
        }
        return h;
    }

    int sendNow(TestDatabase.Fixture t) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tenants.callAsTenant(t.slug(), "lending", () -> tx.execute(s -> digest.sendIfDue()));
    }

    static List<Map<String, Object>> rows(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("SELECT channel, recipient, template_key, params::text AS params FROM notification_outbox"
                        + " WHERE idempotency_key LIKE ? ORDER BY channel")
                .param("insights.digest:" + t.tenantId() + ":%")
                .query()
                .listOfRows();
    }

    @Test
    void theDigestIsOffByDefaultThenGoesOnceADay() {
        TestDatabase.Fixture t = TestDatabase.tenant("digest", true);
        seeder.seed(t.slug());

        ResponseEntity<JsonNode> settings = http.exchange(
                "/api/v1/lending/insights/digest", HttpMethod.GET, new HttpEntity<>(admin(t, null)), JsonNode.class);
        assertThat(settings.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(settings.getBody().get("enabled").asBoolean()).isFalse();
        assertThat(sendNow(t)).isZero();
        assertThat(rows(t)).isEmpty();

        ResponseEntity<JsonNode> refused = http.exchange(
                "/api/v1/lending/insights/digest",
                HttpMethod.PUT,
                new HttpEntity<>(
                        Map.of("enabled", true, "email_recipients", List.of(), "send_hour", 0), admin(t, "\"0\"")),
                JsonNode.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("digest_needs_recipient");

        ResponseEntity<JsonNode> on = http.exchange(
                "/api/v1/lending/insights/digest",
                HttpMethod.PUT,
                new HttpEntity<>(
                        Map.of(
                                "enabled",
                                true,
                                "email_recipients",
                                List.of("owner01@example.test"),
                                "telegram_chat_id",
                                "-100200300",
                                "send_hour",
                                0),
                        admin(t, "\"0\"")),
                JsonNode.class);
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody().get("enabled").asBoolean()).isTrue();

        ResponseEntity<JsonNode> stale = http.exchange(
                "/api/v1/lending/insights/digest",
                HttpMethod.PUT,
                new HttpEntity<>(
                        Map.of("enabled", false, "email_recipients", List.of(), "send_hour", 7), admin(t, "\"0\"")),
                JsonNode.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);

        assertThat(sendNow(t)).isEqualTo(2);
        List<Map<String, Object>> queued = rows(t);
        assertThat(queued).extracting(r -> r.get("channel")).containsExactly("email", "telegram");
        assertThat(queued)
                .extracting(r -> r.get("recipient"))
                .containsExactly("owner01@example.test", "chat:-100200300");
        assertThat((String) queued.getFirst().get("params"))
                .contains("Daily brief for")
                .contains("principal outstanding on")
                .doesNotContainPattern("[\\u2013\\u2014]");
        assertThat(sendNow(t)).as("once a day").isZero();
        assertThat(rows(t)).hasSize(2);

        ResponseEntity<JsonNode> preview = http.exchange(
                "/api/v1/lending/insights/digest/preview",
                HttpMethod.GET,
                new HttpEntity<>(admin(t, null)),
                JsonNode.class);
        assertThat(preview.getBody().get("text").asString()).startsWith("Daily brief for");
    }
}
