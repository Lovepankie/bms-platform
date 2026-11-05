package com.rincoltech.bms.retail.purchasing;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * A supplier's details, its page and its deliveries (issue #820, ADR-125, FR-RET-90). Every name, number and address is
 * fabricated.
 */
class RetailSupplierDetailsIT extends IntegrationTest {

    /** Reads and adds suppliers and restocks, without editing suppliers: the bank account stays hidden. */
    static final String BUYER = "retail.purchase.create,retail.stock.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-supdet", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("SUP-1", 1_000, 1_500);
    }

    static Map<String, Object> details() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("email", "orders@supplier-test.example");
        d.put("address", "Plot 1, Test Street");
        d.put("country", "Uganda");
        d.put("website", "supplier-test.example");
        d.put("payment_terms", "30 days");
        d.put("bank_account", "0000111122");
        d.put("supplier_type", "Wholesaler");
        d.put("notes", "Delivers on Mondays.");
        return d;
    }

    UUID supplier(String name, String contact, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (contact != null) {
            body.put("contact", contact);
        }
        if (details != null) {
            body.put("details", details);
        }
        ResponseEntity<JsonNode> created = api.post("/suppliers", body, ADMIN);
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(created);
    }

    UUID restock(UUID supplier, String on, UUID branch) {
        Map<String, Object> body = RetailPurchasingIT.purchase(
                "cash", supplier, List.of(RetailPurchasingIT.line(product, 1_000L, null, Map.of(branch, "2"))));
        body.put("purchased_on", on);
        ResponseEntity<JsonNode> bought =
                api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(bought.getStatusCode()).as("%s", bought.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(bought);
    }

    ResponseEntity<JsonNode> patch(UUID id, Map<String, Object> body) {
        String etag = api.get("/suppliers/" + id, ADMIN).getHeaders().getETag();
        return api.call(HttpMethod.PATCH, "/suppliers/" + id, body, ADMIN, "*", Map.of("If-Match", etag));
    }

    /** FR-RET-90: a supplier is added with every detail, and the page reads them back with the date added. */
    @Test
    void aSupplierIsAddedWithItsDetailsAndReadBack() {
        UUID id = supplier("Test Supplier 01", "+256700000101", details());

        JsonNode s = api.get("/suppliers/" + id, ADMIN).getBody();
        JsonNode d = s.get("details");
        assertThat(d.get("email").asString()).isEqualTo("orders@supplier-test.example");
        assertThat(d.get("address").asString()).isEqualTo("Plot 1, Test Street");
        assertThat(d.get("country").asString()).isEqualTo("Uganda");
        assertThat(d.get("website").asString()).isEqualTo("supplier-test.example");
        assertThat(d.get("payment_terms").asString()).isEqualTo("30 days");
        assertThat(d.get("bank_account").asString()).isEqualTo("0000111122");
        assertThat(d.get("supplier_type").asString()).isEqualTo("Wholesaler");
        assertThat(d.get("notes").asString()).isEqualTo("Delivers on Mondays.");
        assertThat(s.get("created_at").asString()).isNotBlank();
        assertThat(s.has("last_delivery_on") && !s.get("last_delivery_on").isNull())
                .as("no delivery yet")
                .isFalse();
    }

    /** FR-RET-90: a supplier added before the details keeps working: a name and a contact only, every detail empty. */
    @Test
    void aSupplierWithoutDetailsStillWorks() {
        UUID id = supplier("Test Supplier 02", "+256700000102", null);
        JsonNode s = api.get("/suppliers/" + id, ADMIN).getBody();
        assertThat(s.get("name").asString()).isEqualTo("Test Supplier 02");
        assertThat(s.get("details").size()).isZero();
        restock(id, "2026-01-15", t.headOffice());
        assertThat(api.get("/suppliers/" + id, ADMIN)
                        .getBody()
                        .get("last_delivery_on")
                        .asString())
                .isEqualTo("2026-01-15");
    }

    /** FR-RET-90: an edit changes only what it sends, an empty detail clears it, and the audit names fields only. */
    @Test
    void anEditChangesOnlyWhatItSends() {
        UUID id = supplier("Test Supplier 03", null, details());
        ResponseEntity<JsonNode> edited = patch(
                id, Map.of("details", Map.of("payment_terms", "Cash on delivery", "notes", "", "country", "Kenya")));
        assertThat(edited.getStatusCode()).as("%s", edited.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode d = edited.getBody().get("details");
        assertThat(d.get("payment_terms").asString()).isEqualTo("Cash on delivery");
        assertThat(d.get("country").asString()).isEqualTo("Kenya");
        assertThat(d.has("notes")).as("an empty detail clears it").isFalse();
        assertThat(d.get("email").asString()).as("unchanged").isEqualTo("orders@supplier-test.example");
        assertThat(edited.getBody().get("version").asInt()).isEqualTo(2);

        String audit = TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE entity_id = ? AND action = 'retail.supplier.updated'")
                .param(id)
                .query(String.class)
                .single();
        assertThat(audit).contains("payment_terms", "notes", "country").doesNotContain("Cash on delivery", "Kenya");

        ResponseEntity<JsonNode> nothing = patch(id, Map.of("details", Map.of("country", "Kenya")));
        assertThat(nothing.getBody().get("version").asInt())
                .as("nothing changed")
                .isEqualTo(2);
    }

    /** FR-RET-90: the email must look like one, the website like a web address, and every field has a length. */
    @Test
    void badEmailWebsiteAndLengthsAreRefusedInWords() {
        ResponseEntity<JsonNode> email = api.post(
                "/suppliers", Map.of("name", "Test Supplier 04", "details", Map.of("email", "not an email")), ADMIN);
        assertThat(email.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(email.getBody().get("errors").get(0).get("field").asString()).isEqualTo("details.email");

        ResponseEntity<JsonNode> site = api.post(
                "/suppliers", Map.of("name", "Test Supplier 04", "details", Map.of("website", "not a site")), ADMIN);
        assertThat(site.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(site.getBody().get("errors").get(0).get("field").asString()).isEqualTo("details.website");

        ResponseEntity<JsonNode> script = api.post(
                "/suppliers",
                Map.of("name", "Test Supplier 04", "details", Map.of("website", "javascript:alert(1)")),
                ADMIN);
        assertThat(script.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> longNotes = api.post(
                "/suppliers", Map.of("name", "Test Supplier 04", "details", Map.of("notes", "x".repeat(1001))), ADMIN);
        assertThat(longNotes.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> longType = api.post(
                "/suppliers",
                Map.of("name", "Test Supplier 04", "details", Map.of("supplier_type", "x".repeat(61))),
                ADMIN);
        assertThat(longType.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> ok = api.post(
                "/suppliers",
                Map.of(
                        "name",
                        "Test Supplier 04",
                        "details",
                        Map.of("email", "a@b.example", "website", "https://www.supplier-test.example/shop")),
                ADMIN);
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.CREATED);

        UUID id = RetailTestSupport.id(ok);
        ResponseEntity<JsonNode> badEdit = patch(id, Map.of("details", Map.of("email", "a@b")));
        assertThat(badEdit.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /**
     * FR-RET-90: the last delivery date is the newest restock that is not voided, never typed; a later restock entered
     * first and a voided one do not mislead it.
     */
    @Test
    void theLastDeliveryDateIsDerivedFromTheRestocks() {
        UUID id = supplier("Test Supplier 05", null, null);
        restock(id, "2026-02-10", t.headOffice());
        UUID newest = restock(id, "2026-03-01", t.headOffice());
        restock(id, "2026-02-20", t.headOffice());
        assertThat(api.get("/suppliers/" + id, ADMIN)
                        .getBody()
                        .get("last_delivery_on")
                        .asString())
                .isEqualTo("2026-03-01");

        ResponseEntity<JsonNode> voided = api.postKeyed(
                "/purchases/" + newest + "/void",
                Map.of("reason", "Entered twice in a test"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(api.get("/suppliers/" + id, ADMIN)
                        .getBody()
                        .get("last_delivery_on")
                        .asString())
                .isEqualTo("2026-02-20");

        JsonNode listed = byName(api.get("/suppliers", ADMIN).getBody(), "Test Supplier 05");
        assertThat(listed.get("last_delivery_on").asString()).isEqualTo("2026-02-20");

        ResponseEntity<JsonNode> typed = patch(id, Map.of("last_delivery_on", "2026-12-01"));
        assertThat(api.get("/suppliers/" + id, ADMIN)
                        .getBody()
                        .get("last_delivery_on")
                        .asString())
                .as("never typed: %s", typed.getStatusCode())
                .isEqualTo("2026-02-20");
    }

    /** FR-RET-90: the deliveries list is newest day first, paged, each naming its restock, a voided one marked. */
    @Test
    void theDeliveriesAreListedNewestFirstAndPaged() {
        UUID id = supplier("Test Supplier 06", null, null);
        UUID other = supplier("Test Supplier 07", null, null);
        UUID first = restock(id, "2026-04-01", t.headOffice());
        UUID third = restock(id, "2026-04-03", t.secondBranch());
        UUID second = restock(id, "2026-04-02", t.headOffice());
        restock(other, "2026-04-05", t.headOffice());

        JsonNode page1 =
                api.get("/suppliers/" + id + "/deliveries?limit=2", ADMIN).getBody();
        assertThat(ids(page1)).containsExactly(third.toString(), second.toString());
        assertThat(page1.get("items").get(0).get("purchased_on").asString()).isEqualTo("2026-04-03");
        assertThat(page1.get("items").get(0).get("purchase_no").asString()).isNotBlank();
        assertThat(page1.get("items").get(0).get("lines").asInt()).isEqualTo(1);
        assertThat(page1.get("items").get(0).get("voided").asBoolean()).isFalse();
        assertThat(page1.get("items").get(0).has("total_minor"))
                .as("no amount on the list")
                .isFalse();
        String cursor = page1.get("next_cursor").asString();
        assertThat(cursor).isNotBlank();

        JsonNode page2 = api.get("/suppliers/" + id + "/deliveries?limit=2&cursor=" + cursor, ADMIN)
                .getBody();
        assertThat(ids(page2)).containsExactly(first.toString());
        assertThat(page2.get("next_cursor").isNull()).isTrue();

        assertThat(api.get("/suppliers/" + id + "/deliveries?cursor=nonsense", ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /**
     * FR-RET-90: a person who may buy at one branch sees that branch's deliveries only, and a last delivery date from
     * them alone; another business's supplier is 404.
     */
    @Test
    void theDeliveriesFollowTheBranchScopeAndTheBusiness() {
        UUID id = supplier("Test Supplier 08", null, null);
        UUID atHead = restock(id, "2026-05-01", t.headOffice());
        restock(id, "2026-05-09", t.secondBranch());

        String head = t.headOffice().toString();
        ResponseEntity<JsonNode> scoped =
                api.call(HttpMethod.GET, "/suppliers/" + id + "/deliveries", null, BUYER, head, Map.of());
        assertThat(scoped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(scoped.getBody())).containsExactly(atHead.toString());
        JsonNode page = api.call(HttpMethod.GET, "/suppliers/" + id, null, BUYER, head, Map.of())
                .getBody();
        assertThat(page.get("last_delivery_on").asString()).isEqualTo("2026-05-01");
        assertThat(api.get("/suppliers/" + id, ADMIN)
                        .getBody()
                        .get("last_delivery_on")
                        .asString())
                .isEqualTo("2026-05-09");

        TestDatabase.Fixture otherBusiness = TestDatabase.tenant("retail-supdet-x", false, true);
        RetailTestSupport stranger = new RetailTestSupport(http, otherBusiness);
        assertThat(stranger.get("/suppliers/" + id, ADMIN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stranger.get("/suppliers/" + id + "/deliveries", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stranger.get("/suppliers?q=Test Supplier 08", ADMIN)
                        .getBody()
                        .get("items"))
                .isEmpty();
        assertThat(api.get("/suppliers/" + UUID.randomUUID() + "/deliveries", ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** FR-RET-90: the list is found by name, by phone written several ways, and by type. */
    @Test
    void theListIsSearchedByNamePhoneAndType() {
        supplier("Test Hardware Wholesale", "+256700000201", Map.of("supplier_type", "Wholesaler"));
        supplier("Test Paint Depot", "0700000202", Map.of("supplier_type", "Manufacturer"));
        supplier("Test Cable House", null, Map.of("supplier_type", "wholesaler"));

        assertThat(names(api.get("/suppliers?q=paint", ADMIN).getBody())).containsExactly("Test Paint Depot");
        assertThat(names(api.get("/suppliers?q=0700000201", ADMIN).getBody()))
                .containsExactly("Test Hardware Wholesale");
        assertThat(names(api.get("/suppliers?q=256700000202", ADMIN).getBody())).containsExactly("Test Paint Depot");
        assertThat(names(api.get("/suppliers?q=wholesaler", ADMIN).getBody()))
                .containsExactlyInAnyOrder("Test Hardware Wholesale", "Test Cable House");
        assertThat(names(api.get("/suppliers?q=paint depot", ADMIN).getBody())).containsExactly("Test Paint Depot");
        assertThat(names(api.get("/suppliers?q=50%", ADMIN).getBody())).isEmpty();
        assertThat(names(api.get("/suppliers", ADMIN).getBody())).hasSize(3);

        JsonNode types = api.get("/suppliers/types", ADMIN).getBody().get("items");
        assertThat(types).hasSize(2);
        assertThat(types.get(0).asString()).isEqualToIgnoringCase("wholesaler");
        assertThat(types.get(1).asString()).isEqualTo("Manufacturer");
    }

    /**
     * FR-RET-90: the bank account number is shown only to those who may edit suppliers, on the page, the list and the
     * answer to an add; a person who may not edit cannot save one either.
     */
    @Test
    void theBankAccountIsHiddenFromAReadOnlyPerson() {
        UUID id = supplier("Test Supplier 09", null, details());

        JsonNode page = api.get("/suppliers/" + id, BUYER).getBody();
        assertThat(page.get("details").get("payment_terms").asString()).isEqualTo("30 days");
        assertThat(page.get("details").has("bank_account")).isFalse();
        assertThat(byName(api.get("/suppliers", BUYER).getBody(), "Test Supplier 09")
                        .get("details")
                        .has("bank_account"))
                .isFalse();
        assertThat(api.get("/suppliers/" + id, ADMIN)
                        .getBody()
                        .get("details")
                        .get("bank_account")
                        .asString())
                .isEqualTo("0000111122");

        ResponseEntity<JsonNode> added =
                api.post("/suppliers", Map.of("name", "Test Supplier 10", "details", Map.of("notes", "Fine.")), BUYER);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> withBank = api.post(
                "/suppliers", Map.of("name", "Test Supplier 11", "details", Map.of("bank_account", "1234")), BUYER);
        assertThat(withBank.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(withBank.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("not_allowed");

        String etag = api.get("/suppliers/" + id, ADMIN).getHeaders().getETag();
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/suppliers/" + id,
                                Map.of("details", Map.of("notes", "Changed")),
                                BUYER,
                                "*",
                                Map.of("If-Match", etag))
                        .getStatusCode())
                .as("editing stays retail.catalogue.manage")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Issue #849 (ADR-055): two buyers at one branch, one without {@code retail.profit.read}; that one sees only their
     * own restocks among the supplier's deliveries and dates the last delivery from them alone, on the page and in the
     * list.
     * JUSTIFICATION-A3: #849 item 1 asks for this test; it reuses this class's supplier and page helpers.
     */
    @Test
    void aBuyerWithoutProfitSeesOnlyTheirOwnDeliveries() {
        UUID id = supplier("Test Supplier 12", null, null);
        String head = t.headOffice().toString();
        String buyer = BUYER + ",retail.entry.backdate";
        String profitBuyer = buyer + ",retail.profit.read";
        RetailTestSupport mine = new RetailTestSupport(http, t);
        RetailTestSupport colleague = new RetailTestSupport(http, t);
        LocalDate today = LocalDate.now(BusinessClock.DEFAULT_ZONE);
        String older = today.minusDays(4).toString();
        String newer = today.minusDays(1).toString();
        UUID own = restockAs(mine, buyer, id, older);
        UUID theirs = restockAs(colleague, profitBuyer, id, newer);
        // Two people: each restock is recorded as made by the one who sent it.
        assertThat(mine.userId()).isNotEqualTo(colleague.userId());
        String madeBy = "SELECT created_by FROM retail_purchases WHERE tenant_id = ? AND id = ?";
        assertThat(TestDatabase.owner()
                        .sql(madeBy)
                        .params(t.tenantId(), own)
                        .query(UUID.class)
                        .single())
                .isEqualTo(mine.userId());
        assertThat(TestDatabase.owner()
                        .sql(madeBy)
                        .params(t.tenantId(), theirs)
                        .query(UUID.class)
                        .single())
                .isEqualTo(colleague.userId());

        JsonNode seen = mine.call(HttpMethod.GET, "/suppliers/" + id + "/deliveries", null, buyer, head, Map.of())
                .getBody();
        assertThat(ids(seen)).containsExactly(own.toString());
        JsonNode page = mine.call(HttpMethod.GET, "/suppliers/" + id, null, buyer, head, Map.of())
                .getBody();
        assertThat(page.get("last_delivery_on").asString()).isEqualTo(older);
        JsonNode listed = byName(
                mine.call(HttpMethod.GET, "/suppliers", null, buyer, head, Map.of())
                        .getBody(),
                "Test Supplier 12");
        assertThat(listed.get("last_delivery_on").asString()).isEqualTo(older);

        // The colleague who may see profit sees both, the newest first.
        JsonNode all = colleague
                .call(HttpMethod.GET, "/suppliers/" + id + "/deliveries", null, profitBuyer, head, Map.of())
                .getBody();
        assertThat(ids(all)).containsExactly(theirs.toString(), own.toString());
        assertThat(colleague
                        .call(HttpMethod.GET, "/suppliers/" + id, null, profitBuyer, head, Map.of())
                        .getBody()
                        .get("last_delivery_on")
                        .asString())
                .isEqualTo(newer);
    }

    UUID restockAs(RetailTestSupport who, String permissions, UUID supplier, String on) {
        Map<String, Object> body = RetailPurchasingIT.purchase(
                "cash", supplier, List.of(RetailPurchasingIT.line(product, 1_000L, null, Map.of(t.headOffice(), "2"))));
        body.put("purchased_on", on);
        ResponseEntity<JsonNode> bought = who.postKeyed(
                "/purchases",
                body,
                permissions,
                t.headOffice().toString(),
                UUID.randomUUID().toString());
        assertThat(bought.getStatusCode()).as("%s", bought.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(bought);
    }

    /**
     * Issue #849: no audit row of a supplier ever holds its bank account number, nor a key that could, on the add or on
     * an edit that sets or clears it; the add's row holds the name alone, and an edit only names the fields it changed.
     */
    @Test
    void noSupplierAuditRowHoldsTheBankAccount() {
        UUID id = supplier("Test Supplier 13", "+256700000113", details());
        ResponseEntity<JsonNode> set =
                patch(id, Map.of("details", Map.of("bank_account", "0000333344", "notes", "Changed.")));
        assertThat(set.getStatusCode()).as("%s", set.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> cleared = patch(id, Map.of("details", Map.of("bank_account", "")));
        assertThat(cleared.getStatusCode()).as("%s", cleared.getBody()).isEqualTo(HttpStatus.OK);

        List<String> rows = TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE tenant_id = ? AND entity_id = ?")
                .params(t.tenantId(), id)
                .query(String.class)
                .list();
        assertThat(rows).hasSize(3).noneMatch(r -> r.contains("0000111122") || r.contains("0000333344"));
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND entity_id = ?"
                                + " AND jsonb_path_exists(data, 'strict $.**.bank_account')")
                        .params(t.tenantId(), id)
                        .query(Long.class)
                        .single())
                .as("no key named bank_account at any depth")
                .isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT data -> 'after' ->> 'name' || '|'"
                                + " || (SELECT count(*) FROM jsonb_object_keys(data -> 'after')) FROM audit_log"
                                + " WHERE tenant_id = ? AND entity_id = ? AND action = 'retail.supplier.created'")
                        .params(t.tenantId(), id)
                        .query(String.class)
                        .single())
                .as("the add's row holds the name alone")
                .isEqualTo("Test Supplier 13|1");
    }

    private static JsonNode byName(JsonNode list, String name) {
        for (JsonNode s : list.get("items")) {
            if (s.get("name").asString().equals(name)) {
                return s;
            }
        }
        throw new AssertionError("no supplier " + name);
    }

    private static List<String> names(JsonNode list) {
        List<String> names = new ArrayList<>();
        list.get("items").forEach(s -> names.add(s.get("name").asString()));
        return names;
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(s -> ids.add(s.get("id").asString()));
        return ids;
    }
}
