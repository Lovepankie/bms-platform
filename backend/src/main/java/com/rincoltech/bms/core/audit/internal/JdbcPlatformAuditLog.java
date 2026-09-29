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
}
