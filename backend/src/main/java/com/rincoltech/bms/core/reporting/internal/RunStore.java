package com.rincoltech.bms.core.reporting.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads and writes {@code report_runs} (migration V49). Plain SQL under row-level security: the tenant is the
 * bound one and no statement names it. Every timestamp the application decides is written from the business
 * clock, never from the database's, so a test that pins the clock pins every column. Each method is its own
 * transaction unless the caller already has one.
 */
@Repository
@Transactional
class RunStore {

    /** One run as stored. */
    record Run(
            UUID id,
            String reportKey,
            Map<String, Object> params,
            Map<String, Object> scope,
            String format,
            String status,
            UUID requestedBy,
            LocalDate asOf,
            Instant snapshotAt,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Instant cancelRequestedAt,
            String owner,
            Instant heartbeatAt,
            UUID documentId,
            Integer rowCount,
            Long sizeBytes,
            int rowCap,
            long sizeCapBytes,
            String error,
            Instant expiresAt,
            Instant filesDeletedAt) {}

    /** A ready run whose file is due for deletion. */
    record Expired(UUID runId, UUID documentId) {}

    private static final String COLUMNS = """
            id, report_key, params::text AS params, scope::text AS scope, format, status, requested_by, as_of,
            snapshot_at, created_at, started_at, finished_at, cancel_requested_at, owner, heartbeat_at, document_id,
            row_count, size_bytes,
            row_cap, size_cap_bytes, error, expires_at, files_deleted_at
            """;

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    RunStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private Run map(ResultSet rs, int n) throws SQLException {
        return new Run(
                rs.getObject("id", UUID.class),
                rs.getString("report_key"),
                mapper.readValue(rs.getString("params"), MAP),
                mapper.readValue(rs.getString("scope"), MAP),
                rs.getString("format"),
                rs.getString("status"),
                rs.getObject("requested_by", UUID.class),
                rs.getObject("as_of", LocalDate.class),
                instant(rs, "snapshot_at"),
                instant(rs, "created_at"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                instant(rs, "cancel_requested_at"),
                rs.getString("owner"),
                instant(rs, "heartbeat_at"),
                rs.getObject("document_id", UUID.class),
                rs.getObject("row_count", Integer.class),
                rs.getObject("size_bytes", Long.class),
                rs.getInt("row_cap"),
                rs.getLong("size_cap_bytes"),
                rs.getString("error"),
                instant(rs, "expires_at"),
                instant(rs, "files_deleted_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    // ---- request ------------------------------------------------------------------------------

    /**
     * Queues a run; false when an identical request of the same user is already queued or running (the unique
     * index {@code report_runs_dedupe}), in which case nothing is written.
     */
    boolean insert(
            UUID id,
            Instant now,
            String reportKey,
            Map<String, Object> params,
            String paramsHash,
            Map<String, Object> scope,
            String format,
            UUID requestedBy,
            LocalDate asOf,
            int rowCap,
            long sizeCapBytes) {
        return jdbc.sql("""
                        INSERT INTO report_runs (id, tenant_id, created_at, report_key, params, params_hash, scope,
                                                 format, status, requested_by, as_of, row_cap, size_cap_bytes)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, CAST(? AS jsonb), ?,
                                CAST(? AS jsonb), ?, 'queued', ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, requested_by, params_hash) WHERE status IN ('queued', 'running')
                        DO NOTHING
                        """)
                        .params(
                                id,
                                ts(now),
                                reportKey,
                                mapper.writeValueAsString(params),
                                paramsHash,
                                mapper.writeValueAsString(scope),
                                format,
                                requestedBy,
                                java.sql.Date.valueOf(asOf),
                                rowCap,
                                sizeCapBytes)
                        .update()
                == 1;
    }

    /** Serialises the requests of one requester until the transaction ends (the tenant is part of the key). */
    void lockRequester(UUID requestedBy) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(current_setting('app.tenant_id') || ?, 0))")
                .param(requestedBy.toString())
                .query((rs, n) -> 1)
                .single();
    }

    Optional<Run> findOpenByHash(UUID requestedBy, String paramsHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM report_runs WHERE requested_by = ? AND params_hash = ?"
                        + " AND status IN ('queued', 'running')")
                .params(requestedBy, paramsHash)
                .query(this::map)
                .optional();
    }

    Optional<Run> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM report_runs WHERE id = ?")
                .param(id)
                .query(this::map)
                .optional();
    }

