package com.rincoltech.bms.lending.advisor.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.advisor.internal.AdvisorApi.BriefView;
import com.rincoltech.bms.lending.advisor.internal.AdvisorApi.SettingsView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/lending/advisor} (chapter 7 section 7.11.24, ADR-037): today's brief and the assistant's settings.
 * Responses are never cached. The question box (#230) and the call list are later slices.
 */
@RestController
@RequestMapping(path = "/api/v1/lending/advisor", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-advisor")
class AdvisorController {

    private final BriefReadService briefs;
    private final AdvisorSettingsService settings;

    AdvisorController(BriefReadService briefs, AdvisorSettingsService settings) {
        this.briefs = briefs;
        this.settings = settings;
    }

    @Schema(name = "UpdateAdvisorSettingsRequest", description = "Omitted fields are unchanged")
    record UpdateRequest(
            @Schema(description = "Switch the assistant on or off; needs core.settings.manage as well")
            Boolean enabled,

            @Size(max = 40)
            @Schema(description = "The version of the acknowledgement text that was shown; required when switching on")
            String acknowledgedTextVersion,

            @DecimalMin("0")
            @Schema(
                    type = "integer",
                    description = "Tokens a day for this business, at most the platform ceiling; 0 stops every call")
            BigDecimal dailyTokenCap,

            @DecimalMin("0")
            @Schema(
                    type = "integer",
                    description =
                            "Estimated cost a day in millionths of the cost currency, at most the platform ceiling")
            BigDecimal dailyCostCapMicro) {}

    @GetMapping("/brief")
    @RequiresPermission("lending.advisor.use")
    @Operation(
            summary =
                    "Today's brief: the stored AI brief for a caller who may see every branch, else the automatic summary",
            operationId = "getAdvisorBrief")
    ResponseEntity<BriefView> brief() {
        // 204 when the assistant is not active for the tenant: nothing is read and the card is not drawn.
        return briefs.today()
                .map(b ->
                        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(b))
                .orElseGet(() -> ResponseEntity.noContent()
                        .cacheControl(CacheControl.noStore())
                        .build());
    }

    @GetMapping("/settings")
    @RequiresPermission("lending.advisor.configure")
    @Operation(summary = "The AI assistant's settings, limits and use today", operationId = "getAdvisorSettings")
    ResponseEntity<SettingsView> settings() {
        SettingsView view = settings.view();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(String.valueOf(view.version()))
                .body(view);
    }

    @PutMapping(path = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("lending.advisor.configure")
    @Operation(
            summary = "Switch the assistant on (with the acknowledgement) or off, or set its daily caps; audited",
            operationId = "updateAdvisorSettings")
    ResponseEntity<SettingsView> update(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateRequest request) {
        SettingsView view = settings.update(
                ifMatch,
                new AdvisorSettingsService.Change(
                        request.enabled(),
                        request.acknowledgedTextVersion(),
                        whole(request.dailyTokenCap()),
                        whole(request.dailyCostCapMicro())));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(String.valueOf(view.version()))
                .body(view);
    }

    /** A cap is a whole number; 2.5 is refused, not rounded. */
    private static Long whole(BigDecimal value) {
        if (value == null) {
            return null;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw ApiException.validation(List.of(new FieldProblem("daily_cap", "invalid", "Use a whole number.")));
        }
    }
}
