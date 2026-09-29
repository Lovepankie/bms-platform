package com.rincoltech.bms.core.audit.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/audit-events} (chapter 7 section 7.11.4, FR-AUD-04). */
@RestController
@RequestMapping(path = "/api/v1/audit-events")
@Tag(name = "audit")
class AuditController {

    private final AuditSearch search;

    AuditController(AuditSearch search) {
        this.search = search;
    }

    @Schema(name = "AuditEvent", description = "Identifiers in data are masked (FR-AUD-05)")
    record AuditEventResponse(
            UUID id,
            Instant createdAt,
            UUID actorUserId,
            String actorKind,
            UUID branchId,
            String action,
            String entityType,
            UUID entityId,
            String requestId,
            Map<String, Object> data) {}

    @Schema(name = "AuditEventPage")
    record AuditEventPage(List<AuditEventResponse> items, String nextCursor) {}

    @Schema(name = "AuditExportRequest", description = "The same filters as the search")
    record ExportRequest(
            Instant from,
            Instant to,
            UUID actorUserId,
            @Size(max = 100) String entityType,
            UUID entityId,
            @Size(max = 100) String action) {}

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.audit.read")
    @Operation(
            summary = "Search the audit log in the caller's branch scope (FR-AUD-04)",
            operationId = "searchAuditEvents")
    AuditEventPage list(
            @RequestParam(name = "from", required = false) Instant from,
            @RequestParam(name = "to", required = false) Instant to,
            @RequestParam(name = "actor_user_id", required = false) UUID actorUserId,
            @RequestParam(name = "entity_type", required = false) String entityType,
            @RequestParam(name = "entity_id", required = false) UUID entityId,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return search.page(new AuditSearch.Filter(from, to, actorUserId, entityType, entityId, action), limit, cursor);
    }

    @PostMapping(path = "/export", produces = "text/csv")
    @RequiresPermission("core.audit.export")
    @Operation(
            summary = "Export matching events as CSV, at most 10,000 rows; the export is audited (FR-AUD-04)",
            operationId = "exportAuditEvents")
    ResponseEntity<String> export(@Valid @RequestBody ExportRequest request) {
        String csv = search.export(new AuditSearch.Filter(
                request.from(),
                request.to(),
                request.actorUserId(),
                request.entityType(),
                request.entityId(),
                request.action()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-events.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(csv);
    }
}
