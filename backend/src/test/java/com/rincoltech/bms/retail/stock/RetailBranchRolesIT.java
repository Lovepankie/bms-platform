package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Branch roles and the retail capability settings group (ADR-033, issue #191; docs/specs/
 * trade-capabilities.md sections 1 and 2). Names and amounts are fabricated.
 */
class RetailBranchRolesIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String ROLE_MANAGE = "retail.branch_role.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-roles", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("ROLE-CABLE", 1_000, 1_500);
        api.stockUp(t.headOffice(), product, "20");
        api.stockUp(t.secondBranch(), product, "20");
    }

    ResponseEntity<JsonNode> putCapabilities(Map<String, Object> body, String perms) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        Map<String, String> headers = etag == null ? Map.of() : Map.of("If-Match", etag);
        return api.call(HttpMethod.PUT, "/settings/capabilities", body, perms, "*", headers);
    }

    void switchOn() {
        assertThat(putCapabilities(Map.of("branch_roles", true), SETTINGS).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** Marks the branch a Store or a selling branch through the flag (ADR-048), which is where a store lives since ADR-104. */
    void sells(UUID branch, boolean sells) {
        TestDatabase.owner()
                .sql("UPDATE branches SET sells_goods = ? WHERE id = ?")
                .params(sells, branch)
                .update();
    }

    ResponseEntity<JsonNode> setRole(UUID branch, String role, String perms) {
        return api.call(HttpMethod.PUT, "/branch-roles/" + branch, Map.of("role", role), perms, "*", Map.of());
    }

    ResponseEntity<JsonNode> restock(UUID branch) {
        Map<String, Object> line = Map.of(
                "product_id",
                product,
                "cost_minor",
                1_000,
                "qty_by_branch",
                List.of(Map.of("branch_id", branch, "qty", "2")));
        Map<String, Object> body =
                Map.of("purchased_on", "2026-01-15", "payment_method", "cash", "lines", List.of(line));
        return api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    ResponseEntity<JsonNode> transfer(UUID from, UUID to) {
        Map<String, Object> body = Map.of(
                "from_branch_id", from, "to_branch_id", to, "lines", List.of(Map.of("product_id", product, "qty", 1)));
        return api.postKeyed("/transfers", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    /** Acceptance 2: capability off, every branch sells, and the role routes are 404. */
    @Test
    void withTheCapabilityOffEveryBranchActsAsBoth() {
        ResponseEntity<JsonNode> listed = api.get("/branch-roles", ADMIN);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(listed)).isEqualTo("capability_not_enabled");
        // A role row can exist while the capability is off (set directly); it is ignored.
        TestDatabase.owner()
                .sql("INSERT INTO retail_branch_roles (tenant_id, branch_id, role) VALUES (?, ?, 'shop')")
                .params(t.tenantId(), t.secondBranch())
                .update();
        // The role can no longer say store: a store is the branch flag (ADR-104), so the two never disagree.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("INSERT INTO retail_branch_roles (tenant_id, branch_id, role) VALUES (?, ?, 'store')")
                        .params(t.tenantId(), t.headOffice())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(api.sell(t.secondBranch(), "cash", product, "1").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(api.sell(t.headOffice(), "cash", product, "1").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode group = api.get("/settings/capabilities", SETTINGS).getBody();
        assertThat(group.get("profile").asString()).isEqualTo("shops");
        assertThat(group.get("branch_roles").asBoolean()).isFalse();
    }

    /**
     * Acceptance 1: the matrix of spec 2.1, role by role. A store is the Store flag (ADR-104) and reads as one; restock and
     * transfer are never role limited.
     */
    @Test
    void theRoleMatrixHoldsForStoreShopAndBoth() {
        switchOn();
        for (String role : List.of("store", "shop", "both")) {
            boolean store = role.equals("store");
            sells(t.secondBranch(), !store);
            if (!store) {
                assertThat(setRole(t.secondBranch(), role, ROLE_MANAGE).getStatusCode())
                        .as("set %s", role)
                        .isEqualTo(HttpStatus.OK);
            }
            JsonNode listed = api.get("/branch-roles", ADMIN).getBody().get("items");
            for (JsonNode i : listed) {
                if (i.get("branch_id").asString().equals(t.secondBranch().toString())) {
                    assertThat(i.get("role").asString()).isEqualTo(role);
                    assertThat(i.get("can_sell").asBoolean()).isEqualTo(!store);
                }
            }
            ResponseEntity<JsonNode> sale = api.sell(t.secondBranch(), "cash", product, "1");
            if (store) {
                assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(code(sale)).isEqualTo("branch_does_not_sell");
            } else {
                assertThat(sale.getStatusCode()).as("sale at %s", role).isEqualTo(HttpStatus.CREATED);
            }
            assertThat(restock(t.secondBranch()).getStatusCode())
                    .as("restock at %s", role)
                    .isEqualTo(HttpStatus.CREATED);
            assertThat(transfer(t.headOffice(), t.secondBranch()).getStatusCode())
                    .as("transfer in at %s", role)
                    .isEqualTo(HttpStatus.CREATED);
            assertThat(transfer(t.secondBranch(), t.headOffice()).getStatusCode())
                    .as("transfer out of %s", role)
                    .isEqualTo(HttpStatus.CREATED);
        }
    }

    /** Acceptance 5: stores restock and feed the shop; only the shop sells; history stays valid. */
    @Test
    void aStoreFeedsTheShopAndOnlyTheShopSells() {
        switchOn();
        ResponseEntity<JsonNode> earlier = api.sell(t.headOffice(), "cash", product, "1");
        assertThat(earlier.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID earlierSale = RetailTestSupport.id(earlier);
        sells(t.headOffice(), false);
        assertThat(setRole(t.secondBranch(), "shop", ROLE_MANAGE).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(restock(t.headOffice()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(transfer(t.headOffice(), t.secondBranch()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(api.sell(t.secondBranch(), "cash", product, "1").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(code(api.sell(t.headOffice(), "cash", product, "1"))).isEqualTo("branch_does_not_sell");
        // A sale made while the branch was a seller can still be voided.
        ResponseEntity<JsonNode> voided = api.post(
                "/sales/" + earlierSale + "/void",
                Map.of("reason", "Test void after the branch became a store"),
                ADMIN);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);

        JsonNode items = api.get("/branch-roles", ADMIN).getBody().get("items");
        assertThat(items).hasSize(2);
        for (JsonNode i : items) {
            boolean store = i.get("branch_id").asString().equals(t.headOffice().toString());
            assertThat(i.get("role").asString()).isEqualTo(store ? "store" : "shop");
            assertThat(i.get("can_sell").asBoolean()).isEqualTo(!store);
        }
    }

    /** Acceptance 3 and 4: a role never grants a permission or widens scope; a missing row reads as both. */
    @Test
    void aRoleNeverGrantsAPermissionAndAMissingRowReadsAsBoth() {
        switchOn();
        assertThat(api.get("/branch-roles", ADMIN)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("role")
                        .asString())
                .isEqualTo("both");
        assertThat(setRole(t.secondBranch(), "shop", ROLE_MANAGE).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> body = Map.of(
                "branch_id", t.secondBranch(),
                "payment_method", "cash",
                "lines", List.of(Map.of("product_id", product, "qty", "1")));
        // A shop does not give the permission to sell.
        assertThat(api.postKeyed(
                                "/sales",
                                body,
                                "retail.stock.read",
                                t.secondBranch().toString(),
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // Nor does it widen the scope: a seller scoped to head office cannot sell at the shop.
        ResponseEntity<JsonNode> outside = api.postKeyed(
                "/sales",
                body,
                "retail.sale.create",
                t.headOffice().toString(),
                UUID.randomUUID().toString());
        assertThat(outside.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(outside.getBody().toString()).contains("unknown_branch");
    }

    /**
     * Changing a role needs retail.branch_role.manage, validates the role and is audited. A store is not a role any more
     * (ADR-104): the role store is refused, and so is a role for a branch that is a Store, so the two never disagree.
     */
    @Test
    void changingARoleNeedsThePermissionIsValidatedAndAudited() {
        switchOn();
        assertThat(setRole(t.secondBranch(), "shop", ADMIN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(setRole(t.secondBranch(), "warehouse", ROLE_MANAGE).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> store = setRole(t.secondBranch(), "store", ROLE_MANAGE);
        assertThat(store.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(store)).isEqualTo("validation_failed");
        assertThat(setRole(UUID.randomUUID(), "shop", ROLE_MANAGE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        sells(t.headOffice(), false);
        ResponseEntity<JsonNode> ofAStore = setRole(t.headOffice(), "shop", ROLE_MANAGE);
        assertThat(ofAStore.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(ofAStore)).isEqualTo("branch_is_store");
        assertThat(setRole(t.secondBranch(), "shop", ROLE_MANAGE).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // Setting the same role again writes and audits nothing.
        assertThat(setRole(t.secondBranch(), "shop", ROLE_MANAGE).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.branch_role.changed'")
                        .params(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // A manager scoped to head office cannot reach another branch's role.
        ResponseEntity<JsonNode> scoped = api.call(
                HttpMethod.PUT,
                "/branch-roles/" + t.secondBranch(),
                Map.of("role", "shop"),
                ROLE_MANAGE,
                t.headOffice().toString(),
                Map.of());
        assertThat(scoped.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Spec 1.1 and 1.2, issue #424: absent keys are off, a kind of business writes its preset of the switches it governs, branch
     * roles and returns are left as they are, flags stay toggleable, and every change is audited.
     */
    @Test
    void theKindOfBusinessIsAPresetOfTheFlagsAndEveryChangeIsAudited() {
        assertThat(putCapabilities(Map.of("profile", "wholesale"), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // An unknown key never reaches the group: the request body refuses unknown properties (400).
        // The group's own refusal of unknown keys is covered by RetailCapabilityGroupTest.
        ResponseEntity<JsonNode> unknown = putCapabilities(Map.of("unknown_flag", true), SETTINGS);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(unknown)).isEqualTo("malformed_request");
        // The names from before the kinds are refused on a write.
        ResponseEntity<JsonNode> oldName = putCapabilities(Map.of("profile", "trade"), SETTINGS);
        assertThat(oldName.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(oldName)).isEqualTo("validation_failed");
        assertThat(oldName.getBody().toString()).contains("profile");
        assertThat(putCapabilities(Map.of("profile", "shop"), SETTINGS).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        assertThat(putCapabilities(Map.of("returns", true), SETTINGS).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode wholesale =
                putCapabilities(Map.of("profile", "wholesale"), SETTINGS).getBody();
        assertThat(wholesale.get("profile").asString()).isEqualTo("wholesale");
        for (String on : List.of("units", "price_lists", "customer_accounts", "documents", "purchasing", "returns")) {
            assertThat(wholesale.get(on).asBoolean()).as(on).isTrue();
        }
        assertThat(wholesale.get("transfer_receipt").asBoolean()).isFalse();
        assertThat(wholesale.get("branch_roles").asBoolean()).isFalse();

        JsonNode stores = putCapabilities(Map.of("profile", "stores_and_shops", "price_lists", true), SETTINGS)
                .getBody();
        assertThat(stores.get("profile").asString()).isEqualTo("stores_and_shops");
        assertThat(stores.get("transfer_receipt").asBoolean()).isTrue();
        assertThat(stores.get("price_lists").asBoolean())
                .as("a flag sent with the kind wins")
                .isTrue();
        assertThat(stores.get("documents").asBoolean()).isFalse();
        assertThat(stores.get("returns").asBoolean())
                .as("returns belong to no kind")
                .isTrue();

        JsonNode tweaked = putCapabilities(Map.of("returns", false, "documents", true), SETTINGS)
                .getBody();
        assertThat(tweaked.get("profile").asString()).isEqualTo("stores_and_shops");
        assertThat(tweaked.get("returns").asBoolean()).isFalse();
        assertThat(tweaked.get("documents").asBoolean()).isTrue();

        JsonNode shops = putCapabilities(Map.of("profile", "shops"), SETTINGS).getBody();
        assertThat(shops.get("profile").asString()).isEqualTo("shops");
        for (String off : List.of("price_lists", "customer_accounts", "documents", "purchasing", "transfer_receipt")) {
            assertThat(shops.get(off).asBoolean()).as(off).isFalse();
        }
        assertThat(shops.get("units").asBoolean())
                .as("a kind only ever turns packs on (issue #630)")
                .isTrue();
        List<String> kinds = TestDatabase.owner()
                .sql(
                        "SELECT coalesce(data #>> '{before,profile}', '') || '>' || (data #>> '{after,profile}') FROM audit_log"
                                + " WHERE tenant_id = ? AND action = 'retail.capabilities.changed' ORDER BY created_at, id")
                .params(t.tenantId())
                .query(String.class)
                .list();
        assertThat(kinds).hasSize(5);
        assertThat(kinds).contains("shops>wholesale", "wholesale>stores_and_shops", "stores_and_shops>shops");
    }

    /** A write without the current settings version is refused like PATCH /settings. */
    @Test
    void theCapabilitiesWriteNeedsTheCurrentSettingsVersion() {
        ResponseEntity<JsonNode> none =
                api.call(HttpMethod.PUT, "/settings/capabilities", Map.of("units", true), SETTINGS, "*", Map.of());
        assertThat(none.getStatusCode().is4xxClientError()).isTrue();
        ResponseEntity<JsonNode> stale = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("units", true),
                SETTINGS,
                "*",
                Map.of("If-Match", "999"));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(api.get("/settings/capabilities", SETTINGS)
                        .getBody()
                        .get("units")
                        .asBoolean())
                .isFalse();
    }

    /** A general settings PATCH rewrites the settings row and must keep the module groups in it. */
    @Test
    void aGeneralSettingsPatchKeepsTheCapabilities() {
        switchOn();
        Api session = Api.tenant(http, t.slug());
        String email = Api.email("patchadmin");
        UUID admin = Api.staff(t, email, new Role("tenant_admin", null));
        Session signedIn = session.signIn(admin, email, Api.PASSWORD, null);
        String etag = session.get("/api/v1/settings", signedIn.accessToken())
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> patched = session.call(
                HttpMethod.PATCH,
                "/api/v1/settings",
                Map.of("receipt_footer", "Test footer"),
                signedIn.accessToken(),
                Map.of("If-Match", etag));
        assertThat(patched.getStatusCode()).as("%s", patched.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(api.get("/settings/capabilities", SETTINGS)
                        .getBody()
                        .get("branch_roles")
                        .asBoolean())
                .isTrue();
        assertThat(api.get("/branch-roles", ADMIN).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Spec 1.2 (issue #185): /me lists the enabled modules and, beside them, the enabled capabilities. */
    @Test
    void meListsTheEnabledModulesAndCapabilities() {
        Api session = Api.tenant(http, t.slug());
        String email = Api.email("rolesadmin");
        UUID admin = Api.staff(t, email, new Role("tenant_admin", null));
        Session signedIn = session.signIn(admin, email, Api.PASSWORD, null);

        JsonNode before = session.get("/api/v1/me", signedIn.accessToken()).getBody();
        assertThat(before.get("modules")).hasSize(1);
        assertThat(before.get("modules").get(0).asString()).isEqualTo("retail");
        assertThat(before.get("module_capabilities").get("retail")).isEmpty();
        assertThat(before.get("permissions").toString()).contains(ROLE_MANAGE);

        switchOn();
        JsonNode after = session.get("/api/v1/me", signedIn.accessToken()).getBody();
        assertThat(after.get("module_capabilities").get("retail")).hasSize(1);
        assertThat(after.get("module_capabilities").get("retail").get(0).asString())
                .isEqualTo("branch_roles");
    }
}
