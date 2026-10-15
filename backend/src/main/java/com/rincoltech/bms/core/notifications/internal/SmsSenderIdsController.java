package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/platform/tenants/{tenant_id}/sms/sender-ids} (chapter 7 section 7.11.3, ADR-036 decision 10, spec 4.8):
 * the operator's allow-list of sender ids a tenant may choose from. A tenant admin cannot add to it: there is no
 * tenant route, the table has no grant to the application role, and the database functions refuse a transaction bound
 * to a tenant. An id belongs to one tenant at a time. Registration with the provider and the networks happens outside
 * the platform and is done before an id is approved here (runbook {@code sms-setup}).
 */
@RestController
@RequestMapping(
        path = "/api/v1/platform/tenants/{tenant_id}/sms/sender-ids",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class SmsSenderIdsController {

    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final PlatformAuditLog audit;

    SmsSenderIdsController(JdbcClient jdbc, BusinessClock clock, PlatformAuditLog audit) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
    }

    @Schema(name = "TenantSmsSenderId")
    record SenderId(String senderId, Instant approvedAt) {}

    @Schema(name = "TenantSmsSenderIds")
    record SenderIds(List<SenderId> items) {}

    @GetMapping
    @RequiresPermission("platform.tenants.read")
    @Operation(summary = "The sender ids approved for a tenant (ADR-036)", operationId = "platformListSmsSenderIds")
    @Transactional(readOnly = true)
    public SenderIds list(@PathVariable("tenant_id") UUID tenantId) {
        requireTenant(tenantId);
        return read(tenantId);
    }

    @PutMapping("/{sender_id}")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Approve a sender id for a tenant; 409 sender_id_taken when another tenant holds it (ADR-036)",
            operationId = "platformApproveSmsSenderId")
    @Transactional
    public SenderIds approve(@PathVariable("tenant_id") UUID tenantId, @PathVariable("sender_id") String senderId) {
        check(senderId);
        requireTenant(tenantId);
        UUID operator = CurrentPrincipal.require().userId();
        String result = jdbc.sql("SELECT sms_sender_id_approve(?, ?, ?, ?)")
                .params(tenantId, senderId, operator, Timestamp.from(clock.now()))
                .query(String.class)
                .single();
        if ("taken".equals(result)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "sender_id_taken",
                    "Sender id taken",
                    "Another business already holds this sender id.");
        }
        if ("approved".equals(result)) {
            audit.record("platform.sms.sender_id_approved", operator, tenantId, data(senderId));
        }
        return read(tenantId);
    }

    @DeleteMapping("/{sender_id}")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Withdraw a sender id from a tenant; its messages go from the shared sender at once (ADR-036)",
            operationId = "platformWithdrawSmsSenderId")
    @Transactional
    public SenderIds withdraw(@PathVariable("tenant_id") UUID tenantId, @PathVariable("sender_id") String senderId) {
        check(senderId);
        requireTenant(tenantId);
        UUID operator = CurrentPrincipal.require().userId();
        boolean withdrawn = Boolean.TRUE.equals(jdbc.sql("SELECT sms_sender_id_withdraw(?, ?, ?, ?)")
                .params(tenantId, senderId, operator, Timestamp.from(clock.now()))
                .query(Boolean.class)
                .single());
        if (!withdrawn) {
            throw ApiException.notFound();
        }
        audit.record("platform.sms.sender_id_withdrawn", operator, tenantId, data(senderId));
        return read(tenantId);
    }

    private SenderIds read(UUID tenantId) {
        return new SenderIds(jdbc.sql("SELECT sender_id, approved_at FROM sms_sender_ids_for_tenant(?)")
                .param(tenantId)
                .query((rs, n) -> new SenderId(
                        rs.getString("sender_id"),
                        rs.getTimestamp("approved_at").toInstant()))
                .list());
    }

    private void requireTenant(UUID tenantId) {
        // sms_tenant_control_get answers no row for an unknown tenant (V50), whatever the tenant's controls.
        jdbc.sql("SELECT 1 FROM sms_tenant_control_get(?)")
                .param(tenantId)
                .query((rs, n) -> 1)
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    private static void check(String senderId) {
        if (!SmsSettingsGroup.SENDER.matcher(senderId).matches()) {
            throw ApiException.validation(List.of(new ApiException.FieldProblem(
                    "sender_id",
                    "invalid",
                    "Use 3 to 11 letters and digits, with at least one letter, and no spaces.")));
        }
    }

    private static Map<String, Object> data(String senderId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sender_id", senderId);
        return data;
    }
}
