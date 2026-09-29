package com.rincoltech.bms.core.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Slug from the host or, in development only, the X-Tenant header (chapter 7 section 7.2). */
class TenantResolutionFilterTest {

    TenantResolutionFilter filter(boolean allowHeader) {
        return new TenantResolutionFilter(
                new TenancyProperties(
                        "{slug}-bms-staging.rincoltech.com", "bms-staging.rincoltech.com", allowHeader, null, null),
                null,
                null);
    }

    MockHttpServletRequest request(String host, String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName(host);
        if (header != null) {
            request.addHeader("X-Tenant", header);
        }
        return request;
    }

    @Test
    void theSlugComesFromTheTenantHost() {
        assertThat(filter(false).slugFrom(request("demo-bms-staging.rincoltech.com", null)))
                .contains("demo");
        assertThat(filter(false).slugFrom(request("bms-staging.rincoltech.com", null)))
                .isEmpty();
    }

    @Test
    void theTenantHeaderIsIgnoredUnlessAllowed() {
        assertThat(filter(false).slugFrom(request("localhost", "demo"))).isEmpty();
        assertThat(filter(true).slugFrom(request("localhost", "demo"))).contains("demo");
        assertThat(filter(true).slugFrom(request("localhost", "Demo"))).isEmpty();
    }

    @Test
    void theHostWinsOverTheHeader() {
        assertThat(filter(true).slugFrom(request("demo-bms-staging.rincoltech.com", "other")))
                .contains("demo");
    }
}
