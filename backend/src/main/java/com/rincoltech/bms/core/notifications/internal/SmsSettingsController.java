package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.internal.SmsSettingsService.Change;
import com.rincoltech.bms.core.notifications.internal.SmsSettingsService.View;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/settings/sms} (chapter 7 section 7.11.7, ADR-036, spec 4.8): the tenant's SMS switch with its
 * acknowledgement, sender id (chosen from the ids the platform approved), sending window, daily and monthly caps (which
 * can be lowered under the operator's ceiling, never raised) and balance switch. One version for the whole
 * tenant settings row, sent back as the {@code ETag} and required as {@code If-Match} on every change.
 */
@RestController
@RequestMapping(path = "/api/v1/settings/sms", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "settings")
class SmsSettingsController {

    private final SmsSettingsService service;

    SmsSettingsController(SmsSettingsService service) {
        this.service = service;
    }

    /** Omitted (or null) fields are unchanged. */
    @Schema(name = "UpdateSmsSettingsRequest")
    record UpdateRequest(
            @Schema(description = "Switch SMS on or off. Switching on needs acknowledged_text_version")
            Boolean enabled,

            @Size(max = 40)
            @Schema(
                    description =
                            "The version of the acknowledgement text that was shown (GET acknowledgement.text_version)")
            String acknowledgedTextVersion,

            @Pattern(regexp = "^([A-Za-z0-9]{3,11})?$")
            @Schema(
                    description =
                            "One of approved_sender_ids (422 not_approved otherwise), 3 to 11 letters and digits; an empty string clears it")
            String senderId,

            @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$")
            @Schema(description = "HH:mm on the Africa/Kampala clock, between window_earliest and window_latest")
            String windowStart,

            @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$")
            String windowEnd,

            @DecimalMin("0")
            @DecimalMax("1000000")
            @Schema(
                    type = "integer",
                    description = "Messages a day; 0 stops every message. At most daily_ceiling (422 above_ceiling)."
                            + " A fraction is refused, never rounded")
            BigDecimal dailyCap,

            @DecimalMin("0")
            @DecimalMax("1000000")
            @Schema(
                    type = "integer",
                    description =
                            "Messages a month; 0 stops every message. At most monthly_ceiling (422 above_ceiling)")
            BigDecimal monthlyCap,

            Boolean includeBalance) {}

    @GetMapping
    @RequiresPermission("core.settings.read")
    @Operation(summary = "The tenant's SMS settings, limits and use (ADR-036)", operationId = "getSmsSettings")
    ResponseEntity<View> get() {
        View view = service.view();
        return ResponseEntity.ok().eTag(String.valueOf(view.version())).body(view);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Change the tenant's SMS settings, audited with before and after (ADR-036)",
            operationId = "updateSmsSettings")
    ResponseEntity<View> update(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateRequest request) {
        View view = service.update(
                ifMatch,
                new Change(
                        request.enabled(),
                        request.acknowledgedTextVersion(),
                        request.senderId(),
                        request.windowStart(),
                        request.windowEnd(),
                        whole("daily_cap", request.dailyCap()),
                        whole("monthly_cap", request.monthlyCap()),
                        request.includeBalance()));
        return ResponseEntity.ok().eTag(String.valueOf(view.version())).body(view);
    }

    /** A cap is a whole number; 2.5 is refused, not rounded to 2 (Jackson would otherwise truncate it). */
    private static Integer whole(String field, BigDecimal value) {
        if (value == null) {
            return null;
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            throw ApiException.validation(
                    List.of(new FieldProblem(field, "invalid", "Use a whole number of 0 (stop) or more.")));
        }
    }
}
