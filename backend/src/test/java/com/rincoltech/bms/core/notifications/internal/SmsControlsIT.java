package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * ADR-036 decision 13: SMS is off for every tenant until the platform operator switches it on, and the
 * daily cap is the operator's to set. The tenant has no way to raise it: no tenant route, no table
 * grant, and a database function that refuses a transaction bound to a tenant (the last two are in
 * {@code RlsIsolationIT}).
 */
class SmsControlsIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    String path(TestDatabase.Fixture t) {
        return "/api/v1/platform/tenants/" + t.tenantId() + "/sms";
    }

    Session operator(Api platform) {
        String email = Api.email("sms-operator");
        UUID id = Api.platformUser(email);
        return platform.signIn(id, email, Api.PASSWORD, null);
    }

    @Test
    void theOperatorSwitchesSmsOnAndSetsTheCapAndEveryChangeIsAudited() {
        Api platform = Api.platform(http);
        Session op = operator(platform);
        TestDatabase.Fixture t = TestDatabase.tenant("sms-ctl", true);

        // Off by default, with a small default cap; the platform ceiling and the mode are shown read only.
        JsonNode initial = platform.get(path(t), op.accessToken()).getBody();
        assertThat(initial.get("enabled").asBoolean()).isFalse();
        assertThat(initial.get("daily_cap").asInt()).isEqualTo(200);
        assertThat(initial.get("used_today").asLong()).isZero();
        assertThat(initial.get("platform_daily_cap").asInt()).isEqualTo(2000);
        assertThat(initial.get("mode").asString()).isEqualTo("off");

        ResponseEntity<JsonNode> set =
                platform.call(HttpMethod.PUT, path(t), Map.of("enabled", true, "daily_cap", 750), op.accessToken());
        assertThat(set.getStatusCode()).as("%s", set.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(set.getBody().get("enabled").asBoolean()).isTrue();
        assertThat(set.getBody().get("daily_cap").asInt()).isEqualTo(750);
        assertThat(platform.get(path(t), op.accessToken())
                        .getBody()
                        .get("daily_cap")
                        .asInt())
                .isEqualTo(750);

        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data->>'enabled_before' || ' ' || (data->>'enabled') || ' ' || (data->>'daily_cap_before')"
                                        + " || ' ' || (data->>'daily_cap') FROM platform_audit_log"
                                        + " WHERE action = 'platform.sms.controls_changed' AND tenant_id = ?")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("false true 200 750");

        // Switched off again: the cap stays as set.
        ResponseEntity<JsonNode> off =
                platform.call(HttpMethod.PUT, path(t), Map.of("enabled", false, "daily_cap", 750), op.accessToken());
        assertThat(off.getBody().get("enabled").asBoolean()).isFalse();
    }

    @Test
    void theRequestIsValidatedAndAnUnknownTenantIsNotFound() {
        Api platform = Api.platform(http);
        Session op = operator(platform);
        TestDatabase.Fixture t = TestDatabase.tenant("sms-ctl", true);
        for (Map<String, Object> bad : java.util.List.<Map<String, Object>>of(
                Map.of("enabled", true, "daily_cap", -1),
                Map.of("enabled", true, "daily_cap", 100_001),
                Map.of("daily_cap", 10),
                Map.of("enabled", true))) {
            assertThat(platform.call(HttpMethod.PUT, path(t), bad, op.accessToken())
                            .getStatusCode())
                    .as("%s", bad)
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        String unknown = "/api/v1/platform/tenants/" + UUID.randomUUID() + "/sms";
        assertThat(platform.get(unknown, op.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(platform.call(HttpMethod.PUT, unknown, Map.of("enabled", true, "daily_cap", 10), op.accessToken())
                        .getStatusCode())
                .isNotEqualTo(HttpStatus.OK);
    }

    @Test
    void aTenantUserCannotReachTheControlsAndAnonymousCannotEither() {
        TestDatabase.Fixture t = TestDatabase.tenant("sms-ctl", true);
        // No token: refused. The route is the platform host's; a tenant host has no such route for a tenant user.
        Api anonymous = Api.platform(http);
        assertThat(anonymous.get(path(t), null).getStatusCode().is2xxSuccessful())
                .isFalse();
        assertThat(anonymous
                        .call(HttpMethod.PUT, path(t), Map.of("enabled", true, "daily_cap", 100000), null)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isFalse();
        Api tenantApi = Api.tenant(http, t.slug());
        assertThat(tenantApi
                        .call(HttpMethod.PUT, path(t), Map.of("enabled", true, "daily_cap", 100000), null)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isFalse();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM platform_sms_controls WHERE tenant_id = ? AND enabled")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
