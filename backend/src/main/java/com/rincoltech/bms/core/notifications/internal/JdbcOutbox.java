package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.Outbox;
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

/** Writes outbox rows through {@code notification_outbox_enqueue} (migration V23, ADR-016). */
@Service
class JdbcOutbox implements Outbox {

    private static final Set<String> CHANNELS = Set.of(EMAIL, TELEGRAM);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final BusinessClock clock;
    private final OutboxTemplates templates;

    JdbcOutbox(JdbcClient jdbc, ObjectMapper mapper, BusinessClock clock, OutboxTemplates templates) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
        this.templates = templates;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueue(Message message) {
        if (!CHANNELS.contains(message.channel())) {
            throw new IllegalArgumentException("unknown outbox channel " + message.channel());
        }
        // A template that cannot render would only fail at send time: refuse it in the cause's
        // transaction instead.
        templates.render(message.channel(), message.templateKey(), message.params());
        return Boolean.TRUE.equals(
                jdbc.sql("SELECT notification_outbox_enqueue(?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)")
                        .params(
                                UUID.randomUUID(),
                                message.channel(),
                                message.recipient(),
                                message.templateKey(),
                                mapper.writeValueAsString(message.params()),
                                message.idempotencyKey(),
                                message.throttleKey(),
                                message.expiresAt() == null ? null : Timestamp.from(message.expiresAt()),
                                Timestamp.from(clock.now()))
                        .query(Boolean.class)
                        .single());
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
