package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 *
 * <p>A channel answers with a {@link OutboxChannel.Result} (ADR-036 decision 6): sent; deferred,
 * which gives the attempt back and holds the row until a time; unknown, which marks the row failed
 * and is never retried; refused, which marks it failed with a reason and is never retried. A thrown
 * exception is a clean failure and is retried at the backoff below. The claim takes transactional
 * rows first and rotates across tenants (migration V50).
 */
@Component
class OutboxDispatcher {

    static final int MAX_ATTEMPTS = 3;
    /** Wait before the second and third attempt. */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5));
    /** How long a claimed row stays away from other senders while it is being sent. */
    static final Duration LEASE = Duration.ofMinutes(2);
    /** The delivery log is kept 13 months (ADR-036 decision 3). */
    static final int DELIVERY_RETENTION_MONTHS = 13;

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
        return dispatch(using, max, null);
    }

    /** The senders of every channel but one: the general sender leaves SMS to its own, larger run. */
    int dispatchExcept(String channel, int max) {
        // Push has its own, faster sender (OutboxJobs), so the general one leaves it too.
        return dispatch(
                channels.stream()
                        .filter(c ->
                                !c.channel().equals(channel) && !c.channel().equals(Outbox.PUSH))
                        .toList(),
                max);
    }

    /** One channel only, with a time budget: the SMS sender (spec section 4.6, throughput). */
    int dispatchOnly(String channel, int max, Duration budget) {
        return dispatch(
                channels.stream().filter(c -> c.channel().equals(channel)).toList(), max, budget);
    }

    /**
     * As {@link #dispatch(List, int)}, and stops claiming once {@code budget} has passed (null: no
     * budget), so a provider outage at 15 seconds a message cannot hold one run for hours.
     */
    int dispatch(List<OutboxChannel> using, int max, Duration budget) {
        Map<String, OutboxChannel> byName = enabled(using);
        if (byName.isEmpty()) {
            return 0;
        }
        String[] names = byName.keySet().toArray(String[]::new);
        Instant started = clock.now();
        int tried = 0;
        while (tried < max) {
            if (budget != null && clock.now().isAfter(started.plus(budget))) {
                break;
            }
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
                        rs.getInt("attempts"),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("category"),
                        rs.getString("subject_type"),
                        rs.getObject("subject_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at") == null
                                ? null
                                : rs.getTimestamp("expires_at").toInstant()))
                .optional());
    }

    /** Outside any transaction: render and send, then record the result in its own transaction. */
    private void send(Map<String, OutboxChannel> byName, Row row) {
        OutboxChannel channel = byName.get(row.channel());
        String outcome;
        String error = null;
        Instant retryAt = null;
        Instant newExpiry = null;
        try {
            Map<String, String> params = mapper.readValue(row.params(), PARAMS);
            String subject = null;
            String text = null;
            if (!channel.rendersItself()) {
                try {
                    OutboxTemplates.Rendered rendered = templates.render(row.channel(), row.templateKey(), params);
                    subject = rendered.subject();
                    text = rendered.text();
                } catch (IllegalArgumentException e) {
                    throw new OutboxChannel.SendFailure("template could not be rendered");
                }
            }
            OutboxChannel.RowContext context = new OutboxChannel.RowContext(
                    row.id(),
                    row.tenantId(),
                    row.category(),
                    row.subjectType(),
                    row.subjectId(),
                    row.templateKey(),
                    params,
                    row.attempts(),
                    row.createdAt(),
                    row.expiresAt());
            OutboxChannel.Result result = channel.send(
                    new OutboxChannel.Delivery(row.recipient(), subject, text, row.idempotencyKey(), context));
            Instant now = clock.now();
            switch (result) {
                case OutboxChannel.Result.Sent sent -> outcome = "sent";
                case OutboxChannel.Result.Deferred deferred -> {
                    outcome = "deferred";
                    // A deferral never lands in the past: that would claim the row again at once.
                    retryAt = deferred.until() == null || !deferred.until().isAfter(now)
                            ? now.plus(Duration.ofMinutes(1))
                            : deferred.until();
                    newExpiry = deferred.extendExpiryTo();
                }
                case OutboxChannel.Result.Unknown unknown -> outcome = "unknown";
                case OutboxChannel.Result.Refused refused -> {
                    outcome = "refused";
                    error = safeReason(refused.reason());
                }
            }
        } catch (Exception e) {
            outcome = "failed";
            error = safeError(e);
        }
        Instant now = clock.now();
        if (retryAt == null) {
            retryAt = now.plus(BACKOFF.get(Math.min(row.attempts(), BACKOFF.size()) - 1));
        }
        String recordedOutcome = outcome;
        String recordedError = error;
        Instant recordedRetry = retryAt;
        Instant recordedExpiry = newExpiry;
        String status =
                transactions.execute(tx -> jdbc.sql("SELECT notification_outbox_record_result(?, ?, ?, ?, ?, ?, ?)")
                        .params(
                                row.id(),
                                recordedOutcome,
                                recordedError,
                                MAX_ATTEMPTS,
                                Timestamp.from(recordedRetry),
                                Timestamp.from(now),
                                recordedExpiry == null ? null : Timestamp.from(recordedExpiry))
                        .query(String.class)
                        .optional()
                        .orElse("none"));
        if (outcome.equals("sent")) {
            log.info("outbox row sent: id={} channel={} template={}", row.id(), row.channel(), row.templateKey());
        } else {
            log.warn(
                    "outbox row not sent: id={} channel={} template={} attempt={} outcome={} status={} error={}",
                    row.id(),
                    row.channel(),
                    row.templateKey(),
                    row.attempts(),
                    outcome,
                    status,
                    error);
        }
    }

    /**
     * The nightly purge of {@link OutboxJobs}: sent rows after 30 days, links after 7, and SMS
     * delivery log rows after 13 months.
     */
    int purge() {
        Instant now = clock.now();
        return transactions.execute(status -> {
            int outbox = jdbc.sql("SELECT notification_outbox_purge(?, ?, ?)")
                    .params(
                            Timestamp.from(now.minus(Duration.ofDays(30))),
                            Timestamp.from(now.minus(OutboxJobs.LINK_LIFETIME)),
                            Timestamp.from(now))
                    .query(Integer.class)
                    .single();
            int deliveries = jdbc.sql("SELECT sms_deliveries_purge(?)")
                    .param(Timestamp.from(now.atZone(ZoneOffset.UTC)
                            .minusMonths(DELIVERY_RETENTION_MONTHS)
                            .toInstant()))
                    .query(Integer.class)
                    .single();
            return outbox + deliveries;
        });
    }

    /**
     * The frequent job of {@link OutboxJobs}: an unsent row whose own expiry has passed loses its parameters and is marked
     * failed ("expired"), so a one-time link (a password reset, FR-IAM-15) is in the database in plain text for its lifetime
     * and the job's interval, not until the nightly purge. Deletes nothing: the same function as {@link #purge()} with
     * thresholds that match no age.
     */
    int clearExpiredLinks() {
        Instant now = clock.now();
        return transactions.execute(status -> jdbc.sql("SELECT notification_outbox_purge(?, ?, ?)")
                .params(Timestamp.from(Instant.EPOCH), Timestamp.from(Instant.EPOCH), Timestamp.from(now))
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

    /** A refusal reason is a short code; anything else is reduced so a number or text can never reach a row. */
    static String safeReason(String reason) {
        return reason != null && reason.matches("^[a-z][a-z0-9_]{0,60}$") ? reason : "refused";
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
            int attempts,
            UUID tenantId,
            String category,
            String subjectType,
            UUID subjectId,
            Instant createdAt,
            Instant expiresAt) {}
}
