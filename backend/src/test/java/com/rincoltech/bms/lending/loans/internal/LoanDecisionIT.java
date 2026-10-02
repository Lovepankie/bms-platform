package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The decision on an appraised loan and the expiry of approvals (#43; FR-ORG-06 to FR-ORG-08,
 * FR-APR-03). Three users throughout: the officer submits, a second officer appraises, a branch
 * manager decides. All figures fabricated.
 */
class LoanDecisionIT extends LoanFixtures {

    @Autowired
    TenantJobs jobs;

    @Autowired
    DecisionService decisions;

    @Autowired
    @Qualifier("loanApprovalExpiry")
    RecurringTask<Void> task;

    final UUID appraiser = UUID.randomUUID();
    final UUID manager = UUID.randomUUID();

    /** Submitted by the officer and appraised by the appraiser: status appraised, version 3. */
    String appraised(String member, String product, Integer term) {
        String loan = apply(member, product, 500_000, term).getBody().get("id").asString();
        asOfficer(HttpMethod.POST, LOANS + "/" + loan + "/submit", "\"1\"", null);
        appraise(loan);
        return loan;
    }

    void appraise(String loan) {
        ResponseEntity<JsonNode> a = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/appraisals",
                as(appraiser, officerPerms, "*", "\"2\""),
                Map.of("declared_monthly_income_minor", 2_400_000));
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    ResponseEntity<JsonNode> decide(String loan, UUID user, String ifMatch, Map<String, Object> body) {
        return send(HttpMethod.POST, LOANS + "/" + loan + "/decision", as(user, managerPerms, "*", ifMatch), body);
    }