    /** A run row locked for the change that follows. */
    Optional<Run> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM report_runs WHERE id = ? FOR UPDATE")
                .param(id)
                .query(this::map)
                .optional();
    }

    List<Run> listByRequester(UUID requestedBy, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM report_runs WHERE requested_by = ?"
                        + " ORDER BY created_at DESC, id LIMIT ?")
                .params(requestedBy, limit)
                .query(this::map)
                .list();
    }

    /** The requester's newest run of one report, if any (the data export card, ADR-074). */
    Optional<Run> latestOf(UUID requestedBy, String reportKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM report_runs WHERE requested_by = ? AND report_key = ?"
                        + " ORDER BY created_at DESC, id LIMIT 1")
                .params(requestedBy, reportKey)
                .query(this::map)
                .optional();
    }

    /** When a run of this report last finished ready, by anyone in the business; null when none has. */
    Instant lastReadyAt(String reportKey) {
        return jdbc.sql("""
                        SELECT finished_at FROM report_runs WHERE report_key = ? AND status = 'ready'
                         ORDER BY finished_at DESC LIMIT 1
                        """)
                .param(reportKey)
                .query((rs, n) -> instant(rs, "finished_at"))
                .optional()
                .orElse(null);
    }

    int countCreatedSince(UUID requestedBy, Instant since) {
        return jdbc.sql("SELECT count(*) FROM report_runs WHERE requested_by = ? AND created_at > ?")
                .params(requestedBy, ts(since))
                .query(Integer.class)
                .single();
    }

    // ---- the runner ---------------------------------------------------------------------------

    /**
     * The oldest queued run of this tenant when none is running, now running; empty when there is nothing to
     * do. Locks with {@code SKIP LOCKED}, so two claimers never take the same run; the unique index
     * {@code report_runs_one_running} backs it, and a lost race surfaces as a duplicate key for the caller to
     * treat as "nothing claimed".
     */
    Optional<Run> claimNext(Instant now, String owner) {
        Optional<UUID> id = jdbc.sql("""
                        SELECT r.id FROM report_runs r
                         WHERE r.status = 'queued'
                           AND NOT EXISTS (SELECT 1 FROM report_runs x WHERE x.status = 'running')
                         ORDER BY r.created_at, r.id
                         LIMIT 1 FOR UPDATE SKIP LOCKED
                        """).query(UUID.class).optional();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        jdbc.sql("""
                        UPDATE report_runs SET status = 'running', started_at = ?, heartbeat_at = ?, owner = ?,
                                               updated_at = ? WHERE id = ?
                        """).params(ts(now), ts(now), owner, ts(now), id.get()).update();
        return find(id.get());
    }

    void recordSnapshot(UUID id, Instant snapshotAt, Instant now) {
        jdbc.sql("UPDATE report_runs SET snapshot_at = ?, updated_at = ? WHERE id = ? AND status = 'running'")
                .params(ts(snapshotAt), ts(now), id)
                .update();
    }

    /** True when the run was still running and is now ready; false when it was cancelled in the meantime. */
    boolean markReady(UUID id, UUID documentId, int rowCount, long sizeBytes, Instant now, Instant expiresAt) {
        return jdbc.sql("""
                        UPDATE report_runs
                           SET status = 'ready', document_id = ?, row_count = ?, size_bytes = ?, finished_at = ?,
                               updated_at = ?, expires_at = ?
                         WHERE id = ? AND status = 'running' AND cancel_requested_at IS NULL
                        """)
                        .params(documentId, rowCount, sizeBytes, ts(now), ts(now), ts(expiresAt), id)
                        .update()
                == 1;
    }

    /** Fails a run that is queued or running; returns whether it changed. */
    boolean markFailed(UUID id, String error, Instant now) {
        return jdbc.sql("""
                        UPDATE report_runs SET status = 'failed', error = ?, finished_at = ?, updated_at = ?
                         WHERE id = ? AND status IN ('queued', 'running')
                        """).params(error, ts(now), ts(now), id).update() == 1;
    }

    boolean markCancelled(UUID id, Instant now) {
        return jdbc.sql("""
                        UPDATE report_runs SET status = 'cancelled', finished_at = ?, updated_at = ?
                         WHERE id = ? AND status IN ('queued', 'running')
                        """).params(ts(now), ts(now), id).update() == 1;
    }

    /** Asks a running run to stop; the worker sees it at its next checkpoint. */
    boolean requestCancel(UUID id, Instant now) {
        return jdbc.sql("""
                        UPDATE report_runs SET cancel_requested_at = ?, updated_at = ?
                         WHERE id = ? AND status = 'running' AND cancel_requested_at IS NULL
                        """).params(ts(now), ts(now), id).update() == 1;
    }

    /** True when the run should stop: it was cancelled or failed from outside, or a cancel was requested. */
    boolean shouldStop(UUID id) {
        return jdbc.sql("SELECT status <> 'running' OR cancel_requested_at IS NOT NULL FROM report_runs WHERE id = ?")
                .param(id)
                .query(Boolean.class)
                .optional()
                .orElse(true);
    }

    /** Says a run is still alive; false when it is no longer running (cancelled or failed from outside). */
    boolean heartbeat(UUID id, Instant now) {
        return jdbc.sql("UPDATE report_runs SET heartbeat_at = ? WHERE id = ? AND status = 'running'")
                        .params(ts(now), id)
                        .update()
                == 1;
    }

    /** Fails every run of this tenant whose process is gone: no heartbeat since the cutoff; returns their ids. */
    List<UUID> failStale(Instant cutoff, Instant now) {
        return jdbc.sql("""
                        UPDATE report_runs SET status = 'failed', error = 'interrupted', finished_at = ?, updated_at = ?
                         WHERE status = 'running' AND coalesce(heartbeat_at, started_at) < ?
                        RETURNING id
                        """)
                .params(ts(now), ts(now), ts(cutoff))
                .query(UUID.class)
                .list();
    }

    /** Fails one run this process was executing when it was told to stop; false when it had already ended. */
    boolean failInterrupted(UUID id, Instant now) {
        return markFailed(id, "interrupted", now);
    }

    // ---- expiry -------------------------------------------------------------------------------

    List<Expired> expired(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT id, document_id FROM report_runs
                         WHERE status = 'ready' AND files_deleted_at IS NULL AND expires_at <= ?
                         ORDER BY expires_at, id LIMIT ?
                        """)
                .params(ts(now), limit)
                .query((rs, n) -> new Expired(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class)))
                .list();
    }

    void markFilesDeleted(UUID id, Instant now) {
        jdbc.sql("UPDATE report_runs SET files_deleted_at = ?, updated_at = ? WHERE id = ?")
                .params(ts(now), ts(now), id)
                .update();
    }
}
