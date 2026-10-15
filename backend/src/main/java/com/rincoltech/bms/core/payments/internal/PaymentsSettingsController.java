package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.payments.internal.PaymentsSettingsService.Change;
import com.rincoltech.bms.core.payments.internal.PaymentsSettingsService.View;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
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
 * {@code /api/v1/payments/settings} (chapter 7 section 7.11.10, ADR-035 decision 11): the tenant's switch for collecting
 * by mobile money or card, and a daily ceiling. One version for the whole tenant settings row, sent back as the
 * {@code ETag} and required as {@code If-Match} on every change. Switching on is refused with 409
 * {@code merchant_not_bound_to_tenant}, {@code gateway_method_unmapped} or {@code gateway_not_configured}.
 */
@RestController
@RequestMapping(path = "/api/v1/payments/settings", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "payments")
class PaymentsSettingsController {

    private final PaymentsSettingsService service;

    PaymentsSettingsController(PaymentsSettingsService service) {
        this.service = service;
    }

    /** Omitted fields are unchanged. */
    @Schema(name = "UpdatePaymentsSettingsRequest")
    record UpdateRequest(
            @Schema(description = "Switch collections by mobile money and card on or off (off by default)")
            Boolean collectionsEnabled,

            @Schema(
                    type = "integer",
                    description = "The most the business may ask to collect in a day, in minor units; 1 or more."
                            + " A fraction is refused, never rounded")
            BigDecimal dailyCeilingMinor,

            @Schema(description = "Remove the daily ceiling")
            Boolean clearDailyCeiling) {}

    @GetMapping
    @RequiresPermission("core.payments.read")
    @Operation(
            summary = "The tenant's payments settings and what the host offers (ADR-035)",
            operationId = "getPaymentsSettings")
    ResponseEntity<View> get(HttpServletResponse response) {
        NoStore.apply(response);
        View view = service.view();
        return ResponseEntity.ok().eTag(String.valueOf(view.version())).body(view);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Change the tenant's payments settings, audited with before and after (ADR-035)",
            operationId = "updatePaymentsSettings")
    ResponseEntity<View> update(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        View view = service.update(
                ifMatch,
                new Change(
                        request.collectionsEnabled(),
                        whole("daily_ceiling_minor", request.dailyCeilingMinor()),
                        Boolean.TRUE.equals(request.clearDailyCeiling())));
        return ResponseEntity.ok().eTag(String.valueOf(view.version())).body(view);
    }

    /** A ceiling is a whole number; 2.5 is refused, not truncated to 2 (Jackson would otherwise truncate it). */
    private static Long whole(String field, BigDecimal value) {
        if (value == null) {
            return null;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw ApiException.validation(
                    List.of(new FieldProblem(field, "invalid", "Use a whole number of 1 or more.")));
        }
    }
}
