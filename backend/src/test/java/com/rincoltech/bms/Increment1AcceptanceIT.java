package com.rincoltech.bms;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import com.rincoltech.bms.testsupport.TestApprovalAction;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The increment 1 demo (docs/specs/lending-mvp-scope.md, issue #7): a platform operator creates a
 * tenant; its first admin accepts the invitation and enrols MFA; the admin invites a branch
 * manager and a cashier, who accept; the cashier requests a test action and the branch manager,
 * as checker, approves it. Everything goes through the HTTP API with fabricated people.
 */
class Increment1AcceptanceIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    TestApprovalAction action;

    static String token(JsonNode invitation) {
        String url = invitation.get("url").asString();
        return url.substring(url.indexOf("#token=") + 7);
    }

    Session accept(Api api, UUID userId, String email, JsonNode invitation) {
        ResponseEntity<JsonNode> accepted = api.post(
                "/api/v1/auth/staff/invitations/accept",
                Map.of("token", token(invitation), "password", Api.PASSWORD),
                null);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        return api.signIn(userId, email, Api.PASSWORD, null);
    }

    @Test
    void aTenantAdminInvitesAManagerAndACashierAndACheckerApprovesATestAction() {
        // 1. The platform operator signs in (password and mandatory TOTP) and creates the tenant.
        String operatorEmail = Api.email("operator");
        UUID operator = Api.platformUser(operatorEmail);
        Api platform = Api.platform(http);
        Session op = platform.signIn(operator, operatorEmail, Api.PASSWORD, null);
        String slug = "demo-" + UUID.randomUUID().toString().substring(0, 8);
        String adminEmail = Api.email("owner");
        ResponseEntity<JsonNode> created = platform.post(
                "/api/v1/platform/tenants",
                Map.of(
                        "name",
                        "Demo Lender (fabricated)",
                        "slug",
                        slug,
                        "plan_code",
                        "starter",
                        "modules",
                        List.of("lending"),
                        "head_office",
                        Map.of("code", "HQ", "name", "Head Office"),
                        "admin",
                        Map.of("full_name", "Test Owner 01", "email", adminEmail)),
                op.accessToken());
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID headOffice =
                UUID.fromString(created.getBody().get("head_office_branch_id").asString());
        JsonNode adminInvitation = created.getBody().get("admin_invitation");
        UUID adminId = UUID.fromString(adminInvitation.get("user_id").asString());

        // 2. The tenant admin accepts, is forced to enrol TOTP, and signs in.
        Api tenant = Api.tenant(http, slug);
        Session admin = accept(tenant, adminId, adminEmail, adminInvitation);
        assertThat(admin.recoveryCodes()).hasSize(10);

        // 3. The admin invites a branch manager and a cashier at the head office.
        String managerEmail = Api.email("manager");
        String cashierEmail = Api.email("cashier");
        ResponseEntity<JsonNode> manager = tenant.post(
                "/api/v1/users",
                Map.of(
                        "full_name",
                        "Test Manager 01",
                        "email",
                        managerEmail,
                        "roles",
                        List.of(Map.of("role_key", "branch_manager", "branch_id", headOffice.toString()))),
                admin.accessToken());
        ResponseEntity<JsonNode> cashier = tenant.post(
                "/api/v1/users",
                Map.of(
                        "full_name",
                        "Test Cashier 01",
                        "email",
                        cashierEmail,
                        "roles",
                        List.of(Map.of("role_key", "cashier", "branch_id", headOffice.toString()))),
                admin.accessToken());
        assertThat(manager.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cashier.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 4. Both accept their one-time links and sign in.
        Session managerSession = accept(
                tenant,
                UUID.fromString(manager.getBody().get("user").get("id").asString()),
                managerEmail,
                manager.getBody().get("invitation"));
        Session cashierSession = accept(
                tenant,
                UUID.fromString(cashier.getBody().get("user").get("id").asString()),
                cashierEmail,
                cashier.getBody().get("invitation"));

        // 5. The cashier (maker) requests the test action; nothing happens yet.
        UUID subject = UUID.randomUUID();
        ResponseEntity<JsonNode> requested = tenant.post(
                "/api/v1/test-actions",
                Map.of("subject_id", subject, "branch_id", headOffice, "amount_minor", 250_000, "note", "demo"),
                cashierSession.accessToken());
        assertThat(requested.getStatusCode()).as("%s", requested.getBody()).isEqualTo(HttpStatus.OK);
        String approvalId = requested.getBody().get("approval_id").asString();
        assertThat(action.executionsOf(subject)).isZero();

        // 6. The branch manager (checker) sees it in the queue and approves it; it executes once.
        JsonNode queue = tenant.get("/api/v1/approvals?status=pending", managerSession.accessToken())
                .getBody()
                .get("items");
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).get("id").asString()).isEqualTo(approvalId);
        assertThat(queue.get(0).get("requested_by_name").asString()).isEqualTo("Test Cashier 01");
        ResponseEntity<JsonNode> approved = tenant.post(
                "/api/v1/approvals/" + approvalId + "/approve", Map.of("note", "demo"), managerSession.accessToken());
        assertThat(approved.getStatusCode()).as("%s", approved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().get("status").asString()).isEqualTo("approved");
        assertThat(action.executionsOf(subject)).isEqualTo(1);
    }
}
