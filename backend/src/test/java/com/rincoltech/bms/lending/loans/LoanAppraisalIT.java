package com.rincoltech.bms.lending.loans;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Appraisal, credit score and exposure (#42; FR-ORG-04, FR-ORG-05, chapter 3 section 3.18.1). The
 * arithmetic of the score is covered by CreditScoreTest; this covers what the service feeds it and
 * what it stores. All figures fabricated.
 */
class LoanAppraisalIT extends LoanFixtures {

    /** A submitted bullet loan of 500,000 at 20% for one month: one instalment of 600,000. */
    String submitted(String member, String product) {
        String loan = apply(member, product, 500_000, null).getBody().get("id").asString();
        asOfficer(HttpMethod.POST, LOANS + "/" + loan + "/submit", "\"1\"", null);
        return loan;
    }

    ResponseEntity<JsonNode> appraise(String loan, String ifMatch, Map<String, Object> body) {
        return asOfficer(HttpMethod.POST, LOANS + "/" + loan + "/appraisals", ifMatch, body);
    }

    void makeActive(String loan, int daysPastDue, long outstanding) {
        TestDatabase.owner()
                .sql("UPDATE lending_loans SET status = 'active', days_past_due = ?, principal_outstanding_minor = ?"
                        + " WHERE id = ?::uuid")
                .params(daysPastDue, outstanding, loan)
                .update();
    }

    /**
     * History 20 (no prior loans); q = 600,000 / 2,400,000 = 0.25, so affordability 30; no
     * collateral, 0; exposure 10. Score 60, band B. The loan moves to appraised.
     */
    @Test
    void anAppraisalScoresTheLoanAndMovesItToAppraised() {
        String product = product("APPR", false, false);
        String member = member("Test Borrower 21", "0700000021", t.headOffice(), true);
        String loan = submitted(member, product);

        ResponseEntity<JsonNode> created = appraise(
                loan,
                "\"2\"",
                Map.of("declared_monthly_income_minor", 2_400_000, "visit_notes", "Test: stall visited"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode a = created.getBody();
        assertThat(a.get("score").asInt()).isEqualTo(60);
        assertThat(a.get("band").asString()).isEqualTo("B");
        assertThat(a.get("recommendation").asString()).isEqualTo("approve");
        assertThat(a.get("appraised_by").asString()).isEqualTo(officer.toString());
        assertThat(a.get("visit_notes").asString()).isEqualTo("Test: stall visited");
        JsonNode components = a.get("components");
        assertThat(components.get("repayment_history").get("points").asInt()).isEqualTo(20);
        assertThat(components.get("affordability").get("points").asInt()).isEqualTo(30);
        assertThat(components
                        .get("affordability")
                        .get("monthly_instalment_minor")
                        .asLong())
                .isEqualTo(600_000);
        assertThat(components.get("collateral_cover").get("points").asInt()).isZero();
        assertThat(components.get("exposure").get("points").asInt()).isEqualTo(10);
        assertThat(a.get("weights").get("repayment_history").asInt()).isEqualTo(40);
        assertThat(a.get("flags").valueStream().map(JsonNode::asString)).containsExactly("NEW_MEMBER");
        assertThat(a.get("exposure").get("own_loans")).isEmpty();

        JsonNode l = asOfficer(HttpMethod.GET, LOANS + "/" + loan, null, null).getBody();
        assertThat(l.get("status").asString()).isEqualTo("appraised");
        assertThat(l.get("version").asInt()).isEqualTo(3);
        assertThat(TestDatabase.owner()
                        .sql("SELECT appraised_by FROM lending_loans WHERE id = ?::uuid")
                        .param(loan)
                        .query(UUID.class)
                        .single())
                .isEqualTo(officer);
        assertThat(asOfficer(HttpMethod.GET, LOANS + "/" + loan + "/status-history", null, null)
                        .getBody()
                        .get("items")
                        .findValuesAsString("to_status"))
                .containsExactly("draft", "submitted", "appraised");
    }

    /** FR-ORG-05: own loans, loans guaranteed and linked parties' loans all show, and arrears cost points. */
    @Test
    void exposureCoversOwnGuaranteedAndLinkedPartyLoans() {
        String product = product("EXPO", false, false);
        String borrower = member("Test Borrower 22", "0700000022", t.headOffice(), true);
        String kin = member("Test Kin 22", "0700000122", t.headOffice(), true);
        String friend = member("Test Friend 22", "0700000222", t.headOffice(), true);

        String ownOther = submitted(borrower, product);
        makeActive(ownOther, 10, 300_000);
        String kinLoan = submitted(kin, product);
        makeActive(kinLoan, 45, 400_000);
        TestDatabase.owner()
                .sql("INSERT INTO lending_next_of_kin (id, tenant_id, member_id, full_name, relationship,"
                        + " linked_member_id, link_method, link_status)"
                        + " VALUES (?, ?, ?::uuid, 'Test Kin 22', 'sibling', ?::uuid, 'manual', 'confirmed')")
                .params(UUID.randomUUID(), t.tenantId(), borrower, kin)
                .update();
        String friendLoan =
                apply(friend, product, 500_000, null).getBody().get("id").asString();
        asOfficer(
                HttpMethod.PUT,
                LOANS + "/" + friendLoan + "/guarantors",
                "\"1\"",
                Map.of("guarantors", List.of(Map.of("member_id", borrower, "guaranteed_amount_minor", 200_000))));
        asOfficer(HttpMethod.POST, LOANS + "/" + friendLoan + "/submit", "\"2\"", null);

        String loan = submitted(borrower, product);
        JsonNode a = appraise(loan, "\"2\"", Map.of("declared_monthly_income_minor", 2_400_000))
                .getBody();

        assertThat(a.get("flags").valueStream().map(JsonNode::asString))
                .containsExactly(
                        "EXISTING_LOAN_IN_ARREARS", "LINKED_PARTY_IN_ARREARS", "MULTIPLE_ACTIVE_LOANS", "NEW_MEMBER");
        assertThat(a.get("components").get("exposure").get("points").asInt()).isZero();
        assertThat(a.get("score").asInt()).isEqualTo(50);
        assertThat(a.get("band").asString()).isEqualTo("C");
        assertThat(a.get("recommendation").asString()).isEqualTo("review");

        JsonNode own = a.get("exposure").get("own_loans");
        assertThat(own).hasSize(1);
        assertThat(own.get(0).get("loan_id").asString()).isEqualTo(ownOther);
        assertThat(own.get(0).get("outstanding_minor").asLong()).isEqualTo(300_000);
        assertThat(own.get(0).get("days_past_due").asInt()).isEqualTo(10);
        assertThat(a.get("exposure").get("guaranteed_loans").findValuesAsString("loan_id"))
                .containsExactly(friendLoan);
        assertThat(a.get("exposure").get("linked_party_loans").findValuesAsString("loan_id"))
                .containsExactlyInAnyOrder(kinLoan, friendLoan);
    }

    /** FR-ORG-04: an appraisal is a snapshot. A later change to the member changes only the next one. */
    @Test
    void aLaterAppraisalLeavesTheEarlierSnapshotAlone() {
        String product = product("SNAP", false, false);
        String member = member("Test Borrower 23", "0700000023", t.headOffice(), true);
        String loan = submitted(member, product);

        JsonNode first = appraise(loan, "\"2\"", Map.of()).getBody();
        assertThat(first.get("flags").valueStream().map(JsonNode::asString)).contains("INCOME_NOT_DECLARED");
        assertThat(first.get("score").asInt()).isEqualTo(30);
        assertThat(first.get("recommendation").asString()).isEqualTo("decline");

        TestDatabase.owner()
                .sql("UPDATE lending_members SET monthly_income_minor = 2400000 WHERE id = ?::uuid")
                .param(member)
                .update();
        UUID manager = UUID.randomUUID();
        JsonNode second = send(
                        HttpMethod.POST,
                        LOANS + "/" + loan + "/appraisals",
                        as(manager, managerPerms, "*", "\"3\""),
                        Map.of())
                .getBody();
        assertThat(second.get("score").asInt()).isEqualTo(60);
        assertThat(second.get("declared_monthly_income_minor").asLong()).isEqualTo(2_400_000);

        JsonNode items = asOfficer(HttpMethod.GET, LOANS + "/" + loan + "/appraisals", null, null)
                .getBody()
                .get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("id").asString()).isEqualTo(second.get("id").asString());
        assertThat(items.get(1).get("score").asInt()).isEqualTo(30);
        assertThat(items.get(1).get("declared_monthly_income_minor").isNull()).isTrue();

        JsonNode l = asOfficer(HttpMethod.GET, LOANS + "/" + loan, null, null).getBody();
        assertThat(l.get("status").asString()).isEqualTo("appraised");
        assertThat(l.get("version").asInt()).isEqualTo(4);
        assertThat(TestDatabase.owner()
                        .sql("SELECT appraised_by FROM lending_loans WHERE id = ?::uuid")
                        .param(loan)
                        .query(UUID.class)
                        .single())
                .isEqualTo(manager);
    }

    /** Only a submitted application is appraised, with the permission, the version and the branch. */
    @Test
    void appraisalNeedsTheStatusThePermissionAndTheScope() {
        String product = product("GATE", false, false);
        String member = member("Test Borrower 24", "0700000024", t.headOffice(), true);
        String draft = apply(member, product, 500_000, null).getBody().get("id").asString();
        ResponseEntity<JsonNode> tooEarly = appraise(draft, "\"1\"", Map.of());
        assertThat(tooEarly.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tooEarly.getBody().get("code").asString()).isEqualTo("invalid_status_transition");

        String loan = submitted(member, product);
        String path = LOANS + "/" + loan + "/appraisals";
        assertThat(appraise(loan, null, Map.of()).getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(appraise(loan, "\"1\"", Map.of()).getBody().get("code").asString())
                .isEqualTo("version_conflict");
        assertThat(send(HttpMethod.POST, path, as(officer, "lending.loans.read", "*", "\"2\""), Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(send(
                                HttpMethod.POST,
                                path,
                                as(officer, officerPerms, t.secondBranch().toString(), "\"2\""),
                                Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(
                                HttpMethod.GET,
                                path,
                                as(officer, officerPerms, t.secondBranch().toString(), null),
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(appraise(loan, "\"2\"", Map.of("monthly_obligations_minor", -1))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /**
     * Appraisals are append-only, so an absurd amount must be refused before a row exists: each money
     * input above the loan money bound is a 422 and the loan stays submitted with no appraisal.
     */
    @Test
    void anAbsurdAmountIsRefusedBeforeAnythingIsWritten() {
        String product = product("BOUND", false, false);
        String member = member("Test Borrower 25", "0700000025", t.headOffice(), true);
        String loan = submitted(member, product);
        for (String field : List.of("declared_monthly_income_minor", "monthly_obligations_minor")) {
            ResponseEntity<JsonNode> absurd = appraise(loan, "\"2\"", Map.of(field, Long.MAX_VALUE));
            assertThat(absurd.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(absurd.getBody().get("code").asString()).isEqualTo("validation_failed");
            assertThat(absurd.getBody().get("errors").findValuesAsString("field"))
                    .containsExactly(field);
        }
        assertThat(asOfficer(HttpMethod.GET, LOANS + "/" + loan + "/appraisals", null, null)
                        .getBody()
                        .get("items"))
                .isEmpty();
        assertThat(asOfficer(HttpMethod.GET, LOANS + "/" + loan, null, null)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("submitted");
    }
}
