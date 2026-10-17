package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.notifications.internal.SmsConfiguration.SmsProviderRef;
import com.rincoltech.bms.core.notifications.internal.SmsTemplateLanguage.Event;
import com.rincoltech.bms.core.notifications.internal.SmsTemplateService.Measure;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.PhoneNumbers;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The test message of the wording screen (spec 4.10): an administrator sends a template, filled with fabricated data,
 * to one number to see it on a phone. It must never reach a real person by accident, so it is stricter than the
 * channel and independent of it:
 *
 * <ul>
 *   <li>mode {@code off}: nothing is sent;
 *   <li>mode {@code sink}: the sink records it, nothing leaves the platform;
 *   <li>mode {@code allowlist}: a real send only to a number in {@code BMS_SMS_ALLOWLIST} (checked here, not only in
 *       the provider wrapper); any other number goes to a fresh sink. A real send is held to what a real message is held
 *       to, before it goes: the operator's switch, the tenant's own switch, the opt-out list and the caps (reasons
 *       {@code operator_sms_off}, {@code tenant_sms_off}, {@code blocked_optout}, {@code cap_reached}); and it is written
 *       to the delivery and cost log, so it counts toward the caps and the usage;
 *   <li>mode {@code live}: never sent. The preview is the test; a live provider is not asked to text a number a
 *       person typed into a form.
 * </ul>
 *
 * The text starts with "TEST: " so nobody takes it for a receipt. The message does not go through the outbox; a sink
 * test costs nothing and is not logged as a delivery, a real one is. Both are audited with the masked number and the
 * outcome, and limited to {@value #PER_HOUR} an hour per business. The startup guard (staging reaches only the
 * provider's sandbox) is untouched and still holds underneath.
 */
@Service
class SmsTestService {

    static final int PER_HOUR = 10;
    static final String PREFIX = "TEST: ";
    private static final Logger log = LoggerFactory.getLogger(SmsTestService.class);

    @Schema(name = "SmsTestMessageResult")
    record Result(
            @Schema(
                    description =
                            "True when a provider accepted the message for a real phone (allowlist mode, a listed number)")
            boolean sent,

            @Schema(description = "sunk (recorded, nothing left the platform), sent, or not_sent")
            String outcome,

            @Schema(
                    description =
                            "For not_sent: channel_off, live_mode_preview_only, operator_sms_off, tenant_sms_off, blocked_optout, cap_reached or the provider's reason")
            String reason,

            String mode,
            String recipientMasked,

            @Schema(description = "The text as it was (or would be) sent, with the TEST prefix")
            String text,

            Measure measure) {}

    private final SmsSettings settings;
    private final SmsProviderRef providerRef;
    private final SmsTemplateService templates;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final SmsTenantConfig tenantConfig;
    private final SmsRecipientHash hasher;
    private final BusinessClock clock;

    SmsTestService(
            SmsSettings settings,
            SmsProviderRef providerRef,
            SmsTemplateService templates,
            JdbcClient jdbc,
            AuditLog audit,
            PlatformTransactionManager transactionManager,
            SmsTenantConfig tenantConfig,
            SmsRecipientHash hasher,
            BusinessClock clock) {
        this.tenantConfig = tenantConfig;
        this.hasher = hasher;
        this.clock = clock;
        this.settings = settings;
        this.providerRef = providerRef;
        this.templates = templates;
        this.jdbc = jdbc;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
    }

    Result send(String eventKey, String rawText, String rawTo) {
        Event event = templates.event(eventKey);
        String to = PhoneNumbers.forSms(rawTo)
                .orElseThrow(() -> ApiException.validation(List.of(
                        new FieldProblem("to", "invalid_phone", "Use a Uganda mobile number such as 0700 000 001."))));
        String masked = SmsOutboxChannel.maskE164(to);
        // The wording is checked and rendered first, so a bad text is a 422 before anything is limited or sent.
        String body = tx.execute(s -> {
            var preview = templates.preview(eventKey, rawText);
            return PREFIX + preview.text();
        });
        Measure measure = tx.execute(s -> templates.measure(event, body, templates.costRate()));

        SmsSettings.Mode mode = settings.mode();
        SmsProvider provider = providerRef.provider();
        if (mode == SmsSettings.Mode.OFF || provider == null) {
            return done(eventKey, mode, masked, body, measure, false, "not_sent", "channel_off");
        }
        if (mode == SmsSettings.Mode.LIVE) {
            return done(eventKey, mode, masked, body, measure, false, "not_sent", "live_mode_preview_only");
        }
        // JUSTIFICATION-A3: round-2 review (a real test send is gated, reserved and sent under one lock per business).
        // Sink mode, or allowlist mode with a number that is not listed: a sink of our own, whatever the provider is.
        boolean real =
                mode == SmsSettings.Mode.ALLOWLIST && settings.allowlist().contains(to);
        UUID reservation = null;
        if (real) {
            // A real send costs money and reaches a phone, so it obeys everything a real message does: both switches,
            // the opt-out list and the caps. The check, the rate limit and the reservation of its place in the delivery
            // log are one transaction under a lock per business, so two sends at "cap minus one" cannot both pass: the
            // second sees the first one's reservation. (The sink costs nothing and reaches nobody, so it needs none.)
            Reserved reserved = tx.execute(s -> reserve(to, masked, body, provider.name(), eventKey));
            if (reserved.blocked() != null) {
                return done(eventKey, mode, masked, body, measure, false, "not_sent", reserved.blocked());
            }
            reservation = reserved.id();
        } else {
            limit();
        }

        SmsProvider chosen = real ? provider : new SinkSmsProvider();
        SmsProvider.Outcome outcome;
        try {
            outcome = chosen.send(to, body, null, "test:" + UUID.randomUUID());
        } catch (SmsProvider.Transient e) {
            log.info("SMS test message not sent: reason={}", e.getMessage());
            finish(reservation, "failed", null, e.getMessage());
            return done(eventKey, mode, masked, body, measure, false, "not_sent", e.getMessage());
        } catch (SmsProvider.Ambiguous e) {
            // It may have gone and cost: it is logged unknown, which counts toward the caps.
            log.info("SMS test message outcome unknown: reason={}", e.getMessage());
            finish(reservation, "unknown", null, e.getMessage());
            return done(eventKey, mode, masked, body, measure, false, "not_sent", e.getMessage());
        } catch (RuntimeException e) {
            // Whatever happened, it may have reached the provider: the reservation stays counted, as unknown.
            finish(reservation, "unknown", null, "unexpected_error");
            throw e;
        }
        return switch (outcome.status()) {
            case SUNK -> {
                finish(reservation, "sunk", outcome, null);
                yield done(eventKey, mode, masked, body, measure, false, "sunk", null);
            }
            case ACCEPTED -> {
                // An accepted send is never lost: the reservation already counts, and finish() cannot fail the call.
                finish(reservation, "sent", outcome, null);
                yield done(eventKey, mode, masked, body, measure, true, "sent", null);
            }
            case REJECTED -> {
                finish(reservation, "rejected", outcome, outcome.failureReason());
                yield done(eventKey, mode, masked, body, measure, false, "not_sent", outcome.failureReason());
            }
        };
    }

    private record Reserved(String blocked, UUID id) {}

    /**
     * One transaction, under the business's test lock: the gate, the rate limit, the audit of the attempt and a
     * {@code queued} delivery row that holds the message's place against the caps until the send's outcome is known.
     */
    private Reserved reserve(String to, String masked, String body, String provider, String eventKey) {
        lockTests();
        Optional<String> blocked = gate(to);
        if (blocked.isPresent()) {
            return new Reserved(blocked.get(), null);
        }
        checkRate();
        auditAttempt();
        UUID id = UUID.randomUUID();
        Instant now = clock.now();
        String templateKey = "test." + eventKey;
        jdbc.sql("INSERT INTO sms_deliveries (id, tenant_id, created_at, updated_at, outbox_id,"
                        + " template_key, category, recipient_masked, recipient_hash, provider, segments, status, attempts)"
                        + " VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, 'transactional', ?, ?, ?, ?,"
                        + " 'queued', 1)")
                .params(
                        id,
                        Timestamp.from(now),
                        Timestamp.from(now),
                        UUID.randomUUID(),
                        templateKey.substring(0, Math.min(100, templateKey.length())),
                        masked,
                        hasher.of(to),
                        provider,
                        SmsSegments.count(body).segments())
                .update();
        return new Reserved(null, id);
    }

    /**
     * Settles the reservation with what the provider said: cost, ids and status. It never throws: if the update fails
     * the row stays {@code queued}, which counts toward the caps exactly like a sent one, so an accepted message is
     * never missing from the log.
     */
    private void finish(UUID id, String status, SmsProvider.Outcome outcome, String reason) {
        if (id == null) {
            return;
        }
        try {
            SmsCost.Cost cost =
                    outcome == null ? null : SmsCost.parse(outcome.costRaw()).orElse(null);
            tx.executeWithoutResult(s -> jdbc.sql("UPDATE sms_deliveries SET status = ?, provider_message_id = ?,"
                            + " provider_segments = ?, cost_minor = ?, currency = ?, cost_raw = ?, failure_reason = ?,"
                            + " updated_at = ? WHERE id = ?")
                    .params(
                            status,
                            outcome == null ? null : outcome.messageId(),
                            outcome == null ? null : outcome.providerSegments(),
                            cost == null ? null : cost.minor(),
                            cost == null ? null : cost.currency(),
                            outcome == null ? null : outcome.costRaw(),
                            reason == null ? null : reason.substring(0, Math.min(100, reason.length())),
                            Timestamp.from(clock.now()),
                            id)
                    .update());
        } catch (RuntimeException e) {
            log.error("SMS test message {} was handled but its delivery row could not be settled", id, e);
        }
    }

    /**
     * Why a real test send must not go, or empty: the operator's switch or the tenant's own is off, the number is on the
     * opt-out list for every message, or a cap is reached. A test is held to the cap itself, not to twice it as a receipt
     * is.
     */
    private Optional<String> gate(String to) {
        Optional<Integer> operatorCap = jdbc.sql("SELECT daily_cap FROM sms_tenant_control_current() WHERE enabled")
                .query(Integer.class)
                .optional();
        if (operatorCap.isEmpty()) {
            return Optional.of("operator_sms_off");
        }
        SmsTenantConfig.Config config = tenantConfig.current();
        if (!config.enabled()) {
            return Optional.of("tenant_sms_off");
        }
        boolean optedOut = Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM sms_opt_outs"
                        + " WHERE removed_at IS NULL AND scope = 'all' AND recipient_hash = ?)")
                .param(hasher.of(to))
                .query(Boolean.class)
                .single());
        if (optedOut) {
            return Optional.of("blocked_optout");
        }
        LocalDate today = clock.now().atZone(BusinessClock.DEFAULT_ZONE).toLocalDate();
        long dailyCap = config.dailyCap() == null ? operatorCap.get() : Math.min(operatorCap.get(), config.dailyCap());
        if (count(today, today.plusDays(1)) >= dailyCap) {
            return Optional.of("cap_reached");
        }
        if (config.monthlyCap() != null) {
            LocalDate first = today.withDayOfMonth(1);
            if (count(first, first.plusMonths(1)) >= config.monthlyCap()) {
                return Optional.of("cap_reached");
            }
        }
        long platform = jdbc.sql("SELECT sms_platform_count_since(?, ?)")
                .params(zoned(today), zoned(today.plusDays(1)))
                .query(Long.class)
                .single();
        return platform >= settings.platformDailyCap() ? Optional.of("cap_reached") : Optional.empty();
    }

    private long count(LocalDate from, LocalDate to) {
        return jdbc.sql("SELECT count(*) FROM sms_deliveries WHERE created_at >= ? AND created_at < ?"
                        + " AND status IN ('queued', 'sent', 'delivered', 'undelivered', 'unknown', 'sunk')")
                .params(zoned(from), zoned(to))
                .query(Long.class)
                .single();
    }

    private static Timestamp zoned(LocalDate day) {
        return Timestamp.from(day.atStartOfDay(BusinessClock.DEFAULT_ZONE).toInstant());
    }

    /** A sink test: the rate limit and the audit of the attempt, in one transaction under the business's test lock. */
    private void limit() {
        tx.executeWithoutResult(s -> {
            lockTests();
            checkRate();
            // The attempt is recorded before the send, in this transaction, so the limit holds under concurrent clicks.
            auditAttempt();
        });
    }

    /** One lock per business for the test messages, held to the end of the transaction. */
    private void lockTests() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('sms.test:' || current_setting('app.tenant_id'), 0))")
                .query((rs, n) -> 1)
                .single();
    }

    /** At most {@value #PER_HOUR} an hour per business, counted from the audit log (call under the lock). */
    private void checkRate() {
        long recent = jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'core.sms.test_message'"
                        + " AND created_at > now() - interval '1 hour'")
                .query(Long.class)
                .single();
        if (recent >= PER_HOUR) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "rate_limited",
                    "Too many test messages",
                    "At most " + PER_HOUR + " test messages an hour. Try again later.");
        }
    }

    private void auditAttempt() {
        audit.record(new AuditLog.Entry(
                "core.sms.test_message",
                "core.sms_test",
                UUID.randomUUID(),
                null,
                Map.of(),
                Map.of("step", "attempt")));
    }

    private Result done(
            String eventKey,
            SmsSettings.Mode mode,
            String masked,
            String body,
            Measure measure,
            boolean sent,
            String outcome,
            String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", eventKey);
        data.put("mode", mode.key());
        data.put("recipient", masked);
        data.put("outcome", outcome);
        if (reason != null) {
            data.put("reason", reason);
        }
        tx.executeWithoutResult(s -> audit.record(new AuditLog.Entry(
                "core.sms.test_message_result", "core.sms_test", UUID.randomUUID(), null, Map.of(), data)));
        return new Result(sent, outcome, reason, mode.key(), masked, body, measure);
    }
}
