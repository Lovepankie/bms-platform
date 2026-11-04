package com.rincoltech.bms.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Issue #823 (FR-IAM-01, FR-IAM-04, FR-IAM-05, FR-IAM-15): a staff email typed with spaces inside it, before it, after it or
 * with a non-breaking space is the same email, on the server, at sign-in, at forgot password, in an invitation and in an
 * edit; the lockout counts every form against one address; a phone login keeps its own format check. All users, emails and
 * phones are fabricated.
 */
class StaffEmailSpacesIT extends IntegrationTest {

    /** Documentation addresses (RFC 5737), one per forgot password request, so no per-address limit crosses tests. */
    static final AtomicInteger ADDRESS = new AtomicInteger(0);

    /** A non-breaking space, as a copy and paste from a document or a chat often carries. */
    static final String NBSP = Character.toString(0x00A0);

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    Session adminSession;

    @BeforeEach
    void setUp() {
        http = Api.withoutRetries(http);
        // Two-step sign-in is optional by default (ADR-046), so every account here signs in with the password alone.
        t = TestDatabase.tenant("spaces", true);
        api = Api.tenant(http, t.slug());
        String adminEmail = Api.email("admin");
        UUID admin = Api.staff(t, adminEmail, new Role("tenant_admin", null));
        adminSession = api.signIn(admin, adminEmail, Api.PASSWORD, null);
    }

    /** The ways a person types or pastes {@code email} with a space: inside, before, after, non-breaking, a tab. */
    static List<String> spaced(String email) {
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        return List.of(
                local.substring(0, 3) + " " + local.substring(3) + "@" + domain,
                local + " @ " + domain,
                "  " + email,
                email + "  ",
                local + NBSP + "@" + domain,
                NBSP + email.toUpperCase() + NBSP,
                local + "@\t" + domain);
    }

    String storedEmail(UUID id) {
        return TestDatabase.owner()
                .sql("SELECT email FROM users WHERE id = ?")
                .param(id)
                .query(String.class)
                .single();
    }

