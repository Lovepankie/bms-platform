package com.rincoltech.bms.lending.collateral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.time.LocalDate;
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
 * The collateral register (#13; FR-COL-01 to FR-COL-05, FR-AUD-01, NFR-ISO-04, chapter 8 section
 * 8.4 for release). All data fabricated; plates are in the UXX range.
 */
class CollateralIT extends IntegrationTest {

    static final String OFFICER = String.join(
            ",",
            "lending.members.read",
            "lending.members.create",
            "lending.collateral.read",
            "lending.collateral.manage",
            "lending.collateral.release_request",
            "core.approvals.read");
    static final String MANAGER = "lending.collateral.read,lending.collateral.release_approve,core.approvals.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    UUID officer;
    String member;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("coll", true);
        officer = UUID.randomUUID();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("full_name", "Test Borrower 01");
        body.put("phone", "0700000001");
        body.put("id_type", "none");
        member = send(HttpMethod.POST, "/api/v1/lending/members", headers(officer, OFFICER, "*", null), body)
                .getBody()
                .get("id")
                .asString();
    }

    HttpHeaders headers(UUID user, String permissions, String branches, String ifMatch) {
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

    ResponseEntity<JsonNode> register(String type, String reference, String custody, String location) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("member_id", member);
        body.put("collateral_type", type);
        body.put("description", "Test " + type);
        body.put("reference_no", reference);
        body.put("estimated_value_minor", 5000000);
        body.put("custody_status", custody);
        body.put("storage_location", location);
        return send(HttpMethod.POST, "/api/v1/lending/collateral", headers(officer, OFFICER, "*", null), body);
    }

    String path(String id) {
        return "/api/v1/lending/collateral/" + id;
    }

    /** FR-COL-01: a vehicle needs its plate, normalised; the same reference cannot be pledged twice. */
    @Test
    void aVehicleNeedsAPlateAndCannotBePledgedTwice() {
        ResponseEntity<JsonNode> noPlate = register("vehicle", null, null, null);
        assertThat(noPlate.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> car = register("vehicle", "uxx 001x", null, null);
        assertThat(car.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(car.getBody().get("reference_no_normalised").asString()).isEqualTo("UXX001X");
        assertThat(car.getBody().get("custody_status").asString()).isEqualTo("pledged");
        assertThat(car.getBody().get("branch_id").asString())
                .isEqualTo(t.headOffice().toString());
        assertThat(car.getBody().get("collateral_value_minor").asLong()).isEqualTo(5000000);

        ResponseEntity<JsonNode> again = register("vehicle", "UXX001X", null, null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("collateral_already_pledged");

        JsonNode detail = send(
                        HttpMethod.GET,
                        path(car.getBody().get("id").asString()),
                        headers(officer, OFFICER, "*", null),
                        null)
                .getBody();
        assertThat(detail.get("events").get(0).get("event_type").asString()).isEqualTo("registered");
    }

    /** FR-COL-02: the latest forced sale value is the collateral value; forced sale never above market. */
    @Test
    void theLatestForcedSaleValueIsTheCollateralValue() {
        String id = register("land_title", "TEST-TITLE-01", null, null)
                .getBody()
                .get("id")
                .asString();
        String valuations = path(id) + "/valuations";

        ResponseEntity<JsonNode> tooHigh = send(
                HttpMethod.POST,
                valuations,
                headers(officer, OFFICER, "*", null),
                Map.of("valued_on", "2026-01-10", "market_value_minor", 1000, "forced_sale_value_minor", 2000));
        assertThat(tooHigh.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> future = send(
                HttpMethod.POST,
                valuations,
                headers(officer, OFFICER, "*", null),
                Map.of("valued_on", LocalDate.now().plusDays(10).toString(), "market_value_minor", 1000));
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        send(
                HttpMethod.POST,
                valuations,
                headers(officer, OFFICER, "*", null),
                Map.of("valued_on", "2026-01-10", "market_value_minor", 8000000, "forced_sale_value_minor", 6000000));
        send(
                HttpMethod.POST,
                valuations,
                headers(officer, OFFICER, "*", null),
                Map.of("valued_on", "2026-02-10", "market_value_minor", 7000000, "forced_sale_value_minor", 4000000));

        JsonNode detail = send(HttpMethod.GET, path(id), headers(officer, OFFICER, "*", null), null)
                .getBody();
        assertThat(detail.get("item").get("collateral_value_minor").asLong()).isEqualTo(4000000);
        assertThat(detail.get("valuations")).hasSize(2);
    }

    /** FR-COL-03: custody moves along its allowed path; events are append only. */
    @Test
    void custodyFollowsItsTimelineAndTheTimelineIsAppendOnly() {
        String id = register("vehicle_logbook", "TEST-LOG-01", null, null)
                .getBody()
                .get("id")
                .asString();
        String events = path(id) + "/events";

        ResponseEntity<JsonNode> noLocation = send(
                HttpMethod.POST,
                events,
                headers(officer, OFFICER, "*", "\"1\""),
                Map.of("event_type", "received_into_custody"));
        assertThat(noLocation.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> received = send(
                HttpMethod.POST,
                events,
                headers(officer, OFFICER, "*", "\"1\""),
                Map.of("event_type", "received_into_custody", "location", "Test safe A"));
        assertThat(received.getBody().get("item").get("custody_status").asString())
                .isEqualTo("in_custody");
        assertThat(received.getBody().get("item").get("storage_location").asString())
                .isEqualTo("Test safe A");
        assertThat(received.getHeaders().getETag()).isEqualTo("\"2\"");

        ResponseEntity<JsonNode> dispose = send(
                HttpMethod.POST, events, headers(officer, OFFICER, "*", "\"2\""), Map.of("event_type", "disposed"));
        assertThat(dispose.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dispose.getBody().get("code").asString()).isEqualTo("invalid_status_transition");

        send(
                HttpMethod.POST,
                events,
                headers(officer, OFFICER, "*", "\"2\""),
                Map.of("event_type", "moved", "location", "Test safe B"));
        JsonNode detail = send(HttpMethod.GET, path(id), headers(officer, OFFICER, "*", null), null)
                .getBody();
        assertThat(detail.get("events")).hasSize(3);
        assertThat(detail.get("item").get("storage_location").asString()).isEqualTo("Test safe B");

        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("DELETE FROM lending_collateral_events WHERE collateral_id = ?::uuid")
                        .param(id)
                        .update())
                .rootCause()
                .hasMessageContaining("append-only");
    }

    /** FR-COL-04: release is maker-checker; the maker cannot approve; approval releases and records who collected. */
    @Test
    void releaseNeedsASecondPerson() {
        String id = register("national_id", "CMTEST0000001A", "in_custody", "Test drawer 1")
                .getBody()
                .get("id")
                .asString();

        ResponseEntity<JsonNode> requested = send(
                HttpMethod.POST,
                path(id) + "/release",
                headers(officer, OFFICER, "*", "\"1\""),
                Map.of("collected_by", "Test Borrower 01"));
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String approval = requested.getBody().get("approval_id").asString();
        JsonNode pending = send(HttpMethod.GET, path(id), headers(officer, OFFICER, "*", null), null)
                .getBody();
        assertThat(pending.get("item").get("custody_status").asString()).isEqualTo("in_custody");

        ResponseEntity<JsonNode> self = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/approve",
                headers(officer, OFFICER + ",lending.collateral.release_approve", "*", null),
                Map.of("note", "checked"));
        assertThat(self.getStatusCode()).isNotEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> approved = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/approve",
                headers(UUID.randomUUID(), MANAGER, "*", null),
                Map.of("note", "checked"));
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode detail = send(HttpMethod.GET, path(id), headers(officer, OFFICER, "*", null), null)
                .getBody();
        assertThat(detail.get("item").get("custody_status").asString()).isEqualTo("released");
        JsonNode last = detail.get("events").get(detail.get("events").size() - 1);
        assertThat(last.get("event_type").asString()).isEqualTo("released");
        assertThat(last.get("counterparty_name").asString()).isEqualTo("Test Borrower 01");
        assertThat(last.get("approval_request_id").asString()).isEqualTo(approval);

        // Released, the same reference may be pledged again (FR-COL-01 interim rule).
        assertThat(register("national_id", "CMTEST0000001A", null, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    /** FR-COL-05: a type the tenant switched off cannot be registered. */
    @Test
    void aDisabledTypeCannotBeRegistered() {
        TestDatabase.owner()
                .sql(
                        "INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?, '{\"disabled_collateral_types\": [\"national_id\"]}')")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        ResponseEntity<JsonNode> refused = register("national_id", "CMTEST0000002A", null, null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("collateral_type_disabled");
        assertThat(register("household_item", null, null, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** NFR-ISO-04: branch scope on list, detail and change. */
    @Test
    void branchScopeHolds() {
        String id = register("other", "TEST-OTHER-01", null, null)
                .getBody()
                .get("id")
                .asString();
        String branchTwo = t.secondBranch().toString();
        assertThat(send(HttpMethod.GET, path(id), headers(officer, OFFICER, branchTwo, null), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.GET, "/api/v1/lending/collateral", headers(officer, OFFICER, branchTwo, null), null)
                        .getBody()
                        .get("items"))
                .isEmpty();
        assertThat(send(
                                HttpMethod.GET,
                                "/api/v1/lending/collateral?member_id=" + member,
                                headers(officer, OFFICER, "*", null),
                                null)
                        .getBody()
                        .get("items"))
                .hasSize(1);
        assertThat(send(
                                HttpMethod.PATCH,
                                path(id),
                                headers(officer, OFFICER, branchTwo, "\"1\""),
                                Map.of("description", "Test changed"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