    static Map<String, Object> approve(Long principal, Integer term) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", "approve");
        body.put("approved_principal_minor", principal);
        body.put("approved_term_count", term);
        return body;
    }

    void setMember(String sql, String id) {
        TestDatabase.owner()
                .sql("UPDATE lending_members SET " + sql + " WHERE id = ?::uuid")
                .param(id)
                .update();
    }

    /**
     * The increment 4 demo: submitted, appraised and approved by three different users, at a lower
     * principal and a shorter term. The preview is the approved 400,000 at 20% flat: 480,000.
     */
    @Test
    void aSecondUserApprovesAtLowerTerms() {
        String product = product("DECIDE", false, false);
        String member = member("Test Borrower 31", "0700000031", t.headOffice(), true);
        String loan = appraised(member, product, 3);

        ResponseEntity<JsonNode> approved = decide(loan, manager, "\"3\"", approve(400_000L, 2));
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode l = approved.getBody();
        assertThat(l.get("status").asString()).isEqualTo("approved");
        assertThat(l.get("approved_by").asString()).isEqualTo(manager.toString());
        assertThat(l.get("approved_at").isNull()).isFalse();
        assertThat(l.get("approved_principal_minor").asLong()).isEqualTo(400_000);
        assertThat(l.get("approved_term_count").asInt()).isEqualTo(2);
        assertThat(l.get("requested_principal_minor").asLong()).isEqualTo(500_000);
        assertThat(l.get("version").asInt()).isEqualTo(4);
        JsonNode schedule = l.get("provisional_schedule");
        assertThat(schedule).hasSize(1);
        assertThat(schedule.get(0).get("due_date").asString()).isEqualTo("2030-05-16");
        assertThat(schedule.get(0).get("total_minor").asLong()).isEqualTo(480_000);
        assertThat(asOfficer(HttpMethod.GET, LOANS + "/" + loan + "/status-history", null, null)
                        .getBody()
                        .get("items")
                        .findValuesAsString("to_status"))
                .containsExactly("draft", "submitted", "appraised", "approved");

        // Decided once: a second decision has nothing to decide.
        assertThat(decide(loan, manager, "\"4\"", approve(null, null))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("invalid_status_transition");
    }

    /** FR-APR-03 with each forbidden combination, and FR-ORG-06: never above what was requested. */
    @Test
    void theApproverIsNeitherSubmitterNorAppraiserAndNeverGoesHigher() {
        String product = product("RULES", false, false);
        String member = member("Test Borrower 32", "0700000032", t.headOffice(), true);
        String loan = appraised(member, product, 2);

        for (UUID forbidden : List.of(officer, appraiser)) {
            ResponseEntity<JsonNode> self = decide(loan, forbidden, "\"3\"", approve(null, null));
            assertThat(self.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(self.getBody().get("code").asString()).isEqualTo("self_approval_forbidden");
        }
        assertThat(decide(loan, manager, "\"3\"", approve(500_001L, null))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("above_requested_principal");
        assertThat(decide(loan, manager, "\"3\"", approve(null, 3))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("above_requested_term");
        assertThat(decide(loan, manager, "\"3\"", approve(50_000L, null))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("below_product_minimum");

        // Without lending.loans.approve the route is closed; a stale version is a conflict.
        assertThat(asOfficer(HttpMethod.POST, LOANS + "/" + loan + "/decision", "\"3\"", approve(null, null))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(decide(loan, manager, "\"2\"", approve(null, null))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("version_conflict");

        // Defaults to the requested terms.
        JsonNode l = decide(loan, manager, "\"3\"", approve(null, null)).getBody();
        assertThat(l.get("approved_principal_minor").asLong()).isEqualTo(500_000);
        assertThat(l.get("approved_term_count").asInt()).isEqualTo(2);
    }

    /** FR-ORG-07: each failing check returns its code and blocks the approval. */
    @Test
    void eachFailingCheckBlocksTheApproval() {
        // Minimum cover 2.5 times the principal: 1,000,000 pledged covers 400,000, not 500,000.
        String product = product("CHECKS", false, true, 25_000);
        String borrower = member("Test Borrower 33", "0700000033", t.headOffice(), true);
        String item = collateralItem(borrower, "TEST-PLEDGE-33");
        String loan =
                apply(borrower, product, 500_000, null).getBody().get("id").asString();
        asOfficer(
                HttpMethod.PUT,
                LOANS + "/" + loan + "/collateral",
                "\"1\"",
                Map.of("collateral", List.of(Map.of("collateral_id", item, "pledged_value_minor", 1_000_000))));
        asOfficer(HttpMethod.POST, LOANS + "/" + loan + "/submit", "\"2\"", null);
        send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/appraisals",
                as(appraiser, officerPerms, "*", "\"3\""),
                Map.of("declared_monthly_income_minor", 2_400_000));

        setMember("is_blacklisted = true, blacklist_reason = 'Test: fixture'", borrower);
        assertThat(code(decide(loan, manager, "\"4\"", approve(400_000L, null))))
                .isEqualTo("member_blacklisted");
        setMember("is_blacklisted = false, blacklist_reason = NULL, kyc_status = 'pending_verification'", borrower);
        assertThat(code(decide(loan, manager, "\"4\"", approve(400_000L, null))))
                .isEqualTo("kyc_not_verified");
        setMember("kyc_status = 'verified'", borrower);

        ResponseEntity<JsonNode> thin = decide(loan, manager, "\"4\"", approve(null, null));
        assertThat(thin.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(thin)).isEqualTo("collateral_below_product_minimum");

        String other = product("OTHER", false, false);
        String active =
                apply(borrower, other, 500_000, null).getBody().get("id").asString();
        TestDatabase.owner()
                .sql("UPDATE lending_loans SET status = 'active' WHERE id = ?::uuid")
                .param(active)
                .update();
        UUID settings = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?, '{\"max_active_loans_per_member\": 1}')")
                .params(settings, t.tenantId())
                .update();
        assertThat(code(decide(loan, manager, "\"4\"", approve(400_000L, null))))
                .isEqualTo("max_active_loans_reached");
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = '{\"max_active_loans_per_member\": 2}' WHERE id = ?")
                .param(settings)
                .update();

        JsonNode l = decide(loan, manager, "\"4\"", approve(400_000L, null)).getBody();
        assertThat(l.get("status").asString()).isEqualTo("approved");
        assertThat(l.get("version").asInt()).isEqualTo(5);
    }

    /** FR-ORG-06: a rejection needs a reason; the submitter's manager may be anyone with the permission. */
    @Test
    void aRejectionNeedsAReason() {
        String product = product("REJECT", false, false);
        String member = member("Test Borrower 34", "0700000034", t.headOffice(), true);
        String loan = appraised(member, product, null);

        ResponseEntity<JsonNode> bare = decide(loan, manager, "\"3\"", Map.of("decision", "reject"));
        assertThat(bare.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(bare.getBody().get("errors").findValuesAsString("field")).containsExactly("note");

        JsonNode l = decide(loan, manager, "\"3\"", Map.of("decision", "reject", "note", "Test: income not shown"))
                .getBody();
        assertThat(l.get("status").asString()).isEqualTo("rejected");
        assertThat(l.get("rejected_reason").asString()).isEqualTo("Test: income not shown");
        assertThat(l.get("approved_by").isNull()).isTrue();
        assertThat(l.get("provisional_schedule")).isEmpty();
    }

    /** FR-ORG-08: an approval older than approval_validity_days (default 14) is cancelled by the nightly task. */
    @Test
    void anApprovalNobodyDisbursedExpires() {
        String product = product("EXPIRE", false, false);
        String member = member("Test Borrower 35", "0700000035", t.headOffice(), true);
        String stale = appraised(member, product, null);
        String fresh = appraised(member, product, null);
        decide(stale, manager, "\"3\"", approve(null, null));
        decide(fresh, manager, "\"3\"", approve(null, null));
        TestDatabase.owner()
                .sql("UPDATE lending_loans SET approved_at = now() - interval '15 days' WHERE id = ?::uuid")
                .param(stale)
                .update();
        TestDatabase.owner()
                .sql("UPDATE lending_loans SET approved_at = now() - interval '13 days' WHERE id = ?::uuid")
                .param(fresh)
                .update();

        jobs.forEachActiveTenantWithModule(LoanJobs.APPROVAL_EXPIRY, "lending", tenantId -> decisions.expireOverdue());

        assertThat(task.getName()).isEqualTo(LoanJobs.APPROVAL_EXPIRY);
        JsonNode expired =
                asOfficer(HttpMethod.GET, LOANS + "/" + stale, null, null).getBody();
        assertThat(expired.get("status").asString()).isEqualTo("cancelled");
        assertThat(expired.get("cancelled_reason").asString()).isEqualTo("approval_expired");
        assertThat(asOfficer(HttpMethod.GET, LOANS + "/" + fresh, null, null)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("approved");
        assertThat(TestDatabase.owner()
                        .sql("SELECT actor_kind FROM audit_log WHERE entity_id = ?::uuid"
                                + " AND action = 'lending.loan.approval_expired'")
                        .param(stale)
                        .query(String.class)
                        .single())
                .isEqualTo("system");
        JsonNode last = asOfficer(HttpMethod.GET, LOANS + "/" + stale + "/status-history", null, null)
                .getBody()
                .get("items")
                .get(4);
        assertThat(last.get("to_status").asString()).isEqualTo("cancelled");
        assertThat(last.get("reason").asString()).isEqualTo("approval_expired");
        assertThat(last.get("changed_by").isNull()).isTrue();
    }

    static String code(ResponseEntity<JsonNode> response) {
        return response.getBody().get("code").asString();
    }
}
