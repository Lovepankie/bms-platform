package com.rincoltech.bms.retail.catalogue;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.LinkedHashMap;
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
 * A product name is kept once per unit (issue #352, ADR-044): the same name after trimming, folding case and collapsing
 * spaces and punctuation is refused with 422 duplicate_product_name naming the existing item; an administrator may add it
 * anyway with a reason; a close name is a warning only; the CSV import skips and reports a repeated name. Fabricated data.
 */
class RetailProductNamesIT extends IntegrationTest {

    static final String ADMINISTRATOR = ADMIN + ",core.settings.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID category;
    UUID metres;
    UUID rolls;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-names", false, true);
        api = new RetailTestSupport(http, t);
        category = api.category("Test Wiring");
        metres = api.unit("test-metre");
        rolls = api.unit("test-roll");
    }

    ResponseEntity<JsonNode> create(
            String code, String description, UUID unit, String duplicateReason, String permissions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("description", description);
        body.put("category_id", category);
        body.put("unit_id", unit);
        body.put("cost_minor", 1_000);
        body.put("sell_minor", 1_500);
        if (duplicateReason != null) {
            body.put("duplicate_reason", duplicateReason);
        }
        return api.post("/products", body, permissions);
    }

    ResponseEntity<JsonNode> rename(UUID product, Map<String, Object> change, String permissions) {
        String etag = api.get("/products/" + product, ADMIN).getHeaders().getETag();
        return api.call(HttpMethod.PATCH, "/products/" + product, change, permissions, "*", Map.of("If-Match", etag));
    }

    long productsNamed(String description) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_products WHERE tenant_id = ? AND description = ?")
                .params(t.tenantId(), description)
                .query(Long.class)
                .single();
    }

    @Test
    void theSameNameInTheSameUnitIsRefusedWhateverTheCaseSpacesOrPunctuation() {
        ResponseEntity<JsonNode> first = create("NM-1", "Double Socket, 13A", metres, null, ADMIN);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID firstId = RetailTestSupport.id(first);

        for (String again : new String[] {
            "Double Socket, 13A", "double socket 13a", "  DOUBLE   SOCKET - 13A ", "Double-Socket (13A)"
        }) {
            ResponseEntity<JsonNode> refused = create("NM-2", again, metres, null, ADMIN);
            assertThat(refused.getStatusCode()).as(again).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            JsonNode body = refused.getBody();
            assertThat(body.get("code").asString()).isEqualTo("duplicate_product_name");
            assertThat(body.get("detail").asString())
                    .isEqualTo("This product already exists: Double Socket, 13A (NM-1).");
            assertThat(body.get("existing_id").asString()).isEqualTo(firstId.toString());
            assertThat(body.get("existing_code").asString()).isEqualTo("NM-1");
            assertThat(body.get("may_add_anyway").asBoolean()).isFalse();
        }
        assertThat(productsNamed("double socket 13a")).isZero();
    }

    @Test
    void aPointInANumberIsNotPunctuationToIgnore() {
        assertThat(create("NM-1", "Cable 2.5mm", metres, null, ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(create("NM-2", "Cable 25mm", metres, null, ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(create("NM-3", "Cable 2,5mm", metres, null, ADMIN).getStatusCode())
                .as("a comma is punctuation like a point")
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void theSameNameInAnotherUnitIsAllowed() {
        assertThat(create("NM-1", "Cable 2.5mm", metres, null, ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(create("NM-2", "cable 2.5 mm", rolls, null, ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void renamingOrChangingTheUnitIsCheckedToo() {
        UUID a = RetailTestSupport.id(create("NM-1", "Test Switch One", metres, null, ADMIN));
        UUID b = RetailTestSupport.id(create("NM-2", "Test Switch Two", metres, null, ADMIN));

        ResponseEntity<JsonNode> clash = rename(b, Map.of("description", "test switch  one"), ADMIN);
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(clash.getBody().get("code").asString()).isEqualTo("duplicate_product_name");
        assertThat(clash.getBody().get("existing_id").asString()).isEqualTo(a.toString());

        // Only case and spaces of its own name: it is not its own duplicate.
        assertThat(rename(a, Map.of("description", "TEST SWITCH ONE"), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Another unit is another item; moving item B into item A's unit under A's name is refused.
        UUID c = RetailTestSupport.id(create("NM-3", "Test Switch One", rolls, null, ADMIN));
        ResponseEntity<JsonNode> unitClash = rename(c, Map.of("unit_id", metres), ADMIN);
        assertThat(unitClash.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(unitClash.getBody().get("code").asString()).isEqualTo("duplicate_product_name");
    }

    @Test
    void anEditThatLeavesTheNameAloneIsNotBlockedByAnOlderDuplicate() {
        UUID a = RetailTestSupport.id(create("NM-1", "Test Lamp", metres, null, ADMIN));
        // A legacy duplicate that exists already (written as the owner, as an import would have).
        TestDatabase.owner().sql("""
                        INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,
                                                     sell_minor, currency, active)
                        SELECT gen_random_uuid(), tenant_id, 'NM-OLD', 'test  lamp', category_id, unit_id, 1, 2, currency, true
                          FROM retail_products WHERE id = ?
                        """).param(a).update();
        UUID old = TestDatabase.owner()
                .sql("SELECT id FROM retail_products WHERE tenant_id = ? AND code = 'NM-OLD'")
                .param(t.tenantId())
                .query(UUID.class)
                .single();
        ResponseEntity<JsonNode> off = rename(old, Map.of("active", false), ADMIN);
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(off.getBody().get("active").asBoolean()).isFalse();
    }

    @Test
    void anAdministratorMayAddAnywayWithAReasonAndItIsStoredAndAudited() {
        UUID first = RetailTestSupport.id(create("NM-1", "Test Bulb", metres, null, ADMIN));

        ResponseEntity<JsonNode> refused = create("NM-2", "Test Bulb", metres, null, ADMINISTRATOR);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("may_add_anyway").asBoolean()).isTrue();

        // A manager without the administrator permission is told so, with or without a reason.
        ResponseEntity<JsonNode> manager = create("NM-2", "Test Bulb", metres, "Different brand, same name", ADMIN);
        assertThat(manager.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(manager.getBody().get("code").asString()).isEqualTo("permission_denied");

        ResponseEntity<JsonNode> tooShort = create("NM-2", "Test Bulb", metres, "no", ADMINISTRATOR);
        assertThat(tooShort.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(tooShort.getBody().get("errors").get(0).get("field").asString())
                .isEqualTo("duplicate_reason");

        ResponseEntity<JsonNode> added = create("NM-2", "Test Bulb", metres, " Other brand, same name ", ADMINISTRATOR);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID second = RetailTestSupport.id(added);
        assertThat(second).isNotEqualTo(first);
        assertThat(TestDatabase.owner()
                        .sql("SELECT duplicate_reason FROM retail_products WHERE id = ?")
                        .param(second)
                        .query(String.class)
                        .single())
                .isEqualTo("Other brand, same name");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'retail.product.created'"
                                        + " AND entity_id = ?")
                        .params(t.tenantId(), second)
                        .query(String.class)
                        .single())
                .contains("Other brand, same name");

        // A third one with no duplicate has no reason stored.
        UUID plain = RetailTestSupport.id(create("NM-3", "Test Bulb Large", metres, null, ADMIN));
        assertThat(TestDatabase.owner()
                        .sql("SELECT duplicate_reason FROM retail_products WHERE id = ?")
                        .param(plain)
                        .query(String.class)
                        .optional())
                .isEmpty();
    }

    @Test
    void aCloseNameIsOnlyAWarningAndTheEndpointNeedsTheCataloguePermission() {
        UUID first = RetailTestSupport.id(create("NM-1", "Double Socket 13A White", metres, null, ADMIN));
        create("NM-2", "Quick Brown Fox Brand Hammer", metres, null, ADMIN);

        // Saving a near name is not refused.
        ResponseEntity<JsonNode> near = create("NM-3", "Double Soket 13A White", metres, null, ADMIN);
        assertThat(near.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> asked =
                api.get("/products/similar?description=Double Socket 13A Whit&unit_id=" + metres, ADMIN);
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asked.getBody().get("exact")).isEmpty();
        JsonNode similar = asked.getBody().get("similar");
        assertThat(similar).hasSize(2);
        assertThat(similar.get(0).get("distance").asInt()).isPositive();
        assertThat(similar.toString()).contains(first.toString()).doesNotContain("Hammer");

        ResponseEntity<JsonNode> exact =
                api.get("/products/similar?description=double socket, 13a white&unit_id=" + metres, ADMIN);
        assertThat(exact.getBody().get("exact")).hasSize(1);
        assertThat(exact.getBody().get("exact").get(0).get("code").asString()).isEqualTo("NM-1");

        // The same name in another unit is not an exact match: it is shown as close, with distance 0.
        ResponseEntity<JsonNode> otherUnit =
                api.get("/products/similar?description=double socket 13a white&unit_id=" + rolls, ADMIN);
        assertThat(otherUnit.getBody().get("exact")).isEmpty();
        assertThat(otherUnit.getBody().get("similar").get(0).get("distance").asInt())
                .isZero();

        // An item being edited never matches itself.
        ResponseEntity<JsonNode> self = api.get(
                "/products/similar?description=Double Socket 13A White&unit_id=" + metres + "&exclude_id=" + first,
                ADMIN);
        assertThat(self.getBody().get("exact")).isEmpty();

        assertThat(api.get("/products/similar?description=x", SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aCategoryCorrectionIsAuditedWithTheOldAndTheNewCategory() {
        UUID item = RetailTestSupport.id(create("NM-1", "Test Lamp Holder", metres, null, ADMIN));
        UUID lighting = api.category("Test Lighting");
        ResponseEntity<JsonNode> moved = rename(item, Map.of("category_id", lighting), ADMIN);
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(moved.getBody().get("category").asString()).isEqualTo("Test Lighting");
        String audit = TestDatabase.owner()
                .sql(
                        "SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'retail.product.updated' AND entity_id = ?")
                .params(t.tenantId(), item)
                .query(String.class)
                .single();
        assertThat(audit)
                .contains("Test Wiring")
                .contains("Test Lighting")
                .contains(category.toString())
                .contains(lighting.toString());
    }

    @Test
    void theCsvImportSkipsAndReportsARepeatedName() {
        String header = "code,description,category,unit,sell price";
        create("NM-1", "Test Pipe 20mm", metres, null, ADMIN);
        String csv = header + "\n"
                + "IMP-1,test  pipe 20mm,Test Wiring,test-metre,900\n"
                + "IMP-2,Test Elbow,Test Wiring,test-metre,500\n"
                + "IMP-3,TEST ELBOW.,Test Wiring,test-metre,500\n"
                + "IMP-4,Test Elbow,Test Wiring,test-roll,500\n";
        ResponseEntity<JsonNode> done = api.post("/products/import?dry_run=false", Map.of("csv", csv), ADMINISTRATOR);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rows = done.getBody().get("rows");
        assertThat(rows.get(0).get("outcome").asString()).isEqualTo("skipped");
        assertThat(rows.get(0).get("message").asString()).contains("NM-1");
        assertThat(rows.get(1).get("outcome").asString()).isEqualTo("added");
        assertThat(rows.get(2).get("outcome").asString()).isEqualTo("skipped");
        assertThat(rows.get(2).get("message").asString()).contains("line 3");
        assertThat(rows.get(3).get("outcome").asString())
                .as("another unit is another item")
                .isEqualTo("added");
        assertThat(done.getBody().get("added").asInt()).isEqualTo(2);
        assertThat(done.getBody().get("skipped").asInt()).isEqualTo(2);
    }

    @Test
    void aUnitIsNeverConvertedOnceTheItemHasStock() {
        UUID item = RetailTestSupport.id(create("NM-9", "Test Cable Roll", metres, null, ADMIN));
        // No stock yet: the label can still be put right.
        assertThat(rename(item, Map.of("unit_id", rolls), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        api.importedBalance(t.headOffice(), item, "5");
        ResponseEntity<JsonNode> refused = rename(item, Map.of("unit_id", metres), ADMIN);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("unit_in_use_by_stock");
        assertThat(refused.getBody().get("detail").asString())
                .contains("relabelled, not converted")
                .contains("Add a new item with the right unit");
        assertThat(api.get("/products/" + item, ADMIN).getBody().get("unit_id").asString())
                .isEqualTo(rolls.toString());
        // Other changes to an item with stock still go through.
        assertThat(rename(item, Map.of("active", false), ADMIN).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
