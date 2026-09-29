package com.rincoltech.bms.core.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Host parsing of chapter 7 section 7.2 (FR-TEN-02 reserved labels). */
class TenantResolutionFilterTest {

    TenantResolutionFilter filter(boolean allowHeader) {
        return new TenantResolutionFilter(new TenancyProperties("bms.example", allowHeader), null, null);
    }

    @Test
    void oneLabelUnderTheBaseDomainIsTheSlug() {
        assertThat(filter(false).slugFromHost("pilot.bms.example")).contains("pilot");
        assertThat(filter(false).slugFromHost("PILOT.BMS.EXAMPLE")).contains("pilot");
    }

    @Test
    void reservedNestedAndForeignHostsCarryNoSlug() {
        TenantResolutionFilter f = filter(false);
        assertThat(f.slugFromHost("app.bms.example")).isEmpty();
        assertThat(f.slugFromHost("api.bms.example")).isEmpty();
        assertThat(f.slugFromHost("a.b.bms.example")).isEmpty();
        assertThat(f.slugFromHost("bms.example")).isEmpty();
        assertThat(f.slugFromHost("pilot.other.example")).isEmpty();
        assertThat(f.slugFromHost("-bad.bms.example")).isEmpty();
    }

    @Test
    void theTenantHeaderIsIgnoredUnlessAllowed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("localhost");
        request.addHeader("X-Tenant", "pilot");
        assertThat(filter(false).slugFrom(request)).isEmpty();
        assertThat(filter(true).slugFrom(request)).contains("pilot");
    }

    @Test
    void theHostWinsOverTheHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("pilot.bms.example");
        request.addHeader("X-Tenant", "other");
        assertThat(filter(true).slugFrom(request)).contains("pilot");
    }
}
