package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

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
                .sql("INSERT INTO retail_branch_roles (tenant_id, branch_id, role) VALUES (?, ?, 'store')")
                .params(t.tenantId(), t.secondBranch())
                .update();
        assertThat(api.sell(t.secondBranch(), "cash", product, "1").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(api.sell(t.headOffice(), "cash", product, "1").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode group = api.get("/settings/capabilities", SETTINGS).getBody();
        assertThat(group.get("profile").asString()).isEqualTo("shop");
        assertThat(group.get("branch_roles").asBoolean()).isFalse();
    }

    /** Acceptance 1: the matrix of spec 2.1, role by role. Restock and transfer are never role limited. */
    @Test
    void theRoleMatrixHoldsForStoreShopAndBoth() {
        switchOn();
        for (String role : List.of("store", "shop", "both")) {
            assertThat(setRole(t.secondBranch(), role, ROLE_MANAGE).getStatusCode())
                    .as("set %s", role)
                    .isEqualTo(HttpStatus.OK);
            ResponseEntity<JsonNode> sale = api.sell(t.secondBranch(), "cash", product, "1");
            if (role.equals("store")) {
                assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(code(sale)).isEqualTo("branch_role_forbids_sales");
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
        assertThat(setRole(t.headOffice(), "store", ROLE_MANAGE).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(setRole(t.secondBranch(), "shop", ROLE_MANAGE).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(restock(t.headOffice()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(transfer(t.headOffice(), t.secondBranch()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(api.sell(t.secondBranch(), "cash", product, "1").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(code(api.sell(t.headOffice(), "cash", product, "1"))).isEqualTo("branch_role_forbids_sales");
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

    /** Changing a role needs retail.branch_role.manage, validates the role and is audited. */
    @Test
    void changingARoleNeedsThePermissionIsValidatedAndAudited() {
        switchOn();
        assertThat(setRole(t.secondBranch(), "store", ADMIN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(setRole(t.secondBranch(), "warehouse", ROLE_MANAGE).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(setRole(UUID.randomUUID(), "store", ROLE_MANAGE).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(setRole(t.secondBranch(), "store", ROLE_MANAGE).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // Setting the same role again writes and audits nothing.
        assertThat(setRole(t.secondBranch(), "store", ROLE_MANAGE).getStatusCode())
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

    /** Spec 1.1 and 1.2: absent keys are off, a profile writes a preset, flags stay toggleable, audited. */
    @Test
    void theProfileIsAPresetOfTheFlagsAndEveryChangeIsAudited() {
        assertThat(putCapabilities(Map.of("profile", "trade"), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // An unknown key never reaches the group: the request body refuses unknown properties (400).
        // The group's own refusal of unknown keys is covered by RetailCapabilityGroupTest.
        ResponseEntity<JsonNode> unknown = putCapabilities(Map.of("unknown_flag", true), SETTINGS);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(unknown)).isEqualTo("malformed_request");
        assertThat(putCapabilities(Map.of("profile", "wholesale"), SETTINGS).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        JsonNode trade = putCapabilities(Map.of("profile", "trade"), SETTINGS).getBody();
        assertThat(trade.get("profile").asString()).isEqualTo("trade");
        for (String on :
                List.of("branch_roles", "units", "price_lists", "customer_accounts", "returns", "transfer_receipt")) {
            assertThat(trade.get(on).asBoolean()).as(on).isTrue();
        }
        assertThat(trade.get("documents").asBoolean()).isFalse();
        assertThat(trade.get("purchasing").asBoolean()).isFalse();

        JsonNode tweaked = putCapabilities(Map.of("returns", false, "documents", true), SETTINGS)
                .getBody();
        assertThat(tweaked.get("returns").asBoolean()).isFalse();
        assertThat(tweaked.get("documents").asBoolean()).isTrue();
        assertThat(tweaked.get("branch_roles").asBoolean()).isTrue();

        JsonNode shop = putCapabilities(Map.of("profile", "shop"), SETTINGS).getBody();
        assertThat(shop.get("branch_roles").asBoolean()).isFalse();
        assertThat(shop.get("documents").asBoolean()).isFalse();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.capabilities.changed'")
                        .params(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
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
