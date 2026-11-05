package com.rincoltech.bms.core.operations;

import com.rincoltech.bms.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * An {@code idempotency_keys} row inserted in a transaction that stays open, which is exactly what a
 * concurrent request that has claimed the key but not yet finished looks like to the next one: it holds
 * the key's transaction lock (issue #852, the same lock {@code JdbcIdempotency} takes) and the row, so the
 * next request with the key, from the same person or anyone else, waits until the 5 second lock timeout
 * and answers 409. {@link #complete} finishes it as a completed request with a stored answer; closing
 * without it rolls back.
 */
final class HeldIdempotencyKey implements AutoCloseable {

    private final Connection connection;
    private final UUID principalId;
    private final String key;
    private boolean done;

    HeldIdempotencyKey(UUID tenantId, UUID principalId, String key) throws SQLException {
        this.principalId = principalId;
        this.key = key;
        connection = TestDatabase.ownerDataSource().getConnection();
        connection.setAutoCommit(false);
        // The expression of JdbcIdempotency.LOCK_SQL, with the business named instead of read from app.tenant_id.
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?::text || ':idempotency:' || ?, 0))")) {
            ps.setObject(1, tenantId);
            ps.setString(2, key);
            ps.execute();
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO idempotency_keys (tenant_id, principal_id, key, method, path, request_hash, status)
                VALUES (?, ?, ?, 'POST', '/held', ?, 'in_progress')
                """)) {
            ps.setObject(1, tenantId);
            ps.setObject(2, principalId);
            ps.setString(3, key);
            ps.setString(4, "0".repeat(64));
            ps.executeUpdate();
        }
    }

    /** Finishes the held request: its row completed with this stored answer, committed. */
    void complete(String responseJson) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                UPDATE idempotency_keys SET status = 'completed', response_status = 201, response_body = CAST(? AS jsonb)
                 WHERE principal_id = ? AND key = ?
                """)) {
            ps.setString(1, responseJson);
            ps.setObject(2, principalId);
            ps.setString(3, key);
            ps.executeUpdate();
        }
        connection.commit();
        done = true;
    }

    @Override
    public void close() throws SQLException {
        try {
            if (!done) {
                connection.rollback();
            }
        } finally {
            connection.close();
        }
    }
}
