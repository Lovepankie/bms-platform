package com.rincoltech.bms.core.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.kernel.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Which POSTs a read-only business may still send (FR-TEN-06, ADR-073, ADR-074 decision 7). The audit CSV and a report
 * run behave as before the data export: allowed when the subscription ended, 423 when the operator suspended the business.
 */
class SuspendedTenantInterceptorTest {

    static final String DOWNLOAD_URL = "/api/v1/documents/7f1c2b3a-0000-4000-8000-000000000001/download-url";

    final SuspendedTenantInterceptor interceptor = new SuspendedTenantInterceptor();

    MockHttpServletRequest post(String status, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setAttribute(TenantResolutionFilter.STATUS_ATTRIBUTE, status);
        return request;
    }

    String refusal(String status, String path) {
        try {
            interceptor.preHandle(post(status, path), new MockHttpServletResponse(), null);
        } catch (ApiException e) {
            return e.code();
        }
        return null;
    }

    @Test
    void theAuditExportAndAReportRunPassWhenEndedAndAreRefusedWhenSuspended() {
        for (String path : new String[] {"/api/v1/audit-events/export", "/api/v1/report-runs"}) {
            assertThat(refusal("ended", path)).as(path).isNull();
            assertThat(refusal("suspended", path)).as(path).isEqualTo("tenant_suspended");
            assertThat(refusal("active", path)).as(path).isNull();
        }
    }

    @Test
    void theDataExportRunPassesOnlyWhenEnded() {
        assertThat(refusal("ended", "/api/v1/data-export/runs")).isNull();
        assertThat(refusal("suspended", "/api/v1/data-export/runs")).isEqualTo("tenant_suspended");
    }

    /** The interceptor only passes the address on, marked; the documents module decides which file may be signed. */
    @Test
    void aDownloadAddressIsPassedOnMarkedWhenEndedAndRefusedWhenSuspended() throws Exception {
        MockHttpServletRequest ended = post("ended", DOWNLOAD_URL);
        assertThat(interceptor.preHandle(ended, new MockHttpServletResponse(), null))
                .isTrue();
        assertThat(ended.getAttribute(Documents.ENDED_BUSINESS_ATTRIBUTE)).isEqualTo(Boolean.TRUE);

        MockHttpServletRequest active = post("active", DOWNLOAD_URL);
        assertThat(interceptor.preHandle(active, new MockHttpServletResponse(), null))
                .isTrue();
        assertThat(active.getAttribute(Documents.ENDED_BUSINESS_ATTRIBUTE)).isNull();

        assertThat(refusal("suspended", DOWNLOAD_URL)).isEqualTo("tenant_suspended");
    }

    /** Issue #647: a suspended or ended business may still sign out, here and everywhere, as for a lost phone. */
    @Test
    void signingOutPassesWhenSuspendedOrEnded() {
        for (String path : List.of("/api/v1/auth/logout", "/api/v1/auth/logout-everywhere")) {
            assertThat(refusal("suspended", path)).as(path).isNull();
            assertThat(refusal("ended", path)).as(path).isNull();
        }
    }

    @Test
    void anyOtherWriteIsRefused() {
        assertThat(refusal("ended", "/api/v1/data-export/settings")).isEqualTo("subscription_ended");
        assertThat(refusal("ended", "/api/v1/documents/not-a-uuid/download-url"))
                .isEqualTo("subscription_ended");
        assertThatThrownBy(() -> interceptor.preHandle(
                        post("suspended", "/api/v1/retail/sales"), new MockHttpServletResponse(), null))
                .isInstanceOf(ApiException.class);
    }
}
