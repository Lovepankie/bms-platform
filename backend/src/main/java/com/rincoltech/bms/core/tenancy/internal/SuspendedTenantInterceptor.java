package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.kernel.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A suspended tenant is read only (FR-TEN-06): every state-changing request returns 423
 * {@code tenant_suspended}, except staff sign-in, MFA, refresh and sign-out, so staff can still
 * sign in to read and export, and the two reads that are a POST: asking for the business's own
 * workbook and the signed address of a file (issue #334, ADR-074). Member portal sign-in is refused
 * like any write.
 */
class SuspendedTenantInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"suspended".equals(request.getAttribute(TenantResolutionFilter.STATUS_ATTRIBUTE))
                || SAFE_METHODS.contains(request.getMethod())
                || staffSessionPath(request.getRequestURI())
                || exportPath(request.getRequestURI())) {
            return true;
        }
        throw new ApiException(
                HttpStatus.LOCKED,
                "tenant_suspended",
                "Tenant suspended",
                "This tenant is suspended and read only. Contact the platform operator.");
    }

    /** Asking for the data export and fetching a file change none of the business's data (ADR-074). */
    static boolean exportPath(String path) {
        return path.equals("/api/v1/data-export/runs")
                || DOWNLOAD_URL.matcher(path).matches();
    }

    private static final Pattern DOWNLOAD_URL = Pattern.compile("^/api/v1/documents/[0-9a-fA-F-]{36}/download-url$");

    static boolean staffSessionPath(String path) {
        return path.startsWith("/api/v1/auth/staff/")
                || path.equals("/api/v1/auth/refresh")
                || path.equals("/api/v1/auth/logout");
    }
}
