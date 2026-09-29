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
 * Member lifecycle after registration (#10): edit with If-Match, duplicate check, KYC decision,
 * blacklist and status (FR-MEM-04, FR-MEM-05, FR-MEM-10, FR-MEM-13, FR-AUD-01, FR-AUD-05,
 * NFR-ISO-04, chapter 7 sections 7.9 and 7.11.11). All data fabricated.
 */
class MemberLifecycleIT extends IntegrationTest {

    static final String ALL = String.join(
            ",",
            "lending.members.read",
            "lending.members.create",
            "lending.members.update",
            "lending.members.verify_kyc",
            "lending.members.blacklist");

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    UUID staff;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("lifecycle", true);
        staff = UUID.randomUUID();
    }

    HttpHeaders headers(String permissions, String branches, String ifMatch) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", staff.toString());
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

    Map<String, Object> member(UUID branch, String name, String phone, String nin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("full_name", name);
        body.put("phone", phone);
        body.put("id_type", nin == null ? "none" : "nin");
        body.put("national_id", nin);
        return body;
    }

    String create(UUID branch, String name, String phone, String nin) {
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST, "/api/v1/lending/members", headers(ALL, "*", null), member(branch, name, phone, nin));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    String path(String id) {
        return "/api/v1/lending/members/" + id;
    }

    String lastAudit(String id) {
        return TestDatabase.owner()
                .sql(
                        "SELECT action || ' ' || data::text FROM audit_log WHERE entity_id = ?::uuid ORDER BY created_at DESC, id DESC LIMIT 1")
                .param(id)
                .query(String.class)
                .single();
    }

    /** FR-AUD-01, FR-AUD-05: an edit bumps the version and audits only what changed, masked. */
    @Test
    void anEditBumpsTheVersionAndAuditsOnlyTheChangedFields() {
        String id = create(t.headOffice(), "Test Borrower 01", "0700000001", null);

        ResponseEntity<JsonNode> r = send(
                HttpMethod.PATCH,
                path(id),
                headers(ALL, "*", "\"1\""),
                Map.of("occupation", "Test Farmer", "phone", "0700000091"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getETag()).isEqualTo("\"2\"");
        assertThat(r.getBody().get("occupation").asString()).isEqualTo("Test Farmer");
        assertThat(r.getBody().get("phone_e164").asString()).isEqualTo("+256700000091");
        assertThat(r.getBody().get("full_name").asString()).isEqualTo("Test Borrower 01");
        assertThat(lastAudit(id))
                .startsWith("lending.member.updated ")
                .contains("occupation", "0091")
                .doesNotContain("full_name", "+256700000091");
    }

    /** Chapter 7 section 7.9: If-Match is required and must be current. */
    @Test
    void anEditNeedsTheCurrentVersion() {
        String id = create(t.headOffice(), "Test Borrower 02", "0700000002", null);

        ResponseEntity<JsonNode> missing =
                send(HttpMethod.PATCH, path(id), headers(ALL, "*", null), Map.of("occupation", "Test Farmer"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

        ResponseEntity<JsonNode> stale =
                send(HttpMethod.PATCH, path(id), headers(ALL, "*", "\"7\""), Map.of("occupation", "Test Farmer"));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("version_conflict");
    }

    /** FR-MEM-10: status changes through the edit. */
    @Test
    void aMemberCanBeSetInactiveOrExited() {
        String id = create(t.headOffice(), "Test Borrower 03", "0700000003", null);
        ResponseEntity<JsonNode> r =
                send(HttpMethod.PATCH, path(id), headers(ALL, "*", "\"1\""), Map.of("status", "exited"));
        assertThat(r.getBody().get("status").asString()).isEqualTo("exited");
    }

    /** FR-MEM-04: a phone already on another member needs the user's confirmation, on create and on edit. */
    @Test
    void aSharedPhoneNeedsConfirmation() {
        create(t.headOffice(), "Test Borrower 04", "0700000004", null);

        Map<String, Object> second = member(t.headOffice(), "Test Borrower 05", "0700000004", null);
        ResponseEntity<JsonNode> refused =
                send(HttpMethod.POST, "/api/v1/lending/members", headers(ALL, "*", null), second);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("duplicate_phone");
        assertThat(refused.getBody().get("detail").asString()).contains("M000001");

        second.put("confirmed_not_duplicate", true);
        ResponseEntity<JsonNode> confirmed =
                send(HttpMethod.POST, "/api/v1/lending/members", headers(ALL, "*", null), second);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lastAudit(confirmed.getBody().get("id").asString())).contains("confirmed_not_duplicate");

        String third = create(t.headOffice(), "Test Borrower 06", "0700000006", null);
        ResponseEntity<JsonNode> edit =
                send(HttpMethod.PATCH, path(third), headers(ALL, "*", "\"1\""), Map.of("phone", "0700000004"));
        assertThat(edit.getBody().get("code").asString()).isEqualTo("duplicate_phone");
    }

    /** FR-MEM-03 on edit: a NIN already on another member is refused. */
    @Test
    void anEditCannotTakeAnotherMembersNin() {
        create(t.headOffice(), "Test Borrower 07", "0700000007", "CMTEST0000007A");
        String id = create(t.headOffice(), "Test Borrower 08", "0700000008", null);
        ResponseEntity<JsonNode> r = send(
                HttpMethod.PATCH,
                path(id),
                headers(ALL, "*", "\"1\""),
                Map.of("id_type", "nin", "national_id", "CMTEST0000007A"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("duplicate_nin");
    }

    /** FR-MEM-04: matches by NIN, phone and name; out-of-scope matches show only number and reasons. */
    @Test
    void theDuplicateCheckFindsMatchesAndRespectsBranchScope() {
        create(t.headOffice(), "Test Borrower Alpha", "0700000009", "CMTEST0000009A");
        create(t.headOffice(), "Other Person Zulu", "0700000010", null);
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("full_name", "Alpha Test Borrower");
        probe.put("phone", "700000009");
        probe.put("national_id", "cmtest0000009a");

        JsonNode all = send(HttpMethod.POST, "/api/v1/lending/members/duplicate-check", headers(ALL, "*", null), probe)
                .getBody()
                .get("candidates");
        assertThat(all).hasSize(1);
        JsonNode c = all.get(0);
        assertThat(c.get("member_no").asString()).isEqualTo("M000001");
        assertThat(c.get("in_scope").asBoolean()).isTrue();
        assertThat(c.get("match_reasons").valueStream().map(JsonNode::asString).toList())
                .containsExactly("nin", "phone", "name");
        assertThat(c.get("phone_e164_masked").asString()).endsWith("0009").doesNotContain("7000");

        JsonNode scoped = send(
                        HttpMethod.POST,
                        "/api/v1/lending/members/duplicate-check",
                        headers(ALL, t.secondBranch().toString(), null),
                        probe)
                .getBody()
                .get("candidates")
                .get(0);
        assertThat(scoped.get("in_scope").asBoolean()).isFalse();
        assertThat(scoped.get("member_no").asString()).isEqualTo("M000001");
        assertThat(scoped.get("id").isNull()).isTrue();
        assertThat(scoped.get("full_name").isNull()).isTrue();

        ResponseEntity<JsonNode> empty =
                send(HttpMethod.POST, "/api/v1/lending/members/duplicate-check", headers(ALL, "*", null), Map.of());
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** FR-MEM-05: only a member pending verification is decided; rejecting needs a note. */
    @Test
    void kycIsDecidedOnlyWhenPendingVerification() {
        String id = create(t.headOffice(), "Test Borrower 11", "0700000011", null);
        String verify = path(id) + "/kyc/verify";

        ResponseEntity<JsonNode> tooEarly =
                send(HttpMethod.POST, verify, headers(ALL, "*", "\"1\""), Map.of("decision", "verified"));
        assertThat(tooEarly.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tooEarly.getBody().get("code").asString()).isEqualTo("invalid_status_transition");

        TestDatabase.owner()
                .sql("UPDATE lending_members SET kyc_status = 'pending_verification' WHERE id = ?::uuid")
                .param(id)
                .update();
        ResponseEntity<JsonNode> noNote =
                send(HttpMethod.POST, verify, headers(ALL, "*", "\"1\""), Map.of("decision", "rejected"));
        assertThat(noNote.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> ok =
                send(HttpMethod.POST, verify, headers(ALL, "*", "\"1\""), Map.of("decision", "verified"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("kyc_status").asString()).isEqualTo("verified");
        assertThat(ok.getBody().get("kyc_verified_by").asString()).isEqualTo(staff.toString());
        assertThat(ok.getBody().get("kyc_verified_at").isNull()).isFalse();
        assertThat(lastAudit(id)).startsWith("lending.member.kyc_verified ");
    }

    /** FR-MEM-13: blacklisting needs a reason and is audited; lifting clears the stored reason. */
    @Test
    void blacklistingNeedsAReasonAndCanBeLifted() {
        String id = create(t.headOffice(), "Test Borrower 12", "0700000012", null);
        String bl = path(id) + "/blacklist";

        ResponseEntity<JsonNode> noReason =
                send(HttpMethod.POST, bl, headers(ALL, "*", "\"1\""), Map.of("is_blacklisted", true));
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> on = send(
                HttpMethod.POST,
                bl,
                headers(ALL, "*", "\"1\""),
                Map.of("is_blacklisted", true, "reason", "Test reason"));
        assertThat(on.getBody().get("is_blacklisted").asBoolean()).isTrue();
        assertThat(on.getBody().get("blacklist_reason").asString()).isEqualTo("Test reason");
        assertThat(lastAudit(id)).startsWith("lending.member.blacklisted ");

        ResponseEntity<JsonNode> again = send(
                HttpMethod.POST,
                bl,
                headers(ALL, "*", "\"2\""),
                Map.of("is_blacklisted", true, "reason", "Test reason"));
        assertThat(again.getBody().get("code").asString()).isEqualTo("invalid_status_transition");

        ResponseEntity<JsonNode> off =
                send(HttpMethod.POST, bl, headers(ALL, "*", "\"2\""), Map.of("is_blacklisted", false));
        assertThat(off.getBody().get("is_blacklisted").asBoolean()).isFalse();
        assertThat(off.getBody().get("blacklist_reason").isNull()).isTrue();
        assertThat(lastAudit(id)).startsWith("lending.member.blacklist_lifted ").contains("Test reason");
    }

    /** NFR-ISO-04 and chapter 8: out of branch scope is 404, a missing permission is 403. */
    @Test
    void changesRespectBranchScopeAndPermissions() {
        String id = create(t.headOffice(), "Test Borrower 13", "0700000013", null);

        ResponseEntity<JsonNode> otherBranch = send(
                HttpMethod.PATCH,
                path(id),
                headers(ALL, t.secondBranch().toString(), "\"1\""),
                Map.of("occupation", "Test Farmer"));
        assertThat(otherBranch.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> readOnly = send(
                HttpMethod.POST,
                path(id) + "/blacklist",
                headers("lending.members.read,lending.members.update", "*", "\"1\""),
                Map.of("is_blacklisted", true, "reason", "Test reason"));
        assertThat(readOnly.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
