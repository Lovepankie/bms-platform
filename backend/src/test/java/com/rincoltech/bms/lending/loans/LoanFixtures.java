package com.rincoltech.bms.lending.loans;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Shared fixtures of the loan integration tests: a tenant, roles from the seeded matrix, products, members. */
public abstract class LoanFixtures extends IntegrationTest {

    protected static final String LOANS = "/api/v1/lending/loans";

    @Autowired
    protected TestRestTemplate http;

    protected TestDatabase.Fixture t;
    protected UUID officer;
    protected String officerPerms;
    protected String managerPerms;
    protected String adminPerms;

    @BeforeEach
    protected void setUp() {
        t = TestDatabase.tenant("loans", true);
        officer = UUID.randomUUID();
        officerPerms = permissionsOf("loan_officer");
        managerPerms = permissionsOf("branch_manager");
        adminPerms = permissionsOf("tenant_admin");
    }

    protected static String permissionsOf(String role) {
        return TestDatabase.owner()
                .sql("SELECT string_agg(permission_key, ',') FROM role_permissions WHERE role_key = ?")
                .param(role)
                .query(String.class)
                .single();
    }

    protected HttpHeaders as(UUID user, String permissions, String branches, String ifMatch) {
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

    protected ResponseEntity<JsonNode> send(HttpMethod method, String path, HttpHeaders h, Object body) {
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> asOfficer(HttpMethod method, String path, String ifMatch, Object body) {
        return send(method, path, as(officer, officerPerms, "*", ifMatch), body);
    }

    protected String product(String code, boolean guarantor, boolean collateral) {
        return product(code, guarantor, collateral, collateral ? 10_000 : null);
    }

    protected String product(String code, boolean guarantor, boolean collateral, Integer minCoverBp) {
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
        terms.put("min_collateral_cover_bp", minCoverBp);
        return send(
                        HttpMethod.POST,
                        "/api/v1/lending/loan-products",
                        as(UUID.randomUUID(), adminPerms, "*", null),
                        Map.of("code", code, "name", "Test " + code, "terms", terms))
                .getBody()
                .get("id")
                .asString();
    }

    protected String member(String name, String phone, java.util.UUID branch, boolean verified) {
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

    protected ResponseEntity<JsonNode> apply(String memberId, String productId, long principal, Integer term) {
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

    protected String collateralItem(String memberId, String reference) {
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
}
