package com.rincoltech.bms.lending.loans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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
        terms.put("min_collateral_cover_bp", collateral ? 10_000 : null);
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
        body.put("proposed_disbursement_date", "2030-03-16");
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
        assertThat(item.get("due_date").asString()).isEqualTo("2030-04-16");
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

    void item(String sql, String id) {
        TestDatabase.owner()
                .sql("UPDATE lending_collateral_items SET " + sql + " WHERE id = ?::uuid")
                .param(id)
                .update();
    }

    void memberRow(String sql, String id) {
        TestDatabase.owner()
                .sql("UPDATE lending_members SET " + sql + " WHERE id = ?::uuid")
                .param(id)
                .update();
    }

    static Map<String, Object> pledge(String item, long value) {
        return Map.of("collateral", List.of(Map.of("collateral_id", item, "pledged_value_minor", value)));
    }

    static String code(ResponseEntity<JsonNode> response) {
        return response.getBody().get("code").asString();
    }

    /**
     * FR-COL-01 under concurrency: two drafts pledge the same item at the same moment. The item's
     * row lock makes them take turns, so exactly one wins and the other sees the pledge.
     */
    @Test
    void twoLoansPledgingOneItemAtOnceGiveOneWinner() throws Exception {
        String product = product("RACE", false, false);
        String borrower = member("Test Borrower 11", "0700000011", t.headOffice(), true);
        String item = collateralItem(borrower, "TEST-PLEDGE-11");
        List<String> loans = List.of(
                apply(borrower, product, 500_000, null).getBody().get("id").asString(),
                apply(borrower, product, 500_000, null).getBody().get("id").asString());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<HttpStatusCode>> results = loans.stream()
                    .map(loan -> pool.submit(() -> {
                        start.await();
                        return asOfficer(
                                        HttpMethod.PUT,
                                        LOANS + "/" + loan + "/collateral",
                                        "\"1\"",
                                        pledge(item, 1_000_000))
                                .getStatusCode();
                    }))
                    .toList();
            start.countDown();
            List<HttpStatusCode> statuses = new ArrayList<>();
            for (Future<HttpStatusCode> r : results) {
                statuses.add(r.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        }
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM lending_loan_collateral WHERE collateral_id = ?::uuid"
                                + " AND released_at IS NULL")
                        .param(item)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /** A pledge states at most the item's value, and a secured product takes valued items only. */
    @Test
    void aPledgeCannotExceedTheItemsValue() {
        String secured = product("VALUED", false, true);
        String borrower = member("Test Borrower 12", "0700000012", t.headOffice(), true);
        String item = collateralItem(borrower, "TEST-PLEDGE-12");
        String loan =
                apply(borrower, secured, 500_000, null).getBody().get("id").asString();
        String path = LOANS + "/" + loan + "/collateral";

        ResponseEntity<JsonNode> over = asOfficer(HttpMethod.PUT, path, "\"1\"", pledge(item, 2_000_001));
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        JsonNode problem = over.getBody().get("errors").get(0);
        assertThat(problem.get("field").asString()).isEqualTo("collateral[0].pledged_value_minor");
        assertThat(problem.get("code").asString()).isEqualTo("pledge_exceeds_value");
        assertThat(asOfficer(HttpMethod.PUT, path, "\"1\"", pledge(item, Long.MAX_VALUE))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        item("estimated_value_minor = NULL", item);
        assertThat(asOfficer(HttpMethod.PUT, path, "\"1\"", pledge(item, 1_000_000))
                        .getBody()
                        .get("errors")
                        .findValuesAsString("code"))
                .containsExactly("collateral_not_valued");
        item("estimated_value_minor = 2000000, currency = 'USD'", item);
        assertThat(asOfficer(HttpMethod.PUT, path, "\"1\"", pledge(item, 1_000_000))
                        .getBody()
                        .get("errors")
                        .findValuesAsString("code"))
                .containsExactly("currency_mismatch");
        item("currency = 'UGX', custody_status = 'seized'", item);
        assertThat(asOfficer(HttpMethod.PUT, path, "\"1\"", pledge(item, 1_000_000))
                        .getBody()
                        .get("errors")
                        .findValuesAsString("code"))
                .containsExactly("collateral_not_held");
        item("custody_status = 'in_custody'", item);
        assertThat(asOfficer(HttpMethod.PUT, path, "\"1\"", pledge(item, 2_000_000))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** FR-ORG-03: submit checks everything again as it stands now, each refusal with its own code. */
    @Test
    void submitChecksAgainWhatTheDraftCollected() {
        String product = product("RECHECK", true, true);
        String borrower = member("Test Borrower 13", "0700000013", t.headOffice(), true);
        String guarantor = member("Test Guarantor 13", "0700000113", t.headOffice(), true);
        String item = collateralItem(borrower, "TEST-PLEDGE-13");
        String loan =
                apply(borrower, product, 500_000, null).getBody().get("id").asString();
        String path = LOANS + "/" + loan;
        asOfficer(
                HttpMethod.PUT,
                path + "/guarantors",
                "\"1\"",
                Map.of("guarantors", List.of(Map.of("member_id", guarantor, "guaranteed_amount_minor", 200_000))));
        asOfficer(HttpMethod.PUT, path + "/collateral", "\"2\"", pledge(item, 1_000_000));

        item("custody_status = 'released'", item);
        ResponseEntity<JsonNode> gone = asOfficer(HttpMethod.POST, path + "/submit", "\"3\"", null);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(gone)).isEqualTo("collateral_not_held");
        item("custody_status = 'in_custody', estimated_value_minor = 900000", item);
        assertThat(code(asOfficer(HttpMethod.POST, path + "/submit", "\"3\"", null)))
                .isEqualTo("pledge_exceeds_value");
        item("estimated_value_minor = 2000000", item);

        memberRow("status = 'exited'", guarantor);
        assertThat(code(asOfficer(HttpMethod.POST, path + "/submit", "\"3\"", null)))
                .isEqualTo("guarantor_not_active");
        memberRow("status = 'active', is_blacklisted = true, blacklist_reason = 'Test: fixture'", guarantor);
        assertThat(code(asOfficer(HttpMethod.POST, path + "/submit", "\"3\"", null)))
                .isEqualTo("guarantor_blacklisted");
        memberRow("is_blacklisted = false, blacklist_reason = NULL", guarantor);

        memberRow("is_blacklisted = true, blacklist_reason = 'Test: fixture'", borrower);
        assertThat(code(asOfficer(HttpMethod.POST, path + "/submit", "\"3\"", null)))
                .isEqualTo("member_blacklisted");
        memberRow("is_blacklisted = false, blacklist_reason = NULL, status = 'exited'", borrower);
        assertThat(code(asOfficer(HttpMethod.POST, path + "/submit", "\"3\"", null)))
                .isEqualTo("member_not_active");
        memberRow("status = 'active'", borrower);

        ResponseEntity<JsonNode> submitted = asOfficer(HttpMethod.POST, path + "/submit", "\"3\"", null);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asOfficer(HttpMethod.POST, path + "/submit", "\"4\"", null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    /** Guarantors and pledges are frozen with the loan: the database refuses a change once it is not a draft. */
    @Test
    void pledgesAndGuarantorsChangeOnlyOnADraft() {
        String product = product("FROZEN", false, false);
        String borrower = member("Test Borrower 14", "0700000014", t.headOffice(), true);
        String other = member("Test Guarantor 14", "0700000114", t.headOffice(), true);
        String item = collateralItem(borrower, "TEST-PLEDGE-14");
        String loan =
                apply(borrower, product, 500_000, null).getBody().get("id").asString();
        String path = LOANS + "/" + loan;
        asOfficer(HttpMethod.PUT, path + "/collateral", "\"1\"", pledge(item, 1_000_000));
        asOfficer(HttpMethod.POST, path + "/submit", "\"2\"", null);

        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE lending_loan_collateral SET pledged_value_minor = 5 WHERE loan_id = ?::uuid")
                        .param(loan)
                        .update())
                .hasMessageContaining("only on a draft loan");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("INSERT INTO lending_loan_guarantors (id, tenant_id, loan_id, guarantor_member_id,"
                                + " guaranteed_amount_minor, status) VALUES (?, ?, ?::uuid, ?::uuid, 1000, 'active')")
                        .params(UUID.randomUUID(), t.tenantId(), loan, other)
                        .update())
                .hasMessageContaining("only on a draft loan");

        // Cancelling releases the pledge (the one change allowed later) and frees the item for another loan.
        send(
                HttpMethod.POST,
                path + "/cancel",
                as(UUID.randomUUID(), managerPerms, "*", "\"3\""),
                Map.of("note", "Test: member withdrew"));
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM lending_loan_collateral WHERE loan_id = ?::uuid"
                                + " AND released_at IS NOT NULL")
                        .param(loan)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        String next =
                apply(borrower, product, 500_000, null).getBody().get("id").asString();
        assertThat(asOfficer(HttpMethod.PUT, LOANS + "/" + next + "/collateral", "\"1\"", pledge(item, 1_000_000))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** FR-ORG-03: the schedule carries the version's added fees, and submit fixes the date it runs from. */
    @Test
    void theProvisionalScheduleCarriesFeesAndAFixedDate() {
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
        terms.put(
                "fees",
                List.of(Map.of(
                        "name",
                        "Test insurance",
                        "fee_type",
                        "insurance",
                        "calc_method",
                        "percent_of_principal",
                        "rate_bp",
                        200,
                        "timing",
                        "added_to_loan")));
        String product = send(
                        HttpMethod.POST,
                        "/api/v1/lending/loan-products",
                        as(UUID.randomUUID(), adminPerms, "*", null),
                        Map.of("code", "FEES", "name", "Test FEES", "terms", terms))
                .getBody()
                .get("id")
                .asString();
        String borrower = member("Test Borrower 15", "0700000015", t.headOffice(), true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("member_id", borrower);
        body.put("product_id", product);
        body.put("requested_principal_minor", 500_000);
        body.put("purpose_category", "business");
        body.put("purpose_text", "Test stock");
        JsonNode draft = asOfficer(HttpMethod.POST, LOANS, null, body).getBody();
        assertThat(draft.get("proposed_disbursement_date").isNull()).isTrue();
        JsonNode item = draft.get("provisional_schedule").get(0);
        assertThat(item.get("fee_minor").asLong()).isEqualTo(10_000);
        assertThat(item.get("total_minor").asLong()).isEqualTo(610_000);

        String path = LOANS + "/" + draft.get("id").asString();
        Map<String, Object> past = Map.of("proposed_disbursement_date", "2020-01-01");
        assertThat(asOfficer(HttpMethod.PATCH, path, "\"1\"", past)
                        .getBody()
                        .get("errors")
                        .findValuesAsString("code"))
                .containsExactly("in_the_past");
        JsonNode cleared = asOfficer(HttpMethod.PATCH, path, "\"1\"", Map.of("purpose_text", ""))
                .getBody();
        assertThat(cleared.get("purpose_text").isNull()).isTrue();

        JsonNode submitted =
                asOfficer(HttpMethod.POST, path + "/submit", "\"2\"", null).getBody();
        assertThat(submitted.get("proposed_disbursement_date").isNull()).isFalse();
    }

    /** Every change needs the current version, and another tenant never sees the loan. */
    @Test
    void changesNeedTheVersionAndStayInsideTheTenant() {
        String product = product("VERSIONS", false, false);
        String borrower = member("Test Borrower 16", "0700000016", t.headOffice(), true);
        String loan =
                apply(borrower, product, 500_000, null).getBody().get("id").asString();
        String path = LOANS + "/" + loan;
        Map<String, Object> note = Map.of("note", "Test: note");
        List<Object[]> calls = List.of(
                new Object[] {HttpMethod.PATCH, path, Map.of("requested_term_count", 2)},
                new Object[] {HttpMethod.PUT, path + "/guarantors", Map.of("guarantors", List.of())},
                new Object[] {HttpMethod.PUT, path + "/collateral", Map.of("collateral", List.of())},
                new Object[] {HttpMethod.POST, path + "/submit", null},
                new Object[] {HttpMethod.POST, path + "/cancel", note});
        for (Object[] c : calls) {
            assertThat(asOfficer((HttpMethod) c[0], (String) c[1], null, c[2]).getStatusCode())
                    .as("%s %s without If-Match", c[0], c[1])
                    .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
            assertThat(code(asOfficer((HttpMethod) c[0], (String) c[1], "\"9\"", c[2])))
                    .as("%s %s with a stale version", c[0], c[1])
                    .isEqualTo("version_conflict");
        }
        HttpHeaders manager = as(UUID.randomUUID(), managerPerms, "*", null);
        assertThat(send(HttpMethod.POST, path + "/return", manager, note).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(code(send(
                        HttpMethod.POST, path + "/return", as(UUID.randomUUID(), managerPerms, "*", "\"9\""), note)))
                .isEqualTo("version_conflict");

        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("member_id", UUID.randomUUID());
        unknown.put("product_id", product);
        unknown.put("requested_principal_minor", 500_000);
        unknown.put("purpose_category", "business");
        assertThat(asOfficer(HttpMethod.POST, LOANS, null, unknown)
                        .getBody()
                        .get("errors")
                        .findValuesAsString("code"))
                .containsExactly("unknown_member");

        TestDatabase.Fixture other = TestDatabase.tenant("loans-other", true);
        HttpHeaders foreign = as(officer, officerPerms, "*", null);
        foreign.set("X-Tenant", other.slug());
        assertThat(send(HttpMethod.GET, path, foreign, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
