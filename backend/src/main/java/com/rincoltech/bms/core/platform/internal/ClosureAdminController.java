package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.identity.OperatorSecurity;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/platform/closures} (issue #571, ADR-090): the requests to close a business across businesses, and the
 * operator's removal once a request's cooling-off period is over, with the operator's own authenticator code and a reason.
 * Platform operators only.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/closures", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class ClosureAdminController {

    private final ClosureService closures;
    private final OperatorSecurity security;

    ClosureAdminController(ClosureService closures, OperatorSecurity security) {
        this.closures = closures;
        this.security = security;
    }

    @Schema(name = "PlatformClosure")
    record QueuedClosure(
            UUID id,
            UUID tenantId,
            String tenantSlug,
            String tenantName,

            @Schema(description = "requested, cancelled, removing or removed")
            String status,

            Instant requestedAt,
            String requestedByName,

            @Schema(description = "The first day the business may be removed")
            LocalDate removeAfter,

            @Schema(description = "Whether the cooling-off period is over in the business's own time zone")
            boolean removable,

            Instant cancelledAt,
            Instant removedAt,

            @Schema(description = "When removed: the day after which the kept money records may be deleted")
            LocalDate keepUntil,

            String removedByName) {}

    @Schema(name = "PlatformClosureRemoved")
    record Removed(
            UUID closureId,
            UUID tenantId,

            @Schema(description = "The day after which the kept money records may be deleted")
            LocalDate keepUntil,

            @Schema(description = "Rows deleted or anonymised, by area")
            Map<String, Integer> erased) {}

    @GetMapping
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary =
                    "The open requests to close a business, soonest removable first, and those of the last 180 days (ADR-090)",
            operationId = "platformClosures")
    List<QueuedClosure> list() {
        return closures.list();
    }

    @PostMapping(path = "/{closure_id}/remove", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Remove a business whose Owner asked to close it, once the cooling-off is over, with a reason; deletes or anonymises its personal data and keeps its money records anonymised until a date; needs a current code from the operator's own authenticator (ADR-090)",
            operationId = "platformRemoveClosedBusiness")
    Removed remove(
            @PathVariable("closure_id") UUID closureId, @Valid @RequestBody PlatformController.StepUpRequest request) {
        closures.requireRemovable(closureId);
        security.requireTotp(request.totpCode());
        return closures.remove(closureId, request.reason());
    }
}
