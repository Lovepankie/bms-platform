package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.kernel.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A suspended tenant is read only (FR-TEN-06): every state-changing request returns 423
 * {@code tenant_suspended}, except staff sign-in, MFA, refresh and sign-out, so staff can still
 * sign in to read and export. Member portal sign-in is refused like any write.
 *
 * <p>A business whose Owner cancelled and whose end date has passed is read only the same way, with 423
 * {@code subscription_ended} (ADR-073): besides the sign-in routes, the exports that are a POST (the audit CSV and a
 * report run) still work, so the business can take its data away.
 */
class SuspendedTenantInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object status = request.getAttribute(TenantResolutionFilter.STATUS_ATTRIBUTE);
        if (!"suspended".equals(status) && !"ended".equals(status)
                || SAFE_METHODS.contains(request.getMethod())
                || staffSessionPath(request.getRequestURI())) {
            return true;
        }
        if ("ended".equals(status)) {
            if (exportPath(request.getRequestURI())) {
                return true;
            }
            throw new ApiException(
                    HttpStatus.LOCKED,
                    "subscription_ended",
                    "Subscription ended",
                    "The subscription of this business has ended, so it is read only: you can sign in, look at"
                            + " everything and download your data, but not add or change anything. Contact the platform"
                            + " operator to start it again.");
        }
        throw new ApiException(
                HttpStatus.LOCKED,
                "tenant_suspended",
                "Tenant suspended",
                "This tenant is suspended and read only. Contact the platform operator.");
    }

    static boolean exportPath(String path) {
        return path.equals("/api/v1/audit-events/export") || path.equals("/api/v1/report-runs");
    }

    static boolean staffSessionPath(String path) {
        return path.startsWith("/api/v1/auth/staff/")
                || path.equals("/api/v1/auth/refresh")
                || path.equals("/api/v1/auth/logout");
    }
}
