/**
 * The platform console API on the platform host, {@code BMS_PLATFORM_HOST} (chapter 7 section
 * 7.11.3): tenant creation with plan, modules, head office and first tenant admin (FR-TEN-01), module switching
 * (FR-TEN-03), subscription status and suspension (FR-TEN-05, FR-TEN-06), and the MFA reset of a
 * tenant's only admin (FR-IAM-12), and, for the operator screens of issue #407 (ADR-056), the price book (plans with their
 * prices over time), the plan and the subscription dates of a tenant, the payments the operator records (behind the
 * {@code Idempotency-Key} protocol of {@code core.operations}) and the Business page overview, and the feedback every business sent
 * (issue #447, ADR-065, through the public port of {@code core.feedback}), the referrals and their commissions (issue #464,
 * ADR-069, through the public port of {@code core.referrals}; a payment recorded for a referred business adds its commission line),
 * and, on a business's own host, the Owner's own plan
 * (issue #472, ADR-073: ask for a module, stop one, cancel, undo) with the operator's decisions and the nightly apply; the
 * Owner and the operators are told through the outbox of {@code core.notifications}. Only platform operators reach it. It changes tenants through
 * the SECURITY DEFINER platform functions of migration V2 (ADR-016), and acts inside one tenant
 * only through {@code TenantContext.callAs}, so the transaction manager still binds that tenant.
 * Public API: {@link com.rincoltech.bms.core.platform.TenantProvisioning}, the one tenant creation
 * path, which onboarding's Activate uses (ADR-024).
 */
@ApplicationModule(
        id = "core.platform",
        displayName = "Core: Platform Console",
        allowedDependencies = {
            "kernel",
            "core.tenancy",
            "core.identity",
            "core.audit",
            "core.operations",
            "core.feedback",
            "core.notifications",
            "core.referrals"
        })
package com.rincoltech.bms.core.platform;

import org.springframework.modulith.ApplicationModule;
