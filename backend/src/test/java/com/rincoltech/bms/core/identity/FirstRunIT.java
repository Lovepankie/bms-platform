package com.rincoltech.bms.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The first run of a new user (issues #19 and #86, ADR-025): accepting an invitation signs the
 * user in through the normal sign-in rules, and guided tour progress is remembered per user on the
 * server. All users and contacts are fabricated.
 */
class FirstRunIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    Session adminSession;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("firstrun", true, true);
        api = Api.tenant(http, t.slug());
        String adminEmail = Api.email("admin");
        UUID admin = Api.staff(t, adminEmail, new Role("tenant_admin", null));
        adminSession = api.signIn(admin, adminEmail, Api.PASSWORD, null);
    }

    String invite(String email, String role) {
        Map<String, Object> assignment = new java.util.HashMap<>();
        assignment.put("role_key", role);
        assignment.put("branch_id", role.equals("tenant_admin") ? null : t.headOffice());
        ResponseEntity<JsonNode> invited = api.post(
                "/api/v1/users",
                Map.of("full_name", "Test Staff Invitee", "email", email, "roles", List.of(assignment)),
                adminSession.accessToken());
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String url = invited.getBody().get("invitation").get("url").asString();
        return url.substring(url.indexOf("#token=") + 7);
    }

    ResponseEntity<JsonNode> accept(String token) {
        return api.post(
                "/api/v1/auth/staff/invitations/accept", Map.of("token", token, "password", Api.PASSWORD), null);
    }

    ResponseEntity<JsonNode> recordTour(String tourId, Object body, String token) {
        return api.call(HttpMethod.PUT, "/api/v1/me/tours/" + tourId, body, token);
    }

    /** FR-IAM-06, issue #86: a role that requires a second factor goes to enrolment, not past it. */
    @Test
    void acceptingAsARoleThatRequiresTheFactorLeadsToEnrolmentNotToASession() {
        ResponseEntity<JsonNode> accepted = accept(invite(Api.email("owner2"), "tenant_admin"));

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody().get("status").asString()).isEqualTo("mfa_enrolment_required");
        assertThat(accepted.getBody().get("access_token").isNull()).isTrue();
        assertThat(accepted.getHeaders().get("Set-Cookie")).isNull();
        String mfaToken = accepted.getBody().get("mfa_token").asString();
        ResponseEntity<JsonNode> enrolment =
                api.post("/api/v1/auth/staff/mfa/enrol", Map.of("mfa_token", mfaToken), null);
        assertThat(enrolment.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(enrolment.getBody().get("otpauth_uri").asString()).startsWith("otpauth://totp/");
    }

    /** Issue #86: a refused acceptance (a link used twice) signs nobody in. */
    @Test
    void aUsedLinkSignsNobodyIn() {
        String token = invite(Api.email("seller"), "retail_sales");
        assertThat(accept(token).getBody().get("status").asString()).isEqualTo("signed_in");

        ResponseEntity<JsonNode> again = accept(token);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("invitation_invalid");
        assertThat(again.getHeaders().get("Set-Cookie")).isNull();
    }

    /** Issue #19: tour progress follows the user to any device and belongs to that user only. */
    @Test
    void tourProgressIsRememberedPerUser() {
        String sellerEmail = Api.email("seller2");
        UUID seller = Api.staff(t, sellerEmail, new Role("retail_sales", t.headOffice()));
        Session sellerSession = api.signIn(seller, sellerEmail, Api.PASSWORD, null);
        assertThat(api.get("/api/v1/me", sellerSession.accessToken())
                        .getBody()
                        .get("tours")
                        .size())
                .isZero();

        assertThat(recordTour("retail-sales", Map.of("status", "completed", "version", 1), sellerSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(recordTour("admin-welcome", Map.of("status", "dismissed", "version", 2), sellerSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        // A second write of the same tour replaces the first.
        recordTour("retail-sales", Map.of("status", "completed", "version", 3), sellerSession.accessToken());

        // A new session (another device) sees the same progress.
        Session otherDevice = api.signIn(seller, sellerEmail, Api.PASSWORD, null);
        JsonNode tours =
                api.get("/api/v1/me", otherDevice.accessToken()).getBody().get("tours");
        assertThat(tours.size()).isEqualTo(2);
        assertThat(tours.get("retail-sales").get("status").asString()).isEqualTo("completed");
        assertThat(tours.get("retail-sales").get("version").asInt()).isEqualTo(3);
        assertThat(tours.get("admin-welcome").get("status").asString()).isEqualTo("dismissed");
        assertThat(tours.get("admin-welcome").get("at").asString()).isNotBlank();

        // The admin's own progress is untouched.
        assertThat(api.get("/api/v1/me", adminSession.accessToken())
                        .getBody()
                        .get("tours")
                        .size())
                .isZero();
    }

    /** Issue #19: the tour id and the body are checked; an anonymous caller is refused. */
    @Test
    void badTourWritesAreRefused() {
        String token = adminSession.accessToken();
        assertThat(recordTour("Not_A_Tour", Map.of("status", "completed", "version", 1), token)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("tour_id_invalid");
        assertThat(recordTour("admin-welcome", Map.of("status", "finished", "version", 1), token)
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();
        assertThat(recordTour("admin-welcome", Map.of("status", "completed", "version", 0), token)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(recordTour("admin-welcome", Map.of("status", "completed", "version", 1), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
