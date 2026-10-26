package com.rincoltech.bms.core.help.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The tables of Ask BMS (migration V108): the answer cache, the question log, the tickets the limits count, the spend of
 * each provider call, and the two reads of the cache key that come from the database (the asker's roles and the permission
 * matrix version). Each method runs in a short transaction of its own, so the tenant is bound and no connection is held
 * while a provider is asked; the writes commit whatever the caller does next.
 */
@Component
class AskStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    AskStore(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.writes = new TransactionTemplate(transactions);
        this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
    }

    record Cached(String answer, String citedArticle, String provider, String model) {}

    /** One question, as the spend log keeps it: counts, names and closed words, never the text. */
    record Asked(
            UUID id,
            UUID userId,
            Instant askedAt,
            String questionHash,
            String locale,
            String outcome,
            String fallbackReason,
            String provider,
            String model,
            List<String> failures,
            boolean counted,
            String citedArticle,
            int inputTokens,
            int outputTokens,
            long costMicro,
            String costCurrency,
            long latencyMs) {}

    /**
     * The roles the user holds now and the permission matrix version: the first 16 hex digits of the SHA-256 of every
     * role and permission pair, so a change to what any role may do is a new cache key.
     */
    record KeyParts(List<String> roles, String matrixVersion) {}

    KeyParts keyParts(UUID userId) {
        return reads.execute(tx -> {
            List<String> roles = jdbc.sql("""
                            SELECT DISTINCT role_key FROM user_role_assignments
                             WHERE user_id = ? AND revoked_at IS NULL ORDER BY role_key
                            """).param(userId).query(String.class).list();
            List<String> pairs = jdbc.sql("""
                            SELECT role_key || ':' || permission_key FROM role_permissions
                             ORDER BY role_key, permission_key
                            """).query(String.class).list();
            return new KeyParts(roles, sha256(String.join("\n", pairs)).substring(0, 16));
        });
    }

    Optional<Cached> cached(String key, Instant now) {
        return reads.execute(tx -> jdbc.sql("""
                        SELECT answer, cited_article, provider, model FROM help_ask_answers
                         WHERE cache_key = ? AND expires_at > ?
                        """)
                .params(key, Timestamp.from(now))
                .query((rs, n) -> new Cached(
                        rs.getString("answer"),
                        rs.getString("cited_article"),
                        rs.getString("provider"),
                        rs.getString("model")))
                .optional());
    }

    /** Stores an answer under its key; a row already there is replaced only once it has expired. */
    void cache(String key, Cached answer, String revision, Instant now, Instant expires) {
        writes.executeWithoutResult(tx -> jdbc.sql("""
                        INSERT INTO help_ask_answers (tenant_id, cache_key, answer, cited_article, provider, model, revision,
                                                      created_at, expires_at)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, cache_key) DO UPDATE
                           SET answer = EXCLUDED.answer, cited_article = EXCLUDED.cited_article,
                               provider = EXCLUDED.provider, model = EXCLUDED.model, revision = EXCLUDED.revision,
                               created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at
                         WHERE help_ask_answers.expires_at <= EXCLUDED.created_at
                        """)
                .params(
                        key,
                        answer.answer(),
                        answer.citedArticle(),
                        answer.provider(),
                        answer.model(),
                        revision,
                        Timestamp.from(now),
                        Timestamp.from(expires))
                .update());
    }

    /** One lock for the reservation of every business's paid calls: the monthly cap is a sum across businesses. */
    private static final long SPEND_LOCK = 7_220_000_020L;

    /**
     * Takes a ticket of {@code kind} for the user when fewer than {@code limit} tickets of that kind were taken since
     * {@code from} (by the user for {@code ask}, by the business for {@code provider}), and records it before the work it
     * counts, under the business's advisory lock: questions sent at once are counted one after the other, so none passes a
     * check another has not yet recorded. False when the limit is reached; nothing is written then.
     */
    boolean ticket(String kind, UUID userId, Instant now, Instant from, long limit) {
        Boolean taken = writes.execute(tx -> {
            jdbc.sql(
                            "SELECT pg_advisory_xact_lock(hashtextextended('help_ask:' || current_setting('app.tenant_id'), 0))")
                    .query()
                    .listOfRows();
            long used = "ask".equals(kind)
                    ? jdbc.sql("SELECT count(*) FROM help_ask_tickets WHERE kind = 'ask' AND user_id = ? AND at >= ?")
                            .params(userId, Timestamp.from(from))
                            .query(Long.class)
                            .single()
                    : jdbc.sql("SELECT count(*) FROM help_ask_tickets WHERE kind = 'provider' AND at >= ?")
                            .param(Timestamp.from(from))
                            .query(Long.class)
                            .single();
            if (used >= limit) {
                return false;
            }
            jdbc.sql("""
                            INSERT INTO help_ask_tickets (id, tenant_id, user_id, at, kind)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?)
                            """)
                    .params(UUID.randomUUID(), userId, Timestamp.from(now), kind)
                    .update();
            return true;
        });
        return Boolean.TRUE.equals(taken);
    }

    /**
     * Reserves the most a provider call can cost before it is made. With a {@code monthlyCap}, under one platform-wide lock,
     * the reservation is refused (empty) when the provider's spend in {@code currency} by every business since
     * {@code monthStart}, reservations of calls in flight included, plus this one would pass it.
     */
    Optional<UUID> reserve(
            String provider,
            String model,
            Instant now,
            long reserveMicro,
            String currency,
            Long monthlyCap,
            Instant monthStart) {
        return writes.execute(tx -> {
            if (monthlyCap != null) {
                jdbc.sql("SELECT pg_advisory_xact_lock(?)")
                        .param(SPEND_LOCK)
                        .query()
                        .listOfRows();
                long spent = jdbc.sql("SELECT help_ask_platform_spend(?, ?, ?)")
                        .params(provider, currency, Timestamp.from(monthStart))
                        .query(Long.class)
                        .single();
                if (monthlyCap <= 0 || spent + reserveMicro > monthlyCap) {
                    return Optional.<UUID>empty();
                }
            }
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO help_ask_spend (id, tenant_id, provider, model, reserved_at, cost_micro, cost_currency)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                            """)
                    .params(id, provider, model, Timestamp.from(now), reserveMicro, currency)
                    .update();
            return Optional.of(id);
        });
    }

    /** Settles a reservation with what the provider reported; a call that may have been billed but reported nothing keeps it. */
    void settle(UUID id, Instant now, int inputTokens, int outputTokens, long costMicro) {
        writes.executeWithoutResult(tx -> jdbc.sql("""
                        UPDATE help_ask_spend SET settled_at = ?, input_tokens = ?, output_tokens = ?, cost_micro = ?
                         WHERE id = ? AND settled_at IS NULL
                        """)
                .params(Timestamp.from(now), inputTokens, outputTokens, costMicro, id)
                .update());
    }

    void log(Asked a) {
        writes.executeWithoutResult(tx -> jdbc.sql("""
                        INSERT INTO help_ask_questions (id, tenant_id, user_id, asked_at, question_hash, locale, outcome,
                                                        fallback_reason, provider, model, failures, counted, cited_article,
                                                        input_tokens, output_tokens, cost_micro, cost_currency, latency_ms)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        a.id(),
                        a.userId(),
                        Timestamp.from(a.askedAt()),
                        a.questionHash(),
                        a.locale(),
                        a.outcome(),
                        a.fallbackReason(),
                        a.provider(),
                        a.model(),
                        a.failures().toArray(String[]::new),
                        a.counted(),
                        a.citedArticle(),
                        a.inputTokens(),
                        a.outputTokens(),
                        a.costMicro(),
                        a.costCurrency(),
                        (int) Math.min(Integer.MAX_VALUE, a.latencyMs()))
                .update());
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }
}
