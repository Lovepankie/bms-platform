package com.rincoltech.bms.retail.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The kind of business (issue #424, ADR-092): migration V103 renames the shop and trade profiles without changing a switch, the
 * server reads a row written before the rename the same way, and the operator sees the kind. Names are fabricated.
 */
class RetailBusinessTypeIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";

    @Autowired
    TestRestTemplate http;

    /** The block between the markers of V103, run on its own against fabricated rows of this test's tenants. */
    @Test
    void theMigrationRenamesTheProfilesAndKeepsEverySwitchAndAnythingElse() throws IOException {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V103__retail_business_type.sql"));
        int from = migration.indexOf("-- profile-rename:begin") + "-- profile-rename:begin".length();
        String rename = migration.substring(from, migration.indexOf("-- profile-rename:end"));
        assertThat(rename).contains("UPDATE tenant_settings");

        TestDatabase.Fixture shop = tenant("bt-shop");
        TestDatabase.Fixture stores = tenant("bt-stores");
        TestDatabase.Fixture trade = tenant("bt-trade");
        TestDatabase.Fixture documentsOnly = tenant("bt-docs");
        TestDatabase.Fixture shopWholesale = tenant("bt-shop-wh");
        TestDatabase.Fixture shopStores = tenant("bt-shop-st");
        TestDatabase.Fixture never = tenant("bt-never");
        TestDatabase.Fixture renamed = tenant("bt-renamed");
        arrange(
                shop,
                "{\"display_name\": \"Test Shop\", \"module_groups\": {\"retail\": {\"profile\": \"shop\", \"returns\": true}}}");
        arrange(
                stores,
                "{\"module_groups\": {\"retail\": {\"profile\": \"trade\", \"branch_roles\": true, \"transfer_receipt\": true,"
                        + " \"units\": false}}}");
        arrange(
                trade,
                "{\"module_groups\": {\"retail\": {\"profile\": \"trade\", \"branch_roles\": true, \"price_lists\": true,"
                        + " \"credit_sale_recognition\": \"on_sale\"}, \"sms\": {\"enabled\": false}}}");
        arrange(documentsOnly, "{\"module_groups\": {\"retail\": {\"profile\": \"trade\", \"documents\": true}}}");
        arrange(shopWholesale, "{\"module_groups\": {\"retail\": {\"profile\": \"shop\", \"price_lists\": true}}}");
        arrange(shopStores, "{\"module_groups\": {\"retail\": {\"profile\": \"shop\", \"transfer_receipt\": true}}}");
        arrange(never, "{\"module_groups\": {\"retail\": {\"units\": true}}}");
        arrange(renamed, "{\"module_groups\": {\"retail\": {\"profile\": \"wholesale\", \"units\": true}}}");
        Map<TestDatabase.Fixture, JsonNode> before = Stream.of(
                        shop, stores, trade, documentsOnly, shopWholesale, shopStores, never, renamed)
                .collect(Collectors.toMap(f -> f, this::settingsOf));
        Map<TestDatabase.Fixture, Integer> versions = Stream.of(
                        shop, stores, trade, documentsOnly, shopWholesale, shopStores, never, renamed)
                .collect(Collectors.toMap(f -> f, this::versionOf));

        String own = Stream.of(shop, stores, trade, documentsOnly, shopWholesale, shopStores, never, renamed)
                .map(f -> "'" + f.tenantId() + "'")
                .collect(Collectors.joining(", "));
        String scoped = rename.replace(
                "WHERE ts.settings #>> '{module_groups,retail,profile}' IN ('shop', 'trade');",
                "WHERE ts.settings #>> '{module_groups,retail,profile}' IN ('shop', 'trade') AND ts.tenant_id IN ("
                        + own + ");");
        assertThat(scoped).isNotEqualTo(rename);
        TestDatabase.owner().sql(scoped).update();
        // Run again: the second run changes nothing, the versions included.
        TestDatabase.owner().sql(scoped).update();

        assertThat(profileOf(shop)).isEqualTo("shops");
        assertThat(profileOf(stores)).isEqualTo("stores_and_shops");
        assertThat(profileOf(trade)).isEqualTo("wholesale_and_shops");
        assertThat(profileOf(documentsOnly)).isEqualTo("wholesale_and_shops");
        // A shop is judged from its switches too, so a row an older server wrote back is never mislabelled.
        assertThat(profileOf(shopWholesale)).isEqualTo("wholesale_and_shops");
        assertThat(profileOf(shopStores)).isEqualTo("stores_and_shops");
        assertThat(settingsOf(never).get("module_groups").get("retail").has("profile"))
                .isFalse();
        assertThat(profileOf(renamed)).isEqualTo("wholesale");
        for (TestDatabase.Fixture f : List.of(shop, stores, trade, documentsOnly, shopWholesale, shopStores)) {
            JsonNode was = withoutProfile(before.get(f));
            assertThat(withoutProfile(settingsOf(f)))
                    .as("everything but the profile of %s", f.slug())
                    .isEqualTo(was);
            assertThat(versionOf(f)).isEqualTo(versions.get(f) + 1);
        }
        for (TestDatabase.Fixture f : List.of(never, renamed)) {
            assertThat(settingsOf(f)).isEqualTo(before.get(f));
            assertThat(versionOf(f)).isEqualTo(versions.get(f));
        }
    }

    /** A row the previous release wrote during the deploy reads as the kind the migration would have made, every switch as it is. */
    @Test
    void aTradeRowWrittenByThePreviousReleaseReadsAsItsKindWithEverySwitchAsItWas() {
        TestDatabase.Fixture t = tenant("bt-legacy");
        arrange(
                t,
                "{\"module_groups\": {\"retail\": {\"profile\": \"trade\", \"branch_roles\": true, \"customer_accounts\": true,"
                        + " \"returns\": true}}}");
        RetailTestSupport api = new RetailTestSupport(http, t);

        JsonNode read = api.get("/settings/capabilities", SETTINGS).getBody();

        assertThat(read.get("profile").asString()).isEqualTo("wholesale_and_shops");
        assertThat(read.get("branch_roles").asBoolean()).isTrue();
        assertThat(read.get("customer_accounts").asBoolean()).isTrue();
        assertThat(read.get("returns").asBoolean()).isTrue();
        for (String off : List.of("units", "price_lists", "documents", "purchasing", "transfer_receipt")) {
            assertThat(read.get(off).asBoolean()).as(off).isFalse();
        }
    }

    /** The operator's business list and page show the kind the administrator chose, and nothing for a business that never chose. */
    @Test
    void theOperatorSeesTheKindOfBusiness() {
        TestDatabase.Fixture chosen = tenant("bt-operator");
        TestDatabase.Fixture untouched = tenant("bt-untouched");
        RetailTestSupport api = new RetailTestSupport(http, chosen);
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> saved = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("profile", "wholesale_and_shops"),
                SETTINGS,
                "*",
                Map.of("If-Match", etag));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        // A row the previous release wrote back during a deploy: the operator reads it as its kind, never as a wrong
        // default.
        TestDatabase.Fixture legacy = tenant("bt-op-legacy");
        arrange(legacy, "{\"module_groups\": {\"retail\": {\"profile\": \"shop\", \"units\": true}}}");

        Api platform = Api.platform(http);
        String email = Api.email("operator");
        UUID operatorId = Api.platformUser(email);
        Session operator = platform.signIn(operatorId, email, Api.PASSWORD, null);

        JsonNode one = platform.get("/api/v1/platform/tenants/" + chosen.tenantId(), operator.accessToken())
                .getBody();
        assertThat(one.get("business_type").asString()).isEqualTo("wholesale_and_shops");
        JsonNode page = platform.get(
                        "/api/v1/platform/tenants/" + chosen.tenantId() + "/overview", operator.accessToken())
                .getBody();
        assertThat(page.get("tenant").get("business_type").asString()).isEqualTo("wholesale_and_shops");
        JsonNode list = platform.get("/api/v1/platform/tenants?q=" + chosen.slug(), operator.accessToken())
                .getBody();
        assertThat(list.get("items").get(0).get("business_type").asString()).isEqualTo("wholesale_and_shops");
        // The overview's other columns survive the function V103 made again.
        for (String column : List.of(
                "id",
                "slug",
                "name",
                "status",
                "plan_code",
                "plan_name",
                "currency",
                "timezone",
                "created_at",
                "address")) {
            assertThat(page.get("tenant").path(column).isMissingNode()
                            || page.get("tenant").get(column).isNull())
                    .as(column)
                    .isFalse();
        }
        assertThat(page.get("tenant").get("modules").get(0).asString()).isEqualTo("retail");
        assertThat(page.get("tenant").get("pilot").isBoolean()).isTrue();
        assertThat(platform.get("/api/v1/platform/tenants/" + legacy.tenantId(), operator.accessToken())
                        .getBody()
                        .get("business_type")
                        .asString())
                .isEqualTo("wholesale_and_shops");
        JsonNode none = platform.get("/api/v1/platform/tenants/" + untouched.tenantId(), operator.accessToken())
                .getBody();
        assertThat(none.path("business_type").isMissingNode()
                        || none.get("business_type").isNull())
                .isTrue();

        // Only core.settings.manage changes the kind: a retail administrator without it is refused and nothing changes.
        ResponseEntity<JsonNode> refused = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("profile", "shops"),
                com.rincoltech.bms.retail.RetailTestSupport.ADMIN,
                "*",
                Map.of("If-Match", saved.getHeaders().getETag()));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(platform.get("/api/v1/platform/tenants/" + chosen.tenantId(), operator.accessToken())
                        .getBody()
                        .get("business_type")
                        .asString())
                .isEqualTo("wholesale_and_shops");
    }

    TestDatabase.Fixture tenant(String prefix) {
        return TestDatabase.tenant(prefix, false, true);
    }

    void arrange(TestDatabase.Fixture tenant, String json) {
        TestDatabase.owner()
                .sql(
                        "INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (gen_random_uuid(), ?, CAST(? AS jsonb))"
                                + " ON CONFLICT (tenant_id) DO UPDATE SET settings = excluded.settings")
                .params(tenant.tenantId(), json)
                .update();
    }

    JsonNode settingsOf(TestDatabase.Fixture tenant) {
        String json = TestDatabase.owner()
                .sql("SELECT settings::text FROM tenant_settings WHERE tenant_id = ?")
                .param(tenant.tenantId())
                .query(String.class)
                .single();
        return JsonMapper.builder().build().readTree(json);
    }

    int versionOf(TestDatabase.Fixture tenant) {
        return TestDatabase.owner()
                .sql("SELECT version FROM tenant_settings WHERE tenant_id = ?")
                .param(tenant.tenantId())
                .query(Integer.class)
                .single();
    }

    String profileOf(TestDatabase.Fixture tenant) {
        return settingsOf(tenant)
                .get("module_groups")
                .get("retail")
                .get("profile")
                .asString();
    }

    static JsonNode withoutProfile(JsonNode settings) {
        JsonNode copy = settings.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) copy.get("module_groups").get("retail")).remove("profile");
        return copy;
    }
}
