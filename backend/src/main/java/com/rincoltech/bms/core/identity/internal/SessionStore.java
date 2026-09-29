package com.rincoltech.bms.core.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Server-side sessions (FR-IAM-07, FR-IAM-08; chapter 6 tables {@code auth_sessions} and
 * {@code platform_sessions}). One row per refresh token; a rotation marks the row rotated and
 * inserts the next one in the same family. Only hashes of refresh tokens are stored.
 */
final class SessionStore {

    record Row(
            UUID id,
            UUID userId,
            UUID familyId,
            Instant expiresAt,
            Instant idleExpiresAt,
            Instant rotatedAt,
            Instant revokedAt) {}

    private final JdbcClient jdbc;
    private final String table;
    private final String userColumn;
    private final boolean tenantOwned;

    private SessionStore(JdbcClient jdbc, String table, String userColumn, boolean tenantOwned) {
        this.jdbc = jdbc;
        this.table = table;
        this.userColumn = userColumn;
        this.tenantOwned = tenantOwned;
    }

    static SessionStore staff(JdbcClient jdbc) {
        return new SessionStore(jdbc, "auth_sessions", "user_id", true);
    }

    static SessionStore platform(JdbcClient jdbc) {
        return new SessionStore(jdbc, "platform_sessions", "platform_user_id", false);
    }

    void insert(
            UUID id,
            UUID userId,
            UUID familyId,
            String refreshTokenHash,
            Instant expiresAt,
            Instant idleExpiresAt,
            String ip,
            String userAgent) {
        String tenantColumn = tenantOwned ? "tenant_id, " : "";
        String tenantValue = tenantOwned ? "current_setting('app.tenant_id')::uuid, " : "";
        jdbc.sql("INSERT INTO " + table + " (id, " + tenantColumn + userColumn
                        + ", family_id, refresh_token_hash, expires_at, idle_expires_at, ip, user_agent)"
                        + " VALUES (?, " + tenantValue + "?, ?, ?, ?, ?, CAST(? AS inet), ?)")
                .params(
                        id,
                        userId,
                        familyId,
                        refreshTokenHash,
                        Timestamp.from(expiresAt),
                        Timestamp.from(idleExpiresAt),
                        ip,
                        userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 300)))
                .update();
    }

    Optional<Row> byRefreshHashForUpdate(String refreshTokenHash) {
        return jdbc.sql(select() + " WHERE refresh_token_hash = ? FOR UPDATE")
                .param(refreshTokenHash)
                .query(this::map)
                .optional();
    }

    Optional<Row> byId(UUID id) {
        return jdbc.sql(select() + " WHERE id = ?").param(id).query(this::map).optional();
    }

    void markRotated(UUID id, Instant at) {
        jdbc.sql("UPDATE " + table + " SET rotated_at = ? WHERE id = ?")
                .params(Timestamp.from(at), id)
                .update();
    }

    int revokeFamily(UUID familyId, String reason, Instant at) {
        return jdbc.sql("UPDATE " + table
                        + " SET revoked_at = ?, revoked_reason = ? WHERE family_id = ? AND revoked_at IS NULL")
                .params(Timestamp.from(at), reason, familyId)
                .update();
    }

    int revokeAllOf(UUID userId, String reason, Instant at) {
        return jdbc.sql("UPDATE " + table + " SET revoked_at = ?, revoked_reason = ? WHERE " + userColumn
                        + " = ? AND revoked_at IS NULL")
                .params(Timestamp.from(at), reason, userId)
                .update();
    }

    private String select() {
        return "SELECT id, " + userColumn + " AS user_id, family_id, expires_at, idle_expires_at, rotated_at,"
                + " revoked_at FROM " + table;
    }

    private Row map(ResultSet rs, int n) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getObject("family_id", UUID.class),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("idle_expires_at")),
                instant(rs.getTimestamp("rotated_at")),
                instant(rs.getTimestamp("revoked_at")));
    }

    static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
