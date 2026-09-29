/**
 * Audit (FR-AUD-01 to FR-AUD-05). Every state-changing service operation calls
 * {@link com.rincoltech.bms.core.audit.AuditLog#record} inside its own transaction, so the audit
 * row commits or rolls back with the change it describes.
 */
@ApplicationModule(
        id = "core.audit",
        displayName = "Core: Audit",
        allowedDependencies = {"kernel"})
package com.rincoltech.bms.core.audit;

import org.springframework.modulith.ApplicationModule;
