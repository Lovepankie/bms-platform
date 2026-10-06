/**
 * The platform console API on the platform host, {@code BMS_PLATFORM_HOST} (chapter 7 section
 * 7.11.3): tenant creation with plan, modules, head office and first tenant admin (FR-TEN-01), module switching
 * (FR-TEN-03), subscription status and suspension (FR-TEN-05, FR-TEN-06), and the MFA reset of a
 * tenant's only admin (FR-IAM-12). Only platform operators reach it. It changes tenants through
 * the SECURITY DEFINER platform functions of migration V2 (ADR-016), and acts inside one tenant
 * only through {@code TenantContext.callAs}, so the transaction manager still binds that tenant.
 * Public API: {@link com.rincoltech.bms.core.platform.TenantProvisioning}, the one tenant creation
 * path, which onboarding's Activate uses (ADR-024).
 */
@ApplicationModule(
        id = "core.platform",
        displayName = "Core: Platform Console",
        allowedDependencies = {"kernel", "core.tenancy", "core.identity", "core.audit"})
package com.rincoltech.bms.core.platform;

import org.springframework.modulith.ApplicationModule;
