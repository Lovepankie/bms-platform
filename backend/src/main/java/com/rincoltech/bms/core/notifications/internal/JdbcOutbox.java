package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes outbox rows through {@code notification_outbox_enqueue} (migration V23, ADR-016; the
 * overload with the category, subject and hold time is migration V50, ADR-036). The tenant of a row
 * is the one bound to the caller's transaction, set inside the function; it is never a parameter.
 */
@Service
class JdbcOutbox implements Outbox {

    private static final Set<String> CHANNELS = Set.of(EMAIL, TELEGRAM, SMS);
    private static final Set<String> CATEGORIES = Set.of(TRANSACTIONAL, REMINDER, ARREARS);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final BusinessClock clock;
    private final OutboxTemplates templates;
    private final TenantSettings settings;

    JdbcOutbox(
            JdbcClient jdbc,
            ObjectMapper mapper,
            BusinessClock clock,
            OutboxTemplates templates,
            TenantSettings settings) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.templates = templates;
        this.settings = settings;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(Message message) {
        if (!CHANNELS.contains(message.channel())) {
            throw new IllegalArgumentException("unknown outbox channel " + message.channel());
        }
        if (!CATEGORIES.contains(message.category())) {
            throw new IllegalArgumentException("unknown outbox category " + message.category());
        }
        // A template that cannot render would only fail at send time: refuse it in the cause's
        // transaction instead.
        templates.render(message.channel(), message.templateKey(), message.params());
        Instant now = clock.now();
        Instant notBefore = message.notBefore();
        Instant expiresAt = message.expiresAt();
        if (SMS.equals(message.channel()) && !TRANSACTIONAL.equals(message.category())) {
            // A reminder waits for the tenant's window and expires at the end of that window day
            // (ADR-036 decisions 5 and 14); the tenant is bound, as in every enqueue of an SMS.
            SmsWindow window = SmsWindow.of(settings.smsWindowStart(), settings.smsWindowEnd());
            notBefore = notBefore == null ? window.nextOpen(now) : notBefore;
            expiresAt = expiresAt == null ? window.endOfDay(notBefore) : expiresAt;
        }
        Subject subject = message.subject();
        return Boolean.TRUE.equals(
                jdbc.sql("SELECT notification_outbox_enqueue(?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?)")
                        .params(
                                UUID.randomUUID(),
                                message.channel(),
                                message.recipient(),
                                message.templateKey(),
                                mapper.writeValueAsString(message.params()),
                                message.idempotencyKey(),
                                message.throttleKey(),
                                expiresAt == null ? null : Timestamp.from(expiresAt),
                                Timestamp.from(now),
                                message.category(),
                                subject == null ? null : subject.type(),
                                subject == null ? null : subject.id(),
                                notBefore == null ? null : Timestamp.from(notBefore))
                        .query(Boolean.class)
                        .single());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelUnclaimed(String eventRef) {
        return jdbc.sql("SELECT notification_outbox_cancel_unclaimed(?, ?)")
                .params(eventRef, Timestamp.from(clock.now()))
                .query(Integer.class)
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int countSince(String throttleKey, String templateKey, Instant since, Instant until) {
        return jdbc.sql("SELECT notification_outbox_count_since(?, ?, ?, ?)")
                .params(throttleKey, templateKey, Timestamp.from(since), Timestamp.from(until))
                .query(Integer.class)
                .single();
    }
}
