package com.rincoltech.bms.core.audit.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.kernel.RequestContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class JdbcPlatformAuditLog implements PlatformAuditLog {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    JdbcPlatformAuditLog(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String action, UUID platformUserId, UUID tenantId, Map<String, Object> data) {
        jdbc.sql("""
                        INSERT INTO platform_audit_log (id, platform_user_id, action, tenant_id, data, request_id, ip)
                        VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS inet))
                        """)
                .param(UUID.randomUUID())
                .param(platformUserId)
                .param(action)
                .param(tenantId)
                .param(mapper.writeValueAsString(JdbcAuditLog.mask(data)))
                .param(RequestContext.requestId())
                .param(RequestContext.clientIp())
                .update();
    }

    // JUSTIFICATION-A3: the two reads of this port the #333 bounce log needs (the operator's view, repeats).
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public java.util.List<Entry> recent(java.util.Set<String> actions, int limit) {
        return jdbc.sql("""
                        SELECT occurred_at, action, tenant_id, data::text AS data FROM platform_audit_log
                         WHERE action = ANY (?) ORDER BY occurred_at DESC LIMIT ?
                        """)
                .params(actions.toArray(String[]::new), limit)
                .query((rs, n) -> {
                    Map<String, String> data = new java.util.LinkedHashMap<>();
                    mapper.readTree(rs.getString("data")).properties().forEach(e -> {
                        if (e.getValue().isValueNode()) {
                            data.put(e.getKey(), e.getValue().asString());
                        }
                    });
                    return new Entry(
                            rs.getTimestamp("occurred_at").toInstant(),
                            rs.getString("action"),
                            rs.getObject("tenant_id", UUID.class),
                            data);
                })
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean recorded(java.util.Set<String> actions, String key, String value, java.time.Instant since) {
        // occurred_at first, so the time index (platform_audit_log_by_time) bounds the rows read.
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM platform_audit_log
                                        WHERE occurred_at >= ? AND action = ANY (?) AND data ->> ? = ?)
                        """)
                .params(java.sql.Timestamp.from(since), actions.toArray(String[]::new), key, value)
                .query(Boolean.class)
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public java.util.Optional<String> latestAction(java.util.Set<String> actions, String key, String value) {
        return jdbc.sql("""
                        SELECT action FROM platform_audit_log
                         WHERE action = ANY (?) AND data ->> ? = ?
                         ORDER BY occurred_at DESC, id DESC LIMIT 1
                        """)
                .params(actions.toArray(String[]::new), key, value)
                .query(String.class)
                .optional();
    }
}
