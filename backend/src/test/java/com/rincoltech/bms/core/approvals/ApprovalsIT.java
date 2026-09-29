package com.rincoltech.bms.core.approvals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import com.rincoltech.bms.testsupport.TestApprovalAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The maker-checker mechanism through HTTP (FR-APR-01 to FR-APR-08), with the test action a
 * cashier requests and a branch manager decides. Users and amounts are fabricated.
 */
class ApprovalsIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    TestApprovalAction action;

    TestDatabase.Fixture t;
    Api api;
    Session cashier;
    Session manager;
    Session officer;
    Session managerOfBranchTwo;
    Session tenantAdmin;

    Session user(String prefix, Role... roles) {
        String email = Api.email(prefix);
        UUID id = Api.staff(t, email, roles);
        return api.signIn(id, email, Api.PASSWORD, null);
    }

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("apr", true);
        api = Api.tenant(http, t.slug());
        cashier = user("cashier", new Role("cashier", t.headOffice()));
        manager = user("manager", new Role("branch_manager", t.headOffice()));
        officer = user("officer", new Role("loan_officer", t.headOffice()));
        managerOfBranchTwo = user("manager2", new Role("branch_manager", t.secondBranch()));
        tenantAdmin = user("admin", new Role("tenant_admin", null));
    }

    ResponseEntity<JsonNode> request(Session maker, UUID subject, UUID branch, Long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("subject_id", subject);
        body.put("branch_id", branch);
        body.put("amount_minor", amount);
        body.put("note", "fabricated test action");
        return api.post("/api/v1/test-actions", body, maker.accessToken());
    }

    UUID pending(Session maker, UUID subject) {
        ResponseEntity<JsonNode> r = request(maker, subject, t.headOffice(), 500_000L);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("executed").asBoolean()).isFalse();
        return UUID.fromString(r.getBody().get("approval_id").asString());
    }

    ResponseEntity<JsonNode> approve(Session checker, UUID approval) {
        return api.post("/api/v1/approvals/" + approval + "/approve", Map.of("note", "checked"), checker.accessToken());
    }

    String status(UUID approval) {
        return TestDatabase.owner()
                .sql("SELECT status FROM approval_requests WHERE id = ?")
                .param(approval)
                .query(String.class)
                .single();
    }

    List<String> queue(Session who) {
        JsonNode items = api.get("/api/v1/approvals?status=pending", who.accessToken())
                .getBody()
                .get("items");
        return StreamSupport.stream(items.spliterator(), false)
                .map(i -> i.get("id").asString())
                .toList();
    }

    /** FR-APR-01, FR-APR-06: pending, no effect, then approved and executed exactly once. */
    @Test
    void aCheckerApprovesAndTheActionExecutesOnce() {
        UUID subject = UUID.randomUUID();
        UUID approval = pending(cashier, subject);
        assertThat(action.executionsOf(subject)).isZero();
        assertThat(status(approval)).isEqualTo("pending");

        ResponseEntity<JsonNode> approved = approve(manager, approval);
        assertThat(approved.getStatusCode()).as("%s", approved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().get("status").asString()).isEqualTo("approved");
        assertThat(action.executionsOf(subject)).isEqualTo(1);
        var execution = action.executions.stream()
                .filter(e -> e.subjectId().equals(subject))
                .findFirst()
                .orElseThrow();
        // FR-APR-08: exactly the stored payload, with maker and checker.
        assertThat(execution.payload()).containsEntry("note", "fabricated test action");
        assertThat(execution.makerId()).isEqualTo(cashier.userId());
        assertThat(execution.checkerId()).isEqualTo(manager.userId());

        ResponseEntity<JsonNode> twice = approve(tenantAdmin, approval);
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(twice.getBody().get("code").asString()).isEqualTo("invalid_status_transition");
        assertThat(action.executionsOf(subject)).isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'core.approval.approved'")
                        .param(approval)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /** FR-APR-02: the maker cannot approve, and the database refuses it too. */
    @Test
    void theMakerCannotApproveTheirOwnRequest() {
        UUID approval = pending(manager, UUID.randomUUID());
        ResponseEntity<JsonNode> self = approve(manager, approval);
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(self.getBody().get("code").asString()).isEqualTo("self_approval_forbidden");
        assertThat(status(approval)).isEqualTo("pending");

        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE approval_requests SET status = 'approved', decided_by = requested_by WHERE id = ?")
                        .param(approval)
                        .update())
                .hasStackTraceContaining("approval_checker_is_not_maker");
    }

    /** FR-APR-03 mechanism: a user the action names as conflicted cannot decide. */
    @Test
    void aConflictedCheckerIsRefused() {
        UUID subject = UUID.randomUUID();
        action.conflicts.put(subject, Set.of(manager.userId()));
        UUID approval = pending(cashier, subject);
        assertThat(approve(manager, approval).getBody().get("code").asString()).isEqualTo("approver_conflict");
        assertThat(approve(tenantAdmin, approval).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** FR-APR-06: reject needs a note; the note is recorded and nothing executes. */
    @Test
    void rejectionNeedsANoteAndExecutesNothing() {
        UUID subject = UUID.randomUUID();
        UUID approval = pending(cashier, subject);
        ResponseEntity<JsonNode> noNote =
                api.post("/api/v1/approvals/" + approval + "/reject", Map.of(), manager.accessToken());
        assertThat(noNote.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> rejected = api.post(
                "/api/v1/approvals/" + approval + "/reject",
                Map.of("note", "Amount does not match the fabricated voucher"),
                manager.accessToken());
        assertThat(rejected.getBody().get("status").asString()).isEqualTo("rejected");
        assertThat(rejected.getBody().get("decision_note").asString()).contains("fabricated voucher");
        assertThat(action.executionsOf(subject)).isZero();
        assertThat(approve(tenantAdmin, approval).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /** FR-APR-07: expiry after 7 days, and the maker's own cancellation. */
    @Test
    void expiredAndCancelledRequestsCannotBeApproved() {
        UUID expiring = pending(cashier, UUID.randomUUID());
        TestDatabase.owner()
                .sql("UPDATE approval_requests SET expires_at = now() - interval '1 second' WHERE id = ?")
                .param(expiring)
                .update();
        assertThat(api.get("/api/v1/approvals/" + expiring, manager.accessToken())
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("expired");
        ResponseEntity<JsonNode> late = approve(manager, expiring);
        assertThat(late.getBody().get("code").asString()).isEqualTo("approval_expired");
        assertThat(status(expiring)).isEqualTo("expired");

        UUID cancelling = pending(cashier, UUID.randomUUID());
        assertThat(api.post("/api/v1/approvals/" + cancelling + "/cancel", null, manager.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> cancelled =
                api.post("/api/v1/approvals/" + cancelling + "/cancel", null, cashier.accessToken());
        assertThat(cancelled.getBody().get("status").asString()).isEqualTo("cancelled");
        assertThat(approve(manager, cancelling).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /** FR-APR-08: a changed subject marks the request stale and nothing executes. */
    @Test
    void aChangedSubjectMakesTheRequestStale() {
        UUID subject = UUID.randomUUID();
        UUID approval = pending(cashier, subject);
        action.versions.put(subject, 2);
        ResponseEntity<JsonNode> stale = approve(manager, approval);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("subject_changed");
        assertThat(status(approval)).isEqualTo("stale");
        assertThat(action.executionsOf(subject)).isZero();
    }

    /** FR-APR-06: a failed execution leaves the request pending with the error shown. */
    @Test
    void aFailedExecutionLeavesTheRequestPending() {
        UUID subject = UUID.randomUUID();
        UUID approval = pending(cashier, subject);
        action.failing.add(subject);
        ResponseEntity<JsonNode> failed = approve(manager, approval);
        assertThat(failed.getBody().get("code").asString()).isEqualTo("approval_execution_failed");
        JsonNode detail =
                api.get("/api/v1/approvals/" + approval, manager.accessToken()).getBody();
        assertThat(detail.get("status").asString()).isEqualTo("pending");
        assertThat(detail.get("execution_error").asString()).isEqualTo("internal_error");

        action.failing.remove(subject);
        assertThat(approve(manager, approval).getBody().get("status").asString())
                .isEqualTo("approved");
        assertThat(action.executionsOf(subject)).isEqualTo(1);
    }

    /** FR-APR-04: below the threshold the action executes without a checker. */
    @Test
    void belowTheThresholdTheActionExecutesAtOnce() {
        ResponseEntity<JsonNode> settings = api.get("/api/v1/settings", tenantAdmin.accessToken());
        ResponseEntity<JsonNode> set = api.call(
                HttpMethod.PATCH,
                "/api/v1/settings",
                Map.of("approval_thresholds_minor", Map.of(TestApprovalAction.TYPE, 1_000_000)),
                tenantAdmin.accessToken(),
                Map.of("If-Match", settings.getHeaders().getETag()));
        assertThat(set.getStatusCode()).as("%s", set.getBody()).isEqualTo(HttpStatus.OK);

        UUID below = UUID.randomUUID();
        ResponseEntity<JsonNode> r = request(cashier, below, t.headOffice(), 999_999L);
        assertThat(r.getBody().get("executed").asBoolean()).isTrue();
        assertThat(r.getBody().get("approval_id").isNull()).isTrue();
        assertThat(action.executionsOf(below)).isEqualTo(1);

        UUID at = UUID.randomUUID();
        ResponseEntity<JsonNode> pending = request(cashier, at, t.headOffice(), 1_000_000L);
        assertThat(pending.getBody().get("executed").asBoolean()).isFalse();
        assertThat(action.executionsOf(at)).isZero();
    }

    /** One pending request per action and subject. */
    @Test
    void aSecondPendingRequestForTheSameSubjectIsRefused() {
        UUID subject = UUID.randomUUID();
        pending(cashier, subject);
        ResponseEntity<JsonNode> second = request(cashier, subject, t.headOffice(), 500_000L);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code").asString()).isEqualTo("approval_already_pending");
    }

    /**
     * FR-APR-05, NFR-ISO-04, chapter 8 section 8.3.1: the queue shows what each role may decide
     * in its branches, plus what the user made; nothing else.
     */
    @Test
    void theQueueIsScopedByRoleAndBranch() {
        UUID atHead = pending(cashier, UUID.randomUUID());
        String cashierTwoEmail = Api.email("cashier2");
        UUID cashierTwoId = Api.staff(t, cashierTwoEmail, new Role("cashier", t.secondBranch()));
        Session cashierTwo = api.signIn(cashierTwoId, cashierTwoEmail, Api.PASSWORD, null);
        ResponseEntity<JsonNode> r = request(cashierTwo, UUID.randomUUID(), t.secondBranch(), 500_000L);
        UUID atTwo = UUID.fromString(r.getBody().get("approval_id").asString());

        assertThat(queue(manager)).contains(atHead.toString()).doesNotContain(atTwo.toString());
        assertThat(queue(managerOfBranchTwo)).contains(atTwo.toString()).doesNotContain(atHead.toString());
        assertThat(queue(tenantAdmin)).contains(atHead.toString(), atTwo.toString());
        assertThat(queue(cashier)).containsExactly(atHead.toString());
        assertThat(queue(officer)).isEmpty();

        JsonNode item = api.get("/api/v1/approvals?status=pending", manager.accessToken())
                .getBody()
                .get("items")
                .get(0);
        assertThat(item.get("can_decide").asBoolean()).isTrue();
        assertThat(item.get("amount_minor").asLong()).isEqualTo(500_000L);
        assertThat(item.get("requested_by_name").asString()).startsWith("Test Staff cashier");

        // A request outside the checker's branches is indistinguishable from a missing one.
        assertThat(approve(managerOfBranchTwo, atHead).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/api/v1/approvals/" + atHead, officer.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // A maker without the permission in the branch cannot request there.
        assertThat(request(cashier, UUID.randomUUID(), t.secondBranch(), 1L).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
