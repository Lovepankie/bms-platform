/**
 * Tenancy (ADR-003): resolves the tenant from the request host, binds {@code app.tenant_id} at
 * the start of every transaction, and owns branches, enabled modules and per-tenant sequences.
 * Public API: {@link com.rincoltech.bms.core.tenancy.Branches},
 * {@link com.rincoltech.bms.core.tenancy.TenantModules},
 * {@link com.rincoltech.bms.core.tenancy.TenantSequences},
 * {@link com.rincoltech.bms.core.tenancy.ModuleManifest}.
 */
@ApplicationModule(id = "core.tenancy", displayName = "Core: Tenancy", allowedDependencies = "kernel")
package com.rincoltech.bms.core.tenancy;

import org.springframework.modulith.ApplicationModule;
