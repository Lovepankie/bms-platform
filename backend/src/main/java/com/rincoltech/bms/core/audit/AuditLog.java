package com.rincoltech.bms.core.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Writes one {@code audit_log} row in the caller's transaction (it refuses to run without one).
 * Actor, tenant, request id and client address come from the request context, never from the
 * caller. Personal identifiers in {@code before} and {@code after} are masked to their last 4
 * characters before they are stored (FR-AUD-05).
 */
public interface AuditLog {

    void record(Entry entry);

    /**
     * For events whose actor is not (or not yet) the request's principal: a sign-in attempt, an
     * invitation acceptance, or a platform operator acting on a tenant (FR-AUD-03).
     *
     * @param actorUserId the user the event is about or by; {@code null} when unknown
     * @param actorKind {@code staff}, {@code member}, {@code system} or {@code platform}
     */
    void record(Entry entry, UUID actorUserId, String actorKind);

    /**
     * @param action {@code <module>.<entity>.<verb>}, for example {@code lending.member.created}
     */
    record Entry(
            String action,
            String entityType,
            UUID entityId,
            UUID branchId,
            Map<String, Object> before,
            Map<String, Object> after) {

        public static Entry created(
                String action, String entityType, UUID entityId, UUID branchId, Map<String, Object> after) {
            return new Entry(action, entityType, entityId, branchId, Map.of(), after);
        }
    }
}
