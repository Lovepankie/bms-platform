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

    /** One row as {@link #recent} reads it: the data's text values only. */
    record Entry(java.time.Instant occurredAt, String action, UUID tenantId, Map<String, String> data) {}

    /** The latest rows of these actions, newest first, at most {@code limit} (issue #333: the email bounces). */
    java.util.List<Entry> recent(java.util.Set<String> actions, int limit);

    /**
     * True when a row of one of these actions since {@code since} carries {@code value} under {@code key} in its data (a
     * repeat delivery); the time bound keeps the search to the recent part of a log that only grows.
     */
    boolean recorded(java.util.Set<String> actions, String key, String value, java.time.Instant since);

    /**
     * The action of the newest row of these actions that carries {@code value} under {@code key} in its data, or empty
     * (issue #333: whether an email address is suppressed, by the newest of its suppress and clear entries).
     */
    java.util.Optional<String> latestAction(java.util.Set<String> actions, String key, String value);
}
