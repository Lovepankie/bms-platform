package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.reports.internal.ControlsApi.Groups;
import com.rincoltech.bms.retail.reports.internal.ControlsApi.Patterns;
import com.rincoltech.bms.retail.reports.internal.ControlsApi.Records;
import com.rincoltech.bms.retail.reports.internal.ControlsApi.Summary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/retail/controls} (chapter 7 section 7.11.20; issue #357, ADR-047): read only, one permission. */
@RestController
@RequestMapping(path = "/api/v1/retail/controls", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-controls")
class ControlsController {

    private final ControlsService service;

    ControlsController(ControlsService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    @RequiresPermission("retail.controls.read")
    @Operation(
            summary = "Cards of the actions that deserve a second look, with the previous period (issue #357)",
            operationId = "getRetailControlsSummary")
    Summary summary(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "within_minutes", required = false) Integer withinMinutes) {
        return service.summary(branchIds, from, to, withinMinutes);
    }

    @GetMapping("/groups")
    @RequiresPermission("retail.controls.read")
    @Operation(
            summary = "One metric grouped by shop, person, day or reason, with a rate per 100 sales (issue #357)",
            operationId = "listRetailControlsGroups")
    Groups groups(
            @RequestParam(name = "metric") String metric,
            @RequestParam(name = "group_by") String groupBy,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "user_id", required = false) UUID userId,
            @RequestParam(name = "approver_id", required = false) UUID approverId,
            @RequestParam(name = "reason", required = false) String reason,
            @RequestParam(name = "within_minutes", required = false) Integer withinMinutes,
            @RequestParam(name = "offset", required = false) Integer offset) {
        return service.groups(
                metric, groupBy, level, branchIds, from, to, userId, approverId, reason, withinMinutes, offset);
    }

    @GetMapping("/records")
    @RequiresPermission("retail.controls.read")
    @Operation(
            summary = "The individual records behind a metric and a group, newest first (issue #357)",
            operationId = "listRetailControlsRecords")
    Records records(
            @RequestParam(name = "metric") String metric,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "user_id", required = false) UUID userId,
            @RequestParam(name = "approver_id", required = false) UUID approverId,
            @RequestParam(name = "reason", required = false) String reason,
            @RequestParam(name = "within_minutes", required = false) Integer withinMinutes,
            @RequestParam(name = "offset", required = false) Integer offset) {
        return service.records(metric, branchIds, from, to, userId, approverId, reason, withinMinutes, offset);
    }

    @GetMapping("/patterns")
    @RequiresPermission("retail.controls.read")
    @Operation(
            summary = "People and items whose rate is more than twice their shop's own (issue #357)",
            operationId = "getRetailControlsPatterns")
    Patterns patterns(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to) {
        return service.patterns(branchIds, from, to);
    }
}
