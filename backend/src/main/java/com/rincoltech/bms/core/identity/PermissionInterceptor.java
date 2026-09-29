package com.rincoltech.bms.core.identity;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.PublicEndpoint;
import com.rincoltech.bms.kernel.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces the route's declared permission before any business logic (chapter 8 section 8.3.3).
 * A route with no declaration is refused: an undeclared route fails closed, and the route test
 * fails the build before one can ship.
 */
class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Framework handlers (the OpenAPI document, the error page) are outside this rule; every
        // handler written in this code base is inside it.
        if (!(handler instanceof HandlerMethod method)
                || !method.getBeanType().getPackageName().startsWith("com.rincoltech.bms")) {
            return true;
        }
        if (method.hasMethodAnnotation(PublicEndpoint.class)) {
            return true;
        }
        RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
        if (required == null) {
            throw denied();
        }
        Principal principal = CurrentPrincipal.require();
        if (!principal.hasPermission(required.value())) {
            throw denied();
        }
        return true;
    }

    private static ApiException denied() {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                "permission_denied",
                "Permission denied",
                "You do not have permission to perform this action.");
    }
}
