package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.Masking;
import com.rincoltech.bms.kernel.PhoneNumbers;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SMS as a channel of the outbox (ADR-036, spec section 4.6). One message goes through these
 * steps, none of them holding a database connection across the network call:
 *
 * <ol>
 *   <li><b>A short tenant transaction</b> binds the tenant stored on the row (the dispatcher claimed
 *       it as a platform step; {@code TenantJobs.callForTenant} is the one way a stored tenant id becomes a
 *       bound tenant, and refuses a suspended or closed one) and decides: SMS switched off for the tenant (refused), the number
 *       (refused unless it is a Uganda mobile number we can read without guessing), the opt-out
 *       (logged {@code blocked_optout}, refused), the sending window for reminders and arrears
 *       (deferred), the per tenant and platform caps (deferred or held, never dropped), the text
 *       (rendered here because a tenant setting decides whether it carries a balance) and its
 *       segments. It then writes the delivery log row {@code queued} and commits, so a crash from
 *       here on is recognised.
 *   <li><b>The provider call</b>, outside any transaction, at most 15 seconds in total.
 *   <li><b>A second short tenant transaction</b> moves the log row to its outcome, cost included. If
 *       that write fails the answer is still returned: the message went, and a resend would be worse
 *       than a stale log row.
 * </ol>
 *
 * A row found already {@code queued} or {@code unknown} at the start of an attempt means an earlier
 * attempt died or timed out after it may have reached the provider: the answer is
 * {@link Result#unknown()} and the provider is not called again. A row already {@code sent} or
 * {@code sunk} is answered sent without a second call. A clean failure leaves the log row
 * {@code failed} and the next attempt may go. Logs carry the outbox id, the status and a reason
 * code, never a number or text.
 */
final class SmsOutboxChannel implements OutboxChannel {

    static final String CHANNEL = "sms";
    private static final Logger log = LoggerFactory.getLogger(SmsOutboxChannel.class);
    private static final ZoneId ZONE = BusinessClock.DEFAULT_ZONE;
    private static final String COUNTED = "'queued', 'sent', 'delivered', 'undelivered', 'unknown', 'sunk'";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate modeTransaction;
    private final SmsSettings settings;
    private final SmsProvider provider;
    private final OutboxTemplates templates;
    private final BusinessClock clock;
    private final SmsTenantConfig tenantConfig;
    private final TenantJobs tenantJobs;
    private final SmsRecipientHash hasher;
    private final Outbox outbox;
    private final AtomicBoolean modeRecorded = new AtomicBoolean();

    /** @param provider the provider for the configured mode, or null when the channel cannot send (mode off, unset keys) */
    SmsOutboxChannel(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            SmsSettings settings,
            SmsProvider provider,
            OutboxTemplates templates,
            BusinessClock clock,
            SmsTenantConfig tenantConfig,
            TenantJobs tenantJobs,
            SmsRecipientHash hasher,
            Outbox outbox) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        // Recording the mode writes, and enabled() is also asked from a read only request (the operator
        // portal's outbox status): it gets its own transaction.
        this.modeTransaction = new TransactionTemplate(transactionManager);
        this.modeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.settings = settings;
        this.provider = provider;
        this.templates = templates;
        this.clock = clock;
        this.tenantConfig = tenantConfig;
        this.tenantJobs = tenantJobs;
        this.hasher = hasher;
        this.outbox = outbox;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public boolean rendersItself() {
        return true;
    }

    /**
     * True when the mode and the provider allow a send <em>and</em> the database knows the mode: the
     * backlog drop of ADR-036 decision 14 runs first, at the first call after a start, in every mode
     * (an {@code off} start must be recorded too, or a later move to a sending mode would not see
     * that it came from one). Until it has run, the channel does not send.
     */
    @Override
    public boolean enabled() {
        return recordMode() && provider != null;
    }

    private boolean recordMode() {
        if (modeRecorded.get()) {
            return true;
        }
        try {
            Instant now = clock.now();
            Integer dropped = modeTransaction.execute(tx -> jdbc.sql("SELECT sms_channel_mode_changed(?, ?)")
                    .params(settings.mode().key(), Timestamp.from(now))
                    .query(Integer.class)
                    .single());
            modeRecorded.set(true);
            if (dropped != null && dropped > 0) {
                log.warn(
                        "SMS mode is now {}: {} queued message(s) from before were dropped, not sent",
                        settings.mode().key(),
                        dropped);
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("SMS mode could not be recorded yet: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    // ---- one message ----------------------------------------------------------------------------

    @Override
    public Result send(Delivery delivery) throws Exception {
        RowContext row = delivery.context();
        if (row.tenantId() == null || row.outboxId() == null) {
            return Result.refused("no_tenant");
        }
        Instant now = clock.now();
        Prepared prepared;
        try {
            prepared = tenantJobs.callForTenant(row.tenantId(), () -> prepare(delivery, row, now));
        } catch (TenantJobs.InactiveTenantException e) {
            // A suspended or closed tenant sends nothing (its outbox rows are not its to use any more).
            return Result.refused("tenant_inactive");
        }
        raise(prepared.alerts());
        if (prepared.result() != null) {
            return prepared.result();
        }

        // A send that ran past its lease may have been withdrawn by a reversal meanwhile: ask once more, immediately
        // before the provider call, and do not call it for a row that is no longer waiting (ADR-036, third amendment).
        if (!stillPending(row.outboxId())) {
            finish(row, prepared, provider.name(), "failed", null, "cancelled_reversed");
            return Result.refused("cancelled_reversed");
        }
        SmsProvider.Outcome outcome;
        try {
            outcome = provider.send(prepared.to(), prepared.text(), prepared.senderId(), delivery.idempotencyKey());
        } catch (SmsProvider.Transient e) {
            finish(row, prepared, provider.name(), "failed", null, e.getMessage());
            providerAlert(e.getMessage());
            throw new SendFailure(e.getMessage());
        } catch (SmsProvider.Ambiguous e) {
            finish(row, prepared, provider.name(), "unknown", null, e.getMessage());
            return Result.unknown();
        } catch (RuntimeException e) {
            // An adapter bug after the request may have gone: never retried (no idempotency key).
            finish(row, prepared, provider.name(), "unknown", null, "adapter_error");
            return Result.unknown();
        }
        return switch (outcome.status()) {
            case ACCEPTED -> {
                finish(row, prepared, outcome.provider(), "sent", outcome, null);
                yield Result.SENT;
            }
            case SUNK -> {
                finish(row, prepared, outcome.provider(), "sunk", outcome, null);
                yield Result.SENT;
            }
            case REJECTED -> {
                finish(row, prepared, outcome.provider(), "rejected", outcome, outcome.failureReason());
                yield Result.refused(outcome.failureReason());
            }
        };
    }

    /** Platform step: whether the outbox row is still pending (a reversal may have cancelled it). */
    private boolean stillPending(UUID outboxId) {
        return Boolean.TRUE.equals(transactions.execute(tx -> jdbc.sql("SELECT notification_outbox_is_pending(?)")
                .param(outboxId)
                .query(Boolean.class)
                .single()));
    }

    /** What the first transaction decided: an answer now, or the message to send. */
    private record Prepared(
            Result result, List<Alert> alerts, String to, String text, int segments, String hash, String senderId) {

        static Prepared done(Result result, List<Alert> alerts) {
            return new Prepared(result, alerts, null, null, 0, null, null);
        }

        /** The number and the text are not printed, even by accident. */
        @Override
        public String toString() {
            return "Prepared[segments=" + segments + "]";
        }
    }

    private record Alert(String scope, String state, long cap, String day) {}

    private record Existing(UUID id, String status, String failureReason) {}

    private Prepared prepare(Delivery delivery, RowContext row, Instant now) {
        List<Alert> alerts = new ArrayList<>();
        Optional<Integer> cap = jdbc.sql("SELECT daily_cap FROM sms_tenant_control_current() WHERE enabled")
                .query(Integer.class)
                .optional();
        if (cap.isEmpty()) {
            return Prepared.done(Result.refused("tenant_sms_off"), alerts);
        }
        // The tenant's own switch (the settings group sms): while it is off the row is refused, never held, so a
        // message queued before the tenant switched off cannot be released by switching on again.
        SmsTenantConfig.Config config = tenantConfig.current();
        if (!config.enabled()) {
            return Prepared.done(Result.refused("tenant_sms_off"), alerts);
        }
        // A row queued before the moment the tenant last switched on is the backlog of ADR-036 decision 14, even when
        // it
        // was claimed before that switch-on committed (the drop at the switch-on only reaches rows still pending).
        if (config.enabledSince() != null
                && row.queuedAt() != null
                && row.queuedAt().isBefore(config.enabledSince())) {
            return Prepared.done(Result.refused("backlog_dropped"), alerts);
        }

        Optional<Existing> existing = jdbc.sql(
                        "SELECT id, status, failure_reason FROM sms_deliveries WHERE outbox_id = ?")
                .param(row.outboxId())
                .query((rs, n) -> new Existing(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("failure_reason")))
                .optional();
        if (existing.isPresent()) {
            switch (existing.get().status()) {
                case "sent", "delivered", "undelivered", "sunk" -> {
                    return Prepared.done(Result.SENT, alerts);
                }
                case "blocked_optout" -> {
                    return Prepared.done(Result.refused("blocked_optout"), alerts);
                }
                case "rejected" -> {
                    return Prepared.done(Result.refused(reasonOr(existing.get().failureReason(), "rejected")), alerts);
                }
                case "queued", "unknown" -> {
                    // An earlier attempt died or timed out after it may have reached the provider.
                    jdbc.sql(
                                    "UPDATE sms_deliveries SET status = 'unknown', failure_reason = 'unknown_outcome', updated_at = ?"
                                            + " WHERE id = ?")
                            .params(Timestamp.from(now), existing.get().id())
                            .update();
                    return Prepared.done(Result.unknown(), alerts);
                }
                default -> {
                    // failed: a clean failure last time; this attempt may go.
                }
            }
        }

        Optional<String> phone = PhoneNumbers.forSms(delivery.recipient());
        String raw = delivery.recipient() == null ? "" : delivery.recipient().trim();
        String hash = hasher.of(phone.orElse(raw));
        String masked = phone.map(SmsOutboxChannel::maskE164).orElseGet(() -> tail(Masking.lastFour(raw), 20));
        if (phone.isEmpty()) {
            record(existing, row, masked, hash, 0, "rejected", "invalid_phone", now);
            return Prepared.done(Result.refused("invalid_phone"), alerts);
        }

        if (optedOut(row, hash)) {
            record(existing, row, masked, hash, 0, "blocked_optout", null, now);
            return Prepared.done(Result.refused("blocked_optout"), alerts);
        }

        boolean transactional = Outbox.TRANSACTIONAL.equals(row.category());
        SmsWindow window = SmsWindow.of(config.windowStart(), config.windowEnd());
        if (!transactional && !window.isOpen(now)) {
            return Prepared.done(Result.deferred(window.nextOpen(now)), alerts);
        }

        // Caps (ADR-036 decision 13): counted over today's business day, Africa/Kampala. The tenant's own cap can
        // only lower the operator's: the lower of the two applies, and 0 (the operator's or the tenant's) stops
        // every message, receipts included (twice zero is zero).
        LocalDate today = now.atZone(ZONE).toLocalDate();
        Instant dayStart = today.atStartOfDay(ZONE).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(ZONE).toInstant();
        long tenantCount = jdbc.sql("SELECT count(*) FROM sms_deliveries WHERE created_at >= ? AND created_at < ?"
                        + " AND status IN (" + COUNTED + ")")
                .params(Timestamp.from(dayStart), Timestamp.from(dayEnd))
                .query(Long.class)
                .single();
        long platformCount = jdbc.sql("SELECT sms_platform_count_since(?, ?)")
                .params(Timestamp.from(dayStart), Timestamp.from(dayEnd))
                .query(Long.class)
                .single();
        long tenantCap = config.dailyCap() == null ? cap.get() : Math.min(cap.get(), config.dailyCap());
        long platformCap = settings.platformDailyCap();
        // The monthly cap is the tenant's own and is counted only when it set one (the ceiling, thirty-one times the
        // daily cap, can never bind before the daily cap does).
        boolean overMonth = false;
        if (config.monthlyCap() != null) {
            LocalDate firstOfMonth = today.withDayOfMonth(1);
            long monthCount = jdbc.sql("SELECT count(*) FROM sms_deliveries WHERE created_at >= ? AND created_at < ?"
                            + " AND status IN (" + COUNTED + ")")
                    .params(
                            Timestamp.from(firstOfMonth.atStartOfDay(ZONE).toInstant()),
                            Timestamp.from(firstOfMonth
                                    .plusMonths(1)
                                    .atStartOfDay(ZONE)
                                    .toInstant()))
                    .query(Long.class)
                    .single();
            overMonth = transactional ? monthCount >= 2L * config.monthlyCap() : monthCount >= config.monthlyCap();
        }
        String tenantScope = "tenant " + row.tenantId();
        String day = today.toString();
        warn(alerts, tenantScope, tenantCount, tenantCap, day);
        warn(alerts, "platform", platformCount, platformCap, day);

        // Nothing over a cap is sent and nothing is dropped before it expires: it waits. A reminder
        // over the tenant cap waits for tomorrow's window; a receipt may go up to twice the cap;
        // beyond that, or over the platform ceiling, a row is held an hour at a time.
        Instant holdUntil = now.plusSeconds(3600);
        if (platformCount >= platformCap) {
            return Prepared.done(Result.deferred(holdUntil), alerts);
        }
        if (overMonth || (transactional ? tenantCount >= 2 * tenantCap : tenantCount >= tenantCap)) {
            if (transactional) {
                return Prepared.done(Result.deferred(holdUntil), alerts);
            }
            // Tomorrow's window, and an expiry that reaches its end (the row expired at the end of today's).
            Instant tomorrow = window.nextDayStart(now);
            return Prepared.done(Result.deferred(tomorrow, window.endOfDay(tomorrow)), alerts);
        }

        Map<String, String> params = new HashMap<>(row.params());
        // An SMS is plain text on one line: a value (the business name is the only free text) loses its control and
        // direction-changing characters whichever wording is used, the default or the tenant's own.
        params.replaceAll((name, value) -> SmsTemplateLanguage.clean(value));
        if (!config.includeBalance()) {
            params.remove("balance");
        }
        String text;
        try {
            text = renderText(row.templateKey(), params);
        } catch (IllegalArgumentException e) {
            return Prepared.done(Result.refused("template_error"), alerts);
        }
        int segments = SmsSegments.count(text).segments();
        // The sender id the tenant chose, only while the platform still approves it for this tenant; else the shared
        // sender.
        String senderId = config.senderId() == null
                ? null
                : jdbc.sql("SELECT sms_sender_id_approved(?)")
                        .param(config.senderId())
                        .query(String.class)
                        .optional()
                        .orElse(null);
        record(existing, row, masked, hash, segments, "queued", null, now);
        return new Prepared(null, alerts, phone.get(), text, segments, hash, senderId);
    }

    /**
     * The text of one message: the tenant's own wording for this event when it has saved one and it still renders (it
     * is checked again here, since the whitelist may have moved on), else the default. A broken override never costs
     * a receipt: the default goes instead, and a warning without the text is logged.
     */
    private String renderText(String templateKey, Map<String, String> params) {
        var event = SmsTemplateEvents.find(templateKey);
        if (event.isPresent()) {
            Optional<String> override = jdbc.sql("SELECT body FROM sms_template_overrides WHERE event_key = ?")
                    .param(templateKey)
                    .query(String.class)
                    .optional();
            if (override.isPresent()) {
                try {
                    SmsTemplateLanguage.validate(event.get(), override.get());
                    return SmsTemplateLanguage.render(event.get(), override.get(), params);
                } catch (SmsTemplateLanguage.Problem e) {
                    log.warn(
                            "SMS wording override for {} did not render ({}); the default is sent",
                            templateKey,
                            e.code());
                }
            }
        }
        return templates.render(CHANNEL, templateKey, params).text();
    }

    /** An operator alert at 80 percent of a cap and again when it is reached (once each per day). */
    private static void warn(List<Alert> alerts, String scope, long count, long cap, String day) {
        if (cap == 0 || count * 100 >= cap * 80) {
            alerts.add(new Alert(scope, count >= cap ? "100 percent" : "80 percent", cap, day));
        }
    }

    /**
     * Two kinds of row block a message (spec 4.9): one about a member (it blocks the member, on the number it was
     * recorded for when it holds one, else on any number) and one about a number alone (it blocks that number whoever
     * the message is about, matched by the keyed hash, which is how a pasted STOP list works). Scope {@code all}
     * blocks every category, {@code reminders} blocks reminders and arrears.
     */
    private boolean optedOut(RowContext row, String hash) {
        boolean member = "member".equals(row.subjectType()) && row.subjectId() != null;
        String aboutMember =
                member ? " OR (o.member_id = ? AND (o.recipient_hash IS NULL OR o.recipient_hash = ?))" : "";
        var query = jdbc.sql("SELECT EXISTS (SELECT 1 FROM sms_opt_outs o WHERE o.removed_at IS NULL"
                + " AND (o.scope = 'all' OR (o.scope = 'reminders' AND ? <> 'transactional'))"
                + " AND ((o.member_id IS NULL AND o.recipient_hash = ?)" + aboutMember + "))");
        return Boolean.TRUE.equals((member
                        ? query.params(row.category(), hash, row.subjectId(), hash)
                        : query.params(row.category(), hash))
                .query(Boolean.class)
                .single());
    }

    /** Inserts the delivery row, or moves a previous clean failure back to this attempt. */
    private void record(
            Optional<Existing> existing,
            RowContext row,
            String masked,
            String hash,
            int segments,
            String status,
            String reason,
            Instant now) {
        String providerName = provider == null ? SinkSmsProvider.NAME : provider.name();
        if (existing.isPresent()) {
            jdbc.sql("UPDATE sms_deliveries SET status = ?, failure_reason = ?, segments = ?, attempts = ?,"
                            + " recipient_masked = ?, recipient_hash = ?, provider = ?, updated_at = ? WHERE id = ?")
                    .params(
                            status,
                            reason,
                            segments == 0 ? null : segments,
                            row.attempt(),
                            masked,
                            hash,
                            providerName,
                            Timestamp.from(now),
                            existing.get().id())
                    .update();
            return;
        }
        jdbc.sql(
                        "INSERT INTO sms_deliveries (id, tenant_id, created_at, updated_at, outbox_id, template_key, category,"
                                + " subject_type, subject_id, recipient_masked, recipient_hash, provider, segments, status,"
                                + " failure_reason, attempts)"
                                + " VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(
                        UUID.randomUUID(),
                        Timestamp.from(now),
                        Timestamp.from(now),
                        row.outboxId(),
                        row.templateKey(),
                        row.category(),
                        row.subjectType(),
                        row.subjectId(),
                        masked,
                        hash,
                        providerName,
                        segments == 0 ? null : segments,
                        status,
                        reason,
                        row.attempt())
                .update();
    }

    /** The second short transaction. A failure here is logged and never changes the answer. */
    private void finish(
            RowContext row,
            Prepared prepared,
            String providerName,
            String status,
            SmsProvider.Outcome outcome,
            String reason) {
        SmsCost.Cost cost =
                outcome == null ? null : SmsCost.parse(outcome.costRaw()).orElse(null);
        Instant now = clock.now();
        try {
            tenantJobs.callForTenant(
                    row.tenantId(),
                    () -> jdbc.sql("UPDATE sms_deliveries SET status = ?, provider = ?, provider_message_id = ?,"
                                    + " provider_segments = ?, cost_minor = ?, currency = ?, cost_raw = ?,"
                                    + " failure_reason = ?, updated_at = ? WHERE outbox_id = ?")
                            .params(
                                    status,
                                    providerName,
                                    outcome == null ? null : outcome.messageId(),
                                    outcome == null ? null : outcome.providerSegments(),
                                    cost == null ? null : cost.minor(),
                                    cost == null ? null : cost.currency(),
                                    outcome == null ? null : outcome.costRaw(),
                                    reason == null ? null : tail(reason, 100),
                                    Timestamp.from(now),
                                    row.outboxId())
                            .update());
        } catch (RuntimeException e) {
            log.warn(
                    "SMS delivery log not updated: outbox={} status={} error={}",
                    row.outboxId(),
                    status,
                    e.getClass().getSimpleName());
        }
        log.info(
                "SMS {}: outbox={} template={} segments={}",
                status,
                row.outboxId(),
                row.templateKey(),
                prepared.segments());
    }

    /** Platform step: one operator alert per scope, state and day (the idempotency key keeps it to one). */
    private void raise(List<Alert> alerts) {
        for (Alert alert : alerts) {
            try {
                transactions.executeWithoutResult(tx -> outbox.enqueue(new Outbox.Message(
                        Outbox.TELEGRAM,
                        Outbox.OPERATOR_CHAT,
                        "sms.cap_alert",
                        Map.of(
                                "day", alert.day(),
                                "scope", alert.scope(),
                                "state", alert.state(),
                                "cap", String.valueOf(alert.cap())),
                        "sms.cap_alert:" + alert.scope() + ":" + alert.state() + ":" + alert.day())));
            } catch (RuntimeException e) {
                log.warn("SMS cap alert not queued: {}", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * The provider refused our key or has no credit (spec 4.14): the operator hears once a day through the
     * Telegram operator chat. Nothing else about the message goes in it.
     */
    private void providerAlert(String reason) {
        if (!"provider_auth_failed".equals(reason) && !"provider_insufficient_balance".equals(reason)) {
            return;
        }
        String day = clock.now().atZone(ZONE).toLocalDate().toString();
        try {
            transactions.executeWithoutResult(tx -> outbox.enqueue(new Outbox.Message(
                    Outbox.TELEGRAM,
                    Outbox.OPERATOR_CHAT,
                    "sms.provider_alert",
                    Map.of("day", day, "reason", reason),
                    "sms.provider_alert:" + reason + ":" + day)));
        } catch (RuntimeException e) {
            log.warn("SMS provider alert not queued: {}", e.getClass().getSimpleName());
        }
    }

    /** {@code +256700000001} as {@code +256*****0001}. */
    static String maskE164(String e164) {
        return e164.substring(0, 4) + "*".repeat(e164.length() - 8) + e164.substring(e164.length() - 4);
    }

    private static String reasonOr(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    private static String tail(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(value.length() - max);
    }
}