    @Test
    void frIam04_aStaffEmailWithSpacesInsideBeforeOrAfterSignsIn() {
        String email = Api.email("cashier");
        Api.staff(t, email, new Role("cashier", t.headOffice()));

        for (String typed : spaced(email)) {
            ResponseEntity<JsonNode> signedIn = api.login(typed, Api.PASSWORD);
            assertThat(signedIn.getStatusCode()).as(typed).isEqualTo(HttpStatus.OK);
            assertThat(signedIn.getBody().get("status").asString()).as(typed).isEqualTo("signed_in");
        }
        ResponseEntity<JsonNode> wrong = api.login(spaced(email).getFirst(), "Not-The-Password-1");
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrong.getBody().get("code").asString()).isEqualTo("invalid_credentials");
    }

    /** FR-IAM-05 as before: the failures of every spaced form count on one account, and on one address of nobody. */
    @Test
    void frIam05_spacesDoNotSplitTheLock() {
        String email = Api.email("teller");
        UUID teller = Api.staff(t, email, new Role("cashier", t.headOffice()));
        List<String> forms = spaced(email);
        for (int i = 0; i < 5; i++) {
            assertThat(api.login(forms.get(i), "Wrong-Password-" + i).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<JsonNode> locked = api.login(email, Api.PASSWORD);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(locked.getBody().get("code").asString()).isEqualTo("account_locked");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action ="
                                + " 'core.auth.account_locked' AND entity_id = ?")
                        .params(t.tenantId(), teller)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        String nobody = Api.email("nobody");
        List<String> nobodyForms = spaced(nobody);
        for (int i = 0; i < 5; i++) {
            assertThat(api.login(nobodyForms.get(i), "Wrong-Password-" + i).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<JsonNode> nobodyLocked = api.login(nobodyForms.get(5), "Wrong-Password-5");
        assertThat(nobodyLocked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(nobodyLocked.getBody().get("code").asString()).isEqualTo("account_locked");
    }

    @Test
    void frIam15_forgotPasswordFindsAStaffEmailTypedWithSpaces() {
        for (String form : List.of("inside", "before", "after", "nbsp")) {
            String email = Api.email("forgot");
            Api.staff(t, email, new Role("cashier", t.headOffice()));
            int at = email.indexOf('@');
            String typed = switch (form) {
                case "inside" -> email.substring(0, 2) + " " + email.substring(2, at) + " @" + email.substring(at + 1);
                case "before" -> "   " + email;
                case "after" -> email + "   ";
                default -> email.substring(0, at) + NBSP + "@" + email.substring(at + 1);
            };
            ResponseEntity<JsonNode> asked = api.call(
                    HttpMethod.POST,
                    "/api/v1/auth/staff/password/forgot",
                    Map.of("login", typed),
                    null,
                    Map.of("X-Forwarded-For", "192.0.2." + (200 + ADDRESS.incrementAndGet() % 50)));

            assertThat(asked.getStatusCode()).as(form).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(TestDatabase.owner()
                            .sql("SELECT count(*) FROM notification_outbox"
                                    + " WHERE template_key = 'identity.password_reset' AND recipient = ?")
                            .param(email)
                            .query(Long.class)
                            .single())
                    .as(form)
                    .isEqualTo(1);
        }
    }

    @Test
    void frIam01_anInvitationSavesTheEmailWithoutSpaces() {
        String email = Api.email("invitee");
        String typed = " " + email.substring(0, 4).toUpperCase() + " "
                + email.substring(4).replace("@", NBSP + "@") + " ";
        Map<String, Object> role = new HashMap<>();
        role.put("role_key", "cashier");
        role.put("branch_id", t.headOffice());
        ResponseEntity<JsonNode> invited = api.post(
                "/api/v1/users",
                Map.of("full_name", "Test Staff Invitee", "email", typed, "roles", List.of(role)),
                adminSession.accessToken());

        assertThat(invited.getStatusCode()).as("%s", invited.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString(invited.getBody().get("user").get("id").asString());
        assertThat(invited.getBody().get("user").get("email").asString()).isEqualTo(email);
        assertThat(storedEmail(id)).isEqualTo(email);

        // The same email with other spaces is the same person: refused as a duplicate, not a second user.
        ResponseEntity<JsonNode> again = api.post(
                "/api/v1/users",
                Map.of("full_name", "Test Staff Twice", "email", email.replace("@", " @ "), "roles", List.of(role)),
                adminSession.accessToken());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("duplicate_email");
    }

    @Test
    void frIam04_anEditSavesTheEmailWithoutSpacesAndItSignsIn() {
        UUID cashier = Api.staff(t, Api.email("before"), new Role("cashier", t.headOffice()));
        String email = Api.email("after");
        String typed = NBSP + email.substring(0, 3) + " " + email.substring(3) + "\t";
        String etag = api.get("/api/v1/users/" + cashier, adminSession.accessToken())
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> edited = api.call(
                HttpMethod.PATCH,
                "/api/v1/users/" + cashier,
                Map.of("email", typed),
                adminSession.accessToken(),
                Map.of("If-Match", etag));

        assertThat(edited.getStatusCode()).as("%s", edited.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().get("email").asString()).isEqualTo(email);
        assertThat(storedEmail(cashier)).isEqualTo(email);
        assertThat(api.login(email, Api.PASSWORD).getBody().get("status").asString())
                .isEqualTo("signed_in");
    }

    /** A login with no {@code @} is still a phone, read by its own check; spaces in a phone keep their meaning. */
    @Test
    void frIam04_aPhoneLoginIsUnchanged() {
        UUID clerk = Api.staff(t, Api.email("phone"), new Role("cashier", t.headOffice()));
        String phone = "+2567000008" + String.format("%02d", ADDRESS.incrementAndGet() % 100);
        TestDatabase.owner()
                .sql("UPDATE users SET phone_e164 = ? WHERE id = ?")
                .params(phone, clerk)
                .update();
        String national = "0" + phone.substring(4);

        for (String typed : List.of(
                phone,
                national,
                national.substring(0, 4) + " " + national.substring(4, 7) + " " + national.substring(7),
                "+256 " + phone.substring(4, 7) + " " + phone.substring(7))) {
            assertThat(api.login(typed, Api.PASSWORD).getBody().get("status").asString())
                    .as(typed)
                    .isEqualTo("signed_in");
        }
        // Digits that are not a Uganda mobile number stay refused, with or without spaces.
        assertThat(api.login("0700 000", Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
