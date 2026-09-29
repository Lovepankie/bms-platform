package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.PublicEndpoint;
import com.rincoltech.bms.kernel.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Enforces the route's declared permission before any business logic (chapter 8 section 8.3.3).
 * A route with no declaration is refused: an undeclared route fails closed, and the route test
 * fails the build before one can ship. A denial on a money-moving permission is audited in its
 * own transaction (FR-AUD-03).
 */
class PermissionInterceptor implements HandlerInterceptor {

    private final Supplier<Set<String>> moneyMoving;
    private final AuditLog audit;
    private final TransactionTemplate transactions;

    PermissionInterceptor(Supplier<Set<String>> moneyMoving, AuditLog audit, TransactionTemplate transactions) {
        this.moneyMoving = moneyMoving;
        this.audit = audit;
        this.transactions = transactions;
    }

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
        AuthenticatedEndpoint authenticated = method.getMethodAnnotation(AuthenticatedEndpoint.class);
        if (authenticated != null) {
            if (!authenticated.kind().equals(CurrentPrincipal.require().kind())) {
                throw denied();
            }
            return true;
        }
        RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
        if (required == null) {
            throw denied();
        }
        Principal principal = CurrentPrincipal.require();
        if (!principal.hasPermission(required.value())) {
            if (moneyMoving.get().contains(required.value()) && "staff".equals(principal.kind())) {
                transactions.executeWithoutResult(status -> audit.record(new AuditLog.Entry(
                        "core.permission.denied",
                        "core.route",
                        null,
                        null,
                        Map.of(),
                        Map.of(
                                "permission",
                                required.value(),
                                "method",
                                request.getMethod(),
                                "path",
                                request.getRequestURI()))));
            }
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
