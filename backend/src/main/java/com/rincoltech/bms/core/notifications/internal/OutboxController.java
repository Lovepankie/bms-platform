package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Masking;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/platform/outbox} (chapter 7 section 7.11.3): what the senders could not deliver,
 * for the operator portal (spec section 11). Recipients are masked; no text or parameter leaves
 * the database.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/outbox", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class OutboxController {

    private final JdbcClient jdbc;
    private final OutboxDispatcher dispatcher;
    private final BusinessClock clock;
    private final PlatformAuditLog audit;
    private final EmailSuppressions suppressions;

    OutboxController(
            JdbcClient jdbc,
            OutboxDispatcher dispatcher,
            BusinessClock clock,
            PlatformAuditLog audit,
            EmailSuppressions suppressions) {
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.audit = audit;
        this.suppressions = suppressions;
    }

    @Schema(name = "OutboxUnsuppressRequest")
    record UnsuppressRequest(
            @NotBlank @Email @Size(max = 254) String email) {}

    @Schema(name = "OutboxFailure")
    record Failure(
            UUID id,
            String channel,

            @Schema(description = "Masked to its last 4 characters")
            String recipient,

            String templateKey,
            int attempts,

            @Schema(description = "The error class, never a message text or an address")
            String lastError,

            Instant createdAt,
            Instant failedAt,

            @Schema(
                    description =
                            "The tenant that caused the row (an SMS, a tenant digest), or null for a platform row")
            UUID tenantId,

            @Schema(description = "transactional, reminder or arrears")
            String category) {}

    @Schema(name = "OutboxBounce")
    record Bounce(
            Instant occurredAt,

            @Schema(description = "bounced, complained or suppressed (Resend did not send it: the address is blocked)")
            String kind,

            @Schema(description = "The outbox row the email was, when Resend gave it back")
            UUID outboxId,

            @Schema(description = "The business whose email it was, or null for a platform email")
            UUID tenantId,

            String templateKey,

            @Schema(description = "The recipient's domain only, never the address")
            String recipientDomain,

            @Schema(description = "Resend's bounce type, for example Permanent or Transient; null for a complaint")
            String bounceType) {}

    @Schema(name = "OutboxStatus")
    record Status(
            @Schema(description = "Channels whose sender is configured; rows of the others stay pending")
            List<String> enabledChannels,

            @Schema(description = "Rows per status: pending, sent, failed")
            Map<String, Long> counts,

            List<Failure> failures,

            @Schema(description = "The latest 100 bounces and complaints Resend reported (issue #333, ADR-117)")
            List<Bounce> bounces) {}

    @GetMapping
    @RequiresPermission("platform.tenants.read")
    @Operation(summary = "Outbox counts and the failed rows (FR-NTF-09)", operationId = "platformOutbox")
    @Transactional(readOnly = true)
    public Status status() {
        Map<String, Long> counts = new LinkedHashMap<>(Map.of("pending", 0L, "sent", 0L, "failed", 0L));
        jdbc.sql("SELECT status, total FROM notification_outbox_counts()")
                .query((rs, n) -> counts.put(rs.getString("status"), rs.getLong("total")))
                .list();
        List<Failure> failures = jdbc.sql("SELECT * FROM notification_outbox_failures(200)")
                .query((rs, n) -> new Failure(
                        rs.getObject("id", UUID.class),
                        rs.getString("channel"),
                        Masking.lastFour(rs.getString("recipient")),
                        rs.getString("template_key"),
                        rs.getInt("attempts"),
                        rs.getString("last_error"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("failed_at").toInstant(),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("category")))
                .list();
        List<Bounce> bounces = audit.recent(ResendWebhookController.ACTIONS, 100).stream()
                .map(e -> new Bounce(
                        e.occurredAt(),
                        switch (e.action()) {
                            case ResendWebhookController.BOUNCED -> "bounced";
                            case ResendWebhookController.SUPPRESSED -> "suppressed";
                            default -> "complained";
                        },
                        e.data().get("outbox_id") == null
                                ? null
                                : UUID.fromString(e.data().get("outbox_id")),
                        e.tenantId(),
                        e.data().get("template"),
                        e.data().get("recipient_domain"),
                        e.data().get("bounce_type")))
                .toList();
        return new Status(dispatcher.enabledChannels(), counts, failures, bounces);
    }

    @PostMapping("/{outbox_id}/retry")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Send a failed row again, if younger than its link's lifetime (FR-NTF-09)",
            operationId = "platformRetryOutbox")
    @Transactional
    public ResponseEntity<Void> retry(@PathVariable("outbox_id") UUID id) {
        Instant now = clock.now();
        String result = jdbc.sql("SELECT notification_outbox_retry(?, ?, ?)")
                .params(id, Timestamp.from(now.minus(OutboxJobs.LINK_LIFETIME)), Timestamp.from(now))
                .query(String.class)
                .single();
        if (result.equals("not_failed")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "outbox_not_failed",
                    "Not a failed message",
                    "Only a failed message can be retried.");
        }
        if (result.equals("too_old")) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "outbox_too_old",
                    "Too old to send",
                    "This message is older than its link's lifetime. Issue a new link instead, for example with Needs info.");
        }
        audit.record("platform.outbox.retried", CurrentPrincipal.require().userId(), null, Map.of("outbox_id", id));
        return ResponseEntity.noContent().build();
    }

    // JUSTIFICATION-A3: the operator's clear of a suppression the #759 lead asked for (third review, must-fix 2).
    /**
     * Clears the suppression of one address (ADR-117 decision 9): records {@code platform.email.unsuppressed} with the
     * address's keyed hash and the operator, so the senders send to it again. The address is in the body, never the path,
     * and is not stored or logged. 409 {@code email_not_suppressed} when it was not suppressed (a typo writes nothing).
     */
    @PostMapping("/unsuppress")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Send email to an address again after a bounce, complaint or suppression (ADR-117)",
            operationId = "platformUnsuppressEmail")
    @Transactional
    public ResponseEntity<Void> unsuppress(@Valid @RequestBody UnsuppressRequest request) {
        if (!suppressions.clear(request.email(), CurrentPrincipal.require().userId())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "email_not_suppressed",
                    "Not suppressed",
                    "BMS is not holding back email to this address.");
        }
        return ResponseEntity.noContent().build();
    }
}
