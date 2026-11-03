package com.rincoltech.bms.core.onboarding.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The hostname check of the Activate form over HTTP (issue #155): an operator asks whether the business's own address
 * answers, the host is built from the slug and the tenant host pattern only, and an invalid slug or a caller who is not
 * signed in is refused before anything is asked. The test host pattern is under the reserved .test name, which never
 * resolves, so the address does not answer here; each answer the check can give is proven in {@link HostnameCheckTest}.
 */
class HostnameCheckIT extends IntegrationTest {

    static final String PATH = "/api/v1/platform/applications/hostname-check?slug=";

    @Autowired
    TestRestTemplate http;

    Api platform;
    Session operator;

    @BeforeEach
    void setUp() {
        platform = Api.platform(http);
        String email = Api.email("hostcheck");
        UUID operatorId = Api.platformUser(email);
        operator = platform.signIn(operatorId, email, Api.PASSWORD, null);
    }

    @Test
    void asksTheBusinessesOwnHostAndSaysWhatHappened() {
        ResponseEntity<JsonNode> check = platform.get(PATH + "Test-Shop-155", operator.accessToken());

        assertThat(check.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(check.getBody().path("host").asString()).isEqualTo("test-shop-155-bms-staging.rincoltech.test");
        assertThat(check.getBody().path("outcome").asString()).isEqualTo("not_found");
    }

    @Test
    void refusesAnInvalidSlug() {
        ResponseEntity<JsonNode> invalid = platform.get(PATH + "-bad-", operator.accessToken());

        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().path("code").asString()).isEqualTo("invalid_slug");
    }

    @Test
    void refusesACallerWhoIsNotSignedIn() {
        assertThat(platform.get(PATH + "test-shop-155", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
