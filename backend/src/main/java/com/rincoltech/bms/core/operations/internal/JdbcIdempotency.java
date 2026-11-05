package com.rincoltech.bms.core.operations.internal;

import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Chapter 7 section 7.8 on {@code idempotency_keys}. Every request with a key first takes a transaction lock on the
 * business and the key, whoever sends it, so a concurrent request with the same key waits for the first to commit or
 * roll back; {@code lock_timeout} bounds that wait at 5 seconds (409 {@code idempotency_in_progress}). Under the lock it
 * reads the key's row in this business: the caller's own row is replayed or refused as before; a row of anyone else is
 * refused with 422 {@code idempotency_key_other_user} and its stored answer is never read into the response (issue #852,
 * ADR-026 decision 5).
 */
@Service
class JdbcIdempotency implements Idempotency {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    JdbcIdempotency(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private record Stored(String requestHash, String body) {}

    /**
     * The transaction lock of one key in the bound business. A test that holds a key in progress takes the same lock
     * (HeldIdempotencyKey).
     */
    static final String LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended(current_setting('app.tenant_id') || ':idempotency:' || ?, 0))";

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> Outcome<T> once(
            String key, String method, String path, Object request, Class<T> responseType, Supplier<T> work) {
        return run(CurrentPrincipal.require().userId(), key, method, path, request, responseType, work);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> Outcome<T> onceAsJob(
            String job, String key, String method, String path, Object request, Class<T> type, Supplier<T> work) {
        return run(Idempotency.jobPrincipal(job), key, method, path, request, type, work);
    }

    private <T> Outcome<T> run(
            UUID principal, String key, String method, String path, Object request, Class<T> type, Supplier<T> work) {
        if (key == null || key.isBlank()) {
            throw ApiException.rule(
                    "idempotency_key_missing",
                    "Send an Idempotency-Key header (8 to 100 characters) with this request.");
        }
        if (key.length() < 8 || key.length() > 100) {
            throw ApiException.validation(
                    List.of(new FieldProblem("Idempotency-Key", "invalid", "The key must be 8 to 100 characters.")));
        }
        String hash = sha256(method + " " + path + "\n" + mapper.writeValueAsString(request));
        jdbc.sql("SET LOCAL lock_timeout = '5s'").update();
        int inserted;
        try {
            // Whoever sends the key: a second person's request waits for the first one's answer like a retry does.
            jdbc.sql(LOCK_SQL).param(key).query(rs -> {});
            // The caller's own row first; a business may hold one key under two people from before issue #852, and each
            // of them still replays their own.
            Boolean mine = jdbc.sql("""
                            SELECT principal_id = ? AS mine FROM idempotency_keys
                             WHERE tenant_id = current_setting('app.tenant_id')::uuid AND key = ?
                             ORDER BY (principal_id = ?) DESC LIMIT 1
                            """)
                    .params(principal, key, principal)
                    .query(Boolean.class)
                    .optional()
                    .orElse(null);
            if (Boolean.FALSE.equals(mine)) {
                // Neither the other person's answer nor whether their request matched this one is told.
                throw ApiException.rule(
                        Idempotency.OTHER_USER,
                        "Someone else in this business already used this Idempotency-Key. Nothing was saved; send the"
                                + " request again with a new key.");
            }
            inserted = jdbc.sql("""
                            INSERT INTO idempotency_keys (tenant_id, principal_id, key, method, path, request_hash, status)
                            VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, 'in_progress')
                            ON CONFLICT (tenant_id, principal_id, key) DO NOTHING
                            """).params(principal, key, method, path, hash).update();
        } catch (DataAccessException e) {
            if (lockTimeout(e)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        Idempotency.IN_PROGRESS,
                        "Request in progress",
                        "A request with this Idempotency-Key is still being processed; retry shortly.");
            }
            throw e;
        }
        if (inserted == 0) {
            Stored stored = jdbc.sql("""
                            SELECT request_hash, response_body::text AS body FROM idempotency_keys
                             WHERE tenant_id = current_setting('app.tenant_id')::uuid AND principal_id = ? AND key = ?
                            """)
                    .params(principal, key)
                    .query((rs, n) -> new Stored(rs.getString(1), rs.getString(2)))
                    .single();
            if (!stored.requestHash().equals(hash)) {
                throw ApiException.rule(
                        Idempotency.KEY_REUSED, "This Idempotency-Key was used for a different request.");
            }
            return new Outcome<>(mapper.readValue(stored.body(), type), true);
        }
        T body = work.get();
        jdbc.sql("""
                        UPDATE idempotency_keys SET status = 'completed', response_status = 201,
                               response_body = CAST(? AS jsonb)
                         WHERE tenant_id = current_setting('app.tenant_id')::uuid AND principal_id = ? AND key = ?
                        """).params(mapper.writeValueAsString(body), principal, key).update();
        return new Outcome<>(body, false);
    }

    private static boolean lockTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
