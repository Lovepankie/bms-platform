package com.rincoltech.bms.core.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Actuator exposure (chapter 7 section 7.11.1, chapter 8): only liveness, readiness and version
 * on the application port, without details; only health and info on the internal management
 * port; everything else absent on both.
 */
class OperationsIT extends IntegrationTest {

    static final List<String> SENSITIVE = List.of(
            "env",
            "beans",
            "configprops",
            "heapdump",
            "threaddump",
            "loggers",
            "mappings",
            "metrics",
            "scheduledtasks",
            "shutdown",
            "conditions",
            "caches",
            "flyway",
            "sbom",
            "startup");

    @Autowired
    TestRestTemplate http;

    @LocalManagementPort
    int managementPort;

    @Test
    void livenessAndReadinessAreUpWithoutDetails() {
        ResponseEntity<String> live = http.getForEntity("/healthz", String.class);
        ResponseEntity<String> ready = http.getForEntity("/readyz", String.class);
        assertThat(live.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(live.getBody()).isEqualTo("{\"status\":\"UP\"}");
        assertThat(ready.getBody()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void versionReportsTheBuild() {
        ResponseEntity<String> version = http.getForEntity("/version", String.class);
        assertThat(version.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(version.getBody()).contains("\"version\"").contains("\"git_sha\"");
    }

    @Test
    void noActuatorEndpointIsReachableOnTheApplicationPort() {
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        for (String endpoint : SENSITIVE) {
            assertThat(http.getForEntity("/actuator/" + endpoint, String.class).getStatusCode())
                    .as("/actuator/%s on the application port", endpoint)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void theManagementPortServesOnlyHealthAndInfo() {
        String base = "http://localhost:" + managementPort + "/actuator/";
        // The root health lists the group names and nothing else: no components, no details.
        assertThat(http.getForEntity(base + "health", String.class).getBody())
                .contains("\"status\":\"UP\"")
                .doesNotContain("components")
                .doesNotContain("details");
        assertThat(http.getForEntity(base + "info", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        for (String endpoint : SENSITIVE) {
            assertThat(http.getForEntity(base + endpoint, String.class).getStatusCode())
                    .as("/actuator/%s on the management port", endpoint)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
