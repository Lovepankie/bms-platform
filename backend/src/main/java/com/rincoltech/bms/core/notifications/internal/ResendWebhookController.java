package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.PublicEndpoint;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /api/v1/platform/webhooks/resend} (issue #333, ADR-117 decision 8): Resend's events about the emails it
 * sent. Public by design: Resend calls it, with no session. It trusts nothing it has not checked. The body must carry a
 * Svix signature made with {@code BMS_RESEND_WEBHOOK_SECRET}, over the message id, its timestamp and the exact body, and
 * the timestamp must be within five minutes of now; anything else gets 401 and writes nothing. With the secret unset the
 * route answers 503 and logs one warning (issue #767): it never accepts an unsigned event.
 *
 * <p>A bounce ({@code email.bounced}), a complaint ({@code email.complained}) or a send Resend refused because the address
 * is on its suppression list ({@code email.suppressed}) becomes one row of the platform audit log
 * ({@code platform.email.bounced}, {@code platform.email.complained}, {@code platform.email.suppressed}), with the outbox
 * row id and the business from the tags the sender put on the email, the recipient's domain only (never the address)
 * and the bounce type. No migration: the application role already writes that log. A repeat delivery of the same
 * message id writes nothing more. Other event types are acknowledged and ignored. The operator's outbox page lists
 * these rows.
 *
 * <p>Only events whose {@code env} tag is this environment's are recorded: staging and production share the Resend
 * account, so the others (and any event with no tag, such as an email sent by SMTP or Resend's test event) are answered
 * 200 and dropped. A hard bounce, a complaint and a suppressed send also carry {@code recipient_hash}, the address's
 * keyed hash from {@link EmailSuppressions}, which stops later sends to it (ADR-117 decision 9); a soft bounce does not.
 *
 * <p>A bounce or a complaint also marks its own outbox row failed, with {@code last_error} {@code bounced} or
 * {@code complained} (issue #767, migration V138): the definer function finds the row by the {@code outbox_id} and
 * {@code tenant_id} tags together, so one business's event never touches another's row. The body is read as a
 * {@link JsonNode}, a lenient type, because Resend adds fields the strict request mapping would refuse.
 */
@RestController
@RequestMapping("/api/v1/platform/webhooks")
@Hidden
class ResendWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ResendWebhookController.class);

    static final String BOUNCED = "platform.email.bounced";
    static final String COMPLAINED = "platform.email.complained";
    /** Resend did not send the email because the address is on its suppression list (an earlier bounce or complaint). */
    static final String SUPPRESSED = "platform.email.suppressed";

    static final Set<String> ACTIONS = Set.of(BOUNCED, COMPLAINED, SUPPRESSED);
    /** How far a signed timestamp may be from now, either way, before the message is treated as a replay. */
    static final Duration TOLERANCE = Duration.ofMinutes(5);
    /** Resend's events are small; anything larger is not one. */
    static final int MAX_BODY = 256 * 1024;

    private final PlatformAuditLog audit;
    private final ObjectMapper mapper;
    private final BusinessClock clock;
    private final byte[] secret;
    private final String environment;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final EmailSuppressions suppressions;
    /** Set once the first call without a secret is logged, so Resend's retries never fill the log. */
    private final AtomicBoolean unsetLogged = new AtomicBoolean();

    ResendWebhookController(
            PlatformAuditLog audit,
            ObjectMapper mapper,
            BusinessClock clock,
            org.springframework.jdbc.core.simple.JdbcClient jdbc,
            EmailSuppressions suppressions,
            @Value("${bms.notifications.mail.resend-webhook-secret:}") String secret,
            @Value("${bms.environment:}") String environment) {
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
        this.jdbc = jdbc;
        this.suppressions = suppressions;
        this.secret = decodeSecret(secret);
        this.environment = ResendEmailChannel.environmentTag(environment);
    }

    /** The key of a {@code whsec_<base64>} secret; empty when unset or not in that shape (the route is then off). */
    static byte[] decodeSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return new byte[0];
        }
        String value = secret.strip();
        try {
            return Base64.getDecoder().decode(value.startsWith("whsec_") ? value.substring(6) : value);
        } catch (IllegalArgumentException e) {
            log.warn("BMS_RESEND_WEBHOOK_SECRET is not a whsec_ secret; the Resend webhook is off (value not logged)");
            return new byte[0];
        }
    }

    @PostMapping("/resend")
    @PublicEndpoint
    @Transactional
    ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        if (secret.length == 0) {
            // Never accepts an unsigned event. 503, not 404, so Resend keeps retrying until the operator sets the
            // secret, and one warning in the log rather than one per retry (issue #767).
            if (unsetLogged.compareAndSet(false, true)) {
                log.warn("Resend webhook called but BMS_RESEND_WEBHOOK_SECRET is unset; answering 503 until it is set");
            }
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "webhook_not_configured",
                    "Not configured",
                    "This webhook has no signing secret on this host.");
        }
        byte[] raw;
        try (InputStream in = request.getInputStream()) {
            raw = in.readNBytes(MAX_BODY + 1);
        }
        if (raw.length > MAX_BODY) {
            throw new ApiException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "body_too_large", "Too large", "This is not a Resend event.");
        }
        String body = new String(raw, StandardCharsets.UTF_8);
        String id = request.getHeader("svix-id");
        if (!verified(
                secret,
                id,
                request.getHeader("svix-timestamp"),
                request.getHeader("svix-signature"),
                body,
                clock.now())) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "bad_signature", "Not signed", "The signature does not check.");
        }
        JsonNode event;
        try {
            event = mapper.readTree(body);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_event", "Not an event", "The body is not an event.");
        }
        String type = event.path("type").asString("");
        String action = switch (type) {
            case "email.bounced" -> BOUNCED;
            case "email.complained" -> COMPLAINED;
            case "email.suppressed" -> SUPPRESSED;
            default -> null;
        };
        if (action == null) {
            return ResponseEntity.noContent().build();
        }
        JsonNode data = event.path("data");
        Map<String, String> tags = tags(data.path("tags"));
        // Staging and production share the Resend account, and Resend sends every event to every endpoint: an email of
        // another environment (or with no environment tag) is acknowledged and not recorded here.
        if (!environment.equals(tags.get("env"))) {
            log.debug("resend {} of another environment, or with no env tag, dropped", type);
            return ResponseEntity.ok().build();
        }
        // One delivery at a time per message id, so a slow first call and Resend's retry never both write; only the
        // last week is searched, since Resend stops retrying long before that.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))")
                .param("resend:" + id)
                .query()
                .singleRow();
        if (audit.recorded(ACTIONS, "svix_id", id, clock.now().minus(Duration.ofDays(7)))) {
            return ResponseEntity.noContent().build();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("svix_id", id);
        details.put("outbox_id", uuidOrNull(tags.get("outbox_id")));
        details.put("template", safe(tags.get("template")));
        details.put("recipient_domain", domain(data.path("to")));
        details.put("bounce_type", safe(data.path("bounce").path("type").asString(null)));
        details.put("bounce_subtype", safe(data.path("bounce").path("subType").asString(null)));
        // A hard bounce, a complaint or a suppressed send stops later sends to the address (ADR-117 decision 9): its
        // keyed hash, never the address. A soft bounce is a full mailbox or a passing fault and suppresses nothing.
        String address = firstAddress(data.path("to"));
        if (address.contains("@") && (!action.equals(BOUNCED) || "Permanent".equals(details.get("bounce_type")))) {
            details.put(EmailSuppressions.KEY, suppressions.hash(address));
        }
        details.values().removeIf(java.util.Objects::isNull);
        UUID tenant = uuidOrNullAsUuid(tags.get("tenant_id"));
        audit.record(action, null, tenant, details);
        // JUSTIFICATION-A3: issue #767's one step, the row itself marked failed (by id and business, from the tags).
        UUID outboxId = uuidOrNullAsUuid(tags.get("outbox_id"));
        String row = "none";
        if (outboxId != null && !action.equals(SUPPRESSED)) {
            String reason = action.equals(BOUNCED) ? "bounced" : "complained";
            row = jdbc.sql("SELECT notification_outbox_mark_undelivered(?, CAST(? AS uuid), ?, ?)")
                    .params(outboxId, tenant, reason, Timestamp.from(clock.now()))
                    .query(String.class)
                    .single();
        }
        // The recipient's domain only, never the address.
        log.info("resend {}: outbox={} domain={} row={}", type, outboxId, details.get("recipient_domain"), row);
        return ResponseEntity.noContent().build();
    }

    /**
     * The Svix check: {@code v1,<base64 HMAC-SHA256>} over {@code <id>.<timestamp>.<body>} with the secret, one of the
     * space separated signatures matching in constant time, and the timestamp within {@link #TOLERANCE} of now.
     */
    static boolean verified(byte[] key, String id, String timestamp, String signatures, String body, Instant now) {
        if (key.length == 0 || id == null || timestamp == null || signatures == null || id.length() > 200) {
            return false;
        }
        try {
            // A number out of Instant's range (a forged timestamp) is a refusal, never a 500.
            if (Duration.between(Instant.ofEpochSecond(Long.parseLong(timestamp.strip())), now)
                            .abs()
                            .compareTo(TOLERANCE)
                    > 0) {
                return false;
            }
        } catch (NumberFormatException | java.time.DateTimeException | ArithmeticException e) {
            return false;
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            expected = mac.doFinal((id + "." + timestamp.strip() + "." + body).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
        for (String part : signatures.strip().split(" ")) {
            int comma = part.indexOf(',');
            if (comma < 0 || !part.substring(0, comma).equals("v1")) {
                continue;
            }
            try {
                if (MessageDigest.isEqual(expected, Base64.getDecoder().decode(part.substring(comma + 1)))) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // Not base64: not a match.
            }
        }
        return false;
    }

    /** Tags as Resend gives them in an event: an object of names to values, or a list of name and value pairs. */
    static Map<String, String> tags(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node.isObject()) {
            node.properties().forEach(e -> out.put(e.getKey(), e.getValue().asString("")));
        } else if (node.isArray()) {
            node.forEach(
                    t -> out.put(t.path("name").asString(""), t.path("value").asString("")));
        }
        return out;
    }

    /** The domain of the first recipient, in lower case; never the address. */
    static String domain(JsonNode to) {
        String first = firstAddress(to);
        int at = first.lastIndexOf('@');
        String domain = at < 0 ? "" : first.substring(at + 1).strip().toLowerCase(Locale.ROOT);
        return domain.matches("^[a-z0-9.-]{1,253}$") ? domain : null;
    }

    /** The first recipient as Resend gives it, a list or one string; only ever hashed or cut to its domain. */
    static String firstAddress(JsonNode to) {
        return to.isArray() && !to.isEmpty() ? to.get(0).asString("") : to.asString("");
    }

    private static String uuidOrNull(String value) {
        UUID id = uuidOrNullAsUuid(value);
        return id == null ? null : id.toString();
    }

    private static UUID uuidOrNullAsUuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A short code as Resend sends it ({@code Permanent}, {@code General}, a template key), or null. */
    private static String safe(String value) {
        return value != null && value.matches("^[A-Za-z0-9_.-]{1,60}$") ? value : null;
    }
}
