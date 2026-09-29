package com.rincoltech.bms.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Audit search, export and security events (FR-AUD-03, FR-AUD-04, NFR-ISO-04). */
class AuditSearchIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    Session admin;
    Session auditor;
    Session manager;

    Session staff(String prefix, Role... roles) {
        String email = Api.email(prefix);
        return api.signIn(Api.staff(t, email, roles), email, Api.PASSWORD, null);
    }

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("audit", true);
        api = Api.tenant(http, t.slug());
        admin = staff("admin", new Role("tenant_admin", null));
        auditor = staff("auditor", new Role("auditor", null));
        manager = staff("manager", new Role("branch_manager", t.headOffice()));
        api.post("/api/v1/branches", Map.of("code", "AUD1", "name", "Test Branch Audit"), admin.accessToken());
        TestDatabase.owner()
                .sql(
                        "INSERT INTO audit_log (id, tenant_id, actor_kind, branch_id, action, entity_type) VALUES (?, ?, 'system', ?, 'test.event.at_head', 'test')")
                .params(UUID.randomUUID(), t.tenantId(), t.headOffice())
                .update();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO audit_log (id, tenant_id, actor_kind, branch_id, action, entity_type) VALUES (?, ?, 'system', ?, 'test.event.at_two', 'test')")
                .params(UUID.randomUUID(), t.tenantId(), t.secondBranch())
                .update();
    }

    List<String> actions(Session who, String query) {
        ResponseEntity<JsonNode> r = api.get("/api/v1/audit-events" + query, who.accessToken());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return StreamSupport.stream(r.getBody().get("items").spliterator(), false)
                .map(i -> i.get("action").asString())
                .toList();
    }

    /** FR-AUD-04: filters, and a branch-scoped user sees only their branches' events. */
    @Test
    void searchFiltersAndRespectsBranchScope() {
        assertThat(actions(auditor, "?limit=200"))
                .contains("test.event.at_head", "test.event.at_two", "core.branch.created", "core.auth.signed_in");
        assertThat(actions(auditor, "?action=core.branch.created")).containsExactly("core.branch.created");
        assertThat(actions(auditor, "?entity_type=test")).hasSize(2);
        assertThat(actions(auditor, "?actor_user_id=" + admin.userId() + "&action=core.auth.signed_in"))
                .hasSize(1);
        assertThat(actions(auditor, "?from=2999-01-01T00:00:00Z")).isEmpty();
        assertThat(actions(manager, "?limit=200")).containsExactly("test.event.at_head");

        ResponseEntity<JsonNode> page = api.get("/api/v1/audit-events?limit=1", auditor.accessToken());
        String cursor = page.getBody().get("next_cursor").asString();
        ResponseEntity<JsonNode> next = api.get("/api/v1/audit-events?limit=1&cursor=" + cursor, auditor.accessToken());
        assertThat(next.getBody().get("items").get(0).get("id").asString())
                .isNotEqualTo(page.getBody().get("items").get(0).get("id").asString());
    }

    /** FR-AUD-04: export to CSV, itself audited; the export permission is required. */
    @Test
    void exportIsCsvAndAudited() {
        ResponseEntity<String> csv =
                api.postForText("/api/v1/audit-events/export", Map.of("entity_type", "test"), auditor.accessToken());
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(csv.getBody().lines().toList())
                .hasSize(3)
                .first()
                .asString()
                .startsWith("id,created_at,actor_user_id");
        assertThat(actions(auditor, "?action=core.audit.exported")).hasSize(1);
        assertThat(api.postForText("/api/v1/audit-events/export", Map.of(), manager.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** FR-AUD-03: a permission denial on a money-moving route is audited. */
    @Test
    void aDenialOnAMoneyMovingRouteIsAudited() {
        Session officer = staff("officer", new Role("loan_officer", t.headOffice()));
        ResponseEntity<JsonNode> denied = api.post(
                "/api/v1/test-actions",
                Map.of("subject_id", UUID.randomUUID(), "branch_id", t.headOffice(), "amount_minor", 1000),
                officer.accessToken());
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String data = TestDatabase.owner()
                .sql(
                        "SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'core.permission.denied' AND actor_user_id = ?")
                .params(t.tenantId(), officer.userId())
                .query(String.class)
                .single();
        assertThat(data).contains("lending.disbursements.request");

        // A denial on a route that moves no money is refused without an audit row.
        assertThat(api.get("/api/v1/users", officer.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.permission.denied'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }
}
