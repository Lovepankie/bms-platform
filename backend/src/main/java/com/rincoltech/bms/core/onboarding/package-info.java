/**
 * Self-onboarding (ADR-024, docs/specs/self-onboarding-and-subscriptions.md sections 4, 8, 10 and
 * 12; build step 1): the public sign-up form and applicant page on the platform host, the
 * applications queue of the operator portal with Verify, Needs info and Reject, and Activate,
 * which creates the tenant through {@link com.rincoltech.bms.core.platform.TenantProvisioning}
 * (the same path as the platform API) and queues the activation email in the same transaction. An
 * application is not a tenant: it lives in a platform table that bms_app reaches only through
 * SECURITY DEFINER functions (migration V23, ADR-016).
 */
@ApplicationModule(
        id = "core.onboarding",
        displayName = "Core: Onboarding",
        allowedDependencies = {"kernel", "core.tenancy", "core.platform", "core.audit", "core.notifications"})
package com.rincoltech.bms.core.onboarding;

import org.springframework.modulith.ApplicationModule;
