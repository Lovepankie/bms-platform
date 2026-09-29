package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.tenancy.ModuleManifest;
import com.rincoltech.bms.core.tenancy.TenantModules;
import com.rincoltech.bms.kernel.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses {@code /api/v1/<module>/...} with 404 {@code module_not_enabled} when the bound tenant
 * has not switched that module on (FR-TEN-03). Module keys come from the registered
 * {@link ModuleManifest} beans; this class never names a vertical.
 */
class ModuleGateInterceptor implements HandlerInterceptor {

    private final Set<String> moduleKeys;
    private final TenantModules tenantModules;

    ModuleGateInterceptor(List<ModuleManifest> manifests, TenantModules tenantModules) {
        this.moduleKeys = manifests.stream().map(ModuleManifest::key).collect(Collectors.toUnmodifiableSet());
        this.tenantModules = tenantModules;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String[] parts = request.getRequestURI().split("/");
        // "", "api", "v1", "<module>", ...
        if (parts.length > 3 && moduleKeys.contains(parts[3]) && !tenantModules.isEnabled(parts[3])) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "module_not_enabled",
                    "Module not enabled",
                    "This module is not enabled for the tenant.");
        }
        return true;
    }
}
