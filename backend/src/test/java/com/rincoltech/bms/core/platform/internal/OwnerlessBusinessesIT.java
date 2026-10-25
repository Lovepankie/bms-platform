package com.rincoltech.bms.core.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The operator's list of businesses with no Owner (issue #571, ADR-090, FR-IAM-18): a business with no Owner is listed with
 * its active administrators, a business with an Owner is not; Set the Owner needs the operator's own code and a reason, is
 * audited in both logs, and takes the business off the list. Only platform operators see the list. Fabricated data only.
 */
class OwnerlessBusinessesIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    record Operator(Api api, UUID id, Session session) {}

    Operator operator() {
        Api platform = Api.platform(http);
        String email = Api.email("owner-op");
        UUID id = Api.platformUser(email);
        return new Operator(platform, id, platform.signIn(id, email, Api.PASSWORD, null));
    }

    static JsonNode find(JsonNode list, UUID tenantId) {
        for (JsonNode row : list) {
            if (row.get("tenant_id").asString().equals(tenantId.toString())) {
                return row;
            }
        }
        return null;
    }

    ResponseEntity<JsonNode> list(Operator op) {
        return op.api()
                .get("/api/v1/platform/tenants/without-owner", op.session().accessToken());
    }

    /** FR-IAM-18: listed with its administrators, Set the Owner with a code and a reason, then off the list. */
    @Test
    void frIam18_aBusinessWithNoOwnerIsListedAndTheOperatorSetsTheOwner() {
        TestDatabase.Fixture none = TestDatabase.tenant("noowner", false, true);
        String adminEmail = Api.email("noowner-admin");
        UUID admin = Api.staff(none, adminEmail, new Role("tenant_admin", null));
        UUID cashier = Api.staff(none, Api.email("noowner-cashier"), new Role("cashier", none.headOffice()));
        TestDatabase.Fixture owned = TestDatabase.tenant("owned", false, true);
        UUID owner = Api.staff(owned, Api.email("owned-owner"), new Role("tenant_admin", null));
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = true WHERE id = ?")
                .param(owner)
                .update();
        Operator op = operator();

        ResponseEntity<JsonNode> listed = list(op);
        assertThat(listed.getStatusCode()).as("%s", listed.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode row = find(listed.getBody(), none.tenantId());
        assertThat(row).isNotNull();
        assertThat(row.get("slug").asString()).isEqualTo(none.slug());
        assertThat(row.get("admins")).hasSize(1);
        assertThat(row.get("admins").get(0).get("id").asString()).isEqualTo(admin.toString());
        assertThat(row.get("admins").get(0).get("email").asString()).isEqualTo(adminEmail);
        assertThat(find(listed.getBody(), owned.tenantId()))
                .as("a business with an Owner")
                .isNull();

        Session staff = Api.tenant(http, owned.slug())
                .signIn(
                        owner,
                        TestDatabase.owner()
                                .sql("SELECT email FROM users WHERE id = ?")
                                .param(owner)
                                .query(String.class)
                                .single(),
                        Api.PASSWORD,
                        null);
        assertThat(Api.platform(http)
                        .get("/api/v1/platform/tenants/without-owner", staff.accessToken())
                        .getStatusCode())
                .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);

        String path = "/api/v1/platform/tenants/" + none.tenantId() + "/users/";
        ResponseEntity<JsonNode> notAdmin = op.api()
                .post(
                        path + cashier + "/make-owner",
                        Map.of("totp_code", code(op), "reason", "Test: the owner asked us"),
                        op.session().accessToken());
        assertThat(notAdmin.getStatusCode().is4xxClientError()).isTrue();
        ResponseEntity<JsonNode> badCode = op.api()
                .post(
                        path + admin + "/make-owner",
                        Map.of("totp_code", "000000", "reason", "Test: the owner asked us"),
                        op.session().accessToken());
        assertThat(badCode.getStatusCode().is4xxClientError()).isTrue();
        assertThat(find(list(op).getBody(), none.tenantId())).isNotNull();

        ResponseEntity<JsonNode> set = op.api()
                .post(
                        path + admin + "/make-owner",
                        Map.of("totp_code", code(op), "reason", "Test: the owner asked us"),
                        op.session().accessToken());
        assertThat(set.getStatusCode()).as("%s", set.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(TestDatabase.owner()
                        .sql("SELECT is_owner FROM users WHERE id = ?")
                        .param(admin)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM platform_audit_log WHERE action = 'platform.tenant.owner_transferred'"
                                        + " AND tenant_id = ? AND platform_user_id = ?")
                        .params(none.tenantId(), op.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(find(list(op).getBody(), none.tenantId())).as("off the list").isNull();
    }

    String code(Operator op) {
        Api.allowTotpReuse(op.id());
        return Api.totp(op.session().totpSecret(), Instant.now());
    }
}
