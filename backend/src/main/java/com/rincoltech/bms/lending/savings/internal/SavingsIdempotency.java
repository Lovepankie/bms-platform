package com.rincoltech.bms.lending.savings.internal;

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
 * The {@code Idempotency-Key} protocol of chapter 7 section 7.8 for the savings money routes, on
 * {@code idempotency_keys}, inside the caller's transaction: exactly the protocol of the loans
 * module's {@code LoanIdempotency}, which savings may not reach (ADR-002). Issue #177 lifts the
 * copies into one core implementation; this one goes with them.
 */
@Service
class SavingsIdempotency {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    SavingsIdempotency(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    record Outcome<T>(T body, boolean replayed) {}

    private record Stored(String requestHash, String body) {}

    @Transactional(propagation = Propagation.MANDATORY)
    <T> Outcome<T> once(String key, String path, Object request, Class<T> responseType, Supplier<T> work) {
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
        String hash = sha256("POST " + path + "\n" + mapper.writeValueAsString(request));
        jdbc.sql("SET LOCAL lock_timeout = '5s'").update();
        int inserted;
        try {
            inserted = jdbc.sql("""
                            INSERT INTO idempotency_keys (tenant_id, principal_id, key, method, path, request_hash, status)
                            VALUES (current_setting('app.tenant_id')::uuid, ?, ?, 'POST', ?, ?, 'in_progress')
                            ON CONFLICT (tenant_id, principal_id, key) DO NOTHING
                            """).params(principal, key, path, hash).update();
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
                            SELECT request_hash, response_body::text FROM idempotency_keys
                             WHERE principal_id = ? AND key = ?
                            """)
                    .params(principal, key)
                    .query((rs, n) -> new Stored(rs.getString(1), rs.getString(2)))
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

    private static String sha256(String s) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
