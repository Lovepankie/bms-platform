package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Web Push as a channel of the outbox (ADR-050). A row is addressed to one person ({@code user:<id>}) and the channel sends
 * it to every device that person has subscribed: a person with a phone and a laptop hears on both. The dispatcher has
 * already rendered the title and body from the push template and honoured the row's retry and expiry rules; this channel
 * adds the devices, in a short tenant transaction of its own (the same stored-tenant step as SMS), the encrypted send outside
 * any transaction, and the clean-up of devices the push service says are gone.
 *
 * <p>Answers: sent when at least one device took the message; refused (never retried) when the person has no device left,
 * the tenant is inactive or the row is malformed; a clean failure, which the dispatcher retries with backoff and then marks
 * failed, when devices exist and every one answered with a temporary problem. A device that answers 404 or 410 is deleted;
 * one the push service rejects (a key that no longer matches after a rotation, say) is kept, so a misconfiguration never
 * wipes the devices, and the row is refused. Logs carry the outbox id and counts only.
 */
final class PushOutboxChannel implements OutboxChannel {

    static final String USER_PREFIX = "user:";
    /** A message is worth delivering for a day at most, whatever the row's own expiry says. */
    static final int MAX_TTL_SECONDS = 24 * 3600;

    static final int DEFAULT_TTL_SECONDS = 6 * 3600;
    static final int TITLE_MAX = 80;
    static final int BODY_MAX = 200;

    private static final Logger log = LoggerFactory.getLogger(PushOutboxChannel.class);

    private final VapidKeys keys;
    private final PushSender sender;
    private final TenantJobs tenantJobs;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final BusinessClock clock;

    PushOutboxChannel(
            VapidKeys keys,
            PushSender sender,
            TenantJobs tenantJobs,
            JdbcClient jdbc,
            ObjectMapper mapper,
            BusinessClock clock) {
        this.keys = keys;
        this.sender = sender;
        this.tenantJobs = tenantJobs;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String channel() {
        return Outbox.PUSH;
    }

    @Override
    public boolean enabled() {
        return keys.available();
    }

    private record Device(UUID id, PushSender.Target target) {}

    @Override
    public Result send(Delivery delivery) throws SendFailure {
        RowContext row = delivery.context();
        UUID tenantId = row.tenantId();
        UUID userId = userOf(delivery.recipient());
        if (tenantId == null || userId == null) {
            return Result.refused("malformed_row");
        }
        List<Device> devices;
        try {
            devices = tenantJobs.callForTenant(tenantId, () -> devicesOf(userId));
        } catch (TenantJobs.InactiveTenantException e) {
            return Result.refused("tenant_inactive");
        }
        if (devices.isEmpty()) {
            return Result.refused("no_device");
        }
        String payload = payload(delivery, row);
        int ttl = ttlSeconds(row.expiresAt());
        int sent = 0;
        int temporary = 0;
        int gone = 0;
        int rejected = 0;
        for (Device device : devices) {
            PushSender.Outcome outcome = sender.send(device.target(), payload, ttl);
            switch (outcome) {
                case SENT -> {
                    sent++;
                    touch(tenantId, device.id());
                }
                case GONE -> {
                    gone++;
                    forget(tenantId, device.id());
                }
                case REJECTED -> rejected++;
                case TRANSIENT -> temporary++;
            }
        }
        log.info(
                "push row {}: devices={} sent={} temporary={} rejected={} removed={}",
                row.outboxId(),
                devices.size(),
                sent,
                temporary,
                rejected,
                gone);
        if (sent > 0) {
            return Result.SENT;
        }
        if (temporary > 0) {
            // A clean failure: the dispatcher retries at its backoff and, after the third, marks the row failed.
            throw new SendFailure("the push service did not accept the message");
        }
        return Result.refused("no_deliverable_device");
    }

    private List<Device> devicesOf(UUID userId) {
        return jdbc.sql("SELECT id, endpoint, p256dh, auth FROM push_subscriptions WHERE user_id = ?"
                        + " ORDER BY coalesce(last_used_at, created_at) DESC")
                .param(userId)
                .query((rs, n) -> new Device(
                        rs.getObject("id", UUID.class),
                        new PushSender.Target(rs.getString("endpoint"), rs.getString("p256dh"), rs.getString("auth"))))
                .list();
    }

    private void touch(UUID tenantId, UUID deviceId) {
        Timestamp now = Timestamp.from(clock.now());
        quietly(
                tenantId,
                () -> jdbc.sql("UPDATE push_subscriptions SET last_used_at = ? WHERE id = ?")
                        .params(now, deviceId)
                        .update());
    }

    private void forget(UUID tenantId, UUID deviceId) {
        quietly(
                tenantId,
                () -> jdbc.sql("DELETE FROM push_subscriptions WHERE id = ?")
                        .param(deviceId)
                        .update());
    }

    /** Bookkeeping after a send must never undo it: the message went, and a failure here only leaves a stale row. */
    private void quietly(UUID tenantId, java.util.function.Supplier<Integer> work) {
        try {
            tenantJobs.callForTenant(tenantId, work);
        } catch (RuntimeException e) {
            log.warn("push bookkeeping failed: {}", e.getClass().getSimpleName());
        }
    }

    private int ttlSeconds(Instant expiresAt) {
        if (expiresAt == null) {
            return DEFAULT_TTL_SECONDS;
        }
        long seconds = Duration.between(clock.now(), expiresAt).toSeconds();
        return (int) Math.max(60, Math.min(MAX_TTL_SECONDS, seconds));
    }

    private String payload(Delivery delivery, RowContext row) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("title", cut(delivery.subject(), TITLE_MAX));
        body.put("body", cut(delivery.text(), BODY_MAX));
        body.put("path", safePath(row.params().get("path")));
        body.put("tag", delivery.idempotencyKey());
        return mapper.writeValueAsString(body);
    }

    /** An app route only: one leading slash, no scheme, no second slash, no backslash, no control character. */
    static String safePath(String path) {
        if (path == null
                || path.length() > 200
                || !path.startsWith("/")
                || path.startsWith("//")
                || path.chars().anyMatch(c -> c == '\\' || c < 0x20)) {
            return "/staff";
        }
        return path;
    }

    private static String cut(String text, int max) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max - 1) + "...";
    }

    private static UUID userOf(String recipient) {
        if (recipient == null || !recipient.startsWith(USER_PREFIX)) {
            return null;
        }
        try {
            return UUID.fromString(recipient.substring(USER_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
