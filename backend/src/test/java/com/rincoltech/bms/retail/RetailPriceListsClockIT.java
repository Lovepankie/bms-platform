package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * The effective dates of a price list at the edge of the business day (issue #195; chapter 5 section 5.4.5): at 22:30 UTC
 * it is already 01:30 the next day in Africa/Kampala, and the business date, not the UTC date, decides when a price
 * starts, whether a start is "in the past", the default sale date and which item a sale takes. Fabricated names and amounts.
 */
@Import(RetailPriceListsClockIT.FixedClock.class)
class RetailPriceListsClockIT extends IntegrationTest {

    /** 2026-10-08 01:30 in Kampala, 2026-10-07 22:30 in UTC. */
    static final Instant NOW = Instant.parse("2026-10-07T22:30:00Z");

    static final String KAMPALA = "2026-10-08";
    static final String UTC_DAY = "2026-10-07";
    static final String SETTINGS = "core.settings.manage";
    static final String MANAGER = ADMIN + ",retail.pricelist.manage,retail.pricelist.use,retail.price.override";

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

    @Autowired
    BusinessClock clock;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID pen;
    UUID piece;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("pl-clock", false, true);
        api = new RetailTestSupport(http, t);
        pen = api.product("PC-PEN", 30, 100);
        piece = UUID.fromString(
                api.get("/products/" + pen, ADMIN).getBody().get("unit_id").asString());
        api.stockUp(t.headOffice(), pen, "1000");
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("price_lists", true),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
    }

    ResponseEntity<JsonNode> item(UUID list, long price, String from, String to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product_id", pen);
        body.put("unit_id", piece);
        body.put("price_minor", price);
        if (from != null) {
            body.put("valid_from", from);
        }
        if (to != null) {
            body.put("valid_to", to);
        }
        return api.post("/price-lists/" + list + "/items", body, MANAGER);
    }

    ResponseEntity<JsonNode> sale(UUID customer, String date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        body.put("customer_id", customer);
        if (date != null) {
            body.put("sale_date", date);
        }
        body.put("lines", List.of(Map.of("product_id", pen, "qty", "1")));
        return api.postKeyed("/sales", body, MANAGER, "*", UUID.randomUUID().toString());
    }

    @Test
    void theBusinessDateNotTheUtcDateDecidesWhenAPriceStartsAndWhichOneASaleTakes() {
        assertThat(clock.today(BusinessClock.DEFAULT_ZONE)).isEqualTo(LocalDate.parse(KAMPALA));
        UUID list = RetailTestSupport.id(api.post("/price-lists", Map.of("name", "Wholesale"), MANAGER));
        UUID customer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 01"), ADMIN));
        int version = api.get("/customers", ADMIN)
                .getBody()
                .get("items")
                .get(0)
                .get("version")
                .asInt();
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/customers/" + customer + "/pricing",
                                Map.of("price_list_id", list),
                                MANAGER,
                                "*",
                                Map.of("If-Match", String.valueOf(version)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // The default first day is the Kampala day; the UTC day is already the past.
        ResponseEntity<JsonNode> first = item(list, 90, null, null);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("valid_from").asString()).isEqualTo(KAMPALA);
        assertThat(first.getBody().get("status").asString()).isEqualTo("current");
        ResponseEntity<JsonNode> past = item(list, 80, UTC_DAY, null);
        assertThat(past.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(past.getBody().toString()).contains("in_the_past");

        // A sale with no date is dated with the Kampala day and takes the price that starts on it.
        JsonNode today = sale(customer, null).getBody();
        assertThat(today.get("sale_date").asString()).isEqualTo(KAMPALA);
        assertThat(today.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(90);
        // The day before the first day the price is not yet in force: the retail price.
        JsonNode before = sale(customer, UTC_DAY).getBody();
        assertThat(before.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(100);
        assertThat(before.get("lines").get(0).get("price").get("source").asString())
                .isEqualTo("retail");

        // A price from the next Kampala day closes the current one on the Kampala day (its last day).
        ResponseEntity<JsonNode> next = item(list, 85, "2026-10-09", null);
        assertThat(next.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(next.getBody().get("status").asString()).isEqualTo("scheduled");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT valid_to::text FROM retail_price_list_items WHERE price_minor = 90 AND tenant_id = ?")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo(KAMPALA);
        assertThat(sale(customer, null)
                        .getBody()
                        .get("lines")
                        .get(0)
                        .get("unit_price_minor")
                        .asLong())
                .isEqualTo(90);

        // An item that ended on the UTC day is ended by the Kampala day: it prices nothing on it.
        UUID old = RetailTestSupport.id(api.post("/price-lists", Map.of("name", "Old"), MANAGER));
        TestDatabase.owner().sql("""
                        INSERT INTO retail_price_list_items (id, tenant_id, price_list_id, product_id, unit_id, min_qty,
                            price_minor, valid_from, valid_to)
                        VALUES (gen_random_uuid(), ?, ?, ?, ?, 0, 50, DATE '2026-10-01', DATE '2026-10-07')
                        """).params(t.tenantId(), old, pen, piece).update();
        JsonNode ended = api.get("/price-lists/" + old + "/items?include_ended=true", MANAGER)
                .getBody()
                .get("items")
                .get(0);
        assertThat(ended.get("status").asString()).isEqualTo("ended");
        assertThat(api.get("/price-lists/" + old + "/items", MANAGER).getBody().get("items"))
                .isEmpty();
    }
}
