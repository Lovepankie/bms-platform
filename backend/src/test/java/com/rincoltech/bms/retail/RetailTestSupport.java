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

    /** A POST with an Idempotency-Key, for the money-moving routes (chapter 7 section 7.8). */
    public ResponseEntity<JsonNode> postKeyed(
            String path, Object body, String permissions, String branchIds, String key) {
        return call(HttpMethod.POST, path, body, permissions, branchIds, Map.of("Idempotency-Key", key));
    }

    /** A sale at a branch of one line, as an admin, with a fresh key. */
    public ResponseEntity<JsonNode> sell(UUID branchId, String method, UUID productId, String qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branchId);
        body.put("payment_method", method);
        if (method.equals("credit")) {
            body.put("buyer_name", "Test Buyer 01");
        }
        body.put("lines", java.util.List.of(Map.of("product_id", productId, "qty", qty)));
        return postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    /** Brings a branch's balance to {@code qty} with a committed stock-take, as an admin. */
    public void stockUp(UUID branchId, UUID productId, String qty) {
        Map<String, Object> body = Map.of(
                "branch_id", branchId, "lines", java.util.List.of(Map.of("product_id", productId, "counted_qty", qty)));
        UUID stocktake = id(post("/stocktakes", body, ADMIN));
        ResponseEntity<JsonNode> committed = post("/stocktakes/" + stocktake + "/commit", Map.of(), ADMIN);
        if (!committed.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("stock-take commit: " + committed.getBody());
        }
    }

    /** Sets {@code retail_allow_negative_stock} through the settings API (FR-RET-03). */
    public void allowNegativeStock(boolean allow) {
        HttpHeaders h = headers("core.settings.read,core.settings.manage", "*", Map.of());
        ResponseEntity<JsonNode> current =
                http.exchange("/api/v1/settings", HttpMethod.GET, new HttpEntity<>(null, h), JsonNode.class);
        h.add(HttpHeaders.IF_MATCH, current.getHeaders().getETag());
        ResponseEntity<JsonNode> patched = http.exchange(
                "/api/v1/settings",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("retail_allow_negative_stock", allow), h),
                JsonNode.class);
        if (!patched.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("settings: " + patched.getBody());
        }
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
