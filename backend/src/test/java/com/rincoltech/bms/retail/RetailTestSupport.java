package com.rincoltech.bms.retail;

import com.rincoltech.bms.TestDatabase;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Calls the retail API with the development principal headers of chapter 7 section 7.4.3 and
 * arranges a fabricated catalogue. All names and amounts are invented.
 */
public final class RetailTestSupport {

    public static final String BASE = "/api/v1/retail";

    /** The admin column of the chapter 8 matrix for retail. */
    public static final String ADMIN = "retail.catalogue.manage,retail.price.edit,retail.customer.manage,"
            + "retail.sale.create,retail.sale.read,retail.sale.void,retail.stock.read,retail.stocktake.commit,"
            + "retail.purchase.create,retail.usage.report,retail.profit.read";

    /** The sales column of the chapter 8 matrix for retail. */
    public static final String SALES =
            "retail.sale.create,retail.sale.read,retail.stock.read,retail.usage.report,retail.customer.manage";

    private final TestRestTemplate http;
    private final TestDatabase.Fixture tenant;
    private final UUID userId = UUID.randomUUID();

    public RetailTestSupport(TestRestTemplate http, TestDatabase.Fixture tenant) {
        this.http = http;
        this.tenant = tenant;
    }

    public UUID userId() {
        return userId;
    }

    public HttpHeaders headers(String permissions, String branchIds, Map<String, String> extra) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", tenant.slug());
        h.add("X-Dev-User-Id", userId.toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", branchIds);
        extra.forEach(h::add);
        return h;
    }

    public ResponseEntity<JsonNode> call(
            HttpMethod method,
            String path,
            Object body,
            String permissions,
            String branchIds,
            Map<String, String> extra) {
        return http.exchange(
                BASE + path, method, new HttpEntity<>(body, headers(permissions, branchIds, extra)), JsonNode.class);
    }

    public ResponseEntity<JsonNode> get(String path, String permissions) {
        return call(HttpMethod.GET, path, null, permissions, "*", Map.of());
    }

    public ResponseEntity<JsonNode> post(String path, Object body, String permissions) {
        return call(HttpMethod.POST, path, body, permissions, "*", Map.of());
    }

    public UUID category(String name) {
        return id(post("/categories", Map.of("name", name), ADMIN));
    }

    public UUID unit(String name) {
        return id(post("/units", Map.of("name", name), ADMIN));
    }

    /** A product in a fresh category and unit; prices in minor units. */
    public UUID product(String code, long costMinor, long sellMinor) {
        UUID category = category("Test Category " + code);
        UUID unit = unit("u-" + code.toLowerCase());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("description", "Test Product " + code);
        body.put("category_id", category);
        body.put("unit_id", unit);
        body.put("cost_minor", costMinor);
        body.put("sell_minor", sellMinor);
        return id(post("/products", body, ADMIN));
    }

    public static UUID id(ResponseEntity<JsonNode> response) {
        if (response.getBody() == null || !response.getBody().has("id")) {
            throw new AssertionError("no id in " + response.getStatusCode() + " " + response.getBody());
        }
        return UUID.fromString(response.getBody().get("id").asString());
    }
}
