package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.PushPreferences;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.kernel.Versions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/settings/notifications} (chapter 7 section 7.11.7, issue #412, ADR-057): which everyday push notices the
 * business sends (all on until switched off) and when the daily closing summary goes. One version for the whole tenant
 * settings row, sent back as the {@code ETag} and required as {@code If-Match} on a change, as for the other settings groups.
 * Each person still turns notifications on for their own device; this only says what the business sends.
 */
@RestController
@RequestMapping(path = "/api/v1/settings/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "settings")
class NotificationSettingsController {

    private final ModuleSettings settings;
    private final CurrentTenant tenant;
    private final VapidKeys keys;

    NotificationSettingsController(ModuleSettings settings, CurrentTenant tenant, VapidKeys keys) {
        this.settings = settings;
        this.tenant = tenant;
        this.keys = keys;
    }

    @Schema(name = "NotificationSettings")
    record View(
            @Schema(
                    description =
                            "New orders from a phone call, WhatsApp, Instagram, Facebook or elsewhere, to the order's shop")
            boolean newOrders,

            @Schema(description = "An order out for delivery and delivered, to the person who took it")
            boolean orderUpdates,

            @Schema(description = "An item below its low-stock level at a shop or store, once a day per item")
            boolean lowStock,

            @Schema(description = "Credit payments due today or overdue, each morning")
            boolean creditDue,

            @Schema(description = "The daily closing summary to the owner and the administrators")
            boolean dailySummary,

            @Schema(
                    description = "HH:mm on the business's clock (timezone) when the daily summary goes",
                    example = "20:00")
            String dailySummaryTime,

            @Schema(description = "The business's time zone, for the summary time", example = "Africa/Kampala")
            String timezone,

            @Schema(description = "False when this server has no push keys: nothing is sent whatever is chosen here")
            boolean pushAvailable,

            int version) {}

    /** Omitted (or null) fields are unchanged. */
    @Schema(name = "UpdateNotificationSettingsRequest")
    record UpdateRequest(
            Boolean newOrders,
            Boolean orderUpdates,
            Boolean lowStock,
            Boolean creditDue,
            Boolean dailySummary,

            @Pattern(regexp = "^(([01][0-9]|2[0-2]):(00|15|30|45)|23:(00|15|30))$")
            @Schema(description = "HH:mm on the business's clock, a quarter hour up to 23:30", example = "20:00")
            String dailySummaryTime) {}

    @GetMapping
    @RequiresPermission("core.settings.read")
    @Operation(
            summary = "Which everyday push notices the business sends, and when the daily summary goes (issue #412)",
            operationId = "getNotificationSettings")
    ResponseEntity<View> get() {
        View view = view();
        return ResponseEntity.ok().eTag(String.valueOf(view.version())).body(view);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary =
                    "Change which everyday push notices the business sends, audited with before and after (issue #412)",
            operationId = "updateNotificationSettings")
    ResponseEntity<View> update(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateRequest request) {
        int expected = Versions.fromIfMatch(ifMatch);
        Map<String, Object> proposed = new LinkedHashMap<>();
        put(proposed, PushPreferences.NEW_ORDERS, request.newOrders());
        put(proposed, PushPreferences.ORDER_UPDATES, request.orderUpdates());
        put(proposed, PushPreferences.LOW_STOCK, request.lowStock());
        put(proposed, PushPreferences.CREDIT_DUE, request.creditDue());
        put(proposed, PushPreferences.DAILY_SUMMARY, request.dailySummary());
        put(proposed, NotificationSettingsGroup.SUMMARY_TIME, request.dailySummaryTime());
        settings.change(NotificationSettingsGroup.KEY, proposed, expected);
        View view = view();
        return ResponseEntity.ok().eTag(String.valueOf(view.version())).body(view);
    }

    private static void put(Map<String, Object> proposed, String key, Object value) {
        if (value != null) {
            proposed.put(key, value);
        }
    }

    private View view() {
        Map<String, Object> g = settings.group(NotificationSettingsGroup.KEY);
        return new View(
                Boolean.TRUE.equals(g.get(PushPreferences.NEW_ORDERS)),
                Boolean.TRUE.equals(g.get(PushPreferences.ORDER_UPDATES)),
                Boolean.TRUE.equals(g.get(PushPreferences.LOW_STOCK)),
                Boolean.TRUE.equals(g.get(PushPreferences.CREDIT_DUE)),
                Boolean.TRUE.equals(g.get(PushPreferences.DAILY_SUMMARY)),
                (String) g.get(NotificationSettingsGroup.SUMMARY_TIME),
                tenant.profile().timezone().getId(),
                keys.available(),
                settings.version());
    }
}
