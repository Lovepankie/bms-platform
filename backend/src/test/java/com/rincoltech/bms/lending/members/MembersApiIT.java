package com.rincoltech.bms.lending.members;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.util.LinkedHashMap;
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
 * The reference vertical slice end to end through HTTP (FR-MEM-01 to FR-MEM-03, FR-AUD-01,
 * FR-TEN-03, NFR-ISO-04, chapter 7). Tenant from X-Tenant (test profile), principal from the
 * development headers of chapter 7 section 7.4.3. All data fabricated.
 */
class MembersApiIT extends IntegrationTest {

    static final String ALL = "lending.members.read,lending.members.create";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    UUID staff;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("members", true);
        staff = UUID.randomUUID();
    }

    HttpHeaders headers(String tenantSlug, String permissions, String branches) {
        HttpHeaders h = new HttpHeaders();
        if (tenantSlug != null) {
            h.add("X-Tenant", tenantSlug);
        }
        h.add("X-Dev-User-Id", staff.toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", branches);
        return h;
    }

    Map<String, Object> newMember(UUID branch, String name, String phone, String nin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("full_name", name);
        body.put("phone", phone);
        body.put("id_type", nin == null ? "none" : "nin");
        body.put("national_id", nin);
        body.put("marital_status", "married");
        body.put("occupation", "Test Trader");
        body.put("location", "Test Village A");
        return body;
    }

    ResponseEntity<JsonNode> post(HttpHeaders h, Object body) {
        return http.exchange("/api/v1/lending/members", HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
    }

    ResponseEntity<JsonNode> get(HttpHeaders h, String path) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
    }

    @Test
    void createListAndGetAMember() {
        ResponseEntity<JsonNode> created = post(
                headers(t.slug(), ALL, "*"),
                newMember(t.headOffice(), "Test Borrower 01", "0700000001", "cmtest0000001a"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode m = created.getBody();
        assertThat(m.get("member_no").asString()).isEqualTo("M000001");
        assertThat(m.get("phone_e164").asString()).isEqualTo("+256700000001");
        assertThat(m.get("national_id").asString()).isEqualTo("CMTEST0000001A");
        assertThat(m.get("currency").asString()).isEqualTo("UGX");
        assertThat(m.get("kyc_status").asString()).isEqualTo("incomplete");
        assertThat(created.getHeaders().getLocation())
                .hasToString("/api/v1/lending/members/" + m.get("id").asString());
        assertThat(created.getHeaders().getETag()).isEqualTo("\"1\"");

        ResponseEntity<JsonNode> list = get(headers(t.slug(), ALL, "*"), "/api/v1/lending/members");
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode item = list.getBody().get("items").get(0);
        assertThat(item.get("member_no").asString()).isEqualTo("M000001");
        assertThat(item.get("phone_e164_masked").asString()).isEqualTo("*********0001");
        assertThat(item.get("national_id_masked").asString()).endsWith("001A").doesNotContain("CMTEST");
        assertThat(list.getBody().get("next_cursor").isNull()).isTrue();

        ResponseEntity<JsonNode> one = get(
                headers(t.slug(), ALL, "*"),
                "/api/v1/lending/members/" + m.get("id").asString());
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one.getBody().get("full_name").asString()).isEqualTo("Test Borrower 01");
    }

    /** FR-AUD-01, FR-AUD-05: one audit row in the same transaction, identifiers masked. */
    @Test
    void creatingAMemberWritesAMaskedAuditRow() {
        String id = post(
                        headers(t.slug(), ALL, "*"),
                        newMember(t.headOffice(), "Test Borrower 02", "0700000002", "CMTEST0000002A"))
                .getBody()
                .get("id")
                .asString();
        Map<String, Object> row = TestDatabase.owner()
                .sql(
                        "SELECT action, actor_user_id, actor_kind, request_id, data::text AS data FROM audit_log WHERE entity_id = ?::uuid")
                .param(id)
                .query()
                .singleRow();
        assertThat(row.get("action")).isEqualTo("lending.member.created");
        assertThat(row.get("actor_user_id")).isEqualTo(staff);
        assertThat(row.get("actor_kind")).isEqualTo("staff");
        assertThat(row.get("request_id")).isNotNull();
        assertThat((String) row.get("data"))
                .contains("002A")
                .doesNotContain("CMTEST0000002A")
                .doesNotContain("+256700000002");
    }

    @Test
    void validationFailuresUseTheProblemShape() {
        ResponseEntity<JsonNode> missing = post(headers(t.slug(), ALL, "*"), Map.of("phone", "0700000003"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(missing.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(missing.getBody().get("code").asString()).isEqualTo("validation_failed");
        assertThat(missing.getBody().get("request_id").asString()).isNotBlank();
        assertThat(missing.getBody().get("errors").findValuesAsString("field"))
                .contains("full_name", "branch_id", "id_type");

        ResponseEntity<JsonNode> badPhone =
                post(headers(t.slug(), ALL, "*"), newMember(t.headOffice(), "Test Borrower 03", "12345", null));
        assertThat(badPhone.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(badPhone.getBody().get("code").asString()).isEqualTo("invalid_phone");

        ResponseEntity<JsonNode> badNin =
                post(headers(t.slug(), ALL, "*"), newMember(t.headOffice(), "Test Borrower 04", "0700000004", "XX123"));
        assertThat(badNin.getBody().get("code").asString()).isEqualTo("invalid_nin");
    }

    @Test
    void aDuplicateNinIsRefusedAndLeavesNoAuditRow() {
        post(
                headers(t.slug(), ALL, "*"),
                newMember(t.headOffice(), "Test Borrower 05", "0700000005", "CMTEST0000005A"));
        ResponseEntity<JsonNode> dup = post(
                headers(t.slug(), ALL, "*"),
                newMember(t.headOffice(), "Test Borrower 06", "0700000006", "CMTEST0000005A"));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody().get("code").asString()).isEqualTo("duplicate_nin");
        assertThat(dup.getBody().get("detail").asString()).contains("M000001");
        long audits = TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'lending.member.created'")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        assertThat(audits).isEqualTo(1);
    }

    /** Tenant isolation through the API: B never sees A's member, by list or by id. */
    @Test
    void anotherTenantCannotSeeTheMember() {
        String id = post(headers(t.slug(), ALL, "*"), newMember(t.headOffice(), "Test Borrower 07", "0700000007", null))
                .getBody()
                .get("id")
                .asString();
        TestDatabase.Fixture other = TestDatabase.tenant("members-other", true);

        assertThat(get(headers(other.slug(), ALL, "*"), "/api/v1/lending/members/" + id)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(headers(other.slug(), ALL, "*"), "/api/v1/lending/members")
                        .getBody()
                        .get("items"))
                .isEmpty();
        // Naming tenant B's branch from tenant A's context does not reach tenant B either.
        ResponseEntity<JsonNode> cross = post(
                headers(t.slug(), ALL, "*"), newMember(other.headOffice(), "Test Borrower 08", "0700000008", null));
        assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** NFR-ISO-04: branch scope on list and detail. */
    @Test
    void aBranchScopedUserSeesOnlyTheirBranch() {
        String inHq = post(
                        headers(t.slug(), ALL, "*"), newMember(t.headOffice(), "Test Borrower 09", "0700000009", null))
                .getBody()
                .get("id")
                .asString();
        post(headers(t.slug(), ALL, "*"), newMember(t.secondBranch(), "Test Borrower 10", "0700000010", null));

        HttpHeaders branchTwoOnly = headers(t.slug(), ALL, t.secondBranch().toString());
        JsonNode items = get(branchTwoOnly, "/api/v1/lending/members").getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("full_name").asString()).isEqualTo("Test Borrower 10");
        assertThat(get(branchTwoOnly, "/api/v1/lending/members/" + inHq).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(branchTwoOnly, "/api/v1/lending/members?branch_id=" + t.headOffice())
                        .getBody()
                        .get("items"))
                .isEmpty();
        ResponseEntity<JsonNode> outOfScope =
                post(branchTwoOnly, newMember(t.headOffice(), "Test Borrower 11", "0700000011", null));
        assertThat(outOfScope.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void listsPageWithAnOpaqueCursor() {
        for (int i = 12; i <= 14; i++) {
            post(headers(t.slug(), ALL, "*"), newMember(t.headOffice(), "Test Borrower " + i, "07000000" + i, null));
        }
        JsonNode first = get(headers(t.slug(), ALL, "*"), "/api/v1/lending/members?limit=2")
                .getBody();
        assertThat(first.get("items")).hasSize(2);
        String cursor = first.get("next_cursor").asString();
        JsonNode second = get(headers(t.slug(), ALL, "*"), "/api/v1/lending/members?limit=2&cursor=" + cursor)
                .getBody();
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("items").get(0).get("member_no").asString()).isEqualTo("M000003");
        assertThat(second.get("next_cursor").isNull()).isTrue();
    }

    /** Fail closed: no tenant, an unknown tenant, no principal, no permission, module off. */
    @Test
    void requestsFailClosed() {
        ResponseEntity<JsonNode> noTenant = get(headers(null, ALL, "*"), "/api/v1/lending/members");
        assertThat(noTenant.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(noTenant.getBody().get("code").asString()).isEqualTo("unknown_tenant");

        assertThat(get(headers("no-such-tenant", ALL, "*"), "/api/v1/lending/members")
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("unknown_tenant");

        HttpHeaders anonymous = new HttpHeaders();
        anonymous.add("X-Tenant", t.slug());
        ResponseEntity<JsonNode> unauthenticated = get(anonymous, "/api/v1/lending/members");
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<JsonNode> denied = post(
                headers(t.slug(), "lending.members.read", "*"),
                newMember(t.headOffice(), "Test Borrower 15", "0700000015", null));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().get("code").asString()).isEqualTo("permission_denied");

        TestDatabase.Fixture noLending = TestDatabase.tenant("members-nolending", false);
        ResponseEntity<JsonNode> off = get(headers(noLending.slug(), ALL, "*"), "/api/v1/lending/members");
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(off.getBody().get("code").asString()).isEqualTo("module_not_enabled");
    }

    /** The host, not a header, is the production path: <slug>.<base domain>. */
    @Test
    void theTenantResolvesFromTheHost() {
        HttpHeaders h = headers(null, ALL, "*");
        h.set(HttpHeaders.HOST, t.slug() + ".bms.test");
        ResponseEntity<JsonNode> list = get(h, "/api/v1/lending/members");
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
