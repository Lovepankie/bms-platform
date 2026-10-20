package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * One business clock (ADR-043): a payment is entered at the moment the business clock says, not at the database's, so the day it
 * counts on is the tenant's day. The clock is pinned at 22:30 UTC, which is already 01:30 the next day in Africa/Kampala, so the
 * UTC date and the business date differ and nothing depends on the day the test runs.
 */
@Import(RetailCreditRecognitionClockIT.FixedClock.class)
class RetailCreditRecognitionClockIT extends IntegrationTest {

    /** 2026-10-08 01:30 in Kampala, 2026-10-07 22:30 in UTC. */
    static final Instant NOW = Instant.parse("2026-10-07T22:30:00Z");

    static final String BUSINESS_DAY = "2026-10-08";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    TestRestTemplate http;

    @Test
    void aPaymentCountsOnTheTenantsDayItWasEnteredNearMidnight() {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-crc", false, true);
        RetailTestSupport api = new RetailTestSupport(http, t);
        UUID product = api.product("CRC-1", 600, 1_000);
        api.importedBalance(t.headOffice(), product, "100");
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> on = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("credit_sale_recognition", "on_payment"),
                "core.settings.manage",
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(on.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody().get("credit_recognition_effective_from").asString())
                .as("the day after the business day, not after the UTC day")
                .isEqualTo("2026-10-09");
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = jsonb_set(settings,"
                        + " '{module_groups,retail,credit_recognition_effective_from}', '\"1970-01-01\"'::jsonb)"
                        + " WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "credit");
        body.put("buyer_name", "Test Buyer 12");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "3")));
        UUID sale = RetailTestSupport.id(
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString()));
        ResponseEntity<JsonNode> paid = api.postKeyed(
                "/sales/" + sale + "/payments",
                Map.of("amount_minor", 1_000, "method", "cash"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(paid.getStatusCode().is2xxSuccessful())
                .as("%s", paid.getBody())
                .isTrue();

        JsonNode report = api.get("/reports/profit/daily?from=" + BUSINESS_DAY + "&to=" + BUSINESS_DAY, ADMIN)
                .getBody();
        assertThat(report.get("rows")).hasSize(1);
        assertThat(report.get("rows").get(0).get("date").asString()).isEqualTo(BUSINESS_DAY);
        assertThat(report.get("rows").get(0).get("sales_minor").asLong()).isEqualTo(1_000);
        assertThat(report.get("rows").get(0).get("profit_minor").asLong()).isEqualTo(400);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT CAST((created_at AT TIME ZONE 'UTC') AS text) FROM retail_sale_payments WHERE sale_id = ?")
                        .param(sale)
                        .query(String.class)
                        .single())
                .as("entered at the business clock's moment, not the database's")
                .startsWith("2026-10-07 22:30:00");
    }

    @Test
    void theDayTheRuleStartsIsStoredAsADateSoChangingTheTimeZoneDoesNotMoveIt() {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-crz", false, true);
        RetailTestSupport api = new RetailTestSupport(http, t);
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> on = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("credit_sale_recognition", "on_payment"),
                "core.settings.manage",
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(on.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody().get("credit_recognition_effective_from").asString())
                .isEqualTo("2026-10-09");

        // The business later moves to a zone where this instant is still the evening of the 7th.
        TestDatabase.owner()
                .sql("UPDATE tenants SET timezone = 'America/Los_Angeles' WHERE id = ?")
                .param(t.tenantId())
                .update();
        assertThat(api.get("/settings/capabilities", "core.settings.manage")
                        .getBody()
                        .get("credit_recognition_effective_from")
                        .asString())
                .as("one stored date: no instant is re-read in another zone")
                .isEqualTo("2026-10-09");
    }

    @Test
    void theEntryDayOfASaleIsTheTenantsDayNotTheUtcDay() {
        TestDatabase.Fixture t = TestDatabase.tenant("retail-crw", false, true);
        RetailTestSupport api = new RetailTestSupport(http, t);
        UUID product = api.product("CRW-1", 600, 1_000);
        api.importedBalance(t.headOffice(), product, "100");
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/settings/capabilities",
                                Map.of("credit_sale_recognition", "on_payment"),
                                "core.settings.manage",
                                "*",
                                etag == null ? Map.of() : Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // The rule starts on 6 October in Kampala.
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = jsonb_set(settings,"
                        + " '{module_groups,retail,credit_recognition_effective_from}', '\"2026-10-06\"'::jsonb)"
                        + " WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        // Two sales dated 5 October. One was entered at 22:30 UTC on the 5th, which is 01:30 on the 6th in Kampala: on
        // the start
        // day, so counted when the buyer pays. The other at 20:30 UTC, which is 23:30 on the 5th: before it, so counted
        // at the sale.
        UUID late = sale(api, t, product, "2026-10-05T22:30:00Z");
        UUID early = sale(api, t, product, "2026-10-05T20:30:00Z");

        assertThat(api.get("/sales/" + late, ADMIN)
                        .getBody()
                        .get("counted_when_paid")
                        .asBoolean())
                .isTrue();
        assertThat(api.get("/sales/" + early, ADMIN)
                        .getBody()
                        .get("counted_when_paid")
                        .asBoolean())
                .isFalse();
        JsonNode report = api.get("/reports/profit/daily?from=2026-10-05&to=2026-10-05", ADMIN)
                .getBody();
        assertThat(report.get("profit_minor").asLong())
                .as("only the sale entered before the start day counts at the sale")
                .isEqualTo(400);
        assertThat(report.get("sold_minor").asLong()).isEqualTo(2_000);
        assertThat(report.get("on_credit_minor").asLong())
                .as("only what is owed on the sale counted when the buyer pays")
                .isEqualTo(1_000);
        assertThat(report.get("counted_at_sale_minor").asLong())
                .as("the profit of the sale entered before the start day, counted at the sale")
                .isEqualTo(400);
        assertThat(report.get("received_minor").asLong())
                .as("nobody has paid either sale")
                .isZero();
    }

    private UUID sale(RetailTestSupport api, TestDatabase.Fixture t, UUID product, String enteredAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("sale_date", "2026-10-05");
        body.put("payment_method", "credit");
        body.put("buyer_name", "Test Buyer 15");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        UUID id = RetailTestSupport.id(
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString()));
        TestDatabase.owner()
                .sql("DO $$ BEGIN PERFORM set_config('bms.allow_mutation', 'on', true);"
                        + " UPDATE retail_sales SET created_at = TIMESTAMPTZ '" + enteredAt + "' WHERE id = '" + id
                        + "'; END $$")
                .update();
        return id;
    }
}
