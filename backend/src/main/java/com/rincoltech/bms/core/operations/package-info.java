/**
 * Operations: the version endpoint, the "migrations at head" readiness check and the startup
 * guard that refuses an over-privileged database role (NFR-SEC-03), and the one {@code Idempotency-Key}
 * implementation every money-moving route uses (chapter 7 section 7.8).
 */
@ApplicationModule(
        id = "core.operations",
        displayName = "Core: Operations",
        allowedDependencies = {"kernel", "core.identity"})
package com.rincoltech.bms.core.operations;

import org.springframework.modulith.ApplicationModule;
