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
 */
class SuspendedTenantInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"suspended".equals(request.getAttribute(TenantResolutionFilter.STATUS_ATTRIBUTE))
                || SAFE_METHODS.contains(request.getMethod())
                || staffSessionPath(request.getRequestURI())) {
            return true;
        }
        throw new ApiException(
                HttpStatus.LOCKED,
                "tenant_suspended",
                "Tenant suspended",
                "This tenant is suspended and read only. Contact the platform operator.");
    }

    static boolean staffSessionPath(String path) {
        return path.startsWith("/api/v1/auth/staff/")
                || path.equals("/api/v1/auth/refresh")
                || path.equals("/api/v1/auth/logout");
    }
}
