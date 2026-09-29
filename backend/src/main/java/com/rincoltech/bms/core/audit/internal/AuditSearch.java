package com.rincoltech.bms.core.audit.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.audit.internal.AuditController.AuditEventPage;
import com.rincoltech.bms.core.audit.internal.AuditController.AuditEventResponse;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Audit search and export (FR-AUD-04). Branch scope applies: a user scoped to some branches sees
 * the events of those branches only; events that belong to no branch (sign-ins, user and settings
 * changes) are shown to users whose scope covers every branch.
 */
@Service
class AuditSearch {

    static final int EXPORT_LIMIT = 10_000;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final AuditLog audit;

    AuditSearch(JdbcClient jdbc, ObjectMapper mapper, AuditLog audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.audit = audit;
    }

    record Filter(Instant from, Instant to, UUID actorUserId, String entityType, UUID entityId, String action) {}

    @Transactional(readOnly = true)
    AuditEventPage page(Filter filter, Integer limit, String cursor) {
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        List<AuditEventResponse> rows = query(filter, "core.audit.read", Cursor.decode(cursor), size + 1)
                .orElse(List.of());
        boolean more = rows.size() > size;
        List<AuditEventResponse> items = more ? rows.subList(0, size) : rows;
        String next = more
                ? Cursor.encode(
                        items.getLast().createdAt() + "|" + items.getLast().id())
                : null;
        return new AuditEventPage(List.copyOf(items), next);
    }

    @Transactional
    String export(Filter filter) {
        List<AuditEventResponse> rows = query(filter, "core.audit.export", Optional.empty(), EXPORT_LIMIT)
                .orElse(List.of());
        StringBuilder csv = new StringBuilder(
                "id,created_at,actor_user_id,actor_kind,branch_id,action,entity_type,entity_id,request_id,data\n");
        for (AuditEventResponse r : rows) {
            csv.append(String.join(
                            ",",
                            cell(r.id()),
                            cell(r.createdAt()),
                            cell(r.actorUserId()),
                            cell(r.actorKind()),
                            cell(r.branchId()),
                            cell(r.action()),
                            cell(r.entityType()),
                            cell(r.entityId()),
                            cell(r.requestId()),
                            cell(mapper.writeValueAsString(r.data()))))
                    .append('\n');
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("rows", rows.size());
        after.put("from", filter.from() == null ? null : filter.from().toString());
        after.put("to", filter.to() == null ? null : filter.to().toString());
        after.put("entity_type", filter.entityType());
        after.put("action", filter.action());
        audit.record(new AuditLog.Entry("core.audit.exported", "core.audit_log", null, null, Map.of(), after));
        return csv.toString();
    }

    private Optional<List<AuditEventResponse>> query(Filter f, String permission, Optional<String> cursor, int limit) {
        Principal principal = CurrentPrincipal.require();
        StringBuilder sql = new StringBuilder("""
                SELECT id, created_at, actor_user_id, actor_kind, branch_id, action, entity_type, entity_id, request_id,
                       data::text AS data
                  FROM audit_log WHERE true""");
        Map<String, Object> params = new LinkedHashMap<>();
        List<UUID> branches = principal.branchFilter(permission, null);
        if (branches != null) {
            if (branches.isEmpty()) {
                return Optional.empty();
            }
            sql.append(" AND branch_id IN (:branches)");
            params.put("branches", branches);
        }
        if (f.from() != null) {
            sql.append(" AND created_at >= :from");
            params.put("from", Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            sql.append(" AND created_at <= :to");
            params.put("to", Timestamp.from(f.to()));
        }
        if (f.actorUserId() != null) {
            sql.append(" AND actor_user_id = :actor");
            params.put("actor", f.actorUserId());
        }
        if (f.entityType() != null && !f.entityType().isBlank()) {
            sql.append(" AND entity_type = :entityType");
            params.put("entityType", f.entityType());
        }
        if (f.entityId() != null) {
            sql.append(" AND entity_id = :entityId");
            params.put("entityId", f.entityId());
        }
        if (f.action() != null && !f.action().isBlank()) {
            sql.append(" AND action = :action");
            params.put("action", f.action());
        }
        if (cursor.isPresent()) {
            String[] parts = cursor.get().split("\\|");
            sql.append(" AND (created_at, id) < (:afterAt, :afterId)");
            params.put("afterAt", Timestamp.from(Instant.parse(parts[0])));
            params.put("afterId", UUID.fromString(parts[1]));
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        params.put("limit", limit);
        return Optional.of(
                jdbc.sql(sql.toString()).params(params).query(this::map).list());
    }

    private AuditEventResponse map(ResultSet rs, int n) throws SQLException {
        return new AuditEventResponse(
                rs.getObject("id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("actor_kind"),
                rs.getObject("branch_id", UUID.class),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class),
                rs.getString("request_id"),
                mapper.readValue(rs.getString("data"), MAP));
    }

    /** RFC 4180 quoting; a leading formula character is neutralised for spreadsheet safety. */
    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("'")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
