package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Issue #77, ADR-020 decision 10: cost, loss and gain reach only callers holding
 * {@code retail.profit.read}. An audit reader without it finds none in the audit log; a price
 * editor without it cannot tell costs apart through the price edit; two editors of one product
 * do not overwrite each other. Amounts and names are fabricated.
 */
class RetailCostVisibilityIT extends IntegrationTest {

    /** The auditor's audit permissions, and no retail permission at all. */
    static final String AUDITOR = "core.audit.read,core.audit.export";

    /** A custom role that may edit prices but not read cost. */
    static final String PRICER = "retail.price.edit,retail.stock.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-cost-vis", false, true);
        api = new RetailTestSupport(http, t);
    }

    ResponseEntity<JsonNode> auditEvents(UUID entityId) {
        ResponseEntity<JsonNode> r = http.exchange(
                "/api/v1/audit-events?limit=100&entity_id=" + entityId,
                HttpMethod.GET,
                new HttpEntity<>(null, api.headers(AUDITOR, "*", Map.of())),
                JsonNode.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r;
    }

    static void collectKeys(JsonNode node, List<String> keys) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                keys.add(e.getKey());
                collectKeys(e.getValue(), keys);
            }
        } else if (node.isArray()) {
            node.forEach(n -> collectKeys(n, keys));
        }
    }

    @Test
    void anAuditReaderWithoutProfitReadSeesNoCostLossOrGain() {
        UUID product = api.product("VIS-1", 4_000, 7_000);
        api.stockUp(t.headOffice(), product, "10");
        UUID sale = RetailTestSupport.id(api.sell(t.headOffice(), "cash", product, "1"));
        UUID usage = RetailTestSupport.id(api.postKeyed(
                "/usage",
                Map.of(
                        "branch_id",
                        t.headOffice(),
                        "kind",
                        "damaged",
                        "reason",
                        "Test breakage",
                        "lines",
                        List.of(Map.of("product_id", product, "qty", "1"))),
                ADMIN,
                "*",
                UUID.randomUUID().toString()));
        Map<String, Object> shortCount = Map.of(
                "branch_id", t.headOffice(), "lines", List.of(Map.of("product_id", product, "counted_qty", "5")));
        UUID stocktake = RetailTestSupport.id(api.post("/stocktakes", shortCount, ADMIN));
        assertThat(api.post("/stocktakes/" + stocktake + "/commit", Map.of(), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.editPrices(product, Map.of("cost_minor", 4_100, "reason", "Test supplier rise"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", 4_200);
        line.put("qty_by_branch", List.of(Map.of("branch_id", t.headOffice(), "qty", "3")));
        UUID purchase = RetailTestSupport.id(api.postKeyed(
                "/purchases",
                Map.of("purchased_on", "2026-01-15", "payment_method", "cash", "lines", List.of(line)),
                ADMIN,
                "*",
                UUID.randomUUID().toString()));

        List<String> actions = new ArrayList<>();
        for (UUID entity : List.of(product, sale, usage, stocktake, purchase)) {
            for (JsonNode event : auditEvents(entity).getBody().get("items")) {
                actions.add(event.get("action").asString());
                List<String> keys = new ArrayList<>();
                collectKeys(event, keys);
                assertThat(keys)
                        .as("%s", event)
                        .noneMatch(k -> k.contains("cost") || k.contains("loss") || k.contains("gain"));
                if (event.get("action").asString().equals("retail.purchase.created")) {
                    assertThat(keys).as("a purchase total is at cost").doesNotContain("total_minor");
                }
            }
        }
        assertThat(actions)
                .contains(
                        "retail.product.created",
                        "retail.product.price_changed",
                        "retail.sale.created",
                        "retail.usage.reported",
                        "retail.stocktake.committed",
                        "retail.purchase.created");

        ResponseEntity<String> csv = http.exchange(
                "/api/v1/audit-events/export",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), api.headers(AUDITOR, "*", Map.of())),
                String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getBody()).contains("retail.stocktake.committed").doesNotContain("cost", "loss_", "gain_");
    }

    /** #77 item 3: the same body gets the same answer whatever the cost is. */
    @Test
    void aPriceEditorWithoutProfitReadCannotProbeTheCost() {
        UUID cheap = api.product("PRB-1", 400, 900);
        UUID dear = api.product("PRB-2", 500, 900);
        Map<String, Object> guess = Map.of("cost_minor", 400, "reason", "Test guess");
        ResponseEntity<JsonNode> a = api.editPrices(cheap, guess, PRICER);
        ResponseEntity<JsonNode> b = api.editPrices(dear, guess, PRICER);
        assertThat(a.getStatusCode()).as("%s", a.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(b.getStatusCode()).as("%s", b.getBody()).isEqualTo(HttpStatus.OK);
        for (JsonNode p : List.of(a.getBody(), b.getBody())) {
            assertThat(p.has("cost_minor")).isFalse();
            assertThat(p.get("sell_minor").asLong()).isEqualTo(900);
            assertThat(p.get("version").asInt()).isEqualTo(2);
        }

        Map<String, Object> sameSell = Map.of("sell_minor", 900, "reason", "Test same");
        for (UUID p : List.of(cheap, dear)) {
            ResponseEntity<JsonNode> r = api.editPrices(p, sameSell, PRICER);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(r.getBody().get("code").asString()).isEqualTo("price_unchanged");
        }

        ResponseEntity<JsonNode> admin =
                api.editPrices(cheap, Map.of("cost_minor", 400, "sell_minor", 900, "reason", "Test same"), ADMIN);
        assertThat(admin.getBody().get("code").asString())
                .as("a caller who reads cost still gets price_unchanged")
                .isEqualTo("price_unchanged");
    }

    /** #77 item 2: two editors read version 1; the second save is refused, not lost. */
    @Test
    void twoPriceEditorsDoNotOverwriteEachOther() {
        UUID product = api.product("ETG-1", 1_000, 1_500);
        ResponseEntity<JsonNode> read = api.get("/products/" + product, ADMIN);
        String etag = read.getHeaders().getETag();
        assertThat(etag).isEqualTo("\"1\"");

        ResponseEntity<JsonNode> first = api.call(
                HttpMethod.POST,
                "/products/" + product + "/prices",
                Map.of("sell_minor", 1_600, "reason", "Test editor one"),
                ADMIN,
                "*",
                Map.of("If-Match", etag));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getETag()).isEqualTo("\"2\"");

        ResponseEntity<JsonNode> second = api.call(
                HttpMethod.POST,
                "/products/" + product + "/prices",
                Map.of("sell_minor", 1_700, "reason", "Test editor two"),
                ADMIN,
                "*",
                Map.of("If-Match", etag));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code").asString()).isEqualTo("version_conflict");

        ResponseEntity<JsonNode> missing = api.call(
                HttpMethod.POST,
                "/products/" + product + "/prices",
                Map.of("sell_minor", 1_700, "reason", "Test no header"),
                ADMIN,
                "*",
                Map.of());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

        assertThat(api.get("/products/" + product, ADMIN)
                        .getBody()
                        .get("sell_minor")
                        .asLong())
                .isEqualTo(1_600);
        assertThat(api.get("/products/" + product + "/price-history", ADMIN)
                        .getBody()
                        .get("items"))
                .hasSize(2);
    }
}
