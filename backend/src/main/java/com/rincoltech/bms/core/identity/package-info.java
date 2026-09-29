/**
 * Identity and access (chapter 7 section 7.4, chapter 8, ADR-014): staff and platform sign-in
 * with argon2id passwords and TOTP second factor, recovery codes and the admin MFA reset,
 * server-side revocable sessions with rotating refresh tokens, the authentication filter and the
 * route permission check, staff invitations, role assignments with branch scope, {@code /me}.
 * The principal types themselves live in the kernel, so every module can read them. Public API:
 * {@link com.rincoltech.bms.core.identity.TenantAdmins}, for the platform console, and
 * {@link com.rincoltech.bms.core.identity.ProvisioningKeys}, for provisioning a host.
 */
@ApplicationModule(
        id = "core.identity",
        displayName = "Core: Identity and Access",
        allowedDependencies = {"kernel", "core.tenancy", "core.audit", "core.notifications"})
package com.rincoltech.bms.core.identity;

import org.springframework.modulith.ApplicationModule;
