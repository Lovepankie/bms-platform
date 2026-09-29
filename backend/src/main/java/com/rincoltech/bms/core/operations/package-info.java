/**
 * Operations: the version endpoint, the "migrations at head" readiness check and the startup
 * guard that refuses an over-privileged database role (NFR-SEC-03).
 */
@ApplicationModule(
        id = "core.operations",
        displayName = "Core: Operations",
        allowedDependencies = {"kernel", "core.identity"})
package com.rincoltech.bms.core.operations;

import org.springframework.modulith.ApplicationModule;
