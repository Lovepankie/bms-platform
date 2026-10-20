package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.notifications.internal.PushApi.Config;
import com.rincoltech.bms.core.notifications.internal.PushApi.Device;
import com.rincoltech.bms.core.notifications.internal.PushApi.DeviceList;
import com.rincoltech.bms.core.notifications.internal.PushApi.SubscribeRequest;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's own push devices (ADR-050): register one, list them, remove one. Every operation is for the signed-in person
 * and nobody else's: the user is the principal, never a parameter, and row-level security keeps a tenant's devices from
 * every other tenant. An endpoint is the device, so it is unique in the tenant: subscribing from a device another person
 * used moves it to the caller. A person keeps at most {@value #MAX_DEVICES} devices; the least recently used goes first.
 */
@Service
class PushDevices {

    static final int MAX_DEVICES = 10;

    private final JdbcClient jdbc;
    private final VapidKeys keys;
    private final PushEndpoints endpoints;
    private final BusinessClock clock;
    private final AuditLog audit;

    PushDevices(JdbcClient jdbc, VapidKeys keys, PushEndpoints endpoints, BusinessClock clock, AuditLog audit) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.endpoints = endpoints;
        this.clock = clock;
        this.audit = audit;
    }

    Config config() {
        return new Config(keys.available(), keys.available() ? keys.publicKeyText() : null);
    }

    @Transactional
    Device subscribe(SubscribeRequest request, String userAgent) {
        if (!keys.available()) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "push_not_available",
                    "Notifications are not switched on",
                    "This server does not send notifications yet.");
        }
        validate(request);
        UUID user = CurrentPrincipal.require().userId();
        UUID id = UUID.randomUUID();
        String ua = userAgent == null ? null : cut(userAgent, 300);
        String hash = hash(request.endpoint());
        UUID before = jdbc.sql("SELECT user_id FROM push_subscriptions WHERE endpoint_hash = ?")
                .param(hash)
                .query(UUID.class)
                .optional()
                .orElse(null);
        Device device = jdbc.sql("""
                        INSERT INTO push_subscriptions (id, tenant_id, user_id, endpoint, endpoint_hash, p256dh, auth,
                                                        user_agent, created_at)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, endpoint_hash) DO UPDATE
                            SET user_id = excluded.user_id, p256dh = excluded.p256dh, auth = excluded.auth,
                                user_agent = excluded.user_agent,
                                created_at = CASE WHEN push_subscriptions.user_id <> excluded.user_id
                                                  THEN excluded.created_at ELSE push_subscriptions.created_at END,
                                last_used_at = CASE WHEN push_subscriptions.user_id <> excluded.user_id
                                                    THEN NULL ELSE push_subscriptions.last_used_at END
                        RETURNING id, user_agent, created_at, last_used_at
                        """)
                .params(
                        id,
                        user,
                        request.endpoint(),
                        hash,
                        request.keys().p256dh(),
                        request.keys().auth(),
                        ua,
                        Timestamp.from(clock.now()))
                .query((rs, n) -> device(rs))
                .single();
        // The oldest devices beyond the limit go, so a person who reinstalls often does not collect dead rows.
        jdbc.sql("""
                        DELETE FROM push_subscriptions
                         WHERE id IN (SELECT id FROM push_subscriptions WHERE user_id = ?
                                       ORDER BY greatest(created_at, coalesce(last_used_at, created_at)) DESC OFFSET ?)
                        """).params(user, MAX_DEVICES).update();
        // Audited when the device is new or changes owner, not on every quiet refresh at page load.
        if (before == null || !before.equals(user)) {
            audit.record(AuditLog.Entry.created(
                    "core.push.subscribed",
                    "core.push_subscription",
                    device.id(),
                    null,
                    Map.of("device", device.label())));
        }
        return device;
    }

    @Transactional(readOnly = true)
    DeviceList mine() {
        UUID user = CurrentPrincipal.require().userId();
        List<Device> items = jdbc.sql("SELECT id, user_agent, created_at, last_used_at FROM push_subscriptions"
                        + " WHERE user_id = ? ORDER BY created_at DESC")
                .param(user)
                .query((rs, n) -> device(rs))
                .list();
        return new DeviceList(items);
    }

    /** Removes the caller's device with this endpoint; nothing to remove is not a failure (the aim is already met). */
    @Transactional
    void unsubscribe(String endpoint) {
        UUID user = CurrentPrincipal.require().userId();
        int removed = jdbc.sql("DELETE FROM push_subscriptions WHERE user_id = ? AND endpoint_hash = ?")
                .params(user, hash(endpoint))
                .update();
        if (removed > 0) {
            audit.record(AuditLog.Entry.created(
                    "core.push.unsubscribed", "core.push_subscription", user, null, Map.of("devices", removed)));
        }
    }

    @Transactional
    void remove(UUID deviceId) {
        UUID user = CurrentPrincipal.require().userId();
        int removed = jdbc.sql("DELETE FROM push_subscriptions WHERE id = ? AND user_id = ?")
                .params(deviceId, user)
                .update();
        if (removed == 0) {
            throw ApiException.notFound();
        }
        audit.record(AuditLog.Entry.created(
                "core.push.unsubscribed", "core.push_subscription", deviceId, null, Map.of("devices", removed)));
    }

    private void validate(SubscribeRequest request) {
        if (!endpoints.allowed(request.endpoint())) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "endpoint", "invalid", "This is not the address of a push service this server can use.")));
        }
        try {
            // The browser sends unpadded base64url; padding would pass the decoder but fail the table's check (a 500).
            if (request.keys().auth().indexOf('=') >= 0
                    || request.keys().p256dh().indexOf('=') >= 0) {
                throw new IllegalArgumentException();
            }
            if (WebPushCrypto.decode(request.keys().auth()).length != 16) {
                throw new IllegalArgumentException();
            }
            WebPushCrypto.publicKey(WebPushCrypto.decode(request.keys().p256dh()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "keys", "invalid", "The subscription keys are not valid. Turn notifications off and on again.")));
        }
    }

    private static Device device(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp used = rs.getTimestamp("last_used_at");
        return new Device(
                rs.getObject("id", UUID.class),
                label(rs.getString("user_agent")),
                rs.getTimestamp("created_at").toInstant(),
                used == null ? null : used.toInstant());
    }

    /** "Chrome on Android": enough for a person to tell their devices apart, from the browser's own description. */
    static String label(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown device";
        }
        String ua = userAgent;
        String os = ua.contains("Android")
                ? "Android"
                : ua.contains("iPhone") || ua.contains("iPad")
                        ? "iPhone or iPad"
                        : ua.contains("Windows")
                                ? "Windows"
                                : ua.contains("Mac OS X") ? "Mac" : ua.contains("Linux") ? "Linux" : "a device";
        String browser = ua.contains("Edg/")
                ? "Edge"
                : ua.contains("Firefox/")
                        ? "Firefox"
                        : ua.contains("Chrome/") ? "Chrome" : ua.contains("Safari/") ? "Safari" : "Browser";
        return browser + " on " + os;
    }

    static String hash(String endpoint) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(endpoint.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no SHA-256", e);
        }
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
