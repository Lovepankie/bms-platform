package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers outbox rows (FR-NTF-09) in three steps per row (review N3): a short transaction claims
 * the next due row of an enabled channel ({@code FOR UPDATE SKIP LOCKED}), counts the attempt and
 * sets a lease, and commits; the send runs outside any transaction, holding no connection; a
 * second short transaction records the result. A crash, a lost connection or an Error during the
 * send leaves the row {@code pending} with the attempt already counted, and it comes back after
 * the lease; a row whose attempts are used up (or whose link has expired) is marked {@code failed}
 * by the next claim instead of being sent again, so the bound holds even when every send dies with
 * an Error: delivery is at least once, the idempotency key travels with the message where the
 * provider supports one, and the third failed attempt marks the row {@code failed}, which the
 * operator portal lists. Logs carry the row id, channel, template and attempt only: never the
 * recipient, the text or a link.
 */
@Component
class OutboxDispatcher {

    static final int MAX_ATTEMPTS = 3;
    /** Wait before the second and third attempt. */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5));
    /** How long a claimed row stays away from other senders while it is being sent. */
    static final Duration LEASE = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final OutboxTemplates templates;
    private final ObjectMapper mapper;
    private final BusinessClock clock;
    private final List<OutboxChannel> channels;

    OutboxDispatcher(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            OutboxTemplates templates,
            ObjectMapper mapper,
            BusinessClock clock,
            List<OutboxChannel> channels) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.templates = templates;
        this.mapper = mapper;
        this.clock = clock;
        this.channels = List.copyOf(channels);
    }

    /** The channels that send; the others keep their rows pending. */
    List<String> enabledChannels() {
        return enabled(channels).keySet().stream().sorted().toList();
    }

    /** Sends up to {@code max} due rows with the configured channels; returns how many were tried. */
    int dispatch(int max) {
        return dispatch(channels, max);
    }

    int dispatch(List<OutboxChannel> using, int max) {
        Map<String, OutboxChannel> byName = enabled(using);
        if (byName.isEmpty()) {
            return 0;
        }
        String[] names = byName.keySet().toArray(String[]::new);
        int tried = 0;
        while (tried < max) {
            Optional<Row> claimed = claim(names);
            if (claimed.isEmpty()) {
                break;
            }
            send(byName, claimed.get());
            tried++;
        }
        return tried;
    }

    private Optional<Row> claim(String[] names) {
        Instant now = clock.now();
        return transactions.execute(status -> jdbc.sql("SELECT * FROM notification_outbox_claim(?, ?, ?, ?)")
                .params(names, MAX_ATTEMPTS, Timestamp.from(now), Timestamp.from(now.plus(LEASE)))
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("channel"),
                        rs.getString("recipient"),
                        rs.getString("template_key"),
                        rs.getString("params"),
                        rs.getString("idempotency_key"),
                        rs.getInt("attempts")))
                .optional());
    }

    /** Outside any transaction: render and send, then record the result in its own transaction. */
    private void send(Map<String, OutboxChannel> byName, Row row) {
        String error = null;
        try {
            OutboxTemplates.Rendered rendered;
            try {
                rendered = templates.render(row.channel(), row.templateKey(), mapper.readValue(row.params(), PARAMS));
            } catch (IllegalArgumentException e) {
                throw new OutboxChannel.SendFailure("template could not be rendered");
            }
            byName.get(row.channel())
                    .send(new OutboxChannel.Delivery(
                            row.recipient(), rendered.subject(), rendered.text(), row.idempotencyKey()));
        } catch (Exception e) {
            error = safeError(e);
        }
        Instant now = clock.now();
        Instant retryAt = now.plus(BACKOFF.get(Math.min(row.attempts(), BACKOFF.size()) - 1));
        boolean ok = error == null;
        String failure = error;
        String status = transactions.execute(tx -> jdbc.sql("SELECT notification_outbox_record(?, ?, ?, ?, ?, ?)")
                .params(row.id(), ok, failure, MAX_ATTEMPTS, Timestamp.from(retryAt), Timestamp.from(now))
                .query(String.class)
                .single());
        if (ok) {
            log.info("outbox row sent: id={} channel={} template={}", row.id(), row.channel(), row.templateKey());
        } else {
            log.warn(
                    "outbox send failed: id={} channel={} template={} attempt={} status={} error={}",
                    row.id(),
                    row.channel(),
                    row.templateKey(),
                    row.attempts(),
                    status,
                    failure);
        }
    }

    /** The nightly purge of {@link OutboxJobs}: sent rows after 30 days, links after 7. */
    int purge() {
        Instant now = clock.now();
        return transactions.execute(status -> jdbc.sql("SELECT notification_outbox_purge(?, ?, ?)")
                .params(
                        Timestamp.from(now.minus(Duration.ofDays(30))),
                        Timestamp.from(now.minus(OutboxJobs.LINK_LIFETIME)),
                        Timestamp.from(now))
                .query(Integer.class)
                .single());
    }

    /**
     * The exception class, plus the message of a {@link OutboxChannel.SendFailure}, which the
     * senders write to carry no recipient, text or secret. Any other exception's message (a
     * provider's, or the JDK's, which can quote a URL holding the bot token) is dropped.
     */
    static String safeError(Exception e) {
        return e instanceof OutboxChannel.SendFailure && e.getMessage() != null
                ? "SendFailure: " + e.getMessage()
                : e.getClass().getSimpleName();
    }

    private static Map<String, OutboxChannel> enabled(List<OutboxChannel> using) {
        return using.stream()
                .filter(OutboxChannel::enabled)
                .collect(Collectors.toMap(OutboxChannel::channel, c -> c, (a, b) -> a));
    }

    private record Row(
            UUID id,
            String channel,
            String recipient,
            String templateKey,
            String params,
            String idempotencyKey,
            int attempts) {}
}
