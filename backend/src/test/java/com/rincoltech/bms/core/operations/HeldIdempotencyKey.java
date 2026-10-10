package com.rincoltech.bms.core.operations;

import com.rincoltech.bms.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * An {@code idempotency_keys} row inserted in a transaction that stays open, which is exactly what a
 * concurrent request that has claimed the key but not yet finished looks like to the next one: its
 * insert waits on this row until the 5 second lock timeout and answers 409. Closing rolls back.
 */
final class HeldIdempotencyKey implements AutoCloseable {

    private final Connection connection;

    HeldIdempotencyKey(UUID tenantId, UUID principalId, String key) throws SQLException {
        connection = TestDatabase.ownerDataSource().getConnection();
        connection.setAutoCommit(false);
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

    @Override
    public void close() throws SQLException {
        try {
            connection.rollback();
        } finally {
            connection.close();
        }
    }
}
