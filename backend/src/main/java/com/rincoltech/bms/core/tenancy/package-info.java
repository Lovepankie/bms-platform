/**
 * Tenancy (ADR-003): resolves the tenant from the request host, binds {@code app.tenant_id} at
 * the start of every transaction, refuses writes for a suspended tenant (FR-TEN-06), and owns
 * branches (FR-BR-01), settings (FR-TEN-08), plan limits (FR-TEN-04), enabled modules and
 * per-tenant sequences. Public API: {@link com.rincoltech.bms.core.tenancy.Branches},
 * {@link com.rincoltech.bms.core.tenancy.TenantModules},
 * {@link com.rincoltech.bms.core.tenancy.TenantSequences},
 * {@link com.rincoltech.bms.core.tenancy.TenantSettings},
 * {@link com.rincoltech.bms.core.tenancy.PlanLimits},
 * {@link com.rincoltech.bms.core.tenancy.PlatformHost},
 * {@link com.rincoltech.bms.core.tenancy.BranchDeactivationGuard},
 * {@link com.rincoltech.bms.core.tenancy.BranchProvisioning},
 * {@link com.rincoltech.bms.core.tenancy.ModuleManifest}.
 */
@ApplicationModule(
        id = "core.tenancy",
        displayName = "Core: Tenancy",
        allowedDependencies = {"kernel", "core.audit"})
package com.rincoltech.bms.core.tenancy;

import org.springframework.modulith.ApplicationModule;
