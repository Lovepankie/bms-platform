package com.rincoltech.bms.lending.loans;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Loan origination up to submission (#41; FR-ORG-01 to FR-ORG-03, FR-MEM-05, FR-COL-04 with
 * pledges, FR-PRD-04 at loan level, NFR-ISO-04, chapter 3 section 3.18). Roles use the permission
 * sets the chapter 8 matrix seeds. All figures fabricated.
 */
class LoanApplicationIT extends IntegrationTest {

    static final String LOANS = "/api/v1/lending/loans";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    UUID officer;
    String officerPerms;
    String managerPerms;
    String adminPerms;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("loans", true);
        officer = UUID.randomUUID();
        officerPerms = permissionsOf("loan_officer");
        managerPerms = permissionsOf("branch_manager");
        adminPerms = permissionsOf("tenant_admin");
    }

    static String permissionsOf(String role) {
        return TestDatabase.owner()
                .sql("SELECT string_agg(permission_key, ',') FROM role_permissions WHERE role_key = ?")
                .param(role)
                .query(String.class)
                .single();
    }

    HttpHeaders as(UUID user, String permissions, String branches, String ifMatch) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", user.toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", branches);
        if (ifMatch != null) {
            h.add(HttpHeaders.IF_MATCH, ifMatch);
        }
        return h;
    }

    ResponseEntity<JsonNode> send(HttpMethod method, String path, HttpHeaders h, Object body) {
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    ResponseEntity<JsonNode> asOfficer(HttpMethod method, String path, String ifMatch, Object body) {
        return send(method, path, as(officer, officerPerms, "*", ifMatch), body);
    }

    String product(String code, boolean guarantor, boolean collateral) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("interest_method", "flat");
        terms.put("interest_rate_bp", 2000);
        terms.put("rate_unit", "per_term");
        terms.put("term_unit", "month");
        terms.put("min_term_count", 1);
        terms.put("max_term_count", 3);
        terms.put("default_term_count", 1);
        terms.put("repayment_pattern", "bullet");
        terms.put("min_principal_minor", 100_000);
        terms.put("max_principal_minor", 5_000_000);
        terms.put("requires_guarantor", guarantor);
        terms.put("requires_collateral", collateral);
        return send(
                        HttpMethod.POST,
                        "/api/v1/lending/loan-products",
                        as(UUID.randomUUID(), adminPerms, "*", null),
                        Map.of("code", code, "name", "Test " + code, "terms", terms))
                .getBody()
                .get("id")
                .asString();
    }

    String member(String name, String phone, java.util.UUID branch, boolean verified) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("full_name", name);
        body.put("phone", phone);
        body.put("id_type", "none");
        body.put("confirmed_not_duplicate", true);
        String id = send(HttpMethod.POST, "/api/v1/lending/members", as(officer, officerPerms, "*", null), body)
                .getBody()
                .get("id")
                .asString();
        if (verified) {
            TestDatabase.owner()
                    .sql("UPDATE lending_members SET kyc_status = 'verified' WHERE id = ?::uuid")
                    .param(id)
                    .update();
        }
        return id;
    }

    ResponseEntity<JsonNode> apply(String memberId, String productId, long principal, Integer term) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("member_id", memberId);
        body.put("product_id", productId);
        body.put("requested_principal_minor", principal);
        body.put("requested_term_count", term);
        body.put("purpose_category", "business");
        body.put("purpose_text", "Test stock");
        body.put("proposed_disbursement_date", "2026-03-16");
        return asOfficer(HttpMethod.POST, LOANS, null, body);
    }

    String collateralItem(String memberId, String reference) {
        return send(
                        HttpMethod.POST,
                        "/api/v1/lending/collateral",
                        as(officer, officerPerms, "*", null),
                        Map.of(
                                "member_id", memberId,
                                "collateral_type", "other",
                                "description", "Test item",
                                "reference_no", reference,
                                "estimated_value_minor", 2_000_000,
                                "custody_status", "in_custody",
                                "storage_location", "Test safe"))
                .getBody()
                .get("id")
                .asString();
    }

    /** FR-ORG-01: a draft at the member's branch, terms copied from the product, a provisional schedule, history. */
    @Test
    void aDraftCopiesTheProductTermsAndShowsAProvisionalSchedule() {
        String product = product("BULLET", false, false);
        String member = member("Test Borrower 01", "0700000001", t.headOffice(), true);
        ResponseEntity<JsonNode> created = apply(member, product, 500_000, null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode l = created.getBody();
        assertThat(l.get("loan_no").asString()).isEqualTo("LN000001");
        assertThat(l.get("status").asString()).isEqualTo("draft");
        assertThat(l.get("branch_id").asString()).isEqualTo(t.headOffice().toString());
        assertThat(l.get("officer_user_id").asString()).isEqualTo(officer.toString());
        assertThat(l.get("requested_term_count").asInt()).isEqualTo(1);
        assertThat(l.get("interest_rate_bp").asInt()).isEqualTo(2000);
        assertThat(l.get("member_no").asString()).isEqualTo("M000001");
        assertThat(l.get("product_code").asString()).isEqualTo("BULLET");
        JsonNode item = l.get("provisional_schedule").get(0);
        assertThat(item.get("due_date").asString()).isEqualTo("2026-04-16");
        assertThat(item.get("total_minor").asLong()).isEqualTo(600_000);
        JsonNode history = asOfficer(
                        HttpMethod.GET, LOANS + "/" + l.get("id").asString() + "/status-history", null, null)
                .getBody()
                .get("items");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("to_status").asString()).isEqualTo("draft");
    }

    /** FR-ORG-01: principal and term inside the product's limits; archived products and exited members refused. */
    @Test
    void applicationsRespectTheProductAndTheMember() {
        String product = product("LIMITS", false, false);
        String member = member("Test Borrower 02", "0700000002", t.headOffice(), true);
        assertThat(apply(member, product, 50_000, null).getBody().get("code").asString())
                .isEqualTo("below_product_minimum");
        assertThat(apply(member, product, 500_000, 4).getBody().get("code").asString())
                .isEqualTo("above_product_maximum");

        String archived = product("OLD", false, false);
        send(
                HttpMethod.POST,
                "/api/v1/lending/loan-products/" + archived + "/archive",
                as(UUID.randomUUID(), adminPerms, "*", "\"1\""),
                null);
        assertThat(apply(member, archived, 500_000, null).getBody().get("code").asString())
                .isEqualTo("product_archived");

        TestDatabase.owner()
                .sql("UPDATE lending_members SET status = 'exited' WHERE id = ?::uuid")
                .param(member)
                .update();
        assertThat(apply(member, product, 500_000, null).getBody().get("code").asString())
                .isEqualTo("member_not_active");
    }

    /** FR-ORG-03: submit needs the product's guarantor and collateral, and verified KYC. */
    @Test
    void submissionChecksGuarantorCollateralAndKyc() {
        String product = product("SECURED", true, true);
        String borrower = member("Test Borrower 03", "0700000003", t.headOffice(), false);
        String guarantor = member("Test Guarantor 03", "0700000033", t.headOffice(), true);
        String loan =
                apply(borrower, product, 500_000, null).getBody().get("id").asString();
        String path = LOANS + "/" + loan;

        assertThat(asOfficer(HttpMethod.POST, path + "/submit", "\"1\"", null)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("guarantor_required");

        ResponseEntity<JsonNode> self = asOfficer(
                HttpMethod.PUT,
                path + "/guarantors",
                "\"1\"",
                Map.of("guarantors", List.of(Map.of("member_id", borrower, "guaranteed_amount_minor", 100_000))));
        assertThat(self.getBody().get("errors").findValuesAsString("code")).contains("guarantor_is_borrower");
        JsonNode withGuarantor = asOfficer(
                        HttpMethod.PUT,
                        path + "/guarantors",
                        "\"1\"",
                        Map.of(
                                "guarantors",
                                List.of(Map.of(
                                        "member_id",
                                        guarantor,
                                        "guaranteed_amount_minor",
                                        200_000,
                                        "relationship",
                                        "friend"))))
                .getBody();
        assertThat(withGuarantor.get("guarantors").get(0).get("member_no").asString())
                .isEqualTo("M000002");
        assertThat(withGuarantor.get("version").asInt()).isEqualTo(2);

        assertThat(asOfficer(HttpMethod.POST, path + "/submit", "\"2\"", null)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("collateral_required");
        String item = collateralItem(borrower, "TEST-PLEDGE-03");
        asOfficer(
                HttpMethod.PUT,
                path + "/collateral",
                "\"2\"",
                Map.of("collateral", List.of(Map.of("collateral_id", item, "pledged_value_minor", 1_000_000))));

        assertThat(asOfficer(HttpMethod.POST, path + "/submit", "\"3\"", null)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("kyc_not_verified");
        TestDatabase.owner()
                .sql("UPDATE lending_members SET kyc_status = 'verified' WHERE id = ?::uuid")
                .param(borrower)
                .update();
        ResponseEntity<JsonNode> submitted = asOfficer(HttpMethod.POST, path + "/submit", "\"3\"", null);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(submitted.getBody().get("status").asString()).isEqualTo("submitted");
        assertThat(submitted.getBody().get("submitted_by").asString()).isEqualTo(officer.toString());

        // Frozen: no more edits until it is returned for correction.
        assertThat(asOfficer(HttpMethod.PATCH, path, "\"4\"", Map.of("requested_term_count", 2))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("invalid_status_transition");
    }

    /** FR-COL-01 and FR-COL-04: an item secures one open loan at a time, and is not released while it does. */
    @Test
    void anItemSecuresOneOpenLoanAndIsNotReleasedWhileItDoes() {
        String product = product("PLEDGE", false, false);
        String borrower = member("Test Borrower 04", "0700000004", t.headOffice(), true);
        String other = member("Test Borrower 05", "0700000005", t.headOffice(), true);
        String item = collateralItem(borrower, "TEST-PLEDGE-04");
        String first =
                apply(borrower, product, 500_000, null).getBody().get("id").asString();
        String second =
                apply(borrower, product, 500_000, null).getBody().get("id").asString();
        Map<String, Object> pledge =
                Map.of("collateral", List.of(Map.of("collateral_id", item, "pledged_value_minor", 1_000_000)));

        assertThat(asOfficer(HttpMethod.PUT, LOANS + "/" + first + "/collateral", "\"1\"", pledge)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> twice =
                asOfficer(HttpMethod.PUT, LOANS + "/" + second + "/collateral", "\"1\"", pledge);
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(twice.getBody().get("code").asString()).isEqualTo("collateral_already_pledged");

        String othersLoan =
                apply(other, product, 500_000, null).getBody().get("id").asString();
        assertThat(asOfficer(HttpMethod.PUT, LOANS + "/" + othersLoan + "/collateral", "\"1\"", pledge)
                        .getBody()
                        .get("errors")
                        .findValuesAsString("code"))
                .contains("unknown_collateral");

        String release = "/api/v1/lending/collateral/" + item + "/release";
        ResponseEntity<JsonNode> held = send(
                HttpMethod.POST,
                release,
                as(officer, officerPerms, "*", "\"1\""),
                Map.of("collected_by", "Test Borrower 04"));
        assertThat(held.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(held.getBody().get("code").asString()).isEqualTo("collateral_secures_open_loan");

        asOfficer(
                HttpMethod.POST, LOANS + "/" + first + "/cancel", "\"2\"", Map.of("note", "Test: applied by mistake"));
        ResponseEntity<JsonNode> free = send(
                HttpMethod.POST,
                release,
                as(officer, officerPerms, "*", "\"1\""),
                Map.of("collected_by", "Test Borrower 04"));
        assertThat(free.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    /** Chapter 3 section 3.18: return needs lending.loans.approve; an officer cancels only their own draft. */
    @Test
    void returnAndCancelFollowTheStatusTable() {
        String product = product("FLOW", false, false);
        String member = member("Test Borrower 06", "0700000006", t.headOffice(), true);
        String loan = apply(member, product, 500_000, null).getBody().get("id").asString();
        String path = LOANS + "/" + loan;
        asOfficer(HttpMethod.POST, path + "/submit", "\"1\"", null);

        assertThat(asOfficer(HttpMethod.POST, path + "/return", "\"2\"", Map.of("note", "Test: check income"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        UUID manager = UUID.randomUUID();
        ResponseEntity<JsonNode> returned = send(
                HttpMethod.POST,
                path + "/return",
                as(manager, managerPerms, "*", "\"2\""),
                Map.of("note", "Test: check income"));
        assertThat(returned.getBody().get("status").asString()).isEqualTo("draft");

        asOfficer(HttpMethod.POST, path + "/submit", "\"3\"", null);
        ResponseEntity<JsonNode> officerCancel =
                asOfficer(HttpMethod.POST, path + "/cancel", "\"4\"", Map.of("note", "Test: member withdrew"));
        assertThat(officerCancel.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> managerCancel = send(
                HttpMethod.POST,
                path + "/cancel",
                as(manager, managerPerms, "*", "\"4\""),
                Map.of("note", "Test: member withdrew"));
        assertThat(managerCancel.getBody().get("status").asString()).isEqualTo("cancelled");
        assertThat(managerCancel.getBody().get("cancelled_reason").asString()).isEqualTo("Test: member withdrew");

        JsonNode history = asOfficer(HttpMethod.GET, path + "/status-history", null, null)
                .getBody()
                .get("items");
        assertThat(history.findValuesAsString("to_status"))
                .containsExactly("draft", "submitted", "draft", "submitted", "cancelled");
    }

    /** FR-PRD-04: a product edit after the application changes nothing on the loan. */
    @Test
    void aLoanKeepsTheTermsItWasCreatedWith() {
        String product = product("STABLE", false, false);
        String member = member("Test Borrower 07", "0700000007", t.headOffice(), true);
        String loan = apply(member, product, 500_000, null).getBody().get("id").asString();
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("interest_method", "flat");
        terms.put("interest_rate_bp", 1000);
        terms.put("rate_unit", "per_term");
        terms.put("term_unit", "month");
        terms.put("min_term_count", 1);
        terms.put("max_term_count", 3);
        terms.put("default_term_count", 1);
        terms.put("repayment_pattern", "bullet");
        terms.put("min_principal_minor", 100_000);
        terms.put("max_principal_minor", 5_000_000);
        send(
                HttpMethod.POST,
                "/api/v1/lending/loan-products/" + product + "/versions",
                as(UUID.randomUUID(), adminPerms, "*", "\"1\""),
                terms);

        JsonNode l = asOfficer(HttpMethod.GET, LOANS + "/" + loan, null, null).getBody();
        assertThat(l.get("interest_rate_bp").asInt()).isEqualTo(2000);
        assertThat(l.get("provisional_schedule").get(0).get("interest_minor").asLong())
                .isEqualTo(100_000);
    }

    /** NFR-ISO-04: a loan outside the caller's branches is a 404 and absent from the list. */
    @Test
    void branchScopeHolds() {
        String product = product("SCOPE", false, false);
        String member = member("Test Borrower 08", "0700000008", t.headOffice(), true);
        String loan = apply(member, product, 500_000, null).getBody().get("id").asString();
        HttpHeaders branchTwo = as(officer, officerPerms, t.secondBranch().toString(), null);
        assertThat(send(HttpMethod.GET, LOANS + "/" + loan, branchTwo, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.GET, LOANS, branchTwo, null).getBody().get("items"))
                .isEmpty();
        assertThat(asOfficer(HttpMethod.GET, LOANS + "?member_id=" + member, null, null)
                        .getBody()
                        .get("items"))
                .hasSize(1);
    }
}
