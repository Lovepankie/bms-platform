package com.rincoltech.bms.lending.loans.internal;

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
 * The {@code Idempotency-Key} protocol of chapter 7 section 7.8 for lending's money-moving routes,
 * on {@code idempotency_keys}, inside the caller's transaction, so the key row and the business
 * write commit or roll back together: a missing key is 422 {@code idempotency_key_missing}; a key
 * still held by a concurrent request after the 5 second lock timeout is 409
 * {@code idempotency_in_progress}; a key reused with a different request is 422
 * {@code idempotency_key_reused}; a completed key replays its stored response without doing the
 * work again. The same protocol as retail's {@code RetailIdempotency}, which lending may not
 * depend on (ADR-002); lifting both into a core module is noted in ADR-025.
 */
@Service
class LoanIdempotency {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    LoanIdempotency(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private record Stored(String requestHash, String status, String body) {}

    record Outcome<T>(T body, boolean replayed) {}

    @Transactional(propagation = Propagation.MANDATORY)
    <T> Outcome<T> once(
            String key, String method, String path, Object request, Class<T> responseType, Supplier<T> work) {
        if (key == null || key.isBlank()) {
            throw ApiException.rule(
                    "idempotency_key_missing",
                    "Send an Idempotency-Key header (8 to 100 characters) with this request.");
        }
        if (key.length() < 8 || key.length() > 100) {
            throw ApiException.validation(
                    List.of(new FieldProblem("Idempotency-Key", "invalid", "The key must be 8 to 100 characters.")));
        }
        UUID principal = CurrentPrincipal.require().userId();
        String hash = sha256(method + " " + path + "\n" + mapper.writeValueAsString(request));
        jdbc.sql("SET LOCAL lock_timeout = '5s'").update();
        int inserted;
        try {
            inserted = jdbc.sql("""
                            INSERT INTO idempotency_keys (tenant_id, principal_id, key, method, path, request_hash, status)
                            VALUES (current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, 'in_progress')
                            ON CONFLICT (tenant_id, principal_id, key) DO NOTHING
                            """).params(principal, key, method, path, hash).update();
        } catch (DataAccessException e) {
            if (lockTimeout(e)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "idempotency_in_progress",
                        "Request in progress",
                        "A request with this Idempotency-Key is still being processed; retry shortly.");
            }
            throw e;
        }
        if (inserted == 0) {
            Stored stored = jdbc.sql("""
                            SELECT request_hash, status, response_body::text AS body FROM idempotency_keys
                             WHERE principal_id = ? AND key = ?
                            """)
                    .params(principal, key)
                    .query((rs, n) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3)))
                    .single();
            if (!stored.requestHash().equals(hash)) {
                throw ApiException.rule(
                        "idempotency_key_reused", "This Idempotency-Key was used for a different request.");
            }
            return new Outcome<>(mapper.readValue(stored.body(), responseType), true);
        }
        T body = work.get();
        jdbc.sql("""
                        UPDATE idempotency_keys SET status = 'completed', response_status = 201,
                               response_body = CAST(? AS jsonb)
                         WHERE principal_id = ? AND key = ?
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
