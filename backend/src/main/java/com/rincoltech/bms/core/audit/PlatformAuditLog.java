package com.rincoltech.bms.core.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Writes one {@code platform_audit_log} row (chapter 8 section 8.6): platform operator actions and
 * platform sign-in events. The table has no tenant policy and survives tenant deletion; tenants
 * have no endpoint that reads it. Request id and client address come from the request context.
 */
public interface PlatformAuditLog {

    /**
     * @param platformUserId the operator, or {@code null} for an unknown sign-in identity
     * @param tenantId the tenant acted on, or {@code null}
     * @param data details; identifiers under the keys of FR-AUD-05 are masked
     */
    void record(String action, UUID platformUserId, UUID tenantId, Map<String, Object> data);
}
