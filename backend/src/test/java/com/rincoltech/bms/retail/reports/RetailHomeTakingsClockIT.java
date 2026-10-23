package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.MovableClockConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The Home takings count a payment on a credit sale on the Kampala day written on it ({@code paid_on}), not on the moment it was
 * entered (issue #485). A credit sale of 5,000 dated 14 October 2026 at head office is paid in cash near midnight and on a day
 * other than the one it was entered.
 */
@Import(MovableClockConfig.class)
@ResourceLock(MovableClockConfig.LOCK)
class RetailHomeTakingsClockIT extends IntegrationTest {

    static final LocalDate DAY = LocalDate.parse("2026-10-15");

    /** 12:00 on the 15th in Kampala. */
    static final Instant NOON = Instant.parse("2026-10-15T09:00:00Z");

    /** 23:30 on the 15th in Kampala, 20:30 UTC. */
    static final Instant LATE = Instant.parse("2026-10-15T20:30:00Z");

    /** 00:30 on the 16th in Kampala, still the 15th in UTC. */
    static final Instant EARLY = Instant.parse("2026-10-15T21:30:00Z");

    @Autowired
    TestRestTemplate http;

    @Autowired
    MovableClock clock;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID sale;

    @BeforeEach
    void setUp() {
        clock.set(NOON);
        t = TestDatabase.tenant("retail-hclk", false, true);
        api = new RetailTestSupport(http, t);
        UUID product = api.product("HCK-1", 600, 1_000);
        api.stockUp(t.headOffice(), product, "10");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("sale_date", DAY.minusDays(1).toString());
        body.put("payment_method", "credit");
        body.put("buyer_name", "Test Buyer 03");
        body.put("due_date", DAY.plusDays(30).toString());
        body.put("lines", List.of(Map.of("product_id", product, "qty", "5")));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        sale = RetailTestSupport.id(r);
    }

    UUID pay(long amount) {
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + sale + "/payments",
                Map.of("amount_minor", amount, "method", "cash"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return TestDatabase.owner()
                .sql("SELECT id FROM retail_sale_payments WHERE tenant_id = ? AND sale_id = ? AND amount_minor = ?")
                .params(t.tenantId(), sale, amount)
                .query(UUID.class)
                .single();
    }

    JsonNode home() {
        ResponseEntity<JsonNode> r = api.get("/reports/home", RetailHomeSummaryIT.TILL);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static long cash(JsonNode home) {
        return RetailHomeSummaryIT.method(home.get("takings"), "cash")
                .get("amount_minor")
                .asLong();
    }

    @Test
    void aPaymentAt2330CountsThatDayAndOneAt0030TheNext() {
        clock.set(LATE);
        pay(200);
        JsonNode late = home();
        assertThat(late.get("date").asString()).isEqualTo(DAY.toString());
        assertThat(cash(late)).isEqualTo(200);

        clock.set(EARLY);
        pay(300);
        JsonNode early = home();
        assertThat(early.get("date").asString()).isEqualTo(DAY.plusDays(1).toString());
        assertThat(cash(early)).isEqualTo(300);

        // Back on the 15th, the payment of the 16th is not in it.
        clock.set(LATE.plusSeconds(15 * 60));
        assertThat(cash(home())).isEqualTo(200);
    }

    @Test
    void aPaymentDatedAfterTheDayItWasEnteredCountsOnItsOwnDay() {
        // The payment route refuses a paid_on after today; an offline replay or an import can still write one.
        UUID id = pay(400);
        TestDatabase.owner()
                .sql("DO $$ BEGIN PERFORM set_config('bms.allow_mutation', 'on', true);"
                        + " UPDATE retail_sale_payments SET paid_on = DATE '" + DAY.plusDays(1) + "' WHERE id = '" + id
                        + "'; END $$")
                .update();
        assertThat(cash(home())).as("dated tomorrow, so not today's").isZero();

        clock.set(NOON.plusSeconds(24 * 3600));
        JsonNode next = home();
        assertThat(next.get("date").asString()).isEqualTo(DAY.plusDays(1).toString());
        assertThat(cash(next)).as("entered yesterday, dated today").isEqualTo(400);
    }
}
