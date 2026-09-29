package com.rincoltech.bms.core.audit.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Masking;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.RequestContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class JdbcAuditLog implements AuditLog {

    /** Keys whose values are personal identifiers (FR-AUD-05). */
    static final Set<String> MASKED_KEYS =
            Set.of("national_id", "phone_e164", "alt_phone_e164", "phone", "other_id_number", "payer_phone_e164");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    JdbcAuditLog(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Entry entry) {
        Optional<Principal> principal = CurrentPrincipal.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", mask(entry.before()));
        data.put("after", mask(entry.after()));
        jdbc.sql("""
                        INSERT INTO audit_log (id, tenant_id, actor_user_id, actor_kind, branch_id, action,
                                               entity_type, entity_id, request_id, ip, data)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, CAST(? AS inet), CAST(? AS jsonb))
                        """)
                .param(UUID.randomUUID())
                .param(principal.map(Principal::userId).orElse(null))
                .param(principal.map(Principal::kind).orElse("system"))
                .param(entry.branchId())
                .param(entry.action())
                .param(entry.entityType())
                .param(entry.entityId())
                .param(RequestContext.requestId())
                .param(RequestContext.clientIp())
                .param(toJson(data))
                .update();
    }

    static Map<String, Object> mask(Map<String, Object> values) {
        Map<String, Object> masked = new LinkedHashMap<>();
        values.forEach((key, value) ->
                masked.put(key, MASKED_KEYS.contains(key) && value instanceof String s ? Masking.lastFour(s) : value));
        return masked;
    }

    private String toJson(Map<String, Object> data) {
        return mapper.writeValueAsString(data);
    }
}
