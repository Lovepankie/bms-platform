package com.rincoltech.bms.retail.catalogue;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Item codes made from the category (issue #493, owner decisions D14 and D16, ADR-081): a category has a code start of 2 to 4
 * letters, unique in the business, suggested from its name and editable; an item added without a typed code gets the
 * category's next free number, PREFIX-0001, also when two people add at once; a typed code is kept; the import fills blank
 * codes from each row's category; moving an item to another category keeps its code. Fabricated data.
 */
class RetailItemCodesIT extends IntegrationTest {

    static final String ADMINISTRATOR = ADMIN + ",core.settings.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID unit;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-codes", false, true);
        api = new RetailTestSupport(http, t);
        unit = api.unit("test-piece");
    }

    JsonNode category(UUID id) {
        for (JsonNode c : api.get("/categories", ADMIN).getBody().get("items")) {
            if (c.get("id").asString().equals(id.toString())) {
                return c;
            }
        }
        throw new AssertionError("no category " + id);
    }

    ResponseEntity<JsonNode> add(UUID category, String code, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (code != null) {
            body.put("code", code);
        }
        body.put("description", description);
        body.put("category_id", category);
        body.put("unit_id", unit);
        body.put("cost_minor", 1_000);
        body.put("sell_minor", 1_500);
        return api.post("/products", body, ADMIN);
    }

    String codeOf(ResponseEntity<JsonNode> created) {
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().get("code").asString();
    }

    /** A PATCH under If-Match with the row's current version. */
    ResponseEntity<JsonNode> patch(String path, Map<String, Object> change) {
        String etag = path.startsWith("/products")
                ? api.get(path, ADMIN).getHeaders().getETag()
                : "\""
                        + category(UUID.fromString(path.substring("/categories/".length())))
                                .get("version")
                                .asInt()
                        + "\"";
        return api.call(HttpMethod.PATCH, path, change, ADMIN, "*", Map.of("If-Match", etag));
    }

    @Test
    void aCategoryGetsACodeStartSuggestedFromItsName() {
        assertThat(category(api.category("Bulbs")).get("code_prefix").asString())
                .isEqualTo("BLB");
        assertThat(category(api.category("TVs")).get("code_prefix").asString()).isEqualTo("TV");
        assertThat(category(api.category("Phone accessories"))
                        .get("code_prefix")
                        .asString())
                .isEqualTo("PHA");
        assertThat(category(api.category("Extensions")).get("code_prefix").asString())
                .isEqualTo("EXT");
        // Blub would also be BLB, which Bulbs holds: the next free one is offered instead.
        assertThat(api.get("/categories/prefix-suggestion?name=Blub", ADMIN)
                        .getBody()
                        .get("code_prefix")
                        .asString())
                .isEqualTo("BLBA");
        assertThat(category(api.category("Blub")).get("code_prefix").asString()).isEqualTo("BLBA");
    }

    @Test
    void theCodeStartIsEditableAndUniqueInTheBusiness() {
        UUID bulbs = api.category("Bulbs");
        ResponseEntity<JsonNode> typed =
                api.post("/categories", Map.of("name", "Test Lamps", "code_prefix", "lmp"), ADMIN);
        assertThat(typed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(typed.getBody().get("code_prefix").asString()).isEqualTo("LMP");

        ResponseEntity<JsonNode> taken =
                api.post("/categories", Map.of("name", "Test Cables", "code_prefix", "BLB"), ADMIN);
        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(taken.getBody().get("code").asString()).isEqualTo("duplicate_category_prefix");

        ResponseEntity<JsonNode> bad = patch("/categories/" + bulbs, Map.of("code_prefix", "B1"));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> clash = patch("/categories/" + bulbs, Map.of("code_prefix", "LMP"));
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(clash.getBody().get("code").asString()).isEqualTo("duplicate_category_prefix");

        ResponseEntity<JsonNode> renamed = patch("/categories/" + bulbs, Map.of("code_prefix", "bul"));
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().get("code_prefix").asString()).isEqualTo("BUL");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.category.updated'"
                                + " AND data::text LIKE '%BUL%'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void anItemWithoutACodeGetsTheCategorysNextNumberAndATypedCodeIsKept() {
        UUID bulbs = api.category("Bulbs");
        assertThat(category(bulbs).get("next_code").asString()).isEqualTo("BLB-0001");
        assertThat(codeOf(add(bulbs, null, "Test bulb 9W"))).isEqualTo("BLB-0001");
        assertThat(codeOf(add(bulbs, "  ", "Test bulb 12W"))).isEqualTo("BLB-0002");
        assertThat(codeOf(add(bulbs, "LED-OWN-1", "Test bulb 15W"))).isEqualTo("LED-OWN-1");
        // A code typed in the category's own shape is kept, and the numbering steps over it.
        assertThat(codeOf(add(bulbs, "blb-0003", "Test bulb 18W"))).isEqualTo("blb-0003");
        assertThat(category(bulbs).get("next_code").asString()).isEqualTo("BLB-0004");
        assertThat(codeOf(add(bulbs, null, "Test bulb 20W"))).isEqualTo("BLB-0004");
        // Uniqueness is unchanged: a typed code another item holds is refused.
        ResponseEntity<JsonNode> twice = add(bulbs, "BLB-0001", "Test bulb 25W");
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(twice.getBody().get("code").asString()).isEqualTo("duplicate_product_code");
    }

    @Test
    void itemsAddedAtOnceInOneCategoryGetDifferentNumbers() throws Exception {
        UUID bulbs = api.category("Bulbs");
        int adds = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(adds);
        try {
            List<Future<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
            for (int i = 0; i < adds; i++) {
                String name = "Test bulb at once " + i;
                calls.add(pool.submit(() -> {
                    go.await();
                    return add(bulbs, null, name);
                }));
            }
            go.countDown();
            Set<String> codes = new HashSet<>();
            for (Future<ResponseEntity<JsonNode>> call : calls) {
                codes.add(codeOf(call.get()));
            }
            assertThat(codes)
                    .containsExactlyInAnyOrder("BLB-0001", "BLB-0002", "BLB-0003", "BLB-0004", "BLB-0005", "BLB-0006");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theImportFillsBlankCodesFromEachRowsCategoryAndKeepsTypedOnes() {
        UUID bulbs = api.category("Bulbs");
        codeOf(add(bulbs, null, "Test bulb 9W"));
        String csv = "code,description,category,unit,sell_price\n"
                + ",Test bulb 12W,Bulbs,test-piece,2000\n"
                + "BLB-0002,Test bulb 15W,Bulbs,test-piece,2500\n"
                + ",Test bulb 18W,bulbs,test-piece,3000\n"
                + ",Test extension 4 way,Extensions,test-piece,9000\n"
                + "OWN-7,Test extension 6 way,Extensions,test-piece,12000\n";
        ResponseEntity<JsonNode> dry = api.post("/products/import?dry_run=true", Map.of("csv", csv), ADMINISTRATOR);
        assertThat(dry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dry.getBody().get("added").asInt()).isEqualTo(5);
        assertThat(dry.getBody().get("rows").get(0).get("code").asString()).isEmpty();
        assertThat(dry.getBody().get("rows").get(0).get("message").asString()).contains("next code of Bulbs");

        ResponseEntity<JsonNode> applied =
                api.post("/products/import?dry_run=false", Map.of("csv", csv), ADMINISTRATOR);
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> reported = new ArrayList<>();
        applied.getBody().get("rows").forEach(r -> reported.add(r.get("code").asString()));
        // The typed BLB-0002 goes in first, so the blank rows of Bulbs step over it.
        assertThat(reported).containsExactly("BLB-0003", "BLB-0002", "BLB-0004", "EXT-0001", "OWN-7");
        assertThat(codesByName())
                .containsEntry("Test bulb 12W", "BLB-0003")
                .containsEntry("Test bulb 15W", "BLB-0002")
                .containsEntry("Test bulb 18W", "BLB-0004")
                .containsEntry("Test extension 4 way", "EXT-0001")
                .containsEntry("Test extension 6 way", "OWN-7");

        // A file without a code column at all is read as every code blank.
        ResponseEntity<JsonNode> noColumn = api.post(
                "/products/import?dry_run=false",
                Map.of("csv", "description,category,unit,sell_price\nTest bulb 30W,Bulbs,test-piece,4000\n"),
                ADMINISTRATOR);
        assertThat(noColumn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(codesByName()).containsEntry("Test bulb 30W", "BLB-0005");
    }

    @Test
    void existingCodesStayWhenTheCodeStartChangesAndMovingAnItemKeepsItsCode() {
        UUID bulbs = api.category("Bulbs");
        UUID lamps = api.category("Lamps");
        UUID typed = RetailTestSupport.id(add(bulbs, "OLD-CODE-1", "Test bulb 9W"));
        UUID made = RetailTestSupport.id(add(bulbs, null, "Test bulb 12W"));

        assertThat(patch("/categories/" + bulbs, Map.of("code_prefix", "BUL")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.get("/products/" + typed, ADMIN).getBody().get("code").asString())
                .isEqualTo("OLD-CODE-1");
        assertThat(api.get("/products/" + made, ADMIN).getBody().get("code").asString())
                .isEqualTo("BLB-0001");
        // The numbering of the category goes on under its new start.
        assertThat(codeOf(add(bulbs, null, "Test bulb 15W"))).isEqualTo("BUL-0002");

        ResponseEntity<JsonNode> moved = patch("/products/" + made, Map.of("category_id", lamps));
        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(moved.getBody().get("code").asString()).isEqualTo("BLB-0001");
        assertThat(moved.getBody().get("category").asString()).isEqualTo("Lamps");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.product.updated'"
                                + " AND entity_id = ? AND data::text LIKE '%Bulbs%' AND data::text LIKE '%Lamps%'")
                        .params(t.tenantId(), made)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // The new category's numbering is not used by the move.
        assertThat(codeOf(add(lamps, null, "Test lamp 40W"))).isEqualTo("LMP-0001");
    }

    @Test
    void applyingABlankCodeFileAgainAddsNothingAndARepeatedRowIsAddedOnce() {
        api.category("Bulbs");
        String csv = "code,description,category,unit,sell_price\n"
                + ",Test bulb 12W,Bulbs,test-piece,2000\n"
                + ",Test bulb 15W,Bulbs,test-piece,2500\n"
                + ",Test bulb 15W,Bulbs,test-piece,2500\n";
        ResponseEntity<JsonNode> first = api.post("/products/import?dry_run=false", Map.of("csv", csv), ADMINISTRATOR);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("added").asInt()).isEqualTo(2);
        assertThat(first.getBody().get("skipped").asInt()).isEqualTo(1);

        // A second press of Add, or the same file again: a row without a code is matched on its name and unit, so it is
        // skipped rather than added under a new number.
        ResponseEntity<JsonNode> again = api.post("/products/import?dry_run=false", Map.of("csv", csv), ADMINISTRATOR);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("added").asInt()).isZero();
        assertThat(again.getBody().get("skipped").asInt()).isEqualTo(3);
        assertThat(codesByName()).hasSize(2).containsValues("BLB-0001", "BLB-0002");
    }

    @Test
    void anOpenAddWithAMadeCodeQueuesTheNextOneButNotATypedAdd() throws Exception {
        UUID bulbs = api.category("Bulbs");
        try (Connection making = TestDatabase.appDataSource().getConnection();
                Connection typing = TestDatabase.appDataSource().getConnection()) {
            for (Connection c : List.of(making, typing)) {
                c.setAutoCommit(false);
                try (PreparedStatement s = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    s.setString(1, t.tenantId().toString());
                    s.execute();
                }
                try (Statement s = c.createStatement()) {
                    s.execute("SET LOCAL lock_timeout = '1s'");
                }
            }
            try (PreparedStatement s = making.prepareStatement("SELECT bms_next_item_code(?)")) {
                s.setObject(1, bulbs);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    assertThat(r.getString(1)).isEqualTo("BLB-0001");
                }
            }
            // The foreign key check of an insert into the category takes FOR KEY SHARE, which FOR NO KEY UPDATE leaves
            // free.
            try (PreparedStatement s = typing.prepareStatement("""
                    INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,
                                                 sell_minor, currency)
                    VALUES (gen_random_uuid(), current_setting('app.tenant_id')::uuid, 'OWN-1', 'Test bulb typed', ?, ?, 100,
                            200, 'UGX')
                    """)) {
                s.setObject(1, bulbs);
                s.setObject(2, unit);
                assertThat(s.executeUpdate()).isEqualTo(1);
            }
            // A second made code in the same category waits for the first add's commit.
            try (PreparedStatement s = typing.prepareStatement("SELECT bms_next_item_code(?)")) {
                s.setObject(1, bulbs);
                assertThatThrownBy(s::executeQuery)
                        .isInstanceOf(SQLException.class)
                        .satisfies(e ->
                                assertThat(((SQLException) e).getSQLState()).isEqualTo("55P03"));
            }
            typing.rollback();
            making.rollback();
        }
    }

    Map<String, String> codesByName() {
        Map<String, String> codes = new LinkedHashMap<>();
        TestDatabase.owner()
                .sql("SELECT description, code FROM retail_products WHERE tenant_id = ?")
                .param(t.tenantId())
                .query((rs, n) -> codes.put(rs.getString("description"), rs.getString("code")))
                .list();
        return codes;
    }
}
