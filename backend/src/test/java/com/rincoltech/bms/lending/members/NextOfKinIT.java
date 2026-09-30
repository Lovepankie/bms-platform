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
 * Next of kin, automatic and suggested links, and the relationship panel (#11; FR-MEM-06,
 * FR-MEM-07, FR-MEM-08, FR-AUD-01, NFR-ISO-04, chapter 7 section 7.11.11). All data fabricated.
 */
class NextOfKinIT extends IntegrationTest {

    static final String ALL = "lending.members.read,lending.members.create,lending.members.update";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("kin", true);
    }

    HttpHeaders headers(String branches, String ifMatch) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", ALL);
        h.add("X-Dev-Branch-Ids", branches);
        if (ifMatch != null) {
            h.add(HttpHeaders.IF_MATCH, ifMatch);
        }
        return h;
    }

    ResponseEntity<JsonNode> send(HttpMethod method, String path, HttpHeaders h, Object body) {
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    String member(UUID branch, String name, String phone, String nin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("full_name", name);
        body.put("phone", phone);
        body.put("id_type", nin == null ? "none" : "nin");
        body.put("national_id", nin);
        body.put("confirmed_not_duplicate", true);
        return send(HttpMethod.POST, "/api/v1/lending/members", headers("*", null), body)
                .getBody()
                .get("id")
                .asString();
    }

    ResponseEntity<JsonNode> addKin(String memberId, String name, String phone, String nin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("full_name", name);
        body.put("phone", phone);
        body.put("national_id", nin);
        body.put("relationship", "sibling");
        return send(HttpMethod.POST, "/api/v1/lending/members/" + memberId + "/next-of-kin", headers("*", null), body);
    }

    JsonNode relationships(String memberId, String branches) {
        return send(
                        HttpMethod.GET,
                        "/api/v1/lending/members/" + memberId + "/relationships",
                        headers(branches, null),
                        null)
                .getBody();
    }

    /** The fixture relationship: a borrower who is another borrower's next of kin, linked by NIN, both ways. */
    @Test
    void aNinMatchLinksAtOnceAndShowsOnBothPanels() {
        String first = member(t.headOffice(), "Test Borrower 01", "0700000001", "CMTEST0000001A");
        String second = member(t.headOffice(), "Test Borrower 02", "0700000002", null);

        ResponseEntity<JsonNode> kin = addKin(second, "Test Borrower 01", null, "cmtest0000001a");
        assertThat(kin.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(kin.getBody().get("link_method").asString()).isEqualTo("nin");
        assertThat(kin.getBody().get("link_status").asString()).isEqualTo("confirmed");
        assertThat(kin.getBody().get("linked_member_no").asString()).isEqualTo("M000001");

        JsonNode ofSecond = relationships(second, "*");
        assertThat(ofSecond.get("names_as_kin").get(0).get("linked_member_id").asString())
                .isEqualTo(first);
        JsonNode ofFirst = relationships(first, "*");
        assertThat(ofFirst.get("named_as_kin_by")).hasSize(1);
        assertThat(ofFirst.get("named_as_kin_by").get(0).get("member_no").asString())
                .isEqualTo("M000002");
        assertThat(ofFirst.get("named_as_kin_by").get(0).get("relationship").asString())
                .isEqualTo("sibling");
    }

    /** FR-MEM-07 backward: registering the member later links the next of kin that named them. */
    @Test
    void registeringTheKinAsAMemberLaterLinksThem() {
        String borrower = member(t.headOffice(), "Test Borrower 03", "0700000003", null);
        String kinId = addKin(borrower, "Test Kin 03", null, "CMTEST0000033A")
                .getBody()
                .get("id")
                .asString();

        String kinAsMember = member(t.headOffice(), "Test Kin 03", "0700000033", "CMTEST0000033A");

        JsonNode kin = relationships(borrower, "*").get("names_as_kin").get(0);
        assertThat(kin.get("id").asString()).isEqualTo(kinId);
        assertThat(kin.get("linked_member_id").asString()).isEqualTo(kinAsMember);
        assertThat(kin.get("link_status").asString()).isEqualTo("confirmed");
        String audit = TestDatabase.owner()
                .sql(
                        "SELECT action FROM audit_log WHERE entity_id = ?::uuid AND action = 'lending.member.kin_links_found'")
                .param(kinAsMember)
                .query(String.class)
                .single();
        assertThat(audit).isEqualTo("lending.member.kin_links_found");
    }

    /** FR-MEM-07: a phone-only match is suggested, not in the graph until confirmed; a rejection keeps it out. */
    @Test
    void aPhoneMatchIsSuggestedUntilConfirmed() {
        String holder = member(t.headOffice(), "Test Borrower 04", "0700000004", null);
        String borrower = member(t.headOffice(), "Test Borrower 05", "0700000005", null);
        JsonNode kin = addKin(borrower, "Test Kin 04", "+256 700 000 004", null).getBody();
        assertThat(kin.get("link_status").asString()).isEqualTo("suggested");
        assertThat(kin.get("linked_member_id").asString()).isEqualTo(holder);
        assertThat(relationships(holder, "*").get("named_as_kin_by")).isEmpty();

        String link = "/api/v1/lending/next-of-kin/" + kin.get("id").asString() + "/link";
        ResponseEntity<JsonNode> confirmed =
                send(HttpMethod.POST, link, headers("*", "\"1\""), Map.of("decision", "confirm"));
        assertThat(confirmed.getBody().get("link_status").asString()).isEqualTo("confirmed");
        assertThat(relationships(holder, "*").get("named_as_kin_by")).hasSize(1);

        ResponseEntity<JsonNode> again =
                send(HttpMethod.POST, link, headers("*", "\"2\""), Map.of("decision", "reject"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("invalid_status_transition");

        JsonNode other = addKin(borrower, "Test Kin 05", "0700000004", null).getBody();
        send(
                HttpMethod.POST,
                "/api/v1/lending/next-of-kin/" + other.get("id").asString() + "/link",
                headers("*", "\"1\""),
                Map.of("decision", "reject"));
        assertThat(relationships(holder, "*").get("named_as_kin_by")).hasSize(1);
    }

    /** One primary next of kin per member; edits need If-Match and a changed phone re-resolves the link. */
    @Test
    void editsKeepOnePrimaryAndReResolveTheLink() {
        String holder = member(t.headOffice(), "Test Borrower 06", "0700000006", null);
        String borrower = member(t.headOffice(), "Test Borrower 07", "0700000007", null);
        Map<String, Object> firstBody = new LinkedHashMap<>();
        firstBody.put("full_name", "Test Kin 06");
        firstBody.put("relationship", "spouse");
        firstBody.put("is_primary", true);
        String first = send(
                        HttpMethod.POST,
                        "/api/v1/lending/members/" + borrower + "/next-of-kin",
                        headers("*", null),
                        firstBody)
                .getBody()
                .get("id")
                .asString();
        String second =
                addKin(borrower, "Test Kin 07", null, null).getBody().get("id").asString();

        ResponseEntity<JsonNode> noIfMatch = send(
                HttpMethod.PATCH,
                "/api/v1/lending/next-of-kin/" + second,
                headers("*", null),
                Map.of("is_primary", true));
        assertThat(noIfMatch.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

        ResponseEntity<JsonNode> edited = send(
                HttpMethod.PATCH,
                "/api/v1/lending/next-of-kin/" + second,
                headers("*", "\"1\""),
                Map.of("is_primary", true, "phone", "0700000006"));
        assertThat(edited.getBody().get("is_primary").asBoolean()).isTrue();
        assertThat(edited.getBody().get("link_status").asString()).isEqualTo("suggested");
        assertThat(edited.getBody().get("linked_member_id").asString()).isEqualTo(holder);

        JsonNode list = send(
                        HttpMethod.GET,
                        "/api/v1/lending/members/" + borrower + "/next-of-kin",
                        headers("*", null),
                        null)
                .getBody()
                .get("items");
        assertThat(list.get(0).get("id").asString()).isEqualTo(second);
        assertThat(list.get(1).get("id").asString()).isEqualTo(first);
        assertThat(list.get(1).get("is_primary").asBoolean()).isFalse();
    }

    /** Chapter 7 section 7.11.11: the last next of kin of a KYC-complete member cannot be removed. */
    @Test
    void theLastNextOfKinOfAKycCompleteMemberStays() {
        String borrower = member(t.headOffice(), "Test Borrower 08", "0700000008", null);
        String kin =
                addKin(borrower, "Test Kin 08", null, null).getBody().get("id").asString();
        TestDatabase.owner()
                .sql("UPDATE lending_members SET kyc_status = 'pending_verification' WHERE id = ?::uuid")
                .param(borrower)
                .update();

        ResponseEntity<JsonNode> refused =
                send(HttpMethod.DELETE, "/api/v1/lending/next-of-kin/" + kin, headers("*", "\"1\""), null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("next_of_kin_required");

        String spare =
                addKin(borrower, "Test Kin 09", null, null).getBody().get("id").asString();
        ResponseEntity<JsonNode> removed =
                send(HttpMethod.DELETE, "/api/v1/lending/next-of-kin/" + spare, headers("*", "\"1\""), null);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** NFR-ISO-04: next of kin of an out-of-scope member are a 404; out-of-scope namers show only a number. */
    @Test
    void branchScopeHoldsOnKinAndOnThePanel() {
        String inHq = member(t.headOffice(), "Test Borrower 10", "0700000010", "CMTEST0000010A");
        String inBranchTwo = member(t.secondBranch(), "Test Borrower 11", "0700000011", null);
        String hqKin =
                addKin(inHq, "Test Kin 10", null, null).getBody().get("id").asString();
        addKin(inBranchTwo, "Test Borrower 10", null, "CMTEST0000010A");

        String branchTwo = t.secondBranch().toString();
        assertThat(send(
                                HttpMethod.GET,
                                "/api/v1/lending/members/" + inHq + "/next-of-kin",
                                headers(branchTwo, null),
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(
                                HttpMethod.PATCH,
                                "/api/v1/lending/next-of-kin/" + hqKin,
                                headers(branchTwo, "\"1\""),
                                Map.of("location", "Test Village B"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode namer = relationships(inHq, t.headOffice().toString())
                .get("named_as_kin_by")
                .get(0);
        assertThat(namer.get("in_scope").asBoolean()).isFalse();
        assertThat(namer.get("member_no").asString()).isEqualTo("M000002");
        assertThat(namer.get("full_name").isNull()).isTrue();
    }
}
