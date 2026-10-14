package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/platform/tenants/{tenant_id}/sms} (chapter 7 section 7.11.3, ADR-036 decision 13):
 * the operator switches SMS on for a tenant and sets its daily cap. These are the only way to change
 * either: the tenant admin has no endpoint, the table has no grant to the application role, and the
 * database function refuses a transaction bound to a tenant. SMS is off for every tenant until an
 * operator turns it on.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/tenants/{tenant_id}/sms", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class SmsControlsController {

    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final PlatformAuditLog audit;
    private final SmsSettings settings;

    SmsControlsController(JdbcClient jdbc, BusinessClock clock, PlatformAuditLog audit, SmsSettings settings) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.settings = settings;
    }

    @Schema(name = "TenantSmsControls")
    record Controls(
            @Schema(description = "Off by default; only the platform operator switches it on")
            boolean enabled,

            @Schema(description = "Messages a day; receipts may go up to twice this, reminders stop at it")
            int dailyCap,

            @Schema(description = "Messages counted today (Africa/Kampala), sent, in flight or sunk")
            long usedToday,

            @Schema(description = "The platform-wide ceiling of BMS_SMS_PLATFORM_DAILY_CAP, read only")
            int platformDailyCap,

            @Schema(description = "BMS_SMS_MODE of this environment: off, sink, allowlist or live")
            String mode) {}

    @Schema(name = "UpdateTenantSmsControlsRequest")
    record UpdateRequest(
            @NotNull Boolean enabled,
            @NotNull @Min(0) @Max(100000) Integer dailyCap) {}

    @GetMapping
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "A tenant's SMS switch, daily cap and use today (ADR-036)",
            operationId = "platformGetSmsControls")
    @Transactional(readOnly = true)
    public Controls get(@PathVariable("tenant_id") UUID tenantId) {
        return read(tenantId);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Switch SMS on or off for a tenant and set its daily cap (ADR-036)",
            operationId = "platformSetSmsControls")
    @Transactional
    public Controls set(@PathVariable("tenant_id") UUID tenantId, @Valid @RequestBody UpdateRequest request) {
        Controls before = read(tenantId);
        jdbc.sql("SELECT sms_tenant_control_set(?, ?, ?, ?, ?)")
                .params(
                        tenantId,
                        request.enabled(),
                        request.dailyCap(),
                        CurrentPrincipal.require().userId(),
                        Timestamp.from(clock.now()))
                .query((rs, n) -> 1)
                .single();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled_before", before.enabled());
        data.put("enabled", request.enabled());
        data.put("daily_cap_before", before.dailyCap());
        data.put("daily_cap", request.dailyCap());
        audit.record("platform.sms.controls_changed", CurrentPrincipal.require().userId(), tenantId, data);
        return read(tenantId);
    }

    private Controls read(UUID tenantId) {
        var row = jdbc.sql("SELECT enabled, daily_cap FROM sms_tenant_control_get(?)")
                .param(tenantId)
                .query((rs, n) -> Map.entry(rs.getBoolean("enabled"), rs.getInt("daily_cap")))
                .optional()
                .orElseThrow(ApiException::notFound);
        Instant now = clock.now();
        LocalDate today = now.atZone(BusinessClock.DEFAULT_ZONE).toLocalDate();
        long used = jdbc.sql("SELECT sms_tenant_count_since(?, ?, ?)")
                .params(
                        tenantId,
                        Timestamp.from(
                                today.atStartOfDay(BusinessClock.DEFAULT_ZONE).toInstant()),
                        Timestamp.from(today.plusDays(1)
                                .atStartOfDay(BusinessClock.DEFAULT_ZONE)
                                .toInstant()))
                .query(Long.class)
                .single();
        return new Controls(
                row.getKey(),
                row.getValue(),
                used,
                settings.platformDailyCap(),
                settings.mode().key());
    }
}
