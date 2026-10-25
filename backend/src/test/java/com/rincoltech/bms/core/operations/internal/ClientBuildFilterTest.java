package com.rincoltech.bms.core.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** #576, ADR-088: every response names the server's build, and an app built before BMS_MIN_CLIENT_BUILD is told it is too old. */
class ClientBuildFilterTest {

    static final String SHA = "sha-test576";

    ClientBuildFilter filter(String min) {
        return filter(min, "");
    }

    ClientBuildFilter filter(String min, String ownBuilt) {
        var beans = new StaticListableBeanFactory();
        var version = new VersionController(
                beans.getBeanProvider(BuildProperties.class), beans.getBeanProvider(GitProperties.class), SHA);
        return new ClientBuildFilter(
                version, min, ownBuilt, JsonMapper.builder().build());
    }

    MockHttpServletResponse send(ClientBuildFilter filter, String path, String clientBuild) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        if (clientBuild != null) {
            request.addHeader("Bms-Client-Build", clientBuild);
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void everyResponseNamesTheServersBuild() throws Exception {
        assertThat(send(filter(""), "/api/v1/me", null).getHeader("Bms-Build")).isEqualTo(SHA);
        assertThat(send(filter(""), "/version", null).getHeader("Bms-Build")).isEqualTo(SHA);
    }

    @Test
    void withoutAMinimumEveryAppIsServed() throws Exception {
        assertThat(send(filter(""), "/api/v1/me", "sha-old0001; built=20200101000000")
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void anAppBuiltBeforeTheMinimumGets426AppTooOld() throws Exception {
        var response = send(filter("20261025120000"), "/api/v1/me", "sha-old0001; built=20261025115959");
        assertThat(response.getStatus()).isEqualTo(426);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"code\":\"app_too_old\"");
        assertThat(response.getHeader("Bms-Build")).isEqualTo(SHA);
    }

    @Test
    void anAppBuiltAtOrAfterTheMinimumIsServed() throws Exception {
        var filter = filter("20261025120000");
        assertThat(send(filter, "/api/v1/me", "sha-new0001; built=20261025120000")
                        .getStatus())
                .isEqualTo(200);
        assertThat(send(filter, "/api/v1/me", "sha-new0002; built=20261026080000")
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aRequestWithoutABuildTimeIsNeverRefused() throws Exception {
        var filter = filter("20261025120000");
        assertThat(send(filter, "/api/v1/me", null).getStatus()).isEqualTo(200);
        assertThat(send(filter, "/api/v1/me", "sha-local01").getStatus()).isEqualTo(200);
        assertThat(send(filter, "/api/v1/me", "built=garbage").getStatus()).isEqualTo(200);
    }

    @Test
    void onlyTheApiIsRefused() throws Exception {
        assertThat(send(filter("20261025120000"), "/healthz", "sha-old0001; built=20200101000000")
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    void aMinimumThatIsNotACommitTimeStopsTheStart() {
        assertThatThrownBy(() -> filter("sha-abc1234"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BMS_MIN_CLIENT_BUILD");
    }

    @Test
    void aMinimumLaterThanThisReleaseIsIgnoredSoARollbackServesItsOwnApps() throws Exception {
        // R2 set the minimum to its own time; R1, rolled back to, still reads it from .env.
        var rolledBack = filter("20261025140000", "20261024090000");
        assertThat(send(rolledBack, "/api/v1/me", "sha-r1app01; built=20261024090000")
                        .getStatus())
                .isEqualTo(200);
        var current = filter("20261025140000", "20261025140000");
        assertThat(send(current, "/api/v1/me", "sha-r1app01; built=20261024090000")
                        .getStatus())
                .isEqualTo(426);
        assertThat(send(current, "/api/v1/me", "sha-r2app01; built=20261025140000")
                        .getStatus())
                .isEqualTo(200);
    }
}
