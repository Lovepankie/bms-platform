package com.rincoltech.bms.retail.catalogue;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Catalogue management without an operator (#146): rename and deactivate categories and units, edit
 * suppliers and credit buyers, per endpoint and permission, audited without cost. Fabricated data.
 */
class RetailCatalogueManagementIT extends IntegrationTest {

    /** A user who may read stock and add restocks but not manage the catalogue. */
    static final String READER = "retail.stock.read,retail.purchase.create";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-mgmt", false, true);
        api = new RetailTestSupport(http, t);
    }

    /** A PATCH that sends the row's current version, as the screens do. */
    ResponseEntity<JsonNode> patch(String path, Object body, String permissions) {
        String table = path.startsWith("/categories/")
                ? "retail_categories"
                : path.startsWith("/units/")
                        ? "retail_units"
                        : path.startsWith("/suppliers/") ? "retail_suppliers" : "retail_customers";
        UUID id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
        int version = TestDatabase.owner()
                .sql("SELECT version FROM " + table + " WHERE id = ?")
                .param(id)
                .query(Integer.class)
                .optional()
                .orElse(1);
        return patchIfMatch(path, body, permissions, String.valueOf(version));
    }

    ResponseEntity<JsonNode> patchIfMatch(String path, Object body, String permissions, String ifMatch) {
        return api.call(
                HttpMethod.PATCH,
                path,
                body,
                permissions,
                "*",
                ifMatch == null ? Map.of() : Map.of("If-Match", ifMatch));
    }

    List<String> actions() {
        return TestDatabase.owner()
                .sql("SELECT action FROM audit_log WHERE tenant_id = ? ORDER BY created_at, action")
                .param(t.tenantId())
                .query(String.class)
                .list();
    }

    @Test
    void aCategoryIsRenamedAndDeactivatedNeverDeleted() {
        UUID product = api.product("MGT-1", 1_000, 1_500);
        JsonNode list = api.get("/categories", ADMIN).getBody().get("items");
        UUID category = UUID.fromString(list.get(0).get("id").asString());
        assertThat(list.get(0).get("product_count").asInt()).isEqualTo(1);
        assertThat(list.get(0).get("active").asBoolean()).isTrue();

        assertThat(patch("/categories/" + category, Map.of("name", "Test Renamed"), SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> renamed = patch("/categories/" + category, Map.of("name", " Test Renamed "), ADMIN);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().get("name").asString()).isEqualTo("Test Renamed");
        assertThat(api.get("/products/" + product, ADMIN)
                        .getBody()
                        .get("category")
                        .asString())
                .isEqualTo("Test Renamed");

        UUID other = api.category("Test Other");
        ResponseEntity<JsonNode> clash = patch("/categories/" + other, Map.of("name", "test renamed"), ADMIN);
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(clash.getBody().get("code").asString()).isEqualTo("duplicate_category");

        ResponseEntity<JsonNode> off = patch("/categories/" + category, Map.of("active", false), ADMIN);
        assertThat(off.getBody().get("active").asBoolean()).isFalse();
        assertThat(off.getBody().get("product_count").asInt()).isEqualTo(1);
        // The product keeps its category; a new product cannot choose the switched-off one.
        assertThat(api.get("/products/" + product, ADMIN)
                        .getBody()
                        .get("category_id")
                        .asString())
                .isEqualTo(category.toString());
        UUID unit = api.unit("pcs");
        ResponseEntity<JsonNode> created = api.post(
                "/products",
                Map.of(
                        "code",
                        "MGT-2",
                        "description",
                        "Test item",
                        "category_id",
                        category,
                        "unit_id",
                        unit,
                        "cost_minor",
                        1,
                        "sell_minor",
                        2),
                ADMIN);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(patch("/categories/" + UUID.randomUUID(), Map.of("active", false), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(actions()).contains("retail.category.updated");
    }

    @Test
    void aUnitIsRenamedAndDeactivated() {
        api.product("MGT-3", 1_000, 1_500);
        JsonNode list = api.get("/units", ADMIN).getBody().get("items");
        UUID unit = UUID.fromString(list.get(0).get("id").asString());
        assertThat(list.get(0).get("product_count").asInt()).isEqualTo(1);

        assertThat(patch("/units/" + unit, Map.of("name", "box"), READER).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(patch("/units/" + unit, Map.of("name", "box"), ADMIN)
                        .getBody()
                        .get("name")
                        .asString())
                .isEqualTo("box");
        UUID pcs = api.unit("pcs");
        assertThat(patch("/units/" + pcs, Map.of("name", "BOX"), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(patch("/units/" + unit, Map.of("active", false), ADMIN)
                        .getBody()
                        .get("active")
                        .asBoolean())
                .isFalse();
        assertThat(patch("/units/" + unit, Map.of("name", " "), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(actions()).contains("retail.unit.updated");
    }

    @Test
    void aSupplierIsEditedAndSwitchedOff() {
        UUID supplier = RetailTestSupport.id(
                api.post("/suppliers", Map.of("name", "Test Supplier 01", "contact", "+256700000001"), ADMIN));
        assertThat(patch("/suppliers/" + supplier, Map.of("name", "Test Supplier 02"), READER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> edited =
                patch("/suppliers/" + supplier, Map.of("name", "Test Supplier 02", "contact", ""), ADMIN);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().get("name").asString()).isEqualTo("Test Supplier 02");
        assertThat(edited.getBody().path("contact").isNull()
                        || edited.getBody().path("contact").isMissingNode())
                .isTrue();

        UUID second = RetailTestSupport.id(api.post("/suppliers", Map.of("name", "Test Supplier 03"), ADMIN));
        assertThat(patch("/suppliers/" + second, Map.of("name", "test supplier 02"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        assertThat(patch("/suppliers/" + supplier, Map.of("active", false), ADMIN)
                        .getBody()
                        .get("active")
                        .asBoolean())
                .isFalse();
        UUID product = api.product("MGT-4", 1_000, 1_500);
        ResponseEntity<JsonNode> restock = api.postKeyed(
                "/purchases",
                Map.of(
                        "supplier_id",
                        supplier,
                        "purchased_on",
                        java.time.LocalDate.now(java.time.ZoneId.of("Africa/Kampala"))
                                .toString(),
                        "payment_method",
                        "credit",
                        "lines",
                        List.of(Map.of(
                                "product_id",
                                product,
                                "cost_minor",
                                1_000,
                                "qty_by_branch",
                                List.of(Map.of("branch_id", t.headOffice(), "qty", "1"))))),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(restock.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(actions()).contains("retail.supplier.updated");
        // The audit holds what changed and never the contact itself.
        String audited = TestDatabase.owner()
                .sql("SELECT cast(data AS text) FROM audit_log WHERE action = 'retail.supplier.updated'"
                        + " AND tenant_id = ? ORDER BY created_at LIMIT 1")
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(audited).doesNotContain("+256700000001");
    }

    @Test
    void aCreditBuyerIsEditedAndTheListShowsWhatIsOwed() {
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 01"), ADMIN));
        UUID product = api.product("MGT-5", 1_000, 1_500);
        api.stockUp(t.headOffice(), product, "5");
        ResponseEntity<JsonNode> sale = api.postKeyed(
                "/sales",
                Map.of(
                        "branch_id",
                        t.headOffice(),
                        "payment_method",
                        "credit",
                        "customer_id",
                        buyer,
                        "lines",
                        List.of(Map.of("product_id", product, "qty", "2"))),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode row = api.get("/customers", ADMIN).getBody().get("items").get(0);
        assertThat(row.get("balance_minor").asLong()).isEqualTo(3_000);

        assertThat(patch("/customers/" + buyer, Map.of("contact", "+256700000002"), READER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> edited =
                patch("/customers/" + buyer, Map.of("name", "Test Buyer 01B", "contact", "+256700000002"), ADMIN);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().get("contact").asString()).isEqualTo("+256700000002");
        assertThat(edited.getBody().has("balance_minor")).isFalse();
        assertThat(api.get("/customers?query=01b", ADMIN).getBody().get("items"))
                .hasSize(1);
        assertThat(patch("/customers/" + UUID.randomUUID(), Map.of("name", "X"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(actions()).contains("retail.customer.updated");
    }

    /** A product's code is normalised like the importer's, a duplicate says so, and the audit holds no cost. */
    @Test
    void productCodesAreNormalisedAndAuditHoldsNoCost() {
        api.product("MGT-6", 7_777, 9_999);
        UUID category = api.category("Test Cat Z");
        UUID unit = api.unit("kg");
        Map<String, Object> body = Map.of(
                "code",
                " MGT-6 ",
                "description",
                "Test again",
                "category_id",
                category,
                "unit_id",
                unit,
                "cost_minor",
                1,
                "sell_minor",
                2);
        ResponseEntity<JsonNode> again = api.post("/products", body, ADMIN);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("detail").asString()).contains("code exists");
        String audited = TestDatabase.owner()
                .sql("SELECT string_agg(cast(data AS text),"
                        + " ' ') FROM audit_log WHERE tenant_id = ? AND action LIKE 'retail.product.%'")
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(audited).doesNotContain("7777").doesNotContain("cost");
    }

    @Test
    void aStaleOrMissingIfMatchIsRefusedOnEveryNewPatchEndpoint() {
        UUID category = api.category("Test Stale Category");
        UUID unit = api.unit("test-stale-unit");
        UUID supplier = id(api.post("/suppliers", Map.of("name", "Test Stale Supplier"), ADMIN));
        UUID buyer = id(api.post("/customers", Map.of("name", "Test Stale Buyer"), ADMIN));
        for (String path :
                List.of("/categories/" + category, "/units/" + unit, "/suppliers/" + supplier, "/customers/" + buyer)) {
            Map<String, Object> first = Map.of("name", "Test Renamed " + path.substring(1, 4));
            ResponseEntity<JsonNode> ok = patchIfMatch(path, first, ADMIN, "1");
            assertThat(ok.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(ok.getHeaders().getETag()).as(path).isEqualTo("\"2\"");
            assertThat(ok.getBody().get("version").asInt()).isEqualTo(2);

            ResponseEntity<JsonNode> stale =
                    patchIfMatch(path, Map.of("name", "Test Other " + path.substring(1, 4)), ADMIN, "1");
            assertThat(stale.getStatusCode()).as(path).isEqualTo(HttpStatus.CONFLICT);
            assertThat(stale.getBody().get("code").asString()).isEqualTo("version_conflict");
            assertThat(patchIfMatch(path, first, ADMIN, null).getStatusCode())
                    .as(path)
                    .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
            assertThat(patchIfMatch(path, Map.of("name", "Test Other " + path.substring(1, 4)), ADMIN, "2")
                            .getStatusCode())
                    .as(path)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    private static UUID id(ResponseEntity<JsonNode> r) {
        return RetailTestSupport.id(r);
    }

    @Test
    void creditBuyersPageByCursorAndSearchOnTheServer() {
        for (String name : List.of("Test Buyer C", "Test Buyer A", "Test Other", "Test Buyer B")) {
            api.post("/customers", Map.of("name", name), ADMIN);
        }
        JsonNode first = api.get("/customers?limit=3", ADMIN).getBody();
        assertThat(first.get("items")).hasSize(3);
        assertThat(first.get("items").get(0).get("name").asString()).isEqualTo("Test Buyer A");
        String cursor = first.get("next_cursor").asString();
        JsonNode second = api.get("/customers?limit=3&cursor=" + cursor, ADMIN).getBody();
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("items").get(0).get("name").asString()).isEqualTo("Test Other");
        assertThat(second.get("next_cursor") == null
                        || second.get("next_cursor").isNull())
                .isTrue();
        JsonNode found = api.get("/customers?query=buyer b", ADMIN).getBody();
        assertThat(found.get("items")).hasSize(1);
        assertThat(api.get("/customers?cursor=!!", ADMIN).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
