package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.retail.sales.internal.SalesApi.Clash;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Failure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** SQL for the clashes of offline sales and the refused ones the devices report (ADR-067). RLS supplies the tenant. */
@Repository
class OfflineSalesRepository {

    private static final String SELECT_FAILURE = """
            SELECT f.id, f.client_sale_id, f.branch_id, f.made_by, u.full_name AS made_by_name, f.occurred_at,
                   f.payment_method, f.total_minor, f.request::text AS request, f.code, f.message, f.status, f.decided_by,
                   f.decided_at, f.discard_reason, f.sale_id, f.created_at
              FROM retail_offline_failures f LEFT JOIN users u ON u.id = f.made_by
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    OfflineSalesRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** The clashes in these branches (null: every branch), open or resolved or both, newest first, at most 200. */
    List<Clash> clashes(List<UUID> branchIds, String status) {
        StringBuilder sql = new StringBuilder(SalesRepository.CLASHES + " WHERE true");
        if (branchIds != null) {
            if (branchIds.isEmpty()) {
                return List.of();
            }
            sql.append(" AND c.branch_id IN (:branches)");
        }
        if (status.equals("open")) {
            sql.append(" AND c.resolved_at IS NULL");
        } else if (status.equals("resolved")) {
            sql.append(" AND c.resolved_at IS NOT NULL");
        }
        sql.append(" ORDER BY c.created_at DESC, c.id LIMIT 200");
        var statement = jdbc.sql(sql.toString());
        if (branchIds != null) {
            statement = statement.param("branches", branchIds);
        }
        return statement.query((rs, n) -> SalesRepository.clash(rs)).list();
    }

    Optional<Clash> clash(UUID id, boolean lock) {
        return jdbc.sql(SalesRepository.CLASHES + " WHERE c.id = ?" + (lock ? " FOR UPDATE OF c" : ""))
                .param(id)
                .query((rs, n) -> SalesRepository.clash(rs))
                .optional();
    }

    void resolveClash(UUID id, UUID by, String note) {
        jdbc.sql("""
                        UPDATE retail_sale_clashes SET resolved_at = now(), resolved_by = ?, resolution_note = ?
                         WHERE id = ?
                        """).params(by, note, id).update();
    }

    Optional<Failure> failure(UUID id, boolean lock) {
        return jdbc.sql(SELECT_FAILURE + " WHERE f.id = ?" + (lock ? " FOR UPDATE OF f" : ""))
                .param(id)
                .query((rs, n) -> failure(rs))
                .optional();
    }

    Optional<Failure> failureByClient(UUID clientSaleId, boolean lock) {
        return jdbc.sql(SELECT_FAILURE + " WHERE f.client_sale_id = ?" + (lock ? " FOR UPDATE OF f" : ""))
                .param(clientSaleId)
                .query((rs, n) -> failure(rs))
                .optional();
    }

    boolean branchExists(UUID branchId) {
        return jdbc.sql("SELECT count(*) FROM branches WHERE id = ?")
                        .param(branchId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** Inserts the report unless one with this client sale id exists; true when written. */
    boolean insertFailure(SalesApi.FailureRequest r, UUID madeBy) {
        return jdbc.sql("""
                        INSERT INTO retail_offline_failures (id, tenant_id, client_sale_id, branch_id, made_by, occurred_at,
                            payment_method, total_minor, request, code, message)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                        ON CONFLICT (tenant_id, client_sale_id) DO NOTHING
                        """)
                        .params(
                                UUID.randomUUID(),
                                r.clientSaleId(),
                                r.branchId(),
                                madeBy,
                                Timestamp.from(r.occurredAt()),
                                r.paymentMethod(),
                                r.totalMinor(),
                                mapper.writeValueAsString(r.request()),
                                r.code(),
                                r.message())
                        .update()
                == 1;
    }

    /** A retry that was refused again: open with the new refusal. */
    void reopen(UUID id, String code, String message) {
        jdbc.sql("""
                        UPDATE retail_offline_failures SET status = 'open', code = ?, message = ?, updated_at = now()
                         WHERE id = ?
                        """).params(code, message, id).update();
    }

    void decide(UUID id, String status, UUID by, String discardReason) {
        jdbc.sql("""
                        UPDATE retail_offline_failures SET status = ?, decided_by = ?, decided_at = now(), discard_reason = ?,
                               updated_at = now()
                         WHERE id = ?
                        """).params(status, by, discardReason, id).update();
    }

    /** Reported failures in these branches (null: every branch) with one of the statuses, newest first, at most 200. */
    List<Failure> failures(List<UUID> branchIds, List<String> statuses) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        var statement = jdbc.sql(SELECT_FAILURE + " WHERE f.status IN (:statuses)"
                        + (branchIds == null ? "" : " AND f.branch_id IN (:branches)")
                        + " ORDER BY f.created_at DESC, f.id LIMIT 200")
                .param("statuses", statuses);
        if (branchIds != null) {
            statement = statement.param("branches", branchIds);
        }
        return statement.query((rs, n) -> failure(rs)).list();
    }

    /** The caller's own reports: these client sale ids, or every one still open or waiting to be sent again. */
    List<Failure> mine(UUID madeBy, List<UUID> clientSaleIds) {
        if (clientSaleIds.isEmpty()) {
            return jdbc.sql(
                            SELECT_FAILURE
                                    + " WHERE f.made_by = ? AND f.status IN ('open', 'retry') ORDER BY f.created_at, f.id LIMIT 200")
                    .param(madeBy)
                    .query((rs, n) -> failure(rs))
                    .list();
        }
        return jdbc.sql(SELECT_FAILURE
                        + " WHERE f.made_by = :by AND f.client_sale_id IN (:ids) ORDER BY f.created_at, f.id")
                .param("by", madeBy)
                .param("ids", clientSaleIds)
                .query((rs, n) -> failure(rs))
                .list();
    }

    private Failure failure(ResultSet rs) throws SQLException {
        Map<String, Object> request = mapper.readValue(rs.getString("request"), new TypeReference<>() {});
        return new Failure(
                rs.getObject("id", UUID.class),
                rs.getObject("client_sale_id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("made_by", UUID.class),
                rs.getString("made_by_name"),
                instant(rs, "occurred_at"),
                rs.getString("payment_method"),
                rs.getLong("total_minor"),
                request,
                rs.getString("code"),
                rs.getString("message"),
                rs.getString("status"),
                rs.getObject("decided_by", UUID.class),
                instant(rs, "decided_at"),
                rs.getString("discard_reason"),
                rs.getObject("sale_id", UUID.class),
                instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
