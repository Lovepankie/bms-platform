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

    OutboxController(JdbcClient jdbc, OutboxDispatcher dispatcher, BusinessClock clock, PlatformAuditLog audit) {
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.audit = audit;
    }

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
            Instant failedAt) {}

    @Schema(name = "OutboxStatus")
    record Status(
            @Schema(description = "Channels whose sender is configured; rows of the others stay pending")
            List<String> enabledChannels,

            @Schema(description = "Rows per status: pending, sent, failed")
            Map<String, Long> counts,

            List<Failure> failures) {}

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
                        rs.getTimestamp("failed_at").toInstant()))
                .list();
        return new Status(dispatcher.enabledChannels(), counts, failures);
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
}
